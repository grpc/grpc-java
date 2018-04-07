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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Stopwatch;
import com.google.common.util.concurrent.MoreExecutors;
import io.grpc.Status;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Manages keepalive pings.
 */
public class KeepAliveManager {
  private static final long MIN_KEEPALIVE_TIME_NANOS = TimeUnit.SECONDS.toNanos(10);
  private static final long MIN_KEEPALIVE_TIMEOUT_NANOS = TimeUnit.MILLISECONDS.toNanos(10L);

  private final KeepAlivePinger keepAlivePinger;
  private final ScheduledExecutorService scheduler;
  private final long keepAliveTimeInNanos;
  private final long keepAliveTimeoutInNanos;
  private final boolean keepAliveDuringTransportIdle;
  private final Rescheduler sendPingRescheduler;

  private State state = State.IDLE;
  /** Whether a ping was suppressed because the channel was inactive. */
  private boolean pingSuppressed;
  private ScheduledFuture<?> shutdownFuture;
  private final Runnable shutdown = new LogExceptionRunnable(new Runnable() {
    @Override
    public void run() {
      boolean shouldShutdown = false;
      synchronized (KeepAliveManager.this) {
        if (state != State.DISCONNECTED) {
          // We haven't received a ping response within the timeout. The connection is likely gone
          // already. Shutdown the transport and fail all existing rpcs.
          state = State.DISCONNECTED;
          shouldShutdown = true;
        }
      }
      if (shouldShutdown) {
        keepAlivePinger.onPingTimeout();
      }
    }
  });

  private enum State {
    /*
     * We don't need to do any keepalives. This means the transport has no active rpcs and
     * keepAliveDuringTransportIdle == false.
     */
    IDLE,
    /*
     * We have scheduled a ping to be sent in the future. We may decide to delay it if we receive
     * some data.
     */
    PING_SCHEDULED,
    /*
     * The ping has been sent out. Waiting for a ping response.
     */
    PING_SENT,
    /*
     * Transport goes idle after ping has been sent.
     */
    IDLE_AND_PING_SENT,
    /*
     * The transport has been disconnected. We won't do keepalives any more.
     */
    DISCONNECTED,
  }

  /**
   * Creates a KeepAliverManager.
   */
  public KeepAliveManager(KeepAlivePinger keepAlivePinger, ScheduledExecutorService scheduler,
                          long keepAliveTimeInNanos, long keepAliveTimeoutInNanos,
                          boolean keepAliveDuringTransportIdle) {
    this(keepAlivePinger, scheduler, Stopwatch.createUnstarted(), keepAliveTimeInNanos,
        keepAliveTimeoutInNanos, keepAliveDuringTransportIdle);
  }

  @VisibleForTesting
  KeepAliveManager(KeepAlivePinger keepAlivePinger, ScheduledExecutorService scheduler,
                   Stopwatch stopwatch, long keepAliveTimeInNanos, long keepAliveTimeoutInNanos,
                   boolean keepAliveDuringTransportIdle) {
    this.keepAlivePinger = checkNotNull(keepAlivePinger, "keepAlivePinger");
    this.scheduler = checkNotNull(scheduler, "scheduler");
    this.keepAliveTimeInNanos = keepAliveTimeInNanos;
    this.keepAliveTimeoutInNanos = keepAliveTimeoutInNanos;
    this.keepAliveDuringTransportIdle = keepAliveDuringTransportIdle;
    Runnable sendPing = new LogExceptionRunnable(new Runnable() {
      @Override public void run() {
        sendPing();
      }
    });
    this.sendPingRescheduler = new Rescheduler(
        sendPing, new SynchronizedDirectExecutor(), scheduler, stopwatch);
  }

  /** Start keepalive monitoring. */
  public synchronized void onTransportStarted() {
    sendPingRescheduler.reschedule(keepAliveTimeInNanos, TimeUnit.NANOSECONDS);
    if (keepAliveDuringTransportIdle) {
      onTransportActive();
    }
  }

  /**
   * Transport has received some data so that we can delay sending keepalives.
   */
  public synchronized void onDataReceived() {
    if (state == State.PING_SCHEDULED) {
      sendPingRescheduler.reschedule(keepAliveTimeInNanos, TimeUnit.NANOSECONDS);
    } else if (state == State.PING_SENT || state == State.IDLE_AND_PING_SENT) {
      // Ping acked or effectively ping acked.
      if (shutdownFuture != null) {
        shutdownFuture.cancel(false);
      }
      if (state == State.IDLE_AND_PING_SENT) {
        state = State.IDLE;
      } else {
        state = State.PING_SCHEDULED;
      }
      sendPingRescheduler.reschedule(keepAliveTimeInNanos, TimeUnit.NANOSECONDS);
    }
  }

