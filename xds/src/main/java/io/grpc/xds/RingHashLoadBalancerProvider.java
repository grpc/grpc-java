/*
 * Copyright 2021 The gRPC Authors
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

import io.grpc.Internal;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancerProvider;
import io.grpc.NameResolver.ConfigOrError;
import io.grpc.Status;
import io.grpc.internal.JsonUtil;
import io.grpc.xds.RingHashLoadBalancer.RingHashConfig;
import java.util.Map;

/**
 * The provider for the "ring_hash" balancing policy.
 */
@Internal
public final class RingHashLoadBalancerProvider extends LoadBalancerProvider {

  private static final boolean enableRingHash =
      Boolean.parseBoolean(System.getenv("GRPC_XDS_EXPERIMENTAL_ENABLE_RING_HASH"));

  @Override
  public LoadBalancer newLoadBalancer(Helper helper) {
    return new RingHashLoadBalancer(helper);
  }

  @Override
  public boolean isAvailable() {
    return enableRingHash;
  }

  @Override
  public int getPriority() {
    return 5;
  }

  @Override
  public String getPolicyName() {
    return "ring_hash";
  }

  @Override
  public ConfigOrError parseLoadBalancingPolicyConfig(Map<String, ?> rawLoadBalancingPolicyConfig) {
    Long minRingSize = JsonUtil.getNumberAsLong(rawLoadBalancingPolicyConfig, "minRingSize");
    Long maxRingSize = JsonUtil.getNumberAsLong(rawLoadBalancingPolicyConfig, "maxRingSize");
    if (minRingSize == null || maxRingSize == null) {
      return ConfigOrError.fromError(Status.INVALID_ARGUMENT.withDescription(
          "Missing 'mingRingSize'/'maxRingSize'"));
    }
    if (minRingSize <= 0 || maxRingSize <= 0 || minRingSize > maxRingSize) {
      return ConfigOrError.fromError(Status.INVALID_ARGUMENT.withDescription(
          "Invalid 'mingRingSize'/'maxRingSize'"));
    }
    return ConfigOrError.fromConfig(new RingHashConfig(minRingSize, maxRingSize));
  }
}
