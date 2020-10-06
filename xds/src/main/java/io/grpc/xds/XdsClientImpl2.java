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
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.MessageOrBuilder;
import com.google.protobuf.TypeRegistry;
import com.google.protobuf.util.Durations;
import com.google.protobuf.util.JsonFormat;
import com.google.rpc.Code;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.DiscoveryType;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.EdsClusterConfig;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.LbPolicy;
import io.envoyproxy.envoy.config.core.v3.Address;
import io.envoyproxy.envoy.config.core.v3.HttpProtocolOptions;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.endpoint.v3.LbEndpoint;
import io.envoyproxy.envoy.config.listener.v3.FilterChain;
import io.envoyproxy.envoy.config.listener.v3.FilterChainMatch;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.envoyproxy.envoy.config.route.v3.VirtualHost;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.Rds;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.UpstreamTlsContext;
import io.envoyproxy.envoy.service.discovery.v3.AggregatedDiscoveryServiceGrpc;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryRequest;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryResponse;
import io.grpc.InternalLogId;
import io.grpc.Status;
import io.grpc.SynchronizationContext;
import io.grpc.SynchronizationContext.ScheduledHandle;
import io.grpc.internal.BackoffPolicy;
import io.grpc.stub.StreamObserver;
import io.grpc.xds.EnvoyProtoData.DropOverload;
import io.grpc.xds.EnvoyProtoData.Locality;
import io.grpc.xds.EnvoyProtoData.LocalityLbEndpoints;
import io.grpc.xds.EnvoyProtoData.Node;
import io.grpc.xds.EnvoyProtoData.StructOrError;
import io.grpc.xds.LoadStatsManager.LoadStatsStore;
import io.grpc.xds.XdsLogger.XdsLogLevel;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

final class XdsClientImpl2 extends XdsClient {

  // Longest time to wait, since the subscription to some resource, for concluding its absence.
  @VisibleForTesting
  static final int INITIAL_RESOURCE_FETCH_TIMEOUT_SEC = 15;
  private static final String ADS_TYPE_URL_LDS_V2 = "type.googleapis.com/envoy.api.v2.Listener";
  private static final String ADS_TYPE_URL_LDS =
      "type.googleapis.com/envoy.config.listener.v3.Listener";
  private static final String ADS_TYPE_URL_RDS_V2 =
      "type.googleapis.com/envoy.api.v2.RouteConfiguration";
  private static final String ADS_TYPE_URL_RDS =
      "type.googleapis.com/envoy.config.route.v3.RouteConfiguration";
  private static final String TYPE_URL_HTTP_CONNECTION_MANAGER_V2 =
      "type.googleapis.com/envoy.config.filter.network.http_connection_manager.v2"
          + ".HttpConnectionManager";
  private static final String TYPE_URL_HTTP_CONNECTION_MANAGER =
      "type.googleapis.com/envoy.extensions.filters.network.http_connection_manager.v3"
          + ".HttpConnectionManager";
  private static final String ADS_TYPE_URL_CDS_V2 = "type.googleapis.com/envoy.api.v2.Cluster";
  private static final String ADS_TYPE_URL_CDS =
      "type.googleapis.com/envoy.config.cluster.v3.Cluster";
  private static final String ADS_TYPE_URL_EDS_V2 =
      "type.googleapis.com/envoy.api.v2.ClusterLoadAssignment";
  private static final String ADS_TYPE_URL_EDS =
      "type.googleapis.com/envoy.config.endpoint.v3.ClusterLoadAssignment";

  private final MessagePrinter respPrinter = new MessagePrinter();
  private final InternalLogId logId;
  private final XdsLogger logger;
  private final String targetName;  // TODO: delete me.
  private final XdsChannel xdsChannel;
  private final SynchronizationContext syncContext;
  private final ScheduledExecutorService timeService;
  private final BackoffPolicy.Provider backoffPolicyProvider;
  private final Supplier<Stopwatch> stopwatchSupplier;
  private final Stopwatch adsStreamRetryStopwatch;
  // The node identifier to be included in xDS requests. Management server only requires the
  // first request to carry the node identifier on a stream. It should be identical if present
  // more than once.
  private Node node;

  private final Map<String, ResourceSubscriber> ldsResourceSubscribers = new HashMap<>();
  private final Map<String, ResourceSubscriber> rdsResourceSubscribers = new HashMap<>();
  private final Map<String, ResourceSubscriber> cdsResourceSubscribers = new HashMap<>();
  private final Map<String, ResourceSubscriber> edsResourceSubscribers = new HashMap<>();

  private final LoadStatsManager loadStatsManager = new LoadStatsManager();

  // Last successfully applied version_info for each resource type. Starts with empty string.
  // A version_info is used to update management server with client's most recent knowledge of
  // resources.
  private String ldsVersion = "";
  private String rdsVersion = "";
  private String cdsVersion = "";
  private String edsVersion = "";

  @Nullable
  private AbstractAdsStream adsStream;
  @Nullable
  private BackoffPolicy retryBackoffPolicy;
  @Nullable
  private ScheduledHandle rpcRetryTimer;
  @Nullable
  private LoadReportClient lrsClient;
  private int loadReportCount;  // number of clusters enabling load reporting

  // For server side usage.
  @Nullable
  private ListenerWatcher listenerWatcher;
  private int listenerPort = -1;
  @Nullable
  private ScheduledHandle ldsRespTimer;

  XdsClientImpl2(
      String targetName,
      XdsChannel channel,
      Node node,
      SynchronizationContext syncContext,
      ScheduledExecutorService timeService,
      BackoffPolicy.Provider backoffPolicyProvider,
      Supplier<Stopwatch> stopwatchSupplier) {
    this.targetName = checkNotNull(targetName, "targetName");
    this.xdsChannel = checkNotNull(channel, "channel");
    this.node = checkNotNull(node, "node");
    this.syncContext = checkNotNull(syncContext, "syncContext");
    this.timeService = checkNotNull(timeService, "timeService");
    this.backoffPolicyProvider = checkNotNull(backoffPolicyProvider, "backoffPolicyProvider");
    this.stopwatchSupplier = checkNotNull(stopwatchSupplier, "stopwatch");
    adsStreamRetryStopwatch = stopwatchSupplier.get();
    logId = InternalLogId.allocate("xds-client", null);
    logger = XdsLogger.withLogId(logId);
    logger.log(XdsLogLevel.INFO, "Created");
  }

  @Override
  void shutdown() {
    logger.log(XdsLogLevel.INFO, "Shutting down");
    xdsChannel.getManagedChannel().shutdown();
    if (adsStream != null) {
      adsStream.close(Status.CANCELLED.withDescription("shutdown").asException());
    }
    cleanUpResourceTimers();
    if (lrsClient != null) {
      lrsClient.stopLoadReporting();
      lrsClient = null;
    }
    if (rpcRetryTimer != null) {
      rpcRetryTimer.cancel();
    }
  }

  private void cleanUpResourceTimers() {
    if (ldsRespTimer != null) {
      ldsRespTimer.cancel();
      ldsRespTimer = null;
    }
    for (ResourceSubscriber subscriber : ldsResourceSubscribers.values()) {
      subscriber.stopTimer();
    }
    for (ResourceSubscriber subscriber : rdsResourceSubscribers.values()) {
      subscriber.stopTimer();
    }
    for (ResourceSubscriber subscriber : cdsResourceSubscribers.values()) {
      subscriber.stopTimer();
    }
    for (ResourceSubscriber subscriber : edsResourceSubscribers.values()) {
      subscriber.stopTimer();
    }
  }

