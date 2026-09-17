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

import com.google.common.base.MoreObjects;
import io.grpc.ConnectivityState;
import io.grpc.InternalLogId;
import io.grpc.LoadBalancer;
import io.grpc.Status;
import io.grpc.SynchronizationContext;
import io.grpc.SynchronizationContext.ScheduledHandle;
import io.grpc.internal.GrpcUtil;
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
  // Reported by a child before its policy has produced a picker. It is composed with the numeric
  // priority like any other child picker, so that the delay type does not change (and therefore
  // does not end one delay and start another) once the child reports for the first time.
  private static final SubchannelPicker UNINITIALIZED_CHILD_PICKER = new FixedResultPicker(
      PickResult.withNoResult("connecting", "priority child state uninitialized"));

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
  static boolean enablePriorityLbChildPolicyCache =
      GrpcUtil.getFlag("GRPC_EXPERIMENTAL_ENABLE_PRIORITY_LB_CHILD_POLICY_CACHE", false);

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
          if (enablePriorityLbChildPolicyCache) {
            childLbState.deactivate();
          } else {
            childLbState.tearDown();
            children.remove(priority);
          }
        }
      }
    }
    handlingResolvedAddresses = true;
    for (int i = 0; i < priorityNames.size(); i++) {
      String priority = priorityNames.get(i);
      ChildLbState childLbState = children.get(priority);
      if (childLbState != null) {
        // The position of a priority within the list can change between config updates; keep the
        // numeric priority used for delay reporting in sync before the child can report a picker.
        childLbState.updatePriorityIndex(i);
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
            new ChildLbState(priority, i, priorityConfigs.get(priority).ignoreReresolution);
        children.put(priority, child);
        // Child is created in CONNECTING with pending failOverTimer
        updateOverallState(priority, child.connectivityState, child.picker);
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
      if (child.failOverTimer.isPending()) {
        updateOverallState(priority, child.connectivityState, child.picker);
        return Status.OK; // Give priority i time to connect.
      }
    }
    for (int i = 0; i < priorityNames.size(); i++) {
      String priority = priorityNames.get(i);
      ChildLbState child = children.get(priority);
      if (child.connectivityState.equals(CONNECTING)) {
        updateOverallState(priority, child.connectivityState, child.picker);
        return Status.OK;
      }
    }
    logger.log(XdsLogLevel.DEBUG, "All priority failed");
    String lastPriority = priorityNames.get(priorityNames.size() - 1);
    ChildLbState child = children.get(lastPriority);
    updateOverallState(lastPriority, child.connectivityState, child.picker);
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
    // Zero-based position of this priority within the ordered priority list of the most recent
    // config. This is the numeric priority that gRFC A121 requires to be prepended to the delay
    // type; the priority name cannot be used because it is derived from the (unbounded) xDS
    // cluster name, while the delay type is used as a metric label. If the priority is dropped
    // from the config while the child is cached for reuse, the last known index is retained.
    private int priorityIndex;
    // The picker most recently reported by the child policy, before delay type composition.
    private SubchannelPicker childPicker = UNINITIALIZED_CHILD_PICKER;
    // The picker exposed to the parent. Derived from childPicker, connectivityState and
    // priorityIndex; always updated through buildPicker().
    SubchannelPicker picker;

    ChildLbState(final String priority, int priorityIndex, boolean ignoreReresolution) {
      this.priority = priority;
      this.priorityIndex = priorityIndex;
      picker = buildPicker();
      childHelper = new ChildHelper(ignoreReresolution);
      lb = new GracefulSwitchLoadBalancer(childHelper);
      failOverTimer = syncContext.schedule(new FailOverTask(), 10, TimeUnit.SECONDS, executor);
      logger.log(XdsLogLevel.DEBUG, "Priority created: {0}", priority);
    }

    /** Updates the numeric priority of this child after a config update. */
    void updatePriorityIndex(int newPriorityIndex) {
      if (priorityIndex == newPriorityIndex) {
        return;
      }
      priorityIndex = newPriorityIndex;
      // The numeric priority is part of the delay type, so the exposed picker is stale. The
      // rebuilt picker is not equal to the previous one, so updateOverallState() propagates it.
      picker = buildPicker();
    }

    /** Composes the picker exposed to the parent from the child's most recent picker. */
    private SubchannelPicker buildPicker() {
      if (connectivityState == CONNECTING || connectivityState == IDLE) {
        return new PriorityPicker(childPicker, priorityIndex, priority);
      }
      return childPicker;
    }

    final class FailOverTask implements Runnable {
      @Override
      public void run() {
        if (deletionTimer != null && deletionTimer.isPending()) {
          // The child is deactivated.
          return;
        }
        logger.log(XdsLogLevel.DEBUG, "Priority {0} failed over to next", priority);
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
        ConnectivityState oldState = connectivityState;
        connectivityState = newState;
        childPicker = newPicker;
        picker = buildPicker();

        if (deletionTimer != null && deletionTimer.isPending()) {
          return;
        }
        if (newState.equals(CONNECTING) && !oldState.equals(newState)) {
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

  /**
   * Prepends this policy's numeric priority to the delay type reported by the child policy, per
   * <a href="https://github.com/grpc/proposal/blob/master/A121-rpc-delay-observability.md">gRFC
   * A121</a>. E.g. a child reporting {@code "connecting"} at priority 0 becomes
   * {@code "0:connecting"}, and nested priority policies stack their prefixes, e.g.
   * {@code "0:1:connecting"}.
   *
   * <p>Only the numeric priority is used in the delay type, because the delay type is used as a
   * metric label and must stay low-cardinality. The priority name, which embeds the xDS cluster
   * name, is only reported in the (high-cardinality, tracing-only) delay reason.
   */
  private static final class PriorityPicker extends SubchannelPicker {
    private final SubchannelPicker delegate;
    private final int priorityIndex;
    private final String priorityName;

    PriorityPicker(SubchannelPicker delegate, int priorityIndex, String priorityName) {
      this.delegate = checkNotNull(delegate, "delegate");
      this.priorityIndex = priorityIndex;
      this.priorityName = checkNotNull(priorityName, "priorityName");
    }

    @Override
    public PickResult pickSubchannel(PickSubchannelArgs args) {
      PickResult childResult = delegate.pickSubchannel(args);
      if (!childResult.hasResult() && childResult.getDelayType() != null) {
        String childType = childResult.getDelayType();
        String childReason = childResult.getDelayReason();
        String composedType = priorityIndex + ":" + childType;
        String reason = "waiting on priority " + priorityIndex + " (child '" + priorityName
            + "'): " + (childReason != null ? childReason : childType);
        return PickResult.withNoResult(composedType, reason);
      }
      return childResult;
    }

    @Nullable
    private static PickResult fixedPickResult(SubchannelPicker picker) {
      SubchannelPicker cur = picker;
      while (cur instanceof PriorityPicker) {
        cur = ((PriorityPicker) cur).delegate;
      }
      if (cur instanceof FixedResultPicker) {
        return picker.pickSubchannel(null);
      }
      return null;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      PriorityPicker that = (PriorityPicker) o;
      if (priorityIndex != that.priorityIndex
          || !priorityName.equals(that.priorityName)
          || !delegate.equals(that.delegate)) {
        return false;
      }
      PickResult thisFixed = fixedPickResult(this);
      PickResult thatFixed = fixedPickResult(that);
      if (thisFixed != null && thatFixed != null) {
        return Objects.equals(thisFixed.getDelayType(), thatFixed.getDelayType())
            && Objects.equals(thisFixed.getDelayReason(), thatFixed.getDelayReason());
      }
      return true;
    }

    @Override
    public int hashCode() {
      PickResult fixed = fixedPickResult(this);
      return Objects.hash(
          delegate,
          priorityIndex,
          priorityName,
          fixed != null ? fixed.getDelayType() : null,
          fixed != null ? fixed.getDelayReason() : null);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("delegate", delegate)
          .add("priorityIndex", priorityIndex)
          .add("priorityName", priorityName)
          .toString();
    }
  }
}
