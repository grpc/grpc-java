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
import static io.grpc.xds.EnvoyProtoData.TRANSPORT_SOCKET_NAME_TLS;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Stopwatch;
import com.google.common.base.Supplier;
import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.Durations;
import io.envoyproxy.envoy.config.cluster.v3.CircuitBreakers.Thresholds;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.CustomClusterType;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.DiscoveryType;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.LbPolicy;
import io.envoyproxy.envoy.config.core.v3.HttpProtocolOptions;
import io.envoyproxy.envoy.config.core.v3.RoutingPriority;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.endpoint.v3.LbEndpoint;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.envoyproxy.envoy.config.route.v3.VirtualHost;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.Rds;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.SynchronizationContext.ScheduledHandle;
import io.grpc.internal.BackoffPolicy;
import io.grpc.xds.EnvoyProtoData.DropOverload;
import io.grpc.xds.EnvoyProtoData.Locality;
import io.grpc.xds.EnvoyProtoData.LocalityLbEndpoints;
import io.grpc.xds.EnvoyProtoData.Node;
import io.grpc.xds.EnvoyProtoData.StructOrError;
import io.grpc.xds.EnvoyServerProtoData.UpstreamTlsContext;
import io.grpc.xds.LoadStatsManager.LoadStatsStore;
import io.grpc.xds.XdsClient.CdsUpdate.AggregateClusterConfig;
import io.grpc.xds.XdsClient.CdsUpdate.ClusterType;
import io.grpc.xds.XdsClient.CdsUpdate.EdsClusterConfig;
import io.grpc.xds.XdsClient.CdsUpdate.LogicalDnsClusterConfig;
import io.grpc.xds.XdsLogger.XdsLogLevel;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

/**
 * XdsClient implementation for client side usages.
 */
final class ClientXdsClient extends AbstractXdsClient {

  // Longest time to wait, since the subscription to some resource, for concluding its absence.
  @VisibleForTesting
  static final int INITIAL_RESOURCE_FETCH_TIMEOUT_SEC = 15;
  @VisibleForTesting
  static final String AGGREGATE_CLUSTER_TYPE_NAME = "envoy.clusters.aggregate";
  private static final String TYPE_URL_HTTP_CONNECTION_MANAGER_V2 =
      "type.googleapis.com/envoy.config.filter.network.http_connection_manager.v2"
          + ".HttpConnectionManager";
  private static final String TYPE_URL_HTTP_CONNECTION_MANAGER =
      "type.googleapis.com/envoy.extensions.filters.network.http_connection_manager.v3"
          + ".HttpConnectionManager";
  private static final String TYPE_URL_UPSTREAM_TLS_CONTEXT =
      "type.googleapis.com/envoy.extensions.transport_sockets.tls.v3.UpstreamTlsContext";
  private static final String TYPE_URL_UPSTREAM_TLS_CONTEXT_V2 =
      "type.googleapis.com/envoy.api.v2.auth.UpstreamTlsContext";
  private static final String TYPE_URL_CLUSTER_CONFIG_V2 =
      "type.googleapis.com/envoy.config.cluster.aggregate.v2alpha.ClusterConfig";
  private static final String TYPE_URL_CLUSTER_CONFIG =
      "type.googleapis.com/envoy.extensions.clusters.aggregate.v3.ClusterConfig";

  private final Map<String, ResourceSubscriber> ldsResourceSubscribers = new HashMap<>();
  private final Map<String, ResourceSubscriber> rdsResourceSubscribers = new HashMap<>();
  private final Map<String, ResourceSubscriber> cdsResourceSubscribers = new HashMap<>();
  private final Map<String, ResourceSubscriber> edsResourceSubscribers = new HashMap<>();
  private final LoadStatsManager loadStatsManager = new LoadStatsManager();
  private final LoadReportClient lrsClient;
  private boolean reportingLoad;

