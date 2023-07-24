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

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.truth.Truth.assertThat;
import static io.grpc.ConnectivityState.CONNECTING;
import static io.grpc.ConnectivityState.IDLE;
import static io.grpc.ConnectivityState.READY;
import static io.grpc.ConnectivityState.TRANSIENT_FAILURE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.mock;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;import io.grpc.*;
import io.grpc.LoadBalancer.CreateSubchannelArgs;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancer.PickResult;
import io.grpc.LoadBalancer.PickSubchannelArgs;
import io.grpc.LoadBalancer.ResolvedAddresses;
import io.grpc.LoadBalancer.Subchannel;
import io.grpc.LoadBalancer.SubchannelPicker;
import io.grpc.LoadBalancer.SubchannelStateListener;
import io.grpc.Status.Code;
import io.grpc.internal.PickFirstLeafLoadBalancer.PickFirstLeafLoadBalancerConfig;
import io.grpc.internal.PickFirstLeafLoadBalancer.Index;
import java.net.SocketAddress;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.*;
import org.mockito.invocation.InvocationOnMock;import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;import org.mockito.stubbing.Answer;


/** Unit test for {@link PickFirstLeafLoadBalancer}. */
@RunWith(JUnit4.class)
public class PickFirstLeafLoadBalancerTest {
    private PickFirstLeafLoadBalancer loadBalancer;
    private List<EquivalentAddressGroup> servers = Lists.newArrayList();
    private List<SocketAddress> socketAddresses = Lists.newArrayList();
    private static final Attributes.Key<String> FOO = Attributes.Key.create("foo");
    private static final String AUTHORITY = "fakeauthority";
    private static final String USER_AGENT = "mosaic";
    private final FakeClock fakeClock = new FakeClock();
    private final InternalChannelz channelz = new InternalChannelz();
    private InternalSubchannel InternalSubchannel;

    @Mock private BackoffPolicy mockBackoffPolicy1;
    @Mock private BackoffPolicy mockBackoffPolicy2;
    @Mock private BackoffPolicy mockBackoffPolicy3;
    @Mock private BackoffPolicy.Provider mockBackoffPolicyProvider;
    @Mock private ClientTransportFactory mockTransportFactory;

    private final LinkedList<String> callbackInvokes = new LinkedList<>();
    private final InternalSubchannel.Callback mockInternalSubchannelCallback =
            new InternalSubchannel.Callback() {
                @Override
                protected void onTerminated(InternalSubchannel is) {
                    assertSame(InternalSubchannel, is);
                    callbackInvokes.add("onTerminated");
                }

                @Override
                protected void onStateChange(InternalSubchannel is, ConnectivityStateInfo newState) {
                    assertSame(InternalSubchannel, is);
                    callbackInvokes.add("onStateChange:" + newState);
                }

                @Override
                protected void onInUse(InternalSubchannel is) {
                    assertSame(InternalSubchannel, is);
                    callbackInvokes.add("onInUse");
                }

                @Override
                protected void onNotInUse(InternalSubchannel is) {
                    assertSame(InternalSubchannel, is);
                    callbackInvokes.add("onNotInUse");
                }
            };

