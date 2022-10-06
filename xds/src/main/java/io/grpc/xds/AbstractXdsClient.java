/*
 * Copyright 2020 The gRPC Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.grpc.xds;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static io.grpc.xds.XdsClusterResource.ADS_TYPE_URL_CDS;
import static io.grpc.xds.XdsClusterResource.ADS_TYPE_URL_CDS_V2;
import static io.grpc.xds.XdsEndpointResource.ADS_TYPE_URL_EDS;
import static io.grpc.xds.XdsEndpointResource.ADS_TYPE_URL_EDS_V2;
import static io.grpc.xds.XdsListenerResource.ADS_TYPE_URL_LDS;
import static io.grpc.xds.XdsListenerResource.ADS_TYPE_URL_LDS_V2;
import static io.grpc.xds.XdsRouteConfigureResource.ADS_TYPE_URL_RDS;
import static io.grpc.xds.XdsRouteConfigureResource.ADS_TYPE_URL_RDS_V2;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Stopwatch;
import com.google.common.base.Supplier;
import com.google.protobuf.Any;
import com.google.rpc.Code;
import io.envoyproxy.envoy.service.discovery.v3.AggregatedDiscoveryServiceGrpc;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryRequest;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryResponse;
import io.grpc.Channel;
import io.grpc.Context;
import io.grpc.InternalLogId;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.SynchronizationContext;
import io.grpc.SynchronizationContext.ScheduledHandle;
import io.grpc.internal.BackoffPolicy;
import io.grpc.stub.StreamObserver;
import io.grpc.xds.Bootstrapper.ServerInfo;
import io.grpc.xds.EnvoyProtoData.Node;
import io.grpc.xds.XdsClient.ResourceStore;
import io.grpc.xds.XdsClient.XdsResponseHandler;
import io.grpc.xds.XdsClientImpl.XdsChannelFactory;
import io.grpc.xds.XdsLogger.XdsLogLevel;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

/**
 * Common base type for XdsClient implementations, which encapsulates the layer abstraction of
 * the xDS RPC stream.
 */
final class AbstractXdsClient {
  private final SynchronizationContext syncContext;
  private final InternalLogId logId;
  private final XdsLogger logger;
  private final ServerInfo serverInfo;
  private final ManagedChannel channel;
  private final XdsResponseHandler xdsResponseHandler;
  private final ResourceStore resourceStore;
  private final Context context;
  private final ScheduledExecutorService timeService;
  private final BackoffPolicy.Provider backoffPolicyProvider;
  private final Stopwatch stopwatch;
  private final Node bootstrapNode;

  // Last successfully applied version_info for each resource type. Starts with empty string.
  // A version_info is used to update management server with client's most recent knowledge of
  // resources.
  private final Map<XdsResourceType<?>, String> versions = new HashMap<>();

  private boolean shutdown;
  @Nullable
  private AbstractAdsStream adsStream;
  @Nullable
  private BackoffPolicy retryBackoffPolicy;
  @Nullable
  private ScheduledHandle rpcRetryTimer;

  /** An entity that manages ADS RPCs over a single channel. */
  // TODO: rename to XdsChannel
  AbstractXdsClient(
      XdsChannelFactory xdsChannelFactory,
      ServerInfo serverInfo,
      Node bootstrapNode,
      XdsResponseHandler xdsResponseHandler,
      ResourceStore resourceStore,
      Context context,
      ScheduledExecutorService
      timeService,
      SynchronizationContext syncContext,
      BackoffPolicy.Provider backoffPolicyProvider,
      Supplier<Stopwatch> stopwatchSupplier) {
    this.serverInfo = checkNotNull(serverInfo, "serverInfo");
    this.channel = checkNotNull(xdsChannelFactory, "xdsChannelFactory").create(serverInfo);
    this.xdsResponseHandler = checkNotNull(xdsResponseHandler, "xdsResponseHandler");
    this.resourceStore = checkNotNull(resourceStore, "resourcesSubscriber");
    this.bootstrapNode = checkNotNull(bootstrapNode, "bootstrapNode");
    this.context = checkNotNull(context, "context");
    this.timeService = checkNotNull(timeService, "timeService");
    this.syncContext = checkNotNull(syncContext, "syncContext");
    this.backoffPolicyProvider = checkNotNull(backoffPolicyProvider, "backoffPolicyProvider");
    stopwatch = checkNotNull(stopwatchSupplier, "stopwatchSupplier").get();
    logId = InternalLogId.allocate("xds-client", serverInfo.target());
    logger = XdsLogger.withLogId(logId);
    logger.log(XdsLogLevel.INFO, "Created");
  }

  /** The underlying channel. */
  // Currently, only externally used for LrsClient.
  Channel channel() {
    return channel;
  }

