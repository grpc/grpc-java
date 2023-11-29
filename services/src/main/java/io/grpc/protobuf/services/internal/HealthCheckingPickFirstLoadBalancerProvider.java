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

package io.grpc.protobuf.services.internal;

import io.grpc.Internal;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancerProvider;
import io.grpc.NameResolver.ConfigOrError;
import io.grpc.internal.PickFirstLoadBalancerProvider;
import io.grpc.protobuf.services.HealthCheckingLoadBalancerUtil;
import java.util.Map;

/**
 * The health-check-capable provider for the "pick first" balancing policy.  This overrides
 * the "pick first" provided by grpc-core.
 */
@Internal
public final class HealthCheckingPickFirstLoadBalancerProvider extends LoadBalancerProvider {
  private final LoadBalancerProvider pickFirstProvider;

  public HealthCheckingPickFirstLoadBalancerProvider() {
    pickFirstProvider = new PickFirstLoadBalancerProvider();
  }

  @Override
  public boolean isAvailable() {
    return pickFirstProvider.isAvailable();
  }

  @Override
  public int getPriority() {
    return pickFirstProvider.getPriority() + 1;
  }

  @Override
  public String getPolicyName() {
    return pickFirstProvider.getPolicyName();
  }

  @Override
  public LoadBalancer newLoadBalancer(Helper helper) {
    return HealthCheckingLoadBalancerUtil.newHealthCheckingLoadBalancer(pickFirstProvider, helper);
  }

  @Override
  public ConfigOrError parseLoadBalancingPolicyConfig(
      Map<String, ?> rawLoadBalancingPolicyConfig) {
    return pickFirstProvider.parseLoadBalancingPolicyConfig(rawLoadBalancingPolicyConfig);
  }
}
