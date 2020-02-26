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

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Ascii;
import io.envoyproxy.envoy.api.v2.auth.CertificateValidationContext;
import java.net.Socket;
import java.security.cert.CertificateException;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import javax.annotation.Nullable;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.X509ExtendedTrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Extension of {@link X509ExtendedTrustManager} that implements verification of
 * SANs (subject-alternate-names) against the list in CertificateValidationContext.
 */
final class SdsX509TrustManager extends X509ExtendedTrustManager implements X509TrustManager {

  // ref: io.grpc.okhttp.internal.OkHostnameVerifier and
  // sun.security.x509.GeneralNameInterface
  private static final int ALT_DNS_NAME = 2;
  private static final int ALT_URI_NAME = 6;
  private static final int ALT_IPA_NAME = 7;

  private final X509ExtendedTrustManager delegate;
  private final CertificateValidationContext certContext;

  SdsX509TrustManager(@Nullable CertificateValidationContext certContext,
      X509ExtendedTrustManager delegate) {
    checkNotNull(delegate, "delegate");
    this.certContext = certContext;
    this.delegate = delegate;
  }

  // Copied from OkHostnameVerifier.verifyHostName().
  private static boolean verifyDnsNameInPattern(String pattern, String sanToVerify) {
    // Basic sanity checks
    // Check length == 0 instead of .isEmpty() to support Java 5.
    if (sanToVerify == null
        || sanToVerify.length() == 0
        || sanToVerify.startsWith(".")
        || sanToVerify.endsWith("..")) {
      // Invalid domain name
      return false;
    }
    if (pattern == null
        || pattern.length() == 0
        || pattern.startsWith(".")
        || pattern.endsWith("..")) {
      // Invalid pattern/domain name
      return false;
    }

    // Normalize sanToVerify and pattern by turning them into absolute domain names if they are not
    // yet absolute. This is needed because server certificates do not normally contain absolute
    // names or patterns, but they should be treated as absolute. At the same time, any sanToVerify
    // presented to this method should also be treated as absolute for the purposes of matching
    // to the server certificate.
    //   www.android.com  matches www.android.com
    //   www.android.com  matches www.android.com.
    //   www.android.com. matches www.android.com.
    //   www.android.com. matches www.android.com
    if (!sanToVerify.endsWith(".")) {
      sanToVerify += '.';
    }
    if (!pattern.endsWith(".")) {
      pattern += '.';
    }
    // sanToVerify and pattern are now absolute domain names.

    pattern = pattern.toLowerCase(Locale.US);
    // sanToVerify and pattern are now in lower case -- domain names are case-insensitive.

    if (!pattern.contains("*")) {
      // Not a wildcard pattern -- sanToVerify and pattern must match exactly.
      return sanToVerify.equals(pattern);
    }
    // Wildcard pattern

    // WILDCARD PATTERN RULES:
    // 1. Asterisk (*) is only permitted in the left-most domain name label and must be the
    //    only character in that label (i.e., must match the whole left-most label).
    //    For example, *.example.com is permitted, while *a.example.com, a*.example.com,
    //    a*b.example.com, a.*.example.com are not permitted.
    // 2. Asterisk (*) cannot match across domain name labels.
    //    For example, *.example.com matches test.example.com but does not match
    //    sub.test.example.com.
    // 3. Wildcard patterns for single-label domain names are not permitted.

    if (!pattern.startsWith("*.") || pattern.indexOf('*', 1) != -1) {
      // Asterisk (*) is only permitted in the left-most domain name label and must be the only
      // character in that label
      return false;
    }

    // Optimization: check whether sanToVerify is too short to match the pattern. sanToVerify must
    // be at
    // least as long as the pattern because asterisk must match the whole left-most label and
    // sanToVerify starts with a non-empty label. Thus, asterisk has to match one or more
    // characters.
    if (sanToVerify.length() < pattern.length()) {
      // sanToVerify too short to match the pattern.
      return false;
    }

    if ("*.".equals(pattern)) {
      // Wildcard pattern for single-label domain name -- not permitted.
      return false;
    }

    // sanToVerify must end with the region of pattern following the asterisk.
    String suffix = pattern.substring(1);
    if (!sanToVerify.endsWith(suffix)) {
      // sanToVerify does not end with the suffix
      return false;
    }

    // Check that asterisk did not match across domain name labels.
    int suffixStartIndexInHostName = sanToVerify.length() - suffix.length();
    // Asterisk is matching across domain name labels -- not permitted.
    return suffixStartIndexInHostName <= 0
        || sanToVerify.lastIndexOf('.', suffixStartIndexInHostName - 1) == -1;

    // sanToVerify matches pattern
  }

