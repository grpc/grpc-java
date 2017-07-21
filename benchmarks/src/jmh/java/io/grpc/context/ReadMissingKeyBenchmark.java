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

package io.grpc.context;

import com.google.common.collect.Lists;
import io.grpc.Context;
import io.grpc.Context.Key;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.GroupThreads;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

@State(Scope.Benchmark)
public class ReadMissingKeyBenchmark {
  /**
   * The number of times to insert the set of keys.
   */
  @Param({"1", "5", "20"})
  public int putIterations;

  /**
   * If true we will use the 4-pair batch put, otherwise puts will be individual.
   */
  @Param({"true", "false"})
  public boolean batched;

  /**
   * Number of keys to put for each iteration.
   */
  @Param({"1", "4", "10"})
  public int numKeys;

  // Context only supports a 4-pair put function
  private static final int CONTEXT_MAX_BATCH_SIZE = 4;
  private static final int DUMMY_VAL = 1;

  private Context contextChain = Context.ROOT;
  private final Key<Integer> missingKey = Context.key("missing_key");

  @Setup
  public void setUp() throws ExecutionException, InterruptedException {
    final List<Key<Integer>> keys = makeKeys(numKeys);
    final List<List<Key<Integer>>> keyBatches = Lists.partition(keys, CONTEXT_MAX_BATCH_SIZE);
    for (int i = 0; i < putIterations; i++) {
      if (batched) {
        for (Key<Integer> key : keys) {
          contextChain = contextChain.withValue(key, DUMMY_VAL);
        }
      } else {
        for (List<Key<Integer>> batch : keyBatches) {
          contextChain = batchPut(contextChain, batch, DUMMY_VAL);
        }
      }
    }
  }

  /**
   * Missing keys are typically the slow path for Contexts.
   */
  @Benchmark
  @BenchmarkMode(Mode.SampleTime)
  @OutputTimeUnit(TimeUnit.NANOSECONDS)
  @GroupThreads(6)
  public void readMissingKey() throws ExecutionException, InterruptedException {
    missingKey.get(contextChain);
  }

  /**
   * Creates a list of unique keys.
   */
  private static List<Key<Integer>> makeKeys(int numKeys) {
    List<Key<Integer>> keys = new ArrayList<Key<Integer>>();
    for (int k = 0; k < numKeys; k++) {
      Key<Integer> key = Context.key(String.format("key_%d", k));
      keys.add(key);
    }
    return keys;
  }

  /**
   * Returns a new Context based off of `c` where all keys from `batch` are inserted with `val`.
   */
  private static Context batchPut(Context c, List<Key<Integer>> batch, int val) {
    switch (batch.size()) {
      case 4:
        return c.withValues(
            batch.get(0), val,
            batch.get(1), val,
            batch.get(2), val,
            batch.get(3), val);
      case 3:
        return c.withValues(
            batch.get(0), val,
            batch.get(1), val,
            batch.get(2), val);
      case 2:
        return c.withValues(
            batch.get(0), val,
            batch.get(1), val);
      case 1:
        return c.withValue(batch.get(0), val);
      default:
        throw new RuntimeException();
    }
  }
}
