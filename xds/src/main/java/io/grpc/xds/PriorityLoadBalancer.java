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

import io.grpc.ConnectivityState;
import io.grpc.InternalLogId;
import io.grpc.LoadBalancer;
import io.grpc.Status;
import io.grpc.SynchronizationContext;
import io.grpc.SynchronizationContext.ScheduledHandle;
import io.grpc.util.ForwardingLoadBalancerHelper;
import io.grpc.util.GracefulSwitchLoadBalancer;
import io.grpc.xds.PriorityLoadBalancerProvider.PriorityLbConfig;
import io.grpc.xds.PriorityLoadBalancerProvider.PriorityLbConfig.PriorityChildConfig;
import io.grpc.xds.client.XdsLogger;
import io.grpc.xds.client.XdsLogger.XdsLogLevel;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

/**
 * Load balancer for priority policy. A <em>priority</em> represents a logical entity within a
 * cluster for load balancing purposes.
 */
final class PriorityLoadBalancer extends LoadBalancer {
  private final Helper helper;
  private final SynchronizationContext syncContext;
  private final ScheduledExecutorService executor;
  private final XdsLogger logger;

  // Includes all active and deactivated children. Mutable. New entries are only added from priority
  // 0 up to the selected priority. An entry is only deleted 15 minutes after its deactivation.
  // Note that calling into a child can cause the child to call back into the LB policy and modify
  // the map.  Therefore copy values before looping over them.
  private final Map<String, ChildLbState> children = new HashMap<>();

  // Following fields are only null initially.
  private ResolvedAddresses resolvedAddresses;
  // List of priority names in order.
  private List<String> priorityNames;
  // Config for each priority.
  private Map<String, PriorityChildConfig> priorityConfigs;
  @Nullable private String currentPriority;
  private ConnectivityState currentConnectivityState;
  private SubchannelPicker currentPicker;
  // Set to true if currently in the process of handling resolved addresses.
  private boolean handlingResolvedAddresses;

  PriorityLoadBalancer(Helper helper) {
    this.helper = checkNotNull(helper, "helper");
    syncContext = helper.getSynchronizationContext();
    executor = helper.getScheduledExecutorService();
    InternalLogId logId = InternalLogId.allocate("priority-lb", helper.getAuthority());
    logger = XdsLogger.withLogId(logId);
    logger.log(XdsLogLevel.INFO, "Created");
  }

  @Override
  public Status acceptResolvedAddresses(ResolvedAddresses resolvedAddresses) {
    logger.log(XdsLogLevel.DEBUG, "Received resolution result: {0}", resolvedAddresses);
    this.resolvedAddresses = resolvedAddresses;
    PriorityLbConfig config = (PriorityLbConfig) resolvedAddresses.getLoadBalancingPolicyConfig();
    checkNotNull(config, "missing priority lb config");
    priorityNames = config.priorities;
    priorityConfigs = config.childConfigs;
    Status status = Status.OK;
    Set<String> prioritySet = new HashSet<>(config.priorities);
    ArrayList<String> childKeys = new ArrayList<>(children.keySet());
    for (String priority : childKeys) {
      if (!prioritySet.contains(priority)) {
        ChildLbState childLbState = children.get(priority);
        if (childLbState != null) {
          childLbState.deactivate();
        }
      }
    }
    handlingResolvedAddresses = true;
    for (String priority : priorityNames) {
      ChildLbState childLbState = children.get(priority);
      if (childLbState != null) {
        Status newStatus = childLbState.updateResolvedAddresses();
        if (!newStatus.isOk()) {
          status = newStatus;
        }
      }
    }
    handlingResolvedAddresses = false;
    Status newStatus = tryNextPriority();
    if (!newStatus.isOk()) {
      status = newStatus;
    }
    return status;
  }

  @Override
  public void handleNameResolutionError(Status error) {
    logger.log(XdsLogLevel.WARNING, "Received name resolution error: {0}", error);
    boolean gotoTransientFailure = true;
    Collection<ChildLbState> childValues = new ArrayList<>(children.values());
    for (ChildLbState child : childValues) {
      if (priorityNames.contains(child.priority)) {
        child.lb.handleNameResolutionError(error);
        gotoTransientFailure = false;
      }
    }
    if (gotoTransientFailure) {
      updateOverallState(
          null, TRANSIENT_FAILURE, new FixedResultPicker(PickResult.withError(error)));
    }
  }

  @Override
  public void shutdown() {
    logger.log(XdsLogLevel.INFO, "Shutdown");
    Collection<ChildLbState> childValues = new ArrayList<>(children.values());
    for (ChildLbState child : childValues) {
      child.tearDown();
    }
    children.clear();
  }

