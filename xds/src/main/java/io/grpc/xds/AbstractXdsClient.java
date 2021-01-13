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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Stopwatch;
import com.google.common.base.Supplier;
import com.google.protobuf.Any;
import com.google.rpc.Code;
import io.envoyproxy.envoy.service.discovery.v3.AggregatedDiscoveryServiceGrpc;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryRequest;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryResponse;
import io.grpc.InternalLogId;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.SynchronizationContext;
import io.grpc.SynchronizationContext.ScheduledHandle;
import io.grpc.internal.BackoffPolicy;
import io.grpc.stub.StreamObserver;
import io.grpc.xds.EnvoyProtoData.Node;
import io.grpc.xds.XdsLogger.XdsLogLevel;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

/**
 * Common base type for XdsClient implementations, which encapsulates the layer abstraction of
 * the xDS RPC stream.
 */
abstract class AbstractXdsClient extends XdsClient {

  private static final String ADS_TYPE_URL_LDS_V2 = "type.googleapis.com/envoy.api.v2.Listener";
  private static final String ADS_TYPE_URL_LDS =
      "type.googleapis.com/envoy.config.listener.v3.Listener";
  private static final String ADS_TYPE_URL_RDS_V2 =
      "type.googleapis.com/envoy.api.v2.RouteConfiguration";
  private static final String ADS_TYPE_URL_RDS =
      "type.googleapis.com/envoy.config.route.v3.RouteConfiguration";
  @VisibleForTesting
  static final String ADS_TYPE_URL_CDS_V2 = "type.googleapis.com/envoy.api.v2.Cluster";
  private static final String ADS_TYPE_URL_CDS =
      "type.googleapis.com/envoy.config.cluster.v3.Cluster";
  private static final String ADS_TYPE_URL_EDS_V2 =
      "type.googleapis.com/envoy.api.v2.ClusterLoadAssignment";
  private static final String ADS_TYPE_URL_EDS =
      "type.googleapis.com/envoy.config.endpoint.v3.ClusterLoadAssignment";

  private final SynchronizationContext syncContext = new SynchronizationContext(
      new Thread.UncaughtExceptionHandler() {
        @Override
        public void uncaughtException(Thread t, Throwable e) {
          getLogger().log(
              XdsLogLevel.ERROR,
              "Uncaught exception in XdsClient SynchronizationContext. Panic!",
              e);
          // TODO(chengyuanzhang): better error handling.
          throw new AssertionError(e);
        }
      });
  private final MessagePrinter msgPrinter = new MessagePrinter();
  private final InternalLogId logId;
  private final XdsLogger logger;
  private final ManagedChannel channel;
  private final boolean useProtocolV3;
  private final ScheduledExecutorService timeService;
  private final BackoffPolicy.Provider backoffPolicyProvider;
  private final Stopwatch stopwatch;
  // The node identifier to be included in xDS requests. Management server only requires the
  // first request to carry the node identifier on a stream. It should be identical if present
  // more than once.
  // FIXME(chengyuanzhang): should immutable and invisible to child classes. Currently server side
  //  has some protocol workaround usages.
  protected Node node;

  // Last successfully applied version_info for each resource type. Starts with empty string.
  // A version_info is used to update management server with client's most recent knowledge of
  // resources.
  private String ldsVersion = "";
  private String rdsVersion = "";
  private String cdsVersion = "";
  private String edsVersion = "";

  private boolean shutdown;
  @Nullable
  private AbstractAdsStream adsStream;
  @Nullable
  private BackoffPolicy retryBackoffPolicy;
  @Nullable
  private ScheduledHandle rpcRetryTimer;

  AbstractXdsClient(ManagedChannel channel, boolean useProtocolV3, Node node,
      ScheduledExecutorService timeService, BackoffPolicy.Provider backoffPolicyProvider,
      Supplier<Stopwatch> stopwatchSupplier) {
    this.channel = checkNotNull(channel, "channel");
    this.useProtocolV3 = useProtocolV3;
    this.node = checkNotNull(node, "node");
    this.timeService = checkNotNull(timeService, "timeService");
    this.backoffPolicyProvider = checkNotNull(backoffPolicyProvider, "backoffPolicyProvider");
    stopwatch = checkNotNull(stopwatchSupplier, "stopwatchSupplier").get();
    logId = InternalLogId.allocate("xds-client", null);
    logger = XdsLogger.withLogId(logId);
    logger.log(XdsLogLevel.INFO, "Created");
  }

