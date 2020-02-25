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

package io.grpc.rls.internal;

import static com.google.common.truth.Truth.assertThat;

import io.grpc.rls.internal.AdaptiveThrottler.Ticker;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class AdaptiveThrottlerTest {
  private static final float TOLERANCE = 0.0001f;

  private final FakeTicker ticker = new FakeTicker();
  private final AdaptiveThrottler throttler =
      new AdaptiveThrottler.Builder()
          .setHistorySeconds(1)
          .setRatioForAccepts(1.0f)
          .setRequestsPadding(1)
          .setTicker(ticker)
          .build();

  @Test
  public void shouldThrottle() {
    // initial states
    assertThat(throttler.requestStat.get(ticker.nowInMillis())).isEqualTo(0L);
    assertThat(throttler.throttledStat.get(ticker.nowInMillis())).isEqualTo(0L);
    assertThat(throttler.getThrottleProbability(ticker.nowInMillis())).isWithin(TOLERANCE).of(0.0f);

    // Request 1, allowed by all.
    assertThat(throttler.shouldThrottle(0.4f)).isFalse();
    ticker.advance(1L);
    throttler.registerBackendResponse(false);

    assertThat(throttler.requestStat.get(ticker.nowInMillis())).isEqualTo(1L);
    assertThat(throttler.throttledStat.get(ticker.nowInMillis())).isEqualTo(0L);
    assertThat(throttler.getThrottleProbability(ticker.nowInMillis())).isWithin(TOLERANCE).of(0.0f);

    // Request 2, throttled by backend
    assertThat(throttler.shouldThrottle(0.4f)).isFalse();
    ticker.advance(1L);
    throttler.registerBackendResponse(true);

    assertThat(throttler.requestStat.get(ticker.nowInMillis())).isEqualTo(2L);
    assertThat(throttler.throttledStat.get(ticker.nowInMillis())).isEqualTo(1L);
    assertThat(throttler.getThrottleProbability(ticker.nowInMillis()))
        .isWithin(TOLERANCE)
        .of(1.0f / 3.0f);

    // Skip half a second (half the duration).
    ticker.setNow(500L);

    // Request 3, throttled by backend
    assertThat(throttler.shouldThrottle(0.4f)).isFalse();
    ticker.advance(1L);
    throttler.registerBackendResponse(true);

    assertThat(throttler.requestStat.get(ticker.nowInMillis())).isEqualTo(3L);
    assertThat(throttler.throttledStat.get(ticker.nowInMillis())).isEqualTo(2L);
    assertThat(throttler.getThrottleProbability(ticker.nowInMillis()))
        .isWithin(TOLERANCE)
        .of(2.0f / 4.0f);

    // Request 4, throttled by client.
    assertThat(throttler.shouldThrottle(0.4f)).isTrue();
    ticker.advance(1L);

    assertThat(throttler.requestStat.get(ticker.nowInMillis())).isEqualTo(4L);
    assertThat(throttler.throttledStat.get(ticker.nowInMillis())).isEqualTo(3L);
    assertThat(throttler.getThrottleProbability(ticker.nowInMillis()))
        .isWithin(TOLERANCE)
        .of(3.0f / 5.0f);

    // Skip to the point where only requests 3 and 4 are visible.
    ticker.setNow(1250L);

    assertThat(throttler.requestStat.get(ticker.nowInMillis())).isEqualTo(2L);
    assertThat(throttler.throttledStat.get(ticker.nowInMillis())).isEqualTo(2L);
    assertThat(throttler.getThrottleProbability(ticker.nowInMillis()))
        .isWithin(TOLERANCE)
        .of(2.0f / 3.0f);
  }

  private static final class FakeTicker implements Ticker {
    private AtomicLong currentTimeInMillis = new AtomicLong();

    FakeTicker() {}

    @Override
    public long nowInMillis() {
      return currentTimeInMillis.get();
    }

    long advance(long millis) {
      return currentTimeInMillis.addAndGet(millis);
    }

    void setNow(long nowInMillis) {
      currentTimeInMillis.set(nowInMillis);
    }
  }
}