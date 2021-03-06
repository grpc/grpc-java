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

package io.grpc.xds;

import static com.google.common.truth.Truth.assertThat;
import static io.grpc.ConnectivityState.CONNECTING;
import static io.grpc.ConnectivityState.IDLE;
import static io.grpc.ConnectivityState.READY;
import static io.grpc.ConnectivityState.TRANSIENT_FAILURE;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.google.common.collect.Iterables;
import io.grpc.Attributes;
import io.grpc.CallOptions;
import io.grpc.ConnectivityStateInfo;
import io.grpc.EquivalentAddressGroup;
import io.grpc.LoadBalancer.CreateSubchannelArgs;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancer.PickResult;
import io.grpc.LoadBalancer.PickSubchannelArgs;
import io.grpc.LoadBalancer.ResolvedAddresses;
import io.grpc.LoadBalancer.Subchannel;
import io.grpc.LoadBalancer.SubchannelPicker;
import io.grpc.LoadBalancer.SubchannelStateListener;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.Status.Code;
import io.grpc.internal.PickSubchannelArgsImpl;
import io.grpc.testing.TestMethodDescriptors;
import io.grpc.xds.RingHashLoadBalancer.RingHashConfig;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.mockito.stubbing.Answer;

/** Unit test for {@link io.grpc.LoadBalancer}. */
@RunWith(JUnit4.class)
public class RingHashLoadBalancerTest {
  @Rule
  public final MockitoRule mocks = MockitoJUnit.rule();

  private final Map<List<EquivalentAddressGroup>, Subchannel> subchannels = new HashMap<>();
  private final Map<Subchannel, SubchannelStateListener> subchannelStateListeners =
      new HashMap<>();
  private final XxHash64 hashFunc = XxHash64.INSTANCE;
  @Mock
  private Helper helper;
  @Captor
  private ArgumentCaptor<SubchannelPicker> pickerCaptor;
  private RingHashLoadBalancer loadBalancer;

  @Before
  public void setUp() {
    when(helper.createSubchannel(any(CreateSubchannelArgs.class))).thenAnswer(
        new Answer<Subchannel>() {
          @Override
          public Subchannel answer(InvocationOnMock invocation) throws Throwable {
            CreateSubchannelArgs args = (CreateSubchannelArgs) invocation.getArguments()[0];
            final Subchannel subchannel = mock(Subchannel.class);
            when(subchannel.getAllAddresses()).thenReturn(args.getAddresses());
            when(subchannel.getAttributes()).thenReturn(args.getAttributes());
            subchannels.put(args.getAddresses(), subchannel);
            doAnswer(new Answer<Void>() {
              @Override
              public Void answer(InvocationOnMock invocation) throws Throwable {
                subchannelStateListeners.put(
                    subchannel, (SubchannelStateListener) invocation.getArguments()[0]);
                return null;
              }
            }).when(subchannel).start(any(SubchannelStateListener.class));
            return subchannel;
          }
        });
    loadBalancer = new RingHashLoadBalancer(helper);
  }

  @After
  public void tearDown() {
    loadBalancer.shutdown();
    for (Subchannel subchannel : subchannels.values()) {
      verify(subchannel).shutdown();
    }
  }

  @Test
  public void subchannelLazyConnectUntilPicked() {
    RingHashConfig config = new RingHashConfig(10, 100);
    List<EquivalentAddressGroup> servers = createServers(1, 1, 1);
    loadBalancer.handleResolvedAddresses(
        ResolvedAddresses.newBuilder()
            .setAddresses(servers).setLoadBalancingPolicyConfig(config).build());
    assertThat(subchannels).isEmpty();
    verify(helper).updateBalancingState(eq(IDLE), pickerCaptor.capture());

    // Picking subchannel triggers connection.
    PickSubchannelArgs args = new PickSubchannelArgsImpl(
        TestMethodDescriptors.voidMethod(), new Metadata(),
        CallOptions.DEFAULT.withOption(XdsNameResolver.RPC_HASH_KEY, hashFunc.hashVoid()));
    PickResult result = pickerCaptor.getValue().pickSubchannel(args);
    assertThat(result.getStatus().isOk()).isTrue();
    assertThat(result.getSubchannel()).isNull();
    verify(helper).createSubchannel(any(CreateSubchannelArgs.class));
    assertThat(subchannels).hasSize(1);
    Subchannel subchannel = Iterables.getOnlyElement(subchannels.values());
    verify(subchannel).requestConnection();
    deliverSubchannelState(subchannel, ConnectivityStateInfo.forNonError(CONNECTING));
    verify(helper).updateBalancingState(eq(CONNECTING), any(SubchannelPicker.class));

    // Subchannel becomes ready, triggers pick again.
    deliverSubchannelState(subchannel, ConnectivityStateInfo.forNonError(READY));
    verify(helper).updateBalancingState(eq(READY), pickerCaptor.capture());
    result = pickerCaptor.getValue().pickSubchannel(args);
    assertThat(result.getSubchannel()).isSameInstanceAs(subchannel);
  }

