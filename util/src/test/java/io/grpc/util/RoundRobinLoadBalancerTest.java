/*
 * Copyright 2016 The gRPC Authors
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

import static com.google.common.truth.Truth.assertThat;
import static io.grpc.ConnectivityState.CONNECTING;
import static io.grpc.ConnectivityState.IDLE;
import static io.grpc.ConnectivityState.READY;
import static io.grpc.ConnectivityState.SHUTDOWN;
import static io.grpc.ConnectivityState.TRANSIENT_FAILURE;
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

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import io.grpc.Attributes;
import io.grpc.ConnectivityState;
import io.grpc.ConnectivityStateInfo;
import io.grpc.EquivalentAddressGroup;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancer.CreateSubchannelArgs;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancer.PickSubchannelArgs;
import io.grpc.LoadBalancer.ResolvedAddresses;
import io.grpc.LoadBalancer.Subchannel;
import io.grpc.LoadBalancer.SubchannelPicker;
import io.grpc.LoadBalancer.SubchannelStateListener;
import io.grpc.Status;
import io.grpc.internal.TestUtils;
import io.grpc.util.MultiChildLoadBalancer.ChildLbState;
import io.grpc.util.RoundRobinLoadBalancer.EmptyPicker;
import io.grpc.util.RoundRobinLoadBalancer.ReadyPicker;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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

/** Unit test for {@link RoundRobinLoadBalancer}. */
@RunWith(JUnit4.class)
public class RoundRobinLoadBalancerTest {
  private static final Attributes.Key<String> MAJOR_KEY = Attributes.Key.create("major-key");

  @Rule public final MockitoRule mocks = MockitoJUnit.rule();

  private RoundRobinLoadBalancer loadBalancer;
  private final List<EquivalentAddressGroup> servers = Lists.newArrayList();
  private final Map<List<EquivalentAddressGroup>, Subchannel> subchannels =
      new ConcurrentHashMap<>();
  private final Map<Subchannel, Subchannel> mockToRealSubChannelMap = new HashMap<>();
  private final Map<Subchannel, SubchannelStateListener> subchannelStateListeners =
      Maps.newLinkedHashMap();
  private final Attributes affinity =
      Attributes.newBuilder().set(MAJOR_KEY, "I got the keys").build();

  @Captor
  private ArgumentCaptor<SubchannelPicker> pickerCaptor;
  @Captor
  private ArgumentCaptor<ConnectivityState> stateCaptor;
  @Captor
  private ArgumentCaptor<CreateSubchannelArgs> createArgsCaptor;
  private Helper mockHelper = mock(Helper.class, delegatesTo(new TestHelper()));

  @Mock // This LoadBalancer doesn't use any of the arg fields, as verified in tearDown().
  private PickSubchannelArgs mockArgs;

  @Before
  public void setUp() {
    for (int i = 0; i < 3; i++) {
      SocketAddress addr = new FakeSocketAddress("server" + i);
      EquivalentAddressGroup eag = new EquivalentAddressGroup(addr);
      servers.add(eag);
    }

    loadBalancer = new RoundRobinLoadBalancer(mockHelper);
  }