  private static boolean verifyDnsNameInSanList(String altNameFromCert,
                                                List<String> verifySanList) {
    for (String verifySan : verifySanList) {
      if (verifyDnsNameInPattern(altNameFromCert, verifySan)) {
        return true;
      }
    }
    return false;
  }

  /**
   * helper function for verifying URI or IP address. For now we compare IP addresses as strings
   * without any regard to IPv4 vs IPv6.
   *
   * @param stringFromCert either URI or IP address
   * @param verifySanList list of SANs from certificate context
   * @return true if there is a match
   */
  private static boolean verifyStringInSanList(String stringFromCert, List<String> verifySanList) {
    for (String sanToVerify : verifySanList) {
      if (Ascii.equalsIgnoreCase(sanToVerify, stringFromCert)) {
        return true;
      }
    }
    return false;
  }

  private static boolean verifyOneSanInList(List<?> entry, List<String> verifySanList)
      throws CertificateParsingException {
    // from OkHostnameVerifier.getSubjectAltNames
    if (entry == null || entry.size() < 2) {
      throw new CertificateParsingException("Invalid SAN entry");
    }
    Integer altNameType = (Integer) entry.get(0);
    if (altNameType == null) {
      throw new CertificateParsingException("Invalid SAN entry: null altNameType");
    }
    String altNameFromCert = (String) entry.get(1);
    switch (altNameType) {
      case ALT_DNS_NAME:
        return verifyDnsNameInSanList(altNameFromCert, verifySanList);
      case ALT_URI_NAME:
      case ALT_IPA_NAME:
        return verifyStringInSanList(altNameFromCert, verifySanList);
      default:
        throw new CertificateParsingException("Unsupported altNameType: " + altNameType);
    }
  }

  // logic from Envoy::Extensions::TransportSockets::Tls::ContextImpl::verifySubjectAltName
  private static void verifySubjectAltNameInLeaf(X509Certificate cert, List<String> verifyList)
      throws CertificateException {
    Collection<List<?>> names = cert.getSubjectAlternativeNames();
    if (names == null || names.isEmpty()) {
      throw new CertificateException("Peer certificate SAN check failed");
    }
    for (List<?> name : names) {
      if (verifyOneSanInList(name, verifyList)) {
        return;
      }
    }
    // at this point there's no match
    throw new CertificateException("Peer certificate SAN check failed");
  }

  /**
   * Verifies SANs in the peer cert chain against verify_subject_alt_name in the certContext.
   * This is called from various check*Trusted methods.
   */
  @VisibleForTesting
  void verifySubjectAltNameInChain(X509Certificate[] peerCertChain) throws CertificateException {
    if (certContext == null) {
      return;
    }
    List<String> verifyList = certContext.getVerifySubjectAltNameList();
    if (verifyList.isEmpty()) {
      return;
    }
    if (peerCertChain == null || peerCertChain.length < 1) {
      throw new CertificateException("Peer certificate(s) missing");
    }
    // verify SANs only in the top cert (leaf cert)
    verifySubjectAltNameInLeaf(peerCertChain[0], verifyList);
  }

  @Override
  public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket)
      throws CertificateException {
    delegate.checkClientTrusted(chain, authType, socket);
    verifySubjectAltNameInChain(chain);
  }

  @Override
  public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine sslEngine)
      throws CertificateException {
    delegate.checkClientTrusted(chain, authType, sslEngine);
    verifySubjectAltNameInChain(chain);
  }

  @Override
  public void checkClientTrusted(X509Certificate[] chain, String authType)
      throws CertificateException {
    delegate.checkClientTrusted(chain, authType);
    verifySubjectAltNameInChain(chain);
  }

  @Override
  public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket)
      throws CertificateException {
    if (socket instanceof SSLSocket) {
      SSLSocket sslSocket = (SSLSocket) socket;
      SSLParameters sslParams = sslSocket.getSSLParameters();
      if (sslParams != null) {
        sslParams.setEndpointIdentificationAlgorithm(null);
        sslSocket.setSSLParameters(sslParams);
      }
    }
    delegate.checkServerTrusted(chain, authType, socket);
    verifySubjectAltNameInChain(chain);
  }

  @Override
  public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine sslEngine)
      throws CertificateException {
    SSLParameters sslParams = sslEngine.getSSLParameters();
    if (sslParams != null) {
      sslParams.setEndpointIdentificationAlgorithm(null);
      sslEngine.setSSLParameters(sslParams);
    }
    delegate.checkServerTrusted(chain, authType, sslEngine);
    verifySubjectAltNameInChain(chain);
  }

  @Override
  public void checkServerTrusted(X509Certificate[] chain, String authType)
      throws CertificateException {
    delegate.checkServerTrusted(chain, authType);
    verifySubjectAltNameInChain(chain);
  }

  @Override
  public X509Certificate[] getAcceptedIssuers() {
    return delegate.getAcceptedIssuers();
  }
}
