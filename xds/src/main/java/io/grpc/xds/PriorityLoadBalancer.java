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
import io.grpc.xds.PriorityLoadBalancerProvider.PriorityLbConfig;
import io.grpc.xds.XdsLogger.XdsLogLevel;
import io.grpc.xds.XdsSubchannelPickers.ErrorPicker;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

/** Load balancer for priority policy. */
final class PriorityLoadBalancer extends LoadBalancer {
  private final Helper helper;
  private final SynchronizationContext syncContext;
  private final ScheduledExecutorService executor;
  private final XdsLogger logger;

  // Includes all active and deactivated children. Mutable. New entries are only added from priority
  // 0 up to the selected priority. An entry is only deleted 15 minutes after the its deactivation.
  private final Map<String, ChildLbState> children = new HashMap<>();

  // Following fields are only null initially.
  private ResolvedAddresses resolvedAddresses;
  private List<String> priorityNames;
  private Map<String, Integer> priorityNameToIndex;
  private ConnectivityState currentConnectivityState;
  private SubchannelPicker currentPicker;

  PriorityLoadBalancer(Helper helper) {
    this.helper = checkNotNull(helper, "helper");
    syncContext = helper.getSynchronizationContext();
    executor = helper.getScheduledExecutorService();
    InternalLogId logId = InternalLogId.allocate("priority-lb", helper.getAuthority());
    logger = XdsLogger.withLogId(logId);
    logger.log(XdsLogLevel.INFO, "Created");
  }

  @Override
  public void handleResolvedAddresses(ResolvedAddresses resolvedAddresses) {
    logger.log(XdsLogLevel.DEBUG, "Received resolution result: {0}", resolvedAddresses);
    this.resolvedAddresses = resolvedAddresses;
    PriorityLbConfig config = (PriorityLbConfig) resolvedAddresses.getLoadBalancingPolicyConfig();
    checkNotNull(config, "missing priority lb config");
    priorityNames = config.priorities;
    Map<String, Integer> pToI = new HashMap<>();
    for (int i = 0; i < priorityNames.size(); i++) {
      pToI.put(priorityNames.get(i), i);
    }
    priorityNameToIndex = Collections.unmodifiableMap(pToI);
    for (String priority : children.keySet()) {
      if (!priorityNameToIndex.containsKey(priority)) {
        children.get(priority).deactivate();
      }
    }
    for (String priority : priorityNames) {
      if (children.containsKey(priority)) {
        children.get(priority).updateResolvedAddresses();
      }
    }
    // Not to report connecting in case a pending priority bumps up on top of the current READY
    // priority.
    tryNextPriority(false);
  }

  @Override
  public void handleNameResolutionError(Status error) {
    logger.log(XdsLogLevel.WARNING, "Received name resolution error: {0}", error);
    boolean gotoTransientFailure = true;
    for (ChildLbState child : children.values()) {
      if (priorityNames.contains(child.priority)) {
        child.lb.handleNameResolutionError(error);
        gotoTransientFailure = false;
      }
    }
    if (gotoTransientFailure) {
      updateOverallState(TRANSIENT_FAILURE, new ErrorPicker(error));
    }
  }

  @Override
  public void shutdown() {
    logger.log(XdsLogLevel.INFO, "Shutdown");
    for (ChildLbState child : children.values()) {
      child.tearDown();
    }
  }

  private void tryNextPriority(boolean reportConnecting) {
    for (int i = 0; i < priorityNames.size(); i++) {
      String priority = priorityNames.get(i);
      if (!children.containsKey(priority)) {
        ChildLbState child = new ChildLbState(priority);
        children.put(priority, child);
        child.updateResolvedAddresses();
        updateOverallState(CONNECTING, BUFFER_PICKER);
        return; // Give priority i time to connect.
      }
      ChildLbState child = children.get(priority);
      child.reactivate();
      if (child.connectivityState.equals(READY) || child.connectivityState.equals(IDLE)) {
        logger.log(XdsLogLevel.DEBUG, "Shifted to priority {0}", priority);
        updateOverallState(child.connectivityState, child.picker);
        for (int j = i + 1; j < priorityNames.size(); j++) {
          String p = priorityNames.get(j);
          if (children.containsKey(p)) {
            children.get(p).deactivate();
          }
        }
        return;
      }
      if (child.failOverTimer != null && child.failOverTimer.isPending()) {
        if (reportConnecting) {
          updateOverallState(CONNECTING, BUFFER_PICKER);
        }
        return; // Give priority i time to connect.
      }
    }
    // TODO(zdapeng): Include error details of each priority.
    logger.log(XdsLogLevel.DEBUG, "All priority failed");
    updateOverallState(TRANSIENT_FAILURE, new ErrorPicker(Status.UNAVAILABLE));
  }

