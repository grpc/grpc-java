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
import static io.grpc.ConnectivityState.SHUTDOWN;
import static io.grpc.ConnectivityState.TRANSIENT_FAILURE;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
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
import io.grpc.SynchronizationContext;
import io.grpc.internal.PickSubchannelArgsImpl;
import io.grpc.testing.TestMethodDescriptors;
import io.grpc.xds.RingHashLoadBalancer.RingHashConfig;
import java.lang.Thread.UncaughtExceptionHandler;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Collections;
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
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.mockito.stubbing.Answer;

/** Unit test for {@link io.grpc.LoadBalancer}. */
@RunWith(JUnit4.class)
public class RingHashLoadBalancerTest {
  private static final String AUTHORITY = "foo.googleapis.com";
  private static final Attributes.Key<String> CUSTOM_KEY = Attributes.Key.create("custom-key");

  @Rule
  public final MockitoRule mocks = MockitoJUnit.rule();
  private final SynchronizationContext syncContext = new SynchronizationContext(
      new UncaughtExceptionHandler() {
        @Override
        public void uncaughtException(Thread t, Throwable e) {
          throw new AssertionError(e);
        }
      });
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
    when(helper.getAuthority()).thenReturn(AUTHORITY);
    when(helper.getSynchronizationContext()).thenReturn(syncContext);
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
    // Skip uninterested interactions.
    verify(helper).getAuthority();
    verify(helper).getSynchronizationContext();
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
    List<EquivalentAddressGroup> servers = createWeightedServerAddrs(1);  // one server
    loadBalancer.handleResolvedAddresses(
        ResolvedAddresses.newBuilder()
            .setAddresses(servers).setLoadBalancingPolicyConfig(config).build());
    verify(helper).createSubchannel(any(CreateSubchannelArgs.class));
    Subchannel subchannel = Iterables.getOnlyElement(subchannels.values());
    verify(subchannel, never()).requestConnection();
    verify(helper).updateBalancingState(eq(IDLE), pickerCaptor.capture());

    // Picking subchannel triggers connection.
    PickSubchannelArgs args = new PickSubchannelArgsImpl(
        TestMethodDescriptors.voidMethod(), new Metadata(),
        CallOptions.DEFAULT.withOption(XdsNameResolver.RPC_HASH_KEY, hashFunc.hashVoid()));
    PickResult result = pickerCaptor.getValue().pickSubchannel(args);
    assertThat(result.getStatus().isOk()).isTrue();
    assertThat(result.getSubchannel()).isNull();
    verify(subchannel).requestConnection();
    deliverSubchannelState(subchannel, ConnectivityStateInfo.forNonError(CONNECTING));
    verify(helper).updateBalancingState(eq(CONNECTING), any(SubchannelPicker.class));

