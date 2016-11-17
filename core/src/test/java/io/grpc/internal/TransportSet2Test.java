/*
 * Copyright 2015, Google Inc. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 *    * Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 *    * Redistributions in binary form must reproduce the above
 * copyright notice, this list of conditions and the following disclaimer
 * in the documentation and/or other materials provided with the
 * distribution.
 *
 *    * Neither the name of Google Inc. nor the names of its
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package io.grpc.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import io.grpc.ConnectivityState;
import io.grpc.ConnectivityStateInfo;
import io.grpc.EquivalentAddressGroup;
import io.grpc.Status;
import io.grpc.internal.TestUtils.MockClientTransportInfo;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.net.SocketAddress;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Unit tests for {@link TransportSet2}.
 *
 * <p>It only tests the logic that is not covered by {@link ManagedChannelImplTransportManagerTest}.
 */
@RunWith(JUnit4.class)
public class TransportSet2Test {

  private static final String AUTHORITY = "fakeauthority";
  private static final String USER_AGENT = "mosaic";
  private static final ConnectivityStateInfo UNAVAILABLE_STATE =
      ConnectivityStateInfo.forTransientFailure(Status.UNAVAILABLE);
  private static final ConnectivityStateInfo RESOURCE_EXHAUSTED_STATE =
      ConnectivityStateInfo.forTransientFailure(Status.RESOURCE_EXHAUSTED);

  // For scheduled executor
  private final FakeClock fakeClock = new FakeClock();
  // For channelExecutor
  private final FakeClock fakeExecutor = new FakeClock();
  private final SerializingExecutor channelExecutor =
      new SerializingExecutor(fakeExecutor.getScheduledExecutorService());

  @Mock private BackoffPolicy mockBackoffPolicy1;
  @Mock private BackoffPolicy mockBackoffPolicy2;
  @Mock private BackoffPolicy mockBackoffPolicy3;
  @Mock private BackoffPolicy.Provider mockBackoffPolicyProvider;
  @Mock private ClientTransportFactory mockTransportFactory;

  private final LinkedList<String> callbackInvokes = new LinkedList<String>();
  private final TransportSet2.Callback mockTransportSetCallback = new TransportSet2.Callback() {
      @Override
      public void onTerminated(TransportSet2 ts) {
        assertSame(transportSet, ts);
        callbackInvokes.add("onTerminated");
      }

      @Override
      public void onStateChange(TransportSet2 ts, ConnectivityStateInfo newState) {
        assertSame(transportSet, ts);
        callbackInvokes.add("onStateChange:" + newState);
      }

      @Override
      public void onInUse(TransportSet2 ts) {
        assertSame(transportSet, ts);
        callbackInvokes.add("onInUse");
      }

      @Override
      public void onNotInUse(TransportSet2 ts) {
        assertSame(transportSet, ts);
        callbackInvokes.add("onNotInUse");
      }
    };

  private TransportSet2 transportSet;
  private EquivalentAddressGroup addressGroup;
  private BlockingQueue<MockClientTransportInfo> transports;

  @Before public void setUp() {
    MockitoAnnotations.initMocks(this);

    when(mockBackoffPolicyProvider.get())
        .thenReturn(mockBackoffPolicy1, mockBackoffPolicy2, mockBackoffPolicy3);
    when(mockBackoffPolicy1.nextBackoffMillis()).thenReturn(10L, 100L);
    when(mockBackoffPolicy2.nextBackoffMillis()).thenReturn(10L, 100L);
    when(mockBackoffPolicy3.nextBackoffMillis()).thenReturn(10L, 100L);
    transports = TestUtils.captureTransports(mockTransportFactory);
  }

  @After public void noMorePendingTasks() {
    assertEquals(0, fakeClock.numPendingTasks());
    assertEquals(0, fakeExecutor.numPendingTasks());
  }

