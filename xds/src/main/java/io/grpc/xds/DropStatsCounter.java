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

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.base.MoreObjects;
import com.google.common.base.Stopwatch;
import com.google.common.base.Supplier;
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
  private final ConcurrentMap<String, AtomicLong> categorizedDrops = new ConcurrentHashMap<>();
  private final Stopwatch stopwatch;

  DropStatsCounter(Supplier<Stopwatch> stopwatchSupplier) {
    this.stopwatch = checkNotNull(stopwatchSupplier, "stopwatchSupplier").get();
    stopwatch.reset().start();
  }

  void recordDroppedRequest(String category) {
    // There is a race between this method and snapshot(), causing one drop recorded but may not
    // be included in any snapshot. This is acceptable and the race window is extremely small.
    AtomicLong counter = categorizedDrops.putIfAbsent(category, new AtomicLong(1L));
    if (counter != null) {
      counter.getAndIncrement();
    }
  }

  void recordDroppedRequest() {
    uncategorizedDrops.getAndIncrement();
  }

  synchronized DropStatsSnapshot snapshot() {
    Map<String, Long> drops = new HashMap<>();
    for (Map.Entry<String, AtomicLong> entry : categorizedDrops.entrySet()) {
      drops.put(entry.getKey(), entry.getValue().get());
    }
    categorizedDrops.clear();
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
