/*
 * Copyright 2017 The gRPC Authors
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

package io.grpc;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;

import io.grpc.LoadBalancer.CreateSubchannelArgs;
import io.grpc.LoadBalancer.PickResult;
import io.grpc.LoadBalancer.ResolvedAddresses;
import io.grpc.LoadBalancer.Subchannel;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for the inner classes in {@link LoadBalancer}. */
@RunWith(JUnit4.class)
public class LoadBalancerTest {
  private final Subchannel subchannel = mock(Subchannel.class);
  private final Subchannel subchannel2 = mock(Subchannel.class);
  private final ClientStreamTracer.Factory tracerFactory = mock(ClientStreamTracer.Factory.class);
  private final Status status = Status.UNAVAILABLE.withDescription("for test");
  private final Status status2 = Status.UNAVAILABLE.withDescription("for test 2");
  private final EquivalentAddressGroup eag = new EquivalentAddressGroup(new SocketAddress() {});
  private final Attributes attrs = Attributes.newBuilder()
      .set(Attributes.Key.create("trash"), new Object())
      .build();

  @Test
  public void pickResult_withSubchannel() {
    PickResult result = PickResult.withSubchannel(subchannel);
    assertThat(result.getSubchannel()).isSameInstanceAs(subchannel);
    assertThat(result.getStatus()).isSameInstanceAs(Status.OK);
    assertThat(result.getStreamTracerFactory()).isNull();
    assertThat(result.isDrop()).isFalse();
  }

  @Test
  public void pickResult_withSubchannelAndTracer() {
    PickResult result = PickResult.withSubchannel(subchannel, tracerFactory);
    assertThat(result.getSubchannel()).isSameInstanceAs(subchannel);
    assertThat(result.getStatus()).isSameInstanceAs(Status.OK);
    assertThat(result.getStreamTracerFactory()).isSameInstanceAs(tracerFactory);
    assertThat(result.isDrop()).isFalse();
  }

  @Test
  public void pickResult_withNoResult() {
    PickResult result = PickResult.withNoResult();
    assertThat(result.getSubchannel()).isNull();
    assertThat(result.getStatus()).isSameInstanceAs(Status.OK);
    assertThat(result.getStreamTracerFactory()).isNull();
    assertThat(result.isDrop()).isFalse();
  }

  @Test
  public void pickResult_withError() {
    PickResult result = PickResult.withError(status);
    assertThat(result.getSubchannel()).isNull();
    assertThat(result.getStatus()).isSameInstanceAs(status);
    assertThat(result.getStreamTracerFactory()).isNull();
    assertThat(result.isDrop()).isFalse();
  }

  @Test
  public void pickResult_withDrop() {
    PickResult result = PickResult.withDrop(status);
    assertThat(result.getSubchannel()).isNull();
    assertThat(result.getStatus()).isSameInstanceAs(status);
    assertThat(result.getStreamTracerFactory()).isNull();
    assertThat(result.isDrop()).isTrue();
  }

  @Test
  public void pickResult_equals() {
    PickResult sc1 = PickResult.withSubchannel(subchannel);
    PickResult sc2 = PickResult.withSubchannel(subchannel);
    PickResult sc3 = PickResult.withSubchannel(subchannel, tracerFactory);
    PickResult sc4 = PickResult.withSubchannel(subchannel2);
    PickResult nr = PickResult.withNoResult();
    PickResult error1 = PickResult.withError(status);
    PickResult error2 = PickResult.withError(status2);
    PickResult error3 = PickResult.withError(status2);
    PickResult drop1 = PickResult.withDrop(status);
    PickResult drop2 = PickResult.withDrop(status);
    PickResult drop3 = PickResult.withDrop(status2);

    assertThat(sc1).isNotEqualTo(nr);
    assertThat(sc1).isNotEqualTo(error1);
    assertThat(sc1).isNotEqualTo(drop1);
    assertThat(sc1).isEqualTo(sc2);
    assertThat(sc1).isNotEqualTo(sc3);
    assertThat(sc1).isNotEqualTo(sc4);

    assertThat(error1).isNotEqualTo(error2);
    assertThat(error2).isEqualTo(error3);

    assertThat(drop1).isEqualTo(drop2);
    assertThat(drop1).isNotEqualTo(drop3);

    assertThat(error1.getStatus()).isEqualTo(drop1.getStatus());
    assertThat(error1).isNotEqualTo(drop1);
  }

