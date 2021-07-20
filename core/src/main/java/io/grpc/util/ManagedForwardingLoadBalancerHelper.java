/*
 * Copyright 2021 The gRPC Authors
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

import static com.google.common.base.Preconditions.checkState;

import io.grpc.ChannelCredentials;
import io.grpc.ConnectivityState;
import io.grpc.EquivalentAddressGroup;
import io.grpc.ExperimentalApi;
import io.grpc.LoadBalancer.CreateSubchannelArgs;
import io.grpc.LoadBalancer.Subchannel;
import io.grpc.LoadBalancer.SubchannelPicker;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import java.util.List;

/**
 * Provides additional sanity checks for {@link io.grpc.LoadBalancer} implementations. This
 * enforces LoadBalancer implementations to keep aware of its lifetime when interacting with
 * other components that have different lifecycle.
 */
@ExperimentalApi("TODO")
public abstract class ManagedForwardingLoadBalancerHelper extends ForwardingLoadBalancerHelper {

  /**
   * Returns {@code true} if its corresponding {@link io.grpc.LoadBalancer} has been shut down.
   */
  protected abstract boolean isBalancerShutdown();

  @Override
  public Subchannel createSubchannel(CreateSubchannelArgs args) {
    checkState(!isBalancerShutdown(), "balancer has been shutdown");
    return super.createSubchannel(args);
  }

  @Override
  public ManagedChannel createOobChannel(EquivalentAddressGroup eag, String authority) {
    checkState(!isBalancerShutdown(), "balancer has been shutdown");
    return super.createOobChannel(eag, authority);
  }

  @Override
  public ManagedChannel createOobChannel(List<EquivalentAddressGroup> eag, String authority) {
    checkState(!isBalancerShutdown(), "balancer has been shutdown");
    return super.createOobChannel(eag, authority);
  }

  @Override
  public void updateOobChannelAddresses(ManagedChannel channel, EquivalentAddressGroup eag) {
    checkState(!isBalancerShutdown(), "balancer has been shutdown");
    super.updateOobChannelAddresses(channel, eag);
  }

  @Override
  public void updateOobChannelAddresses(ManagedChannel channel, List<EquivalentAddressGroup> eag) {
    checkState(!isBalancerShutdown(), "balancer has been shutdown");
    super.updateOobChannelAddresses(channel, eag);
  }

  @Deprecated
  @Override
  public ManagedChannelBuilder<?> createResolvingOobChannelBuilder(String target) {
    checkState(!isBalancerShutdown(), "balancer has been shutdown");
    return super.createResolvingOobChannelBuilder(target);
  }

  @Override
  public ManagedChannelBuilder<?> createResolvingOobChannelBuilder(String target,
      ChannelCredentials creds) {
    checkState(!isBalancerShutdown(), "balancer has been shutdown");
    return super.createResolvingOobChannelBuilder(target, creds);
  }

  @Override
  public ManagedChannel createResolvingOobChannel(String target) {
    checkState(!isBalancerShutdown(), "balancer has been shutdown");
    return super.createResolvingOobChannel(target);
  }

  @Override
  public void updateBalancingState(ConnectivityState newState, SubchannelPicker newPicker) {
    checkState(!isBalancerShutdown(), "balancer has been shutdown");
    super.updateBalancingState(newState, newPicker);
  }

  @Override
  public void refreshNameResolution() {
    checkState(!isBalancerShutdown(), "balancer has been shutdown");
    super.refreshNameResolution();
  }
}
