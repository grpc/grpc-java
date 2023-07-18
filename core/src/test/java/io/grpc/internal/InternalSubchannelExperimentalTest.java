/*
 * Copyright 2015 The gRPC Authors
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
import static io.grpc.ConnectivityState.SHUTDOWN;
import static io.grpc.ConnectivityState.TRANSIENT_FAILURE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.google.common.collect.Iterables;
import io.grpc.Attributes;
import io.grpc.ConnectivityStateInfo;
import io.grpc.EquivalentAddressGroup;
import io.grpc.InternalChannelz;
import io.grpc.InternalLogId;
import io.grpc.InternalWithLogId;
import io.grpc.Status;
import io.grpc.SynchronizationContext;
import io.grpc.internal.InternalSubchannelExperimental.CallTracingTransport;
import io.grpc.internal.InternalSubchannelExperimental.TransportLogger;
import io.grpc.internal.TestUtils.MockClientTransportInfo;
import java.net.SocketAddress;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/**
 * Unit tests for {@link InternalSubchannelExperimental}.
 */
@RunWith(JUnit4.class)
public class InternalSubchannelExperimentalTest {
    @Rule
    public final MockitoRule mocks = MockitoJUnit.rule();
    @SuppressWarnings("deprecation") // https://github.com/grpc/grpc-java/issues/7467
    @Rule
    public final ExpectedException thrown = ExpectedException.none();

    private static final String AUTHORITY = "fakeauthority";
    private static final String USER_AGENT = "mosaic";
    private static final ConnectivityStateInfo UNAVAILABLE_STATE =
            ConnectivityStateInfo.forTransientFailure(Status.UNAVAILABLE);
    private static final ConnectivityStateInfo RESOURCE_EXHAUSTED_STATE =
            ConnectivityStateInfo.forTransientFailure(Status.RESOURCE_EXHAUSTED);
    private static final Status SHUTDOWN_REASON = Status.UNAVAILABLE.withDescription("for test");

    // For scheduled executor
    private final FakeClock fakeClock = new FakeClock();
    // For syncContext
    private final FakeClock fakeExecutor = new FakeClock();
    private final SynchronizationContext syncContext = new SynchronizationContext(
            new Thread.UncaughtExceptionHandler() {
                @Override
                public void uncaughtException(Thread t, Throwable e) {
                    throw new AssertionError(e);
                }
            });

    private final InternalChannelz channelz = new InternalChannelz();

    @Mock private BackoffPolicy mockBackoffPolicy1;
    @Mock private BackoffPolicy mockBackoffPolicy2;
    @Mock private BackoffPolicy mockBackoffPolicy3;
    @Mock private BackoffPolicy.Provider mockBackoffPolicyProvider;
    @Mock private ClientTransportFactory mockTransportFactory;

    private final LinkedList<String> callbackInvokes = new LinkedList<>();
    private final InternalSubchannelExperimental.Callback mockInternalSubchannelExperimentalCallback =
            new InternalSubchannelExperimental.Callback() {
                @Override
                protected void onTerminated(InternalSubchannelExperimental is) {
                    assertSame(InternalSubchannelExperimental, is);
                    callbackInvokes.add("onTerminated");
                }

                @Override
                protected void onStateChange(InternalSubchannelExperimental is, ConnectivityStateInfo newState) {
                    assertSame(InternalSubchannelExperimental, is);
                    callbackInvokes.add("onStateChange:" + newState);
                }

                @Override
                protected void onInUse(InternalSubchannelExperimental is) {
                    assertSame(InternalSubchannelExperimental, is);
                    callbackInvokes.add("onInUse");
                }

                @Override
                protected void onNotInUse(InternalSubchannelExperimental is) {
                    assertSame(InternalSubchannelExperimental, is);
                    callbackInvokes.add("onNotInUse");
                }
            };

    private InternalSubchannelExperimental InternalSubchannelExperimental;
    private BlockingQueue<MockClientTransportInfo> transports;

    @Before public void setUp() {
        when(mockBackoffPolicyProvider.get())
                .thenReturn(mockBackoffPolicy1, mockBackoffPolicy2, mockBackoffPolicy3);
        when(mockBackoffPolicy1.nextBackoffNanos()).thenReturn(10L, 100L);
        when(mockBackoffPolicy2.nextBackoffNanos()).thenReturn(10L, 100L);
        when(mockBackoffPolicy3.nextBackoffNanos()).thenReturn(10L, 100L);
        transports = TestUtils.captureTransports(mockTransportFactory);
    }

    @After public void noMorePendingTasks() {
        assertEquals(0, fakeClock.numPendingTasks());
        assertEquals(0, fakeExecutor.numPendingTasks());
    }

