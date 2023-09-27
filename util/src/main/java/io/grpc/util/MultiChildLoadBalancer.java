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

package io.grpc.util;

import static com.google.common.base.Preconditions.checkNotNull;
import static io.grpc.ConnectivityState.CONNECTING;
import static io.grpc.ConnectivityState.IDLE;
import static io.grpc.ConnectivityState.READY;
import static io.grpc.ConnectivityState.TRANSIENT_FAILURE;

import com.google.common.annotations.VisibleForTesting;
import io.grpc.ConnectivityState;
import io.grpc.Internal;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancerProvider;
import io.grpc.Status;
import io.grpc.SynchronizationContext;
import io.grpc.SynchronizationContext.ScheduledHandle;
import io.grpc.internal.ServiceConfigUtil.PolicySelection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;

/**
 * A base load balancing policy for those policies which has multiple children such as
 * ClusterManager or the petiole policies.  For internal use only.
 */
@Internal
public abstract class MultiChildLoadBalancer extends LoadBalancer {

  @VisibleForTesting
  public static final int DELAYED_CHILD_DELETION_TIME_MINUTES = 15;
  private static final Logger logger = Logger.getLogger(MultiChildLoadBalancer.class.getName());
  private final Map<Object, ChildLbState> childLbStates = new HashMap<>();
  private final Helper helper;
  protected final SynchronizationContext syncContext;
  private final ScheduledExecutorService timeService;
  // Set to true if currently in the process of handling resolved addresses.
  private boolean resolvingAddresses;

  protected MultiChildLoadBalancer(Helper helper) {
    this.helper = checkNotNull(helper, "helper");
    this.syncContext = checkNotNull(helper.getSynchronizationContext(), "syncContext");
    this.timeService = checkNotNull(helper.getScheduledExecutorService(), "timeService");
    logger.log(Level.FINE, "Created");
  }

  protected SubchannelPicker getInitialPicker() {
    return EMPTY_PICKER;
  }

  protected SubchannelPicker getErrorPicker(Status error)  {
    return new FixedResultPicker(PickResult.withError(error));
  }

  protected abstract Map<Object, PolicySelection> getPolicySelectionMap(
      ResolvedAddresses resolvedAddresses);

  protected abstract SubchannelPicker getSubchannelPicker(
      Map<Object, SubchannelPicker> childPickers);

  @Override
  public boolean acceptResolvedAddresses(ResolvedAddresses resolvedAddresses) {
    try {
      resolvingAddresses = true;
      return acceptResolvedAddressesInternal(resolvedAddresses);
    } finally {
      resolvingAddresses = false;
    }
  }

  private boolean acceptResolvedAddressesInternal(ResolvedAddresses resolvedAddresses) {
    logger.log(Level.FINE, "Received resolution result: {0}", resolvedAddresses);
    Map<Object, PolicySelection> newChildPolicies = getPolicySelectionMap(resolvedAddresses);
    for (Map.Entry<Object, PolicySelection> entry : newChildPolicies.entrySet()) {
      final Object key = entry.getKey();
      LoadBalancerProvider childPolicyProvider = entry.getValue().getProvider();
      Object childConfig = entry.getValue().getConfig();
      if (!childLbStates.containsKey(key)) {
        childLbStates.put(key, new ChildLbState(key, childPolicyProvider, getInitialPicker()));
      } else {
        childLbStates.get(key).reactivate(childPolicyProvider);
      }
      LoadBalancer childLb = childLbStates.get(key).lb;
      ResolvedAddresses childAddresses =
          resolvedAddresses.toBuilder().setLoadBalancingPolicyConfig(childConfig).build();
      childLb.handleResolvedAddresses(childAddresses);
    }
    for (Object key : childLbStates.keySet()) {
      if (!newChildPolicies.containsKey(key)) {
        childLbStates.get(key).deactivate();
      }
    }
    // Must update channel picker before return so that new RPCs will not be routed to deleted
    // clusters and resolver can remove them in service config.
    updateOverallBalancingState();
    return true;
  }

