/*
 * Copyright 2024 The gRPC Authors
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

package io.grpc.s2a.internal.handshaker;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Strings.isNullOrEmpty;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.net.HostAndPort;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.errorprone.annotations.ThreadSafe;
import io.grpc.Channel;
import io.grpc.internal.GrpcUtil;
import io.grpc.internal.ObjectPool;
import io.grpc.internal.SharedResourcePool;
import io.grpc.netty.GrpcHttp2ConnectionHandler;
import io.grpc.netty.InternalProtocolNegotiator;
import io.grpc.netty.InternalProtocolNegotiator.ProtocolNegotiator;
import io.grpc.netty.InternalProtocolNegotiators;
import io.grpc.netty.InternalProtocolNegotiators.ProtocolNegotiationHandler;
import io.grpc.s2a.internal.handshaker.S2AIdentity;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerAdapter;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.ssl.SslContext;
import io.netty.util.AsciiString;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import javax.annotation.Nullable;

/** Factory for performing negotiation of a secure channel using the S2A. */
@ThreadSafe
public final class S2AProtocolNegotiatorFactory {
  @VisibleForTesting static final int DEFAULT_PORT = 443;
  private static final AsciiString SCHEME = AsciiString.of("https");

  /**
   * Creates a {@code S2AProtocolNegotiatorFactory} configured for a client to establish secure
   * connections using the S2A.
   *
   * @param localIdentity the identity of the client; if none is provided, the S2A will use the
   *     client's default identity.
   * @param s2aChannelPool a pool of shared channels that can be used to connect to the S2A.
   * @param stub the stub to use to communicate with S2A. If none is provided the channelPool
   *     will be used to create the stub. This is exposed for verifying the stream to S2A gets
   *     closed in tests.
   * @return a factory for creating a client-side protocol negotiator.
   */
  public static InternalProtocolNegotiator.ClientFactory createClientFactory(
      @Nullable S2AIdentity localIdentity, ObjectPool<Channel> s2aChannelPool,
      @Nullable S2AStub stub) {
    checkNotNull(s2aChannelPool, "S2A channel pool should not be null.");
    return new S2AClientProtocolNegotiatorFactory(localIdentity, s2aChannelPool, stub);
  }

  static final class S2AClientProtocolNegotiatorFactory
      implements InternalProtocolNegotiator.ClientFactory {
    private final @Nullable S2AIdentity localIdentity;
    private final ObjectPool<Channel> channelPool;
    private final @Nullable S2AStub stub;

    S2AClientProtocolNegotiatorFactory(
        @Nullable S2AIdentity localIdentity, ObjectPool<Channel> channelPool,
        @Nullable S2AStub stub) {
      this.localIdentity = localIdentity;
      this.channelPool = channelPool;
      this.stub = stub;
    }

    @Override
    public ProtocolNegotiator newNegotiator() {
      return S2AProtocolNegotiator.createForClient(channelPool, localIdentity, stub);
    }

    @Override
    public int getDefaultPort() {
      return DEFAULT_PORT;
    }
  }

  /** Negotiates the TLS handshake using S2A. */
  @VisibleForTesting
  static final class S2AProtocolNegotiator implements ProtocolNegotiator {

    private final ObjectPool<Channel> channelPool;
    private @Nullable Channel channel = null;
    private final Optional<S2AIdentity> localIdentity;
    private final @Nullable S2AStub stub;
    private final ListeningExecutorService service =
        MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(1));

    static S2AProtocolNegotiator createForClient(
        ObjectPool<Channel> channelPool, @Nullable S2AIdentity localIdentity,
        @Nullable S2AStub stub) {
      checkNotNull(channelPool, "Channel pool should not be null.");
      if (localIdentity == null) {
        return new S2AProtocolNegotiator(channelPool, Optional.empty(), stub);
      } else {
        return new S2AProtocolNegotiator(channelPool, Optional.of(localIdentity), stub);
      }
    }

    @VisibleForTesting
    static @Nullable String getHostNameFromAuthority(@Nullable String authority) {
      if (authority == null) {
        return null;
      }
      return HostAndPort.fromString(authority).getHost();
    }

    private S2AProtocolNegotiator(ObjectPool<Channel> channelPool,
        Optional<S2AIdentity> localIdentity, @Nullable S2AStub stub) {
      this.channelPool = channelPool;
      this.localIdentity = localIdentity;
      this.stub = stub;
      if (this.stub == null) {
        this.channel = channelPool.getObject();
      }
    }

