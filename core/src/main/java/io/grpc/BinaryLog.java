/*
 * Copyright 2018, gRPC Authors All rights reserved.
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

package io.grpc;

import java.io.Closeable;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;

@ThreadSafe
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/4017")
public abstract class BinaryLog implements Closeable {
  /**
   * Returns a {@link ServerInterceptor} for binary logging. gRPC is free to cache the interceptor,
   * so the interceptor must be reusable across server calls. At runtime, the request and response
   * types passed into the interceptor is always {@link java.io.InputStream}.
   * Returns {@code null} if this method is not binary logged.
   */
  @Nullable
  public abstract ServerInterceptor getServerInterceptor(String fullMethodName);

  /**
   * Returns a {@link ClientInterceptor} for binary logging. gRPC is free to cache the interceptor,
   * so the interceptor must be reusable across server calls. At runtime, the request and response
   * types passed into the interceptor is always {@link java.io.InputStream}.
   * Returns {@code null} if this method is not binary logged.
   */
  @Nullable
  public abstract ClientInterceptor getClientInterceptor(String fullMethodName);

  /**
   * A priority, from 0 to 10 that this provider should be used, taking the current environment into
   * consideration. 5 should be considered the default, and then tweaked based on environment
   * detection. A priority of 0 does not imply that the provider wouldn't work; just that it should
   * be last in line.
   */
  protected abstract int priority();

  /**
   * Checks this provider is available for use, taking the current environment into consideration.
   * If {@code false}, no other methods are safe to be called.
   */
  protected abstract boolean isAvailable();
}
