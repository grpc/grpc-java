/*
 * Copyright 2018 The gRPC Authors
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

import com.google.common.annotations.VisibleForTesting;
import io.grpc.internal.ClientTransportFactory;
import io.grpc.internal.TransportTracer;
import javax.annotation.CheckReturnValue;

/**
 * Internal Accessor for tests.
 */
@VisibleForTesting
public final class InternalNettyTestAccessor {
  private InternalNettyTestAccessor() {}

  public static void setTransportTracerFactory(
      NettyChannelBuilder builder, TransportTracer.Factory factory) {
    builder.setTransportTracerFactory(factory);
  }

  @CheckReturnValue
  public static ClientTransportFactory buildTransportFactory(NettyChannelBuilder builder) {
    return builder.buildTransportFactory();
  }
}
