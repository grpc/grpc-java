/*
 * Copyright 2016 The gRPC Authors
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

package io.grpc.testing.integration;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.base.Stopwatch;
import com.google.common.primitives.Longs;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.DefaultByteBufHolder;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.channels.ClosedChannelException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * TrafficControlProxy applies latency and bandwidth limitations to a connection.
 */
@SuppressWarnings("FutureReturnValueIgnored")
public final class TrafficControlProxy {

  private static final ClosedChannelException expectedClose = new ClosedChannelException();

  private static final long NANOS_PER_SECOND = TimeUnit.SECONDS.toNanos(1);

  private static final int DEFAULT_BAND_BPS = 1024 * 1024;
  private static final int DEFAULT_DELAY_NANOS = 200 * 1000 * 1000;
  private static final Logger logger = Logger.getLogger(TrafficControlProxy.class.getName());

  // TODO: make host and ports arguments
  private final int destinationServerPort;
  private final int maxBytesPerSecond;
  private final long latencyNanos;
  private ChannelFuture bindFuture;
  private List<ChannelFuture> connectFutures = new ArrayList<>();
  private EventLoopGroup group = new NioEventLoopGroup(1);

  /**
   * Returns a new TrafficControlProxy with default bandwidth and latency.
   */
  public TrafficControlProxy(int serverPort) {
    this(serverPort, DEFAULT_BAND_BPS, DEFAULT_DELAY_NANOS, TimeUnit.NANOSECONDS);
  }

  /**
   * Returns a new TrafficControlProxy with bandwidth set to targetBPS, and latency set to
   * targetLatency in latencyUnits.
   */
  public TrafficControlProxy(
      int destinationServerPort, int maxBytesPerSecond, int targetLatency, TimeUnit latencyUnits) {
    checkArgument(maxBytesPerSecond > 0, "bad bytesPerSecond %s", maxBytesPerSecond);
    checkArgument(targetLatency > 0, "bad targetLatency %s", targetLatency);
    checkNotNull(latencyUnits, "latencyUnits");
    checkArgument(destinationServerPort > 0, "Bad serverPort %s", destinationServerPort);

    this.maxBytesPerSecond = maxBytesPerSecond;
    // divide by 2 because latency is applied in both directions
    this.latencyNanos = latencyUnits.toNanos(targetLatency) / 2;
    this.destinationServerPort = destinationServerPort;
  }

