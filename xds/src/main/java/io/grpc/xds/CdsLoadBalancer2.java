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
import static io.grpc.ConnectivityState.TRANSIENT_FAILURE;
import static io.grpc.xds.CdsLoadBalancer2.ClusterResolverConfig.DiscoveryMechanism;
import static io.grpc.xds.XdsLbPolicies.PRIORITY_POLICY_NAME;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.protobuf.Struct;
import io.grpc.Attributes;
import io.grpc.EquivalentAddressGroup;
import io.grpc.InternalLogId;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancerProvider;
import io.grpc.LoadBalancerRegistry;
import io.grpc.NameResolver;
import io.grpc.Status;
import io.grpc.StatusOr;
import io.grpc.SynchronizationContext;
import io.grpc.internal.BackoffPolicy;
import io.grpc.internal.ExponentialBackoffPolicy;
import io.grpc.util.ForwardingLoadBalancerHelper;
import io.grpc.util.GracefulSwitchLoadBalancer;
import io.grpc.util.OutlierDetectionLoadBalancer;
import io.grpc.util.OutlierDetectionLoadBalancer.OutlierDetectionLoadBalancerConfig;
import io.grpc.xds.CdsLoadBalancerProvider.CdsConfig;
import io.grpc.xds.PriorityLoadBalancerProvider.PriorityLbConfig.PriorityChildConfig;
import io.grpc.xds.XdsClusterResource.CdsUpdate;
import io.grpc.xds.XdsClusterResource.CdsUpdate.ClusterType;
import io.grpc.xds.XdsConfig.XdsClusterConfig;
import io.grpc.xds.XdsEndpointResource.EdsUpdate;
import io.grpc.xds.client.Bootstrapper;
import io.grpc.xds.client.Locality;
import io.grpc.xds.client.XdsLogger;
import io.grpc.xds.client.XdsLogger.XdsLogLevel;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

/**
 * Load balancer for cds_experimental LB policy. One instance per top-level cluster.
 * The top-level cluster may be a plain EDS/logical-DNS cluster or an aggregate cluster formed
 * by a group of sub-clusters in a tree hierarchy.
 */
final class CdsLoadBalancer2 extends LoadBalancer {
  private final XdsLogger logger;
  private final Helper helper;
  private final SynchronizationContext syncContext;
  private final LoadBalancerRegistry lbRegistry;
  private CdsLbState rootCdsLbState;
  private ResolvedAddresses resolvedAddresses;
  private final BackoffPolicy.Provider backoffPolicyProvider;

  CdsLoadBalancer2(Helper helper) {
    this(helper, LoadBalancerRegistry.getDefaultRegistry(),
        new ExponentialBackoffPolicy.Provider());
  }

  @VisibleForTesting
  CdsLoadBalancer2(Helper helper, LoadBalancerRegistry lbRegistry,
                   BackoffPolicy.Provider backoffPolicyProvider) {
    this.helper = checkNotNull(helper, "helper");
    this.syncContext = checkNotNull(helper.getSynchronizationContext(), "syncContext");
    this.lbRegistry = checkNotNull(lbRegistry, "lbRegistry");
    this.backoffPolicyProvider = checkNotNull(backoffPolicyProvider, "backoffPolicyProvider");
    logger = XdsLogger.withLogId(InternalLogId.allocate("cds-lb", helper.getAuthority()));
    logger.log(XdsLogLevel.INFO, "Created");
  }

  /**
   * Generates the config to be used in the priority LB policy for the single priority of
   * logical DNS cluster.
   *
   * <p>priority LB -> cluster_impl LB (single hardcoded priority) -> pick_first
   */
  static PriorityChildConfig generateDnsBasedPriorityChildConfig(
      String cluster, @Nullable Bootstrapper.ServerInfo lrsServerInfo,
      @Nullable Long maxConcurrentRequests,
      @Nullable EnvoyServerProtoData.UpstreamTlsContext tlsContext,
      Map<String, Struct> filterMetadata,
      LoadBalancerRegistry lbRegistry, List<Endpoints.DropOverload> dropOverloads) {
    // Override endpoint-level LB policy with pick_first for logical DNS cluster.
    Object endpointLbConfig = GracefulSwitchLoadBalancer.createLoadBalancingPolicyConfig(
        lbRegistry.getProvider("pick_first"), null);
    ClusterImplLoadBalancerProvider.ClusterImplConfig clusterImplConfig =
        new ClusterImplLoadBalancerProvider.ClusterImplConfig(cluster, null, lrsServerInfo,
            maxConcurrentRequests, dropOverloads, endpointLbConfig, tlsContext, filterMetadata);
    LoadBalancerProvider clusterImplLbProvider =
        lbRegistry.getProvider(XdsLbPolicies.CLUSTER_IMPL_POLICY_NAME);
    Object clusterImplPolicy = GracefulSwitchLoadBalancer.createLoadBalancingPolicyConfig(
        clusterImplLbProvider, clusterImplConfig);
    return new PriorityChildConfig(clusterImplPolicy, false /* ignoreReresolution*/);
  }

