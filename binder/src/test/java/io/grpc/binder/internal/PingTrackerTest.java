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

package io.grpc.binder.internal;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static java.util.concurrent.TimeUnit.SECONDS;

import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.internal.ClientTransport;
import io.grpc.internal.FakeClock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class PingTrackerTest {

  private final FakeClock clock = new FakeClock();

  @Nullable private Status pingFailureStatus;
  private List<Integer> sentPings;

  private TestCallback callback;
  private PingTracker pingTracker;

  @Before
  public void setUp() {
    sentPings = new ArrayList<>();
    callback = new TestCallback();
    pingTracker =
        new PingTracker(
            clock.getTicker(),
            (id) -> {
              sentPings.add(id);
              if (pingFailureStatus != null) {
                throw pingFailureStatus.asException();
              }
            });
  }

  @Test
  public void successfulPing() throws Exception {
    pingTracker.startPing(callback, directExecutor());
    assertThat(sentPings).hasSize(1);
    callback.assertNotCalled();
    clock.forwardTime(3, SECONDS);
    pingTracker.onPingResponse(sentPings.get(0));
    callback.assertSuccess(Duration.ofSeconds(3).toNanos());
  }

  @Test
  public void failedPing() throws Exception {
    pingFailureStatus = Status.INTERNAL.withDescription("Hello");
    pingTracker.startPing(callback, directExecutor());
    callback.assertFailure(pingFailureStatus);
  }

  @Test
  public void noSuccessAfterFailure() throws Exception {
    pingFailureStatus = Status.INTERNAL.withDescription("Hello");
    pingTracker.startPing(callback, directExecutor());
    pingTracker.onPingResponse(sentPings.get(0));
    callback.assertFailure(pingFailureStatus);
  }

  @Test
  public void noMultiSuccess() throws Exception {
    pingTracker.startPing(callback, directExecutor());
    pingTracker.onPingResponse(sentPings.get(0));
    pingTracker.onPingResponse(sentPings.get(0));
    callback.assertSuccess(); // Checks we were only called once.
  }

  private static final class TestCallback implements ClientTransport.PingCallback {
    private int numCallbacks;
    private boolean success;
    private boolean failure;
    private Throwable failureException;
    private long roundtripTimeNanos;

    @Override
    public synchronized void onSuccess(long roundtripTimeNanos) {
      numCallbacks += 1;
      success = true;
      this.roundtripTimeNanos = roundtripTimeNanos;
    }

    @Override
    public synchronized void onFailure(Throwable failureException) {
      numCallbacks += 1;
      failure = true;
      this.failureException = failureException;
    }

    public void assertNotCalled() {
      assertThat(numCallbacks).isEqualTo(0);
    }

    public void assertSuccess() {
      assertThat(numCallbacks).isEqualTo(1);
      assertThat(success).isTrue();
    }

    public void assertSuccess(long expectRoundTripTimeNanos) {
      assertSuccess();
      assertThat(roundtripTimeNanos).isEqualTo(expectRoundTripTimeNanos);
    }

    public void assertFailure(Status status) {
      assertThat(numCallbacks).isEqualTo(1);
      assertThat(failure).isTrue();
      assertThat(((StatusException) failureException).getStatus()).isSameInstanceAs(status);
    }

    public void assertFailure(Status.Code statusCode) {
      assertThat(numCallbacks).isEqualTo(1);
      assertThat(failure).isTrue();
      assertThat(((StatusException) failureException).getStatus().getCode()).isEqualTo(statusCode);
    }
  }
}
