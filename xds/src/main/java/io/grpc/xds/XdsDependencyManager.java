/*
 * Copyright 2024 The gRPC Authors
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
import static io.grpc.xds.client.XdsClient.ResourceUpdate;
import static io.grpc.xds.client.XdsLogger.XdsLogLevel.DEBUG;

import com.google.common.collect.Sets;
import io.grpc.InternalLogId;
import io.grpc.Status;
import io.grpc.StatusOr;
import io.grpc.SynchronizationContext;
import io.grpc.xds.XdsRouteConfigureResource.RdsUpdate;
import io.grpc.xds.client.XdsClient;
import io.grpc.xds.client.XdsClient.ResourceWatcher;
import io.grpc.xds.client.XdsLogger;
import io.grpc.xds.client.XdsResourceType;
import java.io.Closeable;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nullable;

/**
 * This class acts as a layer of indirection between the XdsClient and the NameResolver. It
 * maintains the watchers for the xds resources and when an update is received, it either requests
 * referenced resources or updates the XdsConfig and notifies the XdsConfigWatcher.
 */
@SuppressWarnings("unused") // TODO remove when changes for A74 are fully implemented
final class XdsDependencyManager implements XdsConfig.XdsClusterSubscriptionRegistry {
  private final XdsClient xdsClient;
  private final XdsConfigWatcher xdsConfigWatcher;
  private final SynchronizationContext syncContext;
  private final String dataPlaneAuthority;
  private final Map<String, Set<ClusterSubscription>> clusterSubscriptions = new HashMap<>();

  private final InternalLogId logId;
  private final XdsLogger logger;
  private XdsConfig lastXdsConfig = null;
  private final Map<XdsResourceType<?>, TypeWatchers<?>> resourceWatchers = new HashMap<>();

  XdsDependencyManager(XdsClient xdsClient, XdsConfigWatcher xdsConfigWatcher,
                              SynchronizationContext syncContext, String dataPlaneAuthority,
                              String listenerName) {
    logId = InternalLogId.allocate("xds-dependency-manager", listenerName);
    logger = XdsLogger.withLogId(logId);
    this.xdsClient = xdsClient;
    this.xdsConfigWatcher = xdsConfigWatcher;
    this.syncContext = syncContext;
    this.dataPlaneAuthority = dataPlaneAuthority;

    // start the ball rolling
    addWatcher(new LdsWatcher(listenerName));
  }

  @Override
  public ClusterSubscription subscribeToCluster(String clusterName) {
    checkNotNull(clusterName, "clusterName");
    ClusterSubscription subscription = new ClusterSubscription(clusterName);

    Set<ClusterSubscription> localSubscriptions =
        clusterSubscriptions.computeIfAbsent(clusterName, k -> new HashSet<>());
    localSubscriptions.add(subscription);
    addWatcher(new CdsWatcher(clusterName));

    return subscription;
  }

  @SuppressWarnings("unchecked")
  private <T extends ResourceUpdate> void addWatcher(XdsWatcherBase<T> watcher) {
    XdsResourceType<T> type = watcher.type;
    String resourceName = watcher.resourceName;

    this.syncContext.execute(() -> {
      TypeWatchers<T> typeWatchers = (TypeWatchers<T>)resourceWatchers.get(type);
      if (typeWatchers == null) {
        typeWatchers = new TypeWatchers<>(type);
        resourceWatchers.put(type, typeWatchers);
      }

      typeWatchers.add(resourceName, watcher);
      xdsClient.watchXdsResource(type, resourceName, watcher);
    });
  }

  private <T extends ResourceUpdate> void cancelWatcher(XdsWatcherBase<T> watcher) {
    XdsResourceType<T> type = watcher.type;
    String resourceName = watcher.resourceName;

    this.syncContext.execute(() -> {
      TypeWatchers<T> typeWatchers = (TypeWatchers<T>)resourceWatchers.get(type);
      if (typeWatchers == null) {
        logger.log(DEBUG, "Trying to cancel watcher {0}, but type not watched", watcher);
        return;
      }

      typeWatchers.watchers.remove(resourceName);
      xdsClient.cancelXdsResourceWatch(type, resourceName, watcher);
    });

  }

