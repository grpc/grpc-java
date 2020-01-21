/*
 * Copyright 2014 The gRPC Authors
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

package io.grpc.netty;

import static com.google.common.base.Preconditions.checkNotNull;
import static io.grpc.netty.NettyServerBuilder.MAX_CONNECTION_AGE_NANOS_DISABLED;
import static io.netty.channel.ChannelOption.ALLOCATOR;
import static io.netty.channel.ChannelOption.SO_BACKLOG;
import static io.netty.channel.ChannelOption.SO_KEEPALIVE;

import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import io.grpc.InternalChannelz;
import io.grpc.InternalChannelz.SocketStats;
import io.grpc.InternalInstrumented;
import io.grpc.InternalLogId;
import io.grpc.InternalWithLogId;
import io.grpc.ServerStreamTracer;
import io.grpc.internal.InternalServer;
import io.grpc.internal.ObjectPool;
import io.grpc.internal.ServerListener;
import io.grpc.internal.ServerTransportListener;
import io.grpc.internal.TransportTracer;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFactory;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.ServerChannel;
import io.netty.util.AbstractReferenceCounted;
import io.netty.util.ReferenceCounted;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import java.io.IOException;
import java.net.SocketAddress;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Netty-based server implementation.
 */
class NettyServer implements InternalServer, InternalWithLogId {
  private static final Logger log = Logger.getLogger(InternalServer.class.getName());

  private final InternalLogId logId;
  private final SocketAddress address;
  private final ChannelFactory<? extends ServerChannel> channelFactory;
  private final Map<ChannelOption<?>, ?> channelOptions;
  private final ProtocolNegotiator protocolNegotiator;
  private final int maxStreamsPerConnection;
  private final ObjectPool<? extends EventLoopGroup> bossGroupPool;
  private final ObjectPool<? extends EventLoopGroup> workerGroupPool;
  private final boolean forceHeapBuffer;
  private EventLoopGroup bossGroup;
  private EventLoopGroup workerGroup;
  private ServerListener listener;
  private Channel channel;
  private final int flowControlWindow;
  private final int maxMessageSize;
  private final int maxHeaderListSize;
  private final long keepAliveTimeInNanos;
  private final long keepAliveTimeoutInNanos;
  private final long maxConnectionIdleInNanos;
  private final long maxConnectionAgeInNanos;
  private final long maxConnectionAgeGraceInNanos;
  private final boolean permitKeepAliveWithoutCalls;
  private final long permitKeepAliveTimeInNanos;
  private final ReferenceCounted sharedResourceReferenceCounter =
      new SharedResourceReferenceCounter();
  private final List<? extends ServerStreamTracer.Factory> streamTracerFactories;
  private final TransportTracer.Factory transportTracerFactory;
  private final InternalChannelz channelz;
  // Only modified in event loop but safe to read any time.
  private volatile InternalInstrumented<SocketStats> listenSocketStats;

