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
import static io.grpc.xds.LeastRequestLoadBalancer.STATE_INFO;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import io.grpc.Attributes;
import io.grpc.ClientStreamTracer;
import io.grpc.ClientStreamTracer.StreamInfo;
import io.grpc.ConnectivityState;
import io.grpc.ConnectivityStateInfo;
import io.grpc.EquivalentAddressGroup;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancer.CreateSubchannelArgs;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancer.PickResult;
import io.grpc.LoadBalancer.PickSubchannelArgs;
import io.grpc.LoadBalancer.ResolvedAddresses;
import io.grpc.LoadBalancer.Subchannel;
import io.grpc.LoadBalancer.SubchannelPicker;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.util.AbstractTestHelper;
import io.grpc.util.MultiChildLoadBalancer.ChildLbState;
import io.grpc.xds.LeastRequestLoadBalancer.EmptyPicker;
import io.grpc.xds.LeastRequestLoadBalancer.LeastRequestConfig;
import io.grpc.xds.LeastRequestLoadBalancer.LeastRequestLbState;
import io.grpc.xds.LeastRequestLoadBalancer.ReadyPicker;
import io.grpc.xds.LeastRequestLoadBalancer.Ref;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
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

/** Unit test for {@link LeastRequestLoadBalancer}. */
@RunWith(JUnit4.class)
public class LeastRequestLoadBalancerTest {
  @Rule public final MockitoRule mocks = MockitoJUnit.rule();

  private static final Attributes.Key<String> MAJOR_KEY = Attributes.Key.create("major-key");

  private LeastRequestLoadBalancer loadBalancer;
  private final List<EquivalentAddressGroup> servers = Lists.newArrayList();
  private final Map<List<EquivalentAddressGroup>, Subchannel> subchannels = Maps.newLinkedHashMap();
  private final Attributes affinity =
      Attributes.newBuilder().set(MAJOR_KEY, "I got the keys").build();

  @Captor
  private ArgumentCaptor<SubchannelPicker> pickerCaptor;
  @Captor
  private ArgumentCaptor<ConnectivityState> stateCaptor;
  @Captor
  private ArgumentCaptor<CreateSubchannelArgs> createArgsCaptor;
  private final TestHelper testHelperInstance = new TestHelper();
  private Helper helper = mock(Helper.class, delegatesTo(testHelperInstance));

  @Mock
  private ThreadSafeRandom mockRandom;

  @Mock // This LoadBalancer doesn't use any of the arg fields, as verified in tearDown().
  private PickSubchannelArgs mockArgs;

  @Before
  public void setUp() {
    for (int i = 0; i < 3; i++) {
      SocketAddress addr = new FakeSocketAddress("server" + i);
      EquivalentAddressGroup eag = new EquivalentAddressGroup(addr);
      servers.add(eag);
      // Subchannel sc = helper.createSubchannel(CreateSubchannelArgs.newBuilder().setAddresses(eag)
      //     .build());
      // subchannels.put(Arrays.asList(eag), sc);
    }

    loadBalancer = new LeastRequestLoadBalancer(helper, mockRandom);
  }

  @After
  public void tearDown() throws Exception {
    verifyNoMoreInteractions(mockRandom);
    verifyNoMoreInteractions(mockArgs);
  }

  @Test
  public void pickAfterResolved() throws Exception {
    boolean addressesAccepted = loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(servers).setAttributes(affinity).build());
    assertThat(addressesAccepted).isTrue();
    final Subchannel readySubchannel = subchannels.values().iterator().next();
    deliverSubchannelState(readySubchannel, ConnectivityStateInfo.forNonError(READY));

    verify(helper, times(3)).createSubchannel(createArgsCaptor.capture());
    List<List<EquivalentAddressGroup>> capturedAddrs = new ArrayList<>();
    for (CreateSubchannelArgs arg : createArgsCaptor.getAllValues()) {
      capturedAddrs.add(arg.getAddresses());
    }

    assertThat(capturedAddrs).containsAtLeastElementsIn(subchannels.keySet());
    for (Subchannel subchannel : subchannels.values()) {
      verify(subchannel).requestConnection();
      verify(subchannel, never()).shutdown();
    }

