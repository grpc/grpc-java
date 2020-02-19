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
import static io.grpc.xds.XdsClientImpl.ADS_TYPE_URL_LDS;
import static io.grpc.xds.XdsClientImpl.INITIAL_RESOURCE_FETCH_TIMEOUT_SEC;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Stopwatch;
import com.google.common.base.Strings;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import com.google.rpc.Code;
import io.envoyproxy.envoy.api.v2.DiscoveryRequest;
import io.envoyproxy.envoy.api.v2.DiscoveryResponse;
import io.envoyproxy.envoy.api.v2.Listener;
import io.envoyproxy.envoy.api.v2.core.CidrRange;
import io.envoyproxy.envoy.api.v2.core.Node;
import io.envoyproxy.envoy.api.v2.listener.FilterChain;
import io.envoyproxy.envoy.api.v2.listener.FilterChainMatch;
import io.envoyproxy.envoy.service.discovery.v2.AggregatedDiscoveryServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.SynchronizationContext;
import io.grpc.SynchronizationContext.ScheduledHandle;
import io.grpc.internal.BackoffPolicy;
import io.grpc.stub.StreamObserver;
import io.grpc.xds.Bootstrapper.ServerInfo;
import io.grpc.xds.XdsClientImpl.MessagePrinter;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;

/** XdsClient implementation to fetch Listener object(s) to configure server/listener. */
final class XdsClientImplForListener extends XdsClient {
  private static final Logger logger = Logger.getLogger(XdsClientImplForListener.class.getName());

  private final MessagePrinter respPrinter = new MessagePrinter();

  private final ManagedChannel channel;
  private final SynchronizationContext syncContext;
  private final ScheduledExecutorService timeService;
  private final BackoffPolicy.Provider backoffPolicyProvider;
  private final Stopwatch adsStreamRetryStopwatch;
  // The node identifier to be included in xDS requests. Management server only requires the
  // first request to carry the node identifier on a stream. It should be identical if present
  // more than once.
  private Node node;

  // Timer for concluding the currently requesting LDS resource not found.
  @Nullable
  private ScheduledHandle ldsRespTimer;

  @Nullable
  private AdsStream adsStream;
  @Nullable
  private BackoffPolicy retryBackoffPolicy;
  @Nullable
  private ScheduledHandle rpcRetryTimer;

  // Following fields are set only after the ConfigWatcher registered. Once set, they should
  // never change.
  @Nullable
  private ConfigWatcher configWatcher;
  // The host name passed in watchConfigData()
  @Nullable
  private String hostName;
  // The port passed in watchConfigData()
  private int port;
  // The "xds:" URI (including port suffix if present) that the gRPC client targets for.
  @Nullable
  private String ldsResourceName;

  XdsClientImplForListener(
      List<ServerInfo> servers,  // list of management servers
      XdsChannelFactory channelFactory,
      Node node,
      SynchronizationContext syncContext,
      ScheduledExecutorService timeService,
      BackoffPolicy.Provider backoffPolicyProvider,
      Supplier<Stopwatch> stopwatchSupplier) throws UnknownHostException {
    this(servers, channelFactory, node, syncContext, timeService,
        backoffPolicyProvider, stopwatchSupplier,
        InetAddress.getLocalHost().getHostAddress());
  }

  @VisibleForTesting
  XdsClientImplForListener(
      List<ServerInfo> servers,  // list of management servers
      XdsChannelFactory channelFactory,
      Node node,
      SynchronizationContext syncContext,
      ScheduledExecutorService timeService,
      BackoffPolicy.Provider backoffPolicyProvider,
      Supplier<Stopwatch> stopwatchSupplier,
      String localIp) {
    this.channel =
        checkNotNull(channelFactory, "channelFactory")
            .createChannel(checkNotNull(servers, "servers"));
    this.syncContext = checkNotNull(syncContext, "syncContext");
    this.timeService = checkNotNull(timeService, "timeService");
    this.backoffPolicyProvider = checkNotNull(backoffPolicyProvider, "backoffPolicyProvider");
    adsStreamRetryStopwatch = checkNotNull(stopwatchSupplier, "stopwatch").get();
    this.node = addEnvoyMetadata(node, localIp);
  }

