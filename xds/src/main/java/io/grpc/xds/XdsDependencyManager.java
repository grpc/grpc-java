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
import io.grpc.xds.VirtualHost.Route.RouteAction.ClusterWeight;
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
import java.util.Set;
import javax.annotation.Nullable;

/**
 * This class acts as a layer of indirection between the XdsClient and the NameResolver. It
 * maintains the watchers for the xds resources and when an update is received, it either requests
 * referenced resources or updates the XdsConfig and notifies the XdsConfigWatcher.  Each instance
 * applies to a single data plane authority.
 */
final class XdsDependencyManager implements XdsConfig.XdsClusterSubscriptionRegistry {
  public static final XdsClusterResource CLUSTER_RESOURCE = XdsClusterResource.getInstance();
  public static final XdsEndpointResource ENDPOINT_RESOURCE = XdsEndpointResource.getInstance();
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
    this.dataPlaneAuthority = checkNotNull(dataPlaneAuthority, "dataPlaneAuthority");

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

  private boolean hasWatcher(XdsResourceType<?> type, String resourceName) {
    TypeWatchers<?> typeWatchers = resourceWatchers.get(type);
    return typeWatchers != null && typeWatchers.watchers.containsKey(resourceName);
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

  @SuppressWarnings("unchecked")
  private <T extends ResourceUpdate> void cancelWatcher(XdsWatcherBase<T> watcher) {
    if (watcher == null) {
      return;
    }

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
    resourceWatchers.clear();
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
      XdsWatcherBase<?> cdsWatcher =
          resourceWatchers.get(CLUSTER_RESOURCE).watchers.get(clusterName);
      cancelClusterWatcherTree((CdsWatcher) cdsWatcher);

      maybePublishConfig();
    }
  }

  private void cancelClusterWatcherTree(CdsWatcher root) {
    checkNotNull(root, "root");
    cancelWatcher(root);

    if (root.getData() == null || !root.getData().hasValue()) {
      return;
    }

    XdsClusterResource.CdsUpdate cdsUpdate = root.getData().getValue();
    switch (cdsUpdate.clusterType()) {
      case EDS:
        String edsServiceName = cdsUpdate.edsServiceName();
        EdsWatcher edsWatcher =
            (EdsWatcher) resourceWatchers.get(ENDPOINT_RESOURCE).watchers.get(edsServiceName);
        cancelWatcher(edsWatcher);
        break;
      case AGGREGATE:
        for (String cluster : cdsUpdate.prioritizedClusterNames()) {
          CdsWatcher clusterWatcher =
              (CdsWatcher) resourceWatchers.get(CLUSTER_RESOURCE).watchers.get(cluster);
          if (clusterWatcher != null) {
            cancelClusterWatcherTree(clusterWatcher);
          }
        }
        break;
      case LOGICAL_DNS:
        // no eds needed
        break;
      default:
        throw new AssertionError("Unknown cluster type: " + cdsUpdate.clusterType());
    }
  }