  @Test public void singleAddressReconnect() {
    SocketAddress addr = mock(SocketAddress.class);
    createTransportSet(addr);
    assertEquals(ConnectivityState.IDLE, transportSet.getState());

    // Invocation counters
    int transportsCreated = 0;
    int backoff1Consulted = 0;
    int backoff2Consulted = 0;
    int backoffReset = 0;

    // First attempt
    assertEquals(ConnectivityState.IDLE, transportSet.getState());
    assertNoCallbackInvoke();
    assertNull(transportSet.obtainActiveTransport());
    assertExactCallbackInvokes("onStateChange:CONNECTING");
    assertEquals(ConnectivityState.CONNECTING, transportSet.getState());
    verify(mockTransportFactory, times(++transportsCreated))
        .newClientTransport(addr, AUTHORITY, USER_AGENT);

    // Fail this one. Because there is only one address to try, enter TRANSIENT_FAILURE.
    assertNoCallbackInvoke();
    transports.poll().listener.transportShutdown(Status.UNAVAILABLE);
    assertEquals(ConnectivityState.TRANSIENT_FAILURE, transportSet.getState());
    assertExactCallbackInvokes("onStateChange:" + UNAVAILABLE_STATE);
    // Backoff reset and using first back-off value interval
    verify(mockBackoffPolicy1, times(++backoff1Consulted)).nextBackoffMillis();
    verify(mockBackoffPolicyProvider, times(++backoffReset)).get();

    // Second attempt
    // Transport creation doesn't happen until time is due
    fakeClock.forwardMillis(9);
    assertNull(transportSet.obtainActiveTransport());
    verify(mockTransportFactory, times(transportsCreated))
        .newClientTransport(addr, AUTHORITY, USER_AGENT);
    assertEquals(ConnectivityState.TRANSIENT_FAILURE, transportSet.getState());

    assertNoCallbackInvoke();
    fakeClock.forwardMillis(1);
    assertExactCallbackInvokes("onStateChange:CONNECTING");
    assertEquals(ConnectivityState.CONNECTING, transportSet.getState());
    verify(mockTransportFactory, times(++transportsCreated))
        .newClientTransport(addr, AUTHORITY, USER_AGENT);
    // Fail this one too
    assertNoCallbackInvoke();
    // Here we use a different status from the first failure, and verify that it's passed to
    // the callback.
    transports.poll().listener.transportShutdown(Status.RESOURCE_EXHAUSTED);
    assertEquals(ConnectivityState.TRANSIENT_FAILURE, transportSet.getState());
    assertExactCallbackInvokes("onStateChange:" + RESOURCE_EXHAUSTED_STATE);
    // Second back-off interval
    verify(mockBackoffPolicy1, times(++backoff1Consulted)).nextBackoffMillis();
    verify(mockBackoffPolicyProvider, times(backoffReset)).get();

    // Third attempt
    // Transport creation doesn't happen until time is due
    fakeClock.forwardMillis(99);
    assertNull(transportSet.obtainActiveTransport());
    verify(mockTransportFactory, times(transportsCreated))
        .newClientTransport(addr, AUTHORITY, USER_AGENT);
    assertEquals(ConnectivityState.TRANSIENT_FAILURE, transportSet.getState());
    assertNoCallbackInvoke();
    fakeClock.forwardMillis(1);
    assertEquals(ConnectivityState.CONNECTING, transportSet.getState());
    assertExactCallbackInvokes("onStateChange:CONNECTING");
    assertNull(transportSet.obtainActiveTransport());
    verify(mockTransportFactory, times(++transportsCreated))
        .newClientTransport(addr, AUTHORITY, USER_AGENT);
    // Let this one succeed, will enter READY state.
    assertNoCallbackInvoke();
    transports.peek().listener.transportReady();
    assertExactCallbackInvokes("onStateChange:READY");
    assertEquals(ConnectivityState.READY, transportSet.getState());
    assertSame(transports.peek().transport, transportSet.obtainActiveTransport());

    // Close the READY transport, will enter IDLE state.
    assertNoCallbackInvoke();
    transports.poll().listener.transportShutdown(Status.UNAVAILABLE);
    assertEquals(ConnectivityState.IDLE, transportSet.getState());
    assertExactCallbackInvokes("onStateChange:IDLE");

    // Back-off is reset, and the next attempt will happen immediately
    assertNull(transportSet.obtainActiveTransport());
    assertEquals(ConnectivityState.CONNECTING, transportSet.getState());
    assertExactCallbackInvokes("onStateChange:CONNECTING");
    verify(mockBackoffPolicyProvider, times(backoffReset)).get();
    verify(mockTransportFactory, times(++transportsCreated))
        .newClientTransport(addr, AUTHORITY, USER_AGENT);

    // Final checks for consultations on back-off policies
    verify(mockBackoffPolicy1, times(backoff1Consulted)).nextBackoffMillis();
    verify(mockBackoffPolicy2, times(backoff2Consulted)).nextBackoffMillis();
  }

