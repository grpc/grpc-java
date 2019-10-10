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

package io.grpc.xds.sds.internal;

import io.grpc.Internal;
import io.grpc.netty.GrpcHttp2ConnectionHandler;
import io.grpc.netty.InternalNettyChannelBuilder;
import io.grpc.netty.InternalNettyChannelBuilder.ProtocolNegotiatorFactory;
import io.grpc.netty.InternalProtocolNegotiator;
import io.grpc.netty.InternalProtocolNegotiator.ProtocolNegotiator;
import io.grpc.netty.NettyChannelBuilder;
import io.netty.channel.ChannelHandler;
import io.netty.util.AsciiString;

/**
 * Provides client and server side gRPC {@link ProtocolNegotiator}s that use SDS to provide the SSL
 * context.
 */
@Internal
public final class SdsProtocolNegotiators {

  private static final AsciiString SCHEME = AsciiString.of("https");

  private static final class ClientSdsProtocolNegotiatorFactory
      implements InternalNettyChannelBuilder.ProtocolNegotiatorFactory {

    @Override
    public InternalProtocolNegotiator.ProtocolNegotiator buildProtocolNegotiator() {
      final ClientSdsProtocolNegotiator negotiator = new ClientSdsProtocolNegotiator();
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

  private static final class ClientSdsProtocolNegotiator implements ProtocolNegotiator {

    @Override
    public AsciiString scheme() {
      return SCHEME;
    }

    @Override
    public ChannelHandler newHandler(GrpcHttp2ConnectionHandler grpcHandler) {
      // TODO(sanjaypujare): once implemented return ClientSdsHandler
      throw new UnsupportedOperationException("Not implemented yet");
    }

    @Override
    public void close() {}
  }

  private static final class ServerSdsProtocolNegotiator implements ProtocolNegotiator {

    @Override
    public AsciiString scheme() {
      return SCHEME;
    }

    @Override
    public ChannelHandler newHandler(GrpcHttp2ConnectionHandler grpcHandler) {
      // TODO(sanjaypujare): once implemented return ServerSdsHandler
      throw new UnsupportedOperationException("Not implemented yet");
    }

    @Override
    public void close() {}
  }

  /** Sets the {@link ProtocolNegotiatorFactory} on a NettyChannelBuilder. */
  public static void setProtocolNegotiatorFactory(NettyChannelBuilder builder) {
    InternalNettyChannelBuilder.setProtocolNegotiatorFactory(
        builder, new ClientSdsProtocolNegotiatorFactory());
  }

  /** Creates an SDS based {@link ProtocolNegotiator} for a server. */
  public static ProtocolNegotiator serverProtocolNegotiator() {
    return new ServerSdsProtocolNegotiator();
  }
}
