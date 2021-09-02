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
import static io.netty.channel.ChannelOption.SO_KEEPALIVE;

import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import io.grpc.Attributes;
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
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.ServerChannel;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.ChannelGroupFuture;
import io.netty.channel.group.ChannelGroupFutureListener;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.util.AbstractReferenceCounted;
import io.netty.util.ReferenceCounted;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import java.io.IOException;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Netty-based server implementation.
 */
class NettyServer implements InternalServer, InternalWithLogId {
  private static final Logger log = Logger.getLogger(InternalServer.class.getName());

  private final InternalLogId logId;
  private final List<? extends SocketAddress> addresses;
  private final ChannelFactory<? extends ServerChannel> channelFactory;
  private final Map<ChannelOption<?>, ?> channelOptions;
  private final Map<ChannelOption<?>, ?> childChannelOptions;
  private final ProtocolNegotiator protocolNegotiator;
  private final int maxStreamsPerConnection;
  private final ObjectPool<? extends EventLoopGroup> bossGroupPool;
  private final ObjectPool<? extends EventLoopGroup> workerGroupPool;
  private final boolean forceHeapBuffer;
  private EventLoopGroup bossGroup;
  private EventLoopGroup workerGroup;
  private ServerListener listener;
  private final ChannelGroup channelGroup;
  private final boolean autoFlowControl;
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
  private final Attributes eagAttributes;
  private final ReferenceCounted sharedResourceReferenceCounter =
      new SharedResourceReferenceCounter();
  private final List<? extends ServerStreamTracer.Factory> streamTracerFactories;
  private final TransportTracer.Factory transportTracerFactory;
  private final InternalChannelz channelz;
  private volatile List<InternalInstrumented<SocketStats>> listenSocketStatsList =
      Collections.emptyList();
  private volatile boolean terminated;
  private final EventLoop bossExecutor;

  NettyServer(
      List<? extends SocketAddress> addresses,
      ChannelFactory<? extends ServerChannel> channelFactory,
      Map<ChannelOption<?>, ?> channelOptions,
      Map<ChannelOption<?>, ?> childChannelOptions,
      ObjectPool<? extends EventLoopGroup> bossGroupPool,
      ObjectPool<? extends EventLoopGroup> workerGroupPool,
      boolean forceHeapBuffer,
      ProtocolNegotiator protocolNegotiator,
      List<? extends ServerStreamTracer.Factory> streamTracerFactories,
      TransportTracer.Factory transportTracerFactory,
      int maxStreamsPerConnection, boolean autoFlowControl, int flowControlWindow,
      int maxMessageSize, int maxHeaderListSize,
      long keepAliveTimeInNanos, long keepAliveTimeoutInNanos,
      long maxConnectionIdleInNanos,
      long maxConnectionAgeInNanos, long maxConnectionAgeGraceInNanos,
      boolean permitKeepAliveWithoutCalls, long permitKeepAliveTimeInNanos,
      Attributes eagAttributes, InternalChannelz channelz) {
    this.addresses = checkNotNull(addresses, "addresses");
    this.channelFactory = checkNotNull(channelFactory, "channelFactory");
    checkNotNull(channelOptions, "channelOptions");
    this.channelOptions = new HashMap<ChannelOption<?>, Object>(channelOptions);
    checkNotNull(childChannelOptions, "childChannelOptions");
    this.childChannelOptions = new HashMap<ChannelOption<?>, Object>(childChannelOptions);
    this.bossGroupPool = checkNotNull(bossGroupPool, "bossGroupPool");
    this.workerGroupPool = checkNotNull(workerGroupPool, "workerGroupPool");
    this.forceHeapBuffer = forceHeapBuffer;
    this.bossGroup = bossGroupPool.getObject();
    this.bossExecutor = bossGroup.next();
    this.channelGroup = new DefaultChannelGroup(this.bossExecutor);
    this.workerGroup = workerGroupPool.getObject();
    this.protocolNegotiator = checkNotNull(protocolNegotiator, "protocolNegotiator");
    this.streamTracerFactories = checkNotNull(streamTracerFactories, "streamTracerFactories");
    this.transportTracerFactory = transportTracerFactory;
    this.maxStreamsPerConnection = maxStreamsPerConnection;
    this.autoFlowControl = autoFlowControl;
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
    this.eagAttributes = checkNotNull(eagAttributes, "eagAttributes");
    this.channelz = Preconditions.checkNotNull(channelz);
    this.logId = InternalLogId.allocate(getClass(), addresses.isEmpty() ? "No address" :
        String.valueOf(addresses));
  }

  @Override
  public SocketAddress getListenSocketAddress() {
    Iterator<Channel> it = channelGroup.iterator();
    if (it.hasNext()) {
      return it.next().localAddress();
    } else {
      // server is not listening/bound yet, just return the original port.
      return addresses.isEmpty() ? null : addresses.get(0);
    }
  }

