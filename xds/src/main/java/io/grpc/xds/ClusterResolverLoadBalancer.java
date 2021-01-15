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

import static com.google.common.base.Preconditions.checkNotNull;
import static io.grpc.ConnectivityState.CONNECTING;
import static io.grpc.ConnectivityState.TRANSIENT_FAILURE;
import static io.grpc.xds.XdsLbPolicies.LRS_POLICY_NAME;
import static io.grpc.xds.XdsLbPolicies.PRIORITY_POLICY_NAME;
import static io.grpc.xds.XdsSubchannelPickers.BUFFER_PICKER;

import com.google.common.annotations.VisibleForTesting;
import io.grpc.EquivalentAddressGroup;
import io.grpc.InternalLogId;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancerProvider;
import io.grpc.LoadBalancerRegistry;
import io.grpc.NameResolver;
import io.grpc.NameResolver.ResolutionResult;
import io.grpc.Status;
import io.grpc.SynchronizationContext;
import io.grpc.SynchronizationContext.ScheduledHandle;
import io.grpc.internal.BackoffPolicy;
import io.grpc.internal.ExponentialBackoffPolicy;
import io.grpc.internal.ObjectPool;
import io.grpc.internal.ServiceConfigUtil.PolicySelection;
import io.grpc.util.ForwardingLoadBalancerHelper;
import io.grpc.util.GracefulSwitchLoadBalancer;
import io.grpc.xds.ClusterImplLoadBalancerProvider.ClusterImplConfig;
import io.grpc.xds.ClusterResolverLoadBalancerProvider.ClusterResolverConfig;
import io.grpc.xds.ClusterResolverLoadBalancerProvider.ClusterResolverConfig.DiscoveryMechanism;
import io.grpc.xds.EnvoyProtoData.DropOverload;
import io.grpc.xds.EnvoyProtoData.LbEndpoint;
import io.grpc.xds.EnvoyProtoData.Locality;
import io.grpc.xds.EnvoyProtoData.LocalityLbEndpoints;
import io.grpc.xds.EnvoyServerProtoData.UpstreamTlsContext;
import io.grpc.xds.LrsLoadBalancerProvider.LrsConfig;
import io.grpc.xds.PriorityLoadBalancerProvider.PriorityLbConfig;
import io.grpc.xds.PriorityLoadBalancerProvider.PriorityLbConfig.PriorityChildConfig;
import io.grpc.xds.WeightedTargetLoadBalancerProvider.WeightedPolicySelection;
import io.grpc.xds.WeightedTargetLoadBalancerProvider.WeightedTargetConfig;
import io.grpc.xds.XdsClient.EdsResourceWatcher;
import io.grpc.xds.XdsClient.EdsUpdate;
import io.grpc.xds.XdsLogger.XdsLogLevel;
import io.grpc.xds.XdsSubchannelPickers.ErrorPicker;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

/**
 * Load balancer for cluster_resolver_experimental LB policy. This LB policy is the child LB policy
 * of the cds_experimental LB policy and the parent LB policy of the priority_experimental LB
 * policy in the xDS load balancing hierarchy. This policy resolves endpoints of non-aggregate
 * clusters (e.g., EDS or Logical DNS) and groups endpoints in priorities and localities to be
 * used in the downstream LB policies for fine-grained load balancing purposes.
 */
final class ClusterResolverLoadBalancer extends LoadBalancer {

  private static final Locality LOGICAL_DNS_CLUSTER_LOCALITY = new Locality("", "", "");
  private final XdsLogger logger;
  private final String authority;
  private final SynchronizationContext syncContext;
  private final ScheduledExecutorService timeService;
  private final LoadBalancerRegistry lbRegistry;
  private final BackoffPolicy.Provider backoffPolicyProvider;
  private final GracefulSwitchLoadBalancer delegate;
  private ObjectPool<XdsClient> xdsClientPool;
  private XdsClient xdsClient;
  private ClusterResolverConfig config;

  ClusterResolverLoadBalancer(Helper helper) {
    this(helper, LoadBalancerRegistry.getDefaultRegistry(),
        new ExponentialBackoffPolicy.Provider());
  }

