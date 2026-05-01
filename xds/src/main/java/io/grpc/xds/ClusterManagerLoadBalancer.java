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
import static io.grpc.ConnectivityState.TRANSIENT_FAILURE;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import io.grpc.ConnectivityState;
import io.grpc.InternalLogId;
import io.grpc.LoadBalancer;
import io.grpc.Status;
import io.grpc.SynchronizationContext;
import io.grpc.SynchronizationContext.ScheduledHandle;
import io.grpc.util.GracefulSwitchLoadBalancer;
import io.grpc.util.MultiChildLoadBalancer;
import io.grpc.xds.ClusterManagerLoadBalancerProvider.ClusterManagerConfig;
import io.grpc.xds.client.XdsLogger;
import io.grpc.xds.client.XdsLogger.XdsLogLevel;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

/**
 * The top-level load balancing policy for use in XDS.
 * This policy does not immediately delete its children.  Instead, it marks them deactivated
 * and starts a timer for deletion.  If a subsequent address update restores the child, then it is
 * simply reactivated instead of built from scratch.  This is necessary because XDS can frequently
 * remove and then add back a server as machines are rebooted or repurposed for load management.
 *
 * <p>Note that this LB does not automatically reconnect children who go into IDLE status
 */
class ClusterManagerLoadBalancer extends MultiChildLoadBalancer {

  // 15 minutes is long enough for a reboot and the services to restart while not so long that
  // many children are waiting for cleanup.
  @VisibleForTesting
  public static final int DELAYED_CHILD_DELETION_TIME_MINUTES = 15;
  protected final SynchronizationContext syncContext;
  private final ScheduledExecutorService timeService;
  private final XdsLogger logger;
  private ResolvedAddresses lastResolvedAddresses;

  ClusterManagerLoadBalancer(Helper helper) {
    super(helper);
    this.syncContext = checkNotNull(helper.getSynchronizationContext(), "syncContext");
    this.timeService = checkNotNull(helper.getScheduledExecutorService(), "timeService");
    logger = XdsLogger.withLogId(
        InternalLogId.allocate("cluster_manager-lb", helper.getAuthority()));

    logger.log(XdsLogLevel.INFO, "Created");
  }

  @Override
  protected ChildLbState createChildLbState(Object key) {
    return new ClusterManagerLbState(key, GracefulSwitchLoadBalancerFactory.INSTANCE);
  }

  @Override
  protected Map<Object, ResolvedAddresses> createChildAddressesMap(
      ResolvedAddresses resolvedAddresses) {
    lastResolvedAddresses = resolvedAddresses;

    ClusterManagerConfig config = (ClusterManagerConfig)
        resolvedAddresses.getLoadBalancingPolicyConfig();
    logger.log(
        XdsLogLevel.INFO,
        "Received cluster_manager lb config: child names={0}", config.childPolicies.keySet());
    Map<Object, ResolvedAddresses> childAddresses = new HashMap<>();

    // Reactivate children with config; deactivate children without config
    for (ChildLbState rawState : getChildLbStates()) {
      ClusterManagerLbState state = (ClusterManagerLbState) rawState;
      if (config.childPolicies.containsKey(state.getKey())) {
        // Active child
        if (state.deletionTimer != null) {
          state.reactivateChild();
        }
      } else {
        // Inactive child
        if (state.deletionTimer == null) {
          state.deactivateChild();
        }
        if (state.deletionTimer.isPending()) {
          childAddresses.put(state.getKey(), null); // Preserve child, without config update
        }
      }
    }

    for (Map.Entry<String, Object> childPolicy : config.childPolicies.entrySet()) {
      ResolvedAddresses addresses = resolvedAddresses.toBuilder()
          .setLoadBalancingPolicyConfig(childPolicy.getValue())
          .build();
      childAddresses.put(childPolicy.getKey(), addresses);
    }
    return childAddresses;
  }

  /**
   * Using the state of all children will calculate the current connectivity state,
   * update currentConnectivityState, generate a picker and then call
   * {@link Helper#updateBalancingState(ConnectivityState, SubchannelPicker)}.
   */
  @Override
  protected void updateOverallBalancingState() {
    ConnectivityState overallState = null;
    final Map<Object, SubchannelPicker> childPickers = new HashMap<>();
    for (ChildLbState childLbState : getChildLbStates()) {
      if (((ClusterManagerLbState) childLbState).deletionTimer != null) {
        continue;
      }
      childPickers.put(childLbState.getKey(), childLbState.getCurrentPicker());
      overallState = aggregateState(overallState, childLbState.getCurrentState());
    }

    if (overallState != null) {
      getHelper().updateBalancingState(overallState, getSubchannelPicker(childPickers));
      currentConnectivityState = overallState;
    }
  }

