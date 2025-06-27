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
import static com.google.common.base.Preconditions.checkState;
import static io.grpc.xds.client.XdsClient.ResourceUpdate;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
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
import io.grpc.xds.client.XdsResourceType;
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
  private static final int MAX_CLUSTER_RECURSION_DEPTH = 16; // Specified by gRFC A37
  private final String listenerName;
  private final XdsClient xdsClient;
  private final SynchronizationContext syncContext;
  private final String dataPlaneAuthority;
  private XdsConfigWatcher xdsConfigWatcher;

  private StatusOr<XdsConfig> lastUpdate = null;
  private final Map<XdsResourceType<?>, TypeWatchers<?>> resourceWatchers = new HashMap<>();
  private final Set<ClusterSubscription> subscriptions = new HashSet<>();

  XdsDependencyManager(XdsClient xdsClient,
                       SynchronizationContext syncContext, String dataPlaneAuthority,
                       String listenerName, NameResolver.Args nameResolverArgs,
                       ScheduledExecutorService scheduler) {
    this.listenerName = checkNotNull(listenerName, "listenerName");
    this.xdsClient = checkNotNull(xdsClient, "xdsClient");
    this.syncContext = checkNotNull(syncContext, "syncContext");
    this.dataPlaneAuthority = checkNotNull(dataPlaneAuthority, "dataPlaneAuthority");
    checkNotNull(nameResolverArgs, "nameResolverArgs");
    checkNotNull(scheduler, "scheduler");
  }

  public static String toContextStr(String typeName, String resourceName) {
    return typeName + " resource " + resourceName;
  }

  public void start(XdsConfigWatcher xdsConfigWatcher) {
    checkState(this.xdsConfigWatcher == null, "dep manager may not be restarted");
    this.xdsConfigWatcher = checkNotNull(xdsConfigWatcher, "xdsConfigWatcher");
    // start the ball rolling
    syncContext.execute(() -> addWatcher(new LdsWatcher(listenerName)));
  }

  @Override
  public XdsConfig.Subscription subscribeToCluster(String clusterName) {
    checkState(this.xdsConfigWatcher != null, "dep manager must first be started");
    checkNotNull(clusterName, "clusterName");
    ClusterSubscription subscription = new ClusterSubscription(clusterName);

    syncContext.execute(() -> {
      if (getWatchers(XdsListenerResource.getInstance()).isEmpty()) {
        subscription.closed = true;
        return; // shutdown() called
      }
      subscriptions.add(subscription);
      addClusterWatcher(clusterName);
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

  public void shutdown() {
    syncContext.execute(() -> {
      for (TypeWatchers<?> watchers : resourceWatchers.values()) {
        shutdownWatchersForType(watchers);
      }
      resourceWatchers.clear();
      subscriptions.clear();
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
    syncContext.execute(() -> {
      if (subscription.closed) {
        return;
      }
      subscription.closed = true;
      if (!subscriptions.remove(subscription)) {
        return; // shutdown() called
      }
      maybePublishConfig();
    });
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
    // Create a config and discard any watchers not accessed
    WatcherTracer tracer = new WatcherTracer(resourceWatchers);
    StatusOr<XdsConfig> config = buildUpdate(
        tracer, listenerName, dataPlaneAuthority, subscriptions);
    tracer.closeUnusedWatchers();
    return config;
  }

  private static StatusOr<XdsConfig> buildUpdate(
      WatcherTracer tracer,
      String listenerName,
      String dataPlaneAuthority,
      Set<ClusterSubscription> subscriptions) {
    XdsConfig.XdsConfigBuilder builder = new XdsConfig.XdsConfigBuilder();

    // Iterate watchers and build the XdsConfig

    XdsWatcherBase<XdsListenerResource.LdsUpdate> ldsWatcher
        = tracer.getWatcher(XdsListenerResource.getInstance(), listenerName);
    if (ldsWatcher == null) {
      return StatusOr.fromStatus(Status.UNAVAILABLE.withDescription(
          "Bug: No listener watcher found for " + listenerName));
    }
    if (!ldsWatcher.getData().hasValue()) {
      return StatusOr.fromStatus(ldsWatcher.getData().getStatus());
    }
    XdsListenerResource.LdsUpdate ldsUpdate = ldsWatcher.getData().getValue();
    builder.setListener(ldsUpdate);

    RdsUpdateSupplier routeSource = ((LdsWatcher) ldsWatcher).getRouteSource(tracer);
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
    for (String cluster : getClusterNamesFromVirtualHost(activeVirtualHost)) {
      addConfigForCluster(clusters, cluster, ancestors, tracer);
    }
    for (ClusterSubscription subscription : subscriptions) {
      addConfigForCluster(clusters, subscription.getClusterName(), ancestors, tracer);
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

  private static void addConfigForCluster(
      Map<String, StatusOr<XdsConfig.XdsClusterConfig>> clusters,
      String clusterName,
      @SuppressWarnings("NonApiType") // Need order-preserving set for errors
      LinkedHashSet<String> ancestors,
      WatcherTracer tracer) {
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

    CdsWatcher cdsWatcher = (CdsWatcher) tracer.getWatcher(CLUSTER_RESOURCE, clusterName);
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
          addConfigForCluster(clusters, childCluster, ancestors, tracer);
          StatusOr<XdsConfig.XdsClusterConfig> config = clusters.get(childCluster);
          if (!config.hasValue()) {
            // gRFC A37 says: If any of a CDS policy's watchers reports that the resource does not
            // exist the policy should report that it is in TRANSIENT_FAILURE. If any of the
            // watchers reports a transient ADS stream error, the policy should report that it is in
            // TRANSIENT_FAILURE if it has never passed a config to its child.
            //
            // But there's currently disagreement about whether that is actually what we want, and
            // that was not originally implemented in gRPC Java. So we're keeping Java's old
            // behavior for now and only failing the "leaves" (which is a bit arbitrary for a
            // cycle).
            leafNames.add(childCluster);
            continue;
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
            tracer.getWatcher(ENDPOINT_RESOURCE, cdsWatcher.getEdsServiceName());
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
    if (clusters.containsKey(clusterName)) {
      // If a cycle is detected, we'll have detected it while recursing, so now there will be a key
      // present. We don't want to overwrite it with a non-error value.
      return;
    }
    clusters.put(clusterName, StatusOr.fromValue(
        new XdsConfig.XdsClusterConfig(clusterName, cdsUpdate, child)));
  }

  private void addRdsWatcher(String resourceName) {
    if (getWatchers(XdsRouteConfigureResource.getInstance()).containsKey(resourceName)) {
      return;
    }

    addWatcher(new RdsWatcher(resourceName));
  }

  private void addEdsWatcher(String edsServiceName) {
    if (getWatchers(XdsEndpointResource.getInstance()).containsKey(edsServiceName)) {
      return;
    }

    addWatcher(new EdsWatcher(edsServiceName));
  }

  private void addClusterWatcher(String clusterName) {
    if (getWatchers(CLUSTER_RESOURCE).containsKey(clusterName)) {
      return;
    }

    addWatcher(new CdsWatcher(clusterName));
  }

  private void updateRoutes(List<VirtualHost> virtualHosts) {
    VirtualHost virtualHost =
        RoutingUtils.findVirtualHostForHostName(virtualHosts, dataPlaneAuthority);
    Set<String> newClusters = getClusterNamesFromVirtualHost(virtualHost);
    newClusters.forEach((cluster) -> addClusterWatcher(cluster));
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

  private final class ClusterSubscription implements XdsConfig.Subscription {
    private final String clusterName;
    boolean closed; // Accessed from syncContext

    public ClusterSubscription(String clusterName) {
      this.clusterName = checkNotNull(clusterName, "clusterName");
    }

    String getClusterName() {
      return clusterName;
    }

    @Override
    public void close() {
      releaseSubscription(this);
    }
  }

  /** State for tracing garbage collector. */
  private static final class WatcherTracer {
    private final Map<XdsResourceType<?>, TypeWatchers<?>> resourceWatchers;
    private final Map<XdsResourceType<?>, TypeWatchers<?>> usedWatchers;

    public WatcherTracer(Map<XdsResourceType<?>, TypeWatchers<?>> resourceWatchers) {
      this.resourceWatchers = resourceWatchers;

      this.usedWatchers = new HashMap<>();
      for (XdsResourceType<?> type : resourceWatchers.keySet()) {
        usedWatchers.put(type, newTypeWatchers(type));
      }
    }

    private static <T extends ResourceUpdate> TypeWatchers<T> newTypeWatchers(
        XdsResourceType<T> type) {
      return new TypeWatchers<T>(type);
    }

    public <T extends ResourceUpdate> XdsWatcherBase<T> getWatcher(
        XdsResourceType<T> resourceType, String name) {
      TypeWatchers<?> typeWatchers = resourceWatchers.get(resourceType);
      if (typeWatchers == null) {
        return null;
      }
      assert typeWatchers.resourceType == resourceType;
      @SuppressWarnings("unchecked")
      TypeWatchers<T> tTypeWatchers = (TypeWatchers<T>) typeWatchers;
      XdsWatcherBase<T> watcher = tTypeWatchers.watchers.get(name);
      if (watcher == null) {
        return null;
      }
      @SuppressWarnings("unchecked")
      TypeWatchers<T> usedTypeWatchers = (TypeWatchers<T>) usedWatchers.get(resourceType);
      usedTypeWatchers.watchers.put(name, watcher);
      return watcher;
    }

    /** Shut down unused watchers. */
    public void closeUnusedWatchers() {
      boolean changed = false; // Help out the GC by preferring old objects
      for (XdsResourceType<?> type : resourceWatchers.keySet()) {
        TypeWatchers<?> orig = resourceWatchers.get(type);
        TypeWatchers<?> used = usedWatchers.get(type);
        for (String name : orig.watchers.keySet()) {
          if (used.watchers.containsKey(name)) {
            continue;
          }
          orig.watchers.get(name).close();
          changed = true;
        }
      }
      if (changed) {
        resourceWatchers.putAll(usedWatchers);
      }
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

    public void close() {
      cancelled = true;
      xdsClient.cancelXdsResourceWatch(type, resourceName, this);
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
      if (httpConnectionManager == null) {
        // TCP listener. Unsupported config
        virtualHosts = Collections.emptyList(); // Not null, to not delegate to RDS
      } else {
        virtualHosts = httpConnectionManager.virtualHosts();
      }
      if (virtualHosts != null) {
        updateRoutes(virtualHosts);
      }

      String rdsName = getRdsName(update);
      if (rdsName != null) {
        addRdsWatcher(rdsName);
      }

      setData(update);
      maybePublishConfig();
    }

    private String getRdsName(XdsListenerResource.LdsUpdate update) {
      HttpConnectionManager httpConnectionManager = update.httpConnectionManager();
      if (httpConnectionManager == null) {
        // TCP listener. Unsupported config
        return null;
      }
      return httpConnectionManager.rdsName();
    }

    private RdsWatcher getRdsWatcher(XdsListenerResource.LdsUpdate update, WatcherTracer tracer) {
      String rdsName = getRdsName(update);
      if (rdsName == null) {
        return null;
      }
      return (RdsWatcher) tracer.getWatcher(XdsRouteConfigureResource.getInstance(), rdsName);
    }

    public RdsUpdateSupplier getRouteSource(WatcherTracer tracer) {
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
      RdsWatcher rdsWatcher = getRdsWatcher(getData().getValue(), tracer);
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
      setData(update);
      updateRoutes(update.virtualHosts);
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
    CdsWatcher(String resourceName) {
      super(CLUSTER_RESOURCE, checkNotNull(resourceName, "resourceName"));
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
          addEdsWatcher(getEdsServiceName());
          break;
        case LOGICAL_DNS:
          setData(update);
          // no eds needed
          break;
        case AGGREGATE:
          setData(update);
          update.prioritizedClusterNames()
              .forEach(name -> addClusterWatcher(name));
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
    private EdsWatcher(String resourceName) {
      super(ENDPOINT_RESOURCE, checkNotNull(resourceName, "resourceName"));
    }

    @Override
    public void onChanged(XdsEndpointResource.EdsUpdate update) {
      if (cancelled) {
        return;
      }
      setData(checkNotNull(update, "update"));
      maybePublishConfig();
    }
  }
}
