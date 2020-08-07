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

package io.grpc.xds.internal.sds.trust;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Collection;

/**
 * Contains certificate utility method(s).
 */
public final class CertificateUtils {

  private static CertificateFactory factory;

  private static synchronized void initInstance() throws CertificateException {
    if (factory == null) {
      factory = CertificateFactory.getInstance("X.509");
    }
  }

  /**
   * Generates X509Certificate array from a file on disk.
   *
   * @param file a {@link File} containing the cert data
   */
  static X509Certificate[] toX509Certificates(File file) throws CertificateException, IOException {
    try (FileInputStream fis = new FileInputStream(file);
        BufferedInputStream bis = new BufferedInputStream(fis)) {
      return toX509Certificates(bis);
    }
  }

  static synchronized X509Certificate[] toX509Certificates(InputStream inputStream)
      throws CertificateException, IOException {
    initInstance();
    Collection<? extends Certificate> certs = factory.generateCertificates(inputStream);
    return certs.toArray(new X509Certificate[0]);

  }

  /** See {@link CertificateFactory#generateCertificate(InputStream)}. */
  public static synchronized X509Certificate toX509Certificate(InputStream inputStream)
          throws CertificateException, IOException {
    initInstance();
    Certificate cert = factory.generateCertificate(inputStream);
    return (X509Certificate) cert;
  }

  private CertificateUtils() {}
}
