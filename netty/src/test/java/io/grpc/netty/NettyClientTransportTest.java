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

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.TruthJUnit.assume;
import static io.grpc.internal.GrpcUtil.DEFAULT_MAX_MESSAGE_SIZE;
import static io.grpc.internal.GrpcUtil.DEFAULT_SERVER_KEEPALIVE_TIMEOUT_NANOS;
import static io.grpc.internal.GrpcUtil.DEFAULT_SERVER_KEEPALIVE_TIME_NANOS;
import static io.grpc.internal.GrpcUtil.KEEPALIVE_TIME_NANOS_DISABLED;
import static io.grpc.internal.GrpcUtil.USER_AGENT_KEY;
import static io.grpc.netty.NettyServerBuilder.MAX_CONNECTION_AGE_GRACE_NANOS_INFINITE;
import static io.grpc.netty.NettyServerBuilder.MAX_CONNECTION_AGE_NANOS_DISABLED;
import static io.grpc.netty.NettyServerBuilder.MAX_CONNECTION_IDLE_NANOS_DISABLED;
import static io.grpc.netty.NettyServerBuilder.MAX_RST_COUNT_DISABLED;
import static io.netty.handler.codec.http2.Http2CodecUtil.DEFAULT_WINDOW_SIZE;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.google.common.base.Strings;
import com.google.common.base.Ticker;
import com.google.common.io.ByteStreams;
import com.google.common.util.concurrent.SettableFuture;
import io.grpc.Attributes;
import io.grpc.CallOptions;
import io.grpc.ChannelLogger;
import io.grpc.ClientStreamTracer;
import io.grpc.Grpc;
import io.grpc.InternalChannelz;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.MethodDescriptor.Marshaller;
import io.grpc.ServerStreamTracer;
import io.grpc.Status;
import io.grpc.Status.Code;
import io.grpc.StatusException;
import io.grpc.internal.ClientStream;
import io.grpc.internal.ClientStreamListener;
import io.grpc.internal.ClientTransport;
import io.grpc.internal.FakeClock;
import io.grpc.internal.FixedObjectPool;
import io.grpc.internal.GrpcUtil;
import io.grpc.internal.ManagedClientTransport;
import io.grpc.internal.ServerListener;
import io.grpc.internal.ServerStream;
import io.grpc.internal.ServerStreamListener;
import io.grpc.internal.ServerTransport;
import io.grpc.internal.ServerTransportListener;
import io.grpc.internal.TransportTracer;
import io.grpc.internal.testing.TestUtils;
import io.grpc.netty.NettyChannelBuilder.LocalSocketPicker;
import io.grpc.netty.NettyTestUtil.TrackingObjectPoolForTest;
import io.grpc.testing.TlsTesting;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFactory;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPromise;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.ReflectiveChannelFactory;
import io.netty.channel.local.LocalChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannelConfig;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http2.StreamBufferingEncoder;
import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.SslContext;
import io.netty.util.AsciiString;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nullable;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/**
 * Tests for {@link NettyClientTransport}.
 */
@RunWith(JUnit4.class)
public class NettyClientTransportTest {
  @Rule public final MockitoRule mocks = MockitoJUnit.rule();

  private static final SslContext SSL_CONTEXT = createSslContext();

  @Mock
  private ManagedClientTransport.Listener clientTransportListener;

  private final List<NettyClientTransport> transports = new ArrayList<>();
  private final LinkedBlockingQueue<Attributes> serverTransportAttributesList =
      new LinkedBlockingQueue<>();
  private final NioEventLoopGroup group = new NioEventLoopGroup(1);
  private final EchoServerListener serverListener = new EchoServerListener();
  private final InternalChannelz channelz = new InternalChannelz();
  private Runnable tooManyPingsRunnable = new Runnable() {
    // Throwing is useless in this method, because Netty doesn't propagate the exception
    @Override public void run() {}
  };
  private Attributes eagAttributes = Attributes.EMPTY;

  private ProtocolNegotiator negotiator = ProtocolNegotiators.serverTls(SSL_CONTEXT);

  private InetSocketAddress address;
  private String authority;
  private NettyServer server;

  @Before
  public void setup() {
    when(clientTransportListener.filterTransport(any())).thenAnswer(i -> i.getArguments()[0]);
  }

  @After
  public void teardown() throws Exception {
    for (NettyClientTransport transport : transports) {
      transport.shutdown(Status.UNAVAILABLE);
    }

    if (server != null) {
      server.shutdown();
    }

    group.shutdownGracefully(0, 10, TimeUnit.SECONDS);
  }

  @Test
  public void testToString() throws Exception {
    address = TestUtils.testServerAddress(new InetSocketAddress(12345));
    authority = GrpcUtil.authorityFromHostAndPort(address.getHostString(), address.getPort());
    String s = newTransport(newNegotiator()).toString();
    transports.clear();
    assertTrue("Unexpected: " + s, s.contains("NettyClientTransport"));
    assertTrue("Unexpected: " + s, s.contains(address.toString()));
  }

