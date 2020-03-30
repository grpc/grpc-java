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
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.MessageOrBuilder;
import com.google.protobuf.util.JsonFormat;
import com.google.rpc.Code;
import io.envoyproxy.envoy.api.v2.Cluster;
import io.envoyproxy.envoy.api.v2.Cluster.DiscoveryType;
import io.envoyproxy.envoy.api.v2.Cluster.EdsClusterConfig;
import io.envoyproxy.envoy.api.v2.Cluster.LbPolicy;
import io.envoyproxy.envoy.api.v2.ClusterLoadAssignment;
import io.envoyproxy.envoy.api.v2.DiscoveryRequest;
import io.envoyproxy.envoy.api.v2.DiscoveryResponse;
import io.envoyproxy.envoy.api.v2.Listener;
import io.envoyproxy.envoy.api.v2.RouteConfiguration;
import io.envoyproxy.envoy.api.v2.core.Node;
import io.envoyproxy.envoy.api.v2.route.Route;
import io.envoyproxy.envoy.api.v2.route.VirtualHost;
import io.envoyproxy.envoy.config.filter.network.http_connection_manager.v2.HttpConnectionManager;
import io.envoyproxy.envoy.config.filter.network.http_connection_manager.v2.Rds;
import io.envoyproxy.envoy.service.discovery.v2.AggregatedDiscoveryServiceGrpc;
import io.grpc.InternalLogId;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.SynchronizationContext;
import io.grpc.SynchronizationContext.ScheduledHandle;
import io.grpc.internal.BackoffPolicy;
import io.grpc.stub.StreamObserver;
import io.grpc.xds.Bootstrapper.ServerInfo;
import io.grpc.xds.EnvoyProtoData.DropOverload;
import io.grpc.xds.EnvoyProtoData.Locality;
import io.grpc.xds.EnvoyProtoData.LocalityLbEndpoints;
import io.grpc.xds.LoadReportClient.LoadReportCallback;
import io.grpc.xds.XdsLogger.XdsLogLevel;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

final class XdsClientImpl extends XdsClient {

  // Longest time to wait, since the subscription to some resource, for concluding its absence.
  @VisibleForTesting
  static final int INITIAL_RESOURCE_FETCH_TIMEOUT_SEC = 15;

  @VisibleForTesting
  static final String ADS_TYPE_URL_LDS = "type.googleapis.com/envoy.api.v2.Listener";
  @VisibleForTesting
  static final String ADS_TYPE_URL_RDS =
      "type.googleapis.com/envoy.api.v2.RouteConfiguration";
  @VisibleForTesting
  static final String ADS_TYPE_URL_CDS = "type.googleapis.com/envoy.api.v2.Cluster";
  @VisibleForTesting
  static final String ADS_TYPE_URL_EDS =
      "type.googleapis.com/envoy.api.v2.ClusterLoadAssignment";

  private final MessagePrinter respPrinter = new MessagePrinter();

  private final InternalLogId logId;
  private final XdsLogger logger;
  // Name of the target server this gRPC client is trying to talk to.
  private final String targetName;
  private final ManagedChannel channel;
  private final SynchronizationContext syncContext;
  private final ScheduledExecutorService timeService;
  private final BackoffPolicy.Provider backoffPolicyProvider;
  private final Supplier<Stopwatch> stopwatchSupplier;
  private final Stopwatch adsStreamRetryStopwatch;
  // The node identifier to be included in xDS requests. Management server only requires the
  // first request to carry the node identifier on a stream. It should be identical if present
  // more than once.
  private final Node node;

  // Cached data for CDS responses, keyed by cluster names.
  // Optimization: cache ClusterUpdate, which contains only information needed by gRPC, instead
  // of whole Cluster messages to reduce memory usage.
  private final Map<String, ClusterUpdate> clusterNamesToClusterUpdates = new HashMap<>();

  // Cached CDS resources that are known to be absent.
  private final Set<String> absentCdsResources = new HashSet<>();

  // Cached data for EDS responses, keyed by cluster names.
  // CDS responses indicate absence of clusters and EDS responses indicate presence of clusters.
  // Optimization: cache EndpointUpdate, which contains only information needed by gRPC, instead
  // of whole ClusterLoadAssignment messages to reduce memory usage.
  private final Map<String, EndpointUpdate> clusterNamesToEndpointUpdates = new HashMap<>();

  // Cached EDS resources that are known to be absent.
  private final Set<String> absentEdsResources = new HashSet<>();

  // Cluster watchers waiting for cluster information updates. Multiple cluster watchers
  // can watch on information for the same cluster.
  private final Map<String, Set<ClusterWatcher>> clusterWatchers = new HashMap<>();

  // Endpoint watchers waiting for endpoint updates for each cluster. Multiple endpoint
  // watchers can watch endpoints in the same cluster.
  private final Map<String, Set<EndpointWatcher>> endpointWatchers = new HashMap<>();

  // Resource fetch timers are used to conclude absence of resources. Each timer is activated when
  // subscription for the resource starts and disarmed on first update for the resource.

  // Timers for concluding CDS resources not found.
  private final Map<String, ScheduledHandle> cdsRespTimers = new HashMap<>();

  // Timers for concluding EDS resources not found.
  private final Map<String, ScheduledHandle> edsRespTimers = new HashMap<>();

  // Timer for concluding the currently requesting LDS resource not found.
  @Nullable
  private ScheduledHandle ldsRespTimer;

  // Timer for concluding the currently requesting RDS resource not found.
  @Nullable
  private ScheduledHandle rdsRespTimer;

  @Nullable
  private AdsStream adsStream;
  @Nullable
  private BackoffPolicy retryBackoffPolicy;
  @Nullable
  private ScheduledHandle rpcRetryTimer;
  @Nullable
  private LoadReportClient lrsClient;

