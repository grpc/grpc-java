/*
 * Copyright 2019 The gRPC Authors
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
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableMap;
import io.grpc.Internal;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancerProvider;
import io.grpc.LoadBalancerRegistry;
import io.grpc.NameResolver.ConfigOrError;
import io.grpc.Status;
import io.grpc.internal.JsonUtil;
import io.grpc.internal.ServiceConfigUtil;
import io.grpc.internal.ServiceConfigUtil.LbConfig;
import io.grpc.internal.ServiceConfigUtil.PolicySelection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * The provider for the "xds" balancing policy.  This class should not be directly referenced in
 * code.  The policy should be accessed through {@link io.grpc.LoadBalancerRegistry#getProvider}
 * with the name "xds" (currently "xds_experimental").
 */
@Internal
public final class XdsLoadBalancerProvider extends LoadBalancerProvider {

  private static final String XDS_POLICY_NAME = "xds_experimental";

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
    return XDS_POLICY_NAME;
  }

  @Override
  public LoadBalancer newLoadBalancer(Helper helper) {
    return new XdsLoadBalancer(helper);
  }

  @Override
  public ConfigOrError parseLoadBalancingPolicyConfig(
      Map<String, ?> rawLoadBalancingPolicyConfig) {
    return parseLoadBalancingConfigPolicy(
        rawLoadBalancingPolicyConfig, LoadBalancerRegistry.getDefaultRegistry());
  }

  static ConfigOrError parseLoadBalancingConfigPolicy(
      Map<String, ?> rawLoadBalancingPolicyConfig, LoadBalancerRegistry registry) {
    try {
      String cluster = JsonUtil.getString(rawLoadBalancingPolicyConfig, "cluster");

      LbConfig roundRobinConfig = new LbConfig("round_robin", ImmutableMap.<String, Object>of());
      List<LbConfig> endpointPickingConfigs = ServiceConfigUtil.unwrapLoadBalancingConfigList(
          JsonUtil.getListOfObjects(rawLoadBalancingPolicyConfig, "endpointPickingPolicy"));
      if (endpointPickingConfigs == null) {
        endpointPickingConfigs = new ArrayList<>(1);
      } else {
        endpointPickingConfigs = new ArrayList<>(endpointPickingConfigs);
      }
      endpointPickingConfigs.add(roundRobinConfig);
      ConfigOrError childConfigOrError =
          ServiceConfigUtil.selectLbPolicyFromList(endpointPickingConfigs, registry);
      if (childConfigOrError.getError() != null) {
        return childConfigOrError;
      }
      PolicySelection childPolicy = (PolicySelection) childConfigOrError.getConfig();

      List<LbConfig> fallbackConfigs = ServiceConfigUtil.unwrapLoadBalancingConfigList(
          JsonUtil.getListOfObjects(rawLoadBalancingPolicyConfig, "fallbackPolicy"));
      if (fallbackConfigs == null) {
        fallbackConfigs = new ArrayList<>(1);
      } else {
        fallbackConfigs = new ArrayList<>(fallbackConfigs);
      }
      fallbackConfigs.add(roundRobinConfig);
      ConfigOrError fallbackConfigOrError =
          ServiceConfigUtil.selectLbPolicyFromList(fallbackConfigs, registry);
      if (fallbackConfigOrError.getError() != null) {
        return fallbackConfigOrError;
      }
      PolicySelection fallbackPolicy = (PolicySelection) fallbackConfigOrError.getConfig();

      String edsServiceName = JsonUtil.getString(rawLoadBalancingPolicyConfig, "edsServiceName");
      String lrsServerName =
          JsonUtil.getString(rawLoadBalancingPolicyConfig, "lrsLoadReportingServerName");
      return ConfigOrError.fromConfig(
          new XdsConfig(cluster, childPolicy, fallbackPolicy, edsServiceName, lrsServerName));
    } catch (RuntimeException e) {
      return ConfigOrError.fromError(
          Status.fromThrowable(e).withDescription(
              "Failed to parse XDS LB config: " + rawLoadBalancingPolicyConfig));
    }
  }

  /**
   * Represents a successfully parsed and validated LoadBalancingConfig for XDS.
   */
  static final class XdsConfig {
    // FIXME(chengyuanzhang): make cluster name required.
    @Nullable
    final String cluster;
    final PolicySelection endpointPickingPolicy;
    @Nullable
    final PolicySelection fallbackPolicy;
    // Optional. Name to use in EDS query. If not present, defaults to the server name from the
    // target URI.
    @Nullable
    final String edsServiceName;
    // Optional. LRS server to send load reports to. If not present, load reporting will be
    // disabled. If set to the empty string, load reporting will be sent to the same server that
    // we obtained CDS data from.
    @Nullable
    final String lrsServerName;

    XdsConfig(
        @Nullable String cluster,
        PolicySelection endpointPickingPolicy,
        @Nullable PolicySelection fallbackPolicy,
        @Nullable String edsServiceName,
        @Nullable String lrsServerName) {
      this.cluster = cluster;
      this.endpointPickingPolicy = checkNotNull(endpointPickingPolicy, "endpointPickingPolicy");
      this.fallbackPolicy = fallbackPolicy;
      this.edsServiceName = edsServiceName;
      this.lrsServerName = lrsServerName;
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("cluster", cluster)
          .add("endpointPickingPolicy", endpointPickingPolicy)
          .add("fallbackPolicy", fallbackPolicy)
          .add("edsServiceName", edsServiceName)
          .add("lrsServerName", lrsServerName)
          .toString();
    }

    @Override
    public boolean equals(Object obj) {
      if (!(obj instanceof XdsConfig)) {
        return false;
      }
      XdsConfig that = (XdsConfig) obj;
      return Objects.equal(this.cluster, that.cluster)
          && Objects.equal(this.endpointPickingPolicy, that.endpointPickingPolicy)
          && Objects.equal(this.fallbackPolicy, that.fallbackPolicy)
          && Objects.equal(this.edsServiceName, that.edsServiceName)
          && Objects.equal(this.lrsServerName, that.lrsServerName);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(
          cluster, endpointPickingPolicy, fallbackPolicy, edsServiceName, lrsServerName);
    }
  }
}