  @Test
  public void addDefaultUserAgent() throws Exception {
    startServer();
    NettyClientTransport transport = newTransport(newNegotiator());
    callMeMaybe(transport.start(clientTransportListener));

    // Send a single RPC and wait for the response.
    new Rpc(transport).halfClose().waitForResponse();

    // Verify that the received headers contained the User-Agent.
    assertEquals(1, serverListener.streamListeners.size());

    Metadata headers = serverListener.streamListeners.get(0).headers;
    assertEquals(GrpcUtil.getGrpcUserAgent("netty", null), headers.get(USER_AGENT_KEY));
  }

  @Test
  public void setSoLingerChannelOption() throws IOException {
    startServer();
    Map<ChannelOption<?>, Object> channelOptions = new HashMap<>();
    // set SO_LINGER option
    int soLinger = 123;
    channelOptions.put(ChannelOption.SO_LINGER, soLinger);
    NettyClientTransport transport =
        new NettyClientTransport(
            address,
            new ReflectiveChannelFactory<>(NioSocketChannel.class),
            channelOptions,
            group,
            newNegotiator(),
            false,
            DEFAULT_WINDOW_SIZE,
            DEFAULT_MAX_MESSAGE_SIZE,
            GrpcUtil.DEFAULT_MAX_HEADER_LIST_SIZE,
            GrpcUtil.DEFAULT_MAX_HEADER_LIST_SIZE,
            KEEPALIVE_TIME_NANOS_DISABLED,
            1L,
            false,
            authority,
            null /* user agent */,
            tooManyPingsRunnable,
            new TransportTracer(),
            Attributes.EMPTY,
            new SocketPicker(),
            new FakeChannelLogger(),
            false,
            Ticker.systemTicker());
    transports.add(transport);
    callMeMaybe(transport.start(clientTransportListener));

    // verify SO_LINGER has been set
    ChannelConfig config = transport.channel().config();
    assertTrue(config instanceof SocketChannelConfig);
    assertEquals(soLinger, ((SocketChannelConfig) config).getSoLinger());
  }

  @Test
  public void overrideDefaultUserAgent() throws Exception {
    startServer();
    NettyClientTransport transport = newTransport(newNegotiator(),
        DEFAULT_MAX_MESSAGE_SIZE, GrpcUtil.DEFAULT_MAX_HEADER_LIST_SIZE, "testUserAgent", true);
    callMeMaybe(transport.start(clientTransportListener));

    new Rpc(transport, new Metadata()).halfClose().waitForResponse();

    // Verify that the received headers contained the User-Agent.
    assertEquals(1, serverListener.streamListeners.size());
    Metadata receivedHeaders = serverListener.streamListeners.get(0).headers;
    assertEquals(GrpcUtil.getGrpcUserAgent("netty", "testUserAgent"),
        receivedHeaders.get(USER_AGENT_KEY));
  }

  @Test
  public void maxMessageSizeShouldBeEnforced() throws Throwable {
    startServer();
    // Allow the response payloads of up to 1 byte.
    NettyClientTransport transport = newTransport(newNegotiator(),
        1, GrpcUtil.DEFAULT_MAX_HEADER_LIST_SIZE, null, true);
    callMeMaybe(transport.start(clientTransportListener));

    try {
      // Send a single RPC and wait for the response.
      new Rpc(transport).halfClose().waitForResponse();
      fail("Expected the stream to fail.");
    } catch (ExecutionException e) {
      Status status = Status.fromThrowable(e);
      assertEquals(Code.RESOURCE_EXHAUSTED, status.getCode());
      assertTrue("Missing exceeds maximum from: " + status.getDescription(),
          status.getDescription().contains("exceeds maximum"));
    }
  }

  /**
   * Verifies that we can create multiple TLS client transports from the same builder.
   */
  @Test
  public void creatingMultipleTlsTransportsShouldSucceed() throws Exception {
    startServer();

    // Create a couple client transports.
    ProtocolNegotiator negotiator = newNegotiator();
    for (int index = 0; index < 2; ++index) {
      NettyClientTransport transport = newTransport(negotiator);
      callMeMaybe(transport.start(clientTransportListener));
    }

    // Send a single RPC on each transport.
    final List<Rpc> rpcs = new ArrayList<>(transports.size());
    for (NettyClientTransport transport : transports) {
      rpcs.add(new Rpc(transport).halfClose());
    }

    // Wait for the RPCs to complete.
    for (Rpc rpc : rpcs) {
      rpc.waitForResponse();
    }
  }

  @Test
  public void negotiationFailurePropagatesToStatus() throws Exception {
    negotiator = ProtocolNegotiators.serverPlaintext();
    startServer();

    final NoopProtocolNegotiator negotiator = new NoopProtocolNegotiator();
    final NettyClientTransport transport = newTransport(negotiator);
    callMeMaybe(transport.start(clientTransportListener));
    final Status failureStatus = Status.UNAVAILABLE.withDescription("oh noes!");
    transport.channel().eventLoop().execute(new Runnable() {
      @Override
      public void run() {
        negotiator.handler.fail(transport.channel().pipeline().context(negotiator.handler),
            failureStatus.asRuntimeException());
      }
    });

    Rpc rpc = new Rpc(transport).halfClose();
    try {
      rpc.waitForClose();
      fail("expected exception");
    } catch (ExecutionException ex) {
      Status actual = ((StatusException) ex.getCause()).getStatus();
      assertSame(failureStatus.getCode(), actual.getCode());
      assertThat(actual.getDescription()).contains(failureStatus.getDescription());
    }
  }

