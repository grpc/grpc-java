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
import com.google.protobuf.util.JsonFormat;
import com.google.rpc.Code;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.DiscoveryType;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.EdsClusterConfig;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.LbPolicy;
import io.envoyproxy.envoy.config.core.v3.Address;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.endpoint.v3.LbEndpoint;
import io.envoyproxy.envoy.config.listener.v3.FilterChain;
import io.envoyproxy.envoy.config.listener.v3.FilterChainMatch;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.config.route.v3.Route;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.envoyproxy.envoy.config.route.v3.VirtualHost;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.Rds;
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
import io.grpc.xds.EnvoyServerProtoData.UpstreamTlsContext;
import io.grpc.xds.LoadStatsManager.LoadStatsStore;
import io.grpc.xds.XdsLogger.XdsLogLevel;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

final class XdsClientImpl extends XdsClient {

  // Longest time to wait, since the subscription to some resource, for concluding its absence.
  @VisibleForTesting
  static final int INITIAL_RESOURCE_FETCH_TIMEOUT_SEC = 15;

  @VisibleForTesting
  static final String ADS_TYPE_URL_LDS_V2 = "type.googleapis.com/envoy.api.v2.Listener";
  @VisibleForTesting
  static final String ADS_TYPE_URL_LDS =
      "type.googleapis.com/envoy.config.listener.v3.Listener";
  @VisibleForTesting
  static final String ADS_TYPE_URL_RDS_V2 =
      "type.googleapis.com/envoy.api.v2.RouteConfiguration";
  @VisibleForTesting
  static final String ADS_TYPE_URL_RDS =
      "type.googleapis.com/envoy.config.route.v3.RouteConfiguration";
  private static final String TYPE_URL_HTTP_CONNECTION_MANAGER_V2 =
      "type.googleapis.com/envoy.config.filter.network.http_connection_manager.v2"
          + ".HttpConnectionManager";
  private static final String TYPE_URL_HTTP_CONNECTION_MANAGER =
      "type.googleapis.com/envoy.extensions.filters.network.http_connection_manager.v3"
          + ".HttpConnectionManager";
  @VisibleForTesting
  static final String ADS_TYPE_URL_CDS_V2 = "type.googleapis.com/envoy.api.v2.Cluster";
  @VisibleForTesting
  static final String ADS_TYPE_URL_CDS =
      "type.googleapis.com/envoy.config.cluster.v3.Cluster";
  @VisibleForTesting
  static final String ADS_TYPE_URL_EDS_V2 =
      "type.googleapis.com/envoy.api.v2.ClusterLoadAssignment";
  @VisibleForTesting
  static final String ADS_TYPE_URL_EDS =
      "type.googleapis.com/envoy.config.endpoint.v3.ClusterLoadAssignment";

  private final MessagePrinter respPrinter = new MessagePrinter();

  private final InternalLogId logId;
  private final XdsLogger logger;
  // Name of the target server this gRPC client is trying to talk to.
  private final String targetName;
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

  // Timer for concluding the currently requesting LDS resource not found.
  @Nullable
  private ScheduledHandle ldsRespTimer;

  // Timer for concluding the currently requesting RDS resource not found.
  @Nullable
  private ScheduledHandle rdsRespTimer;

  @Nullable
  private AbstractAdsStream adsStream;
  @Nullable
  private BackoffPolicy retryBackoffPolicy;
  @Nullable
  private ScheduledHandle rpcRetryTimer;
  @Nullable
  private LoadReportClient lrsClient;
  private int loadReportCount;  // number of clusters enabling load reporting

  // Following fields are set only after the ConfigWatcher registered. Once set, they should
  // never change. Only a ConfigWatcher or ListenerWatcher can be registered.
  @Nullable
  private ConfigWatcher configWatcher;
  // The "xds:" URI (including port suffix if present) that the gRPC client targets for.
  @Nullable
  private String ldsResourceName;

  // only a ConfigWatcher or ListenerWatcher can be registered.
  @Nullable
  private ListenerWatcher listenerWatcher;
  private int listenerPort = -1;

