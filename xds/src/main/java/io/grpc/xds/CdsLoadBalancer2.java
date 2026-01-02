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
import static io.grpc.xds.XdsLbPolicies.CDS_POLICY_NAME;
import static io.grpc.xds.XdsLbPolicies.PRIORITY_POLICY_NAME;

import com.google.common.collect.ImmutableMap;
import com.google.errorprone.annotations.CheckReturnValue;
import io.grpc.Attributes;
import io.grpc.EquivalentAddressGroup;
import io.grpc.HttpConnectProxiedSocketAddress;
import io.grpc.InternalLogId;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancerProvider;
import io.grpc.LoadBalancerRegistry;
import io.grpc.NameResolver;
import io.grpc.Status;
import io.grpc.StatusOr;
import io.grpc.util.GracefulSwitchLoadBalancer;
import io.grpc.util.OutlierDetectionLoadBalancer.OutlierDetectionLoadBalancerConfig;
import io.grpc.xds.CdsLoadBalancerProvider.CdsConfig;
import io.grpc.xds.ClusterImplLoadBalancerProvider.ClusterImplConfig;
import io.grpc.xds.Endpoints.DropOverload;
import io.grpc.xds.Endpoints.LbEndpoint;
import io.grpc.xds.Endpoints.LocalityLbEndpoints;
import io.grpc.xds.EnvoyServerProtoData.FailurePercentageEjection;
import io.grpc.xds.EnvoyServerProtoData.OutlierDetection;
import io.grpc.xds.EnvoyServerProtoData.SuccessRateEjection;
import io.grpc.xds.PriorityLoadBalancerProvider.PriorityLbConfig;
import io.grpc.xds.PriorityLoadBalancerProvider.PriorityLbConfig.PriorityChildConfig;
import io.grpc.xds.XdsClusterResource.CdsUpdate;
import io.grpc.xds.XdsClusterResource.CdsUpdate.ClusterType;
import io.grpc.xds.XdsConfig.Subscription;
import io.grpc.xds.XdsConfig.XdsClusterConfig;
import io.grpc.xds.XdsConfig.XdsClusterConfig.AggregateConfig;
import io.grpc.xds.XdsConfig.XdsClusterConfig.EndpointConfig;
import io.grpc.xds.XdsEndpointResource.EdsUpdate;
import io.grpc.xds.client.Locality;
import io.grpc.xds.client.XdsLogger;
import io.grpc.xds.client.XdsLogger.XdsLogLevel;
import io.grpc.xds.internal.XdsInternalAttributes;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Load balancer for cds_experimental LB policy. One instance per top-level cluster.
 * The top-level cluster may be a plain EDS/logical-DNS cluster or an aggregate cluster formed
 * by a group of sub-clusters in a tree hierarchy.
 */
final class CdsLoadBalancer2 extends LoadBalancer {
  private final XdsLogger logger;
  private final Helper helper;
  private final LoadBalancerRegistry lbRegistry;
  private final ClusterState clusterState = new ClusterState();
  private GracefulSwitchLoadBalancer delegate;
  // Following fields are effectively final.
  private String clusterName;
  private Subscription clusterSubscription;

  CdsLoadBalancer2(Helper helper, LoadBalancerRegistry lbRegistry) {
    this.helper = checkNotNull(helper, "helper");
    this.lbRegistry = checkNotNull(lbRegistry, "lbRegistry");
    this.delegate = new GracefulSwitchLoadBalancer(helper);
    logger = XdsLogger.withLogId(InternalLogId.allocate("cds-lb", helper.getAuthority()));
    logger.log(XdsLogLevel.INFO, "Created");
  }