  /**
   * Transport has active streams. Start sending keepalives if necessary.
   */
  public synchronized void onTransportActive() {
    if (state == State.IDLE) {
      // When the transport goes active, we do not reset the ping rescheduler. This allows us to
      // quickly check whether the connection is still working.
      state = State.PING_SCHEDULED;
      if (pingSuppressed) {
        pingSuppressed = false;
        sendPing();
      }
    } else if (state == State.IDLE_AND_PING_SENT) {
      state = State.PING_SENT;
    } // Other states are possible when keepAliveDuringTransportIdle == true
  }

  /**
   * Transport has finished all streams.
   */
  public synchronized void onTransportIdle() {
    if (keepAliveDuringTransportIdle) {
      return;
    }
    if (state == State.PING_SCHEDULED) {
      state = State.IDLE;
    }
    if (state == State.PING_SENT) {
      state = State.IDLE_AND_PING_SENT;
    }
  }

  /**
   * Transport is being terminated. We no longer need to do keepalives.
   */
  public synchronized void onTransportTermination() {
    if (state != State.DISCONNECTED) {
      state = State.DISCONNECTED;
      if (shutdownFuture != null) {
        shutdownFuture.cancel(false);
      }
      sendPingRescheduler.cancel(true);
    }
  }

  /**
   * Timer for keepalive expired; proceed to send a ping. Does not actually send a ping if in IDLE
   * state.
   */
  private synchronized void sendPing() {
    // Note that this method is generally called with the lock already held
    if (state == State.PING_SCHEDULED) {
      state = State.PING_SENT;
      // Avoid calling the keepAlivePinger with lock held
      scheduler.execute(new LogExceptionRunnable(new Runnable() {
        @Override public void run() {
          // Send the ping.
          keepAlivePinger.ping();
        }
      }));
      // Schedule a shutdown. It fires if we don't receive the ping response within the timeout.
      shutdownFuture = scheduler.schedule(shutdown, keepAliveTimeoutInNanos,
          TimeUnit.NANOSECONDS);
    } else if (state == State.IDLE) {
      pingSuppressed = true;
    }
  }

  /**
   * Bumps keepalive time to 10 seconds if the specified value was smaller than that.
   */
  public static long clampKeepAliveTimeInNanos(long keepAliveTimeInNanos) {
    return Math.max(keepAliveTimeInNanos, MIN_KEEPALIVE_TIME_NANOS);
  }

  /**
   * Bumps keepalive timeout to 10 milliseconds if the specified value was smaller than that.
   */
  public static long clampKeepAliveTimeoutInNanos(long keepAliveTimeoutInNanos) {
    return Math.max(keepAliveTimeoutInNanos, MIN_KEEPALIVE_TIMEOUT_NANOS);
  }

  private class SynchronizedDirectExecutor implements Executor {
    @Override public void execute(Runnable run) {
      synchronized (KeepAliveManager.this) {
        run.run();
      }
    }
  }

  public interface KeepAlivePinger {
    /**
     * Sends out a keep-alive ping.
     */
    void ping();

    /**
     * Callback when Ping Ack was not received in KEEPALIVE_TIMEOUT. Should shutdown the transport.
     */
    void onPingTimeout();
  }

  /**
   * Default client side {@link KeepAlivePinger}.
   */
  public static final class ClientKeepAlivePinger implements KeepAlivePinger {
    private final ConnectionClientTransport transport;

    public ClientKeepAlivePinger(ConnectionClientTransport transport) {
      this.transport = transport;
    }

    @Override
    public void ping() {
      transport.ping(new ClientTransport.PingCallback() {
        @Override
        public void onSuccess(long roundTripTimeNanos) {}

        @Override
        public void onFailure(Throwable cause) {
          transport.shutdownNow(Status.UNAVAILABLE.withDescription(
              "Keepalive failed. The connection is likely gone"));
        }
      }, MoreExecutors.directExecutor());
    }

    @Override
    public void onPingTimeout() {
      transport.shutdownNow(Status.UNAVAILABLE.withDescription(
          "Keepalive failed. The connection is likely gone"));
    }
  }
}