  /**
   * Check if all resources have results, and if so, generate a new XdsConfig and send it to all
   * the watchers.
   */
  private void maybePublishConfig() {
    syncContext.execute(() -> {
      boolean waitingOnResource = resourceWatchers.values().stream()
          .flatMap(typeWatchers -> typeWatchers.watchers.values().stream())
          .anyMatch(watcher -> !watcher.hasResult());
      if (waitingOnResource) {
        return;
      }

      buildConfig();
      xdsConfigWatcher.onUpdate(lastXdsConfig);
    });
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
        resourceWatchers.get(ENDPOINT_RESOURCE).watchers;
    Map<String, ? extends XdsWatcherBase<?>> cdsWatchers =
        resourceWatchers.get(CLUSTER_RESOURCE).watchers;

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
    final Map<String, XdsWatcherBase<T>> watchers = new HashMap<>();
    final XdsResourceType<T> resourceType;

    TypeWatchers(XdsResourceType<T> resourceType) {
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

  @SuppressWarnings({"ClassCanBeStatic", "unused"})
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

    private LdsWatcher(String resourceName) {
      super(XdsListenerResource.getInstance(), resourceName);
    }

    @Override
    public void onChanged(XdsListenerResource.LdsUpdate update) {
      HttpConnectionManager httpConnectionManager = update.httpConnectionManager();
      List<VirtualHost> virtualHosts = httpConnectionManager.virtualHosts();
      String rdsName = httpConnectionManager.rdsName();

      syncContext.execute(() -> {
        boolean changedRdsName = rdsName != null && !rdsName.equals(this.rdsName);
        if (changedRdsName) {
          cleanUpRdsWatcher();
        }

        if (virtualHosts != null) {
          updateRoutes(virtualHosts);
        } else if (changedRdsName) {
          this.rdsName = rdsName;
          addWatcher(new RdsWatcher(rdsName));
          logger.log(XdsLogger.XdsLogLevel.INFO, "Start watching RDS resource {0}", rdsName);
        }

        setData(update);
        maybePublishConfig();
      });
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
      setData(update);
      syncContext.execute(() -> {
        updateRoutes(update.virtualHosts);
        maybePublishConfig();
      });
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

    CdsWatcher(String resourceName) {
      super(CLUSTER_RESOURCE, resourceName);
    }

    @Override
    public void onChanged(XdsClusterResource.CdsUpdate update) {
      syncContext.execute(() -> {
        switch (update.clusterType()) {
          case EDS:
            setData(update);
            if (!hasWatcher(ENDPOINT_RESOURCE, update.edsServiceName())) {
              addWatcher(new EdsWatcher(update.edsServiceName()));
            } else {
              maybePublishConfig();
            }
            break;
          case LOGICAL_DNS:
            setData(update);
            maybePublishConfig();
            // no eds needed
            break;
          case AGGREGATE:
            if (data.hasValue()) {
              Set<String> oldNames = new HashSet<>(data.getValue().prioritizedClusterNames());
              Set<String> newNames = new HashSet<>(update.prioritizedClusterNames());

              setData(update);

              Set<String> addedClusters = Sets.difference(newNames, oldNames);
              Set<String> deletedClusters = Sets.difference(oldNames, newNames);
              addedClusters.forEach((cluster) -> addWatcher(new CdsWatcher(cluster)));
              deletedClusters.forEach((cluster) -> cancelClusterWatcherTree(getCluster(cluster)));

              if (!addedClusters.isEmpty()) {
                maybePublishConfig();
              }
            } else {
              setData(update);
              update.prioritizedClusterNames().forEach(name -> addWatcher(new CdsWatcher(name)));
            }
            break;
          default:
            throw new AssertionError("Unknown cluster type: " + update.clusterType());
        }
      });
    }

    @Override
    public void onResourceDoesNotExist(String resourceName) {
      handleDoesNotExist(resourceName);
    }

  }

  private class EdsWatcher extends XdsWatcherBase<XdsEndpointResource.EdsUpdate> {
    private EdsWatcher(String resourceName) {
      super(ENDPOINT_RESOURCE, resourceName);
    }

    @Override
    public void onChanged(XdsEndpointResource.EdsUpdate update) {
      syncContext.execute(() -> {
        setData(update);
        maybePublishConfig();
      });
    }

    @Override
    public void onResourceDoesNotExist(String resourceName) {
      handleDoesNotExist(resourceName);
    }
  }

  private void updateRoutes(List<VirtualHost> virtualHosts) {
    String authority = dataPlaneAuthority;

    VirtualHost virtualHost = RoutingUtils.findVirtualHostForHostName(virtualHosts, authority);
    if (virtualHost == null) {
      String error = "Failed to find virtual host matching hostname: " + authority;
      logger.log(XdsLogger.XdsLogLevel.WARNING, error);
      cleanUpRoutes();
      xdsConfigWatcher.onError(
          "xDS node ID:" + dataPlaneAuthority, Status.UNAVAILABLE.withDescription(error));
      return;
    }

    // Get all cluster names to which requests can be routed through the virtual host.
    Set<String> clusters = new HashSet<>();
    for (VirtualHost.Route route : virtualHost.routes()) {
      VirtualHost.Route.RouteAction action = route.routeAction();
      if (action == null) {
        continue;
      }
      if (action.cluster() != null) {
        clusters.add(action.cluster());
      } else if (action.weightedClusters() != null) {
        for (ClusterWeight weighedCluster : action.weightedClusters()) {
          clusters.add(weighedCluster.name());
        }
      }
    }

    // Get existing cluster names
    TypeWatchers<?> clusterWatchers = resourceWatchers.get(CLUSTER_RESOURCE);
    Set<String> oldClusters =
        (clusterWatchers != null) ? clusterWatchers.watchers.keySet() : Collections.emptySet();

    // Calculate diffs.
    Set<String> addedClusters =
        oldClusters == null ? clusters : Sets.difference(clusters, oldClusters);
    Set<String> deletedClusters =
        oldClusters == null ? Collections.emptySet() : Sets.difference(oldClusters, clusters);

    addedClusters.forEach((cluster) -> addWatcher(new CdsWatcher(cluster)));
    deletedClusters.forEach(watcher -> cancelClusterWatcherTree(getCluster(watcher)));
  }

  // Must be in SyncContext
  private void cleanUpRoutes() {
    // Remove RdsWatcher & CDS Watchers
    TypeWatchers<?> rdsWatcher = resourceWatchers.get(XdsRouteConfigureResource.getInstance());
    if (rdsWatcher != null) {
      for (XdsWatcherBase<?> watcher : rdsWatcher.watchers.values()) {
        cancelWatcher(watcher);
      }
    }

    // Remove all CdsWatchers
    TypeWatchers<?> cdsWatcher = resourceWatchers.get(CLUSTER_RESOURCE);
    if (cdsWatcher != null) {
      for (XdsWatcherBase<?> watcher : cdsWatcher.watchers.values()) {
        cancelWatcher(watcher);
      }
    }
  }

  private CdsWatcher getCluster(String clusterName) {
    return (CdsWatcher) resourceWatchers.get(CLUSTER_RESOURCE).watchers.get(clusterName);
  }

}
