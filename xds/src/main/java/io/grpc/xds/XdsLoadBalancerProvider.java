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
import java.util.Collections;
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
      LbConfig roundRobinConfig = new LbConfig("round_robin", ImmutableMap.<String, Object>of());
      List<LbConfig> childPolicyConfigs = ServiceConfigUtil.unwrapLoadBalancingConfigList(
          JsonUtil.getListOfObjects(rawLoadBalancingPolicyConfig, "childPolicy"));
      if (childPolicyConfigs == null || childPolicyConfigs.isEmpty()) {
        childPolicyConfigs = Collections.singletonList(roundRobinConfig);
      }
      ConfigOrError childConfigOrError =
          ServiceConfigUtil.selectLbPolicyFromList(childPolicyConfigs, registry);
      if (childConfigOrError.getError() != null) {
        return childConfigOrError;
      }
      PolicySelection childPolicy = (PolicySelection) childConfigOrError.getConfig();

      List<LbConfig> fallbackConfigs = ServiceConfigUtil.unwrapLoadBalancingConfigList(
          JsonUtil.getListOfObjects(rawLoadBalancingPolicyConfig, "fallbackPolicy"));
      if (fallbackConfigs == null || fallbackConfigs.isEmpty()) {
        fallbackConfigs = Collections.singletonList(roundRobinConfig);
      }
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
          new XdsConfig(edsServiceName, lrsServerName, childPolicy, fallbackPolicy));
    } catch (RuntimeException e) {
      return ConfigOrError.fromError(
          Status.fromThrowable(e).withDescription(
              "Failed to parse XDS LB config: " + rawLoadBalancingPolicyConfig));
    }
  }

  /**
   * Represents a successfully parsed and validated LoadBalancingConfig for XDS LB policy.
   */
  static final class XdsConfig {
    @Nullable
    final String edsServiceName;
    @Nullable
    final String lrsServerName;
    final PolicySelection childPolicy;  // default to round_robin if not specified in proto
    final PolicySelection fallbackPolicy;  // default to round_robin if not specified in proto

    XdsConfig(
        @Nullable String edsServiceName,
        @Nullable String lrsServerName,
        PolicySelection childPolicy,
        PolicySelection fallbackPolicy) {
      this.edsServiceName = edsServiceName;
      this.lrsServerName = lrsServerName;
      this.childPolicy = checkNotNull(childPolicy, "childPolicy");
      this.fallbackPolicy = fallbackPolicy;
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("edsServiceName", edsServiceName)
          .add("lrsServerName", lrsServerName)
          .add("childPolicy", childPolicy)
          .add("fallbackPolicy", fallbackPolicy)
          .toString();
    }

    @Override
    public boolean equals(Object obj) {
      if (!(obj instanceof XdsConfig)) {
        return false;
      }
      XdsConfig that = (XdsConfig) obj;
      return Objects.equal(this.edsServiceName, that.edsServiceName)
          && Objects.equal(this.lrsServerName, that.lrsServerName)
          && Objects.equal(this.childPolicy, that.childPolicy)
          && Objects.equal(this.fallbackPolicy, that.fallbackPolicy);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(
          edsServiceName, lrsServerName, childPolicy, fallbackPolicy);
    }
  }
}