  @VisibleForTesting
  ClusterResolverLoadBalancer(Helper helper, LoadBalancerRegistry lbRegistry,
      BackoffPolicy.Provider backoffPolicyProvider) {
    this.lbRegistry = checkNotNull(lbRegistry, "lbRegistry");
    this.backoffPolicyProvider = checkNotNull(backoffPolicyProvider, "backoffPolicyProvider");
    this.authority = checkNotNull(checkNotNull(helper, "helper").getAuthority(), "authority");
    this.syncContext = checkNotNull(helper.getSynchronizationContext(), "syncContext");
    this.timeService = checkNotNull(helper.getScheduledExecutorService(), "timeService");
    delegate = new GracefulSwitchLoadBalancer(helper);
    logger = XdsLogger.withLogId(
        InternalLogId.allocate("cluster-resolver-lb", helper.getAuthority()));
    logger.log(XdsLogLevel.INFO, "Created");
  }

  @Override
  public void handleResolvedAddresses(ResolvedAddresses resolvedAddresses) {
    logger.log(XdsLogLevel.DEBUG, "Received resolution result: {0}", resolvedAddresses);
    if (xdsClientPool == null) {
      xdsClientPool = resolvedAddresses.getAttributes().get(InternalXdsAttributes.XDS_CLIENT_POOL);
      xdsClient = xdsClientPool.getObject();
    }
    ClusterResolverConfig config =
        (ClusterResolverConfig) resolvedAddresses.getLoadBalancingPolicyConfig();
    if (!Objects.equals(this.config, config)) {
      logger.log(XdsLogLevel.DEBUG, "Config: {0}", config);
      delegate.switchTo(new ClusterResolverLbStateFactory());
      this.config = config;
      delegate.handleResolvedAddresses(resolvedAddresses);
    }
  }

  @Override
  public void handleNameResolutionError(Status error) {
    logger.log(XdsLogLevel.WARNING, "Received name resolution error: {0}", error);
    delegate.handleNameResolutionError(error);
  }

  @Override
  public void shutdown() {
    logger.log(XdsLogLevel.INFO, "Shutdown");
    delegate.shutdown();
    if (xdsClientPool != null) {
      xdsClientPool.returnObject(xdsClient);
    }
  }

  private final class ClusterResolverLbStateFactory extends LoadBalancer.Factory {
    @Override
    public LoadBalancer newLoadBalancer(Helper helper) {
      return new ClusterResolverLbState(helper);
    }
  }

  /**
   * The state of a cluster_resolver LB working session. A new instance is created whenever
   * the cluster_resolver LB receives a new config. The old instance is replaced when the
   * new one is ready to handle new RPCs.
   */
  private final class ClusterResolverLbState extends LoadBalancer {
    private final Helper helper;
    private final List<String> clusters = new ArrayList<>();
    private final Map<String, ClusterState> clusterStates = new HashMap<>();
    // An aggregate cluster is thought of as a cluster that groups the endpoints of the underlying
    // clusters together for load balancing purposes only. Load balancing policies (both locality
    // level and endpoint level) are configured by the aggregate cluster and apply to all of its
    // underlying clusters.
    private PolicySelection localityPickingPolicy;
    private PolicySelection endpointPickingPolicy;
    private ResolvedAddresses resolvedAddresses;
    private LoadBalancer childLb;

    ClusterResolverLbState(Helper helper) {
      this.helper = new RefreshableHelper(checkNotNull(helper, "helper"));
      logger.log(XdsLogLevel.DEBUG, "New ClusterResolverLbState");
    }

