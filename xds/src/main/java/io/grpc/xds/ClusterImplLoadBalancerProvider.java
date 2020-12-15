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

import com.google.common.base.MoreObjects;
import io.grpc.Internal;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancerProvider;
import io.grpc.LoadBalancerRegistry;
import io.grpc.NameResolver.ConfigOrError;
import io.grpc.internal.ServiceConfigUtil.PolicySelection;
import io.grpc.xds.EnvoyProtoData.DropOverload;
import io.grpc.xds.EnvoyServerProtoData.UpstreamTlsContext;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * The provider for the cluster_impl load balancing policy. This class should not be directly
 * referenced in code.  The policy should be accessed through
 * {@link LoadBalancerRegistry#getProvider} with the name "cluster_impl_experimental".
 */
@Internal
public final class ClusterImplLoadBalancerProvider extends LoadBalancerProvider {

  @Override
  public boolean isAvailable() {
    return true;
  }

  @Override
  public int getPriority() {
    return 5;
  }

  @Override
  public String getPolicyName() {
    return XdsLbPolicies.CLUSTER_IMPL_POLICY_NAME;
  }

  @Override
  public ConfigOrError parseLoadBalancingPolicyConfig(Map<String, ?> rawLoadBalancingPolicyConfig) {
    throw new UnsupportedOperationException("not supported as top-level LB policy");
  }

  @Override
  public LoadBalancer newLoadBalancer(Helper helper) {
    return new ClusterImplLoadBalancer(helper);
  }

  static final class ClusterImplConfig {
    // Name of the cluster.
    final String cluster;
    // Resource name used in discovering endpoints via EDS. Only valid for EDS clusters.
    @Nullable
    final String edsServiceName;
    // Load report server name. Null if load reporting is disabled.
    @Nullable
    final String lrsServerName;
    // Cluster-level max concurrent request threshold. Null if not specified.
    @Nullable
    final Long maxConcurrentRequests;
    // TLS context for connections to endpoints.
    @Nullable
    final UpstreamTlsContext tlsContext;
    // Drop configurations.
    final List<DropOverload> dropCategories;
    // Provides the direct child policy and its config.
    final PolicySelection childPolicy;

    ClusterImplConfig(String cluster, @Nullable String edsServiceName,
        @Nullable String lrsServerName, @Nullable Long maxConcurrentRequests,
        List<DropOverload> dropCategories, PolicySelection childPolicy,
        @Nullable UpstreamTlsContext tlsContext) {
      this.cluster = checkNotNull(cluster, "cluster");
      this.edsServiceName = edsServiceName;
      this.lrsServerName = lrsServerName;
      this.maxConcurrentRequests = maxConcurrentRequests;
      this.tlsContext = tlsContext;
      this.dropCategories = Collections.unmodifiableList(
          new ArrayList<>(checkNotNull(dropCategories, "dropCategories")));
      this.childPolicy = checkNotNull(childPolicy, "childPolicy");
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("cluster", cluster)
          .add("edsServiceName", edsServiceName)
          .add("lrsServerName", lrsServerName)
          .add("maxConcurrentRequests", maxConcurrentRequests)
          // Exclude tlsContext as its string representation is cumbersome.
          .add("dropCategories", dropCategories)
          .add("childPolicy", childPolicy)
          .toString();
    }
  }
}