  /**
   * Called when an LDS response is received.
   */
  // Must be synchronized.
  protected void handleLdsResponse(String versionInfo, List<Any> resources, String nonce) {
  }

  /**
   * Called when a RDS response is received.
   */
  // Must be synchronized.
  protected void handleRdsResponse(String versionInfo, List<Any> resources, String nonce) {
  }

  /**
   * Called when a CDS response is received.
   */
  // Must be synchronized.
  protected void handleCdsResponse(String versionInfo, List<Any> resources, String nonce) {
  }

  /**
   * Called when an EDS response is received.
   */
  // Must be synchronized.
  protected void handleEdsResponse(String versionInfo, List<Any> resources, String nonce) {
  }

  /**
   * Called when the ADS stream is closed passively.
   */
  // Must be synchronized.
  protected void handleStreamClosed(Status error) {
  }

  /**
   * Called when the ADS stream has been recreated.
   */
  // Must be synchronized.
  protected void handleStreamRestarted() {
  }

  /**
   * Called when being shut down.
   */
  // Must be synchronized.
  protected void handleShutdown() {
  }

  @Override
  final void shutdown() {
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
        handleShutdown();
      }
    });
  }

  @Override
  boolean isShutDown() {
    return shutdown;
  }

  @Override
  public String toString() {
    return logId.toString();
  }

  /**
   * Returns the collection of resources currently subscribing to or {@code null} if not
   * subscribing to any resources for the given type.
   *
   * <p>Note an empty collection indicates subscribing to resources of the given type with
   * wildcard mode.
   */
  // Must be synchronized.
  @Nullable
  abstract Collection<String> getSubscribedResources(ResourceType type);

  /**
   * Updates the resource subscription for the given resource type.
   */
  // Must be synchronized.
  protected final void adjustResourceSubscription(ResourceType type) {
    if (isInBackoff()) {
      return;
    }
    if (adsStream == null) {
      startRpcStream();
    }
    Collection<String> resources = getSubscribedResources(type);
    if (resources != null) {
      adsStream.sendDiscoveryRequest(type, resources);
    }
  }

  /**
   * Accepts the update for the given resource type by updating the latest resource version
   * and sends an ACK request to the management server.
   */
  // Must be synchronized.
  protected final void ackResponse(ResourceType type, String versionInfo, String nonce) {
    switch (type) {
      case LDS:
        ldsVersion = versionInfo;
        break;
      case RDS:
        rdsVersion = versionInfo;
        break;
      case CDS:
        cdsVersion = versionInfo;
        break;
      case EDS:
        edsVersion = versionInfo;
        break;
      case UNKNOWN:
      default:
        throw new AssertionError("Unknown resource type: " + type);
    }
    logger.log(XdsLogLevel.INFO, "Sending ACK for {0} update, nonce: {1}, current version: {2}",
        type, nonce, versionInfo);
    Collection<String> resources = getSubscribedResources(type);
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
  protected final void nackResponse(ResourceType type, String nonce, String errorDetail) {
    String versionInfo = getCurrentVersion(type);
    logger.log(XdsLogLevel.INFO, "Sending NACK for {0} update, nonce: {1}, current version: {2}",
        type, nonce, versionInfo);
    Collection<String> resources = getSubscribedResources(type);
    if (resources == null) {
      resources = Collections.emptyList();
    }
    adsStream.sendDiscoveryRequest(type, versionInfo, resources, nonce, errorDetail);
  }

  protected final SynchronizationContext getSyncContext() {
    return syncContext;
  }

  protected final ScheduledExecutorService getTimeService() {
    return timeService;
  }

  protected final XdsLogger getLogger() {
    return logger;
  }

  /**
   * Returns {@code true} if the resource discovery is currently in backoff.
   */
  // Must be synchronized.
  protected final boolean isInBackoff() {
    return rpcRetryTimer != null && rpcRetryTimer.isPending();
  }

  /**
   * Establishes the RPC connection by creating a new RPC stream on the given channel for
   * xDS protocol communication.
   */
  // Must be synchronized.
  private void startRpcStream() {
    checkState(adsStream == null, "Previous adsStream has not been cleared yet");
    if (useProtocolV3) {
      adsStream = new AdsStreamV3();
    } else {
      adsStream = new AdsStreamV2();
    }
    adsStream.start();
    logger.log(XdsLogLevel.INFO, "ADS stream started");
    stopwatch.reset().start();
  }

  /**
   * Returns the latest accepted version of the given resource type.
   */
  // Must be synchronized.
  private String getCurrentVersion(ResourceType type) {
    String version;
    switch (type) {
      case LDS:
        version = ldsVersion;
        break;
      case RDS:
        version = rdsVersion;
        break;
      case CDS:
        version = cdsVersion;
        break;
      case EDS:
        version = edsVersion;
        break;
      case UNKNOWN:
      default:
        throw new AssertionError("Unknown resource type: " + type);
    }
    return version;
  }

  @VisibleForTesting
  final class RpcRetryTask implements Runnable {
    @Override
    public void run() {
      if (shutdown) {
        return;
      }
      startRpcStream();
      for (ResourceType type : ResourceType.values()) {
        if (type == ResourceType.UNKNOWN) {
          continue;
        }
        Collection<String> resources = getSubscribedResources(type);
        if (resources != null) {
          adsStream.sendDiscoveryRequest(type, resources);
        }
      }
      handleStreamRestarted();
    }
  }

  protected enum ResourceType {
    UNKNOWN, LDS, RDS, CDS, EDS;

    String typeUrl() {
      switch (this) {
        case LDS:
          return ADS_TYPE_URL_LDS;
        case RDS:
          return ADS_TYPE_URL_RDS;
        case CDS:
          return ADS_TYPE_URL_CDS;
        case EDS:
          return ADS_TYPE_URL_EDS;
        case UNKNOWN:
        default:
          throw new AssertionError("Unknown or missing case in enum switch: " + this);
      }
    }

    String typeUrlV2() {
      switch (this) {
        case LDS:
          return ADS_TYPE_URL_LDS_V2;
        case RDS:
          return ADS_TYPE_URL_RDS_V2;
        case CDS:
          return ADS_TYPE_URL_CDS_V2;
        case EDS:
          return ADS_TYPE_URL_EDS_V2;
        case UNKNOWN:
        default:
          throw new AssertionError("Unknown or missing case in enum switch: " + this);
      }
    }

    private static ResourceType fromTypeUrl(String typeUrl) {
      switch (typeUrl) {
        case ADS_TYPE_URL_LDS:
          // fall trough
        case ADS_TYPE_URL_LDS_V2:
          return LDS;
        case ADS_TYPE_URL_RDS:
          // fall through
        case ADS_TYPE_URL_RDS_V2:
          return RDS;
        case ADS_TYPE_URL_CDS:
          // fall through
        case ADS_TYPE_URL_CDS_V2:
          return CDS;
        case ADS_TYPE_URL_EDS:
          // fall through
        case ADS_TYPE_URL_EDS_V2:
          return EDS;
        default:
          return UNKNOWN;
      }
    }
  }

  private abstract class AbstractAdsStream {
    private boolean responseReceived;
    private boolean closed;

    // Response nonce for the most recently received discovery responses of each resource type.
    // Client initiated requests start response nonce with empty string.
    // A nonce is used to indicate the specific DiscoveryResponse each DiscoveryRequest
    // corresponds to.
    // A nonce becomes stale following a newer nonce being presented to the client in a
    // DiscoveryResponse.
    private String ldsRespNonce = "";
    private String rdsRespNonce = "";
    private String cdsRespNonce = "";
    private String edsRespNonce = "";

    abstract void start();

    abstract void sendError(Exception error);

    /**
     * Sends a discovery request with the given {@code versionInfo}, {@code nonce} and
     * {@code errorDetail}. Used for reacting to a specific discovery response. For
     * client-initiated discovery requests, use {@link
     * #sendDiscoveryRequest(ResourceType, Collection)}.
     */
    abstract void sendDiscoveryRequest(ResourceType type, String versionInfo,
        Collection<String> resources, String nonce, @Nullable String errorDetail);

    /**
     * Sends a client-initiated discovery request.
     */
    final void sendDiscoveryRequest(ResourceType type, Collection<String> resources) {
      String nonce;
      switch (type) {
        case LDS:
          nonce = ldsRespNonce;
          break;
        case RDS:
          nonce = rdsRespNonce;
          break;
        case CDS:
          nonce = cdsRespNonce;
          break;
        case EDS:
          nonce = edsRespNonce;
          break;
        case UNKNOWN:
        default:
          throw new AssertionError("Unknown resource type: " + type);
      }
      logger.log(XdsLogLevel.INFO, "Sending {0} request for resources: {1}", type, resources);
      sendDiscoveryRequest(type, getCurrentVersion(type), resources, nonce, null);
    }

    final void handleRpcResponse(
        ResourceType type, String versionInfo, List<Any> resources, String nonce) {
      if (closed) {
        return;
      }
      responseReceived = true;
      // Nonce in each response is echoed back in the following ACK/NACK request. It is
      // used for management server to identify which response the client is ACKing/NACking.
      // To avoid confusion, client-initiated requests will always use the nonce in
      // most recently received responses of each resource type.
      switch (type) {
        case LDS:
          ldsRespNonce = nonce;
          handleLdsResponse(versionInfo, resources, nonce);
          break;
        case RDS:
          rdsRespNonce = nonce;
          handleRdsResponse(versionInfo, resources, nonce);
          break;
        case CDS:
          cdsRespNonce = nonce;
          handleCdsResponse(versionInfo, resources, nonce);
          break;
        case EDS:
          edsRespNonce = nonce;
          handleEdsResponse(versionInfo, resources, nonce);
          break;
        case UNKNOWN:
        default:
          logger.log(XdsLogLevel.WARNING, "Ignore an unknown type of DiscoveryResponse");
      }
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
      handleStreamClosed(error);
      cleanUp();
      if (responseReceived || retryBackoffPolicy == null) {
        // Reset the backoff sequence if had received a response, or backoff sequence
        // has never been initialized.
        retryBackoffPolicy = backoffPolicyProvider.get();
      }
      long delayNanos = 0;
      if (!responseReceived) {
        delayNanos =
            Math.max(
                0,
                retryBackoffPolicy.nextBackoffNanos()
                    - stopwatch.elapsed(TimeUnit.NANOSECONDS));
      }
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
                  ResourceType type = ResourceType.fromTypeUrl(response.getTypeUrl());
                  if (logger.isLoggable(XdsLogLevel.DEBUG)) {
                    logger.log(XdsLogLevel.DEBUG, "Received {0} response:\n{1}",
                        type, msgPrinter.print(response));
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
    void sendDiscoveryRequest(ResourceType type, String versionInfo, Collection<String> resources,
        String nonce, @Nullable String errorDetail) {
      checkState(requestWriter != null, "ADS stream has not been started");
      io.envoyproxy.envoy.api.v2.DiscoveryRequest.Builder builder =
          io.envoyproxy.envoy.api.v2.DiscoveryRequest.newBuilder()
              .setVersionInfo(versionInfo)
              .setNode(node.toEnvoyProtoNodeV2())
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
      logger.log(XdsLogLevel.DEBUG, "Sent DiscoveryRequest\n{0}", msgPrinter.print(request));
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
              ResourceType type = ResourceType.fromTypeUrl(response.getTypeUrl());
              if (logger.isLoggable(XdsLogLevel.DEBUG)) {
                logger.log(XdsLogLevel.DEBUG, "Received {0} response:\n{1}",
                    type, msgPrinter.print(response));
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
    void sendDiscoveryRequest(ResourceType type, String versionInfo, Collection<String> resources,
        String nonce, @Nullable String errorDetail) {
      checkState(requestWriter != null, "ADS stream has not been started");
      DiscoveryRequest.Builder builder =
          DiscoveryRequest.newBuilder()
              .setVersionInfo(versionInfo)
              .setNode(node.toEnvoyProtoNode())
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
      logger.log(XdsLogLevel.DEBUG, "Sent DiscoveryRequest\n{0}", msgPrinter.print(request));
    }

    @Override
    void sendError(Exception error) {
      requestWriter.onError(error);
    }
  }
}
