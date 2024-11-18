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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.google.common.collect.Lists;
import io.grpc.Attributes;
import io.grpc.ConnectivityState;
import io.grpc.ConnectivityStateInfo;
import io.grpc.EquivalentAddressGroup;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancer.CreateSubchannelArgs;
import io.grpc.LoadBalancer.FixedResultPicker;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancer.PickResult;
import io.grpc.LoadBalancer.PickSubchannelArgs;
import io.grpc.LoadBalancer.ResolvedAddresses;
import io.grpc.LoadBalancer.Subchannel;
import io.grpc.LoadBalancer.SubchannelPicker;
import io.grpc.Status;
import io.grpc.internal.PickFirstLoadBalancerProvider;
import io.grpc.internal.PickFirstLoadBalancerProviderAccessor;
import io.grpc.internal.TestUtils;
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
import java.util.concurrent.atomic.AtomicInteger;
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
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/** Unit test for {@link RoundRobinLoadBalancer}. */
@RunWith(JUnit4.class)
public class RoundRobinLoadBalancerTest {
  private static final Attributes.Key<String> MAJOR_KEY = Attributes.Key.create("major-key");
  private static final SubchannelPicker EMPTY_PICKER =
      new FixedResultPicker(PickResult.withNoResult());

  @Rule public final MockitoRule mocks = MockitoJUnit.rule();

  private RoundRobinLoadBalancer loadBalancer;
  private final List<EquivalentAddressGroup> servers = Lists.newArrayList();
  private final Map<List<EquivalentAddressGroup>, Subchannel> subchannels =
      new ConcurrentHashMap<>();
  private final Attributes affinity =
      Attributes.newBuilder().set(MAJOR_KEY, "I got the keys").build();

  @Captor
  private ArgumentCaptor<SubchannelPicker> pickerCaptor;
  @Captor
  private ArgumentCaptor<ConnectivityState> stateCaptor;
  @Captor
  private ArgumentCaptor<CreateSubchannelArgs> createArgsCaptor;
  private TestHelper testHelperInst = new TestHelper();
  private Helper mockHelper = mock(Helper.class, delegatesTo(testHelperInst));
  private boolean defaultNewPickFirst = PickFirstLoadBalancerProvider.isEnabledNewPickFirst();

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

