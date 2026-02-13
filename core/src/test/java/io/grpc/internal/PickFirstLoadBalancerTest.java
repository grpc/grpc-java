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

package io.grpc.internal;

import static com.google.common.truth.Truth.assertThat;
import static io.grpc.ConnectivityState.CONNECTING;
import static io.grpc.ConnectivityState.IDLE;
import static io.grpc.ConnectivityState.READY;
import static io.grpc.ConnectivityState.TRANSIENT_FAILURE;
import static io.grpc.InternalEquivalentAddressGroup.ATTR_WEIGHT;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.inOrder;
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
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.Status.Code;
import io.grpc.SynchronizationContext;
import io.grpc.internal.PickFirstLoadBalancer.PickFirstLoadBalancerConfig;
import java.net.SocketAddress;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.Random;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;


/** Unit test for {@link PickFirstLoadBalancer}. */
@RunWith(JUnit4.class)
public class PickFirstLoadBalancerTest {
  private PickFirstLoadBalancer loadBalancer;
  private List<EquivalentAddressGroup> servers = Lists.newArrayList();
  private List<SocketAddress> socketAddresses = Lists.newArrayList();

  private static final Attributes.Key<String> FOO = Attributes.Key.create("foo");
  private final SynchronizationContext syncContext = new SynchronizationContext(
      new Thread.UncaughtExceptionHandler() {
        @Override
        public void uncaughtException(Thread t, Throwable e) {
          throw new AssertionError(e);
        }
      });
  private Attributes affinity = Attributes.newBuilder().set(FOO, "bar").build();
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
  private Subchannel mockSubchannel;
  @Mock // This LoadBalancer doesn't use any of the arg fields, as verified in tearDown().
  private PickSubchannelArgs mockArgs;

  private boolean originalWeightedShuffling;

  @Before
  public void setUp() {
    originalWeightedShuffling = PickFirstLeafLoadBalancer.weightedShuffling;

    for (int i = 0; i < 3; i++) {
      SocketAddress addr = new FakeSocketAddress("server" + i);
      servers.add(new EquivalentAddressGroup(addr));
      socketAddresses.add(addr);
    }

    when(mockSubchannel.getAllAddresses()).thenThrow(new UnsupportedOperationException());
    when(mockHelper.getSynchronizationContext()).thenReturn(syncContext);
    when(mockHelper.createSubchannel(any(CreateSubchannelArgs.class))).thenReturn(mockSubchannel);

    loadBalancer = new PickFirstLoadBalancer(mockHelper);
  }

  @After
  public void tearDown() throws Exception {
    PickFirstLeafLoadBalancer.weightedShuffling = originalWeightedShuffling;
    verifyNoMoreInteractions(mockArgs);
  }

  @Test
  public void pickAfterResolved() throws Exception {
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(servers).setAttributes(affinity).build());

    verify(mockHelper).createSubchannel(createArgsCaptor.capture());
    CreateSubchannelArgs args = createArgsCaptor.getValue();
    assertThat(args.getAddresses()).isEqualTo(servers);
    verify(mockHelper).updateBalancingState(eq(CONNECTING), pickerCaptor.capture());
    verify(mockSubchannel).requestConnection();

    // Calling pickSubchannel() twice gave the same result
    assertEquals(pickerCaptor.getValue().pickSubchannel(mockArgs),
        pickerCaptor.getValue().pickSubchannel(mockArgs));

