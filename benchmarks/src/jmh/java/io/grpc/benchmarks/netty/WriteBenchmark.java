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

package io.grpc.benchmarks.netty;

import static io.grpc.benchmarks.netty.WriteBenchmark.ClientHandler.NO_HANDLER;

import io.grpc.netty.WriteCombiningHandler;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerAdapter;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import org.HdrHistogram.Histogram;
import org.HdrHistogram.HistogramIterationValue;
import org.openjdk.jmh.annotations.AuxCounters;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;

/**
 * foo bar.
 */
@State(Scope.Thread)
@Warmup(iterations = 1)
@Measurement(iterations = 1, time = 1)
@BenchmarkMode(Mode.Throughput)
@Fork(1)
public class WriteBenchmark {

  private EventLoopGroup eventLoop;
  private DiscardReadsHandler serverHandler;
  private Channel serverChannel;
  private Channel channel;

  private WriteTask writeTask;
  private final int numIterations = 10 * 1000;

  @Param
  public ClientHandler clientHandler;

  @Param({"1", "2", "4", "8", "16", "32", "64", "128"})
  public int numWrites;

  @Param({"128", "256", "512", "1024", "2048", "4096", "8192"})
  public int bufferSize;

  @Setup
  public void setup() throws Exception {
    ChannelHandler handler = NO_HANDLER.equals(clientHandler)
        ? new ChannelHandlerAdapter() { }
        : new WriteCombiningHandler();
    setupChannelAndEventLoop(handler);

    int[] bufferSizes = new int[numWrites];
    for (int i = 0; i < bufferSizes.length; i++) {
      bufferSizes[i] = bufferSize / numWrites;
    }
    writeTask = new ManyWritesAndFlush(channel, bufferSizes);
  }

  private void setupChannelAndEventLoop(ChannelHandler channelHandler) throws Exception {
    eventLoop = new NioEventLoopGroup(1);
    //((NioEventLoopGroup) eventLoop).setIoRatio(100);

    serverHandler = new DiscardReadsHandler();
    final ServerBootstrap sb = new ServerBootstrap();
    sb.group(eventLoop)
        .option(ChannelOption.SO_RCVBUF, 10 * 1024 * 1024)
        .channel(NioServerSocketChannel.class)
        .childHandler(serverHandler);
    serverChannel = sb.bind(0).sync().channel();

    Bootstrap cb = new Bootstrap();
    cb.group(eventLoop)
        .option(ChannelOption.SO_SNDBUF, 10 * 1024 * 1024)
        .channel(NioSocketChannel.class)
        .handler(channelHandler);

    channel = cb.connect(serverChannel.localAddress()).sync().channel();
  }

  @TearDown
  public void tearDown() throws InterruptedException {
    channel.close().sync().await(1, TimeUnit.SECONDS);
    serverChannel.close().sync().await(1, TimeUnit.SECONDS);
    eventLoop.shutdownGracefully().await(1, TimeUnit.SECONDS);
  }

  /**
   * Foo bar.
   */
  @Benchmark
  public void writeAndFlush(LatencyCounters counters) throws InterruptedException {
    writeTask.histogram(counters.histogram());
    for (int i = 0; i < numIterations; i++) {
      eventLoop.execute(writeTask);
    }

    while (numIterations != counters.histogram().getTotalCount()) {
      Thread.sleep(1000);
    }

    assert serverHandler.bytesDiscarded > 0;
  }

  @AuxCounters
  @State(Scope.Thread)
  public static class LatencyCounters {

    private Histogram hist = new Histogram(60000000L, 3);

    Histogram histogram() {
      return hist;
    }

    @Setup(Level.Iteration)
    public void clean() {
      hist.reset();
    }

    public long pctl10_nanos() {
      return hist.getValueAtPercentile(10);
    }

    public long pctl30_nanos() {
      return hist.getValueAtPercentile(30);
    }

    public long pctl50_nanos() {
      return hist.getValueAtPercentile(50);
    }

    public long pctl70_nanos() {
      return hist.getValueAtPercentile(70);
    }

    public long pctl90_nanos() {
      return hist.getValueAtPercentile(90);
    }

    public long trimmedMean_nanos() {
      return trimmedMean(hist);
    }
  }

  private static long trimmedMean(Histogram hist) {
    long sum = 0;
    int count = 0;
    for (HistogramIterationValue value : hist.recordedValues()) {
      if (value.getPercentile() > 10 && value.getPercentile() < 90) {
        sum += hist.medianEquivalentValue(value.getValueIteratedTo())
            * value.getCountAtValueIteratedTo();
        count += value.getCountAtValueIteratedTo();
      }
    }
    double mean = sum * 1.0 / count;
    return (long) mean;
  }


  private abstract static class WriteTask implements Runnable {

    final Channel channel;
    Histogram hist;

    WriteTask(Channel channel) {
      this.channel = channel;
    }

    /**
     * Needs to be the first method called.
     */
    void histogram(Histogram hist) {
      this.hist = hist;
    }

    ChannelPromise newWriteDurationRecordingPromise(final long startNanos) {
      return channel.newPromise().addListener(new GenericFutureListener<Future<? super Void>>() {
        @Override
        public void operationComplete(Future<? super Void> future) throws Exception {
          long durationNanos = System.nanoTime() - startNanos;
          hist.recordValue(100);
        }
      });
    }
  }

  private static final class OneWriteAndFlush extends WriteTask {

    final ByteBuf buffer;

    OneWriteAndFlush(Channel channel, int numBytes) {
      super(channel);
      buffer = channel.alloc().directBuffer(numBytes, numBytes).writeZero(numBytes);
    }

    @Override
    public void run() {
      final ByteBuf b1 = buffer.retainedDuplicate();
      final long start = System.nanoTime();
      channel.write(b1, newWriteDurationRecordingPromise(start));
      channel.flush();
    }
  }

  private static final class ManyWritesAndFlush extends WriteTask {

    final ByteBuf[] buffers;

    ManyWritesAndFlush(Channel channel, int[] bufferSizes) {
      super(channel);

      buffers = new ByteBuf[bufferSizes.length];
      for (int i = 0; i < bufferSizes.length; i++) {
        int bytes = bufferSizes[i];
        buffers[i] = channel.alloc().directBuffer(bytes, bytes).writeZero(bytes);
      }
    }

    @Override
    public void run() {
      final ByteBuf[] buffers0 = new ByteBuf[buffers.length];
      for (int i = 0; i < buffers0.length; i++) {
        buffers0[i] = buffers[i].retainedDuplicate();
      }
      // Start measuring
      final long start = System.nanoTime();
      for (int i = 0; i < buffers0.length - 1; i++) {
        channel.write(buffers0[i]);
      }
      channel.write(buffers0[buffers0.length - 1], newWriteDurationRecordingPromise(start));
      channel.flush();
    }
  }

  static final class DiscardReadsHandler extends ChannelDuplexHandler {

    volatile long bytesDiscarded;

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
      bytesDiscarded += ((ByteBuf) msg).readableBytes();
      ReferenceCountUtil.safeRelease(msg);
    }
  }

  public enum ClientHandler {
    NO_HANDLER, WRITE_COMBINING
  }
}
