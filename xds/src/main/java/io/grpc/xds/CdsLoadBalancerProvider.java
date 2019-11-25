/*
 * Copyright 2019 The gRPC Authors
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
import io.grpc.xds.CdsLoadBalancer.CdsConfig;
import java.util.Map;

@Internal
public class CdsLoadBalancerProvider extends LoadBalancerProvider {

  static final String CDS_POLICY_NAME = "experimental_cds";
  private static final String CLUSTER_KEY = "cluster";

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
    return CDS_POLICY_NAME;
  }

  @Override
  public LoadBalancer newLoadBalancer(Helper helper) {
    return new CdsLoadBalancer(helper);
  }

  @Override
  public ConfigOrError parseLoadBalancingPolicyConfig(
      Map<String, ?> rawLoadBalancingPolicyConfig) {
    return parseLoadBalancingConfigPolicy(rawLoadBalancingPolicyConfig);
  }

  /**
   * Parses raw load balancing config and returns a {@link ConfigOrError} that contains a
   * {@link CdsConfig} if parsing is successful.
   */
  static ConfigOrError parseLoadBalancingConfigPolicy(Map<String, ?> rawLoadBalancingPolicyConfig) {
    try {
      String cluster =
          JsonUtil.getString(rawLoadBalancingPolicyConfig, CLUSTER_KEY);
      return ConfigOrError.fromConfig(new CdsConfig(cluster));
    } catch (RuntimeException e) {
      return ConfigOrError.fromError(
          Status.UNKNOWN.withDescription("Failed to parse config " + e.getMessage()).withCause(e));
    }
  }
}
