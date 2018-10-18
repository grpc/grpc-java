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

import static com.google.common.base.Preconditions.checkNotNull;

import io.grpc.ControlPlaneScheduler;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

final class ControlPlaneSchedulerImpl extends ControlPlaneScheduler {
  private final ScheduledExecutorService timerService;
  private final ChannelExecutor channelExecutor;
  private final TimeProvider time;

  ControlPlaneSchedulerImpl(
      ScheduledExecutorService timerService, ChannelExecutor channelExecutor,
      TimeProvider time) {
    this.timerService = checkNotNull(timerService, "timerService");
    this.channelExecutor = checkNotNull(channelExecutor, "channelExecutor");
    this.time = checkNotNull(time, "time");
  }

  @Override
  public final ScheduledContext schedule(final Runnable task, long delay, TimeUnit unit) {
    final ManagedRunnable runnable = new ManagedRunnable(task);
    ScheduledFuture<?> future = null;
    if (delay <= 0) {
      channelExecutor.executeLater(runnable).drain();
    } else {
      future = timerService.schedule(new Runnable() {
        @Override
        public void run() {
          channelExecutor.executeLater(runnable).drain();
        }
      }, delay, unit);
    }
    return new ScheduledContextImpl(runnable, future);
  }

  @Override
  public long currentTimeNanos() {
    return time.currentTimeNanos();
  }

  private static class ManagedRunnable implements Runnable {
    final Runnable task;
    boolean isCancelled;
    boolean hasStarted;

    ManagedRunnable(Runnable task) {
      this.task = checkNotNull(task, "task");
    }

    @Override
    public void run() {
      // The task may have been cancelled after timerService calls runInSynchronizationContext()
      // but before the runnable is actually run.  We must guarantee that the task will not be run
      // in this case.
      if (!isCancelled) {
        hasStarted = true;
        task.run();
      }
    }
  }

  private static class ScheduledContextImpl extends ScheduledContext {
    final ManagedRunnable runnable;
    @Nullable
    final ScheduledFuture<?> future;

    ScheduledContextImpl(ManagedRunnable runnable, @Nullable ScheduledFuture<?> future) {
      this.runnable = checkNotNull(runnable, "runnable");
      this.future = future;
    }

    @Override
    public void cancel() {
      runnable.isCancelled = true;
      if (future != null) {
        future.cancel(false);
      }
    }

    @Override
    public boolean isPending() {
      return !(runnable.hasStarted || runnable.isCancelled);
    }
  }
}
