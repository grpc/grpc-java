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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.common.base.Stopwatch;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Unit tests for {@link ResettableTimer}.
 */
@RunWith(JUnit4.class)
public class ResettableTimerTest {
  private static final long TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(1);
  private final FakeClock timerService = new FakeClock();
  private final ResettableTimer timer = new ResettableTimer(TIMEOUT_NANOS, TimeUnit.NANOSECONDS,
      timerService.scheduledExecutorService, Stopwatch.createUnstarted(timerService.ticker)) {
      @Override
      void timerExpired(TimerState state) {
        assertFalse(state.isCancelled());
        expireCount++;
      }
    };

  private int expireCount;

  @After
  public void noPendingTasks() {
    assertEquals(0, timerService.numPendingTasks());
  }

  @Test
  public void unstarted() {
    assertEquals(0, timerService.forwardTime(TIMEOUT_NANOS * 2, TimeUnit.NANOSECONDS));
    assertEquals(0, expireCount);

    timer.stop();
    assertEquals(0, timerService.forwardTime(TIMEOUT_NANOS * 2, TimeUnit.NANOSECONDS));
    assertEquals(0, expireCount);
  }

  @Test
  public void expired() {
    timer.resetAndStart();
    assertEquals(0, timerService.forwardTime(TIMEOUT_NANOS - 1, TimeUnit.NANOSECONDS));
    assertEquals(0, expireCount);
    assertEquals(1, timerService.forwardTime(1, TimeUnit.NANOSECONDS));
    assertEquals(1, expireCount);
    assertEquals(0, timerService.forwardTime(TIMEOUT_NANOS * 2, TimeUnit.NANOSECONDS));
    assertEquals(1, expireCount);

    timer.stop();
    assertEquals(0, timerService.forwardTime(TIMEOUT_NANOS * 2, TimeUnit.NANOSECONDS));
    assertEquals(1, expireCount);
  }

  @Test
  public void resetBeforeExpired() {
    timer.resetAndStart();
    assertEquals(0, timerService.forwardTime(TIMEOUT_NANOS - 1, TimeUnit.NANOSECONDS));
    timer.resetAndStart();
    assertEquals(1, timerService.forwardTime(TIMEOUT_NANOS - 1, TimeUnit.NANOSECONDS));
    timer.resetAndStart();
    assertEquals(1, timerService.forwardTime(TIMEOUT_NANOS - 1, TimeUnit.NANOSECONDS));
    assertEquals(0, expireCount);
    assertEquals(1, timerService.forwardTime(1, TimeUnit.NANOSECONDS));
    assertEquals(1, expireCount);
    assertEquals(0, timerService.forwardTime(TIMEOUT_NANOS * 2, TimeUnit.NANOSECONDS));
    assertEquals(1, expireCount);
  }

  @Test
  public void startBeforeExpired() {
    assertTrue(timer.start());
    assertEquals(0, timerService.forwardTime(TIMEOUT_NANOS - 1, TimeUnit.NANOSECONDS));

    assertFalse(timer.start());
    assertEquals(1, timerService.forwardTime(1, TimeUnit.NANOSECONDS));
    assertEquals(1, expireCount);

    assertTrue(timer.start());
    assertEquals(0, timerService.forwardTime(TIMEOUT_NANOS - 1, TimeUnit.NANOSECONDS));
    assertEquals(1, expireCount);
    assertEquals(1, timerService.forwardTime(1, TimeUnit.NANOSECONDS));
    assertEquals(2, expireCount);
  }

  @Test
  public void stopBeforeExpired() {
    timer.resetAndStart();
    assertEquals(0, timerService.forwardTime(TIMEOUT_NANOS - 1, TimeUnit.NANOSECONDS));
    timer.stop();
    assertEquals(1, timerService.forwardTime(TIMEOUT_NANOS * 2, TimeUnit.NANOSECONDS));
    assertEquals(0, expireCount);
    assertEquals(0, timerService.forwardTime(TIMEOUT_NANOS * 2, TimeUnit.NANOSECONDS));

    // Check that it's still usable
    timer.resetAndStart();
    assertEquals(0, timerService.forwardTime(TIMEOUT_NANOS - 1, TimeUnit.NANOSECONDS));
    assertEquals(0, expireCount);
    assertEquals(1, timerService.forwardTime(1, TimeUnit.NANOSECONDS));
    assertEquals(1, expireCount);
    assertEquals(0, timerService.forwardTime(TIMEOUT_NANOS * 2, TimeUnit.NANOSECONDS));
    assertEquals(1, expireCount);
  }

  @Test
  public void shutdownBeforeExpired() {
    timer.resetAndStart();
    assertEquals(0, timerService.forwardTime(TIMEOUT_NANOS - 1, TimeUnit.NANOSECONDS));
    timer.shutdown();
    // The task is removed from the scheduled executor
    assertEquals(0, timerService.forwardTime(TIMEOUT_NANOS * 2, TimeUnit.NANOSECONDS));
    assertEquals(0, expireCount);
  }

  @Test
  public void shutdownAfterStopped() {
    timer.resetAndStart();
    assertEquals(0, timerService.forwardTime(TIMEOUT_NANOS - 1, TimeUnit.NANOSECONDS));
    timer.stop();
    timer.shutdown();
    assertEquals(0, timerService.forwardTime(TIMEOUT_NANOS * 2, TimeUnit.NANOSECONDS));
    assertEquals(0, expireCount);
  }

