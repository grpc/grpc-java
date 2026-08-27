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

import io.grpc.Status;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * An non-registered provider for CertProviderSslContextProvider to use the same code path for
 * system root certs as provider-obtained certs.
 */
final class SystemRootCertificateProvider extends CertificateProvider {
  public SystemRootCertificateProvider(CertificateProvider.Watcher watcher) {
    super(new DistributorWatcher(), false);
    getWatcher().addWatcher(watcher);
  }

  @Override
  public void start() {
    try {
      TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(
          TrustManagerFactory.getDefaultAlgorithm());
      trustManagerFactory.init((KeyStore) null);

      List<TrustManager> trustManagers = Arrays.asList(trustManagerFactory.getTrustManagers());
      List<X509Certificate> rootCerts = trustManagers.stream()
          .filter(X509TrustManager.class::isInstance)
          .map(X509TrustManager.class::cast)
          .map(trustManager -> Arrays.asList(trustManager.getAcceptedIssuers()))
          .flatMap(Collection::stream)
          .collect(Collectors.toList());
      getWatcher().updateTrustedRoots(rootCerts);
    } catch (KeyStoreException | NoSuchAlgorithmException ex) {
      getWatcher().onError(Status.UNAVAILABLE
          .withDescription("Could not load system root certs")
          .withCause(ex));
    }
  }

  @Override
  public void close() {
    // Unnecessary because there's no more callbacks, but do it for good measure
    for (Watcher watcher : getWatcher().getDownstreamWatchers()) {
      getWatcher().removeWatcher(watcher);
    }
  }
}
