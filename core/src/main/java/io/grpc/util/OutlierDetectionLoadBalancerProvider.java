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

package io.grpc.util;

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
import io.grpc.internal.TimeProvider;
import io.grpc.util.OutlierDetectionLoadBalancer.OutlierDetectionLoadBalancerConfig;
import io.grpc.util.OutlierDetectionLoadBalancer.OutlierDetectionLoadBalancerConfig.FailurePercentageEjection;
import io.grpc.util.OutlierDetectionLoadBalancer.OutlierDetectionLoadBalancerConfig.SuccessRateEjection;
import java.util.List;
import java.util.Map;

@Internal
public final class OutlierDetectionLoadBalancerProvider extends LoadBalancerProvider {

  @Override
  public LoadBalancer newLoadBalancer(Helper helper) {
    return new OutlierDetectionLoadBalancer(helper, TimeProvider.SYSTEM_TIME_PROVIDER);
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
    return "outlier_detection_experimental";
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
    // Common configuration.
    Long intervalNanos = JsonUtil.getStringAsDuration(rawConfig, "interval");
    Long baseEjectionTimeNanos = JsonUtil.getStringAsDuration(rawConfig, "baseEjectionTime");
    Long maxEjectionTimeNanos = JsonUtil.getStringAsDuration(rawConfig, "maxEjectionTime");
    Integer maxEjectionPercentage = JsonUtil.getNumberAsInteger(rawConfig,
        "maxEjectionPercentage");

    OutlierDetectionLoadBalancerConfig.Builder configBuilder
        = new OutlierDetectionLoadBalancerConfig.Builder();
    if (intervalNanos != null) {
      configBuilder.setIntervalNanos(intervalNanos);
    }
    if (baseEjectionTimeNanos != null) {
      configBuilder.setBaseEjectionTimeNanos(baseEjectionTimeNanos);
    }
    if (maxEjectionTimeNanos != null) {
      configBuilder.setMaxEjectionTimeNanos(maxEjectionTimeNanos);
    }
    if (maxEjectionPercentage != null) {
      configBuilder.setMaxEjectionPercent(maxEjectionPercentage);
    }

    // Success rate ejection specific configuration.
    Map<String, ?> rawSuccessRateEjection = JsonUtil.getObject(rawConfig, "successRateEjection");
    if (rawSuccessRateEjection != null) {
      SuccessRateEjection.Builder successRateEjectionBuilder = new SuccessRateEjection.Builder();

      Integer stdevFactor = JsonUtil.getNumberAsInteger(rawSuccessRateEjection, "stdevFactor");
      Integer enforcementPercentage = JsonUtil.getNumberAsInteger(rawSuccessRateEjection,
          "enforcementPercentage");
      Integer minimumHosts = JsonUtil.getNumberAsInteger(rawSuccessRateEjection, "minimumHosts");
      Integer requestVolume = JsonUtil.getNumberAsInteger(rawSuccessRateEjection, "requestVolume");

      if (stdevFactor != null) {
        successRateEjectionBuilder.setStdevFactor(stdevFactor);
      }
      if (enforcementPercentage != null) {
        successRateEjectionBuilder.setEnforcementPercentage(enforcementPercentage);
      }
      if (minimumHosts != null) {
        successRateEjectionBuilder.setMinimumHosts(minimumHosts);
      }
      if (requestVolume != null) {
        successRateEjectionBuilder.setRequestVolume(requestVolume);
      }

      configBuilder.setSuccessRateEjection(successRateEjectionBuilder.build());
    }

    // Failure percentage ejection specific configuration.
    Map<String, ?> rawFailurePercentageEjection = JsonUtil.getObject(rawConfig,
        "failurePercentageEjection");
    if (rawFailurePercentageEjection != null) {
      FailurePercentageEjection.Builder failurePercentageEjectionBuilder
          = new FailurePercentageEjection.Builder();

      Integer threshold = JsonUtil.getNumberAsInteger(rawFailurePercentageEjection, "threshold");
      Integer enforcementPercentage = JsonUtil.getNumberAsInteger(rawFailurePercentageEjection,
          "enforcementPercentage");
      Integer minimumHosts = JsonUtil.getNumberAsInteger(rawFailurePercentageEjection,
          "minimumHosts");
      Integer requestVolume = JsonUtil.getNumberAsInteger(rawFailurePercentageEjection,
          "requestVolume");

      if (threshold != null) {
        failurePercentageEjectionBuilder.setThreshold(threshold);
      }
      if (enforcementPercentage != null) {
        failurePercentageEjectionBuilder.setEnforcementPercentage(enforcementPercentage);
      }
      if (minimumHosts != null) {
        failurePercentageEjectionBuilder.setMinimumHosts(minimumHosts);
      }
      if (requestVolume != null) {
        failurePercentageEjectionBuilder.setRequestVolume(requestVolume);
      }

      configBuilder.setFailurePercentageEjection(failurePercentageEjectionBuilder.build());
    }

    // Child load balancer configuration.
    List<LbConfig> childConfigCandidates = ServiceConfigUtil.unwrapLoadBalancingConfigList(
        JsonUtil.getListOfObjects(rawConfig, "childPolicy"));
    if (childConfigCandidates == null || childConfigCandidates.isEmpty()) {
      return ConfigOrError.fromError(Status.INTERNAL.withDescription(
          "No child policy in outlier_detection_experimental LB policy: "
              + rawConfig));
    }
    ConfigOrError selectedConfig =
        ServiceConfigUtil.selectLbPolicyFromList(childConfigCandidates,
            LoadBalancerRegistry.getDefaultRegistry());
    if (selectedConfig.getError() != null) {
      return selectedConfig;
    }
    configBuilder.setChildPolicy((PolicySelection) selectedConfig.getConfig());

    return ConfigOrError.fromConfig(configBuilder.build());
  }
}
