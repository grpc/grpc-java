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

package io.grpc;

import static org.junit.Assert.assertEquals;

import java.time.Duration;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link TimeUtils}. */
@RunWith(JUnit4.class)
@IgnoreJRERequirement
public class TimeUtilsTest {

  @Test
  public void testConvertNormalDuration() {
    Duration duration = Duration.ofSeconds(10);
    long expected = 10 * 1_000_000_000L;

    assertEquals(expected, TimeUtils.convertToNanos(duration));
  }

  @Test
  public void testConvertNegativeDuration() {
    Duration duration = Duration.ofSeconds(-3);
    long expected = -3 * 1_000_000_000L;

    assertEquals(expected, TimeUtils.convertToNanos(duration));
  }

  @Test
  public void testConvertTooLargeDuration() {
    Duration duration = Duration.ofSeconds(Long.MAX_VALUE / 1_000_000_000L + 1);

    assertEquals(Long.MAX_VALUE, TimeUtils.convertToNanos(duration));
  }

  @Test
  public void testConvertTooLargeNegativeDuration() {
    Duration duration = Duration.ofSeconds(Long.MIN_VALUE / 1_000_000_000L - 1);

    assertEquals(Long.MIN_VALUE, TimeUtils.convertToNanos(duration));
  }
}