    @Override
    public void handleResolvedAddresses(ResolvedAddresses resolvedAddresses) {
      this.resolvedAddresses = resolvedAddresses;
      ClusterResolverConfig config =
          (ClusterResolverConfig) resolvedAddresses.getLoadBalancingPolicyConfig();
      localityPickingPolicy = config.localityPickingPolicy;
      endpointPickingPolicy = config.endpointPickingPolicy;
      for (DiscoveryMechanism instance : config.discoveryMechanisms) {
        clusters.add(instance.cluster);
        ClusterState state;
        if (instance.type == DiscoveryMechanism.Type.EDS) {
          state = new EdsClusterState(instance.cluster, instance.edsServiceName,
              instance.lrsServerName, instance.maxConcurrentRequests, instance.tlsContext);
          clusterStates.put(instance.cluster, state);
        } else {  // logical DNS
          state = new LogicalDnsClusterState(instance.cluster, instance.lrsServerName,
              instance.maxConcurrentRequests, instance.tlsContext);
          clusterStates.put(instance.cluster, state);
        }
        state.start();
      }
    }

    @Override
    public void handleNameResolutionError(Status error) {
      if (childLb != null) {
        childLb.handleNameResolutionError(error);
      } else {
        helper.updateBalancingState(TRANSIENT_FAILURE, new ErrorPicker(error));
      }
    }

    @Override
    public void shutdown() {
      for (ClusterState state : clusterStates.values()) {
        state.shutdown();
      }
      if (childLb != null) {
        childLb.shutdown();
      }
    }

    private void handleEndpointResourceUpdate() {
      List<EquivalentAddressGroup> addresses = new ArrayList<>();
      Map<String, PriorityChildConfig> priorityChildConfigs = new HashMap<>();
      List<String> priorities = new ArrayList<>();  // totally ordered priority list
      boolean allResolved = true;
      for (String cluster : clusters) {
        ClusterState state = clusterStates.get(cluster);
        if (!state.resolved) {
          allResolved = false;
          continue;
        }
        if (state.result != null) {
          addresses.addAll(state.result.addresses);
          priorityChildConfigs.putAll(state.result.priorityChildConfigs);
          priorities.addAll(state.result.priorities);
        }
      }
      if (addresses.isEmpty()) {
        if (childLb != null) {
          childLb.shutdown();
          childLb = null;
        }
        if (allResolved) {
          Status unavailable = Status.UNAVAILABLE.withDescription("No usable endpoint");
          helper.updateBalancingState(TRANSIENT_FAILURE, new ErrorPicker(unavailable));
        } else {
          helper.updateBalancingState(CONNECTING, BUFFER_PICKER);
        }
        return;
      }
      PriorityLbConfig childConfig =
          new PriorityLbConfig(Collections.unmodifiableMap(priorityChildConfigs),
              Collections.unmodifiableList(priorities));
      if (childLb == null) {
        childLb = lbRegistry.getProvider(PRIORITY_POLICY_NAME).newLoadBalancer(helper);
      }
      childLb.handleResolvedAddresses(
          resolvedAddresses.toBuilder()
              .setLoadBalancingPolicyConfig(childConfig)
              .setAddresses(Collections.unmodifiableList(addresses))
              .build());
    }

    private void handleEndpointResolutionError() {
      boolean allInError = true;
      for (ClusterState state :  clusterStates.values()) {
        if (state.status.isOk()) {
          allInError = false;
        }
      }
      if (allInError) {
        // Propagate the error status of the last cluster. This is the best we can do.
        Status error = clusterStates.get(clusters.get(clusters.size() - 1)).status;
        if (childLb != null) {
          childLb.handleNameResolutionError(error);
        } else {
          helper.updateBalancingState(TRANSIENT_FAILURE, new ErrorPicker(error));
        }
      }
    }

    /**
     * Wires re-resolution requests from downstream LB policies with DNS resolver.
     */
    private final class RefreshableHelper extends ForwardingLoadBalancerHelper {
      private final Helper delegate;

      private RefreshableHelper(Helper delegate) {
        this.delegate = checkNotNull(delegate, "delegate");
      }

      @Override
      public void refreshNameResolution() {
        for (ClusterState state : clusterStates.values()) {
          if (state instanceof LogicalDnsClusterState) {
            ((LogicalDnsClusterState) state).refresh();
          }
        }
      }

      @Override
      protected Helper delegate() {
        return delegate;
      }
    }