  @Override
  void watchLdsResource(String resourceName, LdsResourceWatcher watcher) {
    ResourceSubscriber subscriber = ldsResourceSubscribers.get(resourceName);
    if (subscriber == null) {
      logger.log(XdsLogLevel.INFO, "Subscribe CDS resource {0}", resourceName);
      subscriber = new ResourceSubscriber(ResourceType.LDS, resourceName);
      ldsResourceSubscribers.put(resourceName, subscriber);
      adjustResourceSubscription(ResourceType.LDS, ldsResourceSubscribers.keySet());
    }
    subscriber.addWatcher(watcher);
  }

  @Override
  void cancelLdsResourceWatch(String resourceName, LdsResourceWatcher watcher) {
    ResourceSubscriber subscriber = ldsResourceSubscribers.get(resourceName);
    subscriber.removeWatcher(watcher);
    if (!subscriber.isWatched()) {
      subscriber.stopTimer();
      logger.log(XdsLogLevel.INFO, "Unsubscribe LDS resource {0}", resourceName);
      ldsResourceSubscribers.remove(resourceName);
      adjustResourceSubscription(ResourceType.LDS, ldsResourceSubscribers.keySet());
    }
  }

  @Override
  void watchRdsResource(String resourceName, RdsResourceWatcher watcher) {
    ResourceSubscriber subscriber = rdsResourceSubscribers.get(resourceName);
    if (subscriber == null) {
      logger.log(XdsLogLevel.INFO, "Subscribe RDS resource {0}", resourceName);
      subscriber = new ResourceSubscriber(ResourceType.RDS, resourceName);
      rdsResourceSubscribers.put(resourceName, subscriber);
      adjustResourceSubscription(ResourceType.RDS, rdsResourceSubscribers.keySet());
    }
    subscriber.addWatcher(watcher);
  }

  @Override
  void cancelRdsResourceWatch(String resourceName, RdsResourceWatcher watcher) {
    ResourceSubscriber subscriber = rdsResourceSubscribers.get(resourceName);
    subscriber.removeWatcher(watcher);
    if (!subscriber.isWatched()) {
      subscriber.stopTimer();
      logger.log(XdsLogLevel.INFO, "Unsubscribe RDS resource {0}", resourceName);
      rdsResourceSubscribers.remove(resourceName);
      adjustResourceSubscription(ResourceType.RDS, rdsResourceSubscribers.keySet());
    }
  }

  @Override
  void watchCdsResource(String resourceName, CdsResourceWatcher watcher) {
    ResourceSubscriber subscriber = cdsResourceSubscribers.get(resourceName);
    if (subscriber == null) {
      logger.log(XdsLogLevel.INFO, "Subscribe CDS resource {0}", resourceName);
      subscriber = new ResourceSubscriber(ResourceType.CDS, resourceName);
      cdsResourceSubscribers.put(resourceName, subscriber);
      adjustResourceSubscription(ResourceType.CDS, cdsResourceSubscribers.keySet());
    }
    subscriber.addWatcher(watcher);
  }

  @Override
  void cancelCdsResourceWatch(String resourceName, CdsResourceWatcher watcher) {
    ResourceSubscriber subscriber = cdsResourceSubscribers.get(resourceName);
    subscriber.removeWatcher(watcher);
    if (!subscriber.isWatched()) {
      subscriber.stopTimer();
      logger.log(XdsLogLevel.INFO, "Unsubscribe CDS resource {0}", resourceName);
      cdsResourceSubscribers.remove(resourceName);
      adjustResourceSubscription(ResourceType.CDS, cdsResourceSubscribers.keySet());
    }
  }

  @Override
  void watchEdsResource(String resourceName, EdsResourceWatcher watcher) {
    ResourceSubscriber subscriber = edsResourceSubscribers.get(resourceName);
    if (subscriber == null) {
      logger.log(XdsLogLevel.INFO, "Subscribe EDS resource {0}", resourceName);
      subscriber = new ResourceSubscriber(ResourceType.EDS, resourceName);
      edsResourceSubscribers.put(resourceName, subscriber);
      adjustResourceSubscription(ResourceType.EDS, edsResourceSubscribers.keySet());
    }
    subscriber.addWatcher(watcher);
  }

  @Override
  void cancelEdsResourceWatch(String resourceName, EdsResourceWatcher watcher) {
    ResourceSubscriber subscriber = edsResourceSubscribers.get(resourceName);
    subscriber.removeWatcher(watcher);
    if (!subscriber.isWatched()) {
      subscriber.stopTimer();
      logger.log(XdsLogLevel.INFO, "Unsubscribe EDS resource {0}", resourceName);
      edsResourceSubscribers.remove(resourceName);
      adjustResourceSubscription(ResourceType.EDS, edsResourceSubscribers.keySet());
    }
  }

  @Override
  void watchListenerData(int port, ListenerWatcher watcher) {
    checkState(listenerWatcher == null, "ListenerWatcher already registered");
    listenerWatcher = checkNotNull(watcher, "watcher");
    checkArgument(port > 0, "port needs to be > 0");
    this.listenerPort = port;
    logger.log(XdsLogLevel.INFO, "Started watching listener for port {0}", port);
    if (rpcRetryTimer != null && rpcRetryTimer.isPending()) {
      // Currently in retry backoff.
      return;
    }
    if (adsStream == null) {
      startRpcStream();
    }
    updateNodeMetadataForListenerRequest(port);
    adsStream.sendXdsRequest(ResourceType.LDS, ImmutableList.<String>of());
    ldsRespTimer =
        syncContext
            .schedule(
                new ListenerResourceFetchTimeoutTask(":" + port),
                INITIAL_RESOURCE_FETCH_TIMEOUT_SEC, TimeUnit.SECONDS, timeService);
  }

  /** In case of Listener watcher metadata to be updated to include port. */
  private void updateNodeMetadataForListenerRequest(int port) {
    Map<String, Object> newMetadata = new HashMap<>();
    if (node.getMetadata() != null) {
      newMetadata.putAll(node.getMetadata());
    }
    newMetadata.put("TRAFFICDIRECTOR_PROXYLESS", "1");
    // TODO(sanjaypujare): eliminate usage of listening_addresses.
    EnvoyProtoData.Address listeningAddress =
        new EnvoyProtoData.Address("0.0.0.0", port);
    node =
        node.toBuilder().setMetadata(newMetadata).addListeningAddresses(listeningAddress).build();
  }

  @Override
  void reportClientStats() {
    if (lrsClient == null) {
      logger.log(XdsLogLevel.INFO, "Turning on load reporting");
      lrsClient =
          new LoadReportClient(
              targetName,
              loadStatsManager,
              xdsChannel,
              node,
              syncContext,
              timeService,
              backoffPolicyProvider,
              stopwatchSupplier);
    }
    if (loadReportCount == 0) {
      lrsClient.startLoadReporting();
    }
    loadReportCount++;
  }

  @Override
  void cancelClientStatsReport() {
    checkState(loadReportCount > 0, "load reporting was never started");
    loadReportCount--;
    if (loadReportCount == 0) {
      logger.log(XdsLogLevel.INFO, "Turning off load reporting");
      lrsClient.stopLoadReporting();
      lrsClient = null;
    }
  }

  @Override
  LoadStatsStore addClientStats(String clusterName, @Nullable String clusterServiceName) {
    return loadStatsManager.addLoadStats(clusterName, clusterServiceName);
  }

  @Override
  void removeClientStats(String clusterName, @Nullable String clusterServiceName) {
    loadStatsManager.removeLoadStats(clusterName, clusterServiceName);
  }

  @Override
  public String toString() {
    return logId.toString();
  }

