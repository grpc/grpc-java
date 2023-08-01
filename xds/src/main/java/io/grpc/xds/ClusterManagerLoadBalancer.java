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

import static io.grpc.ConnectivityState.CONNECTING;
import static io.grpc.ConnectivityState.IDLE;
import static io.grpc.ConnectivityState.READY;
import static io.grpc.ConnectivityState.TRANSIENT_FAILURE;
import static io.grpc.xds.XdsSubchannelPickers.BUFFER_PICKER;

import com.google.common.base.MoreObjects;
import io.grpc.ConnectivityState;
import io.grpc.InternalLogId;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancerProvider;
import io.grpc.Status;
import io.grpc.SynchronizationContext.ScheduledHandle;
import io.grpc.internal.ServiceConfigUtil.PolicySelection;
import io.grpc.util.ForwardingLoadBalancerHelper;
import io.grpc.util.GracefulSwitchLoadBalancer;
import io.grpc.util.MultiChildLoadBalancer;
import io.grpc.xds.ClusterManagerLoadBalancerProvider.ClusterManagerConfig;
import io.grpc.xds.XdsLogger.XdsLogLevel;
import io.grpc.xds.XdsSubchannelPickers.ErrorPicker;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

/**
 * The top-level load balancing policy.
 */
class ClusterManagerLoadBalancer extends MultiChildLoadBalancer {

  private final XdsLogger logger;

  ClusterManagerLoadBalancer(Helper helper) {
    super(helper);
    logger = XdsLogger.withLogId(
        InternalLogId.allocate("cluster_manager-lb", helper.getAuthority()));
    logger.log(XdsLogLevel.INFO, "Created");
  }

  @Override
  protected SubchannelPicker getInitialPicker() {
    return BUFFER_PICKER;
  }

  @Override
  protected SubchannelPicker getErrorPicker(Status error) {
    return new ErrorPicker(error);
  }

  @Override
  protected Map<Object, PolicySelection> getPolicySelectionMap(
      ResolvedAddresses resolvedAddresses) {
    ClusterManagerConfig config = (ClusterManagerConfig)
        resolvedAddresses.getLoadBalancingPolicyConfig();
    Map<Object, PolicySelection> newChildPolicies = new HashMap<>(config.childPolicies);
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
}
