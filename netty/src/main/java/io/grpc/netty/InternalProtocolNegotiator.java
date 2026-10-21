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

import com.google.common.base.Preconditions;
import io.grpc.Internal;
import io.grpc.internal.ObjectPool;
import io.netty.channel.ChannelHandler;
import io.netty.util.AsciiString;
import java.util.concurrent.Executor;

/**
 * Internal accessor for {@link ProtocolNegotiator}.
 */
@Internal
public final class InternalProtocolNegotiator {

  private InternalProtocolNegotiator() {}

  public interface ProtocolNegotiator extends io.grpc.netty.ProtocolNegotiator {}

  static final class ProtocolNegotiatorAdapter
      implements InternalProtocolNegotiator.ProtocolNegotiator {
    private final io.grpc.netty.ProtocolNegotiator negotiator;

    public ProtocolNegotiatorAdapter(io.grpc.netty.ProtocolNegotiator negotiator) {
      this.negotiator = Preconditions.checkNotNull(negotiator, "negotiator");
    }

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

  public interface ClientFactory extends io.grpc.netty.ProtocolNegotiator.ClientFactory {
    @Override ProtocolNegotiator newNegotiator();
  }

  public interface ServerFactory extends io.grpc.netty.ProtocolNegotiator.ServerFactory {
    @Override ProtocolNegotiator newNegotiator(ObjectPool<? extends Executor> offloadExecutorPool);
  }
}
