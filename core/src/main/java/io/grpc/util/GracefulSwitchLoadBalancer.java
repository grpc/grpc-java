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
import static com.google.common.base.Preconditions.checkState;

import com.google.common.annotations.VisibleForTesting;
import io.grpc.ConnectivityState;
import io.grpc.ConnectivityStateInfo;
import io.grpc.ExperimentalApi;
import io.grpc.LoadBalancer;
import io.grpc.Status;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;

/**
 * A load balancer that gracefully swaps to a new lb policy. If the channel is currently in a state
 * other than READY, the new policy will be swapped into place immediately.  Otherwise, the channel
 * will keep using the old policy until the new policy reports READY or the old policy exits READY.
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/5999")
@NotThreadSafe // Must be accessed in SynchronizationContext
public final class GracefulSwitchLoadBalancer extends ForwardingLoadBalancer {
  private static final LoadBalancer NOOP_BALANCER = new LoadBalancer() {
    @Override
    public void handleNameResolutionError(Status error) {}

    @Override
    public void shutdown() {}
  };

  @VisibleForTesting
  static final SubchannelPicker BUFFER_PICKER = new SubchannelPicker() {
    @Override
    public PickResult pickSubchannel(PickSubchannelArgs args) {
      return PickResult.withNoResult();
    }

    @Override
    public String toString() {
      return "BUFFER_PICKER";
    }
  };

  private final Helper helper;

  // While the new policy is not fully switched on, the pendingLb is handling new updates from name
  // resolver, and the currentLb is updating channel state and picker for the given helper.
  // The current fields are guaranteed to be set after the initial swapTo().
  // The pending fields are cleared when it becomes current.
  @Nullable private LoadBalancer.Factory currentBalancerFactory;
  private LoadBalancer currentLb = NOOP_BALANCER;
  @Nullable private LoadBalancer.Factory pendingBalancerFactory;
  private LoadBalancer pendingLb = NOOP_BALANCER;
  private ConnectivityState pendingState;
  private SubchannelPicker pendingPicker;

  private boolean currentLbIsReady;

  public GracefulSwitchLoadBalancer(Helper helper) {
    this.helper = checkNotNull(helper, "helper");
  }

  /**
   * Gracefully switch to a new policy defined by the given factory, if the given factory isn't
   * equal to the current one.
   */
  public void switchTo(LoadBalancer.Factory newBalancerFactory) {
    checkNotNull(newBalancerFactory, "newBalancerFactory");

    if (newBalancerFactory.equals(pendingBalancerFactory)) {
      return;
    }
    pendingLb.shutdown();
    pendingLb = NOOP_BALANCER;
    pendingBalancerFactory = null;
    pendingState = ConnectivityState.CONNECTING;
    pendingPicker = BUFFER_PICKER;

    if (newBalancerFactory.equals(currentBalancerFactory)) {
      return;
    }

    class PendingHelper extends ForwardingLoadBalancerHelper {
      LoadBalancer lb;

      @Override
      protected Helper delegate() {
        return helper;
      }

      @Override
      public void updateBalancingState(ConnectivityState newState, SubchannelPicker newPicker) {
        if (lb == pendingLb) {
          checkState(currentLbIsReady, "there's pending lb while current lb has been out of READY");
          pendingState = newState;
          pendingPicker = newPicker;
          if (newState == ConnectivityState.READY) {
            swap();
          }
        } else if (lb == currentLb) {
          currentLbIsReady = newState == ConnectivityState.READY;
          if (!currentLbIsReady && pendingLb != NOOP_BALANCER) {
            swap(); // current policy exits READY, so swap
          } else {
            helper.updateBalancingState(newState, newPicker);
          }
        }
      }
    }

    PendingHelper pendingHelper = new PendingHelper();
    pendingHelper.lb = newBalancerFactory.newLoadBalancer(pendingHelper);
    pendingLb = pendingHelper.lb;
    pendingBalancerFactory = newBalancerFactory;
    if (!currentLbIsReady) {
      swap(); // the old policy is not READY at the moment, so swap to the new one right now
    }
  }

  private void swap() {
    helper.updateBalancingState(pendingState, pendingPicker);
    currentLb.shutdown();
    currentLb = pendingLb;
    currentBalancerFactory = pendingBalancerFactory;
    pendingLb = NOOP_BALANCER;
    pendingBalancerFactory = null;
  }

  @Override
  protected LoadBalancer delegate() {
    return pendingLb == NOOP_BALANCER ? currentLb : pendingLb;
  }

  @Override
  @Deprecated
  public void handleSubchannelState(
      Subchannel subchannel, ConnectivityStateInfo stateInfo) {
    throw new UnsupportedOperationException(
        "handleSubchannelState() is not supported by " + this.getClass().getName());
  }

  @Override
  public void shutdown() {
    pendingLb.shutdown();
    currentLb.shutdown();
  }
}
