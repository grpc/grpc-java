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
import io.grpc.NameResolver.ConfigOrError;
import io.grpc.internal.ServiceConfigUtil.PolicySelection;
import io.grpc.xds.EnvoyServerProtoData.UpstreamTlsContext;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nullable;

/**
 * The provider for the cluster_resolver load balancing policy. This class should not be directly
 * referenced in code.  The policy should be accessed through
 * {@link io.grpc.LoadBalancerRegistry#getProvider} with the name "cluster_resolver_experimental".
 */
@Internal
public final class ClusterResolverLoadBalancerProvider extends LoadBalancerProvider {

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
    return XdsLbPolicies.CLUSTER_RESOLVER_POLICY_NAME;
  }

  @Override
  public ConfigOrError parseLoadBalancingPolicyConfig(Map<String, ?> rawLoadBalancingPolicyConfig) {
    throw new UnsupportedOperationException("not supported as top-level LB policy");
  }

  @Override
  public LoadBalancer newLoadBalancer(Helper helper) {
    return new ClusterResolverLoadBalancer(helper);
  }

  static final class ClusterResolverConfig {
    // Ordered list of clusters to be resolved.
    final List<DiscoveryMechanism> discoveryMechanisms;
    final PolicySelection localityPickingPolicy;
    final PolicySelection endpointPickingPolicy;

    ClusterResolverConfig(List<DiscoveryMechanism> discoveryMechanisms,
        PolicySelection localityPickingPolicy, PolicySelection endpointPickingPolicy) {
      this.discoveryMechanisms = checkNotNull(discoveryMechanisms, "discoveryMechanisms");
      this.localityPickingPolicy = checkNotNull(localityPickingPolicy, "localityPickingPolicy");
      this.endpointPickingPolicy = checkNotNull(endpointPickingPolicy, "endpointPickingPolicy");
    }

    @Override
    public int hashCode() {
      return Objects.hash(discoveryMechanisms, localityPickingPolicy, endpointPickingPolicy);
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
          && localityPickingPolicy.equals(that.localityPickingPolicy)
          && endpointPickingPolicy.equals(that.endpointPickingPolicy);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("discoveryMechanisms", discoveryMechanisms)
          .add("localityPickingPolicy", localityPickingPolicy)
          .add("endpointPickingPolicy", endpointPickingPolicy)
          .toString();
    }

    // Describes the mechanism for a specific cluster.
    static final class DiscoveryMechanism {
      // Name of the cluster to resolve.
      final String cluster;
      // Type of the cluster.
      final Type type;
      // Load reporting server name. Null if not enabled.
      @Nullable
      final String lrsServerName;
      // Cluster-level max concurrent request threshold. Null if not specified.
      @Nullable
      final Long maxConcurrentRequests;
      // TLS context for connections to endpoints in the cluster.
      @Nullable
      final UpstreamTlsContext tlsContext;
      // Resource name for resolving endpoints via EDS. Only valid for EDS clusters.
      @Nullable
      final String edsServiceName;

      enum Type {
        EDS,
        LOGICAL_DNS,
      }

      private DiscoveryMechanism(String cluster, Type type, @Nullable String edsServiceName,
          @Nullable String lrsServerName, @Nullable Long maxConcurrentRequests,
          @Nullable UpstreamTlsContext tlsContext) {
        this.cluster = checkNotNull(cluster, "cluster");
        this.type = checkNotNull(type, "type");
        this.edsServiceName = edsServiceName;
        this.lrsServerName = lrsServerName;
        this.maxConcurrentRequests = maxConcurrentRequests;
        this.tlsContext = tlsContext;
      }

      static DiscoveryMechanism forEds(String cluster, @Nullable String edsServiceName,
          @Nullable String lrsServerName, @Nullable Long maxConcurrentRequests,
          @Nullable UpstreamTlsContext tlsContext) {
        return new DiscoveryMechanism(cluster, Type.EDS, edsServiceName, lrsServerName,
            maxConcurrentRequests, tlsContext);
      }

      static DiscoveryMechanism forLogicalDns(String cluster, @Nullable String lrsServerName,
          @Nullable Long maxConcurrentRequests, @Nullable UpstreamTlsContext tlsContext) {
        return new DiscoveryMechanism(cluster, Type.LOGICAL_DNS, null, lrsServerName,
            maxConcurrentRequests, tlsContext);
      }

      @Override
      public int hashCode() {
        return Objects.hash(cluster, type, lrsServerName, maxConcurrentRequests, tlsContext,
            edsServiceName);
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
            && Objects.equals(lrsServerName, that.lrsServerName)
            && Objects.equals(maxConcurrentRequests, that.maxConcurrentRequests)
            && Objects.equals(tlsContext, that.tlsContext);
      }

      @Override
      public String toString() {
        MoreObjects.ToStringHelper toStringHelper =
            MoreObjects.toStringHelper(this)
                .add("cluster", cluster)
                .add("type", type)
                .add("lrsServerName", lrsServerName)
                // Exclude tlsContext as its string representation is cumbersome.
                .add("maxConcurrentRequests", maxConcurrentRequests);
        if (type == Type.EDS) {
          toStringHelper.add("edsServiceName", edsServiceName);
        }
        return toStringHelper.toString();
      }
    }
  }
}
