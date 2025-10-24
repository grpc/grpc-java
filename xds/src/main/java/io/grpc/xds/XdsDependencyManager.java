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
import com.google.common.collect.ImmutableMap;
import io.grpc.EquivalentAddressGroup;
import io.grpc.NameResolver;
import io.grpc.NameResolverProvider;
import io.grpc.Status;
import io.grpc.StatusOr;
import io.grpc.SynchronizationContext;
import io.grpc.internal.RetryingNameResolver;
import io.grpc.xds.Endpoints.LocalityLbEndpoints;
import io.grpc.xds.VirtualHost.Route.RouteAction.ClusterWeight;
import io.grpc.xds.XdsClusterResource.CdsUpdate.ClusterType;
import io.grpc.xds.XdsConfig.XdsClusterConfig.AggregateConfig;
import io.grpc.xds.XdsConfig.XdsClusterConfig.EndpointConfig;
import io.grpc.xds.XdsRouteConfigureResource.RdsUpdate;
import io.grpc.xds.client.Locality;
import io.grpc.xds.client.XdsClient;
import io.grpc.xds.client.XdsClient.ResourceWatcher;
import io.grpc.xds.client.XdsResourceType;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * This class acts as a layer of indirection between the XdsClient and the NameResolver. It
 * maintains the watchers for the xds resources and when an update is received, it either requests
 * referenced resources or updates the XdsConfig and notifies the XdsConfigWatcher.  Each instance
 * applies to a single data plane authority.
 */
final class XdsDependencyManager implements XdsConfig.XdsClusterSubscriptionRegistry {
  private enum TrackedWatcherTypeEnum {
    LDS, RDS, CDS, EDS, DNS
  }

  private static final TrackedWatcherType<XdsListenerResource.LdsUpdate> LDS_TYPE =
      new TrackedWatcherType<>(TrackedWatcherTypeEnum.LDS);
  private static final TrackedWatcherType<RdsUpdate> RDS_TYPE =
      new TrackedWatcherType<>(TrackedWatcherTypeEnum.RDS);
  private static final TrackedWatcherType<XdsClusterResource.CdsUpdate> CDS_TYPE =
      new TrackedWatcherType<>(TrackedWatcherTypeEnum.CDS);
  private static final TrackedWatcherType<XdsEndpointResource.EdsUpdate> EDS_TYPE =
      new TrackedWatcherType<>(TrackedWatcherTypeEnum.EDS);
  private static final TrackedWatcherType<List<EquivalentAddressGroup>> DNS_TYPE =
      new TrackedWatcherType<>(TrackedWatcherTypeEnum.DNS);

  // DNS-resolved endpoints do not have the definition of the locality it belongs to, just hardcode
  // to an empty locality.
  private static final Locality LOGICAL_DNS_CLUSTER_LOCALITY = Locality.create("", "", "");

  private static final int MAX_CLUSTER_RECURSION_DEPTH = 16; // Specified by gRFC A37

  static boolean enableLogicalDns = true;

  private final String listenerName;
  private final XdsClient xdsClient;
  private final SynchronizationContext syncContext;
  private final String dataPlaneAuthority;
  private final NameResolver.Args nameResolverArgs;
  private XdsConfigWatcher xdsConfigWatcher;

  private StatusOr<XdsConfig> lastUpdate = null;
  private final Map<TrackedWatcherTypeEnum, TypeWatchers<?>> resourceWatchers =
      new EnumMap<>(TrackedWatcherTypeEnum.class);
  private final Set<ClusterSubscription> subscriptions = new HashSet<>();

  XdsDependencyManager(
      XdsClient xdsClient,
      SynchronizationContext syncContext,
      String dataPlaneAuthority,
      String listenerName,
      NameResolver.Args nameResolverArgs) {
    this.listenerName = checkNotNull(listenerName, "listenerName");
    this.xdsClient = checkNotNull(xdsClient, "xdsClient");
    this.syncContext = checkNotNull(syncContext, "syncContext");
    this.dataPlaneAuthority = checkNotNull(dataPlaneAuthority, "dataPlaneAuthority");
    this.nameResolverArgs = checkNotNull(nameResolverArgs, "nameResolverArgs");
  }

