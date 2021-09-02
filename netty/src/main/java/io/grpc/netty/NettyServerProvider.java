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

import io.grpc.Internal;
import io.grpc.ServerCredentials;
import io.grpc.ServerProvider;
import java.net.InetSocketAddress;

/** Provider for {@link NettyServerBuilder} instances. */
@Internal
public final class NettyServerProvider extends ServerProvider {

  @Override
  protected boolean isAvailable() {
    return true;
  }

  @Override
  protected int priority() {
    return 5;
  }

  @Override
  protected NettyServerBuilder builderForPort(int port) {
    return NettyServerBuilder.forPort(port);
  }

  @Override
  protected NewServerBuilderResult newServerBuilderForPort(int port, ServerCredentials creds) {
    ProtocolNegotiators.FromServerCredentialsResult result = ProtocolNegotiators.from(creds);
    if (result.error != null) {
      return NewServerBuilderResult.error(result.error);
    }
    return NewServerBuilderResult.serverBuilder(
        new NettyServerBuilder(new InetSocketAddress(port), result.negotiator));
  }
}

