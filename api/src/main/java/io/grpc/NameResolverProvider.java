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

package io.grpc;

import java.util.List;

/**
 * Provider of name resolvers for name agnostic consumption.
 *
 * <p>Implementations <em>should not</em> throw. If they do, it may interrupt class loading. If
 * exceptions may reasonably occur for implementation-specific reasons, implementations should
 * generally handle the exception gracefully and return {@code false} from {@link #isAvailable()}.
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/4159")
public abstract class NameResolverProvider extends NameResolver.Factory {

  /**
   * The port number used in case the target or the underlying naming system doesn't provide a
   * port number.
   *
   * @since 1.0.0
   */
  @SuppressWarnings("unused") // Avoids outside callers accidentally depending on the super class.
  @Deprecated
  public static final Attributes.Key<Integer> PARAMS_DEFAULT_PORT =
      NameResolver.Factory.PARAMS_DEFAULT_PORT;

  /**
   * Returns non-{@code null} ClassLoader-wide providers, in preference order.
   *
   * @since 1.0.0
   * @deprecated Has no replacement
   */
  @Deprecated
  public static List<NameResolverProvider> providers() {
    return NameResolverRegistry.getDefaultRegistry().providers();
  }

  /**
   * @since 1.0.0
   * @deprecated Use NameResolverRegistry.getDefaultRegistry().asFactory()
   */
  @Deprecated
  public static NameResolver.Factory asFactory() {
    return NameResolverRegistry.getDefaultRegistry().asFactory();
  }

  /**
   * Whether this provider is available for use, taking the current environment into consideration.
   * If {@code false}, no other methods are safe to be called.
   *
   * @since 1.0.0
   */
  protected abstract boolean isAvailable();

  /**
   * A priority, from 0 to 10 that this provider should be used, taking the current environment into
   * consideration. 5 should be considered the default, and then tweaked based on environment
   * detection. A priority of 0 does not imply that the provider wouldn't work; just that it should
   * be last in line.
   *
   * @since 1.0.0
   */
  protected abstract int priority();
}