  @Test public void twoAddressesReconnect() {
    SocketAddress addr1 = mock(SocketAddress.class);
    SocketAddress addr2 = mock(SocketAddress.class);
    createTransportSet(addr1, addr2);
    assertEquals(ConnectivityState.IDLE, transportSet.getState());
    // Invocation counters
    int transportsAddr1 = 0;
    int transportsAddr2 = 0;
    int backoff1Consulted = 0;
    int backoff2Consulted = 0;
    int backoff3Consulted = 0;
    int backoffReset = 0;

    // First attempt
    assertNoCallbackInvoke();
    assertNull(transportSet.obtainActiveTransport());
    assertExactCallbackInvokes("onStateChange:CONNECTING");
    assertEquals(ConnectivityState.CONNECTING, transportSet.getState());
    verify(mockTransportFactory, times(++transportsAddr1))
        .newClientTransport(addr1, AUTHORITY, USER_AGENT);

    // Let this one fail without success
    transports.poll().listener.transportShutdown(Status.UNAVAILABLE);
    // Still in CONNECTING
    assertNull(transportSet.obtainActiveTransport());
    assertNoCallbackInvoke();
    assertEquals(ConnectivityState.CONNECTING, transportSet.getState());

    // Second attempt will start immediately. Still no back-off policy.
    verify(mockBackoffPolicyProvider, times(backoffReset)).get();
    verify(mockTransportFactory, times(++transportsAddr2))
        .newClientTransport(addr2, AUTHORITY, USER_AGENT);
    assertNull(transportSet.obtainActiveTransport());
    // Fail this one too
    assertNoCallbackInvoke();
    transports.poll().listener.transportShutdown(Status.UNAVAILABLE);
    // All addresses have failed. Delayed transport will be in back-off interval.
    assertEquals(ConnectivityState.TRANSIENT_FAILURE, transportSet.getState());
    assertExactCallbackInvokes("onStateChange:" + UNAVAILABLE_STATE);
    // Backoff reset and first back-off interval begins
    verify(mockBackoffPolicy1, times(++backoff1Consulted)).nextBackoffMillis();
    verify(mockBackoffPolicyProvider, times(++backoffReset)).get();

    // No reconnect during TRANSIENT_FAILURE even when requested.
    assertNull(transportSet.obtainActiveTransport());
    assertNoCallbackInvoke();
    assertEquals(ConnectivityState.TRANSIENT_FAILURE, transportSet.getState());

    // Third attempt is the first address, thus controlled by the first back-off interval.
    fakeClock.forwardMillis(9);
    verify(mockTransportFactory, times(transportsAddr1))
        .newClientTransport(addr1, AUTHORITY, USER_AGENT);
    assertEquals(ConnectivityState.TRANSIENT_FAILURE, transportSet.getState());
    assertNoCallbackInvoke();
    fakeClock.forwardMillis(1);
    assertExactCallbackInvokes("onStateChange:CONNECTING");
    assertEquals(ConnectivityState.CONNECTING, transportSet.getState());
    verify(mockTransportFactory, times(++transportsAddr1))
        .newClientTransport(addr1, AUTHORITY, USER_AGENT);
    // Fail this one too
    transports.poll().listener.transportShutdown(Status.UNAVAILABLE);
    assertEquals(ConnectivityState.CONNECTING, transportSet.getState());

    // Forth attempt will start immediately. Keep back-off policy.
    assertNull(transportSet.obtainActiveTransport());
    assertEquals(ConnectivityState.CONNECTING, transportSet.getState());
    verify(mockBackoffPolicyProvider, times(backoffReset)).get();
    verify(mockTransportFactory, times(++transportsAddr2))
        .newClientTransport(addr2, AUTHORITY, USER_AGENT);
    // Fail this one too
    assertNoCallbackInvoke();
    transports.poll().listener.transportShutdown(Status.RESOURCE_EXHAUSTED);
    // All addresses have failed again. Delayed transport will be in back-off interval.
    assertExactCallbackInvokes("onStateChange:" + RESOURCE_EXHAUSTED_STATE);
    assertEquals(ConnectivityState.TRANSIENT_FAILURE, transportSet.getState());
    // Second back-off interval begins
    verify(mockBackoffPolicy1, times(++backoff1Consulted)).nextBackoffMillis();
    verify(mockBackoffPolicyProvider, times(backoffReset)).get();

    // Fifth attempt for the first address, thus controlled by the second back-off interval.
    assertEquals(ConnectivityState.TRANSIENT_FAILURE, transportSet.getState());
    fakeClock.forwardMillis(99);
    verify(mockTransportFactory, times(transportsAddr1))
        .newClientTransport(addr1, AUTHORITY, USER_AGENT);
    assertEquals(ConnectivityState.TRANSIENT_FAILURE, transportSet.getState());
    assertNoCallbackInvoke();
    fakeClock.forwardMillis(1);
    assertExactCallbackInvokes("onStateChange:CONNECTING");
    assertEquals(ConnectivityState.CONNECTING, transportSet.getState());
    verify(mockTransportFactory, times(++transportsAddr1))
        .newClientTransport(addr1, AUTHORITY, USER_AGENT);
    // Let it through
    assertNoCallbackInvoke();
    transports.peek().listener.transportReady();
    assertExactCallbackInvokes("onStateChange:READY");
    assertEquals(ConnectivityState.READY, transportSet.getState());

    assertSame(transports.peek().transport, transportSet.obtainActiveTransport());
    // Then close it.
    assertNoCallbackInvoke();
    transports.poll().listener.transportShutdown(Status.UNAVAILABLE);
    assertExactCallbackInvokes("onStateChange:IDLE");
    assertEquals(ConnectivityState.IDLE, transportSet.getState());

    // First attempt after a successful connection. Old back-off policy should be ignored, but there
    // is not yet a need for a new one. Start from the first address.
    assertNull(transportSet.obtainActiveTransport());
    assertEquals(ConnectivityState.CONNECTING, transportSet.getState());
    assertExactCallbackInvokes("onStateChange:CONNECTING");
    verify(mockBackoffPolicyProvider, times(backoffReset)).get();
    verify(mockTransportFactory, times(++transportsAddr1))
        .newClientTransport(addr1, AUTHORITY, USER_AGENT);
    // Fail the transport
    transports.poll().listener.transportShutdown(Status.UNAVAILABLE);
    assertEquals(ConnectivityState.CONNECTING, transportSet.getState());

    // Second attempt will start immediately. Still no new back-off policy.
    verify(mockBackoffPolicyProvider, times(backoffReset)).get();
    verify(mockTransportFactory, times(++transportsAddr2))
        .newClientTransport(addr2, AUTHORITY, USER_AGENT);
    // Fail this one too
    assertEquals(ConnectivityState.CONNECTING, transportSet.getState());
    transports.poll().listener.transportShutdown(Status.UNAVAILABLE);
    // All addresses have failed. Enter TRANSIENT_FAILURE. Back-off in effect.
    assertExactCallbackInvokes("onStateChange:" + UNAVAILABLE_STATE);
    assertEquals(ConnectivityState.TRANSIENT_FAILURE, transportSet.getState());
    // Back-off reset and first back-off interval begins
    verify(mockBackoffPolicy2, times(++backoff2Consulted)).nextBackoffMillis();
    verify(mockBackoffPolicyProvider, times(++backoffReset)).get();

    // Third attempt is the first address, thus controlled by the first back-off interval.
    fakeClock.forwardMillis(9);
    verify(mockTransportFactory, times(transportsAddr1))
        .newClientTransport(addr1, AUTHORITY, USER_AGENT);
    assertEquals(ConnectivityState.TRANSIENT_FAILURE, transportSet.getState());
    assertNoCallbackInvoke();
    fakeClock.forwardMillis(1);
    assertExactCallbackInvokes("onStateChange:CONNECTING");
    assertEquals(ConnectivityState.CONNECTING, transportSet.getState());
    verify(mockTransportFactory, times(++transportsAddr1))
        .newClientTransport(addr1, AUTHORITY, USER_AGENT);

    // Final checks on invocations on back-off policies
    verify(mockBackoffPolicy1, times(backoff1Consulted)).nextBackoffMillis();
    verify(mockBackoffPolicy2, times(backoff2Consulted)).nextBackoffMillis();
    verify(mockBackoffPolicy3, times(backoff3Consulted)).nextBackoffMillis();
  }