    /**
     * Resolution state of an underlying cluster.
     */
    private abstract class ClusterState {
      // Name of the cluster to be resolved.
      protected final String name;
      // The resource name to be used for resolving endpoints via EDS.
      // Always null if the cluster is a logical DNS cluster.
      @Nullable
      protected final String edsServiceName;
      @Nullable
      protected final String lrsServerName;
      @Nullable
      protected final Long maxConcurrentRequests;
      @Nullable
      protected final UpstreamTlsContext tlsContext;
      // Resolution status, may contain most recent error encountered.
      protected Status status = Status.OK;
      // True if has received resolution result.
      protected boolean resolved;
      // Most recently resolved addresses and config, or null if resource not exists.
      @Nullable
      protected ClusterResolutionResult result;
      protected boolean shutdown;

      private ClusterState(String name, @Nullable String edsServiceName,
          @Nullable String lrsServerName, @Nullable Long maxConcurrentRequests,
          @Nullable UpstreamTlsContext tlsContext) {
        this.name = name;
        this.edsServiceName = edsServiceName;
        this.lrsServerName = lrsServerName;
        this.maxConcurrentRequests = maxConcurrentRequests;
        this.tlsContext = tlsContext;
      }

      abstract void start();

      void shutdown() {
        shutdown = true;
      }
    }

    private class EdsClusterState extends ClusterState implements EdsResourceWatcher {

      private EdsClusterState(String name, @Nullable String edsServiceName,
          @Nullable String lrsServerName, @Nullable Long maxConcurrentRequests,
          @Nullable UpstreamTlsContext tlsContext) {
        super(name, edsServiceName, lrsServerName, maxConcurrentRequests, tlsContext);
      }

      @Override
      void start() {
        String resourceName = edsServiceName != null ? edsServiceName : name;
        logger.log(XdsLogLevel.INFO, "Start watching EDS resource {0}", resourceName);
        xdsClient.watchEdsResource(resourceName, this);
      }

      @Override
      protected void shutdown() {
        super.shutdown();
        String resourceName = edsServiceName != null ? edsServiceName : name;
        logger.log(XdsLogLevel.INFO, "Stop watching EDS resource {0}", resourceName);
        xdsClient.cancelEdsResourceWatch(resourceName, this);
      }

      @Override
      public void onChanged(final EdsUpdate update) {
        class EndpointsUpdated implements Runnable {
          @Override
          public void run() {
            if (shutdown) {
              return;
            }
            logger.log(XdsLogLevel.DEBUG, "Received endpoint update {0}", update);
            if (logger.isLoggable(XdsLogLevel.INFO)) {
              logger.log(XdsLogLevel.INFO, "Cluster {0}: {1} localities, {2} drop categories",
                  update.clusterName, update.localityLbEndpointsMap.size(),
                  update.dropPolicies.size());
            }
            Map<Locality, LocalityLbEndpoints> localityLbEndpoints =
                update.localityLbEndpointsMap;
            List<DropOverload> dropOverloads = update.dropPolicies;
            List<EquivalentAddressGroup> addresses = new ArrayList<>();
            Map<String, Map<Locality, Integer>> prioritizedLocalityWeights = new HashMap<>();
            for (Locality locality : localityLbEndpoints.keySet()) {
              LocalityLbEndpoints localityLbInfo = localityLbEndpoints.get(locality);
              int priority = localityLbInfo.getPriority();
              String priorityName = priorityName(name, priority);
              boolean discard = true;
              for (LbEndpoint endpoint : localityLbInfo.getEndpoints()) {
                if (endpoint.isHealthy()) {
                  discard = false;
                  EquivalentAddressGroup eag =
                      AddressFilter.setPathFilter(
                          endpoint.getAddress(),
                          Arrays.asList(priorityName, localityName(locality)));
                  addresses.add(eag);
                }
              }
              if (discard) {
                logger.log(XdsLogLevel.INFO,
                    "Discard locality {0} with 0 healthy endpoints", locality);
                continue;
              }
              if (!prioritizedLocalityWeights.containsKey(priorityName)) {
                prioritizedLocalityWeights.put(priorityName, new HashMap<Locality, Integer>());
              }
              prioritizedLocalityWeights.get(priorityName).put(
                  locality, localityLbInfo.getLocalityWeight());
            }
            if (prioritizedLocalityWeights.isEmpty()) {
              // Will still update the result, as if the cluster resource is revoked.
              logger.log(XdsLogLevel.INFO,
                  "Cluster {0} has no usable priority/locality/endpoint", update.clusterName);
            }
            List<String> priorities = new ArrayList<>(prioritizedLocalityWeights.keySet());
            Collections.sort(priorities);
            Map<String, PriorityChildConfig> priorityChildConfigs = generatePriorityChildConfigs(
                name, edsServiceName, lrsServerName, maxConcurrentRequests, tlsContext,
                localityPickingPolicy, endpointPickingPolicy, true, lbRegistry,
                prioritizedLocalityWeights, dropOverloads);
            status = Status.OK;
            resolved = true;
            result = new ClusterResolutionResult(addresses, priorityChildConfigs, priorities);
            handleEndpointResourceUpdate();
          }
        }

        syncContext.execute(new EndpointsUpdated());
      }