  private Status tryNextPriority() {
    for (int i = 0; i < priorityNames.size(); i++) {
      String priority = priorityNames.get(i);
      if (!children.containsKey(priority)) {
        ChildLbState child =
            new ChildLbState(priority, priorityConfigs.get(priority).ignoreReresolution);
        children.put(priority, child);
        updateOverallState(priority, CONNECTING, new FixedResultPicker(PickResult.withNoResult()));
        // Calling the child's updateResolvedAddresses() can result in tryNextPriority() being
        // called recursively. We need to be sure to be done with processing here before it is
        // called.
        return child.updateResolvedAddresses(); // Give priority i time to connect.
      }
      ChildLbState child = children.get(priority);
      child.reactivate();
      if (child.connectivityState.equals(READY) || child.connectivityState.equals(IDLE)) {
        logger.log(XdsLogLevel.DEBUG, "Shifted to priority {0}", priority);
        updateOverallState(priority, child.connectivityState, child.picker);
        for (int j = i + 1; j < priorityNames.size(); j++) {
          String p = priorityNames.get(j);
          if (children.containsKey(p)) {
            children.get(p).deactivate();
          }
        }
        return Status.OK;
      }
      if (child.failOverTimer != null && child.failOverTimer.isPending()) {
        updateOverallState(priority, child.connectivityState, child.picker);
        return Status.OK; // Give priority i time to connect.
      }
      if (priority.equals(currentPriority) && child.connectivityState != TRANSIENT_FAILURE) {
        // If the current priority is not changed into TRANSIENT_FAILURE, keep using it.
        updateOverallState(priority, child.connectivityState, child.picker);
        return Status.OK;
      }
    }
    // TODO(zdapeng): Include error details of each priority.
    logger.log(XdsLogLevel.DEBUG, "All priority failed");
    String lastPriority = priorityNames.get(priorityNames.size() - 1);
    SubchannelPicker errorPicker = children.get(lastPriority).picker;
    updateOverallState(lastPriority, TRANSIENT_FAILURE, errorPicker);
    return Status.OK;
  }

  private void updateOverallState(
      @Nullable String priority, ConnectivityState state, SubchannelPicker picker) {
    if (!Objects.equals(priority, currentPriority) || !state.equals(currentConnectivityState)
        || !picker.equals(currentPicker)) {
      currentPriority = priority;
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
    ScheduledHandle failOverTimer;
    boolean seenReadyOrIdleSinceTransientFailure = false;
    // Timer to delay shutdown and deletion of the priority. Scheduled whenever the child is
    // deactivated.
    @Nullable ScheduledHandle deletionTimer;
    ConnectivityState connectivityState = CONNECTING;
    SubchannelPicker picker = new FixedResultPicker(PickResult.withNoResult());

    ChildLbState(final String priority, boolean ignoreReresolution) {
      this.priority = priority;
      childHelper = new ChildHelper(ignoreReresolution);
      lb = new GracefulSwitchLoadBalancer(childHelper);
      failOverTimer = syncContext.schedule(new FailOverTask(), 10, TimeUnit.SECONDS, executor);
      logger.log(XdsLogLevel.DEBUG, "Priority created: {0}", priority);
    }

    final class FailOverTask implements Runnable {
      @Override
      public void run() {
        if (deletionTimer != null && deletionTimer.isPending()) {
          // The child is deactivated.
          return;
        }
        picker = new FixedResultPicker(PickResult.withError(
            Status.UNAVAILABLE.withDescription("Connection timeout for priority " + priority)));
        logger.log(XdsLogLevel.DEBUG, "Priority {0} failed over to next", priority);
        currentPriority = null; // reset currentPriority to guarantee failover happen
        Status status = tryNextPriority();
        if (!status.isOk()) {
          // A child had a problem with the addresses/config. Request it to be refreshed
          helper.refreshNameResolution();
        }
      }
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
    Status updateResolvedAddresses() {
      PriorityLbConfig config =
          (PriorityLbConfig) resolvedAddresses.getLoadBalancingPolicyConfig();
      return lb.acceptResolvedAddresses(
          resolvedAddresses.toBuilder()
              .setAddresses(AddressFilter.filter(resolvedAddresses.getAddresses(), priority))
              .setLoadBalancingPolicyConfig(config.childConfigs.get(priority).childConfig)
              .build());
    }

    final class ChildHelper extends ForwardingLoadBalancerHelper {
      private final boolean ignoreReresolution;

      ChildHelper(boolean ignoreReresolution) {
        this.ignoreReresolution = ignoreReresolution;
      }

      @Override
      public void refreshNameResolution() {
        if (!ignoreReresolution) {
          delegate().refreshNameResolution();
        }
      }

      @Override
      public void updateBalancingState(final ConnectivityState newState,
          final SubchannelPicker newPicker) {
        if (!children.containsKey(priority)) {
          return;
        }
        connectivityState = newState;
        picker = newPicker;

        if (deletionTimer != null && deletionTimer.isPending()) {
          return;
        }
        if (newState.equals(CONNECTING)) {
          if (!failOverTimer.isPending() && seenReadyOrIdleSinceTransientFailure) {
            failOverTimer = syncContext.schedule(new FailOverTask(), 10, TimeUnit.SECONDS,
                executor);
          }
        } else if (newState.equals(READY) || newState.equals(IDLE)) {
          seenReadyOrIdleSinceTransientFailure = true;
          failOverTimer.cancel();
        } else if (newState.equals(TRANSIENT_FAILURE)) {
          seenReadyOrIdleSinceTransientFailure = false;
          failOverTimer.cancel();
        }

        // If we are currently handling newly resolved addresses, let's not try to reconfigure as
        // the address handling process will take care of that to provide an atomic config update.
        if (!handlingResolvedAddresses) {
          Status status = tryNextPriority();
          if (!status.isOk()) {
            // A child had a problem with the addresses/config. Request it to be refreshed
            helper.refreshNameResolution();
          }
        }
      }

      @Override
      protected Helper delegate() {
        return helper;
      }
    }
  }
}
