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
import static com.google.common.base.Preconditions.checkState;

import com.google.common.base.Stopwatch;

import java.util.concurrent.ScheduledExecutorService;
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
 *
 * <h3>Threading considerations</h3>
 *
 * <p>The callback method {@link #timerExpired} is not called under any lock, which makes it
 * possible for {@link #stop} or {@link #resetAndStart} to proceed in the middle of {@link
 * #timerExpired}. In some cases, you may want to use {@link TimerState} to decide whether the run
 * should proceed.
 *
 * <p>For example, consider we want to bookkeep the idleness of a system. If {@code onActive()} has
 * not been called for TIMEOUT, the system goes to idle state. Here is a <strong>seemingly</strong>
 * correct implementation:
 *
 * <p><pre>
 * boolean idle;
 *
 * ResettableTimer idleTimer = new ResettableTimer(TIMEOUT, ...) {
 *   void timerExpired(TimerState state) {
 *     synchronized (mylock) {
 *       idle = true;
 *     }
 *   }
 * };
 *
 * void onActive() {
 *   synchronized (mylock) {
 *     idle = false;
 *     idleTimer.resetAndStart();
 *   }
 * }
 * </pre>
 *
 * <p>The pathological scenario is:
 * <ol>
 * <li>{@code timerExpired()} starts, but hasn't entered the synchronized block.</li>
 * <li>{@code onActive()} enters and exits synchronized block. Since {@code timerExpired} has
 * already started, it won't be stopped.</li>
 * <li>{@code timerExpired()} enters synchronized block, and set idle to true.</li>
 * </ol>
 *
 * <p>The end result is, the system is now in idle state despite that {@code onActive} has just been
 * called.
 *
 * <p>Following is the correct implementation. {@code resetAndStart()} will make {@code
 * state.isCancelled()} return {@code false} in the forementioned scenario, which stops {@code idle}
 * from being unexpectedlly set.
 *
 * <p><pre>
 * boolean idle;
 *
 * ResettableTimer idleTimer = new ResettableTimer(TIMEOUT, ...) {
 *   void timerExpired(TimerState state) {
 *     synchronized (mylock) {
 *       if (state.isCancelled()) {
 *         return;
 *       }
 *       idle = true;
 *     }
 *   }
 * };
 *
 * void onActive() {
 *   synchronized (mylock) {
 *     idle = false;
 *     idleTimer.resetAndStart();  // makes state.isCancelled() return true
 *   }
 * }
 * </pre>
 */
@ThreadSafe
abstract class ResettableTimer {
  private final long timeoutNanos;
  private final ScheduledExecutorService executor;
  private final Stopwatch stopwatch;
  private final Object lock = new Object();

  private class Task implements Runnable {
    final TimerState state = new TimerState();

    @Override
    public void run() {
      synchronized (lock) {
        if (!stopwatch.isRunning()) {
          // stop() has been called
          return;
        }
        long leftNanos = timeoutNanos - stopwatch.elapsed(TimeUnit.NANOSECONDS);
        if (leftNanos > 0) {
          currentTask = null;
          scheduleTask(leftNanos);
          return;
        }
        callbackRunning = true;
      }
      try {
        // We explicitly don't run the callback under the lock, so that the callback can have its
        // own synchronization and have the freedom to do something outside of any lock.
        timerExpired(state);
      } finally {
        synchronized (lock) {
          callbackRunning = false;
          currentTask = null;
          if (schedulePending) {
            schedulePending = false;
            scheduleTask(timeoutNanos);
          }
        }
      }
    }

    @Override
    public String toString() {
      return ResettableTimer.this.toString();
    }
  }

  @GuardedBy("lock")
  @Nullable
  private Task currentTask;

  @GuardedBy("lock")
  private boolean callbackRunning;

  @GuardedBy("lock")
  private boolean schedulePending;

  protected ResettableTimer(long timeout, TimeUnit unit, ScheduledExecutorService executor,
      Stopwatch stopwatch) {
    this.timeoutNanos = unit.toNanos(timeout);
    this.executor = checkNotNull(executor);
    this.stopwatch = checkNotNull(stopwatch);
  }

  /**
   * Handler to run when timer expired.
   *
   * <p>Timer won't restart automatically.
   *
   * @param state gives the handler the chance to check whether the timer is cancelled in the middle
   *              of the run
   */
  abstract void timerExpired(TimerState state);

  /**
   * Reset the timer and start it. {@link #timerExpired} will be run after timeout from now unless
   * {@link #resetAndStart} or {@link #stop} is called before that.
   */
  final void resetAndStart() {
    synchronized (lock) {
      stopwatch.reset().start();
      if (currentTask == null) {
        scheduleTask(timeoutNanos);
      } else {
        currentTask.state.cancelled = true;
        if (callbackRunning) {
          // currentTask has not been cleared yet, will let Task.run() schedule the timer after it's
          // done.
          schedulePending = true;
        }
      }
    }
  }

  /**
   * Stop the timer.
   */
  final void stop() {
    synchronized (lock) {
      if (currentTask != null) {
        stopwatch.stop();
        currentTask.state.cancelled = true;
        currentTask = null;
      }
    }
  }

  @GuardedBy("lock")
  private void scheduleTask(long nanos) {
    checkState(currentTask == null, "task already scheduled or running");
    currentTask = new Task();
    executor.schedule(new LogExceptionRunnable(currentTask), nanos, TimeUnit.NANOSECONDS);
  }

  /**
   * Holds the most up-to-date states of the timer.
   */
  final class TimerState {
    @GuardedBy("lock")
    private boolean cancelled;

    /**
     * Returns {@code true} if the current run is cancelled (either by {@link #stop} or {@link
     * #resetAndStart}).
     */
    boolean isCancelled() {
      synchronized (lock) {
        return cancelled;
      }
    }
  }
}