      @Override
      public void onResourceDoesNotExist(final String resourceName) {
        syncContext.execute(new Runnable() {
          @Override
          public void run() {
            if (shutdown) {
              return;
            }
            logger.log(XdsLogLevel.INFO, "Resource {0} unavailable", resourceName);
            status = Status.OK;
            resolved = true;
            result = null;  // resource revoked
            handleEndpointResourceUpdate();
          }
        });
      }

      @Override
      public void onError(final Status error) {
        syncContext.execute(new Runnable() {
          @Override
          public void run() {
            if (shutdown) {
              return;
            }
            status = error;
            logger.log(XdsLogLevel.WARNING, "Received EDS error: {0}", error);
            handleEndpointResolutionError();
          }
        });
      }
    }

    private class LogicalDnsClusterState extends ClusterState {
      private final NameResolver resolver;
      @Nullable
      private BackoffPolicy backoffPolicy;
      @Nullable
      private ScheduledHandle scheduledRefresh;

      private LogicalDnsClusterState(String name, @Nullable String lrsServerName,
          @Nullable Long maxConcurrentRequests, @Nullable UpstreamTlsContext tlsContext) {
        super(name, null, lrsServerName, maxConcurrentRequests, tlsContext);
        NameResolver.Args args = helper.getNameResolverArgs();
        URI uri;
        try {
          uri = new URI(authority);
        } catch (URISyntaxException e) {
          // TODO(chengyuanzhang): unlikely to happen, but maybe handle it more gracefully.
          throw new AssertionError("Bug, invalid authority: " + authority, e);
        }
        resolver = helper.getNameResolverRegistry().asFactory().newNameResolver(uri, args);
      }

      @Override
      void start() {
        resolver.start(new NameResolverListener());
      }

      void refresh() {
        cancelBackoff();
        resolver.refresh();
      }

      @Override
      void shutdown() {
        super.shutdown();
        resolver.shutdown();
        cancelBackoff();
      }

      private void cancelBackoff() {
        if (scheduledRefresh != null) {
          scheduledRefresh.cancel();
          scheduledRefresh = null;
          backoffPolicy = null;
        }
      }

      private class DelayedNameResolverRefresh implements Runnable {
        @Override
        public void run() {
          scheduledRefresh = null;
          if (!shutdown) {
            resolver.refresh();
          }
        }
      }

