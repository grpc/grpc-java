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

import com.google.common.annotations.VisibleForTesting;
import io.grpc.ManagedChannel;
import io.grpc.xds.internal.grpcservice.GrpcServiceConfig.GoogleGrpcConfig;
import java.util.function.Function;

/**
 * Concrete class managing the lifecycle of a single ManagedChannel for a GrpcServiceConfig.
 */
public class CachedChannelManager {
  private final Function<GrpcServiceConfig, ManagedChannel> channelCreator;

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
  @VisibleForTesting
  public CachedChannelManager(Function<GrpcServiceConfig, ManagedChannel> channelCreator) {
    this.channelCreator = checkNotNull(channelCreator, "channelCreator");
  }

  /**
   * Returns a ManagedChannel for the given configuration.
   */
  public ManagedChannel getChannel(GrpcServiceConfig config) {
    return channelCreator.apply(config);
  }

  /** Removes underlying resources on shutdown. */
  public void close() {
    // No-op as channel caching and lifecycle management is removed.
  }
}