    @Override
    public AsciiString scheme() {
      return SCHEME;
    }

    @Override
    public ChannelHandler newHandler(GrpcHttp2ConnectionHandler grpcHandler) {
      checkNotNull(grpcHandler, "grpcHandler should not be null.");
      String hostname = getHostNameFromAuthority(grpcHandler.getAuthority());
      checkArgument(!isNullOrEmpty(hostname), "hostname should not be null or empty.");
      return new S2AProtocolNegotiationHandler(
        grpcHandler, channel, localIdentity, hostname, service, stub);
    }

    @Override
    public void close() {
      service.shutdown();
      if (channel != null) {
        channelPool.returnObject(channel);
      }
    }
  }

  @VisibleForTesting
  static class BufferReadsHandler extends ChannelInboundHandlerAdapter {
    private final List<Object> reads = new ArrayList<>();
    private boolean readComplete;

    public List<Object> getReads() {
      return reads;
    }

    @Override
    public void channelRead(ChannelHandlerContext unused, Object msg) {
      reads.add(msg);
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext unused) {
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
  }

  private static final class S2AProtocolNegotiationHandler extends ProtocolNegotiationHandler {
    private final @Nullable Channel channel;
    private final Optional<S2AIdentity> localIdentity;
    private final String hostname;
    private final GrpcHttp2ConnectionHandler grpcHandler;
    private final ListeningExecutorService service;
    private final @Nullable S2AStub stub;

    private S2AProtocolNegotiationHandler(
        GrpcHttp2ConnectionHandler grpcHandler,
        Channel channel,
        Optional<S2AIdentity> localIdentity,
        String hostname,
        ListeningExecutorService service,
        @Nullable S2AStub stub) {
      super(
          // superclass (InternalProtocolNegotiators.ProtocolNegotiationHandler) expects 'next'
          // handler but we don't have a next handler _yet_. So we "disable" superclass's behavior
          // here and then manually add 'next' when we call fireProtocolNegotiationEvent()
          new ChannelHandlerAdapter() {
            @Override
            public void handlerAdded(ChannelHandlerContext ctx) {
              ctx.pipeline().remove(this);
            }
          },
          grpcHandler.getNegotiationLogger());
      this.grpcHandler = grpcHandler;
      this.channel = channel;
      this.localIdentity = localIdentity;
      this.hostname = hostname;
      checkNotNull(service, "service should not be null.");
      this.service = service;
      this.stub = stub;
    }

    @Override
    protected void handlerAdded0(ChannelHandlerContext ctx) {
      // Buffer all reads until the TLS Handler is added.
      BufferReadsHandler bufferReads = new BufferReadsHandler();
      ctx.pipeline().addBefore(ctx.name(), /* name= */ null, bufferReads);

      S2AStub s2aStub;
      if (this.stub == null) {
        checkNotNull(channel, "Channel to S2A should not be null");
        s2aStub = S2AStub.newInstance(S2AServiceGrpc.newStub(channel));
      } else {
        s2aStub = this.stub;
      }

      ListenableFuture<SslContext> sslContextFuture =
          service.submit(() -> SslContextFactory.createForClient(s2aStub, hostname, localIdentity));
      Futures.addCallback(
          sslContextFuture,
          new FutureCallback<SslContext>() {
            @Override
            public void onSuccess(SslContext sslContext) {
              ChannelHandler handler =
                  InternalProtocolNegotiators.tls(
                          sslContext,
                          SharedResourcePool.forResource(GrpcUtil.SHARED_CHANNEL_EXECUTOR),
                          com.google.common.base.Optional.of(new Runnable() {
                            @Override
                            public void run() {
                              s2aStub.close();
                            }
                          }))
                      .newHandler(grpcHandler);

              // Delegate the rest of the handshake to the TLS handler. and remove the 
              // bufferReads handler.
              ctx.pipeline().addAfter(ctx.name(), /* name= */ null, handler);
              fireProtocolNegotiationEvent(ctx);
              ctx.pipeline().remove(bufferReads);
            }

            @Override
            public void onFailure(Throwable t) {
              ctx.fireExceptionCaught(t);
            }
          },
          service);
    }
  }

  private S2AProtocolNegotiatorFactory() {}
}