  /**
   * Starts a new thread that waits for client and server and start reader/writer threads.
   */
  public void start() throws IOException {
    bindFuture = new ServerBootstrap()
        .channel(NioServerSocketChannel.class)
        .group(group)
        .childHandler(new ChannelInitializer<Channel>() {
          @Override
          protected void initChannel(Channel childServerChannel) throws Exception {
            childServerChannel.config().setAutoRead(false);
            Stopwatch watch = Stopwatch.createStarted();
            ProxyHandler inboundHandler = new ProxyHandler(watch, latencyNanos, maxBytesPerSecond);
            final ProxyHandler outboundHandler =
                new ProxyHandler(watch, latencyNanos, maxBytesPerSecond);
            outboundHandler.next = childServerChannel;
            childServerChannel.pipeline().addLast(inboundHandler);

            ChannelFuture clientFuture = new Bootstrap()
                .group(childServerChannel.eventLoop())
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<Channel>() {

                  @Override
                  protected void initChannel(Channel clientChannel) throws Exception {
                    clientChannel.config().setAutoRead(false);

                    clientChannel.pipeline().addLast(outboundHandler);
                  }
                })
                .register();
            inboundHandler.next = clientFuture.channel();

            ChannelFuture connectFuture =
                clientFuture.channel().connect(
                    new InetSocketAddress("localhost", destinationServerPort));
            connectFutures.add(connectFuture);
          }
        })
        .bind(0);
    bindFuture.addListener(new ChannelFutureListener() {
      @Override
      public void operationComplete(ChannelFuture future) throws Exception {
        if (future.isSuccess()) {
          Channel chan = future.channel();
          logger.info("Started new proxy on port " + chan.localAddress());
        } else {
          logger.log(Level.SEVERE, "Failed to bind", future.cause());
        }
      }
    });
  }

  /**
   * Gets the listening port.
   */
  public int getPort() {
    try {
      bindFuture.sync();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException(e);
    }

    SocketAddress addr = bindFuture.channel().localAddress();
    return ((InetSocketAddress) addr).getPort();
  }

  private static final class ProxyHandler extends ChannelDuplexHandler {

    private final Stopwatch watch;
    private final long latencyNanos;
    private final int maxBytesPerSecond;
    private final int maxBloat;
    private int currentBloat;
    Channel next; // set just after construction

    // state
    private final Queue<QueuedWrite> bufsToWrite = new ArrayDeque<>();
    private long absLastWriteNanos;
    private int availableBytes;

    ProxyHandler(Stopwatch watch, long latencyNanos, int maxBytesPerSecond) {
      this.watch = watch;
      this.latencyNanos = latencyNanos;
      this.maxBytesPerSecond = maxBytesPerSecond;
      this.maxBloat = maxBytesPerSecond * 2;
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
      ByteBuf buf = (ByteBuf) msg;
      currentBloat += buf.readableBytes();
      if (currentBloat < maxBloat) {
        next.read();
      }
      bufsToWrite.add(new QueuedWrite(
          buf,
          promise,
          watch.elapsed(TimeUnit.NANOSECONDS) + latencyNanos));
    }

    @Override
    public void flush(ChannelHandlerContext ctx) {
      schedule(ctx);
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
      super.channelActive(ctx);
      ctx.read();
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
      super.handlerAdded(ctx);
      ctx.read();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
      next.writeAndFlush(msg).addListener(new LogAndClose());
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
      next.close();
      // TODO(carl-mastrangelo): maybe fail the promises.
      super.channelInactive(ctx);
    }

    private void schedule(final ChannelHandlerContext ctx) {
      QueuedWrite peeked = bufsToWrite.peek();
      if (peeked == null) {
        ctx.flush();
        return;
      }
      ctx.channel().eventLoop().schedule(new Runnable() {
        @Override
        public void run() {
          writeAsMuchAsPossible(ctx);
        }
      }, peeked.absWriteAtNanos - watch.elapsed(TimeUnit.NANOSECONDS), TimeUnit.NANOSECONDS);
    }

    private void writeAsMuchAsPossible(ChannelHandlerContext ctx) {
      if (!ctx.channel().isOpen()) {
        QueuedWrite write;
        while ((write = bufsToWrite.poll()) != null) {
          write.release();
          write.promise.tryFailure(expectedClose);
        }
        return;
      }
      QueuedWrite peekedWrite = bufsToWrite.peek();

      while (peekedWrite != null) {
        long absNanoTime = watch.elapsed(TimeUnit.NANOSECONDS);
        if (peekedWrite.absWriteAtNanos <= absNanoTime) {
          int desiredBytes = peekedWrite.content().readableBytes();
          if (desiredBytes == 0) {
            bufsToWrite.poll().release();
            peekedWrite = bufsToWrite.peek();
            continue;
          }
          int writableBytes = writableBytes(absNanoTime);
          if (writableBytes > 0) {
            final int toWrite = Math.min(writableBytes, desiredBytes);
            availableBytes = Math.max(availableBytes - toWrite, 0);
            absLastWriteNanos = absNanoTime;
            ChannelFuture flushed;
            if (toWrite == desiredBytes) {
              flushed = ctx.writeAndFlush(peekedWrite.content(), peekedWrite.promise);
              bufsToWrite.poll();
              peekedWrite = bufsToWrite.peek();
            } else {
              flushed = ctx.writeAndFlush(peekedWrite.content().readRetainedSlice(toWrite))
                  .addListener(new LogAndClose());
            }
            flushed.addListener(new ChannelFutureListener() {
              @Override
              public void operationComplete(ChannelFuture future) {
                if (future.isSuccess()) {
                  currentBloat -= toWrite;
                  if (currentBloat < maxBloat) {
                    next.read();
                  }
                }
              }
            });
            continue;
          }
        }
        schedule(ctx);
        return;
      }
    }

    private int writableBytes(long absNanos) {
      int baseBytes = availableBytes;
      int additionalBytesPerSecond = 0;
      if (absNanos > absLastWriteNanos) {
        long nanosSinceLastWrite = Math.min(absNanos - absLastWriteNanos, NANOS_PER_SECOND);
        assert Long.MAX_VALUE / maxBytesPerSecond > nanosSinceLastWrite;
        long nanoBytesSinceLastWrite = nanosSinceLastWrite * maxBytesPerSecond;
        additionalBytesPerSecond = (int) Math.min(
            nanoBytesSinceLastWrite / NANOS_PER_SECOND, Integer.MAX_VALUE - baseBytes);
      }
      return baseBytes + additionalBytesPerSecond;
    }
  }

  /** Interrupt all workers and close sockets. */
  public void shutDown() {
    logger.info("Proxy shutting down... ");
    final CountDownLatch latch = new CountDownLatch(1 + connectFutures.size());
    final class CloseListener implements ChannelFutureListener {

      @Override
      public void operationComplete(ChannelFuture future) {
        latch.countDown();
        if (future.isSuccess()) {
          logger.log(Level.FINE, "Shutdown Complete of {}", future.channel().localAddress());
        } else {
          logger.log(Level.SEVERE, "failed to close proxy", future.cause());
        }
      }
    }

    bindFuture.channel().close().addListener(new ChannelFutureListener() {

      @Override
      public void operationComplete(ChannelFuture future) {

        if (future.isSuccess()) {
          for (ChannelFuture cf : connectFutures) {
            cf.channel().close().addListener(new CloseListener());
          }
        }
      }
    }).addListener(new CloseListener());

    try {
      latch.await(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException(e);
    } finally {
      group.shutdownGracefully();
    }
  }

  private static final class LogAndClose implements ChannelFutureListener {

    @Override
    public void operationComplete(ChannelFuture future) {
      if (!future.isSuccess() && future.cause() != expectedClose) {
        logger.log(Level.SEVERE, "FAILURE", future.cause());
        future.channel().close();
      }
    }
  }

  private static final class QueuedWrite extends DefaultByteBufHolder
      implements Comparable<QueuedWrite> {

    private final long absWriteAtNanos;
    final ChannelPromise promise;

    QueuedWrite(ByteBuf data, ChannelPromise promise, long absWriteAtNanos) {
      super(data);
      this.promise = promise;
      this.absWriteAtNanos = absWriteAtNanos;
    }

    @Override
    public int compareTo(QueuedWrite o) {
      return Longs.compare(absWriteAtNanos, o.absWriteAtNanos);
    }
  }
}
