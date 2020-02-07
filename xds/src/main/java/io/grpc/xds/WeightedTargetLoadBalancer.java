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
import static io.grpc.ConnectivityState.CONNECTING;
import static io.grpc.ConnectivityState.IDLE;
import static io.grpc.ConnectivityState.READY;
import static io.grpc.ConnectivityState.TRANSIENT_FAILURE;
import static io.grpc.xds.XdsSubchannelPickers.BUFFER_PICKER;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import io.grpc.ChannelLogger;
import io.grpc.ChannelLogger.ChannelLogLevel;
import io.grpc.ConnectivityState;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancerProvider;
import io.grpc.LoadBalancerRegistry;
import io.grpc.Status;
import io.grpc.util.ForwardingLoadBalancerHelper;
import io.grpc.xds.RandomWeightedPicker.WeightedChildPicker;
import io.grpc.xds.RandomWeightedPicker.WeightedPickerFactory;
import io.grpc.xds.XdsSubchannelPickers.ErrorPicker;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nullable;

/**
 * Load balancer for weighted_target policy.
 */
final class WeightedTargetLoadBalancer extends LoadBalancer {

  private final ChannelLogger channelLogger;
  private final Map<WeightedLbConfig, LoadBalancer> childBalancers;
  private final Map<WeightedLbConfig, ChildHelper> childHelpers;

  /**
   * Constructs a WeightedTargetLoadBalancer with the given weighted list of child balancer configs.
   * The list must not be empty and must not contain duplicate lb configs.
   */
  WeightedTargetLoadBalancer(Helper helper, List<WeightedLbConfig> weightedLbConfigs) {
    this(
        checkNotNull(helper, "helper"),
        checkNotNull(weightedLbConfigs, "weightedLbConfigs"),
        LoadBalancerRegistry.getDefaultRegistry(),
        WeightedPickerFactory.RANDOM_PICKER_FACTORY);
  }

  @VisibleForTesting
  WeightedTargetLoadBalancer(
      Helper helper, List<WeightedLbConfig> weightedLbConfigs, LoadBalancerRegistry lbRegistry,
      WeightedPickerFactory weightedPickerFactory) {
    WeightedTargetLbHelper weightedTargetLbHelper =
        new WeightedTargetLbHelper(helper, weightedPickerFactory);
    channelLogger = helper.getChannelLogger();
    checkArgument(!weightedLbConfigs.isEmpty(), "No child lb configs provided");
    ImmutableMap.Builder<WeightedLbConfig, LoadBalancer> childBalancersBuilder
        = ImmutableMap.builder();
    ImmutableMap.Builder<WeightedLbConfig, ChildHelper> childHelpersBuilder
        = ImmutableMap.builder();
    for (WeightedLbConfig weightedLbConfig : weightedLbConfigs) {
      ChildHelper childHelper = new ChildHelper(weightedTargetLbHelper);
      LoadBalancerProvider loadBalancerProvider =
          lbRegistry.getProvider(weightedLbConfig.policyName);
      LoadBalancer childBalancer = loadBalancerProvider.newLoadBalancer(childHelper);
      childBalancersBuilder.put(weightedLbConfig, childBalancer);
      childHelpersBuilder.put(weightedLbConfig, childHelper);
    }
    childBalancers = childBalancersBuilder.build();
    childHelpers = childHelpersBuilder.build();
  }

  @Override
  public void handleResolvedAddresses(ResolvedAddresses resolvedAddresses) {
    channelLogger.log(ChannelLogLevel.DEBUG, "Received ResolvedAddresses {0}", resolvedAddresses);
    // Each child balancer will update with its own child policy.
    for (Map.Entry<WeightedLbConfig, LoadBalancer> configToBalancer : childBalancers.entrySet()) {
      configToBalancer.getValue().handleResolvedAddresses(
          resolvedAddresses.toBuilder()
              .setLoadBalancingPolicyConfig(configToBalancer.getKey().config)
              .build());
    }
  }

