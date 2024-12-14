/*
 * Copyright 2024 The gRPC Authors
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

package io.grpc.internal;

import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.security.auth.x500.X500Principal;

/**
 * Contains certificate/key PEM file utility method(s) for internal usage.
 */
public class CertificateUtils {
  /**
   * Creates a X509TrustManagers using the provided CA certs.
   */
  public static List<TrustManager> getTrustManagers(InputStream rootCerts)
      throws GeneralSecurityException {
    KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
    try {
      ks.load(null, null);
    } catch (IOException ex) {
      // Shouldn't really happen, as we're not loading any data.
      throw new GeneralSecurityException(ex);
    }
    X509Certificate[] certs = CertificateUtils.getX509Certificates(rootCerts);
    for (X509Certificate cert : certs) {
      X500Principal principal = cert.getSubjectX500Principal();
      ks.setCertificateEntry(principal.getName("RFC2253"), cert);
    }

    TrustManagerFactory trustManagerFactory =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    trustManagerFactory.init(ks);
    return Arrays.asList(trustManagerFactory.getTrustManagers());
  }

  private static X509Certificate[] getX509Certificates(InputStream inputStream)
      throws CertificateException {
    CertificateFactory factory = CertificateFactory.getInstance("X.509");
    Collection<? extends Certificate> certs = factory.generateCertificates(inputStream);
    return certs.toArray(new X509Certificate[0]);
  }
}
