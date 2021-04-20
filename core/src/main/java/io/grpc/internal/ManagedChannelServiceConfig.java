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

package io.grpc.internal;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import com.google.common.base.Strings;
import io.grpc.CallOptions;
import io.grpc.InternalConfigSelector;
import io.grpc.LoadBalancer.PickSubchannelArgs;
import io.grpc.MethodDescriptor;
import io.grpc.internal.RetriableStream.Throttle;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * {@link ManagedChannelServiceConfig} is a fully parsed and validated representation of service
 * configuration data.
 */
final class ManagedChannelServiceConfig {

  @Nullable
  private final MethodInfo defaultMethodConfig;
  private final Map<String, MethodInfo> serviceMethodMap;
  private final Map<String, MethodInfo> serviceMap;
  @Nullable
  private final Throttle retryThrottling;
  @Nullable
  private final Object loadBalancingConfig;
  @Nullable
  private final Map<String, ?> healthCheckingConfig;

  ManagedChannelServiceConfig(
      @Nullable MethodInfo defaultMethodConfig,
      Map<String, MethodInfo> serviceMethodMap,
      Map<String, MethodInfo> serviceMap,
      @Nullable Throttle retryThrottling,
      @Nullable Object loadBalancingConfig,
      @Nullable Map<String, ?> healthCheckingConfig) {
    this.defaultMethodConfig = defaultMethodConfig;
    this.serviceMethodMap = Collections.unmodifiableMap(new HashMap<>(serviceMethodMap));
    this.serviceMap = Collections.unmodifiableMap(new HashMap<>(serviceMap));
    this.retryThrottling = retryThrottling;
    this.loadBalancingConfig = loadBalancingConfig;
    this.healthCheckingConfig =
        healthCheckingConfig != null
            ? Collections.unmodifiableMap(new HashMap<>(healthCheckingConfig))
            : null;
  }

  /** Returns an empty {@link ManagedChannelServiceConfig}. */
  static ManagedChannelServiceConfig empty() {
    return
        new ManagedChannelServiceConfig(
            null,
            new HashMap<String, MethodInfo>(),
            new HashMap<String, MethodInfo>(),
            /* retryThrottling= */ null,
            /* loadBalancingConfig= */ null,
            /* healthCheckingConfig= */ null);
  }

  /**
   * Parses the Channel level config values (e.g. excludes load balancing)
   */
  static ManagedChannelServiceConfig fromServiceConfig(
      Map<String, ?> serviceConfig,
      boolean retryEnabled,
      int maxRetryAttemptsLimit,
      int maxHedgedAttemptsLimit,
      @Nullable Object loadBalancingConfig) {
    Throttle retryThrottling = null;
    if (retryEnabled) {
      retryThrottling = ServiceConfigUtil.getThrottlePolicy(serviceConfig);
    }
    Map<String, MethodInfo> serviceMethodMap = new HashMap<>();
    Map<String, MethodInfo> serviceMap = new HashMap<>();
    Map<String, ?> healthCheckingConfig =
        ServiceConfigUtil.getHealthCheckedService(serviceConfig);

    // Try and do as much validation here before we swap out the existing configuration.  In case
    // the input is invalid, we don't want to lose the existing configuration.
    List<Map<String, ?>> methodConfigs =
        ServiceConfigUtil.getMethodConfigFromServiceConfig(serviceConfig);

    if (methodConfigs == null) {
      // this is surprising, but possible.
      return
          new ManagedChannelServiceConfig(
              null,
              serviceMethodMap,
              serviceMap,
              retryThrottling,
              loadBalancingConfig,
              healthCheckingConfig);
    }

    MethodInfo defaultMethodConfig = null;
    for (Map<String, ?> methodConfig : methodConfigs) {
      MethodInfo info = new MethodInfo(
          methodConfig, retryEnabled, maxRetryAttemptsLimit, maxHedgedAttemptsLimit);

      List<Map<String, ?>> nameList =
          ServiceConfigUtil.getNameListFromMethodConfig(methodConfig);

      if (nameList == null || nameList.isEmpty()) {
        continue;
      }
      for (Map<String, ?> name : nameList) {
        String serviceName = ServiceConfigUtil.getServiceFromName(name);
        String methodName = ServiceConfigUtil.getMethodFromName(name);
        if (Strings.isNullOrEmpty(serviceName)) {
          checkArgument(
              Strings.isNullOrEmpty(methodName), "missing service name for method %s", methodName);
          checkArgument(
              defaultMethodConfig == null,
              "Duplicate default method config in service config %s",
              serviceConfig);
          defaultMethodConfig = info;
        } else if (Strings.isNullOrEmpty(methodName)) {
          // Service scoped config
          checkArgument(
              !serviceMap.containsKey(serviceName), "Duplicate service %s", serviceName);
          serviceMap.put(serviceName, info);
        } else {
          // Method scoped config
          String fullMethodName = MethodDescriptor.generateFullMethodName(serviceName, methodName);
          checkArgument(
              !serviceMethodMap.containsKey(fullMethodName),
              "Duplicate method name %s",
              fullMethodName);
          serviceMethodMap.put(fullMethodName, info);
        }
      }
    }

    return
        new ManagedChannelServiceConfig(
            defaultMethodConfig,
            serviceMethodMap,
            serviceMap,
            retryThrottling,
            loadBalancingConfig,
            healthCheckingConfig);
  }

