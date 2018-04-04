/*
 * Copyright 2017, gRPC Authors All rights reserved.
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

package io.grpc.internal;

import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.InternalServiceProviders;
import io.grpc.InternalServiceProviders.PriorityAccessor;
import io.grpc.ServerMethodDefinition;
import io.grpc.ServerStreamTracer;
import java.io.Closeable;
import java.util.Collections;
import javax.annotation.Nullable;

public abstract class BinaryLogProvider implements Closeable {
  private static final BinaryLogProvider PROVIDER = InternalServiceProviders.load(
      BinaryLogProvider.class,
      Collections.<Class<?>>emptyList(),
      BinaryLogProvider.class.getClassLoader(),
      new PriorityAccessor<BinaryLogProvider>() {
        @Override
        public boolean isAvailable(BinaryLogProvider provider) {
          return provider.isAvailable();
        }

        @Override
        public int getPriority(BinaryLogProvider provider) {
          return provider.priority();
        }
      });

  /**
   * Returns a {@code BinaryLogProvider}, or {@code null} if there is no provider.
   */
  @Nullable
  public static BinaryLogProvider provider() {
    return PROVIDER;
  }

  /**
   * Wraps a channel to provide binary logging on {@link ClientCall}s as needed.
   */
  public abstract Channel wrapChannel(Channel channel);

  /**
   * Wraps a {@link ServerMethodDefinition} such that it performs binary logging if needed.
   */
  public abstract <ReqT, RespT> ServerMethodDefinition<?, ?> wrapMethodDefinition(
      ServerMethodDefinition<ReqT, RespT> oMethodDef);

  /**
   * Returns a {@link ServerStreamTracer.Factory} that copies the call ID to {@link io.grpc.Context}
   * as {@code SERVER_CALL_ID_CONTEXT_KEY}.
   */
  public abstract ServerStreamTracer.Factory getServerCallIdSetter();

  /**
   * Returns a {@link ClientInterceptor} that copies the call ID to {@link io.grpc.CallOptions}
   * as {@code CALL_CLIENT_CALL_ID_CALLOPTION_KEY}.
   */
  public abstract ClientInterceptor getClientCallIdSetter();

  /**
   * A priority, from 0 to 10 that this provider should be used, taking the current environment into
   * consideration. 5 should be considered the default, and then tweaked based on environment
   * detection. A priority of 0 does not imply that the provider wouldn't work; just that it should
   * be last in line.
   */
  protected abstract int priority();

  /**
   * Whether this provider is available for use, taking the current environment into consideration.
   * If {@code false}, no other methods are safe to be called.
   */
  protected abstract boolean isAvailable();
}