  // Following fields are set only after the ConfigWatcher registered. Once set, they should
  // never change.
  @Nullable
  private ConfigWatcher configWatcher;
  // The "xds:" URI (including port suffix if present) that the gRPC client targets for.
  @Nullable
  private String ldsResourceName;

  XdsClientImpl(
      String targetName,
      List<ServerInfo> servers,  // list of management servers
      XdsChannelFactory channelFactory,
      Node node,
      SynchronizationContext syncContext,
      ScheduledExecutorService timeService,
      BackoffPolicy.Provider backoffPolicyProvider,
      Supplier<Stopwatch> stopwatchSupplier) {
    this.targetName = checkNotNull(targetName, "targetName");
    this.channel =
        checkNotNull(channelFactory, "channelFactory")
            .createChannel(checkNotNull(servers, "servers"));
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
    channel.shutdown();
    if (adsStream != null) {
      adsStream.close(Status.CANCELLED.withDescription("shutdown").asException());
    }
    cleanUpResources();
    if (lrsClient != null) {
      lrsClient.stopLoadReporting();
      lrsClient = null;
    }
    if (rpcRetryTimer != null) {
      rpcRetryTimer.cancel();
    }
  }

  /**
   * Purge cache for resources and cancel resource fetch timers.
   */
  private void cleanUpResources() {
    clusterNamesToClusterUpdates.clear();
    absentCdsResources.clear();
    clusterNamesToEndpointUpdates.clear();
    absentEdsResources.clear();

    if (ldsRespTimer != null) {
      ldsRespTimer.cancel();
      ldsRespTimer = null;
    }
    if (rdsRespTimer != null) {
      rdsRespTimer.cancel();
      rdsRespTimer = null;
    }
    for (ScheduledHandle handle : cdsRespTimers.values()) {
      handle.cancel();
    }
    cdsRespTimers.clear();
    for (ScheduledHandle handle : edsRespTimers.values()) {
      handle.cancel();
    }
    edsRespTimers.clear();
  }

  @Override
  void watchConfigData(String targetAuthority, ConfigWatcher watcher) {
    checkState(configWatcher == null, "watcher for %s already registered", targetAuthority);
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
    adsStream.sendXdsRequest(ADS_TYPE_URL_LDS, ImmutableList.of(ldsResourceName));
    ldsRespTimer =
        syncContext
            .schedule(
                new LdsResourceFetchTimeoutTask(ldsResourceName),
                INITIAL_RESOURCE_FETCH_TIMEOUT_SEC, TimeUnit.SECONDS, timeService);
  }

  @Override
  void watchClusterData(String clusterName, ClusterWatcher watcher) {
    checkNotNull(clusterName, "clusterName");
    checkNotNull(watcher, "watcher");
    boolean needRequest = false;
    if (!clusterWatchers.containsKey(clusterName)) {
      logger.log(XdsLogLevel.INFO, "Start watching cluster {0}", clusterName);
      needRequest = true;
      clusterWatchers.put(clusterName, new HashSet<ClusterWatcher>());
    }
    Set<ClusterWatcher> watchers = clusterWatchers.get(clusterName);
    checkState(!watchers.contains(watcher), "watcher for %s already registered", clusterName);
    watchers.add(watcher);
    // If local cache contains cluster information to be watched, notify the watcher immediately.
    if (absentCdsResources.contains(clusterName)) {
      logger.log(XdsLogLevel.DEBUG, "Cluster resource {0} is known to be absent", clusterName);
      watcher.onError(
          Status.NOT_FOUND
              .withDescription(
                  "Cluster resource [" + clusterName + "] not found."));
      return;
    }
    if (clusterNamesToClusterUpdates.containsKey(clusterName)) {
      logger.log(XdsLogLevel.DEBUG, "Retrieve cluster info {0} from local cache", clusterName);
      watcher.onClusterChanged(clusterNamesToClusterUpdates.get(clusterName));
      return;
    }

    if (needRequest) {
      if (rpcRetryTimer != null && rpcRetryTimer.isPending()) {
        // Currently in retry backoff.
        return;
      }
      if (adsStream == null) {
        startRpcStream();
      }
      adsStream.sendXdsRequest(ADS_TYPE_URL_CDS, clusterWatchers.keySet());
      ScheduledHandle timeoutHandle =
          syncContext
              .schedule(
                  new CdsResourceFetchTimeoutTask(clusterName),
                  INITIAL_RESOURCE_FETCH_TIMEOUT_SEC, TimeUnit.SECONDS, timeService);
      cdsRespTimers.put(clusterName, timeoutHandle);
    }
  }

  @Override
  void cancelClusterDataWatch(String clusterName, ClusterWatcher watcher) {
    checkNotNull(watcher, "watcher");
    Set<ClusterWatcher> watchers = clusterWatchers.get(clusterName);
    checkState(
        watchers != null && watchers.contains(watcher),
        "watcher for %s was not registered", clusterName);
    watchers.remove(watcher);
    if (watchers.isEmpty()) {
      logger.log(XdsLogLevel.INFO, "Stop watching cluster {0}", clusterName);
      clusterWatchers.remove(clusterName);
      // Remove the corresponding CDS entry.
      absentCdsResources.remove(clusterName);
      clusterNamesToClusterUpdates.remove(clusterName);
      // Cancel and delete response timer waiting for the corresponding resource.
      if (cdsRespTimers.containsKey(clusterName)) {
        cdsRespTimers.get(clusterName).cancel();
        cdsRespTimers.remove(clusterName);
      }
      // No longer interested in this cluster, send an updated CDS request to unsubscribe
      // this resource.
      if (rpcRetryTimer != null && rpcRetryTimer.isPending()) {
        // Currently in retry backoff.
        return;
      }
      checkState(adsStream != null,
          "Severe bug: ADS stream was not created while an endpoint watcher was registered");
      adsStream.sendXdsRequest(ADS_TYPE_URL_CDS, clusterWatchers.keySet());
    }
  }

