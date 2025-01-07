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

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.errorprone.annotations.ForOverride;
import io.grpc.Attributes;
import io.grpc.CallCredentials;
import io.grpc.ChannelCredentials;
import io.grpc.ChannelLogger;
import io.grpc.ChannelLogger.ChannelLogLevel;
import io.grpc.ChoiceChannelCredentials;
import io.grpc.ChoiceServerCredentials;
import io.grpc.CompositeCallCredentials;
import io.grpc.CompositeChannelCredentials;
import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.InsecureServerCredentials;
import io.grpc.InternalChannelz.Security;
import io.grpc.InternalChannelz.Tls;
import io.grpc.SecurityLevel;
import io.grpc.ServerCredentials;
import io.grpc.Status;
import io.grpc.TlsChannelCredentials;
import io.grpc.TlsServerCredentials;
import io.grpc.internal.GrpcAttributes;
import io.grpc.internal.GrpcUtil;
import io.grpc.internal.ObjectPool;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpClientUpgradeHandler;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http2.Http2ClientUpgradeCodec;
import io.netty.handler.proxy.HttpProxyHandler;
import io.netty.handler.proxy.ProxyConnectionEvent;
import io.netty.handler.ssl.OpenSsl;
import io.netty.handler.ssl.OpenSslEngine;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.SslHandshakeCompletionEvent;
import io.netty.handler.ssl.SslProvider;
import io.netty.util.AsciiString;
import java.io.ByteArrayInputStream;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.channels.ClosedChannelException;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;

/**
 * Common {@link ProtocolNegotiator}s used by gRPC.
 */
final class ProtocolNegotiators {
  private static final Logger log = Logger.getLogger(ProtocolNegotiators.class.getName());
  private static final EnumSet<TlsChannelCredentials.Feature> understoodTlsFeatures =
      EnumSet.of(
          TlsChannelCredentials.Feature.MTLS, TlsChannelCredentials.Feature.CUSTOM_MANAGERS);
  private static final EnumSet<TlsServerCredentials.Feature> understoodServerTlsFeatures =
      EnumSet.of(
          TlsServerCredentials.Feature.MTLS, TlsServerCredentials.Feature.CUSTOM_MANAGERS);


  private ProtocolNegotiators() {
  }

  public static FromChannelCredentialsResult from(ChannelCredentials creds) {
    if (creds instanceof TlsChannelCredentials) {
      TlsChannelCredentials tlsCreds = (TlsChannelCredentials) creds;
      Set<TlsChannelCredentials.Feature> incomprehensible =
          tlsCreds.incomprehensible(understoodTlsFeatures);
      if (!incomprehensible.isEmpty()) {
        return FromChannelCredentialsResult.error(
            "TLS features not understood: " + incomprehensible);
      }
      SslContextBuilder builder = GrpcSslContexts.forClient();
      if (tlsCreds.getKeyManagers() != null) {
        builder.keyManager(new FixedKeyManagerFactory(tlsCreds.getKeyManagers()));
      } else if (tlsCreds.getPrivateKey() != null) {
        builder.keyManager(
            new ByteArrayInputStream(tlsCreds.getCertificateChain()),
            new ByteArrayInputStream(tlsCreds.getPrivateKey()),
            tlsCreds.getPrivateKeyPassword());
      }
      if (tlsCreds.getTrustManagers() != null) {
        builder.trustManager(new FixedTrustManagerFactory(tlsCreds.getTrustManagers()));
      } else if (tlsCreds.getRootCertificates() != null) {
        builder.trustManager(new ByteArrayInputStream(tlsCreds.getRootCertificates()));
      } // else use system default
      try {
        return FromChannelCredentialsResult.negotiator(tlsClientFactory(builder.build()));
      } catch (SSLException ex) {
        log.log(Level.FINE, "Exception building SslContext", ex);
        return FromChannelCredentialsResult.error(
            "Unable to create SslContext: " + ex.getMessage());
      }

    } else if (creds instanceof InsecureChannelCredentials) {
      return FromChannelCredentialsResult.negotiator(plaintextClientFactory());

    } else if (creds instanceof CompositeChannelCredentials) {
      CompositeChannelCredentials compCreds = (CompositeChannelCredentials) creds;
      return from(compCreds.getChannelCredentials())
          .withCallCredentials(compCreds.getCallCredentials());

    } else if (creds instanceof NettyChannelCredentials) {
      NettyChannelCredentials nettyCreds = (NettyChannelCredentials) creds;
      return FromChannelCredentialsResult.negotiator(nettyCreds.getNegotiator());

    } else if (creds instanceof ChoiceChannelCredentials) {
      ChoiceChannelCredentials choiceCreds = (ChoiceChannelCredentials) creds;
      StringBuilder error = new StringBuilder();
      for (ChannelCredentials innerCreds : choiceCreds.getCredentialsList()) {
        FromChannelCredentialsResult result = from(innerCreds);
        if (result.error == null) {
          return result;
        }
        error.append(", ");
        error.append(result.error);
      }
      return FromChannelCredentialsResult.error(error.substring(2));

    } else {
      return FromChannelCredentialsResult.error(
          "Unsupported credential type: " + creds.getClass().getName());
    }
  }

