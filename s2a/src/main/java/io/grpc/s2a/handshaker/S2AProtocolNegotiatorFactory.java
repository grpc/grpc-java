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

package io.grpc.s2a.handshaker;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.net.HostAndPort;
import com.google.errorprone.annotations.ThreadSafe;
import io.grpc.Channel;
import io.grpc.ChannelLogger;
import io.grpc.internal.ObjectPool;
import io.grpc.netty.GrpcHttp2ConnectionHandler;
import io.grpc.netty.InternalProtocolNegotiator;
import io.grpc.netty.InternalProtocolNegotiator.ProtocolNegotiator;
import io.grpc.netty.InternalProtocolNegotiators;
import io.grpc.netty.InternalProtocolNegotiators.ProtocolNegotiationHandler;
import io.grpc.s2a.channel.S2AChannelPool;
import io.grpc.s2a.channel.S2AGrpcChannelPool;
import io.grpc.s2a.handshaker.S2AIdentity;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.ssl.SslContext;
import io.netty.util.AsciiString;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.util.Optional;
import org.checkerframework.checker.nullness.qual.Nullable;

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
   * @return a factory for creating a client-side protocol negotiator.
   */
  public static InternalProtocolNegotiator.ClientFactory createClientFactory(
      Optional<S2AIdentity> localIdentity, ObjectPool<Channel> s2aChannelPool) {
    checkNotNull(s2aChannelPool, "S2A channel pool should not be null.");
    checkNotNull(localIdentity, "Local identity should not be null on the client side.");
    S2AChannelPool channelPool = S2AGrpcChannelPool.create(s2aChannelPool);
    return new S2AClientProtocolNegotiatorFactory(localIdentity, channelPool);
  }

  static final class S2AClientProtocolNegotiatorFactory
      implements InternalProtocolNegotiator.ClientFactory {
    private final Optional<S2AIdentity> localIdentity;
    private final S2AChannelPool channelPool;

    S2AClientProtocolNegotiatorFactory(
        Optional<S2AIdentity> localIdentity, S2AChannelPool channelPool) {
      this.localIdentity = localIdentity;
      this.channelPool = channelPool;
    }

    @Override
    public ProtocolNegotiator newNegotiator() {
      return S2AProtocolNegotiator.createForClient(channelPool, localIdentity);
    }

    @Override
    public int getDefaultPort() {
      return DEFAULT_PORT;
    }
  }

  /** Negotiates the TLS handshake using S2A. */
  @VisibleForTesting
  static final class S2AProtocolNegotiator implements ProtocolNegotiator {

    private final S2AChannelPool channelPool;
    private final Optional<S2AIdentity> localIdentity;

    static S2AProtocolNegotiator createForClient(
        S2AChannelPool channelPool, Optional<S2AIdentity> localIdentity) {
      checkNotNull(channelPool, "Channel pool should not be null.");
      checkNotNull(localIdentity, "Local identity should not be null on the client side.");
      return new S2AProtocolNegotiator(channelPool, localIdentity);
    }

    @VisibleForTesting
    static @Nullable String getHostNameFromAuthority(@Nullable String authority) {
      if (authority == null) {
        return null;
      }
      return HostAndPort.fromString(authority).getHost();
    }

    private S2AProtocolNegotiator(S2AChannelPool channelPool, Optional<S2AIdentity> localIdentity) {
      this.channelPool = channelPool;
      this.localIdentity = localIdentity;
    }

    @Override
    public AsciiString scheme() {
      return SCHEME;
    }

    @Override
    public ChannelHandler newHandler(GrpcHttp2ConnectionHandler grpcHandler) {
      checkNotNull(grpcHandler, "grpcHandler should not be null.");
      String hostname = getHostNameFromAuthority(grpcHandler.getAuthority());
      checkNotNull(hostname, "hostname should not be null.");
      return new S2AProtocolNegotiationHandler(
          InternalProtocolNegotiators.grpcNegotiationHandler(grpcHandler),
          grpcHandler.getNegotiationLogger(),
          channelPool,
          localIdentity,
          hostname,
          grpcHandler);
    }

    @Override
    public void close() {
      channelPool.close();
    }
  }

  private static final class S2AProtocolNegotiationHandler extends ProtocolNegotiationHandler {
    private final S2AChannelPool channelPool;
    private final Optional<S2AIdentity> localIdentity;
    private final String hostname;
    private InternalProtocolNegotiator.ProtocolNegotiator negotiator;
    private final GrpcHttp2ConnectionHandler grpcHandler;

    private S2AProtocolNegotiationHandler(
        ChannelHandler next,
        ChannelLogger negotiationLogger,
        S2AChannelPool channelPool,
        Optional<S2AIdentity> localIdentity,
        String hostname,
        GrpcHttp2ConnectionHandler grpcHandler) {
      super(next, negotiationLogger);
      this.channelPool = channelPool;
      this.localIdentity = localIdentity;
      this.hostname = hostname;
      this.grpcHandler = grpcHandler;
    }

    @Override
    protected void handlerAdded0(ChannelHandlerContext ctx) throws GeneralSecurityException {
      SslContext sslContext;
      try {
        // Establish a stream to S2A server.
        Channel ch = channelPool.getChannel();
        S2AServiceGrpc.S2AServiceStub stub = S2AServiceGrpc.newStub(ch);
        S2AStub s2aStub = S2AStub.newInstance(stub);
        sslContext = SslContextFactory.createForClient(s2aStub, hostname, localIdentity);
      } catch (InterruptedException
          | IOException
          | IllegalArgumentException
          | UnrecoverableKeyException
          | CertificateException
          | NoSuchAlgorithmException
          | KeyStoreException e) {
        // GeneralSecurityException is intentionally not caught, and rather propagated. This is done
        // because throwing a GeneralSecurityException in this context indicates that we encountered
        // a retryable error.
        throw new IllegalArgumentException(
            "Something went wrong during the initialization of SslContext.", e);
      }
      negotiator = InternalProtocolNegotiators.tls(sslContext);
      ctx.pipeline().addBefore(ctx.name(), /* name= */ null, negotiator.newHandler(grpcHandler));
    }
  }

  private S2AProtocolNegotiatorFactory() {}
}