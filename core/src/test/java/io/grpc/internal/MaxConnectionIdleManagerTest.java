/*
 * Copyright 2017 The gRPC Authors
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

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/** Unit tests for {@link MaxConnectionIdleManager}. */
@RunWith(JUnit4.class)
public class MaxConnectionIdleManagerTest {
  @Rule public final MockitoRule mocks = MockitoJUnit.rule();

  private final FakeClock fakeClock = new FakeClock();
  private final MaxConnectionIdleManager.Ticker ticker = new MaxConnectionIdleManager.Ticker() {
    @Override
    public long nanoTime() {
      return fakeClock.getTicker().read();
    }
  };

  @Mock
  private Runnable closure;

  @Test
  public void maxIdleReached() {
    MaxConnectionIdleManager maxConnectionIdleManager =
        new MaxConnectionIdleManager(123L, ticker);

    maxConnectionIdleManager.start(closure, fakeClock.getScheduledExecutorService());
    maxConnectionIdleManager.onTransportIdle();
    fakeClock.forwardNanos(123L);

    verify(closure).run();
  }

  @Test
  public void maxIdleNotReachedAndReached() {
    MaxConnectionIdleManager maxConnectionIdleManager =
        new MaxConnectionIdleManager(123L, ticker);

    maxConnectionIdleManager.start(closure, fakeClock.getScheduledExecutorService());
    maxConnectionIdleManager.onTransportIdle();
    fakeClock.forwardNanos(100L);
    // max idle not reached
    maxConnectionIdleManager.onTransportActive();
    maxConnectionIdleManager.onTransportIdle();
    fakeClock.forwardNanos(100L);
    // max idle not reached although accumulative idle time exceeds max idle time
    maxConnectionIdleManager.onTransportActive();
    fakeClock.forwardNanos(100L);

    verify(closure, never()).run();

    // max idle reached
    maxConnectionIdleManager.onTransportIdle();
    fakeClock.forwardNanos(123L);

    verify(closure).run();
  }

  @Test
  public void shutdownThenMaxIdleReached() {
    MaxConnectionIdleManager maxConnectionIdleManager =
        new MaxConnectionIdleManager(123L, ticker);

    maxConnectionIdleManager.start(closure, fakeClock.getScheduledExecutorService());
    maxConnectionIdleManager.onTransportIdle();
    maxConnectionIdleManager.onTransportTermination();
    fakeClock.forwardNanos(123L);

    verify(closure, never()).run();
  }
}