  @Test
  public void helper_createSubchannelList_throws() {
    try {
      new NoopHelper().createSubchannel(CreateSubchannelArgs.newBuilder()
          .setAddresses(eag)
          .setAttributes(attrs)
          .build());
      fail("Should throw");
    } catch (UnsupportedOperationException e) {
      // expected
    }
  }

  @Test
  public void subchannel_getAddresses_delegates() {
    class OverrideGetAllAddresses extends EmptySubchannel {
      boolean ran;

      @Override public List<EquivalentAddressGroup> getAllAddresses() {
        ran = true;
        return Arrays.asList(eag);
      }
    }

    OverrideGetAllAddresses subchannel = new OverrideGetAllAddresses();
    assertThat(subchannel.getAddresses()).isEqualTo(eag);
    assertThat(subchannel.ran).isTrue();
  }

  @Test(expected = IllegalStateException.class)
  public void subchannel_getAddresses_throwsOnTwoAddrs() {
    new EmptySubchannel() {
      boolean ran;

      @Override public List<EquivalentAddressGroup> getAllAddresses() {
        ran = true;
        // Doubling up eag is technically a bad idea, but nothing here cares
        return Arrays.asList(eag, eag);
      }
    }.getAddresses();
  }

  @Test
  public void createSubchannelArgs_option_keyOps() {
    CreateSubchannelArgs.Key<String> testKey = CreateSubchannelArgs.Key.create("test-key");
    String testValue = "test-value";
    CreateSubchannelArgs.Key<String> testWithDefaultKey = CreateSubchannelArgs.Key
        .createWithDefault("test-key", testValue);
    CreateSubchannelArgs args = CreateSubchannelArgs.newBuilder()
        .setAddresses(eag)
        .setAttributes(attrs)
        .build();
    assertThat(args.getOption(testKey)).isNull();
    assertThat(args.getOption(testWithDefaultKey)).isSameInstanceAs(testValue);
  }

  @Test
  public void createSubchannelArgs_option_addGet() {
    String testValue = "test-value";
    CreateSubchannelArgs.Key<String> testKey = CreateSubchannelArgs.Key.create("test-key");
    CreateSubchannelArgs args = CreateSubchannelArgs.newBuilder()
        .setAddresses(eag)
        .setAttributes(attrs)
        .addOption(testKey, testValue)
        .build();
    assertThat(args.getOption(testKey)).isEqualTo(testValue);
  }

  @Test
  public void createSubchannelArgs_option_lastOneWins() {
    String testValue1 = "test-value-1";
    String testValue2 = "test-value-2";
    CreateSubchannelArgs.Key<String> testKey = CreateSubchannelArgs.Key.create("test-key");
    CreateSubchannelArgs args = CreateSubchannelArgs.newBuilder()
        .setAddresses(eag)
        .setAttributes(attrs)
        .addOption(testKey, testValue1)
        .addOption(testKey, testValue2)
        .build();
    assertThat(args.getOption(testKey)).isEqualTo(testValue2);
  }

  @Test
  public void createSubchannelArgs_build() {
    CreateSubchannelArgs.Key<Object> testKey = CreateSubchannelArgs.Key.create("test-key");
    Object testValue = new Object();
    CreateSubchannelArgs args = CreateSubchannelArgs.newBuilder()
        .setAddresses(eag)
        .setAttributes(attrs)
        .addOption(testKey, testValue)
        .build();
    CreateSubchannelArgs rebuildedArgs = args.toBuilder().build();
    assertThat(rebuildedArgs.getAddresses()).containsExactly(eag);
    assertThat(rebuildedArgs.getAttributes()).isSameInstanceAs(attrs);
    assertThat(rebuildedArgs.getOption(testKey)).isSameInstanceAs(testValue);
  }

  @Test
  public void createSubchannelArgs_toString() {
    CreateSubchannelArgs.Key<String> testKey = CreateSubchannelArgs.Key.create("test-key");
    CreateSubchannelArgs args = CreateSubchannelArgs.newBuilder()
        .setAddresses(eag)
        .setAttributes(attrs)
        .addOption(testKey, "test-value")
        .build();
    String str = args.toString();
    assertThat(str).contains("addrs=");
    assertThat(str).contains("attrs=");
    assertThat(str).contains("customOptions=");
  }