  public void shutdown() {
    for (TypeWatchers<?> watchers : resourceWatchers.values()) {
      shutdownWatchersForType(watchers);
    }
  }

  private <T extends ResourceUpdate> void shutdownWatchersForType(TypeWatchers<T> watchers) {
    for (Map.Entry<String, XdsWatcherBase<T>> watcherEntry : watchers.watchers.entrySet()) {
      xdsClient.cancelXdsResourceWatch(watchers.resourceType, watcherEntry.getKey(),
          watcherEntry.getValue());
    }
  }

  private void releaseSubscription(ClusterSubscription subscription) {
    checkNotNull(subscription, "subscription");
    String clusterName = subscription.getClusterName();
    Set<ClusterSubscription> subscriptions = clusterSubscriptions.get(clusterName);
    if (subscriptions == null) {
      logger.log(DEBUG, "Subscription already released for {0}", clusterName);
      return;
    }

    subscriptions.remove(subscription);
    if (subscriptions.isEmpty()) {
      clusterSubscriptions.remove(clusterName);
      // TODO release XdsClient watches for this cluster and its endpoint
      maybePublishConfig();
    }
  }

  /**
   * Check if all resources have results, and if so, generate a new XdsConfig and send it to all
   * the watchers.
   */
  private void maybePublishConfig() {
    boolean waitingOnResource = resourceWatchers.values().stream()
        .flatMap(typeWatchers -> typeWatchers.watchers.values().stream())
        .anyMatch(watcher -> !watcher.hasResult());
    if (waitingOnResource) {
      return;
    }

    buildConfig();
    xdsConfigWatcher.onUpdate(lastXdsConfig);
  }

  private void buildConfig() {
    XdsConfig.XdsConfigBuilder builder = new XdsConfig.XdsConfigBuilder();

    // Iterate watchers and build the XdsConfig

    // Will only be 1 listener and 1 route resource
    resourceWatchers.get(XdsListenerResource.getInstance()).watchers.values().stream()
        .map(watcher -> (LdsWatcher) watcher)
        .forEach(watcher -> builder.setListener(watcher.getData().getValue()));

    resourceWatchers.get(XdsRouteConfigureResource.getInstance()).watchers.values().stream()
        .map(watcher -> (RdsWatcher) watcher)
        .forEach(watcher -> builder.setRoute(watcher.getData().getValue()));

    Map<String, ? extends XdsWatcherBase<?>> edsWatchers =
        resourceWatchers.get(XdsEndpointResource.getInstance()).watchers;
    Map<String, ? extends XdsWatcherBase<?>> cdsWatchers =
        resourceWatchers.get(XdsClusterResource.getInstance()).watchers;

    // Iterate CDS watchers
    for (XdsWatcherBase<?> watcher : cdsWatchers.values()) {
      CdsWatcher cdsWatcher = (CdsWatcher) watcher;
      String clusterName = cdsWatcher.resourceName();
      StatusOr<XdsClusterResource.CdsUpdate> cdsUpdate = cdsWatcher.getData();
      if (cdsUpdate.hasValue()) {
        XdsConfig.XdsClusterConfig clusterConfig;
        String edsName = cdsUpdate.getValue().edsServiceName();
        EdsWatcher edsWatcher = (EdsWatcher) edsWatchers.get(edsName);
        assert edsWatcher != null;
        clusterConfig = new XdsConfig.XdsClusterConfig(clusterName, cdsUpdate.getValue(),
            edsWatcher.getData());
        builder.addCluster(clusterName, StatusOr.fromValue(clusterConfig));
      } else {
        builder.addCluster(clusterName, StatusOr.fromStatus(cdsUpdate.getStatus()));
      }
    }

    lastXdsConfig = builder.build();
  }