  @Test
  public void connectIsLazy() {
    SocketAddress addr = mock(SocketAddress.class);
    createTransportSet(addr);

    // Invocation counters
    int transportsCreated = 0;

    // Won't connect until requested
    verify(mockTransportFactory, times(transportsCreated))
        .newClientTransport(addr, AUTHORITY, USER_AGENT);

    // First attempt
    transportSet.obtainActiveTransport();
    assertExactCallbackInvokes("onStateChange:CONNECTING");
    verify(mockTransportFactory, times(++transportsCreated))
        .newClientTransport(addr, AUTHORITY, USER_AGENT);

    // Fail this one
    transports.poll().listener.transportShutdown(Status.UNAVAILABLE);
    assertExactCallbackInvokes("onStateChange:" + UNAVAILABLE_STATE);

    // Will always reconnect after back-off
    fakeClock.forwardMillis(10);
    assertExactCallbackInvokes("onStateChange:CONNECTING");
    verify(mockTransportFactory, times(++transportsCreated))
        .newClientTransport(addr, AUTHORITY, USER_AGENT);

    // Make this one proceed
    transports.peek().listener.transportReady();
    assertExactCallbackInvokes("onStateChange:READY");
    // Then go-away
    transports.poll().listener.transportShutdown(Status.UNAVAILABLE);
    assertExactCallbackInvokes("onStateChange:IDLE");

    // No scheduled tasks that would ever try to reconnect ...
    assertEquals(0, fakeClock.numPendingTasks());
    assertEquals(0, fakeExecutor.numPendingTasks());
    
    // ... until it's requested.
    transportSet.obtainActiveTransport();
    assertExactCallbackInvokes("onStateChange:CONNECTING");
    verify(mockTransportFactory, times(++transportsCreated))
        .newClientTransport(addr, AUTHORITY, USER_AGENT);
  }