  /**
   * Generates configs to be used in the priority LB policy for priorities in an EDS cluster.
   *
   * <p>priority LB -> cluster_impl LB (one per priority) -> (weighted_target LB
   * -> round_robin / least_request_experimental (one per locality)) / ring_hash_experimental
   */
  static Map<String, PriorityChildConfig> generateEdsBasedPriorityChildConfigs(
      String cluster, @Nullable String edsServiceName,
      @Nullable Bootstrapper.ServerInfo lrsServerInfo,
      @Nullable Long maxConcurrentRequests,
      @Nullable EnvoyServerProtoData.UpstreamTlsContext tlsContext,
      Map<String, Struct> filterMetadata,
      @Nullable EnvoyServerProtoData.OutlierDetection outlierDetection, Object endpointLbConfig,
      LoadBalancerRegistry lbRegistry, Map<String,
      Map<Locality, Integer>> prioritizedLocalityWeights,
      List<Endpoints.DropOverload> dropOverloads) {
    Map<String, PriorityChildConfig> configs = new HashMap<>();
    for (String priority : prioritizedLocalityWeights.keySet()) {
      ClusterImplLoadBalancerProvider.ClusterImplConfig clusterImplConfig =
          new ClusterImplLoadBalancerProvider.ClusterImplConfig(
              cluster, edsServiceName, lrsServerInfo, maxConcurrentRequests,
              dropOverloads, endpointLbConfig, tlsContext, filterMetadata);
      LoadBalancerProvider clusterImplLbProvider =
          lbRegistry.getProvider(XdsLbPolicies.CLUSTER_IMPL_POLICY_NAME);
      Object priorityChildPolicy = GracefulSwitchLoadBalancer.createLoadBalancingPolicyConfig(
          clusterImplLbProvider, clusterImplConfig);

      // If outlier detection has been configured we wrap the child policy in the outlier detection
      // load balancer.
      if (outlierDetection != null) {
        LoadBalancerProvider outlierDetectionProvider = lbRegistry.getProvider(
            "outlier_detection_experimental");
        priorityChildPolicy = GracefulSwitchLoadBalancer.createLoadBalancingPolicyConfig(
            outlierDetectionProvider,
            buildOutlierDetectionLbConfig(outlierDetection, priorityChildPolicy));
      }

      PriorityChildConfig priorityChildConfig =
          new PriorityChildConfig(priorityChildPolicy, true /* ignoreReresolution */);
      configs.put(priority, priorityChildConfig);
    }
    return configs;
  }

  /**
   * Converts {@link EnvoyServerProtoData.OutlierDetection} that represents the xDS configuration to
   * {@link OutlierDetectionLoadBalancerConfig} that the {@link OutlierDetectionLoadBalancer}
   * understands.
   */
  private static OutlierDetectionLoadBalancerConfig buildOutlierDetectionLbConfig(
      EnvoyServerProtoData.OutlierDetection outlierDetection, Object childConfig) {
    OutlierDetectionLoadBalancerConfig.Builder configBuilder
        = new OutlierDetectionLoadBalancerConfig.Builder();

    configBuilder.setChildConfig(childConfig);

    if (outlierDetection.intervalNanos() != null) {
      configBuilder.setIntervalNanos(outlierDetection.intervalNanos());
    }
    if (outlierDetection.baseEjectionTimeNanos() != null) {
      configBuilder.setBaseEjectionTimeNanos(outlierDetection.baseEjectionTimeNanos());
    }
    if (outlierDetection.maxEjectionTimeNanos() != null) {
      configBuilder.setMaxEjectionTimeNanos(outlierDetection.maxEjectionTimeNanos());
    }
    if (outlierDetection.maxEjectionPercent() != null) {
      configBuilder.setMaxEjectionPercent(outlierDetection.maxEjectionPercent());
    }

    EnvoyServerProtoData.SuccessRateEjection successRate = outlierDetection.successRateEjection();
    if (successRate != null) {
      OutlierDetectionLoadBalancerConfig.SuccessRateEjection.Builder successRateConfigBuilder =
          new OutlierDetectionLoadBalancerConfig.SuccessRateEjection.Builder();

      if (successRate.stdevFactor() != null) {
        successRateConfigBuilder.setStdevFactor(successRate.stdevFactor());
      }
      if (successRate.enforcementPercentage() != null) {
        successRateConfigBuilder.setEnforcementPercentage(successRate.enforcementPercentage());
      }
      if (successRate.minimumHosts() != null) {
        successRateConfigBuilder.setMinimumHosts(successRate.minimumHosts());
      }
      if (successRate.requestVolume() != null) {
        successRateConfigBuilder.setRequestVolume(successRate.requestVolume());
      }

      configBuilder.setSuccessRateEjection(successRateConfigBuilder.build());
    }

    EnvoyServerProtoData.FailurePercentageEjection failurePercentage =
        outlierDetection.failurePercentageEjection();
    if (failurePercentage != null) {
      OutlierDetectionLoadBalancerConfig.FailurePercentageEjection.Builder failurePctCfgBldr =
          new OutlierDetectionLoadBalancerConfig.FailurePercentageEjection.Builder();

      if (failurePercentage.threshold() != null) {
        failurePctCfgBldr.setThreshold(failurePercentage.threshold());
      }
      if (failurePercentage.enforcementPercentage() != null) {
        failurePctCfgBldr.setEnforcementPercentage(failurePercentage.enforcementPercentage());
      }
      if (failurePercentage.minimumHosts() != null) {
        failurePctCfgBldr.setMinimumHosts(failurePercentage.minimumHosts());
      }
      if (failurePercentage.requestVolume() != null) {
        failurePctCfgBldr.setRequestVolume(failurePercentage.requestVolume());
      }

      configBuilder.setFailurePercentageEjection(failurePctCfgBldr.build());
    }

    return configBuilder.build();
  }

  /**
   * Generates a string that represents the priority in the LB policy config. The string is unique
   * across priorities in all clusters and priorityName(c, p1) < priorityName(c, p2) iff p1 < p2.
   * The ordering is undefined for priorities in different clusters.
   */
  static String priorityName(String cluster, int priority) {
    return cluster + "[child" + priority + "]";
  }

  /**
   * Generates a string that represents the locality in the LB policy config. The string is unique
   * across all localities in all clusters.
   */
  static String localityName(Locality locality) {
    return "{region=\"" + locality.region()
        + "\", zone=\"" + locality.zone()
        + "\", sub_zone=\"" + locality.subZone()
        + "\"}";
  }