  public static FromServerCredentialsResult from(ServerCredentials creds) {
    if (creds instanceof TlsServerCredentials) {
      TlsServerCredentials tlsCreds = (TlsServerCredentials) creds;
      Set<TlsServerCredentials.Feature> incomprehensible =
          tlsCreds.incomprehensible(understoodServerTlsFeatures);
      if (!incomprehensible.isEmpty()) {
        return FromServerCredentialsResult.error(
            "TLS features not understood: " + incomprehensible);
      }
      SslContextBuilder builder;
      if (tlsCreds.getKeyManagers() != null) {
        builder = GrpcSslContexts.configure(SslContextBuilder.forServer(
            new FixedKeyManagerFactory(tlsCreds.getKeyManagers())));
      } else if (tlsCreds.getPrivateKey() != null) {
        builder = GrpcSslContexts.forServer(
            new ByteArrayInputStream(tlsCreds.getCertificateChain()),
            new ByteArrayInputStream(tlsCreds.getPrivateKey()),
            tlsCreds.getPrivateKeyPassword());
      } else {
        throw new AssertionError("BUG! No key");
      }
      if (tlsCreds.getTrustManagers() != null) {
        builder.trustManager(new FixedTrustManagerFactory(tlsCreds.getTrustManagers()));
      } else if (tlsCreds.getRootCertificates() != null) {
        builder.trustManager(new ByteArrayInputStream(tlsCreds.getRootCertificates()));
      } // else use system default
      switch (tlsCreds.getClientAuth()) {
        case OPTIONAL:
          builder.clientAuth(io.netty.handler.ssl.ClientAuth.OPTIONAL);
          break;

        case REQUIRE:
          builder.clientAuth(io.netty.handler.ssl.ClientAuth.REQUIRE);
          break;

        case NONE:
          builder.clientAuth(io.netty.handler.ssl.ClientAuth.NONE);
          break;

        default:
          return FromServerCredentialsResult.error(
              "Unknown TlsServerCredentials.ClientAuth value: " + tlsCreds.getClientAuth());
      }
      SslContext sslContext;
      try {
        sslContext = builder.build();
      } catch (SSLException ex) {
        throw new IllegalArgumentException(
            "Unexpected error converting ServerCredentials to Netty SslContext", ex);
      }
      return FromServerCredentialsResult.negotiator(serverTlsFactory(sslContext));

    } else if (creds instanceof InsecureServerCredentials) {
      return FromServerCredentialsResult.negotiator(serverPlaintextFactory());

    } else if (creds instanceof NettyServerCredentials) {
      NettyServerCredentials nettyCreds = (NettyServerCredentials) creds;
      return FromServerCredentialsResult.negotiator(nettyCreds.getNegotiator());

    } else if (creds instanceof ChoiceServerCredentials) {
      ChoiceServerCredentials choiceCreds = (ChoiceServerCredentials) creds;
      StringBuilder error = new StringBuilder();
      for (ServerCredentials innerCreds : choiceCreds.getCredentialsList()) {
        FromServerCredentialsResult result = from(innerCreds);
        if (result.error == null) {
          return result;
        }
        error.append(", ");
        error.append(result.error);
      }
      return FromServerCredentialsResult.error(error.substring(2));

    } else {
      return FromServerCredentialsResult.error(
          "Unsupported credential type: " + creds.getClass().getName());
    }
  }

  public static final class FromChannelCredentialsResult {
    public final ProtocolNegotiator.ClientFactory negotiator;
    public final CallCredentials callCredentials;
    public final String error;

    private FromChannelCredentialsResult(ProtocolNegotiator.ClientFactory negotiator,
        CallCredentials creds, String error) {
      this.negotiator = negotiator;
      this.callCredentials = creds;
      this.error = error;
    }

    public static FromChannelCredentialsResult error(String error) {
      return new FromChannelCredentialsResult(
          null, null, Preconditions.checkNotNull(error, "error"));
    }

    public static FromChannelCredentialsResult negotiator(
        ProtocolNegotiator.ClientFactory factory) {
      return new FromChannelCredentialsResult(
          Preconditions.checkNotNull(factory, "factory"), null, null);
    }

    public FromChannelCredentialsResult withCallCredentials(CallCredentials callCreds) {
      Preconditions.checkNotNull(callCreds, "callCreds");
      if (error != null) {
        return this;
      }
      if (this.callCredentials != null) {
        callCreds = new CompositeCallCredentials(this.callCredentials, callCreds);
      }
      return new FromChannelCredentialsResult(negotiator, callCreds, null);
    }
  }

  public static final class FromServerCredentialsResult {
    public final ProtocolNegotiator.ServerFactory negotiator;
    public final String error;

    private FromServerCredentialsResult(ProtocolNegotiator.ServerFactory negotiator, String error) {
      this.negotiator = negotiator;
      this.error = error;
    }

    public static FromServerCredentialsResult error(String error) {
      return new FromServerCredentialsResult(null, Preconditions.checkNotNull(error, "error"));
    }

    public static FromServerCredentialsResult negotiator(ProtocolNegotiator.ServerFactory factory) {
      return new FromServerCredentialsResult(Preconditions.checkNotNull(factory, "factory"), null);
    }
  }

  public static ProtocolNegotiator.ServerFactory fixedServerFactory(
      ProtocolNegotiator negotiator) {
    return new FixedProtocolNegotiatorServerFactory(negotiator);
  }