  XdsClientImpl(
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
    logId = InternalLogId.allocate("xds-client", targetName);
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
    if (rdsRespTimer != null) {
      rdsRespTimer.cancel();
      rdsRespTimer = null;
    }
    for (ResourceSubscriber subscriber : cdsResourceSubscribers.values()) {
      subscriber.stopTimer();
    }
    for (ResourceSubscriber subscriber : edsResourceSubscribers.values()) {
      subscriber.stopTimer();
    }
  }

  @Override
  void watchConfigData(String targetAuthority, ConfigWatcher watcher) {
    checkState(configWatcher == null, "watcher for %s already registered", targetAuthority);
    checkState(listenerWatcher == null, "ListenerWatcher already registered");
    ldsResourceName = checkNotNull(targetAuthority, "targetAuthority");
    configWatcher = checkNotNull(watcher, "watcher");
    logger.log(XdsLogLevel.INFO, "Started watching config {0}", ldsResourceName);
    if (rpcRetryTimer != null && rpcRetryTimer.isPending()) {
      // Currently in retry backoff.
      return;
    }
    if (adsStream == null) {
      startRpcStream();
    }
    adsStream.sendXdsRequest(ResourceType.LDS, ImmutableList.of(ldsResourceName));
    ldsRespTimer =
        syncContext
            .schedule(
                new LdsResourceFetchTimeoutTask(ldsResourceName),
                INITIAL_RESOURCE_FETCH_TIMEOUT_SEC, TimeUnit.SECONDS, timeService);
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
    checkState(configWatcher == null,
        "ListenerWatcher cannot be set when ConfigWatcher set");
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

  /**
   * Calls handleLdsResponseForListener or handleLdsResponseForConfigUpdate based on which watcher
   * was set.
   */
  private void handleLdsResponse(DiscoveryResponseData ldsResponse) {
    checkState((configWatcher != null) != (listenerWatcher != null),
        "No LDS request was ever sent. Management server is doing something wrong");
    if (listenerWatcher != null) {
      handleLdsResponseForListener(ldsResponse);
    } else {
      handleLdsResponseForConfigUpdate(ldsResponse);
    }
  }

  /**
   * Handles LDS response to find the HttpConnectionManager message for the requested resource name.
   * Proceed with the resolved RouteConfiguration in HttpConnectionManager message of the requested
   * listener, if exists, to find the VirtualHost configuration for the "xds:" URI
   * (with the port, if any, stripped off). Or sends an RDS request if configured for dynamic
   * resolution. The response is NACKed if contains invalid data for gRPC's usage. Otherwise, an
   * ACK request is sent to management server.
   */
  private void handleLdsResponseForConfigUpdate(DiscoveryResponseData ldsResponse) {
    checkState(ldsResourceName != null && configWatcher != null,
        "LDS request for ConfigWatcher was never sent!");

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
          ResourceType.LDS, ImmutableList.of(ldsResourceName),
          ldsResponse.getVersionInfo(), "Malformed LDS response: " + e);
      return;
    }
    logger.log(XdsLogLevel.INFO, "Received LDS response for resources: {0}", listenerNames);

    // Unpack HttpConnectionManager messages.
    HttpConnectionManager requestedHttpConnManager = null;
    try {
      for (Listener listener : listeners) {
        Any apiListener = listener.getApiListener().getApiListener();
        if (apiListener.getTypeUrl().equals(TYPE_URL_HTTP_CONNECTION_MANAGER_V2)) {
          apiListener =
              apiListener.toBuilder().setTypeUrl(TYPE_URL_HTTP_CONNECTION_MANAGER).build();
        }
        HttpConnectionManager hm = apiListener.unpack(HttpConnectionManager.class);
        if (listener.getName().equals(ldsResourceName)) {
          requestedHttpConnManager = hm;
        }
      }
    } catch (InvalidProtocolBufferException e) {
      logger.log(
          XdsLogLevel.WARNING,
          "Failed to unpack HttpConnectionManagers in Listeners of LDS response {0}", e);
      adsStream.sendNackRequest(
          ResourceType.LDS, ImmutableList.of(ldsResourceName),
          ldsResponse.getVersionInfo(), "Malformed LDS response: " + e);
      return;
    }