    @Test public void eagAttribute_propagatesToTransport() {
        SocketAddress addr = new SocketAddress() {};
        Attributes attr = Attributes.newBuilder().set(Attributes.Key.create("some-key"), "1").build();
        createInternalSubchannelExperimental(new EquivalentAddressGroup(Arrays.asList(addr), attr));

        // First attempt
        assertNull(InternalSubchannelExperimental.obtainActiveTransport());
        assertEquals(CONNECTING, InternalSubchannelExperimental.getState());
        verify(mockTransportFactory).newClientTransport(
                eq(addr),
                eq(createClientTransportOptions().setEagAttributes(attr)),
                isA(TransportLogger.class));
    }

    @Test public void eagAuthorityOverride_propagatesToTransport() {
        SocketAddress addr = new SocketAddress() {};
        String overriddenAuthority = "authority-override";
        Attributes attr = Attributes.newBuilder()
                .set(EquivalentAddressGroup.ATTR_AUTHORITY_OVERRIDE, overriddenAuthority).build();
        createInternalSubchannelExperimental(new EquivalentAddressGroup(Arrays.asList(addr), attr));

        // First attempt
        assertNull(InternalSubchannelExperimental.obtainActiveTransport());
        assertEquals(CONNECTING, InternalSubchannelExperimental.getState());
        verify(mockTransportFactory).newClientTransport(
                eq(addr),
                eq(createClientTransportOptions().setAuthority(overriddenAuthority).setEagAttributes(attr)),
                isA(TransportLogger.class));
    }

