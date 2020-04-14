/*
 * Copyright 2020 The gRPC Authors
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

package io.grpc.inprocess;

import io.grpc.Attributes;
import io.grpc.Internal;
import io.grpc.ServerStreamTracer;
import io.grpc.internal.ConnectionClientTransport;
import io.grpc.internal.ObjectPool;
import io.grpc.internal.ServerListener;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Internal {@link InProcessTransport} accessor.
 *
 * <p>This is intended for use by io.grpc.internal, and the specifically
 * supported transport packages.
 */
@Internal
public final class InternalInProcess {

  private InternalInProcess() {}

  /**
   * Creates a new InProcessTransport.
   *
   * <p>When started, the transport will be registered with the given
   * {@link ServerListener}.
   */
  @Internal
  public static ConnectionClientTransport createInProcessTransport(
      String name,
      int maxInboundMetadataSize,
      String authority,
      String userAgent,
      Attributes eagAttrs,
      ObjectPool<ScheduledExecutorService> serverSchedulerPool,
      List<ServerStreamTracer.Factory> serverStreamTracerFactories,
      ServerListener serverListener) {
    return new InProcessTransport(
        name,
        maxInboundMetadataSize,
        authority,
        userAgent,
        eagAttrs,
        serverSchedulerPool,
        serverStreamTracerFactories,
        serverListener);
  }
}