  /**
   * Establishes the RPC connection by creating a new RPC stream on the given channel for
   * xDS protocol communication.
   */
  private void startRpcStream() {
    checkState(adsStream == null, "Previous adsStream has not been cleared yet");
    if (xdsChannel.isUseProtocolV3()) {
      adsStream = new AdsStream();
    } else {
      adsStream = new AdsStreamV2();
    }
    adsStream.start();
    logger.log(XdsLogLevel.INFO, "ADS stream started");
    adsStreamRetryStopwatch.reset().start();
  }

  private void handleLdsResponse(DiscoveryResponseData ldsResponse) {
    if (listenerWatcher != null) {
      handleLdsResponseForServer(ldsResponse);
    } else {
      handleLdsResponseForClient(ldsResponse);
    }
  }

  private void handleLdsResponseForClient(DiscoveryResponseData ldsResponse) {
    // Unpack Listener messages.
    List<Listener> listeners = new ArrayList<>(ldsResponse.getResourcesList().size());
    List<String> listenerNames = new ArrayList<>(ldsResponse.getResourcesList().size());
    try {
      for (com.google.protobuf.Any res : ldsResponse.getResourcesList()) {
        if (res.getTypeUrl().equals(ADS_TYPE_URL_LDS_V2)) {
          res = res.toBuilder().setTypeUrl(ADS_TYPE_URL_LDS).build();
        }
        Listener listener = res.unpack(Listener.class);
        listeners.add(listener);
        listenerNames.add(listener.getName());
      }
    } catch (InvalidProtocolBufferException e) {
      logger.log(XdsLogLevel.WARNING, "Failed to unpack Listeners in LDS response {0}", e);
      adsStream.sendNackRequest(
          ResourceType.LDS, ldsResourceSubscribers.keySet(),
          ldsResponse.getVersionInfo(), "Malformed LDS response: " + e);
      return;
    }
    logger.log(XdsLogLevel.INFO, "Received LDS response for resources: {0}", listenerNames);

    // Unpack HttpConnectionManager messages.
    Map<String, HttpConnectionManager> httpConnectionManagers = new HashMap<>(listeners.size());
    try {
      for (Listener listener : listeners) {
        Any apiListener = listener.getApiListener().getApiListener();
        if (apiListener.getTypeUrl().equals(TYPE_URL_HTTP_CONNECTION_MANAGER_V2)) {
          apiListener =
              apiListener.toBuilder().setTypeUrl(TYPE_URL_HTTP_CONNECTION_MANAGER).build();
        }
        HttpConnectionManager hcm = apiListener.unpack(HttpConnectionManager.class);
        httpConnectionManagers.put(listener.getName(), hcm);
      }
    } catch (InvalidProtocolBufferException e) {
      logger.log(
          XdsLogLevel.WARNING,
          "Failed to unpack HttpConnectionManagers in Listeners of LDS response {0}", e);
      adsStream.sendNackRequest(
          ResourceType.LDS, ldsResourceSubscribers.keySet(),
          ldsResponse.getVersionInfo(), "Malformed LDS response: " + e);
      return;
    }

    Map<String, LdsUpdate> ldsUpdates = new HashMap<>();
    Set<String> rdsNames = new HashSet<>();
    String errorMessage = null;
    for (Map.Entry<String, HttpConnectionManager> entry : httpConnectionManagers.entrySet()) {
      String listenerName = entry.getKey();
      HttpConnectionManager hcm = entry.getValue();
      LdsUpdate.Builder updateBuilder = LdsUpdate.newBuilder();
      if (hcm.hasRouteConfig()) {
        for (VirtualHost virtualHostProto : hcm.getRouteConfig().getVirtualHostsList()) {
          StructOrError<EnvoyProtoData.VirtualHost> virtualHost =
              EnvoyProtoData.VirtualHost.fromEnvoyProtoVirtualHost(virtualHostProto);
          if (virtualHost.getErrorDetail() != null) {
            errorMessage = "Listener " + listenerName + " contains invalid virtual host: "
                + virtualHost.getErrorDetail();
            break;
          } else {
            updateBuilder.addVirtualHost(virtualHost.getStruct());
          }
        }
      } else if (hcm.hasRds()) {
        Rds rds = hcm.getRds();
        if (!rds.getConfigSource().hasAds()) {
          errorMessage = "Listener " + listenerName + " with RDS config_source not set to ADS";
        } else {
          updateBuilder.setRdsName(rds.getRouteConfigName());
          rdsNames.add(rds.getRouteConfigName());
        }
      } else {
        errorMessage = "Listener " + listenerName + " without inline RouteConfiguration or RDS";
      }
      if (errorMessage != null) {
        break;
      }
      if (hcm.hasCommonHttpProtocolOptions()) {
        HttpProtocolOptions options = hcm.getCommonHttpProtocolOptions();
        if (options.hasMaxStreamDuration()) {
          updateBuilder.setHttpMaxStreamDurationNano(
              Durations.toNanos(options.getMaxStreamDuration()));
        }
      }
      ldsUpdates.put(listenerName, updateBuilder.build());
    }
    if (errorMessage != null) {
      adsStream.sendNackRequest(
          ResourceType.LDS, ldsResourceSubscribers.keySet(),
          ldsResponse.getVersionInfo(), errorMessage);
      return;
    }
    adsStream.sendAckRequest(
        ResourceType.LDS, ldsResourceSubscribers.keySet(), ldsResponse.getVersionInfo());

    for (String resource : ldsResourceSubscribers.keySet()) {
      ResourceSubscriber subscriber = ldsResourceSubscribers.get(resource);
      if (ldsUpdates.containsKey(resource)) {
        subscriber.onData(ldsUpdates.get(resource));
      } else {
        subscriber.onAbsent();
      }
    }
    for (String resource : rdsResourceSubscribers.keySet()) {
      if (!rdsNames.contains(resource)) {
        ResourceSubscriber subscriber = rdsResourceSubscribers.get(resource);
        subscriber.onAbsent();
      }
    }
  }

  private void handleRdsResponse(DiscoveryResponseData rdsResponse) {
    // Unpack RouteConfiguration messages.
    Map<String, RouteConfiguration> routeConfigs =
        new HashMap<>(rdsResponse.getResourcesList().size());
    try {
      for (com.google.protobuf.Any res : rdsResponse.getResourcesList()) {
        if (res.getTypeUrl().equals(ADS_TYPE_URL_RDS_V2)) {
          res = res.toBuilder().setTypeUrl(ADS_TYPE_URL_RDS).build();
        }
        RouteConfiguration rc = res.unpack(RouteConfiguration.class);
        routeConfigs.put(rc.getName(), rc);
      }
    } catch (InvalidProtocolBufferException e) {
      logger.log(
          XdsLogLevel.WARNING, "Failed to unpack RouteConfiguration in RDS response {0}", e);
      adsStream.sendNackRequest(
          ResourceType.RDS, rdsResourceSubscribers.keySet(),
          rdsResponse.getVersionInfo(), "Malformed RDS response: " + e);
      return;
    }
    logger.log(
        XdsLogLevel.INFO, "Received RDS response for resources: {0}", routeConfigs.keySet());

    Map<String, RdsUpdate> rdsUpdates = new HashMap<>();
    String errorMessage = null;
    for (Map.Entry<String, RouteConfiguration> entry : routeConfigs.entrySet()) {
      String routeConfigName = entry.getKey();
      RouteConfiguration routeConfig = entry.getValue();
      List<EnvoyProtoData.VirtualHost> virtualHosts =
          new ArrayList<>(routeConfig.getVirtualHostsCount());
      for (VirtualHost virtualHostProto : routeConfig.getVirtualHostsList()) {
        StructOrError<EnvoyProtoData.VirtualHost> virtualHost =
            EnvoyProtoData.VirtualHost.fromEnvoyProtoVirtualHost(virtualHostProto);
        if (virtualHost.getErrorDetail() != null) {
          errorMessage = "RouteConfiguration " + routeConfigName
              + " contains invalid virtual host: " + virtualHost.getErrorDetail();
          break;
        } else {
          virtualHosts.add(virtualHost.getStruct());
        }
      }
      if (errorMessage != null) {
        break;
      }
      rdsUpdates.put(routeConfigName, RdsUpdate.fromVirtualHosts(virtualHosts));
    }
    if (errorMessage != null) {
      adsStream.sendNackRequest(ResourceType.RDS, rdsResourceSubscribers.keySet(),
          rdsResponse.getVersionInfo(), errorMessage);
      return;
    }
    adsStream.sendAckRequest(ResourceType.RDS, rdsResourceSubscribers.keySet(),
        rdsResponse.getVersionInfo());

    for (String resource : rdsResourceSubscribers.keySet()) {
      if (rdsUpdates.containsKey(resource)) {
        ResourceSubscriber subscriber = rdsResourceSubscribers.get(resource);
        subscriber.onData(rdsUpdates.get(resource));
      }
    }
  }