  @Test
  public void shutdownBeforeTransportCreated() throws Exception {
    SocketAddress addr = mock(SocketAddress.class);
    createTransportSet(addr);

    // First transport is created immediately
    transportSet.obtainActiveTransport();
    assertExactCallbackInvokes("onStateChange:CONNECTING");
    verify(mockTransportFactory).newClientTransport(addr, AUTHORITY, USER_AGENT);

    // Fail this one
    MockClientTransportInfo transportInfo = transports.poll();
    transportInfo.listener.transportShutdown(Status.UNAVAILABLE);
    transportInfo.listener.transportTerminated();

    // Entering TRANSIENT_FAILURE, waiting for back-off
    assertExactCallbackInvokes("onStateChange:" + UNAVAILABLE_STATE);

    // Shut down TransportSet before the transport is created.
    transportSet.shutdown();
    // TransportSet terminated promptly.
    assertExactCallbackInvokes("onStateChange:SHUTDOWN", "onTerminated");

    // Futher call to obtainActiveTransport() is no-op.
    assertNull(transportSet.obtainActiveTransport());
    assertEquals(ConnectivityState.SHUTDOWN, transportSet.getState());
    assertNoCallbackInvoke();

    // No more transports will be created.
    fakeClock.forwardMillis(10000);
    assertEquals(ConnectivityState.SHUTDOWN, transportSet.getState());
    verifyNoMoreInteractions(mockTransportFactory);
    assertEquals(0, transports.size());
    assertNoCallbackInvoke();
  }

  @Test
  public void shutdownBeforeTransportReady() throws Exception {
    SocketAddress addr = mock(SocketAddress.class);
    createTransportSet(addr);

    transportSet.obtainActiveTransport();
    assertExactCallbackInvokes("onStateChange:CONNECTING");
    MockClientTransportInfo transportInfo = transports.poll();

    // Shutdown the TransportSet before the pending transport is ready
    assertNull(transportSet.obtainActiveTransport());
    transportSet.shutdown();
    assertExactCallbackInvokes("onStateChange:SHUTDOWN");

    // The transport should've been shut down even though it's not the active transport yet.
    verify(transportInfo.transport).shutdown();
    transportInfo.listener.transportShutdown(Status.UNAVAILABLE);
    assertNoCallbackInvoke();
    transportInfo.listener.transportTerminated();
    assertExactCallbackInvokes("onTerminated");
    assertEquals(ConnectivityState.SHUTDOWN, transportSet.getState());
  }

