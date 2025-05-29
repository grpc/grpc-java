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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Sets;
import io.grpc.InternalLogId;
import io.grpc.NameResolver;
import io.grpc.Status;
import io.grpc.StatusOr;
import io.grpc.SynchronizationContext;
import io.grpc.xds.VirtualHost.Route.RouteAction.ClusterWeight;
import io.grpc.xds.XdsClusterResource.CdsUpdate.ClusterType;
import io.grpc.xds.XdsConfig.XdsClusterConfig.AggregateConfig;
import io.grpc.xds.XdsConfig.XdsClusterConfig.EndpointConfig;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
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
  private static final int MAX_CLUSTER_RECURSION_DEPTH = 16; // Matches C++
  private final XdsClient xdsClient;
  private final XdsConfigWatcher xdsConfigWatcher;
  private final SynchronizationContext syncContext;
  private final String dataPlaneAuthority;

  private final InternalLogId logId;
  private final XdsLogger logger;
  private StatusOr<XdsConfig> lastUpdate = null;
  private final Map<XdsResourceType<?>, TypeWatchers<?>> resourceWatchers = new HashMap<>();

  XdsDependencyManager(XdsClient xdsClient, XdsConfigWatcher xdsConfigWatcher,
                       SynchronizationContext syncContext, String dataPlaneAuthority,
                       String listenerName, NameResolver.Args nameResolverArgs,
                       ScheduledExecutorService scheduler) {
    logId = InternalLogId.allocate("xds-dependency-manager", listenerName);
    logger = XdsLogger.withLogId(logId);
    this.xdsClient = checkNotNull(xdsClient, "xdsClient");
    this.xdsConfigWatcher = checkNotNull(xdsConfigWatcher, "xdsConfigWatcher");
    this.syncContext = checkNotNull(syncContext, "syncContext");
    this.dataPlaneAuthority = checkNotNull(dataPlaneAuthority, "dataPlaneAuthority");
    checkNotNull(nameResolverArgs, "nameResolverArgs");
    checkNotNull(scheduler, "scheduler");

    // start the ball rolling
    syncContext.execute(() -> addWatcher(new LdsWatcher(listenerName)));
  }

  public static String toContextStr(String typeName, String resourceName) {
    return typeName + " resource " + resourceName;
  }

  @Override
  public Closeable subscribeToCluster(String clusterName) {
    checkNotNull(clusterName, "clusterName");
    ClusterSubscription subscription = new ClusterSubscription(clusterName);

    syncContext.execute(() -> {
      if (getWatchers(XdsListenerResource.getInstance()).isEmpty()) {
        subscription.closed = true;
        return; // shutdown() called
      }
      addClusterWatcher(clusterName, subscription);
    });

    return subscription;
  }

  private <T extends ResourceUpdate> void addWatcher(XdsWatcherBase<T> watcher) {
    syncContext.throwIfNotInThisSynchronizationContext();
    XdsResourceType<T> type = watcher.type;
    String resourceName = watcher.resourceName;

    getWatchers(type).put(resourceName, watcher);
    xdsClient.watchXdsResource(type, resourceName, watcher, syncContext);
  }

  private void cancelCdsWatcher(CdsWatcher watcher, Object parentContext) {
    if (watcher == null) {
      return;
    }
    watcher.parentContexts.remove(parentContext);
    if (watcher.parentContexts.isEmpty()) {
      cancelWatcher(watcher);
    }
  }

  private void cancelEdsWatcher(EdsWatcher watcher, CdsWatcher parentContext) {
    if (watcher == null) {
      return;
    }
    watcher.parentContexts.remove(parentContext);
    if (watcher.parentContexts.isEmpty()) {
      cancelWatcher(watcher);
    }
  }

  private <T extends ResourceUpdate> void cancelWatcher(XdsWatcherBase<T> watcher) {
    syncContext.throwIfNotInThisSynchronizationContext();

    if (watcher == null) {
      return;
    }

    if (watcher instanceof CdsWatcher || watcher instanceof EdsWatcher) {
      throwIfParentContextsNotEmpty(watcher);
    }

    watcher.cancelled = true;
    XdsResourceType<T> type = watcher.type;
    String resourceName = watcher.resourceName;

    if (getWatchers(type).remove(resourceName) == null) {
      logger.log(DEBUG, "Trying to cancel watcher {0}, but it isn't watched", watcher);
      return;
    }

    xdsClient.cancelXdsResourceWatch(type, resourceName, watcher);
  }

  private static void throwIfParentContextsNotEmpty(XdsWatcherBase<?> watcher) {
    if (watcher instanceof CdsWatcher) {
      CdsWatcher cdsWatcher = (CdsWatcher) watcher;
      if (!cdsWatcher.parentContexts.isEmpty()) {
        String msg = String.format("CdsWatcher %s has parent contexts %s",
            cdsWatcher.resourceName(), cdsWatcher.parentContexts);
        throw new IllegalStateException(msg);
      }
    } else if (watcher instanceof EdsWatcher) {
      EdsWatcher edsWatcher = (EdsWatcher) watcher;
      if (!edsWatcher.parentContexts.isEmpty()) {
        String msg = String.format("CdsWatcher %s has parent contexts %s",
            edsWatcher.resourceName(), edsWatcher.parentContexts);
        throw new IllegalStateException(msg);
      }
    }
  }

  public void shutdown() {
    syncContext.execute(() -> {
      for (TypeWatchers<?> watchers : resourceWatchers.values()) {
        shutdownWatchersForType(watchers);
      }
      resourceWatchers.clear();
    });
  }

  private <T extends ResourceUpdate> void shutdownWatchersForType(TypeWatchers<T> watchers) {
    for (Map.Entry<String, XdsWatcherBase<T>> watcherEntry : watchers.watchers.entrySet()) {
      xdsClient.cancelXdsResourceWatch(watchers.resourceType, watcherEntry.getKey(),
          watcherEntry.getValue());
      watcherEntry.getValue().cancelled = true;
    }
  }

  private void releaseSubscription(ClusterSubscription subscription) {
    checkNotNull(subscription, "subscription");
    String clusterName = subscription.getClusterName();
    syncContext.execute(() -> {
      if (subscription.closed) {
        return;
      }
      subscription.closed = true;
      XdsWatcherBase<XdsClusterResource.CdsUpdate> cdsWatcher
          = getWatchers(CLUSTER_RESOURCE).get(clusterName);
      if (cdsWatcher == null) {
        return; // shutdown() called
      }
      cancelClusterWatcherTree((CdsWatcher) cdsWatcher, subscription);
      maybePublishConfig();
    });
  }

  private void cancelClusterWatcherTree(CdsWatcher root, Object parentContext) {
    checkNotNull(root, "root");

    cancelCdsWatcher(root, parentContext);

    if (!root.hasDataValue() || !root.parentContexts.isEmpty()) {
      return;
    }

    XdsClusterResource.CdsUpdate cdsUpdate = root.getData().getValue();
    switch (cdsUpdate.clusterType()) {
      case EDS:
        String edsServiceName = root.getEdsServiceName();
        EdsWatcher edsWatcher = (EdsWatcher) getWatchers(ENDPOINT_RESOURCE).get(edsServiceName);
        cancelEdsWatcher(edsWatcher, root);
        break;
      case AGGREGATE:
        for (String cluster : cdsUpdate.prioritizedClusterNames()) {
          CdsWatcher clusterWatcher = (CdsWatcher) getWatchers(CLUSTER_RESOURCE).get(cluster);
          if (clusterWatcher != null) {
            cancelClusterWatcherTree(clusterWatcher, root);
          }
        }
        break;
      case LOGICAL_DNS:
        // no eds needed, so everything happens in cancelCdsWatcher()
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
    syncContext.throwIfNotInThisSynchronizationContext();
    if (getWatchers(XdsListenerResource.getInstance()).isEmpty()) {
      return; // shutdown() called
    }
    boolean waitingOnResource = resourceWatchers.values().stream()
        .flatMap(typeWatchers -> typeWatchers.watchers.values().stream())
        .anyMatch(XdsWatcherBase::missingResult);
    if (waitingOnResource) {
      return;
    }

    StatusOr<XdsConfig> newUpdate = buildUpdate();
    if (Objects.equals(newUpdate, lastUpdate)) {
      return;
    }
    assert newUpdate.hasValue()
        || (newUpdate.getStatus().getCode() == Status.Code.UNAVAILABLE
            || newUpdate.getStatus().getCode() == Status.Code.INTERNAL);
    lastUpdate = newUpdate;
    xdsConfigWatcher.onUpdate(lastUpdate);
  }

  @VisibleForTesting
  StatusOr<XdsConfig> buildUpdate() {
    XdsConfig.XdsConfigBuilder builder = new XdsConfig.XdsConfigBuilder();

    // Iterate watchers and build the XdsConfig

    // Will only be 1 listener and 1 route resource
    RdsUpdateSupplier routeSource = null;
    for (XdsWatcherBase<XdsListenerResource.LdsUpdate> ldsWatcher :
        getWatchers(XdsListenerResource.getInstance()).values()) {
      if (!ldsWatcher.getData().hasValue()) {
        return StatusOr.fromStatus(ldsWatcher.getData().getStatus());
      }
      XdsListenerResource.LdsUpdate ldsUpdate = ldsWatcher.getData().getValue();
      builder.setListener(ldsUpdate);
      routeSource = ((LdsWatcher) ldsWatcher).getRouteSource();
    }

    if (routeSource == null) {
      return StatusOr.fromStatus(Status.UNAVAILABLE.withDescription(
          "Bug: No route source found for listener " + dataPlaneAuthority));
    }

    StatusOr<RdsUpdate> statusOrRdsUpdate = routeSource.getRdsUpdate();
    if (!statusOrRdsUpdate.hasValue()) {
      return StatusOr.fromStatus(statusOrRdsUpdate.getStatus());
    }
    RdsUpdate rdsUpdate = statusOrRdsUpdate.getValue();
    builder.setRoute(rdsUpdate);

    VirtualHost activeVirtualHost =
        RoutingUtils.findVirtualHostForHostName(rdsUpdate.virtualHosts, dataPlaneAuthority);
    if (activeVirtualHost == null) {
      String error = "Failed to find virtual host matching hostname: " + dataPlaneAuthority;
      return StatusOr.fromStatus(Status.UNAVAILABLE.withDescription(error));
    }
    builder.setVirtualHost(activeVirtualHost);

    Map<String, StatusOr<XdsConfig.XdsClusterConfig>> clusters = new HashMap<>();
    LinkedHashSet<String> ancestors = new LinkedHashSet<>();
    for (String cluster : getWatchers(CLUSTER_RESOURCE).keySet()) {
      addConfigForCluster(clusters, cluster, ancestors);
    }
    for (Map.Entry<String, StatusOr<XdsConfig.XdsClusterConfig>> me : clusters.entrySet()) {
      builder.addCluster(me.getKey(), me.getValue());
    }

    return StatusOr.fromValue(builder.build());
  }

  private <T extends ResourceUpdate> Map<String, XdsWatcherBase<T>> getWatchers(
      XdsResourceType<T> resourceType) {
    TypeWatchers<?> typeWatchers = resourceWatchers.get(resourceType);
    if (typeWatchers == null) {
      typeWatchers = new TypeWatchers<T>(resourceType);
      resourceWatchers.put(resourceType, typeWatchers);
    }
    assert typeWatchers.resourceType == resourceType;
    @SuppressWarnings("unchecked")
    TypeWatchers<T> tTypeWatchers = (TypeWatchers<T>) typeWatchers;
    return tTypeWatchers.watchers;
  }

  private void addConfigForCluster(
      Map<String, StatusOr<XdsConfig.XdsClusterConfig>> clusters,
      String clusterName,
      @SuppressWarnings("NonApiType") // Need order-preserving set for errors
      LinkedHashSet<String> ancestors) {
    if (clusters.containsKey(clusterName)) {
      return;
    }
    if (ancestors.contains(clusterName)) {
      clusters.put(clusterName, StatusOr.fromStatus(
          Status.INTERNAL.withDescription(
              "Aggregate cluster cycle detected: " + ancestors)));
      return;
    }
    if (ancestors.size() > MAX_CLUSTER_RECURSION_DEPTH) {
      clusters.put(clusterName, StatusOr.fromStatus(
          Status.INTERNAL.withDescription("Recursion limit reached: " + ancestors)));
      return;
    }

    CdsWatcher cdsWatcher = (CdsWatcher) getWatchers(CLUSTER_RESOURCE).get(clusterName);
    StatusOr<XdsClusterResource.CdsUpdate> cdsWatcherDataOr = cdsWatcher.getData();
    if (!cdsWatcherDataOr.hasValue()) {
      clusters.put(clusterName, StatusOr.fromStatus(cdsWatcherDataOr.getStatus()));
      return;
    }

    XdsClusterResource.CdsUpdate cdsUpdate = cdsWatcherDataOr.getValue();
    XdsConfig.XdsClusterConfig.ClusterChild child;
    switch (cdsUpdate.clusterType()) {
      case AGGREGATE:
        // Re-inserting a present element into a LinkedHashSet does not reorder the entries, so it
        // preserves the priority across all aggregate clusters
        LinkedHashSet<String> leafNames = new LinkedHashSet<String>();
        ancestors.add(clusterName);
        for (String childCluster : cdsUpdate.prioritizedClusterNames()) {
          addConfigForCluster(clusters, childCluster, ancestors);
          StatusOr<XdsConfig.XdsClusterConfig> config = clusters.get(childCluster);
          if (!config.hasValue()) {
            clusters.put(clusterName, StatusOr.fromStatus(Status.INTERNAL.withDescription(
                "Unable to get leaves for " + clusterName + ": "
                + config.getStatus().getDescription())));
            return;
          }
          XdsConfig.XdsClusterConfig.ClusterChild children = config.getValue().getChildren();
          if (children instanceof AggregateConfig) {
            leafNames.addAll(((AggregateConfig) children).getLeafNames());
          } else {
            leafNames.add(childCluster);
          }
        }
        ancestors.remove(clusterName);

        child = new AggregateConfig(ImmutableList.copyOf(leafNames));
        break;
      case EDS:
        XdsWatcherBase<XdsEndpointResource.EdsUpdate> edsWatcher =
            getWatchers(ENDPOINT_RESOURCE).get(cdsWatcher.getEdsServiceName());
        if (edsWatcher != null) {
          child = new EndpointConfig(edsWatcher.getData());
        } else {
          child = new EndpointConfig(StatusOr.fromStatus(Status.INTERNAL.withDescription(
              "EDS resource not found for cluster " + clusterName)));
        }
        break;
      case LOGICAL_DNS:
        // TODO get the resolved endpoint configuration
        child = new EndpointConfig(StatusOr.fromStatus(
            Status.INTERNAL.withDescription("Logical DNS in dependency manager unsupported")));
        break;
      default:
        throw new IllegalStateException("Unexpected value: " + cdsUpdate.clusterType());
    }
    clusters.put(clusterName, StatusOr.fromValue(
        new XdsConfig.XdsClusterConfig(clusterName, cdsUpdate, child)));
  }

  @Override
  public String toString() {
    return logId.toString();
  }

  private void addEdsWatcher(String edsServiceName, CdsWatcher parentContext) {
    EdsWatcher watcher
        = (EdsWatcher) getWatchers(XdsEndpointResource.getInstance()).get(edsServiceName);
    if (watcher != null) {
      watcher.addParentContext(parentContext); // Is a set, so don't need to check for existence
      return;
    }

    addWatcher(new EdsWatcher(edsServiceName, parentContext));
  }

  private void addClusterWatcher(String clusterName, Object parentContext) {
    CdsWatcher watcher = (CdsWatcher) getWatchers(CLUSTER_RESOURCE).get(clusterName);
    if (watcher != null) {
      watcher.parentContexts.add(parentContext);
      return;
    }

    addWatcher(new CdsWatcher(clusterName, parentContext));
  }

  private void updateRoutes(List<VirtualHost> virtualHosts, Object newParentContext,
                            List<VirtualHost> oldVirtualHosts, boolean sameParentContext) {
    VirtualHost oldVirtualHost =
        RoutingUtils.findVirtualHostForHostName(oldVirtualHosts, dataPlaneAuthority);
    VirtualHost virtualHost =
        RoutingUtils.findVirtualHostForHostName(virtualHosts, dataPlaneAuthority);

    Set<String> newClusters = getClusterNamesFromVirtualHost(virtualHost);
    Set<String> oldClusters = getClusterNamesFromVirtualHost(oldVirtualHost);

    if (sameParentContext) {
      // Calculate diffs.
      Set<String> addedClusters = Sets.difference(newClusters, oldClusters);
      Set<String> deletedClusters = Sets.difference(oldClusters, newClusters);

      deletedClusters.forEach(watcher ->
          cancelClusterWatcherTree(getCluster(watcher), newParentContext));
      addedClusters.forEach((cluster) -> addClusterWatcher(cluster, newParentContext));
    } else {
      newClusters.forEach((cluster) -> addClusterWatcher(cluster, newParentContext));
    }
  }

  private String nodeInfo() {
    return " nodeID: " + xdsClient.getBootstrapInfo().node().getId();
  }

  private static Set<String> getClusterNamesFromVirtualHost(VirtualHost virtualHost) {
    if (virtualHost == null) {
      return Collections.emptySet();
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

    return clusters;
  }

  private CdsWatcher getCluster(String clusterName) {
    return (CdsWatcher) getWatchers(CLUSTER_RESOURCE).get(clusterName);
  }

  private static class TypeWatchers<T extends ResourceUpdate> {
    // Key is resource name
    final Map<String, XdsWatcherBase<T>> watchers = new HashMap<>();
    final XdsResourceType<T> resourceType;

    TypeWatchers(XdsResourceType<T> resourceType) {
      this.resourceType = resourceType;
    }
  }

  public interface XdsConfigWatcher {
    /**
     * An updated XdsConfig or RPC-safe Status. The status code will be either UNAVAILABLE or
     * INTERNAL.
     */
    void onUpdate(StatusOr<XdsConfig> config);
  }

  private final class ClusterSubscription implements Closeable {
    private final String clusterName;
    boolean closed; // Accessed from syncContext

    public ClusterSubscription(String clusterName) {
      this.clusterName = checkNotNull(clusterName, "clusterName");
    }

    String getClusterName() {
      return clusterName;
    }

    @Override
    public void close() throws IOException {
      releaseSubscription(this);
    }
  }

  private abstract class XdsWatcherBase<T extends ResourceUpdate>
      implements ResourceWatcher<T> {
    private final XdsResourceType<T> type;
    private final String resourceName;
    boolean cancelled;

    @Nullable
    private StatusOr<T> data;


    private XdsWatcherBase(XdsResourceType<T> type, String resourceName) {
      this.type = checkNotNull(type, "type");
      this.resourceName = checkNotNull(resourceName, "resourceName");
    }

    @Override
    public void onError(Status error) {
      checkNotNull(error, "error");
      if (cancelled) {
        return;
      }
      // Don't update configuration on error, if we've already received configuration
      if (!hasDataValue()) {
        setDataAsStatus(Status.UNAVAILABLE.withDescription(
            String.format("Error retrieving %s: %s: %s",
              toContextString(), error.getCode(), error.getDescription())));
        maybePublishConfig();
      }
    }

    @Override
    public void onResourceDoesNotExist(String resourceName) {
      if (cancelled) {
        return;
      }

      checkArgument(this.resourceName.equals(resourceName), "Resource name does not match");
      setDataAsStatus(Status.UNAVAILABLE.withDescription(
          toContextString() + " does not exist" + nodeInfo()));
      maybePublishConfig();
    }

    boolean missingResult() {
      return data == null;
    }

    @Nullable
    StatusOr<T> getData() {
      return data;
    }

    boolean hasDataValue() {
      return data != null && data.hasValue();
    }

    String resourceName() {
      return resourceName;
    }

    protected void setData(T data) {
      checkNotNull(data, "data");
      this.data = StatusOr.fromValue(data);
    }

    protected void setDataAsStatus(Status status) {
      checkNotNull(status, "status");
      this.data = StatusOr.fromStatus(status);
    }

    public String toContextString() {
      return toContextStr(type.typeName(), resourceName);
    }
  }

  private interface RdsUpdateSupplier {
    StatusOr<RdsUpdate> getRdsUpdate();
  }

  private class LdsWatcher extends XdsWatcherBase<XdsListenerResource.LdsUpdate>
      implements RdsUpdateSupplier {
    String rdsName;

    private LdsWatcher(String resourceName) {
      super(XdsListenerResource.getInstance(), resourceName);
    }

    @Override
    public void onChanged(XdsListenerResource.LdsUpdate update) {
      checkNotNull(update, "update");
      if (cancelled) {
        return;
      }

      HttpConnectionManager httpConnectionManager = update.httpConnectionManager();
      List<VirtualHost> virtualHosts;
      String rdsName;
      if (httpConnectionManager == null) {
        // TCP listener. Unsupported config
        virtualHosts = Collections.emptyList(); // Not null, to not delegate to RDS
        rdsName = null;
      } else {
        virtualHosts = httpConnectionManager.virtualHosts();
        rdsName = httpConnectionManager.rdsName();
      }
      StatusOr<RdsUpdate> activeRdsUpdate = getRouteSource().getRdsUpdate();
      List<VirtualHost> activeVirtualHosts = activeRdsUpdate.hasValue()
          ? activeRdsUpdate.getValue().virtualHosts
          : Collections.emptyList();

      boolean changedRdsName = !Objects.equals(rdsName, this.rdsName);
      if (changedRdsName) {
        cleanUpRdsWatcher();
      }

      if (virtualHosts != null) {
        // No RDS watcher since we are getting RDS updates via LDS
        updateRoutes(virtualHosts, this, activeVirtualHosts, this.rdsName == null);
        this.rdsName = null;
      } else if (changedRdsName) {
        this.rdsName = rdsName;
        addWatcher(new RdsWatcher(rdsName));
        logger.log(XdsLogger.XdsLogLevel.INFO, "Start watching RDS resource {0}", rdsName);
      }

      setData(update);
      maybePublishConfig();
    }

    @Override
    public void onResourceDoesNotExist(String resourceName) {
      if (cancelled) {
        return;
      }

      checkArgument(resourceName().equals(resourceName), "Resource name does not match");
      setDataAsStatus(Status.UNAVAILABLE.withDescription(
          toContextString() + " does not exist" + nodeInfo()));
      cleanUpRdsWatcher();
      rdsName = null;
      maybePublishConfig();
    }

    private void cleanUpRdsWatcher() {
      RdsWatcher oldRdsWatcher = getRdsWatcher();
      if (oldRdsWatcher != null) {
        cancelWatcher(oldRdsWatcher);
        logger.log(XdsLogger.XdsLogLevel.DEBUG, "Stop watching RDS resource {0}", rdsName);

        // Cleanup clusters (as appropriate) that had the old rds watcher as a parent
        if (!oldRdsWatcher.hasDataValue()) {
          return;
        }
        for (XdsWatcherBase<XdsClusterResource.CdsUpdate> watcher :
            getWatchers(CLUSTER_RESOURCE).values()) {
          cancelCdsWatcher((CdsWatcher) watcher, oldRdsWatcher);
        }
      }
    }

    private RdsWatcher getRdsWatcher() {
      if (rdsName == null) {
        return null;
      }
      return (RdsWatcher) getWatchers(XdsRouteConfigureResource.getInstance()).get(rdsName);
    }

    public RdsUpdateSupplier getRouteSource() {
      if (!hasDataValue()) {
        return this;
      }
      HttpConnectionManager hcm = getData().getValue().httpConnectionManager();
      if (hcm == null) {
        return this;
      }
      List<VirtualHost> virtualHosts = hcm.virtualHosts();
      if (virtualHosts != null) {
        return this;
      }
      RdsWatcher rdsWatcher = getRdsWatcher();
      assert rdsWatcher != null;
      return rdsWatcher;
    }

    @Override
    public StatusOr<RdsUpdate> getRdsUpdate() {
      if (missingResult()) {
        return StatusOr.fromStatus(Status.UNAVAILABLE.withDescription("Not yet loaded"));
      }
      if (!getData().hasValue()) {
        return StatusOr.fromStatus(getData().getStatus());
      }
      HttpConnectionManager hcm = getData().getValue().httpConnectionManager();
      if (hcm == null) {
        return StatusOr.fromStatus(
            Status.UNAVAILABLE.withDescription("Not an API listener" + nodeInfo()));
      }
      List<VirtualHost> virtualHosts = hcm.virtualHosts();
      if (virtualHosts == null) {
        // Code shouldn't trigger this case, as it should be calling RdsWatcher instead. This would
        // be easily implemented with getRdsWatcher().getRdsUpdate(), but getting here is likely a
        // bug
        return StatusOr.fromStatus(Status.INTERNAL.withDescription("Routes are in RDS, not LDS"));
      }
      return StatusOr.fromValue(new RdsUpdate(virtualHosts));
    }
  }

  private class RdsWatcher extends XdsWatcherBase<RdsUpdate> implements RdsUpdateSupplier {

    public RdsWatcher(String resourceName) {
      super(XdsRouteConfigureResource.getInstance(), checkNotNull(resourceName, "resourceName"));
    }

    @Override
    public void onChanged(RdsUpdate update) {
      checkNotNull(update, "update");
      if (cancelled) {
        return;
      }
      List<VirtualHost> oldVirtualHosts = hasDataValue()
          ? getData().getValue().virtualHosts
          : Collections.emptyList();
      setData(update);
      updateRoutes(update.virtualHosts, this, oldVirtualHosts, true);
      maybePublishConfig();
    }

    @Override
    public StatusOr<RdsUpdate> getRdsUpdate() {
      if (missingResult()) {
        return StatusOr.fromStatus(Status.UNAVAILABLE.withDescription("Not yet loaded"));
      }
      return getData();
    }
  }

  private class CdsWatcher extends XdsWatcherBase<XdsClusterResource.CdsUpdate> {
    Set<Object> parentContexts = new HashSet<>();

    CdsWatcher(String resourceName, Object parentContext) {
      super(CLUSTER_RESOURCE, checkNotNull(resourceName, "resourceName"));
      this.parentContexts.add(checkNotNull(parentContext, "parentContext"));
    }

    @Override
    public void onChanged(XdsClusterResource.CdsUpdate update) {
      checkNotNull(update, "update");
      if (cancelled) {
        return;
      }
      switch (update.clusterType()) {
        case EDS:
          setData(update);
          addEdsWatcher(getEdsServiceName(), this);
          break;
        case LOGICAL_DNS:
          setData(update);
          // no eds needed
          break;
        case AGGREGATE:
          Object parentContext = this;
          if (hasDataValue()) {
            Set<String> oldNames = getData().getValue().clusterType() == ClusterType.AGGREGATE
                ? new HashSet<>(getData().getValue().prioritizedClusterNames())
                : Collections.emptySet();
            Set<String> newNames = new HashSet<>(update.prioritizedClusterNames());

            Set<String> deletedClusters = Sets.difference(oldNames, newNames);
            deletedClusters.forEach((cluster)
                -> cancelClusterWatcherTree(getCluster(cluster), parentContext));

            setData(update);
            Set<String> addedClusters = Sets.difference(newNames, oldNames);
            addedClusters.forEach((cluster) -> addClusterWatcher(cluster, parentContext));
          } else {
            setData(update);
            update.prioritizedClusterNames()
                .forEach(name -> addClusterWatcher(name, parentContext));
          }
          break;
        default:
          Status error = Status.UNAVAILABLE.withDescription(
              "unknown cluster type in " + resourceName() + " " + update.clusterType());
          setDataAsStatus(error);
      }
      maybePublishConfig();
    }

    public String getEdsServiceName() {
      XdsClusterResource.CdsUpdate cdsUpdate = getData().getValue();
      assert cdsUpdate.clusterType() == ClusterType.EDS;
      String edsServiceName = cdsUpdate.edsServiceName();
      if (edsServiceName == null) {
        edsServiceName = cdsUpdate.clusterName();
      }
      return edsServiceName;
    }
  }

  private class EdsWatcher extends XdsWatcherBase<XdsEndpointResource.EdsUpdate> {
    private final Set<CdsWatcher> parentContexts = new HashSet<>();

    private EdsWatcher(String resourceName, CdsWatcher parentContext) {
      super(ENDPOINT_RESOURCE, checkNotNull(resourceName, "resourceName"));
      parentContexts.add(checkNotNull(parentContext, "parentContext"));
    }

    @Override
    public void onChanged(XdsEndpointResource.EdsUpdate update) {
      if (cancelled) {
        return;
      }
      setData(checkNotNull(update, "update"));
      maybePublishConfig();
    }

    void addParentContext(CdsWatcher parentContext) {
      parentContexts.add(checkNotNull(parentContext, "parentContext"));
    }
  }
}
