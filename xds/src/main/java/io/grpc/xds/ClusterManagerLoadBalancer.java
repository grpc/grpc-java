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
import io.grpc.ConnectivityState;
import io.grpc.InternalLogId;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancerProvider;
import io.grpc.Status;
import io.grpc.SynchronizationContext;
import io.grpc.SynchronizationContext.ScheduledHandle;
import io.grpc.internal.ServiceConfigUtil.PolicySelection;
import io.grpc.util.ForwardingLoadBalancerHelper;
import io.grpc.util.GracefulSwitchLoadBalancer;
import io.grpc.xds.ClusterManagerLoadBalancerProvider.ClusterManagerConfig;
import io.grpc.xds.XdsLogger.XdsLogLevel;
import io.grpc.xds.XdsSubchannelPickers.ErrorPicker;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

/**
 * The top-level load balancing policy.
 */
class ClusterManagerLoadBalancer extends LoadBalancer {

  @VisibleForTesting
  static final int DELAYED_CHILD_DELETION_TIME_MINUTES = 15;

  private final Map<String, ChildLbState> childLbStates = new HashMap<>();
  private final Helper helper;
  private final SynchronizationContext syncContext;
  private final ScheduledExecutorService timeService;
  private final XdsLogger logger;

  ClusterManagerLoadBalancer(Helper helper) {
    this.helper = checkNotNull(helper, "helper");
    this.syncContext = checkNotNull(helper.getSynchronizationContext(), "syncContext");
    this.timeService = checkNotNull(helper.getScheduledExecutorService(), "timeService");
    logger = XdsLogger.withLogId(
        InternalLogId.allocate("cluster_manager-lb", helper.getAuthority()));
    logger.log(XdsLogLevel.INFO, "Created");
  }

  @Override
  public void handleResolvedAddresses(ResolvedAddresses resolvedAddresses) {
    logger.log(XdsLogLevel.DEBUG, "Received resolution result: {0}", resolvedAddresses);
    ClusterManagerConfig config = (ClusterManagerConfig)
        resolvedAddresses.getLoadBalancingPolicyConfig();
    Map<String, PolicySelection> newChildPolicies = config.childPolicies;
    logger.log(
        XdsLogLevel.INFO,
        "Received cluster_manager lb config: child names={0}", newChildPolicies.keySet());
    for (Map.Entry<String, PolicySelection> entry : newChildPolicies.entrySet()) {
      final String name = entry.getKey();
      LoadBalancerProvider childPolicyProvider = entry.getValue().getProvider();
      Object childConfig = entry.getValue().getConfig();
      if (!childLbStates.containsKey(name)) {
        childLbStates.put(name, new ChildLbState(name, childPolicyProvider));
      } else {
        childLbStates.get(name).reactivate(childPolicyProvider);
      }
      LoadBalancer childLb = childLbStates.get(name).lb;
      ResolvedAddresses childAddresses =
          resolvedAddresses.toBuilder().setLoadBalancingPolicyConfig(childConfig).build();
      childLb.handleResolvedAddresses(childAddresses);
    }
    for (String name : childLbStates.keySet()) {
      if (!newChildPolicies.containsKey(name)) {
        childLbStates.get(name).deactivate();
      }
    }
    // Must update channel picker before return so that new RPCs will not be routed to deleted
    // clusters and resolver can remove them in service config.
    updateOverallBalancingState();
  }

  @Override
  public void handleNameResolutionError(Status error) {
    logger.log(XdsLogLevel.WARNING, "Received name resolution error: {0}", error);
    boolean gotoTransientFailure = true;
    for (ChildLbState state : childLbStates.values()) {
      if (!state.deactivated) {
        gotoTransientFailure = false;
        state.lb.handleNameResolutionError(error);
      }
    }
    if (gotoTransientFailure) {
      helper.updateBalancingState(TRANSIENT_FAILURE, new ErrorPicker(error));
    }
  }

  @Override
  public boolean canHandleEmptyAddressListFromNameResolution() {
    return true;
  }