  private static final class FixedProtocolNegotiatorServerFactory
      implements ProtocolNegotiator.ServerFactory {
    private final ProtocolNegotiator protocolNegotiator;

    public FixedProtocolNegotiatorServerFactory(ProtocolNegotiator protocolNegotiator) {
      this.protocolNegotiator =
          Preconditions.checkNotNull(protocolNegotiator, "protocolNegotiator");
    }

    @Override
    public ProtocolNegotiator newNegotiator(ObjectPool<? extends Executor> offloadExecutorPool) {
      return protocolNegotiator;
    }
  }

  /**
   * Create a server plaintext handler for gRPC.
   */
  public static ProtocolNegotiator serverPlaintext() {
    return new PlaintextProtocolNegotiator();
  }

  /**
   * Create a server plaintext handler factory for gRPC.
   */
  public static ProtocolNegotiator.ServerFactory serverPlaintextFactory() {
    return new PlaintextProtocolNegotiatorServerFactory();
  }

  @VisibleForTesting
  static final class PlaintextProtocolNegotiatorServerFactory
      implements ProtocolNegotiator.ServerFactory {
    @Override
    public ProtocolNegotiator newNegotiator(ObjectPool<? extends Executor> offloadExecutorPool) {
      return serverPlaintext();
    }
  }

  public static ProtocolNegotiator.ServerFactory serverTlsFactory(SslContext sslContext) {
    return new TlsProtocolNegotiatorServerFactory(sslContext);
  }

  @VisibleForTesting
  static final class TlsProtocolNegotiatorServerFactory
      implements ProtocolNegotiator.ServerFactory {
    private final SslContext sslContext;

    public TlsProtocolNegotiatorServerFactory(SslContext sslContext) {
      this.sslContext = Preconditions.checkNotNull(sslContext, "sslContext");
    }

    @Override
    public ProtocolNegotiator newNegotiator(ObjectPool<? extends Executor> offloadExecutorPool) {
      return serverTls(sslContext, offloadExecutorPool);
    }
  }

  /**
   * Create a server TLS handler for HTTP/2 capable of using ALPN/NPN.
   * @param executorPool a dedicated {@link Executor} pool for time-consuming TLS tasks
   */
  public static ProtocolNegotiator serverTls(final SslContext sslContext,
      final ObjectPool<? extends Executor> executorPool) {
    Preconditions.checkNotNull(sslContext, "sslContext");
    final Executor executor;
    if (executorPool != null) {
      // The handlers here can out-live the {@link ProtocolNegotiator}.
      // To keep their own reference to executor from executorPool, we use an extra (unused)
      // reference here forces the executor to stay alive, which prevents it from being re-created
      // for every connection.
      executor = executorPool.getObject();
    } else {
      executor = null;
    }
    return new ProtocolNegotiator() {
      @Override
      public ChannelHandler newHandler(GrpcHttp2ConnectionHandler handler) {
        ChannelHandler gnh = new GrpcNegotiationHandler(handler);
        ChannelHandler sth = new ServerTlsHandler(gnh, sslContext, executorPool);
        return new WaitUntilActiveHandler(sth, handler.getNegotiationLogger());
      }

      @Override
      public void close() {
        if (executorPool != null && executor != null) {
          executorPool.returnObject(executor);
        }
      }

      @Override
      public AsciiString scheme() {
        return Utils.HTTPS;
      }
    };
  }

  /**
   * Create a server TLS handler for HTTP/2 capable of using ALPN/NPN.
   */
  public static ProtocolNegotiator serverTls(final SslContext sslContext) {
    return serverTls(sslContext, null);
  }

  static final class ServerTlsHandler extends ChannelInboundHandlerAdapter {
    private Executor executor;
    private final ChannelHandler next;
    private final SslContext sslContext;

    private ProtocolNegotiationEvent pne = ProtocolNegotiationEvent.DEFAULT;

    ServerTlsHandler(ChannelHandler next,
        SslContext sslContext,
        final ObjectPool<? extends Executor> executorPool) {
      this.sslContext = checkNotNull(sslContext, "sslContext");
      this.next = checkNotNull(next, "next");
      if (executorPool != null) {
        this.executor = executorPool.getObject();
      }
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
      super.handlerAdded(ctx);
      SSLEngine sslEngine = sslContext.newEngine(ctx.alloc());
      ctx.pipeline().addBefore(ctx.name(), /* name= */ null, this.executor != null
          ? new SslHandler(sslEngine, false, this.executor)
          : new SslHandler(sslEngine, false));
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
      if (evt instanceof ProtocolNegotiationEvent) {
        pne = (ProtocolNegotiationEvent) evt;
      } else if (evt instanceof SslHandshakeCompletionEvent) {
        SslHandshakeCompletionEvent handshakeEvent = (SslHandshakeCompletionEvent) evt;
        if (!handshakeEvent.isSuccess()) {
          logSslEngineDetails(Level.FINE, ctx, "TLS negotiation failed for new client.", null);
          ctx.fireExceptionCaught(handshakeEvent.cause());
          return;
        }
        SslHandler sslHandler = ctx.pipeline().get(SslHandler.class);
        if (!sslContext.applicationProtocolNegotiator().protocols().contains(
                sslHandler.applicationProtocol())) {
          logSslEngineDetails(Level.FINE, ctx, "TLS negotiation failed for new client.", null);
          ctx.fireExceptionCaught(unavailableException(
              "Failed protocol negotiation: Unable to find compatible protocol"));
          return;
        }
        ctx.pipeline().replace(ctx.name(), null, next);
        fireProtocolNegotiationEvent(ctx, sslHandler.engine().getSession());
      } else {
        super.userEventTriggered(ctx, evt);
      }
    }

