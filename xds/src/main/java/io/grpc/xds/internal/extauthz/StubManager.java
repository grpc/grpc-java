/*
 * Copyright 2025 The gRPC Authors
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

package io.grpc.xds.internal.extauthz;

import com.google.auto.value.AutoValue;
import io.envoyproxy.envoy.service.auth.v3.AuthorizationGrpc;
import io.grpc.ManagedChannel;
import io.grpc.xds.internal.grpcservice.GrpcServiceConfig.GoogleGrpcConfig;
import io.grpc.xds.internal.grpcservice.GrpcServiceConfigChannelFactory;
import java.util.Optional;
import javax.annotation.concurrent.GuardedBy;

/**
 * Manages the lifecycle of the authorization stub.
 */
public interface StubManager {
  /** Creates a new instance of {@code StubManager}. */
  static StubManager create(GrpcServiceConfigChannelFactory channelFactory) {
    return new StubManagerImpl(channelFactory);
  }

  /**
   * Returns a stub for the given configuration.
   */
  AuthorizationGrpc.AuthorizationStub getStub(ExtAuthzConfig config);

  /**
   * Frees underlying resources on shutdown.
   */
  public void close();

  /**
   * Default implementation of {@link StubManager}.
   */
  final class StubManagerImpl implements StubManager {

    private final GrpcServiceConfigChannelFactory channelFactory;
    private final Object lock = new Object();

    @GuardedBy("lock")
    private Optional<StubHolder> stubHolder = Optional.empty();

    private StubManagerImpl(GrpcServiceConfigChannelFactory channelFactory) { // NOPMD
      this.channelFactory = channelFactory;
    }

    @Override
    public AuthorizationGrpc.AuthorizationStub getStub(ExtAuthzConfig config) {
      GoogleGrpcConfig googleGrpc = config.grpcService().googleGrpc();
      ChannelKey newChannelKey =
          ChannelKey.of(googleGrpc.target(), googleGrpc.hashedChannelCredentials().hash());

      synchronized (lock) {
        if (stubHolder.isPresent() && stubHolder.get().channelKey().equals(newChannelKey)) {
          return stubHolder.get().stub();
        }
        Optional<ManagedChannel> oldChannel = stubHolder.map(StubHolder::channel);
        ManagedChannel newChannel = channelFactory.createChannel(config.grpcService());
        stubHolder = Optional.of(
            StubHolder.create(newChannelKey, newChannel, AuthorizationGrpc.newStub(newChannel)));
        oldChannel.ifPresent(ManagedChannel::shutdown);
        return stubHolder.get().stub();
      }
    }

    @AutoValue
    abstract static class ChannelKey {
      static ChannelKey of(String target, int hash) {
        return new AutoValue_StubManager_StubManagerImpl_ChannelKey(target, hash);
      }

      abstract String target();

      abstract int hash();
    }

    @AutoValue
    abstract static class StubHolder {
      static StubHolder create(ChannelKey channelKey, ManagedChannel channel,
          AuthorizationGrpc.AuthorizationStub stub) {
        return new AutoValue_StubManager_StubManagerImpl_StubHolder(channelKey, channel, stub);
      }

      abstract ChannelKey channelKey();

      abstract ManagedChannel channel();

      abstract AuthorizationGrpc.AuthorizationStub stub();
    }

    @Override
    public void close() {
      synchronized (lock) {
        stubHolder.ifPresent(holder -> {
          holder.channel().shutdown();
        });
      }
    }
  }
}
