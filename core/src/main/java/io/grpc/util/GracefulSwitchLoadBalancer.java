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
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;

/**
 * A forwarding load balancer and holder of currentLb and pendingLb. The pendingLb's helper will not
 * update balancing state until a subchannel managed by the pendingLB is READY, whence the pendingLb
 * becomes current.
 */
@Internal
@NotThreadSafe // Must be accessed in SynchronizationContext
public final class GracefulSwitchLoadBalancer extends ForwardingLoadBalancer {

  @Nullable // never null after init()
  private LoadBalancer delegate;
  @Nullable // never null after init()
  private LoadBalancer currentLb;
  @Nullable // set to null once the new lb becomes current
  private LoadBalancer pendingLb;

  private GracefulSwitchLoadBalancer() {}

  /** Initializes an instance. */
  public static GracefulSwitchLoadBalancer init(LoadBalancerProvider lbProvider, Helper helper) {
    GracefulSwitchLoadBalancer instance = new GracefulSwitchLoadBalancer();
    instance.switchTo(lbProvider, helper);
    return instance;
  }

  /** Gracefully switch to a new balancer. */
  public void switchTo(LoadBalancerProvider lbProvider, final Helper helper) {
    checkNotNull(lbProvider, "lbProvider");
    checkNotNull(helper, "helper");

    if (currentLb == null) {
      delegate = lbProvider.newLoadBalancer(helper);
      currentLb = delegate;
      return;
    }

    if (pendingLb != null) {
      pendingLb.shutdown();
    }

    class PendingHelper extends ForwardingLoadBalancerHelper {
      LoadBalancer lb;

      @Override
      protected Helper delegate() {
        return helper;
      }

      @Override
      public void updateBalancingState(
          ConnectivityState newState, SubchannelPicker newPicker) {
        if (newState == READY && pendingLb == lb) {
          currentLb.shutdown();
          currentLb = lb;
          pendingLb = null;
        }

        if (currentLb == lb) {
          helper.updateBalancingState(newState, newPicker);
        }
      }
    }

    PendingHelper pendingHelper = new PendingHelper();
    delegate = lbProvider.newLoadBalancer(pendingHelper);
    pendingHelper.lb = delegate;
    pendingLb = delegate;
  }

  @Override
  protected LoadBalancer delegate() {
    return delegate;
  }

  @Override
  public void shutdown() {
    currentLb.shutdown();
    if (pendingLb != null) {
      pendingLb.shutdown();
    }
  }
}