    private void fireProtocolNegotiationEvent(ChannelHandlerContext ctx, SSLSession session) {
      Security security = new Security(new Tls(session));
      Attributes attrs = pne.getAttributes().toBuilder()
          .set(GrpcAttributes.ATTR_SECURITY_LEVEL, SecurityLevel.PRIVACY_AND_INTEGRITY)
          .set(Grpc.TRANSPORT_ATTR_SSL_SESSION, session)
          .build();
      ctx.fireUserEventTriggered(pne.withAttributes(attrs).withSecurity(security));
    }
  }

  /**
   * Returns a {@link ProtocolNegotiator} that does HTTP CONNECT proxy negotiation.
   */
  public static ProtocolNegotiator httpProxy(final SocketAddress proxyAddress,
      final @Nullable String proxyUsername, final @Nullable String proxyPassword,
      final ProtocolNegotiator negotiator) {
    checkNotNull(negotiator, "negotiator");
    checkNotNull(proxyAddress, "proxyAddress");
    final AsciiString scheme = negotiator.scheme();
    class ProxyNegotiator implements ProtocolNegotiator {
      @Override
      public ChannelHandler newHandler(GrpcHttp2ConnectionHandler http2Handler) {
        ChannelHandler protocolNegotiationHandler = negotiator.newHandler(http2Handler);
        ChannelLogger negotiationLogger = http2Handler.getNegotiationLogger();
        return new ProxyProtocolNegotiationHandler(
            proxyAddress, proxyUsername, proxyPassword, protocolNegotiationHandler,
            negotiationLogger);
      }

      @Override
      public AsciiString scheme() {
        return scheme;
      }

      // This method is not normally called, because we use httpProxy on a per-connection basis in
      // NettyChannelBuilder. Instead, we expect `negotiator' to be closed by NettyTransportFactory.
      @Override
      public void close() {
        negotiator.close();
      }
    }

    return new ProxyNegotiator();
  }

  /**
   * A Proxy handler follows {@link ProtocolNegotiationHandler} pattern. Upon successful proxy
   * connection, this handler will install {@code next} handler which should be a handler from
   * other type of {@link ProtocolNegotiator} to continue negotiating protocol using proxy.
   */
  static final class ProxyProtocolNegotiationHandler extends ProtocolNegotiationHandler {

    private final SocketAddress address;
    @Nullable private final String userName;
    @Nullable private final String password;

    public ProxyProtocolNegotiationHandler(
        SocketAddress address,
        @Nullable String userName,
        @Nullable String password,
        ChannelHandler next,
        ChannelLogger negotiationLogger) {
      super(next, negotiationLogger);
      this.address = checkNotNull(address, "address");
      this.userName = userName;
      this.password = password;
    }

    @Override
    protected void protocolNegotiationEventTriggered(ChannelHandlerContext ctx) {
      HttpProxyHandler nettyProxyHandler;
      if (userName == null || password == null) {
        nettyProxyHandler = new HttpProxyHandler(address);
      } else {
        nettyProxyHandler = new HttpProxyHandler(address, userName, password);
      }
      ctx.pipeline().addBefore(ctx.name(), /* name= */ null, nettyProxyHandler);
    }

    @Override
    protected void userEventTriggered0(ChannelHandlerContext ctx, Object evt) throws Exception {
      if (evt instanceof ProxyConnectionEvent) {
        fireProtocolNegotiationEvent(ctx);
      } else {
        super.userEventTriggered(ctx, evt);
      }
    }
  }

  static final class ClientTlsProtocolNegotiator implements ProtocolNegotiator {

    public ClientTlsProtocolNegotiator(SslContext sslContext,
        ObjectPool<? extends Executor> executorPool, Optional<Runnable> handshakeCompleteRunnable) {
      this.sslContext = checkNotNull(sslContext, "sslContext");
      this.executorPool = executorPool;
      if (this.executorPool != null) {
        this.executor = this.executorPool.getObject();
      }
      this.handshakeCompleteRunnable = handshakeCompleteRunnable;
    }

    private final SslContext sslContext;
    private final ObjectPool<? extends Executor> executorPool;
    private final Optional<Runnable> handshakeCompleteRunnable;
    private Executor executor;

    @Override
    public AsciiString scheme() {
      return Utils.HTTPS;
    }

    @Override
    public ChannelHandler newHandler(GrpcHttp2ConnectionHandler grpcHandler) {
      ChannelHandler gnh = new GrpcNegotiationHandler(grpcHandler);
      ChannelLogger negotiationLogger = grpcHandler.getNegotiationLogger();
      ChannelHandler cth = new ClientTlsHandler(gnh, sslContext, grpcHandler.getAuthority(),
          this.executor, negotiationLogger, handshakeCompleteRunnable);
      return new WaitUntilActiveHandler(cth, negotiationLogger);
    }

    @Override
    public void close() {
      if (this.executorPool != null && this.executor != null) {
        this.executorPool.returnObject(this.executor);
      }
    }
  }

  static final class ClientTlsHandler extends ProtocolNegotiationHandler {

    private final SslContext sslContext;
    private final String host;
    private final int port;
    private Executor executor;
    private final Optional<Runnable> handshakeCompleteRunnable;

