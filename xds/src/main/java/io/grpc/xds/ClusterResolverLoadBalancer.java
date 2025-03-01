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

import com.google.common.annotations.VisibleForTesting;
import io.grpc.InternalLogId;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancerRegistry;
import io.grpc.Status;
import io.grpc.internal.BackoffPolicy;
import io.grpc.internal.ExponentialBackoffPolicy;
import io.grpc.internal.ObjectPool;
import io.grpc.util.GracefulSwitchLoadBalancer;
import io.grpc.xds.CdsLoadBalancer2.ClusterResolverConfig;
import io.grpc.xds.client.XdsClient;
import io.grpc.xds.client.XdsLogger;
import io.grpc.xds.client.XdsLogger.XdsLogLevel;
import java.util.Objects;

/**
 * Load balancer for cluster_resolver_experimental LB policy. This LB policy is the child LB policy
 * of the cds_experimental LB policy and the parent LB policy of the priority_experimental LB
 * policy in the xDS load balancing hierarchy. This policy resolves endpoints of non-aggregate
 * clusters (e.g., EDS or Logical DNS) and groups endpoints in priorities and localities to be
 * used in the downstream LB policies for fine-grained load balancing purposes.
 */
final class ClusterResolverLoadBalancer extends LoadBalancer {
  private final XdsLogger logger;
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
    delegate = new GracefulSwitchLoadBalancer(helper);
    logger = XdsLogger.withLogId(
        InternalLogId.allocate("cluster-resolver-lb", helper.getAuthority()));
    logger.log(XdsLogLevel.INFO, "Created");
  }

  @Override
  public Status acceptResolvedAddresses(ResolvedAddresses resolvedAddresses) {
    logger.log(XdsLogLevel.DEBUG, "Received resolution result: {0}", resolvedAddresses);
    if (xdsClientPool == null) {
      xdsClientPool = resolvedAddresses.getAttributes().get(XdsAttributes.XDS_CLIENT_POOL);
      xdsClient = xdsClientPool.getObject();
    }
    ClusterResolverConfig config =
        (ClusterResolverConfig) resolvedAddresses.getLoadBalancingPolicyConfig();
    if (!Objects.equals(this.config, config)) {
      logger.log(XdsLogLevel.DEBUG, "Config: {0}", config);
      this.config = config;
      Object gracefulConfig = GracefulSwitchLoadBalancer.createLoadBalancingPolicyConfig(
          null, config); // TODO intentionally broken as class should go away
      delegate.handleResolvedAddresses(
          resolvedAddresses.toBuilder().setLoadBalancingPolicyConfig(gracefulConfig).build());
    }
    return Status.OK;
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

}