      private class NameResolverListener extends NameResolver.Listener2 {
        @Override
        public void onResult(final ResolutionResult resolutionResult) {
          class NameResolved implements Runnable {
            @Override
            public void run() {
              if (shutdown) {
                return;
              }
              backoffPolicy = null;  // reset backoff sequence if succeeded
              String priorityName = priorityName(name, 0);  // value doesn't matter
              List<EquivalentAddressGroup> addresses = new ArrayList<>();
              for (EquivalentAddressGroup eag : resolutionResult.getAddresses()) {
                EquivalentAddressGroup annotatedAddr =
                    AddressFilter.setPathFilter(
                        eag, Arrays.asList(
                            priorityName, LOGICAL_DNS_CLUSTER_LOCALITY.toString()));
                addresses.add(annotatedAddr);
              }
              LoadBalancerProvider endpointPickingLbProvider =
                  lbRegistry.getProvider("pick_first");
              PolicySelection endpointPickingPolicy =
                  new PolicySelection(endpointPickingLbProvider, null);
              PriorityChildConfig priorityChildConfig = generatePriorityChildConfig(
                  name, edsServiceName, lrsServerName, maxConcurrentRequests, tlsContext,
                  endpointPickingPolicy, false, lbRegistry, LOGICAL_DNS_CLUSTER_LOCALITY,
                  Collections.<DropOverload>emptyList());
              status = Status.OK;
              resolved = true;
              result = new ClusterResolutionResult(addresses, priorityName, priorityChildConfig);
              handleEndpointResourceUpdate();
            }
          }

          syncContext.execute(new NameResolved());
        }

        @Override
        public void onError(final Status error) {
          syncContext.execute(new Runnable() {
            @Override
            public void run() {
              if (shutdown) {
                return;
              }
              status = error;
              // NameResolver.Listener API cannot distinguish transient errors, we should avoid
              // waiting for DNS addresses indefinitely.
              resolved = true;
              handleEndpointResolutionError();
              if (scheduledRefresh != null && scheduledRefresh.isPending()) {
                return;
              }
              if (backoffPolicy == null) {
                backoffPolicy = backoffPolicyProvider.get();
              }
              long delayNanos = backoffPolicy.nextBackoffNanos();
              logger.log(XdsLogLevel.DEBUG,
                  "Scheduling DNS resolution backoff for {0} ns", delayNanos);
              scheduledRefresh =
                  syncContext.schedule(
                      new DelayedNameResolverRefresh(), delayNanos, TimeUnit.NANOSECONDS,
                      timeService);
            }
          });
        }
      }
    }
  }

  private static class ClusterResolutionResult {
    // Endpoint addresses.
    private final List<EquivalentAddressGroup> addresses;
    // Config (include load balancing policy/config) for each priority in the cluster.
    private final Map<String, PriorityChildConfig> priorityChildConfigs;
    // List of priority names ordered in descending priorities.
    private final List<String> priorities;

    ClusterResolutionResult(List<EquivalentAddressGroup> addresses, String priority,
        PriorityChildConfig config) {
      this(addresses, Collections.singletonMap(priority, config),
          Collections.singletonList(priority));
    }

    ClusterResolutionResult(List<EquivalentAddressGroup> addresses,
        Map<String, PriorityChildConfig> configs, List<String> priorities) {
      this.addresses = addresses;
      this.priorityChildConfigs = configs;
      this.priorities = priorities;
    }
  }

  /**
   * Generates the config to be used in the priority LB policy for a single priority.
   *
   * <p>priority LB -> cluster_impl LB -> (lrs LB) -> pick_first
   */
  private static PriorityChildConfig generatePriorityChildConfig(
      String cluster, @Nullable String edsServiceName, @Nullable String lrsServerName,
      @Nullable Long maxConcurrentRequests, @Nullable UpstreamTlsContext tlsContext,
      PolicySelection endpointPickingPolicy, boolean ignoreReresolution,
      LoadBalancerRegistry lbRegistry, Locality locality, List<DropOverload> dropOverloads) {
    PolicySelection localityLbPolicy =
        generateLocalityLbConfig(locality, cluster, edsServiceName, lrsServerName,
            endpointPickingPolicy, lbRegistry);
    ClusterImplConfig clusterImplConfig =
        new ClusterImplConfig(cluster, edsServiceName, lrsServerName, maxConcurrentRequests,
            dropOverloads, localityLbPolicy, tlsContext);
    LoadBalancerProvider clusterImplLbProvider =
        lbRegistry.getProvider(XdsLbPolicies.CLUSTER_IMPL_POLICY_NAME);
    PolicySelection clusterImplPolicy =
        new PolicySelection(clusterImplLbProvider, clusterImplConfig);
    return new PriorityChildConfig(clusterImplPolicy, ignoreReresolution);
  }

