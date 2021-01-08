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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
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
import io.netty.channel.ChannelFactory;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerAdapter;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.ReflectiveChannelFactory;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.util.AsciiString;
import io.netty.util.concurrent.Future;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(JUnit4.class)
public class NettyServerTest {
  private final InternalChannelz channelz = new InternalChannelz();
  private final NioEventLoopGroup eventLoop = new NioEventLoopGroup(1);
  private final ChannelFactory<NioServerSocketChannel> channelFactory =
      new ReflectiveChannelFactory<>(NioServerSocketChannel.class);

  @Mock
  EventLoopGroup mockEventLoopGroup;
  @Mock
  EventLoop mockEventLoop;
  @Mock
  Future<Map<ChannelFuture, SocketAddress>> bindFuture;

  @Before
  public void setup() throws Exception {
    MockitoAnnotations.initMocks(this);
    when(mockEventLoopGroup.next()).thenReturn(mockEventLoop);
    when(mockEventLoop
        .submit(ArgumentMatchers.<Callable<Map<ChannelFuture, SocketAddress>>>any()))
        .thenReturn(bindFuture);
  }

  @After
  public void tearDown() throws Exception {
    eventLoop.shutdownGracefully(0, 0, TimeUnit.SECONDS);
    eventLoop.awaitTermination(5, TimeUnit.SECONDS);
  }

