/*
 * Copyright 2025 The gRPC Authors
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

package io.grpc.util;

import io.grpc.Internal;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancerProvider;
import io.grpc.NameResolver.ConfigOrError;
import io.grpc.Status;
import io.grpc.internal.JsonUtil;
import java.util.Map;

@Internal
public final class RandomSubsettingLoadBalancerProvider extends LoadBalancerProvider {
  private static final String POLICY_NAME = "random_subsetting";

  @Override
  public LoadBalancer newLoadBalancer(LoadBalancer.Helper helper) {
    return new RandomSubsettingLoadBalancer(helper);
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
    return POLICY_NAME;
  }

  @Override
  public ConfigOrError parseLoadBalancingPolicyConfig(Map<String, ?> rawConfig) {
    try {
      return parseLoadBalancingPolicyConfigInternal(rawConfig);
    } catch (RuntimeException e) {
      return ConfigOrError.fromError(
          Status.UNAVAILABLE
              .withCause(e)
              .withDescription("Failed parsing configuration for " + getPolicyName()));
    }
  }

  private ConfigOrError parseLoadBalancingPolicyConfigInternal(Map<String, ?> rawConfig) {
    Integer subsetSize = JsonUtil.getNumberAsInteger(rawConfig, "subsetSize");
    if (subsetSize == null) {
      return ConfigOrError.fromError(
          Status.INTERNAL.withDescription(
              "Subset size missing in " + getPolicyName() + ", LB policy config=" + rawConfig));
    }

    ConfigOrError childConfig = GracefulSwitchLoadBalancer.parseLoadBalancingPolicyConfig(
        JsonUtil.getListOfObjects(rawConfig, "childPolicy"));
    if (childConfig.getError() != null) {
      return ConfigOrError.fromError(Status.INTERNAL
          .withDescription(
              "Failed to parse child in " + getPolicyName() + ", LB policy config=" + rawConfig)
          .withCause(childConfig.getError().asRuntimeException()));
    }

    return ConfigOrError.fromConfig(
        new RandomSubsettingLoadBalancer.RandomSubsettingLoadBalancerConfig.Builder()
            .setSubsetSize(subsetSize)
            .setChildConfig(childConfig.getConfig())
            .build());
  }
}