  NettyServer(
      SocketAddress address, ChannelFactory<? extends ServerChannel> channelFactory,
      Map<ChannelOption<?>, ?> channelOptions,
      ObjectPool<? extends EventLoopGroup> bossGroupPool,
      ObjectPool<? extends EventLoopGroup> workerGroupPool,
      boolean forceHeapBuffer,
      ProtocolNegotiator protocolNegotiator,
      List<? extends ServerStreamTracer.Factory> streamTracerFactories,
      TransportTracer.Factory transportTracerFactory,
      int maxStreamsPerConnection, int flowControlWindow, int maxMessageSize, int maxHeaderListSize,
      long keepAliveTimeInNanos, long keepAliveTimeoutInNanos,
      long maxConnectionIdleInNanos,
      long maxConnectionAgeInNanos, long maxConnectionAgeGraceInNanos,
      boolean permitKeepAliveWithoutCalls, long permitKeepAliveTimeInNanos,
      InternalChannelz channelz) {
    this.address = address;
    this.channelFactory = checkNotNull(channelFactory, "channelFactory");
    checkNotNull(channelOptions, "channelOptions");
    this.channelOptions = new HashMap<ChannelOption<?>, Object>(channelOptions);
    this.bossGroupPool = checkNotNull(bossGroupPool, "bossGroupPool");
    this.workerGroupPool = checkNotNull(workerGroupPool, "workerGroupPool");
    this.forceHeapBuffer = forceHeapBuffer;
    this.bossGroup = bossGroupPool.getObject();
    this.workerGroup = workerGroupPool.getObject();
    this.protocolNegotiator = checkNotNull(protocolNegotiator, "protocolNegotiator");
    this.streamTracerFactories = checkNotNull(streamTracerFactories, "streamTracerFactories");
    this.transportTracerFactory = transportTracerFactory;
    this.maxStreamsPerConnection = maxStreamsPerConnection;
    this.flowControlWindow = flowControlWindow;
    this.maxMessageSize = maxMessageSize;
    this.maxHeaderListSize = maxHeaderListSize;
    this.keepAliveTimeInNanos = keepAliveTimeInNanos;
    this.keepAliveTimeoutInNanos = keepAliveTimeoutInNanos;
    this.maxConnectionIdleInNanos = maxConnectionIdleInNanos;
    this.maxConnectionAgeInNanos = maxConnectionAgeInNanos;
    this.maxConnectionAgeGraceInNanos = maxConnectionAgeGraceInNanos;
    this.permitKeepAliveWithoutCalls = permitKeepAliveWithoutCalls;
    this.permitKeepAliveTimeInNanos = permitKeepAliveTimeInNanos;
    this.channelz = Preconditions.checkNotNull(channelz);
    this.logId =
        InternalLogId.allocate(getClass(), address != null ? address.toString() : "No address");
  }

  @Override
  public SocketAddress getListenSocketAddress() {
    if (channel == null) {
      // server is not listening/bound yet, just return the original port.
      return address;
    }
    return channel.localAddress();
  }

  @Override
  public InternalInstrumented<SocketStats> getListenSocketStats() {
    return listenSocketStats;
  }

  @Override
  public void start(ServerListener serverListener) throws IOException {
    listener = checkNotNull(serverListener, "serverListener");

    ServerBootstrap b = new ServerBootstrap();
    b.option(ALLOCATOR, Utils.getByteBufAllocator(forceHeapBuffer));
    b.childOption(ALLOCATOR, Utils.getByteBufAllocator(forceHeapBuffer));
    b.group(bossGroup, workerGroup);
    b.channelFactory(channelFactory);
    // For non-socket based channel, the option will be ignored.
    b.option(SO_BACKLOG, 128);
    b.childOption(SO_KEEPALIVE, true);

    if (channelOptions != null) {
      for (Map.Entry<ChannelOption<?>, ?> entry : channelOptions.entrySet()) {
        @SuppressWarnings("unchecked")
        ChannelOption<Object> key = (ChannelOption<Object>) entry.getKey();
        b.childOption(key, entry.getValue());
      }
    }

    b.childHandler(new ChannelInitializer<Channel>() {
      @Override
      public void initChannel(Channel ch) {

        ChannelPromise channelDone = ch.newPromise();

        long maxConnectionAgeInNanos = NettyServer.this.maxConnectionAgeInNanos;
        if (maxConnectionAgeInNanos != MAX_CONNECTION_AGE_NANOS_DISABLED) {
          // apply a random jitter of +/-10% to max connection age
          maxConnectionAgeInNanos =
              (long) ((.9D + Math.random() * .2D) * maxConnectionAgeInNanos);
        }

        NettyServerTransport transport =
            new NettyServerTransport(
                ch,
                channelDone,
                protocolNegotiator,
                streamTracerFactories,
                transportTracerFactory.create(),
                maxStreamsPerConnection,
                flowControlWindow,
                maxMessageSize,
                maxHeaderListSize,
                keepAliveTimeInNanos,
                keepAliveTimeoutInNanos,
                maxConnectionIdleInNanos,
                maxConnectionAgeInNanos,
                maxConnectionAgeGraceInNanos,
                permitKeepAliveWithoutCalls,
                permitKeepAliveTimeInNanos);
        ServerTransportListener transportListener;
        // This is to order callbacks on the listener, not to guard access to channel.
        synchronized (NettyServer.this) {
          if (channel != null && !channel.isOpen()) {
            // Server already shutdown.
            ch.close();
            return;
          }
          // `channel` shutdown can race with `ch` initialization, so this is only safe to increment
          // inside the lock.
          sharedResourceReferenceCounter.retain();
          transportListener = listener.transportCreated(transport);
        }

        /**
         * Releases the event loop if the channel is "done", possibly due to the channel closing.
         */
        final class LoopReleaser implements ChannelFutureListener {
          private boolean done;

          @Override
          public void operationComplete(ChannelFuture future) throws Exception {
            if (!done) {
              done = true;
              sharedResourceReferenceCounter.release();
            }
          }
        }

        transport.start(transportListener);
        ChannelFutureListener loopReleaser = new LoopReleaser();
        channelDone.addListener(loopReleaser);
        ch.closeFuture().addListener(loopReleaser);
      }
    });
    // Bind and start to accept incoming connections.
    ChannelFuture future = b.bind(address);
    try {
      future.await();
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("Interrupted waiting for bind");
    }
    if (!future.isSuccess()) {
      throw new IOException("Failed to bind", future.cause());
    }
    channel = future.channel();
    channel.eventLoop().execute(new Runnable() {
      @Override
      public void run() {
        listenSocketStats = new ListenSocket(channel);
        channelz.addListenSocket(listenSocketStats);
      }
    });
  }