    verify(helper, times(2))
        .updateBalancingState(stateCaptor.capture(), pickerCaptor.capture());

    assertEquals(CONNECTING, stateCaptor.getAllValues().get(0));
    assertEquals(READY, stateCaptor.getAllValues().get(1));
    assertThat(getList(pickerCaptor.getValue())).containsExactly(readySubchannel);

    verifyNoMoreInteractions(helper);
  }

  @Test
  public void pickAfterResolvedUpdatedHosts() throws Exception {
    Subchannel removedSubchannel = mock(Subchannel.class);
    Subchannel oldSubchannel = mock(Subchannel.class);
    Subchannel newSubchannel = mock(Subchannel.class);

    Attributes.Key<String> key = Attributes.Key.create("check-that-it-is-propagated");
    FakeSocketAddress removedAddr = new FakeSocketAddress("removed");
    EquivalentAddressGroup removedEag = new EquivalentAddressGroup(removedAddr);
    FakeSocketAddress oldAddr = new FakeSocketAddress("old");
    EquivalentAddressGroup oldEag1 = new EquivalentAddressGroup(oldAddr);
    EquivalentAddressGroup oldEag2 = new EquivalentAddressGroup(
        oldAddr, Attributes.newBuilder().set(key, "oldattr").build());
    FakeSocketAddress newAddr = new FakeSocketAddress("new");
    EquivalentAddressGroup newEag = new EquivalentAddressGroup(
        newAddr, Attributes.newBuilder().set(key, "newattr").build());

    subchannels.put(Collections.singletonList(removedEag), removedSubchannel);
    subchannels.put(Collections.singletonList(oldEag1), oldSubchannel);
    subchannels.put(Collections.singletonList(newEag), newSubchannel);

    List<EquivalentAddressGroup> currentServers = Lists.newArrayList(removedEag, oldEag1);

    InOrder inOrder = inOrder(helper);

    boolean addressesAccepted = loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(currentServers).setAttributes(affinity)
            .build());
    assertThat(addressesAccepted).isTrue();

    inOrder.verify(helper).updateBalancingState(eq(CONNECTING), pickerCaptor.capture());

    deliverSubchannelState(removedSubchannel, ConnectivityStateInfo.forNonError(READY));
    deliverSubchannelState(oldSubchannel, ConnectivityStateInfo.forNonError(READY));

    inOrder.verify(helper, times(2)).updateBalancingState(eq(READY), pickerCaptor.capture());
    SubchannelPicker picker = pickerCaptor.getValue();
    assertThat(getList(picker)).containsExactly(removedSubchannel, oldSubchannel);

    verify(removedSubchannel, times(1)).requestConnection();
    verify(oldSubchannel, times(1)).requestConnection();

    assertThat(getChildEags(loadBalancer)).containsExactly(removedEag, oldEag1);

    // This time with Attributes
    List<EquivalentAddressGroup> latestServers = Lists.newArrayList(oldEag2, newEag);

    addressesAccepted = loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(latestServers).setAttributes(affinity).build());
    assertThat(addressesAccepted).isTrue();

    verify(newSubchannel, times(1)).requestConnection();
    verify(oldSubchannel, times(1)).updateAddresses(Arrays.asList(oldEag2));
    verify(removedSubchannel, times(1)).shutdown();

    deliverSubchannelState(removedSubchannel, ConnectivityStateInfo.forNonError(SHUTDOWN));
    deliverSubchannelState(newSubchannel, ConnectivityStateInfo.forNonError(READY));

    assertThat(getChildEags(loadBalancer)).containsExactly(oldEag1, newEag);

    verify(helper, times(3)).createSubchannel(any(CreateSubchannelArgs.class));
    inOrder.verify(helper, times(2)).updateBalancingState(eq(READY), pickerCaptor.capture());

    picker = pickerCaptor.getValue();
    assertThat(getList(picker)).containsExactly(oldSubchannel, newSubchannel);

    verifyNoMoreInteractions(helper);
  }

  private static List<Object> getChildEags(LeastRequestLoadBalancer loadBalancer) {
    return loadBalancer.getChildLbStates().stream()
        .map(ChildLbState::getKey)
        .collect(Collectors.toList());
  }

  private List<Subchannel> getSubchannels(LeastRequestLoadBalancer lb) {
    return lb.getChildLbStates().stream()
        .map(this::getSubchannel)
        .collect(Collectors.toList());
  }

  private LeastRequestLbState getChildLbState(PickResult pickResult) {
    EquivalentAddressGroup eag = pickResult.getSubchannel().getAddresses();
    return (LeastRequestLbState) loadBalancer.getChildLbState(eag);
  }

  @Test
  public void pickAfterStateChange() throws Exception {
    InOrder inOrder = inOrder(helper);
    boolean addressesAccepted = loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(servers).setAttributes(Attributes.EMPTY)
            .build());
    assertThat(addressesAccepted).isTrue();
    Subchannel subchannel = getSubchannels(loadBalancer).get(0);
    Ref<ConnectivityStateInfo> subchannelStateInfo = subchannel.getAttributes().get(STATE_INFO);

    inOrder.verify(helper).updateBalancingState(eq(CONNECTING), isA(EmptyPicker.class));
    assertThat(subchannelStateInfo.value).isEqualTo(ConnectivityStateInfo.forNonError(IDLE));

    deliverSubchannelState(subchannel,
        ConnectivityStateInfo.forNonError(READY));
    inOrder.verify(helper).updateBalancingState(eq(READY), pickerCaptor.capture());
    assertThat(pickerCaptor.getValue()).isInstanceOf(ReadyPicker.class);
    assertThat(subchannelStateInfo.value).isEqualTo(
        ConnectivityStateInfo.forNonError(READY));

    Status error = Status.UNKNOWN.withDescription("¯\\_(ツ)_//¯");
    deliverSubchannelState(subchannel,
        ConnectivityStateInfo.forTransientFailure(error));
    assertThat(subchannelStateInfo.value.getState()).isEqualTo(TRANSIENT_FAILURE);
    assertThat(subchannelStateInfo.value.getStatus()).isEqualTo(error);
    inOrder.verify(helper).refreshNameResolution();
    inOrder.verify(helper).updateBalancingState(eq(CONNECTING), pickerCaptor.capture());
    assertThat(pickerCaptor.getValue()).isInstanceOf(EmptyPicker.class);

    deliverSubchannelState(subchannel,
        ConnectivityStateInfo.forNonError(IDLE));
    inOrder.verify(helper).refreshNameResolution();
    assertThat(subchannelStateInfo.value.getState()).isEqualTo(TRANSIENT_FAILURE);
    assertThat(subchannelStateInfo.value.getStatus()).isEqualTo(error);

    verify(subchannel, times(2)).requestConnection();
    verify(helper, times(3)).createSubchannel(any(CreateSubchannelArgs.class));
    verifyNoMoreInteractions(helper);
  }

  @Test
  public void pickAfterConfigChange() {
    final LeastRequestConfig oldConfig = new LeastRequestConfig(4);
    final LeastRequestConfig newConfig = new LeastRequestConfig(6);
    boolean addressesAccepted = loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(servers).setAttributes(affinity)
            .setLoadBalancingPolicyConfig(oldConfig).build());
    assertThat(addressesAccepted).isTrue();
    final Subchannel readySubchannel = subchannels.values().iterator().next();
    deliverSubchannelState(readySubchannel, ConnectivityStateInfo.forNonError(READY));
    verify(helper, times(3)).createSubchannel(any(CreateSubchannelArgs.class));
    verify(helper, times(2))
        .updateBalancingState(any(ConnectivityState.class), pickerCaptor.capture());

    // At this point it should use a ReadyPicker with oldConfig
    pickerCaptor.getValue().pickSubchannel(mockArgs);
    verify(mockRandom, times(oldConfig.choiceCount)).nextInt(1);

    addressesAccepted = loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(servers).setAttributes(affinity)
            .setLoadBalancingPolicyConfig(newConfig).build());
    assertThat(addressesAccepted).isTrue();
    verify(helper, times(3))
        .updateBalancingState(any(ConnectivityState.class), pickerCaptor.capture());

    // At this point it should use a ReadyPicker with newConfig
    pickerCaptor.getValue().pickSubchannel(mockArgs);
    verify(mockRandom, times(oldConfig.choiceCount + newConfig.choiceCount)).nextInt(1);
    verifyNoMoreInteractions(helper);
  }

  @Test
  public void ignoreShutdownSubchannelStateChange() {
    InOrder inOrder = inOrder(helper);
    boolean addressesAccepted = loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(servers).setAttributes(Attributes.EMPTY)
            .build());
    assertThat(addressesAccepted).isTrue();
    inOrder.verify(helper).updateBalancingState(eq(CONNECTING), isA(EmptyPicker.class));

    loadBalancer.shutdown();
    for (Subchannel sc : getSubchannels(loadBalancer)) {
      verify(sc).shutdown();
      // When the subchannel is being shut down, a SHUTDOWN connectivity state is delivered
      // back to the subchannel state listener.
      deliverSubchannelState(sc, ConnectivityStateInfo.forNonError(SHUTDOWN));
    }

    inOrder.verifyNoMoreInteractions();
  }

  @Test
  public void stayTransientFailureUntilReady() {
    InOrder inOrder = inOrder(helper);
    boolean addressesAccepted = loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(servers).setAttributes(Attributes.EMPTY)
            .build());
    assertThat(addressesAccepted).isTrue();

    inOrder.verify(helper).updateBalancingState(eq(CONNECTING), isA(EmptyPicker.class));

    // Simulate state transitions for each subchannel individually.
    for (Subchannel sc : getSubchannels(loadBalancer)) {
      Status error = Status.UNKNOWN.withDescription("connection broken");
      deliverSubchannelState(
          sc,
          ConnectivityStateInfo.forTransientFailure(error));
      inOrder.verify(helper).refreshNameResolution();
      deliverSubchannelState(
          sc,
          ConnectivityStateInfo.forNonError(CONNECTING));
      Ref<ConnectivityStateInfo> scStateInfo = sc.getAttributes().get(
          STATE_INFO);
      assertThat(scStateInfo.value.getState()).isEqualTo(TRANSIENT_FAILURE);
      assertThat(scStateInfo.value.getStatus()).isEqualTo(error);
    }
    inOrder.verify(helper).updateBalancingState(eq(TRANSIENT_FAILURE), isA(EmptyPicker.class));
    inOrder.verifyNoMoreInteractions();

    Subchannel subchannel = getSubchannels(loadBalancer).iterator().next();
    deliverSubchannelState(subchannel, ConnectivityStateInfo.forNonError(READY));
    Ref<ConnectivityStateInfo> subchannelStateInfo = subchannel.getAttributes().get(
        STATE_INFO);
    assertThat(subchannelStateInfo.value).isEqualTo(ConnectivityStateInfo.forNonError(READY));
    inOrder.verify(helper).updateBalancingState(eq(READY), isA(ReadyPicker.class));

    verify(helper, times(3)).createSubchannel(any(CreateSubchannelArgs.class));
    verifyNoMoreInteractions(helper);
  }

  @Test
  public void refreshNameResolutionWhenSubchannelConnectionBroken() {
    InOrder inOrder = inOrder(helper);
    boolean addressesAccepted = loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(servers).setAttributes(Attributes.EMPTY)
            .build());
    assertThat(addressesAccepted).isTrue();

    verify(helper, times(3)).createSubchannel(any(CreateSubchannelArgs.class));
    inOrder.verify(helper).updateBalancingState(eq(CONNECTING), isA(EmptyPicker.class));

    // Simulate state transitions for each subchannel individually.
    for (Subchannel sc : getSubchannels(loadBalancer)) {
      verify(sc).requestConnection();
      deliverSubchannelState(sc, ConnectivityStateInfo.forNonError(CONNECTING));
      Status error = Status.UNKNOWN.withDescription("connection broken");
      deliverSubchannelState(sc, ConnectivityStateInfo.forTransientFailure(error));
      inOrder.verify(helper).refreshNameResolution();
      deliverSubchannelState(sc, ConnectivityStateInfo.forNonError(READY));
      inOrder.verify(helper).updateBalancingState(eq(READY), isA(ReadyPicker.class));
      // Simulate receiving go-away so READY subchannels transit to IDLE.
      deliverSubchannelState(sc, ConnectivityStateInfo.forNonError(IDLE));
      inOrder.verify(helper).refreshNameResolution();
      verify(sc, times(2)).requestConnection();
      inOrder.verify(helper).updateBalancingState(eq(CONNECTING), isA(EmptyPicker.class));
    }

    verifyNoMoreInteractions(helper);
  }

  @Test
  public void pickerLeastRequest() throws Exception {
    int choiceCount = 2;
    // This should add inFlight counters to all subchannels.
    boolean addressesAccepted = loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(servers).setAttributes(Attributes.EMPTY)
            .setLoadBalancingPolicyConfig(new LeastRequestConfig(choiceCount))
            .build());
    assertThat(addressesAccepted).isTrue();

    assertEquals(3, loadBalancer.getChildLbStates().size());

    List<ChildLbState> childLbStates = Lists.newArrayList(loadBalancer.getChildLbStates());

    // Make sure all inFlight counters have started at 0
    for (int i = 0; i < 3; i++) {
      assertEquals("counter for child " + i, 0,
          ((LeastRequestLbState) childLbStates.get(i)).getActiveRequests());
    }

    for (ChildLbState cs : childLbStates) {
      deliverSubchannelState(getSubchannel(cs), ConnectivityStateInfo.forNonError(READY));
    }

    // Capture the active ReadyPicker once all subchannels are READY
    verify(helper, times(4))
        .updateBalancingState(any(ConnectivityState.class), pickerCaptor.capture());
    assertThat(pickerCaptor.getValue()).isInstanceOf(ReadyPicker.class);

    ReadyPicker picker = (ReadyPicker) pickerCaptor.getValue();

    assertThat(picker.getList()).containsExactlyElementsIn(childLbStates);

    // Make random return 0, then 2 for the sample indexes.
    when(mockRandom.nextInt(childLbStates.size())).thenReturn(0, 2);
    PickResult pickResult1 = picker.pickSubchannel(mockArgs);
    verify(mockRandom, times(choiceCount)).nextInt(childLbStates.size());
    assertEquals(childLbStates.get(0), getChildLbState(pickResult1));
    // This simulates sending the actual RPC on the picked channel
    ClientStreamTracer streamTracer1 =
        pickResult1.getStreamTracerFactory()
            .newClientStreamTracer(StreamInfo.newBuilder().build(), new Metadata());
    streamTracer1.streamCreated(Attributes.EMPTY, new Metadata());
    assertEquals(1, getChildLbState(pickResult1).getActiveRequests());

    // For the second pick it should pick the one with lower inFlight.
    when(mockRandom.nextInt(childLbStates.size())).thenReturn(0, 2);
    PickResult pickResult2 = picker.pickSubchannel(mockArgs);
    // Since this is the second pick we expect the total random samples to be choiceCount * 2
    verify(mockRandom, times(choiceCount * 2)).nextInt(childLbStates.size());
    assertEquals(childLbStates.get(2), getChildLbState(pickResult2));

    // For the third pick we unavoidably pick subchannel with index 1.
    when(mockRandom.nextInt(childLbStates.size())).thenReturn(1, 1);
    PickResult pickResult3 = picker.pickSubchannel(mockArgs);
    verify(mockRandom, times(choiceCount * 3)).nextInt(childLbStates.size());
    assertEquals(childLbStates.get(1), getChildLbState(pickResult3));

    // Finally ensure a finished RPC decreases inFlight
    streamTracer1.streamClosed(Status.OK);
    assertEquals(0, getChildLbState(pickResult1).getActiveRequests());
  }

  @Test
  public void pickerEmptyList() throws Exception {
    SubchannelPicker picker = new EmptyPicker(Status.UNKNOWN);

    assertNull(picker.pickSubchannel(mockArgs).getSubchannel());
    assertEquals(Status.UNKNOWN,
        picker.pickSubchannel(mockArgs).getStatus());
  }

  @Test
  public void nameResolutionErrorWithNoChannels() throws Exception {
    Status error = Status.NOT_FOUND.withDescription("nameResolutionError");
    loadBalancer.handleNameResolutionError(error);
    verify(helper).updateBalancingState(eq(TRANSIENT_FAILURE), pickerCaptor.capture());

    LoadBalancer.PickResult pickResult = pickerCaptor.getValue().pickSubchannel(mockArgs);
    assertNull(pickResult.getSubchannel());
    assertEquals(error, pickResult.getStatus());
    verifyNoMoreInteractions(helper);
  }

  @Test
  public void nameResolutionErrorWithActiveChannels() throws Exception {
    int choiceCount = 8;
    boolean addressesAccepted = loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder()
            .setLoadBalancingPolicyConfig(new LeastRequestConfig(choiceCount))
            .setAddresses(servers).setAttributes(affinity).build());
    assertThat(addressesAccepted).isTrue();
    final Subchannel readySubchannel = subchannels.values().iterator().next();

    deliverSubchannelState(readySubchannel, ConnectivityStateInfo.forNonError(READY));
    loadBalancer.handleNameResolutionError(Status.NOT_FOUND.withDescription("nameResolutionError"));

    verify(helper, times(3)).createSubchannel(any(CreateSubchannelArgs.class));
    verify(helper, times(2))
        .updateBalancingState(stateCaptor.capture(), pickerCaptor.capture());

    Iterator<ConnectivityState> stateIterator = stateCaptor.getAllValues().iterator();
    assertEquals(CONNECTING, stateIterator.next());
    assertEquals(READY, stateIterator.next());

    LoadBalancer.PickResult pickResult = pickerCaptor.getValue().pickSubchannel(mockArgs);
    verify(mockRandom, times(choiceCount)).nextInt(1);
    assertEquals(readySubchannel, pickResult.getSubchannel());
    assertEquals(Status.OK.getCode(), pickResult.getStatus().getCode());

    LoadBalancer.PickResult pickResult2 = pickerCaptor.getValue().pickSubchannel(mockArgs);
    verify(mockRandom, times(choiceCount * 2)).nextInt(1);
    assertEquals(readySubchannel, pickResult2.getSubchannel());
    verifyNoMoreInteractions(helper);
  }

  @Test
  public void subchannelStateIsolation() throws Exception {
    boolean addressesAccepted = loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(servers).setAttributes(Attributes.EMPTY)
            .build());
    assertThat(addressesAccepted).isTrue();
    Iterator<Subchannel> subchannelIterator = subchannels.values().iterator();
    Subchannel sc1 = subchannelIterator.next();
    Subchannel sc2 = subchannelIterator.next();
    Subchannel sc3 = subchannelIterator.next();

    verify(sc1, times(1)).requestConnection();
    verify(sc2, times(1)).requestConnection();
    verify(sc3, times(1)).requestConnection();

    deliverSubchannelState(sc1, ConnectivityStateInfo.forNonError(READY));
    deliverSubchannelState(sc2, ConnectivityStateInfo.forNonError(READY));
    deliverSubchannelState(sc3, ConnectivityStateInfo.forNonError(READY));
    deliverSubchannelState(sc2, ConnectivityStateInfo.forNonError(IDLE));
    deliverSubchannelState(sc3, ConnectivityStateInfo.forTransientFailure(Status.UNAVAILABLE));

    verify(helper, times(3))
        .updateBalancingState(stateCaptor.capture(), pickerCaptor.capture());
    Iterator<ConnectivityState> stateIterator = stateCaptor.getAllValues().iterator();
    Iterator<SubchannelPicker> pickers = pickerCaptor.getAllValues().iterator();
    // The picker is incrementally updated as subchannels become READY
    assertEquals(CONNECTING, stateIterator.next());
    assertThat(pickers.next()).isInstanceOf(EmptyPicker.class);
    assertEquals(READY, stateIterator.next());
    assertThat(getList(pickers.next())).containsExactly(sc1);
    assertEquals(READY, stateIterator.next());
    assertThat(getList(pickers.next())).containsExactly(sc1, sc2);
    assertEquals(READY, stateIterator.next());
    assertThat(getList(pickers.next())).containsExactly(sc1, sc2, sc3);
    // The IDLE subchannel is dropped from the picker, but a reconnection is requested
    assertEquals(READY, stateIterator.next());
    assertThat(getList(pickers.next())).containsExactly(sc1, sc3);
    verify(sc2, times(2)).requestConnection();
    // The failing subchannel is dropped from the picker, with no requested reconnect
    assertEquals(READY, stateIterator.next());
    assertThat(getList(pickers.next())).containsExactly(sc1);
    verify(sc3, times(1)).requestConnection();
    assertThat(stateIterator.hasNext()).isFalse();
    assertThat(pickers.hasNext()).isFalse();
  }

  @Test
  public void readyPicker_emptyList() {
    try {
      // ready picker list must be non-empty
      new ReadyPicker(Collections.emptyList(), 2, mockRandom);
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test
  public void internalPickerComparisons() {
    EmptyPicker emptyOk1 = new EmptyPicker(Status.OK);
    EmptyPicker emptyOk2 = new EmptyPicker(Status.OK.withDescription("different OK"));
    EmptyPicker emptyErr = new EmptyPicker(Status.UNKNOWN.withDescription("¯\\_(ツ)_//¯"));

    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(servers).setAttributes(affinity).build());


    Iterator<ChildLbState> iterator = loadBalancer.getChildLbStates().iterator();
    ChildLbState child1 = iterator.next();
    ChildLbState child2 = iterator.next();
    ReadyPicker ready1 = new ReadyPicker(Arrays.asList(child1, child2), 2, mockRandom);
    ReadyPicker ready2 = new ReadyPicker(Arrays.asList(child1), 2, mockRandom);
    ReadyPicker ready3 = new ReadyPicker(Arrays.asList(child2, child1), 2, mockRandom);
    ReadyPicker ready4 = new ReadyPicker(Arrays.asList(child1, child2), 2, mockRandom);
    ReadyPicker ready5 = new ReadyPicker(Arrays.asList(child2, child1), 2, mockRandom);
    ReadyPicker ready6 = new ReadyPicker(Arrays.asList(child2, child1), 8, mockRandom);

    assertTrue(emptyOk1.isEquivalentTo(emptyOk2));
    assertFalse(emptyOk1.isEquivalentTo(emptyErr));
    assertFalse(ready1.isEquivalentTo(ready2));
    assertTrue(ready1.isEquivalentTo(ready3));
    assertTrue(ready3.isEquivalentTo(ready4));
    assertTrue(ready4.isEquivalentTo(ready5));
    assertFalse(emptyOk1.isEquivalentTo(ready1));
    assertFalse(ready1.isEquivalentTo(emptyOk1));
    assertFalse(ready5.isEquivalentTo(ready6));
  }

  @Test
  public void emptyAddresses() {
    assertThat(loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder()
            .setAddresses(Collections.<EquivalentAddressGroup>emptyList())
            .setAttributes(affinity)
            .build()))
        .isFalse();
  }

  private Subchannel getSubchannel(ChildLbState childLbState) {
    List<EquivalentAddressGroup> eagList =
        Arrays.asList((EquivalentAddressGroup) childLbState.getKey());
    return subchannels.get(eagList);
  }

  private List<Subchannel> getList(SubchannelPicker picker) {
    if (picker instanceof ReadyPicker) {
      return ((ReadyPicker) picker).getList().stream()
          .map(this::getSubchannel)
          .collect(Collectors.toList());
    } else {
      return Collections.emptyList();
    }
  }

  private void deliverSubchannelState(Subchannel subchannel, ConnectivityStateInfo newState) {
    testHelperInstance.deliverSubchannelState(subchannel, newState);
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

  private class TestHelper extends AbstractTestHelper {

    @Override
    public Map<List<EquivalentAddressGroup>, Subchannel> getSubchannelMap() {
      return subchannels;
    }
  }
}