  @Override
  public void handleNameResolutionError(Status error) {
    channelLogger.log(ChannelLogLevel.ERROR, "Name resolution error: {0}", error);
    for (LoadBalancer childBalancer : childBalancers.values()) {
      childBalancer.handleNameResolutionError(error);
    }
  }

  @Override
  public boolean canHandleEmptyAddressListFromNameResolution() {
    return true;
  }

  @Override
  public void shutdown() {
    channelLogger.log(ChannelLogLevel.DEBUG, "weighted_target load balancer is shutting down");
    for (LoadBalancer childBalancer : childBalancers.values()) {
      childBalancer.shutdown();
    }
  }

  static final class WeightedLbConfig {

    final int weight;
    final String policyName;
    final Object config;

    WeightedLbConfig(int weight, String policyName, Object config) {
      checkArgument(weight > 0, "Weight must be greater than zero.");
      this.weight = weight;
      this.policyName = checkNotNull(policyName, "policyName");
      this.config = checkNotNull(config, "config");
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      WeightedLbConfig weightedLbConfig = (WeightedLbConfig) o;
      return weight == weightedLbConfig.weight
          && policyName.equals(weightedLbConfig.policyName)
          && config.equals(weightedLbConfig.config);
    }

    @Override
    public int hashCode() {
      return Objects.hash(weight, policyName, config);
    }
  }

  private final class WeightedTargetLbHelper extends ForwardingLoadBalancerHelper {

    final Helper helper;
    final WeightedPickerFactory weightedPickerFactory;

    WeightedTargetLbHelper(Helper helper, WeightedPickerFactory weightedPickerFactory) {
      this.helper = helper;
      this.weightedPickerFactory = weightedPickerFactory;
    }

    @Override
    protected Helper delegate() {
      return helper;
    }

    void updateBalancingState() {
      List<WeightedChildPicker> childPickers = new ArrayList<>();

      ConnectivityState overallState = null;
      for (Map.Entry<WeightedLbConfig, ChildHelper> configToHelper : childHelpers.entrySet()) {
        int weight = configToHelper.getKey().weight;
        ChildHelper childHelper = configToHelper.getValue();
        ConnectivityState childState = childHelper.currentState;
        overallState = aggregateState(overallState, childState);
        if (READY == childState) {
          childPickers.add(new WeightedChildPicker(weight, childHelper.currentPicker));
        }
      }

      SubchannelPicker picker;
      if (childPickers.isEmpty()) {
        if (overallState == TRANSIENT_FAILURE) {
          picker = new ErrorPicker(Status.UNAVAILABLE); // TODO: more details in status
        } else {
          picker = XdsSubchannelPickers.BUFFER_PICKER;
        }
      } else {
        picker = weightedPickerFactory.picker(childPickers);
      }

      if (overallState != null) {
        helper.updateBalancingState(overallState, picker);
      }
    }

    @Nullable
    ConnectivityState aggregateState(
        @Nullable ConnectivityState overallState, ConnectivityState childState) {
      if (overallState == null) {
        return childState;
      }
      if (overallState == READY || childState == READY) {
        return READY;
      }
      if (overallState == CONNECTING || childState == CONNECTING) {
        return CONNECTING;
      }
      if (overallState == IDLE || childState == IDLE) {
        return IDLE;
      }
      return overallState;
    }
  }

  private static final class ChildHelper extends ForwardingLoadBalancerHelper {

    final WeightedTargetLbHelper parentHelper;

    ConnectivityState currentState = CONNECTING;
    SubchannelPicker currentPicker = BUFFER_PICKER;

    ChildHelper(WeightedTargetLbHelper parentHelper) {
      this.parentHelper = parentHelper;
    }

    @Override
    public void updateBalancingState(ConnectivityState newState, SubchannelPicker newPicker) {
      currentState = newState;
      currentPicker = newPicker;
      parentHelper.updateBalancingState();
    }

    @Override
    protected Helper delegate() {
      return parentHelper;
    }
  }
}