  @Override
  public Status acceptResolvedAddresses(ResolvedAddresses resolvedAddresses) {
    checkNotNull(resolvedAddresses, "resolvedAddresses");
    String rootClusterName = ((CdsConfig) resolvedAddresses.getLoadBalancingPolicyConfig()).name;
    XdsConfig xdsConfig = resolvedAddresses.getAttributes().get(XdsAttributes.XDS_CONFIG);

    if (xdsConfig.getClusters().get(rootClusterName) == null) {
      return Status.UNAVAILABLE.withDescription(
          "CDS resource not found for root cluster: " + rootClusterName);
    }

    logger.log(XdsLogLevel.DEBUG, "Received resolution result: {0}", resolvedAddresses);
    this.resolvedAddresses = resolvedAddresses;
    rootCdsLbState =
        new CdsLbState(rootClusterName, xdsConfig.getClusters(), rootClusterName);
    rootCdsLbState.start();

    return Status.OK;
  }

  @Override
  public void handleNameResolutionError(Status error) {
    logger.log(XdsLogLevel.WARNING, "Received name resolution error: {0}", error);
    if (rootCdsLbState != null && rootCdsLbState.childLb != null) {
      rootCdsLbState.childLb.handleNameResolutionError(error);
    } else {
      helper.updateBalancingState(
          TRANSIENT_FAILURE, new FixedResultPicker(PickResult.withError(error)));
    }
  }

  @Override
  public void shutdown() {
    logger.log(XdsLogLevel.INFO, "Shutdown");
    if (rootCdsLbState != null) {
      rootCdsLbState.shutdown();
    }
  }

  final class ClusterResolverLbStateFactory extends Factory {
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
    private Object endpointLbConfig;
    private ResolvedAddresses resolvedAddresses;
    private LoadBalancer childLb;


    ClusterResolverLbState(Helper helper) {
      this.helper = new RefreshableHelper(checkNotNull(helper, "helper"));
      logger.log(XdsLogLevel.DEBUG, "New ClusterResolverLbState");
    }

    @Override
    public Status acceptResolvedAddresses(ResolvedAddresses resolvedAddresses) {
      this.resolvedAddresses = resolvedAddresses;
      ClusterResolverConfig config =
          (ClusterResolverConfig) resolvedAddresses.getLoadBalancingPolicyConfig();
      endpointLbConfig = config.lbConfig;
      for (DiscoveryMechanism instance : config.discoveryMechanisms) {
        clusters.add(instance.cluster);
        ClusterState state;
        if (instance.type == DiscoveryMechanism.Type.EDS) {
          state = new EdsClusterState(instance.cluster, instance.edsServiceName,
              instance.endpointConfig,
              instance.lrsServerInfo, instance.maxConcurrentRequests, instance.tlsContext,
              instance.filterMetadata, instance.outlierDetection);
        } else {  // logical DNS
          state = new LogicalDnsClusterState(instance.cluster, instance.dnsHostName,
              instance.lrsServerInfo, instance.maxConcurrentRequests, instance.tlsContext,
              instance.filterMetadata);
        }
        clusterStates.put(instance.cluster, state);
        state.start();
      }
      return Status.OK;
    }

