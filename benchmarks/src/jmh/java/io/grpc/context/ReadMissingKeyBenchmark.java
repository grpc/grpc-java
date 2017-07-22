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

import com.google.common.collect.Iterators;
import com.google.common.collect.Lists;
import io.grpc.Context;
import io.grpc.Context.Key;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

@State(Scope.Benchmark)
public class ReadMissingKeyBenchmark {
  /**
   * The number of iterations the workload should run for. An iteration is defined as a series
   * of puts and a series of gets.
   */
  @Param({"1", "5", "20"})
  public int iterations;

  /**
   * If true we will use the 4-pair batch put, otherwise puts will be individual.
   */
  @Param({"true", "false"})
  public boolean batched;

  /**
   * Number of possible present keys.
   */
  @Param({"4", "10"})
  public int numKeys;

  /**
   * The percentage of time we will query for an missing key.
   */
  @Param("0.50")
  public double missingKeyPct;

  /**
   * Number of keys to put for each iteration.
   */
  @Param({"1", "4", "10"})
  public int writesPerIteration;

  /**
   * Number of keys to read for each iteration.
   */
  @Param({"10"})
  public int readsPerIteration;

  // Context only supports a 4-pair put function
  private static final int CONTEXT_MAX_BATCH_SIZE = 4;
  private static final int DUMMY_VAL = 1;
  private static final int WORKLOAD_BUFFER_SIZE = 200;
  // Just an arbitrarily chosen number to avoid always reading the same key.
  private static final int NUM_MISSING_KEYS = 20;

  private final Random rand = new Random();
  private Iterator<List<Key<Integer>>> writeWorkload;
  // Pre-batch the bulk put workload to avoid unnecessary allocation during benchmarks
  private Iterator<List<List<Key<Integer>>>> writeWorkloadBatched;
  private Iterator<List<Key<Integer>>> readWorkload;


  @Setup
  public void setUp() throws ExecutionException, InterruptedException, IOException {
    rand.setSeed(0); // for workload determinism
    List<Key<Integer>> presentKeys = makeKeys("_p", numKeys);
    List<Key<Integer>> missingKeys = makeKeys("_m", NUM_MISSING_KEYS);

    // Create a buffer of random read keys and write keys, then partition them up into
    // the appropriately sized chunks for the test parameters.
    List<Key<Integer>> writeKeys = new ArrayList<Key<Integer>>();
    List<Key<Integer>> readKeys = new ArrayList<Key<Integer>>();
    for (int i = 0; i < WORKLOAD_BUFFER_SIZE; i++) {
      writeKeys.add(randomPick(presentKeys));
      readKeys.add(randomPick(rand.nextDouble() < missingKeyPct ? missingKeys : presentKeys));
    }
    List<List<Key<Integer>>> writesPerIter = Lists.partition(writeKeys, writesPerIteration);
    writeWorkload = Iterators.cycle(writesPerIter);

    List<List<List<Key<Integer>>>> writesPerIterbatched = new ArrayList<List<List<Key<Integer>>>>();
    for (List<Key<Integer>> writeIter : writesPerIter) {
      writesPerIterbatched.add(Lists.partition(writeIter, CONTEXT_MAX_BATCH_SIZE));
    }
    writeWorkloadBatched = Iterators.cycle(writesPerIterbatched);

    readWorkload = Iterators.cycle(Lists.partition(readKeys, readsPerIteration));
  }

  /**
   * Missing keys are typically the slow path for Contexts.
   */
  @Benchmark
  @BenchmarkMode(Mode.SampleTime)
  @OutputTimeUnit(TimeUnit.NANOSECONDS)
  public int readWrite() throws ExecutionException, InterruptedException {
    int dummy = 0;
    Context c = Context.ROOT;
    for (int i = 0; i < iterations; i++) {
      if (batched) {
        for (List<Key<Integer>> keys : writeWorkloadBatched.next()) {
          c = batchPut(c, keys, DUMMY_VAL);
        }
      } else {
        for (Key<Integer> key : writeWorkload.next()) {
          c = c.withValue(key, DUMMY_VAL);
        }
      }
      for (Key<Integer> r : readWorkload.next()) {
        Integer val = r.get(c);
        // Not important what we do here as long as we prevent the JIT from throwing away code
        dummy += (val == null ? 0 : val);
      }
    }
    // Return a result so that the JIT can not optimize us into oblivion
    return dummy;
  }

  private Key<Integer> randomPick(List<Key<Integer>> source) {
    return source.get(rand.nextInt(source.size()));
  }

  /**
   * Creates a list of unique keys.
   */
  private List<Key<Integer>> makeKeys(String suffix, int numKeys) {
    Set<Key<Integer>> keys = new HashSet<Key<Integer>>();
    while (keys.size() < numKeys) {
      String randomStr = String.format("%x", rand.nextInt(1024 * 1024));
      Key<Integer> key = Context.key(String.format("%s_%s", randomStr, suffix));
      keys.add(key);
    }
    return new ArrayList<Key<Integer>>(keys);
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
        throw new AssertionError("Batch size should be between 1 and 4");
    }
  }
}