  @Override
  public void handleNameResolutionError(Status error) {
    logger.log(Level.WARNING, "Received name resolution error: {0}", error);
    boolean gotoTransientFailure = true;
    for (ChildLbState state : childLbStates.values()) {
      if (!state.deactivated) {
        gotoTransientFailure = false;
        state.lb.handleNameResolutionError(error);
      }
    }
    if (gotoTransientFailure) {
      helper.updateBalancingState(TRANSIENT_FAILURE, getErrorPicker(error));
    }
  }

  @Override
  public void shutdown() {
    logger.log(Level.INFO, "Shutdown");
    for (ChildLbState state : childLbStates.values()) {
      state.shutdown();
    }
    childLbStates.clear();
  }

  private void updateOverallBalancingState() {
    ConnectivityState overallState = null;
    final Map<Object, SubchannelPicker> childPickers = new HashMap<>();
    for (ChildLbState childLbState : childLbStates.values()) {
      if (childLbState.deactivated) {
        continue;
      }
      childPickers.put(childLbState.key, childLbState.currentPicker);
      overallState = aggregateState(overallState, childLbState.currentState);
    }
    if (overallState != null) {
      helper.updateBalancingState(overallState, getSubchannelPicker(childPickers));
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

  private final class ChildLbState {
    private final Object key;
    private final GracefulSwitchLoadBalancer lb;
    private LoadBalancerProvider policyProvider;
    private ConnectivityState currentState = CONNECTING;
    private SubchannelPicker currentPicker;
    private boolean deactivated;
    @Nullable
    ScheduledHandle deletionTimer;

    ChildLbState(Object key, LoadBalancerProvider policyProvider, SubchannelPicker initialPicker) {
      this.key = key;
      this.policyProvider = policyProvider;
      lb = new GracefulSwitchLoadBalancer(new ChildLbStateHelper());
      lb.switchTo(policyProvider);
      currentPicker = initialPicker;
    }

    void deactivate() {
      if (deactivated) {
        return;
      }

      class DeletionTask implements Runnable {
        @Override
        public void run() {
          shutdown();
          childLbStates.remove(key);
        }
      }

      deletionTimer =
          syncContext.schedule(
              new DeletionTask(),
              DELAYED_CHILD_DELETION_TIME_MINUTES,
              TimeUnit.MINUTES,
              timeService);
      deactivated = true;
      logger.log(Level.FINE, "Child balancer {0} deactivated", key);
    }

    void reactivate(LoadBalancerProvider policyProvider) {
      if (deletionTimer != null && deletionTimer.isPending()) {
        deletionTimer.cancel();
        deactivated = false;
        logger.log(Level.FINE, "Child balancer {0} reactivated", key);
      }
      if (!this.policyProvider.getPolicyName().equals(policyProvider.getPolicyName())) {
        Object[] objects = {
            key, this.policyProvider.getPolicyName(),policyProvider.getPolicyName()};
        logger.log(Level.FINE, "Child balancer {0} switching policy from {1} to {2}", objects);
        lb.switchTo(policyProvider);
        this.policyProvider = policyProvider;
      }
    }

    void shutdown() {
      if (deletionTimer != null && deletionTimer.isPending()) {
        deletionTimer.cancel();
      }
      lb.shutdown();
      logger.log(Level.FINE, "Child balancer {0} deleted", key);
    }

    private final class ChildLbStateHelper extends ForwardingLoadBalancerHelper {

      @Override
      public void updateBalancingState(final ConnectivityState newState,
          final SubchannelPicker newPicker) {
        // If we are already in the process of resolving addresses, the overall balancing state
        // will be updated at the end of it, and we don't need to trigger that update here.
        if (!childLbStates.containsKey(key)) {
          return;
        }
        // Subchannel picker and state are saved, but will only be propagated to the channel
        // when the child instance exits deactivated state.
        currentState = newState;
        currentPicker = newPicker;
        if (!deactivated && !resolvingAddresses) {
          updateOverallBalancingState();
        }
      }

      @Override
      protected Helper delegate() {
        return helper;
      }
    }
  }
}
