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

import com.google.common.collect.ImmutableMap;
import io.grpc.Attributes;
import io.grpc.ConnectivityState;
import io.grpc.InternalLogId;
import io.grpc.LoadBalancer;
import io.grpc.Status;
import io.grpc.util.ForwardingLoadBalancerHelper;
import io.grpc.util.GracefulSwitchLoadBalancer;
import io.grpc.xds.WeightedRandomPicker.WeightedChildPicker;
import io.grpc.xds.WeightedTargetLoadBalancerProvider.WeightedPolicySelection;
import io.grpc.xds.WeightedTargetLoadBalancerProvider.WeightedTargetConfig;
import io.grpc.xds.client.XdsLogger;
import io.grpc.xds.client.XdsLogger.XdsLogLevel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/** Load balancer for weighted_target policy. */
final class WeightedTargetLoadBalancer extends LoadBalancer {
  public static final Attributes.Key<String> CHILD_NAME =
      Attributes.Key.create("io.grpc.xds.WeightedTargetLoadBalancer.CHILD_NAME");

  private final XdsLogger logger;
  private final Map<String, GracefulSwitchLoadBalancer> childBalancers = new HashMap<>();
  private final Map<String, ChildHelper> childHelpers = new HashMap<>();
  private final Helper helper;

  private Map<String, WeightedPolicySelection> targets = ImmutableMap.of();
  // Set to true if currently in the process of handling resolved addresses.
  private boolean resolvingAddresses;

  WeightedTargetLoadBalancer(Helper helper) {
    this.helper = checkNotNull(helper, "helper");
    logger = XdsLogger.withLogId(
        InternalLogId.allocate("weighted-target-lb", helper.getAuthority()));
    logger.log(XdsLogLevel.INFO, "Created");
  }

  @Override
  public Status acceptResolvedAddresses(ResolvedAddresses resolvedAddresses) {
    try {
      resolvingAddresses = true;
      return acceptResolvedAddressesInternal(resolvedAddresses);
    } finally {
      resolvingAddresses = false;
    }
  }

  public Status acceptResolvedAddressesInternal(ResolvedAddresses resolvedAddresses) {
    logger.log(XdsLogLevel.DEBUG, "Received resolution result: {0}", resolvedAddresses);
    Object lbConfig = resolvedAddresses.getLoadBalancingPolicyConfig();
    checkNotNull(lbConfig, "missing weighted_target lb config");
    WeightedTargetConfig weightedTargetConfig = (WeightedTargetConfig) lbConfig;
    Map<String, WeightedPolicySelection> newTargets = weightedTargetConfig.targets;
    for (String targetName : newTargets.keySet()) {
      if (!targets.containsKey(targetName)) {
        ChildHelper childHelper = new ChildHelper(targetName);
        GracefulSwitchLoadBalancer childBalancer = new GracefulSwitchLoadBalancer(childHelper);
        childHelpers.put(targetName, childHelper);
        childBalancers.put(targetName, childBalancer);
      }
    }
    targets = newTargets;
    Status status = Status.OK;
    for (String targetName : targets.keySet()) {
      Status newStatus = childBalancers.get(targetName).acceptResolvedAddresses(
          resolvedAddresses.toBuilder()
              .setAddresses(AddressFilter.filter(resolvedAddresses.getAddresses(), targetName))
              .setLoadBalancingPolicyConfig(targets.get(targetName).childConfig)
              .setAttributes(resolvedAddresses.getAttributes().toBuilder()
                .set(CHILD_NAME, targetName)
                .build())
              .build());
      if (!newStatus.isOk()) {
        status = newStatus;
      }
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
    updateOverallBalancingState();
    return status;
  }

  @Override
  public void handleNameResolutionError(Status error) {
    logger.log(XdsLogLevel.WARNING, "Received name resolution error: {0}", error);
    if (childBalancers.isEmpty()) {
      helper.updateBalancingState(
          TRANSIENT_FAILURE, new FixedResultPicker(PickResult.withError(error)));
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
    childBalancers.clear();
  }

  private void updateOverallBalancingState() {
    List<WeightedChildPicker> childPickers = new ArrayList<>();

    ConnectivityState overallState = null;
    List<WeightedChildPicker> errorPickers = new ArrayList<>();
    for (String name : targets.keySet()) {
      ChildHelper childHelper = childHelpers.get(name);
      ConnectivityState childState = childHelper.currentState;
      overallState = aggregateState(overallState, childState);
      int weight = targets.get(name).weight;
      if (READY == childState) {
        childPickers.add(new WeightedChildPicker(weight, childHelper.currentPicker));
      } else if (TRANSIENT_FAILURE == childState) {
        errorPickers.add(new WeightedChildPicker(weight, childHelper.currentPicker));
      }
    }

    SubchannelPicker picker;
    if (childPickers.isEmpty()) {
      if (overallState == TRANSIENT_FAILURE) {
        picker = new WeightedRandomPicker(errorPickers);
      } else {
        picker = new FixedResultPicker(PickResult.withNoResult());
      }
    } else {
      picker = new WeightedRandomPicker(childPickers);
    }

    if (overallState != null) {
      helper.updateBalancingState(overallState, picker);
    }
  }

  @Nullable
  private static ConnectivityState aggregateState(
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
    String name;
    ConnectivityState currentState = CONNECTING;
    SubchannelPicker currentPicker = new FixedResultPicker(PickResult.withNoResult());

    private ChildHelper(String name) {
      this.name = name;
    }

    @Override
    public void updateBalancingState(final ConnectivityState newState,
        final SubchannelPicker newPicker) {
      currentState = newState;
      currentPicker = newPicker;

      // If we are already in the process of resolving addresses, the overall balancing state
      // will be updated at the end of it, and we don't need to trigger that update here.
      if (!resolvingAddresses && childBalancers.containsKey(name)) {
        updateOverallBalancingState();
      }
    }

    @Override
    protected Helper delegate() {
      return helper;
    }
  }
}