    @Test public void singleAddressReconnect() {
        SocketAddress addr = mock(SocketAddress.class);
        createInternalSubchannelExperimental(addr);
        assertEquals(IDLE, InternalSubchannelExperimental.getState());

        // Invocation counters
        int transportsCreated = 0;
        int backoff1Consulted = 0;
        int backoff2Consulted = 0;
        int backoffReset = 0;

        // First attempt
        assertEquals(IDLE, InternalSubchannelExperimental.getState());
        assertNoCallbackInvoke();
        assertNull(InternalSubchannelExperimental.obtainActiveTransport());
        assertExactCallbackInvokes("onStateChange:CONNECTING");
        assertEquals(CONNECTING, InternalSubchannelExperimental.getState());
        verify(mockTransportFactory, times(++transportsCreated))
                .newClientTransport(
                        eq(addr),
                        eq(createClientTransportOptions()),
                        isA(TransportLogger.class));

        // Fail this one. Because there is only one address to try, enter TRANSIENT_FAILURE.
        assertNoCallbackInvoke();
        transports.poll().listener.transportShutdown(Status.UNAVAILABLE);
        assertEquals(TRANSIENT_FAILURE, InternalSubchannelExperimental.getState());
        assertExactCallbackInvokes("onStateChange:" + UNAVAILABLE_STATE);

        // Second attempt
        // Transport creation doesn't happen due to backoff
        fakeClock.forwardNanos(9);
        verify(mockTransportFactory, times(transportsCreated))
                .newClientTransport(
                        eq(addr),
                        eq(createClientTransportOptions()),
                        isA(TransportLogger.class));
        assertEquals(TRANSIENT_FAILURE, InternalSubchannelExperimental.getState());

        // transport creation only happens when requested (from PFLB)
        assertNull(InternalSubchannelExperimental.obtainActiveTransport());
        verify(mockTransportFactory, times(++transportsCreated))
                .newClientTransport(
                        eq(addr),
                        eq(createClientTransportOptions()),
                        isA(TransportLogger.class));
        assertEquals(CONNECTING, InternalSubchannelExperimental.getState());

        fakeClock.forwardNanos(1);
        assertExactCallbackInvokes("onStateChange:CONNECTING");
        assertEquals(CONNECTING, InternalSubchannelExperimental.getState());
        verify(mockTransportFactory, times(transportsCreated))
                .newClientTransport(
                        eq(addr),
                        eq(createClientTransportOptions()),
                        isA(TransportLogger.class));

        // Fail this one too
        // Here we use a different status from the first failure, and verify that it's passed to
        // the callback.
        transports.poll().listener.transportShutdown(Status.RESOURCE_EXHAUSTED);
        assertEquals(TRANSIENT_FAILURE, InternalSubchannelExperimental.getState());
        assertExactCallbackInvokes("onStateChange:" + RESOURCE_EXHAUSTED_STATE);

        // Third attempt
        // Transport creation doesn't happen due to backoff
        fakeClock.forwardNanos(99);
        verify(mockTransportFactory, times(transportsCreated))
                .newClientTransport(
                        eq(addr),
                        eq(createClientTransportOptions()),
                        isA(TransportLogger.class));
        assertEquals(TRANSIENT_FAILURE, InternalSubchannelExperimental.getState());
        assertNoCallbackInvoke();

        // transport creation still does not happen
        fakeClock.forwardNanos(1);
        verify(mockTransportFactory, times(transportsCreated))
                .newClientTransport(
                        eq(addr),
                        eq(createClientTransportOptions()),
                        isA(TransportLogger.class));
        assertEquals(TRANSIENT_FAILURE, InternalSubchannelExperimental.getState());
        assertNoCallbackInvoke();

        // transport creation only happens when requested from PFLB
        assertNull(InternalSubchannelExperimental.obtainActiveTransport());
        assertEquals(CONNECTING, InternalSubchannelExperimental.getState());
        assertExactCallbackInvokes("onStateChange:CONNECTING");
        verify(mockTransportFactory, times(++transportsCreated))
                .newClientTransport(
                        eq(addr),
                        eq(createClientTransportOptions()),
                        isA(TransportLogger.class));

        // Let this one succeed, will enter READY state.
        assertNoCallbackInvoke();
        transports.peek().listener.transportReady();
        assertExactCallbackInvokes("onStateChange:READY");
        assertEquals(READY, InternalSubchannelExperimental.getState());
        assertSame(
                transports.peek().transport,
                ((CallTracingTransport) InternalSubchannelExperimental.obtainActiveTransport()).delegate());

        // Close the READY transport, will enter IDLE state.
        assertNoCallbackInvoke();
        transports.poll().listener.transportShutdown(Status.UNAVAILABLE);
        assertEquals(IDLE, InternalSubchannelExperimental.getState());
        assertExactCallbackInvokes("onStateChange:IDLE");

        // Next attempt happens immediately when requested
        assertNull(InternalSubchannelExperimental.obtainActiveTransport());
        assertEquals(CONNECTING, InternalSubchannelExperimental.getState());
        assertExactCallbackInvokes("onStateChange:CONNECTING");
        verify(mockBackoffPolicyProvider, times(backoffReset)).get();
        verify(mockTransportFactory, times(++transportsCreated))
                .newClientTransport(
                        eq(addr),
                        eq(createClientTransportOptions()),
                        isA(TransportLogger.class));
    }

//    @Test public void updateAddresses_disjoint_readyTwice() {
//        SocketAddress addr1 = mock(SocketAddress.class);
//        createInternalSubchannelExperimental(addr1);
//        assertEquals(IDLE, InternalSubchannelExperimental.getState());
//
//        // Address connects
//        assertNull(InternalSubchannelExperimental.obtainActiveTransport());
//        assertExactCallbackInvokes("onStateChange:CONNECTING");
//        verify(mockTransportFactory)
//                .newClientTransport(
//                        eq(addr1),
//                        eq(createClientTransportOptions()),
//                        isA(TransportLogger.class));
//        transports.peek().listener.transportReady();
//        assertExactCallbackInvokes("onStateChange:READY");
//        assertEquals(READY, InternalSubchannelExperimental.getState());
//
//        // Update addresses
//        SocketAddress addr2 = mock(SocketAddress.class);
//        InternalSubchannelExperimental.updateAddresses(
//                Arrays.asList(new EquivalentAddressGroup(Arrays.asList(addr2))));
//        assertExactCallbackInvokes("onStateChange:IDLE");
//        assertEquals(IDLE, InternalSubchannelExperimental.getState());
//        ConnectionClientTransport firstTransport = transports.poll().transport;
//        verify(firstTransport, never()).shutdown(any(Status.class));
//
//        // Address connects
//        assertNull(InternalSubchannelExperimental.obtainActiveTransport());
//        assertExactCallbackInvokes("onStateChange:CONNECTING");
//        verify(mockTransportFactory)
//                .newClientTransport(
//                        eq(addr2),
//                        eq(createClientTransportOptions()),
//                        isA(TransportLogger.class));
//        transports.peek().listener.transportReady();
//        assertExactCallbackInvokes("onStateChange:READY");
//        assertEquals(READY, InternalSubchannelExperimental.getState());
//
//        // Update addresses
//        SocketAddress addr3 = mock(SocketAddress.class);
//        InternalSubchannelExperimental.updateAddresses(
//                Arrays.asList(new EquivalentAddressGroup(Arrays.asList(addr3))));
//        assertExactCallbackInvokes("onStateChange:IDLE");
//        assertEquals(IDLE, InternalSubchannelExperimental.getState());
//        // Earlier transport is shutdown eagerly
//        verify(firstTransport).shutdown(any(Status.class));
//        ConnectionClientTransport secondTransport = transports.peek().transport;
//        verify(secondTransport, never()).shutdown(any(Status.class));
//
//        InternalSubchannelExperimental.shutdown(SHUTDOWN_REASON);
//        verify(secondTransport).shutdown(any(Status.class));
//    }

