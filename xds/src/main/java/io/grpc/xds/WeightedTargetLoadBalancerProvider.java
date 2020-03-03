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
import io.grpc.internal.JsonUtil;
import io.grpc.internal.ServiceConfigUtil;
import io.grpc.internal.ServiceConfigUtil.LbConfig;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;

/**
 * The provider for the weighted_target balancing policy.  This class should not be
 * directly referenced in code.  The policy should be accessed through {@link
 * LoadBalancerRegistry#getProvider} with the name "weighted_target_experimental".
 */
@Internal
public final class WeightedTargetLoadBalancerProvider extends LoadBalancerProvider {

  static final String WEIGHTED_TARGET_POLICY_NAME = "weighted_target_experimental";
  private static final Logger logger =
      Logger.getLogger(WeightedTargetLoadBalancerProvider.class.getName());

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

  private LoadBalancerRegistry loadBalancerRegistry() {
    return lbRegistry == null ? LoadBalancerRegistry.getDefaultRegistry() : lbRegistry;
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
    return WEIGHTED_TARGET_POLICY_NAME;
  }

  @Override
  public LoadBalancer newLoadBalancer(Helper helper) {
    return new WeightedTargetLoadBalancer(helper, loadBalancerRegistry());
  }

  @Override
  public ConfigOrError parseLoadBalancingPolicyConfig(Map<String, ?> rawConfig) {
    try {
      Map<String, ?> targets = JsonUtil.getObject(rawConfig, "targets");
      if (targets == null || targets.isEmpty()) {
        return ConfigOrError.fromError(Status.INTERNAL.withDescription(
            "No targets provided for weighted_target LB policy:\n " + rawConfig));
      }
      Map<String, WeightedPolicySeclection> parsedChildConfigs = new LinkedHashMap<>();
      for (String name : targets.keySet()) {
        Map<String, ?> rawWeightedTarget = JsonUtil.getObject(targets, name);
        if (rawWeightedTarget == null || rawWeightedTarget.isEmpty()) {
          return ConfigOrError.fromError(Status.INTERNAL.withDescription(
              "No config for target " + name + " in weighted_target LB policy:\n " + rawConfig));
        }
        Double weightD = JsonUtil.getNumber(rawWeightedTarget, "weight");
        if (weightD == null || weightD < 1) {
          return ConfigOrError.fromError(Status.INTERNAL.withDescription(
              "Wrong weight for target " + name + " in weighted_target LB policy:\n " + rawConfig));
        }
        List<LbConfig> childConfigCandidates = ServiceConfigUtil.unwrapLoadBalancingConfigList(
            JsonUtil.getListOfObjects(rawWeightedTarget, "childPolicy"));
        if (childConfigCandidates == null || childConfigCandidates.isEmpty()) {
          return ConfigOrError.fromError(Status.INTERNAL.withDescription(
              "No child policy for target " + name + " in weighted_target LB policy:\n "
                  + rawConfig));
        }
        int weight = weightD.intValue();
        boolean targetParsingSucceeded = false;
        for (LbConfig lbConfig : childConfigCandidates) {
          String policyName = lbConfig.getPolicyName();
          LoadBalancerProvider lbProvider = loadBalancerRegistry().getProvider(policyName);
          if (lbProvider == null) {
            logger.log(
                Level.FINEST,
                "The policy {0} for is not available in weighted_target Lb policy:\n {1}",
                new Object[]{policyName, name});
          } else {
            ConfigOrError parsedLbPolicyConfig = lbProvider
                .parseLoadBalancingPolicyConfig(lbConfig.getRawConfigValue());
            if (parsedLbPolicyConfig.getError() != null) {
              // Based on service config error-handling spec, if the chosen config is found invalid
              // while other configs that come later were valid, the gRPC config would still be
              // considered invalid as a whole.
              return parsedLbPolicyConfig;
            }
            parsedChildConfigs.put(
                name,
                new WeightedPolicySeclection(weight, policyName, parsedLbPolicyConfig.getConfig()));
            targetParsingSucceeded = true;
            break;
          }
        }
        if (!targetParsingSucceeded) {
          return ConfigOrError.fromError(Status.INTERNAL.withDescription(
              "No child policy available for target " + name + " in weighted_target LB policy:\n "
                  + rawConfig));
        }
      }
      return ConfigOrError.fromConfig(new WeightedTargetConfig(parsedChildConfigs));
    } catch (RuntimeException e) {
      return ConfigOrError.fromError(
          Status.fromThrowable(e).withDescription(
              "Failed to parse weighted_target LB config: " + rawConfig));
    }
  }

  static final class WeightedPolicySeclection {

    final int weight;
    final String policyName;
    final Object config; // Parsed config.

    @VisibleForTesting
    WeightedPolicySeclection(int weight, String policyName, Object config) {
      this.weight = weight;
      this.policyName = policyName;
      this.config = config;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      WeightedPolicySeclection that = (WeightedPolicySeclection) o;
      return weight == that.weight
          && Objects.equals(policyName, that.policyName)
          && Objects.equals(config, that.config);
    }

    @Override
    public int hashCode() {
      return Objects.hash(weight, policyName, config);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("weight", weight)
          .add("policyName", policyName)
          .add("config", config)
          .toString();
    }
  }

  /** The lb config for WeightedTargetLoadBalancer. */
  static final class WeightedTargetConfig {

    final Map<String, WeightedPolicySeclection> targets;

    @VisibleForTesting
    WeightedTargetConfig(Map<String, WeightedPolicySeclection> targets) {
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
      return Objects.hash(targets);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("targets", targets)
          .toString();
    }
  }
}