  @Deprecated
  @Test
  public void handleResolvedAddresses_delegatesToAcceptResolvedAddresses() {
    final AtomicReference<ResolvedAddresses> resultCapture = new AtomicReference<>();

    LoadBalancer balancer = new LoadBalancer() {
        @Override
        public boolean acceptResolvedAddresses(ResolvedAddresses resolvedAddresses) {
          resultCapture.set(resolvedAddresses);
          return true;
        }

        @Override
        public void handleNameResolutionError(Status error) {
        }

        @Override
        public void handleSubchannelState(Subchannel subchannel, ConnectivityStateInfo state) {
        }

        @Override
        public void shutdown() {
        }
      };

    List<EquivalentAddressGroup> servers = Arrays.asList(
        new EquivalentAddressGroup(new SocketAddress(){}),
        new EquivalentAddressGroup(new SocketAddress(){}));
    ResolvedAddresses addresses = ResolvedAddresses.newBuilder().setAddresses(servers)
        .setAttributes(attrs).build();
    balancer.handleResolvedAddresses(addresses);
    assertThat(resultCapture.get()).isEqualTo(
        ResolvedAddresses.newBuilder().setAddresses(servers).setAttributes(attrs).build());
  }

  @Deprecated
  @Test
  public void acceptResolvedAddresses_delegatesToHandleResolvedAddressGroups() {
    final AtomicReference<ResolvedAddresses> addressesCapture = new AtomicReference<>();

    LoadBalancer balancer = new LoadBalancer() {
        @Override
        public void handleResolvedAddresses(ResolvedAddresses addresses) {
          addressesCapture.set(addresses);
        }

        @Override
        public void handleNameResolutionError(Status error) {
        }

        @Override
        public void handleSubchannelState(Subchannel subchannel, ConnectivityStateInfo state) {
        }

        @Override
        public void shutdown() {
        }
      };

    List<EquivalentAddressGroup> servers = Arrays.asList(
        new EquivalentAddressGroup(new SocketAddress(){}),
        new EquivalentAddressGroup(new SocketAddress(){}));
    ResolvedAddresses addresses = ResolvedAddresses.newBuilder().setAddresses(servers)
        .setAttributes(attrs).build();
    balancer.handleResolvedAddresses(addresses);
    assertThat(addressesCapture.get().getAddresses()).isEqualTo(servers);
    assertThat(addressesCapture.get().getAttributes()).isEqualTo(attrs);
  }

  @Deprecated
  @Test
  public void acceptResolvedAddresses_noInfiniteLoop() {
    final List<ResolvedAddresses> addressesCapture = new ArrayList<>();

    LoadBalancer balancer = new LoadBalancer() {
      @Override
      public void handleResolvedAddresses(ResolvedAddresses addresses) {
        addressesCapture.add(addresses);
        super.handleResolvedAddresses(addresses);
      }

      @Override
      public void handleNameResolutionError(Status error) {
      }

      @Override
      public void shutdown() {
      }
    };

    List<EquivalentAddressGroup> servers = Arrays.asList(
        new EquivalentAddressGroup(new SocketAddress(){}),
        new EquivalentAddressGroup(new SocketAddress(){}));
    ResolvedAddresses addresses = ResolvedAddresses.newBuilder().setAddresses(servers)
        .setAttributes(attrs).build();
    balancer.handleResolvedAddresses(addresses);
    assertThat(addressesCapture).hasSize(1);
    assertThat(addressesCapture.get(0).getAddresses()).isEqualTo(servers);
    assertThat(addressesCapture.get(0).getAttributes()).isEqualTo(attrs);
  }

  private static class NoopHelper extends LoadBalancer.Helper {
    @Override
    public ManagedChannel createOobChannel(EquivalentAddressGroup eag, String authority) {
      return null;
    }

    @Override
    public void updateBalancingState(
        ConnectivityState newState, LoadBalancer.SubchannelPicker newPicker) {}

    @Override public SynchronizationContext getSynchronizationContext() {
      return null;
    }

    @Override public String getAuthority() {
      return null;
    }
  }

  private static class EmptySubchannel extends LoadBalancer.Subchannel {
    @Override public void shutdown() {}

    @Override public void requestConnection() {}

    @Override public Attributes getAttributes() {
      return null;
    }
  }
}