  static Node addEnvoyMetadata(Node node, String localIpAddress) {
    checkNotNull(node, "node");
    checkNotNull(localIpAddress, "localIp");
    // temp workaround until server side LDS is designed
    // add "INSTANCE_IP", "TRAFFICDIRECTOR_INTERCEPTION_PORT" and
    // "TRAFFICDIRECTOR_INBOUND_BACKEND_PORTS" to Node metadata
    Struct newMetadata = node.getMetadata().toBuilder()
        .putFields("INSTANCE_IP",
            Value.newBuilder().setStringValue(localIpAddress).build())
        .putFields("TRAFFICDIRECTOR_INTERCEPTION_PORT",
            Value.newBuilder().setStringValue("15001").build())
        .build();
    return node.toBuilder().setMetadata(newMetadata).build();
  }

  @Override
  void shutdown() {
    logger.log(Level.INFO, "Shutting down XdsClient");
    channel.shutdown();
    if (adsStream != null) {
      adsStream.close(Status.CANCELLED.withDescription("shutdown").asException());
    }
    cleanUpResources();
    if (rpcRetryTimer != null) {
      rpcRetryTimer.cancel();
    }
  }

  /**
   * Purge cache for resources and cancel resource fetch timers.
   */
  private void cleanUpResources() {
    if (ldsRespTimer != null) {
      ldsRespTimer.cancel();
      ldsRespTimer = null;
    }
  }

  @Override
  void watchConfigData(String hostName, int port, ConfigWatcher watcher) {
    checkState(configWatcher == null, "ConfigWatcher is already registered");
    configWatcher = checkNotNull(watcher, "watcher");
    addBackendPortForEnvoyWorkaround(port);
    this.hostName = hostName;
    this.port = port;

    // TODO(sanjaypujare): change to use agreed upon ldsResourceName, until then set to null
    ldsResourceName = null;

    if (rpcRetryTimer != null && rpcRetryTimer.isPending()) {
      // Currently in retry backoff.
      return;
    }
    if (adsStream == null) {
      startRpcStream();
    }
    logger.log(Level.FINEST, "sendXdsRequest ldsResourceName = {0}", ldsResourceName);
    adsStream.sendXdsRequest(ADS_TYPE_URL_LDS, ldsResourceName);
    ldsRespTimer =
        syncContext
            .schedule(
                new LdsResourceFetchTimeoutTask(ldsResourceName),
                INITIAL_RESOURCE_FETCH_TIMEOUT_SEC, TimeUnit.SECONDS, timeService);
  }

  private void addBackendPortForEnvoyWorkaround(int port) {
    // temp workaround until server side LDS is designed
    // add "INSTANCE_IP", "TRAFFICDIRECTOR_INTERCEPTION_PORT" and
    // "TRAFFICDIRECTOR_INBOUND_BACKEND_PORTS" to Node metadata
    Struct newMetadata = node.getMetadata().toBuilder()
        .putFields("TRAFFICDIRECTOR_INBOUND_BACKEND_PORTS",
            Value.newBuilder().setStringValue("" + port).build())
        .build();
    node = node.toBuilder().setMetadata(newMetadata).build();
  }

  /**
   * Establishes the RPC connection by creating a new RPC stream on the given channel for
   * xDS protocol communication.
   */
  private void startRpcStream() {
    checkState(adsStream == null, "Previous adsStream has not been cleared yet");
    AggregatedDiscoveryServiceGrpc.AggregatedDiscoveryServiceStub stub =
        AggregatedDiscoveryServiceGrpc.newStub(channel);
    adsStream = new AdsStream(stub);
    adsStream.start();
    adsStreamRetryStopwatch.reset().start();
  }

