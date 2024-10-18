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

package io.grpc.internal;

import static com.google.common.truth.Truth.assertThat;

import java.time.Instant;
import org.junit.Test;

/**
 *  Unit tests for {@link TimeProvider}.
 */
public class TimeProviderTest {
  @Test
  public void testCurrentTimeNanos() {

    // Get the current time from the TimeProvider
    long actualTimeNanos = TimeProvider.SYSTEM_TIME_PROVIDER.currentTimeNanos();

    // Get the current time from Instant for comparison
    Instant instantNow = Instant.now();
    long expectedTimeNanos = instantNow.getEpochSecond() * 1_000_000_000L + instantNow.getNano();

    // Validate the time returned is close to the expected value within a tolerance
    // (i,e 10 millisecond tolerance in nanoseconds).
    assertThat(actualTimeNanos).isWithin(10_000_000L).of(expectedTimeNanos);
  }
}