  @Override
  void watchEndpointData(String clusterName, EndpointWatcher watcher) {
    checkNotNull(watcher, "watcher");
    boolean needRequest = false;
    if (!endpointWatchers.containsKey(clusterName)) {
      logger.log(XdsLogLevel.INFO, "Start watching endpoints in cluster {0}", clusterName);
      needRequest = true;
      endpointWatchers.put(clusterName, new HashSet<EndpointWatcher>());
    }
    Set<EndpointWatcher> watchers = endpointWatchers.get(clusterName);
    checkState(!watchers.contains(watcher), "watcher for %s already registered", clusterName);
    watchers.add(watcher);
    // If local cache contains endpoint information for the cluster to be watched, notify
    // the watcher immediately.
    if (absentEdsResources.contains(clusterName)) {
      logger.log(
          XdsLogLevel.DEBUG,
          "Endpoint resource for cluster {0} is known to be absent.", clusterName);
      watcher.onError(
          Status.NOT_FOUND
              .withDescription(
                  "Endpoint resource for cluster " + clusterName + " not found."));
      return;
    }
    if (clusterNamesToEndpointUpdates.containsKey(clusterName)) {
      logger.log(
          XdsLogLevel.DEBUG,
          "Retrieve endpoints info for cluster {0} from local cache.", clusterName);
      watcher.onEndpointChanged(clusterNamesToEndpointUpdates.get(clusterName));
      return;
    }

    if (needRequest) {
      if (rpcRetryTimer != null && rpcRetryTimer.isPending()) {
        // Currently in retry backoff.
        return;
      }
      if (adsStream == null) {
        startRpcStream();
      }
      adsStream.sendXdsRequest(ADS_TYPE_URL_EDS, endpointWatchers.keySet());
      ScheduledHandle timeoutHandle =
          syncContext
              .schedule(
                  new EdsResourceFetchTimeoutTask(clusterName),
                  INITIAL_RESOURCE_FETCH_TIMEOUT_SEC, TimeUnit.SECONDS, timeService);
      edsRespTimers.put(clusterName, timeoutHandle);
    }
  }

  @Override
  void cancelEndpointDataWatch(String clusterName, EndpointWatcher watcher) {
    checkNotNull(watcher, "watcher");
    Set<EndpointWatcher> watchers = endpointWatchers.get(clusterName);
    checkState(
        watchers != null && watchers.contains(watcher),
        "watcher for %s was not registered", clusterName);
    watchers.remove(watcher);
    if (watchers.isEmpty()) {
      logger.log(XdsLogLevel.INFO, "Stop watching endpoints in cluster {0}", clusterName);
      endpointWatchers.remove(clusterName);
      // Remove the corresponding EDS cache entry.
      absentEdsResources.remove(clusterName);
      clusterNamesToEndpointUpdates.remove(clusterName);
      // Cancel and delete response timer waiting for the corresponding resource.
      if (edsRespTimers.containsKey(clusterName)) {
        edsRespTimers.get(clusterName).cancel();
        edsRespTimers.remove(clusterName);
      }
      // No longer interested in this cluster, send an updated EDS request to unsubscribe
      // this resource.
      if (rpcRetryTimer != null && rpcRetryTimer.isPending()) {
        // Currently in retry backoff.
        return;
      }
      adsStream.sendXdsRequest(ADS_TYPE_URL_EDS, endpointWatchers.keySet());
    }
  }

  @Override
  void reportClientStats(
      String clusterName, @Nullable String clusterServiceName, LoadStatsStore loadStatsStore) {
    if (lrsClient == null) {
      lrsClient =
          new LoadReportClient(
              logId,
              targetName,
              channel,
              node,
              syncContext,
              timeService,
              backoffPolicyProvider,
              stopwatchSupplier);
      lrsClient.startLoadReporting(new LoadReportCallback() {
        @Override
        public void onReportResponse(long reportIntervalNano) {}
      });
    }
    logger.log(
        XdsLogLevel.INFO,
        "Report loads for cluster: {0}, cluster_service: {1}", clusterName, clusterServiceName);
    lrsClient.addLoadStatsStore(clusterName, clusterServiceName, loadStatsStore);
  }

  @Override
  void cancelClientStatsReport(String clusterName, @Nullable String clusterServiceName) {
    checkState(lrsClient != null, "load reporting was never started");
    logger.log(
        XdsLogLevel.INFO,
        "Stop reporting loads for cluster: {0}, cluster_service: {1}",
        clusterName,
        clusterServiceName);
    lrsClient.removeLoadStatsStore(clusterName, clusterServiceName);
    // TODO(chengyuanzhang): can be optimized to stop load reporting if no more loads need
    //  to be reported.
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
    AggregatedDiscoveryServiceGrpc.AggregatedDiscoveryServiceStub stub =
        AggregatedDiscoveryServiceGrpc.newStub(channel);
    adsStream = new AdsStream(stub);
    adsStream.start();
    logger.log(XdsLogLevel.INFO, "ADS stream started");
    adsStreamRetryStopwatch.reset().start();
  }