  @Test
  public void startStop() throws Exception {
    InetSocketAddress addr = new InetSocketAddress(0);

    class NoHandlerProtocolNegotiator implements ProtocolNegotiator {
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

    NoHandlerProtocolNegotiator protocolNegotiator = new NoHandlerProtocolNegotiator();
    NettyServer ns = new NettyServer(
        Arrays.asList(addr),
        new ReflectiveChannelFactory<>(NioServerSocketChannel.class),
        new HashMap<ChannelOption<?>, Object>(),
        new HashMap<ChannelOption<?>, Object>(),
        new FixedObjectPool<>(eventLoop),
        new FixedObjectPool<>(eventLoop),
        false,
        protocolNegotiator,
        Collections.<ServerStreamTracer.Factory>emptyList(),
        TransportTracer.getDefaultFactory(),
        1, // ignore
        false, // ignore
        1, // ignore
        1, // ignore
        1, // ignore
        1, // ignore
        1, 1, // ignore
        1, 1, // ignore
        true, 0, // ignore
        Attributes.EMPTY,
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
  public void multiPortStartStopGet() throws Exception {
    InetSocketAddress addr1 = new InetSocketAddress(0);
    InetSocketAddress addr2 = new InetSocketAddress(0);

    NettyServer ns = new NettyServer(
        Arrays.asList(addr1, addr2),
        new ReflectiveChannelFactory<>(NioServerSocketChannel.class),
        new HashMap<ChannelOption<?>, Object>(),
        new HashMap<ChannelOption<?>, Object>(),
        new FixedObjectPool<>(eventLoop),
        new FixedObjectPool<>(eventLoop),
        false,
        ProtocolNegotiators.plaintext(),
        Collections.<ServerStreamTracer.Factory>emptyList(),
        TransportTracer.getDefaultFactory(),
        1, // ignore
        false, // ignore
        1, // ignore
        1, // ignore
        1, // ignore
        1, // ignore
        1, 1, // ignore
        1, 1, // ignore
        true, 0, // ignore
        Attributes.EMPTY,
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

    // SocketStats won't be available until the event loop task of adding SocketStats created by
    // ns.start() complete. So submit a noop task and await until it's drained.
    eventLoop.submit(new Runnable() {
      @Override
      public void run() {}
    }).await(5, TimeUnit.SECONDS);

    assertEquals(2, ns.getListenSocketAddresses().size());
    for (SocketAddress address: ns.getListenSocketAddresses()) {
      assertThat(((InetSocketAddress) address).getPort()).isGreaterThan(0);
    }

    List<InternalInstrumented<SocketStats>> stats = ns.getListenSocketStatsList();
    assertEquals(2, ns.getListenSocketStatsList().size());
    for (InternalInstrumented<SocketStats> listenSocket : stats) {
      assertSame(listenSocket, channelz.getSocket(id(listenSocket)));
      // very basic sanity check of the contents
      SocketStats socketStats = listenSocket.getStats().get();
      assertThat(ns.getListenSocketAddresses()).contains(socketStats.local);
      assertNull(socketStats.remote);
    }

    // Cleanup
    ns.shutdown();
    shutdownCompleted.get();

    // listen socket is removed
    for (InternalInstrumented<SocketStats> listenSocket : stats) {
      assertNull(channelz.getSocket(id(listenSocket)));
    }
  }

  @Test(timeout = 60000)
  public void multiPortConnections() throws Exception {
    InetSocketAddress addr1 = new InetSocketAddress(0);
    InetSocketAddress addr2 = new InetSocketAddress(0);
    final CountDownLatch allPortsConnectedCountDown = new CountDownLatch(2);

    NettyServer ns = new NettyServer(
        Arrays.asList(addr1, addr2),
        new ReflectiveChannelFactory<>(NioServerSocketChannel.class),
        new HashMap<ChannelOption<?>, Object>(),
        new HashMap<ChannelOption<?>, Object>(),
        new FixedObjectPool<>(eventLoop),
        new FixedObjectPool<>(eventLoop),
        false,
        ProtocolNegotiators.plaintext(),
        Collections.<ServerStreamTracer.Factory>emptyList(),
        TransportTracer.getDefaultFactory(),
        1, // ignore
        false, // ignore
        1, // ignore
        1, // ignore
        1, // ignore
        1, // ignore
        1, 1, // ignore
        1, 1, // ignore
        true, 0, // ignore
        Attributes.EMPTY,
        channelz);
    final SettableFuture<Void> shutdownCompleted = SettableFuture.create();
    ns.start(new ServerListener() {
      @Override
      public ServerTransportListener transportCreated(ServerTransport transport) {
        allPortsConnectedCountDown.countDown();
        return new NoopServerTransportListener();
      }

      @Override
      public void serverShutdown() {
        shutdownCompleted.set(null);
      }
    });

    // SocketStats won't be available until the event loop task of adding SocketStats created by
    // ns.start() complete. So submit a noop task and await until it's drained.
    eventLoop.submit(new Runnable() {
      @Override
      public void run() {}
    }).await(5, TimeUnit.SECONDS);

    List<SocketAddress> serverSockets = ns.getListenSocketAddresses();
    assertEquals(2, serverSockets.size());

    for (int i = 0; i < 2; i++) {
      Socket socket = new Socket();
      socket.connect(serverSockets.get(i), /* timeout= */ 8000);
      socket.close();
    }
    allPortsConnectedCountDown.await();
    // Cleanup
    ns.shutdown();
    shutdownCompleted.get();
  }

  @Test
  public void getPort_notStarted() {
    InetSocketAddress addr = new InetSocketAddress(0);
    List<InetSocketAddress> addresses = Collections.singletonList(addr);
    NettyServer ns = new NettyServer(
        addresses,
        new ReflectiveChannelFactory<>(NioServerSocketChannel.class),
        new HashMap<ChannelOption<?>, Object>(),
        new HashMap<ChannelOption<?>, Object>(),
        new FixedObjectPool<>(eventLoop),
        new FixedObjectPool<>(eventLoop),
        false,
        ProtocolNegotiators.plaintext(),
        Collections.<ServerStreamTracer.Factory>emptyList(),
        TransportTracer.getDefaultFactory(),
        1, // ignore
        false, // ignore
        1, // ignore
        1, // ignore
        1, // ignore
        1, // ignore
        1, 1, // ignore
        1, 1, // ignore
        true, 0, // ignore
        Attributes.EMPTY,
        channelz);

    assertThat(ns.getListenSocketAddress()).isEqualTo(addr);
    assertThat(ns.getListenSocketAddresses()).isEqualTo(addresses);
  }

  @Test(timeout = 60000)
  public void connectionSettingsPropagated() throws Exception {
    final int originalLowWaterMark = 2097169;
    final int originalHighWaterMark = 2097211;

    Map<ChannelOption<?>, Object> childChannelOptions = new HashMap<>();
    childChannelOptions.put(ChannelOption.WRITE_BUFFER_WATER_MARK,
        new WriteBufferWaterMark(originalLowWaterMark, originalHighWaterMark));

    class TestChannelHandler extends ChannelHandlerAdapter {
      CountDownLatch countDownLatch = new CountDownLatch(1);
      int lowWaterMark;
      int highWaterMark;

      @Override public void handlerAdded(ChannelHandlerContext ctx) {
        Channel channel = ctx.channel();
        WriteBufferWaterMark writeBufferWaterMark = channel.config()
            .getOption(ChannelOption.WRITE_BUFFER_WATER_MARK);
        lowWaterMark = writeBufferWaterMark.low();
        highWaterMark = writeBufferWaterMark.high();

        countDownLatch.countDown();
      }
    }

    final TestChannelHandler channelHandler = new TestChannelHandler();

    class TestProtocolNegotiator implements ProtocolNegotiator {
      Attributes eagAttributes;

      @Override public ChannelHandler newHandler(GrpcHttp2ConnectionHandler handler) {
        eagAttributes = handler.getEagAttributes();
        return channelHandler;
      }

      @Override public void close() {}

      @Override public AsciiString scheme() {
        return Utils.HTTP;
      }
    }

    Attributes eagAttributes = Attributes.newBuilder()
        .set(Attributes.Key.create("foo"), "bar")
        .build();
    TestProtocolNegotiator protocolNegotiator = new TestProtocolNegotiator();
    InetSocketAddress addr = new InetSocketAddress(0);
    NettyServer ns = new NettyServer(
        Arrays.asList(addr),
        new ReflectiveChannelFactory<>(NioServerSocketChannel.class),
        new HashMap<ChannelOption<?>, Object>(),
        childChannelOptions,
        new FixedObjectPool<>(eventLoop),
        new FixedObjectPool<>(eventLoop),
        false,
        protocolNegotiator,
        Collections.<ServerStreamTracer.Factory>emptyList(),
        TransportTracer.getDefaultFactory(),
        1, // ignore
        false, // ignore
        1, // ignore
        1, // ignore
        1, // ignore
        1, // ignore
        1, 1, // ignore
        1, 1, // ignore
        true, 0, // ignore
        eagAttributes,
        channelz);
    ns.start(new ServerListener() {
      @Override
      public ServerTransportListener transportCreated(ServerTransport transport) {
        return new NoopServerTransportListener();
      }

      @Override
      public void serverShutdown() {}
    });

    Socket socket = new Socket();
    socket.connect(ns.getListenSocketAddress(), /* timeout= */ 8000);
    channelHandler.countDownLatch.await();
    socket.close();

    assertThat(protocolNegotiator.eagAttributes).isSameInstanceAs(eagAttributes);
    assertThat(channelHandler.lowWaterMark).isEqualTo(originalLowWaterMark);
    assertThat(channelHandler.highWaterMark).isEqualTo(originalHighWaterMark);

    ns.shutdown();
  }

  @Test
  public void channelzListenSocket() throws Exception {
    InetSocketAddress addr = new InetSocketAddress(0);
    NettyServer ns = new NettyServer(
        Arrays.asList(addr),
        new ReflectiveChannelFactory<>(NioServerSocketChannel.class),
        new HashMap<ChannelOption<?>, Object>(),
        new HashMap<ChannelOption<?>, Object>(),
        new FixedObjectPool<>(eventLoop),
        new FixedObjectPool<>(eventLoop),
        false,
        ProtocolNegotiators.plaintext(),
        Collections.<ServerStreamTracer.Factory>emptyList(),
        TransportTracer.getDefaultFactory(),
        1, // ignore
        false, // ignore
        1, // ignore
        1, // ignore
        1, // ignore
        1, // ignore
        1, 1, // ignore
        1, 1, // ignore
        true, 0, // ignore
        Attributes.EMPTY,
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

  @Test
  @SuppressWarnings("unchecked")
  public void testBindScheduleFailure() throws Exception {
    when(bindFuture.awaitUninterruptibly()).thenReturn(bindFuture);
    when(bindFuture.isSuccess()).thenReturn(false);
    when(bindFuture.getNow()).thenReturn(null);
    Throwable mockCause = mock(Throwable.class);
    when(bindFuture.cause()).thenReturn(mockCause);
    Future<Void> mockFuture = (Future<Void>) mock(Future.class);
    doReturn(mockFuture).when(mockEventLoopGroup).submit(any(Runnable.class));
    SocketAddress addr = new InetSocketAddress(0);
    verifyServerNotStart(Collections.singletonList(addr), mockEventLoopGroup,
        IOException.class, "Failed to bind to addresses " + Arrays.asList(addr));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testBindFailure() throws Exception {
    when(bindFuture.awaitUninterruptibly()).thenReturn(bindFuture);
    ChannelFuture future = mock(ChannelFuture.class);
    when(future.awaitUninterruptibly()).thenReturn(future);
    when(future.isSuccess()).thenReturn(false);
    Channel channel = channelFactory.newChannel();
    eventLoop.register(channel);
    when(future.channel()).thenReturn(channel);
    Throwable mockCause = mock(Throwable.class);
    when(future.cause()).thenReturn(mockCause);
    SocketAddress addr = new InetSocketAddress(0);
    Map<ChannelFuture, SocketAddress> map = ImmutableMap.of(future, addr);
    when(bindFuture.getNow()).thenReturn(map);
    when(bindFuture.isSuccess()).thenReturn(true);
    Future<Void> mockFuture = (Future<Void>) mock(Future.class);
    doReturn(mockFuture).when(mockEventLoopGroup).submit(any(Runnable.class));
    verifyServerNotStart(Collections.singletonList(addr), mockEventLoopGroup,
        IOException.class, "Failed to bind to address " + addr);
  }

  @Test
  public void testBindPartialFailure() throws Exception {
    SocketAddress add1 = new InetSocketAddress(0);
    SocketAddress add2 = new InetSocketAddress(2);
    SocketAddress add3 = new InetSocketAddress(2);
    verifyServerNotStart(ImmutableList.of(add1, add2, add3), eventLoop,
        IOException.class, "Failed to bind to address " + add3);
  }

  private void verifyServerNotStart(List<SocketAddress> addr, EventLoopGroup ev,
      Class<?> expectedException, String expectedMessage)
      throws Exception {
    NettyServer ns = getServer(addr, ev);
    try {
      ns.start(new ServerListener() {
        @Override
        public ServerTransportListener transportCreated(ServerTransport transport) {
          return new NoopServerTransportListener();
        }

        @Override
        public void serverShutdown() {
        }
      });
    } catch (Exception ex) {
      assertTrue(expectedException.isInstance(ex));
      assertThat(ex.getMessage()).isEqualTo(expectedMessage);
      assertFalse(addr.isEmpty());
      // Listener tasks are executed on the event loop, so await until noop task is drained.
      ev.submit(new Runnable() {
        @Override
        public void run() {}
      }).await(5, TimeUnit.SECONDS);
      assertThat(ns.getListenSocketAddress()).isEqualTo(addr.get(0));
      assertThat(ns.getListenSocketAddresses()).isEqualTo(addr);
      assertTrue(ns.getListenSocketStatsList().isEmpty());
      assertNull(ns.getListenSocketStats());
      return;
    }
    fail();
  }

  private NettyServer getServer(List<SocketAddress> addr, EventLoopGroup ev) {
    return new NettyServer(
        addr,
        new ReflectiveChannelFactory<>(NioServerSocketChannel.class),
        new HashMap<ChannelOption<?>, Object>(),
        new HashMap<ChannelOption<?>, Object>(),
        new FixedObjectPool<>(ev),
        new FixedObjectPool<>(ev),
        false,
        ProtocolNegotiators.plaintext(),
        Collections.<ServerStreamTracer.Factory>emptyList(),
        TransportTracer.getDefaultFactory(),
        1, // ignore
        false, // ignore
        1, // ignore
        1, // ignore
        1, // ignore
        1, // ignore
        1, 1, // ignore
        1, 1, // ignore
        true, 0, // ignore
        Attributes.EMPTY,
        channelz);
  }

  private static class NoopServerTransportListener implements ServerTransportListener {
    @Override public void streamCreated(ServerStream stream, String method, Metadata headers) {}

    @Override public Attributes transportReady(Attributes attributes) {
      return attributes;
    }

    @Override public void transportTerminated() {}
  }
}