  @Override
  public List<SocketAddress> getListenSocketAddresses() {
    List<SocketAddress> listenSocketAddresses = new ArrayList<>();
    for (Channel c: channelGroup) {
      listenSocketAddresses.add(c.localAddress());
    }
    // server is not listening/bound yet, just return the original ports.
    if (listenSocketAddresses.isEmpty())  {
      listenSocketAddresses.addAll(addresses);
    }
    return listenSocketAddresses;
  }

  @Override
  public InternalInstrumented<SocketStats> getListenSocketStats() {
    List<InternalInstrumented<SocketStats>> savedListenSocketStatsList = listenSocketStatsList;
    return savedListenSocketStatsList.isEmpty() ? null : savedListenSocketStatsList.get(0);
  }

  @Override
  public List<InternalInstrumented<SocketStats>> getListenSocketStatsList() {
    return listenSocketStatsList;
  }

  @Override
  public void start(ServerListener serverListener) throws IOException {
    listener = checkNotNull(serverListener, "serverListener");

    final ServerBootstrap b = new ServerBootstrap();
    b.option(ALLOCATOR, Utils.getByteBufAllocator(forceHeapBuffer));
    b.childOption(ALLOCATOR, Utils.getByteBufAllocator(forceHeapBuffer));
    b.group(bossExecutor, workerGroup);
    b.channelFactory(channelFactory);
    // For non-socket based channel, the option will be ignored.
    b.childOption(SO_KEEPALIVE, true);

    if (channelOptions != null) {
      for (Map.Entry<ChannelOption<?>, ?> entry : channelOptions.entrySet()) {
        @SuppressWarnings("unchecked")
        ChannelOption<Object> key = (ChannelOption<Object>) entry.getKey();
        b.option(key, entry.getValue());
      }
    }

    if (childChannelOptions != null) {
      for (Map.Entry<ChannelOption<?>, ?> entry : childChannelOptions.entrySet()) {
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
                autoFlowControl,
                flowControlWindow,
                maxMessageSize,
                maxHeaderListSize,
                keepAliveTimeInNanos,
                keepAliveTimeoutInNanos,
                maxConnectionIdleInNanos,
                maxConnectionAgeInNanos,
                maxConnectionAgeGraceInNanos,
                permitKeepAliveWithoutCalls,
                permitKeepAliveTimeInNanos,
                eagAttributes);
        ServerTransportListener transportListener;
        // This is to order callbacks on the listener, not to guard access to channel.
        synchronized (NettyServer.this) {
          if (terminated) {
            // Server already terminated.
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
    Future<Map<ChannelFuture, SocketAddress>> bindCallFuture =
        bossExecutor.submit(
            new Callable<Map<ChannelFuture, SocketAddress>>() {
          @Override
          public Map<ChannelFuture, SocketAddress> call() {
            Map<ChannelFuture, SocketAddress> bindFutures = new HashMap<>();
            for (SocketAddress address: addresses) {
                ChannelFuture future = b.bind(address);
                channelGroup.add(future.channel());
                bindFutures.put(future, address);
            }
            return bindFutures;
          }
        }
    );
    Map<ChannelFuture, SocketAddress> channelFutures =
        bindCallFuture.awaitUninterruptibly().getNow();

    if (!bindCallFuture.isSuccess()) {
      channelGroup.close().awaitUninterruptibly();
      throw new IOException(String.format("Failed to bind to addresses %s",
          addresses), bindCallFuture.cause());
    }
    final List<InternalInstrumented<SocketStats>> socketStats = new ArrayList<>();
    for (Map.Entry<ChannelFuture, SocketAddress> entry: channelFutures.entrySet()) {
      // We'd love to observe interruption, but if interrupted we will need to close the channel,
      // which itself would need an await() to guarantee the port is not used when the method
      // returns. See #6850
      final ChannelFuture future = entry.getKey();
      if (!future.awaitUninterruptibly().isSuccess()) {
        channelGroup.close().awaitUninterruptibly();
        throw new IOException(String.format("Failed to bind to address %s",
            entry.getValue()), future.cause());
      }
      final InternalInstrumented<SocketStats> listenSocketStats =
          new ListenSocket(future.channel());
      channelz.addListenSocket(listenSocketStats);
      socketStats.add(listenSocketStats);
      future.channel().closeFuture().addListener(new ChannelFutureListener() {
        @Override
        public void operationComplete(ChannelFuture future) throws Exception {
          channelz.removeListenSocket(listenSocketStats);
        }
      });
    }
    listenSocketStatsList = Collections.unmodifiableList(socketStats);
  }

  @Override
  public void shutdown() {
    if (terminated) {
      return;
    }
    ChannelGroupFuture groupFuture = channelGroup.close()
        .addListener(new ChannelGroupFutureListener() {
            @Override
            public void operationComplete(ChannelGroupFuture future) throws Exception {
              if (!future.isSuccess()) {
                log.log(Level.WARNING, "Error closing server channel group", future.cause());
              }
              sharedResourceReferenceCounter.release();
              protocolNegotiator.close();
              listenSocketStatsList = Collections.emptyList();
              synchronized (NettyServer.this) {
                listener.serverShutdown();
                terminated = true;
              }
            }
        });
    try {
      groupFuture.await();
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
        .add("addresses", addresses)
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