  void shutdown() {
    syncContext.execute(new Runnable() {
      @Override
      public void run() {
        shutdown = true;
        logger.log(XdsLogLevel.INFO, "Shutting down");
        if (adsStream != null) {
          adsStream.close(Status.CANCELLED.withDescription("shutdown").asException());
        }
        if (rpcRetryTimer != null && rpcRetryTimer.isPending()) {
          rpcRetryTimer.cancel();
        }
        channel.shutdown();
      }
    });
  }

  @Override
  public String toString() {
    return logId.toString();
  }

  /**
   * Updates the resource subscription for the given resource type.
   */
  // Must be synchronized.
  void adjustResourceSubscription(XdsResourceType<?> resourceType) {
    if (isInBackoff()) {
      return;
    }
    if (adsStream == null) {
      startRpcStream();
    }
    Collection<String> resources = resourceStore.getSubscribedResources(serverInfo, resourceType);
    if (resources != null) {
      adsStream.sendDiscoveryRequest(resourceType, resources);
    }
  }

  /**
   * Accepts the update for the given resource type by updating the latest resource version
   * and sends an ACK request to the management server.
   */
  // Must be synchronized.
  void ackResponse(XdsResourceType<?> type, String versionInfo, String nonce) {
    versions.put(type, versionInfo);
    logger.log(XdsLogLevel.INFO, "Sending ACK for {0} update, nonce: {1}, current version: {2}",
        type.typeName(), nonce, versionInfo);
    Collection<String> resources = resourceStore.getSubscribedResources(serverInfo, type);
    if (resources == null) {
      resources = Collections.emptyList();
    }
    adsStream.sendDiscoveryRequest(type, versionInfo, resources, nonce, null);
  }

  /**
   * Rejects the update for the given resource type and sends an NACK request (request with last
   * accepted version) to the management server.
   */
  // Must be synchronized.
  void nackResponse(XdsResourceType<?> type, String nonce, String errorDetail) {
    String versionInfo = versions.getOrDefault(type, "");
    logger.log(XdsLogLevel.INFO, "Sending NACK for {0} update, nonce: {1}, current version: {2}",
        type.typeName(), nonce, versionInfo);
    Collection<String> resources = resourceStore.getSubscribedResources(serverInfo, type);
    if (resources == null) {
      resources = Collections.emptyList();
    }
    adsStream.sendDiscoveryRequest(type, versionInfo, resources, nonce, errorDetail);
  }

  /**
   * Returns {@code true} if the resource discovery is currently in backoff.
   */
  // Must be synchronized.
  boolean isInBackoff() {
    return rpcRetryTimer != null && rpcRetryTimer.isPending();
  }

  /**
   * Establishes the RPC connection by creating a new RPC stream on the given channel for
   * xDS protocol communication.
   */
  // Must be synchronized.
  private void startRpcStream() {
    checkState(adsStream == null, "Previous adsStream has not been cleared yet");
    if (serverInfo.useProtocolV3()) {
      adsStream = new AdsStreamV3();
    } else {
      adsStream = new AdsStreamV2();
    }
    Context prevContext = context.attach();
    try {
      adsStream.start();
    } finally {
      context.detach(prevContext);
    }
    logger.log(XdsLogLevel.INFO, "ADS stream started");
    stopwatch.reset().start();
  }

  @VisibleForTesting
  final class RpcRetryTask implements Runnable {
    @Override
    public void run() {
      if (shutdown) {
        return;
      }
      startRpcStream();
      for (XdsResourceType<?> type : resourceStore.getXdsResourceTypes()) {
        Collection<String> resources = resourceStore.getSubscribedResources(serverInfo, type);
        if (resources != null) {
          adsStream.sendDiscoveryRequest(type, resources);
        }
      }
      xdsResponseHandler.handleStreamRestarted(serverInfo);
    }
  }

  @VisibleForTesting
  @Nullable
  static XdsResourceType<?> fromTypeUrl(String typeUrl) {
    switch (typeUrl) {
      case ADS_TYPE_URL_LDS:
        // fall trough
      case ADS_TYPE_URL_LDS_V2:
        return XdsListenerResource.getInstance();
      case ADS_TYPE_URL_RDS:
        // fall through
      case ADS_TYPE_URL_RDS_V2:
        return XdsRouteConfigureResource.getInstance();
      case ADS_TYPE_URL_CDS:
        // fall through
      case ADS_TYPE_URL_CDS_V2:
        return XdsClusterResource.getInstance();
      case ADS_TYPE_URL_EDS:
        // fall through
      case ADS_TYPE_URL_EDS_V2:
        return XdsEndpointResource.getInstance();
      default:
        return null;
    }
  }