    @Override
    public void handleNameResolutionError(Status error) {
      if (childLb != null) {
        childLb.handleNameResolutionError(error);
      } else {
        helper.updateBalancingState(
            TRANSIENT_FAILURE, new FixedResultPicker(PickResult.withError(error)));
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

      Status endpointNotFound = Status.OK;
      for (String cluster : clusters) {
        ClusterState state = clusterStates.get(cluster);
        // Propagate endpoints to the child LB policy only after all clusters have been resolved.
        if (!state.resolved && state.status.isOk()) {
          return;
        }
        if (state.result != null) {
          addresses.addAll(state.result.addresses);
          priorityChildConfigs.putAll(state.result.priorityChildConfigs);
          priorities.addAll(state.result.priorities);
        } else {
          endpointNotFound = state.status;
        }
      }
      if (addresses.isEmpty()) {
        if (endpointNotFound.isOk()) {
          endpointNotFound = Status.UNAVAILABLE.withDescription(
              "No usable endpoint from cluster(s): " + clusters);
        } else {
          endpointNotFound =
              Status.UNAVAILABLE.withCause(endpointNotFound.getCause())
                  .withDescription(endpointNotFound.getDescription());
        }
        helper.updateBalancingState(
            TRANSIENT_FAILURE, new FixedResultPicker(PickResult.withError(endpointNotFound)));
        if (childLb != null) {
          childLb.shutdown();
          childLb = null;
        }
        return;
      }
      PriorityLoadBalancerProvider.PriorityLbConfig childConfig =
          new PriorityLoadBalancerProvider.PriorityLbConfig(
              Collections.unmodifiableMap(priorityChildConfigs),
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
      Status error = null;
      for (String cluster : clusters) {
        ClusterState state = clusterStates.get(cluster);
        if (state.status.isOk()) {
          allInError = false;
        } else {
          error = state.status;
        }
      }
      if (allInError) {
        if (childLb != null) {
          childLb.handleNameResolutionError(error);
        } else {
          helper.updateBalancingState(
              TRANSIENT_FAILURE, new FixedResultPicker(PickResult.withError(error)));
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
      @Nullable
      protected final Bootstrapper.ServerInfo lrsServerInfo;
      @Nullable
      protected final Long maxConcurrentRequests;
      @Nullable
      protected final EnvoyServerProtoData.UpstreamTlsContext tlsContext;
      protected final Map<String, Struct> filterMetadata;
      @Nullable
      protected final EnvoyServerProtoData.OutlierDetection outlierDetection;
      // Resolution status, may contain most recent error encountered.
      protected Status status = Status.OK;
      // True if has received resolution result.
      protected boolean resolved;
      // Most recently resolved addresses and config, or null if resource not exists.
      @Nullable
      protected ClusterResolutionResult result;

      protected boolean shutdown;

      private ClusterState(String name, @Nullable Bootstrapper.ServerInfo lrsServerInfo,
                           @Nullable Long maxConcurrentRequests,
                           @Nullable EnvoyServerProtoData.UpstreamTlsContext tlsContext,
                           Map<String, Struct> filterMetadata,
                           @Nullable EnvoyServerProtoData.OutlierDetection outlierDetection) {
        this.name = name;
        this.lrsServerInfo = lrsServerInfo;
        this.maxConcurrentRequests = maxConcurrentRequests;
        this.tlsContext = tlsContext;
        this.filterMetadata = ImmutableMap.copyOf(filterMetadata);
        this.outlierDetection = outlierDetection;
      }

      abstract void start();

      void shutdown() {
        shutdown = true;
      }
    }

    private final class EdsClusterState extends ClusterState {
      @Nullable
      private final String edsServiceName;
      private Map<Locality, String> localityPriorityNames = Collections.emptyMap();
      int priorityNameGenId = 1;
      private EdsUpdate edsUpdate;

      private EdsClusterState(String name, @Nullable String edsServiceName,
                              StatusOr<EdsUpdate> edsUpdate,
                              @Nullable Bootstrapper.ServerInfo lrsServerInfo,
                              @Nullable Long maxConcurrentRequests,
                              @Nullable EnvoyServerProtoData.UpstreamTlsContext tlsContext,
                              Map<String, Struct> filterMetadata,
                              @Nullable EnvoyServerProtoData.OutlierDetection outlierDetection) {
        super(name, lrsServerInfo, maxConcurrentRequests, tlsContext, filterMetadata,
            outlierDetection);
        this.edsServiceName = edsServiceName;
        if (edsUpdate.hasValue()) {
          this.edsUpdate = edsUpdate.getValue();
        } else {
          onError(edsUpdate.getStatus());
        }
      }

      @Override
      void start() {
        onChanged(edsUpdate);
      }

      @Override
      protected void shutdown() {
        super.shutdown();
      }

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
            Map<Locality, Endpoints.LocalityLbEndpoints> localityLbEndpoints =
                update.localityLbEndpointsMap;
            List<Endpoints.DropOverload> dropOverloads = update.dropPolicies;
            List<EquivalentAddressGroup> addresses = new ArrayList<>();
            Map<String, Map<Locality, Integer>> prioritizedLocalityWeights = new HashMap<>();
            List<String> sortedPriorityNames = generatePriorityNames(name, localityLbEndpoints);
            for (Locality locality : localityLbEndpoints.keySet()) {
              Endpoints.LocalityLbEndpoints localityLbInfo = localityLbEndpoints.get(locality);
              String priorityName = localityPriorityNames.get(locality);
              boolean discard = true;
              for (Endpoints.LbEndpoint endpoint : localityLbInfo.endpoints()) {
                if (endpoint.isHealthy()) {
                  discard = false;
                  long weight = localityLbInfo.localityWeight();
                  if (endpoint.loadBalancingWeight() != 0) {
                    weight *= endpoint.loadBalancingWeight();
                  }
                  String localityName = localityName(locality);
                  Attributes attr =
                      endpoint.eag().getAttributes().toBuilder()
                          .set(XdsAttributes.ATTR_LOCALITY, locality)
                          .set(XdsAttributes.ATTR_LOCALITY_NAME, localityName)
                          .set(XdsAttributes.ATTR_LOCALITY_WEIGHT,
                              localityLbInfo.localityWeight())
                          .set(XdsAttributes.ATTR_SERVER_WEIGHT, weight)
                          .set(XdsAttributes.ATTR_ADDRESS_NAME, endpoint.hostname())
                          .build();
                  EquivalentAddressGroup eag = new EquivalentAddressGroup(
                      endpoint.eag().getAddresses(), attr);
                  eag = AddressFilter.setPathFilter(eag, Arrays.asList(priorityName, localityName));
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
                  locality, localityLbInfo.localityWeight());
            }
            if (prioritizedLocalityWeights.isEmpty()) {
              // Will still update the result, as if the cluster resource is revoked.
              logger.log(XdsLogLevel.INFO,
                  "Cluster {0} has no usable priority/locality/endpoint", update.clusterName);
            }
            sortedPriorityNames.retainAll(prioritizedLocalityWeights.keySet());
            Map<String, PriorityChildConfig>
                priorityChildConfigs =
                generateEdsBasedPriorityChildConfigs(name, edsServiceName,
                    lrsServerInfo, maxConcurrentRequests, tlsContext, filterMetadata,
                    outlierDetection, endpointLbConfig, lbRegistry, prioritizedLocalityWeights,
                    dropOverloads);
            status = Status.OK;
            resolved = true;
            result = new ClusterResolutionResult(addresses, priorityChildConfigs,
                sortedPriorityNames);
            handleEndpointResourceUpdate();
          }
        }

        new EndpointsUpdated().run();
      }

      private List<String> generatePriorityNames(
          String name, Map<Locality, Endpoints.LocalityLbEndpoints> localityLbEndpoints) {
        TreeMap<Integer, List<Locality>> todo = new TreeMap<>();
        for (Locality locality : localityLbEndpoints.keySet()) {
          int priority = localityLbEndpoints.get(locality).priority();
          if (!todo.containsKey(priority)) {
            todo.put(priority, new ArrayList<>());
          }
          todo.get(priority).add(locality);
        }
        Map<Locality, String> newNames = new HashMap<>();
        Set<String> usedNames = new HashSet<>();
        List<String> ret = new ArrayList<>();
        for (Integer priority: todo.keySet()) {
          String foundName = "";
          for (Locality locality : todo.get(priority)) {
            if (localityPriorityNames.containsKey(locality)
                && usedNames.add(localityPriorityNames.get(locality))) {
              foundName = localityPriorityNames.get(locality);
              break;
            }
          }
          if ("".equals(foundName)) {
            foundName = String.format(Locale.US, "%s[child%d]", name, priorityNameGenId++);
          }
          for (Locality locality : todo.get(priority)) {
            newNames.put(locality, foundName);
          }
          ret.add(foundName);
        }
        localityPriorityNames = newNames;
        return ret;
      }

      void onError(final Status error) {
        if (shutdown) {
          return;
        }
        String resourceName = edsServiceName != null ? edsServiceName : name;
        status = Status.UNAVAILABLE
            .withDescription(String.format("Unable to load EDS %s. xDS server returned: %s: %s",
                resourceName, error.getCode(), error.getDescription()))
            .withCause(error.getCause());
        logger.log(XdsLogLevel.WARNING, "Received EDS error: {0}", error);
        handleEndpointResolutionError();
      }
    }

    private final class LogicalDnsClusterState extends ClusterState {
      private final String dnsHostName;
      private final NameResolver.Factory nameResolverFactory;
      private final NameResolver.Args nameResolverArgs;
      private NameResolver resolver;
      @Nullable
      private BackoffPolicy backoffPolicy;
      @Nullable
      private SynchronizationContext.ScheduledHandle scheduledRefresh;

      private LogicalDnsClusterState(String name, String dnsHostName,
                                     @Nullable Bootstrapper.ServerInfo lrsServerInfo,
                                     @Nullable Long maxConcurrentRequests,
                                     @Nullable EnvoyServerProtoData.UpstreamTlsContext tlsContext,
                                     Map<String, Struct> filterMetadata) {
        super(name, lrsServerInfo, maxConcurrentRequests, tlsContext, filterMetadata, null);
        this.dnsHostName = checkNotNull(dnsHostName, "dnsHostName");
        nameResolverFactory =
            checkNotNull(helper.getNameResolverRegistry().asFactory(), "nameResolverFactory");
        nameResolverArgs = checkNotNull(helper.getNameResolverArgs(), "nameResolverArgs");
      }

      @Override
      void start() {
        URI uri;
        try {
          uri = new URI("dns", "", "/" + dnsHostName, null);
        } catch (URISyntaxException e) {
          status = Status.INTERNAL.withDescription(
              "Bug, invalid URI creation: " + dnsHostName).withCause(e);
          handleEndpointResolutionError();
          return;
        }
        resolver = nameResolverFactory.newNameResolver(uri, nameResolverArgs);
        if (resolver == null) {
          status = Status.INTERNAL.withDescription("Xds cluster resolver lb for logical DNS "
              + "cluster [" + name + "] cannot find DNS resolver with uri:" + uri);
          handleEndpointResolutionError();
          return;
        }
        resolver.start(new LogicalDnsClusterState.NameResolverListener(dnsHostName));
      }

      void refresh() {
        if (resolver == null) {
          return;
        }
        cancelBackoff();
        resolver.refresh();
      }

      @Override
      void shutdown() {
        super.shutdown();
        if (resolver != null) {
          resolver.shutdown();
        }
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
        private final String dnsHostName;

        NameResolverListener(String dnsHostName) {
          this.dnsHostName = dnsHostName;
        }

        @Override
        public void onResult(final NameResolver.ResolutionResult resolutionResult) {
          class NameResolved implements Runnable {
            @Override
            public void run() {
              if (shutdown) {
                return;
              }
              backoffPolicy = null;  // reset backoff sequence if succeeded
              // Arbitrary priority notation for all DNS-resolved endpoints.
              String priorityName = priorityName(name, 0);  // value doesn't matter
              List<EquivalentAddressGroup> addresses = new ArrayList<>();
              for (EquivalentAddressGroup eag : resolutionResult.getAddresses()) {
                // No weight attribute is attached, all endpoint-level LB policy should be able
                // to handle such it.
                String localityName = localityName(XdsNameResolver.LOGICAL_DNS_CLUSTER_LOCALITY);
                Attributes attr = eag.getAttributes().toBuilder()
                    .set(XdsAttributes.ATTR_LOCALITY, XdsNameResolver.LOGICAL_DNS_CLUSTER_LOCALITY)
                    .set(XdsAttributes.ATTR_LOCALITY_NAME, localityName)
                    .set(XdsAttributes.ATTR_ADDRESS_NAME, dnsHostName)
                    .build();
                eag = new EquivalentAddressGroup(eag.getAddresses(), attr);
                eag = AddressFilter.setPathFilter(eag, Arrays.asList(priorityName, localityName));
                addresses.add(eag);
              }
              PriorityChildConfig priorityChildConfig =
                  generateDnsBasedPriorityChildConfig(
                  name, lrsServerInfo, maxConcurrentRequests, tlsContext, filterMetadata,
                  lbRegistry, Collections.<Endpoints.DropOverload>emptyList());
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
              // NameResolver.Listener API cannot distinguish between address-not-found and
              // transient errors. If the error occurs in the first resolution, treat it as
              // address not found. Otherwise, either there is previously resolved addresses
              // previously encountered error, propagate the error to downstream/upstream and
              // let downstream/upstream handle it.
              if (!resolved) {
                resolved = true;
                handleEndpointResourceUpdate();
              } else {
                handleEndpointResolutionError();
              }
              if (scheduledRefresh != null && scheduledRefresh.isPending()) {
                return;
              }
              if (backoffPolicy == null) {
                backoffPolicy = backoffPolicyProvider.get();
              }
              long delayNanos = backoffPolicy.nextBackoffNanos();
              logger.log(XdsLogLevel.DEBUG,
                  "Logical DNS resolver for cluster {0} encountered name resolution "
                      + "error: {1}, scheduling DNS resolution backoff for {2} ns",
                  name, error, delayNanos);
              scheduledRefresh =
                  syncContext.schedule(
                      new LogicalDnsClusterState.DelayedNameResolverRefresh(), delayNanos,
                      TimeUnit.NANOSECONDS, helper.getScheduledExecutorService());
            }
          });
        }
      }
    }
  }