    @Test
    public void connectIsLazy() {
        SocketAddress addr = mock(SocketAddress.class);
        createInternalSubchannelExperimental(addr);

        // Invocation counters
        int transportsCreated = 0;

        // Won't connect until requested
        verify(mockTransportFactory, times(transportsCreated))
                .newClientTransport(
                        eq(addr),
                        eq(createClientTransportOptions()),
                        isA(TransportLogger.class));

        // First attempt
        InternalSubchannelExperimental.obtainActiveTransport();
        assertExactCallbackInvokes("onStateChange:CONNECTING");
        verify(mockTransportFactory, times(++transportsCreated))
                .newClientTransport(
                        eq(addr),
                        eq(createClientTransportOptions()),
                        isA(TransportLogger.class));

        // Fail this one
        transports.poll().listener.transportShutdown(Status.UNAVAILABLE);
        assertExactCallbackInvokes("onStateChange:" + UNAVAILABLE_STATE);

        // Will not reconnect, there is no longer a backoff in internal subchannel
        fakeClock.forwardNanos(10);
        assertNoCallbackInvoke();

        // No scheduled tasks that would ever try to reconnect ...
        assertEquals(0, fakeClock.numPendingTasks());
        assertEquals(0, fakeExecutor.numPendingTasks());

        // ... until it's requested. This logic would happen inside PFLB.
        InternalSubchannelExperimental.obtainActiveTransport();

        assertExactCallbackInvokes("onStateChange:CONNECTING");
        verify(mockTransportFactory, times(++transportsCreated))
                .newClientTransport(
                        eq(addr),
                        eq(createClientTransportOptions()),
                        isA(TransportLogger.class));
    }

    @Test
    public void shutdownWhenReady() throws Exception {
        SocketAddress addr = mock(SocketAddress.class);
        createInternalSubchannelExperimental(addr);

        InternalSubchannelExperimental.obtainActiveTransport();
        MockClientTransportInfo transportInfo = transports.poll();
        transportInfo.listener.transportReady();
        assertExactCallbackInvokes("onStateChange:CONNECTING", "onStateChange:READY");

        InternalSubchannelExperimental.shutdown(SHUTDOWN_REASON);
        verify(transportInfo.transport).shutdown(same(SHUTDOWN_REASON));
        assertExactCallbackInvokes("onStateChange:SHUTDOWN");
        transportInfo.listener.transportShutdown(SHUTDOWN_REASON);

        transportInfo.listener.transportTerminated();
        assertExactCallbackInvokes("onTerminated");
        verify(transportInfo.transport, never()).shutdownNow(any(Status.class));
    }

    @Test
    public void shutdownBeforeTransportCreated() throws Exception {
        SocketAddress addr = mock(SocketAddress.class);
        createInternalSubchannelExperimental(addr);

        // First transport is created immediately
        InternalSubchannelExperimental.obtainActiveTransport();
        assertExactCallbackInvokes("onStateChange:CONNECTING");
        verify(mockTransportFactory)
                .newClientTransport(
                        eq(addr),
                        eq(createClientTransportOptions()),
                        isA(TransportLogger.class));

        // Fail this one
        MockClientTransportInfo transportInfo = transports.poll();
        transportInfo.listener.transportShutdown(Status.UNAVAILABLE);
        transportInfo.listener.transportTerminated();

        // Entering TRANSIENT_FAILURE, waiting for back-off
        assertExactCallbackInvokes("onStateChange:" + UNAVAILABLE_STATE);

        // Save the reconnectTask before shutting down
        FakeClock.ScheduledTask reconnectTask = null;
        for (FakeClock.ScheduledTask task : fakeClock.getPendingTasks()) {
            if (task.command.toString().contains("EndOfCurrentBackoff")) {
                assertNull("There shouldn't be more than one reconnectTask", reconnectTask);
                assertFalse(task.isDone());
                reconnectTask = task;
            }
        }
        assertNull("There should be no reconnectTask. " +
            "This logic has been moved to PickFirstLeafLoadBalancer", reconnectTask);

        // Shut down InternalSubchannelExperimental before the transport is created.
        InternalSubchannelExperimental.shutdown(SHUTDOWN_REASON);
        // InternalSubchannelExperimental terminated promptly.
        assertExactCallbackInvokes("onStateChange:SHUTDOWN", "onTerminated");

        // Futher call to obtainActiveTransport() is no-op.
        assertNull(InternalSubchannelExperimental.obtainActiveTransport());
        assertEquals(SHUTDOWN, InternalSubchannelExperimental.getState());
        assertNoCallbackInvoke();

        // No more transports will be created.
        fakeClock.forwardNanos(10000);
        assertEquals(SHUTDOWN, InternalSubchannelExperimental.getState());
        verifyNoMoreInteractions(mockTransportFactory);
        assertEquals(0, transports.size());
        assertNoCallbackInvoke();
    }

