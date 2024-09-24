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
import static com.google.common.truth.Truth.assertWithMessage;
import static io.grpc.ConnectivityState.CONNECTING;
import static io.grpc.ConnectivityState.IDLE;
import static io.grpc.ConnectivityState.READY;
import static io.grpc.ConnectivityState.SHUTDOWN;
import static io.grpc.ConnectivityState.TRANSIENT_FAILURE;
import static io.grpc.util.MultiChildLoadBalancer.IS_PETIOLE_POLICY;
import static io.grpc.xds.RingHashLoadBalancerTest.InitializationFlags.DO_NOT_RESET_HELPER;
import static io.grpc.xds.RingHashLoadBalancerTest.InitializationFlags.DO_NOT_VERIFY;
import static io.grpc.xds.RingHashLoadBalancerTest.InitializationFlags.RESET_SUBCHANNEL_MOCKS;
import static io.grpc.xds.RingHashLoadBalancerTest.InitializationFlags.STAY_IN_CONNECTING;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.google.common.collect.Iterables;
import com.google.common.primitives.UnsignedInteger;
import io.grpc.Attributes;
import io.grpc.CallOptions;
import io.grpc.ConnectivityState;
import io.grpc.ConnectivityStateInfo;
import io.grpc.EquivalentAddressGroup;
import io.grpc.LoadBalancer.CreateSubchannelArgs;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancer.PickDetailsConsumer;
import io.grpc.LoadBalancer.PickResult;
import io.grpc.LoadBalancer.PickSubchannelArgs;
import io.grpc.LoadBalancer.ResolvedAddresses;
import io.grpc.LoadBalancer.Subchannel;
import io.grpc.LoadBalancer.SubchannelPicker;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.Status.Code;
import io.grpc.SynchronizationContext;
import io.grpc.internal.FakeClock;
import io.grpc.internal.PickFirstLoadBalancerProvider;
import io.grpc.internal.PickSubchannelArgsImpl;
import io.grpc.testing.TestMethodDescriptors;
import io.grpc.util.AbstractTestHelper;
import io.grpc.util.MultiChildLoadBalancer.ChildLbState;
import io.grpc.xds.RingHashLoadBalancer.RingHashConfig;
import java.lang.Thread.UncaughtExceptionHandler;
import java.net.SocketAddress;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
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
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/** Unit test for {@link io.grpc.LoadBalancer}. */
@RunWith(JUnit4.class)
public class RingHashLoadBalancerTest {
  private static final String AUTHORITY = "foo.googleapis.com";
  private static final Attributes.Key<String> CUSTOM_KEY = Attributes.Key.create("custom-key");
  private static final ConnectivityStateInfo CSI_CONNECTING =
      ConnectivityStateInfo.forNonError(CONNECTING);
  public static final ConnectivityStateInfo CSI_READY = ConnectivityStateInfo.forNonError(READY);

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
  private final Deque<Subchannel> connectionRequestedQueue = new ArrayDeque<>();
  private final XxHash64 hashFunc = XxHash64.INSTANCE;
  private final TestHelper testHelperInst = new TestHelper();
  private final Helper helper = mock(Helper.class, delegatesTo(testHelperInst));
  @Captor
  private ArgumentCaptor<SubchannelPicker> pickerCaptor;
  private RingHashLoadBalancer loadBalancer;

  @Before
  public void setUp() {
    loadBalancer = new RingHashLoadBalancer(helper);
    // Consume calls not relevant for tests that would otherwise fail verifyNoMoreInteractions
    verify(helper).getAuthority();
    verify(helper).getSynchronizationContext();
  }

  @After
  public void tearDown() {
    loadBalancer.shutdown();
    for (Subchannel subchannel : subchannels.values()) {
      verify(subchannel).shutdown();
    }
    connectionRequestedQueue.clear();
  }

  @Test
  public void subchannelLazyConnectUntilPicked() {
    RingHashConfig config = new RingHashConfig(10, 100);
    List<EquivalentAddressGroup> servers = createWeightedServerAddrs(1);  // one server
    Status addressesAcceptanceStatus = loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder()
            .setAddresses(servers).setLoadBalancingPolicyConfig(config).build());
    assertThat(addressesAcceptanceStatus.isOk()).isTrue();
    verify(helper).updateBalancingState(eq(IDLE), pickerCaptor.capture());
    assertThat(subchannels.size()).isEqualTo(0);

    // Picking subchannel triggers connection.
    PickSubchannelArgs args = getDefaultPickSubchannelArgs(hashFunc.hashVoid());
    PickResult result = pickerCaptor.getValue().pickSubchannel(args);
    assertThat(result.getStatus().isOk()).isTrue();
    assertThat(result.getSubchannel()).isNull();
    Subchannel subchannel = Iterables.getOnlyElement(subchannels.values());
    int expectedTimes = PickFirstLoadBalancerProvider.isEnabledHappyEyeballs() ? 2 : 1;
    verify(subchannel, times(expectedTimes)).requestConnection();
    verify(helper).updateBalancingState(eq(CONNECTING), any(SubchannelPicker.class));
    verify(helper).createSubchannel(any(CreateSubchannelArgs.class));
    deliverSubchannelState(subchannel, CSI_CONNECTING);
    int expectedCount = PickFirstLoadBalancerProvider.isEnabledNewPickFirst() ? 1 : 2;
    verify(helper, times(expectedCount)).updateBalancingState(eq(CONNECTING), any());