  /**
   * Handles LDS response to find the HttpConnectionManager message for the requested resource name.
   * Proceed with the resolved RouteConfiguration in HttpConnectionManager message of the requested
   * listener, if exists, to find the VirtualHost configuration for the "xds:" URI
   * (with the port, if any, stripped off). Or sends an RDS request if configured for dynamic
   * resolution. The response is NACKed if contains invalid data for gRPC's usage. Otherwise, an
   * ACK request is sent to management server.
   */
  private void handleLdsResponse(DiscoveryResponse ldsResponse) {
    checkState(ldsResourceName != null && configWatcher != null,
        "No LDS request was ever sent. Management server is doing something wrong");
    if (logger.isLoggable(XdsLogLevel.DEBUG)) {
      logger.log(
          XdsLogLevel.DEBUG, "Received  LDS response:\n{0}", respPrinter.print(ldsResponse));
    }

    // Unpack Listener messages.
    List<Listener> listeners = new ArrayList<>(ldsResponse.getResourcesCount());
    List<String> listenerNames = new ArrayList<>(ldsResponse.getResourcesCount());
    try {
      for (com.google.protobuf.Any res : ldsResponse.getResourcesList()) {
        Listener listener = res.unpack(Listener.class);
        listeners.add(listener);
        listenerNames.add(listener.getName());
      }
    } catch (InvalidProtocolBufferException e) {
      logger.log(XdsLogLevel.WARNING, "Failed to unpack Listeners in LDS response {0}", e);
      adsStream.sendNackRequest(
          ADS_TYPE_URL_LDS, ImmutableList.of(ldsResourceName),
          ldsResponse.getVersionInfo(), "Malformed LDS response: " + e);
      return;
    }
    logger.log(XdsLogLevel.INFO, "Received LDS response for resources: {0}", listenerNames);

    // Unpack HttpConnectionManager messages.
    HttpConnectionManager requestedHttpConnManager = null;
    try {
      for (Listener listener : listeners) {
        HttpConnectionManager hm =
            listener.getApiListener().getApiListener().unpack(HttpConnectionManager.class);
        if (listener.getName().equals(ldsResourceName)) {
          requestedHttpConnManager = hm;
        }
      }
    } catch (InvalidProtocolBufferException e) {
      logger.log(
          XdsLogLevel.WARNING,
          "Failed to unpack HttpConnectionManagers in Listeners of LDS response {0}", e);
      adsStream.sendNackRequest(
          ADS_TYPE_URL_LDS, ImmutableList.of(ldsResourceName),
          ldsResponse.getVersionInfo(), "Malformed LDS response: " + e);
      return;
    }

    String errorMessage = null;
    // Field clusterName found in the in-lined RouteConfiguration, if exists.
    String clusterName = null;
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
        clusterName = findClusterNameInRouteConfig(rc, ldsResourceName);
        if (clusterName == null) {
          errorMessage =
              "Listener " + ldsResourceName + " : cannot find a valid cluster name in any "
                  + "virtual hosts inside RouteConfiguration with domains matching: "
                  + ldsResourceName;
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
          ADS_TYPE_URL_LDS, ImmutableList.of(ldsResourceName),
          ldsResponse.getVersionInfo(), errorMessage);
      return;
    }
    adsStream.sendAckRequest(ADS_TYPE_URL_LDS, ImmutableList.of(ldsResourceName),
        ldsResponse.getVersionInfo());

