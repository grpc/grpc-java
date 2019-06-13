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
import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.any;
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
import io.grpc.Status;
import io.grpc.SynchronizationContext;
import java.net.SocketAddress;
import java.util.List;
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
  private ArgumentCaptor<CreateSubchannelArgs> createArgsCaptor;
  @Captor
  private ArgumentCaptor<SubchannelStateListener> stateListenerCaptor;
  @Mock
  private Helper mockHelper;
  @Mock
  private Subchannel mockSubchannel;
  @Mock // This LoadBalancer doesn't use any of the arg fields, as verified in tearDown().
  private PickSubchannelArgs mockArgs;

  @Before
  public void setUp() {
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
  @SuppressWarnings("deprecation")
  public void tearDown() throws Exception {
    verifyNoMoreInteractions(mockArgs);
    verify(mockHelper, never()).createSubchannel(
        ArgumentMatchers.<EquivalentAddressGroup>anyList(), any(Attributes.class));
  }

  @Test
  public void pickAfterResolved() throws Exception {
    loadBalancer.handleResolvedAddresses(
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
  public void requestConnectionPicker() throws Exception {
    loadBalancer.handleResolvedAddresses(
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
  public void pickAfterResolvedAndUnchanged() throws Exception {
    loadBalancer.handleResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(servers).setAttributes(affinity).build());
    verify(mockSubchannel).start(any(SubchannelStateListener.class));
    verify(mockSubchannel).requestConnection();
    loadBalancer.handleResolvedAddresses(
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

    loadBalancer.handleResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(servers).setAttributes(affinity).build());
    inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
    verify(mockSubchannel).start(any(SubchannelStateListener.class));
    CreateSubchannelArgs args = createArgsCaptor.getValue();
    assertThat(args.getAddresses()).isEqualTo(servers);
    inOrder.verify(mockHelper).updateBalancingState(eq(CONNECTING), pickerCaptor.capture());
    verify(mockSubchannel).requestConnection();
    assertEquals(mockSubchannel, pickerCaptor.getValue().pickSubchannel(mockArgs).getSubchannel());

    loadBalancer.handleResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(newServers).setAttributes(affinity).build());
    inOrder.verify(mockSubchannel).updateAddresses(eq(newServers));

    verifyNoMoreInteractions(mockSubchannel);
    verifyNoMoreInteractions(mockHelper);
  }

  @Test
  public void pickAfterStateChangeAfterResolution() throws Exception {
    InOrder inOrder = inOrder(mockHelper);

    loadBalancer.handleResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(servers).setAttributes(affinity).build());
    inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
    CreateSubchannelArgs args = createArgsCaptor.getValue();
    assertThat(args.getAddresses()).isEqualTo(servers);
    verify(mockSubchannel).start(stateListenerCaptor.capture());
    SubchannelStateListener stateListener = stateListenerCaptor.getValue();
    verify(mockHelper).updateBalancingState(eq(CONNECTING), pickerCaptor.capture());
    Subchannel subchannel = pickerCaptor.getValue().pickSubchannel(mockArgs).getSubchannel();
    reset(mockHelper);
    when(mockHelper.getSynchronizationContext()).thenReturn(syncContext);

    Status error = Status.UNAVAILABLE.withDescription("boom!");
    stateListener.onSubchannelState(ConnectivityStateInfo.forTransientFailure(error));
    inOrder.verify(mockHelper).updateBalancingState(eq(TRANSIENT_FAILURE), pickerCaptor.capture());
    assertEquals(error, pickerCaptor.getValue().pickSubchannel(mockArgs).getStatus());

    stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(IDLE));
    inOrder.verify(mockHelper).updateBalancingState(eq(IDLE), pickerCaptor.capture());
    assertEquals(Status.OK, pickerCaptor.getValue().pickSubchannel(mockArgs).getStatus());

    stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
    inOrder.verify(mockHelper).updateBalancingState(eq(READY), pickerCaptor.capture());
    assertEquals(subchannel, pickerCaptor.getValue().pickSubchannel(mockArgs).getSubchannel());

    verify(mockHelper, atLeast(0)).getSynchronizationContext();  // Don't care
    verifyNoMoreInteractions(mockHelper);
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
  public void nameResolutionSuccessAfterError() throws Exception {
    InOrder inOrder = inOrder(mockHelper);

    loadBalancer.handleNameResolutionError(Status.NOT_FOUND.withDescription("nameResolutionError"));
    inOrder.verify(mockHelper)
        .updateBalancingState(any(ConnectivityState.class), any(SubchannelPicker.class));
    verify(mockSubchannel, never()).requestConnection();

    loadBalancer.handleResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(servers).setAttributes(affinity).build());
    inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
    CreateSubchannelArgs args = createArgsCaptor.getValue();
    assertThat(args.getAddresses()).isEqualTo(servers);
    assertThat(args.getAttributes()).isEqualTo(Attributes.EMPTY);
    inOrder.verify(mockHelper).updateBalancingState(eq(CONNECTING), pickerCaptor.capture());
    verify(mockSubchannel).requestConnection();

    assertEquals(mockSubchannel, pickerCaptor.getValue().pickSubchannel(mockArgs)
        .getSubchannel());

    assertEquals(pickerCaptor.getValue().pickSubchannel(mockArgs),
        pickerCaptor.getValue().pickSubchannel(mockArgs));

    verifyNoMoreInteractions(mockHelper);
  }

  @Test
  public void nameResolutionErrorWithStateChanges() throws Exception {
    InOrder inOrder = inOrder(mockHelper);
    loadBalancer.handleResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(servers).setAttributes(affinity).build());
    inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
    verify(mockSubchannel).start(stateListenerCaptor.capture());
    CreateSubchannelArgs args = createArgsCaptor.getValue();
    assertThat(args.getAddresses()).isEqualTo(servers);

    inOrder.verify(mockHelper).updateBalancingState(eq(CONNECTING), any(SubchannelPicker.class));

    SubchannelStateListener stateListener = stateListenerCaptor.getValue();

    stateListener.onSubchannelState(ConnectivityStateInfo.forTransientFailure(Status.UNAVAILABLE));
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
    loadBalancer.handleResolvedAddresses(
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
}