  @Nullable
  Map<String, ?> getHealthCheckingConfig() {
    return healthCheckingConfig;
  }

  /**
   * Used as a fallback per-RPC config supplier when the attributes value of {@link
   * InternalConfigSelector#KEY} is not available. Returns {@code null} if there is no method
   * config in this service config.
   */
  @Nullable
  InternalConfigSelector getDefaultConfigSelector() {
    if (serviceMap.isEmpty() && serviceMethodMap.isEmpty() && defaultMethodConfig == null) {
      return null;
    }
    return new ServiceConfigConvertedSelector(this);
  }

  @VisibleForTesting
  @Nullable
  Object getLoadBalancingConfig() {
    return loadBalancingConfig;
  }

  @Nullable
  Throttle getRetryThrottling() {
    return retryThrottling;
  }

  @Nullable
  MethodInfo getMethodConfig(MethodDescriptor<?, ?> method) {
    MethodInfo methodInfo = serviceMethodMap.get(method.getFullMethodName());
    if (methodInfo == null) {
      String serviceName = method.getServiceName();
      methodInfo = serviceMap.get(serviceName);
    }
    if (methodInfo == null) {
      methodInfo = defaultMethodConfig;
    }
    return methodInfo;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    ManagedChannelServiceConfig that = (ManagedChannelServiceConfig) o;
    return Objects.equal(serviceMethodMap, that.serviceMethodMap)
        && Objects.equal(serviceMap, that.serviceMap)
        && Objects.equal(retryThrottling, that.retryThrottling)
        && Objects.equal(loadBalancingConfig, that.loadBalancingConfig);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(serviceMethodMap, serviceMap, retryThrottling, loadBalancingConfig);
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
        .add("serviceMethodMap", serviceMethodMap)
        .add("serviceMap", serviceMap)
        .add("retryThrottling", retryThrottling)
        .add("loadBalancingConfig", loadBalancingConfig)
        .toString();
  }

  /**
   * Equivalent of MethodConfig from a ServiceConfig with restrictions from Channel setting.
   */
  static final class MethodInfo {
    static final CallOptions.Key<MethodInfo> KEY =
        CallOptions.Key.create("io.grpc.internal.ManagedChannelServiceConfig.MethodInfo");

    // TODO(carl-mastrangelo): add getters for these fields and make them private.
    final Long timeoutNanos;
    final Boolean waitForReady;
    final Integer maxInboundMessageSize;
    final Integer maxOutboundMessageSize;
    final RetryPolicy retryPolicy;
    final HedgingPolicy hedgingPolicy;

    /**
     * Constructor.
     *
     * @param retryEnabled when false, the argument maxRetryAttemptsLimit will have no effect.
     */
    MethodInfo(
        Map<String, ?> methodConfig, boolean retryEnabled, int maxRetryAttemptsLimit,
        int maxHedgedAttemptsLimit) {
      timeoutNanos = ServiceConfigUtil.getTimeoutFromMethodConfig(methodConfig);
      waitForReady = ServiceConfigUtil.getWaitForReadyFromMethodConfig(methodConfig);
      maxInboundMessageSize =
          ServiceConfigUtil.getMaxResponseMessageBytesFromMethodConfig(methodConfig);
      if (maxInboundMessageSize != null) {
        checkArgument(
            maxInboundMessageSize >= 0,
            "maxInboundMessageSize %s exceeds bounds", maxInboundMessageSize);
      }
      maxOutboundMessageSize =
          ServiceConfigUtil.getMaxRequestMessageBytesFromMethodConfig(methodConfig);
      if (maxOutboundMessageSize != null) {
        checkArgument(
            maxOutboundMessageSize >= 0,
            "maxOutboundMessageSize %s exceeds bounds", maxOutboundMessageSize);
      }

      Map<String, ?> retryPolicyMap =
          retryEnabled ? ServiceConfigUtil.getRetryPolicyFromMethodConfig(methodConfig) : null;
      retryPolicy = retryPolicyMap == null
          ? null : retryPolicy(retryPolicyMap, maxRetryAttemptsLimit);

      Map<String, ?> hedgingPolicyMap =
          retryEnabled ? ServiceConfigUtil.getHedgingPolicyFromMethodConfig(methodConfig) : null;
      hedgingPolicy = hedgingPolicyMap == null
          ? null : hedgingPolicy(hedgingPolicyMap, maxHedgedAttemptsLimit);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(
          timeoutNanos,
          waitForReady,
          maxInboundMessageSize,
          maxOutboundMessageSize,
          retryPolicy,
          hedgingPolicy);
    }

