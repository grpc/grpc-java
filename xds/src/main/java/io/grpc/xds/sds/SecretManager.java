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
import io.grpc.Internal;

/**
 * SecretManager to manage secrets. This is used by gRPC-xds to access secrets
 * and not part of the public API of gRPC
 */
//  TODO(sanjaypujare): Lifecycle management for this manager and the {@link SecretProvider}s
//  to be implemented using an object-pool mechanism
@Internal
public final class SecretManager {

  /**
   * Finds an existing SecretProvider or creates it if it doesn't exist.
   *
   * @param configSource   from the SdsSecretConfig
   * @param name      from the SdsSecretConfig
   * @return    the SecretProvider
   */
  public SecretProvider<TlsCertificateStore> findOrCreateTlsCertificateProvider(
      ConfigSource configSource, String name) {
    checkNotNull(configSource, "configSource");
    checkNotNull(name, "name");
    // for now we support only path/volume based secret provider
    if (configSource.getConfigSourceSpecifierCase()
        != ConfigSource.ConfigSourceSpecifierCase.PATH) {
      throw new UnsupportedOperationException("Only file based secret supported");
    }
    return secretVolumeCertificateProviders.findOrCreate(configSource, name);
  }

  private final TlsCertificateSecretProviderMap secretVolumeCertificateProviders =
      new TlsCertificateSecretProviderMap();
}