    ClientTlsHandler(ChannelHandler next, SslContext sslContext, String authority,
        Executor executor, ChannelLogger negotiationLogger,
        Optional<Runnable> handshakeCompleteRunnable) {
      super(next, negotiationLogger);
      this.sslContext = checkNotNull(sslContext, "sslContext");
      HostPort hostPort = parseAuthority(authority);
      this.host = hostPort.host;
      this.port = hostPort.port;
      this.executor = executor;
      this.handshakeCompleteRunnable = handshakeCompleteRunnable;
    }

    @Override
    protected void handlerAdded0(ChannelHandlerContext ctx) {
      SSLEngine sslEngine = sslContext.newEngine(ctx.alloc(), host, port);
      SSLParameters sslParams = sslEngine.getSSLParameters();
      sslParams.setEndpointIdentificationAlgorithm("HTTPS");
      sslEngine.setSSLParameters(sslParams);
      ctx.pipeline().addBefore(ctx.name(), /* name= */ null, this.executor != null
          ? new SslHandler(sslEngine, false, this.executor)
          : new SslHandler(sslEngine, false));
    }

    @Override
    protected void userEventTriggered0(ChannelHandlerContext ctx, Object evt) throws Exception {
      if (evt instanceof SslHandshakeCompletionEvent) {
        SslHandshakeCompletionEvent handshakeEvent = (SslHandshakeCompletionEvent) evt;
        if (handshakeEvent.isSuccess()) {
          SslHandler handler = ctx.pipeline().get(SslHandler.class);
          if (sslContext.applicationProtocolNegotiator().protocols()
              .contains(handler.applicationProtocol())) {
            // Successfully negotiated the protocol.
            logSslEngineDetails(Level.FINER, ctx, "TLS negotiation succeeded.", null);
            propagateTlsComplete(ctx, handler.engine().getSession());
          } else {
            Exception ex =
                unavailableException("Failed ALPN negotiation: Unable to find compatible protocol");
            logSslEngineDetails(Level.FINE, ctx, "TLS negotiation failed.", ex);
            if (handshakeCompleteRunnable.isPresent()) {
              handshakeCompleteRunnable.get().run();
            }
            ctx.fireExceptionCaught(ex);
          }
        } else {
          Throwable t = handshakeEvent.cause();
          if (t instanceof ClosedChannelException) {
            // On channelInactive(), SslHandler creates its own ClosedChannelException and
            // propagates it before the actual channelInactive(). So we assume here that any
            // such exception is from channelInactive() and emulate the normal behavior of
            // WriteBufferingAndExceptionHandler
            t = Status.UNAVAILABLE
                .withDescription("Connection closed while performing TLS negotiation")
                .withCause(t)
                .asRuntimeException();
          }
          if (handshakeCompleteRunnable.isPresent()) {
            handshakeCompleteRunnable.get().run();
          }
          ctx.fireExceptionCaught(t);
        }
      } else {
        super.userEventTriggered0(ctx, evt);
      }
    }

    private void propagateTlsComplete(ChannelHandlerContext ctx, SSLSession session) {
      Security security = new Security(new Tls(session));
      ProtocolNegotiationEvent existingPne = getProtocolNegotiationEvent();
      Attributes attrs = existingPne.getAttributes().toBuilder()
          .set(GrpcAttributes.ATTR_SECURITY_LEVEL, SecurityLevel.PRIVACY_AND_INTEGRITY)
          .set(Grpc.TRANSPORT_ATTR_SSL_SESSION, session)
          .build();
      replaceProtocolNegotiationEvent(existingPne.withAttributes(attrs).withSecurity(security));
      if (handshakeCompleteRunnable.isPresent()) {
        handshakeCompleteRunnable.get().run();
      }
      fireProtocolNegotiationEvent(ctx);
    }
  }

  @VisibleForTesting
  static HostPort parseAuthority(String authority) {
    URI uri = GrpcUtil.authorityToUri(Preconditions.checkNotNull(authority, "authority"));
    String host;
    int port;
    if (uri.getHost() != null) {
      host = uri.getHost();
      port = uri.getPort();
    } else {
      /*
       * Implementation note: We pick -1 as the port here rather than deriving it from the
       * original socket address.  The SSL engine doesn't use this port number when contacting the
       * remote server, but rather it is used for other things like SSL Session caching.  When an
       * invalid authority is provided (like "bad_cert"), picking the original port and passing it
       * in would mean that the port might used under the assumption that it was correct.   By
       * using -1 here, it forces the SSL implementation to treat it as invalid.
       */
      host = authority;
      port = -1;
    }
    return new HostPort(host, port);
  }

  /**
   * Returns a {@link ProtocolNegotiator} that ensures the pipeline is set up so that TLS will
   * be negotiated, the {@code handler} is added and writes to the {@link io.netty.channel.Channel}
   * may happen immediately, even before the TLS Handshake is complete.
   * @param executorPool a dedicated {@link Executor} pool for time-consuming TLS tasks
   */
  public static ProtocolNegotiator tls(SslContext sslContext,
      ObjectPool<? extends Executor> executorPool, Optional<Runnable> handshakeCompleteRunnable) {
    return new ClientTlsProtocolNegotiator(sslContext, executorPool, handshakeCompleteRunnable);
  }

  /**
   * Returns a {@link ProtocolNegotiator} that ensures the pipeline is set up so that TLS will
   * be negotiated, the {@code handler} is added and writes to the {@link io.netty.channel.Channel}
   * may happen immediately, even before the TLS Handshake is complete.
   */
  public static ProtocolNegotiator tls(SslContext sslContext) {
    return tls(sslContext, null, Optional.empty());
  }

