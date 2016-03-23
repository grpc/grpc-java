/*
 * Copyright 2016, Google Inc. All rights reserved.
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

package io.grpc.benchmarks.driver;

import io.grpc.ManagedChannel;
import io.grpc.benchmarks.Transport;
import io.grpc.benchmarks.Utils;
import io.grpc.benchmarks.proto.BenchmarkServiceGrpc;
import io.grpc.benchmarks.proto.Control;
import io.grpc.benchmarks.proto.Messages;
import io.grpc.benchmarks.proto.Payloads;
import io.grpc.benchmarks.proto.Stats;
import io.grpc.internal.ManagedChannelImpl;
import io.grpc.stub.StreamObserver;
import io.netty.util.concurrent.DefaultThreadFactory;

import org.HdrHistogram.Histogram;
import org.HdrHistogram.LogarithmicIterator;
import org.HdrHistogram.Recorder;
import org.apache.commons.math3.distribution.AbstractIntegerDistribution;
import org.apache.commons.math3.distribution.EnumeratedIntegerDistribution;
import org.apache.commons.math3.distribution.PoissonDistribution;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/**
 * Implements the client-side contract for the load testing scenarios.
 */
class LoadClient {

  Control.ClientConfig config;
  AbstractIntegerDistribution distribution;
  final AtomicBoolean shutdown = new AtomicBoolean();
  final int threadCount;

  ManagedChannel[] channels;
  BenchmarkServiceGrpc.BenchmarkServiceBlockingStub[] blockingStubs;
  BenchmarkServiceGrpc.BenchmarkServiceStub[] asyncStubs;
  Recorder recorder;
  private ExecutorService fixedThreadPool;
  private final Messages.SimpleRequest simpleRequest;

  LoadClient(Control.ClientConfig config) throws Exception {
    this.config = config;
    // Create the chanels
    channels = new ManagedChannelImpl[config.getClientChannels()];
    for (int i = 0; i < config.getClientChannels(); i++) {
      channels[i] =
          Utils.newClientChannel(Transport.NETTY_EPOLL,
              Utils.parseSocketAddress(config.getServerTargets(i % config.getServerTargetsCount())),
              config.hasSecurityParams(),
              config.hasSecurityParams() && config.getSecurityParams().getUseTestCa(),
              config.hasSecurityParams()
                  ? config.getSecurityParams().getServerHostOverride() :
                  null,
              true,
              Utils.DEFAULT_FLOW_CONTROL_WINDOW,
              false);
    }

    // Create a stub per channel
    if (config.getClientType() == Control.ClientType.ASYNC_CLIENT) {
      asyncStubs = new BenchmarkServiceGrpc.BenchmarkServiceStub[channels.length];
      for (int i = 0; i < channels.length; i++) {
        asyncStubs[i] = BenchmarkServiceGrpc.newStub(channels[i]);
      }
    } else {
      blockingStubs = new BenchmarkServiceGrpc.BenchmarkServiceBlockingStub[channels.length];
      for (int i = 0; i < channels.length; i++) {
        blockingStubs[i] = BenchmarkServiceGrpc.newBlockingStub(channels[i]);
      }
    }

    // Determine no of threads
    threadCount = config.getClientType() == Control.ClientType.SYNC_CLIENT
        ? config.getOutstandingRpcsPerChannel() * config.getClientChannels() :
        config.getAsyncClientThreads();
    // Use a fixed sized pool of daemon threads.
    fixedThreadPool = Executors.newFixedThreadPool(threadCount,
        new DefaultThreadFactory("client-worker", true));

    // Create the load distribution
    if (config.getLoadParams().getClosedLoop() != null) {
      distribution = new EnumeratedIntegerDistribution(new int[]{0}, new double[]{1});
    } else if (config.getLoadParams().getPoisson() != null) {
      new PoissonDistribution(config.getLoadParams().getPoisson().getOfferedLoad() / threadCount);
    } else  {
      throw new UnsupportedOperationException();
    }

    // Create payloads
    switch (config.getPayloadConfig().getPayloadCase()) {
      case SIMPLE_PARAMS: {
        Payloads.SimpleProtoParams simpleParams = config.getPayloadConfig().getSimpleParams();
        simpleRequest = Utils.makeRequest(Messages.PayloadType.COMPRESSABLE,
            simpleParams.getReqSize(), simpleParams.getRespSize());
        break;
      }
      default: {
        // Not implemented yet
        throw new IllegalArgumentException();
      }
    }

    // Create the histogram recorder
    recorder = new Recorder(3);
  }

  /**
   * Start the load scenario.
   */
  void start() {
    Runnable r;
    for (int i = 0; i < threadCount; i++) {
      r = null;
      if (config.getClientType() == Control.ClientType.SYNC_CLIENT) {
        if (config.getRpcType() == Control.RpcType.UNARY) {
          r = new BlockingUnaryWorker(blockingStubs[i % blockingStubs.length]);
        }
      } else if (config.getClientType() == Control.ClientType.ASYNC_CLIENT) {
        if (config.getRpcType() == Control.RpcType.UNARY) {
          r = new AsyncUnaryWorker(asyncStubs[i % asyncStubs.length]);
        } else if (config.getRpcType() == Control.RpcType.STREAMING) {
          r = new AsyncPingPongWorker(asyncStubs[i % asyncStubs.length]);
        }
      }
      if (r == null) {
        throw new IllegalStateException(config.getRpcType().name()
            + " not supported for client type "
            + config.getClientType());
      }
      fixedThreadPool.execute(r);
    }
  }

