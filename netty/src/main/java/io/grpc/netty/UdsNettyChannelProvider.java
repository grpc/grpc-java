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

import io.grpc.ChannelCredentials;
import io.grpc.Internal;
import io.grpc.ManagedChannelProvider;
import io.grpc.internal.SharedResourcePool;
import io.netty.channel.unix.DomainSocketAddress;
import java.net.SocketAddress;
import java.util.Collection;
import java.util.Collections;

/** Provider for {@link NettyChannelBuilder} instances for UDS channels. */
@Internal
public final class UdsNettyChannelProvider extends ManagedChannelProvider {

  @Override
  public boolean isAvailable() {
    return (Utils.EPOLL_DOMAIN_CLIENT_CHANNEL_TYPE != null);
  }

  @Override
  public int priority() {
    return 3;
  }

  @Override
  public NettyChannelBuilder builderForAddress(String name, int port) {
    throw new AssertionError("NettyChannelProvider shadows this implementation");
  }

  @Override
  public NettyChannelBuilder builderForTarget(String target) {
    throw new AssertionError("NettyChannelProvider shadows this implementation");
  }

  @Override
  public NewChannelBuilderResult newChannelBuilder(String target, ChannelCredentials creds) {
    NewChannelBuilderResult result = new NettyChannelProvider().newChannelBuilder(target, creds);
    if (result.getChannelBuilder() != null) {
      ((NettyChannelBuilder) result.getChannelBuilder())
          .eventLoopGroupPool(SharedResourcePool.forResource(Utils.DEFAULT_WORKER_EVENT_LOOP_GROUP))
          .channelType(Utils.EPOLL_DOMAIN_CLIENT_CHANNEL_TYPE);
    }
    return result;
  }

  @Override
  protected Collection<Class<? extends SocketAddress>> getSupportedSocketAddressTypes() {
    return Collections.singleton(DomainSocketAddress.class);
  }
}
