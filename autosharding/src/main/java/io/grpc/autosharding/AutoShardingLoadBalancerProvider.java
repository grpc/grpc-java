/*
 * Copyright 2026 The gRPC Authors
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

package io.grpc.autosharding;

import com.google.common.base.MoreObjects;
import io.grpc.Internal;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancerProvider;
import io.grpc.NameResolver.ConfigOrError;
import io.grpc.Status;
import io.grpc.internal.JsonUtil;
import java.util.Map;
import java.util.Objects;

@Internal
public final class AutoShardingLoadBalancerProvider extends LoadBalancerProvider {
  static final String POLICY_NAME = "autosharding_experimental";

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
    return POLICY_NAME;
  }

  @Override
  public LoadBalancer newLoadBalancer(LoadBalancer.Helper helper) {
    return new AutoShardingLoadBalancer(helper);
  }

  @Override
  public ConfigOrError parseLoadBalancingPolicyConfig(
      Map<String, ?> rawLoadBalancingPolicyConfig) {
    try {
      String channelFactoryKey =
          JsonUtil.getString(rawLoadBalancingPolicyConfig, "channelFactoryKey");
      if (channelFactoryKey == null || channelFactoryKey.isEmpty()) {
        return ConfigOrError.fromError(
            Status.INVALID_ARGUMENT.withDescription(
                "Missing required field 'channelFactoryKey' in autosharding config"));
      }

      String autoshardingTarget =
          JsonUtil.getString(rawLoadBalancingPolicyConfig, "autoshardingTarget");
      if (autoshardingTarget == null || autoshardingTarget.isEmpty()) {
        autoshardingTarget =
            JsonUtil.getString(rawLoadBalancingPolicyConfig, "slicingTarget");
      }
      if (autoshardingTarget == null || autoshardingTarget.isEmpty()) {
        return ConfigOrError.fromError(
            Status.INVALID_ARGUMENT.withDescription(
                "Missing required field 'autoshardingTarget' in autosharding config"));
      }

      String sliceKeyHeaderName =
          JsonUtil.getString(rawLoadBalancingPolicyConfig, "sliceKeyHeaderName");
      if (sliceKeyHeaderName == null || sliceKeyHeaderName.isEmpty()) {
        return ConfigOrError.fromError(
            Status.INVALID_ARGUMENT.withDescription(
                "Missing required field 'sliceKeyHeaderName' in autosharding config"));
      }

      Boolean enableFallback =
          JsonUtil.getBoolean(rawLoadBalancingPolicyConfig, "enableFallback");
      if (enableFallback == null) {
        enableFallback = false;
      }

      Long initialAssignmentTimeoutNanos =
          JsonUtil.getStringAsDuration(
              rawLoadBalancingPolicyConfig, "initialAssignmentTimeout");

      return ConfigOrError.fromConfig(
          new AutoShardingConfig(
              channelFactoryKey,
              autoshardingTarget,
              sliceKeyHeaderName,
              enableFallback,
              initialAssignmentTimeoutNanos));
    } catch (RuntimeException e) {
      return ConfigOrError.fromError(
          Status.INVALID_ARGUMENT.withDescription(
              "Failed to parse autosharding config: " + e.getMessage()).withCause(e));
    }
  }

  public static final class AutoShardingConfig {
    final String channelFactoryKey;
    final String autoshardingTarget;
    final String sliceKeyHeaderName;
    final boolean enableFallback;
    final Long initialAssignmentTimeoutNanos;

    public AutoShardingConfig(
        String channelFactoryKey,
        String autoshardingTarget,
        String sliceKeyHeaderName,
        boolean enableFallback,
        Long initialAssignmentTimeoutNanos) {
      this.channelFactoryKey = channelFactoryKey;
      this.autoshardingTarget = autoshardingTarget;
      this.sliceKeyHeaderName = sliceKeyHeaderName;
      this.enableFallback = enableFallback;
      this.initialAssignmentTimeoutNanos = initialAssignmentTimeoutNanos;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      AutoShardingConfig that = (AutoShardingConfig) o;
      return enableFallback == that.enableFallback
          && Objects.equals(channelFactoryKey, that.channelFactoryKey)
          && Objects.equals(autoshardingTarget, that.autoshardingTarget)
          && Objects.equals(sliceKeyHeaderName, that.sliceKeyHeaderName)
          && Objects.equals(initialAssignmentTimeoutNanos, that.initialAssignmentTimeoutNanos);
    }

    @Override
    public int hashCode() {
      return Objects.hash(
          channelFactoryKey,
          autoshardingTarget,
          sliceKeyHeaderName,
          enableFallback,
          initialAssignmentTimeoutNanos);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("channelFactoryKey", channelFactoryKey)
          .add("autoshardingTarget", autoshardingTarget)
          .add("sliceKeyHeaderName", sliceKeyHeaderName)
          .add("enableFallback", enableFallback)
          .add("initialAssignmentTimeoutNanos", initialAssignmentTimeoutNanos)
          .toString();
    }
  }
}
