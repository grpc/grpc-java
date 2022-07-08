/*
 * Copyright 2022 The gRPC Authors
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
import io.grpc.Status;
import io.grpc.internal.JsonUtil;
import io.grpc.internal.ServiceConfigUtil;
import io.grpc.internal.ServiceConfigUtil.LbConfig;
import io.grpc.internal.ServiceConfigUtil.PolicySelection;
import io.grpc.xds.WrrLocalityLoadBalancer.WrrLocalityConfig;
import java.util.List;
import java.util.Map;

/**
 * The provider for {@link WrrLocalityLoadBalancer}. An instance of this class should be acquired
 * through {@link LoadBalancerRegistry#getProvider} by using the name
 * "xds_wrr_locality_experimental".
 */
@Internal
public final class WrrLocalityLoadBalancerProvider extends LoadBalancerProvider {

  @Override
  public LoadBalancer newLoadBalancer(Helper helper) {
    return new WrrLocalityLoadBalancer(helper);
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
    return XdsLbPolicies.WRR_LOCALITY_POLICY_NAME;
  }

  @Override
  public ConfigOrError parseLoadBalancingPolicyConfig(Map<String, ?> rawConfig) {
    try {
      List<LbConfig> childConfigCandidates = ServiceConfigUtil.unwrapLoadBalancingConfigList(
          JsonUtil.getListOfObjects(rawConfig, "childPolicy"));
      if (childConfigCandidates == null || childConfigCandidates.isEmpty()) {
        return ConfigOrError.fromError(Status.INTERNAL.withDescription(
            "No child policy in wrr_locality LB policy: "
                + rawConfig));
      }
      ConfigOrError selectedConfig =
          ServiceConfigUtil.selectLbPolicyFromList(childConfigCandidates,
              LoadBalancerRegistry.getDefaultRegistry());
      if (selectedConfig.getError() != null) {
        return selectedConfig;
      }
      PolicySelection policySelection = (PolicySelection) selectedConfig.getConfig();
      return ConfigOrError.fromConfig(new WrrLocalityConfig(policySelection));
    } catch (RuntimeException e) {
      return ConfigOrError.fromError(Status.INTERNAL.withCause(e)
          .withDescription("Failed to parse wrr_locality LB config: " + rawConfig));
    }
  }
}