    @Test
    public void shutdownBeforeTransportReady() throws Exception {
        SocketAddress addr = mock(SocketAddress.class);
        createInternalSubchannelExperimental(addr);

        InternalSubchannelExperimental.obtainActiveTransport();
        assertExactCallbackInvokes("onStateChange:CONNECTING");
        MockClientTransportInfo transportInfo = transports.poll();

        // Shutdown the InternalSubchannelExperimental before the pending transport is ready
        assertNull(InternalSubchannelExperimental.obtainActiveTransport());
        InternalSubchannelExperimental.shutdown(SHUTDOWN_REASON);
        assertExactCallbackInvokes("onStateChange:SHUTDOWN");

        // The transport should've been shut down even though it's not the active transport yet.
        verify(transportInfo.transport).shutdown(same(SHUTDOWN_REASON));
        transportInfo.listener.transportShutdown(Status.UNAVAILABLE);
        assertNoCallbackInvoke();
        transportInfo.listener.transportTerminated();
        assertExactCallbackInvokes("onTerminated");
        assertEquals(SHUTDOWN, InternalSubchannelExperimental.getState());
    }

    @Test
    public void shutdownNow() throws Exception {
        SocketAddress addr = mock(SocketAddress.class);
        createInternalSubchannelExperimental(addr);

        InternalSubchannelExperimental.obtainActiveTransport();
        MockClientTransportInfo t1 = transports.poll();
        t1.listener.transportReady();
        assertExactCallbackInvokes("onStateChange:CONNECTING", "onStateChange:READY");
        t1.listener.transportShutdown(Status.UNAVAILABLE);
        assertExactCallbackInvokes("onStateChange:IDLE");

        InternalSubchannelExperimental.obtainActiveTransport();
        assertExactCallbackInvokes("onStateChange:CONNECTING");
        MockClientTransportInfo t2 = transports.poll();

        Status status = Status.UNAVAILABLE.withDescription("Requested");
        InternalSubchannelExperimental.shutdownNow(status);

        verify(t1.transport).shutdownNow(same(status));
        verify(t2.transport).shutdownNow(same(status));
        assertExactCallbackInvokes("onStateChange:SHUTDOWN");
    }

    @Test
    public void obtainTransportAfterShutdown() throws Exception {
        SocketAddress addr = mock(SocketAddress.class);
        createInternalSubchannelExperimental(addr);

        InternalSubchannelExperimental.shutdown(SHUTDOWN_REASON);
        assertExactCallbackInvokes("onStateChange:SHUTDOWN", "onTerminated");
        assertEquals(SHUTDOWN, InternalSubchannelExperimental.getState());
        assertNull(InternalSubchannelExperimental.obtainActiveTransport());
        verify(mockTransportFactory, times(0))
                .newClientTransport(
                        addr,
                        createClientTransportOptions(),
                        InternalSubchannelExperimental.getChannelLogger());
        assertNoCallbackInvoke();
        assertEquals(SHUTDOWN, InternalSubchannelExperimental.getState());
    }

    @Test
    public void logId() {
        createInternalSubchannelExperimental(mock(SocketAddress.class));

        assertNotNull(InternalSubchannelExperimental.getLogId());
    }

