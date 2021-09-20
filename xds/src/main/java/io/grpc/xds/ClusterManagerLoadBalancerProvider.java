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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nullable;

/**
 * The provider for the cluster_manager load balancing policy. This class should not be directly
 * referenced in code.  The policy should be accessed through
 * {@link LoadBalancerRegistry#getProvider} with the name "cluster_manager_experimental".
 */
@Internal
public class ClusterManagerLoadBalancerProvider extends LoadBalancerProvider {

  @Nullable
  private final LoadBalancerRegistry lbRegistry;

  public ClusterManagerLoadBalancerProvider() {
    this(null);
  }

  @VisibleForTesting
  ClusterManagerLoadBalancerProvider(@Nullable LoadBalancerRegistry lbRegistry) {
    this.lbRegistry = lbRegistry;
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
    return XdsLbPolicies.CLUSTER_MANAGER_POLICY_NAME;
  }

  @Override
  public ConfigOrError parseLoadBalancingPolicyConfig(Map<String, ?> rawConfig) {
    Map<String, PolicySelection> parsedChildPolicies = new LinkedHashMap<>();
    try {
      Map<String, ?> childPolicies = JsonUtil.getObject(rawConfig, "childPolicy");
      if (childPolicies == null || childPolicies.isEmpty()) {
        return ConfigOrError.fromError(Status.INTERNAL.withDescription(
            "No child policy provided for cluster_manager LB policy: " + rawConfig));
      }
      for (String name : childPolicies.keySet()) {
        Map<String, ?> childPolicy = JsonUtil.getObject(childPolicies, name);
        if (childPolicy == null) {
          return ConfigOrError.fromError(Status.INTERNAL.withDescription(
              "No config for child " + name + " in cluster_manager LB policy: " + rawConfig));
        }
        List<LbConfig> childConfigCandidates =
            ServiceConfigUtil.unwrapLoadBalancingConfigList(
                JsonUtil.getListOfObjects(childPolicy, "lbPolicy"));
        if (childConfigCandidates == null || childConfigCandidates.isEmpty()) {
          return ConfigOrError.fromError(Status.INTERNAL.withDescription(
              "No config specified for child " + name + " in cluster_manager Lb policy: "
                  + rawConfig));
        }
        LoadBalancerRegistry registry =
            lbRegistry != null ? lbRegistry : LoadBalancerRegistry.getDefaultRegistry();
        ConfigOrError selectedConfig =
            ServiceConfigUtil.selectLbPolicyFromList(childConfigCandidates, registry);
        if (selectedConfig.getError() != null) {
          Status error = selectedConfig.getError();
          return ConfigOrError.fromError(
              Status.INTERNAL
                  .withCause(error.getCause())
                  .withDescription(error.getDescription())
                  .augmentDescription("Failed to select config for child " + name));
        }
        parsedChildPolicies.put(name, (PolicySelection) selectedConfig.getConfig());
      }
    } catch (RuntimeException e) {
      return ConfigOrError.fromError(
          Status.fromThrowable(e).withDescription(
              "Failed to parse cluster_manager LB config: " + rawConfig));
    }
    return ConfigOrError.fromConfig(new ClusterManagerConfig(parsedChildPolicies));
  }

  @Override
  public LoadBalancer newLoadBalancer(Helper helper) {
    return new ClusterManagerLoadBalancer(helper);
  }

  static class ClusterManagerConfig {
    final Map<String, PolicySelection> childPolicies;

    ClusterManagerConfig(Map<String, PolicySelection> childPolicies) {
      this.childPolicies = Collections.unmodifiableMap(childPolicies);
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof ClusterManagerConfig)) {
        return false;
      }
      ClusterManagerConfig config = (ClusterManagerConfig) o;
      return Objects.equals(childPolicies, config.childPolicies);
    }

    @Override
    public int hashCode() {
      return Objects.hash(childPolicies);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("childPolicies", childPolicies)
          .toString();
    }
  }
}
