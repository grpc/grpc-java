/*
 * Copyright 2015 The gRPC Authors
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

import com.google.common.base.Preconditions;

/**
 * Provider of managed channels for transport agnostic consumption.
 *
 * <p>Implementations can be automatically discovered by gRPC via Java's SPI mechanism. For
 * automatic discovery, the implementation must have a zero-argument constructor and include
 * a resource named {@code META-INF/services/io.grpc.ManagedChannelProvider} in their JAR. The
 * file's contents should be the implementation's class name.
 *
 * <p>Implementations <em>should not</em> throw. If they do, it may interrupt class loading. If
 * exceptions may reasonably occur for implementation-specific reasons, implementations should
 * generally handle the exception gracefully and return {@code false} from {@link #isAvailable()}.
 */
@Internal
public abstract class ManagedChannelProvider {
  /**
   * Returns the ClassLoader-wide default channel.
   *
   * @throws ProviderNotFoundException if no provider is available
   */
  public static ManagedChannelProvider provider() {
    ManagedChannelProvider provider = ManagedChannelRegistry.getDefaultRegistry().provider();
    if (provider == null) {
      throw new ProviderNotFoundException("No functional channel service provider found. "
          + "Try adding a dependency on the grpc-okhttp, grpc-netty, or grpc-netty-shaded "
          + "artifact");
    }
    return provider;
  }

  /**
   * Whether this provider is available for use, taking the current environment into consideration.
   * If {@code false}, no other methods are safe to be called.
   */
  protected abstract boolean isAvailable();

  /**
   * A priority, from 0 to 10 that this provider should be used, taking the current environment into
   * consideration. 5 should be considered the default, and then tweaked based on environment
   * detection. A priority of 0 does not imply that the provider wouldn't work; just that it should
   * be last in line.
   */
  protected abstract int priority();

  /**
   * Creates a new builder with the given host and port.
   */
  protected abstract ManagedChannelBuilder<?> builderForAddress(String name, int port);

  /**
   * Creates a new builder with the given target URI.
   */
  protected abstract ManagedChannelBuilder<?> builderForTarget(String target);

  /**
   * Creates a new builder with the given target URI and credentials. Returns an error-string result
   * if unable to understand the credentials.
   */
  protected NewChannelBuilderResult newChannelBuilder(String target, ChannelCredentials creds) {
    return NewChannelBuilderResult.error("ChannelCredentials are unsupported");
  }

  public static final class NewChannelBuilderResult {
    private final ManagedChannelBuilder<?> channelBuilder;
    private final String error;

    private NewChannelBuilderResult(ManagedChannelBuilder<?> channelBuilder, String error) {
      this.channelBuilder = channelBuilder;
      this.error = error;
    }

    public static NewChannelBuilderResult channelBuilder(ManagedChannelBuilder<?> builder) {
      return new NewChannelBuilderResult(Preconditions.checkNotNull(builder), null);
    }

    public static NewChannelBuilderResult error(String error) {
      return new NewChannelBuilderResult(null, Preconditions.checkNotNull(error));
    }

    public ManagedChannelBuilder<?> getChannelBuilder() {
      return channelBuilder;
    }

    public String getError() {
      return error;
    }
  }

  /**
   * Thrown when no suitable {@link ManagedChannelProvider} objects can be found.
   */
  public static final class ProviderNotFoundException extends RuntimeException {
    private static final long serialVersionUID = 1;

    public ProviderNotFoundException(String msg) {
      super(msg);
    }
  }
}
