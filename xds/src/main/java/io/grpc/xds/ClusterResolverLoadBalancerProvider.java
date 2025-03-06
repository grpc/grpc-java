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
import com.google.common.collect.ImmutableMap;
import com.google.protobuf.Struct;
import io.grpc.Internal;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancerProvider;
import io.grpc.NameResolver.ConfigOrError;
import io.grpc.Status;
import io.grpc.xds.EnvoyServerProtoData.OutlierDetection;
import io.grpc.xds.EnvoyServerProtoData.UpstreamTlsContext;
import io.grpc.xds.client.Bootstrapper.ServerInfo;
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
    return ConfigOrError.fromError(
        Status.INTERNAL.withDescription(getPolicyName() + " cannot be used from service config"));
  }

  @Override
  public LoadBalancer newLoadBalancer(Helper helper) {
    return new ClusterResolverLoadBalancer(helper);
  }

  static final class ClusterResolverConfig {
    // Ordered list of clusters to be resolved.
    final List<DiscoveryMechanism> discoveryMechanisms;
    // GracefulSwitch configuration
    final Object lbConfig;
    private final boolean isHttp11ProxyAvailable;

    ClusterResolverConfig(List<DiscoveryMechanism> discoveryMechanisms, Object lbConfig,
        boolean isHttp11ProxyAvailable) {
      this.discoveryMechanisms = checkNotNull(discoveryMechanisms, "discoveryMechanisms");
      this.lbConfig = checkNotNull(lbConfig, "lbConfig");
      this.isHttp11ProxyAvailable = isHttp11ProxyAvailable;
    }

    boolean isHttp11ProxyAvailable() {
      return isHttp11ProxyAvailable;
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
      final ServerInfo lrsServerInfo;
      // Cluster-level max concurrent request threshold. Null if not specified.
      @Nullable
      final Long maxConcurrentRequests;
      // TLS context for connections to endpoints in the cluster.
      @Nullable
      final UpstreamTlsContext tlsContext;
      // Resource name for resolving endpoints via EDS. Only valid for EDS clusters.
      @Nullable
      final String edsServiceName;
      // Hostname for resolving endpoints via DNS. Only valid for LOGICAL_DNS clusters.
      @Nullable
      final String dnsHostName;
      @Nullable
      final OutlierDetection outlierDetection;
      final Map<String, Struct> filterMetadata;

      enum Type {
        EDS,
        LOGICAL_DNS,
      }

      private DiscoveryMechanism(String cluster, Type type, @Nullable String edsServiceName,
          @Nullable String dnsHostName, @Nullable ServerInfo lrsServerInfo,
          @Nullable Long maxConcurrentRequests, @Nullable UpstreamTlsContext tlsContext,
          Map<String, Struct> filterMetadata, @Nullable OutlierDetection outlierDetection) {
        this.cluster = checkNotNull(cluster, "cluster");
        this.type = checkNotNull(type, "type");
        this.edsServiceName = edsServiceName;
        this.dnsHostName = dnsHostName;
        this.lrsServerInfo = lrsServerInfo;
        this.maxConcurrentRequests = maxConcurrentRequests;
        this.tlsContext = tlsContext;
        this.filterMetadata = ImmutableMap.copyOf(checkNotNull(filterMetadata, "filterMetadata"));
        this.outlierDetection = outlierDetection;
      }

      static DiscoveryMechanism forEds(String cluster, @Nullable String edsServiceName,
          @Nullable ServerInfo lrsServerInfo, @Nullable Long maxConcurrentRequests,
          @Nullable UpstreamTlsContext tlsContext, Map<String, Struct> filterMetadata,
          OutlierDetection outlierDetection) {
        return new DiscoveryMechanism(cluster, Type.EDS, edsServiceName, null, lrsServerInfo,
            maxConcurrentRequests, tlsContext, filterMetadata, outlierDetection);
      }

      static DiscoveryMechanism forLogicalDns(String cluster, String dnsHostName,
          @Nullable ServerInfo lrsServerInfo, @Nullable Long maxConcurrentRequests,
          @Nullable UpstreamTlsContext tlsContext, Map<String, Struct> filterMetadata) {
        return new DiscoveryMechanism(cluster, Type.LOGICAL_DNS, null, dnsHostName,
            lrsServerInfo, maxConcurrentRequests, tlsContext, filterMetadata, null);
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
}
