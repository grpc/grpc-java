/*
 * Copyright 2023 The gRPC Authors
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

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.mock;

import io.grpc.SynchronizationContext;
import java.lang.Thread.UncaughtExceptionHandler;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Unit tests for {@link BackoffPolicyRetryScheduler}.
 */
@RunWith(JUnit4.class)
public class BackoffPolicyRetrySchedulerTest {

  private final FakeClock fakeClock = new FakeClock();

  private BackoffPolicyRetryScheduler scheduler;
  private final SynchronizationContext syncContext = new SynchronizationContext(
      mock(UncaughtExceptionHandler.class));

  @Before
  public void setup() {
    scheduler = new BackoffPolicyRetryScheduler(new FakeBackoffPolicyProvider(),
        fakeClock.getScheduledExecutorService(), syncContext);
  }

  @Test
  public void schedule() {
    AtomicInteger retryCount = new AtomicInteger();
    Runnable retry = retryCount::incrementAndGet;
    syncContext.execute(() -> scheduler.schedule(retry));

    fakeClock.forwardTime(2, TimeUnit.NANOSECONDS);

    assertThat(retryCount.get()).isEqualTo(1);
  }

  @Test
  public void schedule_noMultiple() {
    AtomicInteger retryCount = new AtomicInteger();
    Runnable retry = retryCount::incrementAndGet;

    // We schedule multiple retries...
    syncContext.execute(() -> scheduler.schedule(retry));
    syncContext.execute(() -> scheduler.schedule(retry));

    fakeClock.forwardTime(2, TimeUnit.NANOSECONDS);

    // But only one of them should have run.
    assertThat(retryCount.get()).isEqualTo(1);
  }

  @Test
  public void reset() {
    AtomicInteger retryCount = new AtomicInteger();
    Runnable retry = retryCount::incrementAndGet;
    Runnable retryTwo = () -> {
      retryCount.getAndAdd(2);
    };

    // We schedule one retry.
    syncContext.execute(() -> scheduler.schedule(retry));

    // But then reset.
    syncContext.execute(() -> scheduler.reset());

    // And schedule a different retry.
    syncContext.execute(() -> scheduler.schedule(retryTwo));

    fakeClock.forwardTime(2, TimeUnit.NANOSECONDS);

    // The retry after the reset should have been run.
    assertThat(retryCount.get()).isEqualTo(2);
  }

  private static class FakeBackoffPolicyProvider implements BackoffPolicy.Provider {
    @Override
    public BackoffPolicy get() {
      return new BackoffPolicy() {
        @Override
        public long nextBackoffNanos() {
          return 1;
        }
      };
    }
  }
}