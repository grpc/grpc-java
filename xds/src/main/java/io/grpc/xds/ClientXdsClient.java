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
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.DiscoveryType;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.EdsClusterConfig;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.LbPolicy;
import io.envoyproxy.envoy.config.core.v3.HttpProtocolOptions;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.endpoint.v3.LbEndpoint;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.envoyproxy.envoy.config.route.v3.VirtualHost;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.Rds;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.UpstreamTlsContext;
import io.grpc.Status;
import io.grpc.SynchronizationContext.ScheduledHandle;
import io.grpc.internal.BackoffPolicy;
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

/**
 * XdsClient implementation for client side usages.
 */
final class ClientXdsClient extends AbstractXdsClient {

  // Longest time to wait, since the subscription to some resource, for concluding its absence.
  @VisibleForTesting
  static final int INITIAL_RESOURCE_FETCH_TIMEOUT_SEC = 15;
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

  private final Map<String, ResourceSubscriber> ldsResourceSubscribers = new HashMap<>();
  private final Map<String, ResourceSubscriber> rdsResourceSubscribers = new HashMap<>();
  private final Map<String, ResourceSubscriber> cdsResourceSubscribers = new HashMap<>();
  private final Map<String, ResourceSubscriber> edsResourceSubscribers = new HashMap<>();
  private final LoadStatsManager loadStatsManager = new LoadStatsManager();
  private final LoadReportClient lrsClient;
  private boolean reportingLoad;

  ClientXdsClient(XdsChannel channel, Node node, ScheduledExecutorService timeService,
      BackoffPolicy.Provider backoffPolicyProvider, Supplier<Stopwatch> stopwatchSupplier) {
    super(channel, node, timeService, backoffPolicyProvider, stopwatchSupplier);
    lrsClient = new LoadReportClient(loadStatsManager, channel, node, timeService,
        backoffPolicyProvider, stopwatchSupplier);
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
      nackResponse(ResourceType.LDS, nonce, errorMessage);
      return;
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
      nackResponse(ResourceType.RDS, nonce, errorMessage);
      return;
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
      nackResponse(ResourceType.CDS, nonce, errorMessage);
      return;
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
      if (!edsServices.contains(resource)) {
        subscriber.onAbsent();
      }
    }
  }

  @Nullable
  private static EnvoyServerProtoData.UpstreamTlsContext getTlsContextFromCluster(Cluster cluster)
      throws InvalidProtocolBufferException {
    if (cluster.hasTransportSocket()
        && TRANSPORT_SOCKET_NAME_TLS.equals(cluster.getTransportSocket().getName())) {
      Any any = cluster.getTransportSocket().getTypedConfig();
      if (any.getTypeUrl().equals(TYPE_URL_UPSTREAM_TLS_CONTEXT_V2)) {
        any = any.toBuilder().setTypeUrl(TYPE_URL_UPSTREAM_TLS_CONTEXT).build();
      }
      return EnvoyServerProtoData.UpstreamTlsContext.fromEnvoyProtoUpstreamTlsContext(
          any.unpack(UpstreamTlsContext.class));
    }
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
      nackResponse(ResourceType.EDS, nonce, errorMessage);
      return;
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
          getLogger().log(XdsLogLevel.INFO, "Subscribe CDS resource {0}", resourceName);
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

    // FIXME(chengyuanzhang): should only restart timer if the resource is still unresolved.
    void restartTimer() {
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