  @Test
  public void stopThenRestart() {
    timer.resetAndStart();
    assertEquals(0, timerService.forwardTime(TIMEOUT_NANOS - 1, TimeUnit.NANOSECONDS));
    timer.stop();
    timer.resetAndStart();
    assertEquals(1, timerService.forwardTime(TIMEOUT_NANOS - 1, TimeUnit.NANOSECONDS));
    assertEquals(0, expireCount);
    assertEquals(1, timerService.forwardTime(1, TimeUnit.NANOSECONDS));
    assertEquals(1, expireCount);
  }

  @Test
  public void startThenImmdiatelyReset() {
    timer.resetAndStart();
    timer.resetAndStart();

    assertEquals(0, timerService.forwardTime(TIMEOUT_NANOS - 1, TimeUnit.NANOSECONDS));
    assertEquals(0, expireCount);
    assertEquals(1, timerService.forwardTime(1, TimeUnit.NANOSECONDS));
    assertEquals(1, expireCount);
  }

  @Test
  public void startThenImmdiatelyStop() {
    timer.resetAndStart();
    timer.stop();
    assertEquals(1, timerService.forwardTime(TIMEOUT_NANOS * 2, TimeUnit.NANOSECONDS));
    assertEquals(0, expireCount);

    // Check that it's still usable
    timer.resetAndStart();
    assertEquals(0, timerService.forwardTime(TIMEOUT_NANOS - 1, TimeUnit.NANOSECONDS));
    assertEquals(0, expireCount);
    assertEquals(1, timerService.forwardTime(1, TimeUnit.NANOSECONDS));
    assertEquals(1, expireCount);
    assertEquals(0, timerService.forwardTime(TIMEOUT_NANOS * 2, TimeUnit.NANOSECONDS));
    assertEquals(1, expireCount);
  }

  @Test
  public void startThenImmdiatelyStopThenRestart() {
    timer.resetAndStart();
    timer.stop();
    timer.resetAndStart();

    assertEquals(0, timerService.forwardTime(TIMEOUT_NANOS - 1, TimeUnit.NANOSECONDS));
    assertEquals(0, expireCount);
    assertEquals(1, timerService.forwardTime(1, TimeUnit.NANOSECONDS));
    assertEquals(1, expireCount);
  }

  @Test
  public void resetWhileRunning() {
    final CountDownLatch timerLatch = new CountDownLatch(1);
    final CountDownLatch resetterLatch = new CountDownLatch(1);
    final ResettableTimer awareTimer = new ResettableTimer(TIMEOUT_NANOS, TimeUnit.NANOSECONDS,
        timerService.scheduledExecutorService, Stopwatch.createUnstarted(timerService.ticker)) {
        @Override
        void timerExpired(TimerState state) {
          try {
            assertFalse(state.isCancelled());
            resetterLatch.countDown();
            assertTrue(timerLatch.await(10, TimeUnit.SECONDS));
            if (expireCount == 0) {
              assertTrue(state.isCancelled());
            } else {
              assertFalse(state.isCancelled());
            }
            expireCount++;
          } catch (InterruptedException e) {
            throw new RuntimeException(e);
          }
        }
      };
    new Thread() {
      @Override
      public void run() {
        try {
          assertTrue(resetterLatch.await(10, TimeUnit.SECONDS));
          awareTimer.resetAndStart();
          timerLatch.countDown();
        } catch (InterruptedException e) {
          throw new RuntimeException(e);
        }
      }
    }.start();
    awareTimer.resetAndStart();
    assertEquals(0, timerService.forwardTime(TIMEOUT_NANOS - 1, TimeUnit.NANOSECONDS));
    assertEquals(0, expireCount);
    assertEquals(1, timerService.forwardTime(1, TimeUnit.NANOSECONDS));
    assertEquals(1, expireCount);

    // The timer has been reset in the other thread
    assertEquals(0, timerService.forwardTime(TIMEOUT_NANOS - 1, TimeUnit.NANOSECONDS));
    assertEquals(1, expireCount);
    assertEquals(1, timerService.forwardTime(1, TimeUnit.NANOSECONDS));
    assertEquals(2, expireCount);
  }

  @Test
  public void stopWhileRunning() {
    final CountDownLatch timerLatch = new CountDownLatch(1);
    final CountDownLatch resetterLatch = new CountDownLatch(1);
    final ResettableTimer awareTimer = new ResettableTimer(TIMEOUT_NANOS, TimeUnit.NANOSECONDS,
        timerService.scheduledExecutorService, Stopwatch.createUnstarted(timerService.ticker)) {
        @Override
        void timerExpired(TimerState state) {
          try {
            assertFalse(state.isCancelled());
            resetterLatch.countDown();
            assertTrue(timerLatch.await(10, TimeUnit.SECONDS));
            assertTrue(state.isCancelled());
            expireCount++;
          } catch (InterruptedException e) {
            throw new RuntimeException(e);
          }
        }
      };
    new Thread() {
      @Override
      public void run() {
        try {
          assertTrue(resetterLatch.await(10, TimeUnit.SECONDS));
          awareTimer.stop();
          timerLatch.countDown();
        } catch (InterruptedException e) {
          throw new RuntimeException(e);
        }
      }
    }.start();
    awareTimer.resetAndStart();
    assertEquals(0, timerService.forwardTime(TIMEOUT_NANOS - 1, TimeUnit.NANOSECONDS));
    assertEquals(0, expireCount);
    assertEquals(1, timerService.forwardTime(1, TimeUnit.NANOSECONDS));
    assertEquals(1, expireCount);
    assertEquals(0, timerService.forwardTime(TIMEOUT_NANOS * 2, TimeUnit.NANOSECONDS));
    assertEquals(1, expireCount);
  }
}
