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
import static io.grpc.xds.XdsLbPolicies.CLUSTER_RESOLVER_POLICY_NAME;
import static io.grpc.xds.XdsLbPolicies.PRIORITY_POLICY_NAME;

import com.google.errorprone.annotations.CheckReturnValue;
import io.grpc.InternalLogId;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancerRegistry;
import io.grpc.NameResolver;
import io.grpc.Status;
import io.grpc.StatusOr;
import io.grpc.util.GracefulSwitchLoadBalancer;
import io.grpc.xds.CdsLoadBalancerProvider.CdsConfig;
import io.grpc.xds.ClusterResolverLoadBalancerProvider.ClusterResolverConfig;
import io.grpc.xds.ClusterResolverLoadBalancerProvider.ClusterResolverConfig.DiscoveryMechanism;
import io.grpc.xds.PriorityLoadBalancerProvider.PriorityLbConfig.PriorityChildConfig;
import io.grpc.xds.XdsClusterResource.CdsUpdate;
import io.grpc.xds.XdsClusterResource.CdsUpdate.ClusterType;
import io.grpc.xds.XdsConfig.Subscription;
import io.grpc.xds.XdsConfig.XdsClusterConfig;
import io.grpc.xds.XdsConfig.XdsClusterConfig.AggregateConfig;
import io.grpc.xds.XdsConfig.XdsClusterConfig.EndpointConfig;
import io.grpc.xds.client.XdsLogger;
import io.grpc.xds.client.XdsLogger.XdsLogLevel;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Load balancer for cds_experimental LB policy. One instance per top-level cluster.
 * The top-level cluster may be a plain EDS/logical-DNS cluster or an aggregate cluster formed
 * by a group of sub-clusters in a tree hierarchy.
 */
final class CdsLoadBalancer2 extends LoadBalancer {
  private final XdsLogger logger;
  private final Helper helper;
  private final LoadBalancerRegistry lbRegistry;
  // Following fields are effectively final.
  private String clusterName;
  private Subscription clusterSubscription;
  private LoadBalancer childLb;

  CdsLoadBalancer2(Helper helper, LoadBalancerRegistry lbRegistry) {
    this.helper = checkNotNull(helper, "helper");
    this.lbRegistry = checkNotNull(lbRegistry, "lbRegistry");
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
    Object childConfig;
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
      CdsUpdate result = clusterConfig.getClusterResource();
      DiscoveryMechanism instance;
      if (result.clusterType() == ClusterType.EDS) {
        instance = DiscoveryMechanism.forEds(
            clusterName,
            result.edsServiceName(),
            result.lrsServerInfo(),
            result.maxConcurrentRequests(),
            result.upstreamTlsContext(),
            result.filterMetadata(),
            result.outlierDetection());
      } else {
        instance = DiscoveryMechanism.forLogicalDns(
            clusterName,
            result.dnsHostName(),
            result.lrsServerInfo(),
            result.maxConcurrentRequests(),
            result.upstreamTlsContext(),
            result.filterMetadata());
      }
      childConfig = new ClusterResolverConfig(
              instance,
              configOrError.getConfig(),
              clusterConfig.getClusterResource().isHttp11ProxyAvailable());
      if (childLb == null) {
        childLb = lbRegistry.getProvider(CLUSTER_RESOLVER_POLICY_NAME).newLoadBalancer(helper);
      }
    } else if (clusterConfig.getChildren() instanceof AggregateConfig) {
      if (childLb == null) {
        childLb = lbRegistry.getProvider(PRIORITY_POLICY_NAME).newLoadBalancer(helper);
      }
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
      childConfig = new PriorityLoadBalancerProvider.PriorityLbConfig(
              Collections.unmodifiableMap(priorityChildConfigs), leafClusters);
    } else {
      return fail(Status.INTERNAL.withDescription(
              errorPrefix() + "Unexpected cluster children type: "
                      + clusterConfig.getChildren().getClass()));
    }

    return childLb.acceptResolvedAddresses(
        resolvedAddresses.toBuilder().setLoadBalancingPolicyConfig(childConfig).build());
  }

  @Override
  public void handleNameResolutionError(Status error) {
    logger.log(XdsLogLevel.WARNING, "Received name resolution error: {0}", error);
    if (childLb != null) {
      childLb.handleNameResolutionError(error);
    } else {
      helper.updateBalancingState(
          TRANSIENT_FAILURE, new FixedResultPicker(PickResult.withError(error)));
    }
  }

  @Override
  public void shutdown() {
    logger.log(XdsLogLevel.INFO, "Shutdown");
    if (childLb != null) {
      childLb.shutdown();
      childLb = null;
    }
    if (clusterSubscription != null) {
      clusterSubscription.close();
      clusterSubscription = null;
    }
  }

  @CheckReturnValue // don't forget to return up the stack after the fail call
  private Status fail(Status error) {
    if (childLb != null) {
      childLb.shutdown();
      childLb = null;
    }
    helper.updateBalancingState(
        TRANSIENT_FAILURE, new FixedResultPicker(PickResult.withError(error)));
    return Status.OK; // XdsNameResolver isn't a polling NR, so this value doesn't matter
  }

  private String errorPrefix() {
    return "CdsLb for " + clusterName + ": ";
  }
}
