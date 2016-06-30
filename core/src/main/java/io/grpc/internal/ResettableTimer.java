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

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.base.Stopwatch;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;

/**
 * A timer that is optimized for being reset frequently.
 *
 * <p>When a scheduled timer is cancelled or reset, it doesn't cancel the task from the scheduled
 * executor. Instead, when the task is run, it checks the current state and may schedule a new task
 * for the new expiration time.
 */
@ThreadSafe
abstract class ResettableTimer {
  private final long timeoutNanos;
  private final ScheduledExecutorService executor;
  private final Stopwatch stopwatch;

  private final Runnable runnable = new LogExceptionRunnable(new Runnable() {
      @Override
      public void run() {
        synchronized (getLock()) {
          scheduledTask = null;
          if (!stopwatch.isRunning()) {
            return;
          }
          long leftNanos = timeoutNanos - stopwatch.elapsed(TimeUnit.NANOSECONDS);
          if (leftNanos > 0) {
            scheduledTask = executor.schedule(runnable, leftNanos, TimeUnit.NANOSECONDS);
          } else {
            timerExpired();
          }
        }
      }

      @Override
      public String toString() {
        return ResettableTimer.this.toString();
      }
    });

  @GuardedBy("getLock()")
  @Nullable
  private ScheduledFuture<?> scheduledTask;

  protected ResettableTimer(long timeout, TimeUnit unit, ScheduledExecutorService executor,
      Stopwatch stopwatch) {
    this.timeoutNanos = unit.toNanos(timeout);
    this.executor = checkNotNull(executor);
    this.stopwatch = checkNotNull(stopwatch);
  }

  abstract Object getLock();

  /**
   * Handler to run when timer expired.
   *
   * <p>It doesn't restart the timer automatically.
   */
  @GuardedBy("getLock()")
  abstract void timerExpired();

  /**
   * Reset the timer and start it. {@link #timerExpired} will be run after timeout from now unless
   * {@link #resetAndStart} or {@link #stop} is called before that.
   */
  final void resetAndStart() {
    synchronized (getLock()) {
      stopwatch.reset().start();
      if (scheduledTask == null) {
        scheduledTask = executor.schedule(runnable, timeoutNanos, TimeUnit.NANOSECONDS);
      }
    }
  }

  /**
   * Stop the timer.
   */
  final void stop() {
    synchronized (getLock()) {
      if (scheduledTask != null) {
        stopwatch.stop();
      }
    }
  }
}
