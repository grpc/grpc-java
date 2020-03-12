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

package io.grpc.xds.internal.sds;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.annotations.VisibleForTesting;
import io.envoyproxy.envoy.api.v2.auth.DownstreamTlsContext;
import io.envoyproxy.envoy.api.v2.auth.UpstreamTlsContext;
import io.grpc.netty.GrpcHttp2ConnectionHandler;
import io.grpc.netty.InternalNettyChannelBuilder;
import io.grpc.netty.InternalNettyChannelBuilder.ProtocolNegotiatorFactory;
import io.grpc.netty.InternalProtocolNegotiator;
import io.grpc.netty.InternalProtocolNegotiator.ProtocolNegotiator;
import io.grpc.netty.InternalProtocolNegotiators;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.xds.XdsAttributes;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerAdapter;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.ssl.SslContext;
import io.netty.util.AsciiString;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;

/**
 * Provides client and server side gRPC {@link ProtocolNegotiator}s that use SDS to provide the SSL
 * context.
 */
final class SdsProtocolNegotiators {

  private static final Logger logger = Logger.getLogger(SdsProtocolNegotiators.class.getName());

  private static final AsciiString SCHEME = AsciiString.of("https");

  /**
   * Returns a {@link ProtocolNegotiatorFactory} to be used on {@link NettyChannelBuilder}. Passing
   * {@code null} for upstreamTlsContext will fall back to plaintext.
   */
  // TODO (sanjaypujare) integrate with xDS client to get upstreamTlsContext from CDS
  public static ProtocolNegotiatorFactory clientProtocolNegotiatorFactory(
      @Nullable UpstreamTlsContext upstreamTlsContext) {
    return new ClientSdsProtocolNegotiatorFactory(upstreamTlsContext);
  }

  /**
   * Creates an SDS based {@link ProtocolNegotiator} for a {@link io.grpc.netty.NettyServerBuilder}.
   * Passing {@code null} for downstreamTlsContext will fall back to plaintext.
   */
  // TODO (sanjaypujare) integrate with xDS client to get LDS
  public static ProtocolNegotiator serverProtocolNegotiator(
      @Nullable DownstreamTlsContext downstreamTlsContext) {
    return new ServerSdsProtocolNegotiator(downstreamTlsContext);
  }

  private static final class ClientSdsProtocolNegotiatorFactory
      implements InternalNettyChannelBuilder.ProtocolNegotiatorFactory {

    // TODO (sanjaypujare) integrate with xDS client to get upstreamTlsContext from CDS
    private final UpstreamTlsContext upstreamTlsContext;

    ClientSdsProtocolNegotiatorFactory(UpstreamTlsContext upstreamTlsContext) {
      this.upstreamTlsContext = upstreamTlsContext;
    }

    @Override
    public InternalProtocolNegotiator.ProtocolNegotiator buildProtocolNegotiator() {
      final ClientSdsProtocolNegotiator negotiator =
          new ClientSdsProtocolNegotiator(upstreamTlsContext);
      final class LocalSdsNegotiator implements InternalProtocolNegotiator.ProtocolNegotiator {

        @Override
        public AsciiString scheme() {
          return negotiator.scheme();
        }

        @Override
        public ChannelHandler newHandler(GrpcHttp2ConnectionHandler grpcHandler) {
          return negotiator.newHandler(grpcHandler);
        }

        @Override
        public void close() {
          negotiator.close();
        }
      }

      return new LocalSdsNegotiator();
    }
  }

  @VisibleForTesting
  static final class ClientSdsProtocolNegotiator implements ProtocolNegotiator {

    // TODO (sanjaypujare) remove once we get this from CDS & don't need for testing
    UpstreamTlsContext upstreamTlsContext;

    ClientSdsProtocolNegotiator(UpstreamTlsContext upstreamTlsContext) {
      this.upstreamTlsContext = upstreamTlsContext;
    }

    @Override
    public AsciiString scheme() {
      return SCHEME;
    }

    @Override
    public ChannelHandler newHandler(GrpcHttp2ConnectionHandler grpcHandler) {
      // check if UpstreamTlsContext was passed via attributes
      UpstreamTlsContext localUpstreamTlsContext =
          grpcHandler.getEagAttributes().get(XdsAttributes.ATTR_UPSTREAM_TLS_CONTEXT);
      if (localUpstreamTlsContext == null) {
        localUpstreamTlsContext = upstreamTlsContext;
      }
      if (isTlsContextEmpty(localUpstreamTlsContext)) {
        return InternalProtocolNegotiators.plaintext().newHandler(grpcHandler);
      }
      return new ClientSdsHandler(grpcHandler, localUpstreamTlsContext);
    }