  @Test
  public void tlsNegotiationFailurePropagatesToStatus() throws Exception {
    InputStream serverCert = TlsTesting.loadCert("server1.pem");
    InputStream serverKey = TlsTesting.loadCert("server1.key");
    // Don't trust ca.pem, so that client auth fails
    SslContext sslContext = GrpcSslContexts.forServer(serverCert, serverKey)
        .clientAuth(ClientAuth.REQUIRE)
        .build();
    negotiator = ProtocolNegotiators.serverTls(sslContext);
    startServer();

    InputStream caCert = TlsTesting.loadCert("ca.pem");
    InputStream clientCert = TlsTesting.loadCert("client.pem");
    InputStream clientKey = TlsTesting.loadCert("client.key");
    SslContext clientContext = GrpcSslContexts.forClient()
        .trustManager(caCert)
        .keyManager(clientCert, clientKey)
        .build();
    ProtocolNegotiator negotiator = ProtocolNegotiators.tls(clientContext);
    final NettyClientTransport transport = newTransport(negotiator);
    callMeMaybe(transport.start(clientTransportListener));

    Rpc rpc = new Rpc(transport).halfClose();
    try {
      rpc.waitForClose();
      fail("expected exception");
    } catch (ExecutionException ex) {
      StatusException sre = (StatusException) ex.getCause();
      assertEquals(Status.Code.UNAVAILABLE, sre.getStatus().getCode());
      if (sre.getCause() instanceof SSLHandshakeException) {
        assertThat(sre).hasCauseThat().isInstanceOf(SSLHandshakeException.class);
        assertThat(sre).hasCauseThat().hasMessageThat().contains("SSLV3_ALERT_HANDSHAKE_FAILURE");
      } else {
        // Client cert verification is after handshake in TLSv1.3
        assertThat(sre).hasCauseThat().hasCauseThat().isInstanceOf(SSLException.class);
        assertThat(sre).hasCauseThat().hasMessageThat().contains("CERTIFICATE_REQUIRED");
      }
    }
  }

  @Test
  public void channelExceptionDuringNegotiatonPropagatesToStatus() throws Exception {
    negotiator = ProtocolNegotiators.serverPlaintext();
    startServer();

    NoopProtocolNegotiator negotiator = new NoopProtocolNegotiator();
    NettyClientTransport transport = newTransport(negotiator);
    callMeMaybe(transport.start(clientTransportListener));
    final Status failureStatus = Status.UNAVAILABLE.withDescription("oh noes!");
    transport.channel().pipeline().fireExceptionCaught(failureStatus.asRuntimeException());

    Rpc rpc = new Rpc(transport).halfClose();
    try {
      rpc.waitForClose();
      fail("expected exception");
    } catch (ExecutionException ex) {
      assertSame(failureStatus, ((StatusException) ex.getCause()).getStatus());
    }
  }

  @Test
  public void handlerExceptionDuringNegotiatonPropagatesToStatus() throws Exception {
    negotiator = ProtocolNegotiators.serverPlaintext();
    startServer();

    final NoopProtocolNegotiator negotiator = new NoopProtocolNegotiator();
    final NettyClientTransport transport = newTransport(negotiator);
    callMeMaybe(transport.start(clientTransportListener));
    final Status failureStatus = Status.UNAVAILABLE.withDescription("oh noes!");
    transport.channel().eventLoop().execute(new Runnable() {
      @Override
      public void run() {
        try {
          negotiator.handler.exceptionCaught(
              transport.channel().pipeline().context(negotiator.handler),
              failureStatus.asRuntimeException());
        } catch (Exception ex) {
          throw new RuntimeException(ex);
        }
      }
    });

    Rpc rpc = new Rpc(transport).halfClose();
    try {
      rpc.waitForClose();
      fail("expected exception");
    } catch (ExecutionException ex) {
      Status actual = ((StatusException) ex.getCause()).getStatus();
      assertSame(failureStatus.getCode(), actual.getCode());
      assertThat(actual.getDescription()).contains(failureStatus.getDescription());
    }
  }

  @Test
  public void bufferedStreamsShouldBeClosedWhenConnectionTerminates() throws Exception {
    // Only allow a single stream active at a time.
    startServer(1, GrpcUtil.DEFAULT_MAX_HEADER_LIST_SIZE);

    NettyClientTransport transport = newTransport(newNegotiator());
    callMeMaybe(transport.start(clientTransportListener));

    // Send a dummy RPC in order to ensure that the updated SETTINGS_MAX_CONCURRENT_STREAMS
    // has been received by the remote endpoint.
    new Rpc(transport).halfClose().waitForResponse();

    // Create 3 streams, but don't half-close. The transport will buffer the second and third.
    Rpc[] rpcs = new Rpc[] { new Rpc(transport), new Rpc(transport), new Rpc(transport) };

    // Wait for the response for the stream that was actually created.
    rpcs[0].waitForResponse();

    // Now forcibly terminate the connection from the server side.
    serverListener.transports.get(0).channel().pipeline().firstContext().close();

    // Now wait for both listeners to be closed.
    for (int i = 1; i < rpcs.length; i++) {
      try {
        rpcs[i].waitForClose();
        fail("Expected the RPC to fail");
      } catch (ExecutionException e) {
        // Expected.
        Throwable t = getRootCause(e);
        // Make sure that the Http2ChannelClosedException got replaced with the real cause of
        // the shutdown.
        assertFalse(t instanceof StreamBufferingEncoder.Http2ChannelClosedException);
      }
    }
  }

