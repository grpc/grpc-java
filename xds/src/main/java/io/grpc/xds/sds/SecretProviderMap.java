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

import static com.google.common.base.Preconditions.checkNotNull;

import io.envoyproxy.envoy.api.v2.core.ConfigSource;

import java.util.HashMap;
import java.util.Map;

/**
 * SecretProviderMap used by SecretManager to maintain certificateProviders.
 *
 * @param <T> Type of secret stored in this Map
 */
abstract class SecretProviderMap<T> {

  private final Map<String, SecretProvider<T>> providers;

  protected SecretProviderMap() {
    providers = new HashMap<>();
  }

  /**
   * Finds an existing SecretProvider or creates it if it doesn't exist.
   *
   * @param configSource source part of the SdsSecretConfig
   * @param name name of the SdsSecretConfig
   * @return SecrerProvider
   */
  final synchronized SecretProvider<T> findOrCreate(
      ConfigSource configSource, String name) {
    checkNotNull(configSource, "configSource");
    checkNotNull(name, "name");

    String mapKey = "" + configSource.hashCode() + "." + name;
    SecretProvider<T> provider = providers.get(mapKey);
    if (provider == null) {
      provider = create(configSource, name);
      providers.put(mapKey, provider);
    }
    return provider;
  }

  abstract SecretProvider<T> create(ConfigSource configSource, String name);
}
