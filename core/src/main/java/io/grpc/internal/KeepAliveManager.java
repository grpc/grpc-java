/*
 * Copyright 2016, Google Inc. All rights reserved.
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

import static com.google.common.base.Preconditions.checkState;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.MoreExecutors;
import io.grpc.Status;
import io.grpc.internal.ClientTransport.PingCallback;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Manages keepalive pings.
 */
public class KeepAliveManager {
  private static final SystemTicker SYSTEM_TICKER = new SystemTicker();
  private static final long MIN_KEEPALIVE_DELAY_NANOS = TimeUnit.MINUTES.toNanos(1);

  private final ScheduledExecutorService scheduler;
  private final KeepAlivePinger keepAlivePinger;
  private final Ticker ticker;
  private final boolean noPingWhenIdle;
  private State state;
  private boolean isIdle = true;
  private boolean isShutdown;
  private long nextKeepAliveTime;
  private ScheduledFuture<?> shutdownFuture;
  private ScheduledFuture<?> pingFuture;
  private final Runnable shutdown = new Runnable() {
    @Override
    public void run() {
      keepAlivePinger.onKeepAliveTimeout();
    }
  };
  private final Runnable sendPing = new Runnable() {
    @Override
    public void run() {
      boolean shouldSendPing = false;
      synchronized (KeepAliveManager.this) {
        if (isShutdown) {
          return;
        }
        checkState(state != State.PING_SENT, "Keep-alive in weird state.");
        if (isIdle && noPingWhenIdle) {
          // delay ping indefinitely until onTransportActive
          state = State.PING_DELAYED;
          pingFuture = null;
          return;
        }
        if (state == State.PING_SCHEDULED) {
          shouldSendPing = true;
          state = State.PING_SENT;
          // Schedule a shutdown. It fires if we don't receive the ping response within the timeout.
          shutdownFuture =
              scheduler.schedule(shutdown, keepAliveTimeoutInNanos, TimeUnit.NANOSECONDS);
        } else if (state == State.PING_DELAYED) {
          // We have received some data. Reschedule the ping with the new time.
          pingFuture = scheduler
              .schedule(sendPing, nextKeepAliveTime - ticker.read(), TimeUnit.NANOSECONDS);
          state = State.PING_SCHEDULED;
        }
      }
      if (shouldSendPing) {
        keepAlivePinger.ping();
      }
    }
  };
  private long keepAliveDelayInNanos;
  private long keepAliveTimeoutInNanos;

  private enum State {
    /*
     * We have scheduled a ping to be sent in the future. We may decide to delay it if we receive
     * some data.
     */
    PING_SCHEDULED,
    /*
     * We need to delay the scheduled keepalive ping, or we do not want to schedule any ping during
     * idle.
     */
    PING_DELAYED,
    /*
     * The ping has been sent out. Waiting for a ping response or any inbound data.
     */
    PING_SENT,
  }

  /**
   * Creates a KeepAliverManager.
   */
  public KeepAliveManager(
      KeepAlivePinger keepAlivePinger, ScheduledExecutorService scheduler,
      long keepAliveDelayInNanos, long keepAliveTimeoutInNanos) {
    this(keepAlivePinger, scheduler, SYSTEM_TICKER, keepAliveDelayInNanos, keepAliveTimeoutInNanos);
    // Set a minimum cap on keepalive dealy.
    this.keepAliveDelayInNanos = Math.max(MIN_KEEPALIVE_DELAY_NANOS, keepAliveDelayInNanos);
  }

  @VisibleForTesting
  KeepAliveManager(
      KeepAlivePinger keepAlivePinger, ScheduledExecutorService scheduler, Ticker ticker,
      long keepAliveDelayInNanos, long keepAliveTimeoutInNanos) {
    this.keepAlivePinger = Preconditions.checkNotNull(keepAlivePinger, "transport");
    this.scheduler = Preconditions.checkNotNull(scheduler, "scheduler");
    this.ticker = Preconditions.checkNotNull(ticker, "ticker");
    this.keepAliveDelayInNanos = keepAliveDelayInNanos;
    this.keepAliveTimeoutInNanos = keepAliveTimeoutInNanos;
    this.noPingWhenIdle = keepAlivePinger.isNoPingWhenIdle();
    nextKeepAliveTime = ticker.read() + keepAliveDelayInNanos;
  }

