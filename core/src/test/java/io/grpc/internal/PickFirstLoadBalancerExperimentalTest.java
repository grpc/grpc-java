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

import static com.google.common.base.Preconditions.checkNotNull;import static com.google.common.truth.Truth.assertThat;
import static io.grpc.ConnectivityState.CONNECTING;
import static io.grpc.ConnectivityState.IDLE;
import static io.grpc.ConnectivityState.READY;
import static io.grpc.ConnectivityState.TRANSIENT_FAILURE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.mockito.AdditionalAnswers.delegatesTo;import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;import static org.mockito.Mockito.*;import static org.mockito.Mockito.mock;

import com.google.common.collect.Lists;
import io.grpc.*;
import io.grpc.LoadBalancer.CreateSubchannelArgs;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancer.PickResult;
import io.grpc.LoadBalancer.PickSubchannelArgs;
import io.grpc.LoadBalancer.ResolvedAddresses;
import io.grpc.LoadBalancer.Subchannel;
import io.grpc.LoadBalancer.SubchannelPicker;
import io.grpc.LoadBalancer.SubchannelStateListener;
import io.grpc.Status.Code;
import io.grpc.internal.PickFirstLoadBalancerExperimental.PickFirstLoadBalancerExperimentalConfig;
import java.net.Socket;import java.net.SocketAddress;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.*;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;


/** Unit test for {@link PickFirstLoadBalancerExperimental}. */
@RunWith(JUnit4.class)
public class PickFirstLoadBalancerExperimentalTest {
    private PickFirstLoadBalancerExperimental loadBalancer;
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
    @Mock // This LoadBalancer doesn't use any of the arg fields, as verified in tearDown().
    private PickSubchannelArgs mockArgs;

    @Before
    public void setUp() {
        for (int i = 0; i < 3; i++) {
            SocketAddress addr = new FakeSocketAddress("server" + i);
            servers.add(new EquivalentAddressGroup(addr));
            socketAddresses.add(addr);
        }

        when(mockSubchannel1.getAllAddresses()).thenReturn(servers);
        when(mockHelper.getSynchronizationContext()).thenReturn(syncContext);
        when(mockHelper.createSubchannel(any(CreateSubchannelArgs.class))).thenReturn(mockSubchannel1);
        when(mockBackoffPolicyProvider.get())
                .thenReturn(mockBackoffPolicy1, mockBackoffPolicy2, mockBackoffPolicy3);
        when(mockBackoffPolicy1.nextBackoffNanos()).thenReturn(10L, 100L);
        when(mockBackoffPolicy2.nextBackoffNanos()).thenReturn(10L, 100L);
        when(mockBackoffPolicy3.nextBackoffNanos()).thenReturn(10L, 100L);
        transports = TestUtils.captureTransports(mockTransportFactory);
        loadBalancer = new PickFirstLoadBalancerExperimental(mockHelper);
    }

    @After
    public void tearDown() throws Exception {
        verifyNoMoreInteractions(mockArgs);
    }