  public static class CantConstructChannel extends NioSocketChannel {
    /** Constructor. It doesn't work. Feel free to try. But it doesn't work. */
    public CantConstructChannel() {
      // Use an Error because we've seen cases of channels failing to construct due to classloading
      // problems (like mixing different versions of Netty), and those involve Errors.
      throw new CantConstructChannelError();
    }
  }

  private static class CantConstructChannelError extends Error {}

  @Test
  public void failingToConstructChannelShouldFailGracefully() throws Exception {
    address = TestUtils.testServerAddress(new InetSocketAddress(12345));
    authority = GrpcUtil.authorityFromHostAndPort(address.getHostString(), address.getPort());
    NettyClientTransport transport =
        new NettyClientTransport(
            address,
            new ReflectiveChannelFactory<>(CantConstructChannel.class),
            new HashMap<ChannelOption<?>, Object>(),
            group,
            newNegotiator(),
            false,
            DEFAULT_WINDOW_SIZE,
            DEFAULT_MAX_MESSAGE_SIZE,
            GrpcUtil.DEFAULT_MAX_HEADER_LIST_SIZE,
            GrpcUtil.DEFAULT_MAX_HEADER_LIST_SIZE,
            KEEPALIVE_TIME_NANOS_DISABLED,
            1,
            false,
            authority,
            null,
            tooManyPingsRunnable,
            new TransportTracer(),
            Attributes.EMPTY,
            new SocketPicker(),
            new FakeChannelLogger(),
            false,
            Ticker.systemTicker());
    transports.add(transport);

    // Should not throw
    callMeMaybe(transport.start(clientTransportListener));

    // And RPCs and PINGs should fail cleanly, reporting the failure
    Rpc rpc = new Rpc(transport);
    try {
      rpc.waitForResponse();
      fail("Expected exception");
    } catch (Exception ex) {
      if (!(getRootCause(ex) instanceof CantConstructChannelError)) {
        throw new AssertionError("Could not find expected error", ex);
      }
    }

    final SettableFuture<Object> pingResult = SettableFuture.create();
    FakeClock clock = new FakeClock();
    ClientTransport.PingCallback pingCallback = new ClientTransport.PingCallback() {
      @Override
      public void onSuccess(long roundTripTimeNanos) {
        pingResult.set(roundTripTimeNanos);
      }

      @Override
      public void onFailure(Throwable cause) {
        pingResult.setException(cause);
      }
    };
    transport.ping(pingCallback, clock.getScheduledExecutorService());
    assertFalse(pingResult.isDone());
    clock.runDueTasks();
    assertTrue(pingResult.isDone());
    try {
      pingResult.get();
      fail("Expected exception");
    } catch (Exception ex) {
      if (!(getRootCause(ex) instanceof CantConstructChannelError)) {
        throw new AssertionError("Could not find expected error", ex);
      }
    }
  }

  @Test
  public void channelFactoryShouldSetSocketOptionKeepAlive() throws Exception {
    startServer();
    NettyClientTransport transport = newTransport(newNegotiator(),
        DEFAULT_MAX_MESSAGE_SIZE, GrpcUtil.DEFAULT_MAX_HEADER_LIST_SIZE, "testUserAgent", true,
        TimeUnit.SECONDS.toNanos(10L), TimeUnit.SECONDS.toNanos(1L),
        new ReflectiveChannelFactory<>(NioSocketChannel.class), group);

    callMeMaybe(transport.start(clientTransportListener));

    assertThat(transport.channel().config().getOption(ChannelOption.SO_KEEPALIVE))
        .isTrue();
  }

  @Test
  public void channelFactoryShouldNNotSetSocketOptionKeepAlive() throws Exception {
    startServer();
    DefaultEventLoopGroup group = new DefaultEventLoopGroup(1);
    try {
      NettyClientTransport transport = newTransport(newNegotiator(),
          DEFAULT_MAX_MESSAGE_SIZE, GrpcUtil.DEFAULT_MAX_HEADER_LIST_SIZE, "testUserAgent", true,
          TimeUnit.SECONDS.toNanos(10L), TimeUnit.SECONDS.toNanos(1L),
          new ReflectiveChannelFactory<>(LocalChannel.class), group);

      callMeMaybe(transport.start(clientTransportListener));

      assertThat(transport.channel().config().getOption(ChannelOption.SO_KEEPALIVE))
          .isNull();
    } finally {
      group.shutdownGracefully(0, 10, TimeUnit.SECONDS);
    }
  }

  @Test
  public void maxHeaderListSizeShouldBeEnforcedOnClient() throws Exception {
    startServer();

    NettyClientTransport transport =
        newTransport(newNegotiator(), DEFAULT_MAX_MESSAGE_SIZE, 1, null, true);
    callMeMaybe(transport.start(clientTransportListener));

    try {
      // Send a single RPC and wait for the response.
      new Rpc(transport, new Metadata()).halfClose().waitForResponse();
      fail("The stream should have been failed due to client received header exceeds header list"
          + " size limit!");
    } catch (Exception e) {
      Throwable rootCause = getRootCause(e);
      Status status = ((StatusException) rootCause).getStatus();
      assertEquals(Status.Code.INTERNAL, status.getCode());
      assertEquals("RST_STREAM closed stream. HTTP/2 error code: PROTOCOL_ERROR",
          status.getDescription());
    }
  }

