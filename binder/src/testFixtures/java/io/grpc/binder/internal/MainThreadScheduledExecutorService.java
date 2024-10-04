/*
 * Copyright 2024 The gRPC Authors
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

import android.os.Handler;
import android.os.Looper;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.SettableFuture;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * A {@link ScheduledExecutorService} that queues all work on Android's "main" thread.
 *
 * <p>Use {@link org.robolectric.shadows.ShadowLooper#idle()} to run queued work.
 */
public class MainThreadScheduledExecutorService extends AbstractExecutorService
    implements ScheduledExecutorService {

  private final Handler handler = new Handler(Looper.getMainLooper());

  private static Runnable asRunnableFor(HandlerFuture<Void> future, Runnable runnable) {
    return () -> {
      try {
        runnable.run();
        future.complete(null);
      } catch (Exception e) {
        future.setException(e);
      }
    };
  }

  private static <V> Runnable asRunnableFor(HandlerFuture<V> future, Callable<V> callable) {
    return () -> {
      try {
        future.complete(callable.call());
      } catch (Exception e) {
        future.setException(e);
      }
    };
  }

  @Override
  public ScheduledFuture<?> schedule(Runnable runnable, long l, TimeUnit timeUnit) {
    long millis = timeUnit.toMillis(l);
    HandlerFuture<Void> result = new HandlerFuture<>(Duration.ofMillis(millis));
    handler.postDelayed(asRunnableFor(result, runnable), millis);
    return result;
  }

  @Override
  public <V> ScheduledFuture<V> schedule(Callable<V> callable, long l, TimeUnit timeUnit) {
    long millis = timeUnit.toMillis(l);
    HandlerFuture<V> result = new HandlerFuture<>(Duration.ofMillis(millis));
    handler.postDelayed(asRunnableFor(result, callable), millis);
    return result;
  }

  @Override
  public ScheduledFuture<?> scheduleAtFixedRate(
      Runnable runnable, long delay, long period, TimeUnit timeUnit) {
    return scheduleWithFixedDelay(runnable, delay, period, timeUnit);
  }

  @Override
  public ScheduledFuture<?> scheduleWithFixedDelay(
      Runnable runnable, long initialDelay, long delay, TimeUnit timeUnit) {
    long initialDelayMillis = timeUnit.toMillis(initialDelay);
    long periodMillis = timeUnit.toMillis(delay);
    HandlerFuture<Void> result = new HandlerFuture<>(Duration.ofMillis(initialDelayMillis));

    Runnable scheduledRunnable =
        new Runnable() {
          @Override
          public void run() {
            try {
              runnable.run();
              handler.postDelayed(this, periodMillis);
            } catch (Exception e) {
              result.setException(e);
            }
          }
        };

    handler.postDelayed(scheduledRunnable, initialDelayMillis);
    return result;
  }

  @Override
  public void shutdown() {}

  @Override
  public List<Runnable> shutdownNow() {
    return ImmutableList.of();
  }

  @Override
  public boolean isShutdown() {
    return false;
  }

  @Override
  public boolean isTerminated() {
    return false;
  }

  @Override
  public boolean awaitTermination(long l, TimeUnit timeUnit) {
    return true;
  }

  @Override
  public void execute(Runnable runnable) {
    handler.post(runnable);
  }

  private static class HandlerFuture<V> implements ScheduledFuture<V> {

    private final Duration delay;
    private final SettableFuture<V> delegate = SettableFuture.create();

    HandlerFuture(Duration delay) {
      this.delay = delay;
    }

    @Override
    public long getDelay(TimeUnit timeUnit) {
      return timeUnit.convert(delay.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public int compareTo(Delayed other) {
      return Comparator.comparingLong((Delayed delayed) -> delayed.getDelay(TimeUnit.MILLISECONDS))
          .compare(this, other);
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
      return delegate.cancel(mayInterruptIfRunning);
    }

    @Override
    public boolean isCancelled() {
      return delegate.isCancelled();
    }

    @Override
    public boolean isDone() {
      return delegate.isDone();
    }

    @Override
    public V get() throws ExecutionException, InterruptedException {
      return delegate.get();
    }

    @Override
    public V get(long timeout, TimeUnit timeUnit)
        throws ExecutionException, InterruptedException, TimeoutException {
      return delegate.get(timeout, timeUnit);
    }

    void complete(V result) {
      delegate.set(result);
    }

    void setException(Exception e) {
      delegate.setException(e);
    }
  }
}