  @Override
  public String toString() {
    return logId.toString();
  }

  private static class TypeWatchers<T extends ResourceUpdate> {
    Map<String, XdsWatcherBase<T>> watchers;
    XdsResourceType<T> resourceType;

    TypeWatchers(XdsResourceType<T> resourceType) {
      watchers = new HashMap<>();
      this.resourceType = resourceType;
    }

    public void add(String resourceName, XdsWatcherBase<T> watcher) {
      watchers.put(resourceName, watcher);
    }
  }


  public interface XdsConfigWatcher {

    void onUpdate(XdsConfig config);

    // These 2 methods are invoked when there is an error or
    // does-not-exist on LDS or RDS only.  The context will be a
    // human-readable string indicating the scope in which the error
    // occurred (e.g., the resource type and name).
    void onError(String resourceContext, Status status);

    void onResourceDoesNotExist(String resourceContext);
  }

  private class ClusterSubscription implements Closeable {
    String clusterName;

    public ClusterSubscription(String clusterName) {
      this.clusterName = clusterName;
    }

    public String getClusterName() {
      return clusterName;
    }

    @Override
    public void close() throws IOException {
      releaseSubscription(this);
    }
  }

  @SuppressWarnings("ClassCanBeStatic")
  private abstract class XdsWatcherBase<T extends ResourceUpdate>
      implements ResourceWatcher<T> {
    private final XdsResourceType<T> type;
    private final String resourceName;
    @Nullable
    protected StatusOr<T> data;
    protected boolean transientError = false;


    private XdsWatcherBase(XdsResourceType<T> type, String resourceName) {
      this.type = type;
      this.resourceName = resourceName;
    }

    @Override
    public void onError(Status error) {
      checkNotNull(error, "error");
      data = StatusOr.fromStatus(error);
      transientError = true;
    }

    protected void handleDoesNotExist(String resourceName) {
      checkArgument(this.resourceName.equals(resourceName), "Resource name does not match");
      data = StatusOr.fromStatus(
          Status.UNAVAILABLE.withDescription("No " + type + " resource: " + resourceName));
      transientError = false;
    }

    boolean hasResult() {
      return data != null;
    }

    @Nullable
    StatusOr<T> getData() {
      return data;
    }

    String resourceName() {
      return resourceName;
    }

    protected void setData(T data) {
      checkNotNull(data, "data");
      this.data = StatusOr.fromValue(data);
      transientError = false;
    }

    boolean isTransientError() {
      return data != null && !data.hasValue() && transientError;
    }

    String toContextString() {
      return type + " resource: " + resourceName;
    }
  }

  private class LdsWatcher extends XdsWatcherBase<XdsListenerResource.LdsUpdate> {
    String rdsName;
    XdsListenerResource.LdsUpdate currentLdsUpdate;

    private LdsWatcher(String resourceName) {
      super(XdsListenerResource.getInstance(), resourceName);
    }

    @Override
    public void onChanged(XdsListenerResource.LdsUpdate update) {
      HttpConnectionManager httpConnectionManager = update.httpConnectionManager();
      List<VirtualHost> virtualHosts = httpConnectionManager.virtualHosts();
      String rdsName = httpConnectionManager.rdsName();

      boolean changedRdsName = rdsName != null && !rdsName.equals(this.rdsName);
      if (changedRdsName) {
        cleanUpRdsWatcher();
      }

      if (virtualHosts != null) {
        updateRoutes(virtualHosts, httpConnectionManager.httpMaxStreamDurationNano(),
            httpConnectionManager.httpFilterConfigs());
      } else if (changedRdsName) {
        this.rdsName = rdsName;
        addWatcher(new RdsWatcher(rdsName));
        logger.log(XdsLogger.XdsLogLevel.INFO, "Start watching RDS resource {0}", rdsName);
      }
      // TODO: process the update and add an RdsWatcher if needed
      //   If none needed call maybePublishConfig()
    }

