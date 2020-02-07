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
import io.grpc.internal.ServiceConfigUtil;
import io.grpc.internal.ServiceConfigUtil.LbConfig;
import io.grpc.internal.TimeProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * The provider for the "grpclb" balancing policy.  This class should not be directly referenced in
 * code.  The policy should be accessed through {@link io.grpc.LoadBalancerRegistry#getProvider}
 * with the name "grpclb".
 */
@Internal
public final class GrpclbLoadBalancerProvider extends LoadBalancerProvider {

  private static final Mode DEFAULT_MODE = Mode.ROUND_ROBIN;
  static final String SERVICE_CONFIG_TARGET_NAME = "targetName";

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
    Object rawTarget =
        rawLoadBalancingPolicyConfig.get(GrpclbLoadBalancerProvider.SERVICE_CONFIG_TARGET_NAME);
    String target = (String) rawTarget;
    List<?> rawChildPolicies = getList(rawLoadBalancingPolicyConfig, "childPolicy");

    List<LbConfig> childPolicies =
        rawChildPolicies == null
            ? null
            : ServiceConfigUtil.unwrapLoadBalancingConfigList(checkObjectList(rawChildPolicies));

    if (childPolicies == null || childPolicies.isEmpty()) {
      return ConfigOrError.fromConfig(GrpclbConfig.create(DEFAULT_MODE, target));
    }

    List<String> policiesTried = new ArrayList<>();
    for (LbConfig childPolicy : childPolicies) {
      String childPolicyName = childPolicy.getPolicyName();
      switch (childPolicyName) {
        case "round_robin":
          return ConfigOrError.fromConfig(GrpclbConfig.create(Mode.ROUND_ROBIN, target));
        case "pick_first":
          return ConfigOrError.fromConfig(GrpclbConfig.create(Mode.PICK_FIRST, target));
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

  /**
   * Gets a list from an object for the given key.
   */
  @Nullable
  private static List<?> getList(Map<String, ?> obj, String key) {
    assert key != null;
    if (!obj.containsKey(key)) {
      return null;
    }
    Object value = obj.get(key);
    if (!(value instanceof List)) {
      throw new ClassCastException(
          String.format("value '%s' for key '%s' in %s is not List", value, key, obj));
    }
    return (List<?>) value;
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, ?>> checkObjectList(List<?> rawList) {
    for (int i = 0; i < rawList.size(); i++) {
      if (!(rawList.get(i) instanceof Map)) {
        throw new ClassCastException(
            String.format("value %s for idx %d in %s is not object", rawList.get(i), i, rawList));
      }
    }
    return (List<Map<String, ?>>) rawList;
  }
}