  public static ProtocolNegotiator.ClientFactory tlsClientFactory(SslContext sslContext) {
    return new TlsProtocolNegotiatorClientFactory(sslContext);
  }

  @VisibleForTesting
  static final class TlsProtocolNegotiatorClientFactory
      implements ProtocolNegotiator.ClientFactory {
    private final SslContext sslContext;

    public TlsProtocolNegotiatorClientFactory(SslContext sslContext) {
      this.sslContext = Preconditions.checkNotNull(sslContext, "sslContext");
    }

    @Override public ProtocolNegotiator newNegotiator() {
      return tls(sslContext);
    }

    @Override public int getDefaultPort() {
      return GrpcUtil.DEFAULT_PORT_SSL;
    }
  }

  /** A tuple of (host, port). */
  @VisibleForTesting
  static final class HostPort {
    final String host;
    final int port;

    public HostPort(String host, int port) {
      this.host = host;
      this.port = port;
    }
  }

  /**
   * Returns a {@link ProtocolNegotiator} used for upgrading to HTTP/2 from HTTP/1.x.
   */
  public static ProtocolNegotiator plaintextUpgrade() {
    return new PlaintextUpgradeProtocolNegotiator();
  }

  public static ProtocolNegotiator.ClientFactory plaintextUpgradeClientFactory() {
    return new PlaintextUpgradeProtocolNegotiatorClientFactory();
  }

  private static final class PlaintextUpgradeProtocolNegotiatorClientFactory
      implements ProtocolNegotiator.ClientFactory {
    @Override public ProtocolNegotiator newNegotiator() {
      return plaintextUpgrade();
    }

    @Override public int getDefaultPort() {
      return GrpcUtil.DEFAULT_PORT_PLAINTEXT;
    }
  }

  static final class PlaintextUpgradeProtocolNegotiator implements ProtocolNegotiator {

    @Override
    public AsciiString scheme() {
      return Utils.HTTP;
    }

    @Override
    public ChannelHandler newHandler(GrpcHttp2ConnectionHandler grpcHandler) {
      ChannelHandler upgradeHandler =
          new Http2UpgradeAndGrpcHandler(grpcHandler.getAuthority(), grpcHandler);
      return new WaitUntilActiveHandler(upgradeHandler, grpcHandler.getNegotiationLogger());
    }

    @Override
    public void close() {}
  }

  /**
   * Acts as a combination of Http2Upgrade and {@link GrpcNegotiationHandler}.  Unfortunately,
   * this negotiator doesn't follow the pattern of "just one handler doing negotiation at a time."
   * This is due to the tight coupling between the upgrade handler and the HTTP/2 handler.
   */
  static final class Http2UpgradeAndGrpcHandler extends ChannelInboundHandlerAdapter {

    private final String authority;
    private final GrpcHttp2ConnectionHandler next;
    private final ChannelLogger negotiationLogger;

    private ProtocolNegotiationEvent pne;

    Http2UpgradeAndGrpcHandler(String authority, GrpcHttp2ConnectionHandler next) {
      this.authority = checkNotNull(authority, "authority");
      this.next = checkNotNull(next, "next");
      this.negotiationLogger = next.getNegotiationLogger();
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
      negotiationLogger.log(ChannelLogLevel.INFO, "Http2Upgrade started");
      HttpClientCodec httpClientCodec = new HttpClientCodec();
      ctx.pipeline().addBefore(ctx.name(), null, httpClientCodec);

      Http2ClientUpgradeCodec upgradeCodec = new Http2ClientUpgradeCodec(next);
      HttpClientUpgradeHandler upgrader =
          new HttpClientUpgradeHandler(httpClientCodec, upgradeCodec, /*maxContentLength=*/ 1000);
      ctx.pipeline().addBefore(ctx.name(), null, upgrader);

      // Trigger the HTTP/1.1 plaintext upgrade protocol by issuing an HTTP request
      // which causes the upgrade headers to be added
      DefaultHttpRequest upgradeTrigger =
          new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/");
      upgradeTrigger.headers().add(HttpHeaderNames.HOST, authority);
      ctx.writeAndFlush(upgradeTrigger).addListener(
          ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);
      super.handlerAdded(ctx);
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
      if (evt instanceof ProtocolNegotiationEvent) {
        checkState(pne == null, "negotiation already started");
        pne = (ProtocolNegotiationEvent) evt;
      } else if (evt == HttpClientUpgradeHandler.UpgradeEvent.UPGRADE_SUCCESSFUL) {
        checkState(pne != null, "negotiation not yet complete");
        negotiationLogger.log(ChannelLogLevel.INFO, "Http2Upgrade finished");
        ctx.pipeline().remove(ctx.name());
        next.handleProtocolNegotiationCompleted(pne.getAttributes(), pne.getSecurity());
      } else if (evt == HttpClientUpgradeHandler.UpgradeEvent.UPGRADE_REJECTED) {
        ctx.fireExceptionCaught(unavailableException("HTTP/2 upgrade rejected"));
      } else {
        super.userEventTriggered(ctx, evt);
      }
    }
  }

  /**
   * Returns a {@link ChannelHandler} that ensures that the {@code handler} is added to the
   * pipeline writes to the {@link io.netty.channel.Channel} may happen immediately, even before it
   * is active.
   */
  public static ProtocolNegotiator plaintext() {
    return new PlaintextProtocolNegotiator();
  }