    @Override
    public void onError(Status error) {
      super.onError(error);
      xdsConfigWatcher.onError(toContextString(), error);
    }

    @Override
    public void onResourceDoesNotExist(String resourceName) {
      handleDoesNotExist(resourceName);
      xdsConfigWatcher.onResourceDoesNotExist(toContextString());
    }

    // called in syncContext // TODO should be made available to RdsWatcher
    private void updateRoutes(List<VirtualHost> virtualHosts, long httpMaxStreamDurationNano,
                              @Nullable List<Filter.NamedFilterConfig> filterConfigs) {
      String authority = dataPlaneAuthority;

      // TODO this is a copy from XdsNameResolver, change to what we need here.
      VirtualHost virtualHost = RoutingUtils.findVirtualHostForHostName(virtualHosts, authority);
      if (virtualHost == null) {
        String error = "Failed to find virtual host matching hostname: " + authority;
        logger.log(XdsLogger.XdsLogLevel.WARNING, error);
        cleanUpRoutes(error);
        return;
      }

      List<VirtualHost.Route> routes = virtualHost.routes();

      // Populate all clusters to which requests can be routed to through the virtual host.
      Set<String> clusters = new HashSet<>();
      // uniqueName -> clusterName
      Map<String, String> clusterNameMap = new HashMap<>();
      // uniqueName -> pluginConfig
      Map<String, RouteLookupServiceClusterSpecifierPlugin.RlsPluginConfig> rlsPluginConfigMap = new HashMap<>();
      for (VirtualHost.Route route : routes) {
        VirtualHost.Route.RouteAction action = route.routeAction();
        String prefixedName;
        if (action != null) {
          if (action.cluster() != null) {
            prefixedName = prefixedClusterName(action.cluster());
            clusters.add(prefixedName);
            clusterNameMap.put(prefixedName, action.cluster());
          } else if (action.weightedClusters() != null) {
            for (VirtualHost.Route.RouteAction.ClusterWeight weighedCluster : action.weightedClusters()) {
              prefixedName = prefixedClusterName(weighedCluster.name());
              clusters.add(prefixedName);
              clusterNameMap.put(prefixedName, weighedCluster.name());
            }
          } else if (action.namedClusterSpecifierPluginConfig() != null) {
            ClusterSpecifierPlugin.PluginConfig pluginConfig = action.namedClusterSpecifierPluginConfig().config();
            if (pluginConfig instanceof RouteLookupServiceClusterSpecifierPlugin.RlsPluginConfig) {
              prefixedName = prefixedClusterSpecifierPluginName(
                  action.namedClusterSpecifierPluginConfig().name());
              clusters.add(prefixedName);
              rlsPluginConfigMap.put(prefixedName, (RouteLookupServiceClusterSpecifierPlugin.RlsPluginConfig) pluginConfig);
            }
          }
        }
      }

      // Updates channel's load balancing config whenever the set of selectable clusters changes.
      boolean shouldUpdateResult = existingClusters == null;
      Set<String> addedClusters =
          existingClusters == null ? clusters : Sets.difference(clusters, existingClusters);
      Set<String> deletedClusters =
          existingClusters == null
          ? Collections.emptySet() : Sets.difference(existingClusters, clusters);
      existingClusters = clusters;
      for (String cluster : addedClusters) {
        if (clusterRefs.containsKey(cluster)) {
          clusterRefs.get(cluster).refCount.incrementAndGet();
        } else {
          if (clusterNameMap.containsKey(cluster)) {
            clusterRefs.put(
                cluster,
                XdsNameResolver.ClusterRefState.forCluster(new AtomicInteger(1), clusterNameMap.get(cluster)));
          }
          if (rlsPluginConfigMap.containsKey(cluster)) {
            clusterRefs.put(
                cluster,
                XdsNameResolver.ClusterRefState.forRlsPlugin(
                    new AtomicInteger(1), rlsPluginConfigMap.get(cluster)));
          }
          shouldUpdateResult = true;
        }
      }
      for (String cluster : clusters) {
        RouteLookupServiceClusterSpecifierPlugin.RlsPluginConfig rlsPluginConfig = rlsPluginConfigMap.get(cluster);
        if (!Objects.equals(rlsPluginConfig, clusterRefs.get(cluster).rlsPluginConfig)) {
          XdsNameResolver.ClusterRefState newClusterRefState =
              XdsNameResolver.ClusterRefState.forRlsPlugin(clusterRefs.get(cluster).refCount, rlsPluginConfig);
          clusterRefs.put(cluster, newClusterRefState);
          shouldUpdateResult = true;
        }
      }
      // Update service config to include newly added clusters.
      if (shouldUpdateResult) {
        updateResolutionResult();
      }
      // Make newly added clusters selectable by config selector and deleted clusters no longer
      // selectable.
      routingConfig =
          new XdsNameResolver.RoutingConfig(
              httpMaxStreamDurationNano, routes, filterConfigs,
              virtualHost.filterConfigOverrides());
      shouldUpdateResult = false;
      for (String cluster : deletedClusters) {
        int count = clusterRefs.get(cluster).refCount.decrementAndGet();
        if (count == 0) {
          clusterRefs.remove(cluster);
          shouldUpdateResult = true;
        }
      }
      if (shouldUpdateResult) {
        updateResolutionResult();
      }
    }