  ClientXdsClient(ManagedChannel channel, boolean useProtocolV3, Node node,
      ScheduledExecutorService timeService, BackoffPolicy.Provider backoffPolicyProvider,
      Supplier<Stopwatch> stopwatchSupplier) {
    super(channel, useProtocolV3, node, timeService, backoffPolicyProvider, stopwatchSupplier);
    lrsClient = new LoadReportClient(loadStatsManager, channel, useProtocolV3, node,
        getSyncContext(), timeService, backoffPolicyProvider, stopwatchSupplier);
  }

  @Override
  protected void handleLdsResponse(String versionInfo, List<Any> resources, String nonce) {
    // Unpack Listener messages.
    List<Listener> listeners = new ArrayList<>(resources.size());
    List<String> listenerNames = new ArrayList<>(resources.size());
    try {
      for (com.google.protobuf.Any res : resources) {
        if (res.getTypeUrl().equals(ResourceType.LDS.typeUrlV2())) {
          res = res.toBuilder().setTypeUrl(ResourceType.LDS.typeUrl()).build();
        }
        Listener listener = res.unpack(Listener.class);
        listeners.add(listener);
        listenerNames.add(listener.getName());
      }
    } catch (InvalidProtocolBufferException e) {
      getLogger().log(XdsLogLevel.WARNING, "Failed to unpack Listeners in LDS response {0}", e);
      nackResponse(ResourceType.LDS, nonce, "Malformed LDS response: " + e);
      return;
    }
    getLogger().log(XdsLogLevel.INFO, "Received LDS response for resources: {0}", listenerNames);

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
      getLogger().log(
          XdsLogLevel.WARNING,
          "Failed to unpack HttpConnectionManagers in Listeners of LDS response {0}", e);
      nackResponse(ResourceType.LDS, nonce, "Malformed LDS response: " + e);
      return;
    }

    Map<String, LdsUpdate> ldsUpdates = new HashMap<>();
    Set<String> rdsNames = new HashSet<>();
    for (Map.Entry<String, HttpConnectionManager> entry : httpConnectionManagers.entrySet()) {
      LdsUpdate update;
      String listenerName = entry.getKey();
      HttpConnectionManager hcm = entry.getValue();
      long maxStreamDuration = 0;
      if (hcm.hasCommonHttpProtocolOptions()) {
        HttpProtocolOptions options = hcm.getCommonHttpProtocolOptions();
        if (options.hasMaxStreamDuration()) {
          maxStreamDuration = Durations.toNanos(options.getMaxStreamDuration());
        }
      }
      if (hcm.hasRouteConfig()) {
        List<EnvoyProtoData.VirtualHost> virtualHosts = new ArrayList<>();
        for (VirtualHost virtualHostProto : hcm.getRouteConfig().getVirtualHostsList()) {
          StructOrError<EnvoyProtoData.VirtualHost> virtualHost =
              EnvoyProtoData.VirtualHost.fromEnvoyProtoVirtualHost(virtualHostProto);
          if (virtualHost.getErrorDetail() != null) {
            nackResponse(ResourceType.LDS, nonce,
                "Listener " + listenerName + " contains invalid virtual host: "
                    + virtualHost.getErrorDetail());
            return;
          }
          virtualHosts.add(virtualHost.getStruct());
        }
        update = new LdsUpdate(maxStreamDuration, virtualHosts);
      } else if (hcm.hasRds()) {
        Rds rds = hcm.getRds();
        if (!rds.getConfigSource().hasAds()) {
          nackResponse(ResourceType.LDS, nonce,
              "Listener " + listenerName + " with RDS config_source not set to ADS");
          return;
        }
        update = new LdsUpdate(maxStreamDuration, rds.getRouteConfigName());
        rdsNames.add(rds.getRouteConfigName());
      } else {
        nackResponse(ResourceType.LDS, nonce,
            "Listener " + listenerName + " without inline RouteConfiguration or RDS");
        return;
      }
      ldsUpdates.put(listenerName, update);
    }
    ackResponse(ResourceType.LDS, versionInfo, nonce);

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