  static class ClusterResolutionResult {
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

  static final class ClusterResolverConfig {
    // Ordered list of clusters to be resolved.
    final List<DiscoveryMechanism> discoveryMechanisms;
    // GracefulSwitch configuration
    final Object lbConfig;

    ClusterResolverConfig(List<DiscoveryMechanism> discoveryMechanisms, Object lbConfig) {
      this.discoveryMechanisms = checkNotNull(discoveryMechanisms, "discoveryMechanisms");
      this.lbConfig = checkNotNull(lbConfig, "lbConfig");
    }

    @Override
    public int hashCode() {
      return Objects.hash(discoveryMechanisms, lbConfig);
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      ClusterResolverConfig that = (ClusterResolverConfig) o;
      return discoveryMechanisms.equals(that.discoveryMechanisms)
          && lbConfig.equals(that.lbConfig);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("discoveryMechanisms", discoveryMechanisms)
          .add("lbConfig", lbConfig)
          .toString();
    }

    // Describes the mechanism for a specific cluster.
    static final class DiscoveryMechanism {
      // Name of the cluster to resolve.
      final String cluster;
      // Type of the cluster.
      final Type type;
      // Load reporting server info. Null if not enabled.
      @Nullable
      final Bootstrapper.ServerInfo lrsServerInfo;
      // Cluster-level max concurrent request threshold. Null if not specified.
      @Nullable
      final Long maxConcurrentRequests;
      // TLS context for connections to endpoints in the cluster.
      @Nullable
      final EnvoyServerProtoData.UpstreamTlsContext tlsContext;
      // Resource name for resolving endpoints via EDS. Only valid for EDS clusters.
      @Nullable
      final String edsServiceName;
      // Hostname for resolving endpoints via DNS. Only valid for LOGICAL_DNS clusters.
      @Nullable
      final String dnsHostName;
      @Nullable
      final EnvoyServerProtoData.OutlierDetection outlierDetection;
      final Map<String, Struct> filterMetadata;
      final StatusOr<EdsUpdate> endpointConfig;