  private void handleLdsResponseForServer(DiscoveryResponseData ldsResponse) {
    // Unpack Listener messages.
    Listener requestedListener = null;
    logger.log(XdsLogLevel.DEBUG, "Listener count: {0}", ldsResponse.getResourcesList().size());
    try {
      for (com.google.protobuf.Any res : ldsResponse.getResourcesList()) {
        if (res.getTypeUrl().equals(ADS_TYPE_URL_LDS_V2)) {
          res = res.toBuilder().setTypeUrl(ADS_TYPE_URL_LDS).build();
        }
        Listener listener = res.unpack(Listener.class);
        logger.log(XdsLogLevel.DEBUG, "Found listener {0}", listener.toString());
        if (isRequestedListener(listener)) {
          requestedListener = listener;
          logger.log(XdsLogLevel.DEBUG, "Requested listener found: {0}", listener.getName());
        }
      }
    } catch (InvalidProtocolBufferException e) {
      logger.log(XdsLogLevel.WARNING, "Failed to unpack Listeners in LDS response {0}", e);
      adsStream.sendNackRequest(
          ResourceType.LDS, ImmutableList.<String>of(),
          ldsResponse.getVersionInfo(), "Malformed LDS response: " + e);
      return;
    }
    ListenerUpdate listenerUpdate = null;
    if (requestedListener != null) {
      if (ldsRespTimer != null) {
        ldsRespTimer.cancel();
        ldsRespTimer = null;
      }
      try {
        listenerUpdate = ListenerUpdate.newBuilder()
            .setListener(EnvoyServerProtoData.Listener.fromEnvoyProtoListener(requestedListener))
            .build();
      } catch (InvalidProtocolBufferException e) {
        logger.log(XdsLogLevel.WARNING, "Failed to unpack Listener in LDS response {0}", e);
        adsStream.sendNackRequest(
            ResourceType.LDS, ImmutableList.<String>of(),
            ldsResponse.getVersionInfo(), "Malformed LDS response: " + e);
        return;
      }
    } else {
      if (ldsRespTimer == null) {
        listenerWatcher.onResourceDoesNotExist(":" + listenerPort);
      }
    }
    adsStream.sendAckRequest(ResourceType.LDS, ImmutableList.<String>of(),
        ldsResponse.getVersionInfo());
    if (listenerUpdate != null) {
      listenerWatcher.onListenerChanged(listenerUpdate);
    }
  }

  private boolean isRequestedListener(Listener listener) {
    // TODO(sanjaypujare): check listener.getName() once we know what xDS server returns
    return isAddressMatching(listener.getAddress())
        && hasMatchingFilter(listener.getFilterChainsList());
  }

  private boolean isAddressMatching(Address address) {
    // TODO(sanjaypujare): check IP address once we know xDS server will include it
    return address.hasSocketAddress()
        && (address.getSocketAddress().getPortValue() == listenerPort);
  }

  private boolean hasMatchingFilter(List<FilterChain> filterChainsList) {
    // TODO(sanjaypujare): if myIp to be checked against filterChainMatch.getPrefixRangesList()
    for (FilterChain filterChain : filterChainsList) {
      FilterChainMatch filterChainMatch = filterChain.getFilterChainMatch();

      if (listenerPort == filterChainMatch.getDestinationPort().getValue()) {
        return true;
      }
    }
    return false;
  }

  /**
   * Handles CDS response, which contains a list of Cluster messages with information for a logical
   * cluster. The response is NACKed if messages for requested resources contain invalid
   * information for gRPC's usage. Otherwise, an ACK request is sent to management server.
   * Response data for requested clusters is cached locally, in case of new cluster watchers
   * interested in the same clusters are added later.
   */
  private void handleCdsResponse(DiscoveryResponseData cdsResponse) {
    adsStream.cdsRespNonce = cdsResponse.getNonce();

    // Unpack Cluster messages.
    List<Cluster> clusters = new ArrayList<>(cdsResponse.getResourcesList().size());
    List<String> clusterNames = new ArrayList<>(cdsResponse.getResourcesList().size());
    try {
      for (com.google.protobuf.Any res : cdsResponse.getResourcesList()) {
        if (res.getTypeUrl().equals(ADS_TYPE_URL_CDS_V2)) {
          res = res.toBuilder().setTypeUrl(ADS_TYPE_URL_CDS).build();
        }
        Cluster cluster = res.unpack(Cluster.class);
        clusters.add(cluster);
        clusterNames.add(cluster.getName());
      }
    } catch (InvalidProtocolBufferException e) {
      logger.log(XdsLogLevel.WARNING, "Failed to unpack Clusters in CDS response {0}", e);
      adsStream.sendNackRequest(
          ResourceType.CDS, cdsResourceSubscribers.keySet(),
          cdsResponse.getVersionInfo(), "Malformed CDS response: " + e);
      return;
    }
    logger.log(XdsLogLevel.INFO, "Received CDS response for resources: {0}", clusterNames);

    String errorMessage = null;
    // Cluster information update for requested clusters received in this CDS response.
    Map<String, CdsUpdate> cdsUpdates = new HashMap<>();
    // CDS responses represents the state of the world, EDS services not referenced by
    // Clusters are those no longer exist.
    Set<String> edsServices = new HashSet<>();
    for (Cluster cluster : clusters) {
      String clusterName = cluster.getName();
      // Skip information for clusters not requested.
      // Management server is required to always send newly requested resources, even if they
      // may have been sent previously (proactively). Thus, client does not need to cache
      // unrequested resources.
      if (!cdsResourceSubscribers.containsKey(clusterName)) {
        continue;
      }
      CdsUpdate.Builder updateBuilder = CdsUpdate.newBuilder();
      updateBuilder.setClusterName(clusterName);
      // The type field must be set to EDS.
      if (!cluster.getType().equals(DiscoveryType.EDS)) {
        errorMessage = "Cluster " + clusterName + " : only EDS discovery type is supported "
            + "in gRPC.";
        break;
      }
      // In the eds_cluster_config field, the eds_config field must be set to indicate to
      // use EDS (must be set to use ADS).
      EdsClusterConfig edsClusterConfig = cluster.getEdsClusterConfig();
      if (!edsClusterConfig.getEdsConfig().hasAds()) {
        errorMessage = "Cluster " + clusterName + " : field eds_cluster_config must be set to "
            + "indicate to use EDS over ADS.";
        break;
      }
      // If the service_name field is set, that value will be used for the EDS request.
      if (!edsClusterConfig.getServiceName().isEmpty()) {
        updateBuilder.setEdsServiceName(edsClusterConfig.getServiceName());
        edsServices.add(edsClusterConfig.getServiceName());
      } else {
        edsServices.add(clusterName);
      }
      // The lb_policy field must be set to ROUND_ROBIN.
      if (!cluster.getLbPolicy().equals(LbPolicy.ROUND_ROBIN)) {
        errorMessage = "Cluster " + clusterName + " : only round robin load balancing policy is "
            + "supported in gRPC.";
        break;
      }
      updateBuilder.setLbPolicy("round_robin");
      // If the lrs_server field is set, it must have its self field set, in which case the
      // client should use LRS for load reporting. Otherwise (the lrs_server field is not set),
      // LRS load reporting will be disabled.
      if (cluster.hasLrsServer()) {
        if (!cluster.getLrsServer().hasSelf()) {
          errorMessage = "Cluster " + clusterName + " : only support enabling LRS for the same "
              + "management server.";
          break;
        }
        updateBuilder.setLrsServerName("");
      }
      try {
        EnvoyServerProtoData.UpstreamTlsContext upstreamTlsContext =
            getTlsContextFromCluster(cluster);
        if (upstreamTlsContext != null && upstreamTlsContext.getCommonTlsContext() != null) {
          updateBuilder.setUpstreamTlsContext(upstreamTlsContext);
        }
      } catch (InvalidProtocolBufferException e) {
        errorMessage = "Cluster " + clusterName + " : " + e.getMessage();
        break;
      }
      cdsUpdates.put(clusterName, updateBuilder.build());
    }
    if (errorMessage != null) {
      adsStream.sendNackRequest(
          ResourceType.CDS,
          cdsResourceSubscribers.keySet(),
          cdsResponse.getVersionInfo(),
          errorMessage);
      return;
    }
    adsStream.sendAckRequest(ResourceType.CDS, cdsResourceSubscribers.keySet(),
        cdsResponse.getVersionInfo());

    for (String resource : cdsResourceSubscribers.keySet()) {
      ResourceSubscriber subscriber = cdsResourceSubscribers.get(resource);
      if (cdsUpdates.containsKey(resource)) {
        subscriber.onData(cdsUpdates.get(resource));
      } else {
        subscriber.onAbsent();
      }
    }
    for (String resource : edsResourceSubscribers.keySet()) {
      ResourceSubscriber subscriber = edsResourceSubscribers.get(resource);
      if (!edsServices.contains(resource)) {
        subscriber.onAbsent();
      }
    }
  }