  @Override
  protected void handleRdsResponse(String versionInfo, List<Any> resources, String nonce) {
    // Unpack RouteConfiguration messages.
    Map<String, RouteConfiguration> routeConfigs = new HashMap<>(resources.size());
    try {
      for (com.google.protobuf.Any res : resources) {
        if (res.getTypeUrl().equals(ResourceType.RDS.typeUrlV2())) {
          res = res.toBuilder().setTypeUrl(ResourceType.RDS.typeUrl()).build();
        }
        RouteConfiguration rc = res.unpack(RouteConfiguration.class);
        routeConfigs.put(rc.getName(), rc);
      }
    } catch (InvalidProtocolBufferException e) {
      getLogger().log(
          XdsLogLevel.WARNING, "Failed to unpack RouteConfiguration in RDS response {0}", e);
      nackResponse(ResourceType.RDS, nonce, "Malformed RDS response: " + e);
      return;
    }
    getLogger().log(
        XdsLogLevel.INFO, "Received RDS response for resources: {0}", routeConfigs.keySet());

    Map<String, RdsUpdate> rdsUpdates = new HashMap<>();
    for (Map.Entry<String, RouteConfiguration> entry : routeConfigs.entrySet()) {
      String routeConfigName = entry.getKey();
      RouteConfiguration routeConfig = entry.getValue();
      List<EnvoyProtoData.VirtualHost> virtualHosts =
          new ArrayList<>(routeConfig.getVirtualHostsCount());
      for (VirtualHost virtualHostProto : routeConfig.getVirtualHostsList()) {
        StructOrError<EnvoyProtoData.VirtualHost> virtualHost =
            EnvoyProtoData.VirtualHost.fromEnvoyProtoVirtualHost(virtualHostProto);
        if (virtualHost.getErrorDetail() != null) {
          nackResponse(ResourceType.RDS, nonce, "RouteConfiguration " + routeConfigName
              + " contains invalid virtual host: " + virtualHost.getErrorDetail());
          return;
        }
        virtualHosts.add(virtualHost.getStruct());
      }
      rdsUpdates.put(routeConfigName, new RdsUpdate(virtualHosts));
    }
    ackResponse(ResourceType.RDS, versionInfo, nonce);

    for (String resource : rdsResourceSubscribers.keySet()) {
      if (rdsUpdates.containsKey(resource)) {
        ResourceSubscriber subscriber = rdsResourceSubscribers.get(resource);
        subscriber.onData(rdsUpdates.get(resource));
      }
    }
  }