  @Test
  public void huffmanCodingShouldNotBePerformed() throws Exception {
    @SuppressWarnings("InlineMeInliner") // Requires Java 11
    String longStringOfA = Strings.repeat("a", 128);

    negotiator = ProtocolNegotiators.serverPlaintext();
    startServer();

    NettyClientTransport transport = newTransport(ProtocolNegotiators.plaintext(),
        DEFAULT_MAX_MESSAGE_SIZE, GrpcUtil.DEFAULT_MAX_HEADER_LIST_SIZE, null, false,
        TimeUnit.SECONDS.toNanos(10L), TimeUnit.SECONDS.toNanos(1L),
        new ReflectiveChannelFactory<>(NioSocketChannel.class), group);

    Metadata headers = new Metadata();
    headers.put(Metadata.Key.of("test", Metadata.ASCII_STRING_MARSHALLER),
        longStringOfA);

    callMeMaybe(transport.start(clientTransportListener));

    AtomicBoolean foundExpectedHeaderBytes = new AtomicBoolean(false);

    transport.channel().pipeline().addFirst(new ChannelDuplexHandler() {
      @Override
      public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise)
          throws Exception {
        if (msg instanceof ByteBuf) {
          if (((ByteBuf) msg).toString(StandardCharsets.UTF_8).contains(longStringOfA)) {
            foundExpectedHeaderBytes.set(true);
          }
        }
        super.write(ctx, msg, promise);
      }
    });

    new Rpc(transport, headers).halfClose().waitForResponse();

