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

import com.google.common.annotations.VisibleForTesting;
import io.grpc.xds.internal.certprovider.CertificateProvider.Watcher;
import io.grpc.xds.internal.sds.ReferenceCountingMap;

import java.io.Closeable;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.concurrent.ThreadSafe;

/**
 * Global map of all ref-counted {@link CertificateProvider}s that have been instantiated in
 * the application. Also  propagates updates received from a {@link CertificateProvider} to all
 * the {@link Watcher}s registered for that CertificateProvider. The Store is meant to be
 * used internally by gRPC and *not* a public API.
 */
@ThreadSafe
public final class CertificateProviderStore {
  private static final Logger logger = Logger.getLogger(CertificateProviderStore.class.getName());

  private static CertificateProviderStore instance;
  private final CertificateProviderRegistry certificateProviderRegistry;
  private final ReferenceCountingMap<CertProviderKey, CertificateProvider> certProviderMap;

  /** Opaque Handle returned by {@link #createOrGetProvider}. */
  @VisibleForTesting
  final class Handle implements Closeable {
    private final CertProviderKey key;
    private final Watcher watcher;
    @VisibleForTesting
    final CertificateProvider certProvider;

    private Handle(CertProviderKey key, Watcher watcher, CertificateProvider certProvider) {
      this.key = key;
      this.watcher = watcher;
      this.certProvider = certProvider;
    }

    /**
     * Removes the associated {@link Watcher} for the {@link CertificateProvider} and
     * decrements the ref-count. Releases the {@link CertificateProvider} if the ref-count
     * has reached 0.
     */
    @Override
    public synchronized void close() {
      CertificateProvider.DistributorWatcher distWatcher = certProvider.getWatcher();
      distWatcher.removeWatcher(watcher);
      certProviderMap.release(key, certProvider);
    }
  }

  private static final class CertProviderKey {
    private final String certName;
    private final String pluginName;
    private final boolean notifyCertUpdates;
    private final Object config;

    private CertProviderKey(
        String certName, String pluginName, boolean notifyCertUpdates, Object config) {
      this.certName = certName;
      this.pluginName = pluginName;
      this.notifyCertUpdates = notifyCertUpdates;
      this.config = config;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof CertProviderKey)) {
        return false;
      }
      CertProviderKey that = (CertProviderKey) o;
      return notifyCertUpdates == that.notifyCertUpdates
          && Objects.equals(certName, that.certName)
          && Objects.equals(pluginName, that.pluginName)
          && Objects.equals(config, that.config);
    }

    @Override
    public int hashCode() {
      return Objects.hash(certName, pluginName, notifyCertUpdates, config);
    }

    @Override
    public String toString() {
      return "CertProviderKey{"
          + "certName='"
          + certName
          + '\''
          + ", pluginName='"
          + pluginName
          + '\''
          + ", notifyCertUpdates="
          + notifyCertUpdates
          + ", config="
          + config
          + '}';
    }
  }

  private final class CertProviderFactory
      implements ReferenceCountingMap.ValueFactory<CertProviderKey, CertificateProvider> {

    private CertProviderFactory() {
    }

    @Override
    public CertificateProvider create(CertProviderKey key) {
      CertificateProviderProvider certProviderProvider =
          certificateProviderRegistry.getProvider(key.pluginName);
      if (certProviderProvider == null) {
        throw new IllegalArgumentException("Provider not found for " + key.pluginName);
      }
      CertificateProvider certProvider = certProviderProvider.createCertificateProvider(
              key.config, new CertificateProvider.DistributorWatcher(), key.notifyCertUpdates);
      certProvider.start();
      return certProvider;
    }
  }

  @VisibleForTesting
  public CertificateProviderStore(CertificateProviderRegistry certificateProviderRegistry) {
    this.certificateProviderRegistry = certificateProviderRegistry;
    certProviderMap = new ReferenceCountingMap<>(new CertProviderFactory());
  }

  /**
   * Creates or retrieves a {@link CertificateProvider} instance, increments its ref-count and
   * registers the watcher passed. Returns a {@link Handle} that can be {@link Handle#close()}d when
   * the instance is no longer needed by the caller.
   *
   * @param notifyCertUpdates when true, the caller is interested in identity cert updates. When
   *     false, the caller cannot depend on receiving the {@link Watcher#updateCertificate}
   *     callbacks but may still receive these callbacks which should be ignored.
   * @throws IllegalArgumentException in case of errors in processing config or the plugin is
   *     incapable of sending cert updates when notifyCertUpdates is true.
   * @throws UnsupportedOperationException if the plugin is incapable of sending cert updates when
   *     notifyCertUpdates is true.
   */
  public synchronized Handle createOrGetProvider(
      String certName,
      String pluginName,
      Object config,
      Watcher watcher,
      boolean notifyCertUpdates) {
    if (!notifyCertUpdates) {
      // we try to get a provider first for notifyCertUpdates==true always
      try {
        return createProviderHelper(certName, pluginName, config, watcher, true);
      } catch (UnsupportedOperationException uoe) {
        // ignore & log exception and fall thru to create a provider with actual value
        logger.log(Level.FINE, "Trying to get provider for notifyCertUpdates==true", uoe);
      }
    }
    return createProviderHelper(certName, pluginName, config, watcher, notifyCertUpdates);
  }

  private synchronized Handle createProviderHelper(
      String certName,
      String pluginName,
      Object config,
      Watcher watcher,
      boolean notifyCertUpdates) {
    CertProviderKey key = new CertProviderKey(certName, pluginName, notifyCertUpdates, config);
    CertificateProvider provider = certProviderMap.get(key);
    CertificateProvider.DistributorWatcher distWatcher = provider.getWatcher();
    distWatcher.addWatcher(watcher);
    return new Handle(key, watcher, provider);
  }

  /** Gets the CertificateProviderStore singleton instance. */
  public static synchronized CertificateProviderStore getInstance() {
    if (instance == null) {
      instance = new CertificateProviderStore(CertificateProviderRegistry.getInstance());
    }
    return instance;
  }
}
