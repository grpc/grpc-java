/*
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.grpc.internal;

import static org.junit.Assert.assertEquals;
import static org.mockito.Matchers.isA;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.grpc.Status;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(JUnit4.class)
public final class KeepAliveManagerTest {
  private final FakeTicker ticker = new FakeTicker();
  private KeepAliveManager keepAliveManager;
  @Mock private ManagedClientTransport transport;
  @Mock private ScheduledExecutorService scheduler;

  static class FakeTicker extends KeepAliveManager.Ticker {
    long time;

    @Override
    public long read() {
      return time;
    }
  }

  @Before
  public void setUp() {
    MockitoAnnotations.initMocks(this);
    keepAliveManager = new KeepAliveManager(transport, scheduler, ticker, 1000, 2000);
  }

  @Test
  public void sendKeepAlivePings() {
    ticker.time = 1;
    // Transport becomes active. We should schedule keepalive pings.
    keepAliveManager.onTransportActive();
    ArgumentCaptor<Long> delayCaptor = ArgumentCaptor.forClass(Long.class);
    ArgumentCaptor<Runnable> sendPingCaptor = ArgumentCaptor.forClass(Runnable.class);
    verify(scheduler, times(1)).schedule(sendPingCaptor.capture(), delayCaptor.capture(),
        isA(TimeUnit.class));
    Runnable sendPing = sendPingCaptor.getValue();
    Long delay = delayCaptor.getValue();
    assertEquals(1000 - 1, delay.longValue());

    ScheduledFuture<?> shutdownFuture = mock(ScheduledFuture.class);
    doReturn(shutdownFuture)
        .when(scheduler).schedule(isA(Runnable.class), isA(Long.class), isA(TimeUnit.class));
    // Mannually running the Runnable will send the ping. Shutdown task should be scheduled.
    ticker.time = 1000;
    sendPing.run();
    ArgumentCaptor<ClientTransport.PingCallback> pingCallbackCaptor =
        ArgumentCaptor.forClass(ClientTransport.PingCallback.class);
    verify(transport).ping(pingCallbackCaptor.capture(), isA(Executor.class));
    ClientTransport.PingCallback pingCallback = pingCallbackCaptor.getValue();
    verify(scheduler, times(2)).schedule(isA(Runnable.class), delayCaptor.capture(),
        isA(TimeUnit.class));
    delay = delayCaptor.getValue();
    // Keepalive timeout is 2000.
    assertEquals(2000, delay.longValue());

    // Ping succeeds. Reschedule another ping.
    ticker.time = 1100;
    pingCallback.onSuccess(100);
    verify(scheduler, times(3)).schedule(isA(Runnable.class), delayCaptor.capture(),
        isA(TimeUnit.class));
    // Shutdown task has been cancelled.
    verify(shutdownFuture).cancel(isA(Boolean.class));
    delay = delayCaptor.getValue();
    // Next ping should be exactly 1000 nanoseconds later.
    assertEquals(1000, delay.longValue());
  }

  @Test
  public void keepAlivePingDelayedByIncomingData() {
    // Transport becomes active. We should schedule keepalive pings.
    keepAliveManager.onTransportActive();
    ArgumentCaptor<Runnable> sendPingCaptor = ArgumentCaptor.forClass(Runnable.class);
    verify(scheduler, times(1)).schedule(sendPingCaptor.capture(), isA(Long.class),
        isA(TimeUnit.class));
    Runnable sendPing = sendPingCaptor.getValue();

    // We receive some data. We may need to delay the ping.
    ticker.time = 1500;
    keepAliveManager.onDataReceived();
    ticker.time = 1600;
    sendPing.run();
    // We didn't send the ping.
    verify(transport, times(0)).ping(isA(ClientTransport.PingCallback.class),
        isA(Executor.class));
    // Instead we reschedule.
    ArgumentCaptor<Long> delayCaptor = ArgumentCaptor.forClass(Long.class);
    verify(scheduler, times(2)).schedule(isA(Runnable.class), delayCaptor.capture(),
        isA(TimeUnit.class));
    Long delay = delayCaptor.getValue();
    assertEquals(1500 + 1000 - 1600, delay.longValue());
  }

  @Test
  public void keepAlivePingFails() {
    // Transport becomes active. We should schedule keepalive pings.
    keepAliveManager.onTransportActive();
    ArgumentCaptor<Runnable> sendPingCaptor = ArgumentCaptor.forClass(Runnable.class);
    verify(scheduler, times(1)).schedule(sendPingCaptor.capture(), isA(Long.class),
        isA(TimeUnit.class));
    Runnable sendPing = sendPingCaptor.getValue();

    ScheduledFuture<?> shutdownFuture = mock(ScheduledFuture.class);
    doReturn(shutdownFuture)
        .when(scheduler).schedule(isA(Runnable.class), isA(Long.class), isA(TimeUnit.class));
    // Mannually running the Runnable will send the ping. Shutdown task should be scheduled.
    ticker.time = 1000;
    sendPing.run();
    ArgumentCaptor<ClientTransport.PingCallback> pingCallbackCaptor =
        ArgumentCaptor.forClass(ClientTransport.PingCallback.class);
    verify(transport).ping(pingCallbackCaptor.capture(), isA(Executor.class));
    ClientTransport.PingCallback pingCallback = pingCallbackCaptor.getValue();
    verify(scheduler, times(2)).schedule(isA(Runnable.class), isA(Long.class),
        isA(TimeUnit.class));

    // Ping fails. Shutdown the transport.
    ticker.time = 1100;
    pingCallback.onFailure(new Throwable());
    // Shutdown task has been cancelled.
    verify(shutdownFuture).cancel(isA(Boolean.class));
    ArgumentCaptor<Status> statusCaptor = ArgumentCaptor.forClass(Status.class);
    verify(transport).shutdownNow(statusCaptor.capture());
    Status status = statusCaptor.getValue();
    assertEquals(Status.UNAVAILABLE.getCode(), status.getCode());
  }

  @Test
  public void keepAlivePingFailsAfterItTimesOut() {
    // Transport becomes active. We should schedule keepalive pings.
    keepAliveManager.onTransportActive();
    ArgumentCaptor<Runnable> sendPingCaptor = ArgumentCaptor.forClass(Runnable.class);
    verify(scheduler, times(1)).schedule(sendPingCaptor.capture(), isA(Long.class),
        isA(TimeUnit.class));
    Runnable sendPing = sendPingCaptor.getValue();

    ScheduledFuture<?> shutdownFuture = mock(ScheduledFuture.class);
    doReturn(shutdownFuture)
        .when(scheduler).schedule(isA(Runnable.class), isA(Long.class), isA(TimeUnit.class));
    // Mannually running the Runnable will send the ping. Shutdown task should be scheduled.
    ticker.time = 1000;
    sendPing.run();
    ArgumentCaptor<ClientTransport.PingCallback> pingCallbackCaptor =
        ArgumentCaptor.forClass(ClientTransport.PingCallback.class);
    verify(transport).ping(pingCallbackCaptor.capture(), isA(Executor.class));
    ClientTransport.PingCallback pingCallback = pingCallbackCaptor.getValue();
    ArgumentCaptor<Runnable> shutdownCaptor = ArgumentCaptor.forClass(Runnable.class);
    verify(scheduler, times(2)).schedule(shutdownCaptor.capture(), isA(Long.class),
        isA(TimeUnit.class));
    Runnable shutdown = shutdownCaptor.getValue();

    // We do not receive the ping response. Shutdown runnable runs.
    ticker.time = 3000;
    shutdown.run();
    verify(transport).shutdownNow(isA(Status.class));

    // We receive the ping error after we shutdown the transport.
    pingCallback.onFailure(new Throwable());
    // We should not shutdown transport again.
    verify(transport, times(1)).shutdownNow(isA(Status.class));
  }

  @Test
  public void keepAlivePingTimesOut() {
    // Transport becomes active. We should schedule keepalive pings.
    keepAliveManager.onTransportActive();
    ArgumentCaptor<Runnable> sendPingCaptor = ArgumentCaptor.forClass(Runnable.class);
    verify(scheduler, times(1)).schedule(sendPingCaptor.capture(), isA(Long.class),
        isA(TimeUnit.class));
    Runnable sendPing = sendPingCaptor.getValue();

    ScheduledFuture<?> shutdownFuture = mock(ScheduledFuture.class);
    doReturn(shutdownFuture)
        .when(scheduler).schedule(isA(Runnable.class), isA(Long.class), isA(TimeUnit.class));
    // Mannually running the Runnable will send the ping. Shutdown task should be scheduled.
    ticker.time = 1000;
    sendPing.run();
    ArgumentCaptor<ClientTransport.PingCallback> pingCallbackCaptor =
        ArgumentCaptor.forClass(ClientTransport.PingCallback.class);
    verify(transport).ping(pingCallbackCaptor.capture(), isA(Executor.class));
    ClientTransport.PingCallback pingCallback = pingCallbackCaptor.getValue();
    ArgumentCaptor<Runnable> shutdownCaptor = ArgumentCaptor.forClass(Runnable.class);
    verify(scheduler, times(2)).schedule(shutdownCaptor.capture(), isA(Long.class),
        isA(TimeUnit.class));
    Runnable shutdown = shutdownCaptor.getValue();

    // We do not receive the ping response. Shutdown runnable runs.
    ticker.time = 3000;
    shutdown.run();
    verify(transport).shutdownNow(isA(Status.class));

    // We receive the ping response too late.
    pingCallback.onSuccess(3000);
    // No more ping should be scheduled.
    verify(scheduler, times(2)).schedule(isA(Runnable.class), isA(Long.class),
        isA(TimeUnit.class));
  }

  @Test
  public void transportGoesIdle() {
    // Transport becomes active. We should schedule keepalive pings.
    keepAliveManager.onTransportActive();
    ArgumentCaptor<Runnable> sendPingCaptor = ArgumentCaptor.forClass(Runnable.class);
    verify(scheduler, times(1)).schedule(sendPingCaptor.capture(), isA(Long.class),
        isA(TimeUnit.class));
    Runnable sendPing = sendPingCaptor.getValue();

    // Transport becomes idle. Nothing should happen when ping runnable runs.
    keepAliveManager.onTransportIdle();
    sendPing.run();
    // Ping was not sent.
    verify(transport, times(0)).ping(isA(ClientTransport.PingCallback.class),
        isA(Executor.class));
    // No new ping got scheduled.
    verify(scheduler, times(1)).schedule(isA(Runnable.class), isA(Long.class), isA(TimeUnit.class));
  }

  @Test
  public void transportGoesIdleAfterPingSent() {
    // Transport becomes active. We should schedule keepalive pings.
    keepAliveManager.onTransportActive();
    ArgumentCaptor<Runnable> sendPingCaptor = ArgumentCaptor.forClass(Runnable.class);
    verify(scheduler, times(1)).schedule(sendPingCaptor.capture(), isA(Long.class),
        isA(TimeUnit.class));
    Runnable sendPing = sendPingCaptor.getValue();

    ScheduledFuture<?> shutdownFuture = mock(ScheduledFuture.class);
    doReturn(shutdownFuture)
        .when(scheduler).schedule(isA(Runnable.class), isA(Long.class), isA(TimeUnit.class));
    // Mannually running the Runnable will send the ping. Shutdown task should be scheduled.
    ticker.time = 1000;
    sendPing.run();
    ArgumentCaptor<ClientTransport.PingCallback> pingCallbackCaptor =
        ArgumentCaptor.forClass(ClientTransport.PingCallback.class);
    verify(transport).ping(pingCallbackCaptor.capture(), isA(Executor.class));
    ClientTransport.PingCallback pingCallback = pingCallbackCaptor.getValue();
    verify(scheduler, times(2)).schedule(isA(Runnable.class), isA(Long.class), isA(TimeUnit.class));

    // Transport becomes idle. No more ping should be scheduled after we receive a ping response.
    keepAliveManager.onTransportIdle();
    ticker.time = 1100;
    pingCallback.onSuccess(100);
    verify(scheduler, times(2)).schedule(isA(Runnable.class), isA(Long.class), isA(TimeUnit.class));
    // Shutdown task has been cancelled.
    verify(shutdownFuture).cancel(isA(Boolean.class));

    // Transport becomes active again. Another ping is scheduled.
    keepAliveManager.onTransportActive();
    verify(scheduler, times(3)).schedule(isA(Runnable.class), isA(Long.class), isA(TimeUnit.class));
  }

  @Test
  public void transportGoesIdleAndPingFails() {
    // Transport becomes active. We should schedule keepalive pings.
    keepAliveManager.onTransportActive();
    ArgumentCaptor<Runnable> sendPingCaptor = ArgumentCaptor.forClass(Runnable.class);
    verify(scheduler, times(1)).schedule(sendPingCaptor.capture(), isA(Long.class),
        isA(TimeUnit.class));
    Runnable sendPing = sendPingCaptor.getValue();

    ScheduledFuture<?> shutdownFuture = mock(ScheduledFuture.class);
    doReturn(shutdownFuture)
        .when(scheduler).schedule(isA(Runnable.class), isA(Long.class), isA(TimeUnit.class));
    // Mannually running the Runnable will send the ping. Shutdown task should be scheduled.
    ticker.time = 1000;
    sendPing.run();
    ArgumentCaptor<ClientTransport.PingCallback> pingCallbackCaptor =
        ArgumentCaptor.forClass(ClientTransport.PingCallback.class);
    verify(transport).ping(pingCallbackCaptor.capture(), isA(Executor.class));
    ClientTransport.PingCallback pingCallback = pingCallbackCaptor.getValue();
    verify(scheduler, times(2)).schedule(isA(Runnable.class), isA(Long.class),
        isA(TimeUnit.class));

    // Transport becomes idle. It does not stop the failed ping from shutting down the transport.
    keepAliveManager.onTransportIdle();
    // Ping fails. Shutdown the transport.
    ticker.time = 1100;
    pingCallback.onFailure(new Throwable());
    // Shutdown task has been cancelled.
    verify(shutdownFuture).cancel(isA(Boolean.class));
    ArgumentCaptor<Status> statusCaptor = ArgumentCaptor.forClass(Status.class);
    verify(transport).shutdownNow(statusCaptor.capture());
    Status status = statusCaptor.getValue();
    assertEquals(Status.UNAVAILABLE.getCode(), status.getCode());
  }

  @Test
  public void transportShutsdownAfterPingScheduled() {
    ScheduledFuture<?> pingFuture = mock(ScheduledFuture.class);
    doReturn(pingFuture)
        .when(scheduler).schedule(isA(Runnable.class), isA(Long.class), isA(TimeUnit.class));
    // Ping will be scheduled.
    keepAliveManager.onTransportActive();
    verify(scheduler, times(1)).schedule(isA(Runnable.class), isA(Long.class),
        isA(TimeUnit.class));
    // Transport is shutting down.
    keepAliveManager.onTransportShutdown();
    // Ping future should have been cancelled.
    verify(pingFuture).cancel(isA(Boolean.class));
  }

  @Test
  public void transportShutsdownAfterPingSent() {
    keepAliveManager.onTransportActive();
    ArgumentCaptor<Runnable> sendPingCaptor = ArgumentCaptor.forClass(Runnable.class);
    verify(scheduler, times(1)).schedule(sendPingCaptor.capture(), isA(Long.class),
        isA(TimeUnit.class));
    Runnable sendPing = sendPingCaptor.getValue();

    ScheduledFuture<?> shutdownFuture = mock(ScheduledFuture.class);
    doReturn(shutdownFuture)
        .when(scheduler).schedule(isA(Runnable.class), isA(Long.class), isA(TimeUnit.class));
    // Mannually running the Runnable will send the ping. Shutdown task should be scheduled.
    ticker.time = 1000;
    sendPing.run();
    ArgumentCaptor<ClientTransport.PingCallback> pingCallbackCaptor =
        ArgumentCaptor.forClass(ClientTransport.PingCallback.class);
    verify(transport).ping(pingCallbackCaptor.capture(), isA(Executor.class));
    verify(scheduler, times(2)).schedule(isA(Runnable.class), isA(Long.class),
        isA(TimeUnit.class));

    // Transport is shutting down.
    keepAliveManager.onTransportShutdown();
    // Shutdown task has been cancelled.
    verify(shutdownFuture).cancel(isA(Boolean.class));

    ClientTransport.PingCallback pingCallback = pingCallbackCaptor.getValue();
    // Ping fails after transport shutting down. We will not shutdown the transport again.
    pingCallback.onFailure(new Throwable());
    verify(transport, times(0)).shutdownNow(isA(Status.class));
  }
}