  @Override
  public void shutdown() {
    logger.log(XdsLogLevel.INFO, "Shutdown");
    for (ChildLbState state : childLbStates.values()) {
      state.shutdown();
    }
  }

  private void updateOverallBalancingState() {
    ConnectivityState overallState = null;
    final Map<String, SubchannelPicker> childPickers = new HashMap<>();
    for (ChildLbState childLbState : childLbStates.values()) {
      if (childLbState.deactivated) {
        continue;
      }
      childPickers.put(childLbState.name, childLbState.currentPicker);
      overallState = aggregateState(overallState, childLbState.currentState);
    }
    if (overallState != null) {
      SubchannelPicker picker = new SubchannelPicker() {
        @Override
        public PickResult pickSubchannel(PickSubchannelArgs args) {
          String clusterName =
              args.getCallOptions().getOption(XdsNameResolver.CLUSTER_SELECTION_KEY);
          SubchannelPicker delegate = childPickers.get(clusterName);
          if (delegate == null) {
            return
                PickResult.withError(
                    Status.UNAVAILABLE.withDescription("Unable to find cluster " + clusterName));
          }
          return delegate.pickSubchannel(args);
        }
      };
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

  private final class ChildLbState {
    private final String name;
    private final GracefulSwitchLoadBalancer lb;
    private LoadBalancerProvider policyProvider;
    private ConnectivityState currentState = CONNECTING;
    private SubchannelPicker currentPicker = BUFFER_PICKER;
    private boolean deactivated;
    @Nullable
    ScheduledHandle deletionTimer;

    ChildLbState(String name, LoadBalancerProvider policyProvider) {
      this.name = name;
      this.policyProvider = policyProvider;
      lb = new GracefulSwitchLoadBalancer(new ChildLbStateHelper());
      lb.switchTo(policyProvider);
    }

    void deactivate() {
      if (deactivated) {
        return;
      }

      class DeletionTask implements Runnable {
        @Override
        public void run() {
          shutdown();
          childLbStates.remove(name);
        }
      }

      deletionTimer =
          syncContext.schedule(
              new DeletionTask(),
              DELAYED_CHILD_DELETION_TIME_MINUTES,
              TimeUnit.MINUTES,
              timeService);
      deactivated = true;
      logger.log(XdsLogLevel.DEBUG, "Child balancer {0} deactivated", name);
    }

    void reactivate(LoadBalancerProvider policyProvider) {
      if (deletionTimer != null && deletionTimer.isPending()) {
        deletionTimer.cancel();
        deactivated = false;
        logger.log(XdsLogLevel.DEBUG, "Child balancer {0} reactivated", name);
      }
      if (!this.policyProvider.getPolicyName().equals(policyProvider.getPolicyName())) {
        logger.log(
            XdsLogLevel.DEBUG,
            "Child balancer {0} switching policy from {1} to {2}",
            name, this.policyProvider.getPolicyName(), policyProvider.getPolicyName());
        lb.switchTo(policyProvider);
        this.policyProvider = policyProvider;
      }
    }

    void shutdown() {
      deactivated = true;
      if (deletionTimer != null && deletionTimer.isPending()) {
        deletionTimer.cancel();
      }
      lb.shutdown();
      logger.log(XdsLogLevel.DEBUG, "Child balancer {0} deleted", name);
    }

    private final class ChildLbStateHelper extends ForwardingLoadBalancerHelper {

      @Override
      public void updateBalancingState(final ConnectivityState newState,
          final SubchannelPicker newPicker) {
        syncContext.execute(new Runnable() {
          @Override
          public void run() {
            currentState = newState;
            currentPicker = newPicker;
            // Subchannel picker and state are saved, but will only be propagated to the channel
            // when the child instance exits deactivated state.
            if (!deactivated) {
              updateOverallBalancingState();
            }
          }
        });
      }

      @Override
      protected Helper delegate() {
        return helper;
      }
    }
  }
}