  private abstract class AbstractAdsStream {
    private boolean responseReceived;
    private boolean closed;
    // Response nonce for the most recently received discovery responses of each resource type.
    // Client initiated requests start response nonce with empty string.
    // Nonce in each response is echoed back in the following ACK/NACK request. It is
    // used for management server to identify which response the client is ACKing/NACking.
    // To avoid confusion, client-initiated requests will always use the nonce in
    // most recently received responses of each resource type.
    private final Map<XdsResourceType<?>, String> respNonces = new HashMap<>();

    abstract void start();

    abstract void sendError(Exception error);

    /**
     * Sends a discovery request with the given {@code versionInfo}, {@code nonce} and
     * {@code errorDetail}. Used for reacting to a specific discovery response. For
     * client-initiated discovery requests, use {@link
     * #sendDiscoveryRequest(XdsResourceType, Collection)}.
     */
    abstract void sendDiscoveryRequest(XdsResourceType<?> type, String version,
        Collection<String> resources, String nonce, @Nullable String errorDetail);

    /**
     * Sends a client-initiated discovery request.
     */
    final void sendDiscoveryRequest(XdsResourceType<?> type, Collection<String> resources) {
      logger.log(XdsLogLevel.INFO, "Sending {0} request for resources: {1}", type, resources);
      sendDiscoveryRequest(type, versions.getOrDefault(type, ""), resources,
          respNonces.getOrDefault(type, ""), null);
    }

    final void handleRpcResponse(XdsResourceType<?> type, String versionInfo, List<Any> resources,
                                 String nonce) {
      if (closed) {
        return;
      }
      responseReceived = true;
      if (type != null) {
        respNonces.put(type, nonce);
      }
      xdsResponseHandler.handleResourceResponse(type, serverInfo, versionInfo, resources, nonce);
    }

    final void handleRpcError(Throwable t) {
      handleRpcStreamClosed(Status.fromThrowable(t));
    }

    final void handleRpcCompleted() {
      handleRpcStreamClosed(Status.UNAVAILABLE.withDescription("Closed by server"));
    }

    private void handleRpcStreamClosed(Status error) {
      checkArgument(!error.isOk(), "unexpected OK status");
      if (closed) {
        return;
      }
      logger.log(
          XdsLogLevel.ERROR,
          "ADS stream closed with status {0}: {1}. Cause: {2}",
          error.getCode(), error.getDescription(), error.getCause());
      closed = true;
      xdsResponseHandler.handleStreamClosed(error);
      cleanUp();
      if (responseReceived || retryBackoffPolicy == null) {
        // Reset the backoff sequence if had received a response, or backoff sequence
        // has never been initialized.
        retryBackoffPolicy = backoffPolicyProvider.get();
      }
      long delayNanos = Math.max(
          0,
          retryBackoffPolicy.nextBackoffNanos() - stopwatch.elapsed(TimeUnit.NANOSECONDS));
      logger.log(XdsLogLevel.INFO, "Retry ADS stream in {0} ns", delayNanos);
      rpcRetryTimer = syncContext.schedule(
          new RpcRetryTask(), delayNanos, TimeUnit.NANOSECONDS, timeService);
    }

    private void close(Exception error) {
      if (closed) {
        return;
      }
      closed = true;
      cleanUp();
      sendError(error);
    }

    private void cleanUp() {
      if (adsStream == this) {
        adsStream = null;
      }
    }
  }

  private final class AdsStreamV2 extends AbstractAdsStream {
    private StreamObserver<io.envoyproxy.envoy.api.v2.DiscoveryRequest> requestWriter;

    @Override
    void start() {
      io.envoyproxy.envoy.service.discovery.v2.AggregatedDiscoveryServiceGrpc
          .AggregatedDiscoveryServiceStub stub =
          io.envoyproxy.envoy.service.discovery.v2.AggregatedDiscoveryServiceGrpc.newStub(channel);
      StreamObserver<io.envoyproxy.envoy.api.v2.DiscoveryResponse> responseReaderV2 =
          new StreamObserver<io.envoyproxy.envoy.api.v2.DiscoveryResponse>() {
            @Override
            public void onNext(final io.envoyproxy.envoy.api.v2.DiscoveryResponse response) {
              syncContext.execute(new Runnable() {
                @Override
                public void run() {
                  XdsResourceType<?> type = fromTypeUrl(response.getTypeUrl());
                  if (logger.isLoggable(XdsLogLevel.DEBUG)) {
                    logger.log(
                        XdsLogLevel.DEBUG, "Received {0} response:\n{1}", type,
                        MessagePrinter.print(response));
                  }
                  handleRpcResponse(type, response.getVersionInfo(), response.getResourcesList(),
                      response.getNonce());
                }
              });
            }

            @Override
            public void onError(final Throwable t) {
              syncContext.execute(new Runnable() {
                @Override
                public void run() {
                  handleRpcError(t);
                }
              });
            }

            @Override
            public void onCompleted() {
              syncContext.execute(new Runnable() {
                @Override
                public void run() {
                  handleRpcCompleted();
                }
              });
            }
          };
      requestWriter = stub.withWaitForReady().streamAggregatedResources(responseReaderV2);
    }

