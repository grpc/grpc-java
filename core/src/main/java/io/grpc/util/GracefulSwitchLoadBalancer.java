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
import static io.grpc.ConnectivityState.READY;

import com.google.common.annotations.VisibleForTesting;
import io.grpc.ConnectivityState;
import io.grpc.ConnectivityStateInfo;
import io.grpc.Internal;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancerProvider;
import io.grpc.Status;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;

/**
 * A load balancer that gracefully swaps to a new lb policy. If the channel is currently in a state
 * other than READY, the new policy will be swapped into place immediately.  Otherwise, the channel
 * will keep using the old policy until the new policy reports READY or the old policy exits READY.
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
  private LoadBalancer currentLb = NOOP_BALANCER;
  private LoadBalancer pendingLb = NOOP_BALANCER;
  @Nullable
  private String currentPolicyName;
  @Nullable
  private String pendingPolicyName;
  private boolean isReady;
  private ConnectivityState pendingState;
  private SubchannelPicker pendingPicker;

  public GracefulSwitchLoadBalancer(Helper helper) {
    this.helper = checkNotNull(helper, "helper");
  }

  /** Gracefully switch to a new load balancing policy. */
  public void switchTo(LoadBalancerProvider newLbProvider) {
    checkNotNull(newLbProvider, "newLbProvider");

    String newPolicyName = newLbProvider.getPolicyName();
    if (newPolicyName.equals(pendingPolicyName)) {
      return;
    }
    pendingLb.shutdown();
    pendingLb = NOOP_BALANCER;
    pendingPolicyName = null;
    pendingState = ConnectivityState.IDLE;
    pendingPicker = BUFFER_PICKER;

    if (newPolicyName.equals(currentPolicyName)) {
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
          checkState(isReady, "there is pending lb while current lb has been out of ready");
          pendingState = newState;
          pendingPicker = newPicker;
          if (newState == READY) {
            swap();
          }
        } else if (lb == currentLb) {
          isReady = newState == READY;
          if (!isReady && pendingLb != NOOP_BALANCER) { // current policy exits READY, swap
            swap();
          } else {
            helper.updateBalancingState(newState, newPicker);
          }
        }
      }
    }

    PendingHelper pendingHelper = new PendingHelper();
    pendingHelper.lb = newLbProvider.newLoadBalancer(pendingHelper);
    pendingLb = pendingHelper.lb;
    pendingPolicyName = newPolicyName;
    if (!isReady) { // if the old policy is not READY at the moment, swap to the new one right now
      swap();
    }
  }

  private void swap() {
    currentLb.shutdown();
    currentLb = pendingLb;
    currentPolicyName = pendingPolicyName;
    pendingLb = NOOP_BALANCER;
    pendingPolicyName = null;
    helper.updateBalancingState(pendingState, pendingPicker);
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
