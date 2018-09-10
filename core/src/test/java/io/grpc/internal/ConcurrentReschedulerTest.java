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

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import io.grpc.internal.ConcurrentRescheduler.Precondition;
import java.util.concurrent.TimeUnit;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Unit tests for {@link ConcurrentRescheduler}.
 */
@RunWith(JUnit4.class)
public class ConcurrentReschedulerTest {
  private final FakeClock fakeClock = new FakeClock();
  private final Runnable runnable = new Runnable() {
    @Override
    public void run() {

    }
  };

  private final Precondition precondition = mock(Precondition.class);

  private final ConcurrentRescheduler rescheduler = new ConcurrentRescheduler(
      runnable, fakeClock.getScheduledExecutorService(), precondition, new Object());

  @Before
  public void setUp() {
    doReturn(true).when(precondition).met();
  }

  @Test
  public void scheduleNew() {
    rescheduler.scheduleNewOrNoop(100, TimeUnit.NANOSECONDS);
    assertThat(fakeClock.numPendingTasks()).isEqualTo(1);

    fakeClock.forwardNanos(50);
    assertThat(fakeClock.numPendingTasks()).isEqualTo(1);

    fakeClock.forwardNanos(50);
    assertThat(fakeClock.numPendingTasks()).isEqualTo(0);
  }

  @Test
  public void reschedule() {
    rescheduler.reschedule(100, TimeUnit.NANOSECONDS);
    assertThat(fakeClock.numPendingTasks()).isEqualTo(1);

    fakeClock.forwardNanos(50);
    assertThat(fakeClock.numPendingTasks()).isEqualTo(1);

    fakeClock.forwardNanos(60);
    assertThat(fakeClock.numPendingTasks()).isEqualTo(0);
  }

  @Test
  public void cancel() {
    rescheduler.cancel();
    assertThat(fakeClock.numPendingTasks()).isEqualTo(0);
  }

  @Test
  public void scheduleNewThenScheduleNew() {
    rescheduler.scheduleNewOrNoop(50, TimeUnit.NANOSECONDS);
    rescheduler.scheduleNewOrNoop(100, TimeUnit.NANOSECONDS);
    assertThat(fakeClock.numPendingTasks()).isEqualTo(1);
    fakeClock.forwardNanos(50);
    assertThat(fakeClock.numPendingTasks()).isEqualTo(0);

    rescheduler.scheduleNewOrNoop(100, TimeUnit.NANOSECONDS);
    rescheduler.scheduleNewOrNoop(50, TimeUnit.NANOSECONDS);
    assertThat(fakeClock.numPendingTasks()).isEqualTo(1);
    fakeClock.forwardNanos(50);
    assertThat(fakeClock.numPendingTasks()).isEqualTo(1);
    fakeClock.forwardNanos(50);
    assertThat(fakeClock.numPendingTasks()).isEqualTo(0);
  }

  @Test
  public void scheduleNewThenRescheduleEarlier() {
    rescheduler.scheduleNewOrNoop(100, TimeUnit.NANOSECONDS);
    rescheduler.reschedule(50, TimeUnit.NANOSECONDS);

    assertThat(fakeClock.numPendingTasks()).isEqualTo(1);

    fakeClock.forwardNanos(50);
    assertThat(fakeClock.numPendingTasks()).isEqualTo(0);
  }

  @Test
  public void scheduleNewThenRescheduleLater() {
    rescheduler.scheduleNewOrNoop(100, TimeUnit.NANOSECONDS);
    rescheduler.reschedule(150, TimeUnit.NANOSECONDS);

    fakeClock.forwardNanos(100);
    assertThat(fakeClock.numPendingTasks()).isEqualTo(1);

    fakeClock.forwardNanos(50);
    assertThat(fakeClock.numPendingTasks()).isEqualTo(0);
  }

  @Test
  public void rescheduleThenRescheduleEarlier() {
    rescheduler.reschedule(100, TimeUnit.NANOSECONDS);
    rescheduler.reschedule(50, TimeUnit.NANOSECONDS);

    assertThat(fakeClock.numPendingTasks()).isEqualTo(1);

    fakeClock.forwardNanos(50);
    assertThat(fakeClock.numPendingTasks()).isEqualTo(0);
  }

  @Test
  public void rescheduleThenRescheduleLater() {
    rescheduler.scheduleNewOrNoop(100, TimeUnit.NANOSECONDS);
    rescheduler.reschedule(150, TimeUnit.NANOSECONDS);

    fakeClock.forwardNanos(100);
    assertThat(fakeClock.numPendingTasks()).isEqualTo(1);

    fakeClock.forwardNanos(50);
    assertThat(fakeClock.numPendingTasks()).isEqualTo(0);
  }

  @Test
  public void scheduleNewThenCancel() {
    rescheduler.scheduleNewOrNoop(100, TimeUnit.NANOSECONDS);
    fakeClock.forwardNanos(50);
    rescheduler.cancel();

    assertThat(fakeClock.numPendingTasks()).isEqualTo(0);
  }

  @Test
  public void reschedulehenCancel() {
    rescheduler.reschedule(100, TimeUnit.NANOSECONDS);
    fakeClock.forwardNanos(50);
    rescheduler.cancel();

    assertThat(fakeClock.numPendingTasks()).isEqualTo(0);
  }
}