    private static boolean isTlsContextEmpty(UpstreamTlsContext upstreamTlsContext) {
      return upstreamTlsContext == null || !upstreamTlsContext.hasCommonTlsContext();
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
  static final class ClientSdsHandler
      extends InternalProtocolNegotiators.ProtocolNegotiationHandler {
    private final GrpcHttp2ConnectionHandler grpcHandler;
    private final UpstreamTlsContext upstreamTlsContext;

    ClientSdsHandler(
        GrpcHttp2ConnectionHandler grpcHandler, UpstreamTlsContext upstreamTlsContext) {
      super(
          // superclass (InternalProtocolNegotiators.ProtocolNegotiationHandler) expects 'next'
          // handler but we don't have a next handler _yet_. So we "disable" superclass's behavior
          // here and then manually add 'next' when we call fireProtocolNegotiationEvent()
          new ChannelHandlerAdapter() {
            @Override
            public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
              ctx.pipeline().remove(this);
            }
          });
      checkNotNull(grpcHandler, "grpcHandler");
      this.grpcHandler = grpcHandler;
      this.upstreamTlsContext = upstreamTlsContext;
    }

    @Override
    protected void handlerAdded0(final ChannelHandlerContext ctx) {
      final BufferReadsHandler bufferReads = new BufferReadsHandler();
      ctx.pipeline().addBefore(ctx.name(), null, bufferReads);

      final SslContextProvider<UpstreamTlsContext> sslContextProvider =
          TlsContextManagerImpl.getInstance()
              .findOrCreateClientSslContextProvider(upstreamTlsContext);

      sslContextProvider.addCallback(
          new SslContextProvider.Callback() {

            @Override
            public void updateSecret(SslContext sslContext) {
              logger.log(
                  Level.FINEST,
                  "ClientSdsHandler.updateSecret authority={0}, ctx.name={1}",
                  new Object[]{grpcHandler.getAuthority(), ctx.name()});
              ChannelHandler handler =
                  InternalProtocolNegotiators.tls(sslContext).newHandler(grpcHandler);

              // Delegate rest of handshake to TLS handler
              ctx.pipeline().addAfter(ctx.name(), null, handler);
              fireProtocolNegotiationEvent(ctx);
              ctx.pipeline().remove(bufferReads);
              TlsContextManagerImpl.getInstance()
                  .releaseClientSslContextProvider(sslContextProvider);
            }

            @Override
            public void onException(Throwable throwable) {
              ctx.fireExceptionCaught(throwable);
            }
          },
          ctx.executor());
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause)
        throws Exception {
      logger.log(Level.SEVERE, "exceptionCaught", cause);
      ctx.fireExceptionCaught(cause);
    }
  }

  private static final class ServerSdsProtocolNegotiator implements ProtocolNegotiator {

    // TODO (sanjaypujare) integrate with xDS client to get LDS. LDS watcher will
    // inject/update the downstreamTlsContext from LDS
    private DownstreamTlsContext downstreamTlsContext;

    ServerSdsProtocolNegotiator(DownstreamTlsContext downstreamTlsContext) {
      this.downstreamTlsContext = downstreamTlsContext;
    }

    @Override
    public AsciiString scheme() {
      return SCHEME;
    }

    @Override
    public ChannelHandler newHandler(GrpcHttp2ConnectionHandler grpcHandler) {
      if (isTlsContextEmpty(downstreamTlsContext)) {
        return InternalProtocolNegotiators.serverPlaintext().newHandler(grpcHandler);
      }
      return new ServerSdsHandler(grpcHandler, downstreamTlsContext);
    }

    private static boolean isTlsContextEmpty(DownstreamTlsContext downstreamTlsContext) {
      return downstreamTlsContext == null || !downstreamTlsContext.hasCommonTlsContext();
    }

    @Override
    public void close() {}
  }

  @VisibleForTesting
  static final class ServerSdsHandler
      extends InternalProtocolNegotiators.ProtocolNegotiationHandler {
    private final GrpcHttp2ConnectionHandler grpcHandler;
    private final DownstreamTlsContext downstreamTlsContext;

    ServerSdsHandler(
        GrpcHttp2ConnectionHandler grpcHandler, DownstreamTlsContext downstreamTlsContext) {
      super(
          // superclass (InternalProtocolNegotiators.ProtocolNegotiationHandler) expects 'next'
          // handler but we don't have a next handler _yet_. So we "disable" superclass's behavior
          // here and then manually add 'next' when we call fireProtocolNegotiationEvent()
          new ChannelHandlerAdapter() {
            @Override
            public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
              ctx.pipeline().remove(this);
            }
          });
      checkNotNull(grpcHandler, "grpcHandler");
      this.grpcHandler = grpcHandler;
      this.downstreamTlsContext = downstreamTlsContext;
    }

    @Override
    protected void handlerAdded0(final ChannelHandlerContext ctx) {
      final BufferReadsHandler bufferReads = new BufferReadsHandler();
      ctx.pipeline().addBefore(ctx.name(), null, bufferReads);

      final SslContextProvider<DownstreamTlsContext> sslContextProvider =
          TlsContextManagerImpl.getInstance()
              .findOrCreateServerSslContextProvider(downstreamTlsContext);

      sslContextProvider.addCallback(
          new SslContextProvider.Callback() {

            @Override
            public void updateSecret(SslContext sslContext) {
              ChannelHandler handler =
                  InternalProtocolNegotiators.serverTls(sslContext).newHandler(grpcHandler);

              // Delegate rest of handshake to TLS handler
              ctx.pipeline().addAfter(ctx.name(), null, handler);
              fireProtocolNegotiationEvent(ctx);
              ctx.pipeline().remove(bufferReads);
              TlsContextManagerImpl.getInstance()
                  .releaseServerSslContextProvider(sslContextProvider);
            }

            @Override
            public void onException(Throwable throwable) {
              ctx.fireExceptionCaught(throwable);
            }
          },
          ctx.executor());
    }
  }
}
