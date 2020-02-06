/*
 * Copyright 2016 The gRPC Authors
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
import io.grpc.internal.SharedResourcePool;
import io.netty.channel.socket.nio.NioServerSocketChannel;

/**
 * Internal {@link InternalNettyServerBuilder} accessor.  This is intended for usage internal to
 * the gRPC team.  If you *really* think you need to use this, contact the gRPC team first.
 */
@Internal
public final class InternalNettyServerBuilder {

  public static void setStatsEnabled(NettyServerBuilder builder, boolean value) {
    builder.setStatsEnabled(value);
  }

  public static void setStatsRecordStartedRpcs(NettyServerBuilder builder, boolean value) {
    builder.setStatsRecordStartedRpcs(value);
  }

  public static void setStatsRecordRealTimeMetrics(NettyServerBuilder builder, boolean value) {
    builder.setStatsRecordRealTimeMetrics(value);
  }

  public static void setTracingEnabled(NettyServerBuilder builder, boolean value) {
    builder.setTracingEnabled(value);
  }

  public static void setForceHeapBuffer(NettyServerBuilder builder, boolean value) {
    builder.setForceHeapBuffer(value);
  }

  /**
   * Sets {@link io.grpc.Channel} and {@link io.netty.channel.EventLoopGroup}s to Nio. A major
   * benefit over using existing setters is gRPC will manage the life cycle of {@link
   * io.netty.channel.EventLoopGroup}s.
   */
  public static void useNioTransport(NettyServerBuilder builder) {
    builder.channelType(NioServerSocketChannel.class);
    builder
        .bossEventLoopGroupPool(SharedResourcePool.forResource(Utils.NIO_BOSS_EVENT_LOOP_GROUP));
    builder
        .workerEventLoopGroupPool(
            SharedResourcePool.forResource(Utils.NIO_WORKER_EVENT_LOOP_GROUP));
  }

  private InternalNettyServerBuilder() {}
}