  @Test
  public void obtainTransportAfterShutdown() throws Exception {
    SocketAddress addr = mock(SocketAddress.class);
    createTransportSet(addr);

    transportSet.shutdown();
    assertExactCallbackInvokes("onStateChange:SHUTDOWN", "onTerminated");
    assertEquals(ConnectivityState.SHUTDOWN, transportSet.getState());
    assertNull(transportSet.obtainActiveTransport());
    verify(mockTransportFactory, times(0)).newClientTransport(addr, AUTHORITY, USER_AGENT);
    assertNoCallbackInvoke();
    assertEquals(ConnectivityState.SHUTDOWN, transportSet.getState());
  }

  @Test
  public void logId() {
    createTransportSet(mock(SocketAddress.class));
    assertEquals("TransportSet2@" + Integer.toHexString(transportSet.hashCode()),
        transportSet.getLogId());
  }

  @Test
  public void inUseState() {
    SocketAddress addr = mock(SocketAddress.class);
    createTransportSet(addr);

    transportSet.obtainActiveTransport();
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

    assertNull(transportSet.obtainActiveTransport());
    MockClientTransportInfo t1 = transports.poll();
    t1.listener.transportReady();
    assertExactCallbackInvokes("onStateChange:CONNECTING", "onStateChange:READY");
    t1.listener.transportInUse(true);
    // TransportSet is already in-use, thus doesn't call the callback
    assertNoCallbackInvoke();

    t1.listener.transportInUse(false);
    // t0 is still in-use
    assertNoCallbackInvoke();

    t0.listener.transportInUse(false);
    assertExactCallbackInvokes("onNotInUse");
  }

  @Test
  public void transportTerminateWithoutExitingInUse() {
    // An imperfect transport that terminates without going out of in-use. TransportSet will
    // clear the in-use bit for it.
    SocketAddress addr = mock(SocketAddress.class);
    createTransportSet(addr);

    transportSet.obtainActiveTransport();
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
    createTransportSet(addr1, addr2);
    final AtomicInteger runnableInvokes = new AtomicInteger(0);
    Runnable startRunnable = new Runnable() {
        @Override
        public void run() {
          runnableInvokes.incrementAndGet();
        }
      };
    transports = TestUtils.captureTransports(mockTransportFactory, startRunnable);

    assertEquals(0, runnableInvokes.get());
    transportSet.obtainActiveTransport();
    assertEquals(1, runnableInvokes.get());
    transportSet.obtainActiveTransport();
    assertEquals(1, runnableInvokes.get());

    MockClientTransportInfo t0 = transports.poll();
    t0.listener.transportShutdown(Status.UNAVAILABLE);
    assertEquals(2, runnableInvokes.get());

    // 2nd address: reconnect immediatly
    MockClientTransportInfo t1 = transports.poll();
    t1.listener.transportShutdown(Status.UNAVAILABLE);

    // Addresses exhausted, waiting for back-off.
    assertEquals(2, runnableInvokes.get());
    // Run out the back-off period
    fakeClock.forwardMillis(10);
    assertEquals(3, runnableInvokes.get());

    // This test doesn't care about scheduled TransportSet callbacks.  Clear it up so that
    // noMorePendingTasks() won't fail.
    fakeExecutor.runDueTasks();
    assertEquals(3, runnableInvokes.get());
  }

  private void createTransportSet(SocketAddress ... addrs) {
    addressGroup = new EquivalentAddressGroup(Arrays.asList(addrs));
    transportSet = new TransportSet2(addressGroup, AUTHORITY, USER_AGENT,
        mockBackoffPolicyProvider, mockTransportFactory, fakeClock.getScheduledExecutorService(),
        fakeClock.getStopwatchSupplier(), channelExecutor, mockTransportSetCallback);
  }

  private void assertNoCallbackInvoke() {
    while (fakeExecutor.runDueTasks() > 0) {}
    assertEquals(0, callbackInvokes.size());
  }

  private void assertExactCallbackInvokes(String ... expectedInvokes) {
    // Make sure all callbacks are to run from channelExecutor only.
    assertEquals(0, callbackInvokes.size());

    while (fakeExecutor.runDueTasks() > 0) {}
    assertEquals(Arrays.asList(expectedInvokes), callbackInvokes);
    callbackInvokes.clear();
  }
}