    private void cleanUpRdsWatcher() {
      TypeWatchers<?> watchers = resourceWatchers.get(XdsRouteConfigureResource.getInstance());
      if (watchers == null) {
        return;
      }
      RdsWatcher oldRdsWatcher = (RdsWatcher) watchers.watchers.remove(rdsName);
      if (oldRdsWatcher != null) {
        cancelWatcher(oldRdsWatcher);
      }
    }
  }

  private class RdsWatcher extends XdsWatcherBase<RdsUpdate> {

    public RdsWatcher(String resourceName) {
      super(XdsRouteConfigureResource.getInstance(), resourceName);
    }

    @Override
    public void onChanged(RdsUpdate update) {
      // TODO: process the update and add CdsWatchers for all virtual hosts as needed
      //  If none needed call maybePublishConfig()
    }

    @Override
    public void onError(Status error) {
      super.onError(error);
      xdsConfigWatcher.onError(toContextString(), error);
    }

    @Override
    public void onResourceDoesNotExist(String resourceName) {
      handleDoesNotExist(resourceName);
      xdsConfigWatcher.onResourceDoesNotExist(toContextString());
    }
  }

  private class CdsWatcher extends XdsWatcherBase<XdsClusterResource.CdsUpdate> {

    private CdsWatcher(String resourceName) {
      super(XdsClusterResource.getInstance(), resourceName);
    }

    @Override
    public void onChanged(XdsClusterResource.CdsUpdate update) {
      // TODO: process the update and add an EdsWatcher if needed
      //  else call maybePublishConfig()
    }

    @Override
    public void onResourceDoesNotExist(String resourceName) {
      handleDoesNotExist(resourceName);
    }

  }

  private class EdsWatcher extends XdsWatcherBase<XdsEndpointResource.EdsUpdate> {
    private EdsWatcher(String resourceName) {
      super(XdsEndpointResource.getInstance(), resourceName);
    }

    @Override
    public void onChanged(XdsEndpointResource.EdsUpdate update) {
      // TODO: process the update
      maybePublishConfig();
    }

    @Override
    public void onResourceDoesNotExist(String resourceName) {
      handleDoesNotExist(resourceName);
    }
  }
}
