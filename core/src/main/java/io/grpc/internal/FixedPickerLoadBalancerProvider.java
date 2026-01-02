/*
 * Copyright 2026 The gRPC Authors
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

package io.grpc.internal;

import static java.util.Objects.requireNonNull;

import io.grpc.ConnectivityState;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancerProvider;
import io.grpc.Status;

/** A LB provider whose LB always uses the same picker. */
final class FixedPickerLoadBalancerProvider extends LoadBalancerProvider {
  private final ConnectivityState state;
  private final LoadBalancer.SubchannelPicker picker;
  private final Status acceptAddressesStatus;

  public FixedPickerLoadBalancerProvider(
      ConnectivityState state, LoadBalancer.SubchannelPicker picker, Status acceptAddressesStatus) {
    this.state = requireNonNull(state, "state");
    this.picker = requireNonNull(picker, "picker");
    this.acceptAddressesStatus = requireNonNull(acceptAddressesStatus, "acceptAddressesStatus");
  }

  @Override
  public boolean isAvailable() {
    return true;
  }

  @Override
  public int getPriority() {
    return 5;
  }

  @Override
  public String getPolicyName() {
    return "fixed_picker_lb_internal";
  }

  @Override
  public LoadBalancer newLoadBalancer(LoadBalancer.Helper helper) {
    return new FixedPickerLoadBalancer(helper);
  }

  private final class FixedPickerLoadBalancer extends LoadBalancer {
    private final Helper helper;

    public FixedPickerLoadBalancer(Helper helper) {
      this.helper = requireNonNull(helper, "helper");
    }

    @Override
    public Status acceptResolvedAddresses(ResolvedAddresses resolvedAddresses) {
      helper.updateBalancingState(state, picker);
      return acceptAddressesStatus;
    }

    @Override
    public void handleNameResolutionError(Status error) {
      helper.updateBalancingState(state, picker);
    }

    @Override
    public void shutdown() {}
  }
}
