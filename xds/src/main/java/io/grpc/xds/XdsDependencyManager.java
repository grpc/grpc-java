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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
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
  public static final StatusOr<XdsEndpointResource.EdsUpdate> LOGICAL_DNS_NOT_IMPLEMENTED =
      StatusOr.fromStatus(Status.UNAVAILABLE.withDescription("Logical DNS not implemented"));
  private final XdsClient xdsClient;
  private final XdsConfigWatcher xdsConfigWatcher;
  private final SynchronizationContext syncContext;
  private final String dataPlaneAuthority;

  private final InternalLogId logId;
  private final XdsLogger logger;
  private XdsConfig lastXdsConfig = null;
  private final Map<XdsResourceType<?>, TypeWatchers<?>> resourceWatchers = new HashMap<>();

  XdsDependencyManager(XdsClient xdsClient, XdsConfigWatcher xdsConfigWatcher,
                       SynchronizationContext syncContext, String dataPlaneAuthority,
                       String listenerName) {
    logId = InternalLogId.allocate("xds-dependency-manager", listenerName);
    logger = XdsLogger.withLogId(logId);
    this.xdsClient = checkNotNull(xdsClient, "xdsClient");
    this.xdsConfigWatcher = checkNotNull(xdsConfigWatcher, "xdsConfigWatcher");
    this.syncContext = checkNotNull(syncContext, "syncContext");
    this.dataPlaneAuthority = checkNotNull(dataPlaneAuthority, "dataPlaneAuthority");

    // start the ball rolling
    syncContext.execute(() -> addWatcher(new LdsWatcher(listenerName)));
  }

  public static String  toContextStr(String typeName, String resourceName) {
    return typeName + " resource: " + resourceName;
  }

  @Override
  public Closeable subscribeToCluster(String clusterName) {

    checkNotNull(clusterName, "clusterName");
    ClusterSubscription subscription = new ClusterSubscription(clusterName);

    syncContext.execute(() -> {
      addClusterWatcher(clusterName, subscription, 1);
      maybePublishConfig();
    });

    return subscription;
  }

  private <T extends ResourceUpdate> void addWatcher(XdsWatcherBase<T> watcher) {
    syncContext.throwIfNotInThisSynchronizationContext();
    XdsResourceType<T> type = watcher.type;
    String resourceName = watcher.resourceName;

    @SuppressWarnings("unchecked")
    TypeWatchers<T> typeWatchers = (TypeWatchers<T>)resourceWatchers.get(type);
    if (typeWatchers == null) {
      typeWatchers = new TypeWatchers<>(type);
      resourceWatchers.put(type, typeWatchers);
    }

    typeWatchers.add(resourceName, watcher);
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

    @SuppressWarnings("unchecked")
    TypeWatchers<T> typeWatchers = (TypeWatchers<T>)resourceWatchers.get(type);
    if (typeWatchers == null) {
      logger.log(DEBUG, "Trying to cancel watcher {0}, but type not watched", watcher);
      return;
    }

    typeWatchers.watchers.remove(resourceName);
    xdsClient.cancelXdsResourceWatch(type, resourceName, watcher);

  }

  private static void throwIfParentContextsNotEmpty(XdsWatcherBase<?> watcher) {
    if (watcher instanceof CdsWatcher) {
      CdsWatcher cdsWatcher = (CdsWatcher) watcher;
      if (!cdsWatcher.parentContexts.isEmpty()) {
        String msg = String.format("CdsWatcher %s has parent contexts %s",
            cdsWatcher.resourceName(), cdsWatcher.parentContexts.keySet());
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
    }
  }

  private void releaseSubscription(ClusterSubscription subscription) {
    checkNotNull(subscription, "subscription");
    String clusterName = subscription.getClusterName();
    syncContext.execute(() -> {
      XdsWatcherBase<?> cdsWatcher =
          resourceWatchers.get(CLUSTER_RESOURCE).watchers.get(clusterName);
      if (cdsWatcher == null) {
        return; // already released while waiting for the syncContext
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
        String edsServiceName = cdsUpdate.edsServiceName();
        EdsWatcher edsWatcher =
            (EdsWatcher) resourceWatchers.get(ENDPOINT_RESOURCE).watchers.get(edsServiceName);
        cancelEdsWatcher(edsWatcher, root);
        break;
      case AGGREGATE:
        for (String cluster : cdsUpdate.prioritizedClusterNames()) {
          CdsWatcher clusterWatcher =
              (CdsWatcher) resourceWatchers.get(CLUSTER_RESOURCE).watchers.get(cluster);
          if (clusterWatcher != null) {
            cancelClusterWatcherTree(clusterWatcher, root);
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
    syncContext.throwIfNotInThisSynchronizationContext();
    boolean waitingOnResource = resourceWatchers.values().stream()
        .flatMap(typeWatchers -> typeWatchers.watchers.values().stream())
        .anyMatch(XdsWatcherBase::missingResult);
    if (waitingOnResource) {
      return;
    }

    XdsConfig newConfig = buildConfig();
    if (Objects.equals(newConfig, lastXdsConfig)) {
      return;
    }
    lastXdsConfig = newConfig;
    xdsConfigWatcher.onUpdate(lastXdsConfig);
  }

  @VisibleForTesting
  XdsConfig buildConfig() {
    XdsConfig.XdsConfigBuilder builder = new XdsConfig.XdsConfigBuilder();

    // Iterate watchers and build the XdsConfig

    // Will only be 1 listener and 1 route resource
    VirtualHost activeVirtualHost = getActiveVirtualHost();
    for (XdsWatcherBase<?> xdsWatcherBase :
        resourceWatchers.get(XdsListenerResource.getInstance()).watchers.values()) {
      XdsListenerResource.LdsUpdate ldsUpdate = ((LdsWatcher) xdsWatcherBase).getData().getValue();
      builder.setListener(ldsUpdate);
      if (activeVirtualHost == null) {
        activeVirtualHost = RoutingUtils.findVirtualHostForHostName(
            ldsUpdate.httpConnectionManager().virtualHosts(), dataPlaneAuthority);
      }

      if (ldsUpdate.httpConnectionManager() != null
          && ldsUpdate.httpConnectionManager().virtualHosts() != null) {
        RdsUpdate rdsUpdate = new RdsUpdate(ldsUpdate.httpConnectionManager().virtualHosts());
        builder.setRoute(rdsUpdate);
      }
    }

    if (resourceWatchers.containsKey(XdsRouteConfigureResource.getInstance())) {
      resourceWatchers.get(XdsRouteConfigureResource.getInstance()).watchers.values().stream()
          .map(watcher -> (RdsWatcher) watcher)
          .forEach(watcher -> builder.setRoute(watcher.getData().getValue()));
    }

    if (activeVirtualHost != null) {
      builder.setVirtualHost(activeVirtualHost);
    }


    @SuppressWarnings("unchecked")
    Map<String, ? extends XdsWatcherBase<?>> edsWatchers =
        resourceWatchers.containsKey(ENDPOINT_RESOURCE)
        ? resourceWatchers.get(ENDPOINT_RESOURCE).watchers
        : Collections.EMPTY_MAP;

    @SuppressWarnings("unchecked")
    Map<String, ? extends XdsWatcherBase<?>> cdsWatchers =
        resourceWatchers.containsKey(CLUSTER_RESOURCE)
        ? resourceWatchers.get(CLUSTER_RESOURCE).watchers
        : Collections.EMPTY_MAP;

    // Only care about aggregates from LDS/RDS or subscriptions and the leaf clusters
    List<String> topLevelClusters =
        cdsWatchers.values().stream()
            .filter(XdsDependencyManager::isTopLevelCluster)
            .map(w -> w.resourceName())
            .collect(Collectors.toList());

    // Flatten multi-level aggregates into lists of leaf clusters
    Set<String> leafNames =
        addTopLevelClustersToBuilder(builder, edsWatchers, cdsWatchers, topLevelClusters);

    addLeavesToBuilder(builder, edsWatchers, leafNames);

    return builder.build();
  }

  private void addLeavesToBuilder(XdsConfig.XdsConfigBuilder builder,
                                  Map<String, ? extends XdsWatcherBase<?>> edsWatchers,
                                  Set<String> leafNames) {
    for (String clusterName : leafNames) {
      CdsWatcher cdsWatcher = getCluster(clusterName);
      StatusOr<XdsClusterResource.CdsUpdate> cdsUpdateOr = cdsWatcher.getData();

      if (cdsUpdateOr.hasValue()) {
        XdsClusterResource.CdsUpdate cdsUpdate = cdsUpdateOr.getValue();
        if (cdsUpdate.clusterType() == ClusterType.EDS) {
          EdsWatcher edsWatcher = (EdsWatcher) edsWatchers.get(cdsUpdate.edsServiceName());
          if (edsWatcher != null) {
            EndpointConfig child = new EndpointConfig(edsWatcher.getData());
            builder.addCluster(clusterName, StatusOr.fromValue(
                new XdsConfig.XdsClusterConfig(clusterName, cdsUpdate, child)));
          } else {
            builder.addCluster(clusterName, StatusOr.fromStatus(Status.UNAVAILABLE.withDescription(
                "EDS resource not found for cluster " + clusterName)));
          }
        } else if (cdsUpdate.clusterType() == ClusterType.LOGICAL_DNS) {
          // TODO get the resolved endpoint configuration
          builder.addCluster(clusterName, StatusOr.fromValue(
              new XdsConfig.XdsClusterConfig(
                  clusterName, cdsUpdate, new EndpointConfig(LOGICAL_DNS_NOT_IMPLEMENTED))));
        }
      } else {
        builder.addCluster(clusterName, StatusOr.fromStatus(cdsUpdateOr.getStatus()));
      }
    }
  }

  // Adds the top-level clusters to the builder and returns the leaf cluster names
  private Set<String> addTopLevelClustersToBuilder(
      XdsConfig.XdsConfigBuilder builder, Map<String, ? extends XdsWatcherBase<?>> edsWatchers,
      Map<String, ? extends XdsWatcherBase<?>> cdsWatchers, List<String> topLevelClusters) {

    Set<String> leafClusterNames = new HashSet<>();
    for (String clusterName : topLevelClusters) {
      CdsWatcher cdsWatcher = (CdsWatcher) cdsWatchers.get(clusterName);
      StatusOr<XdsClusterResource.CdsUpdate> cdsWatcherDataOr = cdsWatcher.getData();
      if (!cdsWatcher.hasDataValue()) {
        builder.addCluster(clusterName, StatusOr.fromStatus(cdsWatcherDataOr.getStatus()));
        continue;
      }

      XdsClusterResource.CdsUpdate cdsUpdate = cdsWatcherDataOr.getValue();
      XdsConfig.XdsClusterConfig.ClusterChild child;
      switch (cdsUpdate.clusterType()) {
        case AGGREGATE:
          List<String> leafNames = getLeafNames(cdsUpdate);
          child = new AggregateConfig(leafNames);
          leafClusterNames.addAll(leafNames);
          break;
        case EDS:
          EdsWatcher edsWatcher = (EdsWatcher) edsWatchers.get(cdsUpdate.edsServiceName());
          if (edsWatcher != null) {
            child = new EndpointConfig(edsWatcher.getData());
          } else {
            builder.addCluster(clusterName, StatusOr.fromStatus(Status.UNAVAILABLE.withDescription(
                "EDS resource not found for cluster " + clusterName)));
            continue;
          }
          break;
        case LOGICAL_DNS:
          // TODO get the resolved endpoint configuration
          child = new EndpointConfig(LOGICAL_DNS_NOT_IMPLEMENTED);
          break;
        default:
          throw new IllegalStateException("Unexpected value: " + cdsUpdate.clusterType());
      }
      builder.addCluster(clusterName, StatusOr.fromValue(
          new XdsConfig.XdsClusterConfig(clusterName, cdsUpdate, child)));
    }

    return leafClusterNames;
  }

  private List<String> getLeafNames(XdsClusterResource.CdsUpdate cdsUpdate) {
    List<String> childNames = new ArrayList<>();

    for (String cluster : cdsUpdate.prioritizedClusterNames()) {
      StatusOr<XdsClusterResource.CdsUpdate> data = getCluster(cluster).getData();
      if (data == null || !data.hasValue() || data.getValue() == null) {
        childNames.add(cluster);
        continue;
      }
      if (data.getValue().clusterType() == ClusterType.AGGREGATE) {
        childNames.addAll(getLeafNames(data.getValue()));
      } else {
        childNames.add(cluster);
      }
    }

    return childNames;
  }

  private static boolean isTopLevelCluster(XdsWatcherBase<?> cdsWatcher) {
    if (! (cdsWatcher instanceof CdsWatcher)) {
      return false;
    }
    return ((CdsWatcher)cdsWatcher).parentContexts.values().stream()
        .anyMatch(depth -> depth == 1);
  }

  @Override
  public String toString() {
    return logId.toString();
  }

  // Returns true if the watcher was added, false if it already exists
  private boolean addEdsWatcher(String edsServiceName, CdsWatcher parentContext) {
    TypeWatchers<?> typeWatchers = resourceWatchers.get(XdsEndpointResource.getInstance());
    if (typeWatchers == null || !typeWatchers.watchers.containsKey(edsServiceName)) {
      addWatcher(new EdsWatcher(edsServiceName, parentContext));
      return true;
    }

    EdsWatcher watcher = (EdsWatcher) typeWatchers.watchers.get(edsServiceName);
    watcher.addParentContext(parentContext); // Is a set, so don't need to check for existence
    return false;
  }

  private void addClusterWatcher(String clusterName, Object parentContext, int depth) {
    TypeWatchers<?> clusterWatchers = resourceWatchers.get(CLUSTER_RESOURCE);
    if (clusterWatchers != null) {
      CdsWatcher watcher = (CdsWatcher) clusterWatchers.watchers.get(clusterName);
      if (watcher != null) {
        watcher.parentContexts.put(parentContext, depth);
        return;
      }
    }

    addWatcher(new CdsWatcher(clusterName, parentContext, depth));
  }

  private boolean updateRoutes(List<VirtualHost> virtualHosts, Object newParentContext,
                            VirtualHost oldVirtualHost, boolean sameParentContext) {
    VirtualHost virtualHost =
        RoutingUtils.findVirtualHostForHostName(virtualHosts, dataPlaneAuthority);
    if (virtualHost == null) {
      String error = "Failed to find virtual host matching hostname: " + dataPlaneAuthority;
      logger.log(XdsLogger.XdsLogLevel.WARNING, error);
      cleanUpRoutes();
      xdsConfigWatcher.onError(
          "xDS node ID:" + dataPlaneAuthority, Status.UNAVAILABLE.withDescription(error));
      return false;
    }

    Set<String> newClusters = getClusterNamesFromVirtualHost(virtualHost);
    Set<String> oldClusters = getClusterNamesFromVirtualHost(oldVirtualHost);

    if (sameParentContext) {
      // Calculate diffs.
      Set<String> addedClusters = Sets.difference(newClusters, oldClusters);
      Set<String> deletedClusters = Sets.difference(oldClusters, newClusters);

      deletedClusters.forEach(watcher ->
          cancelClusterWatcherTree(getCluster(watcher), newParentContext));
      addedClusters.forEach((cluster) -> addClusterWatcher(cluster, newParentContext, 1));
    } else {
      newClusters.forEach((cluster) -> addClusterWatcher(cluster, newParentContext, 1));
    }

    return true;
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

  @Nullable
  private VirtualHost getActiveVirtualHost() {
    TypeWatchers<?> rdsWatchers = resourceWatchers.get(XdsRouteConfigureResource.getInstance());
    if (rdsWatchers == null) {
      return null;
    }

    RdsWatcher activeRdsWatcher =
        (RdsWatcher) rdsWatchers.watchers.values().stream().findFirst().orElse(null);
    if (activeRdsWatcher == null || activeRdsWatcher.missingResult()
        || !activeRdsWatcher.getData().hasValue()) {
      return null;
    }

    return RoutingUtils.findVirtualHostForHostName(
        activeRdsWatcher.getData().getValue().virtualHosts, dataPlaneAuthority);
  }

  // Must be in SyncContext
  private void cleanUpRoutes() {
    // Remove RdsWatcher & CDS Watchers
    TypeWatchers<?> rdsResourceWatcher =
        resourceWatchers.get(XdsRouteConfigureResource.getInstance());
    if (rdsResourceWatcher == null || rdsResourceWatcher.watchers.isEmpty()) {
      return;
    }

    XdsWatcherBase<?> watcher = rdsResourceWatcher.watchers.values().stream().findFirst().get();
    cancelWatcher(watcher);

    // Remove CdsWatchers pointed to by the RdsWatcher
    RdsWatcher rdsWatcher = (RdsWatcher) watcher;
    for (String cName : rdsWatcher.getCdsNames()) {
      CdsWatcher cdsWatcher = getCluster(cName);
      if (cdsWatcher != null) {
        cancelClusterWatcherTree(cdsWatcher, rdsWatcher);
      }
    }
  }

  private CdsWatcher getCluster(String clusterName) {
    return (CdsWatcher) resourceWatchers.get(CLUSTER_RESOURCE).watchers.get(clusterName);
  }

  private static class TypeWatchers<T extends ResourceUpdate> {
    // Key is resource name
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
      setDataAsStatus(error);
      // Don't update configuration on error, but if this was the last thing that we were waiting
      // for, we should publish the config.
      if (lastXdsConfig == null) {
        maybePublishConfig();
      }
    }

    protected void handleDoesNotExist(String resourceName) {
      checkArgument(this.resourceName.equals(resourceName), "Resource name does not match");
      setDataAsStatus(Status.UNAVAILABLE.withDescription("No " + toContextString()));
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

    String toContextString() {
      return toContextStr(type.typeName(), resourceName);
    }
  }

  private class LdsWatcher extends XdsWatcherBase<XdsListenerResource.LdsUpdate> {
    String rdsName;

    private LdsWatcher(String resourceName) {
      super(XdsListenerResource.getInstance(), resourceName);
    }

    @Override
    public void onChanged(XdsListenerResource.LdsUpdate update) {
      checkNotNull(update, "update");

      HttpConnectionManager httpConnectionManager = update.httpConnectionManager();
      List<VirtualHost> virtualHosts = httpConnectionManager.virtualHosts();
      String rdsName = httpConnectionManager.rdsName();
      VirtualHost activeVirtualHost = getActiveVirtualHost();

      boolean changedRdsName = !Objects.equals(rdsName, this.rdsName);
      if (changedRdsName) {
        cleanUpRdsWatcher();
      }

      if (virtualHosts != null) {
        // No RDS watcher since we are getting RDS updates via LDS
        boolean updateSuccessful = updateRoutes(virtualHosts, this, activeVirtualHost, this.rdsName == null);
        this.rdsName = null;
        if (!updateSuccessful) {
          lastXdsConfig = null;
          return;
        }

      } else if (changedRdsName) {
        lastXdsConfig = null;
        this.rdsName = rdsName;
        addWatcher(new RdsWatcher(rdsName));
        logger.log(XdsLogger.XdsLogLevel.INFO, "Start watching RDS resource {0}", rdsName);
      }

      setData(update);
      if (virtualHosts != null || changedRdsName) {
        maybePublishConfig();
      }
    }

    @Override
    public void onError(Status error) {
      super.onError(checkNotNull(error, "error"));
      lastXdsConfig = null; //When we get a good update, we will publish it
      xdsConfigWatcher.onError(toContextString(), error);
    }

    @Override
    public void onResourceDoesNotExist(String resourceName) {
      if (cancelled) {
        return;
      }

      handleDoesNotExist(resourceName);
      cleanUpRdsWatcher();
      rdsName = null;
      lastXdsConfig = null; // Publishing an empty result
      xdsConfigWatcher.onResourceDoesNotExist(toContextString());
    }

    private void cleanUpRdsWatcher() {
      RdsWatcher oldRdsWatcher = getRdsWatcher();
      if (oldRdsWatcher != null) {
        cancelWatcher(oldRdsWatcher);
        logger.log(XdsLogger.XdsLogLevel.DEBUG, "Stop watching RDS resource {0}", rdsName);

        // Cleanup clusters (as appropriate) that had the old rds watcher as a parent
        if (!oldRdsWatcher.hasDataValue() || !oldRdsWatcher.getData().hasValue()
            || resourceWatchers.get(CLUSTER_RESOURCE) == null) {
          return;
        }
        for (XdsWatcherBase<?> watcher :
            resourceWatchers.get(CLUSTER_RESOURCE).watchers.values()) {
          cancelCdsWatcher((CdsWatcher) watcher, oldRdsWatcher);
        }
      }
    }

    private RdsWatcher getRdsWatcher() {
      TypeWatchers<?> watchers = resourceWatchers.get(XdsRouteConfigureResource.getInstance());
      if (watchers == null || rdsName == null || watchers.watchers.isEmpty()) {
        return null;
      }

      return (RdsWatcher) watchers.watchers.get(rdsName);
    }
  }

  private class RdsWatcher extends XdsWatcherBase<RdsUpdate> {

    public RdsWatcher(String resourceName) {
      super(XdsRouteConfigureResource.getInstance(), checkNotNull(resourceName, "resourceName"));
    }

    @Override
    public void onChanged(RdsUpdate update) {
      checkNotNull(update, "update");
      RdsUpdate oldData = hasDataValue() ? getData().getValue() : null;
      VirtualHost oldVirtualHost =
          (oldData != null)
          ? RoutingUtils.findVirtualHostForHostName(oldData.virtualHosts, dataPlaneAuthority)
          : null;
      setData(update);
      if (updateRoutes(update.virtualHosts, this, oldVirtualHost, true)) {
        maybePublishConfig();
      }
    }

    @Override
    public void onError(Status error) {
      super.onError(checkNotNull(error, "error"));
      xdsConfigWatcher.onError(toContextString(), error);
      lastXdsConfig = null; // will publish when we get a good update
    }

    @Override
    public void onResourceDoesNotExist(String resourceName) {
      if (cancelled) {
        return;
      }

      handleDoesNotExist(checkNotNull(resourceName, "resourceName"));
      xdsConfigWatcher.onResourceDoesNotExist(toContextString());
      lastXdsConfig = null; // Published an empty result
    }

    ImmutableList<String> getCdsNames() {
      if (!hasDataValue() || getData().getValue().virtualHosts == null) {
        return ImmutableList.of();
      }

      return ImmutableList.copyOf(getClusterNamesFromVirtualHost(getActiveVirtualHost()));
    }
  }

  private class CdsWatcher extends XdsWatcherBase<XdsClusterResource.CdsUpdate> {
    Map<Object, Integer> parentContexts = new HashMap<>();

    CdsWatcher(String resourceName, Object parentContext, int depth) {
      super(CLUSTER_RESOURCE, checkNotNull(resourceName, "resourceName"));
      this.parentContexts.put(checkNotNull(parentContext, "parentContext"), depth);
    }

    @Override
    public void onChanged(XdsClusterResource.CdsUpdate update) {
      checkNotNull(update, "update");
      switch (update.clusterType()) {
        case EDS:
          setData(update);
          if (update.edsServiceName() == null) {
            Status error = Status.UNAVAILABLE.withDescription("EDS cluster missing edsServiceName");
            setDataAsStatus(error);
            maybePublishConfig();
            return;
          }
          if (!addEdsWatcher(update.edsServiceName(), this))  {
            maybePublishConfig();
          }
          break;
        case LOGICAL_DNS:
          setData(update);
          maybePublishConfig();
          // no eds needed
          break;
        case AGGREGATE:
          Object parentContext = this;
          int depth = parentContexts.values().stream().max(Integer::compare).orElse(0) + 1;
          if (depth > MAX_CLUSTER_RECURSION_DEPTH) {
            logger.log(XdsLogger.XdsLogLevel.WARNING,
                "Cluster recursion depth limit exceeded for cluster {0}", resourceName());
            Status error = Status.UNAVAILABLE.withDescription(
                "aggregate cluster graph exceeds max depth");
            setDataAsStatus(error);
          }
          if (hasDataValue()) {
            ImmutableList<String> oldChildNames = getData().getValue().prioritizedClusterNames();
            Set<String> oldNames = oldChildNames != null
                                   ? new HashSet<>(oldChildNames)
                                   : new HashSet<>();
            ImmutableList<String> newChildNames = update.prioritizedClusterNames();
            Set<String> newNames =
                newChildNames != null ? new HashSet<>(newChildNames) : new HashSet<>();


            Set<String> deletedClusters = Sets.difference(oldNames, newNames);
            deletedClusters.forEach((cluster)
                -> cancelClusterWatcherTree(getCluster(cluster), parentContext));

            if (depth <= MAX_CLUSTER_RECURSION_DEPTH) {
              setData(update);
              Set<String> addedClusters = Sets.difference(newNames, oldNames);
              addedClusters.forEach((cluster) -> addClusterWatcher(cluster, parentContext, depth));

              if (addedClusters.isEmpty()) {
                maybePublishConfig();
              }
            } else { // data was set to error status above
              maybePublishConfig();
            }

          } else if (depth <= MAX_CLUSTER_RECURSION_DEPTH) {
            setData(update);
            update.prioritizedClusterNames()
                .forEach(name -> addClusterWatcher(name, parentContext, depth));
            maybePublishConfig();
          }
          break;
        default:
          Status error = Status.UNAVAILABLE.withDescription(
              "aggregate cluster graph exceeds max depth");
          setDataAsStatus(error);
          maybePublishConfig();
      }
    }

    @Override
    public void onResourceDoesNotExist(String resourceName) {
      if (cancelled) {
        return;
      }
      handleDoesNotExist(checkNotNull(resourceName, "resourceName"));
      maybePublishConfig();
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
      setData(checkNotNull(update, "update"));
      maybePublishConfig();
    }

    @Override
    public void onResourceDoesNotExist(String resourceName) {
      if (cancelled) {
        return;
      }
      handleDoesNotExist(checkNotNull(resourceName, "resourceName"));
      maybePublishConfig();
    }

    void addParentContext(CdsWatcher parentContext) {
      parentContexts.add(checkNotNull(parentContext, "parentContext"));
    }
  }
}
