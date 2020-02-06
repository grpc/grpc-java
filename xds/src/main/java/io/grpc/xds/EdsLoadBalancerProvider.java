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

import io.grpc.Internal;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancerProvider;
import io.grpc.LoadBalancerRegistry;
import io.grpc.NameResolver.ConfigOrError;
import io.grpc.xds.EdsLoadBalancer.ResourceUpdateCallback;
import java.util.Map;

/**
 * The provider for the "eds" balancing policy.  This class should not be directly referenced in
 * code.  The policy should be accessed through {@link io.grpc.LoadBalancerRegistry#getProvider}
 * with the name "eds_experimental").
 */
@Internal
public class EdsLoadBalancerProvider extends LoadBalancerProvider {

  static final String EDS_POLICY_NAME = "eds_experimental";

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
    return EDS_POLICY_NAME;
  }

  @Override
  public LoadBalancer newLoadBalancer(Helper helper) {
    return new EdsLoadBalancer(
        helper,
        new ResourceUpdateCallback() {
          @Override
          public void onWorking() {}

          @Override
          public void onError() {}

          @Override
          public void onAllDrop() {}
        });
  }

  @Override
  public ConfigOrError parseLoadBalancingPolicyConfig(
      Map<String, ?> rawLoadBalancingPolicyConfig) {
    return XdsLoadBalancerProvider.parseLoadBalancingConfigPolicy(
        rawLoadBalancingPolicyConfig, LoadBalancerRegistry.getDefaultRegistry());
  }
}
