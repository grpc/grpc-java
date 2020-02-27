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

import com.google.common.annotations.VisibleForTesting;
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

  static final String XDS_POLICY_NAME = "xds_experimental";

  private static final LbConfig DEFAULT_FALLBACK_POLICY =
      new LbConfig("round_robin", ImmutableMap.<String, Void>of());

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
      LbConfig childPolicy = selectChildPolicy(rawLoadBalancingPolicyConfig, registry);
      LbConfig fallbackPolicy = selectFallbackPolicy(rawLoadBalancingPolicyConfig, registry);
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

  @VisibleForTesting
  static LbConfig selectFallbackPolicy(
      Map<String, ?> rawLoadBalancingPolicyConfig, LoadBalancerRegistry lbRegistry) {
    List<LbConfig> fallbackConfigs = ServiceConfigUtil.unwrapLoadBalancingConfigList(
        JsonUtil.getListOfObjects(rawLoadBalancingPolicyConfig, "fallbackPolicy"));
    LbConfig fallbackPolicy = selectSupportedLbPolicy(fallbackConfigs, lbRegistry);
    return fallbackPolicy == null ? DEFAULT_FALLBACK_POLICY : fallbackPolicy;
  }

  @Nullable
  @VisibleForTesting
  static LbConfig selectChildPolicy(
      Map<String, ?> rawLoadBalancingPolicyConfig, LoadBalancerRegistry lbRegistry) {
    List<LbConfig> childConfigs = ServiceConfigUtil.unwrapLoadBalancingConfigList(
        JsonUtil.getListOfObjects(rawLoadBalancingPolicyConfig, "childPolicy"));
    return selectSupportedLbPolicy(childConfigs, lbRegistry);
  }

  @Nullable
  private static LbConfig selectSupportedLbPolicy(
      @Nullable List<LbConfig> lbConfigs, LoadBalancerRegistry lbRegistry) {
    if (lbConfigs == null) {
      return null;
    }
    for (LbConfig lbConfig : lbConfigs) {
      String lbPolicy = lbConfig.getPolicyName();
      if (lbRegistry.getProvider(lbPolicy) != null) {
        return lbConfig;
      }
    }
    return null;
  }

  /**
   * Represents a successfully parsed and validated LoadBalancingConfig for XDS.
   */
  static final class XdsConfig {
    // FIXME(chengyuanzhang): make cluster name required.
    @Nullable
    final String cluster;
    // TODO(carl-mastrangelo): make these Object's containing the fully parsed child configs.
    @Nullable
    final LbConfig childPolicy;
    @Nullable
    final LbConfig fallbackPolicy;
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
        @Nullable LbConfig childPolicy,
        @Nullable LbConfig fallbackPolicy,
        @Nullable String edsServiceName,
        @Nullable String lrsServerName) {
      this.cluster = cluster;
      this.childPolicy = childPolicy;
      this.fallbackPolicy = fallbackPolicy;
      this.edsServiceName = edsServiceName;
      this.lrsServerName = lrsServerName;
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("cluster", cluster)
          .add("childPolicy", childPolicy)
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
          && Objects.equal(this.childPolicy, that.childPolicy)
          && Objects.equal(this.fallbackPolicy, that.fallbackPolicy)
          && Objects.equal(this.edsServiceName, that.edsServiceName)
          && Objects.equal(this.lrsServerName, that.lrsServerName);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(cluster, childPolicy, fallbackPolicy, edsServiceName, lrsServerName);
    }
  }
}