  /**
   * Handles LDS response to locate a listener that we are interested in using for server side
   * xDS processing. Currently we do Envoy based Listener processing which will be changed
   * to proxyless gRPC based processing, once API finalized.
   */
  private void handleLdsResponse(DiscoveryResponse ldsResponse) {
    if (logger.isLoggable(Level.FINE)) {
      logger.log(Level.FINE, "Received an LDS response: {0}", respPrinter.print(ldsResponse));
    }
    checkState(configWatcher != null,
        "No LDS request was ever sent. Management server is doing something wrong");

    // Unpack Listener messages.
    Listener requestedListener = null;
    logger.log(Level.FINEST, "Listener count: {0}", ldsResponse.getResourcesCount());
    try {
      for (com.google.protobuf.Any res : ldsResponse.getResourcesList()) {
        Listener listener = res.unpack(Listener.class);
        logger.log(Level.FINEST, "Found listener {0}", listener.toString());
        if (isRequestedListener(listener)) {
          requestedListener = listener;
          logger.log(Level.FINEST, "Requested listener found: {0}", listener.getName());
        }
      }
    } catch (InvalidProtocolBufferException e) {
      adsStream.sendNackRequest(ADS_TYPE_URL_LDS, ldsResourceName,
          "Broken LDS response.");
      return;
    }
    adsStream.sendAckRequest(ADS_TYPE_URL_LDS, ldsResourceName,
        ldsResponse.getVersionInfo());
    if (requestedListener != null) {
      // Found requestedListener
      if (ldsRespTimer != null) {
        ldsRespTimer.cancel();
        ldsRespTimer = null;
      }
      ConfigUpdate configUpdate = ConfigUpdate.newBuilder()
          .setClusterName(null)
          .setListener(EnvoyServerProtoData.Listener.fromEnvoyProtoListener(requestedListener))
          .build();
      configWatcher.onConfigChanged(configUpdate);
    } else {
      // did not find the requested listener:
      if (ldsRespTimer == null) {
        configWatcher.onError(Status.NOT_FOUND.withDescription("did not find listener for "
            + hostName + ":" + port));
      }
    }
  }

  private boolean isRequestedListener(Listener listener) {
    String listenerName = listener.getName();
    // ldsResourceName is null so that won't match listenerName
    // return listener based on temporary Envoy based processing:
    if (listenerName.equals("TRAFFICDIRECTOR_INTERCEPTION_LISTENER")
        && hasMatchingFilter(listener.getFilterChainsList())) {
      return true;
    }
    return false;
  }

  private boolean hasMatchingFilter(List<FilterChain> filterChainsList) {
    String myIp = node.getMetadata().getFieldsOrDefault("INSTANCE_IP", null).getStringValue();
    for (FilterChain filterChain : filterChainsList) {
      FilterChainMatch filterChainMatch = filterChain.getFilterChainMatch();

      if (!isIpMatchingPrefixRange(myIp, filterChainMatch.getPrefixRangesList())) {
        continue;
      }
      if (port == filterChainMatch.getDestinationPort().getValue()) {
        return true;
      }
    }
    return false;
  }

  private static boolean isIpMatchingPrefixRange(String myIp, List<CidrRange> prefixRangesList) {
    if (Strings.isNullOrEmpty(myIp)) {
      return true;
    }
    for (CidrRange prefixRange : prefixRangesList) {
      String addressPrefix = prefixRange.getAddressPrefix();
      int prefixLen = prefixRange.getPrefixLen().getValue();

      if (prefixLen == 32 && myIp.equals(addressPrefix)) {
        return true;
      }
      //TODO(sanjaypujare): add logic for when prefixLen < 32 by creating a mask
    }
    return false;
  }

