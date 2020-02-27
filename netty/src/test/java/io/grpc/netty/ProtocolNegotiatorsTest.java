/*
 * Copyright 2015 The gRPC Authors
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

import static com.google.common.base.Charsets.UTF_8;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;

import io.grpc.Attributes;
import io.grpc.Grpc;
import io.grpc.InternalChannelz.Security;
import io.grpc.SecurityLevel;
import io.grpc.internal.GrpcAttributes;
import io.grpc.internal.testing.TestUtils;
import io.grpc.netty.ProtocolNegotiators.ClientTlsHandler;
import io.grpc.netty.ProtocolNegotiators.ClientTlsProtocolNegotiator;
import io.grpc.netty.ProtocolNegotiators.HostPort;
import io.grpc.netty.ProtocolNegotiators.ServerTlsHandler;
import io.grpc.netty.ProtocolNegotiators.WaitUntilActiveHandler;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerAdapter;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandler;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.channel.DefaultEventLoop;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalChannel;
import io.netty.channel.local.LocalServerChannel;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpServerUpgradeHandler;
import io.netty.handler.codec.http.HttpServerUpgradeHandler.UpgradeCodec;
import io.netty.handler.codec.http.HttpServerUpgradeHandler.UpgradeCodecFactory;
import io.netty.handler.codec.http2.DefaultHttp2Connection;
import io.netty.handler.codec.http2.DefaultHttp2ConnectionDecoder;
import io.netty.handler.codec.http2.DefaultHttp2ConnectionEncoder;
import io.netty.handler.codec.http2.DefaultHttp2FrameReader;
import io.netty.handler.codec.http2.DefaultHttp2FrameWriter;
import io.netty.handler.codec.http2.Http2ConnectionDecoder;
import io.netty.handler.codec.http2.Http2ConnectionEncoder;
import io.netty.handler.codec.http2.Http2ServerUpgradeCodec;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.proxy.ProxyConnectException;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.SslHandshakeCompletionEvent;
import io.netty.handler.ssl.SupportedCipherSuiteFilter;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import java.io.File;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Filter;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSession;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.DisableOnDebug;
import org.junit.rules.ExpectedException;
import org.junit.rules.TestRule;
import org.junit.rules.Timeout;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

@RunWith(JUnit4.class)
public class ProtocolNegotiatorsTest {
  private static final Runnable NOOP_RUNNABLE = new Runnable() {
    @Override public void run() {}
  };

  private static final int TIMEOUT_SECONDS = 60;
  @Rule public final TestRule globalTimeout = new DisableOnDebug(Timeout.seconds(TIMEOUT_SECONDS));
  @Rule public final ExpectedException thrown = ExpectedException.none();

  private final EventLoopGroup group = new DefaultEventLoop();
  private Channel chan;
  private Channel server;

  private final GrpcHttp2ConnectionHandler grpcHandler =
      FakeGrpcHttp2ConnectionHandler.newHandler();

  private EmbeddedChannel channel = new EmbeddedChannel();
  private ChannelPipeline pipeline = channel.pipeline();
  private SslContext sslContext;
  private SSLEngine engine;
  private ChannelHandlerContext channelHandlerCtx;

  @Before
  public void setUp() throws Exception {
    File serverCert = TestUtils.loadCert("server1.pem");
    File key = TestUtils.loadCert("server1.key");
    sslContext = GrpcSslContexts.forServer(serverCert, key)
        .ciphers(TestUtils.preferredTestCiphers(), SupportedCipherSuiteFilter.INSTANCE).build();
    engine = SSLContext.getDefault().createSSLEngine();
    engine.setUseClientMode(true);
  }

  @After
  public void tearDown() {
    if (server != null) {
      server.close();
    }
    if (chan != null) {
      chan.close();
    }
    group.shutdownGracefully();
  }

  @Test
  public void waitUntilActiveHandler_handlerAdded() throws Exception {
    final CountDownLatch latch = new CountDownLatch(1);

    final WaitUntilActiveHandler handler =
        new WaitUntilActiveHandler(new ChannelHandlerAdapter() {
          @Override
          public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
            assertTrue(ctx.channel().isActive());
            latch.countDown();
            super.handlerAdded(ctx);
          }
        });

    ChannelHandler lateAddingHandler = new ChannelInboundHandlerAdapter() {
      @Override
      public void channelActive(ChannelHandlerContext ctx) throws Exception {
        ctx.pipeline().addLast(handler);
        ctx.pipeline().fireUserEventTriggered(ProtocolNegotiationEvent.DEFAULT);
        // do not propagate channelActive().
      }
    };

    LocalAddress addr = new LocalAddress("local");
    ChannelFuture cf = new Bootstrap()
        .channel(LocalChannel.class)
        .handler(lateAddingHandler)
        .group(group)
        .register();
    chan = cf.channel();
    ChannelFuture sf = new ServerBootstrap()
        .channel(LocalServerChannel.class)
        .childHandler(new ChannelHandlerAdapter() {})
        .group(group)
        .bind(addr);
    server = sf.channel();
    sf.sync();

    assertEquals(1, latch.getCount());

    chan.connect(addr).sync();
    assertTrue(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    assertNull(chan.pipeline().context(WaitUntilActiveHandler.class));
  }

  @Test
  public void waitUntilActiveHandler_channelActive() throws Exception {
    final CountDownLatch latch = new CountDownLatch(1);
    WaitUntilActiveHandler handler =
        new WaitUntilActiveHandler(new ChannelHandlerAdapter() {
          @Override
          public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
            assertTrue(ctx.channel().isActive());
            latch.countDown();
            super.handlerAdded(ctx);
          }
        });

    LocalAddress addr = new LocalAddress("local");
    ChannelFuture cf = new Bootstrap()
        .channel(LocalChannel.class)
        .handler(handler)
        .group(group)
        .register();
    chan = cf.channel();
    ChannelFuture sf = new ServerBootstrap()
        .channel(LocalServerChannel.class)
        .childHandler(new ChannelHandlerAdapter() {})
        .group(group)
        .bind(addr);
    server = sf.channel();
    sf.sync();

    assertEquals(1, latch.getCount());

    chan.connect(addr).sync();
    chan.pipeline().fireUserEventTriggered(ProtocolNegotiationEvent.DEFAULT);
    assertTrue(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    assertNull(chan.pipeline().context(WaitUntilActiveHandler.class));
  }

  @Test
  public void tlsHandler_failsOnNullEngine() throws Exception {
    thrown.expect(NullPointerException.class);
    thrown.expectMessage("ssl");

    Object unused = ProtocolNegotiators.serverTls(null);
  }


  @Test
  public void tlsHandler_handlerAddedAddsSslHandler() throws Exception {
    ChannelHandler handler = new ServerTlsHandler(grpcHandler, sslContext, null);

    pipeline.addLast(handler);

    assertTrue(pipeline.first() instanceof SslHandler);
  }

  @Test
  public void tlsHandler_userEventTriggeredNonSslEvent() throws Exception {
    ChannelHandler handler = new ServerTlsHandler(grpcHandler, sslContext, null);
    pipeline.addLast(handler);
    channelHandlerCtx = pipeline.context(handler);
    Object nonSslEvent = new Object();

    pipeline.fireUserEventTriggered(nonSslEvent);

    // A non ssl event should not cause the grpcHandler to be in the pipeline yet.
    ChannelHandlerContext grpcHandlerCtx = pipeline.context(grpcHandler);
    assertNull(grpcHandlerCtx);
  }

  @Test
  public void tlsHandler_userEventTriggeredSslEvent_unsupportedProtocol() throws Exception {
    SslHandler badSslHandler = new SslHandler(engine, false) {
      @Override
      public String applicationProtocol() {
        return "badprotocol";
      }
    };

    ChannelHandler handler = new ServerTlsHandler(grpcHandler, sslContext, null);
    pipeline.addLast(handler);

    final AtomicReference<Throwable> error = new AtomicReference<>();
    ChannelHandler errorCapture = new ChannelInboundHandlerAdapter() {
      @Override
      public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        error.set(cause);
      }
    };

    pipeline.addLast(errorCapture);

    pipeline.replace(SslHandler.class, null, badSslHandler);
    channelHandlerCtx = pipeline.context(handler);
    Object sslEvent = SslHandshakeCompletionEvent.SUCCESS;

    pipeline.fireUserEventTriggered(sslEvent);

    // No h2 protocol was specified, so there should be an error, (normally handled by WBAEH)
    assertThat(error.get()).hasMessageThat().contains("Unable to find compatible protocol");
    ChannelHandlerContext grpcHandlerCtx = pipeline.context(grpcHandler);
    assertNull(grpcHandlerCtx);
  }

  @Test
  public void tlsHandler_userEventTriggeredSslEvent_handshakeFailure() throws Exception {
    ChannelHandler handler = new ServerTlsHandler(grpcHandler, sslContext, null);
    pipeline.addLast(handler);
    channelHandlerCtx = pipeline.context(handler);
    Object sslEvent = new SslHandshakeCompletionEvent(new RuntimeException("bad"));

    final AtomicReference<Throwable> error = new AtomicReference<>();
    ChannelHandler errorCapture = new ChannelInboundHandlerAdapter() {
      @Override
      public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        error.set(cause);
      }
    };

    pipeline.addLast(errorCapture);

    pipeline.fireUserEventTriggered(sslEvent);

    // No h2 protocol was specified, so there should be an error, (normally handled by WBAEH)
    assertThat(error.get()).hasMessageThat().contains("bad");
    ChannelHandlerContext grpcHandlerCtx = pipeline.context(grpcHandler);
    assertNull(grpcHandlerCtx);
  }

  @Test
  public void tlsHandler_userEventTriggeredSslEvent_supportedProtocolH2() throws Exception {
    SslHandler goodSslHandler = new SslHandler(engine, false) {
      @Override
      public String applicationProtocol() {
        return "h2";
      }
    };

    ChannelHandler handler = new ServerTlsHandler(grpcHandler, sslContext, null);
    pipeline.addLast(handler);

    pipeline.replace(SslHandler.class, null, goodSslHandler);
    channelHandlerCtx = pipeline.context(handler);
    Object sslEvent = SslHandshakeCompletionEvent.SUCCESS;

    pipeline.fireUserEventTriggered(sslEvent);

    assertTrue(channel.isOpen());
    ChannelHandlerContext grpcHandlerCtx = pipeline.context(grpcHandler);
    assertNotNull(grpcHandlerCtx);
  }

  @Test
  public void serverTlsHandler_userEventTriggeredSslEvent_supportedProtocolCustom()
      throws Exception {
    SslHandler goodSslHandler = new SslHandler(engine, false) {
      @Override
      public String applicationProtocol() {
        return "managed_mtls";
      }
    };

    File serverCert = TestUtils.loadCert("server1.pem");
    File key = TestUtils.loadCert("server1.key");
    List<String> alpnList = Arrays.asList("managed_mtls", "h2");
    ApplicationProtocolConfig apn = new ApplicationProtocolConfig(
        ApplicationProtocolConfig.Protocol.ALPN,
        ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
        ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
        alpnList);

    sslContext = GrpcSslContexts.forServer(serverCert, key)
        .ciphers(TestUtils.preferredTestCiphers(), SupportedCipherSuiteFilter.INSTANCE)
        .applicationProtocolConfig(apn).build();

    ChannelHandler handler = new ServerTlsHandler(grpcHandler, sslContext, null);
    pipeline.addLast(handler);

    pipeline.replace(SslHandler.class, null, goodSslHandler);
    channelHandlerCtx = pipeline.context(handler);
    Object sslEvent = SslHandshakeCompletionEvent.SUCCESS;

    pipeline.fireUserEventTriggered(sslEvent);

    assertTrue(channel.isOpen());
    ChannelHandlerContext grpcHandlerCtx = pipeline.context(grpcHandler);
    assertNotNull(grpcHandlerCtx);
  }

  @Test
  public void serverTlsHandler_userEventTriggeredSslEvent_unsupportedProtocolCustom()
      throws Exception {
    SslHandler badSslHandler = new SslHandler(engine, false) {
      @Override
      public String applicationProtocol() {
        return "badprotocol";
      }
    };

    File serverCert = TestUtils.loadCert("server1.pem");
    File key = TestUtils.loadCert("server1.key");
    List<String> alpnList = Arrays.asList("managed_mtls", "h2");
    ApplicationProtocolConfig apn = new ApplicationProtocolConfig(
        ApplicationProtocolConfig.Protocol.ALPN,
        ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
        ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
        alpnList);

    sslContext = GrpcSslContexts.forServer(serverCert, key)
        .ciphers(TestUtils.preferredTestCiphers(), SupportedCipherSuiteFilter.INSTANCE)
        .applicationProtocolConfig(apn).build();
    ChannelHandler handler = new ServerTlsHandler(grpcHandler, sslContext, null);
    pipeline.addLast(handler);

    final AtomicReference<Throwable> error = new AtomicReference<>();
    ChannelHandler errorCapture = new ChannelInboundHandlerAdapter() {
      @Override
      public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        error.set(cause);
      }
    };

    pipeline.addLast(errorCapture);

    pipeline.replace(SslHandler.class, null, badSslHandler);
    channelHandlerCtx = pipeline.context(handler);
    Object sslEvent = SslHandshakeCompletionEvent.SUCCESS;

    pipeline.fireUserEventTriggered(sslEvent);

    // No h2 protocol was specified, so there should be an error, (normally handled by WBAEH)
    assertThat(error.get()).hasMessageThat().contains("Unable to find compatible protocol");
    ChannelHandlerContext grpcHandlerCtx = pipeline.context(grpcHandler);
    assertNull(grpcHandlerCtx);
  }

  @Test
  public void clientTlsHandler_userEventTriggeredSslEvent_supportedProtocolH2() throws Exception {
    SslHandler goodSslHandler = new SslHandler(engine, false) {
      @Override
      public String applicationProtocol() {
        return "h2";
      }
    };
    DefaultEventLoopGroup elg = new DefaultEventLoopGroup(1);

    ClientTlsHandler handler = new ClientTlsHandler(grpcHandler, sslContext, "authority", elg);
    pipeline.addLast(handler);
    pipeline.replace(SslHandler.class, null, goodSslHandler);
    pipeline.fireUserEventTriggered(ProtocolNegotiationEvent.DEFAULT);
    channelHandlerCtx = pipeline.context(handler);
    Object sslEvent = SslHandshakeCompletionEvent.SUCCESS;

    pipeline.fireUserEventTriggered(sslEvent);

    ChannelHandlerContext grpcHandlerCtx = pipeline.context(grpcHandler);
    assertNotNull(grpcHandlerCtx);
  }

  @Test
  public void clientTlsHandler_userEventTriggeredSslEvent_supportedProtocolCustom()
      throws Exception {
    SslHandler goodSslHandler = new SslHandler(engine, false) {
      @Override
      public String applicationProtocol() {
        return "managed_mtls";
      }
    };
    DefaultEventLoopGroup elg = new DefaultEventLoopGroup(1);

    File clientCert = TestUtils.loadCert("client.pem");
    File key = TestUtils.loadCert("client.key");
    List<String> alpnList = Arrays.asList("managed_mtls", "h2");
    ApplicationProtocolConfig apn = new ApplicationProtocolConfig(
        ApplicationProtocolConfig.Protocol.ALPN,
        ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
        ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
        alpnList);

    sslContext = GrpcSslContexts.forClient()
        .keyManager(clientCert, key)
        .ciphers(TestUtils.preferredTestCiphers(), SupportedCipherSuiteFilter.INSTANCE)
        .applicationProtocolConfig(apn).build();

    ClientTlsHandler handler = new ClientTlsHandler(grpcHandler, sslContext, "authority", elg);
    pipeline.addLast(handler);
    pipeline.replace(SslHandler.class, null, goodSslHandler);
    pipeline.fireUserEventTriggered(ProtocolNegotiationEvent.DEFAULT);
    channelHandlerCtx = pipeline.context(handler);
    Object sslEvent = SslHandshakeCompletionEvent.SUCCESS;

    pipeline.fireUserEventTriggered(sslEvent);

    ChannelHandlerContext grpcHandlerCtx = pipeline.context(grpcHandler);
    assertNotNull(grpcHandlerCtx);
  }

  @Test
  public void clientTlsHandler_userEventTriggeredSslEvent_unsupportedProtocol() throws Exception {
    SslHandler goodSslHandler = new SslHandler(engine, false) {
      @Override
      public String applicationProtocol() {
        return "badproto";
      }
    };
    DefaultEventLoopGroup elg = new DefaultEventLoopGroup(1);

    ClientTlsHandler handler = new ClientTlsHandler(grpcHandler, sslContext, "authority", elg);
    pipeline.addLast(handler);

    final AtomicReference<Throwable> error = new AtomicReference<>();
    ChannelHandler errorCapture = new ChannelInboundHandlerAdapter() {
      @Override
      public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        error.set(cause);
      }
    };

    pipeline.addLast(errorCapture);
    pipeline.replace(SslHandler.class, null, goodSslHandler);
    pipeline.fireUserEventTriggered(ProtocolNegotiationEvent.DEFAULT);
    channelHandlerCtx = pipeline.context(handler);
    Object sslEvent = SslHandshakeCompletionEvent.SUCCESS;

    pipeline.fireUserEventTriggered(sslEvent);

    // Bad protocol was specified, so there should be an error, (normally handled by WBAEH)
    assertThat(error.get()).hasMessageThat().contains("Unable to find compatible protocol");
    ChannelHandlerContext grpcHandlerCtx = pipeline.context(grpcHandler);
    assertNull(grpcHandlerCtx);
  }

  @Test
  public void engineLog() {
    ChannelHandler handler = new ServerTlsHandler(grpcHandler, sslContext, null);
    pipeline.addLast(handler);
    channelHandlerCtx = pipeline.context(handler);

    Logger logger = Logger.getLogger(ProtocolNegotiators.class.getName());
    Filter oldFilter = logger.getFilter();
    try {
      logger.setFilter(new Filter() {
        @Override
        public boolean isLoggable(LogRecord record) {
          // We still want to the log method to be exercised, just not printed to stderr.
          return false;
        }
      });

      ProtocolNegotiators.logSslEngineDetails(
          Level.INFO, channelHandlerCtx, "message", new Exception("bad"));
    } finally {
      logger.setFilter(oldFilter);
    }
  }

  @Test
  public void tls_failsOnNullSslContext() {
    thrown.expect(NullPointerException.class);

    Object unused = ProtocolNegotiators.tls(null);
  }

  @Test
  public void tls_hostAndPort() {
    HostPort hostPort = ProtocolNegotiators.parseAuthority("authority:1234");

    assertEquals("authority", hostPort.host);
    assertEquals(1234, hostPort.port);
  }

  @Test
  public void tls_host() {
    HostPort hostPort = ProtocolNegotiators.parseAuthority("[::1]");

    assertEquals("[::1]", hostPort.host);
    assertEquals(-1, hostPort.port);
  }

  @Test
  public void tls_invalidHost() throws SSLException {
    HostPort hostPort = ProtocolNegotiators.parseAuthority("bad_host:1234");

    // Even though it looks like a port, we treat it as part of the authority, since the host is
    // invalid.
    assertEquals("bad_host:1234", hostPort.host);
    assertEquals(-1, hostPort.port);
  }

  @Test
  public void httpProxy_nullAddressNpe() throws Exception {
    thrown.expect(NullPointerException.class);
    Object unused =
        ProtocolNegotiators.httpProxy(null, "user", "pass", ProtocolNegotiators.plaintext());
  }

  @Test
  public void httpProxy_nullNegotiatorNpe() throws Exception {
    thrown.expect(NullPointerException.class);
    Object unused = ProtocolNegotiators.httpProxy(
        InetSocketAddress.createUnresolved("localhost", 80), "user", "pass", null);
  }

  @Test
  public void httpProxy_nullUserPassNoException() throws Exception {
    assertNotNull(ProtocolNegotiators.httpProxy(
        InetSocketAddress.createUnresolved("localhost", 80), null, null,
        ProtocolNegotiators.plaintext()));
  }

  @Test
  public void httpProxy_completes() throws Exception {
    DefaultEventLoopGroup elg = new DefaultEventLoopGroup(1);
    // ProxyHandler is incompatible with EmbeddedChannel because when channelRegistered() is called
    // the channel is already active.
    LocalAddress proxy = new LocalAddress("httpProxy_completes");
    SocketAddress host = InetSocketAddress.createUnresolved("specialHost", 314);

    ChannelInboundHandler mockHandler = mock(ChannelInboundHandler.class);
    Channel serverChannel = new ServerBootstrap().group(elg).channel(LocalServerChannel.class)
        .childHandler(mockHandler)
        .bind(proxy).sync().channel();

    ProtocolNegotiator nego =
        ProtocolNegotiators.httpProxy(proxy, null, null, ProtocolNegotiators.plaintext());
    // normally NettyClientTransport will add WBAEH which kick start the ProtocolNegotiation,
    // mocking the behavior using KickStartHandler.
    ChannelHandler handler =
        new KickStartHandler(nego.newHandler(FakeGrpcHttp2ConnectionHandler.noopHandler()));
    Channel channel = new Bootstrap().group(elg).channel(LocalChannel.class).handler(handler)
        .register().sync().channel();
    pipeline = channel.pipeline();
    // Wait for initialization to complete
    channel.eventLoop().submit(NOOP_RUNNABLE).sync();
    channel.connect(host).sync();
    serverChannel.close();
    ArgumentCaptor<ChannelHandlerContext> contextCaptor =
        ArgumentCaptor.forClass(ChannelHandlerContext.class);
    Mockito.verify(mockHandler).channelActive(contextCaptor.capture());
    ChannelHandlerContext serverContext = contextCaptor.getValue();

    final String golden = "isThisThingOn?";
    ChannelFuture negotiationFuture = channel.writeAndFlush(bb(golden, channel));

    // Wait for sending initial request to complete
    channel.eventLoop().submit(NOOP_RUNNABLE).sync();
    ArgumentCaptor<Object> objectCaptor = ArgumentCaptor.forClass(Object.class);
    Mockito.verify(mockHandler)
        .channelRead(ArgumentMatchers.<ChannelHandlerContext>any(), objectCaptor.capture());
    ByteBuf b = (ByteBuf) objectCaptor.getValue();
    String request = b.toString(UTF_8);
    b.release();
    assertTrue("No trailing newline: " + request, request.endsWith("\r\n\r\n"));
    assertTrue("No CONNECT: " + request, request.startsWith("CONNECT specialHost:314 "));
    assertTrue("No host header: " + request, request.contains("host: specialHost:314"));

    assertFalse(negotiationFuture.isDone());
    serverContext.writeAndFlush(bb("HTTP/1.1 200 OK\r\n\r\n", serverContext.channel())).sync();
    negotiationFuture.sync();

    channel.eventLoop().submit(NOOP_RUNNABLE).sync();
    objectCaptor = ArgumentCaptor.forClass(Object.class);
    Mockito.verify(mockHandler, times(2))
        .channelRead(ArgumentMatchers.<ChannelHandlerContext>any(), objectCaptor.capture());
    b = (ByteBuf) objectCaptor.getAllValues().get(1);
    // If we were using the real grpcHandler, this would have been the HTTP/2 preface
    String preface = b.toString(UTF_8);
    b.release();
    assertEquals(golden, preface);

    channel.close();
  }

  @Test
  public void httpProxy_500() throws Exception {
    DefaultEventLoopGroup elg = new DefaultEventLoopGroup(1);
    // ProxyHandler is incompatible with EmbeddedChannel because when channelRegistered() is called
    // the channel is already active.
    LocalAddress proxy = new LocalAddress("httpProxy_500");
    SocketAddress host = InetSocketAddress.createUnresolved("specialHost", 314);

    ChannelInboundHandler mockHandler = mock(ChannelInboundHandler.class);
    Channel serverChannel = new ServerBootstrap().group(elg).channel(LocalServerChannel.class)
        .childHandler(mockHandler)
        .bind(proxy).sync().channel();

    ProtocolNegotiator nego =
        ProtocolNegotiators.httpProxy(proxy, null, null, ProtocolNegotiators.plaintext());
    // normally NettyClientTransport will add WBAEH which kick start the ProtocolNegotiation,
    // mocking the behavior using KickStartHandler.
    ChannelHandler handler =
        new KickStartHandler(nego.newHandler(FakeGrpcHttp2ConnectionHandler.noopHandler()));
    Channel channel = new Bootstrap().group(elg).channel(LocalChannel.class).handler(handler)
        .register().sync().channel();
    pipeline = channel.pipeline();
    // Wait for initialization to complete
    channel.eventLoop().submit(NOOP_RUNNABLE).sync();
    channel.connect(host).sync();
    serverChannel.close();
    ArgumentCaptor<ChannelHandlerContext> contextCaptor =
        ArgumentCaptor.forClass(ChannelHandlerContext.class);
    Mockito.verify(mockHandler).channelActive(contextCaptor.capture());
    ChannelHandlerContext serverContext = contextCaptor.getValue();

    final String golden = "isThisThingOn?";
    ChannelFuture negotiationFuture = channel.writeAndFlush(bb(golden, channel));

    // Wait for sending initial request to complete
    channel.eventLoop().submit(NOOP_RUNNABLE).sync();
    ArgumentCaptor<Object> objectCaptor = ArgumentCaptor.forClass(Object.class);
    Mockito.verify(mockHandler)
        .channelRead(any(ChannelHandlerContext.class), objectCaptor.capture());
    ByteBuf request = (ByteBuf) objectCaptor.getValue();
    request.release();

    assertFalse(negotiationFuture.isDone());
    String response = "HTTP/1.1 500 OMG\r\nContent-Length: 4\r\n\r\noops";
    serverContext.writeAndFlush(bb(response, serverContext.channel())).sync();
    thrown.expect(ProxyConnectException.class);
    try {
      negotiationFuture.sync();
    } finally {
      channel.close();
    }
  }

  @Test
  public void waitUntilActiveHandler_firesNegotiation() throws Exception {
    EventLoopGroup elg = new DefaultEventLoopGroup(1);
    SocketAddress addr = new LocalAddress("addr");
    final AtomicReference<Object> event = new AtomicReference<>();
    ChannelHandler next = new ChannelInboundHandlerAdapter() {
      @Override
      public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
        event.set(evt);
        ctx.close();
      }
    };
    Channel s = new ServerBootstrap()
        .childHandler(new ChannelInboundHandlerAdapter())
        .group(elg)
        .channel(LocalServerChannel.class)
        .bind(addr)
        .sync()
        .channel();
    Channel c = new Bootstrap()
        .handler(new WaitUntilActiveHandler(next))
        .channel(LocalChannel.class).group(group)
        .connect(addr)
        .sync()
        .channel();
    c.pipeline().fireUserEventTriggered(ProtocolNegotiationEvent.DEFAULT);
    SocketAddress localAddr = c.localAddress();
    ProtocolNegotiationEvent expectedEvent = ProtocolNegotiationEvent.DEFAULT
        .withAttributes(
            Attributes.newBuilder()
                .set(Grpc.TRANSPORT_ATTR_LOCAL_ADDR, localAddr)
                .set(Grpc.TRANSPORT_ATTR_REMOTE_ADDR, addr)
                .set(GrpcAttributes.ATTR_SECURITY_LEVEL, SecurityLevel.NONE)
                .build());

    c.closeFuture().sync();
    assertThat(event.get()).isInstanceOf(ProtocolNegotiationEvent.class);
    ProtocolNegotiationEvent actual = (ProtocolNegotiationEvent) event.get();
    assertThat(actual).isEqualTo(expectedEvent);

    s.close();
    elg.shutdownGracefully();
  }

  @Test
  public void clientTlsHandler_firesNegotiation() throws Exception {
    SelfSignedCertificate cert = new SelfSignedCertificate("authority");
    SslContext clientSslContext =
        GrpcSslContexts.configure(SslContextBuilder.forClient().trustManager(cert.cert())).build();
    SslContext serverSslContext =
        GrpcSslContexts.configure(SslContextBuilder.forServer(cert.key(), cert.cert())).build();
    FakeGrpcHttp2ConnectionHandler gh = FakeGrpcHttp2ConnectionHandler.newHandler();
    ClientTlsProtocolNegotiator pn = new ClientTlsProtocolNegotiator(clientSslContext, null);
    WriteBufferingAndExceptionHandler clientWbaeh =
        new WriteBufferingAndExceptionHandler(pn.newHandler(gh));

    SocketAddress addr = new LocalAddress("addr");

    ChannelHandler sh =
        ProtocolNegotiators.serverTls(serverSslContext)
            .newHandler(FakeGrpcHttp2ConnectionHandler.noopHandler());
    WriteBufferingAndExceptionHandler serverWbaeh = new WriteBufferingAndExceptionHandler(sh);
    Channel s = new ServerBootstrap()
        .childHandler(serverWbaeh)
        .group(group)
        .channel(LocalServerChannel.class)
        .bind(addr)
        .sync()
        .channel();
    Channel c = new Bootstrap()
        .handler(clientWbaeh)
        .channel(LocalChannel.class)
        .group(group)
        .register()
        .sync()
        .channel();
    ChannelFuture write = c.writeAndFlush(NettyClientHandler.NOOP_MESSAGE);
    c.connect(addr).sync();
    write.sync();

    boolean completed = gh.negotiated.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    if (!completed) {
      assertTrue("failed to negotiated", write.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
      // sync should fail if we are in this block.
      write.sync();
      throw new AssertionError("neither wrote nor negotiated");
    }
    c.close();
    s.close();
    pn.close();

    assertThat(gh.securityInfo).isNotNull();
    assertThat(gh.securityInfo.tls).isNotNull();
    assertThat(gh.attrs.get(GrpcAttributes.ATTR_SECURITY_LEVEL))
        .isEqualTo(SecurityLevel.PRIVACY_AND_INTEGRITY);
    assertThat(gh.attrs.get(Grpc.TRANSPORT_ATTR_SSL_SESSION)).isInstanceOf(SSLSession.class);
    // This is not part of the ClientTls negotiation, but shows that the negotiation event happens
    // in the right order.
    assertThat(gh.attrs.get(Grpc.TRANSPORT_ATTR_REMOTE_ADDR)).isEqualTo(addr);
  }

  @Test
  public void plaintextUpgradeNegotiator() throws Exception {
    LocalAddress addr = new LocalAddress("plaintextUpgradeNegotiator");
    UpgradeCodecFactory ucf = new UpgradeCodecFactory() {

      @Override
      public UpgradeCodec newUpgradeCodec(CharSequence protocol) {
        return new Http2ServerUpgradeCodec(FakeGrpcHttp2ConnectionHandler.newHandler());
      }
    };
    final HttpServerCodec serverCodec = new HttpServerCodec();
    final HttpServerUpgradeHandler serverUpgradeHandler =
        new HttpServerUpgradeHandler(serverCodec, ucf);
    Channel serverChannel = new ServerBootstrap()
        .group(group)
        .channel(LocalServerChannel.class)
        .childHandler(new ChannelInitializer<Channel>() {

          @Override
          protected void initChannel(Channel ch) throws Exception {
            ch.pipeline().addLast(serverCodec, serverUpgradeHandler);
          }
        })
        .bind(addr)
        .sync()
        .channel();

    FakeGrpcHttp2ConnectionHandler gh = FakeGrpcHttp2ConnectionHandler.newHandler();
    ProtocolNegotiator nego = ProtocolNegotiators.plaintextUpgrade();
    ChannelHandler ch = nego.newHandler(gh);
    WriteBufferingAndExceptionHandler wbaeh = new WriteBufferingAndExceptionHandler(ch);

    Channel channel = new Bootstrap()
        .group(group)
        .channel(LocalChannel.class)
        .handler(wbaeh)
        .register()
        .sync()
        .channel();

    ChannelFuture write = channel.writeAndFlush(NettyClientHandler.NOOP_MESSAGE);
    channel.connect(serverChannel.localAddress());

    boolean completed = gh.negotiated.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    if (!completed) {
      assertTrue("failed to negotiated", write.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
      // sync should fail if we are in this block.
      write.sync();
      throw new AssertionError("neither wrote nor negotiated");
    }

    channel.close().sync();
    serverChannel.close();

    assertThat(gh.securityInfo).isNull();
    assertThat(gh.attrs.get(GrpcAttributes.ATTR_SECURITY_LEVEL)).isEqualTo(SecurityLevel.NONE);
    assertThat(gh.attrs.get(Grpc.TRANSPORT_ATTR_REMOTE_ADDR)).isEqualTo(addr);
  }

  private static class FakeGrpcHttp2ConnectionHandler extends GrpcHttp2ConnectionHandler {

    static FakeGrpcHttp2ConnectionHandler noopHandler() {
      return newHandler(true);
    }

    static FakeGrpcHttp2ConnectionHandler newHandler() {
      return newHandler(false);
    }

    private static FakeGrpcHttp2ConnectionHandler newHandler(boolean noop) {
      DefaultHttp2Connection conn = new DefaultHttp2Connection(/*server=*/ false);
      DefaultHttp2ConnectionEncoder encoder =
          new DefaultHttp2ConnectionEncoder(conn, new DefaultHttp2FrameWriter());
      DefaultHttp2ConnectionDecoder decoder =
          new DefaultHttp2ConnectionDecoder(conn, encoder, new DefaultHttp2FrameReader());
      Http2Settings settings = new Http2Settings();
      return new FakeGrpcHttp2ConnectionHandler(
          /*channelUnused=*/ null, decoder, encoder, settings, noop);
    }

    private final boolean noop;
    private Attributes attrs;
    private Security securityInfo;
    private final CountDownLatch negotiated = new CountDownLatch(1);
    private ChannelHandlerContext ctx;

    FakeGrpcHttp2ConnectionHandler(ChannelPromise channelUnused,
        Http2ConnectionDecoder decoder,
        Http2ConnectionEncoder encoder,
        Http2Settings initialSettings,
        boolean noop) {
      super(channelUnused, decoder, encoder, initialSettings);
      this.noop = noop;
    }

    @Override
    public void handleProtocolNegotiationCompleted(Attributes attrs, Security securityInfo) {
      checkNotNull(ctx, "handleProtocolNegotiationCompleted cannot be called before handlerAdded");
      super.handleProtocolNegotiationCompleted(attrs, securityInfo);
      this.attrs = attrs;
      this.securityInfo = securityInfo;
      // Add a temp handler that verifies first message is a NOOP_MESSAGE
      ctx.pipeline().addBefore(ctx.name(), null, new ChannelOutboundHandlerAdapter() {
        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise)
            throws Exception {
          checkState(
              msg == NettyClientHandler.NOOP_MESSAGE, "First message should be NOOP_MESSAGE");
          promise.trySuccess();
          ctx.pipeline().remove(this);
        }
      });
      NettyClientHandler.writeBufferingAndRemove(ctx.channel());
      negotiated.countDown();
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
      if (noop) {
        ctx.pipeline().remove(ctx.name());
      } else {
        super.handlerAdded(ctx);
      }
      this.ctx = ctx;
    }

    @Override
    public String getAuthority() {
      return "authority";
    }
  }

  private static ByteBuf bb(String s, Channel c) {
    return ByteBufUtil.writeUtf8(c.alloc(), s);
  }

  private static final class KickStartHandler extends ChannelDuplexHandler {

    private final ChannelHandler next;

    public KickStartHandler(ChannelHandler next) {
      this.next = checkNotNull(next, "next");
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
      ctx.pipeline().replace(ctx.name(), null, next);
      ctx.pipeline().fireUserEventTriggered(ProtocolNegotiationEvent.DEFAULT);
    }
  }
}
