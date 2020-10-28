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

package io.grpc.internal;

import io.grpc.ForwardingServerBuilder;
import io.grpc.ServerBuilder;

/**
 * Temporary shim for {@link io.grpc.ForwardingServerBuilder} to fix ABI backward compatibility.
 *
 * @param <T> The concrete type of this builder.
 * @see <a href="https://github.com/grpc/grpc-java/issues/7211">grpc/grpc-java#7211</a>
 */
public abstract class AbstractServerImplBuilder
    <T extends AbstractServerImplBuilder<T>> extends ForwardingServerBuilder<T> {
  /**
   * This method serves to force sub classes to "hide" this static factory.
   */
  public static ServerBuilder<?> forPort(int port) {
    throw new UnsupportedOperationException("Subclass failed to hide static factory");
  }
}
