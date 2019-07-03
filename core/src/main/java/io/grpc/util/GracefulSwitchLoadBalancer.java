/*
 * Copyright 2019 The gRPC Authors
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
import static io.grpc.ConnectivityState.READY;

import io.grpc.ConnectivityState;
import io.grpc.ConnectivityStateInfo;
import io.grpc.Internal;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancerProvider;
import io.grpc.Status;
import javax.annotation.concurrent.NotThreadSafe;

/**
 * A forwarding load balancer and holder of currentLb and pendingLb. The pendingLb's helper will not
 * update balancing state until a subchannel managed by the pendingLB is READY, whence the currentLb
 * shuts down and the pendingLb becomes current.
 */
@Internal
@NotThreadSafe // Must be accessed in SynchronizationContext
public final class GracefulSwitchLoadBalancer extends ForwardingLoadBalancer {
  private static final LoadBalancer NOOP_BALANCER = new LoadBalancer() {
    @Override
    public void handleNameResolutionError(Status error) {}

    @Override
    public void shutdown() {}
  };

  private LoadBalancer delegate = NOOP_BALANCER;
  private LoadBalancer currentLb = NOOP_BALANCER;
  private LoadBalancer pendingLb = NOOP_BALANCER;

  /** Gracefully switch to a new load balancing policy. */
  public void switchTo(LoadBalancerProvider newLbProvider, final Helper newHelper) {
    checkNotNull(newLbProvider, "newLbProvider");
    checkNotNull(newHelper, "newHelper");

    if (currentLb == NOOP_BALANCER) {
      delegate = newLbProvider.newLoadBalancer(newHelper);
      currentLb = delegate;
      return;
    }

    class PendingHelper extends ForwardingLoadBalancerHelper {
      LoadBalancer lb;

      @Override
      protected Helper delegate() {
        return newHelper;
      }

      @Override
      public void updateBalancingState(ConnectivityState newState, SubchannelPicker newPicker) {
        if (newState == READY && pendingLb == lb) {
          currentLb.shutdown();
          currentLb = lb;
          pendingLb = NOOP_BALANCER;
        }

        if (currentLb == lb) {
          newHelper.updateBalancingState(newState, newPicker);
        }
      }
    }

    PendingHelper pendingHelper = new PendingHelper();
    delegate = newLbProvider.newLoadBalancer(pendingHelper);
    pendingHelper.lb = delegate;
    pendingLb.shutdown();
    pendingLb = delegate;
  }

  @Override
  protected LoadBalancer delegate() {
    return delegate;
  }

  @Deprecated
  @Override
  public void handleSubchannelState(Subchannel subchannel, ConnectivityStateInfo stateInfo) {
    pendingLb.handleSubchannelState(subchannel, stateInfo);
    currentLb.handleSubchannelState(subchannel, stateInfo);
  }

  @Override
  public void requestConnection() {
    pendingLb.requestConnection();
    currentLb.requestConnection();
  }

  @Override
  public void shutdown() {
    pendingLb.shutdown();
    currentLb.shutdown();
  }
}
