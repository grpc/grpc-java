/*
 * Copyright 2018 The gRPC Authors
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

import static com.google.common.base.Preconditions.checkState;

import com.google.common.base.Stopwatch;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.annotation.CheckForNull;
import javax.annotation.concurrent.GuardedBy;

/**
 * Reschedules a runnable lazily. A thread-safe version of {@link Rescheduler}. As opposed to
 * {@code Rescheduler}, {@code ConcurrentRescheduler} does not execute {@code runnable.run()} in a
 * serialized executor.
 */
public class ConcurrentRescheduler {

  private final Runnable runnable;
  private final ScheduledExecutorService scheduler;
  private final Stopwatch stopwatch;

  private final Object lock = new Object();

  // Set to null when the task of the old value is cancelled;
  // also set to null when the task of the old value starts to run;
  // set to a new instance when a new task is scheduled;
  // also set to a new instance when the task is rescheduled ahead;
  // lazily set to a new instance when the task of the old value is postponed.
  // Whenever cancelled or rescheduled ahead, the old value MUST be marked with
  // cancelledPermanently = true.
  @GuardedBy("lock")
  @CheckForNull
  private FutureRunnable wakeUp;

  ConcurrentRescheduler(
      Runnable runnable,
      ScheduledExecutorService scheduler,
      Stopwatch stopwatch) {
    this.runnable = runnable;
    this.scheduler = scheduler;
    this.stopwatch = stopwatch;
    stopwatch.start();
  }

  /**
   * Schedules a new one or reschedule an existing one.
   */
  void reschedule(long delay, TimeUnit timeUnit) {
    if (delay < 0) {
      delay = 0;
    }
    long delayNanos = timeUnit.toNanos(delay);
    long newRunAtNanos = nanoTime() + delayNanos;
    FutureRunnable oldWakeUp;
    FutureRunnable newWakeUp;
    boolean needCancel = false;

    synchronized (lock) {
      oldWakeUp = wakeUp;
      if (oldWakeUp == null) {
        // schedule new one
      } else if (newRunAtNanos - oldWakeUp.runAtNanos < 0) {
        // need to schedule earlier:
        // cancel the old one and schedule new one
        oldWakeUp.cancelledPermanently = true;
        needCancel = true;
      } else {
        // postpone and return
        oldWakeUp.runAtNanos = newRunAtNanos;
        oldWakeUp.disabled = false;
        return;
      }

      newWakeUp = new FutureRunnable(newRunAtNanos);
      wakeUp = newWakeUp;
    }

    if (needCancel && oldWakeUp.task != null) {
      oldWakeUp.task.cancel(false);
    }

    schedule(newWakeUp);
  }

  /**
   * Schedules a new one if currently no one in schedule.
   */
  void scheduleNewOrNoop(long delay, TimeUnit timeUnit) {
    if (delay < 0) {
      delay = 0;
    }
    long delayNanos = timeUnit.toNanos(delay);
    long newRunAtNanos = nanoTime() + delayNanos;
    FutureRunnable newWakeUp;

    synchronized (lock) {
      if (wakeUp == null) {
        newWakeUp = new FutureRunnable(newRunAtNanos);
        wakeUp = newWakeUp;
      } else {
        return;
      }
    }

    schedule(newWakeUp);
  }

  void cancel(boolean permanent) {
    Future<?> oldTask;

    synchronized (lock) {
      FutureRunnable oldWakeUp = wakeUp;
      if (oldWakeUp == null) {
        return;
      }
      oldWakeUp.disabled = true;
      oldWakeUp.cancelledPermanently = permanent;
      oldTask = oldWakeUp.task;
      if (permanent) {
        wakeUp = null;
      }
    }

    if (permanent && oldTask != null) {
      oldTask.cancel(false);
    }
  }

  private void schedule(FutureRunnable newWakeUp) {
    // don't care whether newWakeUp.runAtNanos is stale or not
    Future<?> task =
        scheduler.schedule(newWakeUp, newWakeUp.runAtNanos - nanoTime(), TimeUnit.NANOSECONDS);

    boolean cancelledPermanently = false;

    synchronized (lock) {
      if (newWakeUp.cancelledPermanently) {
        cancelledPermanently = true;
      } else {
        newWakeUp.task = task;
      }
    }

    if (cancelledPermanently) {
      task.cancel(false);
    }
  }

  private final class FutureRunnable implements Runnable {

    long runAtNanos;
    boolean cancelledPermanently;
    boolean disabled;

    @CheckForNull
    Future<?> task;

    FutureRunnable(long runAtNanos) {
      this.runAtNanos = runAtNanos;
    }

    @Override
    public void run() {
      FutureRunnable postponed = null;

      synchronized (lock) {
        if (wakeUp == this) {
          wakeUp = null;
        }

        if (cancelledPermanently || disabled) {
          return;
        }

        // there couldn't be another uncancelled wakeUp
        checkState(wakeUp == null, "wakeUp not null and not cancelled");

        if (runAtNanos > nanoTime()) {
          postponed = new FutureRunnable(runAtNanos);
          wakeUp = postponed;
        }
      }

      if (postponed != null) {
        schedule(postponed);
        return;
      }

      runnable.run();
    }
  }

  private long nanoTime() {
    return stopwatch.elapsed(TimeUnit.NANOSECONDS);
  }
}