  public static String toContextStr(String typeName, String resourceName) {
    return typeName + " resource " + resourceName;
  }

  public void start(XdsConfigWatcher xdsConfigWatcher) {
    checkState(this.xdsConfigWatcher == null, "dep manager may not be restarted");
    this.xdsConfigWatcher = checkNotNull(xdsConfigWatcher, "xdsConfigWatcher");
    // start the ball rolling
    syncContext.execute(() -> addWatcher(LDS_TYPE, new LdsWatcher(listenerName)));
  }

  @Override
  public XdsConfig.Subscription subscribeToCluster(String clusterName) {
    checkState(this.xdsConfigWatcher != null, "dep manager must first be started");
    checkNotNull(clusterName, "clusterName");
    ClusterSubscription subscription = new ClusterSubscription(clusterName);

    syncContext.execute(() -> {
      if (getWatchers(LDS_TYPE).isEmpty()) {
        subscription.closed = true;
        return; // shutdown() called
      }
      subscriptions.add(subscription);
      addClusterWatcher(clusterName);
    });

    return subscription;
  }

  /**
   * For all logical dns clusters refresh their results.
   */
  public void requestReresolution() {
    syncContext.execute(() -> {
      for (TrackedWatcher<List<EquivalentAddressGroup>> watcher : getWatchers(DNS_TYPE).values()) {
        DnsWatcher dnsWatcher = (DnsWatcher) watcher;
        dnsWatcher.refresh();
      }
    });
  }

  private <T extends ResourceUpdate> void addWatcher(
      TrackedWatcherType<T> watcherType, XdsWatcherBase<T> watcher) {
    syncContext.throwIfNotInThisSynchronizationContext();
    XdsResourceType<T> type = watcher.type;
    String resourceName = watcher.resourceName;

    getWatchers(watcherType).put(resourceName, watcher);
    xdsClient.watchXdsResource(type, resourceName, watcher, syncContext);
  }

  public void shutdown() {
    syncContext.execute(() -> {
      for (TypeWatchers<?> watchers : resourceWatchers.values()) {
        for (TrackedWatcher<?> watcher : watchers.watchers.values()) {
          watcher.close();
        }
      }
      resourceWatchers.clear();
      subscriptions.clear();
    });
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
    if (getWatchers(LDS_TYPE).isEmpty()) {
      return; // shutdown() called
    }
    boolean waitingOnResource = resourceWatchers.values().stream()
        .flatMap(typeWatchers -> typeWatchers.watchers.values().stream())
        .anyMatch(TrackedWatcher::missingResult);
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

    TrackedWatcher<XdsListenerResource.LdsUpdate> ldsWatcher
        = tracer.getWatcher(LDS_TYPE, listenerName);
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

  private <T> Map<String, TrackedWatcher<T>> getWatchers(TrackedWatcherType<T> watcherType) {
    TypeWatchers<?> typeWatchers = resourceWatchers.get(watcherType.typeEnum);
    if (typeWatchers == null) {
      typeWatchers = new TypeWatchers<T>(watcherType);
      resourceWatchers.put(watcherType.typeEnum, typeWatchers);
    }
    assert typeWatchers.watcherType == watcherType;
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

    CdsWatcher cdsWatcher = (CdsWatcher) tracer.getWatcher(CDS_TYPE, clusterName);
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
        TrackedWatcher<XdsEndpointResource.EdsUpdate> edsWatcher =
            tracer.getWatcher(EDS_TYPE, cdsWatcher.getEdsServiceName());
        if (edsWatcher != null) {
          child = new EndpointConfig(edsWatcher.getData());
        } else {
          child = new EndpointConfig(StatusOr.fromStatus(Status.INTERNAL.withDescription(
              "EDS resource not found for cluster " + clusterName)));
        }
        break;
      case LOGICAL_DNS:
        if (enableLogicalDns) {
          TrackedWatcher<List<EquivalentAddressGroup>> dnsWatcher =
              tracer.getWatcher(DNS_TYPE, cdsUpdate.dnsHostName());
          child = new EndpointConfig(dnsToEdsUpdate(dnsWatcher.getData(), cdsUpdate.dnsHostName()));
        } else {
          child = new EndpointConfig(StatusOr.fromStatus(
              Status.INTERNAL.withDescription("Logical DNS in dependency manager unsupported")));
        }
        break;
      default:
        child = new EndpointConfig(StatusOr.fromStatus(Status.UNAVAILABLE.withDescription(
              "Unknown type in cluster " + clusterName + " " + cdsUpdate.clusterType())));
    }
    if (clusters.containsKey(clusterName)) {
      // If a cycle is detected, we'll have detected it while recursing, so now there will be a key
      // present. We don't want to overwrite it with a non-error value.
      return;
    }
    clusters.put(clusterName, StatusOr.fromValue(
        new XdsConfig.XdsClusterConfig(clusterName, cdsUpdate, child)));
  }