  @Test
  public void subchannelNotAutoReconnectAfterDisconnected() {
    RingHashConfig config = new RingHashConfig(10, 100);
    List<EquivalentAddressGroup> servers = createServers(1, 1, 1);
    loadBalancer.handleResolvedAddresses(
        ResolvedAddresses.newBuilder()
            .setAddresses(servers).setLoadBalancingPolicyConfig(config).build());
    verify(helper).updateBalancingState(eq(IDLE), pickerCaptor.capture());

    // Picking subchannel triggers connection.
    PickSubchannelArgs args = new PickSubchannelArgsImpl(
        TestMethodDescriptors.voidMethod(), new Metadata(),
        CallOptions.DEFAULT.withOption(XdsNameResolver.RPC_HASH_KEY, hashFunc.hashVoid()));
    pickerCaptor.getValue().pickSubchannel(args);
    verify(helper).createSubchannel(any(CreateSubchannelArgs.class));
    Subchannel subchannel = Iterables.getOnlyElement(subchannels.values());
    verify(subchannel, times(1)).requestConnection();
    deliverSubchannelState(subchannel, ConnectivityStateInfo.forNonError(READY));
    verify(helper).updateBalancingState(eq(READY), any(SubchannelPicker.class));
    deliverSubchannelState(subchannel, ConnectivityStateInfo.forTransientFailure(
        Status.UNAUTHENTICATED.withDescription("Permission denied")));
    verify(helper, times(2)).updateBalancingState(eq(IDLE), pickerCaptor.capture());
    verify(subchannel, times(1)).requestConnection();

    // Picking again triggers reconnection.
    pickerCaptor.getValue().pickSubchannel(args);
    verify(subchannel, times(2)).requestConnection();
  }

  @Test
  public void deterministicPickWithHostsPartiallyRemoved() {
    RingHashConfig config = new RingHashConfig(10, 100);
    List<EquivalentAddressGroup> servers = createServers(1, 1, 1, 1, 1);
    loadBalancer.handleResolvedAddresses(
        ResolvedAddresses.newBuilder()
            .setAddresses(servers).setLoadBalancingPolicyConfig(config).build());
    verify(helper).updateBalancingState(eq(IDLE), pickerCaptor.capture());

    // Simulate rpc hash hits one ring entry exactly.
    long rpcHash = hashFunc.hashAsciiString("[FakeSocketAddress-server1]_0");
    PickSubchannelArgs args = new PickSubchannelArgsImpl(
        TestMethodDescriptors.voidMethod(), new Metadata(),
        CallOptions.DEFAULT.withOption(XdsNameResolver.RPC_HASH_KEY, rpcHash));
    pickerCaptor.getValue().pickSubchannel(args);
    deliverSubchannelState(
        Iterables.getOnlyElement(subchannels.values()), ConnectivityStateInfo.forNonError(READY));
    verify(helper).updateBalancingState(eq(READY), pickerCaptor.capture());
    PickResult result = pickerCaptor.getValue().pickSubchannel(args);
    Subchannel subchannel = result.getSubchannel();
    assertThat(subchannel.getAddresses()).isEqualTo(servers.get(1));

    servers = servers.subList(0, 2);  // only server0 and server1 left
    loadBalancer.handleResolvedAddresses(
        ResolvedAddresses.newBuilder()
            .setAddresses(servers).setLoadBalancingPolicyConfig(config).build());
    verify(helper, times(2)).updateBalancingState(eq(READY), pickerCaptor.capture());
    assertThat(pickerCaptor.getValue().pickSubchannel(args).getSubchannel())
        .isSameInstanceAs(subchannel);
  }