  @Override
  public Status acceptResolvedAddresses(ResolvedAddresses resolvedAddresses) {
    logger.log(XdsLogLevel.DEBUG, "Received resolution result: {0}", resolvedAddresses);
    if (this.clusterName == null) {
      CdsConfig config = (CdsConfig) resolvedAddresses.getLoadBalancingPolicyConfig();
      logger.log(XdsLogLevel.INFO, "Config: {0}", config);
      if (config.isDynamic) {
        clusterSubscription = resolvedAddresses.getAttributes()
            .get(XdsAttributes.XDS_CLUSTER_SUBSCRIPT_REGISTRY)
            .subscribeToCluster(config.name);
      }
      this.clusterName = config.name;
    }
    XdsConfig xdsConfig = resolvedAddresses.getAttributes().get(XdsAttributes.XDS_CONFIG);
    StatusOr<XdsClusterConfig> clusterConfigOr = xdsConfig.getClusters().get(clusterName);
    if (clusterConfigOr == null) {
      if (clusterSubscription == null) {
        // Should be impossible, because XdsDependencyManager wouldn't have generated this
        return fail(Status.INTERNAL.withDescription(
            errorPrefix() + "Unable to find non-dynamic cluster"));
      }
      // The dynamic cluster must not have loaded yet
      return Status.OK;
    }
    if (!clusterConfigOr.hasValue()) {
      return fail(clusterConfigOr.getStatus());
    }
    XdsClusterConfig clusterConfig = clusterConfigOr.getValue();

    NameResolver.ConfigOrError configOrError;
    if (clusterConfig.getChildren() instanceof EndpointConfig) {
      // The LB policy config is provided in service_config.proto/JSON format.
      configOrError =
              GracefulSwitchLoadBalancer.parseLoadBalancingPolicyConfig(
                      Arrays.asList(clusterConfig.getClusterResource().lbPolicyConfig()),
                      lbRegistry);
      if (configOrError.getError() != null) {
        // Should be impossible, because XdsClusterResource validated this
        return fail(Status.INTERNAL.withDescription(
                errorPrefix() + "Unable to parse the LB config: " + configOrError.getError()));
      }

      StatusOr<EdsUpdate> edsUpdate = getEdsUpdate(xdsConfig, clusterName);
      StatusOr<ClusterResolutionResult> statusOrResult = clusterState.edsUpdateToResult(
          clusterName,
          clusterConfig.getClusterResource(),
          configOrError.getConfig(),
          edsUpdate);
      if (!statusOrResult.hasValue()) {
        Status status = Status.UNAVAILABLE
            .withDescription(statusOrResult.getStatus().getDescription())
            .withCause(statusOrResult.getStatus().getCause());
        delegate.handleNameResolutionError(status);
        return status;
      }
      ClusterResolutionResult result = statusOrResult.getValue();
      List<EquivalentAddressGroup> addresses = result.addresses;
      if (addresses.isEmpty()) {
        Status status = Status.UNAVAILABLE
            .withDescription("No usable endpoint from cluster: " + clusterName);
        delegate.handleNameResolutionError(status);
        return status;
      }
      Object gracefulConfig = GracefulSwitchLoadBalancer.createLoadBalancingPolicyConfig(
          lbRegistry.getProvider(PRIORITY_POLICY_NAME),
          new PriorityLbConfig(
              Collections.unmodifiableMap(result.priorityChildConfigs),
              Collections.unmodifiableList(result.priorities)));
      return delegate.acceptResolvedAddresses(
          resolvedAddresses.toBuilder()
            .setLoadBalancingPolicyConfig(gracefulConfig)
            .setAddresses(Collections.unmodifiableList(addresses))
            .build());
    } else if (clusterConfig.getChildren() instanceof AggregateConfig) {
      Map<String, PriorityChildConfig> priorityChildConfigs = new HashMap<>();
      List<String> leafClusters = ((AggregateConfig) clusterConfig.getChildren()).getLeafNames();
      for (String childCluster: leafClusters) {
        priorityChildConfigs.put(childCluster,
                new PriorityChildConfig(
                        GracefulSwitchLoadBalancer.createLoadBalancingPolicyConfig(
                                lbRegistry.getProvider(CDS_POLICY_NAME),
                                new CdsConfig(childCluster)),
                        false));
      }
      Object gracefulConfig = GracefulSwitchLoadBalancer.createLoadBalancingPolicyConfig(
          lbRegistry.getProvider(PRIORITY_POLICY_NAME),
          new PriorityLoadBalancerProvider.PriorityLbConfig(
              Collections.unmodifiableMap(priorityChildConfigs), leafClusters));
      return delegate.acceptResolvedAddresses(
          resolvedAddresses.toBuilder().setLoadBalancingPolicyConfig(gracefulConfig).build());
    } else {
      return fail(Status.INTERNAL.withDescription(
              errorPrefix() + "Unexpected cluster children type: "
                      + clusterConfig.getChildren().getClass()));
    }
  }

