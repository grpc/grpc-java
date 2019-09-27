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

package io.grpc.xds.sds;

import io.grpc.Internal;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * A SecretProvider is a "container" or provider of a secret. This is used by gRPC-xds to access
 * secrets, so is not part of the public API of gRPC. This "container" may represent a stream that
 * is receiving the requested secret(s) or it could represent file-system based secret(s) that are
 * dynamic. Synchronous and Asynchronous methods to access the underlying secret are available. See
 * {@link SecretManager} for a note on lifecycle management
 */
@Internal
public interface SecretProvider<T> {

  /**
   * Gets the current secret (waits indefinitely if necessary).
   */
  T get() throws InterruptedException, ExecutionException;

  /**
   * Gets the secret (and waits if necessary for the given time and then returns the result, if
   * available.
   */
  T get(long timeout, TimeUnit unit)
      throws InterruptedException, ExecutionException, TimeoutException;

  /**
   * Returns {@code true} if secret is available.
   */
  boolean isAvailable();

  /**
   * Registers a listener to be on the given executor. The listener will run when the result is
   * {@linkplain #isAvailable()} or immediately, if the result is already available.
   */
  void addListener(Runnable listener, Executor executor);
}
