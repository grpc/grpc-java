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

package io.grpc.alts;

import io.grpc.ForwardingChannelBuilder;
import io.grpc.ManagedChannelBuilder;
import io.grpc.internal.GrpcUtil;
import io.grpc.netty.NettyChannelBuilder;

/**
 * {@code ManagedChannelBuilder} for Google Compute Engine. This class sets up a secure channel
 * using ALTS if applicable and using TLS as fallback.
 */
public final class ComputeEngineChannelBuilder
    extends ForwardingChannelBuilder<ComputeEngineChannelBuilder> {

  private final NettyChannelBuilder delegate;

  private ComputeEngineChannelBuilder(String target) {
    delegate = NettyChannelBuilder.forTarget(target, ComputeEngineChannelCredentials.create());
  }

  /** "Overrides" the static method in {@link ManagedChannelBuilder}. */
  public static final ComputeEngineChannelBuilder forTarget(String target) {
    return new ComputeEngineChannelBuilder(target);
  }

  /** "Overrides" the static method in {@link ManagedChannelBuilder}. */
  public static ComputeEngineChannelBuilder forAddress(String name, int port) {
    return forTarget(GrpcUtil.authorityFromHostAndPort(name, port));
  }

  @Override
  protected NettyChannelBuilder delegate() {
    return delegate;
  }
}