      enum Type {
        EDS,
        LOGICAL_DNS,
      }

      private DiscoveryMechanism(
          String cluster, Type type, @Nullable String edsServiceName,
          @Nullable String dnsHostName, @Nullable Bootstrapper.ServerInfo lrsServerInfo,
          @Nullable Long maxConcurrentRequests,
          @Nullable EnvoyServerProtoData.UpstreamTlsContext tlsContext,
          Map<String, Struct> filterMetadata,
          @Nullable EnvoyServerProtoData.OutlierDetection outlierDetection,
          @Nullable StatusOr<EdsUpdate> endpointConfig) {
        this.cluster = checkNotNull(cluster, "cluster");
        this.type = checkNotNull(type, "type");
        this.edsServiceName = edsServiceName;
        this.dnsHostName = dnsHostName;
        this.lrsServerInfo = lrsServerInfo;
        this.maxConcurrentRequests = maxConcurrentRequests;
        this.tlsContext = tlsContext;
        this.filterMetadata = ImmutableMap.copyOf(checkNotNull(filterMetadata, "filterMetadata"));
        this.outlierDetection = outlierDetection;
        this.endpointConfig = endpointConfig;
      }

      static DiscoveryMechanism forEds(
          String cluster,
          @Nullable String edsServiceName,
          @Nullable Bootstrapper.ServerInfo lrsServerInfo,
          @Nullable Long maxConcurrentRequests,
          @Nullable EnvoyServerProtoData.UpstreamTlsContext tlsContext,
          Map<String, Struct> filterMetadata,
          EnvoyServerProtoData.OutlierDetection outlierDetection,
          StatusOr<EdsUpdate> endpointConfig) {
        return new DiscoveryMechanism(cluster, Type.EDS, edsServiceName, null, lrsServerInfo,
            maxConcurrentRequests, tlsContext, filterMetadata, outlierDetection, endpointConfig);
      }

      static DiscoveryMechanism forLogicalDns(
          String cluster, String dnsHostName,
          @Nullable Bootstrapper.ServerInfo lrsServerInfo, @Nullable Long maxConcurrentRequests,
          @Nullable EnvoyServerProtoData.UpstreamTlsContext tlsContext,
          Map<String, Struct> filterMetadata) {
        return new DiscoveryMechanism(cluster, Type.LOGICAL_DNS, null, dnsHostName,
            lrsServerInfo, maxConcurrentRequests, tlsContext, filterMetadata, null, null);
      }

      @Override
      public int hashCode() {
        return Objects.hash(cluster, type, lrsServerInfo, maxConcurrentRequests, tlsContext,
            edsServiceName, dnsHostName, filterMetadata, outlierDetection);
      }

      @Override
      public boolean equals(Object o) {
        if (this == o) {
          return true;
        }
        if (o == null || getClass() != o.getClass()) {
          return false;
        }
        DiscoveryMechanism that = (DiscoveryMechanism) o;
        return cluster.equals(that.cluster)
            && type == that.type
            && Objects.equals(edsServiceName, that.edsServiceName)
            && Objects.equals(dnsHostName, that.dnsHostName)
            && Objects.equals(lrsServerInfo, that.lrsServerInfo)
            && Objects.equals(maxConcurrentRequests, that.maxConcurrentRequests)
            && Objects.equals(tlsContext, that.tlsContext)
            && Objects.equals(filterMetadata, that.filterMetadata)
            && Objects.equals(outlierDetection, that.outlierDetection);
      }

      @Override
      public String toString() {
        MoreObjects.ToStringHelper toStringHelper =
            MoreObjects.toStringHelper(this)
                .add("cluster", cluster)
                .add("type", type)
                .add("edsServiceName", edsServiceName)
                .add("dnsHostName", dnsHostName)
                .add("lrsServerInfo", lrsServerInfo)
                // Exclude tlsContext as its string representation is cumbersome.
                .add("maxConcurrentRequests", maxConcurrentRequests)
                .add("filterMetadata", filterMetadata)
                // Exclude outlierDetection as its string representation is long.
                ;
        return toStringHelper.toString();
      }
    }
  }

  /**
   * The state of a CDS working session of {@link CdsLoadBalancer2}. Created and started when
   * receiving the CDS LB policy config with the top-level cluster name.
   */
  final class CdsLbState {

    private final ClusterStateDetails root;
    private final Map<String, ClusterStateDetails> clusterStates = new ConcurrentHashMap<>();
    private LoadBalancer childLb;