  @Override
  public void handleNameResolutionError(Status error) {
    logger.log(XdsLogLevel.WARNING, "Received name resolution error: {0}", error);
    if (delegate != null) {
      delegate.handleNameResolutionError(error);
    } else {
      helper.updateBalancingState(
          TRANSIENT_FAILURE, new FixedResultPicker(PickResult.withError(error)));
    }
  }

  @Override
  public void shutdown() {
    logger.log(XdsLogLevel.INFO, "Shutdown");
    delegate.shutdown();
    delegate = new GracefulSwitchLoadBalancer(helper);
    if (clusterSubscription != null) {
      clusterSubscription.close();
      clusterSubscription = null;
    }
  }

  @CheckReturnValue // don't forget to return up the stack after the fail call
  private Status fail(Status error) {
    delegate.shutdown();
    helper.updateBalancingState(
        TRANSIENT_FAILURE, new FixedResultPicker(PickResult.withError(error)));
    return Status.OK; // XdsNameResolver isn't a polling NR, so this value doesn't matter
  }

  private String errorPrefix() {
    return "CdsLb for " + clusterName + ": ";
  }

  private static StatusOr<EdsUpdate> getEdsUpdate(XdsConfig xdsConfig, String cluster) {
    StatusOr<XdsClusterConfig> clusterConfig = xdsConfig.getClusters().get(cluster);
    if (clusterConfig == null) {
      return StatusOr.fromStatus(Status.INTERNAL
          .withDescription("BUG: cluster resolver could not find cluster in xdsConfig"));
    }
    if (!clusterConfig.hasValue()) {
      return StatusOr.fromStatus(clusterConfig.getStatus());
    }
    if (!(clusterConfig.getValue().getChildren() instanceof XdsClusterConfig.EndpointConfig)) {
      return StatusOr.fromStatus(Status.INTERNAL
          .withDescription("BUG: cluster resolver cluster with children of unknown type"));
    }
    XdsClusterConfig.EndpointConfig endpointConfig =
        (XdsClusterConfig.EndpointConfig) clusterConfig.getValue().getChildren();
    return endpointConfig.getEndpoint();
  }

  /**
   * Generates a string that represents the priority in the LB policy config. The string is unique
   * across priorities in all clusters and priorityName(c, p1) < priorityName(c, p2) iff p1 < p2.
   * The ordering is undefined for priorities in different clusters.
   */
  private static String priorityName(String cluster, int priority) {
    return cluster + "[child" + priority + "]";
  }

  /**
   * Generates a string that represents the locality in the LB policy config. The string is unique
   * across all localities in all clusters.
   */
  private static String localityName(Locality locality) {
    return "{region=\"" + locality.region()
        + "\", zone=\"" + locality.zone()
        + "\", sub_zone=\"" + locality.subZone()
        + "\"}";
  }

  private final class ClusterState {
    private Map<Locality, String> localityPriorityNames = Collections.emptyMap();
    int priorityNameGenId = 1;