  private Status acceptAddresses(List<EquivalentAddressGroup> eagList, Attributes attrs) {
    return loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(eagList).setAttributes(attrs).build());
  }

  @After
  public void tearDown() throws Exception {
    PickFirstLoadBalancerProviderAccessor.setEnableNewPickFirst(defaultNewPickFirst);
    verifyNoMoreInteractions(mockArgs);
  }

  @Test
  public void pickAfterResolved() throws Exception {
    Status addressesAcceptanceStatus = acceptAddresses(servers, affinity);
    assertThat(addressesAcceptanceStatus.isOk()).isTrue();
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

    AbstractTestHelper.verifyNoMoreMeaningfulInteractions(mockHelper);
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

    Status addressesAcceptanceStatus = acceptAddresses(currentServers, affinity);
    assertThat(addressesAcceptanceStatus.isOk()).isTrue();

    inOrder.verify(mockHelper).updateBalancingState(eq(CONNECTING), pickerCaptor.capture());

    deliverSubchannelState(removedSubchannel, ConnectivityStateInfo.forNonError(READY));
    deliverSubchannelState(oldSubchannel, ConnectivityStateInfo.forNonError(READY));

    inOrder.verify(mockHelper, times(2)).updateBalancingState(eq(READY), pickerCaptor.capture());
    SubchannelPicker picker = pickerCaptor.getValue();
    assertThat(getList(picker)).containsExactly(removedSubchannel, oldSubchannel);

    verify(removedSubchannel, times(1)).requestConnection();
    verify(oldSubchannel, times(1)).requestConnection();

    // This time with Attributes
    List<EquivalentAddressGroup> latestServers = Lists.newArrayList(oldEag2, newEag);

    addressesAcceptanceStatus = loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(latestServers).setAttributes(affinity).build());
    assertThat(addressesAcceptanceStatus.isOk()).isTrue();

    verify(newSubchannel, times(1)).requestConnection();
    verify(oldSubchannel, times(1)).updateAddresses(Arrays.asList(oldEag2));
    verify(removedSubchannel, times(1)).shutdown();

    deliverSubchannelState(newSubchannel, ConnectivityStateInfo.forNonError(READY));

    verify(mockHelper, times(6)).createSubchannel(any(CreateSubchannelArgs.class));
    inOrder.verify(mockHelper, times(2)).updateBalancingState(eq(READY), pickerCaptor.capture());

    picker = pickerCaptor.getValue();
    assertThat(getList(picker)).containsExactly(oldSubchannel, newSubchannel);

    AbstractTestHelper.verifyNoMoreMeaningfulInteractions(mockHelper);
  }

  @Test
  public void pickAfterStateChange() throws Exception {
    InOrder inOrder = inOrder(mockHelper);
    Status addressesAcceptanceStatus =
        acceptAddresses(Arrays.asList(servers.get(0)), Attributes.EMPTY);
    assertThat(addressesAcceptanceStatus.isOk()).isTrue();
    inOrder.verify(mockHelper).createSubchannel(any(CreateSubchannelArgs.class));

    // TODO figure out if this method testing the right things

    assertThat(subchannels).hasSize(1);
    Subchannel subchannel = subchannels.values().iterator().next();

    inOrder.verify(mockHelper).updateBalancingState(eq(CONNECTING), eq(EMPTY_PICKER));

    deliverSubchannelState(subchannel, ConnectivityStateInfo.forNonError(READY));
    inOrder.verify(mockHelper).updateBalancingState(eq(READY), pickerCaptor.capture());
    assertThat(pickerCaptor.getValue()).isInstanceOf(ReadyPicker.class);

    Status error = Status.UNKNOWN.withDescription("¯\\_(ツ)_//¯");
    deliverSubchannelState(subchannel,
        ConnectivityStateInfo.forTransientFailure(error));
    AbstractTestHelper.refreshInvokedAndUpdateBS(
        inOrder, TRANSIENT_FAILURE, mockHelper, pickerCaptor);
    assertThat(pickerCaptor.getValue().pickSubchannel(mockArgs).getStatus()).isEqualTo(error);

    deliverSubchannelState(subchannel, ConnectivityStateInfo.forNonError(IDLE));
    inOrder.verify(mockHelper).refreshNameResolution();
    inOrder.verify(mockHelper, never())
        .updateBalancingState(eq(TRANSIENT_FAILURE), any(SubchannelPicker.class));

    verify(subchannel, atLeastOnce()).requestConnection();
    AbstractTestHelper.verifyNoMoreMeaningfulInteractions(mockHelper);
  }

  @Test
  public void ignoreShutdownSubchannelStateChange() {
    InOrder inOrder = inOrder(mockHelper);
    Status addressesAcceptanceStatus = acceptAddresses(servers, Attributes.EMPTY);
    assertThat(addressesAcceptanceStatus.isOk()).isTrue();
    inOrder.verify(mockHelper).updateBalancingState(eq(CONNECTING), eq(EMPTY_PICKER));

    List<Subchannel> savedSubchannels = new ArrayList<>(subchannels.values());
    loadBalancer.shutdown();
    for (Subchannel sc : savedSubchannels) {
      verify(sc).shutdown();
      // When the subchannel is being shut down, a SHUTDOWN connectivity state is delivered
      // back to the subchannel state listener.
      deliverSubchannelState(sc, ConnectivityStateInfo.forNonError(SHUTDOWN));
    }

    inOrder.verifyNoMoreInteractions();
  }

  @Test
  public void stayTransientFailureUntilReady() {
    InOrder inOrder = inOrder(mockHelper);
    Status addressesAcceptanceStatus = acceptAddresses(servers, Attributes.EMPTY);
    assertThat(addressesAcceptanceStatus.isOk()).isTrue();

    inOrder.verify(mockHelper, times(3)).createSubchannel(any(CreateSubchannelArgs.class));
    inOrder.verify(mockHelper).updateBalancingState(eq(CONNECTING), eq(EMPTY_PICKER));

    // Simulate state transitions for each subchannel individually.
    for (Subchannel sc : subchannels.values()) {
      Status error = Status.UNKNOWN.withDescription("connection broken");
      deliverSubchannelState(
          sc,
          ConnectivityStateInfo.forTransientFailure(error));
      deliverSubchannelState(
          sc,
          ConnectivityStateInfo.forNonError(CONNECTING));
    }
    inOrder.verify(mockHelper).updateBalancingState(eq(TRANSIENT_FAILURE), isA(ReadyPicker.class));
    inOrder.verify(mockHelper, atLeast(0)).refreshNameResolution();
    inOrder.verifyNoMoreInteractions();

    Subchannel subchannel = subchannels.values().iterator().next();
    deliverSubchannelState(subchannel, ConnectivityStateInfo.forNonError(READY));
    inOrder.verify(mockHelper).updateBalancingState(eq(READY), isA(ReadyPicker.class));

    inOrder.verify(mockHelper, atLeast(0)).refreshNameResolution();
    inOrder.verifyNoMoreInteractions();
  }

  @Test
  public void refreshNameResolutionWhenSubchannelConnectionBroken() {
    InOrder inOrder = inOrder(mockHelper);
    Status addressesAcceptanceStatus = acceptAddresses(servers, Attributes.EMPTY);
    assertThat(addressesAcceptanceStatus.isOk()).isTrue();

    verify(mockHelper, times(3)).createSubchannel(any(CreateSubchannelArgs.class));
    inOrder.verify(mockHelper).updateBalancingState(eq(CONNECTING), eq(EMPTY_PICKER));

    // Simulate state transitions for each subchannel individually.
    for (Subchannel sc : subchannels.values()) {
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
      verify(sc, times(2)).requestConnection();
      inOrder.verify(mockHelper).updateBalancingState(eq(CONNECTING), eq(EMPTY_PICKER));
    }

    AbstractTestHelper.verifyNoMoreMeaningfulInteractions(mockHelper);
  }

  @Test
  public void removingAddressShutsdownSubchannel() {
    acceptAddresses(servers, affinity);
    final Subchannel subchannel2 = subchannels.get(Collections.singletonList(servers.get(2)));

    InOrder inOrder = Mockito.inOrder(mockHelper, subchannel2);
    // send LB only the first 2 addresses
    List<EquivalentAddressGroup> svs2 = Arrays.asList(servers.get(0), servers.get(1));
    acceptAddresses(svs2, affinity);
    inOrder.verify(mockHelper).updateBalancingState(eq(CONNECTING), any());
    inOrder.verify(subchannel2).shutdown();
  }

  @Test
  public void pickerRoundRobin() throws Exception {
    Subchannel subchannel = mock(Subchannel.class);
    Subchannel subchannel1 = mock(Subchannel.class);
    Subchannel subchannel2 = mock(Subchannel.class);

    ArrayList<SubchannelPicker> pickers = Lists.newArrayList(
        TestUtils.pickerOf(subchannel), TestUtils.pickerOf(subchannel1),
        TestUtils.pickerOf(subchannel2));

    AtomicInteger seq = new AtomicInteger(0);
    ReadyPicker picker = new ReadyPicker(Collections.unmodifiableList(pickers), seq);

    assertEquals(subchannel, picker.pickSubchannel(mockArgs).getSubchannel());
    assertEquals(subchannel1, picker.pickSubchannel(mockArgs).getSubchannel());
    assertEquals(subchannel2, picker.pickSubchannel(mockArgs).getSubchannel());
    assertEquals(subchannel, picker.pickSubchannel(mockArgs).getSubchannel());

    seq.set(Integer.MAX_VALUE);
    assertEquals(subchannel1, picker.pickSubchannel(mockArgs).getSubchannel());
    assertEquals(subchannel, picker.pickSubchannel(mockArgs).getSubchannel());
    assertEquals(subchannel1, picker.pickSubchannel(mockArgs).getSubchannel());
    assertEquals(subchannel2, picker.pickSubchannel(mockArgs).getSubchannel());
    assertEquals(subchannel, picker.pickSubchannel(mockArgs).getSubchannel());
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
    Status addressesAcceptanceStatus = acceptAddresses(servers, affinity);
    final Subchannel readySubchannel = subchannels.values().iterator().next();
    assertThat(addressesAcceptanceStatus.isOk()).isTrue();
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
    AbstractTestHelper.verifyNoMoreMeaningfulInteractions(mockHelper);
  }

  @Test
  public void subchannelStateIsolation() throws Exception {
    Status addressesAcceptanceStatus = acceptAddresses(servers, Attributes.EMPTY);
    assertThat(addressesAcceptanceStatus.isOk()).isTrue();

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
    assertThat(pickers.next()).isEqualTo(EMPTY_PICKER);
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
  public void subchannelHealthObserved() throws Exception {
    // Only the new PF policy observes the new separate listener for health
    PickFirstLoadBalancerProviderAccessor.setEnableNewPickFirst(true);
    // PickFirst does most of this work. If the test fails, check IS_PETIOLE_POLICY
    Map<Subchannel, LoadBalancer.SubchannelStateListener> healthListeners = new HashMap<>();
    loadBalancer = new RoundRobinLoadBalancer(new ForwardingLoadBalancerHelper() {
      @Override
      public Subchannel createSubchannel(CreateSubchannelArgs args) {
        Subchannel subchannel = super.createSubchannel(args.toBuilder()
            .setAttributes(args.getAttributes().toBuilder()
              .set(LoadBalancer.HAS_HEALTH_PRODUCER_LISTENER_KEY, true)
              .build())
            .build());
        healthListeners.put(
            subchannel, args.getOption(LoadBalancer.HEALTH_CONSUMER_LISTENER_ARG_KEY));
        return subchannel;
      }

      @Override
      protected Helper delegate() {
        return mockHelper;
      }
    });

    InOrder inOrder = inOrder(mockHelper);
    Status addressesAcceptanceStatus = acceptAddresses(servers, Attributes.EMPTY);
    assertThat(addressesAcceptanceStatus.isOk()).isTrue();
    Subchannel subchannel0 = subchannels.get(Arrays.asList(servers.get(0)));
    Subchannel subchannel1 = subchannels.get(Arrays.asList(servers.get(1)));
    Subchannel subchannel2 = subchannels.get(Arrays.asList(servers.get(2)));

    // Subchannels go READY, but the LB waits for health
    for (Subchannel subchannel : subchannels.values()) {
      deliverSubchannelState(subchannel, ConnectivityStateInfo.forNonError(READY));
    }
    inOrder.verify(mockHelper, times(0))
        .updateBalancingState(eq(READY), any(SubchannelPicker.class));

    // Health results lets subchannels go READY
    healthListeners.get(subchannel0).onSubchannelState(
        ConnectivityStateInfo.forTransientFailure(Status.UNAVAILABLE.withDescription("oh no")));
    healthListeners.get(subchannel1).onSubchannelState(ConnectivityStateInfo.forNonError(READY));
    healthListeners.get(subchannel2).onSubchannelState(ConnectivityStateInfo.forNonError(READY));
    inOrder.verify(mockHelper, times(2)).updateBalancingState(eq(READY), pickerCaptor.capture());
    SubchannelPicker picker = pickerCaptor.getValue();
    List<Subchannel> picks = Arrays.asList(
        picker.pickSubchannel(mockArgs).getSubchannel(),
        picker.pickSubchannel(mockArgs).getSubchannel(),
        picker.pickSubchannel(mockArgs).getSubchannel(),
        picker.pickSubchannel(mockArgs).getSubchannel());
    assertThat(picks).containsExactly(subchannel1, subchannel2, subchannel1, subchannel2);
  }

  @Test
  public void readyPicker_emptyList() {
    // ready picker list must be non-empty
    try {
      new ReadyPicker(Collections.emptyList(), new AtomicInteger(0));
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test
  public void internalPickerComparisons() {
    SubchannelPicker empty1 = new FixedResultPicker(PickResult.withNoResult());
    SubchannelPicker empty2 = new FixedResultPicker(PickResult.withNoResult());

    AtomicInteger seq = new AtomicInteger(0);
    acceptAddresses(servers, Attributes.EMPTY); // create subchannels
    Iterator<Subchannel> subchannelIterator = subchannels.values().iterator();
    SubchannelPicker sc1 = TestUtils.pickerOf(subchannelIterator.next());
    SubchannelPicker sc2 = TestUtils.pickerOf(subchannelIterator.next());
    SubchannelPicker ready1 = new ReadyPicker(Arrays.asList(sc1, sc2), seq);
    SubchannelPicker ready2 = new ReadyPicker(Arrays.asList(sc1), seq);
    SubchannelPicker ready3 = new ReadyPicker(Arrays.asList(sc2, sc1), seq);
    SubchannelPicker ready4 = new ReadyPicker(Arrays.asList(sc1, sc2), seq);
    SubchannelPicker ready5 = new ReadyPicker(Arrays.asList(sc2, sc1), new AtomicInteger(0));

    assertThat(empty1).isEqualTo(empty2);
    assertThat(ready1).isNotEqualTo(ready2);
    assertThat(ready1).isEqualTo(ready3);
    assertThat(ready3).isEqualTo(ready4);
    assertThat(ready4).isNotEqualTo(ready5);
    assertThat(empty1).isNotEqualTo(ready1);
    assertThat(ready1).isNotEqualTo(empty1);
  }

  @Test
  public void emptyAddresses() {
    assertThat(loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder()
            .setAddresses(Collections.emptyList())
            .setAttributes(affinity)
            .build()).isOk()).isFalse();
  }

  private List<Subchannel> getList(SubchannelPicker picker) {

    if (picker instanceof ReadyPicker) {
      List<Subchannel> subchannelList = new ArrayList<>();
      for (SubchannelPicker childPicker : ((ReadyPicker) picker).getSubchannelPickers()) {
        subchannelList.add(childPicker.pickSubchannel(mockArgs).getSubchannel());
      }
      return subchannelList;
    } else {
      return new ArrayList<>();
    }
  }

  private void deliverSubchannelState(Subchannel subchannel, ConnectivityStateInfo newState) {
    testHelperInst.deliverSubchannelState(subchannel, newState);
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
