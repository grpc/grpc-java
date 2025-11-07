/*
 * Copyright 2025 The gRPC Authors
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

package io.grpc.xds;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.annotations.VisibleForTesting;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;

/**
 * Registry of {@link XdsCallCredentialsProvider}s. The {@link #getDefaultRegistry default
 * instance} loads hardcoded providers at runtime.
 */
@ThreadSafe
final class XdsCallCredentialsRegistry {
  private static XdsCallCredentialsRegistry instance;

  private final Map<String, XdsCallCredentialsProvider> registeredProviders =
      new HashMap<>();

  /**
   * Returns the default registry that loads hardcoded providers at runtime.
   */
  public static synchronized XdsCallCredentialsRegistry getDefaultRegistry() {
    if (instance == null) {
      instance = newRegistry().register(new JwtTokenFileXdsCallCredentialsProvider());
    }
    return instance;
  }

  @VisibleForTesting
  static XdsCallCredentialsRegistry newRegistry() {
    return new XdsCallCredentialsRegistry();
  }

  @VisibleForTesting
  XdsCallCredentialsRegistry register(XdsCallCredentialsProvider... providers) {
    for (XdsCallCredentialsProvider provider : providers) {
      registeredProviders.put(provider.getName(), provider);
    }
    return this;
  }

  @VisibleForTesting
  synchronized Map<String, XdsCallCredentialsProvider> providers() {
    return registeredProviders;
  }

  /**
   * Returns the registered provider for the given xDS call credential name, or {@code null} if no
   * suitable provider can be found.
   * Each provider declares its name via {@link XdsCallCredentialsProvider#getName}.
   */
  @Nullable
  public synchronized XdsCallCredentialsProvider getProvider(String name) {
    return registeredProviders.get(checkNotNull(name, "name"));
  }
}
