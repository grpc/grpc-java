/*
 * Copyright 2018 The gRPC Authors
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

package io.grpc.grpclb;

import com.google.common.base.Stopwatch;
import io.grpc.Internal;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancerProvider;
import io.grpc.NameResolver.ConfigOrError;
import io.grpc.Status;
import io.grpc.grpclb.GrpclbState.Mode;
import io.grpc.internal.ExponentialBackoffPolicy;
import io.grpc.internal.JsonUtil;
import io.grpc.internal.ServiceConfigUtil;
import io.grpc.internal.ServiceConfigUtil.LbConfig;
import io.grpc.internal.TimeProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The provider for the "grpclb" balancing policy.  This class should not be directly referenced in
 * code.  The policy should be accessed through {@link io.grpc.LoadBalancerRegistry#getProvider}
 * with the name "grpclb".
 */
@Internal
public final class GrpclbLoadBalancerProvider extends LoadBalancerProvider {

  private static final Mode DEFAULT_MODE = Mode.ROUND_ROBIN;

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
    return "grpclb";
  }

  @Override
  public LoadBalancer newLoadBalancer(LoadBalancer.Helper helper) {
    return new GrpclbLoadBalancer(
        helper, new CachedSubchannelPool(), TimeProvider.SYSTEM_TIME_PROVIDER,
        Stopwatch.createUnstarted(),
        new ExponentialBackoffPolicy.Provider());
  }

  @Override
  public ConfigOrError parseLoadBalancingPolicyConfig(
      Map<String, ?> rawLoadBalancingConfigPolicy) {
    try {
      return parseLoadBalancingConfigPolicyInternal(rawLoadBalancingConfigPolicy);
    } catch (RuntimeException e) {
      return ConfigOrError.fromError(
          Status.fromThrowable(e).withDescription(
              "Failed to parse GRPCLB config: " + rawLoadBalancingConfigPolicy));
    }
  }

  ConfigOrError parseLoadBalancingConfigPolicyInternal(
      Map<String, ?> rawLoadBalancingPolicyConfig) {
    if (rawLoadBalancingPolicyConfig == null) {
      return ConfigOrError.fromConfig(GrpclbConfig.create(DEFAULT_MODE));
    }
    String serviceName = JsonUtil.getString(rawLoadBalancingPolicyConfig, "serviceName");
    List<?> rawChildPolicies = JsonUtil.getList(rawLoadBalancingPolicyConfig, "childPolicy");
    List<LbConfig> childPolicies = null;
    if (rawChildPolicies != null) {
      childPolicies =
          ServiceConfigUtil
              .unwrapLoadBalancingConfigList(JsonUtil.checkObjectList(rawChildPolicies));
    }

    if (childPolicies == null || childPolicies.isEmpty()) {
      return ConfigOrError.fromConfig(GrpclbConfig.create(DEFAULT_MODE, serviceName));
    }

    List<String> policiesTried = new ArrayList<>();
    for (LbConfig childPolicy : childPolicies) {
      String childPolicyName = childPolicy.getPolicyName();
      switch (childPolicyName) {
        case "round_robin":
          return ConfigOrError.fromConfig(GrpclbConfig.create(Mode.ROUND_ROBIN, serviceName));
        case "pick_first":
          return ConfigOrError.fromConfig(GrpclbConfig.create(Mode.PICK_FIRST, serviceName));
        default:
          policiesTried.add(childPolicyName);
      }
    }
    return ConfigOrError.fromError(
        Status
            .INVALID_ARGUMENT
            .withDescription(
                "None of " + policiesTried + " specified child policies are available."));
  }
}
