/*
 * Copyright 20121 The gRPC Authors
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

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.base.MoreObjects;
import com.google.common.base.Stopwatch;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;

/**
 * Recorder for dropped requests.
 */
// TODO(chengyuanzhang): this class can be moved into LoadStatsManager.
@ThreadSafe
final class DropStatsCounter {

  private final AtomicLong uncategorizedDrops = new AtomicLong();
  private volatile ConcurrentMap<String, AtomicLong> categorizedDrops = new ConcurrentHashMap<>();
  private final Stopwatch stopwatch;

  DropStatsCounter(Stopwatch stopwatch) {
    this.stopwatch = checkNotNull(stopwatch, "stopwatch");
    stopwatch.reset().start();
  }

  void recordDroppedRequest(String category) {
    AtomicLong counter = categorizedDrops.putIfAbsent(category, new AtomicLong(1L));
    // There is a race between incrementing an existing atomic and snapshot, causing the one
    // drop recorded but not included in the snapshot. This is acceptable and the race window is
    // extremely small.
    if (counter != null) {
      counter.getAndIncrement();
    }
  }

  void recordDroppedRequest() {
    uncategorizedDrops.getAndIncrement();
  }

  synchronized DropStatsSnapshot snapshot() {
    Map<String, AtomicLong> categorizedDropsCopy = categorizedDrops;
    categorizedDrops = new ConcurrentHashMap<>();
    Map<String, Long> drops = new HashMap<>();
    for (Map.Entry<String, AtomicLong> entry : categorizedDropsCopy.entrySet()) {
      drops.put(entry.getKey(), entry.getValue().get());
    }
    long duration = stopwatch.elapsed(TimeUnit.NANOSECONDS);
    stopwatch.reset().start();
    return new DropStatsSnapshot(drops, uncategorizedDrops.getAndSet(0), duration);
  }

  /**
   * A read-only snapshot for a {@link DropStatsCounter}.
   */
  @Immutable
  static final class DropStatsSnapshot {
    private final Map<String, Long> categorizedDrops;
    private final long uncategorizedDrops;
    private final long durationNano;

    private DropStatsSnapshot(
        Map<String, Long> categorizedDrops, long uncategorizedDrops, long durationNano) {
      this.categorizedDrops = Collections.unmodifiableMap(
          checkNotNull(categorizedDrops, "categorizedDrops"));
      this.uncategorizedDrops = uncategorizedDrops;
      this.durationNano = durationNano;
    }

    Map<String, Long> getCategorizedDrops() {
      return categorizedDrops;
    }

    long getUncategorizedDrops() {
      return uncategorizedDrops;
    }

    long getDurationNano() {
      return durationNano;
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("categorizedDrops", categorizedDrops)
          .add("uncategorizedDrops", uncategorizedDrops)
          .add("durationNano", durationNano)
          .toString();
    }
  }
}