    @Test
    public void pickAfterResolved() throws Exception {
        loadBalancer.acceptResolvedAddresses(
                ResolvedAddresses.newBuilder().setAddresses(servers).setAttributes(affinity).build());
        verify(mockHelper, times(3)).createSubchannel(createArgsCaptor.capture());
        List<CreateSubchannelArgs> argsList = createArgsCaptor.getAllValues();
        assertThat(argsList.get(0).getAddresses().get(0)).isEqualTo(servers.get(0));
        assertThat(argsList.get(1).getAddresses().get(0)).isEqualTo(servers.get(1));
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
    public void pickAfterResolved_shuffle() throws Exception {
        loadBalancer.acceptResolvedAddresses(
                ResolvedAddresses.newBuilder().setAddresses(servers).setAttributes(affinity)
                        .setLoadBalancingPolicyConfig(new PickFirstLoadBalancerExperimentalConfig(true, 123L)).build());

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
                        .setLoadBalancingPolicyConfig(new PickFirstLoadBalancerExperimentalConfig(false)).build());

        verify(mockHelper, times(3)).createSubchannel(createArgsCaptor.capture());
        List<CreateSubchannelArgs> argsList = createArgsCaptor.getAllValues();
        assertThat(argsList.get(0).getAddresses().get(0)).isEqualTo(servers.get(0));
        assertThat(argsList.get(1).getAddresses().get(0)).isEqualTo(servers.get(1));
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
    public void requestConnectionPicker() throws Exception {
        loadBalancer.acceptResolvedAddresses(
            ResolvedAddresses.newBuilder().setAddresses(servers).setAttributes(affinity).build());

        InOrder inOrder = inOrder(mockHelper, mockSubchannel1); // captor: captures
        inOrder.verify(mockSubchannel1).start(stateListenerCaptor.capture());
        SubchannelStateListener stateListener = stateListenerCaptor.getValue();
        inOrder.verify(mockHelper).updateBalancingState(eq(CONNECTING), any(SubchannelPicker.class));
        inOrder.verify(mockSubchannel1).requestConnection();

        SubchannelPicker picker = pickerCaptor.getValue();

        // Calling pickSubchannel() twice gave the same result
        assertEquals(picker.pickSubchannel(mockArgs), picker.pickSubchannel(mockArgs));

        // But the picker calls requestConnection() only once
        inOrder.verify(mockSubchannel1).requestConnection();

        verify(mockSubchannel1, times(2)).requestConnection();
    }

    @Test
    public void refreshNameResolutionAfterSubchannelConnectionBroken() {
        loadBalancer.acceptResolvedAddresses(
                ResolvedAddresses.newBuilder().setAddresses(servers).setAttributes(affinity).build());
        verify(mockHelper, times(3)).createSubchannel(createArgsCaptor.capture());

        InOrder inOrder = inOrder(mockHelper, mockSubchannel1);
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
        loadBalancer.acceptResolvedAddresses(
                ResolvedAddresses.newBuilder().setAddresses(servers).setAttributes(affinity).build());
//        verify(mockSubchannel1).updateAddresses(eq(servers));
//        verifyNoMoreInteractions(mockSubchannel1);

        verify(mockHelper, times(3)).createSubchannel(createArgsCaptor.capture());
        assertThat(createArgsCaptor.getValue()).isNotNull();
        verify(mockHelper)
                .updateBalancingState(isA(ConnectivityState.class), isA(SubchannelPicker.class));
        // Updating the subchannel addresses is unnecessary, but doesn't hurt anything
//        verify(mockHelper).updateAddresses(ArgumentMatchers.<EquivalentAddressGroup>anyList());

//        verifyNoMoreInteractions(mockHelper);
    }

    @Test
    public void pickAfterResolvedAndChanged() throws Exception {
        SocketAddress socketAddr = new FakeSocketAddress("newserver");
        List<EquivalentAddressGroup> newServers =
                Lists.newArrayList(new EquivalentAddressGroup(socketAddr));

        InOrder inOrder = inOrder(mockHelper, mockSubchannel1);

        loadBalancer.acceptResolvedAddresses(
                ResolvedAddresses.newBuilder().setAddresses(servers).setAttributes(affinity).build());
        inOrder.verify(mockHelper, times(3)).createSubchannel(createArgsCaptor.capture());
        verify(mockSubchannel1).start(any(SubchannelStateListener.class));
        List<CreateSubchannelArgs> argsList = createArgsCaptor.getAllValues();
        assertThat(argsList.get(0).getAddresses().get(0)).isEqualTo(servers.get(0));
        assertThat(argsList.get(1).getAddresses().get(0)).isEqualTo(servers.get(1));
        assertThat(argsList.get(2).getAddresses().get(0)).isEqualTo(servers.get(2));
        assertThat(argsList.get(0).getAddresses().size()).isEqualTo(1);
        assertThat(argsList.get(1).getAddresses().size()).isEqualTo(1);
        assertThat(argsList.get(2).getAddresses().size()).isEqualTo(1);
        inOrder.verify(mockHelper).updateBalancingState(eq(CONNECTING), pickerCaptor.capture());
        verify(mockSubchannel1).requestConnection();
        assertEquals(mockSubchannel1, pickerCaptor.getValue().pickSubchannel(mockArgs).getSubchannel());

        loadBalancer.acceptResolvedAddresses(
                ResolvedAddresses.newBuilder().setAddresses(newServers).setAttributes(affinity).build());
//        inOrder.verify(mockSubchannel1).updateAddresses(eq(newServers)); no longer applicable, we do not update addresses for individual subchannels

//        verifyNoMoreInteractions(mockSubchannel1);
//        inOrder.verify(mockHelper).updateAddresses(eq(newServers));
//        verifyNoMoreInteractions(mockHelper);
    }

    @Test
    public void pickAfterStateChangeAfterResolution() throws Exception {
        InOrder inOrder = inOrder(mockHelper);

        loadBalancer.acceptResolvedAddresses(
                ResolvedAddresses.newBuilder().setAddresses(servers).setAttributes(affinity).build());
        inOrder.verify(mockHelper, times(3)).createSubchannel(createArgsCaptor.capture());
        List<CreateSubchannelArgs> argsList = createArgsCaptor.getAllValues();
        assertThat(argsList.get(0).getAddresses().get(0)).isEqualTo(servers.get(0));
        assertThat(argsList.get(1).getAddresses().get(0)).isEqualTo(servers.get(1));
        assertThat(argsList.get(2).getAddresses().get(0)).isEqualTo(servers.get(2));
        assertThat(argsList.get(0).getAddresses().size()).isEqualTo(1);
        assertThat(argsList.get(1).getAddresses().size()).isEqualTo(1);
        assertThat(argsList.get(2).getAddresses().size()).isEqualTo(1);
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
        inOrder.verify(mockHelper).updateBalancingState(eq(TRANSIENT_FAILURE), pickerCaptor.capture());
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
        verify(mockHelper, times(3)).createSubchannel(createArgsCaptor.capture());
        List<CreateSubchannelArgs> argsList = createArgsCaptor.getAllValues();
        assertThat(argsList.get(0).getAddresses().get(0)).isEqualTo(servers.get(0));
        assertThat(argsList.get(1).getAddresses().get(0)).isEqualTo(servers.get(1));
        assertThat(argsList.get(2).getAddresses().get(0)).isEqualTo(servers.get(2));
        assertThat(argsList.get(0).getAddresses().size()).isEqualTo(1);
        assertThat(argsList.get(1).getAddresses().size()).isEqualTo(1);
        assertThat(argsList.get(2).getAddresses().size()).isEqualTo(1);
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
        inOrder.verify(mockHelper).updateBalancingState(eq(TRANSIENT_FAILURE), pickerCaptor.capture());
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
        inOrder.verify(mockHelper, times(3)).createSubchannel(createArgsCaptor.capture());
        List<CreateSubchannelArgs> argsList = createArgsCaptor.getAllValues();
        assertThat(argsList.get(0).getAddresses().get(0)).isEqualTo(servers.get(0));
        assertThat(argsList.get(1).getAddresses().get(0)).isEqualTo(servers.get(1));
        assertThat(argsList.get(2).getAddresses().get(0)).isEqualTo(servers.get(2));
        assertThat(argsList.get(0).getAttributes()).isEqualTo(Attributes.EMPTY);
        assertThat(argsList.get(1).getAttributes()).isEqualTo(Attributes.EMPTY);
        assertThat(argsList.get(2).getAttributes()).isEqualTo(Attributes.EMPTY);
        assertThat(argsList.get(0).getAddresses().size()).isEqualTo(1);
        assertThat(argsList.get(1).getAddresses().size()).isEqualTo(1);
        assertThat(argsList.get(2).getAddresses().size()).isEqualTo(1);

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
        verify(mockHelper, times(3)).createSubchannel(createArgsCaptor.capture());
        verify(mockSubchannel1).start(stateListenerCaptor.capture());
        List<CreateSubchannelArgs> argsList = createArgsCaptor.getAllValues();
        assertThat(argsList.get(0).getAddresses().get(0)).isEqualTo(servers.get(0));
        assertThat(argsList.get(1).getAddresses().get(0)).isEqualTo(servers.get(1));
        assertThat(argsList.get(2).getAddresses().get(0)).isEqualTo(servers.get(2));
        assertThat(argsList.get(0).getAddresses().size()).isEqualTo(1);
        assertThat(argsList.get(1).getAddresses().size()).isEqualTo(1);
        assertThat(argsList.get(2).getAddresses().size()).isEqualTo(1);

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

        verify(mockHelper, times(3)).createSubchannel(createArgsCaptor.capture());
        verify(mockSubchannel1).start(stateListenerCaptor.capture());
        List<CreateSubchannelArgs> argsList = createArgsCaptor.getAllValues();
        assertThat(argsList.get(0).getAddresses().get(0)).isEqualTo(servers.get(0));
        assertThat(argsList.get(1).getAddresses().get(0)).isEqualTo(servers.get(1));
        assertThat(argsList.get(2).getAddresses().get(0)).isEqualTo(servers.get(2));
        assertThat(argsList.get(0).getAddresses().size()).isEqualTo(1);
        assertThat(argsList.get(1).getAddresses().size()).isEqualTo(1);
        assertThat(argsList.get(2).getAddresses().size()).isEqualTo(1);
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
    public void updateAddresses_disjoint_connecting() {
      assertEquals(IDLE, loadBalancer.getCurrentState());
      loadBalancer.acceptResolvedAddresses(
          ResolvedAddresses.newBuilder().setAddresses(servers).setAttributes(affinity).build());
      assertEquals(CONNECTING, loadBalancer.getCurrentState());
      verify(mockSubchannel1).requestConnection();

      SocketAddress socketAddr = new FakeSocketAddress("newserver");
      SocketAddress socketAddr2 = new FakeSocketAddress("newserver2");
      List<EquivalentAddressGroup> newServers =
          Lists.newArrayList(new EquivalentAddressGroup(socketAddr),
          new EquivalentAddressGroup((socketAddr2)));

      loadBalancer.acceptResolvedAddresses(
          ResolvedAddresses.newBuilder().setAddresses(newServers).setAttributes(affinity).build());
      verify(mockSubchannel1).requestConnection();
      assertEquals(CONNECTING, loadBalancer.getCurrentState());
    }

    @Test
    public void oneAddressConnectionSuccessful() {
      // Setting up the Subchannel
      mockSubchannel1 = mock(FakeSubchannel.class);
      when(mockHelper.createSubchannel(any(CreateSubchannelArgs.class)))
          .thenReturn(mockSubchannel1);

      // Starting first connection attempt
      InOrder inOrder = inOrder(mockHelper, mockSubchannel1)

    }

    @Test
    public void twoAddressesSeriallyConnect() {
      // Setting up the Subchannels
      mockSubchannel1 = mock(FakeSubchannel.class);
      mockSubchannel2 = mock(FakeSubchannel.class);
      mockSubchannel3 = mock(FakeSubchannel.class);
      when(mockHelper.createSubchannel(any(CreateSubchannelArgs.class)))
          .thenReturn(mockSubchannel1, mockSubchannel2, mockSubchannel3);

      // Starting first connection attempt
      InOrder inOrder = inOrder(mockHelper, mockSubchannel1, mockSubchannel2, mockSubchannel3); // captor: captures
      assertEquals(IDLE, loadBalancer.getCurrentState());
      loadBalancer.acceptResolvedAddresses(
          ResolvedAddresses.newBuilder().setAddresses(servers).setAttributes(affinity).build());
      inOrder.verify(mockHelper, times(3)).createSubchannel(createArgsCaptor.capture());
//      inOrder.verify(mockHelper).updateBalancingState(eq(CONNECTING), pickerCaptor.capture());
      assertEquals(CONNECTING, loadBalancer.getCurrentState());
      inOrder.verify(mockSubchannel1).start(stateListenerCaptor.capture());
//      SubchannelStateListener stateListener = stateListenerCaptor.getValue();
//      mockSubchannel1.setListener(stateListener);
      inOrder.verify(mockSubchannel1).requestConnection();

      // Failing first connection attempt
      Status error = Status.UNAVAILABLE.withDescription("Simulated connection error");
      // TODO: this is incorrect! method was made temporarily package private for testing, but needs a fix
      loadBalancer.processSubchannelState(mockSubchannel1, ConnectivityStateInfo.forTransientFailure(error));
//      mockSubchannel1.updateState(ConnectivityStateInfo.forTransientFailure(error));
      assertEquals(CONNECTING, loadBalancer.getCurrentState());

      // Starting second connection attempt
      inOrder.verify(mockSubchannel2).start(stateListenerCaptor.capture());
      inOrder.verify(mockSubchannel2).requestConnection();
      assertEquals(CONNECTING, loadBalancer.getCurrentState());
      loadBalancer.processSubchannelState(mockSubchannel2, ConnectivityStateInfo.forNonError(READY));
      assertEquals(READY, loadBalancer.getCurrentState());
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
        updateState(ConnectivityStateInfo.forNonError(CONNECTING));
      }

      public void setListener(SubchannelStateListener listener) {
        this.listener = listener;
      }

      public void updateState(ConnectivityStateInfo newState) {
        listener.onSubchannelState(newState);
      }
    }
}