  @Override
  public void shutdown() {
    if (channel == null || !channel.isOpen()) {
      // Already closed.
      return;
    }
    channel.close().addListener(new ChannelFutureListener() {
      @Override
      public void operationComplete(ChannelFuture future) throws Exception {
        if (!future.isSuccess()) {
          log.log(Level.WARNING, "Error shutting down server", future.cause());
        }
        InternalInstrumented<SocketStats> stats = listenSocketStats;
        listenSocketStats = null;
        if (stats != null) {
          channelz.removeListenSocket(stats);
        }
        sharedResourceReferenceCounter.release();
        protocolNegotiator.close();
        synchronized (NettyServer.this) {
          listener.serverShutdown();
        }
      }
    });
    try {
      channel.closeFuture().await();
    } catch (InterruptedException e) {
      log.log(Level.FINE, "Interrupted while shutting down", e);
      Thread.currentThread().interrupt();
    }
  }

  @Override
  public InternalLogId getLogId() {
    return logId;
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
        .add("logId", logId.getId())
        .add("address", address)
        .toString();
  }

  class SharedResourceReferenceCounter extends AbstractReferenceCounted {
    @Override
    protected void deallocate() {
      try {
        if (bossGroup != null) {
          bossGroupPool.returnObject(bossGroup);
        }
      } finally {
        bossGroup = null;
        try {
          if (workerGroup != null) {
            workerGroupPool.returnObject(workerGroup);
          }
        } finally {
          workerGroup = null;
        }
      }
    }

    @Override
    public ReferenceCounted touch(Object hint) {
      return this;
    }
  }

  /**
   * A class that can answer channelz queries about the server listen sockets.
   */
  private static final class ListenSocket implements InternalInstrumented<SocketStats> {
    private final InternalLogId id;
    private final Channel ch;

    ListenSocket(Channel ch) {
      this.ch = ch;
      this.id = InternalLogId.allocate(getClass(), String.valueOf(ch.localAddress()));
    }

    @Override
    public ListenableFuture<SocketStats> getStats() {
      final SettableFuture<SocketStats> ret = SettableFuture.create();
      if (ch.eventLoop().inEventLoop()) {
        // This is necessary, otherwise we will block forever if we get the future from inside
        // the event loop.
        ret.set(new SocketStats(
            /*data=*/ null,
            ch.localAddress(),
            /*remote=*/ null,
            Utils.getSocketOptions(ch),
            /*security=*/ null));
        return ret;
      }
      ch.eventLoop()
          .submit(
              new Runnable() {
                @Override
                public void run() {
                  ret.set(new SocketStats(
                      /*data=*/ null,
                      ch.localAddress(),
                      /*remote=*/ null,
                      Utils.getSocketOptions(ch),
                      /*security=*/ null));
                }
              })
          .addListener(
              new GenericFutureListener<Future<Object>>() {
                @Override
                public void operationComplete(Future<Object> future) throws Exception {
                  if (!future.isSuccess()) {
                    ret.setException(future.cause());
                  }
                }
              });
      return ret;
    }

    @Override
    public InternalLogId getLogId() {
      return id;
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("logId", id.getId())
          .add("channel", ch)
          .toString();
    }
  }
}
