/*
 * Copyright 2026 The gRPC Authors
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

package io.grpc.xds.internal.grpcservice;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.auto.value.AutoValue;
import io.grpc.ManagedChannel;
import io.grpc.xds.internal.grpcservice.GrpcServiceConfig.GoogleGrpcConfig;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Concrete class managing the lifecycle of a single ManagedChannel for a GrpcServiceConfig.
 */
public class CachedChannelManager {
  private final Function<GrpcServiceConfig, ManagedChannel> channelCreator;
  private final Object lock = new Object();

  private final AtomicReference<ChannelHolder> channelHolder = new AtomicReference<>();

  /**
   * Default constructor for production that creates a channel using the config's target and
   * credentials.
   */
  public CachedChannelManager() {
    this(config -> {
      GoogleGrpcConfig googleGrpc = config.googleGrpc();
      return io.grpc.Grpc.newChannelBuilder(googleGrpc.target(),
          googleGrpc.configuredChannelCredentials().channelCredentials()).build();
    });
  }

  /**
   * Constructor for testing to inject a channel creator.
   */
  public CachedChannelManager(Function<GrpcServiceConfig, ManagedChannel> channelCreator) {
    this.channelCreator = checkNotNull(channelCreator, "channelCreator");
  }

  /**
   * Returns a ManagedChannel for the given configuration. If the target or credentials config
   * changes, the old channel is shut down and a new one is created.
   */
  public ManagedChannel getChannel(GrpcServiceConfig config) {
    GoogleGrpcConfig googleGrpc = config.googleGrpc();
    ChannelKey newChannelKey = ChannelKey.of(
        googleGrpc.target(),
        googleGrpc.configuredChannelCredentials().channelCredsConfig());

    // 1. Fast path: Lock-free read
    ChannelHolder holder = channelHolder.get();
    if (holder != null && holder.channelKey().equals(newChannelKey)) {
      return holder.channel();
    }

    ManagedChannel oldChannel = null;
    ManagedChannel newChannel;

    // 2. Slow path: Update with locking
    synchronized (lock) {
      holder = channelHolder.get(); // Double check
      if (holder != null && holder.channelKey().equals(newChannelKey)) {
        return holder.channel();
      }

      // 3. Create inside lock to avoid creation storms
      newChannel = channelCreator.apply(config);
      ChannelHolder newHolder = ChannelHolder.create(newChannelKey, newChannel);

      if (holder != null) {
        oldChannel = holder.channel();
      }
      channelHolder.set(newHolder);
    }

    // 4. Shutdown outside lock
    if (oldChannel != null) {
      oldChannel.shutdown();
    }

    return newChannel;
  }

  /** Removes underlying resources on shutdown. */
  public void close() {
    ChannelHolder holder = channelHolder.get();
    if (holder != null) {
      holder.channel().shutdown();
    }
  }

  @AutoValue
  abstract static class ChannelKey {
    static ChannelKey of(String target, ChannelCredsConfig credentialsConfig) {
      return new AutoValue_CachedChannelManager_ChannelKey(target, credentialsConfig);
    }

    abstract String target();

    abstract ChannelCredsConfig channelCredsConfig();
  }

  @AutoValue
  abstract static class ChannelHolder {
    static ChannelHolder create(ChannelKey channelKey, ManagedChannel channel) {
      return new AutoValue_CachedChannelManager_ChannelHolder(channelKey, channel);
    }

    abstract ChannelKey channelKey();

    abstract ManagedChannel channel();
  }
}