    if (clusterName != null || rdsRouteConfigName != null) {
      if (ldsRespTimer != null) {
        ldsRespTimer.cancel();
        ldsRespTimer = null;
      }
    }
    if (clusterName != null) {
      // Found clusterName in the in-lined RouteConfiguration.
      logger.log(
          XdsLogLevel.INFO,
          "Found cluster name (inlined in route config): {0}", clusterName);
      ConfigUpdate configUpdate = ConfigUpdate.newBuilder().setClusterName(clusterName).build();
      configWatcher.onConfigChanged(configUpdate);
    } else if (rdsRouteConfigName != null) {
      // Send an RDS request if the resource to request has changed.
      if (!rdsRouteConfigName.equals(adsStream.rdsResourceName)) {
        logger.log(
            XdsLogLevel.INFO,
            "Use RDS to dynamically resolve route config, resource name: {0}", rdsRouteConfigName);
        adsStream.sendXdsRequest(ADS_TYPE_URL_RDS, ImmutableList.of(rdsRouteConfigName));
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
        configWatcher.onError(
            Status.NOT_FOUND.withDescription(
                "Listener resource for listener " + ldsResourceName + " does not exist"));
      }

    }
  }

  /**
   * Handles RDS response to find the RouteConfiguration message for the requested resource name.
   * Proceed with the resolved RouteConfiguration if exists to find the VirtualHost configuration
   * for the "xds:" URI (with the port, if any, stripped off). The response is NACKed if contains
   * invalid data for gRPC's usage. Otherwise, an ACK request is sent to management server.
   */
  private void handleRdsResponse(DiscoveryResponse rdsResponse) {
    if (logger.isLoggable(XdsLogLevel.DEBUG)) {
      logger.log(XdsLogLevel.DEBUG, "Received RDS response:\n{0}", respPrinter.print(rdsResponse));
    }
    checkState(adsStream.rdsResourceName != null,
        "Never requested for RDS resources, management server is doing something wrong");

    // Unpack RouteConfiguration messages.
    List<String> routeConfigNames = new ArrayList<>(rdsResponse.getResourcesCount());
    RouteConfiguration requestedRouteConfig = null;
    try {
      for (com.google.protobuf.Any res : rdsResponse.getResourcesList()) {
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
          ADS_TYPE_URL_RDS, ImmutableList.of(adsStream.rdsResourceName),
          rdsResponse.getVersionInfo(), "Malformed RDS response: " + e);
      return;
    }
    logger.log(
        XdsLogLevel.INFO, "Received RDS response for resources: {0}", routeConfigNames);

    // Resolved cluster name for the requested resource, if exists.
    String clusterName = null;
    if (requestedRouteConfig != null) {
      clusterName = findClusterNameInRouteConfig(requestedRouteConfig, ldsResourceName);
      if (clusterName == null) {
        adsStream.sendNackRequest(
            ADS_TYPE_URL_RDS, ImmutableList.of(adsStream.rdsResourceName),
            rdsResponse.getVersionInfo(),
            "RouteConfiguration " + requestedRouteConfig.getName() + ": cannot find a "
                + "valid cluster name in any virtual hosts with domains matching: "
                + ldsResourceName);
        return;
      }
    }

    adsStream.sendAckRequest(ADS_TYPE_URL_RDS, ImmutableList.of(adsStream.rdsResourceName),
        rdsResponse.getVersionInfo());

    // Notify the ConfigWatcher if this RDS response contains the most recently requested
    // RDS resource.
    if (clusterName != null) {
      if (rdsRespTimer != null) {
        rdsRespTimer.cancel();
        rdsRespTimer = null;
      }
      logger.log(XdsLogLevel.INFO, "Found cluster name: {0}", clusterName);
      ConfigUpdate configUpdate = ConfigUpdate.newBuilder().setClusterName(clusterName).build();
      configWatcher.onConfigChanged(configUpdate);
    }
  }

  /**
   * Processes a RouteConfiguration message to find the name of upstream cluster that requests
   * for the given host will be routed to. Returns the clusterName if found.
   * Otherwise, returns {@code null}.
   */
  @VisibleForTesting
  @Nullable
  static String findClusterNameInRouteConfig(RouteConfiguration config, String hostName) {
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

    // Proceed with the virtual host that has longest wildcard matched domain name with the
    // hostname in original "xds:" URI.
    // Note we would consider upstream cluster not found if the virtual host is not configured
    // correctly for gRPC, even if there exist other virtual hosts with (lower priority)
    // matching domains.
    if (targetVirtualHost != null) {
      // The client will look only at the last route in the list (the default route),
      // whose match field must contain a prefix field whose value is empty string
      // and whose route field must be set.
      List<Route> routes = targetVirtualHost.getRoutesList();
      if (!routes.isEmpty()) {
        Route route = routes.get(routes.size() - 1);
        if (route.getMatch().getPrefix().isEmpty()) {
          if (route.hasRoute()) {
            return route.getRoute().getCluster();
          }
        }
      }
    }
    return null;
  }

  /**
   * Handles CDS response, which contains a list of Cluster messages with information for a logical
   * cluster. The response is NACKed if messages for requested resources contain invalid
   * information for gRPC's usage. Otherwise, an ACK request is sent to management server.
   * Response data for requested clusters is cached locally, in case of new cluster watchers
   * interested in the same clusters are added later.
   */
  private void handleCdsResponse(DiscoveryResponse cdsResponse) {
    if (logger.isLoggable(XdsLogLevel.DEBUG)) {
      logger.log(XdsLogLevel.DEBUG, "Received CDS response:\n{0}", respPrinter.print(cdsResponse));
    }
    adsStream.cdsRespNonce = cdsResponse.getNonce();

    // Unpack Cluster messages.
    List<Cluster> clusters = new ArrayList<>(cdsResponse.getResourcesCount());
    List<String> clusterNames = new ArrayList<>(cdsResponse.getResourcesCount());
    try {
      for (com.google.protobuf.Any res : cdsResponse.getResourcesList()) {
        Cluster cluster = res.unpack(Cluster.class);
        clusters.add(cluster);
        clusterNames.add(cluster.getName());
      }
    } catch (InvalidProtocolBufferException e) {
      logger.log(XdsLogLevel.WARNING, "Failed to unpack Clusters in CDS response {0}", e);
      adsStream.sendNackRequest(
          ADS_TYPE_URL_CDS, clusterWatchers.keySet(),
          cdsResponse.getVersionInfo(), "Malformed CDS response: " + e);
      return;
    }
    logger.log(XdsLogLevel.INFO, "Received CDS response for resources: {0}", clusterNames);

    String errorMessage = null;
    // Cluster information update for requested clusters received in this CDS response.
    Map<String, ClusterUpdate> clusterUpdates = new HashMap<>();
    // CDS responses represents the state of the world, EDS services not referenced by
    // Clusters are those no longer exist.
    Set<String> edsServices = new HashSet<>();
    for (Cluster cluster : clusters) {
      String clusterName = cluster.getName();
      // Skip information for clusters not requested.
      // Management server is required to always send newly requested resources, even if they
      // may have been sent previously (proactively). Thus, client does not need to cache
      // unrequested resources.
      if (!clusterWatchers.containsKey(clusterName)) {
        continue;
      }
      ClusterUpdate.Builder updateBuilder = ClusterUpdate.newBuilder();
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
      if (cluster.hasTlsContext()) {
        updateBuilder.setUpstreamTlsContext(cluster.getTlsContext());
      }
      clusterUpdates.put(clusterName, updateBuilder.build());
    }
    if (errorMessage != null) {
      adsStream.sendNackRequest(
          ADS_TYPE_URL_CDS, clusterWatchers.keySet(), cdsResponse.getVersionInfo(), errorMessage);
      return;
    }
    adsStream.sendAckRequest(ADS_TYPE_URL_CDS, clusterWatchers.keySet(),
        cdsResponse.getVersionInfo());

    // Update local CDS cache with data in this response.
    absentCdsResources.removeAll(clusterUpdates.keySet());
    for (String clusterName : clusterNamesToClusterUpdates.keySet()) {
      if (!clusterUpdates.containsKey(clusterName)) {
        // Some previously existing resource no longer exists.
        absentCdsResources.add(clusterName);
      }
    }
    clusterNamesToClusterUpdates.clear();
    clusterNamesToClusterUpdates.putAll(clusterUpdates);

    // Remove EDS cache entries for ClusterLoadAssignments not referenced by this CDS response.
    for (String clusterName : clusterNamesToEndpointUpdates.keySet()) {
      if (!edsServices.contains(clusterName)) {
        absentEdsResources.add(clusterName);
        // Notify EDS resource removal to watchers.
        if (endpointWatchers.containsKey(clusterName)) {
          Set<EndpointWatcher> watchers = endpointWatchers.get(clusterName);
          for (EndpointWatcher watcher : watchers) {
            watcher.onError(
                Status.NOT_FOUND
                    .withDescription(
                        "Endpoint resource for cluster " + clusterName + " is deleted."));
          }
        }
      }
    }
    clusterNamesToEndpointUpdates.keySet().retainAll(edsServices);

    for (String clusterName : clusterUpdates.keySet()) {
      if (cdsRespTimers.containsKey(clusterName)) {
        cdsRespTimers.get(clusterName).cancel();
        cdsRespTimers.remove(clusterName);
      }
    }

    // Notify watchers if clusters interested in present in this CDS response.
    for (Map.Entry<String, Set<ClusterWatcher>> entry : clusterWatchers.entrySet()) {
      String clusterName = entry.getKey();
      if (clusterUpdates.containsKey(clusterName)) {
        ClusterUpdate clusterUpdate = clusterUpdates.get(clusterName);
        for (ClusterWatcher watcher : entry.getValue()) {
          watcher.onClusterChanged(clusterUpdate);
        }
      } else if (!cdsRespTimers.containsKey(clusterName)) {
        // Update for previously present resource being removed.
        for (ClusterWatcher watcher : entry.getValue()) {
          watcher.onError(
              Status.NOT_FOUND
                  .withDescription("Cluster resource " + clusterName + " not found."));
        }
      }
    }
  }

  /**
   * Handles EDS response, which contains a list of ClusterLoadAssignment messages with
   * endpoint load balancing information for each cluster. The response is NACKed if messages
   * for requested resources contain invalid information for gRPC's usage. Otherwise,
   * an ACK request is sent to management server. Response data for requested clusters is
   * cached locally, in case of new endpoint watchers interested in the same clusters
   * are added later.
   */
  private void handleEdsResponse(DiscoveryResponse edsResponse) {
    if (logger.isLoggable(XdsLogLevel.DEBUG)) {
      logger.log(XdsLogLevel.DEBUG, "Received EDS response:\n{0}", respPrinter.print(edsResponse));
    }

    // Unpack ClusterLoadAssignment messages.
    List<ClusterLoadAssignment> clusterLoadAssignments =
        new ArrayList<>(edsResponse.getResourcesCount());
    List<String> claNames = new ArrayList<>(edsResponse.getResourcesCount());
    try {
      for (com.google.protobuf.Any res : edsResponse.getResourcesList()) {
        ClusterLoadAssignment assignment = res.unpack(ClusterLoadAssignment.class);
        clusterLoadAssignments.add(assignment);
        claNames.add(assignment.getClusterName());
      }
    } catch (InvalidProtocolBufferException e) {
      logger.log(
          XdsLogLevel.WARNING, "Failed to unpack ClusterLoadAssignments in EDS response {0}", e);
      adsStream.sendNackRequest(
          ADS_TYPE_URL_EDS, endpointWatchers.keySet(),
          edsResponse.getVersionInfo(), "Malformed EDS response: " + e);
      return;
    }
    logger.log(XdsLogLevel.INFO, "Received EDS response for resources: {0}", claNames);

    String errorMessage = null;
    // Endpoint information updates for requested clusters received in this EDS response.
    Map<String, EndpointUpdate> endpointUpdates = new HashMap<>();
    // Walk through each ClusterLoadAssignment message. If any of them for requested clusters
    // contain invalid information for gRPC's load balancing usage, the whole response is rejected.
    for (ClusterLoadAssignment assignment : clusterLoadAssignments) {
      String clusterName = assignment.getClusterName();
      // Skip information for clusters not requested.
      // Management server is required to always send newly requested resources, even if they
      // may have been sent previously (proactively). Thus, client does not need to cache
      // unrequested resources.
      if (!endpointWatchers.containsKey(clusterName)) {
        continue;
      }
      EndpointUpdate.Builder updateBuilder = EndpointUpdate.newBuilder();
      updateBuilder.setClusterName(clusterName);
      if (assignment.getEndpointsCount() == 0) {
        errorMessage = "ClusterLoadAssignment " + clusterName + " : no locality endpoints.";
        break;
      }
      Set<Integer> priorities = new HashSet<>();
      int maxPriority = -1;
      for (io.envoyproxy.envoy.api.v2.endpoint.LocalityLbEndpoints localityLbEndpoints
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
        for (io.envoyproxy.envoy.api.v2.endpoint.LbEndpoint lbEndpoint
            : localityLbEndpoints.getLbEndpointsList()) {
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
      for (io.envoyproxy.envoy.api.v2.ClusterLoadAssignment.Policy.DropOverload dropOverload
          : assignment.getPolicy().getDropOverloadsList()) {
        updateBuilder.addDropPolicy(DropOverload.fromEnvoyProtoDropOverload(dropOverload));
      }
      EndpointUpdate update = updateBuilder.build();
      endpointUpdates.put(clusterName, update);
    }
    if (errorMessage != null) {
      adsStream.sendNackRequest(
          ADS_TYPE_URL_EDS, endpointWatchers.keySet(), edsResponse.getVersionInfo(), errorMessage);
      return;
    }
    adsStream.sendAckRequest(ADS_TYPE_URL_EDS, endpointWatchers.keySet(),
        edsResponse.getVersionInfo());

    // Update local EDS cache by inserting updated endpoint information.
    clusterNamesToEndpointUpdates.putAll(endpointUpdates);
    absentEdsResources.removeAll(endpointUpdates.keySet());

    // Notify watchers waiting for updates of endpoint information received in this EDS response.
    for (Map.Entry<String, EndpointUpdate> entry : endpointUpdates.entrySet()) {
      String clusterName = entry.getKey();
      // Cancel and delete response timeout timer.
      if (edsRespTimers.containsKey(clusterName)) {
        edsRespTimers.get(clusterName).cancel();
        edsRespTimers.remove(clusterName);
      }
      if (endpointWatchers.containsKey(clusterName)) {
        for (EndpointWatcher watcher : endpointWatchers.get(clusterName)) {
          watcher.onEndpointChanged(entry.getValue());
        }
      }
    }
  }

  @VisibleForTesting
  final class RpcRetryTask implements Runnable {
    @Override
    public void run() {
      startRpcStream();
      if (configWatcher != null) {
        adsStream.sendXdsRequest(ADS_TYPE_URL_LDS, ImmutableList.of(ldsResourceName));
        ldsRespTimer =
            syncContext
                .schedule(
                    new LdsResourceFetchTimeoutTask(ldsResourceName),
                    INITIAL_RESOURCE_FETCH_TIMEOUT_SEC, TimeUnit.SECONDS, timeService);
      }
      if (!clusterWatchers.isEmpty()) {
        adsStream.sendXdsRequest(ADS_TYPE_URL_CDS, clusterWatchers.keySet());
        for (String clusterName : clusterWatchers.keySet()) {
          ScheduledHandle timeoutHandle =
              syncContext
                  .schedule(
                      new CdsResourceFetchTimeoutTask(clusterName),
                      INITIAL_RESOURCE_FETCH_TIMEOUT_SEC, TimeUnit.SECONDS, timeService);
          cdsRespTimers.put(clusterName, timeoutHandle);
        }
      }
      if (!endpointWatchers.isEmpty()) {
        adsStream.sendXdsRequest(ADS_TYPE_URL_EDS, endpointWatchers.keySet());
        for (String clusterName : endpointWatchers.keySet()) {
          ScheduledHandle timeoutHandle =
              syncContext
                  .schedule(
                      new EdsResourceFetchTimeoutTask(clusterName),
                      INITIAL_RESOURCE_FETCH_TIMEOUT_SEC, TimeUnit.SECONDS, timeService);
          edsRespTimers.put(clusterName, timeoutHandle);
        }
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
    private String rdsVersion = "";
    private String cdsVersion = "";
    private String edsVersion = "";

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
          } else if (typeUrl.equals(ADS_TYPE_URL_RDS)) {
            rdsRespNonce = response.getNonce();
            handleRdsResponse(response);
          } else if (typeUrl.equals(ADS_TYPE_URL_CDS)) {
            cdsRespNonce = response.getNonce();
            handleCdsResponse(response);
          } else if (typeUrl.equals(ADS_TYPE_URL_EDS)) {
            edsRespNonce = response.getNonce();
            handleEdsResponse(response);
          } else {
            logger.log(
                XdsLogLevel.WARNING,
                "Received an unknown type of DiscoveryResponse\n{0}", response);
          }
        }
      });
    }

    @Override
    public void onError(final Throwable t) {
      syncContext.execute(new Runnable() {
        @Override
        public void run() {
          handleStreamClosed(Status.fromThrowable(t));
        }
      });
    }

    @Override
    public void onCompleted() {
      syncContext.execute(new Runnable() {
        @Override
        public void run() {
          handleStreamClosed(
              Status.UNAVAILABLE.withDescription("Closed by server"));
        }
      });
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
      for (Set<ClusterWatcher> watchers : clusterWatchers.values()) {
        for (ClusterWatcher watcher : watchers) {
          watcher.onError(error);
        }
      }
      for (Set<EndpointWatcher> watchers : endpointWatchers.values()) {
        for (EndpointWatcher watcher : watchers) {
          watcher.onError(error);
        }
      }
      cleanUp();
      cleanUpResources();
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
    private void sendXdsRequest(String typeUrl, Collection<String> resourceNames) {
      checkState(requestWriter != null, "ADS stream has not been started");
      String version = "";
      String nonce = "";
      if (typeUrl.equals(ADS_TYPE_URL_LDS)) {
        version = ldsVersion;
        nonce = ldsRespNonce;
        logger.log(XdsLogLevel.INFO, "Sending LDS request for resources: {0}", resourceNames);
      } else if (typeUrl.equals(ADS_TYPE_URL_RDS)) {
        checkArgument(resourceNames.size() == 1,
            "RDS request requesting for more than one resource");
        version = rdsVersion;
        nonce = rdsRespNonce;
        rdsResourceName = resourceNames.iterator().next();
        logger.log(XdsLogLevel.INFO, "Sending RDS request for resources: {0}", resourceNames);
      } else if (typeUrl.equals(ADS_TYPE_URL_CDS)) {
        version = cdsVersion;
        nonce = cdsRespNonce;
        logger.log(XdsLogLevel.INFO, "Sending CDS request for resources: {0}", resourceNames);
      } else if (typeUrl.equals(ADS_TYPE_URL_EDS)) {
        version = edsVersion;
        nonce = edsRespNonce;
        logger.log(XdsLogLevel.INFO, "Sending EDS request for resources: {0}", resourceNames);
      }
      DiscoveryRequest request =
          DiscoveryRequest
              .newBuilder()
              .setVersionInfo(version)
              .setNode(node)
              .addAllResourceNames(resourceNames)
              .setTypeUrl(typeUrl)
              .setResponseNonce(nonce)
              .build();
      requestWriter.onNext(request);
      logger.log(XdsLogLevel.DEBUG, "Sent DiscoveryRequest\n{0}", request);
    }

    /**
     * Sends a DiscoveryRequest with the given information as an ACK. Updates the latest accepted
     * version for the corresponding resource type.
     */
    private void sendAckRequest(String typeUrl, Collection<String> resourceNames,
        String versionInfo) {
      checkState(requestWriter != null, "ADS stream has not been started");
      String nonce = "";
      if (typeUrl.equals(ADS_TYPE_URL_LDS)) {
        ldsVersion = versionInfo;
        nonce = ldsRespNonce;
      } else if (typeUrl.equals(ADS_TYPE_URL_RDS)) {
        rdsVersion = versionInfo;
        nonce = rdsRespNonce;
      } else if (typeUrl.equals(ADS_TYPE_URL_CDS)) {
        cdsVersion = versionInfo;
        nonce = cdsRespNonce;
      } else if (typeUrl.equals(ADS_TYPE_URL_EDS)) {
        edsVersion = versionInfo;
        nonce = edsRespNonce;
      }
      DiscoveryRequest request =
          DiscoveryRequest
              .newBuilder()
              .setVersionInfo(versionInfo)
              .setNode(node)
              .addAllResourceNames(resourceNames)
              .setTypeUrl(typeUrl)
              .setResponseNonce(nonce)
              .build();
      requestWriter.onNext(request);
      logger.log(XdsLogLevel.DEBUG, "Sent ACK request\n{0}", request);
    }

    /**
     * Sends a DiscoveryRequest with the given information as an NACK. NACK takes the previous
     * accepted version.
     */
    private void sendNackRequest(String typeUrl, Collection<String> resourceNames,
        String rejectVersion, String message) {
      checkState(requestWriter != null, "ADS stream has not been started");
      String versionInfo = "";
      String nonce = "";
      if (typeUrl.equals(ADS_TYPE_URL_LDS)) {
        versionInfo = ldsVersion;
        nonce = ldsRespNonce;
        logger.log(
            XdsLogLevel.WARNING,
            "Rejecting LDS update, version: {0}, reason: {1}", rejectVersion, message);
      } else if (typeUrl.equals(ADS_TYPE_URL_RDS)) {
        versionInfo = rdsVersion;
        nonce = rdsRespNonce;
        logger.log(
            XdsLogLevel.WARNING,
            "Rejecting RDS update, version: {0}, reason: {1}", rejectVersion, message);
      } else if (typeUrl.equals(ADS_TYPE_URL_CDS)) {
        versionInfo = cdsVersion;
        nonce = cdsRespNonce;
        logger.log(
            XdsLogLevel.WARNING,
            "Rejecting CDS update, version: {0}, reason: {1}", rejectVersion, message);
      } else if (typeUrl.equals(ADS_TYPE_URL_EDS)) {
        versionInfo = edsVersion;
        nonce = edsRespNonce;
        logger.log(
            XdsLogLevel.WARNING,
            "Rejecting EDS update, version: {0}, reason: {1}", rejectVersion, message);
      }
      DiscoveryRequest request =
          DiscoveryRequest
              .newBuilder()
              .setVersionInfo(versionInfo)
              .setNode(node)
              .addAllResourceNames(resourceNames)
              .setTypeUrl(typeUrl)
              .setResponseNonce(nonce)
              .setErrorDetail(
                  com.google.rpc.Status.newBuilder()
                      .setCode(Code.INVALID_ARGUMENT_VALUE)
                      .setMessage(message))
              .build();
      requestWriter.onNext(request);
      logger.log(XdsLogLevel.DEBUG, "Sent NACK request\n{0}", request);
    }
  }

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

  @VisibleForTesting
  final class LdsResourceFetchTimeoutTask extends ResourceFetchTimeoutTask {

    LdsResourceFetchTimeoutTask(String resourceName) {
      super(resourceName);
    }

    @Override
    public void run() {
      super.run();
      ldsRespTimer = null;
      configWatcher.onError(
          Status.NOT_FOUND
              .withDescription("Listener resource for listener " + resourceName + " not found."));
    }
  }

  @VisibleForTesting
  final class RdsResourceFetchTimeoutTask extends ResourceFetchTimeoutTask {

    RdsResourceFetchTimeoutTask(String resourceName) {
      super(resourceName);
    }

    @Override
    public void run() {
      super.run();
      rdsRespTimer = null;
      configWatcher.onError(Status.NOT_FOUND
          .withDescription(
              "RouteConfiguration resource for route " + resourceName + " not found."));
    }
  }

  @VisibleForTesting
  final class CdsResourceFetchTimeoutTask extends ResourceFetchTimeoutTask {

    CdsResourceFetchTimeoutTask(String resourceName) {
      super(resourceName);
    }

    @Override
    public void run() {
      super.run();
      cdsRespTimers.remove(resourceName);
      absentCdsResources.add(resourceName);
      for (ClusterWatcher wat : clusterWatchers.get(resourceName)) {
        wat.onError(
            Status.NOT_FOUND
                .withDescription("Cluster resource " + resourceName + " not found."));
      }
    }
  }

  @VisibleForTesting
  final class EdsResourceFetchTimeoutTask extends ResourceFetchTimeoutTask {

    EdsResourceFetchTimeoutTask(String resourceName) {
      super(resourceName);
    }

    @Override
    public void run() {
      super.run();
      edsRespTimers.remove(resourceName);
      absentEdsResources.add(resourceName);
      for (EndpointWatcher wat : endpointWatchers.get(resourceName)) {
        wat.onError(
            Status.NOT_FOUND
                .withDescription(
                    "Endpoint resource for cluster " + resourceName + " not found."));
      }
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
              .add(HttpConnectionManager.getDescriptor())
              .add(RouteConfiguration.getDescriptor())
              .add(Cluster.getDescriptor())
              .add(ClusterLoadAssignment.getDescriptor())
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
