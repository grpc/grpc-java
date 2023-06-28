/*
 * Copyright 2019 The gRPC Authors
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

package io.grpc.xds.internal.security;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.annotations.VisibleForTesting;
import io.grpc.Attributes;
import io.grpc.internal.GrpcUtil;
import io.grpc.internal.ObjectPool;
import io.grpc.netty.GrpcHttp2ConnectionHandler;
import io.grpc.netty.InternalProtocolNegotiationEvent;
import io.grpc.netty.InternalProtocolNegotiator;
import io.grpc.netty.InternalProtocolNegotiator.ProtocolNegotiator;
import io.grpc.netty.InternalProtocolNegotiators;
import io.grpc.netty.ProtocolNegotiationEvent;
import io.grpc.xds.InternalXdsAttributes;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerAdapter;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.ssl.SslContext;
import io.netty.util.AsciiString;
import java.security.cert.CertStoreException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;

/**
 * Provides client and server side gRPC {@link ProtocolNegotiator}s to provide the SSL
 * context.
 */
@VisibleForTesting
public final class SecurityProtocolNegotiators {

  // Prevent instantiation.
  private SecurityProtocolNegotiators() {
  }

  private static final Logger logger
      = Logger.getLogger(SecurityProtocolNegotiators.class.getName());

  private static final AsciiString SCHEME = AsciiString.of("http");

  public static final Attributes.Key<SslContextProviderSupplier>
          ATTR_SERVER_SSL_CONTEXT_PROVIDER_SUPPLIER =
          Attributes.Key.create("io.grpc.xds.internal.security.server.sslContextProviderSupplier");

  /**
   * Returns a {@link InternalProtocolNegotiator.ClientFactory}.
   *
   * @param fallbackNegotiator protocol negotiator to use as fallback.
   */
  public static InternalProtocolNegotiator.ClientFactory clientProtocolNegotiatorFactory(
      @Nullable InternalProtocolNegotiator.ClientFactory fallbackNegotiator) {
    return new ClientFactory(fallbackNegotiator);
  }

  public static InternalProtocolNegotiator.ServerFactory serverProtocolNegotiatorFactory(
      @Nullable InternalProtocolNegotiator.ServerFactory fallbackNegotiator) {
    return new ServerFactory(fallbackNegotiator);
  }

  private static final class ServerFactory implements InternalProtocolNegotiator.ServerFactory {

    private final InternalProtocolNegotiator.ServerFactory fallbackProtocolNegotiator;

    private ServerFactory(InternalProtocolNegotiator.ServerFactory fallbackNegotiator) {
      this.fallbackProtocolNegotiator = fallbackNegotiator;
    }

    @Override
    public ProtocolNegotiator newNegotiator(ObjectPool<? extends Executor> offloadExecutorPool) {
      return new ServerSecurityProtocolNegotiator(
          fallbackProtocolNegotiator.newNegotiator(offloadExecutorPool));
    }
  }

  private static final class ClientFactory implements InternalProtocolNegotiator.ClientFactory {

    private final InternalProtocolNegotiator.ClientFactory fallbackProtocolNegotiator;

    private ClientFactory(InternalProtocolNegotiator.ClientFactory fallbackNegotiator) {
      this.fallbackProtocolNegotiator = fallbackNegotiator;
    }

    @Override
    public ProtocolNegotiator newNegotiator() {
      return new ClientSecurityProtocolNegotiator(fallbackProtocolNegotiator.newNegotiator());
    }

    @Override
    public int getDefaultPort() {
      return GrpcUtil.DEFAULT_PORT_SSL;
    }
  }

  @VisibleForTesting
  static final class ClientSecurityProtocolNegotiator implements ProtocolNegotiator {

    @Nullable private final ProtocolNegotiator fallbackProtocolNegotiator;

    ClientSecurityProtocolNegotiator(@Nullable ProtocolNegotiator fallbackProtocolNegotiator) {
      this.fallbackProtocolNegotiator = fallbackProtocolNegotiator;
    }

    @Override
    public AsciiString scheme() {
      return SCHEME;
    }

