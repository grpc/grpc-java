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
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Unit tests for {@link InstantTimeProvider}.
 */
@RunWith(JUnit4.class)
public class InstantTimeProviderTest {
  @Test
  public void testConcurrentCurrentTimeNanos() {

    ConcurrentTimeProvider concurrentTimeProvider = new ConcurrentTimeProvider();
    // Get the current time from the TimeProvider
    long actualTimeNanos = concurrentTimeProvider.currentTimeNanos();

    // Get the current time from Instant for comparison
    Instant instantNow = Instant.now();
    long expectedTimeNanos = TimeUnit.SECONDS.toNanos(instantNow.getEpochSecond())
        + instantNow.getNano();

    // Validate the time returned is close to the expected value within a tolerance
    // (i,e 10 millisecond tolerance in nanoseconds).
    assertThat(actualTimeNanos).isWithin(10_000_000L).of(expectedTimeNanos);
  }

  @Test
  public void testInstantCurrentTimeNanos() {
    try {
      InstantTimeProvider instantTimeProvider = new InstantTimeProvider(
          Class.forName("java.time.Instant"));

      // Get the current time from the TimeProvider
      long actualTimeNanos = instantTimeProvider.currentTimeNanos();

      // Get the current time from Instant for comparison
      Instant instantNow = Instant.now();
      long expectedTimeNanos = TimeUnit.SECONDS.toNanos(instantNow.getEpochSecond())
          + instantNow.getNano();

      // Validate the time returned is close to the expected value within a tolerance
      // (i,e 10 millisecond tolerance in nanoseconds).
      assertThat(actualTimeNanos).isWithin(10_000_000L).of(expectedTimeNanos);
    } catch (ClassNotFoundException e) {
      throw new RuntimeException(e);
    }
  }

  // Test to verify exception is thrown when "now" method is missing
  @Test(expected = RuntimeException.class)
  public void testMissingNowMethodThrowsException() {
    // Define a class without the "now" method
    class InvalidInstant {
      public int getNano() {
        return 0;
      }
      public long getEpochSecond() {
        return 0L;
      }
    }
    // This should throw a RuntimeException because "now" method is missing
    new InstantTimeProvider(InvalidInstant.class);
  }
}