  @Test
  public void deterministicPickWithNewHostsAdded() {
    RingHashConfig config = new RingHashConfig(10, 100);
    List<EquivalentAddressGroup> servers = createServers(1, 1);  // server0 and server1
    loadBalancer.handleResolvedAddresses(
        ResolvedAddresses.newBuilder()
            .setAddresses(servers).setLoadBalancingPolicyConfig(config).build());
    verify(helper).updateBalancingState(eq(IDLE), pickerCaptor.capture());

    // Simulate rpc hash hits one ring entry exactly.
    long rpcHash = hashFunc.hashAsciiString("[FakeSocketAddress-server1]_0");
    PickSubchannelArgs args = new PickSubchannelArgsImpl(
        TestMethodDescriptors.voidMethod(), new Metadata(),
        CallOptions.DEFAULT.withOption(XdsNameResolver.RPC_HASH_KEY, rpcHash));
    pickerCaptor.getValue().pickSubchannel(args);
    deliverSubchannelState(
        Iterables.getOnlyElement(subchannels.values()), ConnectivityStateInfo.forNonError(READY));
    verify(helper).updateBalancingState(eq(READY), pickerCaptor.capture());
    PickResult result = pickerCaptor.getValue().pickSubchannel(args);
    Subchannel subchannel = result.getSubchannel();
    assertThat(subchannel.getAddresses()).isEqualTo(servers.get(1));

    servers = createServers(1, 1, 1, 1, 1);  // server2, server3, server4 added
    loadBalancer.handleResolvedAddresses(
        ResolvedAddresses.newBuilder()
            .setAddresses(servers).setLoadBalancingPolicyConfig(config).build());
    verify(helper, times(2)).updateBalancingState(eq(READY), pickerCaptor.capture());
    assertThat(pickerCaptor.getValue().pickSubchannel(args).getSubchannel())
        .isSameInstanceAs(subchannel);
  }

  @Test
  public void hostSelectionProportionalToWeights() {
    RingHashConfig config = new RingHashConfig(11100, 111000);
    List<EquivalentAddressGroup> servers = createServers(1, 100, 10000);  // 1:100:10000
    loadBalancer.handleResolvedAddresses(
        ResolvedAddresses.newBuilder()
            .setAddresses(servers).setLoadBalancingPolicyConfig(config).build());
    verify(helper).updateBalancingState(eq(IDLE), pickerCaptor.capture());

    // Warm up by trying to connect to all servers.
    for (int i = 0; i < 3; i++) {
      long rpcHash = hashFunc.hashAsciiString(String.format("[FakeSocketAddress-server%d]_0", i));
      PickSubchannelArgs args = new PickSubchannelArgsImpl(
          TestMethodDescriptors.voidMethod(), new Metadata(),
          CallOptions.DEFAULT.withOption(XdsNameResolver.RPC_HASH_KEY, rpcHash));
      pickerCaptor.getValue().pickSubchannel(args);
      verify(helper, times(i + 1)).createSubchannel(any(CreateSubchannelArgs.class));
    }
    assertThat(subchannels).hasSize(3);

    // Bring all subchannels to READY.
    Map<EquivalentAddressGroup, Integer> pickCounts = new HashMap<>();
    for (Subchannel subchannel : subchannels.values()) {
      verify(subchannel).requestConnection();
      deliverSubchannelState(subchannel, ConnectivityStateInfo.forNonError(READY));
      pickCounts.put(subchannel.getAddresses(), 0);
    }
    verify(helper, times(3)).updateBalancingState(eq(READY), pickerCaptor.capture());
    SubchannelPicker picker = pickerCaptor.getValue();

    for (int i = 0; i < 10000; i++) {
      long hash = hashFunc.hashInt(i);
      PickSubchannelArgs args = new PickSubchannelArgsImpl(
          TestMethodDescriptors.voidMethod(), new Metadata(),
          CallOptions.DEFAULT.withOption(XdsNameResolver.RPC_HASH_KEY, hash));
      Subchannel pickedSubchannel = picker.pickSubchannel(args).getSubchannel();
      EquivalentAddressGroup addr = pickedSubchannel.getAddresses();
      pickCounts.put(addr, pickCounts.get(addr) + 1);
    }

    // Actual distribution: server0 = 0, server1 = 90, server2 = 9910
    double ratio01 = (double) pickCounts.get(servers.get(0)) / pickCounts.get(servers.get(1));
    double ratio12 = (double) pickCounts.get(servers.get(1)) / pickCounts.get(servers.get(2));
    assertThat(ratio01).isWithin(0.01).of((double) 1 / 100);
    assertThat(ratio12).isWithin(0.01).of((double) 100 / 10000);
  }