  public static ProtocolNegotiator.ClientFactory plaintextClientFactory() {
    return new PlaintextProtocolNegotiatorClientFactory();
  }

  @VisibleForTesting
  static final class PlaintextProtocolNegotiatorClientFactory
      implements ProtocolNegotiator.ClientFactory {
    @Override public ProtocolNegotiator newNegotiator() {
      return plaintext();
    }

    @Override public int getDefaultPort() {
      return GrpcUtil.DEFAULT_PORT_PLAINTEXT;
    }
  }

  private static RuntimeException unavailableException(String msg) {
    return Status.UNAVAILABLE.withDescription(msg).asRuntimeException();
  }

  @VisibleForTesting
  static void logSslEngineDetails(Level level, ChannelHandlerContext ctx, String msg,
      @Nullable Throwable t) {
    if (!log.isLoggable(level)) {
      return;
    }

    SslHandler sslHandler = ctx.pipeline().get(SslHandler.class);
    SSLEngine engine = sslHandler.engine();

    StringBuilder builder = new StringBuilder(msg);
    builder.append("\nSSLEngine Details: [\n");
    if (engine instanceof OpenSslEngine) {
      builder.append("    OpenSSL, ");
      builder.append("Version: 0x").append(Integer.toHexString(OpenSsl.version()));
      builder.append(" (").append(OpenSsl.versionString()).append("), ");
      builder.append("ALPN supported: ").append(SslProvider.isAlpnSupported(SslProvider.OPENSSL));
    } else if (JettyTlsUtil.isJettyAlpnConfigured()) {
      builder.append("    Jetty ALPN");
    } else if (JettyTlsUtil.isJettyNpnConfigured()) {
      builder.append("    Jetty NPN");
    } else if (JettyTlsUtil.isJava9AlpnAvailable()) {
      builder.append("    JDK9 ALPN");
    }
    builder.append("\n    TLS Protocol: ");
    builder.append(engine.getSession().getProtocol());
    builder.append("\n    Application Protocol: ");
    builder.append(sslHandler.applicationProtocol());
    builder.append("\n    Need Client Auth: " );
    builder.append(engine.getNeedClientAuth());
    builder.append("\n    Want Client Auth: ");
    builder.append(engine.getWantClientAuth());
    builder.append("\n    Supported protocols=");
    builder.append(Arrays.toString(engine.getSupportedProtocols()));
    builder.append("\n    Enabled protocols=");
    builder.append(Arrays.toString(engine.getEnabledProtocols()));
    builder.append("\n    Supported ciphers=");
    builder.append(Arrays.toString(engine.getSupportedCipherSuites()));
    builder.append("\n    Enabled ciphers=");
    builder.append(Arrays.toString(engine.getEnabledCipherSuites()));
    builder.append("\n]");

    log.log(level, builder.toString(), t);
  }

  /**
   * Adapts a {@link ProtocolNegotiationEvent} to the {@link GrpcHttp2ConnectionHandler}.
   */
  static final class GrpcNegotiationHandler extends ChannelInboundHandlerAdapter {
    private final GrpcHttp2ConnectionHandler next;

    public GrpcNegotiationHandler(GrpcHttp2ConnectionHandler next) {
      this.next = checkNotNull(next, "next");
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
      if (evt instanceof ProtocolNegotiationEvent) {
        ProtocolNegotiationEvent protocolNegotiationEvent = (ProtocolNegotiationEvent) evt;
        ctx.pipeline().replace(ctx.name(), null, next);
        next.handleProtocolNegotiationCompleted(
            protocolNegotiationEvent.getAttributes(), protocolNegotiationEvent.getSecurity());
      } else {
        super.userEventTriggered(ctx, evt);
      }
    }
  }

  /*
   * Common {@link ProtocolNegotiator}s used by gRPC.  Protocol negotiation follows a pattern to
   * simplify the pipeline.   The pipeline should look like:
   *
   * 1.  {@link ProtocolNegotiator#newHandler() PN.H}, created.
   * 2.  [Tail], {@link WriteBufferingAndExceptionHandler WBAEH}, [Head]
   * 3.  [Tail], WBAEH, PN.H, [Head]
   *
   * <p>Typically, PN.H with be an instance of {@link InitHandler IH}, which is a trivial handler
   * that can return the {@code scheme()} of the negotiation.  IH, and each handler after,
   * replaces itself with a "next" handler once its part of negotiation is complete.  This keeps
   * the pipeline small, and limits the interaction between handlers.
   *
   * <p>Additionally, each handler may fire a {@link ProtocolNegotiationEvent PNE} just after
   * replacing itself.  Handlers should capture user events of type PNE, and re-trigger the events
   * once that handler's part of negotiation is complete.  This can be seen in the
   * {@link WaitUntilActiveHandler WUAH}, which waits until the channel is active.  Once active, it
   * replaces itself with the next handler, and fires a PNE containing the addresses.  Continuing
   * with IH and WUAH:
   *
   * 3.  [Tail], WBAEH, IH, [Head]
   * 4.  [Tail], WBAEH, WUAH, [Head]
   * 5.  [Tail], WBAEH, {@link GrpcNegotiationHandler}, [Head]
   * 6a. [Tail], WBAEH, {@link GrpcHttp2ConnectionHandler GHCH}, [Head]
   * 6b. [Tail], GHCH, [Head]
   */