    // Subchannel becomes ready, triggers pick again.
    deliverSubchannelState(subchannel, CSI_READY);
    verify(helper).updateBalancingState(eq(READY), pickerCaptor.capture());
    result = pickerCaptor.getValue().pickSubchannel(args);
    assertThat(result.getSubchannel()).isSameInstanceAs(subchannel);
    AbstractTestHelper.verifyNoMoreMeaningfulInteractions(helper);
  }

  @Test
  public void subchannelNotAutoReconnectAfterReenteringIdle() {
    RingHashConfig config = new RingHashConfig(10, 100);
    List<EquivalentAddressGroup> servers = createWeightedServerAddrs(1);  // one server
    Status addressesAcceptanceStatus = loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder()
            .setAddresses(servers).setLoadBalancingPolicyConfig(config).build());
    assertThat(addressesAcceptanceStatus.isOk()).isTrue();
    verify(helper).updateBalancingState(eq(IDLE), pickerCaptor.capture());

    ChildLbState childLbState = loadBalancer.getChildLbStates().iterator().next();
    assertThat(subchannels.get(Collections.singletonList(childLbState.getEag()))).isNull();

    // Picking subchannel triggers connection.
    PickSubchannelArgs args = getDefaultPickSubchannelArgs(hashFunc.hashVoid());
    pickerCaptor.getValue().pickSubchannel(args);
    Subchannel subchannel = subchannels.get(Collections.singletonList(childLbState.getEag()));
    InOrder inOrder = Mockito.inOrder(helper, subchannel);
    int expectedTimes = PickFirstLoadBalancerProvider.isEnabledHappyEyeballs() ? 2 : 1;
    inOrder.verify(subchannel, times(expectedTimes)).requestConnection();
    deliverSubchannelState(subchannel, CSI_READY);
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

    initializeLbSubchannels(config, servers);

    // one in CONNECTING, one in IDLE
    deliverSubchannelState(getSubchannel(servers, 0), CSI_CONNECTING);
    inOrder.verify(helper).updateBalancingState(eq(CONNECTING), any(SubchannelPicker.class));
    verifyConnection(0);

    // two in CONNECTING
    deliverSubchannelState(getSubchannel(servers, 1), CSI_CONNECTING);
    inOrder.verify(helper).updateBalancingState(eq(CONNECTING), any(SubchannelPicker.class));
    verifyConnection(0);

    // one in CONNECTING, one in READY
    deliverSubchannelState(getSubchannel(servers, 1), CSI_READY);
    inOrder.verify(helper).updateBalancingState(eq(READY), any(SubchannelPicker.class));
    verifyConnection(0);

    // one in TRANSIENT_FAILURE, one in READY
    deliverSubchannelState(
        getSubchannel(servers, 0),
        ConnectivityStateInfo.forTransientFailure(
            Status.UNKNOWN.withDescription("unknown failure")));
    if (PickFirstLoadBalancerProvider.isEnabledNewPickFirst()) {
      inOrder.verify(helper).updateBalancingState(eq(READY), any());
    } else {
      inOrder.verify(helper).refreshNameResolution();
      inOrder.verify(helper).updateBalancingState(eq(READY), any());
    }
    verifyConnection(0);

    // one in TRANSIENT_FAILURE, one in IDLE
    deliverSubchannelState(
        getSubchannel(servers, 1),
        ConnectivityStateInfo.forNonError(IDLE));
    if (PickFirstLoadBalancerProvider.isEnabledNewPickFirst()) {
      inOrder.verify(helper).updateBalancingState(eq(CONNECTING), any());
    } else {
      inOrder.verify(helper).refreshNameResolution();
      inOrder.verify(helper).updateBalancingState(eq(CONNECTING), any());
    }
    verifyConnection(0);
  }

  private void verifyConnection(int times) {
    for (int i = 0; i < times; i++) {
      Subchannel connectOnce = connectionRequestedQueue.poll();
      assertWithMessage("Null connection is at (%s) of (%s)", i, times)
          .that(connectOnce).isNotNull();
      clearInvocations(connectOnce);
    }
    assertThat(connectionRequestedQueue.poll()).isNull();
  }

  @Test
  public void aggregateSubchannelStates_allSubchannelsInTransientFailure() {
    RingHashConfig config = new RingHashConfig(10, 100);
    List<EquivalentAddressGroup> servers = createWeightedServerAddrs(1, 1, 1, 1);

    List<Subchannel> subChannelList = initializeLbSubchannels(config, servers, STAY_IN_CONNECTING);

    // reset inOrder to include all the childLBs now that they have been created
    clearInvocations(helper);
    InOrder inOrder = Mockito.inOrder(helper,
        subChannelList.get(0), subChannelList.get(1), subChannelList.get(2), subChannelList.get(3));

    // one in TRANSIENT_FAILURE, three in CONNECTING
    deliverNotFound(subChannelList, 0);
    refreshInvokedButNotUpdateBS(inOrder, TRANSIENT_FAILURE);

    // two in TRANSIENT_FAILURE, two in CONNECTING
    deliverNotFound(subChannelList, 1);
    refreshInvokedAndUpdateBS(inOrder, TRANSIENT_FAILURE);

    // All 4 in TF switch to TF
    deliverNotFound(subChannelList, 2);
    refreshInvokedAndUpdateBS(inOrder, TRANSIENT_FAILURE);
    deliverNotFound(subChannelList, 3);
    refreshInvokedAndUpdateBS(inOrder, TRANSIENT_FAILURE);

    // reset subchannel to CONNECTING - shouldn't change anything since PF hides the state change
    deliverSubchannelState(subChannelList.get(2), CSI_CONNECTING);
    inOrder.verify(helper, never())
        .updateBalancingState(eq(TRANSIENT_FAILURE), any(SubchannelPicker.class));
    inOrder.verify(subChannelList.get(2), never()).requestConnection();

    // three in TRANSIENT_FAILURE, one in READY
    deliverSubchannelState(subChannelList.get(2), CSI_READY);
    inOrder.verify(helper).updateBalancingState(eq(READY), any(SubchannelPicker.class));
    inOrder.verify(subChannelList.get(2), never()).requestConnection();
  }

  // Old PF and new PF reverse calling order of updateBlaancingState and refreshNameResolution
  private void refreshInvokedButNotUpdateBS(InOrder inOrder, ConnectivityState state) {
    inOrder.verify(helper, never()).updateBalancingState(eq(state), any(SubchannelPicker.class));
    inOrder.verify(helper).refreshNameResolution();
    inOrder.verify(helper, never()).updateBalancingState(eq(state), any(SubchannelPicker.class));
  }

  // Old PF and new PF reverse calling order of updateBlaancingState and refreshNameResolution
  private void refreshInvokedAndUpdateBS(InOrder inOrder, ConnectivityState state) {
    if (PickFirstLoadBalancerProvider.isEnabledNewPickFirst()) {
      inOrder.verify(helper).updateBalancingState(eq(state), any());
    }

    inOrder.verify(helper).refreshNameResolution();

    if (!PickFirstLoadBalancerProvider.isEnabledNewPickFirst()) {
      inOrder.verify(helper).updateBalancingState(eq(state), any());
    }
  }

  @Test
  public void ignoreShutdownSubchannelStateChange() {
    RingHashConfig config = new RingHashConfig(10, 100);
    List<EquivalentAddressGroup> servers = createWeightedServerAddrs(1, 1, 1);
    initializeLbSubchannels(config, servers);

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
    initializeLbSubchannels(config, servers);
    InOrder inOrder = Mockito.inOrder(helper);

    // Bring all subchannels to READY so that next pick always succeeds.
    for (Subchannel subchannel : subchannels.values()) {
      deliverSubchannelState(subchannel, CSI_READY);
      inOrder.verify(helper).updateBalancingState(eq(READY), pickerCaptor.capture());
    }

    // Simulate rpc hash hits one ring entry exactly for server1.
    long rpcHash = hashFunc.hashAsciiString("FakeSocketAddress-server1_0");
    PickSubchannelArgs args = getDefaultPickSubchannelArgs(rpcHash);
    pickerCaptor.getValue().pickSubchannel(args);
    PickResult result = pickerCaptor.getValue().pickSubchannel(args);
    Subchannel subchannel = result.getSubchannel();
    assertThat(subchannel.getAddresses()).isEqualTo(servers.get(1));

    List<EquivalentAddressGroup> updatedServers = new ArrayList<>();
    for (EquivalentAddressGroup addr : servers.subList(0, 2)) {  // only server0 and server1 left
      Attributes attr = addr.getAttributes().toBuilder().set(CUSTOM_KEY, "custom value").build();
      updatedServers.add(new EquivalentAddressGroup(addr.getAddresses(), attr));
    }
    Subchannel subchannel0_old = getSubchannel(servers, 0);
    Subchannel subchannel1_old = getSubchannel(servers, 1);
    Status addressesAcceptanceStatus = loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder()
            .setAddresses(updatedServers).setLoadBalancingPolicyConfig(config).build());
    assertThat(addressesAcceptanceStatus.isOk()).isTrue();
    verify(subchannel0_old).updateAddresses(Collections.singletonList(updatedServers.get(0)));
    verify(subchannel1_old).updateAddresses(Collections.singletonList(updatedServers.get(1)));
    inOrder.verify(helper).updateBalancingState(eq(READY), pickerCaptor.capture());
    assertThat(pickerCaptor.getValue().pickSubchannel(args).getSubchannel())
        .isSameInstanceAs(subchannel);
    verifyNoMoreInteractions(helper);
  }

  @Test
  public void deterministicPickWithNewHostsAdded() {
    RingHashConfig config = new RingHashConfig(10, 100);
    List<EquivalentAddressGroup> servers = createWeightedServerAddrs(1, 1);  // server0 and server1
    initializeLbSubchannels(config, servers, DO_NOT_VERIFY, DO_NOT_RESET_HELPER);

    InOrder inOrder = Mockito.inOrder(helper);

    // Bring all subchannels to READY so that next pick always succeeds.
    for (Subchannel subchannel : subchannels.values()) {
      deliverSubchannelState(subchannel, CSI_READY);
      inOrder.verify(helper).updateBalancingState(eq(READY), pickerCaptor.capture());
    }

    // Simulate rpc hash hits one ring entry exactly for server1.
    long rpcHash = hashFunc.hashAsciiString("FakeSocketAddress-server1_0");
    PickSubchannelArgs args = getDefaultPickSubchannelArgs(rpcHash);
    pickerCaptor.getValue().pickSubchannel(args);
    PickResult result = pickerCaptor.getValue().pickSubchannel(args);
    Subchannel subchannel = result.getSubchannel();
    assertThat(subchannel.getAddresses()).isEqualTo(servers.get(1));

    servers = createWeightedServerAddrs(1, 1, 1, 1, 1);  // server2, server3, server4 added
    Status addressesAcceptanceStatus = loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder()
            .setAddresses(servers).setLoadBalancingPolicyConfig(config).build());
    assertThat(addressesAcceptanceStatus.isOk()).isTrue();
    assertThat(loadBalancer.getChildLbStates().size()).isEqualTo(5);
    inOrder.verify(helper).updateBalancingState(eq(READY), pickerCaptor.capture());
    assertThat(pickerCaptor.getValue().pickSubchannel(args).getSubchannel())
        .isSameInstanceAs(subchannel);
    inOrder.verifyNoMoreInteractions();
  }

  private Subchannel getSubChannel(EquivalentAddressGroup eag) {
    return subchannels.get(Collections.singletonList(eag));
  }

  @Test
  public void skipFailingHosts_pickNextNonFailingHost() {
    // Map each server address to exactly one ring entry.
    RingHashConfig config = new RingHashConfig(3, 3);
    List<EquivalentAddressGroup> servers = createWeightedServerAddrs(1, 1, 1);
    Status addressesAcceptanceStatus =
        loadBalancer.acceptResolvedAddresses(
            ResolvedAddresses.newBuilder()
                .setAddresses(servers).setLoadBalancingPolicyConfig(config).build());
    assertThat(addressesAcceptanceStatus.isOk()).isTrue();

    // Create subchannel for the first address
    loadBalancer.getChildLbStateEag(servers.get(0)).getCurrentPicker()
        .pickSubchannel(getDefaultPickSubchannelArgs(hashFunc.hashVoid()));
    verifyConnection(1);

    reset(helper);
    // ring:
    //   "FakeSocketAddress-server0_0"
    //   "FakeSocketAddress-server1_0"
    //   "FakeSocketAddress-server2_0"

    long rpcHash = hashFunc.hashAsciiString("FakeSocketAddress-server0_0");
    PickSubchannelArgs args = getDefaultPickSubchannelArgs(rpcHash);

    // Bring down server0 to force trying server2.
    deliverSubchannelState(
        getSubChannel(servers.get(0)),
        ConnectivityStateInfo.forTransientFailure(
            Status.UNAVAILABLE.withDescription("unreachable")));
    verify(helper).updateBalancingState(eq(CONNECTING), pickerCaptor.capture());

    PickResult result = pickerCaptor.getValue().pickSubchannel(args);
    assertThat(result.getStatus().isOk()).isTrue();
    assertThat(result.getSubchannel()).isNull();  // buffer request
    int expectedTimes = PickFirstLoadBalancerProvider.isEnabledHappyEyeballs() ? 2 : 1;
    // verify kicked off connection to server2
    verify(getSubChannel(servers.get(1)), times(expectedTimes)).requestConnection();
    assertThat(subchannels.size()).isEqualTo(2);  // no excessive connection

    deliverSubchannelState(getSubChannel(servers.get(1)), CSI_CONNECTING);
    verify(helper, atLeast(1))
        .updateBalancingState(eq(CONNECTING), pickerCaptor.capture());

    result = pickerCaptor.getValue().pickSubchannel(args);
    assertThat(result.getStatus().isOk()).isTrue();
    assertThat(result.getSubchannel()).isNull();  // buffer request

    deliverSubchannelState(getSubChannel(servers.get(1)), CSI_READY);
    verify(helper).updateBalancingState(eq(READY), pickerCaptor.capture());

    result = pickerCaptor.getValue().pickSubchannel(args);
    assertThat(result.getStatus().isOk()).isTrue();
    assertThat(result.getSubchannel().getAddresses()).isEqualTo(servers.get(1));
  }

  private PickSubchannelArgs getDefaultPickSubchannelArgs(long rpcHash) {
    return new PickSubchannelArgsImpl(
        TestMethodDescriptors.voidMethod(), new Metadata(),
        CallOptions.DEFAULT.withOption(XdsNameResolver.RPC_HASH_KEY, rpcHash),
        new PickDetailsConsumer() {});
  }

  private PickSubchannelArgs getDefaultPickSubchannelArgsForServer(int serverid) {
    long rpcHash = hashFunc.hashAsciiString("FakeSocketAddress-server" + serverid + "_0");
    return getDefaultPickSubchannelArgs(rpcHash);
  }

  @Test
  public void skipFailingHosts_firstTwoHostsFailed_pickNextFirstReady() {
    // Map each server address to exactly one ring entry.
    RingHashConfig config = new RingHashConfig(3, 3);
    List<EquivalentAddressGroup> servers = createWeightedServerAddrs(1, 1, 1);

    initializeLbSubchannels(config, servers);

    // ring:
    //   "FakeSocketAddress-server0_0"
    //   "FakeSocketAddress-server1_0"
    //   "FakeSocketAddress-server2_0"

    long rpcHash = hashFunc.hashAsciiString("FakeSocketAddress-server1_0");
    PickSubchannelArgs args = getDefaultPickSubchannelArgs(rpcHash);

    // Bring down server0 and server2 to force trying server1.
    deliverSubchannelState(
        getSubchannel(servers, 1),
        ConnectivityStateInfo.forTransientFailure(
            Status.UNAVAILABLE.withDescription("unreachable")));
    deliverSubchannelState(
        getSubchannel(servers, 2),
        ConnectivityStateInfo.forTransientFailure(
            Status.PERMISSION_DENIED.withDescription("permission denied")));
    verify(helper).updateBalancingState(eq(CONNECTING), pickerCaptor.capture());
    verifyConnection(0);
    PickResult result = pickerCaptor.getValue().pickSubchannel(args); // activate last subchannel
    assertThat(result.getStatus().isOk()).isTrue();
    int expectedCount = PickFirstLoadBalancerProvider.isEnabledNewPickFirst() ? 0 : 1;
    verifyConnection(expectedCount);

    deliverSubchannelState(
        getSubchannel(servers, 0),
        ConnectivityStateInfo.forTransientFailure(
            Status.PERMISSION_DENIED.withDescription("permission denied again")));
    verify(helper, times(2)).updateBalancingState(eq(TRANSIENT_FAILURE), pickerCaptor.capture());
    result = pickerCaptor.getValue().pickSubchannel(args);
    assertThat(result.getStatus().isOk()).isFalse();  // fail the RPC
    assertThat(result.getStatus().getCode())
        .isEqualTo(Code.UNAVAILABLE);  // with error status for the original server hit by hash
    assertThat(result.getStatus().getDescription()).isEqualTo("unreachable");

    // Now connecting to server1.
    deliverSubchannelState(getSubchannel(servers, 1), CSI_CONNECTING);

    reset(helper);

    result = pickerCaptor.getValue().pickSubchannel(args);
    assertThat(result.getStatus().isOk()).isFalse();  // fail the RPC
    assertThat(result.getStatus().getCode())
        .isEqualTo(Code.UNAVAILABLE);  // with error status for the original server hit by hash
    assertThat(result.getStatus().getDescription()).isEqualTo("unreachable");

    // Simulate server1 becomes READY.
    deliverSubchannelState(getSubchannel(servers, 1), CSI_READY);
    verify(helper).updateBalancingState(eq(READY), pickerCaptor.capture());

    SubchannelPicker picker = pickerCaptor.getValue();
    result = picker.pickSubchannel(args);
    assertThat(result.getStatus().isOk()).isTrue();  // succeed
    assertThat(result.getSubchannel().getAddresses()).isEqualTo(servers.get(1));  // with server1
    assertThat(picker.pickSubchannel(getDefaultPickSubchannelArgsForServer(0))).isEqualTo(result);
    assertThat(picker.pickSubchannel(getDefaultPickSubchannelArgsForServer(2))).isEqualTo(result);
  }

  @Test
  public void removingAddressShutdownSubchannel() {
    // Map each server address to exactly one ring entry.
    RingHashConfig config = new RingHashConfig(3, 3);
    List<EquivalentAddressGroup> svs1 = createWeightedServerAddrs(1, 1, 1);
    List<Subchannel> subchannels1 = initializeLbSubchannels(config, svs1, STAY_IN_CONNECTING);

    List<EquivalentAddressGroup> svs2 = createWeightedServerAddrs(1, 1);
    InOrder inOrder = Mockito.inOrder(helper, subchannels1.get(2));
    // send LB the missing address
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder()
            .setAddresses(svs2).setLoadBalancingPolicyConfig(config).build());
    inOrder.verify(helper).updateBalancingState(eq(CONNECTING), any());
    inOrder.verify(subchannels1.get(2)).shutdown();
  }

  @Test
  public void allSubchannelsInTransientFailure() {
    // Map each server address to exactly one ring entry.
    RingHashConfig config = new RingHashConfig(3, 3);
    List<EquivalentAddressGroup> servers = createWeightedServerAddrs(1, 1, 1);
    initializeLbSubchannels(config, servers);

    // Bring all subchannels to TRANSIENT_FAILURE.
    for (Subchannel subchannel : subchannels.values()) {
      deliverSubchannelState(subchannel, ConnectivityStateInfo.forTransientFailure(
          Status.UNAVAILABLE.withDescription(
              subchannel.getAddresses().getAddresses() + " unreachable")));
    }
    verify(helper, atLeastOnce())
        .updateBalancingState(eq(TRANSIENT_FAILURE), pickerCaptor.capture());
    verifyConnection(0);

    // Picking subchannel triggers connection. RPC hash hits server0.
    PickSubchannelArgs args = getDefaultPickSubchannelArgsForServer(0);
    PickResult result = pickerCaptor.getValue().pickSubchannel(args);
    assertThat(result.getStatus().isOk()).isFalse();
    assertThat(result.getStatus().getCode()).isEqualTo(Code.UNAVAILABLE);
    assertThat(result.getStatus().getDescription())
        .isEqualTo("[FakeSocketAddress-server0] unreachable");
    verifyConnection(0); // TF has already started taking care of this, pick doesn't need to
  }

  @Test
  public void firstSubchannelIdle() {
    // Map each server address to exactly one ring entry.
    RingHashConfig config = new RingHashConfig(3, 3);
    List<EquivalentAddressGroup> servers = createWeightedServerAddrs(1, 1, 1);
    initializeLbSubchannels(config, servers);

    // Go to TF does nothing, though PF will try to reconnect after backoff
    deliverSubchannelState(getSubchannel(servers, 1),
        ConnectivityStateInfo.forTransientFailure(
        Status.UNAVAILABLE.withDescription("unreachable")));
    verify(helper).updateBalancingState(eq(CONNECTING), pickerCaptor.capture());
    verifyConnection(0);

    // Picking subchannel triggers connection. RPC hash hits server0.
    PickSubchannelArgs args = getDefaultPickSubchannelArgs(hashFunc.hashVoid());
    PickResult result = pickerCaptor.getValue().pickSubchannel(args);
    assertThat(result.getStatus().isOk()).isTrue();
    verifyConnection(1);
  }

  @Test
  public void firstSubchannelConnecting() {
    // Map each server address to exactly one ring entry.
    RingHashConfig config = new RingHashConfig(3, 3);
    List<EquivalentAddressGroup> servers = createWeightedServerAddrs(1, 1, 1);
    initializeLbSubchannels(config, servers);

    deliverSubchannelState(getSubchannel(servers, 0), CSI_CONNECTING);
    deliverSubchannelState(getSubchannel(servers, 1), CSI_CONNECTING);
    verify(helper, times(2)).updateBalancingState(eq(CONNECTING), pickerCaptor.capture());

    // Picking subchannel triggers connection.
    PickSubchannelArgs args = getDefaultPickSubchannelArgs(hashFunc.hashVoid());
    PickResult result = pickerCaptor.getValue().pickSubchannel(args);
    assertThat(result.getStatus().isOk()).isTrue();
    verify(getSubchannel(servers, 0), never()).requestConnection();
    verify(getSubchannel(servers, 1), never()).requestConnection();
    verify(getSubchannel(servers, 2), never()).requestConnection();
  }

  private Subchannel getSubchannel(List<EquivalentAddressGroup> servers, int serverIndex) {
    return subchannels.get(Collections.singletonList(servers.get(serverIndex)));
  }

  @Test
  public void firstSubchannelFailure() {
    // Map each server address to exactly one ring entry.
    RingHashConfig config = new RingHashConfig(3, 3);
    List<EquivalentAddressGroup> servers = createWeightedServerAddrs(1, 1, 1);

    List<Subchannel> subchannelList =
        initializeLbSubchannels(config, servers, RESET_SUBCHANNEL_MOCKS);

    // ring:
    //   "FakeSocketAddress-server1_0"
    //   "FakeSocketAddress-server0_0"
    //   "FakeSocketAddress-server2_0"

    deliverSubchannelState(subchannelList.get(0),
        ConnectivityStateInfo.forTransientFailure(
            Status.UNAVAILABLE.withDescription("unreachable")));
    verify(helper).updateBalancingState(eq(CONNECTING), pickerCaptor.capture());
    verifyConnection(0);

    // Per GRFC A61 Picking subchannel should no longer request connections that were failing
    PickSubchannelArgs args = getDefaultPickSubchannelArgs(hashFunc.hashVoid());
    SubchannelPicker picker1 = pickerCaptor.getValue();
    PickResult result = picker1.pickSubchannel(args);
    assertThat(result.getStatus().isOk()).isTrue();
    assertThat(result.getSubchannel()).isNull();
    verify(subchannelList.get(0), never()).requestConnection(); // In TF
    verify(subchannelList.get(1)).requestConnection();
    verify(subchannelList.get(2), never()).requestConnection(); // Not one of the first 2
  }

  @Test
  public void secondSubchannelConnecting() {
    // Map each server address to exactly one ring entry.
    RingHashConfig config = new RingHashConfig(3, 3);
    List<EquivalentAddressGroup> servers = createWeightedServerAddrs(1, 1, 1);

    initializeLbSubchannels(config, servers);

    // ring:
    //   "FakeSocketAddress-server1_0"
    //   "FakeSocketAddress-server0_0"
    //   "FakeSocketAddress-server2_0"

    Subchannel firstSubchannel = getSubchannel(servers, 0);
    deliverSubchannelUnreachable(firstSubchannel);
    verifyConnection(0);

    deliverSubchannelState(getSubchannel(servers, 2), CSI_CONNECTING);
    verify(helper, times(2)).updateBalancingState(eq(CONNECTING), pickerCaptor.capture());
    verifyConnection(0);

    // Picking subchannel when idle triggers connection.
    deliverSubchannelState(getSubchannel(servers, 2),
        ConnectivityStateInfo.forNonError(IDLE));
    verifyConnection(0);
    PickSubchannelArgs args = getDefaultPickSubchannelArgs(hashFunc.hashVoid());
    PickResult result = pickerCaptor.getValue().pickSubchannel(args);
    assertThat(result.getStatus().isOk()).isTrue();
    verifyConnection(1);
  }

  @Test
  public void secondSubchannelFailure() {
    // Map each server address to exactly one ring entry.
    RingHashConfig config = new RingHashConfig(3, 3);
    List<EquivalentAddressGroup> servers = createWeightedServerAddrs(1, 1, 1);

    initializeLbSubchannels(config, servers);

    // ring:
    //   "FakeSocketAddress-server1_0"
    //   "FakeSocketAddress-server0_0"
    //   "FakeSocketAddress-server2_0"

    Subchannel firstSubchannel = getSubchannel(servers, 0);
    deliverSubchannelUnreachable(firstSubchannel);
    deliverSubchannelUnreachable(getSubchannel(servers, 2));
    verify(helper).updateBalancingState(eq(TRANSIENT_FAILURE), pickerCaptor.capture());
    verifyConnection(0);

    // Picking subchannel triggers connection.
    PickSubchannelArgs args = getDefaultPickSubchannelArgs(hashFunc.hashVoid());
    PickResult result = pickerCaptor.getValue().pickSubchannel(args);
    assertThat(result.getStatus().isOk()).isTrue();
    verify(getSubchannel(servers, 1)).requestConnection();
    verifyConnection(1);
  }

  @Test
  public void thirdSubchannelConnecting() {
    // Map each server address to exactly one ring entry.
    RingHashConfig config = new RingHashConfig(3, 3);
    List<EquivalentAddressGroup> servers = createWeightedServerAddrs(1, 1, 1);

    initializeLbSubchannels(config, servers);

    // ring:
    //   "FakeSocketAddress-server1_0"
    //   "FakeSocketAddress-server0_0"
    //   "FakeSocketAddress-server2_0"

    Subchannel firstSubchannel = getSubchannel(servers, 0);

    deliverSubchannelUnreachable(firstSubchannel);
    deliverSubchannelUnreachable(getSubchannel(servers, 2));
    deliverSubchannelState(getSubchannel(servers, 1), CSI_CONNECTING);
    verify(helper, atLeastOnce())
        .updateBalancingState(eq(TRANSIENT_FAILURE), pickerCaptor.capture());
    verifyConnection(0);

    // Picking subchannel should not trigger connection per gRFC A61.
    PickSubchannelArgs args = getDefaultPickSubchannelArgs(hashFunc.hashVoid());
    PickResult result = pickerCaptor.getValue().pickSubchannel(args);
    assertThat(result.getStatus().isOk()).isTrue();
    verifyConnection(0);
  }

  @Test
  public void stickyTransientFailure() {
    // Map each server address to exactly one ring entry.
    RingHashConfig config = new RingHashConfig(3, 3);
    List<EquivalentAddressGroup> servers = createWeightedServerAddrs(1, 1, 1);

    initializeLbSubchannels(config, servers);

    // Bring one subchannel to TRANSIENT_FAILURE.
    Subchannel firstSubchannel = getSubchannel(servers, 0);
    deliverSubchannelUnreachable(firstSubchannel);

    verify(helper).updateBalancingState(eq(CONNECTING), pickerCaptor.capture());
    verifyConnection(0);

    reset(helper);
    deliverSubchannelState(firstSubchannel, ConnectivityStateInfo.forNonError(IDLE));
    // Should not have called updateBalancingState on the helper again because PickFirst is
    // shielding the higher level from the state change.
    verify(helper, never()).updateBalancingState(any(), any());
    verifyConnection(PickFirstLoadBalancerProvider.isEnabledNewPickFirst() ? 0 : 1);

    // Picking subchannel triggers connection on second address. RPC hash hits server0.
    PickSubchannelArgs args = getDefaultPickSubchannelArgs(hashFunc.hashVoid());
    PickResult result = pickerCaptor.getValue().pickSubchannel(args);
    assertThat(result.getStatus().isOk()).isTrue();
    verify(getSubchannel(servers, 1)).requestConnection();
    verify(getSubchannel(servers, 2), never()).requestConnection();
  }

  @Test
  public void largeWeights() {
    RingHashConfig config = new RingHashConfig(10000, 100000);  // large ring
    List<EquivalentAddressGroup> servers =
        createWeightedServerAddrs(Integer.MAX_VALUE, 10, 100); // MAX:10:100

    initializeLbSubchannels(config, servers);

    // Try value between max signed and max unsigned int
    servers = createWeightedServerAddrs(Integer.MAX_VALUE + 100L, 100); // (MAX+100):100
    Status addressesAcceptanceStatus = loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder()
            .setAddresses(servers).setLoadBalancingPolicyConfig(config).build());
    assertThat(addressesAcceptanceStatus.isOk()).isTrue();

    // Try a negative value
    servers = createWeightedServerAddrs(10, -20, 100); // 10:-20:100
    addressesAcceptanceStatus = loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder()
            .setAddresses(servers).setLoadBalancingPolicyConfig(config).build());
    assertThat(addressesAcceptanceStatus.isOk()).isFalse();

    // Try an individual value larger than max unsigned int
    long maxUnsigned = UnsignedInteger.MAX_VALUE.longValue();
    servers = createWeightedServerAddrs(maxUnsigned + 10, 10, 100); // uMAX+10:10:100
    addressesAcceptanceStatus = loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder()
            .setAddresses(servers).setLoadBalancingPolicyConfig(config).build());
    assertThat(addressesAcceptanceStatus.isOk()).isFalse();

    // Try a sum of values larger than max unsigned int
    servers = createWeightedServerAddrs(Integer.MAX_VALUE, Integer.MAX_VALUE, 100); // MAX:MAX:100
    addressesAcceptanceStatus = loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder()
            .setAddresses(servers).setLoadBalancingPolicyConfig(config).build());
    assertThat(addressesAcceptanceStatus.isOk()).isFalse();
  }

  @Test
  public void hostSelectionProportionalToWeights() {
    RingHashConfig config = new RingHashConfig(10000, 100000);  // large ring
    List<EquivalentAddressGroup> servers = createWeightedServerAddrs(1, 10, 100); // 1:10:100

    initializeLbSubchannels(config, servers);

    // Bring all subchannels to READY.
    Map<EquivalentAddressGroup, Integer> pickCounts = new HashMap<>();
    for (Subchannel subchannel : subchannels.values()) {
      deliverSubchannelState(subchannel, CSI_READY);
      pickCounts.put(subchannel.getAddresses(), 0);
    }
    verify(helper, times(3)).updateBalancingState(eq(READY), pickerCaptor.capture());
    SubchannelPicker picker = pickerCaptor.getValue();

    for (int i = 0; i < 10000; i++) {
      long hash = hashFunc.hashInt(i);
      PickSubchannelArgs args = getDefaultPickSubchannelArgs(hash);
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

    initializeLbSubchannels(config, servers, DO_NOT_VERIFY, DO_NOT_RESET_HELPER);
    verify(helper).createSubchannel(any(CreateSubchannelArgs.class));
    verify(helper, times(2)).updateBalancingState(eq(IDLE), pickerCaptor.capture());

    // Picking subchannel triggers subchannel creation and connection.
    PickSubchannelArgs args = getDefaultPickSubchannelArgs(hashFunc.hashVoid());
    pickerCaptor.getValue().pickSubchannel(args);
    verify(helper, never()).updateBalancingState(eq(READY), any(SubchannelPicker.class));
    deliverSubchannelState(
        Iterables.getOnlyElement(subchannels.values()), CSI_READY);
    verify(helper).updateBalancingState(eq(READY), any(SubchannelPicker.class));
    reset(helper);

    loadBalancer.handleNameResolutionError(Status.NOT_FOUND.withDescription("target not found"));
    verifyNoMoreInteractions(helper);
  }

  @Test
  public void duplicateAddresses() {
    RingHashConfig config = new RingHashConfig(10, 100);
    List<EquivalentAddressGroup> servers = createRepeatedServerAddrs(1, 2, 3);

    initializeLbSubchannels(config, servers, DO_NOT_VERIFY);

    verify(helper).updateBalancingState(eq(TRANSIENT_FAILURE), pickerCaptor.capture());

    PickSubchannelArgs args = getDefaultPickSubchannelArgs(hashFunc.hashVoid());
    PickResult result = pickerCaptor.getValue().pickSubchannel(args);
    assertThat(result.getStatus().isOk()).isFalse();  // fail the RPC
    assertThat(result.getStatus().getCode())
        .isEqualTo(Code.UNAVAILABLE);  // with error status for the original server hit by hash
    String description = result.getStatus().getDescription();
    assertThat(description).startsWith(
        "Ring hash lb error: EDS resolution was successful, but there were duplicate addresses: ");
    assertThat(description).contains("Address: FakeSocketAddress-server1, count: 2");
    assertThat(description).contains("Address: FakeSocketAddress-server2, count: 3");
  }

  private List<Subchannel> initializeLbSubchannels(RingHashConfig config,
      List<EquivalentAddressGroup> servers, InitializationFlags... initFlags) {

    boolean doVerifies = true;
    boolean resetSubchannels = false;
    boolean returnToIdle = true;
    boolean resetHelper = true;
    for (InitializationFlags flag : initFlags) {
      switch (flag) {
        case DO_NOT_VERIFY:
          doVerifies = false;
          break;
        case RESET_SUBCHANNEL_MOCKS:
          resetSubchannels = true;
          break;
        case STAY_IN_CONNECTING:
          returnToIdle = false;
          break;
        case DO_NOT_RESET_HELPER:
          resetHelper = false;
          break;
        default:
          throw new IllegalArgumentException("Unrecognized flag: " + flag);
      }
    }

    Status addressesAcceptanceStatus =
        loadBalancer.acceptResolvedAddresses(
            ResolvedAddresses.newBuilder()
                .setAddresses(servers).setLoadBalancingPolicyConfig(config).build());

    if (doVerifies) {
      assertThat(addressesAcceptanceStatus.isOk()).isTrue();
      verify(helper).updateBalancingState(eq(IDLE), any(SubchannelPicker.class));
    }

    if (!addressesAcceptanceStatus.isOk()) {
      return new ArrayList<>();
    }

    // Activate them all to create the child LB and subchannel
    for (ChildLbState childLbState : loadBalancer.getChildLbStates()) {
      childLbState.getCurrentPicker()
          .pickSubchannel(getDefaultPickSubchannelArgs(hashFunc.hashVoid()));
      assertThat(childLbState.getResolvedAddresses().getAttributes().get(IS_PETIOLE_POLICY))
          .isTrue();
    }

    if (doVerifies) {
      verify(helper, times(servers.size())).createSubchannel(any(CreateSubchannelArgs.class));
      verify(helper, times(servers.size()))
          .updateBalancingState(eq(CONNECTING), any(SubchannelPicker.class));
      verifyConnection(servers.size());
    }

    if (returnToIdle) {
      for (Subchannel subchannel : subchannels.values()) {
        deliverSubchannelState(subchannel, ConnectivityStateInfo.forNonError(IDLE));
      }
      if (doVerifies) {
        verify(helper, times(2 * servers.size() - 1))
            .updateBalancingState(eq(CONNECTING), any(SubchannelPicker.class));
        verify(helper, times(2)).updateBalancingState(eq(IDLE), any(SubchannelPicker.class));
      }
    }


    // Get a list of subchannels in the same order as servers
    List<Subchannel> subchannelList = new ArrayList<>();
    for (EquivalentAddressGroup server : servers) {
      List<EquivalentAddressGroup> singletonList = Collections.singletonList(server);
      Subchannel subchannel = subchannels.get(singletonList);
      subchannelList.add(subchannel);
      if (resetSubchannels) {
        reset(subchannel);
      }
    }

    if (resetHelper) {
      reset(helper);
    }

    return subchannelList;
  }

  private void deliverSubchannelState(Subchannel subchannel, ConnectivityStateInfo state) {
    testHelperInst.deliverSubchannelState(subchannel, state);
  }

  private void deliverNotFound(List<Subchannel> subChannelList, int index) {
    deliverSubchannelState(
        subChannelList.get(index),
        ConnectivityStateInfo.forTransientFailure(
            Status.UNAVAILABLE.withDescription("also not found")));
  }

  protected void deliverSubchannelUnreachable(Subchannel subchannel) {
    deliverSubchannelState(subchannel,
        ConnectivityStateInfo.forTransientFailure(
            Status.UNAVAILABLE.withDescription(
                subchannel.getAddresses().getAddresses() + "unreachable")));

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

  private class TestHelper extends AbstractTestHelper {
    public TestHelper() {
      super(new FakeClock(), syncContext);
    }

    @Override
    public Map<List<EquivalentAddressGroup>, Subchannel> getSubchannelMap() {
      return subchannels;
    }

    @Override
    public String getAuthority() {
      return AUTHORITY;
    }

    private Subchannel getMockSubchannel(Subchannel realSubchannel) {
      return realToMockSubChannelMap.get(realSubchannel);
    }

    @Override
    protected AbstractTestHelper.TestSubchannel createRealSubchannel(CreateSubchannelArgs args) {
      return new RingHashTestSubchannel(args);
    }

    private class RingHashTestSubchannel extends AbstractTestHelper.TestSubchannel {

      RingHashTestSubchannel(CreateSubchannelArgs args) {
        super(args);
      }

      @Override
      public void requestConnection() {
        connectionRequestedQueue.offer(getMockSubchannel(this));
      }

    }
  }

  enum InitializationFlags {
    DO_NOT_VERIFY,
    RESET_SUBCHANNEL_MOCKS,
    STAY_IN_CONNECTING,
    DO_NOT_RESET_HELPER
  }
}
