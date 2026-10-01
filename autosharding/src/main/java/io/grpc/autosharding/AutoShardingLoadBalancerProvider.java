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

import io.grpc.Internal;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancerProvider;
import io.grpc.Metadata;
import io.grpc.NameResolver.ConfigOrError;
import io.grpc.Status;
import io.grpc.internal.JsonUtil;
import java.util.Map;

/**
 * Provider for the {@code autosharding_experimental} balancing policy.
 *
 * <p>Registering this on the classpath is what makes the policy reachable by name, both from a
 * service config and from the xDS integration.
 */
@Internal
public final class AutoShardingLoadBalancerProvider extends LoadBalancerProvider {
  private static final String POLICY_NAME = "autosharding_experimental";

  @Override
  public LoadBalancer newLoadBalancer(LoadBalancer.Helper helper) {
    return new AutoShardingLoadBalancer(helper);
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
    return POLICY_NAME;
  }

  @Override
  public ConfigOrError parseLoadBalancingPolicyConfig(Map<String, ?> rawConfig) {
    try {
      return parseLoadBalancingPolicyConfigInternal(rawConfig);
    } catch (RuntimeException e) {
      return ConfigOrError.fromError(
          Status.UNAVAILABLE
              .withCause(e)
              .withDescription("Failed parsing configuration for " + getPolicyName()));
    }
  }

  /**
   * Translates {@code AutoshardingLbConfig} from its JSON form, as specified by gRFC A119.
   *
   * <p>The configuration is a proto3 message, so an absent string field and an empty one are the
   * same value and no field is required. What gets rejected here is therefore a judgment call
   * rather than something the gRFC spells out, and the bar is deliberately high: a value is only
   * turned away when it has no coherent reading, or when honouring it would go wrong quietly.
   * Anything the sharding service or the "Channel Factory" is the authority on is left to fail
   * visibly at runtime instead of being guessed at here.
   */
  private ConfigOrError parseLoadBalancingPolicyConfigInternal(Map<String, ?> rawConfig) {
    String channelFactoryKey = JsonUtil.getString(rawConfig, "channelFactoryKey");
    if (channelFactoryKey == null) {
      channelFactoryKey = "";
    }

    String autoshardingTarget = JsonUtil.getString(rawConfig, "autoshardingTarget");
    if (autoshardingTarget == null) {
      autoshardingTarget = "";
    }

    String keyHeaderName = JsonUtil.getString(rawConfig, "keyHeaderName");
    if (keyHeaderName == null || keyHeaderName.isEmpty()) {
      return error("'keyHeaderName' is required, LB policy config=" + rawConfig);
    }
    try {
      // Rejects names that are not valid header names. Doing it here means a typo surfaces as a
      // channel error instead of an exception thrown on the synchronization context later.
      Metadata.Key<byte[]> unused = AutoShardingPicker.createKeyHeader(keyHeaderName);
    } catch (IllegalArgumentException e) {
      return error("'keyHeaderName' is not a valid header name: " + keyHeaderName);
    }

    Boolean enableFallback = JsonUtil.getBoolean(rawConfig, "enableFallback");

    Long initialAssignmentTimeoutNanos =
        JsonUtil.getStringAsDuration(rawConfig, "initialAssignmentTimeout");
    if (initialAssignmentTimeoutNanos == null) {
      initialAssignmentTimeoutNanos =
          AutoShardingLoadBalancerConfig.DEFAULT_INITIAL_ASSIGNMENT_TIMEOUT_NANOS;
    } else if (initialAssignmentTimeoutNanos < 0) {
      return error(
          "'initialAssignmentTimeout' must not be negative, LB policy config=" + rawConfig);
    }

    return ConfigOrError.fromConfig(
        new AutoShardingLoadBalancerConfig(
            channelFactoryKey,
            autoshardingTarget,
            keyHeaderName,
            enableFallback != null && enableFallback,
            initialAssignmentTimeoutNanos));
  }

  private static ConfigOrError error(String description) {
    return ConfigOrError.fromError(
        Status.UNAVAILABLE.withDescription("autosharding: " + description));
  }
}