    @Override
    public ChannelHandler newHandler(GrpcHttp2ConnectionHandler grpcHandler) {
      // check if SslContextProviderSupplier was passed via attributes
      SslContextProviderSupplier localSslContextProviderSupplier =
          grpcHandler.getEagAttributes().get(
              InternalXdsAttributes.ATTR_SSL_CONTEXT_PROVIDER_SUPPLIER);
      if (localSslContextProviderSupplier == null) {
        checkNotNull(
            fallbackProtocolNegotiator, "No TLS config and no fallbackProtocolNegotiator!");
        return fallbackProtocolNegotiator.newHandler(grpcHandler);
      }
      return new ClientSecurityHandler(grpcHandler, localSslContextProviderSupplier);
    }

    @Override
    public void close() {}
  }

  private static class BufferReadsHandler extends ChannelInboundHandlerAdapter {
    private final List<Object> reads = new ArrayList<>();
    private boolean readComplete;

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
      reads.add(msg);
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) {
      readComplete = true;
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
      for (Object msg : reads) {
        super.channelRead(ctx, msg);
      }
      if (readComplete) {
        super.channelReadComplete(ctx);
      }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
      logger.log(Level.SEVERE, "exceptionCaught", cause);
      ctx.fireExceptionCaught(cause);
    }
  }

  @VisibleForTesting
  static final class ClientSecurityHandler
      extends InternalProtocolNegotiators.ProtocolNegotiationHandler {
    private final GrpcHttp2ConnectionHandler grpcHandler;
    private final SslContextProviderSupplier sslContextProviderSupplier;

    ClientSecurityHandler(
        GrpcHttp2ConnectionHandler grpcHandler,
        SslContextProviderSupplier sslContextProviderSupplier) {
      super(
          // superclass (InternalProtocolNegotiators.ProtocolNegotiationHandler) expects 'next'
          // handler but we don't have a next handler _yet_. So we "disable" superclass's behavior
          // here and then manually add 'next' when we call fireProtocolNegotiationEvent()
          new ChannelHandlerAdapter() {
            @Override
            public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
              ctx.pipeline().remove(this);
            }
          }, grpcHandler.getNegotiationLogger());
      checkNotNull(grpcHandler, "grpcHandler");
      this.grpcHandler = grpcHandler;
      this.sslContextProviderSupplier = sslContextProviderSupplier;
    }

    @Override
    protected void handlerAdded0(final ChannelHandlerContext ctx) {
      final BufferReadsHandler bufferReads = new BufferReadsHandler();
      ctx.pipeline().addBefore(ctx.name(), null, bufferReads);

      sslContextProviderSupplier.updateSslContext(
          new SslContextProvider.Callback(ctx.executor()) {

            @Override
            public void updateSslContext(SslContext sslContext) {
              if (ctx.isRemoved()) {
                return;
              }
              logger.log(
                  Level.FINEST,
                  "ClientSecurityHandler.updateSslContext authority={0}, ctx.name={1}",
                  new Object[]{grpcHandler.getAuthority(), ctx.name()});
              ChannelHandler handler =
                  InternalProtocolNegotiators.tls(sslContext).newHandler(grpcHandler);

              // Delegate rest of handshake to TLS handler
              ctx.pipeline().addAfter(ctx.name(), null, handler);
              fireProtocolNegotiationEvent(ctx);
              ctx.pipeline().remove(bufferReads);
            }

            @Override
            public void onException(Throwable throwable) {
              ctx.fireExceptionCaught(throwable);
            }
          }
      );
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause)
        throws Exception {
      logger.log(Level.SEVERE, "exceptionCaught", cause);
      ctx.fireExceptionCaught(cause);
    }
  }

  private static final class ServerSecurityProtocolNegotiator implements ProtocolNegotiator {

    @Nullable private final ProtocolNegotiator fallbackProtocolNegotiator;

    /** Constructor. */
    @VisibleForTesting
    public ServerSecurityProtocolNegotiator(
        @Nullable ProtocolNegotiator fallbackProtocolNegotiator) {
      this.fallbackProtocolNegotiator = fallbackProtocolNegotiator;
    }

    @Override
    public AsciiString scheme() {
      return SCHEME;
    }

    @Override
    public ChannelHandler newHandler(GrpcHttp2ConnectionHandler grpcHandler) {
      return new HandlerPickerHandler(grpcHandler, fallbackProtocolNegotiator);
    }

    @Override
    public void close() {}
  }

  @VisibleForTesting
  static final class HandlerPickerHandler
      extends ChannelInboundHandlerAdapter {
    private final GrpcHttp2ConnectionHandler grpcHandler;
    @Nullable private final ProtocolNegotiator fallbackProtocolNegotiator;

    HandlerPickerHandler(
        GrpcHttp2ConnectionHandler grpcHandler,
        @Nullable ProtocolNegotiator fallbackProtocolNegotiator) {
      this.grpcHandler = checkNotNull(grpcHandler, "grpcHandler");
      this.fallbackProtocolNegotiator = fallbackProtocolNegotiator;
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
      if (evt instanceof ProtocolNegotiationEvent) {
        ProtocolNegotiationEvent pne = (ProtocolNegotiationEvent)evt;
        SslContextProviderSupplier sslContextProviderSupplier = InternalProtocolNegotiationEvent
                .getAttributes(pne).get(ATTR_SERVER_SSL_CONTEXT_PROVIDER_SUPPLIER);
        if (sslContextProviderSupplier == null) {
          logger.log(Level.FINE, "No sslContextProviderSupplier found in filterChainMatch "
              + "for connection from {0} to {1}",
              new Object[]{ctx.channel().remoteAddress(), ctx.channel().localAddress()});
          if (fallbackProtocolNegotiator == null) {
            ctx.fireExceptionCaught(new CertStoreException("No certificate source found!"));
            return;
          }
          logger.log(Level.FINE, "Using fallback credentials for connection from {0} to {1}",
              new Object[]{ctx.channel().remoteAddress(), ctx.channel().localAddress()});
          ctx.pipeline()
              .replace(
                  this,
                  null,
                  fallbackProtocolNegotiator.newHandler(grpcHandler));
          ctx.fireUserEventTriggered(pne);
          return;
        } else {
          ctx.pipeline()
              .replace(
                  this,
                  null,
                  new ServerSecurityHandler(
                      grpcHandler, sslContextProviderSupplier));
          ctx.fireUserEventTriggered(pne);
          return;
        }
      } else {
        super.userEventTriggered(ctx, evt);
      }
    }
  }

  @VisibleForTesting
  static final class ServerSecurityHandler
          extends InternalProtocolNegotiators.ProtocolNegotiationHandler {
    private final GrpcHttp2ConnectionHandler grpcHandler;
    private final SslContextProviderSupplier sslContextProviderSupplier;

    ServerSecurityHandler(
            GrpcHttp2ConnectionHandler grpcHandler,
            SslContextProviderSupplier sslContextProviderSupplier) {
      super(
          // superclass (InternalProtocolNegotiators.ProtocolNegotiationHandler) expects 'next'
          // handler but we don't have a next handler _yet_. So we "disable" superclass's behavior
          // here and then manually add 'next' when we call fireProtocolNegotiationEvent()
          new ChannelHandlerAdapter() {
            @Override
            public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
              ctx.pipeline().remove(this);
            }
          }, grpcHandler.getNegotiationLogger());
      checkNotNull(grpcHandler, "grpcHandler");
      this.grpcHandler = grpcHandler;
      this.sslContextProviderSupplier = sslContextProviderSupplier;
    }

    @Override
    protected void handlerAdded0(final ChannelHandlerContext ctx) {
      final BufferReadsHandler bufferReads = new BufferReadsHandler();
      ctx.pipeline().addBefore(ctx.name(), null, bufferReads);

      sslContextProviderSupplier.updateSslContext(
          new SslContextProvider.Callback(ctx.executor()) {

            @Override
            public void updateSslContext(SslContext sslContext) {
              ChannelHandler handler =
                  InternalProtocolNegotiators.serverTls(sslContext).newHandler(grpcHandler);

              // Delegate rest of handshake to TLS handler
              if (!ctx.isRemoved()) {
                ctx.pipeline().addAfter(ctx.name(), null, handler);
                fireProtocolNegotiationEvent(ctx);
                ctx.pipeline().remove(bufferReads);
              }
            }

            @Override
            public void onException(Throwable throwable) {
              ctx.fireExceptionCaught(throwable);
            }
          }
      );
    }
  }
}