    String errorMessage = null;
    // Routes found in the in-lined RouteConfiguration, if exists.
    List<EnvoyProtoData.Route> routes = null;
    // RouteConfiguration name to be used as the resource name for RDS request, if exists.
    String rdsRouteConfigName = null;
    // Process the requested Listener if exists, either extract cluster information from in-lined
    // RouteConfiguration message or send an RDS request for dynamic resolution.
    if (requestedHttpConnManager != null) {
      logger.log(XdsLogLevel.DEBUG, "Found http connection manager");
      // The HttpConnectionManager message must either provide the RouteConfiguration directly
      // in-line or tell the client to use RDS to obtain it.
      // TODO(chengyuanzhang): if both route_config and rds are set, it should be either invalid
      //  data or one supersedes the other. TBD.
      if (requestedHttpConnManager.hasRouteConfig()) {
        RouteConfiguration rc = requestedHttpConnManager.getRouteConfig();
        try {
          routes = findRoutesInRouteConfig(rc, ldsResourceName);
        } catch (InvalidProtoDataException e) {
          errorMessage =
              "Listener " + ldsResourceName + " : cannot find a valid cluster name in any "
                  + "virtual hosts domains matching: " + ldsResourceName
                  + " with the reason : " + e.getMessage();
        }
      } else if (requestedHttpConnManager.hasRds()) {
        Rds rds = requestedHttpConnManager.getRds();
        if (!rds.getConfigSource().hasAds()) {
          errorMessage =
              "Listener " + ldsResourceName + " : for using RDS, config_source must be "
                  + "set to use ADS.";
        } else {
          rdsRouteConfigName = rds.getRouteConfigName();
        }
      } else {
        errorMessage = "Listener " + ldsResourceName + " : HttpConnectionManager message must "
            + "either provide the RouteConfiguration directly in-line or tell the client to "
            + "use RDS to obtain it.";
      }
    }

    if (errorMessage != null) {
      adsStream.sendNackRequest(
          ResourceType.LDS, ImmutableList.of(ldsResourceName),
          ldsResponse.getVersionInfo(), errorMessage);
      return;
    }
    adsStream.sendAckRequest(ResourceType.LDS, ImmutableList.of(ldsResourceName),
        ldsResponse.getVersionInfo());