  private void updateOverallState(ConnectivityState state, SubchannelPicker picker) {
    if (!state.equals(currentConnectivityState) || !picker.equals(currentPicker)) {
      currentConnectivityState = state;
      currentPicker = picker;
      helper.updateBalancingState(state, picker);
    }
  }

  private final class ChildLbState {
    final String priority;
    final ChildHelper childHelper;
    final GracefulSwitchLoadBalancer lb;
    // Timer to fail over to the next priority if not connected in 10 sec. Scheduled only once at
    // child initialization.
    final ScheduledHandle failOverTimer;
    // Timer to delay shutdown and deletion of the priority. Scheduled whenever the child is
    // deactivated.
    @Nullable ScheduledHandle deletionTimer;
    @Nullable String policy;
    ConnectivityState connectivityState = CONNECTING;
    SubchannelPicker picker = BUFFER_PICKER;

    ChildLbState(final String priority) {
      this.priority = priority;
      childHelper = new ChildHelper();
      lb = new GracefulSwitchLoadBalancer(childHelper);

      class FailOverTask implements Runnable {
        @Override
        public void run() {
          if (deletionTimer != null && deletionTimer.isPending()) {
            // The child is deactivated.
            return;
          }
          logger.log(XdsLogLevel.DEBUG, "Priority {0} failed over to next", priority);
          tryNextPriority(true);
        }
      }

      failOverTimer = syncContext.schedule(new FailOverTask(), 10, TimeUnit.SECONDS, executor);
      logger.log(XdsLogLevel.DEBUG, "Priority created: {0}", priority);
    }

    /**
     * Called when the child becomes a priority that is or appears before the first READY one in the
     * {@code priorities} list, due to either config update or balancing state update.
     */
    void reactivate() {
      if (deletionTimer != null && deletionTimer.isPending()) {
        deletionTimer.cancel();
        logger.log(XdsLogLevel.DEBUG, "Priority reactivated: {0}", priority);
      }
    }

    /**
     * Called when either the child is removed by config update, or a higher priority becomes READY.
     */
    void deactivate() {
      if (deletionTimer != null && deletionTimer.isPending()) {
        return;
      }

      class DeletionTask implements Runnable {
        @Override
        public void run() {
          tearDown();
          children.remove(priority);
        }
      }

      deletionTimer = syncContext.schedule(new DeletionTask(), 15, TimeUnit.MINUTES, executor);
      logger.log(XdsLogLevel.DEBUG, "Priority deactivated: {0}", priority);
    }

    void tearDown() {
      if (failOverTimer.isPending()) {
        failOverTimer.cancel();
      }
      if (deletionTimer != null && deletionTimer.isPending()) {
        deletionTimer.cancel();
      }
      lb.shutdown();
      logger.log(XdsLogLevel.DEBUG, "Priority deleted: {0}", priority);
    }

    /**
     * Called either when the child is just created and in this case updated with the cached {@code
     * resolvedAddresses}, or when priority lb receives a new resolved addresses while the child
     * already exists.
     */
    void updateResolvedAddresses() {
      final ResolvedAddresses addresses = resolvedAddresses;
      syncContext.execute(
          new Runnable() {
            @Override
            public void run() {
              PriorityLbConfig config = (PriorityLbConfig) addresses.getLoadBalancingPolicyConfig();
              PolicySelection childPolicySelection = config.childConfigs.get(priority);
              LoadBalancerProvider lbProvider = childPolicySelection.getProvider();
              String newPolicy = lbProvider.getPolicyName();
              if (!newPolicy.equals(policy)) {
                policy = newPolicy;
                lb.switchTo(lbProvider);
              }
              lb.handleResolvedAddresses(
                  addresses
                      .toBuilder()
                      .setAddresses(AddressFilter.filter(addresses.getAddresses(), priority))
                      .setLoadBalancingPolicyConfig(childPolicySelection.getConfig())
                      .build());
            }
          });
    }

    final class ChildHelper extends ForwardingLoadBalancerHelper {
      @Override
      public void updateBalancingState(ConnectivityState newState, SubchannelPicker newPicker) {
        connectivityState = newState;
        picker = newPicker;
        if (deletionTimer != null && deletionTimer.isPending()) {
          return;
        }
        if (failOverTimer.isPending()) {
          if (newState.equals(READY) || newState.equals(TRANSIENT_FAILURE)) {
            failOverTimer.cancel();
          }
        }
        tryNextPriority(true);
      }

      @Override
      protected Helper delegate() {
        return helper;
      }
    }
  }
}
