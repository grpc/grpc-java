/*
 * Copyright 2019 The gRPC Authors
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
import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import io.envoyproxy.envoy.api.v2.DiscoveryRequest;
import io.envoyproxy.envoy.api.v2.DiscoveryResponse;
import io.envoyproxy.envoy.api.v2.Listener;
import io.envoyproxy.envoy.api.v2.RouteConfiguration;
import io.envoyproxy.envoy.api.v2.core.Node;
import io.envoyproxy.envoy.api.v2.route.Route;
import io.envoyproxy.envoy.api.v2.route.VirtualHost;
import io.envoyproxy.envoy.config.filter.network.http_connection_manager.v2.HttpConnectionManager;
import io.envoyproxy.envoy.service.discovery.v2.AggregatedDiscoveryServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.SynchronizationContext;
import io.grpc.SynchronizationContext.ScheduledHandle;
import io.grpc.internal.BackoffPolicy;
import io.grpc.stub.StreamObserver;
import io.grpc.xds.Bootstrapper.ChannelCreds;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

final class XdsClientImpl extends XdsClient {
  private static final Logger logger = Logger.getLogger(XdsClientImpl.class.getName());

  private static final String ADS_TYPE_URL_LDS = "type.googleapis.com/envoy.api.v2.Listener";
  private static final String ADS_TYPE_URL_RDS =
      "type.googleapis.com/envoy.api.v2.RouteConfiguration";

  private final ManagedChannel channel;
  private final SynchronizationContext syncContext;
  private final ScheduledExecutorService timeService;
  private final BackoffPolicy.Provider backoffPolicyProvider;
  private final Stopwatch stopwatch;
  // The original "xds:" URI for the server name that the gRPC client targets for.
  private final String targetName;
  // The node identifier to be included in xDS requests. Management server only requires the
  // first request to carry the node identifier on a stream. It should be identical if present
  // more than once.
  private final Node node;
  private final ConfigWatcher configWatcher;

  private AdsStream adsStream;

  private BackoffPolicy retryBackoffPolicy;
  private ScheduledHandle rpcRetryTimer;

  XdsClientImpl(
      String serverUri, /* URI of the management server to be connected to */
      Node node,
      ChannelCreds channelCreds, /* channel credentials for xDS communication (not used now) */
      SynchronizationContext syncContext,
      ScheduledExecutorService timeService,
      BackoffPolicy.Provider backoffPolicyProvider,
      Stopwatch stopwatch,
      String targetName,
      ConfigWatcher configWatcher) {
    this(createChannel(checkNotNull(serverUri, "serverUri")), node, syncContext,
        timeService, backoffPolicyProvider, stopwatch, targetName, configWatcher);
  }

  @VisibleForTesting
  XdsClientImpl(
      ManagedChannel channel,
      Node node,
      SynchronizationContext syncContext,
      ScheduledExecutorService timeService,
      BackoffPolicy.Provider backoffPolicyProvider,
      Stopwatch stopwatch,
      String targetName,
      ConfigWatcher configWatcher) {
    this.channel = checkNotNull(channel, "channel");
    this.syncContext = checkNotNull(syncContext, "syncContext");
    this.timeService = checkNotNull(timeService, "timeService");
    this.backoffPolicyProvider = checkNotNull(backoffPolicyProvider, "backoffPolicyProvider");
    this.stopwatch = checkNotNull(stopwatch, "stopwatch");
    this.targetName = checkNotNull(targetName, "targetName");
    this.node = checkNotNull(node, "node");
    this.configWatcher = checkNotNull(configWatcher, "configWatcher");
  }

  private static ManagedChannel createChannel(String serverUri) {
    return ManagedChannelBuilder.forTarget(serverUri).build();
  }

  /**
   * Starts with a LDS RPC.
   */
  void start() {
    startDiscoveryRpc();
  }

  void shutdown() {
    channel.shutdown();
    if (adsStream != null) {
      adsStream.close(Status.CANCELLED.withDescription("shutdown").asException());
    }
    if (rpcRetryTimer != null) {
      rpcRetryTimer.cancel();
    }
  }

  /**
   * Creates a new RPC stream for xDS protocol communication and starts with a LDS request.
   */
  private void startDiscoveryRpc() {
    checkState(adsStream == null, "previous adsStream has not been cleared yet");
    AggregatedDiscoveryServiceGrpc.AggregatedDiscoveryServiceStub stub =
        AggregatedDiscoveryServiceGrpc.newStub(channel);
    adsStream = new AdsStream(stub);
    adsStream.start();
    stopwatch.reset().start();
    adsStream.sendLdsRequest(targetName, "");
  }

  /**
   * Handles LDS response to find the HttpConnectionManager message for the requested resource name.
   * Sends an ACK request to the management server if the HttpConnectionManager for the requested
   * resource name is found, which may either contain the RouteConfiguration directly in-line or
   * contain the configuration to send RDS requests for dynamic resolution. Otherwise, sends back
   * a NACK request. Proceed with the resolved RouteConfiguration (if exists) to find the
   * VirtualHost configuration for the "xds:" URI.
   */
  private void handleLdsResponse(DiscoveryResponse ldsResponse) {
    logger.log(Level.FINE, "Received a LDS response: {0}", ldsResponse);
    List<Any> respResources = ldsResponse.getResourcesList();
    HttpConnectionManager connManager = null;
    try {
      for (com.google.protobuf.Any res : respResources) {
        Listener listener = res.unpack(Listener.class);
        if (listener.getName().equals(targetName)) {
          HttpConnectionManager httpConnManager =
              listener.getApiListener().getApiListener().unpack(HttpConnectionManager.class);
          if (httpConnManager.hasRouteConfig() || httpConnManager.hasRds()) {
            connManager = httpConnManager;
          }
          // Ignore remaining listeners once the requested one is found.
          break;
        }
      }
    } catch (InvalidProtocolBufferException e) {
      configWatcher.onError(Status.fromThrowable(e).augmentDescription("Invalid LDS response"));
    }
    if (connManager != null) {
      // Accept this listener update.
      adsStream.ldsVersion = ldsResponse.getVersionInfo();
    }
    // Send ACK/NACK request.
    adsStream.sendLdsRequest(targetName, ldsResponse.getNonce());
    if (connManager != null) {
      if (connManager.hasRouteConfig()) {
        processRouteConfig(connManager.getRouteConfig());
      } else if (connManager.hasRds()) {
        String rcName = connManager.getRds().getRouteConfigName();
        adsStream.sendRdsRequest(rcName, ldsResponse.getNonce());
      } else {
        // Impossible to be here.
        throw new AssertionError("Severe bug: accepted listener update contains invalid config");
      }
    }
  }

  /**
   * Handles RDS response to find the RouteConfiguration message for the requested resource name.
   * Sends an ACK request to the management server if the RouteConfiguration for the requested
   * resource name is found. Otherwise, sends back a NACK request. Proceed with the
   * resolved RouteConfiguration (if exists) to find the VirtualHost configuration for the "xds:"
   * URI.
   */
  private void handleRdsResponse(DiscoveryResponse rdsResponse) {
    logger.log(Level.FINE, "Received an RDS response: {0}", rdsResponse);
    List<com.google.protobuf.Any> respResources = rdsResponse.getResourcesList();
    RouteConfiguration routeConfig = null;
    try {
      for (com.google.protobuf.Any res : respResources) {
        RouteConfiguration conf = res.unpack(RouteConfiguration.class);
        if (routeConfig.getName().equals(adsStream.rdsResourceName)) {
          routeConfig = conf;
          // Ignore remaining route configs once the requested one is found.
          break;
        }
      }
    } catch (InvalidProtocolBufferException e) {
      configWatcher.onError(Status.fromThrowable(e).augmentDescription("Invalid RDS response"));
    }
    if (routeConfig != null) {
      // Accept this route configuration update.
      adsStream.rdsVersion = rdsResponse.getVersionInfo();
    }
    // Send an ACK/NACK request.
    adsStream.sendRdsRequest(adsStream.rdsResourceName, rdsResponse.getNonce());
    if (routeConfig != null) {
      processRouteConfig(routeConfig);
    }
  }

  /**
   * Processes RouteConfiguration message, which may either contain a VirtualHost with domains
   * matching the "xds:" URI directly in-line or contain the configuration to send VHDS requests
   * for dynamic resolution (currently not implemented). Forwards the resolved VirtualHost
   * configuration as a {@link io.grpc.xds.XdsClient.ConfigUpdate} to the registered config watcher.
   * Otherwise, forwards an error message to the config watcher.
   */
  private void processRouteConfig(RouteConfiguration config) {
    List<VirtualHost> virtualHosts = config.getVirtualHostsList();
    String clusterName = null;
    // Proceed with the virtual host that has longest wildcard matched domain name with the
    // original "xds:" URI.
    int matchingLen = -1;  // longest length of wildcard pattern that matches target name
    for (VirtualHost vHost : virtualHosts) {
      for (String domain : vHost.getDomainsList()) {
        if (matchHostName(targetName, domain) && domain.length() > matchingLen) {
          matchingLen = domain.length();
          // The client will look only at the last route in the list (the default route),
          // whose match field must be empty and whose route field must be set.
          List<Route> routes = vHost.getRoutesList();
          if (!routes.isEmpty()) {
            Route route = routes.get(routes.size() - 1);
            // TODO(chengyuanzhang): check the match field must be empty.
            if (route.hasRoute()) {
              clusterName = route.getRoute().getCluster();
            }
          }
        }
      }
    }
    // TODO(chengyuanzhang): check VHDS config and perform VHDS if set.
    if (clusterName == null) {
      configWatcher.onError(
          Status.NOT_FOUND.withDescription("Virtual host for target " + targetName + " not found"));
    } else {
      ConfigUpdate configUpdate = ConfigUpdate.newBuilder().setClusterName(clusterName).build();
      configWatcher.onConfigChanged(configUpdate);
    }
  }

  private final class RpcRetryTask implements Runnable {
    @Override
    public void run() {
      startDiscoveryRpc();
    }
  }

  private final class AdsStream implements StreamObserver<DiscoveryResponse> {
    private final AggregatedDiscoveryServiceGrpc.AggregatedDiscoveryServiceStub stub;

    private StreamObserver<DiscoveryRequest> requestWriter;
    private boolean responseReceived;
    private boolean closed;

    // Last successfully applied version_info for each resource type. Starts with empty string.
    private String ldsVersion = "";
    private String rdsVersion = "";

    // Most recently requested resource name for each resource type. Note the resource_name in
    // LDS requests will always be the
    private String rdsResourceName = "";

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
          responseReceived = true;
          String typeUrl = response.getTypeUrl();
          if (typeUrl.equals(ADS_TYPE_URL_LDS)) {
            handleLdsResponse(response);
          } else if (typeUrl.equals(ADS_TYPE_URL_RDS)) {
            handleRdsResponse(response);
          }
          // TODO(zdapeng): add CDS/EDS response handles.
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
              Status.UNAVAILABLE.withDescription("ADS stream [" + this + "] was closed"));
        }
      });
    }

    private void handleStreamClosed(Status error) {
      checkArgument(!error.isOk(), "unexpected OK status");
      if (closed) {
        return;
      }
      closed = true;
      cleanUp();
      configWatcher.onError(error);
      if (responseReceived || retryBackoffPolicy == null) {
        // Reset the backoff sequence if had received a response, or backoff sequence
        // has never been initialized.
        retryBackoffPolicy = backoffPolicyProvider.get();
      }
      long delayNanos = 0;
      if (!responseReceived) {
        delayNanos =
            retryBackoffPolicy.nextBackoffNanos() - stopwatch.elapsed(TimeUnit.NANOSECONDS);
      }
      logger.log(Level.FINE, "{0} stream closed, retry in {1} ns", new Object[]{this, delayNanos});
      if (delayNanos <= 0) {
        startDiscoveryRpc();
      } else {
        rpcRetryTimer =
            syncContext.schedule(
                new RpcRetryTask(), delayNanos, TimeUnit.NANOSECONDS, timeService);
      }
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

    private void sendLdsRequest(String resourceName, String nonce) {
      checkState(requestWriter != null, "ADS stream has not been started");
      DiscoveryRequest request =
          DiscoveryRequest
              .newBuilder()
              .setVersionInfo(ldsVersion)
              .setNode(node)
              .addResourceNames(resourceName)
              .setTypeUrl(ADS_TYPE_URL_LDS)
              .setResponseNonce(nonce)
              .build();
      requestWriter.onNext(request);
    }

    private void sendRdsRequest(String resourceName, String nonce) {
      checkState(requestWriter != null, "ADS has not been started");
      DiscoveryRequest request =
          DiscoveryRequest
              .newBuilder()
              .setVersionInfo(rdsVersion)
              .setNode(node)
              .addResourceNames(resourceName)
              .setTypeUrl(ADS_TYPE_URL_RDS)
              .setResponseNonce(nonce)
              .build();
      requestWriter.onNext(request);
    }
  }

  @Override
  void watchClusterData(String clusterName, ClusterWatcher watcher) {

  }

  @Override
  void cancelClusterDataWatch(ClusterWatcher watcher) {

  }

  @Override
  void watchEndpointData(String clusterName, EndpointWatcher watcher) {

  }

  @Override
  void cancelEndpointDataWatch(EndpointWatcher watcher) {

  }

  /**
   * Returns {@code true} iff {@code hostName} matches the domain name {@code pattern}.
   *
   * <p>Wildcard pattern rules:
   * <ol>
   * <li>A single asterisk (*) matches any domain.</li>
   * <li>Asterisk (*) is only permitted in the left-most or the right-most part of the pattern,
   *     but not both.</li>
   * </ol>
   */
  private static boolean matchHostName(String hostName, String pattern) {
    // Basic sanity checks
    if (hostName == null || hostName.length() == 0 || hostName.startsWith(".")
        || hostName.endsWith(".")) {
      // Invalid domain name
      return false;
    }
    if (pattern == null || pattern.length() == 0 || pattern.startsWith(".")
        || pattern.endsWith(".")) {
      // Invalid pattern/domain name
      return false;
    }

    pattern = pattern.toLowerCase(Locale.US);
    // hostName and pattern are now in lower case -- domain names are case-insensitive.

    if (!pattern.contains("*")) {
      // Not a wildcard pattern -- hostName and pattern must match exactly.
      return hostName.equals(pattern);
    }
    // Wildcard pattern

    if (pattern.length() == 1) {
      return true;
    }

    int index = pattern.indexOf('*');

    if (pattern.indexOf('*', index + 1) != -1) {
      // At most one asterisk (*) is allowed.
      return false;
    }

    if (index != 0 && index != pattern.length() - 1) {
      return false;
    }

    // Optimization: check whether hostName is too short to match the pattern. hostName must be at
    // least as long as the pattern because asterisk has to match one or more characters.
    if (hostName.length() < pattern.length()) {
      // hostName too short to match the pattern.
      return false;
    }

    if (index == 0 && !hostName.endsWith(pattern.substring(1))) {
      // Prefix matching fails.
      return false;
    }

    // Pattern matches hostname if suffix matching succeeds.
    return index == pattern.length() - 1
        && hostName.startsWith(pattern.substring(0, pattern.length() - 1));
  }
}