    private CdsLbState(String rootCluster,
                       ImmutableMap<String, StatusOr<XdsClusterConfig>> clusterConfigs,
                       String rootName) {
      root = new ClusterStateDetails(rootName, clusterConfigs.get(rootName));
      clusterStates.put(rootCluster, root);
      initializeChildren(clusterConfigs, root);
    }

    private void start() {
      root.start();
      handleClusterDiscovered();
    }

    private void shutdown() {
      root.shutdown();
      if (childLb != null) {
        childLb.shutdown();
      }
    }

    // If doesn't have children is a no-op
    private void initializeChildren(ImmutableMap<String,
        StatusOr<XdsClusterConfig>> clusterConfigs, ClusterStateDetails curRoot) {
      if (curRoot.result == null) {
        return;
      }
      ImmutableList<String> childNames = curRoot.result.prioritizedClusterNames();
      if (childNames == null) {
        return;
      }

      for (String clusterName : childNames) {
        StatusOr<XdsClusterConfig> configStatusOr = clusterConfigs.get(clusterName);
        if (configStatusOr == null) {
          logger.log(XdsLogLevel.DEBUG, "Child cluster %s of %s has no matching config",
              clusterName, this.root.name);
          continue;
        }
        ClusterStateDetails clusterStateDetails = clusterStates.get(clusterName);
        if (clusterStateDetails == null) {
          clusterStateDetails = new ClusterStateDetails(clusterName, configStatusOr);
          clusterStates.put(clusterName, clusterStateDetails);
        }
        initializeChildren(clusterConfigs, clusterStateDetails);
      }
    }


    private void handleClusterDiscovered() {
      List<DiscoveryMechanism> instances = new ArrayList<>();

      // Used for loop detection to break the infinite recursion that loops would cause
      Map<ClusterStateDetails, List<ClusterStateDetails>> parentClusters = new HashMap<>();
      Status loopStatus = null;

      // Level-order traversal.
      // Collect configurations for all non-aggregate (leaf) clusters.
      Queue<ClusterStateDetails> queue = new ArrayDeque<>();
      queue.add(root);
      while (!queue.isEmpty()) {
        int size = queue.size();
        for (int i = 0; i < size; i++) {
          ClusterStateDetails clusterState = queue.remove();
          if (clusterState.result == null) {  // resource revoked or not exists
            continue;
          }
          if (clusterState.isLeaf) {
            if (instances.stream().map(inst -> inst.cluster).noneMatch(clusterState.name::equals)) {
              DiscoveryMechanism instance;
              if (clusterState.result.clusterType() == ClusterType.EDS) {
                instance = DiscoveryMechanism.forEds(
                    clusterState.name, clusterState.result.edsServiceName(),
                    clusterState.result.lrsServerInfo(),
                    clusterState.result.maxConcurrentRequests(),
                    clusterState.result.upstreamTlsContext(),
                    clusterState.result.filterMetadata(),
                    clusterState.result.outlierDetection(),
                    clusterState.getEndpointConfigStatusOr());
              } else {  // logical DNS
                instance = DiscoveryMechanism.forLogicalDns(
                    clusterState.name, clusterState.result.dnsHostName(),
                    clusterState.result.lrsServerInfo(),
                    clusterState.result.maxConcurrentRequests(),
                    clusterState.result.upstreamTlsContext(),
                    clusterState.result.filterMetadata());
              }
              instances.add(instance);
            }
          } else {
            if (clusterState.childClusterStates == null) {
              continue;
            }
            // Do loop detection and break recursion if detected
            List<String> namesCausingLoops = identifyLoops(clusterState, parentClusters);
            if (namesCausingLoops.isEmpty()) {
              queue.addAll(clusterState.childClusterStates.values());
            } else {
              // Do cleanup
              if (childLb != null) {
                childLb.shutdown();
                childLb = null;
              }
              if (loopStatus != null) {
                logger.log(XdsLogLevel.WARNING,
                    "Multiple loops in CDS config.  Old msg:  " + loopStatus.getDescription());
              }
              loopStatus = Status.UNAVAILABLE.withDescription(String.format(
                  "CDS error: circular aggregate clusters directly under %s for "
                      + "root cluster %s, named %s",
                  clusterState.name, root.name, namesCausingLoops));
            }
          }
        }
      }

      if (loopStatus != null) {
        helper.updateBalancingState(
            TRANSIENT_FAILURE, new FixedResultPicker(PickResult.withError(loopStatus)));
        return;
      }

      if (instances.isEmpty()) {  // none of non-aggregate clusters exists
        if (childLb != null) {
          childLb.shutdown();
          childLb = null;
        }
        Status unavailable = Status.UNAVAILABLE.withDescription(
            "CDS error: found 0 leaf (logical DNS or EDS) clusters for root cluster " + root.name);
        helper.updateBalancingState(
            TRANSIENT_FAILURE, new FixedResultPicker(PickResult.withError(unavailable)));
        return;
      }

      // The LB policy config is provided in service_config.proto/JSON format.
      NameResolver.ConfigOrError configOrError =
          GracefulSwitchLoadBalancer.parseLoadBalancingPolicyConfig(
              Arrays.asList(root.result.lbPolicyConfig()), lbRegistry);
      if (configOrError.getError() != null) {
        throw configOrError.getError().augmentDescription("Unable to parse the LB config")
            .asRuntimeException();
      }

      ClusterResolverConfig config = new ClusterResolverConfig(
          Collections.unmodifiableList(instances), configOrError.getConfig());
      if (childLb == null) {
        logger.log(XdsLogLevel.DEBUG, "Config: {0}", config);
        childLb = new GracefulSwitchLoadBalancer(helper);
      }
      Object gracefulConfig =
          GracefulSwitchLoadBalancer.createLoadBalancingPolicyConfig(
              new ClusterResolverLbStateFactory(), config);
      childLb.handleResolvedAddresses(
          resolvedAddresses.toBuilder().setLoadBalancingPolicyConfig(gracefulConfig).build());
    }

