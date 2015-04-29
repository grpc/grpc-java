/*
 * Copyright 2014, Google Inc. All rights reserved.
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
package io.grpc.benchmarks.netty;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Benchmark using configuration intended to allow maximum QPS.
 */
@State(Scope.Benchmark)
@Fork(1)
public class MaxQpsBenchmark extends AbstractBenchmark {

  @Param({"1", "2", "4", "8", "16", "32"})
  public int channelCount = 4;

  @Param({"10", "100", "1000"})
  public int maxConcurrentStreams = 100;

  private AtomicLong callCounter;
  private long localCount;
  private AtomicBoolean completed;

  @Setup(Level.Trial)
  public void setup() throws Exception {
    super.setup(ExecutorType.DIRECT,
        ExecutorType.DIRECT,
        PayloadSize.SMALL,
        PayloadSize.SMALL,
        FlowWindowSize.LARGE,
        ChannelType.NIO,
        maxConcurrentStreams,
        channelCount);
    callCounter = new AtomicLong();
    completed = new AtomicBoolean();
    startUnaryCalls(maxConcurrentStreams, callCounter, completed);
  }

  @TearDown(Level.Trial)
  public void stopChannelsAndServers() throws Exception {
    completed.set(true);
    Thread.sleep(5000);
    super.stopChannelsAndServers();
  }

  @Benchmark
  public void measureUnary() throws Exception {
    while (localCount == 0) {
      Thread.sleep(10);
      localCount = callCounter.getAndSet(0);
    }
    localCount--;
  }

  /**
   * Useful for triggering a subset of the benchmark in a profiler.
   */
  public static void main(String[] argv) throws Exception {
    MaxQpsBenchmark bench = new MaxQpsBenchmark();
    bench.setup();
    Thread.sleep(30000);
    bench.stopChannelsAndServers();
    System.exit(0);
  }
}