    if (routes != null || rdsRouteConfigName != null) {
      if (ldsRespTimer != null) {
        ldsRespTimer.cancel();
        ldsRespTimer = null;
      }
    }
    if (routes != null) {
      // Found  routes in the in-lined RouteConfiguration.
      logger.log(
          XdsLogLevel.DEBUG,
          "Found routes (inlined in route config): {0}", routes);
      ConfigUpdate configUpdate = ConfigUpdate.newBuilder().addRoutes(routes).build();
      configWatcher.onConfigChanged(configUpdate);
    } else if (rdsRouteConfigName != null) {
      // Send an RDS request if the resource to request has changed.
      if (!rdsRouteConfigName.equals(adsStream.rdsResourceName)) {
        logger.log(
            XdsLogLevel.INFO,
            "Use RDS to dynamically resolve route config, resource name: {0}", rdsRouteConfigName);
        adsStream.sendXdsRequest(ResourceType.RDS, ImmutableList.of(rdsRouteConfigName));
        // Cancel the timer for fetching the previous RDS resource.
        if (rdsRespTimer != null) {
          rdsRespTimer.cancel();
        }
        rdsRespTimer =
            syncContext
                .schedule(
                    new RdsResourceFetchTimeoutTask(rdsRouteConfigName),
                    INITIAL_RESOURCE_FETCH_TIMEOUT_SEC, TimeUnit.SECONDS, timeService);
      }
    } else {
      // The requested Listener is removed by management server.
      if (ldsRespTimer == null) {
        configWatcher.onResourceDoesNotExist(ldsResourceName);
      }
    }
  }

  private void handleLdsResponseForListener(DiscoveryResponseData ldsResponse) {
    checkState(ldsResourceName == null && listenerPort > 0 && listenerWatcher != null,
        "LDS request for ListenerWatcher was never sent!");

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
   * Handles RDS response to find the RouteConfiguration message for the requested resource name.
   * Proceed with the resolved RouteConfiguration if exists to find the VirtualHost configuration
   * for the "xds:" URI (with the port, if any, stripped off). The response is NACKed if contains
   * invalid data for gRPC's usage. Otherwise, an ACK request is sent to management server.
   */
  private void handleRdsResponse(DiscoveryResponseData rdsResponse) {
    checkState(adsStream.rdsResourceName != null,
        "Never requested for RDS resources, management server is doing something wrong");

    // Unpack RouteConfiguration messages.
    List<String> routeConfigNames = new ArrayList<>(rdsResponse.getResourcesList().size());
    RouteConfiguration requestedRouteConfig = null;
    try {
      for (com.google.protobuf.Any res : rdsResponse.getResourcesList()) {
        if (res.getTypeUrl().equals(ADS_TYPE_URL_RDS_V2)) {
          res = res.toBuilder().setTypeUrl(ADS_TYPE_URL_RDS).build();
        }
        RouteConfiguration rc = res.unpack(RouteConfiguration.class);
        routeConfigNames.add(rc.getName());
        if (rc.getName().equals(adsStream.rdsResourceName)) {
          requestedRouteConfig = rc;
        }
      }
    } catch (InvalidProtocolBufferException e) {
      logger.log(
          XdsLogLevel.WARNING, "Failed to unpack RouteConfiguration in RDS response {0}", e);
      adsStream.sendNackRequest(
          ResourceType.RDS, ImmutableList.of(adsStream.rdsResourceName),
          rdsResponse.getVersionInfo(), "Malformed RDS response: " + e);
      return;
    }
    logger.log(
        XdsLogLevel.INFO, "Received RDS response for resources: {0}", routeConfigNames);

    // Resolved cluster name for the requested resource, if exists.
    List<EnvoyProtoData.Route> routes = null;
    if (requestedRouteConfig != null) {
      try {
        routes = findRoutesInRouteConfig(requestedRouteConfig, ldsResourceName);
      } catch (InvalidProtoDataException e) {
        String errorDetail = e.getMessage();
        adsStream.sendNackRequest(
            ResourceType.RDS, ImmutableList.of(adsStream.rdsResourceName),
            rdsResponse.getVersionInfo(),
            "RouteConfiguration " + requestedRouteConfig.getName() + ": cannot find a "
                + "valid cluster name in any virtual hosts with domains matching: "
                + ldsResourceName
                + " with the reason: " + errorDetail);
        return;
      }
    }

    adsStream.sendAckRequest(ResourceType.RDS, ImmutableList.of(adsStream.rdsResourceName),
        rdsResponse.getVersionInfo());

    // Notify the ConfigWatcher if this RDS response contains the most recently requested
    // RDS resource.
    if (routes != null) {
      if (rdsRespTimer != null) {
        rdsRespTimer.cancel();
        rdsRespTimer = null;
      }
      logger.log(XdsLogLevel.DEBUG, "Found routes: {0}", routes);
      ConfigUpdate configUpdate  =
          ConfigUpdate.newBuilder().addRoutes(routes).build();
      configWatcher.onConfigChanged(configUpdate);
    }
  }

  /**
   * Processes a RouteConfiguration message to find the routes that requests for the given host will
   * be routed to.
   *
   * @throws InvalidProtoDataException if the message contains invalid data.
   */
  private static List<EnvoyProtoData.Route> findRoutesInRouteConfig(
      RouteConfiguration config, String hostName) throws InvalidProtoDataException {
    VirtualHost targetVirtualHost = findVirtualHostForHostName(config, hostName);
    if (targetVirtualHost == null) {
      throw new InvalidProtoDataException("Unable to find virtual host for " + hostName);
    }

    // Note we would consider upstream cluster not found if the virtual host is not configured
    // correctly for gRPC, even if there exist other virtual hosts with (lower priority)
    // matching domains.
    return populateRoutesInVirtualHost(targetVirtualHost);
  }

  @VisibleForTesting
  static List<EnvoyProtoData.Route> populateRoutesInVirtualHost(VirtualHost virtualHost)
      throws InvalidProtoDataException {
    List<EnvoyProtoData.Route> routes = new ArrayList<>();
    List<Route> routesProto = virtualHost.getRoutesList();
    for (Route routeProto : routesProto) {
      StructOrError<EnvoyProtoData.Route> route =
          EnvoyProtoData.Route.fromEnvoyProtoRoute(routeProto);
      if (route == null) {
        continue;
      } else if (route.getErrorDetail() != null) {
        throw new InvalidProtoDataException(
            "Virtual host [" + virtualHost.getName() + "] contains invalid route : "
                + route.getErrorDetail());
      }
      routes.add(route.getStruct());
    }
    if (routes.isEmpty()) {
      throw new InvalidProtoDataException(
          "Virtual host [" + virtualHost.getName() + "] contains no usable route");
    }
    return Collections.unmodifiableList(routes);
  }

  @VisibleForTesting
  @Nullable
  static VirtualHost findVirtualHostForHostName(
      RouteConfiguration config, String hostName) {
    List<VirtualHost> virtualHosts = config.getVirtualHostsList();
    // Domain search order:
    //  1. Exact domain names: ``www.foo.com``.
    //  2. Suffix domain wildcards: ``*.foo.com`` or ``*-bar.foo.com``.
    //  3. Prefix domain wildcards: ``foo.*`` or ``foo-*``.
    //  4. Special wildcard ``*`` matching any domain.
    //
    //  The longest wildcards match first.
    //  Assuming only a single virtual host in the entire route configuration can match
    //  on ``*`` and a domain must be unique across all virtual hosts.
    int matchingLen = -1; // longest length of wildcard pattern that matches host name
    boolean exactMatchFound = false;  // true if a virtual host with exactly matched domain found
    VirtualHost targetVirtualHost = null;  // target VirtualHost with longest matched domain
    for (VirtualHost vHost : virtualHosts) {
      for (String domain : vHost.getDomainsList()) {
        boolean selected = false;
        if (matchHostName(hostName, domain)) { // matching
          if (!domain.contains("*")) { // exact matching
            exactMatchFound = true;
            targetVirtualHost = vHost;
            break;
          } else if (domain.length() > matchingLen) { // longer matching pattern
            selected = true;
          } else if (domain.length() == matchingLen && domain.startsWith("*")) { // suffix matching
            selected = true;
          }
        }
        if (selected) {
          matchingLen = domain.length();
          targetVirtualHost = vHost;
        }
      }
      if (exactMatchFound) {
        break;
      }
    }
    return targetVirtualHost;
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
        UpstreamTlsContext upstreamTlsContext = getTlsContextFromCluster(cluster);
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
      if (!edsServices.contains(resource)) {
        ResourceSubscriber subscriber = edsResourceSubscribers.get(resource);
        subscriber.onAbsent();
      }
    }
  }

  @Nullable
  private static UpstreamTlsContext getTlsContextFromCluster(Cluster cluster)
      throws InvalidProtocolBufferException {
    if (cluster.hasTransportSocket() && "tls".equals(cluster.getTransportSocket().getName())) {
      Any any = cluster.getTransportSocket().getTypedConfig();
      return UpstreamTlsContext.fromEnvoyProtoUpstreamTlsContext(
          io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.UpstreamTlsContext.parseFrom(
              any.getValue()));
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
      if (configWatcher != null) {
        adsStream.sendXdsRequest(ResourceType.LDS, ImmutableList.of(ldsResourceName));
        ldsRespTimer =
            syncContext
                .schedule(
                    new LdsResourceFetchTimeoutTask(ldsResourceName),
                    INITIAL_RESOURCE_FETCH_TIMEOUT_SEC, TimeUnit.SECONDS, timeService);
      }
      if (listenerWatcher != null) {
        adsStream.sendXdsRequest(ResourceType.LDS, ImmutableList.<String>of());
        ldsRespTimer =
            syncContext
                .schedule(
                    new ListenerResourceFetchTimeoutTask(":" + listenerPort),
                    INITIAL_RESOURCE_FETCH_TIMEOUT_SEC, TimeUnit.SECONDS, timeService);
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

    private String typeUrl() {
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
    // Resource states:
    // - present: data != null; data is the cached data for the resource
    // - absent: absent == true
    // - unknown: anything else
    // Note absent -> data == null, but not vice versa.
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

    // Most recently requested RDS resource name, which is an intermediate resource name for
    // resolving service config.
    // LDS request always use the same resource name, which is the "xds:" URI.
    // Resource names for EDS requests are always represented by the cluster names that
    // watchers are interested in.
    @Nullable
    private String rdsResourceName;

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
      if (configWatcher != null) {
        configWatcher.onError(error);
      }
      if (listenerWatcher != null) {
        listenerWatcher.onError(error);
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
          checkArgument(
              resourceNames.size() == 1, "RDS request requesting for more than one resource");
          version = rdsVersion;
          nonce = rdsRespNonce;
          rdsResourceName = resourceNames.iterator().next();
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
  private abstract class ResourceFetchTimeoutTask implements Runnable {
    final String resourceName;

    ResourceFetchTimeoutTask(String resourceName) {
      this.resourceName = resourceName;
    }

    @Override
    public void run() {
      logger.log(
          XdsLogLevel.WARNING,
          "Did not receive resource info {0} after {1} seconds, conclude it absent",
          resourceName, INITIAL_RESOURCE_FETCH_TIMEOUT_SEC);
    }
  }

  // TODO(chengyuanzhang): delete me.
  @VisibleForTesting
  final class LdsResourceFetchTimeoutTask extends ResourceFetchTimeoutTask {

    LdsResourceFetchTimeoutTask(String resourceName) {
      super(resourceName);
    }

    @Override
    public void run() {
      super.run();
      ldsRespTimer = null;
      configWatcher.onResourceDoesNotExist(resourceName);
    }
  }

  @VisibleForTesting
  final class ListenerResourceFetchTimeoutTask extends ResourceFetchTimeoutTask {

    ListenerResourceFetchTimeoutTask(String resourceName) {
      super(resourceName);
    }

    @Override
    public void run() {
      super.run();
      ldsRespTimer = null;
      listenerWatcher.onResourceDoesNotExist(resourceName);
    }
  }

  // TODO(chengyuanzhang): delete me.
  @VisibleForTesting
  final class RdsResourceFetchTimeoutTask extends ResourceFetchTimeoutTask {

    RdsResourceFetchTimeoutTask(String resourceName) {
      super(resourceName);
    }

    @Override
    public void run() {
      super.run();
      rdsRespTimer = null;
      configWatcher.onResourceDoesNotExist(resourceName);
    }
  }

  /**
   * Returns {@code true} iff {@code hostName} matches the domain name {@code pattern} with
   * case-insensitive.
   *
   * <p>Wildcard pattern rules:
   * <ol>
   * <li>A single asterisk (*) matches any domain.</li>
   * <li>Asterisk (*) is only permitted in the left-most or the right-most part of the pattern,
   *     but not both.</li>
   * </ol>
   */
  @VisibleForTesting
  static boolean matchHostName(String hostName, String pattern) {
    checkArgument(hostName.length() != 0 && !hostName.startsWith(".") && !hostName.endsWith("."),
        "Invalid host name");
    checkArgument(pattern.length() != 0 && !pattern.startsWith(".") && !pattern.endsWith("."),
        "Invalid pattern/domain name");

    hostName = hostName.toLowerCase(Locale.US);
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

    // At most one asterisk (*) is allowed.
    if (pattern.indexOf('*', index + 1) != -1) {
      return false;
    }

    // Asterisk can only match prefix or suffix.
    if (index != 0 && index != pattern.length() - 1) {
      return false;
    }

    // HostName must be at least as long as the pattern because asterisk has to
    // match one or more characters.
    if (hostName.length() < pattern.length()) {
      return false;
    }

    if (index == 0 && hostName.endsWith(pattern.substring(1))) {
      // Prefix matching fails.
      return true;
    }

    // Pattern matches hostname if suffix matching succeeds.
    return index == pattern.length() - 1
        && hostName.startsWith(pattern.substring(0, pattern.length() - 1));
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
      com.google.protobuf.TypeRegistry registry =
          com.google.protobuf.TypeRegistry.newBuilder()
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

  @VisibleForTesting
  static final class InvalidProtoDataException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private InvalidProtoDataException(String message) {
      super(message, null, false, false);
    }
  }
}