    verifyNoMoreInteractions(mockHelper);
  }

  @Test
  public void pickAfterResolved_shuffle_oppositeWeightedShuffling() throws Exception {
    PickFirstLeafLoadBalancer.weightedShuffling = !PickFirstLeafLoadBalancer.weightedShuffling;
    pickAfterResolved_shuffle();
  }

  @Test
  public void pickAfterResolved_shuffle() throws Exception {
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(servers).setAttributes(affinity)
            .setLoadBalancingPolicyConfig(new PickFirstLoadBalancerConfig(true, 123L)).build());

    verify(mockHelper).createSubchannel(createArgsCaptor.capture());
    CreateSubchannelArgs args = createArgsCaptor.getValue();
    // We should still see the same set of addresses.
    assertThat(args.getAddresses()).containsExactlyElementsIn(servers);
    // Because we use a fixed seed, the addresses should always be shuffled in this order.
    assertThat(args.getAddresses().get(0)).isEqualTo(servers.get(1));
    assertThat(args.getAddresses().get(1)).isEqualTo(servers.get(0));
    assertThat(args.getAddresses().get(2)).isEqualTo(servers.get(2));
    verify(mockHelper).updateBalancingState(eq(CONNECTING), pickerCaptor.capture());
    verify(mockSubchannel).requestConnection();

    // Calling pickSubchannel() twice gave the same result
    assertEquals(pickerCaptor.getValue().pickSubchannel(mockArgs),
        pickerCaptor.getValue().pickSubchannel(mockArgs));

    verifyNoMoreInteractions(mockHelper);
  }

  @Test
  public void pickAfterResolved_noShuffle() throws Exception {
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(servers).setAttributes(affinity)
            .setLoadBalancingPolicyConfig(new PickFirstLoadBalancerConfig(false)).build());

    verify(mockHelper).createSubchannel(createArgsCaptor.capture());
    CreateSubchannelArgs args = createArgsCaptor.getValue();
    assertThat(args.getAddresses()).isEqualTo(servers);
    verify(mockHelper).updateBalancingState(eq(CONNECTING), pickerCaptor.capture());
    verify(mockSubchannel).requestConnection();

    // Calling pickSubchannel() twice gave the same result
    assertEquals(pickerCaptor.getValue().pickSubchannel(mockArgs),
        pickerCaptor.getValue().pickSubchannel(mockArgs));

    verifyNoMoreInteractions(mockHelper);
  }

  @Test
  public void pickAfterResolved_shuffleImplicitUniform_oppositeWeightedShuffling() {
    PickFirstLeafLoadBalancer.weightedShuffling = !PickFirstLeafLoadBalancer.weightedShuffling;
    pickAfterResolved_shuffleImplicitUniform();
  }

  @Test
  public void pickAfterResolved_shuffleImplicitUniform() {
    EquivalentAddressGroup eag1 = new EquivalentAddressGroup(new FakeSocketAddress("server1"));
    EquivalentAddressGroup eag2 = new EquivalentAddressGroup(new FakeSocketAddress("server2"));
    EquivalentAddressGroup eag3 = new EquivalentAddressGroup(new FakeSocketAddress("server3"));

    int[] counts = countAddressSelections(99, Arrays.asList(eag1, eag2, eag3));
    assertThat(counts[0]).isWithin(7).of(33);
    assertThat(counts[1]).isWithin(7).of(33);
    assertThat(counts[2]).isWithin(7).of(33);
  }

  @Test
  public void pickAfterResolved_shuffleExplicitUniform_oppositeWeightedShuffling() {
    PickFirstLeafLoadBalancer.weightedShuffling = !PickFirstLeafLoadBalancer.weightedShuffling;
    pickAfterResolved_shuffleExplicitUniform();
  }

  @Test
  public void pickAfterResolved_shuffleExplicitUniform() {
    EquivalentAddressGroup eag1 = new EquivalentAddressGroup(
        new FakeSocketAddress("server1"), Attributes.newBuilder().set(ATTR_WEIGHT, 111L).build());
    EquivalentAddressGroup eag2 = new EquivalentAddressGroup(
        new FakeSocketAddress("server2"), Attributes.newBuilder().set(ATTR_WEIGHT, 111L).build());
    EquivalentAddressGroup eag3 = new EquivalentAddressGroup(
        new FakeSocketAddress("server3"), Attributes.newBuilder().set(ATTR_WEIGHT, 111L).build());

    int[] counts = countAddressSelections(99, Arrays.asList(eag1, eag2, eag3));
    assertThat(counts[0]).isWithin(7).of(33);
    assertThat(counts[1]).isWithin(7).of(33);
    assertThat(counts[2]).isWithin(7).of(33);
  }

  @Test
  public void pickAfterResolved_shuffleWeighted_noWeightedShuffling() {
    PickFirstLeafLoadBalancer.weightedShuffling = false;
    EquivalentAddressGroup eag1 = new EquivalentAddressGroup(
        new FakeSocketAddress("server1"), Attributes.newBuilder().set(ATTR_WEIGHT, 12L).build());
    EquivalentAddressGroup eag2 = new EquivalentAddressGroup(
        new FakeSocketAddress("server2"), Attributes.newBuilder().set(ATTR_WEIGHT, 3L).build());
    EquivalentAddressGroup eag3 = new EquivalentAddressGroup(
        new FakeSocketAddress("server3"), Attributes.newBuilder().set(ATTR_WEIGHT, 1L).build());

    int[] counts = countAddressSelections(100, Arrays.asList(eag1, eag2, eag3));
    assertThat(counts[0]).isWithin(7).of(33);
    assertThat(counts[1]).isWithin(7).of(33);
    assertThat(counts[2]).isWithin(7).of(33);
  }

  @Test
  public void pickAfterResolved_shuffleWeighted_weightedShuffling() {
    PickFirstLeafLoadBalancer.weightedShuffling = true;
    EquivalentAddressGroup eag1 = new EquivalentAddressGroup(
        new FakeSocketAddress("server1"), Attributes.newBuilder().set(ATTR_WEIGHT, 12L).build());
    EquivalentAddressGroup eag2 = new EquivalentAddressGroup(
        new FakeSocketAddress("server2"), Attributes.newBuilder().set(ATTR_WEIGHT, 3L).build());
    EquivalentAddressGroup eag3 = new EquivalentAddressGroup(
        new FakeSocketAddress("server3"), Attributes.newBuilder().set(ATTR_WEIGHT, 1L).build());

    int[] counts = countAddressSelections(100, Arrays.asList(eag1, eag2, eag3));
    assertThat(counts[0]).isWithin(7).of(75); // 100*12/16
    assertThat(counts[1]).isWithin(7).of(19); // 100*3/16
    assertThat(counts[2]).isWithin(7).of(6); // 100*1/16
  }

  /** Returns int[index_of_eag] array with number of times each eag was selected. */
  private int[] countAddressSelections(int trials, List<EquivalentAddressGroup> eags) {
    int[] counts = new int[eags.size()];
    Random random = new Random(1);
    for (int i = 0; i < trials; i++) {
      RecordingHelper helper = new RecordingHelper();
      PickFirstLoadBalancer lb = new PickFirstLoadBalancer(helper);
      assertThat(lb.acceptResolvedAddresses(ResolvedAddresses.newBuilder()
            .setAddresses(eags)
            .setAttributes(affinity)
            .setLoadBalancingPolicyConfig(
                new PickFirstLoadBalancerConfig(true, random.nextLong()))
            .build()))
          .isSameInstanceAs(Status.OK);
      helper.subchannels.remove().listener.onSubchannelState(
          ConnectivityStateInfo.forNonError(READY));

      assertThat(helper.state).isEqualTo(READY);
      Subchannel subchannel = helper.picker.pickSubchannel(mockArgs).getSubchannel();
      counts[eags.indexOf(subchannel.getAllAddresses().get(0))]++;

      lb.shutdown();
    }
    return counts;
  }

  @Test
  public void requestConnectionPicker() throws Exception {
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(servers).setAttributes(affinity).build());

    InOrder inOrder = inOrder(mockHelper, mockSubchannel);
    inOrder.verify(mockSubchannel).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener = stateListenerCaptor.getValue();
    inOrder.verify(mockHelper).updateBalancingState(eq(CONNECTING), any(SubchannelPicker.class));
    inOrder.verify(mockSubchannel).requestConnection();

    stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(IDLE));
    inOrder.verify(mockHelper).updateBalancingState(eq(IDLE), pickerCaptor.capture());

    SubchannelPicker picker = pickerCaptor.getValue();

    // Calling pickSubchannel() twice gave the same result
    assertEquals(picker.pickSubchannel(mockArgs), picker.pickSubchannel(mockArgs));

    // But the picker calls requestConnection() only once
    inOrder.verify(mockSubchannel).requestConnection();

    verify(mockSubchannel, times(2)).requestConnection();
  }

  @Test
  public void refreshNameResolutionAfterSubchannelConnectionBroken() {
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(servers).setAttributes(affinity).build());
    verify(mockHelper).createSubchannel(any(CreateSubchannelArgs.class));

    InOrder inOrder = inOrder(mockHelper, mockSubchannel);
    inOrder.verify(mockSubchannel).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener = stateListenerCaptor.getValue();
    inOrder.verify(mockHelper).updateBalancingState(eq(CONNECTING), pickerCaptor.capture());
    assertThat(pickerCaptor.getValue().pickSubchannel(mockArgs).hasResult()).isFalse();
    inOrder.verify(mockSubchannel).requestConnection();

    stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(CONNECTING));
    inOrder.verify(mockHelper).updateBalancingState(eq(CONNECTING), pickerCaptor.capture());
    assertNull(pickerCaptor.getValue().pickSubchannel(mockArgs).getSubchannel());
    Status error = Status.UNAUTHENTICATED.withDescription("permission denied");
    stateListener.onSubchannelState(ConnectivityStateInfo.forTransientFailure(error));
    inOrder.verify(mockHelper).refreshNameResolution();
    inOrder.verify(mockHelper).updateBalancingState(eq(TRANSIENT_FAILURE), pickerCaptor.capture());
    assertEquals(error, pickerCaptor.getValue().pickSubchannel(mockArgs).getStatus());
    stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
    inOrder.verify(mockHelper).updateBalancingState(eq(READY), pickerCaptor.capture());
    assertSame(mockSubchannel, pickerCaptor.getValue().pickSubchannel(mockArgs).getSubchannel());
    // Simulate receiving go-away so the subchannel transit to IDLE.
    stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(IDLE));
    inOrder.verify(mockHelper).refreshNameResolution();
    inOrder.verify(mockHelper).updateBalancingState(eq(IDLE), any(SubchannelPicker.class));

    verifyNoMoreInteractions(mockHelper, mockSubchannel);
  }

  @Test
  public void pickAfterResolvedAndUnchanged() throws Exception {
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(servers).setAttributes(affinity).build());
    verify(mockSubchannel).start(any(SubchannelStateListener.class));
    verify(mockSubchannel).requestConnection();
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(servers).setAttributes(affinity).build());
    verify(mockSubchannel).updateAddresses(eq(servers));
    verifyNoMoreInteractions(mockSubchannel);

    verify(mockHelper).createSubchannel(createArgsCaptor.capture());
    assertThat(createArgsCaptor.getValue()).isNotNull();
    verify(mockHelper)
        .updateBalancingState(isA(ConnectivityState.class), isA(SubchannelPicker.class));
    // Updating the subchannel addresses is unnecessary, but doesn't hurt anything
    verify(mockSubchannel).updateAddresses(ArgumentMatchers.<EquivalentAddressGroup>anyList());

    verifyNoMoreInteractions(mockHelper);
  }

  @Test
  public void pickAfterResolvedAndChanged() throws Exception {
    SocketAddress socketAddr = new FakeSocketAddress("newserver");
    List<EquivalentAddressGroup> newServers =
        Lists.newArrayList(new EquivalentAddressGroup(socketAddr));

    InOrder inOrder = inOrder(mockHelper, mockSubchannel);

    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(servers).setAttributes(affinity).build());
    inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
    verify(mockSubchannel).start(any(SubchannelStateListener.class));
    CreateSubchannelArgs args = createArgsCaptor.getValue();
    assertThat(args.getAddresses()).isEqualTo(servers);
    inOrder.verify(mockHelper).updateBalancingState(eq(CONNECTING), pickerCaptor.capture());
    verify(mockSubchannel).requestConnection();
    assertThat(pickerCaptor.getValue().pickSubchannel(mockArgs).hasResult()).isFalse();

    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(newServers).setAttributes(affinity).build());
    inOrder.verify(mockSubchannel).updateAddresses(eq(newServers));

    verifyNoMoreInteractions(mockSubchannel);
    verifyNoMoreInteractions(mockHelper);
  }

  @Test
  public void pickAfterStateChangeAfterResolution() throws Exception {
    InOrder inOrder = inOrder(mockHelper);

    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(servers).setAttributes(affinity).build());
    inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
    CreateSubchannelArgs args = createArgsCaptor.getValue();
    assertThat(args.getAddresses()).isEqualTo(servers);
    verify(mockSubchannel).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener = stateListenerCaptor.getValue();
    verify(mockHelper).updateBalancingState(eq(CONNECTING), pickerCaptor.capture());
    assertThat(pickerCaptor.getValue().pickSubchannel(mockArgs).hasResult()).isFalse();
    reset(mockHelper);
    when(mockHelper.getSynchronizationContext()).thenReturn(syncContext);

    stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(IDLE));
    inOrder.verify(mockHelper).refreshNameResolution();
    inOrder.verify(mockHelper).updateBalancingState(eq(IDLE), pickerCaptor.capture());
    assertEquals(Status.OK, pickerCaptor.getValue().pickSubchannel(mockArgs).getStatus());

    Status error = Status.UNAVAILABLE.withDescription("boom!");
    stateListener.onSubchannelState(ConnectivityStateInfo.forTransientFailure(error));
    inOrder.verify(mockHelper).refreshNameResolution();
    inOrder.verify(mockHelper).updateBalancingState(eq(TRANSIENT_FAILURE), pickerCaptor.capture());
    assertEquals(error, pickerCaptor.getValue().pickSubchannel(mockArgs).getStatus());

    stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
    inOrder.verify(mockHelper).updateBalancingState(eq(READY), pickerCaptor.capture());
    assertEquals(mockSubchannel, pickerCaptor.getValue().pickSubchannel(mockArgs).getSubchannel());

    verify(mockHelper, atLeast(0)).getSynchronizationContext();  // Don't care
    verifyNoMoreInteractions(mockHelper);
  }

  @Test
  public void pickAfterResolutionAfterTransientValue() throws Exception {
    InOrder inOrder = inOrder(mockHelper);

    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(servers).setAttributes(affinity).build());
    inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
    CreateSubchannelArgs args = createArgsCaptor.getValue();
    assertThat(args.getAddresses()).isEqualTo(servers);
    verify(mockSubchannel).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener = stateListenerCaptor.getValue();
    verify(mockHelper).updateBalancingState(eq(CONNECTING), pickerCaptor.capture());
    verify(mockSubchannel).requestConnection();
    reset(mockHelper);
    when(mockHelper.getSynchronizationContext()).thenReturn(syncContext);

    // An error has happened.
    Status error = Status.UNAVAILABLE.withDescription("boom!");
    stateListener.onSubchannelState(ConnectivityStateInfo.forTransientFailure(error));
    inOrder.verify(mockHelper).refreshNameResolution();
    inOrder.verify(mockHelper).updateBalancingState(eq(TRANSIENT_FAILURE), pickerCaptor.capture());
    assertEquals(error, pickerCaptor.getValue().pickSubchannel(mockArgs).getStatus());

    // But a subsequent IDLE update should be ignored and the LB state not updated. Additionally,
    // a request for a new connection should be made keep the subchannel trying to connect.
    stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(IDLE));
    inOrder.verify(mockHelper).refreshNameResolution();
    verifyNoMoreInteractions(mockHelper);
    assertEquals(error, pickerCaptor.getValue().pickSubchannel(mockArgs).getStatus());
    verify(mockSubchannel, times(2)).requestConnection();

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
    assertEquals(null, pickResult.getSubchannel());
    assertEquals(error, pickResult.getStatus());
    verify(mockSubchannel, never()).requestConnection();
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
    verify(mockSubchannel, never()).requestConnection();
    verifyNoMoreInteractions(mockHelper);
  }

  @Test
  public void nameResolutionSuccessAfterError() throws Exception {
    InOrder inOrder = inOrder(mockHelper);

    loadBalancer.handleNameResolutionError(Status.NOT_FOUND.withDescription("nameResolutionError"));
    inOrder.verify(mockHelper)
        .updateBalancingState(any(ConnectivityState.class), any(SubchannelPicker.class));
    verify(mockSubchannel, never()).requestConnection();

    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(servers).setAttributes(affinity).build());
    inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
    CreateSubchannelArgs args = createArgsCaptor.getValue();
    assertThat(args.getAddresses()).isEqualTo(servers);
    assertThat(args.getAttributes()).isEqualTo(Attributes.EMPTY);
    inOrder.verify(mockHelper).updateBalancingState(eq(CONNECTING), pickerCaptor.capture());
    verify(mockSubchannel).requestConnection();

    assertThat(pickerCaptor.getValue().pickSubchannel(mockArgs).hasResult()).isFalse();

    assertEquals(pickerCaptor.getValue().pickSubchannel(mockArgs),
        pickerCaptor.getValue().pickSubchannel(mockArgs));

    verifyNoMoreInteractions(mockHelper);
  }

  @Test
  public void nameResolutionErrorWithStateChanges() throws Exception {
    InOrder inOrder = inOrder(mockHelper);
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(servers).setAttributes(affinity).build());
    inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
    verify(mockSubchannel).start(stateListenerCaptor.capture());
    CreateSubchannelArgs args = createArgsCaptor.getValue();
    assertThat(args.getAddresses()).isEqualTo(servers);

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
    assertEquals(null, pickResult.getSubchannel());
    assertEquals(error, pickResult.getStatus());

    Status error2 = Status.NOT_FOUND.withDescription("nameResolutionError2");
    loadBalancer.handleNameResolutionError(error2);
    inOrder.verify(mockHelper).updateBalancingState(eq(TRANSIENT_FAILURE), pickerCaptor.capture());

    pickResult = pickerCaptor.getValue().pickSubchannel(mockArgs);
    assertEquals(null, pickResult.getSubchannel());
    assertEquals(error2, pickResult.getStatus());

    verifyNoMoreInteractions(mockHelper);
  }

  @Test
  public void requestConnection() {
    loadBalancer.requestConnection();

    verify(mockSubchannel, never()).requestConnection();
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(servers).setAttributes(affinity).build());
    verify(mockSubchannel).requestConnection();

    verify(mockHelper).createSubchannel(createArgsCaptor.capture());
    verify(mockSubchannel).start(stateListenerCaptor.capture());
    CreateSubchannelArgs args = createArgsCaptor.getValue();
    assertThat(args.getAddresses()).isEqualTo(servers);
    SubchannelStateListener stateListener = stateListenerCaptor.getValue();

    stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(IDLE));
    verify(mockHelper).updateBalancingState(eq(IDLE), any(SubchannelPicker.class));

    verify(mockSubchannel).requestConnection();
    loadBalancer.requestConnection();
    verify(mockSubchannel, times(2)).requestConnection();
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
      this.listener = listener;
    }

    @Override
    public void updateAddresses(List<EquivalentAddressGroup> addrs) {
      this.eags = Collections.unmodifiableList(addrs);
    }

    @Override
    public void shutdown() {
      listener.onSubchannelState(ConnectivityStateInfo.forNonError(ConnectivityState.SHUTDOWN));
    }

    @Override
    public void requestConnection() {
    }

    @Override
    public String toString() {
      return "FakeSubchannel@" + hashCode() + "(" + eags + ")";
    }
  }

  private class BaseHelper extends Helper {
    @Override
    public ManagedChannel createOobChannel(EquivalentAddressGroup eag, String authority) {
      return null;
    }

    @Override
    public String getAuthority() {
      return null;
    }

    @Override
    public void updateBalancingState(ConnectivityState newState, SubchannelPicker newPicker) {
      // ignore
    }

    @Override
    public SynchronizationContext getSynchronizationContext() {
      return syncContext;
    }

    @Override
    public void refreshNameResolution() {
      // noop
    }
  }

  class RecordingHelper extends BaseHelper {
    ConnectivityState state;
    SubchannelPicker picker;
    final Queue<FakeSubchannel> subchannels = new ArrayDeque<>();

    @Override
    public void updateBalancingState(ConnectivityState newState, SubchannelPicker newPicker) {
      this.state = newState;
      this.picker = newPicker;
    }

    @Override
    public Subchannel createSubchannel(CreateSubchannelArgs args) {
      FakeSubchannel subchannel = new FakeSubchannel(args.getAddresses(), args.getAttributes());
      subchannels.add(subchannel);
      return subchannel;
    }
  }

}