    @Test
    public void inUseState() {
        SocketAddress addr = mock(SocketAddress.class);
        createInternalSubchannelExperimental(addr);

        InternalSubchannelExperimental.obtainActiveTransport();
        MockClientTransportInfo t0 = transports.poll();
        t0.listener.transportReady();
        assertExactCallbackInvokes("onStateChange:CONNECTING", "onStateChange:READY");
        t0.listener.transportInUse(true);
        assertExactCallbackInvokes("onInUse");

        t0.listener.transportInUse(false);
        assertExactCallbackInvokes("onNotInUse");

        t0.listener.transportInUse(true);
        assertExactCallbackInvokes("onInUse");
        t0.listener.transportShutdown(Status.UNAVAILABLE);
        assertExactCallbackInvokes("onStateChange:IDLE");

        assertNull(InternalSubchannelExperimental.obtainActiveTransport());
        MockClientTransportInfo t1 = transports.poll();
        t1.listener.transportReady();
        assertExactCallbackInvokes("onStateChange:CONNECTING", "onStateChange:READY");
        t1.listener.transportInUse(true);
        // InternalSubchannelExperimental is already in-use, thus doesn't call the callback
        assertNoCallbackInvoke();

        t1.listener.transportInUse(false);
        // t0 is still in-use
        assertNoCallbackInvoke();

        t0.listener.transportInUse(false);
        assertExactCallbackInvokes("onNotInUse");
    }

    @Test
    public void transportTerminateWithoutExitingInUse() {
        // An imperfect transport that terminates without going out of in-use. InternalSubchannelExperimental will
        // clear the in-use bit for it.
        SocketAddress addr = mock(SocketAddress.class);
        createInternalSubchannelExperimental(addr);

        InternalSubchannelExperimental.obtainActiveTransport();
        MockClientTransportInfo t0 = transports.poll();
        t0.listener.transportReady();
        assertExactCallbackInvokes("onStateChange:CONNECTING", "onStateChange:READY");
        t0.listener.transportInUse(true);
        assertExactCallbackInvokes("onInUse");

        t0.listener.transportShutdown(Status.UNAVAILABLE);
        assertExactCallbackInvokes("onStateChange:IDLE");
        t0.listener.transportTerminated();
        assertExactCallbackInvokes("onNotInUse");
    }

    @Test
    public void transportStartReturnsRunnable() {
        SocketAddress addr1 = mock(SocketAddress.class);
        SocketAddress addr2 = mock(SocketAddress.class);
        createInternalSubchannelExperimental(addr1);
        final AtomicInteger runnableInvokes = new AtomicInteger(0);
        Runnable startRunnable = new Runnable() {
            @Override
            public void run() {
                runnableInvokes.incrementAndGet();
            }
        };
        transports = TestUtils.captureTransports(mockTransportFactory, startRunnable);

        assertEquals(0, runnableInvokes.get());
        InternalSubchannelExperimental.obtainActiveTransport();
        assertEquals(1, runnableInvokes.get());
        InternalSubchannelExperimental.obtainActiveTransport();
        assertEquals(1, runnableInvokes.get());

        MockClientTransportInfo t0 = transports.poll();
        t0.listener.transportShutdown(Status.UNAVAILABLE);
        assertEquals(1, runnableInvokes.get());
    }

    @Test
    public void resetConnectBackoff() throws Exception {
        SocketAddress addr = mock(SocketAddress.class);
        createInternalSubchannelExperimental(addr);

        // Move into TRANSIENT_FAILURE to schedule reconnect
        InternalSubchannelExperimental.obtainActiveTransport();
        assertExactCallbackInvokes("onStateChange:CONNECTING");
        verify(mockTransportFactory)
                .newClientTransport(
                        eq(addr),
                        eq(createClientTransportOptions()),
                        isA(TransportLogger.class));
        transports.poll().listener.transportShutdown(Status.UNAVAILABLE);
        assertExactCallbackInvokes("onStateChange:" + UNAVAILABLE_STATE);

        // Save the reconnectTask
        FakeClock.ScheduledTask reconnectTask = null;
        for (FakeClock.ScheduledTask task : fakeClock.getPendingTasks()) {
            if (task.command.toString().contains("EndOfCurrentBackoff")) {
                assertNull("There shouldn't be more than one reconnectTask", reconnectTask);
                assertFalse(task.isDone());
                reconnectTask = task;
            }
        }
        assertNull("There should be no reconnectTask. " +
            "This logic has been moved to PickFirstLeafLoadBalancer", reconnectTask);

        InternalSubchannelExperimental.resetConnectBackoff();

        verify(mockTransportFactory, times(2))
                .newClientTransport(
                        eq(addr),
                        eq(createClientTransportOptions()),
                        isA(TransportLogger.class));
        assertExactCallbackInvokes("onStateChange:CONNECTING");

        // Simulate a race between cancel and the task scheduler. Should be a no-op.
        assertNoCallbackInvoke();
        verify(mockTransportFactory, times(2))
                .newClientTransport(
                        eq(addr),
                        eq(createClientTransportOptions()),
                        isA(TransportLogger.class));

        // Fail the reconnect attempt to verify that a fresh reconnect policy is generated after
        // invoking resetConnectBackoff()
        transports.poll().listener.transportShutdown(Status.UNAVAILABLE);
        assertExactCallbackInvokes("onStateChange:" + UNAVAILABLE_STATE);
        fakeClock.forwardNanos(10);
        assertEquals(TRANSIENT_FAILURE, InternalSubchannelExperimental.getState());
    }

