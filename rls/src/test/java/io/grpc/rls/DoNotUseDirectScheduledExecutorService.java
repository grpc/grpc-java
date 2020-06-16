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

package io.grpc.rls;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static org.mockito.Mockito.mock;

import com.google.common.base.MoreObjects;
import io.grpc.internal.TimeProvider;
import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A fake minimal implementation of {@link ScheduledExecutorService} *only* supports
 * {@link ScheduledExecutorService#scheduleAtFixedRate(Runnable, long, long, TimeUnit)} (at most 1
 * task is allowed) and {@link ScheduledExecutorService#schedule(Runnable, long, TimeUnit)}. It is
 * directExecutor equivalent for {@link ScheduledExecutorService}.
 *
 * <p>Example:
 * <pre>
 * import static org.mockito.Mockito.CALLS_REAL_METHODS;
 * import static org.mockito.Mockito.mock;
 *
 * private final DoNotUseDirectScheduledExecutorService fakeScheduledService =
 *     mock(DoNotUseDirectScheduledExecutorService.class, CALLS_REAL_METHODS);
 * </pre>
 *
 * <p>Note: This class is only intended to be used in this test with CALL_REAL_METHODS mock. This
 * implementation is not thread-safe. Not safe to use elsewhere.
 */
abstract class DoNotUseDirectScheduledExecutorService implements ScheduledExecutorService {

  private long currTimeNanos;
  private long period;
  private long nextRun;
  private AtomicReference<Runnable> repeatedCommand;
  private PriorityQueue<ScheduledRunnable> scheduledCommands;
  private boolean initialized;

  private DoNotUseDirectScheduledExecutorService() {
    throw new UnsupportedOperationException("this class is for mock only");
  }

  /**
   * Note: CALLS_REAL_METHODS doesn't initialize instance variables, all the methods need to call
   * maybeInit if they access instance variables.
   */
  private void maybeInit() {
    if (initialized) {
      return;
    }

    initialized = true;
    repeatedCommand = new AtomicReference<>();
    scheduledCommands = new PriorityQueue<>(11, new ScheduledRunnableComparator());
  }

  @Override
  public final ScheduledFuture<?> scheduleAtFixedRate(
      Runnable command, long initialDelay, long period, TimeUnit unit) {
    maybeInit();
    checkArgument(period > 0, "period should be positive");
    checkArgument(initialDelay >= 0, "initial delay should be >= 0");
    checkState(this.repeatedCommand.get() == null, "only can schedule one");
    if (initialDelay == 0) {
      initialDelay = period;
      command.run();
    }
    this.repeatedCommand.set(checkNotNull(command, "command"));
    this.nextRun = checkNotNull(unit, "unit").toNanos(initialDelay) + currTimeNanos;
    this.period = unit.toNanos(period);
    return mock(ScheduledFuture.class);
  }

  @Override
  public final ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
    maybeInit();
    checkNotNull(command, "command");
    checkNotNull(unit, "unit");
    checkArgument(delay > 0, "delay must be positive");
    ScheduledRunnable scheduledRunnable =
        new ScheduledRunnable(currTimeNanos + TimeUnit.NANOSECONDS.convert(delay, unit), command);
    scheduledCommands.add(scheduledRunnable);
    return scheduledRunnable.scheduledFuture;
  }

  final FakeTimeProvider getFakeTimeProvider() {
    maybeInit();
    return new FakeTimeProvider();
  }

  private void forwardTime(long delta, TimeUnit unit) {
    maybeInit();
    checkNotNull(unit, "unit");
    checkArgument(delta > 0, "delta must be positive");
    long finalTime = currTimeNanos + unit.toNanos(delta);

    if (repeatedCommand.get() != null) {
      while (finalTime >= nextRun) {
        scheduledCommands.add(new ScheduledRunnable(nextRun, repeatedCommand.get()));
        nextRun += period;
      }
    }

    while (!scheduledCommands.isEmpty()
        && scheduledCommands.peek().scheduledTimeNanos <= finalTime) {
      ScheduledRunnable scheduledCommand = scheduledCommands.poll();
      try {
        // pretend to run at the scheduled time
        currTimeNanos = scheduledCommand.scheduledTimeNanos;
        scheduledCommand.run();
      } catch (Throwable t) {
        throw new RuntimeException("failed to run scheduled command: " + scheduledCommand, t);
      }
    }

    this.currTimeNanos = finalTime;
  }

  private final class ScheduledRunnable implements Runnable {
    private final long scheduledTimeNanos;
    private final Runnable command;
    private final ScheduledFuture<?> scheduledFuture = new ScheduledRunnable.FakeScheduledFuture();
    private boolean running = false;
    private boolean done = false;

    public ScheduledRunnable(long scheduledTimeNanos, Runnable command) {
      this.scheduledTimeNanos = scheduledTimeNanos;
      this.command = checkNotNull(command, "command");
    }

    @Override
    public void run() {
      if (!scheduledFuture.isCancelled()) {
        running = true;
        command.run();
        done = true;
      }
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("scheduledTimeNanos", scheduledTimeNanos)
          .add("command", command)
          .add("scheduledFuture", scheduledFuture)
          .add("running", running)
          .add("done", done)
          .toString();
    }

    private final class FakeScheduledFuture implements ScheduledFuture<Object> {
      boolean cancelled = false;

      @Override
      public long getDelay(TimeUnit unit) {
        return unit.convert(scheduledTimeNanos - currTimeNanos, TimeUnit.NANOSECONDS);
      }

      @Override
      public int compareTo(Delayed unused) {
        throw new UnsupportedOperationException();
      }

      @Override
      public boolean cancel(boolean mayInterruptIfRunning) {
        if (running) {
          return false;
        }
        cancelled = true;
        return true;
      }

      @Override
      public boolean isCancelled() {
        return cancelled;
      }

      @Override
      public boolean isDone() {
        return done;
      }

      @Override
      public Object get() throws InterruptedException, ExecutionException {
        throw new UnsupportedOperationException();
      }

      @Override
      public Object get(long timeout, TimeUnit unit)
          throws InterruptedException, ExecutionException, TimeoutException {
        throw new UnsupportedOperationException();
      }
    }
  }

  private static final class ScheduledRunnableComparator
      implements Comparator<ScheduledRunnable> {
    @Override
    public int compare(ScheduledRunnable o1, ScheduledRunnable o2) {
      return Long.compare(o1.scheduledTimeNanos, o2.scheduledTimeNanos);
    }
  }

  final class FakeTimeProvider implements TimeProvider {

    @Override
    public long currentTimeNanos() {
      return currTimeNanos;
    }

    void forwardTime(long delta, TimeUnit unit) {
      DoNotUseDirectScheduledExecutorService.this.forwardTime(delta, unit);
    }
  }
}
