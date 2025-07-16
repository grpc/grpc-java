/*
 * Copyright 2025 The gRPC Authors
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

import static com.google.common.truth.Truth.assertThat;

import io.grpc.CallOptions;
import io.grpc.ConnectivityState;
import io.grpc.EquivalentAddressGroup;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancer.ResolvedAddresses;
import io.grpc.LoadBalancer.SubchannelPicker;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.SynchronizationContext;
import io.grpc.internal.PickSubchannelArgsImpl;
import io.grpc.testing.TestMethodDescriptors;
import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit test for {@link io.grpc.xds.LazyLoadBalancer}. */
@RunWith(JUnit4.class)
public final class LazyLoadBalancerTest {
  private SynchronizationContext syncContext =
      new SynchronizationContext((t, e) -> {
        throw new AssertionError(e);
      });
  private LoadBalancer.PickSubchannelArgs args = new PickSubchannelArgsImpl(
      TestMethodDescriptors.voidMethod(),
      new Metadata(),
      CallOptions.DEFAULT,
      new LoadBalancer.PickDetailsConsumer() {});
  private FakeHelper helper = new FakeHelper();

  @Test
  public void pickerIsNoopAfterEarlyShutdown() {
    LazyLoadBalancer lb = new LazyLoadBalancer(helper, new LoadBalancer.Factory() {
      @Override
      public LoadBalancer newLoadBalancer(LoadBalancer.Helper helper) {
        throw new AssertionError("unexpected");
      }
    });
    lb.acceptResolvedAddresses(ResolvedAddresses.newBuilder()
        .setAddresses(Arrays.asList())
        .build());
    SubchannelPicker picker = helper.picker;
    assertThat(picker).isNotNull();
    lb.shutdown();

    picker.pickSubchannel(args);
  }

  class FakeHelper extends LoadBalancer.Helper {
    ConnectivityState state;
    SubchannelPicker picker;

    @Override
    public ManagedChannel createOobChannel(EquivalentAddressGroup eag, String authority) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void updateBalancingState(ConnectivityState newState, SubchannelPicker newPicker) {
      this.state = newState;
      this.picker = newPicker;
    }

    @Override
    public SynchronizationContext getSynchronizationContext() {
      return syncContext;
    }

    @Override
    public String getAuthority() {
      return "localhost";
    }
  }
}