  @VisibleForTesting
  final class RpcRetryTask implements Runnable {
    @Override
    public void run() {
      startRpcStream();
      if (configWatcher != null) {
        adsStream.sendXdsRequest(ADS_TYPE_URL_LDS, ldsResourceName);
        ldsRespTimer =
            syncContext
                .schedule(
                    new LdsResourceFetchTimeoutTask(ldsResourceName),
                    INITIAL_RESOURCE_FETCH_TIMEOUT_SEC, TimeUnit.SECONDS, timeService);
      }
    }
  }

  private final class AdsStream implements StreamObserver<DiscoveryResponse> {
    private final AggregatedDiscoveryServiceGrpc.AggregatedDiscoveryServiceStub stub;

    private StreamObserver<DiscoveryRequest> requestWriter;
    private boolean responseReceived;
    private boolean closed;

    // Last successfully applied version_info for each resource type. Starts with empty string.
    // A version_info is used to update management server with client's most recent knowledge of
    // resources.
    private String ldsVersion = "";

    // Response nonce for the most recently received discovery responses of each resource type.
    // Client initiated requests start response nonce with empty string.
    // A nonce is used to indicate the specific DiscoveryResponse each DiscoveryRequest
    // corresponds to.
    // A nonce becomes stale following a newer nonce being presented to the client in a
    // DiscoveryResponse.
    private String ldsRespNonce = "";

    private AdsStream(AggregatedDiscoveryServiceGrpc.AggregatedDiscoveryServiceStub stub) {
      this.stub = checkNotNull(stub, "stub");
    }

    private void start() {
      requestWriter = stub.withWaitForReady().streamAggregatedResources(this);
    }

    @Override
    public void onNext(final DiscoveryResponse response) {
      syncContext.execute(new Runnable() {
        @Override
        public void run() {
          if (closed) {
            return;
          }
          responseReceived = true;
          String typeUrl = response.getTypeUrl();
          // Nonce in each response is echoed back in the following ACK/NACK request. It is
          // used for management server to identify which response the client is ACKing/NACking.
          // To avoid confusion, client-initiated requests will always use the nonce in
          // most recently received responses of each resource type.
          if (typeUrl.equals(ADS_TYPE_URL_LDS)) {
            ldsRespNonce = response.getNonce();
            handleLdsResponse(response);
          } else {
            logger.log(Level.FINE, "Received unexpected DiscoveryResponse {0}",
                response);
          }
        }
      });
    }

    @Override
    public void onError(final Throwable t) {
      syncContext.execute(new Runnable() {
        @Override
        public void run() {
          handleStreamClosed(
              Status.fromThrowable(t).augmentDescription("ADS stream [" + this + "] had an error"));
        }
      });
    }

    @Override
    public void onCompleted() {
      syncContext.execute(new Runnable() {
        @Override
        public void run() {
          handleStreamClosed(
              Status.UNAVAILABLE.withDescription("ADS stream [" + this + "] was closed by server"));
        }
      });
    }

