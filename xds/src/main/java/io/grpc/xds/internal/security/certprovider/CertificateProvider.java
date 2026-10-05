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

package io.grpc.xds.internal.security.certprovider;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSet;
import com.google.errorprone.annotations.concurrent.GuardedBy;
import io.grpc.Status;
import io.grpc.SynchronizationContext;
import io.grpc.xds.internal.security.Closeable;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

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

  private static final Logger logger = Logger.getLogger(CertificateProvider.class.getName());

  /** A watcher is registered to receive certificate updates. */
  public interface Watcher {
    void updateCertificate(PrivateKey key, List<X509Certificate> certChain);

    void updateTrustedRoots(List<X509Certificate> trustedRoots);

    void updateSpiffeTrustMap(Map<String, List<X509Certificate>> spiffeTrustMap);

    void onError(Status errorStatus);
  }

  @VisibleForTesting
  public static final class DistributorWatcher implements Watcher {
    // All watcher callbacks must run in syncContext to avoid possibility of deadlock
    private final SynchronizationContext syncContext = new SynchronizationContext(
        new Thread.UncaughtExceptionHandler() {
          @Override
          public void uncaughtException(Thread t, Throwable e) {
            logger.log(Level.SEVERE, "Uncaught exception in DistributorWatcher callback", e);
          }
        });

    @GuardedBy("this") private PrivateKey privateKey;
    @GuardedBy("this") private List<X509Certificate> certChain;
    @GuardedBy("this") private List<X509Certificate> trustedRoots;
    @GuardedBy("this") private Map<String, List<X509Certificate>> spiffeTrustMap;

    @GuardedBy("this")
    private final Set<Watcher> downstreamWatchers = new HashSet<>();

    void addWatcher(Watcher watcher) {
      synchronized (this) {
        downstreamWatchers.add(watcher);
        if (privateKey != null && certChain != null) {
          PrivateKey key = privateKey;
          List<X509Certificate> chain = certChain;
          syncContext.executeLater(() -> watcher.updateCertificate(key, chain));
        }
        if (trustedRoots != null) {
          List<X509Certificate> roots = trustedRoots;
          syncContext.executeLater(() -> watcher.updateTrustedRoots(roots));
        }
        if (spiffeTrustMap != null) {
          Map<String, List<X509Certificate>> map = spiffeTrustMap;
          syncContext.executeLater(() -> watcher.updateSpiffeTrustMap(map));
        }
      }
      syncContext.drain();
    }

    synchronized void removeWatcher(Watcher watcher) {
      downstreamWatchers.remove(watcher);
    }

    @VisibleForTesting public synchronized Set<Watcher> getDownstreamWatchers() {
      return ImmutableSet.copyOf(downstreamWatchers);
    }

    @Override
    public void updateCertificate(PrivateKey key, List<X509Certificate> certChain) {
      checkNotNull(key, "key");
      checkNotNull(certChain, "certChain");
      synchronized (this) {
        privateKey = key;
        this.certChain = certChain;
        for (Watcher watcher : downstreamWatchers) {
          syncContext.executeLater(() -> watcher.updateCertificate(key, certChain));
        }
      }
      syncContext.drain();
    }

    @Override
    public void updateTrustedRoots(List<X509Certificate> trustedRoots) {
      checkNotNull(trustedRoots, "trustedRoots");
      synchronized (this) {
        this.trustedRoots = trustedRoots;
        for (Watcher watcher : downstreamWatchers) {
          syncContext.executeLater(() -> watcher.updateTrustedRoots(trustedRoots));
        }
      }
      syncContext.drain();
    }

    @Override
    public void updateSpiffeTrustMap(Map<String, List<X509Certificate>> spiffeTrustMap) {
      synchronized (this) {
        this.spiffeTrustMap = spiffeTrustMap;
        for (Watcher watcher : downstreamWatchers) {
          syncContext.executeLater(() -> watcher.updateSpiffeTrustMap(spiffeTrustMap));
        }
      }
      syncContext.drain();
    }

    @Override
    public void onError(Status errorStatus) {
      List<Watcher> watchers;
      synchronized (this) {
        watchers = new ArrayList<>(downstreamWatchers);
      }
      for (Watcher watcher : watchers) {
        syncContext.executeLater(() -> watcher.onError(errorStatus));
      }
      syncContext.drain();
    }

    synchronized X509Certificate getLastIdentityCert() {
      if (certChain != null && !certChain.isEmpty()) {
        return certChain.get(0);
      }
      return null;
    }

    synchronized void close() {
      downstreamWatchers.clear();
      clearValues();
    }

    synchronized void clearValues() {
      privateKey = null;
      certChain = null;
      trustedRoots = null;
    }
  }

  /**
   * Concrete subclasses will call this to register the {@link Watcher}.
   *
   * @param watcher to register
   * @param notifyCertUpdates if true, the provider is required to call the watcher's
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

  /** Starts the async cert refresh and watcher update cycle. */
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
