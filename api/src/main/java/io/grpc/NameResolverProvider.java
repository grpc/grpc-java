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

import io.grpc.NameResolver.Factory;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Collection;
import java.util.Collections;

/**
 * Provider of name resolvers for name agnostic consumption.
 *
 * <p>Implementations can be automatically discovered by gRPC via Java's SPI mechanism. For
 * automatic discovery, the implementation must have a zero-argument constructor and include
 * a resource named {@code META-INF/services/io.grpc.NameResolverProvider} in their JAR. The
 * file's contents should be the implementation's class name. Implementations that need arguments in
 * their constructor can be manually registered by {@link NameResolverRegistry#register}.
 *
 * <p>Implementations <em>should not</em> throw. If they do, it may interrupt class loading. If
 * exceptions may reasonably occur for implementation-specific reasons, implementations should
 * generally handle the exception gracefully and return {@code false} from {@link #isAvailable()}.
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/4159")
public abstract class NameResolverProvider extends NameResolver.Factory {
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

  /**
   * Returns the scheme associated with the provider. The provider normally should only create a
   * {@link NameResolver} when target URI scheme matches the provider scheme. It temporarily
   * delegates to {@link Factory#getDefaultScheme()} before {@link NameResolver.Factory} is
   * deprecated in https://github.com/grpc/grpc-java/issues/7133.
   *
   * <p>The scheme should be lower-case.
   *
   * @since 1.40.0
   * */
  protected String getScheme() {
    return getDefaultScheme();
  }

  /**
   * Returns the {@link SocketAddress} types this provider's name-resolver is capable of producing.
   * This enables selection of the appropriate {@link ManagedChannelProvider} for a channel.
   *
   * @return the {@link SocketAddress} types this provider's name-resolver is capable of producing.
   */
  protected Collection<Class<? extends SocketAddress>> getProducedSocketAddressTypes() {
    return Collections.singleton(InetSocketAddress.class);
  }
}