  @Override
  protected void handleCdsResponse(String versionInfo, List<Any> resources, String nonce) {
    // Unpack Cluster messages.
    List<Cluster> clusters = new ArrayList<>(resources.size());
    List<String> clusterNames = new ArrayList<>(resources.size());
    try {
      for (com.google.protobuf.Any res : resources) {
        if (res.getTypeUrl().equals(ResourceType.CDS.typeUrlV2())) {
          res = res.toBuilder().setTypeUrl(ResourceType.CDS.typeUrl()).build();
        }
        Cluster cluster = res.unpack(Cluster.class);
        clusters.add(cluster);
        clusterNames.add(cluster.getName());
      }
    } catch (InvalidProtocolBufferException e) {
      getLogger().log(XdsLogLevel.WARNING, "Failed to unpack Clusters in CDS response {0}", e);
      nackResponse(ResourceType.CDS, nonce, "Malformed CDS response: " + e);
      return;
    }
    getLogger().log(XdsLogLevel.INFO, "Received CDS response for resources: {0}", clusterNames);

    Map<String, CdsUpdate> cdsUpdates = new HashMap<>();
    // CDS responses represents the state of the world, EDS resources not referenced in CDS
    // resources should be deleted.
    Set<String> edsResources = new HashSet<>();  // retained EDS resources
    for (Cluster cluster : clusters) {
      String clusterName = cluster.getName();
      // Management server is required to always send newly requested resources, even if they
      // may have been sent previously (proactively). Thus, client does not need to cache
      // unrequested resources.
      if (!cdsResourceSubscribers.containsKey(clusterName)) {
        continue;
      }
      // The lb_policy field must be set to ROUND_ROBIN.
      if (!cluster.getLbPolicy().equals(LbPolicy.ROUND_ROBIN)) {
        nackResponse(ResourceType.CDS, nonce,
            "Cluster " + clusterName + ": unsupported Lb policy: " + cluster.getLbPolicy());
        return;
      }
      String lbPolicy = "round_robin";
      CdsUpdate update = null;
      switch (cluster.getClusterDiscoveryTypeCase()) {
        case TYPE:
          update = parseNonAggregateCluster(cluster, nonce, lbPolicy, edsResources);
          break;
        case CLUSTER_TYPE:
          update = parseAggregateCluster(cluster, nonce, lbPolicy);
          break;
        case CLUSTERDISCOVERYTYPE_NOT_SET:
        default:
          nackResponse(ResourceType.CDS, nonce,
              "Cluster " + clusterName + ": cluster discovery type unspecified");
      }
      if (update == null) {
        return;
      }
      cdsUpdates.put(clusterName, update);
    }
    ackResponse(ResourceType.CDS, versionInfo, nonce);

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
      if (!edsResources.contains(resource)) {
        subscriber.onAbsent();
      }
    }
  }

  /**
   * Parses CDS resource for an aggregate cluster into {@link io.grpc.xds.XdsClient.CdsUpdate}.
   * Returns {@code null} and nack the response with the given nonce if the resource is invalid.
   */
  private CdsUpdate parseAggregateCluster(Cluster cluster, String nonce, String lbPolicy) {
    String clusterName = cluster.getName();
    CustomClusterType customType = cluster.getClusterType();
    String typeName = customType.getName();
    if (!typeName.equals(AGGREGATE_CLUSTER_TYPE_NAME)) {
      nackResponse(ResourceType.CDS, nonce,
          "Cluster " + clusterName + ": unsupported custom cluster type: " + typeName);
      return null;
    }
    io.envoyproxy.envoy.extensions.clusters.aggregate.v3.ClusterConfig clusterConfig;
    Any unpackedClusterConfig = customType.getTypedConfig();
    if (unpackedClusterConfig.getTypeUrl().equals(TYPE_URL_CLUSTER_CONFIG_V2)) {
      unpackedClusterConfig =
          unpackedClusterConfig.toBuilder().setTypeUrl(TYPE_URL_CLUSTER_CONFIG).build();
    }
    try {
      clusterConfig = unpackedClusterConfig.unpack(
          io.envoyproxy.envoy.extensions.clusters.aggregate.v3.ClusterConfig.class);
    } catch (InvalidProtocolBufferException e) {
      nackResponse(ResourceType.CDS, nonce,
          "Cluster " + clusterName + ": invalid cluster config: " + e);
      return null;
    }
    AggregateClusterConfig config =
        new AggregateClusterConfig(lbPolicy, clusterConfig.getClustersList());
    return new CdsUpdate(clusterName, ClusterType.AGGREGATE, config);
  }

  /**
   * Parses CDS resource for a non-aggregate cluster (EDS or Logical DNS) into {@link
   * io.grpc.xds.XdsClient.CdsUpdate}. Returns {@code null} and nack the response with the given
   * nonce if the resource is invalid.
   */
  private CdsUpdate parseNonAggregateCluster(Cluster cluster, String nonce, String lbPolicy,
      Set<String> edsResources) {
    String clusterName = cluster.getName();
    String lrsServerName = null;
    Long maxConcurrentRequests = null;
    UpstreamTlsContext upstreamTlsContext = null;
    if (cluster.hasLrsServer()) {
      if (!cluster.getLrsServer().hasSelf()) {
        nackResponse(ResourceType.CDS, nonce,
            "Cluster " + clusterName + ": only support LRS for the same management server");
        return null;
      }
      lrsServerName = "";
    }
    if (cluster.hasCircuitBreakers()) {
      List<Thresholds> thresholds = cluster.getCircuitBreakers().getThresholdsList();
      for (Thresholds threshold : thresholds) {
        if (threshold.getPriority() != RoutingPriority.DEFAULT) {
          continue;
        }
        if (threshold.hasMaxRequests()) {
          maxConcurrentRequests = (long) threshold.getMaxRequests().getValue();
        }
      }
    }
    if (cluster.hasTransportSocket()
        && TRANSPORT_SOCKET_NAME_TLS.equals(cluster.getTransportSocket().getName())) {
      Any any = cluster.getTransportSocket().getTypedConfig();
      if (any.getTypeUrl().equals(TYPE_URL_UPSTREAM_TLS_CONTEXT_V2)) {
        any = any.toBuilder().setTypeUrl(TYPE_URL_UPSTREAM_TLS_CONTEXT).build();
      }
      io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.UpstreamTlsContext unpacked;
      try {
        unpacked = any.unpack(
            io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.UpstreamTlsContext.class);
      } catch (InvalidProtocolBufferException e) {
        nackResponse(ResourceType.CDS, nonce,
            "Cluster " + clusterName + ": invalid upstream TLS context: " + e);
        return null;
      }
      upstreamTlsContext = UpstreamTlsContext.fromEnvoyProtoUpstreamTlsContext(unpacked);
    }

    DiscoveryType type = cluster.getType();
    if (type == DiscoveryType.EDS) {
      String edsServiceName = null;
      io.envoyproxy.envoy.config.cluster.v3.Cluster.EdsClusterConfig edsClusterConfig =
          cluster.getEdsClusterConfig();
      if (!edsClusterConfig.getEdsConfig().hasAds()) {
        nackResponse(ResourceType.CDS, nonce, "Cluster " + clusterName + ": "
            + "field eds_cluster_config must be set to indicate to use EDS over ADS.");
        return null;
      }
      // If the service_name field is set, that value will be used for the EDS request.
      if (!edsClusterConfig.getServiceName().isEmpty()) {
        edsServiceName = edsClusterConfig.getServiceName();
        edsResources.add(edsServiceName);
      } else {
        edsResources.add(clusterName);
      }
      EdsClusterConfig config = new EdsClusterConfig(lbPolicy, edsServiceName,
          lrsServerName, maxConcurrentRequests, upstreamTlsContext);
      return new CdsUpdate(clusterName, ClusterType.EDS, config);
    } else if (type.equals(DiscoveryType.LOGICAL_DNS)) {
      LogicalDnsClusterConfig config = new LogicalDnsClusterConfig(lbPolicy, lrsServerName,
          maxConcurrentRequests, upstreamTlsContext);
      return new CdsUpdate(clusterName, ClusterType.LOGICAL_DNS, config);
    }
    nackResponse(ResourceType.CDS, nonce,
        "Cluster " + clusterName + ": unsupported built-in discovery type: " + type);
    return null;
  }

  @Override
  protected void handleEdsResponse(String versionInfo, List<Any> resources, String nonce) {
    // Unpack ClusterLoadAssignment messages.
    List<ClusterLoadAssignment> clusterLoadAssignments = new ArrayList<>(resources.size());
    List<String> claNames = new ArrayList<>(resources.size());
    try {
      for (com.google.protobuf.Any res : resources) {
        if (res.getTypeUrl().equals(ResourceType.EDS.typeUrlV2())) {
          res = res.toBuilder().setTypeUrl(ResourceType.EDS.typeUrl()).build();
        }
        ClusterLoadAssignment assignment = res.unpack(ClusterLoadAssignment.class);
        clusterLoadAssignments.add(assignment);
        claNames.add(assignment.getClusterName());
      }
    } catch (InvalidProtocolBufferException e) {
      getLogger().log(
          XdsLogLevel.WARNING, "Failed to unpack ClusterLoadAssignments in EDS response {0}", e);
      nackResponse(ResourceType.EDS, nonce, "Malformed EDS response: " + e);
      return;
    }
    getLogger().log(XdsLogLevel.INFO, "Received EDS response for resources: {0}", claNames);

    Map<String, EdsUpdate> edsUpdates = new HashMap<>();
    for (ClusterLoadAssignment assignment : clusterLoadAssignments) {
      String clusterName = assignment.getClusterName();
      // Skip information for clusters not requested.
      // Management server is required to always send newly requested resources, even if they
      // may have been sent previously (proactively). Thus, client does not need to cache
      // unrequested resources.
      if (!edsResourceSubscribers.containsKey(clusterName)) {
        continue;
      }
      Set<Integer> priorities = new HashSet<>();
      Map<Locality, LocalityLbEndpoints> localityLbEndpointsMap = new LinkedHashMap<>();
      List<DropOverload> dropOverloads = new ArrayList<>();
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
          nackResponse(ResourceType.EDS, nonce,
              "ClusterLoadAssignment " + clusterName + " : locality with negative priority.");
          return;
        }
        maxPriority = Math.max(maxPriority, localityPriority);
        priorities.add(localityPriority);
        // The endpoint field of each lb_endpoints must be set.
        // Inside of it: the address field must be set.
        for (LbEndpoint lbEndpoint : localityLbEndpoints.getLbEndpointsList()) {
          if (!lbEndpoint.getEndpoint().hasAddress()) {
            nackResponse(ResourceType.EDS, nonce,
                "ClusterLoadAssignment " + clusterName + " : endpoint with no address.");
            return;
          }
        }
        // Note endpoints with health status other than UNHEALTHY and UNKNOWN are still
        // handed over to watching parties. It is watching parties' responsibility to
        // filter out unhealthy endpoints. See EnvoyProtoData.LbEndpoint#isHealthy().
        localityLbEndpointsMap.put(
            Locality.fromEnvoyProtoLocality(localityLbEndpoints.getLocality()),
            LocalityLbEndpoints.fromEnvoyProtoLocalityLbEndpoints(localityLbEndpoints));
      }
      if (priorities.size() != maxPriority + 1) {
        nackResponse(ResourceType.EDS, nonce,
            "ClusterLoadAssignment " + clusterName + " : sparse priorities.");
        return;
      }
      for (ClusterLoadAssignment.Policy.DropOverload dropOverload
          : assignment.getPolicy().getDropOverloadsList()) {
        dropOverloads.add(DropOverload.fromEnvoyProtoDropOverload(dropOverload));
      }
      EdsUpdate update = new EdsUpdate(clusterName, localityLbEndpointsMap, dropOverloads);
      edsUpdates.put(clusterName, update);
    }
    ackResponse(ResourceType.EDS, versionInfo, nonce);

    for (String resource : edsResourceSubscribers.keySet()) {
      ResourceSubscriber subscriber = edsResourceSubscribers.get(resource);
      if (edsUpdates.containsKey(resource)) {
        subscriber.onData(edsUpdates.get(resource));
      }
    }
  }

  @Override
  protected void handleStreamClosed(Status error) {
    cleanUpResourceTimers();
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
  }

  @Override
  protected void handleStreamRestarted() {
    for (ResourceSubscriber subscriber : ldsResourceSubscribers.values()) {
      subscriber.restartTimer();
    }
    for (ResourceSubscriber subscriber : rdsResourceSubscribers.values()) {
      subscriber.restartTimer();
    }
    for (ResourceSubscriber subscriber : cdsResourceSubscribers.values()) {
      subscriber.restartTimer();
    }
    for (ResourceSubscriber subscriber : edsResourceSubscribers.values()) {
      subscriber.restartTimer();
    }
  }

  @Override
  protected void handleShutdown() {
    if (reportingLoad) {
      lrsClient.stopLoadReporting();
    }
    cleanUpResourceTimers();
  }

  @Nullable
  @Override
  Collection<String> getSubscribedResources(ResourceType type) {
    switch (type) {
      case LDS:
        return ldsResourceSubscribers.isEmpty() ? null : ldsResourceSubscribers.keySet();
      case RDS:
        return rdsResourceSubscribers.isEmpty() ? null : rdsResourceSubscribers.keySet();
      case CDS:
        return cdsResourceSubscribers.isEmpty() ? null : cdsResourceSubscribers.keySet();
      case EDS:
        return edsResourceSubscribers.isEmpty() ? null : edsResourceSubscribers.keySet();
      case UNKNOWN:
      default:
        throw new AssertionError("Unknown resource type");
    }
  }

  @Override
  void watchLdsResource(final String resourceName, final LdsResourceWatcher watcher) {
    getSyncContext().execute(new Runnable() {
      @Override
      public void run() {
        ResourceSubscriber subscriber = ldsResourceSubscribers.get(resourceName);
        if (subscriber == null) {
          getLogger().log(XdsLogLevel.INFO, "Subscribe LDS resource {0}", resourceName);
          subscriber = new ResourceSubscriber(ResourceType.LDS, resourceName);
          ldsResourceSubscribers.put(resourceName, subscriber);
          adjustResourceSubscription(ResourceType.LDS);
        }
        subscriber.addWatcher(watcher);
      }
    });
  }

  @Override
  void cancelLdsResourceWatch(final String resourceName, final LdsResourceWatcher watcher) {
    getSyncContext().execute(new Runnable() {
      @Override
      public void run() {
        ResourceSubscriber subscriber = ldsResourceSubscribers.get(resourceName);
        subscriber.removeWatcher(watcher);
        if (!subscriber.isWatched()) {
          subscriber.stopTimer();
          getLogger().log(XdsLogLevel.INFO, "Unsubscribe LDS resource {0}", resourceName);
          ldsResourceSubscribers.remove(resourceName);
          adjustResourceSubscription(ResourceType.LDS);
        }
      }
    });
  }

  @Override
  void watchRdsResource(final String resourceName, final RdsResourceWatcher watcher) {
    getSyncContext().execute(new Runnable() {
      @Override
      public void run() {
        ResourceSubscriber subscriber = rdsResourceSubscribers.get(resourceName);
        if (subscriber == null) {
          getLogger().log(XdsLogLevel.INFO, "Subscribe RDS resource {0}", resourceName);
          subscriber = new ResourceSubscriber(ResourceType.RDS, resourceName);
          rdsResourceSubscribers.put(resourceName, subscriber);
          adjustResourceSubscription(ResourceType.RDS);
        }
        subscriber.addWatcher(watcher);
      }
    });
  }

  @Override
  void cancelRdsResourceWatch(final String resourceName, final RdsResourceWatcher watcher) {
    getSyncContext().execute(new Runnable() {
      @Override
      public void run() {
        ResourceSubscriber subscriber = rdsResourceSubscribers.get(resourceName);
        subscriber.removeWatcher(watcher);
        if (!subscriber.isWatched()) {
          subscriber.stopTimer();
          getLogger().log(XdsLogLevel.INFO, "Unsubscribe RDS resource {0}", resourceName);
          rdsResourceSubscribers.remove(resourceName);
          adjustResourceSubscription(ResourceType.RDS);
        }
      }
    });
  }

  @Override
  void watchCdsResource(final String resourceName, final CdsResourceWatcher watcher) {
    getSyncContext().execute(new Runnable() {
      @Override
      public void run() {
        ResourceSubscriber subscriber = cdsResourceSubscribers.get(resourceName);
        if (subscriber == null) {
          getLogger().log(XdsLogLevel.INFO, "Subscribe CDS resource {0}", resourceName);
          subscriber = new ResourceSubscriber(ResourceType.CDS, resourceName);
          cdsResourceSubscribers.put(resourceName, subscriber);
          adjustResourceSubscription(ResourceType.CDS);
        }
        subscriber.addWatcher(watcher);
      }
    });
  }

  @Override
  void cancelCdsResourceWatch(final String resourceName, final CdsResourceWatcher watcher) {
    getSyncContext().execute(new Runnable() {
      @Override
      public void run() {
        ResourceSubscriber subscriber = cdsResourceSubscribers.get(resourceName);
        subscriber.removeWatcher(watcher);
        if (!subscriber.isWatched()) {
          subscriber.stopTimer();
          getLogger().log(XdsLogLevel.INFO, "Unsubscribe CDS resource {0}", resourceName);
          cdsResourceSubscribers.remove(resourceName);
          adjustResourceSubscription(ResourceType.CDS);
        }
      }
    });
  }

  @Override
  void watchEdsResource(final String resourceName, final EdsResourceWatcher watcher) {
    getSyncContext().execute(new Runnable() {
      @Override
      public void run() {
        ResourceSubscriber subscriber = edsResourceSubscribers.get(resourceName);
        if (subscriber == null) {
          getLogger().log(XdsLogLevel.INFO, "Subscribe EDS resource {0}", resourceName);
          subscriber = new ResourceSubscriber(ResourceType.EDS, resourceName);
          edsResourceSubscribers.put(resourceName, subscriber);
          adjustResourceSubscription(ResourceType.EDS);
        }
        subscriber.addWatcher(watcher);
      }
    });
  }

  @Override
  void cancelEdsResourceWatch(final String resourceName, final EdsResourceWatcher watcher) {
    getSyncContext().execute(new Runnable() {
      @Override
      public void run() {
        ResourceSubscriber subscriber = edsResourceSubscribers.get(resourceName);
        subscriber.removeWatcher(watcher);
        if (!subscriber.isWatched()) {
          subscriber.stopTimer();
          getLogger().log(XdsLogLevel.INFO, "Unsubscribe EDS resource {0}", resourceName);
          edsResourceSubscribers.remove(resourceName);
          adjustResourceSubscription(ResourceType.EDS);
        }
      }
    });
  }

  @Override
  LoadStatsStore addClientStats(String clusterName, @Nullable String clusterServiceName) {
    LoadStatsStore loadStatsStore;
    synchronized (this) {
      loadStatsStore = loadStatsManager.addLoadStats(clusterName, clusterServiceName);
    }
    getSyncContext().execute(new Runnable() {
      @Override
      public void run() {
        if (!reportingLoad) {
          lrsClient.startLoadReporting();
          reportingLoad = true;
        }
      }
    });
    return loadStatsStore;
  }

  @Override
  void removeClientStats(String clusterName, @Nullable String clusterServiceName) {
    synchronized (this) {
      loadStatsManager.removeLoadStats(clusterName, clusterServiceName);
    }
  }

  private void cleanUpResourceTimers() {
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
      if (isInBackoff()) {
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
      if (data != null || absent) {  // resource already resolved
        return;
      }
      class ResourceNotFound implements Runnable {
        @Override
        public void run() {
          getLogger().log(XdsLogLevel.INFO, "{0} resource {1} initial fetch timeout",
              type, resource);
          respTimer = null;
          onAbsent();
        }

        @Override
        public String toString() {
          return type + this.getClass().getSimpleName();
        }
      }

      respTimer = getSyncContext().schedule(
          new ResourceNotFound(), INITIAL_RESOURCE_FETCH_TIMEOUT_SEC, TimeUnit.SECONDS,
          getTimeService());
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
      getLogger().log(XdsLogLevel.INFO, "Conclude {0} resource {1} not exist", type, resource);
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
}