  /**
   * Take a snapshot of the statistics which can be returned to the driver.
   */
  Stats.ClientStats getStats() {
    Histogram intervalHistogram = recorder.getIntervalHistogram();

    Stats.ClientStats.Builder statsBuilder = Stats.ClientStats.newBuilder();
    Stats.HistogramData.Builder latenciesBuilder = statsBuilder.getLatenciesBuilder();
    double resolution = Math.max(config.getHistogramParams().getResolution(), 0.001);
    LogarithmicIterator logIterator = new LogarithmicIterator(intervalHistogram, 1,
        1.0 + resolution);
    while (logIterator.hasNext()) {
      latenciesBuilder.addBucket((int) logIterator.next().getCountAtValueIteratedTo());
    }
    latenciesBuilder.setMaxSeen((double) intervalHistogram.getMaxValue());
    latenciesBuilder.setMinSeen((double) intervalHistogram.getMinValue());
    latenciesBuilder.setCount(intervalHistogram.getTotalCount());
    latenciesBuilder.setSum(intervalHistogram.getMean()
        * intervalHistogram.getTotalCount());
    // TODO: No support for sum of squares

    statsBuilder.setTimeElapsed((intervalHistogram.getEndTimeStamp()
        - intervalHistogram.getEndTimeStamp()));
    statsBuilder.setTimeUser(System.nanoTime());
    statsBuilder.setTimeSystem(System.nanoTime());
    return statsBuilder.build();
  }

  /**
   * Shutdown the scenario as cleanly as possible.
   */
  void shutdownNow() {
    shutdown.set(true);
    for (int i = 0; i < channels.length; i++) {
      // Initiate channel shutdown
      channels[i].shutdown();
    }
    for (int i = 0; i < channels.length; i++) {
      try {
        // Wait for channel termination
        channels[i].awaitTermination(1, TimeUnit.SECONDS);
      } catch (InterruptedException ie) {
        channels[i].shutdownNow();
      }
    }
    fixedThreadPool.shutdownNow();
  }

  /**
   * Record the event elapsed time to the histogram and delay initiation of the next event based
   * on the load distribution.
   */
  private void delay(long alreadyElapsed) {
    long nextPermitted = distribution.sample();
    recorder.recordValue(alreadyElapsed / 1000); // Record in microseconds
    if (nextPermitted > alreadyElapsed) {
      LockSupport.parkNanos(nextPermitted - alreadyElapsed);
    }
  }

  /**
   * Worker which executes blocking unary calls. Event timing is the duration between sending the
   * request and receiving the response.
   */
  class BlockingUnaryWorker implements Runnable {
    final BenchmarkServiceGrpc.BenchmarkServiceBlockingStub stub;

    private BlockingUnaryWorker(BenchmarkServiceGrpc.BenchmarkServiceBlockingStub stub) {
      this.stub = stub;
    }

    public void run() {
      long now;
      while (!shutdown.get()) {
        now = System.nanoTime();
        stub.unaryCall(simpleRequest);
        delay(System.nanoTime() - now);
      }
    }
  }

  /**
   * Worker which executes async unary calls. Event timing is the duration between sending the
   * request and receiving the response.
   */
  class AsyncUnaryWorker implements Runnable {
    final BenchmarkServiceGrpc.BenchmarkServiceStub stub;
    final Semaphore maxOutstanding = new Semaphore(config.getOutstandingRpcsPerChannel());

    AsyncUnaryWorker(BenchmarkServiceGrpc.BenchmarkServiceStub stub) {
      this.stub = stub;
    }

    @Override
    public void run() {
      while (!shutdown.get()) {
        maxOutstanding.acquireUninterruptibly();
        stub.unaryCall(simpleRequest, new StreamObserver<Messages.SimpleResponse>() {
          long now = System.nanoTime();
          @Override
          public void onNext(Messages.SimpleResponse value) {

          }

          @Override
          public void onError(Throwable t) {

          }

          @Override
          public void onCompleted() {
            delay(System.nanoTime() - now);
            maxOutstanding.release();
          }
        });
      }
    }
  }

  /**
   * Worker which executes a streaming ping-pong call. Event timing is the duration between
   * sending the ping and receiving the pong.
   */
  class AsyncPingPongWorker implements Runnable {
    final BenchmarkServiceGrpc.BenchmarkServiceStub stub;
    final Semaphore maxOutstanding = new Semaphore(config.getOutstandingRpcsPerChannel());

    AsyncPingPongWorker(BenchmarkServiceGrpc.BenchmarkServiceStub stub) {
      this.stub = stub;
    }

    @Override
    public void run() {
      while (!shutdown.get()) {
        maxOutstanding.acquireUninterruptibly();
        final AtomicReference<StreamObserver<Messages.SimpleRequest>> requestObserver =
            new AtomicReference<StreamObserver<Messages.SimpleRequest>>();
        requestObserver.set(stub.streamingCall(
            new StreamObserver<Messages.SimpleResponse>() {
              long now = System.nanoTime();

              @Override
              public void onNext(Messages.SimpleResponse value) {
                delay(System.nanoTime() - now);
                requestObserver.get().onNext(simpleRequest);
                now = System.nanoTime();
                if (shutdown.get()) {
                  requestObserver.get().onCompleted();
                }
              }

              @Override
              public void onError(Throwable t) {

              }

              @Override
              public void onCompleted() {
                maxOutstanding.release();
              }
            }));
        requestObserver.get().onNext(simpleRequest);
      }
    }
  }
}
