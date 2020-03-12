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

package io.grpc.netty;

import static com.google.common.truth.Truth.assertThat;
import static io.grpc.InternalChannelz.id;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import com.google.common.util.concurrent.SettableFuture;
import io.grpc.Attributes;
import io.grpc.InternalChannelz;
import io.grpc.InternalChannelz.SocketStats;
import io.grpc.InternalInstrumented;
import io.grpc.Metadata;
import io.grpc.ServerStreamTracer;
import io.grpc.internal.FixedObjectPool;
import io.grpc.internal.ServerListener;
import io.grpc.internal.ServerStream;
import io.grpc.internal.ServerTransport;
import io.grpc.internal.ServerTransportListener;
import io.grpc.internal.TransportTracer;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelOption;
import io.netty.channel.ReflectiveChannelFactory;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.util.AsciiString;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class NettyServerTest {
  private final InternalChannelz channelz = new InternalChannelz();
  private final NioEventLoopGroup eventLoop = new NioEventLoopGroup(1);

  @After
  public void tearDown() throws Exception {
    eventLoop.shutdownGracefully(0, 0, TimeUnit.SECONDS);
    eventLoop.awaitTermination(5, TimeUnit.SECONDS);
  }

  @Test
  public void startStop() throws Exception {
    InetSocketAddress addr = new InetSocketAddress(0);

    class TestProtocolNegotiator implements ProtocolNegotiator {
      boolean closed;

      @Override public ChannelHandler newHandler(GrpcHttp2ConnectionHandler handler) {
        throw new UnsupportedOperationException();
      }

      @Override public void close() {
        closed = true;
      }

      @Override public AsciiString scheme() {
        return Utils.HTTP;
      }
    }

    TestProtocolNegotiator protocolNegotiator = new TestProtocolNegotiator();
    NettyServer ns = new NettyServer(
        addr,
        new ReflectiveChannelFactory<>(NioServerSocketChannel.class),
        new HashMap<ChannelOption<?>, Object>(),
        new FixedObjectPool<>(eventLoop),
        new FixedObjectPool<>(eventLoop),
        false,
        protocolNegotiator,
        Collections.<ServerStreamTracer.Factory>emptyList(),
        TransportTracer.getDefaultFactory(),
        1, // ignore
        1, // ignore
        1, // ignore
        1, // ignore
        1, // ignore
        1, 1, // ignore
        1, 1, // ignore
        true, 0, // ignore
        channelz);
    final SettableFuture<Void> serverShutdownCalled = SettableFuture.create();
    ns.start(new ServerListener() {
      @Override
      public ServerTransportListener transportCreated(ServerTransport transport) {
        return new NoopServerTransportListener();
      }

      @Override
      public void serverShutdown() {
        serverShutdownCalled.set(null);
      }
    });

    // Check that we got an actual port.
    assertThat(((InetSocketAddress) ns.getListenSocketAddress()).getPort()).isGreaterThan(0);

    // Cleanup
    ns.shutdown();
    // serverShutdown() signals that resources are freed
    serverShutdownCalled.get(1, TimeUnit.SECONDS);
    assertThat(protocolNegotiator.closed).isTrue();
  }

  @Test
  public void getPort_notStarted() {
    InetSocketAddress addr = new InetSocketAddress(0);
    NettyServer ns = new NettyServer(
        addr,
        new ReflectiveChannelFactory<>(NioServerSocketChannel.class),
        new HashMap<ChannelOption<?>, Object>(),
        new FixedObjectPool<>(eventLoop),
        new FixedObjectPool<>(eventLoop),
        false,
        ProtocolNegotiators.plaintext(),
        Collections.<ServerStreamTracer.Factory>emptyList(),
        TransportTracer.getDefaultFactory(),
        1, // ignore
        1, // ignore
        1, // ignore
        1, // ignore
        1, // ignore
        1, 1, // ignore
        1, 1, // ignore
        true, 0, // ignore
        channelz);

    assertThat(ns.getListenSocketAddress()).isEqualTo(addr);
  }

  @Test(timeout = 60000)
  public void childChannelOptions() throws Exception {
    final int originalLowWaterMark = 2097169;
    final int originalHighWaterMark = 2097211;

    Map<ChannelOption<?>, Object> channelOptions = new HashMap<>();

    channelOptions.put(ChannelOption.WRITE_BUFFER_WATER_MARK,
        new WriteBufferWaterMark(originalLowWaterMark, originalHighWaterMark));

    final AtomicInteger lowWaterMark = new AtomicInteger(0);
    final AtomicInteger highWaterMark = new AtomicInteger(0);

    final CountDownLatch countDownLatch = new CountDownLatch(1);

    InetSocketAddress addr = new InetSocketAddress(0);
    NettyServer ns = new NettyServer(
        addr,
        new ReflectiveChannelFactory<>(NioServerSocketChannel.class),
        channelOptions,
        new FixedObjectPool<>(eventLoop),
        new FixedObjectPool<>(eventLoop),
        false,
        ProtocolNegotiators.plaintext(),
        Collections.<ServerStreamTracer.Factory>emptyList(),
        TransportTracer.getDefaultFactory(),
        1, // ignore
        1, // ignore
        1, // ignore
        1, // ignore
        1, // ignore
        1, 1, // ignore
        1, 1, // ignore
        true, 0, // ignore
        channelz);
    ns.start(new ServerListener() {
      @Override
      public ServerTransportListener transportCreated(ServerTransport transport) {
        Channel channel = ((NettyServerTransport)transport).channel();
        WriteBufferWaterMark writeBufferWaterMark = channel.config()
            .getOption(ChannelOption.WRITE_BUFFER_WATER_MARK);
        lowWaterMark.set(writeBufferWaterMark.low());
        highWaterMark.set(writeBufferWaterMark.high());

        countDownLatch.countDown();

        return new NoopServerTransportListener();
      }

      @Override
      public void serverShutdown() {}
    });

    Socket socket = new Socket();
    socket.connect(ns.getListenSocketAddress(), /* timeout= */ 8000);
    countDownLatch.await();
    socket.close();

    assertThat(lowWaterMark.get()).isEqualTo(originalLowWaterMark);
    assertThat(highWaterMark.get()).isEqualTo(originalHighWaterMark);

    ns.shutdown();
  }

  @Test
  public void channelzListenSocket() throws Exception {
    InetSocketAddress addr = new InetSocketAddress(0);
    NettyServer ns = new NettyServer(
        addr,
        new ReflectiveChannelFactory<>(NioServerSocketChannel.class),
        new HashMap<ChannelOption<?>, Object>(),
        new FixedObjectPool<>(eventLoop),
        new FixedObjectPool<>(eventLoop),
        false,
        ProtocolNegotiators.plaintext(),
        Collections.<ServerStreamTracer.Factory>emptyList(),
        TransportTracer.getDefaultFactory(),
        1, // ignore
        1, // ignore
        1, // ignore
        1, // ignore
        1, // ignore
        1, 1, // ignore
        1, 1, // ignore
        true, 0, // ignore
        channelz);
    final SettableFuture<Void> shutdownCompleted = SettableFuture.create();
    ns.start(new ServerListener() {
      @Override
      public ServerTransportListener transportCreated(ServerTransport transport) {
        return new NoopServerTransportListener();
      }

      @Override
      public void serverShutdown() {
        shutdownCompleted.set(null);
      }
    });

    assertThat(((InetSocketAddress) ns.getListenSocketAddress()).getPort()).isGreaterThan(0);

    // SocketStats won't be available until the event loop task of adding SocketStats created by
    // ns.start() complete. So submit a noop task and await until it's drained.
    eventLoop.submit(new Runnable() {
      @Override
      public void run() {}
    }).await(5, TimeUnit.SECONDS);
    InternalInstrumented<SocketStats> listenSocket = ns.getListenSocketStats();
    assertSame(listenSocket, channelz.getSocket(id(listenSocket)));

    // very basic sanity check of the contents
    SocketStats socketStats = listenSocket.getStats().get();
    assertEquals(ns.getListenSocketAddress(), socketStats.local);
    assertNull(socketStats.remote);

    // TODO(zpencer): uncomment when sock options are exposed
    // by default, there are some socket options set on the listen socket
    // assertThat(socketStats.socketOptions.additional).isNotEmpty();

    // Cleanup
    ns.shutdown();
    shutdownCompleted.get();

    // listen socket is removed
    assertNull(channelz.getSocket(id(listenSocket)));
  }

  private static class NoopServerTransportListener implements ServerTransportListener {
    @Override public void streamCreated(ServerStream stream, String method, Metadata headers) {}

    @Override public Attributes transportReady(Attributes attributes) {
      return attributes;
    }

    @Override public void transportTerminated() {}
  }
}
