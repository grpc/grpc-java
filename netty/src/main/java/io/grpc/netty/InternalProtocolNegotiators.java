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

package io.grpc.netty;

import io.grpc.ChannelLogger;
import io.grpc.internal.ObjectPool;
import io.grpc.netty.ProtocolNegotiators.ClientTlsHandler;
import io.grpc.netty.ProtocolNegotiators.GrpcNegotiationHandler;
import io.grpc.netty.ProtocolNegotiators.WaitUntilActiveHandler;
import io.netty.channel.ChannelHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.util.AsciiString;
import java.util.Optional;
import java.util.concurrent.Executor;

/**
 * Internal accessor for {@link ProtocolNegotiators}.
 */
public final class InternalProtocolNegotiators {

  private InternalProtocolNegotiators() {}

  /**
   * Returns a {@link ProtocolNegotiator} that ensures the pipeline is set up so that TLS will
   * be negotiated, the {@code handler} is added and writes to the {@link io.netty.channel.Channel}
   * may happen immediately, even before the TLS Handshake is complete.
   * @param executorPool a dedicated {@link Executor} pool for time-consuming TLS tasks
   */
  public static InternalProtocolNegotiator.ProtocolNegotiator tls(SslContext sslContext,
          ObjectPool<? extends Executor> executorPool,
          Optional<Runnable> handshakeCompleteRunnable) {
    final io.grpc.netty.ProtocolNegotiator negotiator = ProtocolNegotiators.tls(sslContext,
        executorPool, handshakeCompleteRunnable);
    final class TlsNegotiator implements InternalProtocolNegotiator.ProtocolNegotiator {

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
    
    return new TlsNegotiator();
  }
  
  /**
   * Returns a {@link ProtocolNegotiator} that ensures the pipeline is set up so that TLS will
   * be negotiated, the {@code handler} is added and writes to the {@link io.netty.channel.Channel}
   * may happen immediately, even before the TLS Handshake is complete.
   */
  public static InternalProtocolNegotiator.ProtocolNegotiator tls(SslContext sslContext) {
    return tls(sslContext, null, Optional.empty());
  }

  /**
   * Returns a {@link ProtocolNegotiator} that ensures the pipeline is set up so that TLS will be
   * negotiated, the server TLS {@code handler} is added and writes to the {@link
   * io.netty.channel.Channel} may happen immediately, even before the TLS Handshake is complete.
   */
  public static InternalProtocolNegotiator.ProtocolNegotiator serverTls(SslContext sslContext) {
    final io.grpc.netty.ProtocolNegotiator negotiator = ProtocolNegotiators.serverTls(sslContext);
    final class ServerTlsNegotiator implements InternalProtocolNegotiator.ProtocolNegotiator {

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

    return new ServerTlsNegotiator();
  }

  /** Returns a {@link ProtocolNegotiator} for plaintext client channel. */
  public static InternalProtocolNegotiator.ProtocolNegotiator plaintext() {
    final io.grpc.netty.ProtocolNegotiator negotiator = ProtocolNegotiators.plaintext();
    final class PlaintextNegotiator implements InternalProtocolNegotiator.ProtocolNegotiator {

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

    return new PlaintextNegotiator();
  }

  /** Returns a {@link ProtocolNegotiator} for plaintext server channel. */
  public static InternalProtocolNegotiator.ProtocolNegotiator serverPlaintext() {
    final io.grpc.netty.ProtocolNegotiator negotiator = ProtocolNegotiators.serverPlaintext();
    final class ServerPlaintextNegotiator implements InternalProtocolNegotiator.ProtocolNegotiator {

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

    return new ServerPlaintextNegotiator();
  }

  /**
   * Internal version of {@link WaitUntilActiveHandler}.
   */
  public static ChannelHandler waitUntilActiveHandler(ChannelHandler next,
      ChannelLogger negotiationLogger) {
    return new WaitUntilActiveHandler(next, negotiationLogger);
  }

  /**
   * Internal version of {@link GrpcNegotiationHandler}.
   */
  public static ChannelHandler grpcNegotiationHandler(GrpcHttp2ConnectionHandler next) {
    return new GrpcNegotiationHandler(next);
  }

  public static ChannelHandler clientTlsHandler(
      ChannelHandler next, SslContext sslContext, String authority,
      ChannelLogger negotiationLogger) {
    return new ClientTlsHandler(next, sslContext, authority, null, negotiationLogger,
        Optional.empty());
  }

  public static class ProtocolNegotiationHandler
      extends ProtocolNegotiators.ProtocolNegotiationHandler {

    protected ProtocolNegotiationHandler(ChannelHandler next, String negotiatorName,
        ChannelLogger negotiationLogger) {
      super(next, negotiatorName, negotiationLogger);
    }

    protected ProtocolNegotiationHandler(ChannelHandler next, ChannelLogger negotiationLogger) {
      super(next, negotiationLogger);
    }
  }
}
