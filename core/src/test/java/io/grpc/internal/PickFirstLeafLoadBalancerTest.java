/*
 * Copyright 2023 The gRPC Authors
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

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.truth.Truth.assertThat;
import static io.grpc.ConnectivityState.CONNECTING;
import static io.grpc.ConnectivityState.IDLE;
import static io.grpc.ConnectivityState.READY;
import static io.grpc.ConnectivityState.TRANSIENT_FAILURE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.google.common.collect.Lists;
import io.grpc.Attributes;
import io.grpc.ConnectivityState;
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
import io.grpc.Status;
import io.grpc.Status.Code;
import io.grpc.SynchronizationContext;
import io.grpc.internal.PickFirstLeafLoadBalancer.PickFirstLeafLoadBalancerConfig;
import java.net.SocketAddress;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
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
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;


/** Unit test for {@link PickFirstLeafLoadBalancer}. */
@RunWith(JUnit4.class)
public class PickFirstLeafLoadBalancerTest {
  private PickFirstLeafLoadBalancer loadBalancer;
  private final List<EquivalentAddressGroup> servers = Lists.newArrayList();
  private static final Attributes.Key<String> FOO = Attributes.Key.create("foo");
  private final SynchronizationContext syncContext = new SynchronizationContext(
      new Thread.UncaughtExceptionHandler() {
        @Override
        public void uncaughtException(Thread t, Throwable e) {
          throw new AssertionError(e);
        }
      });
  private final Attributes affinity = Attributes.newBuilder().set(FOO, "bar").build();
  @Rule
  public final MockitoRule mocks = MockitoJUnit.rule();
  @Captor
  private ArgumentCaptor<SubchannelPicker> pickerCaptor;
  @Captor
  private ArgumentCaptor<ConnectivityState> connectivityStateCaptor;
  @Captor
  private ArgumentCaptor<CreateSubchannelArgs> createArgsCaptor;
  @Captor
  private ArgumentCaptor<SubchannelStateListener> stateListenerCaptor;
  @Mock
  private Helper mockHelper;
  @Mock
  private FakeSubchannel mockSubchannel1;
  @Mock
  private FakeSubchannel mockSubchannel2;
  @Mock
  private FakeSubchannel mockSubchannel3;
  @Mock
  private FakeSubchannel mockSubchannel4;
  @Mock // This LoadBalancer doesn't use any of the arg fields, as verified in tearDown().
  private PickSubchannelArgs mockArgs;

  @Before
  public void setUp() {
    for (int i = 1; i < 5; i++) {
      SocketAddress addr = new FakeSocketAddress("server" + i);
      servers.add(new EquivalentAddressGroup(addr));
    }
    mockSubchannel1 = new FakeSubchannel(Lists.newArrayList(
        new EquivalentAddressGroup(new FakeSocketAddress("fake"))), null);
    mockSubchannel1 = mock(FakeSubchannel.class);
    mockSubchannel2 = mock(FakeSubchannel.class);
    mockSubchannel3 = mock(FakeSubchannel.class);
    mockSubchannel4 = mock(FakeSubchannel.class);
    when(mockHelper.createSubchannel(any(CreateSubchannelArgs.class)))
        .thenReturn(mockSubchannel1, mockSubchannel2, mockSubchannel3, mockSubchannel4);

    when(mockSubchannel1.getAllAddresses()).thenReturn(Lists.newArrayList(servers.get(0)));
    when(mockSubchannel2.getAllAddresses()).thenReturn(Lists.newArrayList(servers.get(1)));
    when(mockSubchannel3.getAllAddresses()).thenReturn(Lists.newArrayList(servers.get(2)));
    when(mockSubchannel4.getAllAddresses()).thenReturn(Lists.newArrayList(servers.get(3)));

    when(mockHelper.getSynchronizationContext()).thenReturn(syncContext);
    loadBalancer = new PickFirstLeafLoadBalancer(mockHelper);
  }

  @After
  public void tearDown() throws Exception {
    verifyNoMoreInteractions(mockArgs);
  }

  @Test
  public void pickAfterResolved() throws Exception {
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(servers).setAttributes(affinity).build());
    verify(mockHelper, times(4)).createSubchannel(createArgsCaptor.capture());
    List<CreateSubchannelArgs> argsList = createArgsCaptor.getAllValues();
    assertThat(argsList.get(0).getAddresses().get(0)).isEqualTo(servers.get(0));
    assertThat(argsList.get(1).getAddresses().get(0)).isEqualTo(servers.get(1));
    assertThat(argsList.get(2).getAddresses().get(0)).isEqualTo(servers.get(2));
    assertThat(argsList.get(3).getAddresses().get(0)).isEqualTo(servers.get(3));
    assertThat(argsList.get(0).getAddresses().size()).isEqualTo(1);
    assertThat(argsList.get(1).getAddresses().size()).isEqualTo(1);
    assertThat(argsList.get(2).getAddresses().size()).isEqualTo(1);
    assertThat(argsList.get(3).getAddresses().size()).isEqualTo(1);
    verify(mockHelper).updateBalancingState(eq(CONNECTING), pickerCaptor.capture());
    verify(mockSubchannel1).requestConnection();

    // Calling pickSubchannel() twice gave the same result
    assertEquals(pickerCaptor.getValue().pickSubchannel(mockArgs),
        pickerCaptor.getValue().pickSubchannel(mockArgs));

