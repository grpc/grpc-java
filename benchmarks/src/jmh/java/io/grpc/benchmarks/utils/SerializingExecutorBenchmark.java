/*
 * Copyright 2015, Google Inc. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 *    * Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 *    * Redistributions in binary form must reproduce the above
 * copyright notice, this list of conditions and the following disclaimer
 * in the documentation and/or other materials provided with the
 * distribution.
 *
 *    * Neither the name of Google Inc. nor the names of its
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package io.grpc.benchmarks.utils;

import io.grpc.internal.SerializingExecutor;

import org.openjdk.jmh.annotations.AuxCounters;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Simple benchmark for {@link io.grpc.internal.SerializingExecutor}.
 *
 * <p>WARNING: These benchmarks use highly-synthetic workloads and are only useful as quick
 * comparisons between implementation variations of SerializingExecutor during iteration. Other
 * benchmarks such as {@link io.grpc.benchmarks.netty.FlowControlledMessagesPerSecondBenchmark}
 * provide a more useful real-world test.
 */
@State(Scope.Benchmark)
@Warmup(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 20, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
public class SerializingExecutorBenchmark {

  public static final Runnable DO_WORK = new Runnable() {
    @Override
    public void run() {
      Blackhole.consumeCPU(50);
      final long currentId = Thread.currentThread().getId();
      if (currentId != lastThreadId) {
        threadHops += (lastThreadId == -1) ? 0 : 1;
        lastThreadId = currentId;
      }
      processedCount++;
    }
  };

  public static final Runnable NO_OP = new Runnable() {
    @Override
    public void run() {
      final long currentId = Thread.currentThread().getId();
      if (currentId != lastThreadId) {
        threadHops += (lastThreadId == -1) ? 0 : 1;
        threadHops++;
        lastThreadId = currentId;
      }
      processedCount++;
    }
  };

  /**
   * Use an AuxCounter so we can measure that calls as they occur without consuming CPU
   * in the benchmark method.
   */
  @AuxCounters
  @State(Scope.Thread)
  public static class AdditionalCounters {

    @Setup(Level.Iteration)
    public void clean() {
      lastThreadId = -1;
      threadHops = 0;
      processedCount = 0;
    }

    //Note that this is really the only metric worth looking at because it represents the
    //work done.
    public long runnablesProcessed() {
      return processedCount;
    }

    public int threadHops() {
      return threadHops;
    }
  }

  private static long lastThreadId;
  private static int threadHops;
  private static long processedCount;

  private SerializingExecutor serializingExecutor;
  private final Semaphore semaphore = new Semaphore(0);

  private Runnable notifier = new Runnable() {
    @Override
    public void run() {
      semaphore.release();
    }
  };

  private void doWait() throws InterruptedException {
    serializingExecutor.execute(notifier);
    semaphore.acquireUninterruptibly();
  }

  @Setup(Level.Trial)
  public void setup() {
    serializingExecutor = new SerializingExecutor(Executors.newFixedThreadPool(4,
        new ThreadFactory() {
          @Override
          public Thread newThread(Runnable r) {
            Thread thread = new Thread(r);
            thread.setDaemon(true);
            return thread;
          }
        }));
    processedCount = 0;
    lastThreadId = -1;
    threadHops = 0;
  }

  /**
   * Add a lot of no-op messages in a burst and then wait for completion immediately.
   */
  @Benchmark
  public void enqueueBurstNoOpThenDrain(AdditionalCounters counters) throws Exception {
    lastThreadId = -1;
    for (int i = 0; i < 100; i++) {
      serializingExecutor.execute(NO_OP);
    }
    doWait();
  }

  /**
   * Add a lot of no-op messages and then wait for completion.
   */
  @Benchmark
  public void enqueueNoOpThenDrain(AdditionalCounters counters) throws Exception {
    lastThreadId = -1;
    for (int i = 0; i < 100; i++) {
      serializingExecutor.execute(NO_OP);
      Blackhole.consumeCPU(50);
    }
    doWait();
  }

  /**
   * Add a lot of cpu consuming messages in a burst and then wait for completion.
   */
  @Benchmark
  public void enqueueBurstWorkThenDrain(AdditionalCounters counters) throws Exception {
    lastThreadId = -1;
    for (int i = 0; i < 100; i++) {
      serializingExecutor.execute(DO_WORK);
    }
    doWait();
  }

  /**
   * Add a lot of cpu consuming messages then wait for completion.
   */
  @Benchmark
  public void enqueueWorkThenDrain(AdditionalCounters counters) throws Exception {
    lastThreadId = -1;
    for (int i = 0; i < 100; i++) {
      serializingExecutor.execute(DO_WORK);
      Blackhole.consumeCPU(50);
    }
    doWait();
  }
}