    @Test
    public void resetConnectBackoff_noopOnIdleTransport() throws Exception {
        SocketAddress addr = mock(SocketAddress.class);
        createInternalSubchannelExperimental(addr);
        assertEquals(IDLE, InternalSubchannelExperimental.getState());

        InternalSubchannelExperimental.resetConnectBackoff();

        assertNoCallbackInvoke();
    }

    @Test
    public void channelzMembership() throws Exception {
        SocketAddress addr1 = mock(SocketAddress.class);
        createInternalSubchannelExperimental(addr1);
        InternalSubchannelExperimental.obtainActiveTransport();

        MockClientTransportInfo t0 = transports.poll();
        t0.listener.transportReady();
        assertTrue(channelz.containsClientSocket(t0.transport.getLogId()));
        t0.listener.transportShutdown(Status.RESOURCE_EXHAUSTED);
        t0.listener.transportTerminated();
        assertFalse(channelz.containsClientSocket(t0.transport.getLogId()));
    }

    @Test
    public void channelzStatContainsTransport() throws Exception {
        SocketAddress addr = new SocketAddress() {};
        assertThat(transports).isEmpty();
        createInternalSubchannelExperimental(addr);
        InternalSubchannelExperimental.obtainActiveTransport();

        InternalWithLogId registeredTransport
                = Iterables.getOnlyElement(InternalSubchannelExperimental.getStats().get().sockets);
        MockClientTransportInfo actualTransport = Iterables.getOnlyElement(transports);
        assertEquals(actualTransport.transport.getLogId(), registeredTransport.getLogId());
    }

//  @Test public void index_looping() {
//    Attributes.Key<String> key = Attributes.Key.create("some-key");
//    Attributes attr1 = Attributes.newBuilder().set(key, "1").build();
//    Attributes attr2 = Attributes.newBuilder().set(key, "2").build();
//    Attributes attr3 = Attributes.newBuilder().set(key, "3").build();
//    SocketAddress addr1 = new FakeSocketAddress();
//    SocketAddress addr2 = new FakeSocketAddress();
//    SocketAddress addr3 = new FakeSocketAddress();
//    SocketAddress addr4 = new FakeSocketAddress();
//    SocketAddress addr5 = new FakeSocketAddress();
//    Index index = new Index(Arrays.asList(
//        new EquivalentAddressGroup(Arrays.asList(addr1, addr2), attr1),
//        new EquivalentAddressGroup(Arrays.asList(addr3), attr2),
//        new EquivalentAddressGroup(Arrays.asList(addr4, addr5), attr3)));
//    assertThat(index.getCurrentAddress()).isSameInstanceAs(addr1);
//    assertThat(index.getCurrentEagAttributes()).isSameInstanceAs(attr1);
//    assertThat(index.isAtBeginning()).isTrue();
//    assertThat(index.isValid()).isTrue();
//
//    index.increment();
//    assertThat(index.getCurrentAddress()).isSameInstanceAs(addr2);
//    assertThat(index.getCurrentEagAttributes()).isSameInstanceAs(attr1);
//    assertThat(index.isAtBeginning()).isFalse();
//    assertThat(index.isValid()).isTrue();
//
//    index.increment();
//    assertThat(index.getCurrentAddress()).isSameInstanceAs(addr3);
//    assertThat(index.getCurrentEagAttributes()).isSameInstanceAs(attr2);
//    assertThat(index.isAtBeginning()).isFalse();
//    assertThat(index.isValid()).isTrue();
//
//    index.increment();
//    assertThat(index.getCurrentAddress()).isSameInstanceAs(addr4);
//    assertThat(index.getCurrentEagAttributes()).isSameInstanceAs(attr3);
//    assertThat(index.isAtBeginning()).isFalse();
//    assertThat(index.isValid()).isTrue();
//
//    index.increment();
//    assertThat(index.getCurrentAddress()).isSameInstanceAs(addr5);
//    assertThat(index.getCurrentEagAttributes()).isSameInstanceAs(attr3);
//    assertThat(index.isAtBeginning()).isFalse();
//    assertThat(index.isValid()).isTrue();
//
//    index.increment();
//    assertThat(index.isAtBeginning()).isFalse();
//    assertThat(index.isValid()).isFalse();
//
//    index.reset();
//    assertThat(index.getCurrentAddress()).isSameInstanceAs(addr1);
//    assertThat(index.getCurrentEagAttributes()).isSameInstanceAs(attr1);
//    assertThat(index.isAtBeginning()).isTrue();
//    assertThat(index.isValid()).isTrue();
//
//    // We want to make sure both groupIndex and addressIndex are reset
//    index.increment();
//    index.increment();
//    index.increment();
//    index.increment();
//    assertThat(index.getCurrentAddress()).isSameInstanceAs(addr5);
//    assertThat(index.getCurrentEagAttributes()).isSameInstanceAs(attr3);
//    index.reset();
//    assertThat(index.getCurrentAddress()).isSameInstanceAs(addr1);
//    assertThat(index.getCurrentEagAttributes()).isSameInstanceAs(attr1);
//  }

//  @Test public void index_updateGroups_resets() {
//    SocketAddress addr1 = new FakeSocketAddress();
//    SocketAddress addr2 = new FakeSocketAddress();
//    SocketAddress addr3 = new FakeSocketAddress();
//    Index index = new Index(Arrays.asList(
//        new EquivalentAddressGroup(Arrays.asList(addr1)),
//        new EquivalentAddressGroup(Arrays.asList(addr2, addr3))));
//    index.increment();
//    index.increment();
//    // We want to make sure both groupIndex and addressIndex are reset
//    index.updateGroups(Arrays.asList(
//        new EquivalentAddressGroup(Arrays.asList(addr1)),
//        new EquivalentAddressGroup(Arrays.asList(addr2, addr3))));
//    assertThat(index.getCurrentAddress()).isSameInstanceAs(addr1);
//  }

//  @Test public void index_seekTo() {
//    SocketAddress addr1 = new FakeSocketAddress();
//    SocketAddress addr2 = new FakeSocketAddress();
//    SocketAddress addr3 = new FakeSocketAddress();
//    Index index = new Index(Arrays.asList(
//        new EquivalentAddressGroup(Arrays.asList(addr1, addr2)),
//        new EquivalentAddressGroup(Arrays.asList(addr3))));
//    assertThat(index.seekTo(addr3)).isTrue();
//    assertThat(index.getCurrentAddress()).isSameInstanceAs(addr3);
//    assertThat(index.seekTo(addr1)).isTrue();
//    assertThat(index.getCurrentAddress()).isSameInstanceAs(addr1);
//    assertThat(index.seekTo(addr2)).isTrue();
//    assertThat(index.getCurrentAddress()).isSameInstanceAs(addr2);
//    index.seekTo(new FakeSocketAddress());
//    // Failed seekTo doesn't change the index
//    assertThat(index.getCurrentAddress()).isSameInstanceAs(addr2);
//  }
//
    /** Create ClientTransportOptions. Should not be reused if it may be mutated. */
    private ClientTransportFactory.ClientTransportOptions createClientTransportOptions() {
        return new ClientTransportFactory.ClientTransportOptions()
                .setAuthority(AUTHORITY)
                .setUserAgent(USER_AGENT);
    }

