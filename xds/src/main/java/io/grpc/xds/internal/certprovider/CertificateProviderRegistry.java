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

package io.grpc.xds.internal.certprovider;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.annotations.VisibleForTesting;
import java.util.LinkedHashMap;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;

/** Maintains {@link CertificateProvider}s for all registered plugins. */
@ThreadSafe
public final class CertificateProviderRegistry {
  private static CertificateProviderRegistry instance;
  private final LinkedHashMap<String, CertificateProviderProvider> providers =
      new LinkedHashMap<>();

  @VisibleForTesting
  public CertificateProviderRegistry() {
  }

  /** Returns the singleton registry. */
  public static synchronized CertificateProviderRegistry getInstance() {
    if (instance == null) {
      instance = new CertificateProviderRegistry();
      // TODO(sanjaypujare): replace with Java's SPI mechanism and META-INF resource
      instance.register(new FileWatcherCertificateProviderProvider());
      instance.register(new DynamicReloadingCertificateProviderProvider());
      instance.register(new MeshCaCertificateProviderProvider());
    }
    return instance;
  }

  /**
   * Register a {@link CertificateProviderProvider}.
   *
   * <p>If a provider with the same {@link CertificateProviderProvider#getName name} was already
   * registered, this method will overwrite that provider.
   */
  public synchronized void register(CertificateProviderProvider certificateProviderProvider) {
    checkNotNull(certificateProviderProvider, "certificateProviderProvider");
    providers.put(certificateProviderProvider.getName(), certificateProviderProvider);
  }

  /**
   * Deregisters a provider.  No-op if the provider is not in the registry.
   *
   * @param certificateProviderProvider the provider that was added to the registry via
   * {@link #register}.
   */
  public synchronized void deregister(CertificateProviderProvider certificateProviderProvider) {
    checkNotNull(certificateProviderProvider, "certificateProviderProvider");
    providers.remove(certificateProviderProvider.getName());
  }

  /**
   * Returns the CertificateProviderProvider for the given name, or {@code null} if no
   * provider is found.  Each provider declares its name via {@link
   * CertificateProviderProvider#getName}. This is an internal method of the Registry
   * *only* used by the framework.
   */
  @Nullable
  synchronized CertificateProviderProvider getProvider(String name) {
    return providers.get(checkNotNull(name, "name"));
  }
}