    // Subchannel becomes ready, triggers pick again.
    deliverSubchannelState(subchannel, ConnectivityStateInfo.forNonError(READY));
    verify(helper).updateBalancingState(eq(READY), pickerCaptor.capture());
    result = pickerCaptor.getValue().pickSubchannel(args);
    assertThat(result.getSubchannel()).isSameInstanceAs(subchannel);
    verifyNoMoreInteractions(helper);
  }

  @Test
  public void subchannelNotAutoReconnectAfterReenteringIdle() {
    RingHashConfig config = new RingHashConfig(10, 100);
    List<EquivalentAddressGroup> servers = createWeightedServerAddrs(1);  // one server
    loadBalancer.handleResolvedAddresses(
        ResolvedAddresses.newBuilder()
            .setAddresses(servers).setLoadBalancingPolicyConfig(config).build());
    Subchannel subchannel = Iterables.getOnlyElement(subchannels.values());
    InOrder inOrder = Mockito.inOrder(helper, subchannel);
    inOrder.verify(helper).updateBalancingState(eq(IDLE), pickerCaptor.capture());
    inOrder.verify(subchannel, never()).requestConnection();

    // Picking subchannel triggers connection.
    PickSubchannelArgs args = new PickSubchannelArgsImpl(
        TestMethodDescriptors.voidMethod(), new Metadata(),
        CallOptions.DEFAULT.withOption(XdsNameResolver.RPC_HASH_KEY, hashFunc.hashVoid()));
    pickerCaptor.getValue().pickSubchannel(args);
    inOrder.verify(subchannel).requestConnection();
    deliverSubchannelState(subchannel, ConnectivityStateInfo.forNonError(READY));
    inOrder.verify(helper).updateBalancingState(eq(READY), any(SubchannelPicker.class));
    deliverSubchannelState(subchannel, ConnectivityStateInfo.forNonError(IDLE));
    inOrder.verify(helper).updateBalancingState(eq(IDLE), pickerCaptor.capture());
    inOrder.verify(subchannel, never()).requestConnection();

    // Picking again triggers reconnection.
    pickerCaptor.getValue().pickSubchannel(args);
    inOrder.verify(subchannel).requestConnection();
  }

  @Test
  public void aggregateSubchannelStates_connectingReadyIdleFailure() {
    RingHashConfig config = new RingHashConfig(10, 100);
    List<EquivalentAddressGroup> servers = createWeightedServerAddrs(1, 1);
    InOrder inOrder = Mockito.inOrder(helper);
    loadBalancer.handleResolvedAddresses(
        ResolvedAddresses.newBuilder()
            .setAddresses(servers).setLoadBalancingPolicyConfig(config).build());
    inOrder.verify(helper, times(2)).createSubchannel(any(CreateSubchannelArgs.class));
    inOrder.verify(helper).updateBalancingState(eq(IDLE), any(SubchannelPicker.class));

    // one in CONNECTING, one in IDLE
    deliverSubchannelState(
        subchannels.get(Collections.singletonList(servers.get(0))),
        ConnectivityStateInfo.forNonError(CONNECTING));
    inOrder.verify(helper).updateBalancingState(eq(CONNECTING), any(SubchannelPicker.class));

    // two in CONNECTING
    deliverSubchannelState(
        subchannels.get(Collections.singletonList(servers.get(1))),
        ConnectivityStateInfo.forNonError(CONNECTING));
    inOrder.verify(helper).updateBalancingState(eq(CONNECTING), any(SubchannelPicker.class));

    // one in CONNECTING, one in READY
    deliverSubchannelState(
        subchannels.get(Collections.singletonList(servers.get(1))),
        ConnectivityStateInfo.forNonError(READY));
    inOrder.verify(helper).updateBalancingState(eq(READY), any(SubchannelPicker.class));

    // one in TRANSIENT_FAILURE, one in READY
    deliverSubchannelState(
        subchannels.get(Collections.singletonList(servers.get(0))),
        ConnectivityStateInfo.forTransientFailure(
            Status.UNKNOWN.withDescription("unknown failure")));
    inOrder.verify(helper).refreshNameResolution();
    inOrder.verify(helper).updateBalancingState(eq(READY), any(SubchannelPicker.class));

    // one in TRANSIENT_FAILURE, one in IDLE
    deliverSubchannelState(
        subchannels.get(Collections.singletonList(servers.get(1))),
        ConnectivityStateInfo.forNonError(IDLE));
    inOrder.verify(helper).refreshNameResolution();
    inOrder.verify(helper).updateBalancingState(eq(IDLE), any(SubchannelPicker.class));

    verifyNoMoreInteractions(helper);
  }

  @Test
  public void aggregateSubchannelStates_twoOrMoreSubchannelsInTransientFailure() {
    RingHashConfig config = new RingHashConfig(10, 100);
    List<EquivalentAddressGroup> servers = createWeightedServerAddrs(1, 1, 1, 1);
    InOrder inOrder = Mockito.inOrder(helper);
    loadBalancer.handleResolvedAddresses(
        ResolvedAddresses.newBuilder()
            .setAddresses(servers).setLoadBalancingPolicyConfig(config).build());
    inOrder.verify(helper, times(4)).createSubchannel(any(CreateSubchannelArgs.class));
    inOrder.verify(helper).updateBalancingState(eq(IDLE), any(SubchannelPicker.class));

    // one in TRANSIENT_FAILURE, three in IDLE
    deliverSubchannelState(
        subchannels.get(Collections.singletonList(servers.get(0))),
        ConnectivityStateInfo.forTransientFailure(
            Status.UNAVAILABLE.withDescription("not found")));
    inOrder.verify(helper).refreshNameResolution();
    inOrder.verify(helper).updateBalancingState(eq(IDLE), any(SubchannelPicker.class));

    // two in TRANSIENT_FAILURE, two in IDLE
    deliverSubchannelState(
        subchannels.get(Collections.singletonList(servers.get(1))),
        ConnectivityStateInfo.forTransientFailure(
            Status.UNAVAILABLE.withDescription("also not found")));
    inOrder.verify(helper).refreshNameResolution();
    inOrder.verify(helper)
        .updateBalancingState(eq(TRANSIENT_FAILURE), any(SubchannelPicker.class));

    // two in TRANSIENT_FAILURE, one in CONNECTING, one in IDLE
    // The overall state is dominated by the two in TRANSIENT_FAILURE.
    deliverSubchannelState(
        subchannels.get(Collections.singletonList(servers.get(2))),
        ConnectivityStateInfo.forNonError(CONNECTING));
    inOrder.verify(helper)
        .updateBalancingState(eq(TRANSIENT_FAILURE), any(SubchannelPicker.class));

    // three in TRANSIENT_FAILURE, one in CONNECTING
    deliverSubchannelState(
        subchannels.get(Collections.singletonList(servers.get(3))),
        ConnectivityStateInfo.forTransientFailure(
            Status.UNAVAILABLE.withDescription("connection lost")));
    inOrder.verify(helper).refreshNameResolution();
    inOrder.verify(helper)
        .updateBalancingState(eq(TRANSIENT_FAILURE), any(SubchannelPicker.class));

    // three in TRANSIENT_FAILURE, one in READY
    deliverSubchannelState(
        subchannels.get(Collections.singletonList(servers.get(2))),
        ConnectivityStateInfo.forNonError(READY));
    inOrder.verify(helper).updateBalancingState(eq(READY), any(SubchannelPicker.class));

    verifyNoMoreInteractions(helper);
  }

  @Test
  public void subchannelStayInTransientFailureUntilBecomeReady() {
    RingHashConfig config = new RingHashConfig(10, 100);
    List<EquivalentAddressGroup> servers = createWeightedServerAddrs(1, 1, 1);
    loadBalancer.handleResolvedAddresses(
        ResolvedAddresses.newBuilder()
            .setAddresses(servers).setLoadBalancingPolicyConfig(config).build());
    verify(helper, times(3)).createSubchannel(any(CreateSubchannelArgs.class));
    verify(helper).updateBalancingState(eq(IDLE), any(SubchannelPicker.class));
    reset(helper);

    // Simulate picks have taken place and subchannels have requested connection.
    for (Subchannel subchannel : subchannels.values()) {
      deliverSubchannelState(subchannel, ConnectivityStateInfo.forTransientFailure(
          Status.UNAUTHENTICATED.withDescription("Permission denied")));
    }
    verify(helper, times(3)).refreshNameResolution();

    // Stays in IDLE when until there are two or more subchannels in TRANSIENT_FAILURE.
    verify(helper).updateBalancingState(eq(IDLE), any(SubchannelPicker.class));
    verify(helper, times(2))
        .updateBalancingState(eq(TRANSIENT_FAILURE), any(SubchannelPicker.class));

    verifyNoMoreInteractions(helper);
    // Simulate underlying subchannel auto reconnect after backoff.
    for (Subchannel subchannel : subchannels.values()) {
      deliverSubchannelState(subchannel, ConnectivityStateInfo.forNonError(CONNECTING));
    }
    verifyNoMoreInteractions(helper);

    // Simulate one subchannel enters READY.
    deliverSubchannelState(
        subchannels.values().iterator().next(), ConnectivityStateInfo.forNonError(READY));
    verify(helper).updateBalancingState(eq(READY), any(SubchannelPicker.class));
  }

  @Test
  public void ignoreShutdownSubchannelStateChange() {
    RingHashConfig config = new RingHashConfig(10, 100);
    List<EquivalentAddressGroup> servers = createWeightedServerAddrs(1, 1, 1);
    loadBalancer.handleResolvedAddresses(
        ResolvedAddresses.newBuilder()
            .setAddresses(servers).setLoadBalancingPolicyConfig(config).build());
    verify(helper, times(3)).createSubchannel(any(CreateSubchannelArgs.class));
    verify(helper).updateBalancingState(eq(IDLE), any(SubchannelPicker.class));

    loadBalancer.shutdown();
    for (Subchannel sc : subchannels.values()) {
      verify(sc).shutdown();
      // When the subchannel is being shut down, a SHUTDOWN connectivity state is delivered
      // back to the subchannel state listener.
      deliverSubchannelState(sc, ConnectivityStateInfo.forNonError(SHUTDOWN));
    }
    verifyNoMoreInteractions(helper);
  }

  @Test
  public void deterministicPickWithHostsPartiallyRemoved() {
    RingHashConfig config = new RingHashConfig(10, 100);
    List<EquivalentAddressGroup> servers = createWeightedServerAddrs(1, 1, 1, 1, 1);
    loadBalancer.handleResolvedAddresses(
        ResolvedAddresses.newBuilder()
            .setAddresses(servers).setLoadBalancingPolicyConfig(config).build());
    InOrder inOrder = Mockito.inOrder(helper);
    inOrder.verify(helper, times(5)).createSubchannel(any(CreateSubchannelArgs.class));
    inOrder.verify(helper).updateBalancingState(eq(IDLE), any(SubchannelPicker.class));

    // Bring all subchannels to READY so that next pick always succeeds.
    for (Subchannel subchannel : subchannels.values()) {
      deliverSubchannelState(subchannel, ConnectivityStateInfo.forNonError(READY));
      inOrder.verify(helper).updateBalancingState(eq(READY), pickerCaptor.capture());
    }

    // Simulate rpc hash hits one ring entry exactly for server1.
    long rpcHash = hashFunc.hashAsciiString("[FakeSocketAddress-server1]_0");
    PickSubchannelArgs args = new PickSubchannelArgsImpl(
        TestMethodDescriptors.voidMethod(), new Metadata(),
        CallOptions.DEFAULT.withOption(XdsNameResolver.RPC_HASH_KEY, rpcHash));
    pickerCaptor.getValue().pickSubchannel(args);
    PickResult result = pickerCaptor.getValue().pickSubchannel(args);
    Subchannel subchannel = result.getSubchannel();
    assertThat(subchannel.getAddresses()).isEqualTo(servers.get(1));

    List<EquivalentAddressGroup> updatedServers = new ArrayList<>();
    for (EquivalentAddressGroup addr : servers.subList(0, 2)) {  // only server0 and server1 left
      Attributes attr = addr.getAttributes().toBuilder().set(CUSTOM_KEY, "custom value").build();
      updatedServers.add(new EquivalentAddressGroup(addr.getAddresses(), attr));
    }
    loadBalancer.handleResolvedAddresses(
        ResolvedAddresses.newBuilder()
            .setAddresses(updatedServers).setLoadBalancingPolicyConfig(config).build());
    verify(subchannels.get(Collections.singletonList(servers.get(0))))
        .updateAddresses(Collections.singletonList(updatedServers.get(0)));
    verify(subchannels.get(Collections.singletonList(servers.get(1))))
        .updateAddresses(Collections.singletonList(updatedServers.get(1)));
    inOrder.verify(helper).updateBalancingState(eq(READY), pickerCaptor.capture());
    assertThat(pickerCaptor.getValue().pickSubchannel(args).getSubchannel())
        .isSameInstanceAs(subchannel);
    verifyNoMoreInteractions(helper);
  }

  @Test
  public void deterministicPickWithNewHostsAdded() {
    RingHashConfig config = new RingHashConfig(10, 100);
    List<EquivalentAddressGroup> servers = createWeightedServerAddrs(1, 1);  // server0 and server1
    loadBalancer.handleResolvedAddresses(
        ResolvedAddresses.newBuilder()
            .setAddresses(servers).setLoadBalancingPolicyConfig(config).build());
    InOrder inOrder = Mockito.inOrder(helper);
    inOrder.verify(helper, times(2)).createSubchannel(any(CreateSubchannelArgs.class));
    inOrder.verify(helper).updateBalancingState(eq(IDLE), pickerCaptor.capture());

    // Bring all subchannels to READY so that next pick always succeeds.
    for (Subchannel subchannel : subchannels.values()) {
      deliverSubchannelState(subchannel, ConnectivityStateInfo.forNonError(READY));
      inOrder.verify(helper).updateBalancingState(eq(READY), pickerCaptor.capture());
    }

    // Simulate rpc hash hits one ring entry exactly for server1.
    long rpcHash = hashFunc.hashAsciiString("[FakeSocketAddress-server1]_0");
    PickSubchannelArgs args = new PickSubchannelArgsImpl(
        TestMethodDescriptors.voidMethod(), new Metadata(),
        CallOptions.DEFAULT.withOption(XdsNameResolver.RPC_HASH_KEY, rpcHash));
    pickerCaptor.getValue().pickSubchannel(args);
    PickResult result = pickerCaptor.getValue().pickSubchannel(args);
    Subchannel subchannel = result.getSubchannel();
    assertThat(subchannel.getAddresses()).isEqualTo(servers.get(1));

    servers = createWeightedServerAddrs(1, 1, 1, 1, 1);  // server2, server3, server4 added
    loadBalancer.handleResolvedAddresses(
        ResolvedAddresses.newBuilder()
            .setAddresses(servers).setLoadBalancingPolicyConfig(config).build());
    inOrder.verify(helper, times(3)).createSubchannel(any(CreateSubchannelArgs.class));
    inOrder.verify(helper).updateBalancingState(eq(READY), pickerCaptor.capture());
    assertThat(pickerCaptor.getValue().pickSubchannel(args).getSubchannel())
        .isSameInstanceAs(subchannel);
    verifyNoMoreInteractions(helper);
  }

  @Test
  public void skipFailingHosts_pickNextNonFailingHostInFirstTwoHosts() {
    // Map each server address to exactly one ring entry.
    RingHashConfig config = new RingHashConfig(3, 3);
    List<EquivalentAddressGroup> servers = createWeightedServerAddrs(1, 1, 1);
    loadBalancer.handleResolvedAddresses(
        ResolvedAddresses.newBuilder()
            .setAddresses(servers).setLoadBalancingPolicyConfig(config).build());
    verify(helper, times(3)).createSubchannel(any(CreateSubchannelArgs.class));
    verify(helper).updateBalancingState(eq(IDLE), any(SubchannelPicker.class));  // initial IDLE
    reset(helper);
    // ring:
    //   "[FakeSocketAddress-server1]_0"
    //   "[FakeSocketAddress-server0]_0"
    //   "[FakeSocketAddress-server2]_0"

    long rpcHash = hashFunc.hashAsciiString("[FakeSocketAddress-server0]_0");
    PickSubchannelArgs args = new PickSubchannelArgsImpl(
        TestMethodDescriptors.voidMethod(), new Metadata(),
        CallOptions.DEFAULT.withOption(XdsNameResolver.RPC_HASH_KEY, rpcHash));

    // Bring down server0 to force trying server2.
    deliverSubchannelState(
        subchannels.get(Collections.singletonList(servers.get(0))),
        ConnectivityStateInfo.forTransientFailure(
            Status.UNAVAILABLE.withDescription("unreachable")));
    verify(helper).updateBalancingState(eq(IDLE), pickerCaptor.capture());

    PickResult result = pickerCaptor.getValue().pickSubchannel(args);
    assertThat(result.getStatus().isOk()).isTrue();
    assertThat(result.getSubchannel()).isNull();  // buffer request
    verify(subchannels.get(Collections.singletonList(servers.get(2))))
        .requestConnection();  // kick off connection to server2
    verify(subchannels.get(Collections.singletonList(servers.get(1))), never())
        .requestConnection();  // no excessive connection

    deliverSubchannelState(
        subchannels.get(Collections.singletonList(servers.get(2))),
        ConnectivityStateInfo.forNonError(CONNECTING));
    verify(helper).updateBalancingState(eq(CONNECTING), pickerCaptor.capture());

    result = pickerCaptor.getValue().pickSubchannel(args);
    assertThat(result.getStatus().isOk()).isTrue();
    assertThat(result.getSubchannel()).isNull();  // buffer request

    deliverSubchannelState(
        subchannels.get(Collections.singletonList(servers.get(2))),
        ConnectivityStateInfo.forNonError(READY));
    verify(helper).updateBalancingState(eq(READY), pickerCaptor.capture());

    result = pickerCaptor.getValue().pickSubchannel(args);
    assertThat(result.getStatus().isOk()).isTrue();
    assertThat(result.getSubchannel().getAddresses()).isEqualTo(servers.get(2));
  }

  @Test
  public void skipFailingHosts_firstTwoHostsFailed_pickNextFirstReady() {
    // Map each server address to exactly one ring entry.
    RingHashConfig config = new RingHashConfig(3, 3);
    List<EquivalentAddressGroup> servers = createWeightedServerAddrs(1, 1, 1);
    loadBalancer.handleResolvedAddresses(
        ResolvedAddresses.newBuilder()
            .setAddresses(servers).setLoadBalancingPolicyConfig(config).build());
    verify(helper, times(3)).createSubchannel(any(CreateSubchannelArgs.class));
    verify(helper).updateBalancingState(eq(IDLE), any(SubchannelPicker.class));  // initial IDLE
    reset(helper);
    // ring:
    //   "[FakeSocketAddress-server1]_0"
    //   "[FakeSocketAddress-server0]_0"
    //   "[FakeSocketAddress-server2]_0"

    long rpcHash = hashFunc.hashAsciiString("[FakeSocketAddress-server0]_0");
    PickSubchannelArgs args = new PickSubchannelArgsImpl(
        TestMethodDescriptors.voidMethod(), new Metadata(),
        CallOptions.DEFAULT.withOption(XdsNameResolver.RPC_HASH_KEY, rpcHash));

    // Bring down server0 and server2 to force trying server1.
    deliverSubchannelState(
        subchannels.get(Collections.singletonList(servers.get(0))),
        ConnectivityStateInfo.forTransientFailure(
            Status.UNAVAILABLE.withDescription("unreachable")));
    deliverSubchannelState(
        subchannels.get(Collections.singletonList(servers.get(2))),
        ConnectivityStateInfo.forTransientFailure(
            Status.PERMISSION_DENIED.withDescription("permission denied")));
    verify(helper).updateBalancingState(eq(TRANSIENT_FAILURE), pickerCaptor.capture());
    verify(subchannels.get(Collections.singletonList(servers.get(1))))
        .requestConnection();  // LB attempts to recover by itself

    PickResult result = pickerCaptor.getValue().pickSubchannel(args);
    assertThat(result.getStatus().isOk()).isFalse();  // fail the RPC
    assertThat(result.getStatus().getCode())
        .isEqualTo(Code.UNAVAILABLE);  // with error status for the original server hit by hash
    assertThat(result.getStatus().getDescription()).isEqualTo("unreachable");
    verify(subchannels.get(Collections.singletonList(servers.get(1))), times(2))
        .requestConnection();  // kickoff connection to server3 (next first non-failing)

    // Now connecting to server1.
    deliverSubchannelState(
        subchannels.get(Collections.singletonList(servers.get(1))),
        ConnectivityStateInfo.forNonError(CONNECTING));
    verify(helper, times(2)).updateBalancingState(eq(TRANSIENT_FAILURE), pickerCaptor.capture());

    result = pickerCaptor.getValue().pickSubchannel(args);
    assertThat(result.getStatus().isOk()).isFalse();  // fail the RPC
    assertThat(result.getStatus().getCode())
        .isEqualTo(Code.UNAVAILABLE);  // with error status for the original server hit by hash
    assertThat(result.getStatus().getDescription()).isEqualTo("unreachable");

    // Simulate server1 becomes READY.
    deliverSubchannelState(
        subchannels.get(Collections.singletonList(servers.get(1))),
        ConnectivityStateInfo.forNonError(READY));
    verify(helper).updateBalancingState(eq(READY), pickerCaptor.capture());

    result = pickerCaptor.getValue().pickSubchannel(args);
    assertThat(result.getStatus().isOk()).isTrue();  // succeed
    assertThat(result.getSubchannel().getAddresses()).isEqualTo(servers.get(1));  // with server1
  }

  @Test
  public void allSubchannelsInTransientFailure() {
    // Map each server address to exactly one ring entry.
    RingHashConfig config = new RingHashConfig(3, 3);
    List<EquivalentAddressGroup> servers = createWeightedServerAddrs(1, 1, 1);
    loadBalancer.handleResolvedAddresses(
        ResolvedAddresses.newBuilder()
            .setAddresses(servers).setLoadBalancingPolicyConfig(config).build());
    verify(helper, times(3)).createSubchannel(any(CreateSubchannelArgs.class));
    verify(helper).updateBalancingState(eq(IDLE), any(SubchannelPicker.class));

    // Bring all subchannels to TRANSIENT_FAILURE.
    for (Subchannel subchannel : subchannels.values()) {
      deliverSubchannelState(subchannel, ConnectivityStateInfo.forTransientFailure(
          Status.UNAVAILABLE.withDescription(
              subchannel.getAddresses().getAddresses() + " unreachable")));
    }
    verify(helper, atLeastOnce())
        .updateBalancingState(eq(TRANSIENT_FAILURE), pickerCaptor.capture());

    // Picking subchannel triggers connection. RPC hash hits server0.
    PickSubchannelArgs args = new PickSubchannelArgsImpl(
        TestMethodDescriptors.voidMethod(), new Metadata(),
        CallOptions.DEFAULT.withOption(XdsNameResolver.RPC_HASH_KEY, hashFunc.hashVoid()));
    PickResult result = pickerCaptor.getValue().pickSubchannel(args);
    assertThat(result.getStatus().isOk()).isFalse();
    assertThat(result.getStatus().getCode()).isEqualTo(Code.UNAVAILABLE);
    assertThat(result.getStatus().getDescription())
        .isEqualTo("[FakeSocketAddress-server0] unreachable");
  }

  @Test
  public void hostSelectionProportionalToWeights() {
    RingHashConfig config = new RingHashConfig(10000, 100000);  // large ring
    List<EquivalentAddressGroup> servers = createWeightedServerAddrs(1, 10, 100); // 1:10:100
    loadBalancer.handleResolvedAddresses(
        ResolvedAddresses.newBuilder()
            .setAddresses(servers).setLoadBalancingPolicyConfig(config).build());
    verify(helper, times(3)).createSubchannel(any(CreateSubchannelArgs.class));
    verify(helper).updateBalancingState(eq(IDLE), any(SubchannelPicker.class));

    // Bring all subchannels to READY.
    Map<EquivalentAddressGroup, Integer> pickCounts = new HashMap<>();
    for (Subchannel subchannel : subchannels.values()) {
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

    // Actual distribution: server0 = 104, server1 = 808, server2 = 9088
    double ratio01 = (double) pickCounts.get(servers.get(0)) / pickCounts.get(servers.get(1));
    double ratio12 = (double) pickCounts.get(servers.get(1)) / pickCounts.get(servers.get(2));
    assertThat(ratio01).isWithin(0.03).of((double) 1 / 10);
    assertThat(ratio12).isWithin(0.03).of((double) 10 / 100);
  }

  @Test
  public void hostSelectionProportionalToRepeatedAddressCount() {
    RingHashConfig config = new RingHashConfig(10000, 100000);
    List<EquivalentAddressGroup> servers = createRepeatedServerAddrs(1, 10, 100);  // 1:10:100
    loadBalancer.handleResolvedAddresses(
        ResolvedAddresses.newBuilder()
            .setAddresses(servers).setLoadBalancingPolicyConfig(config).build());
    verify(helper, times(3)).createSubchannel(any(CreateSubchannelArgs.class));
    verify(helper).updateBalancingState(eq(IDLE), any(SubchannelPicker.class));

    // Bring all subchannels to READY.
    Map<EquivalentAddressGroup, Integer> pickCounts = new HashMap<>();
    for (Subchannel subchannel : subchannels.values()) {
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

    // Actual distribution: server0 = 104, server1 = 808, server2 = 9088
    double ratio01 = (double) pickCounts.get(servers.get(0)) / pickCounts.get(servers.get(1));
    double ratio12 = (double) pickCounts.get(servers.get(1)) / pickCounts.get(servers.get(11));
    assertThat(ratio01).isWithin(0.03).of((double) 1 / 10);
    assertThat(ratio12).isWithin(0.03).of((double) 10 / 100);
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
    List<EquivalentAddressGroup> servers = createWeightedServerAddrs(1);
    loadBalancer.handleResolvedAddresses(
        ResolvedAddresses.newBuilder()
            .setAddresses(servers).setLoadBalancingPolicyConfig(config).build());
    verify(helper).createSubchannel(any(CreateSubchannelArgs.class));
    verify(helper).updateBalancingState(eq(IDLE), pickerCaptor.capture());

    // Picking subchannel triggers subchannel creation and connection.
    PickSubchannelArgs args = new PickSubchannelArgsImpl(
        TestMethodDescriptors.voidMethod(), new Metadata(),
        CallOptions.DEFAULT.withOption(XdsNameResolver.RPC_HASH_KEY, hashFunc.hashVoid()));
    pickerCaptor.getValue().pickSubchannel(args);
    deliverSubchannelState(
        Iterables.getOnlyElement(subchannels.values()), ConnectivityStateInfo.forNonError(READY));
    verify(helper).updateBalancingState(eq(READY), any(SubchannelPicker.class));

    loadBalancer.handleNameResolutionError(Status.NOT_FOUND.withDescription("target not found"));
    verifyNoMoreInteractions(helper);
  }

  private void deliverSubchannelState(Subchannel subchannel, ConnectivityStateInfo state) {
    subchannelStateListeners.get(subchannel).onSubchannelState(state);
  }

  private static List<EquivalentAddressGroup> createWeightedServerAddrs(long... weights) {
    List<EquivalentAddressGroup> addrs = new ArrayList<>();
    for (int i = 0; i < weights.length; i++) {
      SocketAddress addr = new FakeSocketAddress("server" + i);
      Attributes attr = Attributes.newBuilder().set(
          InternalXdsAttributes.ATTR_SERVER_WEIGHT, weights[i]).build();
      EquivalentAddressGroup eag = new EquivalentAddressGroup(addr, attr);
      addrs.add(eag);
    }
    return addrs;
  }

  private static List<EquivalentAddressGroup> createRepeatedServerAddrs(long... weights) {
    List<EquivalentAddressGroup> addrs = new ArrayList<>();
    for (int i = 0; i < weights.length; i++) {
      SocketAddress addr = new FakeSocketAddress("server" + i);
      for (int j = 0; j < weights[i]; j++) {
        EquivalentAddressGroup eag = new EquivalentAddressGroup(addr);
        addrs.add(eag);
      }
    }
    return addrs;
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