  @Nullable
  private static EnvoyServerProtoData.UpstreamTlsContext getTlsContextFromCluster(Cluster cluster)
      throws InvalidProtocolBufferException {
    if (cluster.hasTransportSocket() && "tls".equals(cluster.getTransportSocket().getName())) {
      Any any = cluster.getTransportSocket().getTypedConfig();
      return EnvoyServerProtoData.UpstreamTlsContext.fromEnvoyProtoUpstreamTlsContext(
          any.unpack(UpstreamTlsContext.class));
    }
    return null;
  }

  /**
   * Handles EDS response, which contains a list of ClusterLoadAssignment messages with
   * endpoint load balancing information for each cluster. The response is NACKed if messages
   * for requested resources contain invalid information for gRPC's usage. Otherwise,
   * an ACK request is sent to management server. Response data for requested clusters is
   * cached locally, in case of new endpoint watchers interested in the same clusters
   * are added later.
   */
  private void handleEdsResponse(DiscoveryResponseData edsResponse) {
    // Unpack ClusterLoadAssignment messages.
    List<ClusterLoadAssignment> clusterLoadAssignments =
        new ArrayList<>(edsResponse.getResourcesList().size());
    List<String> claNames = new ArrayList<>(edsResponse.getResourcesList().size());
    try {
      for (com.google.protobuf.Any res : edsResponse.getResourcesList()) {
        if (res.getTypeUrl().equals(ADS_TYPE_URL_EDS_V2)) {
          res = res.toBuilder().setTypeUrl(ADS_TYPE_URL_EDS).build();
        }
        ClusterLoadAssignment assignment = res.unpack(ClusterLoadAssignment.class);
        clusterLoadAssignments.add(assignment);
        claNames.add(assignment.getClusterName());
      }
    } catch (InvalidProtocolBufferException e) {
      logger.log(
          XdsLogLevel.WARNING, "Failed to unpack ClusterLoadAssignments in EDS response {0}", e);
      adsStream.sendNackRequest(
          ResourceType.EDS, edsResourceSubscribers.keySet(),
          edsResponse.getVersionInfo(), "Malformed EDS response: " + e);
      return;
    }
    logger.log(XdsLogLevel.INFO, "Received EDS response for resources: {0}", claNames);

    String errorMessage = null;
    // Endpoint information updates for requested clusters received in this EDS response.
    Map<String, EdsUpdate> edsUpdates = new HashMap<>();
    // Walk through each ClusterLoadAssignment message. If any of them for requested clusters
    // contain invalid information for gRPC's load balancing usage, the whole response is rejected.
    for (ClusterLoadAssignment assignment : clusterLoadAssignments) {
      String clusterName = assignment.getClusterName();
      // Skip information for clusters not requested.
      // Management server is required to always send newly requested resources, even if they
      // may have been sent previously (proactively). Thus, client does not need to cache
      // unrequested resources.
      if (!edsResourceSubscribers.containsKey(clusterName)) {
        continue;
      }
      EdsUpdate.Builder updateBuilder = EdsUpdate.newBuilder();
      updateBuilder.setClusterName(clusterName);
      Set<Integer> priorities = new HashSet<>();
      int maxPriority = -1;
      for (io.envoyproxy.envoy.config.endpoint.v3.LocalityLbEndpoints localityLbEndpoints
          : assignment.getEndpointsList()) {
        // Filter out localities without or with 0 weight.
        if (!localityLbEndpoints.hasLoadBalancingWeight()
            || localityLbEndpoints.getLoadBalancingWeight().getValue() < 1) {
          continue;
        }
        int localityPriority = localityLbEndpoints.getPriority();
        if (localityPriority < 0) {
          errorMessage =
              "ClusterLoadAssignment " + clusterName + " : locality with negative priority.";
          break;
        }
        maxPriority = Math.max(maxPriority, localityPriority);
        priorities.add(localityPriority);
        // The endpoint field of each lb_endpoints must be set.
        // Inside of it: the address field must be set.
        for (LbEndpoint lbEndpoint : localityLbEndpoints.getLbEndpointsList()) {
          if (!lbEndpoint.getEndpoint().hasAddress()) {
            errorMessage = "ClusterLoadAssignment " + clusterName + " : endpoint with no address.";
            break;
          }
        }
        if (errorMessage != null) {
          break;
        }
        // Note endpoints with health status other than UNHEALTHY and UNKNOWN are still
        // handed over to watching parties. It is watching parties' responsibility to
        // filter out unhealthy endpoints. See EnvoyProtoData.LbEndpoint#isHealthy().
        updateBuilder.addLocalityLbEndpoints(
            Locality.fromEnvoyProtoLocality(localityLbEndpoints.getLocality()),
            LocalityLbEndpoints.fromEnvoyProtoLocalityLbEndpoints(localityLbEndpoints));
      }
      if (errorMessage != null) {
        break;
      }
      if (priorities.size() != maxPriority + 1) {
        errorMessage = "ClusterLoadAssignment " + clusterName + " : sparse priorities.";
        break;
      }
      for (ClusterLoadAssignment.Policy.DropOverload dropOverload
          : assignment.getPolicy().getDropOverloadsList()) {
        updateBuilder.addDropPolicy(DropOverload.fromEnvoyProtoDropOverload(dropOverload));
      }
      EdsUpdate update = updateBuilder.build();
      edsUpdates.put(clusterName, update);
    }
    if (errorMessage != null) {
      adsStream.sendNackRequest(
          ResourceType.EDS,
          edsResourceSubscribers.keySet(),
          edsResponse.getVersionInfo(),
          errorMessage);
      return;
    }
    adsStream.sendAckRequest(ResourceType.EDS, edsResourceSubscribers.keySet(),
        edsResponse.getVersionInfo());

    for (String resource : edsResourceSubscribers.keySet()) {
      ResourceSubscriber subscriber = edsResourceSubscribers.get(resource);
      if (edsUpdates.containsKey(resource)) {
        subscriber.onData(edsUpdates.get(resource));
      }
    }
  }

