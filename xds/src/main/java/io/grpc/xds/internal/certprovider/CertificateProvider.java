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
import io.grpc.Status;
import io.grpc.xds.internal.sds.Closeable;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A plug-in that provides certificates required by the xDS security component and created
 * using the certificate-provider config from the xDS server.
 *
 * <p>We may move this out of the internal package and make this an official API in the future.
 *
 * <p>The plugin fetches certificates - root and optionally identity cert - required by xDS
 * security.
 */
public abstract class CertificateProvider implements Closeable {

  /** A watcher is registered to receive certificate updates. */
  public interface Watcher {
    void updateCertificate(PrivateKey key, List<X509Certificate> certChain);

    void updateTrustedRoots(List<X509Certificate> trustedRoots);

    void onError(Status errorStatus);
  }

  @VisibleForTesting
  public static final class DistributorWatcher implements Watcher {
    private PrivateKey privateKey;
    private List<X509Certificate> certChain;
    private List<X509Certificate> trustedRoots;

    @VisibleForTesting
    final Set<Watcher> downstreamWatchers = new HashSet<>();

    synchronized void addWatcher(Watcher watcher) {
      downstreamWatchers.add(watcher);
      if (privateKey != null && certChain != null) {
        sendLastCertificateUpdate(watcher);
      }
      if (trustedRoots != null) {
        sendLastTrustedRootsUpdate(watcher);
      }
    }

    synchronized void removeWatcher(Watcher watcher) {
      downstreamWatchers.remove(watcher);
    }

    @VisibleForTesting public Set<Watcher> getDownstreamWatchers() {
      return Collections.unmodifiableSet(downstreamWatchers);
    }

    private void sendLastCertificateUpdate(Watcher watcher) {
      watcher.updateCertificate(privateKey, certChain);
    }

    private void sendLastTrustedRootsUpdate(Watcher watcher) {
      watcher.updateTrustedRoots(trustedRoots);
    }

    @Override
    public synchronized void updateCertificate(PrivateKey key, List<X509Certificate> certChain) {
      checkNotNull(key, "key");
      checkNotNull(certChain, "certChain");
      privateKey = key;
      this.certChain = certChain;
      for (Watcher watcher : downstreamWatchers) {
        sendLastCertificateUpdate(watcher);
      }
    }

    @Override
    public synchronized void updateTrustedRoots(List<X509Certificate> trustedRoots) {
      checkNotNull(trustedRoots, "trustedRoots");
      this.trustedRoots = trustedRoots;
      for (Watcher watcher : downstreamWatchers) {
        sendLastTrustedRootsUpdate(watcher);
      }
    }

    @Override
    public synchronized void onError(Status errorStatus) {
      for (Watcher watcher : downstreamWatchers) {
        watcher.onError(errorStatus);
      }
    }

    X509Certificate getLastIdentityCert() {
      if (certChain != null && !certChain.isEmpty()) {
        return certChain.get(0);
      }
      return null;
    }

    void close() {
      downstreamWatchers.clear();
      clearValues();
    }

    void clearValues() {
      privateKey = null;
      certChain = null;
      trustedRoots = null;
    }
  }

  /**
   * Concrete subclasses will call this to register the {@link Watcher}.
   *
   * @param watcher to register
   * @param notifyCertUpdates if true, the provider is required to call the watcher’s
   *     updateCertificate method. Implies the Provider is capable of minting certificates.
   *     Used by server-side and mTLS client-side. Note the Provider is always required
   *     to call updateTrustedRoots to provide trusted-root updates.
   */
  protected CertificateProvider(DistributorWatcher watcher, boolean notifyCertUpdates) {
    this.watcher = watcher;
    this.notifyCertUpdates = notifyCertUpdates;
  }

  /** Releases all resources and stop cert refreshes and watcher updates. */
  @Override
  public abstract void close();

  /** Starts the cert refresh and watcher update cycle. */
  public abstract void start();

  private final DistributorWatcher watcher;
  private final boolean notifyCertUpdates;

  public DistributorWatcher getWatcher() {
    return watcher;
  }

  public boolean isNotifyCertUpdates() {
    return notifyCertUpdates;
  }


}
