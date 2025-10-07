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
import com.google.common.base.MoreObjects;
import io.grpc.Internal;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancerProvider;
import io.grpc.LoadBalancerRegistry;
import io.grpc.NameResolver.ConfigOrError;
import io.grpc.Status;
import io.grpc.internal.GrpcUtil;
import io.grpc.internal.JsonUtil;
import io.grpc.util.GracefulSwitchLoadBalancer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nullable;

/**
 * The provider for the weighted_target balancing policy.  This class should not be
 * directly referenced in code.  The policy should be accessed through {@link
 * LoadBalancerRegistry#getProvider} with the name "weighted_target_experimental".
 */
@Internal
public final class WeightedTargetLoadBalancerProvider extends LoadBalancerProvider {

  @Nullable
  private final LoadBalancerRegistry lbRegistry;

  // We can not call this(LoadBalancerRegistry.getDefaultRegistry()), because it will get stuck
  // recursively loading LoadBalancerRegistry and WeightedTargetLoadBalancerProvider.
  public WeightedTargetLoadBalancerProvider() {
    this(null);
  }

  @VisibleForTesting
  WeightedTargetLoadBalancerProvider(@Nullable LoadBalancerRegistry lbRegistry) {
    this.lbRegistry = lbRegistry;
  }

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
    return XdsLbPolicies.WEIGHTED_TARGET_POLICY_NAME;
  }

  @Override
  public LoadBalancer newLoadBalancer(Helper helper) {
    return new WeightedTargetLoadBalancer(helper);
  }

  @Override
  public ConfigOrError parseLoadBalancingPolicyConfig(Map<String, ?> rawConfig) {
    try {
      Map<String, ?> targets = JsonUtil.getObject(rawConfig, "targets");
      if (targets == null || targets.isEmpty()) {
        return ConfigOrError.fromError(Status.INTERNAL.withDescription(
            "No targets provided for weighted_target LB policy:\n " + rawConfig));
      }
      Map<String, WeightedPolicySelection> parsedChildConfigs = new LinkedHashMap<>();
      for (String name : targets.keySet()) {
        Map<String, ?> rawWeightedTarget = JsonUtil.getObject(targets, name);
        if (rawWeightedTarget == null || rawWeightedTarget.isEmpty()) {
          return ConfigOrError.fromError(Status.INTERNAL.withDescription(
              "No config for target " + name + " in weighted_target LB policy:\n " + rawConfig));
        }
        Integer weight = JsonUtil.getNumberAsInteger(rawWeightedTarget, "weight");
        if (weight == null || weight < 1) {
          return ConfigOrError.fromError(Status.INTERNAL.withDescription(
              "Wrong weight for target " + name + " in weighted_target LB policy:\n " + rawConfig));
        }
        LoadBalancerRegistry lbRegistry =
            this.lbRegistry == null ? LoadBalancerRegistry.getDefaultRegistry() : this.lbRegistry;
        ConfigOrError childConfig = GracefulSwitchLoadBalancer.parseLoadBalancingPolicyConfig(
            JsonUtil.getListOfObjects(rawWeightedTarget, "childPolicy"), lbRegistry);
        if (childConfig.getError() != null) {
          return ConfigOrError.fromError(GrpcUtil.statusWithDetails(
              Status.Code.INTERNAL,
              "Could not parse weighted_target's child policy: " + name,
              childConfig.getError()));
        }
        parsedChildConfigs.put(name, new WeightedPolicySelection(weight, childConfig.getConfig()));
      }
      return ConfigOrError.fromConfig(new WeightedTargetConfig(parsedChildConfigs));
    } catch (RuntimeException e) {
      return ConfigOrError.fromError(
          Status.INTERNAL.withCause(e).withDescription(
              "Failed to parse weighted_target LB config: " + rawConfig));
    }
  }

  static final class WeightedPolicySelection {

    final int weight;
    final Object childConfig;

    WeightedPolicySelection(int weight, Object childConfig) {
      this.weight = weight;
      this.childConfig = childConfig;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      WeightedPolicySelection that = (WeightedPolicySelection) o;
      return weight == that.weight && Objects.equals(childConfig, that.childConfig);
    }

    @Override
    public int hashCode() {
      return Objects.hash(weight, childConfig);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("weight", weight)
          .add("childConfig", childConfig)
          .toString();
    }
  }

  /** The lb config for WeightedTargetLoadBalancer. */
  static final class WeightedTargetConfig {

    final Map<String, WeightedPolicySelection> targets;

    WeightedTargetConfig(Map<String, WeightedPolicySelection> targets) {
      this.targets = targets;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      WeightedTargetConfig that = (WeightedTargetConfig) o;
      return Objects.equals(targets, that.targets);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(targets);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("targets", targets)
          .toString();
    }
  }
}
