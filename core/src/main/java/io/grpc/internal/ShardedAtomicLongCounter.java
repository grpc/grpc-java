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

import java.util.concurrent.atomic.AtomicLong;

/**
 * An implementation of {@link LongCounter} that works by sharded across an array of
 * {@link AtomicLong} objects. Do not instantiate directly, instead use {@link LongCounterFactory}.
 */
final class ShardedAtomicLongCounter implements LongCounter {
  private final AtomicLong[] counters;

  ShardedAtomicLongCounter(int numShards) {
    counters = new AtomicLong[numShards];
    for (int i = 0; i < numShards; i++) {
      counters[i] = new AtomicLong();
    }
  }

  @Override
  public void add(long delta) {
    counters[(int) (Thread.currentThread().getId() % counters.length)].getAndAdd(delta);
  }

  @Override
  public long value() {
    long val = 0;
    for (AtomicLong counter : counters) {
      val += counter.get();
    }
    return val;
  }
}
