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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import io.grpc.Internal;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancerProvider;
import io.grpc.NameResolver.ConfigOrError;
import io.grpc.Status;
import io.grpc.internal.JsonUtil;
import io.grpc.xds.RingHashLoadBalancer.RingHashConfig;
import io.grpc.xds.RingHashOptions;
import java.util.Map;

/**
 * The provider for the "ring_hash_experimental" balancing policy.
 */
@Internal
public final class RingHashLoadBalancerProvider extends LoadBalancerProvider {

  // Same as ClientXdsClient.DEFAULT_RING_HASH_LB_POLICY_MIN_RING_SIZE
  @VisibleForTesting
  static final long DEFAULT_MIN_RING_SIZE = 1024L;
  // Same as ClientXdsClient.DEFAULT_RING_HASH_LB_POLICY_MAX_RING_SIZE
  @VisibleForTesting
  static final long DEFAULT_MAX_RING_SIZE = 4 * 1024L;

  private static final boolean enableRingHash =
      Strings.isNullOrEmpty(System.getenv("GRPC_XDS_EXPERIMENTAL_ENABLE_RING_HASH"))
          || Boolean.parseBoolean(System.getenv("GRPC_XDS_EXPERIMENTAL_ENABLE_RING_HASH"));

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
    return "ring_hash_experimental";
  }

  @Override
  public ConfigOrError parseLoadBalancingPolicyConfig(Map<String, ?> rawLoadBalancingPolicyConfig) {
    try {
      return parseLoadBalancingPolicyConfigInternal(rawLoadBalancingPolicyConfig);
    } catch (RuntimeException e) {
      return ConfigOrError.fromError(
          Status.UNAVAILABLE.withCause(e).withDescription(
              "Failed parsing configuration for " + getPolicyName()));
    }
  }

  private ConfigOrError parseLoadBalancingPolicyConfigInternal(
      Map<String, ?> rawLoadBalancingPolicyConfig) {
    Long minRingSize = JsonUtil.getNumberAsLong(rawLoadBalancingPolicyConfig, "minRingSize");
    Long maxRingSize = JsonUtil.getNumberAsLong(rawLoadBalancingPolicyConfig, "maxRingSize");
    long maxRingSizeCap = RingHashOptions.getRingSizeCap();
    if (minRingSize == null) {
      minRingSize = DEFAULT_MIN_RING_SIZE;
    }
    if (maxRingSize == null) {
      maxRingSize = DEFAULT_MAX_RING_SIZE;
    }
    if (minRingSize > maxRingSizeCap) {
      minRingSize = maxRingSizeCap;
    }
    if (maxRingSize > maxRingSizeCap) {
      maxRingSize = maxRingSizeCap;
    }
    if (minRingSize <= 0 || maxRingSize <= 0 || minRingSize > maxRingSize) {
      return ConfigOrError.fromError(Status.UNAVAILABLE.withDescription(
          "Invalid 'mingRingSize'/'maxRingSize'"));
    }
    return ConfigOrError.fromConfig(new RingHashConfig(minRingSize, maxRingSize));
  }
}