  /**
   * Generates configs to be used in the priority LB policy for priorities in the cluster.
   *
   * <p>priority LB -> cluster_impl LB (one per priority) -> weighted_target LB
   * -> (lrs LB (one per locality)) -> round_robin
   */
  private static Map<String, PriorityChildConfig> generatePriorityChildConfigs(
      String cluster, @Nullable String edsServiceName, @Nullable String lrsServerName,
      @Nullable Long maxConcurrentRequests, @Nullable UpstreamTlsContext tlsContext,
      PolicySelection localityPickingPolicy, PolicySelection endpointPickingPolicy,
      boolean ignoreReresolution, LoadBalancerRegistry lbRegistry,
      Map<String, Map<Locality, Integer>> prioritizedLocalityWeights,
      List<DropOverload> dropOverloads) {
    Map<String, PriorityChildConfig> configs = new HashMap<>();
    for (String priority : prioritizedLocalityWeights.keySet()) {
      WeightedTargetConfig localityPickingLbConfig =
          generateLocalityPickingLbConfig(cluster, edsServiceName, lrsServerName,
              endpointPickingPolicy, lbRegistry, prioritizedLocalityWeights.get(priority));
      PolicySelection localityPicking =
          new PolicySelection(localityPickingPolicy.getProvider(), localityPickingLbConfig);
      ClusterImplConfig clusterImplConfig =
          new ClusterImplConfig(cluster, edsServiceName, lrsServerName, maxConcurrentRequests,
              dropOverloads, localityPicking, tlsContext);
      LoadBalancerProvider clusterImplLbProvider =
          lbRegistry.getProvider(XdsLbPolicies.CLUSTER_IMPL_POLICY_NAME);
      PolicySelection clusterImplPolicy =
          new PolicySelection(clusterImplLbProvider, clusterImplConfig);
      configs.put(priority, new PriorityChildConfig(clusterImplPolicy, ignoreReresolution));
    }
    return configs;
  }

  private static WeightedTargetConfig generateLocalityPickingLbConfig(
      String cluster, @Nullable String edsServiceName, @Nullable String lrsServerName,
      PolicySelection endpointPickingPolicy, LoadBalancerRegistry lbRegistry,
      Map<Locality, Integer> localityWeights) {
    Map<String, WeightedPolicySelection> targets = new HashMap<>();
    for (Locality locality : localityWeights.keySet()) {
      int weight = localityWeights.get(locality);
      PolicySelection childPolicy =
          generateLocalityLbConfig(locality, cluster, edsServiceName, lrsServerName,
              endpointPickingPolicy, lbRegistry);
      targets.put(localityName(locality), new WeightedPolicySelection(weight, childPolicy));
    }
    return new WeightedTargetConfig(Collections.unmodifiableMap(targets));
  }

  /**
   * Generates intra-locality LB policy (with config) for the given locality.
   */
  private static PolicySelection generateLocalityLbConfig(
      Locality locality, String cluster, @Nullable String edsServiceName,
      @Nullable String lrsServerName, PolicySelection endpointPickingPolicy,
      LoadBalancerRegistry lbRegistry) {
    PolicySelection policy;
    if (lrsServerName != null) {
      LrsConfig childConfig =
          new LrsConfig(cluster, edsServiceName, lrsServerName, locality, endpointPickingPolicy);
      LoadBalancerProvider childPolicyProvider = lbRegistry.getProvider(LRS_POLICY_NAME);
      policy = new PolicySelection(childPolicyProvider, childConfig);
    } else {
      policy = endpointPickingPolicy;
    }
    return policy;
  }

  /**
   * Generates a string that represents the priority in the LB policy config. The string is unique
   * across priorities in all clusters and priorityName(c, p1) < priorityName(c, p2) iff p1 < p2.
   * The ordering is undefined for priorities in different clusters.
   */
  private static String priorityName(String cluster, int priority) {
    return cluster + "[priority" + priority + "]";
  }

  /**
   * Generates a string that represents the locality in the LB policy config. The string is unique
   * across all localities in all clusters.
   */
  private static String localityName(Locality locality) {
    return locality.toString();
  }
}
