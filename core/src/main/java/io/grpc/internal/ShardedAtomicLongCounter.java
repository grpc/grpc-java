/*
 * Copyright 2017, gRPC Authors All rights reserved.
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

import com.google.common.annotations.VisibleForTesting;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * An implementation of {@link LongCounter} that works by sharding across an
 * {@link AtomicLongArray}. Do not instantiate directly, instead use {@link LongCounterFactory}.
 */
final class ShardedAtomicLongCounter implements LongCounter {
  private final AtomicLongArray counters;
  private final int mask;

  // Guava's Striped64 uses this technique to spread accesses across the array.
  private static final Random rng = new Random();
  private static final ThreadLocal<int[]> threadHashCode = new ThreadLocal<int[]>() {
    @Override
    protected int[] initialValue() {
      int[] ret = new int[1];
      int r = rng.nextInt();
      ret[0] = r == 0 ? 1 : r;
      return ret;
    }
  };

  /**
   * Accepts a hint on how many shards should be created. The actual number of shards may differ.
   */
  ShardedAtomicLongCounter(int numShardsHint) {
    int numShards = forcePower2(numShardsHint);
    counters = new AtomicLongArray(numShards);
    mask = numShards - 1;
  }

  /**
   * Force the shard size to a power of 2, with a reasonable ceiling value.
   * Let's avoid clever bit twiddling and keep it simple.
   */
  static int forcePower2(int numShardsHint) {
    if (numShardsHint >= 64) {
      return 64;
    } else if (numShardsHint >= 32) {
      return 32;
    } else if (numShardsHint >= 16) {
      return 16;
    } else {
      return 8;
    }
  }

  @VisibleForTesting
  int getCounterIdx(int hashCode) {
    return hashCode & mask;
  }

  @Override
  public void add(long delta) {
    counters.addAndGet(getCounterIdx(getNextHash()), delta);
  }

  @Override
  public long value() {
    long val = 0;
    for (int i = 0; i < counters.length(); i++) {
      val += counters.get(i);
    }
    return val;
  }

  private int getNextHash() {
    int[] hashHolder = threadHashCode.get();
    int h = hashHolder[0];
    try {
      return h;
    } finally {
      // Rehash for next time
      h ^= h << 13;
      h ^= h >>> 17;
      h ^= h << 5;
      hashHolder[0] = h;
    }
  }
}