  @Test
  public void nameResolutionErrorWithNoActiveSubchannels() {
    Status error = Status.UNAVAILABLE.withDescription("not reachable");
    loadBalancer.handleNameResolutionError(error);
    verify(helper).updateBalancingState(eq(TRANSIENT_FAILURE), pickerCaptor.capture());
    PickResult result = pickerCaptor.getValue().pickSubchannel(mock(PickSubchannelArgs.class));
    assertThat(result.getStatus().getCode()).isEqualTo(Code.UNAVAILABLE);
    assertThat(result.getStatus().getDescription()).isEqualTo("not reachable");
    assertThat(result.getSubchannel()).isNull();
    verifyNoMoreInteractions(helper);
  }

  @Test
  public void nameResolutionErrorWithActiveSubchannels() {
    RingHashConfig config = new RingHashConfig(10, 100);
    List<EquivalentAddressGroup> servers = createServers(1, 1, 1);
    loadBalancer.handleResolvedAddresses(
        ResolvedAddresses.newBuilder()
            .setAddresses(servers).setLoadBalancingPolicyConfig(config).build());
    verify(helper).updateBalancingState(eq(IDLE), pickerCaptor.capture());

    // Picking subchannel triggers subchannel creation and connection.
    PickSubchannelArgs args = new PickSubchannelArgsImpl(
        TestMethodDescriptors.voidMethod(), new Metadata(),
        CallOptions.DEFAULT.withOption(XdsNameResolver.RPC_HASH_KEY, hashFunc.hashVoid()));
    pickerCaptor.getValue().pickSubchannel(args);
    verify(helper).createSubchannel(any(CreateSubchannelArgs.class));
    Subchannel subchannel = Iterables.getOnlyElement(subchannels.values());
    verify(subchannel).requestConnection();
    deliverSubchannelState(subchannel, ConnectivityStateInfo.forNonError(READY));
    verify(helper).updateBalancingState(eq(READY), any(SubchannelPicker.class));

    loadBalancer.handleNameResolutionError(Status.NOT_FOUND.withDescription("target not found"));
    verifyNoMoreInteractions(helper);
  }

  private static List<EquivalentAddressGroup> createServers(long... weights) {
    List<EquivalentAddressGroup> res = new ArrayList<>();
    for (int i = 0; i < weights.length; i++) {
      SocketAddress addr = new FakeSocketAddress("server" + i);
      Attributes attr = Attributes.newBuilder().set(
          InternalXdsAttributes.ATTR_SERVER_WEIGHT, weights[i]).build();
      EquivalentAddressGroup eag = new EquivalentAddressGroup(addr, attr);
      res.add(eag);
    }
    return res;
  }

  private void deliverSubchannelState(Subchannel subchannel, ConnectivityStateInfo state) {
    subchannelStateListeners.get(subchannel).onSubchannelState(state);
  }

  private static class FakeSocketAddress extends SocketAddress {
    private final String name;

    FakeSocketAddress(String name) {
      this.name = name;
    }

    @Override
    public int hashCode() {
      return name.hashCode();
    }

    @Override
    public boolean equals(Object other) {
      if (!(other instanceof FakeSocketAddress)) {
        return false;
      }
      return name.equals(((FakeSocketAddress) other).name);
    }

    @Override
    public String toString() {
      return "FakeSocketAddress-" +  name;
    }
  }
}
