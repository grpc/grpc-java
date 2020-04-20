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

package io.grpc.rls.internal;

import static com.google.common.base.Preconditions.checkNotNull;

import io.grpc.ConnectivityState;
import io.grpc.ConnectivityStateInfo;
import io.grpc.LoadBalancer.CreateSubchannelArgs;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancer.Subchannel;
import io.grpc.LoadBalancer.SubchannelPicker;
import io.grpc.LoadBalancer.SubchannelStateListener;
import io.grpc.rls.internal.ChildLoadBalancerHelper.ChildLoadBalancerHelperProvider;
import io.grpc.rls.internal.LbPolicyConfiguration.ChildPolicyWrapper;
import io.grpc.util.ForwardingLoadBalancerHelper;
import io.grpc.util.ForwardingSubchannel;
import javax.annotation.Nullable;

/**
 * A delegating {@link io.grpc.LoadBalancer.Helper} maintains status of {@link ChildPolicyWrapper}
 * when {@link Subchannel} status changed. This helper is used between child policy and parent
 * load-balancer where each picker in child policy is governed by a governing picker (RlsPicker).
 * The governing picker will be reported back to the parent load-balancer.
 */
final class ChildPolicyReportingHelper extends ForwardingLoadBalancerHelper {

  private final ChildLoadBalancerHelper delegate;
  private final ChildPolicyWrapper childPolicyWrapper;
  @Nullable
  private final ChildLbStatusListener listener;

  ChildPolicyReportingHelper(
      ChildLoadBalancerHelperProvider childHelperProvider,
      ChildPolicyWrapper childPolicyWrapper) {
    this(childHelperProvider, childPolicyWrapper, null);
  }

  ChildPolicyReportingHelper(
      ChildLoadBalancerHelperProvider childHelperProvider,
      ChildPolicyWrapper childPolicyWrapper,
      @Nullable ChildLbStatusListener listener) {
    this.childPolicyWrapper = checkNotNull(childPolicyWrapper, "childPolicyWrapper");
    checkNotNull(childHelperProvider, "childHelperProvider");
    this.delegate = childHelperProvider.forTarget(childPolicyWrapper.getTarget());
    this.listener = listener;
  }

  @Override
  protected Helper delegate() {
    return delegate;
  }

  @Override
  public void updateBalancingState(ConnectivityState newState, SubchannelPicker newPicker) {
    childPolicyWrapper.setPicker(newPicker);
    super.updateBalancingState(newState, newPicker);
    if (listener != null) {
      listener.onStatusChanged(newState);
    }
  }

  @Override
  public Subchannel createSubchannel(CreateSubchannelArgs args) {
    final Subchannel subchannel = super.createSubchannel(args);
    return new ForwardingSubchannel() {
      @Override
      protected Subchannel delegate() {
        return subchannel;
      }

      @Override
      public void start(final SubchannelStateListener listener) {
        super.start(new SubchannelStateListener() {
          @Override
          public void onSubchannelState(ConnectivityStateInfo newState) {
            childPolicyWrapper.setConnectivityStateInfo(newState);
            listener.onSubchannelState(newState);
          }
        });
      }
    };
  }

  /** Listener for child lb status change events. */
  interface ChildLbStatusListener {

    /** Notifies when child lb status changes. */
    void onStatusChanged(ConnectivityState newState);
  }
}