    verifyNoMoreInteractions(mockHelper);
  }

  @Test
  public void pickAfterResolved_shuffle() throws Exception {
    servers.remove(3);
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(servers).setAttributes(affinity)
            .setLoadBalancingPolicyConfig(new PickFirstLeafLoadBalancerConfig(true, 123L)).build());

    verify(mockHelper, times(3)).createSubchannel(createArgsCaptor.capture());
    List<CreateSubchannelArgs> argsList = createArgsCaptor.getAllValues();
    // We should still see the same set of addresses.
    // Because we use a fixed seed, the addresses should always be shuffled in this order.
    assertThat(argsList.get(0).getAddresses().get(0)).isEqualTo(servers.get(1));
    assertThat(argsList.get(1).getAddresses().get(0)).isEqualTo(servers.get(0));
    assertThat(argsList.get(2).getAddresses().get(0)).isEqualTo(servers.get(2));
    assertThat(argsList.get(0).getAddresses().size()).isEqualTo(1);
    assertThat(argsList.get(1).getAddresses().size()).isEqualTo(1);
    assertThat(argsList.get(2).getAddresses().size()).isEqualTo(1);

    verify(mockHelper).updateBalancingState(eq(CONNECTING), pickerCaptor.capture());
    verify(mockSubchannel1).requestConnection();

    // Calling pickSubchannel() twice gave the same result
    assertEquals(pickerCaptor.getValue().pickSubchannel(mockArgs),
        pickerCaptor.getValue().pickSubchannel(mockArgs));

    verifyNoMoreInteractions(mockHelper);
  }

  @Test
  public void pickAfterResolved_noShuffle() throws Exception {
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(servers).setAttributes(affinity)
            .setLoadBalancingPolicyConfig(new PickFirstLeafLoadBalancerConfig(false)).build());

    verify(mockHelper, times(4)).createSubchannel(createArgsCaptor.capture());
    List<CreateSubchannelArgs> argsList = createArgsCaptor.getAllValues();
    assertThat(argsList.get(0).getAddresses().get(0)).isEqualTo(servers.get(0));
    assertThat(argsList.get(1).getAddresses().get(0)).isEqualTo(servers.get(1));
    assertThat(argsList.get(2).getAddresses().get(0)).isEqualTo(servers.get(2));
    assertThat(argsList.get(3).getAddresses().get(0)).isEqualTo(servers.get(3));
    assertThat(argsList.get(0).getAddresses().size()).isEqualTo(1);
    assertThat(argsList.get(1).getAddresses().size()).isEqualTo(1);
    assertThat(argsList.get(2).getAddresses().size()).isEqualTo(1);
    assertThat(argsList.get(3).getAddresses().size()).isEqualTo(1);
    verify(mockHelper).updateBalancingState(eq(CONNECTING), pickerCaptor.capture());
    verify(mockSubchannel1).requestConnection();

    // Calling pickSubchannel() twice gave the same result
    assertEquals(pickerCaptor.getValue().pickSubchannel(mockArgs),
        pickerCaptor.getValue().pickSubchannel(mockArgs));

    verifyNoMoreInteractions(mockHelper);
  }

  @Test
  public void requestConnectionPicker() throws Exception {
    // Set up
    assertEquals(IDLE, loadBalancer.getConnectivityState());
    List<EquivalentAddressGroup> newServers = Lists.newArrayList(servers.get(0), servers.get(1),
        servers.get(2));

    // Accepting resolved addresses
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(newServers).setAttributes(affinity).build());

    InOrder inOrder = inOrder(mockHelper, mockSubchannel1, mockSubchannel2, mockSubchannel3);

    // We initialize and start all subchannels
    inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
    inOrder.verify(mockSubchannel1).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener = stateListenerCaptor.getValue();
    inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
    inOrder.verify(mockSubchannel2).start(stateListenerCaptor.capture());
    inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
    inOrder.verify(mockSubchannel3).start(stateListenerCaptor.capture());

    // We start connection attempt to the first address in the list
    inOrder.verify(mockHelper).updateBalancingState(eq(CONNECTING), any(SubchannelPicker.class));
    assertEquals(CONNECTING, loadBalancer.getConnectivityState());
    inOrder.verify(mockSubchannel1).requestConnection();
    assertEquals(CONNECTING, loadBalancer.getConnectivityState());

    // If we send the first subchannel into idle ...
    stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(IDLE));
    inOrder.verify(mockHelper).updateBalancingState(eq(IDLE), pickerCaptor.capture());

    SubchannelPicker picker = pickerCaptor.getValue();

    // Calling pickSubchannel() requests a connection, gives the same result when called twice.
    assertEquals(picker.pickSubchannel(mockArgs), picker.pickSubchannel(mockArgs));

    // But the picker calls requestConnection() only once for a total of two connection requests.
    inOrder.verify(mockSubchannel1).requestConnection();
    verify(mockSubchannel1, times(2)).requestConnection();
  }

  @Test
  public void refreshNameResolutionAfterSubchannelConnectionBroken() {
    List<EquivalentAddressGroup> newServers = Lists.newArrayList(servers.get(0));
    when(mockSubchannel1.getAllAddresses()).thenReturn(Lists.newArrayList(servers.get(0)));

    // accept resolved addresses
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(newServers).setAttributes(affinity).build());
    InOrder inOrder = inOrder(mockHelper, mockSubchannel1);
    verify(mockHelper).createSubchannel(createArgsCaptor.capture());
    inOrder.verify(mockSubchannel1).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener = stateListenerCaptor.getValue();
    inOrder.verify(mockHelper).updateBalancingState(eq(CONNECTING), pickerCaptor.capture());
    assertNull(pickerCaptor.getValue().pickSubchannel(mockArgs).getSubchannel());
    inOrder.verify(mockSubchannel1).requestConnection();

    Status error = Status.UNAUTHENTICATED.withDescription("permission denied");
    stateListener.onSubchannelState(ConnectivityStateInfo.forTransientFailure(error));
    inOrder.verify(mockHelper).refreshNameResolution();
    inOrder.verify(mockHelper).updateBalancingState(eq(TRANSIENT_FAILURE), pickerCaptor.capture());
    assertEquals(error, pickerCaptor.getValue().pickSubchannel(mockArgs).getStatus());
    stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
    inOrder.verify(mockHelper).updateBalancingState(eq(READY), pickerCaptor.capture());
    assertEquals(mockSubchannel1, pickerCaptor.getValue().pickSubchannel(mockArgs).getSubchannel());

    // Simulate receiving go-away so the subchannel transit to IDLE.
    stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(IDLE));
    inOrder.verify(mockHelper).refreshNameResolution();
    inOrder.verify(mockHelper).updateBalancingState(eq(IDLE), any(SubchannelPicker.class));
  }

  @Test
  public void pickAfterResolvedAndUnchanged() throws Exception {
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(servers).setAttributes(affinity).build());
    verify(mockSubchannel1).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener = stateListenerCaptor.getValue();
    verify(mockSubchannel1).requestConnection();
    stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(CONNECTING));
    verify(mockHelper).updateBalancingState(eq(CONNECTING), any(SubchannelPicker.class));

    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(servers).setAttributes(affinity).build());
    verify(mockSubchannel1).requestConnection();

    verify(mockHelper, times(4)).createSubchannel(createArgsCaptor.capture());
    verify(mockHelper).updateBalancingState(eq(CONNECTING), any(SubchannelPicker.class));
    assertThat(createArgsCaptor.getValue()).isNotNull();
    verify(mockHelper)
        .updateBalancingState(isA(ConnectivityState.class), isA(SubchannelPicker.class));

    verifyNoMoreInteractions(mockHelper);
  }

  @Test
  public void pickAfterResolvedAndChanged() {
    SocketAddress socketAddr1 = new FakeSocketAddress("oldserver");
    List<EquivalentAddressGroup> oldServers =
        Lists.newArrayList(new EquivalentAddressGroup(socketAddr1));

    SocketAddress socketAddr2 = new FakeSocketAddress("newserver");
    List<EquivalentAddressGroup> newServers =
        Lists.newArrayList(new EquivalentAddressGroup(socketAddr2));

    InOrder inOrder = inOrder(mockHelper, mockSubchannel1);

    // accept resolved addresses
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(oldServers).setAttributes(affinity).build());
    inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
    verify(mockSubchannel1).start(any(SubchannelStateListener.class));
    verify(mockSubchannel1).getAttributes();

    // start connection attempt to first address
    inOrder.verify(mockHelper).updateBalancingState(eq(CONNECTING), pickerCaptor.capture());
    verify(mockSubchannel1).requestConnection();

    assertNull(pickerCaptor.getValue().pickSubchannel(mockArgs).getSubchannel());

    // updating the subchannel addresses is unnecessary, but doesn't hurt anything
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(newServers).setAttributes(affinity).build());
    verify(mockSubchannel1).shutdown();

    verifyNoMoreInteractions(mockSubchannel1);
    verify(mockSubchannel2).start(any(SubchannelStateListener.class));
  }

  @Test
  public void pickAfterStateChangeAfterResolution() throws Exception {
    InOrder inOrder = inOrder(mockHelper);

    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(servers).setAttributes(affinity).build());
    inOrder.verify(mockHelper, times(4)).createSubchannel(createArgsCaptor.capture());
    List<CreateSubchannelArgs> argsList = createArgsCaptor.getAllValues();
    assertThat(argsList.get(0).getAddresses().get(0)).isEqualTo(servers.get(0));
    assertThat(argsList.get(1).getAddresses().get(0)).isEqualTo(servers.get(1));
    assertThat(argsList.get(2).getAddresses().get(0)).isEqualTo(servers.get(2));
    assertThat(argsList.get(3).getAddresses().get(0)).isEqualTo(servers.get(3));
    assertThat(argsList.get(0).getAddresses().size()).isEqualTo(1);
    assertThat(argsList.get(1).getAddresses().size()).isEqualTo(1);
    assertThat(argsList.get(2).getAddresses().size()).isEqualTo(1);
    assertThat(argsList.get(3).getAddresses().size()).isEqualTo(1);
    verify(mockSubchannel1).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener = stateListenerCaptor.getValue();
    verify(mockSubchannel2).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener2 = stateListenerCaptor.getValue();
    verify(mockSubchannel3).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener3 = stateListenerCaptor.getValue();
    verify(mockSubchannel4).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener4 = stateListenerCaptor.getValue();
    verify(mockHelper).updateBalancingState(eq(CONNECTING), pickerCaptor.capture());
    reset(mockHelper);
    when(mockHelper.getSynchronizationContext()).thenReturn(syncContext);

    stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(IDLE));
    inOrder.verify(mockHelper).refreshNameResolution();
    inOrder.verify(mockHelper).updateBalancingState(eq(IDLE), pickerCaptor.capture());

    // subchannel reports connecting when pick subchannel is called
    assertEquals(Status.OK, pickerCaptor.getValue().pickSubchannel(mockArgs).getStatus());
    stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(CONNECTING));
    inOrder.verify(mockHelper).updateBalancingState(eq(CONNECTING), pickerCaptor.capture());

    Status error = Status.UNAVAILABLE.withDescription("boom!");
    stateListener.onSubchannelState(ConnectivityStateInfo.forTransientFailure(error));
    stateListener2.onSubchannelState(ConnectivityStateInfo.forTransientFailure(error));
    stateListener3.onSubchannelState(ConnectivityStateInfo.forTransientFailure(error));
    stateListener4.onSubchannelState(ConnectivityStateInfo.forTransientFailure(error));
    inOrder.verify(mockHelper).refreshNameResolution();
    inOrder.verify(mockHelper).updateBalancingState(eq(TRANSIENT_FAILURE), pickerCaptor.capture());
    assertEquals(error, pickerCaptor.getValue().pickSubchannel(mockArgs).getStatus());

    stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
    inOrder.verify(mockHelper).updateBalancingState(eq(READY), pickerCaptor.capture());
    SubchannelPicker picker = pickerCaptor.getValue();
    assertEquals(mockSubchannel1, picker.pickSubchannel(mockArgs).getSubchannel());

    verify(mockHelper, atLeast(0)).getSynchronizationContext();  // Don't care
    verifyNoMoreInteractions(mockHelper);
  }

  @Test
  public void pickAfterResolutionAfterTransientValue() throws Exception {
    InOrder inOrder = inOrder(mockHelper);
    List<EquivalentAddressGroup> newServers = Lists.newArrayList(servers.get(0));
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(newServers).setAttributes(affinity).build());
    verify(mockHelper).createSubchannel(createArgsCaptor.capture());
    verify(mockSubchannel1).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener = stateListenerCaptor.getValue();
    verify(mockHelper).updateBalancingState(eq(CONNECTING), pickerCaptor.capture());
    verify(mockSubchannel1).requestConnection();
    reset(mockHelper);
    when(mockHelper.getSynchronizationContext()).thenReturn(syncContext);

    // An error has happened.
    Status error = Status.UNAVAILABLE.withDescription("boom!");
    stateListener.onSubchannelState(ConnectivityStateInfo.forTransientFailure(error));
    verify(mockHelper).updateBalancingState(eq(TRANSIENT_FAILURE), pickerCaptor.capture());
    inOrder.verify(mockHelper).refreshNameResolution();
    assertEquals(error, pickerCaptor.getValue().pickSubchannel(mockArgs).getStatus());

    // Transition from TRANSIENT_ERROR to CONNECTING should also be ignored.
    stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(CONNECTING));
    verifyNoMoreInteractions(mockHelper);
    assertEquals(error, pickerCaptor.getValue().pickSubchannel(mockArgs).getStatus());
  }

  @Test
  public void nameResolutionError() throws Exception {
    Status error = Status.NOT_FOUND.withDescription("nameResolutionError");
    loadBalancer.handleNameResolutionError(error);
    verify(mockHelper).updateBalancingState(eq(TRANSIENT_FAILURE), pickerCaptor.capture());
    PickResult pickResult = pickerCaptor.getValue().pickSubchannel(mockArgs);
    assertNull(pickResult.getSubchannel());
    assertEquals(error, pickResult.getStatus());
    verify(mockSubchannel1, never()).requestConnection();
    verifyNoMoreInteractions(mockHelper);
  }

  @Test
  public void nameResolutionError_emptyAddressList() throws Exception {
    servers.clear();
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(servers).setAttributes(affinity).build());
    verify(mockHelper).updateBalancingState(connectivityStateCaptor.capture(),
        pickerCaptor.capture());
    PickResult pickResult = pickerCaptor.getValue().pickSubchannel(mockArgs);
    assertThat(pickResult.getSubchannel()).isNull();
    assertThat(pickResult.getStatus().getCode()).isEqualTo(Code.UNAVAILABLE);
    assertThat(pickResult.getStatus().getDescription()).contains("returned no usable address");
    verify(mockSubchannel1, never()).requestConnection();
    verifyNoMoreInteractions(mockHelper);
  }

  @Test
  public void nameResolutionSuccessAfterError() throws Exception {
    InOrder inOrder = inOrder(mockHelper);

    loadBalancer.handleNameResolutionError(Status.NOT_FOUND.withDescription("nameResolutionError"));
    inOrder.verify(mockHelper)
        .updateBalancingState(any(ConnectivityState.class), any(SubchannelPicker.class));
    verify(mockSubchannel1, never()).requestConnection();

    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(servers).setAttributes(affinity).build());
    inOrder.verify(mockHelper, times(4)).createSubchannel(createArgsCaptor.capture());
    List<CreateSubchannelArgs> argsList = createArgsCaptor.getAllValues();
    assertThat(argsList.get(0).getAddresses().get(0)).isEqualTo(servers.get(0));
    assertThat(argsList.get(1).getAddresses().get(0)).isEqualTo(servers.get(1));
    assertThat(argsList.get(2).getAddresses().get(0)).isEqualTo(servers.get(2));
    assertThat(argsList.get(3).getAddresses().get(0)).isEqualTo(servers.get(3));
    assertThat(argsList.get(0).getAddresses().size()).isEqualTo(1);
    assertThat(argsList.get(1).getAddresses().size()).isEqualTo(1);
    assertThat(argsList.get(2).getAddresses().size()).isEqualTo(1);
    assertThat(argsList.get(3).getAddresses().size()).isEqualTo(1);
    assertThat(argsList.get(0).getAttributes()).isEqualTo(Attributes.EMPTY);
    assertThat(argsList.get(1).getAttributes()).isEqualTo(Attributes.EMPTY);
    assertThat(argsList.get(2).getAttributes()).isEqualTo(Attributes.EMPTY);
    assertThat(argsList.get(3).getAttributes()).isEqualTo(Attributes.EMPTY);

    inOrder.verify(mockHelper).updateBalancingState(eq(CONNECTING), pickerCaptor.capture());
    verify(mockSubchannel1).requestConnection();

    assertNull(pickerCaptor.getValue().pickSubchannel(mockArgs)
        .getSubchannel());

    assertEquals(pickerCaptor.getValue().pickSubchannel(mockArgs),
        pickerCaptor.getValue().pickSubchannel(mockArgs));

    verifyNoMoreInteractions(mockHelper);
  }

  @Test
  public void nameResolutionErrorWithStateChanges() throws Exception {
    List<EquivalentAddressGroup> newServers = Lists.newArrayList(servers.get(0));
    InOrder inOrder = inOrder(mockHelper);
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(newServers).setAttributes(affinity).build());
    verify(mockHelper).createSubchannel(createArgsCaptor.capture());
    verify(mockSubchannel1).start(stateListenerCaptor.capture());
    inOrder.verify(mockHelper).updateBalancingState(eq(CONNECTING), any(SubchannelPicker.class));
    SubchannelStateListener stateListener = stateListenerCaptor.getValue();

    stateListener.onSubchannelState(ConnectivityStateInfo.forTransientFailure(Status.UNAVAILABLE));
    inOrder.verify(mockHelper).refreshNameResolution();
    inOrder.verify(mockHelper).updateBalancingState(
        eq(TRANSIENT_FAILURE), any(SubchannelPicker.class));
    Status error = Status.NOT_FOUND.withDescription("nameResolutionError");
    loadBalancer.handleNameResolutionError(error);
    inOrder.verify(mockHelper).updateBalancingState(eq(TRANSIENT_FAILURE), pickerCaptor.capture());
    PickResult pickResult = pickerCaptor.getValue().pickSubchannel(mockArgs);
    assertNull(pickResult.getSubchannel());
    assertEquals(error, pickResult.getStatus());

    Status error2 = Status.NOT_FOUND.withDescription("nameResolutionError2");
    loadBalancer.handleNameResolutionError(error2);
    inOrder.verify(mockHelper).updateBalancingState(eq(TRANSIENT_FAILURE), pickerCaptor.capture());

    pickResult = pickerCaptor.getValue().pickSubchannel(mockArgs);
    assertNull(pickResult.getSubchannel());
    assertEquals(error2, pickResult.getStatus());

    verifyNoMoreInteractions(mockHelper);
  }

  @Test
  public void requestConnection() {
    InOrder inOrder = inOrder(mockHelper, mockSubchannel1, mockSubchannel2,
        mockSubchannel3, mockSubchannel4);
    List<EquivalentAddressGroup> oldServers = Lists.newArrayList(servers.get(0), servers.get(1));

    assertEquals(IDLE, loadBalancer.getConnectivityState());
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(oldServers).setAttributes(affinity).build());
    inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
    inOrder.verify(mockSubchannel1).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener = stateListenerCaptor.getValue();
    inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
    inOrder.verify(mockSubchannel2).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener2 = stateListenerCaptor.getValue();
    inOrder.verify(mockHelper).updateBalancingState(eq(CONNECTING), pickerCaptor.capture());
    inOrder.verify(mockSubchannel1).requestConnection();
    stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(CONNECTING));

    // calling requestConnection() starts next subchannel
    loadBalancer.requestConnection();
    inOrder.verify(mockSubchannel2).requestConnection();
    stateListener2.onSubchannelState(ConnectivityStateInfo.forNonError(CONNECTING));

    // calling requestConnection is now a no-op
    loadBalancer.requestConnection();
    verifyNoMoreInteractions(mockHelper);
  }

  @Test
  public void updateAddresses_emptyEagList_returns_false() {
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(servers).setAttributes(affinity).build());
    assertFalse(loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(Arrays.<EquivalentAddressGroup>asList())
            .setAttributes(affinity).build()).isOk());
    assertEquals(TRANSIENT_FAILURE, loadBalancer.getConnectivityState());
  }

  @Test
  public void updateAddresses_eagListWithNull_returns_false() {
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(servers).setAttributes(affinity).build());
    List<EquivalentAddressGroup> eags = Arrays.asList((EquivalentAddressGroup) null);
    assertFalse(loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(eags).setAttributes(affinity).build()).isOk());
    assertEquals(TRANSIENT_FAILURE, loadBalancer.getConnectivityState());
  }

  @Test
  public void updateAddresses_disjoint_idle() {
    InOrder inOrder = inOrder(mockHelper, mockSubchannel1, mockSubchannel2,
        mockSubchannel3, mockSubchannel4);
    // Creating first set of endpoints/addresses
    List<EquivalentAddressGroup> oldServers = Lists.newArrayList(servers.get(0), servers.get(1));

    // Accept Addresses and verify proper connection flow
    assertEquals(IDLE, loadBalancer.getConnectivityState());
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(oldServers).setAttributes(affinity).build());
    inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
    inOrder.verify(mockSubchannel1).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener = stateListenerCaptor.getValue();
    inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
    inOrder.verify(mockSubchannel2).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener2 = stateListenerCaptor.getValue();
    assertEquals(CONNECTING, loadBalancer.getConnectivityState());

    // First connection attempt is successful
    inOrder.verify(mockSubchannel1).requestConnection();
    assertEquals(CONNECTING, loadBalancer.getConnectivityState());
    stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
    assertEquals(READY, loadBalancer.getConnectivityState());

    // Verify that picker returns correct subchannel
    inOrder.verify(mockHelper).updateBalancingState(eq(READY), pickerCaptor.capture());
    SubchannelPicker picker = pickerCaptor.getValue();
    assertEquals(PickResult.withSubchannel(mockSubchannel1), picker.pickSubchannel(mockArgs));
    assertNotEquals(PickResult.withSubchannel(mockSubchannel2), picker.pickSubchannel(mockArgs));
    assertNotEquals(PickResult.withSubchannel(mockSubchannel3), picker.pickSubchannel(mockArgs));
    assertNotEquals(PickResult.withSubchannel(mockSubchannel4), picker.pickSubchannel(mockArgs));

    // Going into IDLE state
    stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(IDLE));
    assertEquals(IDLE, loadBalancer.getConnectivityState());
    inOrder.verify(mockHelper).updateBalancingState(eq(IDLE), pickerCaptor.capture());
    picker = pickerCaptor.getValue();

    // Creating second set of disjoint endpoints/addresses
    List<EquivalentAddressGroup> newServers = Lists.newArrayList(servers.get(2), servers.get(3));

    // Accept new resolved addresses to update
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(newServers).setAttributes(affinity).build());

    // We create new channels, remove old ones, and keep intersecting ones
    inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
    inOrder.verify(mockSubchannel3).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener3 = stateListenerCaptor.getValue();
    inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
    inOrder.verify(mockSubchannel4).start(stateListenerCaptor.capture());
    assertEquals(IDLE, loadBalancer.getConnectivityState());
    verify(mockSubchannel1).shutdown();
    verify(mockSubchannel2).shutdown();
    assertEquals(IDLE, loadBalancer.getConnectivityState());

    // If obselete subchannel becomes ready, the state should not be affected
    stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
    stateListener2.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
    assertEquals(IDLE, loadBalancer.getConnectivityState());

    // Calling pickSubchannel() twice gave the same result
    assertEquals(picker.pickSubchannel(mockArgs), picker.pickSubchannel(mockArgs));

    // But the picker calls requestConnection() only once
    inOrder.verify(mockSubchannel3).requestConnection();

    // Ready subchannel 3
    stateListener3.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
    assertEquals(READY, loadBalancer.getConnectivityState());
    inOrder.verify(mockHelper).updateBalancingState(eq(READY), pickerCaptor.capture());

    // Picking a subchannel returns subchannel 3
    picker = pickerCaptor.getValue();
    assertEquals(PickResult.withSubchannel(mockSubchannel3), picker.pickSubchannel(mockArgs));
    assertNotEquals(PickResult.withSubchannel(mockSubchannel1), picker.pickSubchannel(mockArgs));
    assertNotEquals(PickResult.withSubchannel(mockSubchannel2), picker.pickSubchannel(mockArgs));
    assertNotEquals(PickResult.withSubchannel(mockSubchannel4), picker.pickSubchannel(mockArgs));
  }

  @Test
  public void updateAddresses_disjoint_connecting() {
    InOrder inOrder = inOrder(mockHelper, mockSubchannel1, mockSubchannel2,
        mockSubchannel3, mockSubchannel4);

    // Creating first set of endpoints/addresses
    List<EquivalentAddressGroup> oldServers = Lists.newArrayList(servers.get(0), servers.get(1));

    // Accept Addresses and verify proper connection flow
    assertEquals(IDLE, loadBalancer.getConnectivityState());
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(oldServers).setAttributes(affinity).build());
    inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
    inOrder.verify(mockSubchannel1).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener = stateListenerCaptor.getValue();
    inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
    inOrder.verify(mockSubchannel2).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener2 = stateListenerCaptor.getValue();
    assertEquals(CONNECTING, loadBalancer.getConnectivityState());
    inOrder.verify(mockSubchannel1).requestConnection();

    // Creating second set of endpoints/addresses
    List<EquivalentAddressGroup> newServers = Lists.newArrayList(servers.get(2), servers.get(3));

    // Accept new resolved addresses to update
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(newServers).setAttributes(affinity).build());
    assertEquals(CONNECTING, loadBalancer.getConnectivityState());
    inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
    inOrder.verify(mockSubchannel3).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener3 = stateListenerCaptor.getValue();
    inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
    inOrder.verify(mockSubchannel4).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener4 = stateListenerCaptor.getValue();
    assertEquals(CONNECTING, loadBalancer.getConnectivityState());

    // Old subchannels should shut down (in no particular order) and request a connection
    verify(mockSubchannel1).shutdown();
    verify(mockSubchannel2).shutdown();
    inOrder.verify(mockSubchannel3).requestConnection();
    assertEquals(CONNECTING, loadBalancer.getConnectivityState());

    // If old subchannel becomes ready, the state should not be affected
    stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
    stateListener2.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
    assertEquals(CONNECTING, loadBalancer.getConnectivityState());

    // Fail connection attempt to third address
    Status error = Status.UNAVAILABLE.withDescription("Simulated connection error");
    stateListener3.onSubchannelState(ConnectivityStateInfo.forTransientFailure(error));
    assertEquals(CONNECTING, loadBalancer.getConnectivityState());

    // Verify starting connection attempt to fourth address
    inOrder.verify(mockSubchannel4).requestConnection();
    assertEquals(CONNECTING, loadBalancer.getConnectivityState());

    // Succeed connection attempt to fourth address
    stateListener4.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
    assertEquals(READY, loadBalancer.getConnectivityState());

    // Verify that picker returns correct subchannel
    inOrder.verify(mockHelper).updateBalancingState(eq(READY), pickerCaptor.capture());
    SubchannelPicker picker = pickerCaptor.getValue();
    assertEquals(PickResult.withSubchannel(mockSubchannel4), picker.pickSubchannel(mockArgs));
    assertNotEquals(PickResult.withSubchannel(mockSubchannel1), picker.pickSubchannel(mockArgs));
    assertNotEquals(PickResult.withSubchannel(mockSubchannel2), picker.pickSubchannel(mockArgs));
    assertNotEquals(PickResult.withSubchannel(mockSubchannel3), picker.pickSubchannel(mockArgs));
  }

  @Test
  public void updateAddresses_disjoint_ready_twice() {
    when(mockHelper.createSubchannel(any(CreateSubchannelArgs.class)))
        .thenReturn(mockSubchannel1, mockSubchannel2, mockSubchannel3,
            mockSubchannel4, mockSubchannel1, mockSubchannel2);
    InOrder inOrder = inOrder(mockHelper, mockSubchannel1, mockSubchannel2,
        mockSubchannel3, mockSubchannel4);
    // Creating first set of endpoints/addresses
    List<EquivalentAddressGroup> oldServers = Lists.newArrayList(servers.get(0), servers.get(1));

    // Accept Addresses and verify proper connection flow
    assertEquals(IDLE, loadBalancer.getConnectivityState());
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(oldServers).setAttributes(affinity).build());
    inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
    inOrder.verify(mockSubchannel1).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener = stateListenerCaptor.getValue();
    inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
    inOrder.verify(mockSubchannel2).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener2 = stateListenerCaptor.getValue();
    assertEquals(CONNECTING, loadBalancer.getConnectivityState());

    // First connection attempt is successful
    inOrder.verify(mockSubchannel1).requestConnection();
    assertEquals(CONNECTING, loadBalancer.getConnectivityState());
    stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
    inOrder.verify(mockSubchannel2).shutdown();
    inOrder.verify(mockHelper).updateBalancingState(eq(READY), pickerCaptor.capture());
    SubchannelPicker picker = pickerCaptor.getValue();
    assertEquals(READY, loadBalancer.getConnectivityState());

    // Verify that picker returns correct subchannel
    assertEquals(PickResult.withSubchannel(mockSubchannel1), picker.pickSubchannel(mockArgs));
    assertNotEquals(PickResult.withSubchannel(mockSubchannel2), picker.pickSubchannel(mockArgs));
    assertNotEquals(PickResult.withSubchannel(mockSubchannel3), picker.pickSubchannel(mockArgs));
    assertNotEquals(PickResult.withSubchannel(mockSubchannel4), picker.pickSubchannel(mockArgs));

    // Creating second set of endpoints/addresses
    List<EquivalentAddressGroup> newServers = Lists.newArrayList(servers.get(2), servers.get(3));

    // Accept new resolved addresses to update
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(newServers).setAttributes(affinity).build());
    inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
    inOrder.verify(mockSubchannel3).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener3 = stateListenerCaptor.getValue();
    inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
    inOrder.verify(mockSubchannel4).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener4 = stateListenerCaptor.getValue();
    assertEquals(CONNECTING, loadBalancer.getConnectivityState());
    inOrder.verify(mockSubchannel1).shutdown();
    inOrder.verify(mockHelper).updateBalancingState(eq(CONNECTING), pickerCaptor.capture());
    picker = pickerCaptor.getValue();

    // If obselete subchannel becomes ready, the state should not be affected
    stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
    stateListener2.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
    assertEquals(CONNECTING, loadBalancer.getConnectivityState());

    // Calling pickSubchannel() twice gave the same result
    assertEquals(picker.pickSubchannel(mockArgs), picker.pickSubchannel(mockArgs));

    // But the picker calls requestConnection() only once
    inOrder.verify(mockSubchannel3).requestConnection();
    picker = pickerCaptor.getValue();
    assertEquals(PickResult.withNoResult(), picker.pickSubchannel(mockArgs));
    assertEquals(CONNECTING, loadBalancer.getConnectivityState());

    // Ready subchannel 3
    stateListener3.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
    // Successful connection shuts down other subchannel
    inOrder.verify(mockSubchannel4).shutdown();
    inOrder.verify(mockHelper).updateBalancingState(eq(READY), pickerCaptor.capture());
    picker = pickerCaptor.getValue();
    assertEquals(READY, loadBalancer.getConnectivityState());

    // Verify that pickSubchannel() returns correct subchannel
    assertEquals(PickResult.withSubchannel(mockSubchannel3), picker.pickSubchannel(mockArgs));
    assertNotEquals(PickResult.withSubchannel(mockSubchannel1), picker.pickSubchannel(mockArgs));
    assertNotEquals(PickResult.withSubchannel(mockSubchannel2), picker.pickSubchannel(mockArgs));
    assertNotEquals(PickResult.withSubchannel(mockSubchannel4), picker.pickSubchannel(mockArgs));

    // Creating third set of endpoints/addresses
    List<EquivalentAddressGroup> newestServers = Lists.newArrayList(servers.get(0), servers.get(1));

    // Second address update
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(newestServers).setAttributes(affinity).build());
    inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
    inOrder.verify(mockSubchannel1).start(stateListenerCaptor.capture());
    stateListener = stateListenerCaptor.getValue();
    inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
    inOrder.verify(mockSubchannel2).start(stateListenerCaptor.capture());
    stateListener2 = stateListenerCaptor.getValue();
    assertEquals(CONNECTING, loadBalancer.getConnectivityState());
    inOrder.verify(mockSubchannel3).shutdown();
    inOrder.verify(mockHelper).updateBalancingState(eq(CONNECTING), pickerCaptor.capture());
    picker = pickerCaptor.getValue();

    // Calling pickSubchannel() twice gave the same result
    assertEquals(picker.pickSubchannel(mockArgs), picker.pickSubchannel(mockArgs));

    // But the picker calls requestConnection() only once
    inOrder.verify(mockSubchannel1).requestConnection();
    assertEquals(PickResult.withNoResult(), pickerCaptor.getValue().pickSubchannel(mockArgs));
    assertEquals(CONNECTING, loadBalancer.getConnectivityState());

    // If obselete subchannel becomes ready, the state should not be affected
    stateListener3.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
    stateListener4.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
    assertEquals(CONNECTING, loadBalancer.getConnectivityState());

    // Connection attempt to address 1 is unsuccessful
    Status error = Status.UNAVAILABLE.withDescription("Simulated connection error");
    stateListener.onSubchannelState(ConnectivityStateInfo.forTransientFailure(error));
    assertEquals(CONNECTING, loadBalancer.getConnectivityState());

    // Starting connection attempt to address 2
    assertEquals(CONNECTING, loadBalancer.getConnectivityState());
    inOrder.verify(mockSubchannel2).requestConnection();
    assertEquals(CONNECTING, loadBalancer.getConnectivityState());

    // Connection attempt to address 2 is successful
    stateListener2.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
    assertEquals(READY, loadBalancer.getConnectivityState());

    // Successful connection shuts down other subchannel
    inOrder.verify(mockSubchannel1).shutdown();
    inOrder.verify(mockHelper).updateBalancingState(eq(READY), pickerCaptor.capture());
    picker = pickerCaptor.getValue();

    // Verify that picker still returns correct subchannel
    assertEquals(PickResult.withSubchannel(mockSubchannel2), picker.pickSubchannel(mockArgs));
    assertNotEquals(PickResult.withSubchannel(mockSubchannel1), picker.pickSubchannel(mockArgs));
    assertNotEquals(PickResult.withSubchannel(mockSubchannel3), picker.pickSubchannel(mockArgs));
    assertNotEquals(PickResult.withSubchannel(mockSubchannel4), picker.pickSubchannel(mockArgs));
  }

  @Test
  public void updateAddresses_disjoint_transient_failure() {
    // Starting first connection attempt
    when(mockHelper.createSubchannel(any(CreateSubchannelArgs.class)))
        .thenReturn(mockSubchannel1, mockSubchannel2, mockSubchannel3,
          mockSubchannel4, mockSubchannel1, mockSubchannel2);
    InOrder inOrder = inOrder(mockHelper, mockSubchannel1, mockSubchannel2,
        mockSubchannel3, mockSubchannel4);

    // Creating first set of endpoints/addresses
    List<EquivalentAddressGroup> addrs = Lists.newArrayList(servers.get(0), servers.get(1));

    assertEquals(IDLE, loadBalancer.getConnectivityState());
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(addrs).setAttributes(affinity).build());
    inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
    assertEquals(CONNECTING, loadBalancer.getConnectivityState());
    inOrder.verify(mockSubchannel1).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener = stateListenerCaptor.getValue();
    inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
    inOrder.verify(mockSubchannel2).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener2 = stateListenerCaptor.getValue();
    assertEquals(CONNECTING, loadBalancer.getConnectivityState());
    inOrder.verify(mockSubchannel1).requestConnection();
    assertEquals(CONNECTING, loadBalancer.getConnectivityState());

    // Failing first connection attempt
    Status error = Status.UNAVAILABLE.withDescription("Simulated connection error");
    stateListener.onSubchannelState(ConnectivityStateInfo.forTransientFailure(error));
    assertEquals(CONNECTING, loadBalancer.getConnectivityState());

    // Starting second connection attempt
    assertEquals(CONNECTING, loadBalancer.getConnectivityState());
    inOrder.verify(mockSubchannel2).requestConnection();
    assertEquals(CONNECTING, loadBalancer.getConnectivityState());

    // Failing second connection attempt
    stateListener2.onSubchannelState(ConnectivityStateInfo.forTransientFailure(error));
    // sticky transient failure
    assertEquals(TRANSIENT_FAILURE, loadBalancer.getConnectivityState());

    // Creating second set of endpoints/addresses
    List<EquivalentAddressGroup> newServers = Lists.newArrayList(servers.get(2), servers.get(3));

    // Accept new resolved addresses to update
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(newServers).setAttributes(affinity).build());

    // subchannel 3 still attempts a connection even though we stay in transient failure
    assertEquals(TRANSIENT_FAILURE, loadBalancer.getConnectivityState());
    inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
    inOrder.verify(mockSubchannel3).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener3 = stateListenerCaptor.getValue();
    inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
    inOrder.verify(mockSubchannel4).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener4 = stateListenerCaptor.getValue();
    verify(mockSubchannel1).shutdown();
    verify(mockSubchannel2).shutdown();
    inOrder.verify(mockSubchannel3).requestConnection();

    // Obselete subchannels should not affect us
    stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
    stateListener2.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
    assertEquals(TRANSIENT_FAILURE, loadBalancer.getConnectivityState());

    // Third subchannel connection attempt is unsuccessful
    stateListener3.onSubchannelState(ConnectivityStateInfo.forTransientFailure(error));
    assertEquals(TRANSIENT_FAILURE, loadBalancer.getConnectivityState());

    inOrder.verify(mockSubchannel4).requestConnection();

    // Fourth subchannel connection attempt is successful
    stateListener4.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
    inOrder.verify(mockSubchannel3).shutdown();
    inOrder.verify(mockHelper).updateBalancingState(eq(READY), pickerCaptor.capture());
    assertEquals(READY, loadBalancer.getConnectivityState());

    // Picking a subchannel returns subchannel 3
    SubchannelPicker picker = pickerCaptor.getValue();
    assertEquals(PickResult.withSubchannel(mockSubchannel4), picker.pickSubchannel(mockArgs));
    assertNotEquals(PickResult.withSubchannel(mockSubchannel1), picker.pickSubchannel(mockArgs));
    assertNotEquals(PickResult.withSubchannel(mockSubchannel2), picker.pickSubchannel(mockArgs));
    assertNotEquals(PickResult.withSubchannel(mockSubchannel3), picker.pickSubchannel(mockArgs));
  }

  @Test
  public void updateAddresses_intersecting_idle() {
    InOrder inOrder = inOrder(mockHelper, mockSubchannel1, mockSubchannel2,
        mockSubchannel3, mockSubchannel4);

    // Creating first set of endpoints/addresses
    List<EquivalentAddressGroup> oldServers =
        Lists.newArrayList(servers.get(0), servers.get(1));

    // Accept Addresses and verify proper connection flow
    assertEquals(IDLE, loadBalancer.getConnectivityState());
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(oldServers).setAttributes(affinity).build());
    inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
    inOrder.verify(mockSubchannel1).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener = stateListenerCaptor.getValue();
    inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
    inOrder.verify(mockSubchannel2).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener2 = stateListenerCaptor.getValue();
    assertEquals(CONNECTING, loadBalancer.getConnectivityState());
    inOrder.verify(mockHelper).updateBalancingState(eq(CONNECTING), pickerCaptor.capture());
    inOrder.verify(mockSubchannel1).requestConnection();

    // First connection attempt is successful
    assertEquals(CONNECTING, loadBalancer.getConnectivityState());
    stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
    assertEquals(READY, loadBalancer.getConnectivityState());

    // Successful connection attempt shuts down other subchannels
    inOrder.verify(mockSubchannel2).shutdown();
    inOrder.verify(mockHelper).updateBalancingState(eq(READY), pickerCaptor.capture());
    SubchannelPicker picker = pickerCaptor.getValue();

    // Verify that picker returns correct subchannel
    assertEquals(PickResult.withSubchannel(mockSubchannel1), picker.pickSubchannel(mockArgs));
    assertNotEquals(PickResult.withSubchannel(mockSubchannel2), picker.pickSubchannel(mockArgs));
    assertNotEquals(PickResult.withSubchannel(mockSubchannel3), picker.pickSubchannel(mockArgs));
    assertNotEquals(PickResult.withSubchannel(mockSubchannel4), picker.pickSubchannel(mockArgs));

    // Going into IDLE state, nothing should happen unless requested
    stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(IDLE));
    assertEquals(IDLE, loadBalancer.getConnectivityState());
    inOrder.verify(mockHelper).refreshNameResolution();
    inOrder.verify(mockHelper).updateBalancingState(eq(IDLE), pickerCaptor.capture());
    picker = pickerCaptor.getValue();
    verifyNoMoreInteractions(mockHelper);

    // Creating second set of intersecting endpoints/addresses
    List<EquivalentAddressGroup> newServers =
        Lists.newArrayList(servers.get(0), servers.get(1), servers.get(3));

    // Accept new resolved addresses to update
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(newServers).setAttributes(affinity).build());
    assertEquals(IDLE, loadBalancer.getConnectivityState());

    // We create new channels and remove old ones, keeping intersecting ones
    inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
    inOrder.verify(mockSubchannel3).start(stateListenerCaptor.capture());
    inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
    inOrder.verify(mockSubchannel4).start(stateListenerCaptor.capture());

    // If obselete subchannel becomes ready, the state should not be affected
    stateListener2.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
    assertEquals(IDLE, loadBalancer.getConnectivityState());

    // Calling pickSubchannel() twice gave the same result
    assertEquals(picker.pickSubchannel(mockArgs), picker.pickSubchannel(mockArgs));

    // But the picker calls requestConnection() only once
    inOrder.verify(mockSubchannel1).requestConnection();

    // internal subchannel calls back and reports connecting
    stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(CONNECTING));
    assertEquals(PickResult.withNoResult(), pickerCaptor.getValue().pickSubchannel(mockArgs));
    assertEquals(CONNECTING, loadBalancer.getConnectivityState());

    // Ready subchannel 1
    stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
    assertEquals(READY, loadBalancer.getConnectivityState());
    inOrder.verify(mockHelper).updateBalancingState(eq(READY), pickerCaptor.capture());

    // Picking a subchannel returns subchannel 1
    picker = pickerCaptor.getValue();
    assertEquals(PickResult.withSubchannel(mockSubchannel1), picker.pickSubchannel(mockArgs));
    assertNotEquals(PickResult.withSubchannel(mockSubchannel2), picker.pickSubchannel(mockArgs));
    assertNotEquals(PickResult.withSubchannel(mockSubchannel3), picker.pickSubchannel(mockArgs));
    assertNotEquals(PickResult.withSubchannel(mockSubchannel4), picker.pickSubchannel(mockArgs));
  }

  @Test
  public void updateAddresses_intersecting_connecting() {
    InOrder inOrder = inOrder(mockHelper, mockSubchannel1, mockSubchannel2,
        mockSubchannel3, mockSubchannel4);

    // Creating first set of endpoints/addresses
    List<EquivalentAddressGroup> oldServers =
        Lists.newArrayList(servers.get(0), servers.get(1), servers.get(2));

    // Accept Addresses and verify proper connection flow
    assertEquals(IDLE, loadBalancer.getConnectivityState());
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(oldServers).setAttributes(affinity).build());
    inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
    inOrder.verify(mockSubchannel1).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener = stateListenerCaptor.getValue();
    inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
    inOrder.verify(mockSubchannel2).start(stateListenerCaptor.capture());
    inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
    inOrder.verify(mockSubchannel3).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener3 = stateListenerCaptor.getValue();
    assertEquals(CONNECTING, loadBalancer.getConnectivityState());
    inOrder.verify(mockHelper).updateBalancingState(eq(CONNECTING), pickerCaptor.capture());
    inOrder.verify(mockSubchannel1).requestConnection();

    // callback from internal subchannel
    stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(CONNECTING));

    // Creating second set of endpoints/addresses
    List<EquivalentAddressGroup> newServers =
        Lists.newArrayList(servers.get(0), servers.get(1), servers.get(3));

    // Accept new resolved addresses to update
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(newServers).setAttributes(affinity).build());
    assertEquals(CONNECTING, loadBalancer.getConnectivityState());

    // Don't unnecessarily create new subchannels and keep intersecting ones
    inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
    inOrder.verify(mockSubchannel4).start(stateListenerCaptor.capture());
    verifyNoMoreInteractions(mockHelper);

    // If obselete subchannel becomes ready, the state should not be affected
    stateListener3.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
    assertEquals(CONNECTING, loadBalancer.getConnectivityState());

    // First connection attempt is successful
    stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
    assertEquals(READY, loadBalancer.getConnectivityState());

    // verify that picker returns correct subchannel
    inOrder.verify(mockHelper).updateBalancingState(eq(READY), pickerCaptor.capture());
    SubchannelPicker picker = pickerCaptor.getValue();
    assertEquals(PickResult.withSubchannel(mockSubchannel1), picker.pickSubchannel(mockArgs));
    assertNotEquals(PickResult.withSubchannel(mockSubchannel2), picker.pickSubchannel(mockArgs));
    assertNotEquals(PickResult.withSubchannel(mockSubchannel3), picker.pickSubchannel(mockArgs));
    assertNotEquals(PickResult.withSubchannel(mockSubchannel4), picker.pickSubchannel(mockArgs));
  }

  @Test
  public void updateAddresses_intersecting_ready() {
    InOrder inOrder = inOrder(mockHelper, mockSubchannel1, mockSubchannel2,
        mockSubchannel3, mockSubchannel4);
    // Creating first set of endpoints/addresses
    List<EquivalentAddressGroup> oldServers =
        Lists.newArrayList(servers.get(0), servers.get(1));

    // Accept Addresses and verify proper connection flow
    assertEquals(IDLE, loadBalancer.getConnectivityState());
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(oldServers).setAttributes(affinity).build());
    inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
    inOrder.verify(mockSubchannel1).start(stateListenerCaptor.capture());
    inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
    SubchannelStateListener stateListener = stateListenerCaptor.getValue();
    inOrder.verify(mockSubchannel2).start(stateListenerCaptor.capture());
    assertEquals(CONNECTING, loadBalancer.getConnectivityState());

    // First connection attempt is successful
    assertEquals(CONNECTING, loadBalancer.getConnectivityState());
    inOrder.verify(mockSubchannel1).requestConnection();
    assertEquals(CONNECTING, loadBalancer.getConnectivityState());
    stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
    assertEquals(READY, loadBalancer.getConnectivityState());
    inOrder.verify(mockSubchannel2).shutdown();
    // Verify that picker returns correct subchannel
    inOrder.verify(mockHelper).updateBalancingState(eq(READY), pickerCaptor.capture());
    SubchannelPicker picker = pickerCaptor.getValue();
    assertEquals(PickResult.withSubchannel(mockSubchannel1), picker.pickSubchannel(mockArgs));
    assertNotEquals(PickResult.withSubchannel(mockSubchannel2), picker.pickSubchannel(mockArgs));
    assertNotEquals(PickResult.withSubchannel(mockSubchannel3), picker.pickSubchannel(mockArgs));
    assertNotEquals(PickResult.withSubchannel(mockSubchannel4), picker.pickSubchannel(mockArgs));

    // Creating second set of endpoints/addresses
    List<EquivalentAddressGroup> newServers =
        Lists.newArrayList(servers.get(0), servers.get(1), servers.get(2));
    // Accept new resolved addresses to update
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(newServers).setAttributes(affinity).build());

    // The state is still READY after update since we had an intersecting subchannel that was READY.
    assertEquals(READY, loadBalancer.getConnectivityState());

    // Verify that picker still returns correct subchannel
    picker = pickerCaptor.getValue();
    assertEquals(PickResult.withSubchannel(mockSubchannel1), picker.pickSubchannel(mockArgs));
    assertNotEquals(PickResult.withSubchannel(mockSubchannel2), picker.pickSubchannel(mockArgs));
    assertNotEquals(PickResult.withSubchannel(mockSubchannel3), picker.pickSubchannel(mockArgs));
    assertNotEquals(PickResult.withSubchannel(mockSubchannel4), picker.pickSubchannel(mockArgs));
  }

  @Test
  public void updateAddresses_intersecting_transient_failure() {
    // Starting first connection attempt
    InOrder inOrder = inOrder(mockHelper, mockSubchannel1, mockSubchannel2,
        mockSubchannel3, mockSubchannel4); // captor: captures

    // Creating first set of endpoints/addresses
    List<EquivalentAddressGroup> oldServers =
        Lists.newArrayList(servers.get(0), servers.get(1));

    assertEquals(IDLE, loadBalancer.getConnectivityState());
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(oldServers).setAttributes(affinity).build());
    inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
    assertEquals(CONNECTING, loadBalancer.getConnectivityState());
    inOrder.verify(mockSubchannel1).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener = stateListenerCaptor.getValue();
    inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
    inOrder.verify(mockSubchannel2).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener2 = stateListenerCaptor.getValue();
    assertEquals(CONNECTING, loadBalancer.getConnectivityState());
    inOrder.verify(mockSubchannel1).requestConnection();
    assertEquals(CONNECTING, loadBalancer.getConnectivityState());

    // Failing first connection attempt
    Status error = Status.UNAVAILABLE.withDescription("Simulated connection error");
    stateListener.onSubchannelState(ConnectivityStateInfo.forTransientFailure(error));
    assertEquals(CONNECTING, loadBalancer.getConnectivityState());

    // Starting second connection attempt
    assertEquals(CONNECTING, loadBalancer.getConnectivityState());
    inOrder.verify(mockSubchannel2).requestConnection();
    assertEquals(CONNECTING, loadBalancer.getConnectivityState());

    // Failing second connection attempt
    stateListener2.onSubchannelState(ConnectivityStateInfo.forTransientFailure(error));
    // sticky transient failure
    assertEquals(TRANSIENT_FAILURE, loadBalancer.getConnectivityState());

    // Creating second set of endpoints/addresses
    List<EquivalentAddressGroup> newServers =
        Lists.newArrayList(servers.get(0), servers.get(1), servers.get(2));

    // Accept new resolved addresses to update
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(newServers).setAttributes(affinity).build());

    // subchannel 3 still attempts a connection even though we stay in transient failure
    assertEquals(TRANSIENT_FAILURE, loadBalancer.getConnectivityState());
    inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
    inOrder.verify(mockSubchannel3).getAttributes();
    inOrder.verify(mockSubchannel3).start(stateListenerCaptor.capture());
    inOrder.verify(mockSubchannel3).requestConnection();

    // no other connections should be requested by LB, we should come out of backoff to request
    verifyNoMoreInteractions(mockSubchannel3);

    stateListener2.onSubchannelState(ConnectivityStateInfo.forNonError(CONNECTING));
    assertEquals(TRANSIENT_FAILURE, loadBalancer.getConnectivityState());

    // Second subchannel connection attempt is now successful
    stateListener2.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
    assertEquals(READY, loadBalancer.getConnectivityState());
    inOrder.verify(mockSubchannel3).shutdown();

    inOrder.verify(mockHelper).updateBalancingState(eq(READY), pickerCaptor.capture());

    // Picking a subchannel returns subchannel 3
    SubchannelPicker picker = pickerCaptor.getValue();
    assertEquals(PickResult.withSubchannel(mockSubchannel2), picker.pickSubchannel(mockArgs));
    assertNotEquals(PickResult.withSubchannel(mockSubchannel1), picker.pickSubchannel(mockArgs));
    assertNotEquals(PickResult.withSubchannel(mockSubchannel3), picker.pickSubchannel(mockArgs));
    assertNotEquals(PickResult.withSubchannel(mockSubchannel4), picker.pickSubchannel(mockArgs));
  }

  @Test
  public void updateAddresses_intersecting_enter_transient_failure() {
    // after an address update occurs, verify that the client properly tries all
    // addresses and only then enters transient failure.
    InOrder inOrder = inOrder(mockHelper, mockSubchannel1, mockSubchannel2,
        mockSubchannel3, mockSubchannel4);

    // Creating first set of endpoints/addresses
    List<EquivalentAddressGroup> oldServers = Lists.newArrayList(servers.get(0), servers.get(1));

    // Accept Addresses and verify proper connection flow
    assertEquals(IDLE, loadBalancer.getConnectivityState());
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(oldServers).setAttributes(affinity).build());
    inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
    inOrder.verify(mockSubchannel1).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener = stateListenerCaptor.getValue();
    inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
    inOrder.verify(mockSubchannel2).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener2 = stateListenerCaptor.getValue();
    assertEquals(CONNECTING, loadBalancer.getConnectivityState());
    inOrder.verify(mockSubchannel1).requestConnection();

    // callback from internal subchannel
    stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(CONNECTING));

    // Creating second set of endpoints/addresses
    List<EquivalentAddressGroup> newServers = Lists.newArrayList(servers.get(0), servers.get(2));

    // Accept new resolved addresses to update
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(newServers).setAttributes(affinity).build());
    assertEquals(CONNECTING, loadBalancer.getConnectivityState());

    // We create new channels and remove old ones, keeping intersecting ones
    inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
    inOrder.verify(mockSubchannel3).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener3 = stateListenerCaptor.getValue();
    inOrder.verify(mockSubchannel2).shutdown();

    // If obselete subchannel becomes ready, the state should not be affected
    stateListener2.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
    assertEquals(CONNECTING, loadBalancer.getConnectivityState());

    // First connection attempt is unsuccessful
    Status error = Status.UNAVAILABLE.withDescription("Simulated connection error");
    stateListener.onSubchannelState(ConnectivityStateInfo.forTransientFailure(error));
    assertEquals(CONNECTING, loadBalancer.getConnectivityState());

    // Subchannel 3 attempt starts but fails
    inOrder.verify(mockSubchannel3).requestConnection();
    assertEquals(CONNECTING, loadBalancer.getConnectivityState());
    stateListener3.onSubchannelState(ConnectivityStateInfo.forTransientFailure(error));
    assertEquals(TRANSIENT_FAILURE, loadBalancer.getConnectivityState());
  }

  @Test
  public void updateAddresses_identical_idle() {
    InOrder inOrder = inOrder(mockHelper, mockSubchannel1, mockSubchannel2,
        mockSubchannel3, mockSubchannel4);
    // Creating first set of endpoints/addresses
    List<EquivalentAddressGroup> oldServers = Lists.newArrayList(servers.get(0), servers.get(1));

    // Accept Addresses and verify proper connection flow
    assertEquals(IDLE, loadBalancer.getConnectivityState());
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(oldServers).setAttributes(affinity).build());
    inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
    inOrder.verify(mockSubchannel1).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener = stateListenerCaptor.getValue();
    inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
    inOrder.verify(mockSubchannel2).start(stateListenerCaptor.capture());
    assertEquals(CONNECTING, loadBalancer.getConnectivityState());

    // First connection attempt is successful
    inOrder.verify(mockSubchannel1).requestConnection();
    assertEquals(CONNECTING, loadBalancer.getConnectivityState());
    stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
    assertEquals(READY, loadBalancer.getConnectivityState());

    // Verify that picker returns correct subchannel
    inOrder.verify(mockHelper).updateBalancingState(eq(READY), pickerCaptor.capture());
    SubchannelPicker picker = pickerCaptor.getValue();
    assertEquals(PickResult.withSubchannel(mockSubchannel1), picker.pickSubchannel(mockArgs));
    assertNotEquals(PickResult.withSubchannel(mockSubchannel2), picker.pickSubchannel(mockArgs));
    assertNotEquals(PickResult.withSubchannel(mockSubchannel3), picker.pickSubchannel(mockArgs));
    assertNotEquals(PickResult.withSubchannel(mockSubchannel4), picker.pickSubchannel(mockArgs));

    // Going into IDLE state
    stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(IDLE));
    assertEquals(IDLE, loadBalancer.getConnectivityState());
    inOrder.verify(mockHelper).updateBalancingState(eq(IDLE), pickerCaptor.capture());

    // Accept same resolved addresses to update
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(oldServers).setAttributes(affinity).build());

    // Verify that no new subchannels were created or started
    verify(mockHelper, times(3)).createSubchannel(createArgsCaptor.capture());
    verify(mockSubchannel1, times(1)).start(stateListenerCaptor.capture());
    verify(mockSubchannel2, times(1)).start(stateListenerCaptor.capture());

    // First connection attempt is successful
    assertEquals(IDLE, loadBalancer.getConnectivityState());
    stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
    assertEquals(READY, loadBalancer.getConnectivityState());

    // verify that picker returns correct subchannel
    inOrder.verify(mockHelper).updateBalancingState(eq(READY), pickerCaptor.capture());
    picker = pickerCaptor.getValue();
    assertEquals(PickResult.withSubchannel(mockSubchannel1), picker.pickSubchannel(mockArgs));
    assertNotEquals(PickResult.withSubchannel(mockSubchannel2), picker.pickSubchannel(mockArgs));
    assertNotEquals(PickResult.withSubchannel(mockSubchannel3), picker.pickSubchannel(mockArgs));
    assertNotEquals(PickResult.withSubchannel(mockSubchannel4), picker.pickSubchannel(mockArgs));
  }

  @Test
  public void updateAddresses_identical_connecting() {
    InOrder inOrder = inOrder(mockHelper, mockSubchannel1, mockSubchannel2,
        mockSubchannel3, mockSubchannel4);
    // Creating first set of endpoints/addresses
    List<EquivalentAddressGroup> oldServers = Lists.newArrayList(servers.get(0), servers.get(1));

    // Accept Addresses and verify proper connection flow
    assertEquals(IDLE, loadBalancer.getConnectivityState());
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(oldServers).setAttributes(affinity).build());
    inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
    inOrder.verify(mockSubchannel1).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener = stateListenerCaptor.getValue();
    inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
    inOrder.verify(mockSubchannel2).start(stateListenerCaptor.capture());
    assertEquals(CONNECTING, loadBalancer.getConnectivityState());

    // Accept same resolved addresses to update
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(oldServers).setAttributes(affinity).build());

    // Verify that no new subchannels were created or started
    verify(mockHelper, times(2)).createSubchannel(createArgsCaptor.capture());
    verify(mockSubchannel1, times(1)).start(stateListenerCaptor.capture());
    verify(mockSubchannel2, times(1)).start(stateListenerCaptor.capture());

    // First connection attempt is successful
    assertEquals(CONNECTING, loadBalancer.getConnectivityState());
    stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
    assertEquals(READY, loadBalancer.getConnectivityState());

    // verify that picker returns correct subchannel
    inOrder.verify(mockHelper).updateBalancingState(eq(READY), pickerCaptor.capture());
    SubchannelPicker picker = pickerCaptor.getValue();
    assertEquals(PickResult.withSubchannel(mockSubchannel1), picker.pickSubchannel(mockArgs));
    assertNotEquals(PickResult.withSubchannel(mockSubchannel2), picker.pickSubchannel(mockArgs));
    assertNotEquals(PickResult.withSubchannel(mockSubchannel3), picker.pickSubchannel(mockArgs));
    assertNotEquals(PickResult.withSubchannel(mockSubchannel4), picker.pickSubchannel(mockArgs));
  }

  @Test
  public void updateAddresses_identical_ready() {
    InOrder inOrder = inOrder(mockHelper, mockSubchannel1, mockSubchannel2,
        mockSubchannel3, mockSubchannel4);
    // Creating first set of endpoints/addresses
    List<EquivalentAddressGroup> oldServers = Lists.newArrayList(servers.get(0), servers.get(1));

    // Accept Addresses and verify proper connection flow
    assertEquals(IDLE, loadBalancer.getConnectivityState());
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(oldServers).setAttributes(affinity).build());
    inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
    inOrder.verify(mockSubchannel1).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener = stateListenerCaptor.getValue();
    inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
    inOrder.verify(mockSubchannel2).start(stateListenerCaptor.capture());
    inOrder.verify(mockHelper).updateBalancingState(eq(CONNECTING), pickerCaptor.capture());
    assertEquals(CONNECTING, loadBalancer.getConnectivityState());

    // First connection attempt is successful
    inOrder.verify(mockSubchannel1).requestConnection();
    assertEquals(CONNECTING, loadBalancer.getConnectivityState());
    stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
    assertEquals(READY, loadBalancer.getConnectivityState());
    inOrder.verify(mockSubchannel2).shutdown();

    // verify that picker returns correct subchannel
    inOrder.verify(mockHelper).updateBalancingState(eq(READY), pickerCaptor.capture());
    SubchannelPicker picker = pickerCaptor.getValue();
    assertEquals(PickResult.withSubchannel(mockSubchannel1), picker.pickSubchannel(mockArgs));
    assertNotEquals(PickResult.withSubchannel(mockSubchannel2), picker.pickSubchannel(mockArgs));
    assertNotEquals(PickResult.withSubchannel(mockSubchannel3), picker.pickSubchannel(mockArgs));
    assertNotEquals(PickResult.withSubchannel(mockSubchannel4), picker.pickSubchannel(mockArgs));

    // Accept same resolved addresses to update
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(oldServers).setAttributes(affinity).build());

    // Verify that no new subchannels were created or started
    verify(mockHelper, times(2)).createSubchannel(
        any(CreateSubchannelArgs.class));
    verify(mockSubchannel1, times(1)).start(
        any(SubchannelStateListener.class));
    verify(mockSubchannel2, times(1)).start(
        any(SubchannelStateListener.class));
    assertEquals(READY, loadBalancer.getConnectivityState());

    // verify that picker hasn't changed via checking mock helper's interactions
    verifyNoMoreInteractions(mockHelper);
  }

  @Test
  public void updateAddresses_identical_transient_failure() {
    InOrder inOrder = inOrder(mockHelper, mockSubchannel1, mockSubchannel2,
        mockSubchannel3, mockSubchannel4);
    // Creating first set of endpoints/addresses
    List<EquivalentAddressGroup> oldServers = Lists.newArrayList(servers.get(0), servers.get(1));

    // Accept Addresses and verify proper connection flow
    assertEquals(IDLE, loadBalancer.getConnectivityState());
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(oldServers).setAttributes(affinity).build());
    inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
    inOrder.verify(mockSubchannel1).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener = stateListenerCaptor.getValue();
    inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
    inOrder.verify(mockSubchannel2).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener2 = stateListenerCaptor.getValue();
    inOrder.verify(mockHelper).updateBalancingState(eq(CONNECTING), pickerCaptor.capture());
    assertEquals(CONNECTING, loadBalancer.getConnectivityState());

    // First connection attempt is unsuccessful
    inOrder.verify(mockSubchannel1).requestConnection();
    assertEquals(CONNECTING, loadBalancer.getConnectivityState());
    Status error = Status.UNAVAILABLE.withDescription("Simulated connection error");
    stateListener.onSubchannelState(ConnectivityStateInfo.forTransientFailure(error));
    assertEquals(CONNECTING, loadBalancer.getConnectivityState());

    // Second connection attempt is unsuccessful
    inOrder.verify(mockSubchannel2).requestConnection();
    assertEquals(CONNECTING, loadBalancer.getConnectivityState());
    stateListener2.onSubchannelState(ConnectivityStateInfo.forTransientFailure(error));
    inOrder.verify(mockHelper).refreshNameResolution();
    inOrder.verify(mockHelper).updateBalancingState(eq(TRANSIENT_FAILURE), pickerCaptor.capture());
    assertEquals(TRANSIENT_FAILURE, loadBalancer.getConnectivityState());

    // Accept same resolved addresses to update
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(oldServers).setAttributes(affinity).build());

    // Verify that no new subchannels were created or started
    verify(mockHelper, times(2)).createSubchannel(createArgsCaptor.capture());
    verify(mockSubchannel1, times(1)).start(stateListenerCaptor.capture());
    verify(mockSubchannel2, times(1)).start(stateListenerCaptor.capture());
    assertEquals(TRANSIENT_FAILURE, loadBalancer.getConnectivityState());

    // No new connections are requested, subchannels responsible for completing their own backoffs
    verifyNoMoreInteractions(mockHelper);

    // First connection attempt is successful
    stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
    assertEquals(READY, loadBalancer.getConnectivityState());
    inOrder.verify(mockSubchannel2).shutdown();

    // verify that picker returns correct subchannel
    inOrder.verify(mockHelper).updateBalancingState(eq(READY), pickerCaptor.capture());
    SubchannelPicker picker = pickerCaptor.getValue();
    assertEquals(PickResult.withSubchannel(mockSubchannel1), picker.pickSubchannel(mockArgs));
    assertNotEquals(PickResult.withSubchannel(mockSubchannel2), picker.pickSubchannel(mockArgs));
    assertNotEquals(PickResult.withSubchannel(mockSubchannel3), picker.pickSubchannel(mockArgs));
    assertNotEquals(PickResult.withSubchannel(mockSubchannel4), picker.pickSubchannel(mockArgs));
  }

  @Test
  public void twoAddressesSeriallyConnect() {
    // Starting first connection attempt
    InOrder inOrder = inOrder(mockHelper, mockSubchannel1, mockSubchannel2, mockSubchannel3);
    assertEquals(IDLE, loadBalancer.getConnectivityState());
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(servers).setAttributes(affinity).build());
    inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
    assertEquals(CONNECTING, loadBalancer.getConnectivityState());
    inOrder.verify(mockSubchannel1).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener = stateListenerCaptor.getValue();
    inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
    inOrder.verify(mockSubchannel2).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener2 = stateListenerCaptor.getValue();
    inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
    inOrder.verify(mockSubchannel3).start(stateListenerCaptor.capture());
    assertEquals(CONNECTING, loadBalancer.getConnectivityState());
    inOrder.verify(mockSubchannel1).requestConnection();
    assertEquals(CONNECTING, loadBalancer.getConnectivityState());

    // Failing first connection attempt
    Status error = Status.UNAVAILABLE.withDescription("Simulated connection error");
    stateListener.onSubchannelState(ConnectivityStateInfo.forTransientFailure(error));
    assertEquals(CONNECTING, loadBalancer.getConnectivityState());

    // Starting second connection attempt
    assertEquals(CONNECTING, loadBalancer.getConnectivityState());
    inOrder.verify(mockSubchannel2).requestConnection();
    assertEquals(CONNECTING, loadBalancer.getConnectivityState());

    // Second connection attempt is successful
    stateListener2.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
    assertEquals(READY, loadBalancer.getConnectivityState());

    // Verify that picker returns correct subchannel
    inOrder.verify(mockHelper).updateBalancingState(eq(READY), pickerCaptor.capture());
    SubchannelPicker picker = pickerCaptor.getValue();
    assertEquals(PickResult.withSubchannel(mockSubchannel2), picker.pickSubchannel(mockArgs));
    assertNotEquals(PickResult.withSubchannel(mockSubchannel1), picker.pickSubchannel(mockArgs));
    assertNotEquals(PickResult.withSubchannel(mockSubchannel3), picker.pickSubchannel(mockArgs));
    assertNotEquals(PickResult.withSubchannel(mockSubchannel4), picker.pickSubchannel(mockArgs));
  }

  @Test
  public void multiple_backoffs() {
    // This test case mimics a backoff without implementing one
    InOrder inOrder = inOrder(mockHelper, mockSubchannel1, mockSubchannel2,
        mockSubchannel3, mockSubchannel4);

    // Creating first set of endpoints/addresses
    List<EquivalentAddressGroup> newServers = Lists.newArrayList(servers.get(0), servers.get(1));

    // Starting first connection attempt
    assertEquals(IDLE, loadBalancer.getConnectivityState());
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(newServers).setAttributes(affinity).build());
    inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
    assertEquals(CONNECTING, loadBalancer.getConnectivityState());
    inOrder.verify(mockSubchannel1).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener = stateListenerCaptor.getValue();
    inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
    inOrder.verify(mockSubchannel2).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener2 = stateListenerCaptor.getValue();
    assertEquals(CONNECTING, loadBalancer.getConnectivityState());
    inOrder.verify(mockHelper).updateBalancingState(eq(CONNECTING), pickerCaptor.capture());
    inOrder.verify(mockSubchannel1).requestConnection();
    assertEquals(CONNECTING, loadBalancer.getConnectivityState());

    // Failing first connection attempt
    Status error = Status.UNAVAILABLE.withDescription("Simulated connection error");
    stateListener.onSubchannelState(ConnectivityStateInfo.forTransientFailure(error));
    assertEquals(CONNECTING, loadBalancer.getConnectivityState());

    // Starting second connection attempt
    assertEquals(CONNECTING, loadBalancer.getConnectivityState());
    inOrder.verify(mockSubchannel2).requestConnection();
    assertEquals(CONNECTING, loadBalancer.getConnectivityState());

    // Failing second connection attempt
    stateListener2.onSubchannelState(ConnectivityStateInfo.forTransientFailure(error));
    inOrder.verify(mockHelper).refreshNameResolution();
    inOrder.verify(mockHelper).updateBalancingState(eq(TRANSIENT_FAILURE), pickerCaptor.capture());
    assertEquals(TRANSIENT_FAILURE, loadBalancer.getConnectivityState());

    // Mimic backoff for first address
    stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(CONNECTING));
    assertEquals(TRANSIENT_FAILURE, loadBalancer.getConnectivityState());

    // Mimic backoff for second address
    stateListener2.onSubchannelState(ConnectivityStateInfo.forNonError(CONNECTING));
    assertEquals(TRANSIENT_FAILURE, loadBalancer.getConnectivityState());

    // Failing first connection attempt
    stateListener2.onSubchannelState(ConnectivityStateInfo.forTransientFailure(error));
    assertEquals(TRANSIENT_FAILURE, loadBalancer.getConnectivityState());

    // Failing second connection attempt
    stateListener2.onSubchannelState(ConnectivityStateInfo.forTransientFailure(error));
    assertEquals(TRANSIENT_FAILURE, loadBalancer.getConnectivityState());

    // Mimic backoff for first address
    stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(CONNECTING));
    assertEquals(TRANSIENT_FAILURE, loadBalancer.getConnectivityState());

    // Mimic backoff for second address
    stateListener2.onSubchannelState(ConnectivityStateInfo.forNonError(CONNECTING));
    assertEquals(TRANSIENT_FAILURE, loadBalancer.getConnectivityState());

    // Connection attempt to second address is now successful
    stateListener2.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
    assertEquals(READY, loadBalancer.getConnectivityState());
    inOrder.verify(mockSubchannel1).shutdown();

    // Verify that picker returns correct subchannel
    inOrder.verify(mockHelper).updateBalancingState(eq(READY), pickerCaptor.capture());
    SubchannelPicker picker = pickerCaptor.getValue();
    when(mockSubchannel2.getAllAddresses()).thenReturn(Lists.newArrayList(servers.get(0)));
    assertEquals(PickResult.withSubchannel(mockSubchannel2), picker.pickSubchannel(mockArgs));
    assertNotEquals(PickResult.withSubchannel(mockSubchannel1), picker.pickSubchannel(mockArgs));
    assertNotEquals(PickResult.withSubchannel(mockSubchannel3), picker.pickSubchannel(mockArgs));
    assertNotEquals(PickResult.withSubchannel(mockSubchannel4), picker.pickSubchannel(mockArgs));

    // If first subchannel is ready before it completes shutdown, we still choose subchannel 2
    // This can be verified by checking the mock helper.
    stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
    verifyNoMoreInteractions(mockHelper);
  }

  @Test
  public void backoff_faster_than_serial_connection() {
    // this tests the case where a subchannel completes its backoff and readies a connection
    // before the other addresses have a chance to complete their connection attempts
    InOrder inOrder = inOrder(mockHelper, mockSubchannel1, mockSubchannel2,
        mockSubchannel3, mockSubchannel4); // captor: captures

    // Creating first set of endpoints/addresses
    List<EquivalentAddressGroup> addrs = Lists.newArrayList(servers.get(0),
        servers.get(1));

    // Accepting resolved addresses starts all subchannels
    assertEquals(IDLE, loadBalancer.getConnectivityState());
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(addrs).setAttributes(affinity).build());
    inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
    assertEquals(CONNECTING, loadBalancer.getConnectivityState());
    inOrder.verify(mockSubchannel1).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener = stateListenerCaptor.getValue();
    inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
    inOrder.verify(mockSubchannel2).start(stateListenerCaptor.capture());
    assertEquals(CONNECTING, loadBalancer.getConnectivityState());
    inOrder.verify(mockSubchannel1).requestConnection();
    assertEquals(CONNECTING, loadBalancer.getConnectivityState());

    // Failing first connection attempt
    Status error = Status.UNAVAILABLE.withDescription("Simulated connection error");
    stateListener.onSubchannelState(ConnectivityStateInfo.forTransientFailure(error));
    assertEquals(CONNECTING, loadBalancer.getConnectivityState());

    // Starting second connection attempt
    assertEquals(CONNECTING, loadBalancer.getConnectivityState());
    inOrder.verify(mockSubchannel2).requestConnection();
    assertEquals(CONNECTING, loadBalancer.getConnectivityState());

    // Mimic backoff for first address
    stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(CONNECTING));
    assertEquals(CONNECTING, loadBalancer.getConnectivityState());

    // Connection attempt to first address is now successful
    stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
    assertEquals(READY, loadBalancer.getConnectivityState());

    // verify that picker returns correct subchannel
    inOrder.verify(mockHelper).updateBalancingState(eq(READY), pickerCaptor.capture());
    SubchannelPicker picker = pickerCaptor.getValue();
    assertEquals(PickResult.withSubchannel(mockSubchannel1), picker.pickSubchannel(mockArgs));
    assertNotEquals(PickResult.withSubchannel(mockSubchannel2), picker.pickSubchannel(mockArgs));
    assertNotEquals(PickResult.withSubchannel(mockSubchannel3), picker.pickSubchannel(mockArgs));
    assertNotEquals(PickResult.withSubchannel(mockSubchannel4), picker.pickSubchannel(mockArgs));
  }

  @Test
  public void success_from_transient_failure() {
    InOrder inOrder = inOrder(mockHelper, mockSubchannel1, mockSubchannel2,
        mockSubchannel3, mockSubchannel4);

    // Creating first set of endpoints/addresses
    List<EquivalentAddressGroup> addrs = Lists.newArrayList(servers.get(0), servers.get(1));

    // Accepting resolved addresses starts all subchannels
    assertEquals(IDLE, loadBalancer.getConnectivityState());
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(addrs).setAttributes(affinity).build());
    inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
    assertEquals(CONNECTING, loadBalancer.getConnectivityState());
    inOrder.verify(mockSubchannel1).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener = stateListenerCaptor.getValue();
    inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
    inOrder.verify(mockSubchannel2).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener2 = stateListenerCaptor.getValue();
    assertEquals(CONNECTING, loadBalancer.getConnectivityState());
    inOrder.verify(mockHelper).updateBalancingState(eq(CONNECTING), pickerCaptor.capture());
    inOrder.verify(mockSubchannel1).requestConnection();
    assertEquals(CONNECTING, loadBalancer.getConnectivityState());

    // Failing first connection attempt
    Status error = Status.UNAVAILABLE.withDescription("Simulated connection error");
    stateListener.onSubchannelState(ConnectivityStateInfo.forTransientFailure(error));
    assertEquals(CONNECTING, loadBalancer.getConnectivityState());

    // Starting second connection attempt
    assertEquals(CONNECTING, loadBalancer.getConnectivityState());
    inOrder.verify(mockSubchannel2).requestConnection();
    assertEquals(CONNECTING, loadBalancer.getConnectivityState());

    // Mimic backoff for first address
    stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(CONNECTING));
    assertEquals(CONNECTING, loadBalancer.getConnectivityState());

    // Failing second connection attempt
    stateListener2.onSubchannelState(ConnectivityStateInfo.forTransientFailure(error));
    inOrder.verify(mockHelper).refreshNameResolution();
    inOrder.verify(mockHelper).updateBalancingState(eq(TRANSIENT_FAILURE), pickerCaptor.capture());
    // sticky transient failure
    assertEquals(TRANSIENT_FAILURE, loadBalancer.getConnectivityState());

    // Failing connection attempt to first address
    stateListener.onSubchannelState(ConnectivityStateInfo.forTransientFailure(error));
    assertEquals(TRANSIENT_FAILURE, loadBalancer.getConnectivityState());

    // Mimic backoff for second address
    stateListener2.onSubchannelState(ConnectivityStateInfo.forNonError(CONNECTING));
    assertEquals(TRANSIENT_FAILURE, loadBalancer.getConnectivityState());

    // Connection attempt to second address is now successful
    stateListener2.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
    assertEquals(READY, loadBalancer.getConnectivityState());

    // Verify that picker returns correct subchannel
    inOrder.verify(mockHelper).updateBalancingState(eq(READY), pickerCaptor.capture());
    SubchannelPicker picker = pickerCaptor.getValue();
    assertEquals(PickResult.withSubchannel(mockSubchannel2), picker.pickSubchannel(mockArgs));
    assertNotEquals(PickResult.withSubchannel(mockSubchannel1), picker.pickSubchannel(mockArgs));
    assertNotEquals(PickResult.withSubchannel(mockSubchannel3), picker.pickSubchannel(mockArgs));
    assertNotEquals(PickResult.withSubchannel(mockSubchannel4), picker.pickSubchannel(mockArgs));

    // If first address is successful, nothing happens. Verify by checking mock helper
    stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
    verifyNoMoreInteractions(mockHelper);
  }

  @Test
  public void lastAddressFailingNotTransientFailure() {
    // This tests the case where after an address update, the last address escapes a backoff
    // and reports transient failure before all addresses have failed connection
    // attempts, in which case we should not report TRANSIENT_FAILURE.
    InOrder inOrder = inOrder(mockHelper, mockSubchannel1, mockSubchannel2,
        mockSubchannel3, mockSubchannel4);
    // Creating first set of endpoints/addresses
    List<EquivalentAddressGroup> oldServers = Lists.newArrayList(servers.get(0), servers.get(1));

    // Accept Addresses and verify proper connection flow
    assertEquals(IDLE, loadBalancer.getConnectivityState());
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(oldServers).setAttributes(affinity).build());
    inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
    inOrder.verify(mockSubchannel1).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener = stateListenerCaptor.getValue();
    inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
    inOrder.verify(mockSubchannel2).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener2 = stateListenerCaptor.getValue();
    inOrder.verify(mockHelper).updateBalancingState(eq(CONNECTING), pickerCaptor.capture());
    assertEquals(CONNECTING, loadBalancer.getConnectivityState());

    // First connection attempt is unsuccessful
    inOrder.verify(mockSubchannel1).requestConnection();
    assertEquals(CONNECTING, loadBalancer.getConnectivityState());
    Status error = Status.UNAVAILABLE.withDescription("Simulated connection error");
    stateListener.onSubchannelState(ConnectivityStateInfo.forTransientFailure(error));
    assertEquals(CONNECTING, loadBalancer.getConnectivityState());

    // Second connection attempt is connecting
    inOrder.verify(mockSubchannel2).requestConnection();
    assertEquals(CONNECTING, loadBalancer.getConnectivityState());

    // Accept same resolved addresses to update
    List<EquivalentAddressGroup> newServers = Lists.newArrayList(servers.get(2), servers.get(1));
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(newServers).setAttributes(affinity).build());

    // Verify that no new subchannels were created or started
    inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
    inOrder.verify(mockSubchannel3).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener3 = stateListenerCaptor.getValue();
    inOrder.verify(mockSubchannel1).shutdown();
    inOrder.verify(mockSubchannel3).requestConnection();
    assertEquals(CONNECTING, loadBalancer.getConnectivityState());

    // Second address connection attempt is unsuccessful, but should not go into transient failure
    stateListener2.onSubchannelState(ConnectivityStateInfo.forTransientFailure(error));
    assertEquals(CONNECTING, loadBalancer.getConnectivityState());

    // Third address connection attempt is unsuccessful, now we enter transient failure
    stateListener3.onSubchannelState(ConnectivityStateInfo.forTransientFailure(error));
    assertEquals(TRANSIENT_FAILURE, loadBalancer.getConnectivityState());

    // Obselete subchannels have no impact
    stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
    assertEquals(TRANSIENT_FAILURE, loadBalancer.getConnectivityState());

    // Second subchannel is successful
    stateListener2.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
    assertEquals(READY, loadBalancer.getConnectivityState());

    // verify that picker returns correct subchannel
    inOrder.verify(mockSubchannel3).shutdown();
    inOrder.verify(mockHelper).updateBalancingState(eq(READY), pickerCaptor.capture());
    SubchannelPicker picker = pickerCaptor.getValue();
    assertEquals(PickResult.withSubchannel(mockSubchannel2), picker.pickSubchannel(mockArgs));
    assertNotEquals(PickResult.withSubchannel(mockSubchannel1), picker.pickSubchannel(mockArgs));
    assertNotEquals(PickResult.withSubchannel(mockSubchannel3), picker.pickSubchannel(mockArgs));
    assertNotEquals(PickResult.withSubchannel(mockSubchannel4), picker.pickSubchannel(mockArgs));
  }

  @Test
  public void recreate_shutdown_subchannel() {
    // Take the case where the latter subchannel is readied. If we then go to an IDLE state and
    // re-request a connection, we should start and create a new subchannel for the first
    // address in our list.

    // Starting first connection attempt
    InOrder inOrder = inOrder(mockHelper, mockSubchannel1, mockSubchannel2,
        mockSubchannel3, mockSubchannel4); // captor: captures

    // Creating first set of endpoints/addresses
    List<EquivalentAddressGroup> addrs =
        Lists.newArrayList(servers.get(0), servers.get(1));

    assertEquals(IDLE, loadBalancer.getConnectivityState());
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(addrs).setAttributes(affinity).build());
    inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
    assertEquals(CONNECTING, loadBalancer.getConnectivityState());
    inOrder.verify(mockSubchannel1).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener = stateListenerCaptor.getValue();
    inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
    inOrder.verify(mockSubchannel2).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener2 = stateListenerCaptor.getValue();
    assertEquals(CONNECTING, loadBalancer.getConnectivityState());
    inOrder.verify(mockSubchannel1).requestConnection();
    assertEquals(CONNECTING, loadBalancer.getConnectivityState());

    // Failing first connection attempt
    Status error = Status.UNAVAILABLE.withDescription("Simulated connection error");
    stateListener.onSubchannelState(ConnectivityStateInfo.forTransientFailure(error));
    assertEquals(CONNECTING, loadBalancer.getConnectivityState());

    // Starting second connection attempt
    assertEquals(CONNECTING, loadBalancer.getConnectivityState());
    inOrder.verify(mockSubchannel2).requestConnection();
    assertEquals(CONNECTING, loadBalancer.getConnectivityState());

    // Successful second connection attempt
    stateListener2.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
    inOrder.verify(mockSubchannel1).shutdown();
    assertEquals(READY, loadBalancer.getConnectivityState());

    // Go to IDLE
    stateListener2.onSubchannelState(ConnectivityStateInfo.forNonError(IDLE));
    inOrder.verify(mockHelper).updateBalancingState(eq(IDLE), pickerCaptor.capture());
    assertEquals(IDLE, loadBalancer.getConnectivityState());

    SubchannelPicker picker = pickerCaptor.getValue();

    // Calling pickSubchannel() requests a connection, gives the same result when called twice.
    assertEquals(picker.pickSubchannel(mockArgs), picker.pickSubchannel(mockArgs));
    inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
    inOrder.verify(mockSubchannel3).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener3 = stateListenerCaptor.getValue();
    inOrder.verify(mockSubchannel3).requestConnection();
    when(mockSubchannel3.getAllAddresses()).thenReturn(Lists.newArrayList(servers.get(0)));
    stateListener3.onSubchannelState(ConnectivityStateInfo.forNonError(CONNECTING));
    inOrder.verify(mockHelper).updateBalancingState(eq(CONNECTING), pickerCaptor.capture());
    assertEquals(CONNECTING, loadBalancer.getConnectivityState());

    // first subchannel connection attempt fails
    stateListener3.onSubchannelState(ConnectivityStateInfo.forTransientFailure(error));
    assertEquals(CONNECTING, loadBalancer.getConnectivityState());

    // second subchannel connection attempt
    inOrder.verify(mockSubchannel2).requestConnection();
    stateListener2.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
    assertEquals(READY, loadBalancer.getConnectivityState());
    inOrder.verify(mockSubchannel3).shutdown();

    // verify that picker returns correct subchannel
    inOrder.verify(mockHelper).updateBalancingState(eq(READY), pickerCaptor.capture());
    picker = pickerCaptor.getValue();
    assertEquals(PickResult.withSubchannel(mockSubchannel2), picker.pickSubchannel(mockArgs));
    assertNotEquals(PickResult.withSubchannel(mockSubchannel1), picker.pickSubchannel(mockArgs));
    assertNotEquals(PickResult.withSubchannel(mockSubchannel3), picker.pickSubchannel(mockArgs));
    assertNotEquals(PickResult.withSubchannel(mockSubchannel4), picker.pickSubchannel(mockArgs));
  }

  @Test
  public void ready_then_transient_failure_again() {
    // Starting first connection attempt
    InOrder inOrder = inOrder(mockHelper, mockSubchannel1, mockSubchannel2,
        mockSubchannel3, mockSubchannel4); // captor: captures

    // Creating first set of endpoints/addresses
    List<EquivalentAddressGroup> addrs =
        Lists.newArrayList(servers.get(0), servers.get(1));

    assertEquals(IDLE, loadBalancer.getConnectivityState());
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(addrs).setAttributes(affinity).build());
    inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
    assertEquals(CONNECTING, loadBalancer.getConnectivityState());
    inOrder.verify(mockSubchannel1).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener = stateListenerCaptor.getValue();
    inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
    inOrder.verify(mockSubchannel2).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener2 = stateListenerCaptor.getValue();
    assertEquals(CONNECTING, loadBalancer.getConnectivityState());
    inOrder.verify(mockSubchannel1).requestConnection();
    assertEquals(CONNECTING, loadBalancer.getConnectivityState());

    // Failing first connection attempt
    Status error = Status.UNAVAILABLE.withDescription("Simulated connection error");
    stateListener.onSubchannelState(ConnectivityStateInfo.forTransientFailure(error));
    assertEquals(CONNECTING, loadBalancer.getConnectivityState());

    // Starting second connection attempt
    assertEquals(CONNECTING, loadBalancer.getConnectivityState());
    inOrder.verify(mockSubchannel2).requestConnection();
    assertEquals(CONNECTING, loadBalancer.getConnectivityState());

    // Successful second connection attempt
    stateListener2.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
    inOrder.verify(mockSubchannel1).shutdown();
    assertEquals(READY, loadBalancer.getConnectivityState());

    // Go to IDLE
    stateListener2.onSubchannelState(ConnectivityStateInfo.forNonError(IDLE));
    inOrder.verify(mockHelper).updateBalancingState(eq(IDLE), pickerCaptor.capture());
    assertEquals(IDLE, loadBalancer.getConnectivityState());

    SubchannelPicker picker = pickerCaptor.getValue();

    // Calling pickSubchannel() requests a connection, gives the same result when called twice.
    assertEquals(picker.pickSubchannel(mockArgs), picker.pickSubchannel(mockArgs));
    inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
    inOrder.verify(mockSubchannel3).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener3 = stateListenerCaptor.getValue();
    inOrder.verify(mockSubchannel3).requestConnection();
    when(mockSubchannel3.getAllAddresses()).thenReturn(Lists.newArrayList(servers.get(0)));
    stateListener3.onSubchannelState(ConnectivityStateInfo.forNonError(CONNECTING));
    inOrder.verify(mockHelper).updateBalancingState(eq(CONNECTING), pickerCaptor.capture());
    assertEquals(CONNECTING, loadBalancer.getConnectivityState());

    // first subchannel connection attempt fails
    stateListener3.onSubchannelState(ConnectivityStateInfo.forTransientFailure(error));
    assertEquals(CONNECTING, loadBalancer.getConnectivityState());

    // second subchannel connection attempt
    inOrder.verify(mockSubchannel2).requestConnection();
    stateListener2.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
    assertEquals(READY, loadBalancer.getConnectivityState());

    // verify that picker returns correct subchannel
    inOrder.verify(mockSubchannel3).shutdown();
    inOrder.verify(mockHelper).updateBalancingState(eq(READY), pickerCaptor.capture());
    picker = pickerCaptor.getValue();
    assertEquals(PickResult.withSubchannel(mockSubchannel2), picker.pickSubchannel(mockArgs));
    assertNotEquals(PickResult.withSubchannel(mockSubchannel1), picker.pickSubchannel(mockArgs));
    assertNotEquals(PickResult.withSubchannel(mockSubchannel3), picker.pickSubchannel(mockArgs));
    assertNotEquals(PickResult.withSubchannel(mockSubchannel4), picker.pickSubchannel(mockArgs));
  }


  @Test
  public void index_looping() {
    Attributes.Key<String> key = Attributes.Key.create("some-key");
    Attributes attr1 = Attributes.newBuilder().set(key, "1").build();
    Attributes attr2 = Attributes.newBuilder().set(key, "2").build();
    Attributes attr3 = Attributes.newBuilder().set(key, "3").build();
    SocketAddress addr1 = new FakeSocketAddress("addr1");
    SocketAddress addr2 = new FakeSocketAddress("addr2");
    SocketAddress addr3 = new FakeSocketAddress("addr3");
    SocketAddress addr4 = new FakeSocketAddress("addr4");
    SocketAddress addr5 = new FakeSocketAddress("addr5");
    PickFirstLeafLoadBalancer.Index index = new PickFirstLeafLoadBalancer.Index(Arrays.asList(
        new EquivalentAddressGroup(Arrays.asList(addr1, addr2), attr1),
        new EquivalentAddressGroup(Arrays.asList(addr3), attr2),
        new EquivalentAddressGroup(Arrays.asList(addr4, addr5), attr3)));
    assertThat(index.getCurrentAddress()).isSameInstanceAs(addr1);
    assertThat(index.getCurrentEagAttributes()).isSameInstanceAs(attr1);
    assertThat(index.isAtBeginning()).isTrue();
    assertThat(index.isValid()).isTrue();

    index.increment();
    assertThat(index.getCurrentAddress()).isSameInstanceAs(addr2);
    assertThat(index.getCurrentEagAttributes()).isSameInstanceAs(attr1);
    assertThat(index.isAtBeginning()).isFalse();
    assertThat(index.isValid()).isTrue();

    index.increment();
    assertThat(index.getCurrentAddress()).isSameInstanceAs(addr3);
    assertThat(index.getCurrentEagAttributes()).isSameInstanceAs(attr2);
    assertThat(index.isAtBeginning()).isFalse();
    assertThat(index.isValid()).isTrue();

    index.increment();
    assertThat(index.getCurrentAddress()).isSameInstanceAs(addr4);
    assertThat(index.getCurrentEagAttributes()).isSameInstanceAs(attr3);
    assertThat(index.isAtBeginning()).isFalse();
    assertThat(index.isValid()).isTrue();

    index.increment();
    assertThat(index.getCurrentAddress()).isSameInstanceAs(addr5);
    assertThat(index.getCurrentEagAttributes()).isSameInstanceAs(attr3);
    assertThat(index.isAtBeginning()).isFalse();
    assertThat(index.isValid()).isTrue();

    index.increment();
    assertThat(index.isAtBeginning()).isFalse();
    assertThat(index.isValid()).isFalse();

    index.reset();
    assertThat(index.getCurrentAddress()).isSameInstanceAs(addr1);
    assertThat(index.getCurrentEagAttributes()).isSameInstanceAs(attr1);
    assertThat(index.isAtBeginning()).isTrue();
    assertThat(index.isValid()).isTrue();

    // We want to make sure both groupIndex and addressIndex are reset
    index.increment();
    index.increment();
    index.increment();
    index.increment();
    assertThat(index.getCurrentAddress()).isSameInstanceAs(addr5);
    assertThat(index.getCurrentEagAttributes()).isSameInstanceAs(attr3);
    index.reset();
    assertThat(index.getCurrentAddress()).isSameInstanceAs(addr1);
    assertThat(index.getCurrentEagAttributes()).isSameInstanceAs(attr1);
  }

  @Test
  public void index_updateGroups_resets() {
    SocketAddress addr1 = new FakeSocketAddress("addr1");
    SocketAddress addr2 = new FakeSocketAddress("addr2");
    SocketAddress addr3 = new FakeSocketAddress("addr3");
    PickFirstLeafLoadBalancer.Index index = new PickFirstLeafLoadBalancer.Index(Arrays.asList(
        new EquivalentAddressGroup(Arrays.asList(addr1)),
        new EquivalentAddressGroup(Arrays.asList(addr2, addr3))));
    index.increment();
    index.increment();
    // We want to make sure both groupIndex and addressIndex are reset
    index.updateGroups(Arrays.asList(
        new EquivalentAddressGroup(Arrays.asList(addr1)),
        new EquivalentAddressGroup(Arrays.asList(addr2, addr3))));
    assertThat(index.getCurrentAddress()).isSameInstanceAs(addr1);
  }

  @Test
  public void index_seekTo() {
    SocketAddress addr1 = new FakeSocketAddress("addr1");
    SocketAddress addr2 = new FakeSocketAddress("addr2");
    SocketAddress addr3 = new FakeSocketAddress("addr3");
    PickFirstLeafLoadBalancer.Index index = new PickFirstLeafLoadBalancer.Index(Arrays.asList(
        new EquivalentAddressGroup(Arrays.asList(addr1, addr2)),
        new EquivalentAddressGroup(Arrays.asList(addr3))));
    assertThat(index.seekTo(addr3)).isTrue();
    assertThat(index.getCurrentAddress()).isSameInstanceAs(addr3);
    assertThat(index.seekTo(addr1)).isTrue();
    assertThat(index.getCurrentAddress()).isSameInstanceAs(addr1);
    assertThat(index.seekTo(addr2)).isTrue();
    assertThat(index.getCurrentAddress()).isSameInstanceAs(addr2);
    index.seekTo(new FakeSocketAddress("addr4"));
    // Failed seekTo doesn't change the index
    assertThat(index.getCurrentAddress()).isSameInstanceAs(addr2);
  }

  private static class FakeSocketAddress extends SocketAddress {
    final String name;

    FakeSocketAddress(String name) {
      this.name = name;
    }

    @Override
    public String toString() {
      return "FakeSocketAddress-" + name;
    }

  }

  private static class FakeSubchannel extends Subchannel {
    private final Attributes attributes;
    private List<EquivalentAddressGroup> eags;
    private SubchannelStateListener listener;

    public FakeSubchannel(List<EquivalentAddressGroup> eags, Attributes attributes) {
      this.eags = Collections.unmodifiableList(eags);
      this.attributes = attributes;
    }

    @Override
    public List<EquivalentAddressGroup> getAllAddresses() {
      return eags;
    }

    @Override
    public Attributes getAttributes() {
      return attributes;
    }

    @Override
    public void start(SubchannelStateListener listener) {
      this.listener = checkNotNull(listener, "listener");
    }

    @Override
    public void updateAddresses(List<EquivalentAddressGroup> addrs) {
      this.eags = Collections.unmodifiableList(addrs);
    }

    @Override
    public void shutdown() {
    }

    @Override
    public void requestConnection() {
      listener.onSubchannelState(ConnectivityStateInfo.forNonError(CONNECTING));
    }
  }
}