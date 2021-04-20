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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.base.MoreObjects;
import io.grpc.Internal;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancerProvider;
import io.grpc.NameResolver.ConfigOrError;
import io.grpc.internal.ServiceConfigUtil.PolicySelection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** Provider for priority load balancing policy. */
@Internal
public final class PriorityLoadBalancerProvider extends LoadBalancerProvider {

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
    return "priority_experimental";
  }

  @Override
  public LoadBalancer newLoadBalancer(Helper helper) {
    return new PriorityLoadBalancer(helper);
  }

  @Override
  public ConfigOrError parseLoadBalancingPolicyConfig(Map<String, ?> rawConfig) {
    throw new UnsupportedOperationException();
  }

  static final class PriorityLbConfig {
    final Map<String, PriorityChildConfig> childConfigs;
    final List<String> priorities;

    PriorityLbConfig(Map<String, PriorityChildConfig> childConfigs, List<String> priorities) {
      this.childConfigs = Collections.unmodifiableMap(checkNotNull(childConfigs, "childConfigs"));
      this.priorities = Collections.unmodifiableList(checkNotNull(priorities, "priorities"));
      checkArgument(!priorities.isEmpty(), "priority list is empty");
      checkArgument(
          childConfigs.keySet().containsAll(priorities),
          "missing child config for at lease one of the priorities");
      checkArgument(
          priorities.size() == new HashSet<>(priorities).size(),
          "duplicate names in priorities");
      checkArgument(
          priorities.size() == childConfigs.keySet().size(),
          "some names in childConfigs are not referenced by priorities");
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("childConfigs", childConfigs)
          .add("priorities", priorities)
          .toString();
    }

    static final class PriorityChildConfig {
      final PolicySelection policySelection;
      final boolean ignoreReresolution;

      PriorityChildConfig(PolicySelection policySelection, boolean ignoreReresolution) {
        this.policySelection = checkNotNull(policySelection, "policySelection");
        this.ignoreReresolution = ignoreReresolution;
      }
    }
  }
}