    private void createInternalSubchannelExperimental(SocketAddress ... addrs) {
        createInternalSubchannelExperimental(new EquivalentAddressGroup(Arrays.asList(addrs)));
    }

    private void createInternalSubchannelExperimental(EquivalentAddressGroup ... addrs) {
        List<EquivalentAddressGroup> addressGroups = Arrays.asList(addrs);
        InternalLogId logId = InternalLogId.allocate("Subchannel", /*details=*/ AUTHORITY);
        ChannelTracer subchannelTracer = new ChannelTracer(logId, 10,
                fakeClock.getTimeProvider().currentTimeNanos(), "Subchannel");
        InternalSubchannelExperimental = new InternalSubchannelExperimental(addressGroups, AUTHORITY, USER_AGENT,
                mockBackoffPolicyProvider, mockTransportFactory, fakeClock.getScheduledExecutorService(),
                fakeClock.getStopwatchSupplier(), syncContext, mockInternalSubchannelExperimentalCallback,
                channelz, CallTracer.getDefaultFactory().create(),
                subchannelTracer,
                logId,
                new ChannelLoggerImpl(subchannelTracer, fakeClock.getTimeProvider()));
    }

    private void assertNoCallbackInvoke() {
        while (fakeExecutor.runDueTasks() > 0) {}
        assertEquals(0, callbackInvokes.size());
    }

    private void assertExactCallbackInvokes(String ... expectedInvokes) {
        assertEquals(Arrays.asList(expectedInvokes), callbackInvokes);
        callbackInvokes.clear();
    }

    private static class FakeSocketAddress extends SocketAddress {}
}