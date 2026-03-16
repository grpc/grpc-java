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

package io.grpc.xds.internal.security.certprovider;

import static java.util.Objects.requireNonNull;

import com.google.common.annotations.VisibleForTesting;
import io.grpc.Status;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Map;

public final class IgnoreUpdatesWatcher implements CertificateProvider.Watcher {
  private final CertificateProvider.Watcher delegate;
  private final boolean ignoreRootCertUpdates;

  public IgnoreUpdatesWatcher(
      CertificateProvider.Watcher delegate, boolean ignoreRootCertUpdates) {
    this.delegate = requireNonNull(delegate, "delegate");
    this.ignoreRootCertUpdates = ignoreRootCertUpdates;
  }

  @Override
  public void updateCertificate(PrivateKey key, List<X509Certificate> certChain) {
    if (ignoreRootCertUpdates) {
      delegate.updateCertificate(key, certChain);
    }
  }

  @Override
  public void updateTrustedRoots(List<X509Certificate> trustedRoots) {
    if (!ignoreRootCertUpdates) {
      delegate.updateTrustedRoots(trustedRoots);
    }
  }

  @Override
  public void updateSpiffeTrustMap(Map<String, List<X509Certificate>> spiffeTrustMap) {
    if (!ignoreRootCertUpdates) {
      delegate.updateSpiffeTrustMap(spiffeTrustMap);
    }
  }

  @Override
  public void onError(Status errorStatus) {
    delegate.onError(errorStatus);
  }

  @VisibleForTesting
  public CertificateProvider.Watcher getDelegate() {
    return delegate;
  }
}