  private static StatusOr<XdsEndpointResource.EdsUpdate> dnsToEdsUpdate(
      StatusOr<List<EquivalentAddressGroup>> dnsData, String dnsHostName) {
    if (!dnsData.hasValue()) {
      return StatusOr.fromStatus(dnsData.getStatus());
    }

    List<SocketAddress> addresses = new ArrayList<>();
    for (EquivalentAddressGroup eag : dnsData.getValue()) {
      addresses.addAll(eag.getAddresses());
    }
    EquivalentAddressGroup eag = new EquivalentAddressGroup(addresses);
    List<Endpoints.LbEndpoint> endpoints = ImmutableList.of(
        Endpoints.LbEndpoint.create(eag, 1, true, dnsHostName, ImmutableMap.of()));
    LocalityLbEndpoints lbEndpoints =
        LocalityLbEndpoints.create(endpoints, 1, 0, ImmutableMap.of());
    return StatusOr.fromValue(new XdsEndpointResource.EdsUpdate(
        "fakeEds_logicalDns",
        Collections.singletonMap(LOGICAL_DNS_CLUSTER_LOCALITY, lbEndpoints),
        new ArrayList<>()));
  }

  private void addRdsWatcher(String resourceName) {
    if (getWatchers(RDS_TYPE).containsKey(resourceName)) {
      return;
    }

    addWatcher(RDS_TYPE, new RdsWatcher(resourceName));
  }

  private void addEdsWatcher(String edsServiceName) {
    if (getWatchers(EDS_TYPE).containsKey(edsServiceName)) {
      return;
    }

    addWatcher(EDS_TYPE, new EdsWatcher(edsServiceName));
  }

  private void addClusterWatcher(String clusterName) {
    if (getWatchers(CDS_TYPE).containsKey(clusterName)) {
      return;
    }

    addWatcher(CDS_TYPE, new CdsWatcher(clusterName));
  }

