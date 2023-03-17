/*
 * Copyright 2023 The gRPC Authors
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

package io.grpc.examples.customloadbalance;

import io.grpc.LoadBalancer;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancerProvider;
import io.grpc.NameResolver.ConfigOrError;
import io.grpc.Status;
import java.util.Map;

public class ShufflingPickFirstLoadBalancerProvider extends LoadBalancerProvider {

  private static final String RANDOM_SEED_KEY = "randomSeed";

  @Override
  public ConfigOrError parseLoadBalancingPolicyConfig(Map<String, ?> rawLoadBalancingPolicyConfig) {
    Long randomSeed = null;
    if (rawLoadBalancingPolicyConfig.containsKey(RANDOM_SEED_KEY)) {
      // The load balancing configuration generally comes from a remote source over the wire, be
      // defensive when parsing it.
      try {
        randomSeed = ((Double) rawLoadBalancingPolicyConfig.get(RANDOM_SEED_KEY)).longValue();
      } catch (RuntimeException e) {
        return ConfigOrError.fromError(
            Status.UNAVAILABLE.withDescription("unable to parse LB config"));
      }
    }
    return ConfigOrError.fromConfig(new ShufflingPickFirstLoadBalancer.Config(randomSeed));
  }

  @Override
  public LoadBalancer newLoadBalancer(Helper helper) {
    return new ShufflingPickFirstLoadBalancer(helper);
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
    return "example.shuffling_pick_first";
  }
}