    private void handleStreamClosed(Status error) {
      checkArgument(!error.isOk(), "unexpected OK status");
      if (closed) {
        return;
      }
      logger.log(Level.FINE, error.getDescription(), error.getCause());
      closed = true;
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
                    - adsStreamRetryStopwatch.elapsed(TimeUnit.NANOSECONDS));
      }
      logger.log(Level.FINE, "{0} stream closed, retry in {1} ns", new Object[]{this, delayNanos});
      rpcRetryTimer =
          syncContext.schedule(
              new RpcRetryTask(), delayNanos, TimeUnit.NANOSECONDS, timeService);
    }

    private void close(Exception error) {
      if (closed) {
        return;
      }
      closed = true;
      cleanUp();
      requestWriter.onError(error);
    }

    private void cleanUp() {
      if (adsStream == this) {
        adsStream = null;
      }
    }

    /**
     * Sends a DiscoveryRequest for the given resource name to management server. Memories the
     * requested resource name (except for LDS as we always request for the singleton Listener)
     * as we need it to find resources in responses.
     */
    private void sendXdsRequest(String typeUrl, String resourceNamesString) {
      checkState(requestWriter != null, "ADS stream has not been started");
      String version = "";
      String nonce = "";
      if (typeUrl.equals(ADS_TYPE_URL_LDS)) {
        version = ldsVersion;
        nonce = ldsRespNonce;
      } else {
        logger.severe("unexpected typeUrl:" + typeUrl);
        return;
      }
      DiscoveryRequest.Builder builder =
              DiscoveryRequest
                      .newBuilder()
                      .setVersionInfo(version)
                      .setNode(node)
                      .setTypeUrl(typeUrl)
                      .setResponseNonce(nonce);
      if (resourceNamesString != null) {
        Collection<String> resourceNames = ImmutableList.of(resourceNamesString);
        builder = builder.addAllResourceNames(resourceNames);
      }
      DiscoveryRequest request = builder.build();
      requestWriter.onNext(request);
      logger.log(Level.FINE, "Sent DiscoveryRequest {0}", request);
    }

    /**
     * Sends a DiscoveryRequest with the given information as an ACK. Updates the latest accepted
     * version for the corresponding resource type.
     */
    private void sendAckRequest(String typeUrl, String resNameString,
        String versionInfo) {
      Collection<String> resourceNames = resNameString == null
          ? null : ImmutableList.of(resNameString);
      checkState(requestWriter != null, "ADS stream has not been started");
      String nonce = "";
      if (typeUrl.equals(ADS_TYPE_URL_LDS)) {
        ldsVersion = versionInfo;
        nonce = ldsRespNonce;
      }
      DiscoveryRequest.Builder builder =
          DiscoveryRequest
              .newBuilder()
              .setVersionInfo(versionInfo)
              .setNode(node)
              .setTypeUrl(typeUrl)
              .setResponseNonce(nonce);
      if (resourceNames != null) {
        builder = builder.addAllResourceNames(resourceNames);
      }
      DiscoveryRequest request = builder.build();
      requestWriter.onNext(request);
      logger.log(Level.FINE, "Sent ACK request {0}", request);
    }

    /**
     * Sends a DiscoveryRequest with the given information as an NACK. NACK takes the previous
     * accepted version.
     */
    private void sendNackRequest(String typeUrl, String resNameString,
        String message) {
      Collection<String> resourceNames = resNameString == null
          ? null : ImmutableList.of(resNameString);
      checkState(requestWriter != null, "ADS stream has not been started");
      String versionInfo = "";
      String nonce = "";
      if (typeUrl.equals(ADS_TYPE_URL_LDS)) {
        versionInfo = ldsVersion;
        nonce = ldsRespNonce;
      }
      DiscoveryRequest.Builder builder =
          DiscoveryRequest
              .newBuilder()
              .setVersionInfo(versionInfo)
              .setNode(node)
              .setTypeUrl(typeUrl)
              .setResponseNonce(nonce)
              .setErrorDetail(
                  com.google.rpc.Status.newBuilder()
                      .setCode(Code.INVALID_ARGUMENT_VALUE)
                      .setMessage(message));
      if (resourceNames != null) {
        builder = builder.addAllResourceNames(resourceNames);
      }
      DiscoveryRequest request = builder.build();
      requestWriter.onNext(request);
      logger.log(Level.FINE, "Sent NACK request {0}", request);
    }
  }

  private abstract static class ResourceFetchTimeoutTask implements Runnable {
    protected final String resourceName;

    ResourceFetchTimeoutTask(String resourceName) {
      this.resourceName = resourceName;
    }
  }

  @VisibleForTesting
  final class LdsResourceFetchTimeoutTask extends ResourceFetchTimeoutTask {

    LdsResourceFetchTimeoutTask(String resourceName) {
      super(resourceName);
    }

    @Override
    public void run() {
      ldsRespTimer = null;
      configWatcher.onError(
          Status.NOT_FOUND
              .withDescription("Listener resource for listener [" + resourceName + "] not found."));
    }
  }
}