  /**
   * Transport has received some data so that we can delay sending keepalives.
   */
  public synchronized void onDataReceived() {
    if (isShutdown) {
      return;
    }
    nextKeepAliveTime = ticker.read() + keepAliveDelayInNanos;
    // We do not cancel the ping future here. This avoids constantly scheduling and cancellation in
    // a busy transport. Instead, we update the status here and reschedule later. So we actually
    // keep one sendPing task always in flight when there're active rpcs.
    if (state == State.PING_SCHEDULED) {
      state = State.PING_DELAYED;
    } else if (state == State.PING_SENT) {
      // Ping acked or effectively ping acked. Cancel shutdown, and then if noPingWhenIdle does not
      // apply, schedule a new keep-alive ping.
      if (shutdownFuture != null) {
        shutdownFuture.cancel(false);
      }
      if (isIdle && noPingWhenIdle) {
        // delay ping indefinitely until onTransportActive
        state = State.PING_DELAYED;
        pingFuture = null;
        return;
      }
      // schedule a new ping
      state = State.PING_SCHEDULED;
      pingFuture =
          scheduler.schedule(sendPing, nextKeepAliveTime - ticker.read(), TimeUnit.NANOSECONDS);
    }
  }

  /**
   * Transport has active streams. Start sending keepalives if necessary.
   */
  public synchronized void onTransportActive() {
    checkState(isIdle, "Keep-alive in weird state.");
    isIdle = false;
    if (isShutdown) {
      return;
    }

    // If a ping was just sent, schedule new keep-alive in onDataReceived(), not here.

    if (pingFuture == null) {
      // pingFuture here could be null either at initialization, or when noPingWhenIdle is true.
      // As no outstanding ping or pingFuture, it is necessary to schedule a new keep-alive ping.

      // When the transport goes active, we do not reset the nextKeepaliveTime. This allows us to
      // quickly check whether the conneciton is still working.
      state = State.PING_SCHEDULED;
      pingFuture =
          scheduler.schedule(sendPing, nextKeepAliveTime - ticker.read(), TimeUnit.NANOSECONDS);
    }
  }

  /**
   * Transport has finished all streams.
   */
  public synchronized void onTransportIdle() {
    checkState(!isIdle, "Keep-alive in weird state.");
    isIdle = true;
  }

  /**
   * Transport is shutting down. We no longer need to do keepalives.
   */
  public synchronized void onTransportShutdown() {
    if (isShutdown) {
      return;
    }
    isShutdown = true;
    if (shutdownFuture != null) {
      shutdownFuture.cancel(false);
      shutdownFuture = null;
    }
    if (pingFuture != null) {
      pingFuture.cancel(false);
      pingFuture = null;
    }
  }

  // TODO(zsurocking): Classes below are copied from Deadline.java. We should consider share the
  // code.

  /** Time source representing nanoseconds since fixed but arbitrary point in time. */
  abstract static class Ticker {
    /** Returns the number of nanoseconds since this source's epoch. */
    public abstract long read();
  }

  private static class SystemTicker extends Ticker {
    @Override
    public long read() {
      return System.nanoTime();
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
    void onKeepAliveTimeout();

    /**
     * Specifies whether not to send keep-alive pings when the transport has no active RPCs.
     */
    boolean isNoPingWhenIdle();
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
      transport.ping(new PingCallback() {
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
    public void onKeepAliveTimeout() {
      transport.shutdownNow(Status.UNAVAILABLE.withDescription(
          "Keepalive failed. The connection is likely gone"));
    }

    @Override
    public boolean isNoPingWhenIdle() {
      return true;
    }
  }
}

