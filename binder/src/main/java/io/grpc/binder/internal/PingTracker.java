/*
 * Copyright 2020 The gRPC Authors
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

package io.grpc.binder.internal;

import com.google.common.base.Ticker;
import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.internal.ClientTransport.PingCallback;
import java.util.concurrent.Executor;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;

/**
 * Tracks an ongoing ping request for a client-side binder transport. We only handle a single active
 * ping at a time, since that's all gRPC appears to need.
 */
final class PingTracker {

  interface PingSender {
    /**
     * Send a ping to the remote endpoint. We expect a subsequent call to {@link #onPingResponse}
     * with the same ID (assuming the ping succeeds).
     */
    void sendPing(int id) throws StatusException;
  }

  private final Ticker ticker;
  private final PingSender pingSender;

  @GuardedBy("this")
  @Nullable
  private Ping pendingPing;

  @GuardedBy("this")
  private int nextPingId;

  PingTracker(Ticker ticker, PingSender pingSender) {
    this.ticker = ticker;
    this.pingSender = pingSender;
  }

  /**
   * Start a ping.
   *
   * <p>See also {@link ClientTransport#ping}.
   *
   * @param callback The callback to report the ping result on.
   * @param executor An executor to call callbacks on.
   *     <p>Note that only one ping callback will be active at a time.
   */
  synchronized void startPing(PingCallback callback, Executor executor) {
    pendingPing = new Ping(callback, executor, nextPingId++);
    try {
      pingSender.sendPing(pendingPing.id);
    } catch (StatusException se) {
      pendingPing.fail(se.getStatus());
      pendingPing = null;
    }
  }

  /** Callback when a ping response with the given ID is received. */
  synchronized void onPingResponse(int id) {
    if (pendingPing != null && pendingPing.id == id) {
      pendingPing.success();
      pendingPing = null;
    }
  }

  private final class Ping {
    private final PingCallback callback;
    private final Executor executor;
    private final int id;
    private final long startTimeNanos;

    @GuardedBy("this")
    private boolean done;

    Ping(PingCallback callback, Executor executor, int id) {
      this.callback = callback;
      this.executor = executor;
      this.id = id;
      this.startTimeNanos = ticker.read();
    }

    private synchronized void fail(Status status) {
      if (!done) {
        done = true;
        executor.execute(() -> callback.onFailure(status.asException()));
      }
    }

    private synchronized void success() {
      if (!done) {
        done = true;
        executor.execute(
            () -> callback.onSuccess(ticker.read() - startTimeNanos));
      }
    }
  }
}
