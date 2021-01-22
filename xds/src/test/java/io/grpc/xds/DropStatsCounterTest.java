/*
 * Copyright 2021 The gRPC Authors
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

package io.grpc.xds;

import static com.google.common.truth.Truth.assertThat;

import io.grpc.internal.FakeClock;
import io.grpc.xds.DropStatsCounter.DropStatsSnapshot;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class DropStatsCounterTest {

  private final FakeClock fakeClock = new FakeClock();
  private final DropStatsCounter dropStatsCounter =
      new DropStatsCounter(fakeClock.getStopwatchSupplier().get());

  @Test
  public void recordAndSnapshot() {
    for (int i = 0; i < 1241; i++) {
      dropStatsCounter.recordDroppedRequest("lb");
    }
    fakeClock.forwardTime(10L, TimeUnit.SECONDS);
    for (int i = 0; i < 999; i++) {
      dropStatsCounter.recordDroppedRequest("throttle");
    }
    fakeClock.forwardTime(15L, TimeUnit.SECONDS);
    for (int i = 0; i < 51; i++) {
      dropStatsCounter.recordDroppedRequest();  // uncategorized
    }
    fakeClock.forwardTime(5L, TimeUnit.SECONDS);
    DropStatsSnapshot snapshot = dropStatsCounter.snapshot();
    assertThat(snapshot.getCategorizedDrops()).containsExactly("lb", 1241L, "throttle", 999L);
    assertThat(snapshot.getUncategorizedDrops()).isEqualTo(51L);
    assertThat(snapshot.getDurationNano()).isEqualTo(TimeUnit.SECONDS.toNanos(10L + 15L + 5L));

    dropStatsCounter.recordDroppedRequest("reset");
    dropStatsCounter.recordDroppedRequest();  // uncategorized
    fakeClock.forwardTime(3L, TimeUnit.MILLISECONDS);
    snapshot = dropStatsCounter.snapshot();
    assertThat(snapshot.getCategorizedDrops()).containsExactly("reset", 1L);
    assertThat(snapshot.getUncategorizedDrops()).isEqualTo(1L);
    assertThat(snapshot.getDurationNano()).isEqualTo(TimeUnit.MILLISECONDS.toNanos(3L));
  }
}
