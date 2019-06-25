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
import io.grpc.Internal;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancerProvider;
import io.grpc.Status;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;

/**
 * A forwarding load balancer and holder of currentLb and pendingLb. The pendingLb's helper will not
 * update balancing state until a subchannel managed by the pendingLB is READY, whence the pendingLb
 * becomes to current.
 */
@Internal
@NotThreadSafe // Must be accessed in SynchronizationContext
public final class GracefulLbSwitch extends ForwardingLoadBalancer {

  // never null
  private final LoadBalancer newLb;
  // never null
  private LoadBalancer currentLb;
  @Nullable // set to null once the new lb becomes current
  private LoadBalancer pendingLb;

  /**
   * Constructor. Example usage:
   *
   * <pre>
   *   private GracefulLbSwitch lbSwitch;
   *
   *   void onLbPolicyChange() {
   *     lbSwitch = new GracefulLbSwitch(lbSwitch, newLbProvider, helper);
   *     lbSwitch.handleResolvedAddresses(resolvedAddresses);
   *   }
   * </pre>
   */
  public GracefulLbSwitch(
      @Nullable GracefulLbSwitch previousSwitch, LoadBalancerProvider lbProvider,
      final Helper helper) {
    checkNotNull(lbProvider, "lbProvider");
    checkNotNull(helper, "helper");

    if (previousSwitch == null) {
      newLb = lbProvider.newLoadBalancer(helper);
      currentLb = newLb;
      return;
    }

    if (previousSwitch.pendingLb != null) {
      previousSwitch.pendingLb.shutdown();
    }

    currentLb = previousSwitch.currentLb;

    class PendingHelper extends ForwardingLoadBalancerHelper {

      @Override
      protected Helper delegate() {
        return helper;
      }

      @Override
      public void updateBalancingState(
          ConnectivityState newState, SubchannelPicker newPicker) {
        if (newState == READY) {
          if (pendingLb != null) {
            currentLb.shutdown();
            currentLb = pendingLb;
            pendingLb = null;
          }
        }

        if (pendingLb == null) {
          helper.updateBalancingState(newState, newPicker);
        }
      }
    }

    newLb = lbProvider.newLoadBalancer(new PendingHelper());
    pendingLb = newLb;
  }

  @Override
  protected LoadBalancer delegate() {
    return newLb;
  }

  @Override
  public void shutdown() {
    currentLb.shutdown();
    if (pendingLb != null) {
      pendingLb.shutdown();
    }
  }
}