  private void adjustResourceSubscription(ResourceType type, Collection<String> resources) {
    if (rpcRetryTimer != null && rpcRetryTimer.isPending()) {
      // Currently in retry backoff.
      return;
    }
    if (adsStream == null) {
      startRpcStream();
    }
    adsStream.sendXdsRequest(type, resources);
  }

  @VisibleForTesting
  final class RpcRetryTask implements Runnable {
    @Override
    public void run() {
      startRpcStream();
      if (listenerWatcher != null) {
        adsStream.sendXdsRequest(ResourceType.LDS, ImmutableList.<String>of());
        ldsRespTimer =
            syncContext
                .schedule(
                    new ListenerResourceFetchTimeoutTask(":" + listenerPort),
                    INITIAL_RESOURCE_FETCH_TIMEOUT_SEC, TimeUnit.SECONDS, timeService);
      }
      if (!ldsResourceSubscribers.isEmpty()) {
        adsStream.sendXdsRequest(ResourceType.LDS, ldsResourceSubscribers.keySet());
        for (ResourceSubscriber subscriber : ldsResourceSubscribers.values()) {
          subscriber.restartTimer();
        }
      }
      if (!rdsResourceSubscribers.isEmpty()) {
        adsStream.sendXdsRequest(ResourceType.RDS, rdsResourceSubscribers.keySet());
        for (ResourceSubscriber subscriber : rdsResourceSubscribers.values()) {
          subscriber.restartTimer();
        }
      }
      if (!cdsResourceSubscribers.isEmpty()) {
        adsStream.sendXdsRequest(ResourceType.CDS, cdsResourceSubscribers.keySet());
        for (ResourceSubscriber subscriber : cdsResourceSubscribers.values()) {
          subscriber.restartTimer();
        }
      }
      if (!edsResourceSubscribers.isEmpty()) {
        adsStream.sendXdsRequest(ResourceType.EDS, edsResourceSubscribers.keySet());
        for (ResourceSubscriber subscriber : edsResourceSubscribers.values()) {
          subscriber.restartTimer();
        }
      }
    }
  }

  @VisibleForTesting
  enum ResourceType {
    UNKNOWN, LDS, RDS, CDS, EDS;

    @VisibleForTesting
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