  private void addDnsWatcher(String dnsHostName) {
    syncContext.throwIfNotInThisSynchronizationContext();
    if (getWatchers(DNS_TYPE).containsKey(dnsHostName)) {
      return;
    }

    DnsWatcher watcher = new DnsWatcher(dnsHostName, nameResolverArgs);
    getWatchers(DNS_TYPE).put(dnsHostName, watcher);
    watcher.start();
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

  private static NameResolver createNameResolver(
      String dnsHostName,
      NameResolver.Args nameResolverArgs) {
    URI uri;
    try {
      uri = new URI("dns", "", "/" + dnsHostName, null);
    } catch (URISyntaxException e) {
      return new FailingNameResolver(
          Status.INTERNAL.withDescription("Bug, invalid URI creation: " + dnsHostName)
            .withCause(e));
    }

    NameResolverProvider provider =
        nameResolverArgs.getNameResolverRegistry().getProviderForScheme("dns");
    if (provider == null) {
      return new FailingNameResolver(
          Status.INTERNAL.withDescription("Could not find dns name resolver"));
    }

    NameResolver bareResolver = provider.newNameResolver(uri, nameResolverArgs);
    if (bareResolver == null) {
      return new FailingNameResolver(
          Status.INTERNAL.withDescription("DNS name resolver provider returned null: " + uri));
    }
    return RetryingNameResolver.wrap(bareResolver, nameResolverArgs);
  }

  private static class TypeWatchers<T> {
    // Key is resource name
    final Map<String, TrackedWatcher<T>> watchers = new HashMap<>();
    final TrackedWatcherType<T> watcherType;

    TypeWatchers(TrackedWatcherType<T> watcherType) {
      this.watcherType = checkNotNull(watcherType, "watcherType");
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
    private final Map<TrackedWatcherTypeEnum, TypeWatchers<?>> resourceWatchers;
    private final Map<TrackedWatcherTypeEnum, TypeWatchers<?>> usedWatchers;

    public WatcherTracer(Map<TrackedWatcherTypeEnum, TypeWatchers<?>> resourceWatchers) {
      this.resourceWatchers = resourceWatchers;

      this.usedWatchers = new EnumMap<>(TrackedWatcherTypeEnum.class);
      for (Map.Entry<TrackedWatcherTypeEnum, TypeWatchers<?>> me : resourceWatchers.entrySet()) {
        usedWatchers.put(me.getKey(), newTypeWatchers(me.getValue().watcherType));
      }
    }

    private static <T> TypeWatchers<T> newTypeWatchers(TrackedWatcherType<T> type) {
      return new TypeWatchers<T>(type);
    }

    public <T> TrackedWatcher<T> getWatcher(TrackedWatcherType<T> watcherType, String name) {
      TypeWatchers<?> typeWatchers = resourceWatchers.get(watcherType.typeEnum);
      if (typeWatchers == null) {
        return null;
      }
      assert typeWatchers.watcherType == watcherType;
      @SuppressWarnings("unchecked")
      TypeWatchers<T> tTypeWatchers = (TypeWatchers<T>) typeWatchers;
      TrackedWatcher<T> watcher = tTypeWatchers.watchers.get(name);
      if (watcher == null) {
        return null;
      }
      @SuppressWarnings("unchecked")
      TypeWatchers<T> usedTypeWatchers = (TypeWatchers<T>) usedWatchers.get(watcherType.typeEnum);
      usedTypeWatchers.watchers.put(name, watcher);
      return watcher;
    }

    /** Shut down unused watchers. */
    public void closeUnusedWatchers() {
      boolean changed = false; // Help out the GC by preferring old objects
      for (TrackedWatcherTypeEnum key : resourceWatchers.keySet()) {
        TypeWatchers<?> orig = resourceWatchers.get(key);
        TypeWatchers<?> used = usedWatchers.get(key);
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

  @SuppressWarnings("UnusedTypeParameter")
  private static final class TrackedWatcherType<T> {
    public final TrackedWatcherTypeEnum typeEnum;

    public TrackedWatcherType(TrackedWatcherTypeEnum typeEnum) {
      this.typeEnum = checkNotNull(typeEnum, "typeEnum");
    }
  }

  private interface TrackedWatcher<T> {
    @Nullable
    StatusOr<T> getData();

    default boolean missingResult() {
      return getData() == null;
    }

    default boolean hasDataValue() {
      StatusOr<T> data = getData();
      return data != null && data.hasValue();
    }

    void close();
  }

  private abstract class XdsWatcherBase<T extends ResourceUpdate>
      implements ResourceWatcher<T>, TrackedWatcher<T> {
    private final XdsResourceType<T> type;
    private final String resourceName;
    boolean cancelled;

    @Nullable
    private StatusOr<T> data;
    @Nullable
    private Status ambientError; // To hold transient errors


    private XdsWatcherBase(XdsResourceType<T> type, String resourceName) {
      this.type = checkNotNull(type, "type");
      this.resourceName = checkNotNull(resourceName, "resourceName");
    }

    @Override
    public void onResourceChanged(StatusOr<T> update) {
      if (cancelled) {
        return;
      }
      ambientError = null;
      if (update.hasValue()) {
        data = update;
        subscribeToChildren(update.getValue());
      } else {
        Status status = update.getStatus();
        Status translatedStatus = Status.UNAVAILABLE.withDescription(
            String.format("Error retrieving %s: %s. Details: %s%s",
                toContextString(),
                status.getCode(),
                status.getDescription() != null ? status.getDescription() : "",
                nodeInfo()));

        data = StatusOr.fromStatus(translatedStatus);
      }
      maybePublishConfig();
    }

    @Override
    public void onAmbientError(Status error) {
      if (cancelled) {
        return;
      }
      ambientError = error.withDescription(
          String.format("Ambient error for %s: %s. Details: %s%s",
              toContextString(),
              error.getCode(),
              error.getDescription(),
              nodeInfo()));
    }

    protected abstract void subscribeToChildren(T update);

    @Override
    public void close() {
      cancelled = true;
      xdsClient.cancelXdsResourceWatch(type, resourceName, this);
    }

    @Override
    @Nullable
    public StatusOr<T> getData() {
      return data;
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
    public void subscribeToChildren(XdsListenerResource.LdsUpdate update) {
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
      return (RdsWatcher) tracer.getWatcher(RDS_TYPE, rdsName);
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
    public void subscribeToChildren(RdsUpdate update) {
      updateRoutes(update.virtualHosts);
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
      super(XdsClusterResource.getInstance(), checkNotNull(resourceName, "resourceName"));
    }

    @Override
    public void subscribeToChildren(XdsClusterResource.CdsUpdate update) {
      switch (update.clusterType()) {
        case EDS:
          addEdsWatcher(getEdsServiceName());
          break;
        case LOGICAL_DNS:
          if (enableLogicalDns) {
            addDnsWatcher(update.dnsHostName());
          }
          break;
        case AGGREGATE:
          update.prioritizedClusterNames()
              .forEach(name -> addClusterWatcher(name));
          break;
        default:
      }
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
      super(XdsEndpointResource.getInstance(), checkNotNull(resourceName, "resourceName"));
    }

    @Override
    public void subscribeToChildren(XdsEndpointResource.EdsUpdate update) {}
  }

  private final class DnsWatcher implements TrackedWatcher<List<EquivalentAddressGroup>> {
    private final NameResolver resolver;
    @Nullable
    private StatusOr<List<EquivalentAddressGroup>> data;
    private boolean cancelled;

    public DnsWatcher(String dnsHostName, NameResolver.Args nameResolverArgs) {
      this.resolver = createNameResolver(dnsHostName, nameResolverArgs);
    }

    public void start() {
      resolver.start(new NameResolverListener());
    }

    public void refresh() {
      if (cancelled) {
        return;
      }
      resolver.refresh();
    }

    @Override
    @Nullable
    public StatusOr<List<EquivalentAddressGroup>> getData() {
      return data;
    }

    @Override
    public void close() {
      if (cancelled) {
        return;
      }
      cancelled = true;
      resolver.shutdown();
    }

    private class NameResolverListener extends NameResolver.Listener2 {
      @Override
      public void onResult(final NameResolver.ResolutionResult resolutionResult) {
        syncContext.execute(() -> onResult2(resolutionResult));
      }

      @Override
      public Status onResult2(final NameResolver.ResolutionResult resolutionResult) {
        if (cancelled) {
          return Status.OK;
        }
        data = resolutionResult.getAddressesOrError();
        maybePublishConfig();
        return resolutionResult.getAddressesOrError().getStatus();
      }

      @Override
      public void onError(final Status error) {
        syncContext.execute(new Runnable() {
          @Override
          public void run() {
            if (cancelled) {
              return;
            }
            // DnsNameResolver cannot distinguish between address-not-found and transient errors.
            // Assume it is a transient error.
            // TODO: Once the resolution note API is available, don't throw away the error if
            // hasDataValue(); pass it as the note instead
            if (!hasDataValue()) {
              data = StatusOr.fromStatus(error);
              maybePublishConfig();
            }
          }
        });
      }
    }
  }

  private static final class FailingNameResolver extends NameResolver {
    private final Status status;

    public FailingNameResolver(Status status) {
      checkNotNull(status, "status");
      checkArgument(!status.isOk(), "Status must not be OK");
      this.status = status;
    }

    @Override
    public void start(Listener2 listener) {
      listener.onError(status);
    }

    @Override
    public String getServiceAuthority() {
      return "bug-if-you-see-this-authority";
    }

    @Override
    public void shutdown() {}
  }
}
