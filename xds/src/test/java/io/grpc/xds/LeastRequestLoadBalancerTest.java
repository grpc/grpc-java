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
import static io.grpc.xds.LeastRequestLoadBalancer.IN_FLIGHTS;
import static io.grpc.xds.LeastRequestLoadBalancer.STATE_INFO;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.doAnswer;
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
import io.grpc.LoadBalancer.SubchannelStateListener;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.xds.LeastRequestLoadBalancer.EmptyPicker;
import io.grpc.xds.LeastRequestLoadBalancer.LeastRequestConfig;
import io.grpc.xds.LeastRequestLoadBalancer.ReadyPicker;
import io.grpc.xds.LeastRequestLoadBalancer.Ref;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
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
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.mockito.stubbing.Answer;

/** Unit test for {@link LeastRequestLoadBalancer}. */
@RunWith(JUnit4.class)
public class LeastRequestLoadBalancerTest {
  @Rule public final MockitoRule mocks = MockitoJUnit.rule();

  private static final Attributes.Key<String> MAJOR_KEY = Attributes.Key.create("major-key");

  private LeastRequestLoadBalancer loadBalancer;
  private final List<EquivalentAddressGroup> servers = Lists.newArrayList();
  private final Map<List<EquivalentAddressGroup>, Subchannel> subchannels = Maps.newLinkedHashMap();
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
  @Mock
  private Helper mockHelper;
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
      Subchannel sc = mock(Subchannel.class);
      subchannels.put(Arrays.asList(eag), sc);
    }

    when(mockHelper.createSubchannel(any(CreateSubchannelArgs.class)))
        .then(new Answer<Subchannel>() {
          @Override
          public Subchannel answer(InvocationOnMock invocation) throws Throwable {
            CreateSubchannelArgs args = (CreateSubchannelArgs) invocation.getArguments()[0];
            final Subchannel subchannel = subchannels.get(args.getAddresses());
            when(subchannel.getAllAddresses()).thenReturn(args.getAddresses());
            when(subchannel.getAttributes()).thenReturn(args.getAttributes());
            doAnswer(
                new Answer<Void>() {
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
    loadBalancer = new LeastRequestLoadBalancer(mockHelper, mockRandom);
  }

  @After
  public void tearDown() throws Exception {
    verifyNoMoreInteractions(mockRandom);
    verifyNoMoreInteractions(mockArgs);
  }

  @Test
  public void pickAfterResolved() throws Exception {
    final Subchannel readySubchannel = subchannels.values().iterator().next();
    boolean addressesAccepted = loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(servers).setAttributes(affinity).build());
    assertThat(addressesAccepted).isTrue();
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

    InOrder inOrder = inOrder(mockHelper);

    boolean addressesAccepted = loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(currentServers).setAttributes(affinity)
            .build());
    assertThat(addressesAccepted).isTrue();

    inOrder.verify(mockHelper).updateBalancingState(eq(CONNECTING), pickerCaptor.capture());

    deliverSubchannelState(removedSubchannel, ConnectivityStateInfo.forNonError(READY));
    deliverSubchannelState(oldSubchannel, ConnectivityStateInfo.forNonError(READY));

    inOrder.verify(mockHelper, times(2)).updateBalancingState(eq(READY), pickerCaptor.capture());
    SubchannelPicker picker = pickerCaptor.getValue();
    assertThat(getList(picker)).containsExactly(removedSubchannel, oldSubchannel);

    verify(removedSubchannel, times(1)).requestConnection();
    verify(oldSubchannel, times(1)).requestConnection();

    assertThat(loadBalancer.getSubchannels()).containsExactly(removedSubchannel,
        oldSubchannel);

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

    assertThat(loadBalancer.getSubchannels()).containsExactly(oldSubchannel,
        newSubchannel);

    verify(mockHelper, times(3)).createSubchannel(any(CreateSubchannelArgs.class));
    inOrder.verify(mockHelper, times(2)).updateBalancingState(eq(READY), pickerCaptor.capture());

    picker = pickerCaptor.getValue();
    assertThat(getList(picker)).containsExactly(oldSubchannel, newSubchannel);

    verifyNoMoreInteractions(mockHelper);
  }

  @Test
  public void pickAfterStateChange() throws Exception {
    InOrder inOrder = inOrder(mockHelper);
    boolean addressesAccepted = loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(servers).setAttributes(Attributes.EMPTY)
            .build());
    assertThat(addressesAccepted).isTrue();
    Subchannel subchannel = loadBalancer.getSubchannels().iterator().next();
    Ref<ConnectivityStateInfo> subchannelStateInfo = subchannel.getAttributes().get(
        STATE_INFO);

    inOrder.verify(mockHelper).updateBalancingState(eq(CONNECTING), isA(EmptyPicker.class));
    assertThat(subchannelStateInfo.value).isEqualTo(ConnectivityStateInfo.forNonError(IDLE));

    deliverSubchannelState(subchannel,
        ConnectivityStateInfo.forNonError(READY));
    inOrder.verify(mockHelper).updateBalancingState(eq(READY), pickerCaptor.capture());
    assertThat(pickerCaptor.getValue()).isInstanceOf(ReadyPicker.class);
    assertThat(subchannelStateInfo.value).isEqualTo(
        ConnectivityStateInfo.forNonError(READY));

    Status error = Status.UNKNOWN.withDescription("¯\\_(ツ)_//¯");
    deliverSubchannelState(subchannel,
        ConnectivityStateInfo.forTransientFailure(error));
    assertThat(subchannelStateInfo.value.getState()).isEqualTo(TRANSIENT_FAILURE);
    assertThat(subchannelStateInfo.value.getStatus()).isEqualTo(error);
    inOrder.verify(mockHelper).refreshNameResolution();
    inOrder.verify(mockHelper).updateBalancingState(eq(CONNECTING), pickerCaptor.capture());
    assertThat(pickerCaptor.getValue()).isInstanceOf(EmptyPicker.class);

    deliverSubchannelState(subchannel,
        ConnectivityStateInfo.forNonError(IDLE));
    inOrder.verify(mockHelper).refreshNameResolution();
    assertThat(subchannelStateInfo.value.getState()).isEqualTo(TRANSIENT_FAILURE);
    assertThat(subchannelStateInfo.value.getStatus()).isEqualTo(error);

    verify(subchannel, times(2)).requestConnection();
    verify(mockHelper, times(3)).createSubchannel(any(CreateSubchannelArgs.class));
    verifyNoMoreInteractions(mockHelper);
  }

  @Test
  public void pickAfterConfigChange() {
    final LeastRequestConfig oldConfig = new LeastRequestConfig(4);
    final LeastRequestConfig newConfig = new LeastRequestConfig(6);
    final Subchannel readySubchannel = subchannels.values().iterator().next();
    boolean addressesAccepted = loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(servers).setAttributes(affinity)
            .setLoadBalancingPolicyConfig(oldConfig).build());
    assertThat(addressesAccepted).isTrue();
    deliverSubchannelState(readySubchannel, ConnectivityStateInfo.forNonError(READY));
    verify(mockHelper, times(3)).createSubchannel(any(CreateSubchannelArgs.class));
    verify(mockHelper, times(2))
        .updateBalancingState(any(ConnectivityState.class), pickerCaptor.capture());

    // At this point it should use a ReadyPicker with oldConfig
    pickerCaptor.getValue().pickSubchannel(mockArgs);
    verify(mockRandom, times(oldConfig.choiceCount)).nextInt(1);

    addressesAccepted = loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(servers).setAttributes(affinity)
            .setLoadBalancingPolicyConfig(newConfig).build());
    assertThat(addressesAccepted).isTrue();
    verify(mockHelper, times(3))
        .updateBalancingState(any(ConnectivityState.class), pickerCaptor.capture());

    // At this point it should use a ReadyPicker with newConfig
    pickerCaptor.getValue().pickSubchannel(mockArgs);
    verify(mockRandom, times(oldConfig.choiceCount + newConfig.choiceCount)).nextInt(1);
    verifyNoMoreInteractions(mockHelper);
  }

  @Test
  public void ignoreShutdownSubchannelStateChange() {
    InOrder inOrder = inOrder(mockHelper);
    boolean addressesAccepted = loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(servers).setAttributes(Attributes.EMPTY)
            .build());
    assertThat(addressesAccepted).isTrue();
    inOrder.verify(mockHelper).updateBalancingState(eq(CONNECTING), isA(EmptyPicker.class));

    loadBalancer.shutdown();
    for (Subchannel sc : loadBalancer.getSubchannels()) {
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
    boolean addressesAccepted = loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(servers).setAttributes(Attributes.EMPTY)
            .build());
    assertThat(addressesAccepted).isTrue();

    inOrder.verify(mockHelper).updateBalancingState(eq(CONNECTING), isA(EmptyPicker.class));

    // Simulate state transitions for each subchannel individually.
    for (Subchannel sc : loadBalancer.getSubchannels()) {
      Status error = Status.UNKNOWN.withDescription("connection broken");
      deliverSubchannelState(
          sc,
          ConnectivityStateInfo.forTransientFailure(error));
      inOrder.verify(mockHelper).refreshNameResolution();
      deliverSubchannelState(
          sc,
          ConnectivityStateInfo.forNonError(CONNECTING));
      Ref<ConnectivityStateInfo> scStateInfo = sc.getAttributes().get(
          STATE_INFO);
      assertThat(scStateInfo.value.getState()).isEqualTo(TRANSIENT_FAILURE);
      assertThat(scStateInfo.value.getStatus()).isEqualTo(error);
    }
    inOrder.verify(mockHelper).updateBalancingState(eq(TRANSIENT_FAILURE), isA(EmptyPicker.class));
    inOrder.verifyNoMoreInteractions();

    Subchannel subchannel = loadBalancer.getSubchannels().iterator().next();
    deliverSubchannelState(subchannel, ConnectivityStateInfo.forNonError(READY));
    Ref<ConnectivityStateInfo> subchannelStateInfo = subchannel.getAttributes().get(
        STATE_INFO);
    assertThat(subchannelStateInfo.value).isEqualTo(ConnectivityStateInfo.forNonError(READY));
    inOrder.verify(mockHelper).updateBalancingState(eq(READY), isA(ReadyPicker.class));

    verify(mockHelper, times(3)).createSubchannel(any(CreateSubchannelArgs.class));
    verifyNoMoreInteractions(mockHelper);
  }

  @Test
  public void refreshNameResolutionWhenSubchannelConnectionBroken() {
    InOrder inOrder = inOrder(mockHelper);
    boolean addressesAccepted = loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(servers).setAttributes(Attributes.EMPTY)
            .build());
    assertThat(addressesAccepted).isTrue();

    verify(mockHelper, times(3)).createSubchannel(any(CreateSubchannelArgs.class));
    inOrder.verify(mockHelper).updateBalancingState(eq(CONNECTING), isA(EmptyPicker.class));

    // Simulate state transitions for each subchannel individually.
    for (Subchannel sc : loadBalancer.getSubchannels()) {
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
      inOrder.verify(mockHelper).updateBalancingState(eq(CONNECTING), isA(EmptyPicker.class));
    }

    verifyNoMoreInteractions(mockHelper);
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

    assertEquals(3, loadBalancer.getSubchannels().size());

    List<Subchannel> subchannels = Lists.newArrayList(loadBalancer.getSubchannels());

    // Make sure all inFlight counters have started at 0
    assertEquals(0,
        subchannels.get(0).getAttributes().get(IN_FLIGHTS).get());
    assertEquals(0,
        subchannels.get(1).getAttributes().get(IN_FLIGHTS).get());
    assertEquals(0,
        subchannels.get(2).getAttributes().get(IN_FLIGHTS).get());

    for (Subchannel sc : subchannels) {
      deliverSubchannelState(sc, ConnectivityStateInfo.forNonError(READY));
    }

    // Capture the active ReadyPicker once all subchannels are READY
    verify(mockHelper, times(4))
        .updateBalancingState(any(ConnectivityState.class), pickerCaptor.capture());
    assertThat(pickerCaptor.getValue()).isInstanceOf(ReadyPicker.class);

    ReadyPicker picker = (ReadyPicker) pickerCaptor.getValue();

    assertThat(picker.getList()).containsExactlyElementsIn(subchannels);

    // Make random return 0, then 2 for the sample indexes.
    when(mockRandom.nextInt(subchannels.size())).thenReturn(0, 2);
    PickResult pickResult1 = picker.pickSubchannel(mockArgs);
    verify(mockRandom, times(choiceCount)).nextInt(subchannels.size());
    assertEquals(subchannels.get(0), pickResult1.getSubchannel());
    // This simulates sending the actual RPC on the picked channel
    ClientStreamTracer streamTracer1 =
        pickResult1.getStreamTracerFactory()
            .newClientStreamTracer(StreamInfo.newBuilder().build(), new Metadata());
    streamTracer1.streamCreated(Attributes.EMPTY, new Metadata());
    assertEquals(1,
        pickResult1.getSubchannel().getAttributes().get(IN_FLIGHTS).get());

    // For the second pick it should pick the one with lower inFlight.
    when(mockRandom.nextInt(subchannels.size())).thenReturn(0, 2);
    PickResult pickResult2 = picker.pickSubchannel(mockArgs);
    // Since this is the second pick we expect the total random samples to be choiceCount * 2
    verify(mockRandom, times(choiceCount * 2)).nextInt(subchannels.size());
    assertEquals(subchannels.get(2), pickResult2.getSubchannel());

    // For the third pick we unavoidably pick subchannel with index 1.
    when(mockRandom.nextInt(subchannels.size())).thenReturn(1, 1);
    PickResult pickResult3 = picker.pickSubchannel(mockArgs);
    verify(mockRandom, times(choiceCount * 3)).nextInt(subchannels.size());
    assertEquals(subchannels.get(1), pickResult3.getSubchannel());

    // Finally ensure a finished RPC decreases inFlight
    streamTracer1.streamClosed(Status.OK);
    assertEquals(0,
        pickResult1.getSubchannel().getAttributes().get(IN_FLIGHTS).get());
  }

  @Test
  public void pickerEmptyList() throws Exception {
    SubchannelPicker picker = new EmptyPicker(Status.UNKNOWN);

    assertEquals(null, picker.pickSubchannel(mockArgs).getSubchannel());
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
    int choiceCount = 8;
    final Subchannel readySubchannel = subchannels.values().iterator().next();
    boolean addressesAccepted = loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder()
            .setLoadBalancingPolicyConfig(new LeastRequestConfig(choiceCount))
            .setAddresses(servers).setAttributes(affinity).build());
    assertThat(addressesAccepted).isTrue();
    deliverSubchannelState(readySubchannel, ConnectivityStateInfo.forNonError(READY));
    loadBalancer.handleNameResolutionError(Status.NOT_FOUND.withDescription("nameResolutionError"));

    verify(mockHelper, times(3)).createSubchannel(any(CreateSubchannelArgs.class));
    verify(mockHelper, times(2))
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
    verifyNoMoreInteractions(mockHelper);
  }

  @Test
  public void subchannelStateIsolation() throws Exception {
    Iterator<Subchannel> subchannelIterator = subchannels.values().iterator();
    Subchannel sc1 = subchannelIterator.next();
    Subchannel sc2 = subchannelIterator.next();
    Subchannel sc3 = subchannelIterator.next();

    boolean addressesAccepted = loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(servers).setAttributes(Attributes.EMPTY)
            .build());
    assertThat(addressesAccepted).isTrue();
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
      new ReadyPicker(Collections.<Subchannel>emptyList(), 2, mockRandom);
      fail();
    } catch (IllegalArgumentException expected) {
    }
  }

  @Test
  public void internalPickerComparisons() {
    EmptyPicker emptyOk1 = new EmptyPicker(Status.OK);
    EmptyPicker emptyOk2 = new EmptyPicker(Status.OK.withDescription("different OK"));
    EmptyPicker emptyErr = new EmptyPicker(Status.UNKNOWN.withDescription("¯\\_(ツ)_//¯"));

    Iterator<Subchannel> subchannelIterator = subchannels.values().iterator();
    Subchannel sc1 = subchannelIterator.next();
    Subchannel sc2 = subchannelIterator.next();
    ReadyPicker ready1 = new ReadyPicker(Arrays.asList(sc1, sc2), 2, mockRandom);
    ReadyPicker ready2 = new ReadyPicker(Arrays.asList(sc1), 2, mockRandom);
    ReadyPicker ready3 = new ReadyPicker(Arrays.asList(sc2, sc1), 2, mockRandom);
    ReadyPicker ready4 = new ReadyPicker(Arrays.asList(sc1, sc2), 2, mockRandom);
    ReadyPicker ready5 = new ReadyPicker(Arrays.asList(sc2, sc1), 2, mockRandom);
    ReadyPicker ready6 = new ReadyPicker(Arrays.asList(sc2, sc1), 8, mockRandom);

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
            .build())).isFalse();
  }

  private static List<Subchannel> getList(SubchannelPicker picker) {
    return picker instanceof ReadyPicker ? ((ReadyPicker) picker).getList() :
        Collections.<Subchannel>emptyList();
  }

  private void deliverSubchannelState(Subchannel subchannel, ConnectivityStateInfo newState) {
    subchannelStateListeners.get(subchannel).onSubchannelState(newState);
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
}