    if (!foundExpectedHeaderBytes.get()) {
      fail("expected to find UTF-8 encoded 'a's in the header");
    }
  }

  @Test
  public void maxHeaderListSizeShouldBeEnforcedOnServer() throws Exception {
    startServer(100, 1);

    NettyClientTransport transport = newTransport(newNegotiator());
    callMeMaybe(transport.start(clientTransportListener));

    try {
      // Send a single RPC and wait for the response.
      new Rpc(transport, new Metadata()).halfClose().waitForResponse();
      fail("The stream should have been failed due to server received header exceeds header list"
          + " size limit!");
    } catch (Exception e) {
      Status status = Status.fromThrowable(e);
      assertEquals(status.toString(), Status.Code.INTERNAL, status.getCode());
    }
  }

  @Test
  public void getAttributes_negotiatorHandler() throws Exception {
    address = TestUtils.testServerAddress(new InetSocketAddress(12345));
    authority = GrpcUtil.authorityFromHostAndPort(address.getHostString(), address.getPort());

    NettyClientTransport transport = newTransport(new NoopProtocolNegotiator());
    callMeMaybe(transport.start(clientTransportListener));

    assertNotNull(transport.getAttributes());
  }

  @Test
  public void getEagAttributes_negotiatorHandler() throws Exception {
    address = TestUtils.testServerAddress(new InetSocketAddress(12345));
    authority = GrpcUtil.authorityFromHostAndPort(address.getHostString(), address.getPort());

    NoopProtocolNegotiator npn = new NoopProtocolNegotiator();
    eagAttributes = Attributes.newBuilder()
        .set(Attributes.Key.create("trash"), "value")
        .build();
    NettyClientTransport transport = newTransport(npn);
    callMeMaybe(transport.start(clientTransportListener));

    // EAG Attributes are available before the negotiation is complete
    assertSame(eagAttributes, npn.grpcHandler.getEagAttributes());
  }

  @Test
  public void clientStreamGetsAttributes() throws Exception {
    startServer();
    NettyClientTransport transport = newTransport(newNegotiator());
    callMeMaybe(transport.start(clientTransportListener));
    Rpc rpc = new Rpc(transport).halfClose();
    rpc.waitForResponse();

    assertNotNull(rpc.stream.getAttributes().get(Grpc.TRANSPORT_ATTR_SSL_SESSION));
    assertEquals(address, rpc.stream.getAttributes().get(Grpc.TRANSPORT_ATTR_REMOTE_ADDR));
    Attributes serverTransportAttrs = serverTransportAttributesList.poll(1, TimeUnit.SECONDS);
    assertNotNull(serverTransportAttrs);
    SocketAddress clientAddr = serverTransportAttrs.get(Grpc.TRANSPORT_ATTR_REMOTE_ADDR);
    assertNotNull(clientAddr);
    assertEquals(clientAddr, rpc.stream.getAttributes().get(Grpc.TRANSPORT_ATTR_LOCAL_ADDR));
  }

  @Test
  public void keepAliveEnabled() throws Exception {
    startServer();
    NettyClientTransport transport = newTransport(newNegotiator(), DEFAULT_MAX_MESSAGE_SIZE,
        GrpcUtil.DEFAULT_MAX_HEADER_LIST_SIZE, null /* user agent */, true /* keep alive */);
    callMeMaybe(transport.start(clientTransportListener));
    Rpc rpc = new Rpc(transport).halfClose();
    rpc.waitForResponse();

    assertNotNull(transport.keepAliveManager());
  }

  @Test
  public void keepAliveDisabled() throws Exception {
    startServer();
    NettyClientTransport transport = newTransport(newNegotiator(), DEFAULT_MAX_MESSAGE_SIZE,
        GrpcUtil.DEFAULT_MAX_HEADER_LIST_SIZE, null /* user agent */, false /* keep alive */);
    callMeMaybe(transport.start(clientTransportListener));
    Rpc rpc = new Rpc(transport).halfClose();
    rpc.waitForResponse();

    assertNull(transport.keepAliveManager());
  }

  @Test
  public void keepAliveEnabled_shouldSetTcpUserTimeout() throws Exception {
    assume().that(Utils.isEpollAvailable()).isTrue();

    startServer();
    EventLoopGroup epollGroup = Utils.DEFAULT_WORKER_EVENT_LOOP_GROUP.create();
    int keepAliveTimeMillis = 12345670;
    int keepAliveTimeoutMillis = 1234567;
    try {
      NettyClientTransport transport = newTransport(newNegotiator(), DEFAULT_MAX_MESSAGE_SIZE,
          GrpcUtil.DEFAULT_MAX_HEADER_LIST_SIZE, null /* user agent */, true /* keep alive */,
          TimeUnit.MILLISECONDS.toNanos(keepAliveTimeMillis),
          TimeUnit.MILLISECONDS.toNanos(keepAliveTimeoutMillis),
          new ReflectiveChannelFactory<>(Utils.DEFAULT_CLIENT_CHANNEL_TYPE), epollGroup);

      callMeMaybe(transport.start(clientTransportListener));

      ChannelOption<Integer> tcpUserTimeoutOption = Utils.maybeGetTcpUserTimeoutOption();
      assertThat(tcpUserTimeoutOption).isNotNull();
      // on some linux based system, the integer value may have error (usually +-1)
      assertThat((double) transport.channel().config().getOption(tcpUserTimeoutOption))
          .isWithin(5.0).of((double) keepAliveTimeoutMillis);
    } finally {
      epollGroup.shutdownGracefully();
    }
  }

  @Test
  public void keepAliveDisabled_shouldNotSetTcpUserTimeout() throws Exception {
    assume().that(Utils.isEpollAvailable()).isTrue();

    startServer();
    EventLoopGroup epollGroup = Utils.DEFAULT_WORKER_EVENT_LOOP_GROUP.create();
    int keepAliveTimeMillis = 12345670;
    try {
      long keepAliveTimeNanos = TimeUnit.MILLISECONDS.toNanos(keepAliveTimeMillis);
      NettyClientTransport transport = newTransport(newNegotiator(), DEFAULT_MAX_MESSAGE_SIZE,
          GrpcUtil.DEFAULT_MAX_HEADER_LIST_SIZE, null /* user agent */, false /* keep alive */,
          keepAliveTimeNanos, keepAliveTimeNanos,
          new ReflectiveChannelFactory<>(Utils.DEFAULT_CLIENT_CHANNEL_TYPE), epollGroup);

      callMeMaybe(transport.start(clientTransportListener));

      ChannelOption<Integer> tcpUserTimeoutOption = Utils.maybeGetTcpUserTimeoutOption();
      assertThat(tcpUserTimeoutOption).isNotNull();
      // default TCP_USER_TIMEOUT=0 (use the system default)
      assertThat(transport.channel().config().getOption(tcpUserTimeoutOption)).isEqualTo(0);
    } finally {
      epollGroup.shutdownGracefully();
    }
  }

  /**
   * Verifies that we can successfully build a server and client negotiator with tls and the
   * executor passing in, and without resource leak after closing the negotiator.
   */
  @Test
  public void tlsNegotiationServerExecutorShouldSucceed() throws Exception {
    // initialize the client and server Executor pool
    TrackingObjectPoolForTest serverExecutorPool = new TrackingObjectPoolForTest();
    TrackingObjectPoolForTest clientExecutorPool = new TrackingObjectPoolForTest();
    assertEquals(false, serverExecutorPool.isInUse());
    assertEquals(false, clientExecutorPool.isInUse());

    InputStream serverCert = TlsTesting.loadCert("server1.pem");
    InputStream serverKey = TlsTesting.loadCert("server1.key");
    SslContext sslContext = GrpcSslContexts.forServer(serverCert, serverKey)
        .clientAuth(ClientAuth.NONE)
        .build();
    negotiator = ProtocolNegotiators.serverTls(sslContext, serverExecutorPool);
    startServer();
    // after starting the server, the Executor in the server pool should be used
    assertEquals(true, serverExecutorPool.isInUse());

    InputStream caCert = TlsTesting.loadCert("ca.pem");
    InputStream clientCert = TlsTesting.loadCert("client.pem");
    InputStream clientKey = TlsTesting.loadCert("client.key");
    SslContext clientContext = GrpcSslContexts.forClient()
        .trustManager(caCert)
        .keyManager(clientCert, clientKey)
        .build();
    ProtocolNegotiator negotiator = ProtocolNegotiators.tls(clientContext, clientExecutorPool,
        Optional.empty());
    // after starting the client, the Executor in the client pool should be used
    assertEquals(true, clientExecutorPool.isInUse());
    final NettyClientTransport transport = newTransport(negotiator);
    callMeMaybe(transport.start(clientTransportListener));
    Rpc rpc = new Rpc(transport).halfClose();
    rpc.waitForResponse();
    // closing the negotiators should return the executors back to pool, and release the resource
    negotiator.close();
    assertEquals(false, clientExecutorPool.isInUse());
    this.negotiator.close();
    assertEquals(false, serverExecutorPool.isInUse());
  }

  private Throwable getRootCause(Throwable t) {
    if (t.getCause() == null) {
      return t;
    }
    return getRootCause(t.getCause());
  }

  private ProtocolNegotiator newNegotiator() throws IOException {
    InputStream caCert = TlsTesting.loadCert("ca.pem");
    SslContext clientContext = GrpcSslContexts.forClient().trustManager(caCert).build();
    return ProtocolNegotiators.tls(clientContext);
  }

  private NettyClientTransport newTransport(ProtocolNegotiator negotiator) {
    return newTransport(negotiator, DEFAULT_MAX_MESSAGE_SIZE, GrpcUtil.DEFAULT_MAX_HEADER_LIST_SIZE,
        null /* user agent */, true /* keep alive */);
  }

  private NettyClientTransport newTransport(ProtocolNegotiator negotiator, int maxMsgSize,
      int maxHeaderListSize, String userAgent, boolean enableKeepAlive) {
    return newTransport(negotiator, maxMsgSize, maxHeaderListSize, userAgent, enableKeepAlive,
        TimeUnit.SECONDS.toNanos(10L), TimeUnit.SECONDS.toNanos(1L),
        new ReflectiveChannelFactory<>(NioSocketChannel.class), group);
  }

  private NettyClientTransport newTransport(ProtocolNegotiator negotiator, int maxMsgSize,
      int maxHeaderListSize, String userAgent, boolean enableKeepAlive, long keepAliveTimeNano,
      long keepAliveTimeoutNano, ChannelFactory<? extends Channel> channelFactory,
      EventLoopGroup group) {
    if (!enableKeepAlive) {
      keepAliveTimeNano = KEEPALIVE_TIME_NANOS_DISABLED;
    }
    NettyClientTransport transport =
        new NettyClientTransport(
            address,
            channelFactory,
            new HashMap<ChannelOption<?>, Object>(),
            group,
            negotiator,
            false,
            DEFAULT_WINDOW_SIZE,
            maxMsgSize,
            maxHeaderListSize,
            maxHeaderListSize,
            keepAliveTimeNano,
            keepAliveTimeoutNano,
            false,
            authority,
            userAgent,
            tooManyPingsRunnable,
            new TransportTracer(),
            eagAttributes,
            new SocketPicker(),
            new FakeChannelLogger(),
            false,
            Ticker.systemTicker());
    transports.add(transport);
    return transport;
  }

  private void startServer() throws IOException {
    startServer(100, GrpcUtil.DEFAULT_MAX_HEADER_LIST_SIZE);
  }

  private void startServer(int maxStreamsPerConnection, int maxHeaderListSize) throws IOException {
    server =
        new NettyServer(
            TestUtils.testServerAddresses(new InetSocketAddress(0)),
            new ReflectiveChannelFactory<>(NioServerSocketChannel.class),
            new HashMap<ChannelOption<?>, Object>(),
            new HashMap<ChannelOption<?>, Object>(),
            new FixedObjectPool<>(group),
            new FixedObjectPool<>(group),
            false,
            negotiator,
            Collections.<ServerStreamTracer.Factory>emptyList(),
            TransportTracer.getDefaultFactory(),
            maxStreamsPerConnection,
            false,
            DEFAULT_WINDOW_SIZE,
            DEFAULT_MAX_MESSAGE_SIZE,
            maxHeaderListSize,
            maxHeaderListSize,
            DEFAULT_SERVER_KEEPALIVE_TIME_NANOS,
            DEFAULT_SERVER_KEEPALIVE_TIMEOUT_NANOS,
            MAX_CONNECTION_IDLE_NANOS_DISABLED,
            MAX_CONNECTION_AGE_NANOS_DISABLED,
            MAX_CONNECTION_AGE_GRACE_NANOS_INFINITE,
            true,
            0,
            MAX_RST_COUNT_DISABLED,
            0,
            Attributes.EMPTY,
            channelz);
    server.start(serverListener);
    address = TestUtils.testServerAddress((InetSocketAddress) server.getListenSocketAddress());
    authority = GrpcUtil.authorityFromHostAndPort(address.getHostString(), address.getPort());
  }

  private void callMeMaybe(Runnable r) {
    if (r != null) {
      r.run();
    }
  }

  private static SslContext createSslContext() {
    try {
      InputStream serverCert = TlsTesting.loadCert("server1.pem");
      InputStream key = TlsTesting.loadCert("server1.key");
      return GrpcSslContexts.forServer(serverCert, key).build();
    } catch (IOException ex) {
      throw new RuntimeException(ex);
    }
  }

  private static class Rpc {
    static final String MESSAGE = "hello";
    static final MethodDescriptor<String, String> METHOD =
        MethodDescriptor.<String, String>newBuilder()
            .setType(MethodDescriptor.MethodType.UNARY)
            .setFullMethodName("testService/test")
            .setRequestMarshaller(StringMarshaller.INSTANCE)
            .setResponseMarshaller(StringMarshaller.INSTANCE)
            .build();

    final ClientStream stream;
    final TestClientStreamListener listener = new TestClientStreamListener();

    Rpc(NettyClientTransport transport) {
      this(transport, new Metadata());
    }

    Rpc(NettyClientTransport transport, Metadata headers) {
      stream = transport.newStream(
          METHOD, headers, CallOptions.DEFAULT,
          new ClientStreamTracer[]{ new ClientStreamTracer() {} });
      stream.start(listener);
      stream.request(1);
      stream.writeMessage(new ByteArrayInputStream(MESSAGE.getBytes(UTF_8)));
      stream.flush();
    }

    Rpc halfClose() {
      stream.halfClose();
      return this;
    }

    void waitForResponse() throws InterruptedException, ExecutionException, TimeoutException {
      listener.responseFuture.get(10, TimeUnit.SECONDS);
    }

    void waitForClose() throws InterruptedException, ExecutionException, TimeoutException {
      listener.closedFuture.get(10, TimeUnit.SECONDS);
    }
  }

  private static final class TestClientStreamListener implements ClientStreamListener {
    final SettableFuture<Void> closedFuture = SettableFuture.create();
    final SettableFuture<Void> responseFuture = SettableFuture.create();

    @Override
    public void headersRead(Metadata headers) {
    }

    @Override
    public void closed(Status status, RpcProgress rpcProgress, Metadata trailers) {
      if (status.isOk()) {
        closedFuture.set(null);
      } else {
        StatusException e = status.asException();
        closedFuture.setException(e);
        responseFuture.setException(e);
      }
    }

    @Override
    public void messagesAvailable(MessageProducer producer) {
      if (producer.next() != null) {
        responseFuture.set(null);
      }
    }

    @Override
    public void onReady() {
    }
  }

  private static final class EchoServerStreamListener implements ServerStreamListener {
    final ServerStream stream;
    final Metadata headers;

    EchoServerStreamListener(ServerStream stream, Metadata headers) {
      this.stream = stream;
      this.headers = headers;
    }

    @Override
    public void messagesAvailable(MessageProducer producer) {
      InputStream message;
      while ((message = producer.next()) != null) {
        // Just echo back the message.
        stream.writeMessage(message);
        stream.flush();
      }
    }

    @Override
    public void onReady() {
    }

    @Override
    public void halfClosed() {
      // Just close when the client closes.
      stream.close(Status.OK, new Metadata());
    }

    @Override
    public void closed(Status status) {
    }
  }

  private final class EchoServerListener implements ServerListener {
    final List<NettyServerTransport> transports = new ArrayList<>();
    final List<EchoServerStreamListener> streamListeners =
            Collections.synchronizedList(new ArrayList<EchoServerStreamListener>());

    @Override
    public ServerTransportListener transportCreated(final ServerTransport transport) {
      transports.add((NettyServerTransport) transport);
      return new ServerTransportListener() {
        @Override
        public void streamCreated(ServerStream stream, String method, Metadata headers) {
          EchoServerStreamListener listener = new EchoServerStreamListener(stream, headers);
          stream.setListener(listener);
          stream.writeHeaders(new Metadata(), true);
          stream.request(1);
          streamListeners.add(listener);
        }

        @Override
        public Attributes transportReady(Attributes transportAttrs) {
          serverTransportAttributesList.add(transportAttrs);
          return transportAttrs;
        }

        @Override
        public void transportTerminated() {}
      };
    }

    @Override
    public void serverShutdown() {
    }
  }

  private static final class StringMarshaller implements Marshaller<String> {
    static final StringMarshaller INSTANCE = new StringMarshaller();

    @Override
    public InputStream stream(String value) {
      return new ByteArrayInputStream(value.getBytes(UTF_8));
    }

    @Override
    public String parse(InputStream stream) {
      try {
        return new String(ByteStreams.toByteArray(stream), UTF_8);
      } catch (IOException ex) {
        throw new RuntimeException(ex);
      }
    }
  }

  private static class NoopHandler extends ChannelDuplexHandler {

    private final GrpcHttp2ConnectionHandler grpcHandler;

    public NoopHandler(GrpcHttp2ConnectionHandler grpcHandler) {
      this.grpcHandler = grpcHandler;
    }

    @Override
    public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
      ctx.pipeline().addBefore(ctx.name(), null, grpcHandler);
    }

    public void fail(ChannelHandlerContext ctx, Throwable cause) {
      ctx.fireExceptionCaught(cause);
    }
  }

  private static class NoopProtocolNegotiator implements ProtocolNegotiator {
    GrpcHttp2ConnectionHandler grpcHandler;
    NoopHandler handler;

    @Override
    public ChannelHandler newHandler(final GrpcHttp2ConnectionHandler grpcHandler) {
      this.grpcHandler = grpcHandler;
      return handler = new NoopHandler(grpcHandler);
    }

    @Override
    public AsciiString scheme() {
      return Utils.HTTP;
    }

    @Override
    public void close() {}
  }

  private static final class SocketPicker extends LocalSocketPicker {

    @Nullable
    @Override
    public SocketAddress createSocketAddress(SocketAddress remoteAddress, Attributes attrs) {
      return null;
    }
  }

  private static final class FakeChannelLogger extends ChannelLogger {

    @Override
    public void log(ChannelLogLevel level, String message) {}

    @Override
    public void log(ChannelLogLevel level, String messageFormat, Object... args) {}
  }
}
