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

import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.annotation.CheckForNull;
import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;

/**
 * Schedules, reschedules or cancels a runnable. As opposed to {@link Rescheduler}, {@code
 * ConcurrentRescheduler} does not execute {@code runnable.run()} in a serialized executor.
 * Lazy-reschedule is not used here because that will add logic complexity and currently no usecase
 * benefits much from that.
 */
@ThreadSafe
final class ConcurrentRescheduler {
  private final Runnable runnable;
  private final ScheduledExecutorService scheduler;
  private final Object lock = new Object();

  // Represents the currently scheduled task.
  // Set to null when the task is cancelled;
  // also set to null when the task starts to run;
  // set to a new instance when a new task is scheduled;
  // also set to a new instance when the task is rescheduled.
  // Whenever cancelled or rescheduled, the old value MUST be marked with cancelled = true.
  @GuardedBy("lock")
  @CheckForNull
  private FutureRunnable wakeUp;

  ConcurrentRescheduler(Runnable runnable, ScheduledExecutorService scheduler) {
    this.runnable = runnable;
    this.scheduler = scheduler;
  }

  /** Schedules a new one or reschedules an existing one. */
  void reschedule(long delay, TimeUnit timeUnit) {
    Future<?> existingTask = null;
    FutureRunnable newWakeUp;

    synchronized (lock) {
      FutureRunnable existingWakeUp = wakeUp;
      if (existingWakeUp != null) {
        // cancel the existing one and schedule new one
        existingWakeUp.cancelled = true;
        existingTask = existingWakeUp.task;
      }
      newWakeUp = new FutureRunnable();
      wakeUp = newWakeUp;
    }

    if (existingTask != null) {
      existingTask.cancel(false);
    }
    schedule(newWakeUp, delay, timeUnit);
  }

  /** Schedules a new one if currently no one in schedule. */
  void scheduleNewOrNoop(long delay, TimeUnit timeUnit) {
    FutureRunnable newWakeUp;

    synchronized (lock) {
      if (wakeUp == null) {
        newWakeUp = new FutureRunnable();
        wakeUp = newWakeUp;
      } else {
        return;
      }
    }

    schedule(newWakeUp, delay, timeUnit);
  }

  void cancel() {
    Future<?> existingTask;

    synchronized (lock) {
      FutureRunnable existingWakeUp = wakeUp;
      if (existingWakeUp == null) {
        return;
      }
      existingWakeUp.cancelled = true;
      existingTask = existingWakeUp.task;
      wakeUp = null;
    }

    if (existingTask != null) {
      existingTask.cancel(false);
    }
  }

  private void schedule(FutureRunnable newWakeUp, long delay, TimeUnit timeUnit) {
    Future<?> task =
        scheduler.schedule(newWakeUp, delay, timeUnit);
    boolean cancelled;

    synchronized (lock) {
      cancelled = newWakeUp.cancelled;
      if (!cancelled) {
        newWakeUp.task = task;
      }
    }

    if (cancelled) {
      task.cancel(false);
    }
  }

  /** Wrapper of a future and a cancelled flag. */
  private final class FutureRunnable implements Runnable {
    // @GuardedBy("lock")
    boolean cancelled;
    // @GuardedBy("lock")
    @CheckForNull
    Future<?> task;

    FutureRunnable() {}

    @Override
    public void run() {
      synchronized (lock) {
        if (wakeUp == this) {
          wakeUp = null;
        }
        if (cancelled) {
          return;
        }
        checkState(wakeUp == null, "wakeUp not null and not cancelled");
      }

      runnable.run();
    }
  }
}
