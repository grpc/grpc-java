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

package io.grpc.xds;

import com.google.common.annotations.VisibleForTesting;
import io.grpc.Deadline;
import io.grpc.ExperimentalApi;
import io.grpc.Internal;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancerProvider;
import io.grpc.NameResolver.ConfigOrError;
import io.grpc.Status;
import io.grpc.internal.JsonUtil;
import io.grpc.xds.WeightedRoundRobinLoadBalancer.WeightedRoundRobinLoadBalancerConfig;
import java.util.Map;

/**
 * Provides a {@link WeightedRoundRobinLoadBalancer}.
 * */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/9885")
@Internal
public final class WeightedRoundRobinLoadBalancerProvider extends LoadBalancerProvider {

  @VisibleForTesting
  static final long MIN_WEIGHT_UPDATE_PERIOD_NANOS = 100_000_000L; // 100ms

  static final String SCHEME = "weighted_round_robin";

  @Override
  public LoadBalancer newLoadBalancer(Helper helper) {
    return new WeightedRoundRobinLoadBalancer(helper, Deadline.getSystemTicker());
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
    return SCHEME;
  }

  @Override
  public ConfigOrError parseLoadBalancingPolicyConfig(Map<String, ?> rawConfig) {
    try {
      return parseLoadBalancingPolicyConfigInternal(rawConfig);
    } catch (RuntimeException e) {
      return ConfigOrError.fromError(
          Status.UNAVAILABLE.withCause(e).withDescription(
              "Failed parsing configuration for " + getPolicyName()));
    }
  }

  private ConfigOrError parseLoadBalancingPolicyConfigInternal(Map<String, ?> rawConfig) {
    Long blackoutPeriodNanos = JsonUtil.getStringAsDuration(rawConfig, "blackoutPeriod");
    Long weightExpirationPeriodNanos =
            JsonUtil.getStringAsDuration(rawConfig, "weightExpirationPeriod");
    Long oobReportingPeriodNanos = JsonUtil.getStringAsDuration(rawConfig, "oobReportingPeriod");
    Boolean enableOobLoadReport = JsonUtil.getBoolean(rawConfig, "enableOobLoadReport");
    Long weightUpdatePeriodNanos = JsonUtil.getStringAsDuration(rawConfig, "weightUpdatePeriod");
    Float errorUtilizationPenalty = JsonUtil.getNumberAsFloat(rawConfig, "errorUtilizationPenalty");

    WeightedRoundRobinLoadBalancerConfig.Builder configBuilder =
            WeightedRoundRobinLoadBalancerConfig.newBuilder();
    if (blackoutPeriodNanos != null) {
      configBuilder.setBlackoutPeriodNanos(blackoutPeriodNanos);
    }
    if (weightExpirationPeriodNanos != null) {
      configBuilder.setWeightExpirationPeriodNanos(weightExpirationPeriodNanos);
    }
    if (enableOobLoadReport != null) {
      configBuilder.setEnableOobLoadReport(enableOobLoadReport);
    }
    if (oobReportingPeriodNanos != null) {
      configBuilder.setOobReportingPeriodNanos(oobReportingPeriodNanos);
    }
    if (weightUpdatePeriodNanos != null) {
      configBuilder.setWeightUpdatePeriodNanos(weightUpdatePeriodNanos);
      if (weightUpdatePeriodNanos < MIN_WEIGHT_UPDATE_PERIOD_NANOS) {
        configBuilder.setWeightUpdatePeriodNanos(MIN_WEIGHT_UPDATE_PERIOD_NANOS);
      }
    }
    if (errorUtilizationPenalty != null) {
      configBuilder.setErrorUtilizationPenalty(errorUtilizationPenalty);
    }
    return ConfigOrError.fromConfig(configBuilder.build());
  }
}
