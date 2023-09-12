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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import io.grpc.InternalLogId;
import io.grpc.LoadBalancerProvider;
import io.grpc.Status;
import io.grpc.SynchronizationContext;
import io.grpc.SynchronizationContext.ScheduledHandle;
import io.grpc.internal.ServiceConfigUtil.PolicySelection;
import io.grpc.util.MultiChildLoadBalancer;
import io.grpc.xds.ClusterManagerLoadBalancerProvider.ClusterManagerConfig;
import io.grpc.xds.XdsLogger.XdsLogLevel;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

/**
 * The top-level load balancing policy.
 */
class ClusterManagerLoadBalancer extends MultiChildLoadBalancer {

  @VisibleForTesting
  public static final int DELAYED_CHILD_DELETION_TIME_MINUTES = 15;
  protected final SynchronizationContext syncContext;
  private final ScheduledExecutorService timeService;
  private final XdsLogger logger;

  ClusterManagerLoadBalancer(Helper helper) {
    super(helper);
    this.syncContext = checkNotNull(helper.getSynchronizationContext(), "syncContext");
    this.timeService = checkNotNull(helper.getScheduledExecutorService(), "timeService");
    logger = XdsLogger.withLogId(
        InternalLogId.allocate("cluster_manager-lb", helper.getAuthority()));

    logger.log(XdsLogLevel.INFO, "Created");
  }

  @Override
  protected ResolvedAddresses getChildAddresses(Object key, ResolvedAddresses resolvedAddresses,
      Object childConfig) {
    return resolvedAddresses.toBuilder().setLoadBalancingPolicyConfig(childConfig).build();
  }

  @Override
  protected Map<Object, ChildLbState> createChildLbMap(ResolvedAddresses resolvedAddresses) {
    ClusterManagerConfig config = (ClusterManagerConfig)
        resolvedAddresses.getLoadBalancingPolicyConfig();
    Map<Object, ChildLbState> newChildPolicies = new HashMap<>();
    if (config != null) {
      for (Entry<String, PolicySelection> entry : config.childPolicies.entrySet()) {
        ChildLbState child = getChildLbState(entry.getKey());
        if (child == null) {
          child = new ClusterManagerLbState(entry.getKey(),
              entry.getValue().getProvider(), entry.getValue().getConfig(), getInitialPicker());
        }
        newChildPolicies.put(entry.getKey(), child);
      }
    }
    logger.log(
        XdsLogLevel.INFO,
        "Received cluster_manager lb config: child names={0}", newChildPolicies.keySet());
    return newChildPolicies;
  }

  @Override
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

  private class ClusterManagerLbState extends ChildLbState {
    @Nullable
    ScheduledHandle deletionTimer;

    public ClusterManagerLbState(Object key, LoadBalancerProvider policyProvider,
        Object childConfig, SubchannelPicker initialPicker) {
      super(key, policyProvider, childConfig, initialPicker);
    }

    @Override
    protected void shutdown() {
      if (deletionTimer != null && deletionTimer.isPending()) {
        deletionTimer.cancel();
      }
      super.shutdown();
    }

    @Override
    protected void reactivate(LoadBalancerProvider policyProvider) {
      if (deletionTimer != null && deletionTimer.isPending()) {
        deletionTimer.cancel();
        logger.log(XdsLogLevel.DEBUG, "Child balancer {0} reactivated", getKey());
      }

      super.reactivate(policyProvider);
    }

    @Override
    protected void deactivate() {
      if (isDeactivated()) {
        return;
      }

      class DeletionTask implements Runnable {

        @Override
        public void run() {
          shutdown();
          removeChild(getKey());
        }
      }

      deletionTimer =
          syncContext.schedule(
              new DeletionTask(),
              DELAYED_CHILD_DELETION_TIME_MINUTES,
              TimeUnit.MINUTES,
              timeService);
      setDeactivated();
      logger.log(XdsLogLevel.DEBUG, "Child balancer {0} deactivated", getKey());
    }

  }
}