    private BlockingQueue<TestUtils.MockClientTransportInfo> transports;


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
    @Rule
    public final ExpectedException thrown = ExpectedException.none();

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
            socketAddresses.add(addr);
        }

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
        when(mockBackoffPolicyProvider.get())
            .thenReturn(mockBackoffPolicy1, mockBackoffPolicy2, mockBackoffPolicy3);
        when(mockBackoffPolicy1.nextBackoffNanos()).thenReturn(10L, 100L);
        when(mockBackoffPolicy2.nextBackoffNanos()).thenReturn(10L, 100L);
        when(mockBackoffPolicy3.nextBackoffNanos()).thenReturn(10L, 100L);
        transports = TestUtils.captureTransports(mockTransportFactory);
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
        loadBalancer.acceptResolvedAddresses(
                ResolvedAddresses.newBuilder().setAddresses(servers).setAttributes(affinity)
                        .setLoadBalancingPolicyConfig(new PickFirstLeafLoadBalancerConfig(true, 123L)).build());

        verify(mockHelper, times(4)).createSubchannel(createArgsCaptor.capture());
        List<CreateSubchannelArgs> argsList = createArgsCaptor.getAllValues();
        // We should still see the same set of addresses.
        // Because we use a fixed seed, the addresses should always be shuffled in this order.
        assertThat(argsList.get(0).getAddresses().get(0)).isEqualTo(servers.get(1));
        assertThat(argsList.get(1).getAddresses().get(0)).isEqualTo(servers.get(0));
        assertThat(argsList.get(2).getAddresses().get(0)).isEqualTo(servers.get(2));
        assertThat(argsList.get(3).getAddresses().get(0)).isEqualTo((servers.get(3)));
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
      // set up
      assertEquals(IDLE, loadBalancer.getCurrentState());
      SocketAddress socketAddr1 = new FakeSocketAddress("newserver1");
      SocketAddress socketAddr2 = new FakeSocketAddress("newserver2");
      SocketAddress socketAddr3 = new FakeSocketAddress("newserver3");
      List<EquivalentAddressGroup> newServers =
          Lists.newArrayList(new EquivalentAddressGroup(socketAddr1),
          new EquivalentAddressGroup(socketAddr2), new EquivalentAddressGroup(socketAddr3));

      // accepting resolved addresses
      loadBalancer.acceptResolvedAddresses(
          ResolvedAddresses.newBuilder().setAddresses(newServers).setAttributes(affinity).build());

      InOrder inOrder = inOrder(mockHelper, mockSubchannel1, mockSubchannel2, mockSubchannel3);

      // we initialize and start all subchannels
      inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
      inOrder.verify(mockSubchannel1).start(stateListenerCaptor.capture());
      SubchannelStateListener stateListener = stateListenerCaptor.getValue();
      inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
      inOrder.verify(mockSubchannel2).start(stateListenerCaptor.capture());
      inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
      inOrder.verify(mockSubchannel3).start(stateListenerCaptor.capture());

      // we start connection attempt to the first address in the list
      inOrder.verify(mockHelper).updateBalancingState(eq(CONNECTING), any(SubchannelPicker.class));
      assertEquals(CONNECTING, loadBalancer.getCurrentState());
      inOrder.verify(mockSubchannel1).requestConnection();
      assertEquals(CONNECTING, loadBalancer.getCurrentState());

      // if we send the first subchannel into idle ...
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
      // acept resolved addresses
      loadBalancer.acceptResolvedAddresses(
          ResolvedAddresses.newBuilder().setAddresses(servers).setAttributes(affinity).build());
      verify(mockHelper, times(4)).createSubchannel(createArgsCaptor.capture());

      InOrder inOrder = inOrder(mockHelper, mockSubchannel1);
      inOrder.verify(mockHelper).updateBalancingState(eq(CONNECTING), any(SubchannelPicker.class));
      inOrder.verify(mockSubchannel1).start(stateListenerCaptor.capture());
      SubchannelStateListener stateListener = stateListenerCaptor.getValue();
      inOrder.verify(mockHelper).updateBalancingState(eq(CONNECTING), pickerCaptor.capture());
      assertSame(mockSubchannel1, pickerCaptor.getValue().pickSubchannel(mockArgs).getSubchannel());
      inOrder.verify(mockSubchannel1).requestConnection();

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
      assertSame(mockSubchannel1, pickerCaptor.getValue().pickSubchannel(mockArgs).getSubchannel());

      // Simulate receiving go-away so the subchannel transit to IDLE.
      stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(IDLE));
      inOrder.verify(mockHelper).refreshNameResolution();
      inOrder.verify(mockHelper).updateBalancingState(eq(IDLE), any(SubchannelPicker.class));
      verifyNoMoreInteractions(mockHelper, mockSubchannel1);
    }

    @Test
    public void pickAfterResolvedAndUnchanged() throws Exception {
      loadBalancer.acceptResolvedAddresses(
          ResolvedAddresses.newBuilder().setAddresses(servers).setAttributes(affinity).build());
      verify(mockSubchannel1).start(any(SubchannelStateListener.class));
      verify(mockSubchannel1).requestConnection();
      verify(mockHelper).updateBalancingState(eq(CONNECTING), any(SubchannelPicker.class));

      loadBalancer.acceptResolvedAddresses(
          ResolvedAddresses.newBuilder().setAddresses(servers).setAttributes(affinity).build());
      // TODO: this practically achieves nothing, but still happens. Verify that it is okay.
      verify(mockSubchannel1, times(2)).requestConnection();
      verifyNoMoreInteractions(mockSubchannel1);

      verify(mockHelper, times(4)).createSubchannel(createArgsCaptor.capture());
      verify(mockHelper).updateBalancingState(eq(CONNECTING), any(SubchannelPicker.class));
      assertThat(createArgsCaptor.getValue()).isNotNull();
      verify(mockHelper)
          .updateBalancingState(isA(ConnectivityState.class), isA(SubchannelPicker.class));

      verifyNoMoreInteractions(mockHelper);
    }

    @Test
    public void pickAfterResolvedAndChanged() throws Exception {
      SocketAddress socketAddr = new FakeSocketAddress("newserver");
      List<EquivalentAddressGroup> newServers =
          Lists.newArrayList(new EquivalentAddressGroup(socketAddr));

      InOrder inOrder = inOrder(mockHelper, mockSubchannel1);

      // accept resolved addresses
      loadBalancer.acceptResolvedAddresses(
          ResolvedAddresses.newBuilder().setAddresses(servers).setAttributes(affinity).build());
      inOrder.verify(mockHelper, times(4)).createSubchannel(createArgsCaptor.capture());
      verify(mockSubchannel1).start(any(SubchannelStateListener.class));
      List<CreateSubchannelArgs> argsList = createArgsCaptor.getAllValues();
      assertThat(argsList.get(0).getAddresses().get(0)).isEqualTo(servers.get(0));
      assertThat(argsList.get(1).getAddresses().get(0)).isEqualTo(servers.get(1));
      assertThat(argsList.get(2).getAddresses().get(0)).isEqualTo(servers.get(2));
      assertThat(argsList.get(3).getAddresses().get(0)).isEqualTo(servers.get(3));
      assertThat(argsList.get(0).getAddresses().size()).isEqualTo(1);
      assertThat(argsList.get(1).getAddresses().size()).isEqualTo(1);
      assertThat(argsList.get(2).getAddresses().size()).isEqualTo(1);
      assertThat(argsList.get(3).getAddresses().size()).isEqualTo(1);

      // start connection attempt to first address
      inOrder.verify(mockHelper).updateBalancingState(eq(CONNECTING), pickerCaptor.capture());
      verify(mockSubchannel1).requestConnection();

      // TODO: verify behavior, this should not return a subchannel...
      assertEquals(mockSubchannel1, pickerCaptor.getValue().pickSubchannel(mockArgs).getSubchannel());

      // updating the subchannel addresses is unnecessary, but doesn't hurt anything
       loadBalancer.acceptResolvedAddresses(
           ResolvedAddresses.newBuilder().setAddresses(newServers).setAttributes(affinity).build());

       inOrder.verify(mockHelper).updateBalancingState(eq(CONNECTING), any(SubchannelPicker.class));

       verifyNoMoreInteractions(mockSubchannel1);
       verifyNoMoreInteractions(mockHelper);
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
        verify(mockHelper).updateBalancingState(eq(CONNECTING), pickerCaptor.capture());
        Subchannel subchannel = pickerCaptor.getValue().pickSubchannel(mockArgs).getSubchannel();
        reset(mockHelper);
        when(mockHelper.getSynchronizationContext()).thenReturn(syncContext);

        stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(IDLE));
        inOrder.verify(mockHelper).refreshNameResolution();
        inOrder.verify(mockHelper).updateBalancingState(eq(IDLE), pickerCaptor.capture());
        assertEquals(Status.OK, pickerCaptor.getValue().pickSubchannel(mockArgs).getStatus());

        Status error = Status.UNAVAILABLE.withDescription("boom!");
        stateListener.onSubchannelState(ConnectivityStateInfo.forTransientFailure(error));
        inOrder.verify(mockHelper).refreshNameResolution();
        assertEquals(error, pickerCaptor.getValue().pickSubchannel(mockArgs).getStatus());

        stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
        inOrder.verify(mockHelper).updateBalancingState(eq(READY), pickerCaptor.capture());
        assertEquals(subchannel, pickerCaptor.getValue().pickSubchannel(mockArgs).getSubchannel());

        verify(mockHelper, atLeast(0)).getSynchronizationContext();  // Don't care
        verifyNoMoreInteractions(mockHelper);
    }

    @Test
    public void pickAfterResolutionAfterTransientValue() throws Exception {
        InOrder inOrder = inOrder(mockHelper);

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
        verify(mockSubchannel1).start(stateListenerCaptor.capture());
        SubchannelStateListener stateListener = stateListenerCaptor.getValue();
        verify(mockHelper).updateBalancingState(eq(CONNECTING), pickerCaptor.capture());
        verify(mockSubchannel1).requestConnection();
        reset(mockHelper);
        when(mockHelper.getSynchronizationContext()).thenReturn(syncContext);

        // An error has happened.
        Status error = Status.UNAVAILABLE.withDescription("boom!");
        stateListener.onSubchannelState(ConnectivityStateInfo.forTransientFailure(error));
        inOrder.verify(mockHelper).refreshNameResolution();
        assertEquals(error, pickerCaptor.getValue().pickSubchannel(mockArgs).getStatus());

        // But a subsequent IDLE update should be ignored and the LB state not updated. Additionally,
        // a request for a new connection should be made keep the subchannel trying to connect.
        stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(IDLE));
        inOrder.verify(mockHelper).refreshNameResolution();
        verifyNoMoreInteractions(mockHelper);
        assertEquals(error, pickerCaptor.getValue().pickSubchannel(mockArgs).getStatus());
        verify(mockSubchannel1, times(2)).requestConnection();

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

        assertEquals(mockSubchannel1, pickerCaptor.getValue().pickSubchannel(mockArgs)
                .getSubchannel());

        assertEquals(pickerCaptor.getValue().pickSubchannel(mockArgs),
                pickerCaptor.getValue().pickSubchannel(mockArgs));

        verifyNoMoreInteractions(mockHelper);
    }

    @Test
    public void nameResolutionErrorWithStateChanges() throws Exception {
        InOrder inOrder = inOrder(mockHelper);
        loadBalancer.acceptResolvedAddresses(
                ResolvedAddresses.newBuilder().setAddresses(servers).setAttributes(affinity).build());
        verify(mockHelper, times(4)).createSubchannel(createArgsCaptor.capture());
        verify(mockSubchannel1).start(stateListenerCaptor.capture());
        verify(mockSubchannel2).start(stateListenerCaptor.capture());
        verify(mockSubchannel3).start(stateListenerCaptor.capture());
        verify(mockSubchannel4).start(stateListenerCaptor.capture());
        List<CreateSubchannelArgs> argsList = createArgsCaptor.getAllValues();
        assertThat(argsList.get(0).getAddresses().get(0)).isEqualTo(servers.get(0));
        assertThat(argsList.get(1).getAddresses().get(0)).isEqualTo(servers.get(1));
        assertThat(argsList.get(2).getAddresses().get(0)).isEqualTo(servers.get(2));
        assertThat(argsList.get(3).getAddresses().get(0)).isEqualTo(servers.get(3));
        assertThat(argsList.get(0).getAddresses().size()).isEqualTo(1);
        assertThat(argsList.get(1).getAddresses().size()).isEqualTo(1);
        assertThat(argsList.get(2).getAddresses().size()).isEqualTo(1);
        assertThat(argsList.get(3).getAddresses().size()).isEqualTo(1);

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

        verify(mockSubchannel1, never()).requestConnection();
        loadBalancer.acceptResolvedAddresses(
                ResolvedAddresses.newBuilder().setAddresses(servers).setAttributes(affinity).build());
        verify(mockSubchannel1).requestConnection();

        verify(mockHelper, times(4)).createSubchannel(createArgsCaptor.capture());
        verify(mockSubchannel1).start(stateListenerCaptor.capture());
        List<CreateSubchannelArgs> argsList = createArgsCaptor.getAllValues();
        assertThat(argsList.get(0).getAddresses().get(0)).isEqualTo(servers.get(0));
        assertThat(argsList.get(1).getAddresses().get(0)).isEqualTo(servers.get(1));
        assertThat(argsList.get(2).getAddresses().get(0)).isEqualTo(servers.get(2));
        assertThat(argsList.get(3).getAddresses().get(0)).isEqualTo(servers.get(3));
        assertThat(argsList.get(0).getAddresses().size()).isEqualTo(1);
        assertThat(argsList.get(1).getAddresses().size()).isEqualTo(1);
        assertThat(argsList.get(2).getAddresses().size()).isEqualTo(1);
        assertThat(argsList.get(3).getAddresses().size()).isEqualTo(1);
        SubchannelStateListener stateListener = stateListenerCaptor.getValue();
        verify(mockSubchannel1).requestConnection();
        assertEquals(CONNECTING, loadBalancer.getCurrentState());
    }

    @Test
    public void updateAddresses_emptyEagList_throws() {
      loadBalancer.acceptResolvedAddresses(
          ResolvedAddresses.newBuilder().setAddresses(servers).setAttributes(affinity).build());
      thrown.expect(IllegalArgumentException.class);
      loadBalancer.updateAddresses(Arrays.<EquivalentAddressGroup>asList());
    }

    @Test
    public void updateAddresses_eagListWithNull_throws() {
      loadBalancer.acceptResolvedAddresses(
          ResolvedAddresses.newBuilder().setAddresses(servers).setAttributes(affinity).build());
      List<EquivalentAddressGroup> eags = Arrays.asList((EquivalentAddressGroup) null);
      thrown.expect(NullPointerException.class);
      loadBalancer.updateAddresses(eags);
    }

    @Test
    public void updateAddresses_disjoint_idle() {
      InOrder inOrder = inOrder(mockHelper, mockSubchannel1, mockSubchannel2, mockSubchannel3, mockSubchannel4);
      // Creating first set of endpoints/addresses
      List<EquivalentAddressGroup> oldServers =
          Lists.newArrayList(servers.get(0),
          servers.get(1));

      // Accept Addresses and verify proper connection flow
      assertEquals(IDLE, loadBalancer.getCurrentState());
      loadBalancer.acceptResolvedAddresses(
          ResolvedAddresses.newBuilder().setAddresses(oldServers).setAttributes(affinity).build());
      inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
      inOrder.verify(mockSubchannel1).start(stateListenerCaptor.capture());
      SubchannelStateListener stateListener = stateListenerCaptor.getValue();
      inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
      inOrder.verify(mockSubchannel2).start(stateListenerCaptor.capture());
      SubchannelStateListener stateListener2 = stateListenerCaptor.getValue();
      assertEquals(CONNECTING, loadBalancer.getCurrentState());

      // First connection attempt is successful
      inOrder.verify(mockSubchannel1).requestConnection();
      assertEquals(CONNECTING, loadBalancer.getCurrentState());
      stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
      assertEquals(READY, loadBalancer.getCurrentState());

      // verify that picker returns correct subchannel
      inOrder.verify(mockHelper).updateBalancingState(eq(READY), pickerCaptor.capture());
      SubchannelPicker picker = pickerCaptor.getValue();
      assertEquals(PickResult.withSubchannel(mockSubchannel1), pickerCaptor.getValue().pickSubchannel(mockArgs));
      assertNotEquals(PickResult.withSubchannel(mockSubchannel2), pickerCaptor.getValue().pickSubchannel(mockArgs));
      assertNotEquals(PickResult.withSubchannel(mockSubchannel3), pickerCaptor.getValue().pickSubchannel(mockArgs));
      assertNotEquals(PickResult.withSubchannel(mockSubchannel4), pickerCaptor.getValue().pickSubchannel(mockArgs));

      // Going into IDLE state
      stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(IDLE));
      assertEquals(IDLE, loadBalancer.getCurrentState());
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
      SubchannelStateListener stateListener4 = stateListenerCaptor.getValue();
      assertEquals(IDLE, loadBalancer.getCurrentState());
      verify(mockSubchannel1).shutdown();
      verify(mockSubchannel2).shutdown();
      assertEquals(IDLE, loadBalancer.getCurrentState());
      inOrder.verify(mockHelper).updateBalancingState(eq(IDLE), pickerCaptor.capture());
      picker = pickerCaptor.getValue();

      // If obselete subchannel becomes ready, the state should not be affected
      stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
      stateListener2.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
      assertEquals(IDLE, loadBalancer.getCurrentState());

      // Calling pickSubchannel() twice gave the same result
      assertEquals(picker.pickSubchannel(mockArgs), picker.pickSubchannel(mockArgs));

      // But the picker calls requestConnection() only once
      inOrder.verify(mockHelper).updateBalancingState(eq(CONNECTING), pickerCaptor.capture());
      inOrder.verify(mockSubchannel3).requestConnection();
      assertEquals(PickResult.withNoResult(), pickerCaptor.getValue().pickSubchannel(mockArgs));
      assertEquals(CONNECTING, loadBalancer.getCurrentState());

      // Ready subchannel 3
      stateListener3.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
      assertEquals(READY, loadBalancer.getCurrentState());
      inOrder.verify(mockHelper).updateBalancingState(eq(READY), pickerCaptor.capture());

      // Picking a subchannel returns subchannel 3
      picker = pickerCaptor.getValue();
      assertEquals(PickResult.withSubchannel(mockSubchannel3), picker.pickSubchannel(mockArgs));
      assertNotEquals(PickResult.withSubchannel(mockSubchannel1), pickerCaptor.getValue().pickSubchannel(mockArgs));
      assertNotEquals(PickResult.withSubchannel(mockSubchannel2), pickerCaptor.getValue().pickSubchannel(mockArgs));
      assertNotEquals(PickResult.withSubchannel(mockSubchannel4), pickerCaptor.getValue().pickSubchannel(mockArgs));
    }

    @Test
    public void updateAddresses_disjoint_connecting() {
      InOrder inOrder = inOrder(mockHelper, mockSubchannel1, mockSubchannel2, mockSubchannel3, mockSubchannel4);

      // Creating first set of endpoints/addresses
      SocketAddress socketAddr1 = new FakeSocketAddress("newserver1");
      SocketAddress socketAddr2 = new FakeSocketAddress("newserver2");
      List<EquivalentAddressGroup> oldServers =
          Lists.newArrayList(new EquivalentAddressGroup(socketAddr1),
          new EquivalentAddressGroup((socketAddr2)));

      // Accept Addresses and verify proper connection flow
      assertEquals(IDLE, loadBalancer.getCurrentState());
      loadBalancer.acceptResolvedAddresses(
          ResolvedAddresses.newBuilder().setAddresses(oldServers).setAttributes(affinity).build());
      inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
      inOrder.verify(mockSubchannel1).start(stateListenerCaptor.capture());
      SubchannelStateListener stateListener = stateListenerCaptor.getValue();
      inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
      inOrder.verify(mockSubchannel2).start(stateListenerCaptor.capture());
      SubchannelStateListener stateListener2 = stateListenerCaptor.getValue();
      assertEquals(CONNECTING, loadBalancer.getCurrentState());
      inOrder.verify(mockSubchannel1).requestConnection();

      // Creating second set of endpoints/addresses
      SocketAddress socketAddr3 = new FakeSocketAddress("newserver3");
      SocketAddress socketAddr4 = new FakeSocketAddress("newserver4");
      List<EquivalentAddressGroup> newServers =
          Lists.newArrayList(new EquivalentAddressGroup(socketAddr3),
          new EquivalentAddressGroup((socketAddr4)));

      // Accept new resolved addresses to update
      loadBalancer.acceptResolvedAddresses(
          ResolvedAddresses.newBuilder().setAddresses(newServers).setAttributes(affinity).build());
      assertEquals(CONNECTING, loadBalancer.getCurrentState());
      inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
      inOrder.verify(mockSubchannel3).start(stateListenerCaptor.capture());
      SubchannelStateListener stateListener3 = stateListenerCaptor.getValue();
      inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
      inOrder.verify(mockSubchannel4).start(stateListenerCaptor.capture());
      SubchannelStateListener stateListener4 = stateListenerCaptor.getValue();
      assertEquals(CONNECTING, loadBalancer.getCurrentState());
      verify(mockSubchannel1).shutdown();
      verify(mockSubchannel2).shutdown();
      inOrder.verify(mockSubchannel3).requestConnection();
      assertEquals(CONNECTING, loadBalancer.getCurrentState());

      // If obselete subchannel becomes ready, the state should not be affected
      stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
      stateListener2.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
      assertEquals(CONNECTING, loadBalancer.getCurrentState());

      // Fail connection attempt to third address
      Status error = Status.UNAVAILABLE.withDescription("Simulated connection error");
      stateListener3.onSubchannelState(ConnectivityStateInfo.forTransientFailure(error));
      assertEquals(CONNECTING, loadBalancer.getCurrentState());

      // Verify starting connection attempt to fourth address
      inOrder.verify(mockSubchannel4).requestConnection();
      assertEquals(CONNECTING, loadBalancer.getCurrentState());

      // Succeed connection attempt to fourth address
      stateListener4.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
      assertEquals(READY, loadBalancer.getCurrentState());

      // Verify that picker returns correct subchannel
      inOrder.verify(mockHelper).updateBalancingState(eq(READY), pickerCaptor.capture());
      SubchannelPicker picker = pickerCaptor.getValue();
      assertEquals(PickResult.withSubchannel(mockSubchannel4), pickerCaptor.getValue().pickSubchannel(mockArgs));
      assertNotEquals(PickResult.withSubchannel(mockSubchannel1), pickerCaptor.getValue().pickSubchannel(mockArgs));
      assertNotEquals(PickResult.withSubchannel(mockSubchannel2), pickerCaptor.getValue().pickSubchannel(mockArgs));
      assertNotEquals(PickResult.withSubchannel(mockSubchannel3), pickerCaptor.getValue().pickSubchannel(mockArgs));
    }

    @Test
    public void updateAddresses_disjoint_ready() {
      InOrder inOrder = inOrder(mockHelper, mockSubchannel1, mockSubchannel2, mockSubchannel3, mockSubchannel4);

      // Creating first set of endpoints/addresses
      SocketAddress socketAddr1 = new FakeSocketAddress("newserver1");
      SocketAddress socketAddr2 = new FakeSocketAddress("newserver2");
      List<EquivalentAddressGroup> oldServers =
          Lists.newArrayList(new EquivalentAddressGroup(socketAddr1),
          new EquivalentAddressGroup((socketAddr2)));

      // Accept Addresses and verify proper connection flow
      assertEquals(IDLE, loadBalancer.getCurrentState());
      loadBalancer.acceptResolvedAddresses(
          ResolvedAddresses.newBuilder().setAddresses(oldServers).setAttributes(affinity).build());
      inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
      inOrder.verify(mockSubchannel1).start(stateListenerCaptor.capture());
      SubchannelStateListener stateListener = stateListenerCaptor.getValue();
      inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
      inOrder.verify(mockSubchannel2).start(stateListenerCaptor.capture());
      SubchannelStateListener stateListener2 = stateListenerCaptor.getValue();
      assertEquals(CONNECTING, loadBalancer.getCurrentState());

      // First connection attempt is successful
      inOrder.verify(mockSubchannel1).requestConnection();
      assertEquals(CONNECTING, loadBalancer.getCurrentState());
      stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
      assertEquals(READY, loadBalancer.getCurrentState());

      // Verify that picker returns correct subchannel
      inOrder.verify(mockHelper).updateBalancingState(eq(READY), pickerCaptor.capture());
      SubchannelPicker picker = pickerCaptor.getValue();
      assertEquals(PickResult.withSubchannel(mockSubchannel1), pickerCaptor.getValue().pickSubchannel(mockArgs));
      assertNotEquals(PickResult.withSubchannel(mockSubchannel2), pickerCaptor.getValue().pickSubchannel(mockArgs));
      assertNotEquals(PickResult.withSubchannel(mockSubchannel3), pickerCaptor.getValue().pickSubchannel(mockArgs));
      assertNotEquals(PickResult.withSubchannel(mockSubchannel4), pickerCaptor.getValue().pickSubchannel(mockArgs));

      // Creating second set of endpoints/addresses
      SocketAddress socketAddr3 = new FakeSocketAddress("newserver3");
      SocketAddress socketAddr4 = new FakeSocketAddress("newserver4");
      List<EquivalentAddressGroup> newServers =
          Lists.newArrayList(new EquivalentAddressGroup(socketAddr3),
          new EquivalentAddressGroup((socketAddr4)));

      // Accept new resolved addresses to update
      loadBalancer.acceptResolvedAddresses(
          ResolvedAddresses.newBuilder().setAddresses(newServers).setAttributes(affinity).build());
      inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
      inOrder.verify(mockSubchannel3).start(stateListenerCaptor.capture());
      SubchannelStateListener stateListener3 = stateListenerCaptor.getValue();
      inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
      inOrder.verify(mockSubchannel4).start(stateListenerCaptor.capture());
      SubchannelStateListener stateListener4 = stateListenerCaptor.getValue();
      assertEquals(IDLE, loadBalancer.getCurrentState());
      verify(mockSubchannel1).shutdown();
      verify(mockSubchannel2).shutdown();
      inOrder.verify(mockHelper).updateBalancingState(eq(IDLE), pickerCaptor.capture());
      picker = pickerCaptor.getValue();

      // If obselete subchannel becomes ready, the state should not be affected
      stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
      stateListener2.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
      assertEquals(IDLE, loadBalancer.getCurrentState());

      // Calling pickSubchannel() twice gave the same result
      assertEquals(picker.pickSubchannel(mockArgs), picker.pickSubchannel(mockArgs));

      // But the picker calls requestConnection() only once
      inOrder.verify(mockHelper).updateBalancingState(eq(CONNECTING), pickerCaptor.capture());
      inOrder.verify(mockSubchannel3).requestConnection();
      picker = pickerCaptor.getValue();
      assertEquals(PickResult.withNoResult(), pickerCaptor.getValue().pickSubchannel(mockArgs));
      assertEquals(CONNECTING, loadBalancer.getCurrentState());

      // Ready subchannel 3
      stateListener3.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
      assertEquals(READY, loadBalancer.getCurrentState());
      inOrder.verify(mockHelper).updateBalancingState(eq(READY), pickerCaptor.capture());
      picker = pickerCaptor.getValue();

      // Verify that pickSubchannel() returns correct subchannel
      assertEquals(PickResult.withSubchannel(mockSubchannel3), pickerCaptor.getValue().pickSubchannel(mockArgs));
      assertNotEquals(PickResult.withSubchannel(mockSubchannel1), pickerCaptor.getValue().pickSubchannel(mockArgs));
      assertNotEquals(PickResult.withSubchannel(mockSubchannel2), pickerCaptor.getValue().pickSubchannel(mockArgs));
      assertNotEquals(PickResult.withSubchannel(mockSubchannel4), pickerCaptor.getValue().pickSubchannel(mockArgs));
    }

    @Test
    public void updateAddresses_disjoint_ready_twice() {
      when(mockHelper.createSubchannel(any(CreateSubchannelArgs.class)))
          .thenReturn(mockSubchannel1, mockSubchannel2, mockSubchannel3,
          mockSubchannel4, mockSubchannel1, mockSubchannel2);
      InOrder inOrder = inOrder(mockHelper, mockSubchannel1, mockSubchannel2, mockSubchannel3, mockSubchannel4);
      // Creating first set of endpoints/addresses
      SocketAddress socketAddr1 = new FakeSocketAddress("server1");
      SocketAddress socketAddr2 = new FakeSocketAddress("server2");
      List<EquivalentAddressGroup> oldServers =
          Lists.newArrayList(new EquivalentAddressGroup(socketAddr1),
          new EquivalentAddressGroup(socketAddr2));

      // Accept Addresses and verify proper connection flow
      assertEquals(IDLE, loadBalancer.getCurrentState());
      loadBalancer.acceptResolvedAddresses(
          ResolvedAddresses.newBuilder().setAddresses(oldServers).setAttributes(affinity).build());
      inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
      inOrder.verify(mockSubchannel1).start(stateListenerCaptor.capture());
      SubchannelStateListener stateListener = stateListenerCaptor.getValue();
      inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
      inOrder.verify(mockSubchannel2).start(stateListenerCaptor.capture());
      SubchannelStateListener stateListener2 = stateListenerCaptor.getValue();
      assertEquals(CONNECTING, loadBalancer.getCurrentState());

      // First connection attempt is successful
      inOrder.verify(mockSubchannel1).requestConnection();
      assertEquals(CONNECTING, loadBalancer.getCurrentState());
      stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
      assertEquals(READY, loadBalancer.getCurrentState());

      // verify that picker returns correct subchannel
      inOrder.verify(mockHelper).updateBalancingState(eq(READY), pickerCaptor.capture());
      SubchannelPicker picker = pickerCaptor.getValue();
      assertEquals(PickResult.withSubchannel(mockSubchannel1), pickerCaptor.getValue().pickSubchannel(mockArgs));
      assertNotEquals(PickResult.withSubchannel(mockSubchannel2), pickerCaptor.getValue().pickSubchannel(mockArgs));
      assertNotEquals(PickResult.withSubchannel(mockSubchannel3), pickerCaptor.getValue().pickSubchannel(mockArgs));
      assertNotEquals(PickResult.withSubchannel(mockSubchannel4), pickerCaptor.getValue().pickSubchannel(mockArgs));

      // Creating second set of endpoints/addresses
      SocketAddress socketAddr3 = new FakeSocketAddress("server3");
      SocketAddress socketAddr4 = new FakeSocketAddress("server4");
      List<EquivalentAddressGroup> newServers =
          Lists.newArrayList(new EquivalentAddressGroup(socketAddr3),
          new EquivalentAddressGroup(socketAddr4));

      // Accept new resolved addresses to update
      loadBalancer.acceptResolvedAddresses(
          ResolvedAddresses.newBuilder().setAddresses(newServers).setAttributes(affinity).build());
      inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
      inOrder.verify(mockSubchannel3).start(stateListenerCaptor.capture());
      SubchannelStateListener stateListener3 = stateListenerCaptor.getValue();
      inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
      inOrder.verify(mockSubchannel4).start(stateListenerCaptor.capture());
      SubchannelStateListener stateListener4 = stateListenerCaptor.getValue();
      assertEquals(IDLE, loadBalancer.getCurrentState());
      verify(mockSubchannel1).shutdown();
      verify(mockSubchannel2).shutdown();
      inOrder.verify(mockHelper).updateBalancingState(eq(IDLE), pickerCaptor.capture());
      picker = pickerCaptor.getValue();

      // If obselete subchannel becomes ready, the state should not be affected
      stateListener.onSubchannelState(ConnectivityStateInfo.forNonError((READY)));
      stateListener2.onSubchannelState(ConnectivityStateInfo.forNonError((READY)));
      assertEquals(IDLE, loadBalancer.getCurrentState());

      // Calling pickSubchannel() twice gave the same result
      assertEquals(picker.pickSubchannel(mockArgs), picker.pickSubchannel(mockArgs));

      // But the picker calls requestConnection() only once
      inOrder.verify(mockHelper).updateBalancingState(eq(CONNECTING), pickerCaptor.capture());
      inOrder.verify(mockSubchannel3).requestConnection();
      picker = pickerCaptor.getValue();
      assertEquals(PickResult.withNoResult(), pickerCaptor.getValue().pickSubchannel(mockArgs));
      assertEquals(CONNECTING, loadBalancer.getCurrentState());

      // Ready subchannel 3
      stateListener3.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
      inOrder.verify(mockHelper).updateBalancingState(eq(READY), pickerCaptor.capture());
      assertEquals(READY, loadBalancer.getCurrentState());

      // Verify that pickSubchannel() returns correct subchannel
      assertEquals(PickResult.withSubchannel(mockSubchannel3), pickerCaptor.getValue().pickSubchannel(mockArgs));
      assertNotEquals(PickResult.withSubchannel(mockSubchannel1), pickerCaptor.getValue().pickSubchannel(mockArgs));
      assertNotEquals(PickResult.withSubchannel(mockSubchannel2), pickerCaptor.getValue().pickSubchannel(mockArgs));
      assertNotEquals(PickResult.withSubchannel(mockSubchannel4), pickerCaptor.getValue().pickSubchannel(mockArgs));

      // Creating third set of endpoints/addresses
      List<EquivalentAddressGroup> newestServers =
          Lists.newArrayList(new EquivalentAddressGroup(socketAddr1),
          new EquivalentAddressGroup((socketAddr2)));

      // Second address update
      loadBalancer.acceptResolvedAddresses(
          ResolvedAddresses.newBuilder().setAddresses(newestServers).setAttributes(affinity).build());
      inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
      inOrder.verify(mockSubchannel1).start(stateListenerCaptor.capture());
      stateListener = stateListenerCaptor.getValue();
      inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
      inOrder.verify(mockSubchannel2).start(stateListenerCaptor.capture());
      stateListener2 = stateListenerCaptor.getValue();
      assertEquals(IDLE, loadBalancer.getCurrentState());
      verify(mockSubchannel3).shutdown();
      verify(mockSubchannel4).shutdown();
      inOrder.verify(mockHelper).updateBalancingState(eq(IDLE), pickerCaptor.capture());
      picker = pickerCaptor.getValue();

      // Calling pickSubchannel() twice gave the same result
      assertEquals(picker.pickSubchannel(mockArgs), picker.pickSubchannel(mockArgs));

      // But the picker calls requestConnection() only once
      inOrder.verify(mockHelper).updateBalancingState(eq(CONNECTING), pickerCaptor.capture());
      inOrder.verify(mockSubchannel1).requestConnection();
      picker = pickerCaptor.getValue();
      assertEquals(PickResult.withNoResult(), pickerCaptor.getValue().pickSubchannel(mockArgs));
      assertEquals(CONNECTING, loadBalancer.getCurrentState());

      // If obselete subchannel becomes ready, the state should not be affected
      stateListener3.onSubchannelState(ConnectivityStateInfo.forNonError((READY)));
      assertEquals(CONNECTING, loadBalancer.getCurrentState());

      // Connection attempt to address 1 is unsuccessful
      Status error = Status.UNAVAILABLE.withDescription("Simulated connection error");
      stateListener.onSubchannelState(ConnectivityStateInfo.forTransientFailure(error));
      assertEquals(CONNECTING, loadBalancer.getCurrentState());

      // Starting connection attempt to address 2
      assertEquals(CONNECTING, loadBalancer.getCurrentState());
      inOrder.verify(mockSubchannel2).requestConnection();
      assertEquals(CONNECTING, loadBalancer.getCurrentState());

      // Connection attempt to address 2 is successful
      stateListener2.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
      assertEquals(READY, loadBalancer.getCurrentState());

      // Verify that picker still returns correct subchannel
      inOrder.verify(mockHelper).updateBalancingState(eq(READY), pickerCaptor.capture());
      picker = pickerCaptor.getValue();
      assertEquals(PickResult.withSubchannel(mockSubchannel2), pickerCaptor.getValue().pickSubchannel(mockArgs));
      assertNotEquals(PickResult.withSubchannel(mockSubchannel1), pickerCaptor.getValue().pickSubchannel(mockArgs));
      assertNotEquals(PickResult.withSubchannel(mockSubchannel3), pickerCaptor.getValue().pickSubchannel(mockArgs));
      assertNotEquals(PickResult.withSubchannel(mockSubchannel4), pickerCaptor.getValue().pickSubchannel(mockArgs));
    }

    @Test
    public void updateAddresses_disjoint_transient_failure() {
      assertEquals(CONNECTING, loadBalancer.getCurrentState());
    }

    @Test
    public void updateAddresses_intersecting_idle() {
      InOrder inOrder = inOrder(mockHelper, mockSubchannel1, mockSubchannel2, mockSubchannel3, mockSubchannel4);

      // Creating first set of endpoints/addresses
      SocketAddress socketAddr1 = mock(SocketAddress.class);
      SocketAddress socketAddr2 = mock(SocketAddress.class);
      SocketAddress socketAddr3 = mock(SocketAddress.class);
      List<EquivalentAddressGroup> oldServers =
          Lists.newArrayList(new EquivalentAddressGroup(socketAddr1),
          new EquivalentAddressGroup(socketAddr2));

      // Accept Addresses and verify proper connection flow
      assertEquals(IDLE, loadBalancer.getCurrentState());
      loadBalancer.acceptResolvedAddresses(
          ResolvedAddresses.newBuilder().setAddresses(oldServers).setAttributes(affinity).build());
      inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
      inOrder.verify(mockSubchannel1).start(stateListenerCaptor.capture());
      SubchannelStateListener stateListener = stateListenerCaptor.getValue();
      inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
      inOrder.verify(mockSubchannel2).start(stateListenerCaptor.capture());
      SubchannelStateListener stateListener2 = stateListenerCaptor.getValue();
      assertEquals(CONNECTING, loadBalancer.getCurrentState());
      inOrder.verify(mockSubchannel1).requestConnection();

      // First connection attempt is successful
      assertEquals(CONNECTING, loadBalancer.getCurrentState());
      stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
      assertEquals(READY, loadBalancer.getCurrentState());

      // Verify that picker returns correct subchannel
      inOrder.verify(mockHelper).updateBalancingState(eq(READY), pickerCaptor.capture());
      SubchannelPicker picker = pickerCaptor.getValue();
      assertEquals(PickResult.withSubchannel(mockSubchannel1), pickerCaptor.getValue().pickSubchannel(mockArgs));
      assertNotEquals(PickResult.withSubchannel(mockSubchannel2), pickerCaptor.getValue().pickSubchannel(mockArgs));
      assertNotEquals(PickResult.withSubchannel(mockSubchannel3), pickerCaptor.getValue().pickSubchannel(mockArgs));
      assertNotEquals(PickResult.withSubchannel(mockSubchannel4), pickerCaptor.getValue().pickSubchannel(mockArgs));

      // Going into IDLE state
      stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(IDLE));
      assertEquals(IDLE, loadBalancer.getCurrentState());
      inOrder.verify(mockHelper).updateBalancingState(eq(IDLE), pickerCaptor.capture());

      // Creating second set of endpoints/addresses
      List<EquivalentAddressGroup> newServers =
          Lists.newArrayList(new EquivalentAddressGroup(socketAddr1),
          new EquivalentAddressGroup(socketAddr3));

      // Accept new resolved addresses to update
      loadBalancer.acceptResolvedAddresses(
          ResolvedAddresses.newBuilder().setAddresses(newServers).setAttributes(affinity).build());
      assertEquals(IDLE, loadBalancer.getCurrentState());

      // We create new channels and remove old ones, keeping intersecting ones
      inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
      inOrder.verify(mockSubchannel3).start(stateListenerCaptor.capture());
      SubchannelStateListener stateListener3 = stateListenerCaptor.getValue();
      inOrder.verify(mockSubchannel2).shutdown();

      // If obselete subchannel becomes ready, the state should not be affected
      stateListener2.onSubchannelState(ConnectivityStateInfo.forNonError((READY)));
      assertEquals(IDLE, loadBalancer.getCurrentState());

      inOrder.verify(mockHelper).updateBalancingState(eq(IDLE), pickerCaptor.capture());
      picker = pickerCaptor.getValue();
      assertEquals(IDLE, loadBalancer.getCurrentState());

      // Calling pickSubchannel() twice gave the same result
      assertEquals(picker.pickSubchannel(mockArgs), picker.pickSubchannel(mockArgs));

      // But the picker calls requestConnection() only once
      inOrder.verify(mockHelper).updateBalancingState(eq(CONNECTING), pickerCaptor.capture());
      inOrder.verify(mockSubchannel1).requestConnection();
      assertEquals(PickResult.withNoResult(), pickerCaptor.getValue().pickSubchannel(mockArgs));
      assertEquals(CONNECTING, loadBalancer.getCurrentState());

      // Ready subchannel 1
      stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
      assertEquals(READY, loadBalancer.getCurrentState());
      inOrder.verify(mockHelper).updateBalancingState(eq(READY), pickerCaptor.capture());

      // Picking a subchannel returns subchannel 2
      picker = pickerCaptor.getValue();
      assertEquals(PickResult.withSubchannel(mockSubchannel1), picker.pickSubchannel(mockArgs));
      assertNotEquals(PickResult.withSubchannel(mockSubchannel2), pickerCaptor.getValue().pickSubchannel(mockArgs));
      assertNotEquals(PickResult.withSubchannel(mockSubchannel3), pickerCaptor.getValue().pickSubchannel(mockArgs));
      assertNotEquals(PickResult.withSubchannel(mockSubchannel4), pickerCaptor.getValue().pickSubchannel(mockArgs));
    }

    @Test
    public void updateAddresses_intersecting_connecting() {
      InOrder inOrder = inOrder(mockHelper, mockSubchannel1, mockSubchannel2, mockSubchannel3, mockSubchannel4);

      // Creating first set of endpoints/addresses
      SocketAddress socketAddr1 = mock(SocketAddress.class);
      SocketAddress socketAddr2 = mock(SocketAddress.class);
      SocketAddress socketAddr3 = mock(SocketAddress.class);
      List<EquivalentAddressGroup> oldServers =
          Lists.newArrayList(new EquivalentAddressGroup(socketAddr1),
          new EquivalentAddressGroup(socketAddr2));

      // Accept Addresses and verify proper connection flow
      assertEquals(IDLE, loadBalancer.getCurrentState());
      loadBalancer.acceptResolvedAddresses(
          ResolvedAddresses.newBuilder().setAddresses(oldServers).setAttributes(affinity).build());
      inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
      inOrder.verify(mockSubchannel1).start(stateListenerCaptor.capture());
      SubchannelStateListener stateListener = stateListenerCaptor.getValue();
      inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
      inOrder.verify(mockSubchannel2).start(stateListenerCaptor.capture());
      SubchannelStateListener stateListener2 = stateListenerCaptor.getValue();
      assertEquals(CONNECTING, loadBalancer.getCurrentState());
      inOrder.verify(mockSubchannel1).requestConnection();

      // Creating second set of endpoints/addresses
      List<EquivalentAddressGroup> newServers =
          Lists.newArrayList(new EquivalentAddressGroup(socketAddr1),
          new EquivalentAddressGroup(socketAddr3));

      // Accept new resolved addresses to update
      loadBalancer.acceptResolvedAddresses(
          ResolvedAddresses.newBuilder().setAddresses(newServers).setAttributes(affinity).build());
      assertEquals(CONNECTING, loadBalancer.getCurrentState());

      // We create new channels and remove old ones, keeping intersecting ones
      inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
      inOrder.verify(mockSubchannel2).shutdown();

      // If obselete subchannel becomes ready, the state should not be affected
      stateListener2.onSubchannelState(ConnectivityStateInfo.forNonError((READY)));
      assertEquals(CONNECTING, loadBalancer.getCurrentState());

      // a connection is called on the subchannel with the original address,
      // but requesting a connection again does virtually nothing
      // TODO: verify that requesting a connection on a connecting subchannel does nothing
      // TODO: this should be the case, as obtainActiveTransport only starts a new one if IDLE
      inOrder.verify(mockSubchannel1).requestConnection();
      inOrder.verifyNoMoreInteractions();
      assertEquals(CONNECTING, loadBalancer.getCurrentState());

      // First connection attempt is successful
      stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
      assertEquals(READY, loadBalancer.getCurrentState());

      // verify that picker returns correct subchannel
      inOrder.verify(mockHelper).updateBalancingState(eq(READY), pickerCaptor.capture());
      SubchannelPicker picker = pickerCaptor.getValue();
      assertEquals(PickResult.withSubchannel(mockSubchannel1), pickerCaptor.getValue().pickSubchannel(mockArgs));
      assertNotEquals(PickResult.withSubchannel(mockSubchannel2), pickerCaptor.getValue().pickSubchannel(mockArgs));
      assertNotEquals(PickResult.withSubchannel(mockSubchannel3), pickerCaptor.getValue().pickSubchannel(mockArgs));
      assertNotEquals(PickResult.withSubchannel(mockSubchannel4), pickerCaptor.getValue().pickSubchannel(mockArgs));
    }

    @Test
    public void updateAddresses_intersecting_ready() {
      InOrder inOrder = inOrder(mockHelper, mockSubchannel1, mockSubchannel2, mockSubchannel3, mockSubchannel4);

      // Creating first set of endpoints/addresses
      SocketAddress socketAddr1 = new FakeSocketAddress("newserver1");
      SocketAddress socketAddr2 = new FakeSocketAddress("newserver2");
      List<EquivalentAddressGroup> oldServers =
          Lists.newArrayList(new EquivalentAddressGroup(socketAddr1),
          new EquivalentAddressGroup((socketAddr2)));

      // Accept Addresses and verify proper connection flow
      assertEquals(IDLE, loadBalancer.getCurrentState());
      loadBalancer.acceptResolvedAddresses(
          ResolvedAddresses.newBuilder().setAddresses(oldServers).setAttributes(affinity).build());
      inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
      inOrder.verify(mockSubchannel1).start(stateListenerCaptor.capture());
      inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
      SubchannelStateListener stateListener = stateListenerCaptor.getValue();
      inOrder.verify(mockSubchannel2).start(stateListenerCaptor.capture());
      assertEquals(CONNECTING, loadBalancer.getCurrentState());

      // First connection attempt is successful
      assertEquals(CONNECTING, loadBalancer.getCurrentState());
      inOrder.verify(mockSubchannel1).requestConnection();
      assertEquals(CONNECTING, loadBalancer.getCurrentState());
      stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
      assertEquals(READY, loadBalancer.getCurrentState());

      // verify that picker returns correct subchannel
      inOrder.verify(mockHelper).updateBalancingState(eq(READY), pickerCaptor.capture());
      SubchannelPicker picker = pickerCaptor.getValue();
      assertEquals(PickResult.withSubchannel(mockSubchannel1), pickerCaptor.getValue().pickSubchannel(mockArgs));
      assertNotEquals(PickResult.withSubchannel(mockSubchannel2), pickerCaptor.getValue().pickSubchannel(mockArgs));
      assertNotEquals(PickResult.withSubchannel(mockSubchannel3), pickerCaptor.getValue().pickSubchannel(mockArgs));
      assertNotEquals(PickResult.withSubchannel(mockSubchannel4), pickerCaptor.getValue().pickSubchannel(mockArgs));

      // Creating second set of endpoints/addresses
      SocketAddress socketAddr3 = new FakeSocketAddress("newserver3");
      List<EquivalentAddressGroup> newServers =
          Lists.newArrayList(new EquivalentAddressGroup(socketAddr1),
          new EquivalentAddressGroup((socketAddr3)));

      // Accept new resolved addresses to update
      loadBalancer.acceptResolvedAddresses(
          ResolvedAddresses.newBuilder().setAddresses(newServers).setAttributes(affinity).build());

      // The state is still READY after update since we had an intersecting subchannel that was READY.
      assertEquals(READY, loadBalancer.getCurrentState());

      // We create new subchannels, delete old ones, and preserve intersecting ones.
      inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
      inOrder.verify(mockSubchannel3).start(stateListenerCaptor.capture());
      inOrder.verify(mockSubchannel2).shutdown();

      // Since we are READY, we do not request another connection.
      inOrder.verifyNoMoreInteractions();
      assertEquals(READY, loadBalancer.getCurrentState());

      // Verify that picker still returns correct subchannel
      picker = pickerCaptor.getValue();
      assertEquals(PickResult.withSubchannel(mockSubchannel1), pickerCaptor.getValue().pickSubchannel(mockArgs));
      assertNotEquals(PickResult.withSubchannel(mockSubchannel2), pickerCaptor.getValue().pickSubchannel(mockArgs));
      assertNotEquals(PickResult.withSubchannel(mockSubchannel3), pickerCaptor.getValue().pickSubchannel(mockArgs));
      assertNotEquals(PickResult.withSubchannel(mockSubchannel4), pickerCaptor.getValue().pickSubchannel(mockArgs));
    }

    @Test
    public void updateAddresses_intersecting_transient_failure() {
      // Starting first connection attempt
      InOrder inOrder = inOrder(mockHelper, mockSubchannel1, mockSubchannel2, mockSubchannel3, mockSubchannel4); // captor: captures

      // Creating first set of endpoints/addresses
      SocketAddress socketAddr1 = new FakeSocketAddress("newserver1");
      SocketAddress socketAddr2 = new FakeSocketAddress("newserver2");
      List<EquivalentAddressGroup> addrs =
          Lists.newArrayList(new EquivalentAddressGroup(socketAddr1),
          new EquivalentAddressGroup((socketAddr2)));

      assertEquals(IDLE, loadBalancer.getCurrentState());
      loadBalancer.acceptResolvedAddresses(
          ResolvedAddresses.newBuilder().setAddresses(addrs).setAttributes(affinity).build());
      inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
      assertEquals(CONNECTING, loadBalancer.getCurrentState());
      inOrder.verify(mockSubchannel1).start(stateListenerCaptor.capture());
      SubchannelStateListener stateListener = stateListenerCaptor.getValue();
      inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
      inOrder.verify(mockSubchannel2).start(stateListenerCaptor.capture());
      SubchannelStateListener stateListener2 = stateListenerCaptor.getValue();
      assertEquals(CONNECTING, loadBalancer.getCurrentState());
      inOrder.verify(mockSubchannel1).requestConnection();
      assertEquals(CONNECTING, loadBalancer.getCurrentState());

      // Failing first connection attempt
      Status error = Status.UNAVAILABLE.withDescription("Simulated connection error");
      stateListener.onSubchannelState(ConnectivityStateInfo.forTransientFailure(error));
      assertEquals(CONNECTING, loadBalancer.getCurrentState());

      // Starting second connection attempt
      assertEquals(CONNECTING, loadBalancer.getCurrentState());
      inOrder.verify(mockSubchannel2).requestConnection();
      assertEquals(CONNECTING, loadBalancer.getCurrentState());

      // Failing second connection attempt
      stateListener2.onSubchannelState(ConnectivityStateInfo.forTransientFailure(error));
      assertEquals(TRANSIENT_FAILURE, loadBalancer.getCurrentState()); // sticky transient failure

      // Creating second set of endpoints/addresses
      SocketAddress socketAddr3 = new FakeSocketAddress("newserver3");
      List<EquivalentAddressGroup> newServers =
          Lists.newArrayList(new EquivalentAddressGroup(socketAddr2),
          new EquivalentAddressGroup((socketAddr3)));

      // Accept new resolved addresses to update
      loadBalancer.acceptResolvedAddresses(
          ResolvedAddresses.newBuilder().setAddresses(newServers).setAttributes(affinity).build());
      // TODO: verify behavior: if we update from transient failure... what happens?

      assertEquals(IDLE, loadBalancer.getCurrentState());
      inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
      inOrder.verify(mockSubchannel3).start(stateListenerCaptor.capture());
      SubchannelStateListener stateListener3 = stateListenerCaptor.getValue();
      inOrder.verify(mockSubchannel1).shutdown();
      assertEquals(IDLE, loadBalancer.getCurrentState());
    }

    @Test
    public void updateAddresses_overlapping_on_others_idle() {
      InOrder inOrder = inOrder(mockHelper, mockSubchannel1, mockSubchannel2, mockSubchannel3, mockSubchannel4);

      // Creating first set of endpoints/addresses
      SocketAddress socketAddr1 = new FakeSocketAddress("newserver1");
      SocketAddress socketAddr2 = new FakeSocketAddress("newserver2");
      List<EquivalentAddressGroup> oldServers =
          Lists.newArrayList(new EquivalentAddressGroup(socketAddr1),
          new EquivalentAddressGroup((socketAddr2)));

      // Accept Addresses and verify proper connection flow
      assertEquals(IDLE, loadBalancer.getCurrentState());
      loadBalancer.acceptResolvedAddresses(
          ResolvedAddresses.newBuilder().setAddresses(oldServers).setAttributes(affinity).build());
      inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
      inOrder.verify(mockSubchannel1).start(stateListenerCaptor.capture());
      SubchannelStateListener stateListener = stateListenerCaptor.getValue();
      inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
      inOrder.verify(mockSubchannel2).start(stateListenerCaptor.capture());
      SubchannelStateListener stateListener2 = stateListenerCaptor.getValue();
      assertEquals(CONNECTING, loadBalancer.getCurrentState());
      inOrder.verify(mockSubchannel1).requestConnection();

      // First connection attempt is successful
      assertEquals(CONNECTING, loadBalancer.getCurrentState());
      stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
      assertEquals(READY, loadBalancer.getCurrentState());

      // verify that picker returns correct subchannel
      inOrder.verify(mockHelper).updateBalancingState(eq(READY), pickerCaptor.capture());
      SubchannelPicker picker = pickerCaptor.getValue();
      assertEquals(PickResult.withSubchannel(mockSubchannel1), pickerCaptor.getValue().pickSubchannel(mockArgs));
      assertNotEquals(PickResult.withSubchannel(mockSubchannel2), pickerCaptor.getValue().pickSubchannel(mockArgs));
      assertNotEquals(PickResult.withSubchannel(mockSubchannel3), pickerCaptor.getValue().pickSubchannel(mockArgs));
      assertNotEquals(PickResult.withSubchannel(mockSubchannel4), pickerCaptor.getValue().pickSubchannel(mockArgs));

      // Going into IDLE state
      stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(IDLE));
      assertEquals(IDLE, loadBalancer.getCurrentState());
      inOrder.verify(mockHelper).updateBalancingState(eq(IDLE), pickerCaptor.capture());

      // Creating second set of endpoints/addresses
      SocketAddress socketAddr3 = new FakeSocketAddress("newserver3");
      List<EquivalentAddressGroup> newServers =
          Lists.newArrayList(new EquivalentAddressGroup(socketAddr2),
          new EquivalentAddressGroup((socketAddr3)));

      // Accept new resolved addresses to update
      loadBalancer.acceptResolvedAddresses(
          ResolvedAddresses.newBuilder().setAddresses(newServers).setAttributes(affinity).build());
      assertEquals(IDLE, loadBalancer.getCurrentState());

      // We create new subchannels, delete old ones, and preserve intersecting ones.
      inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
      inOrder.verify(mockSubchannel3).start(stateListenerCaptor.capture());
      SubchannelStateListener stateListener3 = stateListenerCaptor.getValue();
      inOrder.verify(mockSubchannel1).shutdown();
      inOrder.verify(mockHelper).updateBalancingState(eq(IDLE), pickerCaptor.capture());
      picker = pickerCaptor.getValue();
      assertEquals(IDLE, loadBalancer.getCurrentState());

      // If obselete subchannel becomes ready, the state should not be affected
      stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
      assertEquals(IDLE, loadBalancer.getCurrentState());

      // Calling pickSubchannel() twice gave the same result
      assertEquals(picker.pickSubchannel(mockArgs), picker.pickSubchannel(mockArgs));

      // But the picker calls requestConnection() only once
      inOrder.verify(mockHelper).updateBalancingState(eq(CONNECTING), pickerCaptor.capture());
      inOrder.verify(mockSubchannel2).requestConnection();
      assertEquals(PickResult.withNoResult(), pickerCaptor.getValue().pickSubchannel(mockArgs));
      assertEquals(CONNECTING, loadBalancer.getCurrentState());

      // Ready subchannel 2
      stateListener2.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
      assertEquals(READY, loadBalancer.getCurrentState());
      inOrder.verify(mockHelper).updateBalancingState(eq(READY), pickerCaptor.capture());

      // Picking a subchannel returns subchannel 2
      picker = pickerCaptor.getValue();
      assertEquals(PickResult.withSubchannel(mockSubchannel2), picker.pickSubchannel(mockArgs));
      assertNotEquals(PickResult.withSubchannel(mockSubchannel1), pickerCaptor.getValue().pickSubchannel(mockArgs));
      assertNotEquals(PickResult.withSubchannel(mockSubchannel3), pickerCaptor.getValue().pickSubchannel(mockArgs));
      assertNotEquals(PickResult.withSubchannel(mockSubchannel4), pickerCaptor.getValue().pickSubchannel(mockArgs));
    }

    @Test
    public void updateAddresses_overlapping_on_others_connecting() {
      // this test case considers an address update where the address that overlaps is not on the
      // current attempting address, which was previously thought of as "disjoint" but is no longer the case.
      InOrder inOrder = inOrder(mockHelper, mockSubchannel1, mockSubchannel2, mockSubchannel3, mockSubchannel4);

      // Creating first set of endpoints/addresses
      SocketAddress socketAddr1 = new FakeSocketAddress("newserver1");
      SocketAddress socketAddr2 = new FakeSocketAddress("newserver2");
      List<EquivalentAddressGroup> oldServers =
          Lists.newArrayList(new EquivalentAddressGroup(socketAddr1),
          new EquivalentAddressGroup((socketAddr2)));

      // Accept Addresses and verify proper connection flow
      assertEquals(IDLE, loadBalancer.getCurrentState());
      loadBalancer.acceptResolvedAddresses(
          ResolvedAddresses.newBuilder().setAddresses(oldServers).setAttributes(affinity).build());
      inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
      inOrder.verify(mockSubchannel1).start(stateListenerCaptor.capture());
      SubchannelStateListener stateListener = stateListenerCaptor.getValue();
      inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
      inOrder.verify(mockSubchannel2).start(stateListenerCaptor.capture());
      SubchannelStateListener stateListener2 = stateListenerCaptor.getValue();
      assertEquals(CONNECTING, loadBalancer.getCurrentState());
      inOrder.verify(mockSubchannel1).requestConnection();

      // Creating second set of endpoints/addresses
      SocketAddress socketAddr3 = new FakeSocketAddress("newserver3");
      List<EquivalentAddressGroup> newServers =
          Lists.newArrayList(new EquivalentAddressGroup(socketAddr2),
          new EquivalentAddressGroup((socketAddr3)));

      // Accept new resolved addresses to update
      loadBalancer.acceptResolvedAddresses(
          ResolvedAddresses.newBuilder().setAddresses(newServers).setAttributes(affinity).build());
      assertEquals(CONNECTING, loadBalancer.getCurrentState());

      // We create new subchannels, delete old ones, and preserve intersecting ones.
      inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
      inOrder.verify(mockSubchannel3).start(stateListenerCaptor.capture());
      SubchannelStateListener stateListener3 = stateListenerCaptor.getValue();
      inOrder.verify(mockSubchannel1).shutdown();
      assertEquals(CONNECTING, loadBalancer.getCurrentState());

      // If obselete subchannel becomes ready, the state should not be affected
      stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
      assertEquals(CONNECTING, loadBalancer.getCurrentState());

      // Verify connection attempt to address 2
      inOrder.verify(mockSubchannel2).requestConnection();
      assertEquals(CONNECTING, loadBalancer.getCurrentState());

      // Fail connection attempt to second address
      Status error = Status.UNAVAILABLE.withDescription("Simulated connection error");
      stateListener2.onSubchannelState(ConnectivityStateInfo.forTransientFailure(error));
      assertEquals(CONNECTING, loadBalancer.getCurrentState());

      // Verify starting connection attempt to third address
      inOrder.verify(mockSubchannel3).requestConnection();
      assertEquals(CONNECTING, loadBalancer.getCurrentState());

      // Succeed connection attempt to third address
      stateListener3.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
      assertEquals(READY, loadBalancer.getCurrentState());

      // Verify that picker returns correct subchannel
      inOrder.verify(mockHelper).updateBalancingState(eq(READY), pickerCaptor.capture());
      SubchannelPicker picker = pickerCaptor.getValue();
      assertEquals(PickResult.withSubchannel(mockSubchannel3), pickerCaptor.getValue().pickSubchannel(mockArgs));
      assertNotEquals(PickResult.withSubchannel(mockSubchannel1), pickerCaptor.getValue().pickSubchannel(mockArgs));
      assertNotEquals(PickResult.withSubchannel(mockSubchannel2), pickerCaptor.getValue().pickSubchannel(mockArgs));
      assertNotEquals(PickResult.withSubchannel(mockSubchannel4), pickerCaptor.getValue().pickSubchannel(mockArgs));
    }

    @Test
    public void updateAddresses_overlapping_on_others_ready() {
      // overlapping address not on the current connection attempting subchannel is considered disjoint
      InOrder inOrder = inOrder(mockHelper, mockSubchannel1, mockSubchannel2, mockSubchannel3, mockSubchannel4);

      // Creating first set of endpoints/addresses
      SocketAddress socketAddr1 = new FakeSocketAddress("newserver1");
      SocketAddress socketAddr2 = new FakeSocketAddress("newserver2");
      List<EquivalentAddressGroup> oldServers =
          Lists.newArrayList(new EquivalentAddressGroup(socketAddr1),
          new EquivalentAddressGroup((socketAddr2)));

      // Accept Addresses and verify proper connection flow
      assertEquals(IDLE, loadBalancer.getCurrentState());
      loadBalancer.acceptResolvedAddresses(
          ResolvedAddresses.newBuilder().setAddresses(oldServers).setAttributes(affinity).build());
      inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
      assertEquals(CONNECTING, loadBalancer.getCurrentState());
      inOrder.verify(mockSubchannel1).start(stateListenerCaptor.capture());
      SubchannelStateListener stateListener = stateListenerCaptor.getValue();
      inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
      inOrder.verify(mockSubchannel2).start(stateListenerCaptor.capture());
      SubchannelStateListener stateListener2 = stateListenerCaptor.getValue();
      assertEquals(CONNECTING, loadBalancer.getCurrentState());
      inOrder.verify(mockSubchannel1).requestConnection();
      assertEquals(CONNECTING, loadBalancer.getCurrentState());

      // First connection attempt is successful
      stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
      assertEquals(READY, loadBalancer.getCurrentState());

      // verify that picker returns correct subchannel
      inOrder.verify(mockHelper).updateBalancingState(eq(READY), pickerCaptor.capture());
      SubchannelPicker picker = pickerCaptor.getValue();
      assertEquals(PickResult.withSubchannel(mockSubchannel1), pickerCaptor.getValue().pickSubchannel(mockArgs));
      assertNotEquals(PickResult.withSubchannel(mockSubchannel2), pickerCaptor.getValue().pickSubchannel(mockArgs));
      assertNotEquals(PickResult.withSubchannel(mockSubchannel3), pickerCaptor.getValue().pickSubchannel(mockArgs));
      assertNotEquals(PickResult.withSubchannel(mockSubchannel4), pickerCaptor.getValue().pickSubchannel(mockArgs));

      // Creating second set of endpoints/addresses
      SocketAddress socketAddr3 = new FakeSocketAddress("newserver3");
      List<EquivalentAddressGroup> newServers =
          Lists.newArrayList(new EquivalentAddressGroup(socketAddr2),
          new EquivalentAddressGroup((socketAddr3)));

      // Accept new resolved addresses to update
      loadBalancer.acceptResolvedAddresses(
          ResolvedAddresses.newBuilder().setAddresses(newServers).setAttributes(affinity).build());
      assertEquals(IDLE, loadBalancer.getCurrentState());
      inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
      inOrder.verify(mockSubchannel3).start(stateListenerCaptor.capture());
      SubchannelStateListener stateListener3 = stateListenerCaptor.getValue();
      inOrder.verify(mockSubchannel1).shutdown();
      inOrder.verify(mockHelper).updateBalancingState(eq(IDLE), pickerCaptor.capture());

      // If obselete subchannel becomes ready, the state should not be affected
      stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
      assertEquals(IDLE, loadBalancer.getCurrentState());

      // Request a connection via pickSubchannel()
      picker = pickerCaptor.getValue();

      // Calling pickSubchannel() requests a connection, gives the same result when called twice.
      assertEquals(picker.pickSubchannel(mockArgs), picker.pickSubchannel(mockArgs));

      // But the picker calls requestConnection() only once for a total of two connection requests.
      inOrder.verify(mockSubchannel2).requestConnection();

      // Fail connection attempt to address 2
      Status error = Status.UNAVAILABLE.withDescription("Simulated connection error");
      stateListener2.onSubchannelState(ConnectivityStateInfo.forTransientFailure(error));
      assertEquals(CONNECTING, loadBalancer.getCurrentState());

      // Starting connection attempt to address 3
      assertEquals(CONNECTING, loadBalancer.getCurrentState());
      inOrder.verify(mockSubchannel3).requestConnection();
      assertEquals(CONNECTING, loadBalancer.getCurrentState());

      // Connection attempt to address 2 is successful
      stateListener3.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
      assertEquals(READY, loadBalancer.getCurrentState());

      // Verify that picker returns correct subchannel
      inOrder.verify(mockHelper).updateBalancingState(eq(READY), pickerCaptor.capture());
      picker = pickerCaptor.getValue();
      assertEquals(PickResult.withSubchannel(mockSubchannel3), pickerCaptor.getValue().pickSubchannel(mockArgs));
      assertNotEquals(PickResult.withSubchannel(mockSubchannel1), pickerCaptor.getValue().pickSubchannel(mockArgs));
      assertNotEquals(PickResult.withSubchannel(mockSubchannel2), pickerCaptor.getValue().pickSubchannel(mockArgs));
      assertNotEquals(PickResult.withSubchannel(mockSubchannel4), pickerCaptor.getValue().pickSubchannel(mockArgs));
    }

    @Test
    public void updateAddresses_overlapping_on_others_transient_failure() {
      // overlapping address not on the current connection attempting subchannel is considered disjoint
      InOrder inOrder = inOrder(mockHelper, mockSubchannel1, mockSubchannel2, mockSubchannel3, mockSubchannel4);

      // Creating first set of endpoints/addresses
      SocketAddress socketAddr1 = new FakeSocketAddress("newserver1");
      SocketAddress socketAddr2 = new FakeSocketAddress("newserver2");
      List<EquivalentAddressGroup> oldServers =
          Lists.newArrayList(new EquivalentAddressGroup(socketAddr1),
          new EquivalentAddressGroup((socketAddr2)));

      // Accept Addresses and verify proper connection flow
      assertEquals(IDLE, loadBalancer.getCurrentState());
      loadBalancer.acceptResolvedAddresses(
          ResolvedAddresses.newBuilder().setAddresses(oldServers).setAttributes(affinity).build());
      inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
      assertEquals(CONNECTING, loadBalancer.getCurrentState());
      inOrder.verify(mockSubchannel1).start(stateListenerCaptor.capture());
      SubchannelStateListener stateListener = stateListenerCaptor.getValue();
      inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
      inOrder.verify(mockSubchannel2).start(stateListenerCaptor.capture());
      SubchannelStateListener stateListener2 = stateListenerCaptor.getValue();
      assertEquals(CONNECTING, loadBalancer.getCurrentState());
      inOrder.verify(mockSubchannel1).requestConnection();
      assertEquals(CONNECTING, loadBalancer.getCurrentState());

      // First connection attempt is unsuccessful
      Status error = Status.UNAVAILABLE.withDescription("Simulated connection error");
      stateListener.onSubchannelState(ConnectivityStateInfo.forTransientFailure(error));
      assertEquals(CONNECTING, loadBalancer.getCurrentState());

      // Starting connection attempt to address 2
      inOrder.verify(mockSubchannel2).requestConnection();
      assertEquals(CONNECTING, loadBalancer.getCurrentState());

      // Second connection attempt is unsuccessful -- enter transient failure
      stateListener2.onSubchannelState(ConnectivityStateInfo.forTransientFailure(error));
      assertEquals(TRANSIENT_FAILURE, loadBalancer.getCurrentState());

      // Creating second set of endpoints/addresses
      SocketAddress socketAddr3 = new FakeSocketAddress("newserver3");
      List<EquivalentAddressGroup> newServers =
          Lists.newArrayList(new EquivalentAddressGroup(socketAddr2),
          new EquivalentAddressGroup((socketAddr3)));

      // Accept new resolved addresses to update
      loadBalancer.acceptResolvedAddresses(
          ResolvedAddresses.newBuilder().setAddresses(newServers).setAttributes(affinity).build());
      assertEquals(IDLE, loadBalancer.getCurrentState());
      inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
      inOrder.verify(mockSubchannel3).start(stateListenerCaptor.capture());
      SubchannelStateListener stateListener3 = stateListenerCaptor.getValue();
      inOrder.verify(mockSubchannel1).shutdown();
      inOrder.verify(mockHelper).updateBalancingState(eq(IDLE), pickerCaptor.capture());

      // TODO: verify behavior: if we update from transient failure... what happens?
      assertEquals(READY, loadBalancer.getCurrentState());
    }

    @Test
    public void updateAddresses_identical_idle() {
      InOrder inOrder = inOrder(mockHelper, mockSubchannel1, mockSubchannel2, mockSubchannel3, mockSubchannel4);
      // Creating first set of endpoints/addresses
      List<EquivalentAddressGroup> oldServers =
          Lists.newArrayList(servers.get(0),
          servers.get(1));

      // Accept Addresses and verify proper connection flow
      assertEquals(IDLE, loadBalancer.getCurrentState());
      loadBalancer.acceptResolvedAddresses(
          ResolvedAddresses.newBuilder().setAddresses(oldServers).setAttributes(affinity).build());
      inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
      inOrder.verify(mockSubchannel1).start(stateListenerCaptor.capture());
      SubchannelStateListener stateListener = stateListenerCaptor.getValue();
      inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
      inOrder.verify(mockSubchannel2).start(stateListenerCaptor.capture());
      SubchannelStateListener stateListener2 = stateListenerCaptor.getValue();
      assertEquals(CONNECTING, loadBalancer.getCurrentState());

      // First connection attempt is successful
      inOrder.verify(mockSubchannel1).requestConnection();
      assertEquals(CONNECTING, loadBalancer.getCurrentState());
      stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
      assertEquals(READY, loadBalancer.getCurrentState());

      // verify that picker returns correct subchannel
      inOrder.verify(mockHelper).updateBalancingState(eq(READY), pickerCaptor.capture());
      SubchannelPicker picker = pickerCaptor.getValue();
      assertEquals(PickResult.withSubchannel(mockSubchannel1), pickerCaptor.getValue().pickSubchannel(mockArgs));
      assertNotEquals(PickResult.withSubchannel(mockSubchannel2), pickerCaptor.getValue().pickSubchannel(mockArgs));
      assertNotEquals(PickResult.withSubchannel(mockSubchannel3), pickerCaptor.getValue().pickSubchannel(mockArgs));
      assertNotEquals(PickResult.withSubchannel(mockSubchannel4), pickerCaptor.getValue().pickSubchannel(mockArgs));

      // Going into IDLE state
      stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(IDLE));
      assertEquals(IDLE, loadBalancer.getCurrentState());
      inOrder.verify(mockHelper).updateBalancingState(eq(IDLE), pickerCaptor.capture());
      picker = pickerCaptor.getValue();

      // Accept same resolved addresses to update
      loadBalancer.acceptResolvedAddresses(
          ResolvedAddresses.newBuilder().setAddresses(oldServers).setAttributes(affinity).build());

      // Verify that no new subchannels were created or started
      verify(mockHelper, times(2)).createSubchannel(createArgsCaptor.capture());
      verify(mockSubchannel1, times(1)).start(stateListenerCaptor.capture());
      verify(mockSubchannel2, times(1)).start(stateListenerCaptor.capture());

      // First connection attempt is successful
      assertEquals(IDLE, loadBalancer.getCurrentState());
      stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
      assertEquals(READY, loadBalancer.getCurrentState());

      // verify that picker returns correct subchannel
      inOrder.verify(mockHelper).updateBalancingState(eq(READY), pickerCaptor.capture());
      picker = pickerCaptor.getValue();
      assertEquals(PickResult.withSubchannel(mockSubchannel1), pickerCaptor.getValue().pickSubchannel(mockArgs));
      assertNotEquals(PickResult.withSubchannel(mockSubchannel2), pickerCaptor.getValue().pickSubchannel(mockArgs));
      assertNotEquals(PickResult.withSubchannel(mockSubchannel3), pickerCaptor.getValue().pickSubchannel(mockArgs));
      assertNotEquals(PickResult.withSubchannel(mockSubchannel4), pickerCaptor.getValue().pickSubchannel(mockArgs));
    }

    @Test
    public void updateAddresses_identical_connecting() {
      InOrder inOrder = inOrder(mockHelper, mockSubchannel1, mockSubchannel2, mockSubchannel3, mockSubchannel4);
      // Creating first set of endpoints/addresses
      List<EquivalentAddressGroup> oldServers =
          Lists.newArrayList(servers.get(0),
          servers.get(1));

      // Accept Addresses and verify proper connection flow
      assertEquals(IDLE, loadBalancer.getCurrentState());
      loadBalancer.acceptResolvedAddresses(
          ResolvedAddresses.newBuilder().setAddresses(oldServers).setAttributes(affinity).build());
      inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
      inOrder.verify(mockSubchannel1).start(stateListenerCaptor.capture());
      SubchannelStateListener stateListener = stateListenerCaptor.getValue();
      inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
      inOrder.verify(mockSubchannel2).start(stateListenerCaptor.capture());
      SubchannelStateListener stateListener2 = stateListenerCaptor.getValue();
      assertEquals(CONNECTING, loadBalancer.getCurrentState());

      // Accept same resolved addresses to update
      loadBalancer.acceptResolvedAddresses(
          ResolvedAddresses.newBuilder().setAddresses(oldServers).setAttributes(affinity).build());

      // Verify that no new subchannels were created or started
      verify(mockHelper, times(2)).createSubchannel(createArgsCaptor.capture());
      verify(mockSubchannel1, times(1)).start(stateListenerCaptor.capture());
      verify(mockSubchannel2, times(1)).start(stateListenerCaptor.capture());

      // First connection attempt is successful
      assertEquals(CONNECTING, loadBalancer.getCurrentState());
      stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
      assertEquals(READY, loadBalancer.getCurrentState());

      // verify that picker returns correct subchannel
      inOrder.verify(mockHelper).updateBalancingState(eq(READY), pickerCaptor.capture());
      SubchannelPicker picker = pickerCaptor.getValue();
      assertEquals(PickResult.withSubchannel(mockSubchannel1), pickerCaptor.getValue().pickSubchannel(mockArgs));
      assertNotEquals(PickResult.withSubchannel(mockSubchannel2), pickerCaptor.getValue().pickSubchannel(mockArgs));
      assertNotEquals(PickResult.withSubchannel(mockSubchannel3), pickerCaptor.getValue().pickSubchannel(mockArgs));
      assertNotEquals(PickResult.withSubchannel(mockSubchannel4), pickerCaptor.getValue().pickSubchannel(mockArgs));
    }

    @Test
    public void updateAddresses_identical_ready() {
      InOrder inOrder = inOrder(mockHelper, mockSubchannel1, mockSubchannel2, mockSubchannel3, mockSubchannel4);
      // Creating first set of endpoints/addresses
      List<EquivalentAddressGroup> oldServers =
          Lists.newArrayList(servers.get(0),
          servers.get(1));

      // Accept Addresses and verify proper connection flow
      assertEquals(IDLE, loadBalancer.getCurrentState());
      loadBalancer.acceptResolvedAddresses(
          ResolvedAddresses.newBuilder().setAddresses(oldServers).setAttributes(affinity).build());
      inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
      inOrder.verify(mockSubchannel1).start(stateListenerCaptor.capture());
      SubchannelStateListener stateListener = stateListenerCaptor.getValue();
      inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
      inOrder.verify(mockSubchannel2).start(stateListenerCaptor.capture());
      SubchannelStateListener stateListener2 = stateListenerCaptor.getValue();
      assertEquals(CONNECTING, loadBalancer.getCurrentState());

      // First connection attempt is successful
      inOrder.verify(mockSubchannel1).requestConnection();
      assertEquals(CONNECTING, loadBalancer.getCurrentState());
      stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
      assertEquals(READY, loadBalancer.getCurrentState());

      // verify that picker returns correct subchannel
      inOrder.verify(mockHelper).updateBalancingState(eq(READY), pickerCaptor.capture());
      SubchannelPicker picker = pickerCaptor.getValue();
      assertEquals(PickResult.withSubchannel(mockSubchannel1), pickerCaptor.getValue().pickSubchannel(mockArgs));
      assertNotEquals(PickResult.withSubchannel(mockSubchannel2), pickerCaptor.getValue().pickSubchannel(mockArgs));
      assertNotEquals(PickResult.withSubchannel(mockSubchannel3), pickerCaptor.getValue().pickSubchannel(mockArgs));
      assertNotEquals(PickResult.withSubchannel(mockSubchannel4), pickerCaptor.getValue().pickSubchannel(mockArgs));

      // Accept same resolved addresses to update
      loadBalancer.acceptResolvedAddresses(
          ResolvedAddresses.newBuilder().setAddresses(oldServers).setAttributes(affinity).build());

      // Verify that no new subchannels were created or started
      verify(mockHelper, times(2)).createSubchannel(createArgsCaptor.capture());
      verify(mockSubchannel1, times(1)).start(stateListenerCaptor.capture());
      verify(mockSubchannel2, times(1)).start(stateListenerCaptor.capture());
      assertEquals(READY, loadBalancer.getCurrentState());

      // verify that picker returns correct subchannel
      assertEquals(PickResult.withSubchannel(mockSubchannel1), pickerCaptor.getValue().pickSubchannel(mockArgs));
      assertNotEquals(PickResult.withSubchannel(mockSubchannel2), pickerCaptor.getValue().pickSubchannel(mockArgs));
      assertNotEquals(PickResult.withSubchannel(mockSubchannel3), pickerCaptor.getValue().pickSubchannel(mockArgs));
      assertNotEquals(PickResult.withSubchannel(mockSubchannel4), pickerCaptor.getValue().pickSubchannel(mockArgs));
    }

    @Test
    public void updateAddresses_identical_transient_failure() {
      assertEquals(CONNECTING, loadBalancer.getCurrentState());
    }

    @Test
    public void firstAddressConnectionSuccessful() {
      // Starting first connection attempt
      InOrder inOrder = inOrder(mockHelper, mockSubchannel1, mockSubchannel2, mockSubchannel3);
      loadBalancer.acceptResolvedAddresses(
          ResolvedAddresses.newBuilder().setAddresses(servers).setAttributes(affinity).build());
      inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
      assertEquals(CONNECTING, loadBalancer.getCurrentState());
      inOrder.verify(mockSubchannel1).start(stateListenerCaptor.capture());
      SubchannelStateListener stateListener = stateListenerCaptor.getValue();
      inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
      inOrder.verify(mockSubchannel2).start(stateListenerCaptor.capture());
      SubchannelStateListener stateListener2 = stateListenerCaptor.getValue();
      inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
      inOrder.verify(mockSubchannel3).start(stateListenerCaptor.capture());
      SubchannelStateListener stateListener3 = stateListenerCaptor.getValue();
      assertEquals(CONNECTING, loadBalancer.getCurrentState());
      inOrder.verify(mockSubchannel1).requestConnection();
      assertEquals(CONNECTING, loadBalancer.getCurrentState());

      // First connection attempt is successful
      stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
      assertEquals(READY, loadBalancer.getCurrentState());

      // verify that picker returns correct subchannel
      inOrder.verify(mockHelper).updateBalancingState(eq(READY), pickerCaptor.capture());
      SubchannelPicker picker = pickerCaptor.getValue();
      assertEquals(PickResult.withSubchannel(mockSubchannel1), pickerCaptor.getValue().pickSubchannel(mockArgs));
      assertNotEquals(PickResult.withSubchannel(mockSubchannel2), pickerCaptor.getValue().pickSubchannel(mockArgs));
      assertNotEquals(PickResult.withSubchannel(mockSubchannel3), pickerCaptor.getValue().pickSubchannel(mockArgs));
      assertNotEquals(PickResult.withSubchannel(mockSubchannel4), pickerCaptor.getValue().pickSubchannel(mockArgs));
    }

    @Test
    public void twoAddressesSeriallyConnect() {
      // Starting first connection attempt
      InOrder inOrder = inOrder(mockHelper, mockSubchannel1, mockSubchannel2, mockSubchannel3); // captor: captures
      assertEquals(IDLE, loadBalancer.getCurrentState());
      loadBalancer.acceptResolvedAddresses(
          ResolvedAddresses.newBuilder().setAddresses(servers).setAttributes(affinity).build());
      inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
      assertEquals(CONNECTING, loadBalancer.getCurrentState());
      inOrder.verify(mockSubchannel1).start(stateListenerCaptor.capture());
      SubchannelStateListener stateListener = stateListenerCaptor.getValue();
      inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
      inOrder.verify(mockSubchannel2).start(stateListenerCaptor.capture());
      SubchannelStateListener stateListener2 = stateListenerCaptor.getValue();
      inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
      inOrder.verify(mockSubchannel3).start(stateListenerCaptor.capture());
      SubchannelStateListener stateListener3 = stateListenerCaptor.getValue();
      assertEquals(CONNECTING, loadBalancer.getCurrentState());
      inOrder.verify(mockSubchannel1).requestConnection();
      assertEquals(CONNECTING, loadBalancer.getCurrentState());

      // Failing first connection attempt
      Status error = Status.UNAVAILABLE.withDescription("Simulated connection error");
      stateListener.onSubchannelState(ConnectivityStateInfo.forTransientFailure(error));
      assertEquals(CONNECTING, loadBalancer.getCurrentState());

      // Starting second connection attempt
      assertEquals(CONNECTING, loadBalancer.getCurrentState());
      inOrder.verify(mockSubchannel2).requestConnection();
      assertEquals(CONNECTING, loadBalancer.getCurrentState());

      // Second connection attempt is successful
      stateListener2.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
      assertEquals(READY, loadBalancer.getCurrentState());

      // verify that picker returns correct subchannel
      inOrder.verify(mockHelper).updateBalancingState(eq(READY), pickerCaptor.capture());
      SubchannelPicker picker = pickerCaptor.getValue();
      assertEquals(PickResult.withSubchannel(mockSubchannel2), pickerCaptor.getValue().pickSubchannel(mockArgs));
      assertNotEquals(PickResult.withSubchannel(mockSubchannel1), pickerCaptor.getValue().pickSubchannel(mockArgs));
      assertNotEquals(PickResult.withSubchannel(mockSubchannel3), pickerCaptor.getValue().pickSubchannel(mockArgs));
      assertNotEquals(PickResult.withSubchannel(mockSubchannel4), pickerCaptor.getValue().pickSubchannel(mockArgs));
    }

    @Test
    public void multiple_backoffs() {
      // this test case mimics a backoff without implementing one
      // Starting first connection attempt
      InOrder inOrder = inOrder(mockHelper, mockSubchannel1, mockSubchannel2, mockSubchannel3, mockSubchannel4); // captor: captures

      // Creating first set of endpoints/addresses
      SocketAddress socketAddr1 = new FakeSocketAddress("newserver1");
      SocketAddress socketAddr2 = new FakeSocketAddress("newserver2");
      List<EquivalentAddressGroup> addrs =
          Lists.newArrayList(new EquivalentAddressGroup(socketAddr1),
          new EquivalentAddressGroup((socketAddr2)));

      assertEquals(IDLE, loadBalancer.getCurrentState());
      loadBalancer.acceptResolvedAddresses(
          ResolvedAddresses.newBuilder().setAddresses(addrs).setAttributes(affinity).build());
      inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
      assertEquals(CONNECTING, loadBalancer.getCurrentState());
      inOrder.verify(mockSubchannel1).start(stateListenerCaptor.capture());
      SubchannelStateListener stateListener = stateListenerCaptor.getValue();
      inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
      inOrder.verify(mockSubchannel2).start(stateListenerCaptor.capture());
      SubchannelStateListener stateListener2 = stateListenerCaptor.getValue();
      assertEquals(CONNECTING, loadBalancer.getCurrentState());
      inOrder.verify(mockSubchannel1).requestConnection();
      assertEquals(CONNECTING, loadBalancer.getCurrentState());

      // Failing first connection attempt
      Status error = Status.UNAVAILABLE.withDescription("Simulated connection error");
      stateListener.onSubchannelState(ConnectivityStateInfo.forTransientFailure(error));
      assertEquals(CONNECTING, loadBalancer.getCurrentState());

      // Starting second connection attempt
      assertEquals(CONNECTING, loadBalancer.getCurrentState());
      inOrder.verify(mockSubchannel2).requestConnection();
      assertEquals(CONNECTING, loadBalancer.getCurrentState());

      // Failing second connection attempt
      stateListener2.onSubchannelState(ConnectivityStateInfo.forTransientFailure(error));
      assertEquals(TRANSIENT_FAILURE, loadBalancer.getCurrentState());

      // Mimic backoff for first address
      stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(CONNECTING));
      assertEquals(TRANSIENT_FAILURE, loadBalancer.getCurrentState());

      // Mimic backoff for second address
      stateListener2.onSubchannelState(ConnectivityStateInfo.forNonError(CONNECTING));
      assertEquals(TRANSIENT_FAILURE, loadBalancer.getCurrentState());

      // Failing first connection attempt
      stateListener2.onSubchannelState(ConnectivityStateInfo.forTransientFailure(error));
      assertEquals(TRANSIENT_FAILURE, loadBalancer.getCurrentState());

      // Failing second connection attempt
      stateListener2.onSubchannelState(ConnectivityStateInfo.forTransientFailure(error));
      assertEquals(TRANSIENT_FAILURE, loadBalancer.getCurrentState());

      // Mimic backoff for first address
      stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(CONNECTING));
      assertEquals(TRANSIENT_FAILURE, loadBalancer.getCurrentState());

      // Mimic backoff for second address
      stateListener2.onSubchannelState(ConnectivityStateInfo.forNonError(CONNECTING));
      assertEquals(TRANSIENT_FAILURE, loadBalancer.getCurrentState());

      // Connection attempt to second address is now successful
      stateListener2.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
      assertEquals(READY, loadBalancer.getCurrentState());

      // verify that picker returns correct subchannel
      inOrder.verify(mockHelper).updateBalancingState(eq(READY), pickerCaptor.capture());
      SubchannelPicker picker = pickerCaptor.getValue();
      assertEquals(PickResult.withSubchannel(mockSubchannel2), pickerCaptor.getValue().pickSubchannel(mockArgs));
      assertNotEquals(PickResult.withSubchannel(mockSubchannel1), pickerCaptor.getValue().pickSubchannel(mockArgs));
      assertNotEquals(PickResult.withSubchannel(mockSubchannel3), pickerCaptor.getValue().pickSubchannel(mockArgs));
      assertNotEquals(PickResult.withSubchannel(mockSubchannel4), pickerCaptor.getValue().pickSubchannel(mockArgs));
    }

    @Test
    public void backoff_faster_than_serial_connection() {
      // this tests the case where a subchannel completes its backoff and readies a connection
      // before the other addresses have a chance to complete their connection attempts
      InOrder inOrder = inOrder(mockHelper, mockSubchannel1, mockSubchannel2, mockSubchannel3, mockSubchannel4); // captor: captures

      // Creating first set of endpoints/addresses
      SocketAddress socketAddr1 = new FakeSocketAddress("newserver1");
      SocketAddress socketAddr2 = new FakeSocketAddress("newserver2");
      List<EquivalentAddressGroup> addrs =
          Lists.newArrayList(new EquivalentAddressGroup(socketAddr1),
          new EquivalentAddressGroup((socketAddr2)));

      assertEquals(IDLE, loadBalancer.getCurrentState());
      loadBalancer.acceptResolvedAddresses(
          ResolvedAddresses.newBuilder().setAddresses(addrs).setAttributes(affinity).build());
      inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
      assertEquals(CONNECTING, loadBalancer.getCurrentState());
      inOrder.verify(mockSubchannel1).start(stateListenerCaptor.capture());
      SubchannelStateListener stateListener = stateListenerCaptor.getValue();
      inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
      inOrder.verify(mockSubchannel2).start(stateListenerCaptor.capture());
      SubchannelStateListener stateListener2 = stateListenerCaptor.getValue();
      assertEquals(CONNECTING, loadBalancer.getCurrentState());
      inOrder.verify(mockSubchannel1).requestConnection();
      assertEquals(CONNECTING, loadBalancer.getCurrentState());

      // Failing first connection attempt
      Status error = Status.UNAVAILABLE.withDescription("Simulated connection error");
      stateListener.onSubchannelState(ConnectivityStateInfo.forTransientFailure(error));
      assertEquals(CONNECTING, loadBalancer.getCurrentState());

      // Starting second connection attempt
      assertEquals(CONNECTING, loadBalancer.getCurrentState());
      inOrder.verify(mockSubchannel2).requestConnection();
      assertEquals(CONNECTING, loadBalancer.getCurrentState());

      // Mimic backoff for first address
      stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(CONNECTING));
      assertEquals(CONNECTING, loadBalancer.getCurrentState());

      // Connection attempt to first address is now successful
      stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
      assertEquals(READY, loadBalancer.getCurrentState());

      // verify that picker returns correct subchannel
      inOrder.verify(mockHelper).updateBalancingState(eq(READY), pickerCaptor.capture());
      SubchannelPicker picker = pickerCaptor.getValue();
      assertEquals(PickResult.withSubchannel(mockSubchannel1), pickerCaptor.getValue().pickSubchannel(mockArgs));
      assertNotEquals(PickResult.withSubchannel(mockSubchannel2), pickerCaptor.getValue().pickSubchannel(mockArgs));
      assertNotEquals(PickResult.withSubchannel(mockSubchannel3), pickerCaptor.getValue().pickSubchannel(mockArgs));
      assertNotEquals(PickResult.withSubchannel(mockSubchannel4), pickerCaptor.getValue().pickSubchannel(mockArgs));
    }

    @Test
    public void success_from_transient_failure() {
      // Starting first connection attempt
      InOrder inOrder = inOrder(mockHelper, mockSubchannel1, mockSubchannel2, mockSubchannel3, mockSubchannel4); // captor: captures

      // Creating first set of endpoints/addresses
      SocketAddress socketAddr1 = new FakeSocketAddress("newserver1");
      SocketAddress socketAddr2 = new FakeSocketAddress("newserver2");
      List<EquivalentAddressGroup> addrs =
          Lists.newArrayList(new EquivalentAddressGroup(socketAddr1),
          new EquivalentAddressGroup((socketAddr2)));

      assertEquals(IDLE, loadBalancer.getCurrentState());
      loadBalancer.acceptResolvedAddresses(
          ResolvedAddresses.newBuilder().setAddresses(addrs).setAttributes(affinity).build());
      inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
      assertEquals(CONNECTING, loadBalancer.getCurrentState());
      inOrder.verify(mockSubchannel1).start(stateListenerCaptor.capture());
      SubchannelStateListener stateListener = stateListenerCaptor.getValue();
      inOrder.verify(mockHelper).createSubchannel(createArgsCaptor.capture());
      inOrder.verify(mockSubchannel2).start(stateListenerCaptor.capture());
      SubchannelStateListener stateListener2 = stateListenerCaptor.getValue();
      assertEquals(CONNECTING, loadBalancer.getCurrentState());
      inOrder.verify(mockSubchannel1).requestConnection();
      assertEquals(CONNECTING, loadBalancer.getCurrentState());

      // no backoff scheduled
      assertEquals(0, fakeClock.numPendingTasks());

      // Failing first connection attempt
      Status error = Status.UNAVAILABLE.withDescription("Simulated connection error");
      stateListener.onSubchannelState(ConnectivityStateInfo.forTransientFailure(error));
      assertEquals(CONNECTING, loadBalancer.getCurrentState());

      // Starting second connection attempt
      assertEquals(CONNECTING, loadBalancer.getCurrentState());
      inOrder.verify(mockSubchannel2).requestConnection();
      assertEquals(CONNECTING, loadBalancer.getCurrentState());

      // Mimic backoff for first address
      stateListener.onSubchannelState(ConnectivityStateInfo.forNonError(CONNECTING));
      assertEquals(CONNECTING, loadBalancer.getCurrentState());

      // Failing second connection attempt
      stateListener2.onSubchannelState(ConnectivityStateInfo.forTransientFailure(error));
      assertEquals(TRANSIENT_FAILURE, loadBalancer.getCurrentState()); // sticky transient failure

      // Failing connection attempt to first address
      stateListener.onSubchannelState(ConnectivityStateInfo.forTransientFailure(error));
      assertEquals(TRANSIENT_FAILURE, loadBalancer.getCurrentState());

      // Mimic backoff for second address
      stateListener2.onSubchannelState(ConnectivityStateInfo.forNonError(CONNECTING));
      assertEquals(TRANSIENT_FAILURE, loadBalancer.getCurrentState());

      // Connection attempt to second address is now successful
      stateListener2.onSubchannelState(ConnectivityStateInfo.forNonError(READY));
      assertEquals(READY, loadBalancer.getCurrentState());

      // verify that picker returns correct subchannel
      inOrder.verify(mockHelper).updateBalancingState(eq(READY), pickerCaptor.capture());
      SubchannelPicker picker = pickerCaptor.getValue();
      assertEquals(PickResult.withSubchannel(mockSubchannel2), pickerCaptor.getValue().pickSubchannel(mockArgs));
      assertNotEquals(PickResult.withSubchannel(mockSubchannel1), pickerCaptor.getValue().pickSubchannel(mockArgs));
      assertNotEquals(PickResult.withSubchannel(mockSubchannel3), pickerCaptor.getValue().pickSubchannel(mockArgs));
      assertNotEquals(PickResult.withSubchannel(mockSubchannel4), pickerCaptor.getValue().pickSubchannel(mockArgs));
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
      Index index = new Index(Arrays.asList(
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
      Index index = new Index(Arrays.asList(
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
      Index index = new Index(Arrays.asList(
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