    @Override
    public boolean equals(Object other) {
      if (!(other instanceof MethodInfo)) {
        return false;
      }
      MethodInfo that = (MethodInfo) other;
      return Objects.equal(this.timeoutNanos, that.timeoutNanos)
          && Objects.equal(this.waitForReady, that.waitForReady)
          && Objects.equal(this.maxInboundMessageSize, that.maxInboundMessageSize)
          && Objects.equal(this.maxOutboundMessageSize, that.maxOutboundMessageSize)
          && Objects.equal(this.retryPolicy, that.retryPolicy)
          && Objects.equal(this.hedgingPolicy, that.hedgingPolicy);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("timeoutNanos", timeoutNanos)
          .add("waitForReady", waitForReady)
          .add("maxInboundMessageSize", maxInboundMessageSize)
          .add("maxOutboundMessageSize", maxOutboundMessageSize)
          .add("retryPolicy", retryPolicy)
          .add("hedgingPolicy", hedgingPolicy)
          .toString();
    }

    private static RetryPolicy retryPolicy(Map<String, ?> retryPolicy, int maxAttemptsLimit) {
      int maxAttempts = checkNotNull(
          ServiceConfigUtil.getMaxAttemptsFromRetryPolicy(retryPolicy),
          "maxAttempts cannot be empty");
      checkArgument(maxAttempts >= 2, "maxAttempts must be greater than 1: %s", maxAttempts);
      maxAttempts = Math.min(maxAttempts, maxAttemptsLimit);

      long initialBackoffNanos = checkNotNull(
          ServiceConfigUtil.getInitialBackoffNanosFromRetryPolicy(retryPolicy),
          "initialBackoff cannot be empty");
      checkArgument(
          initialBackoffNanos > 0,
          "initialBackoffNanos must be greater than 0: %s",
          initialBackoffNanos);

      long maxBackoffNanos = checkNotNull(
          ServiceConfigUtil.getMaxBackoffNanosFromRetryPolicy(retryPolicy),
          "maxBackoff cannot be empty");
      checkArgument(
          maxBackoffNanos > 0, "maxBackoff must be greater than 0: %s", maxBackoffNanos);

      double backoffMultiplier = checkNotNull(
          ServiceConfigUtil.getBackoffMultiplierFromRetryPolicy(retryPolicy),
          "backoffMultiplier cannot be empty");
      checkArgument(
          backoffMultiplier > 0,
          "backoffMultiplier must be greater than 0: %s",
          backoffMultiplier);

      return new RetryPolicy(
          maxAttempts, initialBackoffNanos, maxBackoffNanos, backoffMultiplier,
          ServiceConfigUtil.getRetryableStatusCodesFromRetryPolicy(retryPolicy));
    }

    private static HedgingPolicy hedgingPolicy(
        Map<String, ?> hedgingPolicy, int maxAttemptsLimit) {
      int maxAttempts = checkNotNull(
          ServiceConfigUtil.getMaxAttemptsFromHedgingPolicy(hedgingPolicy),
          "maxAttempts cannot be empty");
      checkArgument(maxAttempts >= 2, "maxAttempts must be greater than 1: %s", maxAttempts);
      maxAttempts = Math.min(maxAttempts, maxAttemptsLimit);

      long hedgingDelayNanos = checkNotNull(
          ServiceConfigUtil.getHedgingDelayNanosFromHedgingPolicy(hedgingPolicy),
          "hedgingDelay cannot be empty");
      checkArgument(
          hedgingDelayNanos >= 0, "hedgingDelay must not be negative: %s", hedgingDelayNanos);

      return new HedgingPolicy(
          maxAttempts, hedgingDelayNanos,
          ServiceConfigUtil.getNonFatalStatusCodesFromHedgingPolicy(hedgingPolicy));
    }
  }

  static final class ServiceConfigConvertedSelector extends InternalConfigSelector {

    final ManagedChannelServiceConfig config;

    /** Converts the service config to config selector. */
    private ServiceConfigConvertedSelector(ManagedChannelServiceConfig config) {
      this.config = config;
    }

    @Override
    public Result selectConfig(PickSubchannelArgs args) {
      return Result.newBuilder()
          .setConfig(config)
          .build();
    }
  }
}