  /**
   * A negotiator that only does plain text.
   */
  static final class PlaintextProtocolNegotiator implements ProtocolNegotiator {

    @Override
    public ChannelHandler newHandler(GrpcHttp2ConnectionHandler grpcHandler) {
      ChannelHandler grpcNegotiationHandler = new GrpcNegotiationHandler(grpcHandler);
      ChannelHandler activeHandler = new WaitUntilActiveHandler(grpcNegotiationHandler,
          grpcHandler.getNegotiationLogger());
      return activeHandler;
    }

    @Override
    public void close() {}

    @Override
    public AsciiString scheme() {
      return Utils.HTTP;
    }
  }

  /**
   * Waits for the channel to be active, and then installs the next Handler.  Using this allows
   * subsequent handlers to assume the channel is active and ready to send.  Additionally, this a
   * {@link ProtocolNegotiationEvent}, with the connection addresses.
   */
  static final class WaitUntilActiveHandler extends ProtocolNegotiationHandler {

    boolean protocolNegotiationEventReceived;

    WaitUntilActiveHandler(ChannelHandler next, ChannelLogger negotiationLogger) {
      super(next, negotiationLogger);
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
      if (protocolNegotiationEventReceived) {
        replaceOnActive(ctx);
        fireProtocolNegotiationEvent(ctx);
      }
      // Still propagate channelActive to the new handler.
      super.channelActive(ctx);
    }

    @Override
    protected void protocolNegotiationEventTriggered(ChannelHandlerContext ctx) {
      protocolNegotiationEventReceived = true;
      if (ctx.channel().isActive()) {
        replaceOnActive(ctx);
        fireProtocolNegotiationEvent(ctx);
      }
    }

    private void replaceOnActive(ChannelHandlerContext ctx) {
      ProtocolNegotiationEvent existingPne = getProtocolNegotiationEvent();
      Attributes attrs = existingPne.getAttributes().toBuilder()
          .set(Grpc.TRANSPORT_ATTR_LOCAL_ADDR, ctx.channel().localAddress())
          .set(Grpc.TRANSPORT_ATTR_REMOTE_ADDR, ctx.channel().remoteAddress())
          // Later handlers are expected to overwrite this.
          .set(GrpcAttributes.ATTR_SECURITY_LEVEL, SecurityLevel.NONE)
          .build();
      replaceProtocolNegotiationEvent(existingPne.withAttributes(attrs));
    }
  }

  /**
   * ProtocolNegotiationHandler is a convenience handler that makes it easy to follow the rules for
   * protocol negotiation.  Handlers should strongly consider extending this handler.
   */
  static class ProtocolNegotiationHandler extends ChannelDuplexHandler {

    private final ChannelHandler next;
    private final String negotiatorName;
    private ProtocolNegotiationEvent pne;
    private final ChannelLogger negotiationLogger;

    protected ProtocolNegotiationHandler(ChannelHandler next, String negotiatorName,
        ChannelLogger negotiationLogger) {
      this.next = checkNotNull(next, "next");
      this.negotiatorName = negotiatorName;
      this.negotiationLogger = checkNotNull(negotiationLogger, "negotiationLogger");
    }

    protected ProtocolNegotiationHandler(ChannelHandler next, ChannelLogger negotiationLogger) {
      this.next = checkNotNull(next, "next");
      this.negotiatorName = getClass().getSimpleName().replace("Handler", "");
      this.negotiationLogger = checkNotNull(negotiationLogger, "negotiationLogger");
    }

    @Override
    public final void handlerAdded(ChannelHandlerContext ctx) throws Exception {
      negotiationLogger.log(ChannelLogLevel.DEBUG, "{0} started", negotiatorName);
      handlerAdded0(ctx);
    }

    @ForOverride
    protected void handlerAdded0(ChannelHandlerContext ctx) throws Exception {
      super.handlerAdded(ctx);
    }

    @Override
    public final void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
      if (evt instanceof ProtocolNegotiationEvent) {
        checkState(pne == null, "pre-existing negotiation: %s < %s", pne, evt);
        pne = (ProtocolNegotiationEvent) evt;
        protocolNegotiationEventTriggered(ctx);
      } else {
        userEventTriggered0(ctx, evt);
      }
    }

    protected void userEventTriggered0(ChannelHandlerContext ctx, Object evt) throws Exception {
      super.userEventTriggered(ctx, evt);
    }

    @ForOverride
    protected void protocolNegotiationEventTriggered(ChannelHandlerContext ctx) {
      // no-op
    }

    protected final ProtocolNegotiationEvent getProtocolNegotiationEvent() {
      checkState(pne != null, "previous protocol negotiation event hasn't triggered");
      return pne;
    }

    protected final void replaceProtocolNegotiationEvent(ProtocolNegotiationEvent pne) {
      checkState(this.pne != null, "previous protocol negotiation event hasn't triggered");
      this.pne = checkNotNull(pne);
    }

    protected final void fireProtocolNegotiationEvent(ChannelHandlerContext ctx) {
      checkState(pne != null, "previous protocol negotiation event hasn't triggered");
      negotiationLogger.log(ChannelLogLevel.INFO, "{0} completed", negotiatorName);
      ctx.pipeline().replace(ctx.name(), /* newName= */ null, next);
      ctx.fireUserEventTriggered(pne);
    }
  }
}