    @Override
    void sendDiscoveryRequest(XdsResourceType<?> type, String versionInfo,
                              Collection<String> resources, String nonce,
                              @Nullable String errorDetail) {
      checkState(requestWriter != null, "ADS stream has not been started");
      io.envoyproxy.envoy.api.v2.DiscoveryRequest.Builder builder =
          io.envoyproxy.envoy.api.v2.DiscoveryRequest.newBuilder()
              .setVersionInfo(versionInfo)
              .setNode(bootstrapNode.toEnvoyProtoNodeV2())
              .addAllResourceNames(resources)
              .setTypeUrl(type.typeUrlV2())
              .setResponseNonce(nonce);
      if (errorDetail != null) {
        com.google.rpc.Status error =
            com.google.rpc.Status.newBuilder()
                .setCode(Code.INVALID_ARGUMENT_VALUE)  // FIXME(chengyuanzhang): use correct code
                .setMessage(errorDetail)
                .build();
        builder.setErrorDetail(error);
      }
      io.envoyproxy.envoy.api.v2.DiscoveryRequest request = builder.build();
      requestWriter.onNext(request);
      if (logger.isLoggable(XdsLogLevel.DEBUG)) {
        logger.log(XdsLogLevel.DEBUG, "Sent DiscoveryRequest\n{0}", MessagePrinter.print(request));
      }
    }

    @Override
    void sendError(Exception error) {
      requestWriter.onError(error);
    }
  }

  private final class AdsStreamV3 extends AbstractAdsStream {
    private StreamObserver<DiscoveryRequest> requestWriter;

    @Override
    void start() {
      AggregatedDiscoveryServiceGrpc.AggregatedDiscoveryServiceStub stub =
          AggregatedDiscoveryServiceGrpc.newStub(channel);
      StreamObserver<DiscoveryResponse> responseReader = new StreamObserver<DiscoveryResponse>() {
        @Override
        public void onNext(final DiscoveryResponse response) {
          syncContext.execute(new Runnable() {
            @Override
            public void run() {
              XdsResourceType<?> type = fromTypeUrl(response.getTypeUrl());
              if (logger.isLoggable(XdsLogLevel.DEBUG)) {
                logger.log(
                    XdsLogLevel.DEBUG, "Received {0} response:\n{1}", type,
                    MessagePrinter.print(response));
              }
              handleRpcResponse(type, response.getVersionInfo(), response.getResourcesList(),
                  response.getNonce());
            }
          });
        }

        @Override
        public void onError(final Throwable t) {
          syncContext.execute(new Runnable() {
            @Override
            public void run() {
              handleRpcError(t);
            }
          });
        }

        @Override
        public void onCompleted() {
          syncContext.execute(new Runnable() {
            @Override
            public void run() {
              handleRpcCompleted();
            }
          });
        }
      };
      requestWriter = stub.withWaitForReady().streamAggregatedResources(responseReader);
    }

    @Override
    void sendDiscoveryRequest(XdsResourceType<?> type, String versionInfo,
                              Collection<String> resources, String nonce,
                              @Nullable String errorDetail) {
      checkState(requestWriter != null, "ADS stream has not been started");
      DiscoveryRequest.Builder builder =
          DiscoveryRequest.newBuilder()
              .setVersionInfo(versionInfo)
              .setNode(bootstrapNode.toEnvoyProtoNode())
              .addAllResourceNames(resources)
              .setTypeUrl(type.typeUrl())
              .setResponseNonce(nonce);
      if (errorDetail != null) {
        com.google.rpc.Status error =
            com.google.rpc.Status.newBuilder()
                .setCode(Code.INVALID_ARGUMENT_VALUE)  // FIXME(chengyuanzhang): use correct code
                .setMessage(errorDetail)
                .build();
        builder.setErrorDetail(error);
      }
      DiscoveryRequest request = builder.build();
      requestWriter.onNext(request);
      if (logger.isLoggable(XdsLogLevel.DEBUG)) {
        logger.log(XdsLogLevel.DEBUG, "Sent DiscoveryRequest\n{0}", MessagePrinter.print(request));
      }
    }

    @Override
    void sendError(Exception error) {
      requestWriter.onError(error);
    }
  }
}