    private String typeUrlV2() {
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

  /**
   * Tracks a single subscribed resource.
   */
  private final class ResourceSubscriber {
    private final ResourceType type;
    private final String resource;
    private final Set<ResourceWatcher> watchers = new HashSet<>();
    private ResourceUpdate data;
    private boolean absent;
    private ScheduledHandle respTimer;

    ResourceSubscriber(ResourceType type, String resource) {
      this.type = type;
      this.resource = resource;
      if (rpcRetryTimer != null && rpcRetryTimer.isPending()) {
        return;
      }
      restartTimer();
    }

    void addWatcher(ResourceWatcher watcher) {
      checkArgument(!watchers.contains(watcher), "watcher %s already registered", watcher);
      watchers.add(watcher);
      if (data != null) {
        notifyWatcher(watcher, data);
      } else if (absent) {
        watcher.onResourceDoesNotExist(resource);
      }
    }

    void removeWatcher(ResourceWatcher watcher) {
      checkArgument(watchers.contains(watcher), "watcher %s not registered", watcher);
      watchers.remove(watcher);
    }

    void restartTimer() {
      class ResourceNotFound implements Runnable {
        @Override
        public void run() {
          logger.log(XdsLogLevel.INFO, "{0} resource {1} initial fetch timeout", type, resource);
          respTimer = null;
          onAbsent();
        }

        @Override
        public String toString() {
          return type + this.getClass().getSimpleName();
        }
      }

      respTimer = syncContext.schedule(
          new ResourceNotFound(), INITIAL_RESOURCE_FETCH_TIMEOUT_SEC, TimeUnit.SECONDS,
          timeService);
    }

    void stopTimer() {
      if (respTimer != null && respTimer.isPending()) {
        respTimer.cancel();
        respTimer = null;
      }
    }

    boolean isWatched() {
      return !watchers.isEmpty();
    }

    void onData(ResourceUpdate data) {
      if (respTimer != null && respTimer.isPending()) {
        respTimer.cancel();
        respTimer = null;
      }
      ResourceUpdate oldData = this.data;
      this.data = data;
      absent = false;
      if (!Objects.equals(oldData, data)) {
        for (ResourceWatcher watcher : watchers) {
          notifyWatcher(watcher, data);
        }
      }
    }

    void onAbsent() {
      if (respTimer != null && respTimer.isPending()) {  // too early to conclude absence
        return;
      }
      logger.log(XdsLogLevel.INFO, "Conclude {0} resource {1} not exist", type, resource);
      if (!absent) {
        data = null;
        absent = true;
        for (ResourceWatcher watcher : watchers) {
          watcher.onResourceDoesNotExist(resource);
        }
      }
    }

    void onError(Status error) {
      if (respTimer != null && respTimer.isPending()) {
        respTimer.cancel();
        respTimer = null;
      }
      for (ResourceWatcher watcher : watchers) {
        watcher.onError(error);
      }
    }

    private void notifyWatcher(ResourceWatcher watcher, ResourceUpdate update) {
      switch (type) {
        case LDS:
          ((LdsResourceWatcher) watcher).onChanged((LdsUpdate) update);
          break;
        case RDS:
          ((RdsResourceWatcher) watcher).onChanged((RdsUpdate) update);
          break;
        case CDS:
          ((CdsResourceWatcher) watcher).onChanged((CdsUpdate) update);
          break;
        case EDS:
          ((EdsResourceWatcher) watcher).onChanged((EdsUpdate) update);
          break;
        case UNKNOWN:
        default:
          throw new AssertionError("should never be here");
      }
    }
  }

  private static final class DiscoveryRequestData {
    private final ResourceType resourceType;
    private final Collection<String> resourceNames;
    private final String versionInfo;
    private final String responseNonce;
    private final Node node;
    @Nullable
    private final com.google.rpc.Status errorDetail;

    DiscoveryRequestData(
        ResourceType resourceType, Collection<String> resourceNames, String versionInfo,
        String responseNonce, Node node, @Nullable com.google.rpc.Status errorDetail) {
      this.resourceType = resourceType;
      this.resourceNames = resourceNames;
      this.versionInfo = versionInfo;
      this.responseNonce = responseNonce;
      this.node = node;
      this.errorDetail = errorDetail;
    }

    DiscoveryRequest toEnvoyProto() {
      DiscoveryRequest.Builder builder =
          DiscoveryRequest.newBuilder()
              .setVersionInfo(versionInfo)
              .setNode(node.toEnvoyProtoNode())
              .addAllResourceNames(resourceNames)
              .setTypeUrl(resourceType.typeUrl())
              .setResponseNonce(responseNonce);
      if (errorDetail != null) {
        builder.setErrorDetail(errorDetail);
      }
      return builder.build();
    }

    io.envoyproxy.envoy.api.v2.DiscoveryRequest toEnvoyProtoV2() {
      io.envoyproxy.envoy.api.v2.DiscoveryRequest.Builder builder =
          io.envoyproxy.envoy.api.v2.DiscoveryRequest.newBuilder()
              .setVersionInfo(versionInfo)
              .setNode(node.toEnvoyProtoNodeV2())
              .addAllResourceNames(resourceNames)
              .setTypeUrl(resourceType.typeUrlV2())
              .setResponseNonce(responseNonce);
      if (errorDetail != null) {
        builder.setErrorDetail(errorDetail);
      }
      return builder.build();
    }
  }

  private static final class DiscoveryResponseData {
    private final ResourceType resourceType;
    private final List<Any> resources;
    private final String versionInfo;
    private final String nonce;

    DiscoveryResponseData(
        ResourceType resourceType, List<Any> resources, String versionInfo, String nonce) {
      this.resourceType = resourceType;
      this.resources = resources;
      this.versionInfo = versionInfo;
      this.nonce = nonce;
    }

    ResourceType getResourceType() {
      return resourceType;
    }

    List<Any> getResourcesList() {
      return resources;
    }

    String getVersionInfo() {
      return versionInfo;
    }

    String getNonce() {
      return nonce;
    }

    static DiscoveryResponseData fromEnvoyProto(DiscoveryResponse proto) {
      return new DiscoveryResponseData(
          ResourceType.fromTypeUrl(proto.getTypeUrl()), proto.getResourcesList(),
          proto.getVersionInfo(), proto.getNonce());
    }

    static DiscoveryResponseData fromEnvoyProtoV2(
        io.envoyproxy.envoy.api.v2.DiscoveryResponse proto) {
      return new DiscoveryResponseData(
          ResourceType.fromTypeUrl(proto.getTypeUrl()), proto.getResourcesList(),
          proto.getVersionInfo(), proto.getNonce());
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

    abstract void sendDiscoveryRequest(DiscoveryRequestData request);

    abstract void sendError(Exception error);

    // Must run in syncContext.
    final void handleResponse(DiscoveryResponseData response) {
      if (closed) {
        return;
      }
      responseReceived = true;
      String respNonce = response.getNonce();
      // Nonce in each response is echoed back in the following ACK/NACK request. It is
      // used for management server to identify which response the client is ACKing/NACking.
      // To avoid confusion, client-initiated requests will always use the nonce in
      // most recently received responses of each resource type.
      ResourceType resourceType = response.getResourceType();
      switch (resourceType) {
        case LDS:
          ldsRespNonce = respNonce;
          handleLdsResponse(response);
          break;
        case RDS:
          rdsRespNonce = respNonce;
          handleRdsResponse(response);
          break;
        case CDS:
          cdsRespNonce = respNonce;
          handleCdsResponse(response);
          break;
        case EDS:
          edsRespNonce = respNonce;
          handleEdsResponse(response);
          break;
        case UNKNOWN:
          logger.log(
              XdsLogLevel.WARNING,
              "Received an unknown type of DiscoveryResponse\n{0}",
              respNonce);
          break;
        default:
          throw new AssertionError("Missing case in enum switch: " + resourceType);
      }
    }

    // Must run in syncContext.
    final void handleRpcError(Throwable t) {
      handleStreamClosed(Status.fromThrowable(t));
    }

    // Must run in syncContext.
    final void handleRpcCompleted() {
      handleStreamClosed(Status.UNAVAILABLE.withDescription("Closed by server"));
    }

    private void handleStreamClosed(Status error) {
      checkArgument(!error.isOk(), "unexpected OK status");
      if (closed) {
        return;
      }
      logger.log(
          XdsLogLevel.ERROR,
          "ADS stream closed with status {0}: {1}. Cause: {2}",
          error.getCode(), error.getDescription(), error.getCause());
      closed = true;
      if (listenerWatcher != null) {
        listenerWatcher.onError(error);
      }
      for (ResourceSubscriber subscriber : ldsResourceSubscribers.values()) {
        subscriber.onError(error);
      }
      for (ResourceSubscriber subscriber : rdsResourceSubscribers.values()) {
        subscriber.onError(error);
      }
      for (ResourceSubscriber subscriber : cdsResourceSubscribers.values()) {
        subscriber.onError(error);
      }
      for (ResourceSubscriber subscriber : edsResourceSubscribers.values()) {
        subscriber.onError(error);
      }
      cleanUp();
      cleanUpResourceTimers();
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
      logger.log(XdsLogLevel.INFO, "Retry ADS stream in {0} ns", delayNanos);
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
      sendError(error);
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
    private void sendXdsRequest(ResourceType resourceType, Collection<String> resourceNames) {
      String version;
      String nonce;
      switch (resourceType) {
        case LDS:
          version = ldsVersion;
          nonce = ldsRespNonce;
          logger.log(XdsLogLevel.INFO, "Sending LDS request for resources: {0}", resourceNames);
          break;
        case RDS:
          version = rdsVersion;
          nonce = rdsRespNonce;
          logger.log(XdsLogLevel.INFO, "Sending RDS request for resources: {0}", resourceNames);
          break;
        case CDS:
          version = cdsVersion;
          nonce = cdsRespNonce;
          logger.log(XdsLogLevel.INFO, "Sending CDS request for resources: {0}", resourceNames);
          break;
        case EDS:
          version = edsVersion;
          nonce = edsRespNonce;
          logger.log(XdsLogLevel.INFO, "Sending EDS request for resources: {0}", resourceNames);
          break;
        case UNKNOWN:
        default:
          throw new AssertionError("Unknown or missing case in enum switch: " + resourceType);
      }
      DiscoveryRequestData request =
          new DiscoveryRequestData(resourceType, resourceNames, version, nonce, node, null);
      sendDiscoveryRequest(request);
    }

    /**
     * Sends a DiscoveryRequest with the given information as an ACK. Updates the latest accepted
     * version for the corresponding resource type.
     */
    private void sendAckRequest(ResourceType resourceType, Collection<String> resourceNames,
        String versionInfo) {
      String nonce;
      switch (resourceType) {
        case LDS:
          ldsVersion = versionInfo;
          nonce = ldsRespNonce;
          logger.log(XdsLogLevel.WARNING, "Sending ACK for LDS update, version: {0}", versionInfo);
          break;
        case RDS:
          rdsVersion = versionInfo;
          nonce = rdsRespNonce;
          logger.log(XdsLogLevel.WARNING, "Sending ACK for RDS update, version: {0}", versionInfo);
          break;
        case CDS:
          cdsVersion = versionInfo;
          nonce = cdsRespNonce;
          logger.log(XdsLogLevel.WARNING, "Sending ACK for CDS update, version: {0}", versionInfo);
          break;
        case EDS:
          edsVersion = versionInfo;
          nonce = edsRespNonce;
          logger.log(XdsLogLevel.WARNING, "Sending ACK for EDS update, version: {0}", versionInfo);
          break;
        case UNKNOWN:
        default:
          throw new AssertionError("Unknown or missing case in enum switch: " + resourceType);
      }
      DiscoveryRequestData request =
          new DiscoveryRequestData(resourceType, resourceNames, versionInfo, nonce, node, null);
      sendDiscoveryRequest(request);
    }

    /**
     * Sends a DiscoveryRequest with the given information as an NACK. NACK takes the previous
     * accepted version.
     */
    private void sendNackRequest(ResourceType resourceType, Collection<String> resourceNames,
        String rejectVersion, String message) {
      String versionInfo;
      String nonce;
      switch (resourceType) {
        case LDS:
          versionInfo = ldsVersion;
          nonce = ldsRespNonce;
          logger.log(
              XdsLogLevel.WARNING,
              "Sending NACK for LDS update, version: {0}, reason: {1}",
              rejectVersion,
              message);
          break;
        case RDS:
          versionInfo = rdsVersion;
          nonce = rdsRespNonce;
          logger.log(
              XdsLogLevel.WARNING,
              "Sending NACK for RDS update, version: {0}, reason: {1}",
              rejectVersion,
              message);
          break;
        case CDS:
          versionInfo = cdsVersion;
          nonce = cdsRespNonce;
          logger.log(
              XdsLogLevel.WARNING,
              "Sending NACK for CDS update, version: {0}, reason: {1}",
              rejectVersion,
              message);
          break;
        case EDS:
          versionInfo = edsVersion;
          nonce = edsRespNonce;
          logger.log(
              XdsLogLevel.WARNING,
              "Sending NACK for EDS update, version: {0}, reason: {1}",
              rejectVersion,
              message);
          break;
        case UNKNOWN:
        default:
          throw new AssertionError("Unknown or missing case in enum switch: " + resourceType);
      }
      com.google.rpc.Status error = com.google.rpc.Status.newBuilder()
          .setCode(Code.INVALID_ARGUMENT_VALUE)
          .setMessage(message)
          .build();
      DiscoveryRequestData request =
          new DiscoveryRequestData(resourceType, resourceNames, versionInfo, nonce, node, error);
      sendDiscoveryRequest(request);
    }
  }

  private final class AdsStreamV2 extends AbstractAdsStream {
    private final io.envoyproxy.envoy.service.discovery.v2.AggregatedDiscoveryServiceGrpc
        .AggregatedDiscoveryServiceStub stubV2;
    private StreamObserver<io.envoyproxy.envoy.api.v2.DiscoveryRequest> requestWriterV2;

    AdsStreamV2() {
      stubV2 = io.envoyproxy.envoy.service.discovery.v2.AggregatedDiscoveryServiceGrpc.newStub(
          xdsChannel.getManagedChannel());
    }

    @Override
    void start() {
      StreamObserver<io.envoyproxy.envoy.api.v2.DiscoveryResponse> responseReaderV2 =
          new StreamObserver<io.envoyproxy.envoy.api.v2.DiscoveryResponse>() {
            @Override
            public void onNext(final io.envoyproxy.envoy.api.v2.DiscoveryResponse response) {
              syncContext.execute(new Runnable() {
                @Override
                public void run() {
                  if (logger.isLoggable(XdsLogLevel.DEBUG)) {
                    logger.log(XdsLogLevel.DEBUG, "Received {0} response:\n{1}",
                        ResourceType.fromTypeUrl(response.getTypeUrl()),
                        respPrinter.print(response));
                  }
                  DiscoveryResponseData responseData =
                      DiscoveryResponseData.fromEnvoyProtoV2(response);
                  handleResponse(responseData);
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
      requestWriterV2 = stubV2.withWaitForReady().streamAggregatedResources(responseReaderV2);
    }

    @Override
    void sendDiscoveryRequest(DiscoveryRequestData request) {
      checkState(requestWriterV2 != null, "ADS stream has not been started");
      io.envoyproxy.envoy.api.v2.DiscoveryRequest requestProto =
          request.toEnvoyProtoV2();
      requestWriterV2.onNext(requestProto);
      logger.log(XdsLogLevel.DEBUG, "Sent DiscoveryRequest\n{0}", requestProto);
    }

    @Override
    void sendError(Exception error) {
      requestWriterV2.onError(error);
    }
  }

  // AdsStream V3
  private final class AdsStream extends AbstractAdsStream {
    private final AggregatedDiscoveryServiceGrpc.AggregatedDiscoveryServiceStub stub;
    private StreamObserver<DiscoveryRequest> requestWriter;

    AdsStream() {
      stub = AggregatedDiscoveryServiceGrpc.newStub(xdsChannel.getManagedChannel());
    }

    @Override
    void start() {
      StreamObserver<DiscoveryResponse> responseReader = new StreamObserver<DiscoveryResponse>() {
        @Override
        public void onNext(final DiscoveryResponse response) {
          syncContext.execute(new Runnable() {
            @Override
            public void run() {
              if (logger.isLoggable(XdsLogLevel.DEBUG)) {
                logger.log(XdsLogLevel.DEBUG, "Received {0} response:\n{1}",
                    ResourceType.fromTypeUrl(response.getTypeUrl()), respPrinter.print(response));
              }
              DiscoveryResponseData responseData = DiscoveryResponseData.fromEnvoyProto(response);
              handleResponse(responseData);
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
    void sendDiscoveryRequest(DiscoveryRequestData request) {
      checkState(requestWriter != null, "ADS stream has not been started");
      DiscoveryRequest requestProto = request.toEnvoyProto();
      requestWriter.onNext(requestProto);
      logger.log(XdsLogLevel.DEBUG, "Sent DiscoveryRequest\n{0}", requestProto);
    }

    @Override
    void sendError(Exception error) {
      requestWriter.onError(error);
    }
  }

  // TODO(chengyuanzhang): delete me.
  @VisibleForTesting
  final class ListenerResourceFetchTimeoutTask implements Runnable {
    private String resourceName;

    ListenerResourceFetchTimeoutTask(String resourceName) {
      this.resourceName = resourceName;
    }

    @Override
    public void run() {
      logger.log(
          XdsLogLevel.WARNING,
          "Did not receive resource info {0} after {1} seconds, conclude it absent",
          resourceName, INITIAL_RESOURCE_FETCH_TIMEOUT_SEC);
      ldsRespTimer = null;
      listenerWatcher.onResourceDoesNotExist(resourceName);
    }
  }

  /**
   * Convert protobuf message to human readable String format. Useful for protobuf messages
   * containing {@link com.google.protobuf.Any} fields.
   */
  @VisibleForTesting
  static final class MessagePrinter {
    private final JsonFormat.Printer printer;

    @VisibleForTesting
    MessagePrinter() {
      TypeRegistry registry =
          TypeRegistry.newBuilder()
              .add(Listener.getDescriptor())
              .add(io.envoyproxy.envoy.api.v2.Listener.getDescriptor())
              .add(HttpConnectionManager.getDescriptor())
              .add(
                  io.envoyproxy.envoy.config.filter.network.http_connection_manager.v2
                      .HttpConnectionManager.getDescriptor())
              .add(RouteConfiguration.getDescriptor())
              .add(io.envoyproxy.envoy.api.v2.RouteConfiguration.getDescriptor())
              .add(Cluster.getDescriptor())
              .add(io.envoyproxy.envoy.api.v2.Cluster.getDescriptor())
              .add(ClusterLoadAssignment.getDescriptor())
              .add(io.envoyproxy.envoy.api.v2.ClusterLoadAssignment.getDescriptor())
              .build();
      printer = JsonFormat.printer().usingTypeRegistry(registry);
    }

    @VisibleForTesting
    String print(MessageOrBuilder message) {
      String res;
      try {
        res = printer.print(message);
      } catch (InvalidProtocolBufferException e) {
        res = message + " (failed to pretty-print: " + e + ")";
      }
      return res;
    }
  }
}
