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

import static com.google.common.base.Preconditions.checkNotNull;
import static io.grpc.ConnectivityState.CONNECTING;
import static io.grpc.ConnectivityState.IDLE;
import static io.grpc.ConnectivityState.READY;
import static io.grpc.ConnectivityState.TRANSIENT_FAILURE;
import static io.grpc.xds.XdsSubchannelPickers.BUFFER_PICKER;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import io.grpc.ConnectivityState;
import io.grpc.InternalLogId;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancerRegistry;
import io.grpc.Status;
import io.grpc.util.ForwardingLoadBalancerHelper;
import io.grpc.util.GracefulSwitchLoadBalancer;
import io.grpc.xds.WeightedRandomPicker.WeightedChildPicker;
import io.grpc.xds.WeightedRandomPicker.WeightedPickerFactory;
import io.grpc.xds.WeightedTargetLoadBalancerProvider.WeightedPolicySeclection;
import io.grpc.xds.WeightedTargetLoadBalancerProvider.WeightedTargetConfig;
import io.grpc.xds.XdsLogger.XdsLogLevel;
import io.grpc.xds.XdsSubchannelPickers.ErrorPicker;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/** Load balancer for weighted_target policy. */
final class WeightedTargetLoadBalancer extends LoadBalancer {

  private final XdsLogger logger;
  private final LoadBalancerRegistry lbRegistry;
  private final Map<String, GracefulSwitchLoadBalancer> childBalancers = new HashMap<>();
  private final Map<String, ChildHelper> childHelpers = new HashMap<>();
  private final Helper helper;
  private final WeightedPickerFactory weightedPickerFactory;

  private Map<String, WeightedPolicySeclection> targets = ImmutableMap.of();

  WeightedTargetLoadBalancer(Helper helper, LoadBalancerRegistry lbRegistry) {
    this(
        checkNotNull(helper, "helper"),
        checkNotNull(lbRegistry, "lbRegistry"),
        WeightedRandomPicker.RANDOM_PICKER_FACTORY);
  }

  @VisibleForTesting
  WeightedTargetLoadBalancer(
      Helper helper, LoadBalancerRegistry lbRegistry, WeightedPickerFactory weightedPickerFactory) {
    this.helper = helper;
    this.lbRegistry = lbRegistry;
    this.weightedPickerFactory = weightedPickerFactory;
    logger = XdsLogger.withLogId(
        InternalLogId.allocate("weighted-target-lb", helper.getAuthority()));
    logger.log(XdsLogLevel.INFO, "Created");
  }

  @Override
  public void handleResolvedAddresses(ResolvedAddresses resolvedAddresses) {
    logger.log(XdsLogLevel.DEBUG, "Received resolution result: {0}", resolvedAddresses);
    Object lbConfig = resolvedAddresses.getLoadBalancingPolicyConfig();
    checkNotNull(lbConfig, "missing weighted_target lb config");

    WeightedTargetConfig weightedTargetConfig = (WeightedTargetConfig) lbConfig;
    Map<String, WeightedPolicySeclection> newTargets = weightedTargetConfig.targets;

    for (String targetName : newTargets.keySet()) {
      WeightedPolicySeclection weightedChildLbConfig = newTargets.get(targetName);
      if (!targets.containsKey(targetName)) {
        ChildHelper childHelper = new ChildHelper();
        GracefulSwitchLoadBalancer childBalancer = new GracefulSwitchLoadBalancer(childHelper);
        childBalancer.switchTo(lbRegistry.getProvider(weightedChildLbConfig.policyName));
        childHelpers.put(targetName, childHelper);
        childBalancers.put(targetName, childBalancer);
      } else if (!weightedChildLbConfig.policyName.equals(targets.get(targetName).policyName)) {
        childBalancers.get(targetName)
            .switchTo(lbRegistry.getProvider(weightedChildLbConfig.policyName));
      }
    }

    targets = newTargets;

    for (String targetName : targets.keySet()) {
      childBalancers.get(targetName).handleResolvedAddresses(
          resolvedAddresses.toBuilder()
              .setLoadBalancingPolicyConfig(targets.get(targetName).config)
              .build());
    }

    // Cleanup removed targets.
    // TODO(zdapeng): cache removed target for 15 minutes.
    for (String targetName : childBalancers.keySet()) {
      if (!targets.containsKey(targetName)) {
        childBalancers.get(targetName).shutdown();
      }
    }
    childBalancers.keySet().retainAll(targets.keySet());
    childHelpers.keySet().retainAll(targets.keySet());
  }

  @Override
  public void handleNameResolutionError(Status error) {
    logger.log(XdsLogLevel.WARNING, "Received name resolution error: {0}", error);
    if (childBalancers.isEmpty()) {
      helper.updateBalancingState(TRANSIENT_FAILURE, new ErrorPicker(error));
    }
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
    logger.log(XdsLogLevel.INFO, "Shutdown");
    for (LoadBalancer childBalancer : childBalancers.values()) {
      childBalancer.shutdown();
    }
  }

  private void updateOverallBalancingState() {
    List<WeightedChildPicker> childPickers = new ArrayList<>();

    ConnectivityState overallState = null;
    for (String name : targets.keySet()) {
      ChildHelper childHelper = childHelpers.get(name);
      ConnectivityState childState = childHelper.currentState;
      overallState = aggregateState(overallState, childState);
      if (READY == childState) {
        int weight = targets.get(name).weight;
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
  private ConnectivityState aggregateState(
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

  private final class ChildHelper extends ForwardingLoadBalancerHelper {
    ConnectivityState currentState = CONNECTING;
    SubchannelPicker currentPicker = BUFFER_PICKER;

    @Override
    public void updateBalancingState(ConnectivityState newState, SubchannelPicker newPicker) {
      currentState = newState;
      currentPicker = newPicker;
      updateOverallBalancingState();
    }

    @Override
    protected Helper delegate() {
      return helper;
    }
  }
}