  protected SubchannelPicker getSubchannelPicker(Map<Object, SubchannelPicker> childPickers) {
    return new SubchannelPicker() {
      @Override
      public PickResult pickSubchannel(PickSubchannelArgs args) {
        String clusterName =
            args.getCallOptions().getOption(XdsNameResolver.CLUSTER_SELECTION_KEY);
        SubchannelPicker childPicker = childPickers.get(clusterName);
        if (childPicker == null) {
          return
              PickResult.withError(
                  Status.UNAVAILABLE.withDescription("CDS encountered error: unable to find "
                      + "available subchannel for cluster " + clusterName));
        }
        return childPicker.pickSubchannel(args);
      }

      @Override
      public String toString() {
        return MoreObjects.toStringHelper(this).add("pickers", childPickers).toString();
      }
    };
  }

  @Override
  public void handleNameResolutionError(Status error) {
    logger.log(XdsLogLevel.WARNING, "Received name resolution error: {0}", error);
    boolean gotoTransientFailure = true;
    for (ChildLbState state : getChildLbStates()) {
      if (((ClusterManagerLbState) state).deletionTimer == null) {
        gotoTransientFailure = false;
        state.getLb().handleNameResolutionError(error);
      }
    }
    if (gotoTransientFailure) {
      getHelper().updateBalancingState(
          TRANSIENT_FAILURE, new FixedResultPicker(PickResult.withError(error)));
    }
  }

  /**
   * This differs from the base class in the use of the deletion timer.  When it is deactivated,
   * rather than immediately calling shutdown it starts a timer.  If shutdown or reactivate
   * are called before the timer fires, the timer is canceled.  Otherwise, time timer calls shutdown
   * and removes the child from the petiole policy when it is triggered.
   */
  private class ClusterManagerLbState extends ChildLbState {
    @Nullable
    ScheduledHandle deletionTimer;

    public ClusterManagerLbState(Object key, LoadBalancer.Factory policyFactory) {
      super(key, policyFactory);
    }

    @Override
    protected ChildLbStateHelper createChildHelper() {
      return new ClusterManagerChildHelper();
    }

    @Override
    protected void shutdown() {
      if (deletionTimer != null) {
        deletionTimer.cancel();
        deletionTimer = null;
      }
      super.shutdown();
    }

    void reactivateChild() {
      assert deletionTimer != null;
      deletionTimer.cancel();
      deletionTimer = null;
      logger.log(XdsLogLevel.DEBUG, "Child balancer {0} reactivated", getKey());
    }

    void deactivateChild() {
      assert deletionTimer == null;

      class DeletionTask implements Runnable {

        @Override
        public void run() {
          acceptResolvedAddresses(lastResolvedAddresses);
        }
      }

      deletionTimer =
          syncContext.schedule(
              new DeletionTask(),
              DELAYED_CHILD_DELETION_TIME_MINUTES,
              TimeUnit.MINUTES,
              timeService);
      logger.log(XdsLogLevel.DEBUG, "Child balancer {0} deactivated", getKey());
    }

    private class ClusterManagerChildHelper extends ChildLbStateHelper {
      @Override
      public void updateBalancingState(final ConnectivityState newState,
                                       final SubchannelPicker newPicker) {
        if (getCurrentState() == ConnectivityState.SHUTDOWN) {
          return;
        }

        // Subchannel picker and state are saved, but will only be propagated to the channel
        // when the child instance exits deactivated state.
        setCurrentState(newState);
        setCurrentPicker(newPicker);
        // If we are already in the process of resolving addresses, the overall balancing state
        // will be updated at the end of it, and we don't need to trigger that update here.
        if (deletionTimer == null && !resolvingAddresses) {
          updateOverallBalancingState();
        }
      }
    }
  }

  static final class GracefulSwitchLoadBalancerFactory extends LoadBalancer.Factory {
    static final LoadBalancer.Factory INSTANCE = new GracefulSwitchLoadBalancerFactory();

    @Override
    public LoadBalancer newLoadBalancer(LoadBalancer.Helper helper) {
      return new GracefulSwitchLoadBalancer(helper);
    }
  }
}