    /**
     * Returns children that would cause loops and builds up the parentClusters map.
     **/

    private List<String> identifyLoops(
        ClusterStateDetails clusterState,
        Map<ClusterStateDetails, List<ClusterStateDetails>> parentClusters) {
      Set<String> ancestors = new HashSet<>();
      ancestors.add(clusterState.name);
      addAncestors(ancestors, clusterState, parentClusters);

      List<String> namesCausingLoops = new ArrayList<>();
      for (ClusterStateDetails state : clusterState.childClusterStates.values()) {
        if (ancestors.contains(state.name)) {
          namesCausingLoops.add(state.name);
        }
      }

      // Update parent map with entries from remaining children to clusterState
      clusterState.childClusterStates.values().stream()
          .filter(child -> !namesCausingLoops.contains(child.name))
          .forEach(
              child -> parentClusters.computeIfAbsent(child, k -> new ArrayList<>())
                  .add(clusterState));

      return namesCausingLoops;
    }

    /** Recursively add all parents to the ancestors list. **/
    private void addAncestors(Set<String> ancestors, ClusterStateDetails clusterState,
        Map<ClusterStateDetails, List<ClusterStateDetails>> parentClusters) {
      List<ClusterStateDetails> directParents = parentClusters.get(clusterState);
      if (directParents != null) {
        directParents.stream().map(c -> c.name).forEach(ancestors::add);
        directParents.forEach(p -> addAncestors(ancestors, p, parentClusters));
      }
    }

    private final class ClusterStateDetails {
      private final String name;
      @Nullable
      private Map<String, ClusterStateDetails> childClusterStates;
      @Nullable
      private CdsUpdate result;
      private boolean shutdown;
      // Following fields are effectively final.
      private boolean isLeaf;
      private EdsUpdate endpointConfig;
      private Status error;

      private ClusterStateDetails(String name, StatusOr<XdsClusterConfig> configOr) {
        this.name = name;
        if (configOr.hasValue()) {
          XdsClusterConfig config = configOr.getValue();
          this.result = config.getClusterResource();
          this.isLeaf = result.clusterType() != ClusterType.AGGREGATE;
          if (isLeaf && config.getChildren() != null) {
            // We should only see leaf clusters here.
            assert config.getChildren() instanceof XdsClusterConfig.EndpointConfig;
            StatusOr<EdsUpdate> endpointConfigOr =
                ((XdsClusterConfig.EndpointConfig) config.getChildren()).getEndpoint();
            if (endpointConfigOr.hasValue()) {
              endpointConfig = endpointConfigOr.getValue();
            } else {
              this.error = endpointConfigOr.getStatus();
              this.result = null;
            }
          }
        } else {
          this.error = configOr.getStatus();
        }
      }

      StatusOr<EdsUpdate> getEndpointConfigStatusOr() {
        return (error == null) ? StatusOr.fromValue(endpointConfig) : StatusOr.fromStatus(error);
      }

      private void start() {
        shutdown = false;
        if (error != null) {
          return;
        }
        update(result, StatusOr.fromValue(endpointConfig));
      }

      void shutdown() {
        shutdown = true;
        if (childClusterStates != null) {
          // recursively shut down all descendants
          childClusterStates.values().stream()
              .filter(state -> !state.shutdown)
              .forEach(ClusterStateDetails::shutdown);
        }
      }

      private void update(final CdsUpdate update, StatusOr<EdsUpdate> endpointConfig) {
        if (shutdown) {
          return;
        }
        logger.log(XdsLogLevel.DEBUG, "Received cluster update {0}", update);
        result = update;
        switch (update.clusterType()) {
          case AGGREGATE:
            isLeaf = false;
            logger.log(XdsLogLevel.INFO, "Aggregate cluster {0}, underlying clusters: {1}",
                update.clusterName(), update.prioritizedClusterNames());
            Map<String, ClusterStateDetails> newChildStates = new LinkedHashMap<>();

            for (String cluster : update.prioritizedClusterNames()) {
              if (newChildStates.containsKey(cluster)) {
                logger.log(XdsLogLevel.WARNING,
                    String.format("duplicate cluster name %s in aggregate %s is being ignored",
                        cluster, update.clusterName()));
                continue;
              }
              if (childClusterStates == null || !childClusterStates.containsKey(cluster)) {
                ClusterStateDetails childState = clusterStates.get(cluster);
                if (childState == null) {
                  logger.log(XdsLogLevel.WARNING,
                      "Cluster {0} in aggregate {1} is not found", cluster, update.clusterName());
                  continue;
                }
                if (childState.shutdown) {
                  childState.shutdown = false;
                }
                newChildStates.put(cluster, childState);
              } else {
                newChildStates.put(cluster, childClusterStates.remove(cluster));
              }
            }

            if (childClusterStates != null) {  // stop subscribing to revoked child clusters
              for (ClusterStateDetails oldChildState : childClusterStates.values()) {
                if (!newChildStates.containsKey(oldChildState.name)) {
                  oldChildState.shutdown();
                }
              }
            }
            childClusterStates = newChildStates;
            break;
          case EDS:
            isLeaf = true;
            assert endpointConfig != null;
            if (!endpointConfig.getStatus().isOk()) {
              logger.log(XdsLogLevel.INFO, "EDS cluster {0}, edsServiceName: {1}, error: {2}",
                  update.clusterName(), update.edsServiceName(), endpointConfig.getStatus());
            } else {
              logger.log(XdsLogLevel.INFO, "EDS cluster {0}, edsServiceName: {1}",
                  update.clusterName(), update.edsServiceName());
              this.endpointConfig = endpointConfig.getValue();
            }
            break;
          case LOGICAL_DNS:
            isLeaf = true;
            logger.log(XdsLogLevel.INFO, "Logical DNS cluster {0}", update.clusterName());
            break;
          default:
            throw new AssertionError("should never be here");
        }
      }
    }
  }
}