  private boolean acceptAddresses(List<EquivalentAddressGroup> eagList, Attributes attrs) {
    return loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(eagList).setAttributes(attrs).build());
  }

  @After
  public void tearDown() throws Exception {
    verifyNoMoreInteractions(mockArgs);
  }

  @Test
  public void pickAfterResolved() throws Exception {
    boolean addressesAccepted = acceptAddresses(servers, affinity);
    assertThat(addressesAccepted).isTrue();
    final Subchannel readySubchannel = subchannels.values().iterator().next();
    deliverSubchannelState(readySubchannel, ConnectivityStateInfo.forNonError(READY));

    verify(mockHelper, times(3)).createSubchannel(createArgsCaptor.capture());
    List<List<EquivalentAddressGroup>> capturedAddrs = new ArrayList<>();
    for (CreateSubchannelArgs arg : createArgsCaptor.getAllValues()) {
      capturedAddrs.add(arg.getAddresses());
    }

    assertThat(capturedAddrs).containsAtLeastElementsIn(subchannels.keySet());
    for (Subchannel subchannel : subchannels.values()) {
      verify(subchannel).requestConnection();
      verify(subchannel, never()).shutdown();
    }

    verify(mockHelper, times(2))
        .updateBalancingState(stateCaptor.capture(), pickerCaptor.capture());

    assertEquals(CONNECTING, stateCaptor.getAllValues().get(0));
    assertEquals(READY, stateCaptor.getAllValues().get(1));
    assertThat(getList(pickerCaptor.getValue())).containsExactly(readySubchannel);

    verifyNoMoreInteractions(mockHelper);
  }

  @Test
  public void pickAfterResolvedUpdatedHosts() throws Exception {
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

    Subchannel removedSubchannel = mockHelper.createSubchannel(CreateSubchannelArgs.newBuilder()
        .setAddresses(removedEag).build());
    Subchannel oldSubchannel = mockHelper.createSubchannel(CreateSubchannelArgs.newBuilder()
        .setAddresses(oldEag1).build());
    Subchannel newSubchannel = mockHelper.createSubchannel(CreateSubchannelArgs.newBuilder()
        .setAddresses(newEag).build());

    subchannels.put(Collections.singletonList(removedEag), removedSubchannel);
    subchannels.put(Collections.singletonList(oldEag1), oldSubchannel);
    subchannels.put(Collections.singletonList(newEag), newSubchannel);

    List<EquivalentAddressGroup> currentServers = Lists.newArrayList(removedEag, oldEag1);

    InOrder inOrder = inOrder(mockHelper);

    boolean addressesAccepted = acceptAddresses(currentServers, affinity);
    assertThat(addressesAccepted).isTrue();

    inOrder.verify(mockHelper).updateBalancingState(eq(CONNECTING), pickerCaptor.capture());

    deliverSubchannelState(removedSubchannel, ConnectivityStateInfo.forNonError(READY));
    deliverSubchannelState(oldSubchannel, ConnectivityStateInfo.forNonError(READY));

    inOrder.verify(mockHelper, times(2)).updateBalancingState(eq(READY), pickerCaptor.capture());
    SubchannelPicker picker = pickerCaptor.getValue();
    assertThat(getList(picker)).containsExactly(removedSubchannel, oldSubchannel);

    verify(removedSubchannel, times(1)).requestConnection();
    verify(oldSubchannel, times(1)).requestConnection();

    assertThat(loadBalancer.getChildLbStates().size()).isEqualTo(2);
    assertThat(loadBalancer.getChildLbStateEag(removedEag).getCurrentPicker().pickSubchannel(null)
        .getSubchannel()).isEqualTo(removedSubchannel);
    assertThat(loadBalancer.getChildLbStateEag(oldEag1).getCurrentPicker().pickSubchannel(null)
        .getSubchannel()).isEqualTo(oldSubchannel);

    // This time with Attributes
    List<EquivalentAddressGroup> latestServers = Lists.newArrayList(oldEag2, newEag);

    addressesAccepted = loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(latestServers).setAttributes(affinity).build());
    assertThat(addressesAccepted).isTrue();

    verify(newSubchannel, times(1)).requestConnection();
    verify(oldSubchannel, times(1)).updateAddresses(Arrays.asList(oldEag2));
    verify(removedSubchannel, times(1)).shutdown();

    deliverSubchannelState(newSubchannel, ConnectivityStateInfo.forNonError(READY));

    assertThat(loadBalancer.getChildLbStates().size()).isEqualTo(2);
    assertThat(loadBalancer.getChildLbStateEag(newEag).getCurrentPicker()
        .pickSubchannel(null).getSubchannel()).isEqualTo(newSubchannel);
    assertThat(loadBalancer.getChildLbStateEag(oldEag2).getCurrentPicker()
        .pickSubchannel(null).getSubchannel()).isEqualTo(oldSubchannel);

    verify(mockHelper, times(6)).createSubchannel(any(CreateSubchannelArgs.class));
    inOrder.verify(mockHelper, times(2)).updateBalancingState(eq(READY), pickerCaptor.capture());

    picker = pickerCaptor.getValue();
    assertThat(getList(picker)).containsExactly(oldSubchannel, newSubchannel);

    verifyNoMoreInteractions(mockHelper);
  }

  @Test
  public void pickAfterStateChange() throws Exception {
    InOrder inOrder = inOrder(mockHelper);
    boolean addressesAccepted = acceptAddresses(servers, Attributes.EMPTY);
    assertThat(addressesAccepted).isTrue();

    // TODO figure out if this method testing the right things

    ChildLbState childLbState = loadBalancer.getChildLbStates().iterator().next();
    Subchannel subchannel = childLbState.getCurrentPicker().pickSubchannel(null).getSubchannel();

    inOrder.verify(mockHelper).updateBalancingState(eq(CONNECTING), isA(EmptyPicker.class));
    assertThat(childLbState.getCurrentState()).isEqualTo(CONNECTING);

    deliverSubchannelState(subchannel, ConnectivityStateInfo.forNonError(READY));
    inOrder.verify(mockHelper).updateBalancingState(eq(READY), pickerCaptor.capture());
    assertThat(pickerCaptor.getValue()).isInstanceOf(ReadyPicker.class);
    assertThat(childLbState.getCurrentState()).isEqualTo(READY);

    Status error = Status.UNKNOWN.withDescription("¯\\_(ツ)_//¯");
    deliverSubchannelState(subchannel,
        ConnectivityStateInfo.forTransientFailure(error));
    assertThat(childLbState.getCurrentState()).isEqualTo(TRANSIENT_FAILURE);
    inOrder.verify(mockHelper).refreshNameResolution();
    inOrder.verify(mockHelper).updateBalancingState(eq(CONNECTING), pickerCaptor.capture());
    assertThat(pickerCaptor.getValue()).isInstanceOf(EmptyPicker.class);

    deliverSubchannelState(subchannel,
        ConnectivityStateInfo.forNonError(IDLE));
    inOrder.verify(mockHelper).refreshNameResolution();
    assertThat(childLbState.getCurrentState()).isEqualTo(TRANSIENT_FAILURE);

    verify(subchannel, times(2)).requestConnection();
    verify(mockHelper, times(3)).createSubchannel(any(CreateSubchannelArgs.class));
    verifyNoMoreInteractions(mockHelper);
  }

  @Test
  public void ignoreShutdownSubchannelStateChange() {
    InOrder inOrder = inOrder(mockHelper);
    boolean addressesAccepted = acceptAddresses(servers, Attributes.EMPTY);
    assertThat(addressesAccepted).isTrue();
    inOrder.verify(mockHelper).updateBalancingState(eq(CONNECTING), isA(EmptyPicker.class));

    loadBalancer.shutdown();
    for (ChildLbState child : loadBalancer.getChildLbStates()) {
      Subchannel sc = child.getCurrentPicker().pickSubchannel(null).getSubchannel();
      verify(child).shutdown();
      // When the subchannel is being shut down, a SHUTDOWN connectivity state is delivered
      // back to the subchannel state listener.
      deliverSubchannelState(sc, ConnectivityStateInfo.forNonError(SHUTDOWN));
    }

    inOrder.verifyNoMoreInteractions();
  }

  @Test
  public void stayTransientFailureUntilReady() {
    InOrder inOrder = inOrder(mockHelper);
    boolean addressesAccepted = acceptAddresses(servers, Attributes.EMPTY);
    assertThat(addressesAccepted).isTrue();

    inOrder.verify(mockHelper).updateBalancingState(eq(CONNECTING), isA(EmptyPicker.class));

    Map<ChildLbState, Subchannel> childToSubChannelMap = new HashMap<>();
    // Simulate state transitions for each subchannel individually.
    for ( ChildLbState child : loadBalancer.getChildLbStates()) {
      Subchannel sc = child.getSubchannels(mockArgs);
      childToSubChannelMap.put(child, sc);
      Status error = Status.UNKNOWN.withDescription("connection broken");
      deliverSubchannelState(
          sc,
          ConnectivityStateInfo.forTransientFailure(error));
      assertEquals(TRANSIENT_FAILURE, child.getCurrentState());
      inOrder.verify(mockHelper).refreshNameResolution();
      deliverSubchannelState(
          sc,
          ConnectivityStateInfo.forNonError(CONNECTING));
      assertEquals(TRANSIENT_FAILURE, child.getCurrentState());
    }
    inOrder.verify(mockHelper).updateBalancingState(eq(TRANSIENT_FAILURE), isA(ReadyPicker.class));
    inOrder.verifyNoMoreInteractions();

    ChildLbState child = loadBalancer.getChildLbStates().iterator().next();
    Subchannel subchannel = childToSubChannelMap.get(child);
    deliverSubchannelState(subchannel, ConnectivityStateInfo.forNonError(READY));
    assertThat(child.getCurrentState()).isEqualTo(READY);
    inOrder.verify(mockHelper).updateBalancingState(eq(READY), isA(ReadyPicker.class));

    verify(mockHelper, times(3)).createSubchannel(any(CreateSubchannelArgs.class));
    verifyNoMoreInteractions(mockHelper);
  }

  @Test
  public void refreshNameResolutionWhenSubchannelConnectionBroken() {
    InOrder inOrder = inOrder(mockHelper);
    boolean addressesAccepted = acceptAddresses(servers, Attributes.EMPTY);
    assertThat(addressesAccepted).isTrue();

    verify(mockHelper, times(3)).createSubchannel(any(CreateSubchannelArgs.class));
    inOrder.verify(mockHelper).updateBalancingState(eq(CONNECTING), isA(EmptyPicker.class));

    // Simulate state transitions for each subchannel individually.
    for (ChildLbState child : loadBalancer.getChildLbStates()) {
      Subchannel sc = child.getSubchannels(mockArgs);
      verify(sc).requestConnection();
      deliverSubchannelState(sc, ConnectivityStateInfo.forNonError(CONNECTING));
      Status error = Status.UNKNOWN.withDescription("connection broken");
      deliverSubchannelState(sc, ConnectivityStateInfo.forTransientFailure(error));
      inOrder.verify(mockHelper).refreshNameResolution();
      deliverSubchannelState(sc, ConnectivityStateInfo.forNonError(READY));
      inOrder.verify(mockHelper).updateBalancingState(eq(READY), isA(ReadyPicker.class));
      // Simulate receiving go-away so READY subchannels transit to IDLE.
      deliverSubchannelState(sc, ConnectivityStateInfo.forNonError(IDLE));
      inOrder.verify(mockHelper).refreshNameResolution();
      verify(sc, times(1)).requestConnection();
      inOrder.verify(mockHelper).updateBalancingState(eq(CONNECTING), isA(EmptyPicker.class));
    }

    verifyNoMoreInteractions(mockHelper);
  }

  @Test
  public void pickerRoundRobin() throws Exception {
    Subchannel subchannel = mock(Subchannel.class);
    Subchannel subchannel1 = mock(Subchannel.class);
    Subchannel subchannel2 = mock(Subchannel.class);

    ArrayList<SubchannelPicker> pickers = Lists.newArrayList(
        TestUtils.pickerOf(subchannel), TestUtils.pickerOf(subchannel1),
        TestUtils.pickerOf(subchannel2));

    ReadyPicker picker = new ReadyPicker(Collections.unmodifiableList(pickers),
        0 /* startIndex */);

    assertEquals(subchannel, picker.pickSubchannel(mockArgs).getSubchannel());
    assertEquals(subchannel1, picker.pickSubchannel(mockArgs).getSubchannel());
    assertEquals(subchannel2, picker.pickSubchannel(mockArgs).getSubchannel());
    assertEquals(subchannel, picker.pickSubchannel(mockArgs).getSubchannel());
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
    verify(mockHelper).updateBalancingState(eq(TRANSIENT_FAILURE), pickerCaptor.capture());
    LoadBalancer.PickResult pickResult = pickerCaptor.getValue().pickSubchannel(mockArgs);
    assertNull(pickResult.getSubchannel());
    assertEquals(error, pickResult.getStatus());
    verifyNoMoreInteractions(mockHelper);
  }

  @Test
  public void nameResolutionErrorWithActiveChannels() throws Exception {
    boolean addressesAccepted = acceptAddresses(servers, affinity);
    final Subchannel readySubchannel = subchannels.values().iterator().next();
    assertThat(addressesAccepted).isTrue();
    deliverSubchannelState(readySubchannel, ConnectivityStateInfo.forNonError(READY));
    loadBalancer.resolvingAddresses = true;
    loadBalancer.handleNameResolutionError(Status.NOT_FOUND.withDescription("nameResolutionError"));
    loadBalancer.resolvingAddresses = false;

    verify(mockHelper, times(3)).createSubchannel(any(CreateSubchannelArgs.class));
    verify(mockHelper, times(2))
        .updateBalancingState(stateCaptor.capture(), pickerCaptor.capture());

    Iterator<ConnectivityState> stateIterator = stateCaptor.getAllValues().iterator();
    assertEquals(CONNECTING, stateIterator.next());
    assertEquals(READY, stateIterator.next());

    LoadBalancer.PickResult pickResult = pickerCaptor.getValue().pickSubchannel(mockArgs);
    assertEquals(readySubchannel, pickResult.getSubchannel());
    assertEquals(Status.OK.getCode(), pickResult.getStatus().getCode());

    LoadBalancer.PickResult pickResult2 = pickerCaptor.getValue().pickSubchannel(mockArgs);
    assertEquals(readySubchannel, pickResult2.getSubchannel());
    verifyNoMoreInteractions(mockHelper);
  }

  @Test
  public void subchannelStateIsolation() throws Exception {
    boolean addressesAccepted = acceptAddresses(servers, Attributes.EMPTY);
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

    verify(mockHelper, times(6))
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
    verify(sc2, times(1)).requestConnection();
    // The failing subchannel is dropped from the picker, with no requested reconnect
    assertEquals(READY, stateIterator.next());
    assertThat(getList(pickers.next())).containsExactly(sc1);
    verify(sc3, times(1)).requestConnection();
    assertThat(stateIterator.hasNext()).isFalse();
    assertThat(pickers.hasNext()).isFalse();
  }

  @Test
  public void readyPicker_emptyList() {
    // ready picker list must be non-empty
    try {
      new ReadyPicker(Collections.emptyList(), 0);
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test
  public void internalPickerComparisons() {
    EmptyPicker emptyOk1 = new EmptyPicker(Status.OK);
    EmptyPicker emptyOk2 = new EmptyPicker(Status.OK.withDescription("different OK"));
    EmptyPicker emptyErr = new EmptyPicker(Status.UNKNOWN.withDescription("¯\\_(ツ)_//¯"));

    acceptAddresses(servers, Attributes.EMPTY); // create subchannels
    Iterator<Subchannel> subchannelIterator = subchannels.values().iterator();
    SubchannelPicker sc1 = TestUtils.pickerOf(subchannelIterator.next());
    SubchannelPicker sc2 = TestUtils.pickerOf(subchannelIterator.next());
    ReadyPicker ready1 = new ReadyPicker(Arrays.asList(sc1, sc2), 0);
    ReadyPicker ready2 = new ReadyPicker(Arrays.asList(sc1), 0);
    ReadyPicker ready3 = new ReadyPicker(Arrays.asList(sc2, sc1), 1);
    ReadyPicker ready4 = new ReadyPicker(Arrays.asList(sc1, sc2), 1);
    ReadyPicker ready5 = new ReadyPicker(Arrays.asList(sc2, sc1), 0);

    assertTrue(emptyOk1.isEquivalentTo(emptyOk2));
    assertFalse(emptyOk1.isEquivalentTo(emptyErr));
    assertFalse(ready1.isEquivalentTo(ready2));
    assertTrue(ready1.isEquivalentTo(ready3));
    assertTrue(ready3.isEquivalentTo(ready4));
    assertTrue(ready4.isEquivalentTo(ready5));
    assertFalse(emptyOk1.isEquivalentTo(ready1));
    assertFalse(ready1.isEquivalentTo(emptyOk1));
  }

  @Test
  public void emptyAddresses() {
    assertThat(loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder()
            .setAddresses(Collections.emptyList())
            .setAttributes(affinity)
            .build())).isFalse();
  }

  private List<Subchannel> getList(SubchannelPicker picker) {

    if (picker instanceof ReadyPicker) {
      List<Subchannel> subchannelList = new ArrayList<>();
      for (SubchannelPicker childPicker : ((ReadyPicker) picker).getList()) {
        subchannelList.add(childPicker.pickSubchannel(mockArgs).getSubchannel());
      }
      return subchannelList;
    } else {
      return new ArrayList<>();
    }
  }

  private void deliverSubchannelState(Subchannel subchannel, ConnectivityStateInfo newState) {
    Subchannel realSc = mockToRealSubChannelMap.get(subchannel);
    subchannelStateListeners.get(realSc).onSubchannelState(newState);
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

    @Override
    public Map<Subchannel, Subchannel> getMockToRealSubChannelMap() {
      return mockToRealSubChannelMap;
    }

    @Override
    public Map<Subchannel, SubchannelStateListener> getSubchannelStateListeners() {
      return subchannelStateListeners;
    }
  }
}
