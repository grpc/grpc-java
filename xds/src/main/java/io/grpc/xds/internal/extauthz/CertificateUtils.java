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

package io.grpc.xds.internal.extauthz;

import com.google.common.io.BaseEncoding;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A utility class for certificate-related information.
 */
public final class CertificateUtils {
  private static final Logger logger = Logger.getLogger(CertificateUtils.class.getName());
  // From RFC 5280, section 4.2.1.6, Subject Alternative Name
  // dNSName (2)
  // iPAddress (7)
  private static final int SAN_TYPE_DNS_NAME = 2;
  private static final int SAN_TYPE_URI = 6;

  private CertificateUtils() {}

  /**
   * Gets the principal from a certificate. It returns the cert's first URI SAN if set,
   * otherwise the cert's first DNS SAN if set, otherwise the subject field of the certificate in
   * RFC 2253 format.
   *
   * @param cert The certificate.
   * @return The principal.
   */
  public static String getPrincipal(X509Certificate cert) {
    try {
      Collection<List<?>> sans = cert.getSubjectAlternativeNames();
      if (sans != null) {
        // Look for URI SAN (Priority 1).
        for (List<?> san : sans) {
          if (san.size() == 2 && san.get(0) instanceof Integer
              && (Integer) san.get(0) == SAN_TYPE_URI) {
            return (String) san.get(1);
          }
        }
        // If no URI SAN, look for DNS SAN (Priority 2).
        for (List<?> san : sans) {
          if (san.size() == 2 && san.get(0) instanceof Integer
              && (Integer) san.get(0) == SAN_TYPE_DNS_NAME) {
            return (String) san.get(1);
          }
        }
      }
    } catch (java.security.cert.CertificateParsingException e) {
      logger.log(Level.FINE, "Error parsing certificate SANs.", e);
    }
    return cert.getSubjectX500Principal().getName("RFC2253");
  }


  /**
   * Gets the URL PEM encoded certificate. It Pem encodes first and then urlencodes.
   *
   * @param cert The certificate.
   * @return The URL PEM encoded certificate.
   * @throws CertificateEncodingException If an error occurs while encoding the certificate.
   * @throws UnsupportedEncodingException If an error occurs while encoding the URL.
   */
  public static String getUrlPemEncodedCertificate(X509Certificate cert)
      throws CertificateEncodingException, UnsupportedEncodingException {
    String pemCert = CertPemConverter.toPem(cert);
    return URLEncoder.encode(pemCert, StandardCharsets.UTF_8.toString());
  }

  /**
   * A utility class for PEM encoding.
   */
  private static final class CertPemConverter {

    private static final String X509_PEM_HEADER = "-----BEGIN CERTIFICATE-----\n";
    private static final String X509_PEM_FOOTER = "\n-----END CERTIFICATE-----\n";

    private CertPemConverter() {}

    /**
     * Converts a certificate to a PEM string.
     *
     * @param cert The certificate to convert.
     * @return The PEM encoded certificate.
     * @throws CertificateEncodingException If an error occurs while encoding the certificate.
     */
    public static String toPem(X509Certificate cert) throws CertificateEncodingException {
      return X509_PEM_HEADER + BaseEncoding.base64().encode(cert.getEncoded()) + X509_PEM_FOOTER;
    }
  }
}