    StatusOr<ClusterResolutionResult> edsUpdateToResult(
        String clusterName,
        CdsUpdate discovery,
        Object lbConfig,
        StatusOr<EdsUpdate> updateOr) {
      if (!updateOr.hasValue()) {
        return StatusOr.fromStatus(updateOr.getStatus());
      }
      EdsUpdate update = updateOr.getValue();
      logger.log(XdsLogLevel.DEBUG, "Received endpoint update {0}", update);
      if (logger.isLoggable(XdsLogLevel.INFO)) {
        logger.log(XdsLogLevel.INFO, "Cluster {0}: {1} localities, {2} drop categories",
            clusterName, update.localityLbEndpointsMap.size(),
            update.dropPolicies.size());
      }
      Map<Locality, LocalityLbEndpoints> localityLbEndpoints =
          update.localityLbEndpointsMap;
      List<DropOverload> dropOverloads = update.dropPolicies;
      List<EquivalentAddressGroup> addresses = new ArrayList<>();
      Map<String, Map<Locality, Integer>> prioritizedLocalityWeights = new HashMap<>();
      List<String> sortedPriorityNames =
          generatePriorityNames(clusterName, localityLbEndpoints);
      for (Locality locality : localityLbEndpoints.keySet()) {
        LocalityLbEndpoints localityLbInfo = localityLbEndpoints.get(locality);
        String priorityName = localityPriorityNames.get(locality);
        boolean discard = true;
        for (LbEndpoint endpoint : localityLbInfo.endpoints()) {
          if (endpoint.isHealthy()) {
            discard = false;
            long weight = localityLbInfo.localityWeight();
            if (endpoint.loadBalancingWeight() != 0) {
              weight *= endpoint.loadBalancingWeight();
            }
            String localityName = localityName(locality);
            Attributes attr =
                endpoint.eag().getAttributes().toBuilder()
                    .set(io.grpc.xds.XdsAttributes.ATTR_LOCALITY, locality)
                    .set(EquivalentAddressGroup.ATTR_LOCALITY_NAME, localityName)
                    .set(io.grpc.xds.XdsAttributes.ATTR_LOCALITY_WEIGHT,
                        localityLbInfo.localityWeight())
                    .set(io.grpc.xds.XdsAttributes.ATTR_SERVER_WEIGHT, weight)
                    .set(XdsInternalAttributes.ATTR_ADDRESS_NAME, endpoint.hostname())
                    .build();
            EquivalentAddressGroup eag;
            if (discovery.isHttp11ProxyAvailable()) {
              List<SocketAddress> rewrittenAddresses = new ArrayList<>();
              for (SocketAddress addr : endpoint.eag().getAddresses()) {
                rewrittenAddresses.add(rewriteAddress(
                    addr, endpoint.endpointMetadata(), localityLbInfo.localityMetadata()));
              }
              eag = new EquivalentAddressGroup(rewrittenAddresses, attr);
            } else {
              eag = new EquivalentAddressGroup(endpoint.eag().getAddresses(), attr);
            }
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
            "Cluster {0} has no usable priority/locality/endpoint", clusterName);
      }
      sortedPriorityNames.retainAll(prioritizedLocalityWeights.keySet());
      Map<String, PriorityChildConfig> priorityChildConfigs =
          generatePriorityChildConfigs(
              clusterName, discovery, lbConfig, lbRegistry,
              prioritizedLocalityWeights, dropOverloads);
      return StatusOr.fromValue(new ClusterResolutionResult(addresses, priorityChildConfigs,
          sortedPriorityNames));
    }

    private SocketAddress rewriteAddress(SocketAddress addr,
        ImmutableMap<String, Object> endpointMetadata,
        ImmutableMap<String, Object> localityMetadata) {
      if (!(addr instanceof InetSocketAddress)) {
        return addr;
      }

      SocketAddress proxyAddress;
      try {
        proxyAddress = (SocketAddress) endpointMetadata.get(
            "envoy.http11_proxy_transport_socket.proxy_address");
        if (proxyAddress == null) {
          proxyAddress = (SocketAddress) localityMetadata.get(
              "envoy.http11_proxy_transport_socket.proxy_address");
        }
      } catch (ClassCastException e) {
        return addr;
      }

      if (proxyAddress == null) {
        return addr;
      }

      return HttpConnectProxiedSocketAddress.newBuilder()
          .setTargetAddress((InetSocketAddress) addr)
          .setProxyAddress(proxyAddress)
          .build();
    }

    private List<String> generatePriorityNames(String name,
        Map<Locality, LocalityLbEndpoints> localityLbEndpoints) {
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
          foundName = priorityName(name, priorityNameGenId++);
        }
        for (Locality locality : todo.get(priority)) {
          newNames.put(locality, foundName);
        }
        ret.add(foundName);
      }
      localityPriorityNames = newNames;
      return ret;
    }
  }

  private static class ClusterResolutionResult {
    // Endpoint addresses.
    private final List<EquivalentAddressGroup> addresses;
    // Config (include load balancing policy/config) for each priority in the cluster.
    private final Map<String, PriorityChildConfig> priorityChildConfigs;
    // List of priority names ordered in descending priorities.
    private final List<String> priorities;

    ClusterResolutionResult(List<EquivalentAddressGroup> addresses,
        Map<String, PriorityChildConfig> configs, List<String> priorities) {
      this.addresses = addresses;
      this.priorityChildConfigs = configs;
      this.priorities = priorities;
    }
  }

  /**
   * Generates configs to be used in the priority LB policy for priorities in a cluster.
   *
   * <p>priority LB -> cluster_impl LB (one per priority) -> (weighted_target LB
   * -> round_robin / least_request_experimental (one per locality)) / ring_hash_experimental
   */
  private static Map<String, PriorityChildConfig> generatePriorityChildConfigs(
      String clusterName,
      CdsUpdate discovery,
      Object endpointLbConfig,
      LoadBalancerRegistry lbRegistry,
      Map<String, Map<Locality, Integer>> prioritizedLocalityWeights,
      List<DropOverload> dropOverloads) {
    Map<String, PriorityChildConfig> configs = new HashMap<>();
    for (String priority : prioritizedLocalityWeights.keySet()) {
      ClusterImplConfig clusterImplConfig =
          new ClusterImplConfig(
              clusterName, discovery.edsServiceName(), discovery.lrsServerInfo(),
              discovery.maxConcurrentRequests(), dropOverloads, endpointLbConfig,
              discovery.upstreamTlsContext(), discovery.filterMetadata(),
              discovery.backendMetricPropagation());
      LoadBalancerProvider clusterImplLbProvider =
          lbRegistry.getProvider(XdsLbPolicies.CLUSTER_IMPL_POLICY_NAME);
      Object priorityChildPolicy = GracefulSwitchLoadBalancer.createLoadBalancingPolicyConfig(
          clusterImplLbProvider, clusterImplConfig);

      // If outlier detection has been configured we wrap the child policy in the outlier detection
      // load balancer.
      if (discovery.outlierDetection() != null) {
        LoadBalancerProvider outlierDetectionProvider = lbRegistry.getProvider(
            "outlier_detection_experimental");
        priorityChildPolicy = GracefulSwitchLoadBalancer.createLoadBalancingPolicyConfig(
            outlierDetectionProvider,
            buildOutlierDetectionLbConfig(discovery.outlierDetection(), priorityChildPolicy));
      }

      boolean isEds = discovery.clusterType() == ClusterType.EDS;
      PriorityChildConfig priorityChildConfig =
          new PriorityChildConfig(priorityChildPolicy, isEds /* ignoreReresolution */);
      configs.put(priority, priorityChildConfig);
    }
    return configs;
  }

  /**
   * Converts {@link OutlierDetection} that represents the xDS configuration to {@link
   * OutlierDetectionLoadBalancerConfig} that the {@link io.grpc.util.OutlierDetectionLoadBalancer}
   * understands.
   */
  private static OutlierDetectionLoadBalancerConfig buildOutlierDetectionLbConfig(
      OutlierDetection outlierDetection, Object childConfig) {
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

    SuccessRateEjection successRate = outlierDetection.successRateEjection();
    if (successRate != null) {
      OutlierDetectionLoadBalancerConfig.SuccessRateEjection.Builder
          successRateConfigBuilder = new OutlierDetectionLoadBalancerConfig
          .SuccessRateEjection.Builder();

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

    FailurePercentageEjection failurePercentage = outlierDetection.failurePercentageEjection();
    if (failurePercentage != null) {
      OutlierDetectionLoadBalancerConfig.FailurePercentageEjection.Builder
          failurePercentageConfigBuilder = new OutlierDetectionLoadBalancerConfig
          .FailurePercentageEjection.Builder();

      if (failurePercentage.threshold() != null) {
        failurePercentageConfigBuilder.setThreshold(failurePercentage.threshold());
      }
      if (failurePercentage.enforcementPercentage() != null) {
        failurePercentageConfigBuilder.setEnforcementPercentage(
            failurePercentage.enforcementPercentage());
      }
      if (failurePercentage.minimumHosts() != null) {
        failurePercentageConfigBuilder.setMinimumHosts(failurePercentage.minimumHosts());
      }
      if (failurePercentage.requestVolume() != null) {
        failurePercentageConfigBuilder.setRequestVolume(failurePercentage.requestVolume());
      }

      configBuilder.setFailurePercentageEjection(failurePercentageConfigBuilder.build());
    }

    return configBuilder.build();
  }
}
