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

package io.grpc.rls;

import static com.google.common.base.Preconditions.checkNotNull;

import io.grpc.ConnectivityState;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancer.SubchannelPicker;
import io.grpc.util.ForwardingLoadBalancerHelper;
import javax.annotation.Nonnull;

/**
 * A delegating {@link Helper} for the child load blanacer. The child load-balancer notifies the
 * higher level load-blancer with aggregated status instead of each individual child load-blanacer's
 * state.
 */
final class ChildLoadBalancerHelper extends ForwardingLoadBalancerHelper {

  private final String target;
  private final Helper rlsHelper;
  private final SubchannelStateManager subchannelStateManager;
  private final SubchannelPicker picker;

  private ChildLoadBalancerHelper(
      String target,
      Helper rlsHelper,
      SubchannelStateManager subchannelStateManager,
      SubchannelPicker picker) {
    this.target = checkNotNull(target, "target");
    this.rlsHelper = checkNotNull(rlsHelper, "rlsHelper");
    this.subchannelStateManager = checkNotNull(subchannelStateManager, "subchannelStateManager");
    this.picker = checkNotNull(picker, "picker");
  }

  @Override
  protected Helper delegate() {
    return rlsHelper;
  }

  /**
   * Updates balancing state from one or more subchannels tracked in the {@link
   * SubchannelStateManager}. The passed picker will be ignored, instead the picker which governs
   * many subchannels/pickers will be reported to the parent load-balancer.
   */
  @Override
  public void updateBalancingState(
      @Nonnull ConnectivityState newState,
      @Nonnull SubchannelPicker unused) {
    subchannelStateManager.updateState(target, newState);
    super.updateBalancingState(subchannelStateManager.getAggregatedState(), picker);
  }

  static final class ChildLoadBalancerHelperProvider {
    private final Helper helper;
    private final SubchannelStateManager subchannelStateManager;
    private final SubchannelPicker picker;

    ChildLoadBalancerHelperProvider(
        Helper helper, SubchannelStateManager subchannelStateManager, SubchannelPicker picker) {
      this.helper = checkNotNull(helper, "helper");
      this.subchannelStateManager = checkNotNull(subchannelStateManager, "subchannelStateManager");
      this.picker = checkNotNull(picker, "picker");
    }

    ChildLoadBalancerHelper forTarget(String target) {
      return new ChildLoadBalancerHelper(target, helper, subchannelStateManager, picker);
    }
  }
}
