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
import io.grpc.LoadBalancerProvider;
import io.grpc.NameResolver.ConfigOrError;
import io.grpc.Status;
import io.grpc.internal.JsonUtil;
import io.grpc.xds.LeastRequestLoadBalancer.LeastRequestConfig;
import java.util.Map;

/**
 * Provider for the "least_request_experimental" balancing policy.
 */
@Internal
public final class LeastRequestLoadBalancerProvider extends LoadBalancerProvider {
  // Minimum number of choices allowed.
  static final int MIN_CHOICE_COUNT = 2;
  // Maximum number of choices allowed.
  static final int MAX_CHOICE_COUNT = 10;
  // Same as ClientXdsClient.DEFAULT_LEAST_REQUEST_CHOICE_COUNT
  @VisibleForTesting
  static final Integer DEFAULT_CHOICE_COUNT = 2;

  // Use GRPC_EXPERIMENTAL_ENABLE_LEAST_REQUEST if set,
  // otherwise use the io.grpc.xds.experimentalEnableLeastRequest system property.
  private static final boolean enableLeastRequest =
      !Strings.isNullOrEmpty(System.getenv("GRPC_EXPERIMENTAL_ENABLE_LEAST_REQUEST"))
          ? Boolean.parseBoolean(System.getenv("GRPC_EXPERIMENTAL_ENABLE_LEAST_REQUEST"))
          : Boolean.parseBoolean(System.getProperty("io.grpc.xds.experimentalEnableLeastRequest"));

  @Override
  public LoadBalancer newLoadBalancer(LoadBalancer.Helper helper) {
    return new LeastRequestLoadBalancer(helper);
  }

  @Override
  public boolean isAvailable() {
    return enableLeastRequest;
  }

  @Override
  public int getPriority() {
    return 5;
  }

  @Override
  public String getPolicyName() {
    return "least_request_experimental";
  }

  @Override
  public ConfigOrError parseLoadBalancingPolicyConfig(Map<String, ?> rawConfig) {
    try {
      Integer choiceCount = JsonUtil.getNumberAsInteger(rawConfig, "choiceCount");
      if (choiceCount == null) {
        choiceCount = DEFAULT_CHOICE_COUNT;
      }
      if (choiceCount < MIN_CHOICE_COUNT) {
        return ConfigOrError.fromError(Status.INVALID_ARGUMENT.withDescription(
            "Invalid 'choiceCount'"));
      }
      return ConfigOrError.fromConfig(new LeastRequestConfig(choiceCount));
    } catch (RuntimeException e) {
      return ConfigOrError.fromError(
          Status.fromThrowable(e).withDescription(
              "Failed to parse least_request_experimental LB config: " + rawConfig));
    }
  }
}
