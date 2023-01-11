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
    long delayNanos = scheduler.schedule(() -> {
    });
    assertThat(delayNanos).isEqualTo(1);

    // Second schedule should have no effect.
    delayNanos = scheduler.schedule(() -> {
    });
    assertThat(delayNanos).isEqualTo(-1);

  }

  @Test
  public void reset() {
    long delayNanos = scheduler.schedule(() -> {
    });
    assertThat(delayNanos).isEqualTo(1);

    // Reset should make another schedule possible
    scheduler.reset();

    // Second schedule should now work.
    delayNanos = scheduler.schedule(() -> {
    });
    assertThat(delayNanos).isEqualTo(1);
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