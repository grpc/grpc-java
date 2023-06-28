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

package io.grpc.xds.internal.security.trust;

import static com.google.common.truth.Truth.assertThat;
import static io.grpc.xds.internal.security.CommonTlsContextTestsUtil.BAD_SERVER_PEM_FILE;
import static io.grpc.xds.internal.security.CommonTlsContextTestsUtil.CA_PEM_FILE;
import static io.grpc.xds.internal.security.CommonTlsContextTestsUtil.CLIENT_PEM_FILE;
import static io.grpc.xds.internal.security.CommonTlsContextTestsUtil.SERVER_1_PEM_FILE;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.CertificateValidationContext;
import io.envoyproxy.envoy.type.matcher.v3.RegexMatcher;
import io.envoyproxy.envoy.type.matcher.v3.StringMatcher;
import io.grpc.testing.TlsTesting;
import java.io.IOException;
import java.security.cert.CertStoreException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.List;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.X509ExtendedTrustManager;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/**
 * Unit tests for {@link XdsX509TrustManager}.
 */
@RunWith(JUnit4.class)
public class XdsX509TrustManagerTest {

  @Rule
  public final MockitoRule mockitoRule = MockitoJUnit.rule();

  @Mock
  private X509ExtendedTrustManager mockDelegate;

  @Mock
  private SSLSession mockSession;

  private XdsX509TrustManager trustManager;

  @Test
  public void nullCertContextTest() throws CertificateException, IOException {
    trustManager = new XdsX509TrustManager(null, mockDelegate);
    X509Certificate[] certs =
        CertificateUtils.toX509Certificates(TlsTesting.loadCert(SERVER_1_PEM_FILE));
    trustManager.verifySubjectAltNameInChain(certs);
  }

  @Test
  public void emptySanListContextTest() throws CertificateException, IOException {
    CertificateValidationContext certContext = CertificateValidationContext.getDefaultInstance();
    trustManager = new XdsX509TrustManager(certContext, mockDelegate);
    X509Certificate[] certs =
        CertificateUtils.toX509Certificates(TlsTesting.loadCert(SERVER_1_PEM_FILE));
    trustManager.verifySubjectAltNameInChain(certs);
  }

  @Test
  public void missingPeerCerts() {
    StringMatcher stringMatcher = StringMatcher.newBuilder().setExact("foo.com").build();
    @SuppressWarnings("deprecation")
    CertificateValidationContext certContext =
        CertificateValidationContext.newBuilder().addMatchSubjectAltNames(stringMatcher).build();
    trustManager = new XdsX509TrustManager(certContext, mockDelegate);
    try {
      trustManager.verifySubjectAltNameInChain(null);
      fail("no exception thrown");
    } catch (CertificateException expected) {
      assertThat(expected).hasMessageThat().isEqualTo("Peer certificate(s) missing");
    }
  }

  @Test
  public void emptyArrayPeerCerts() {
    StringMatcher stringMatcher = StringMatcher.newBuilder().setExact("foo.com").build();
    @SuppressWarnings("deprecation")
    CertificateValidationContext certContext =
        CertificateValidationContext.newBuilder().addMatchSubjectAltNames(stringMatcher).build();
    trustManager = new XdsX509TrustManager(certContext, mockDelegate);
    try {
      trustManager.verifySubjectAltNameInChain(new X509Certificate[0]);
      fail("no exception thrown");
    } catch (CertificateException expected) {
      assertThat(expected).hasMessageThat().isEqualTo("Peer certificate(s) missing");
    }
  }

  @Test
  public void noSansInPeerCerts() throws CertificateException, IOException {
    StringMatcher stringMatcher = StringMatcher.newBuilder().setExact("foo.com").build();
    @SuppressWarnings("deprecation")
    CertificateValidationContext certContext =
        CertificateValidationContext.newBuilder().addMatchSubjectAltNames(stringMatcher).build();
    trustManager = new XdsX509TrustManager(certContext, mockDelegate);
    X509Certificate[] certs =
        CertificateUtils.toX509Certificates(TlsTesting.loadCert(CLIENT_PEM_FILE));
    try {
      trustManager.verifySubjectAltNameInChain(certs);
      fail("no exception thrown");
    } catch (CertificateException expected) {
      assertThat(expected).hasMessageThat().isEqualTo("Peer certificate SAN check failed");
    }
  }

  @Test
  public void oneSanInPeerCertsVerifies() throws CertificateException, IOException {
    StringMatcher stringMatcher =
        StringMatcher.newBuilder()
            .setExact("waterzooi.test.google.be")
            .setIgnoreCase(false)
            .build();
    @SuppressWarnings("deprecation")
    CertificateValidationContext certContext =
        CertificateValidationContext.newBuilder().addMatchSubjectAltNames(stringMatcher).build();
    trustManager = new XdsX509TrustManager(certContext, mockDelegate);
    X509Certificate[] certs =
        CertificateUtils.toX509Certificates(TlsTesting.loadCert(SERVER_1_PEM_FILE));
    trustManager.verifySubjectAltNameInChain(certs);
  }

  @Test
  public void oneSanInPeerCertsVerifies_differentCase_expectException()
      throws CertificateException, IOException {
    StringMatcher stringMatcher =
        StringMatcher.newBuilder()
            .setExact("waterZooi.test.Google.be")
            .setIgnoreCase(false)
            .build();
    @SuppressWarnings("deprecation")
    CertificateValidationContext certContext =
        CertificateValidationContext.newBuilder().addMatchSubjectAltNames(stringMatcher).build();
    trustManager = new XdsX509TrustManager(certContext, mockDelegate);
    X509Certificate[] certs =
        CertificateUtils.toX509Certificates(TlsTesting.loadCert(SERVER_1_PEM_FILE));
    try {
      trustManager.verifySubjectAltNameInChain(certs);
      fail("no exception thrown");
    } catch (CertificateException expected) {
      assertThat(expected).hasMessageThat().isEqualTo("Peer certificate SAN check failed");
    }
  }

  @Test
  public void oneSanInPeerCertsVerifies_ignoreCase() throws CertificateException, IOException {
    StringMatcher stringMatcher =
        StringMatcher.newBuilder().setExact("Waterzooi.Test.google.be").setIgnoreCase(true).build();
    @SuppressWarnings("deprecation")
    CertificateValidationContext certContext =
        CertificateValidationContext.newBuilder().addMatchSubjectAltNames(stringMatcher).build();
    trustManager = new XdsX509TrustManager(certContext, mockDelegate);
    X509Certificate[] certs =
        CertificateUtils.toX509Certificates(TlsTesting.loadCert(SERVER_1_PEM_FILE));
    trustManager.verifySubjectAltNameInChain(certs);
  }

  @Test
  public void oneSanInPeerCerts_prefix() throws CertificateException, IOException {
    StringMatcher stringMatcher =
        StringMatcher.newBuilder()
            .setPrefix("waterzooi.") // test.google.be
            .setIgnoreCase(false)
            .build();
    @SuppressWarnings("deprecation")
    CertificateValidationContext certContext =
        CertificateValidationContext.newBuilder().addMatchSubjectAltNames(stringMatcher).build();
    trustManager = new XdsX509TrustManager(certContext, mockDelegate);
    X509Certificate[] certs =
        CertificateUtils.toX509Certificates(TlsTesting.loadCert(SERVER_1_PEM_FILE));
    trustManager.verifySubjectAltNameInChain(certs);
  }

  @Test
  public void oneSanInPeerCertsPrefix_differentCase_expectException()
      throws CertificateException, IOException {
    StringMatcher stringMatcher =
        StringMatcher.newBuilder().setPrefix("waterZooi.").setIgnoreCase(false).build();
    @SuppressWarnings("deprecation")
    CertificateValidationContext certContext =
        CertificateValidationContext.newBuilder().addMatchSubjectAltNames(stringMatcher).build();
    trustManager = new XdsX509TrustManager(certContext, mockDelegate);
    X509Certificate[] certs =
        CertificateUtils.toX509Certificates(TlsTesting.loadCert(SERVER_1_PEM_FILE));
    try {
      trustManager.verifySubjectAltNameInChain(certs);
      fail("no exception thrown");
    } catch (CertificateException expected) {
      assertThat(expected).hasMessageThat().isEqualTo("Peer certificate SAN check failed");
    }
  }

  @Test
  public void oneSanInPeerCerts_prefixIgnoreCase() throws CertificateException, IOException {
    StringMatcher stringMatcher =
        StringMatcher.newBuilder()
            .setPrefix("WaterZooi.") // test.google.be
            .setIgnoreCase(true)
            .build();
    @SuppressWarnings("deprecation")
    CertificateValidationContext certContext =
        CertificateValidationContext.newBuilder().addMatchSubjectAltNames(stringMatcher).build();
    trustManager = new XdsX509TrustManager(certContext, mockDelegate);
    X509Certificate[] certs =
        CertificateUtils.toX509Certificates(TlsTesting.loadCert(SERVER_1_PEM_FILE));
    trustManager.verifySubjectAltNameInChain(certs);
  }

  @Test
  public void oneSanInPeerCerts_suffix() throws CertificateException, IOException {
    StringMatcher stringMatcher =
        StringMatcher.newBuilder().setSuffix(".google.be").setIgnoreCase(false).build();
    @SuppressWarnings("deprecation")
    CertificateValidationContext certContext =
        CertificateValidationContext.newBuilder().addMatchSubjectAltNames(stringMatcher).build();
    trustManager = new XdsX509TrustManager(certContext, mockDelegate);
    X509Certificate[] certs =
        CertificateUtils.toX509Certificates(TlsTesting.loadCert(SERVER_1_PEM_FILE));
    trustManager.verifySubjectAltNameInChain(certs);
  }

  @Test
  public void oneSanInPeerCertsSuffix_differentCase_expectException()
      throws CertificateException, IOException {
    StringMatcher stringMatcher =
        StringMatcher.newBuilder().setSuffix(".gooGle.bE").setIgnoreCase(false).build();
    @SuppressWarnings("deprecation")
    CertificateValidationContext certContext =
        CertificateValidationContext.newBuilder().addMatchSubjectAltNames(stringMatcher).build();
    trustManager = new XdsX509TrustManager(certContext, mockDelegate);
    X509Certificate[] certs =
        CertificateUtils.toX509Certificates(TlsTesting.loadCert(SERVER_1_PEM_FILE));
    try {
      trustManager.verifySubjectAltNameInChain(certs);
      fail("no exception thrown");
    } catch (CertificateException expected) {
      assertThat(expected).hasMessageThat().isEqualTo("Peer certificate SAN check failed");
    }
  }

  @Test
  public void oneSanInPeerCerts_suffixIgnoreCase() throws CertificateException, IOException {
    StringMatcher stringMatcher =
        StringMatcher.newBuilder().setSuffix(".GooGle.BE").setIgnoreCase(true).build();
    @SuppressWarnings("deprecation")
    CertificateValidationContext certContext =
        CertificateValidationContext.newBuilder().addMatchSubjectAltNames(stringMatcher).build();
    trustManager = new XdsX509TrustManager(certContext, mockDelegate);
    X509Certificate[] certs =
        CertificateUtils.toX509Certificates(TlsTesting.loadCert(SERVER_1_PEM_FILE));
    trustManager.verifySubjectAltNameInChain(certs);
  }

  @Test
  public void oneSanInPeerCerts_substring() throws CertificateException, IOException {
    StringMatcher stringMatcher =
        StringMatcher.newBuilder().setContains("zooi.test.google").setIgnoreCase(false).build();
    @SuppressWarnings("deprecation")
    CertificateValidationContext certContext =
        CertificateValidationContext.newBuilder().addMatchSubjectAltNames(stringMatcher).build();
    trustManager = new XdsX509TrustManager(certContext, mockDelegate);
    X509Certificate[] certs =
        CertificateUtils.toX509Certificates(TlsTesting.loadCert(SERVER_1_PEM_FILE));
    trustManager.verifySubjectAltNameInChain(certs);
  }

  @Test
  public void oneSanInPeerCertsSubstring_differentCase_expectException()
      throws CertificateException, IOException {
    StringMatcher stringMatcher =
        StringMatcher.newBuilder().setContains("zooi.Test.gooGle").setIgnoreCase(false).build();
    @SuppressWarnings("deprecation")
    CertificateValidationContext certContext =
        CertificateValidationContext.newBuilder().addMatchSubjectAltNames(stringMatcher).build();
    trustManager = new XdsX509TrustManager(certContext, mockDelegate);
    X509Certificate[] certs =
        CertificateUtils.toX509Certificates(TlsTesting.loadCert(SERVER_1_PEM_FILE));
    try {
      trustManager.verifySubjectAltNameInChain(certs);
      fail("no exception thrown");
    } catch (CertificateException expected) {
      assertThat(expected).hasMessageThat().isEqualTo("Peer certificate SAN check failed");
    }
  }

  @Test
  public void oneSanInPeerCerts_substringIgnoreCase() throws CertificateException, IOException {
    StringMatcher stringMatcher =
        StringMatcher.newBuilder().setContains("zooI.Test.Google").setIgnoreCase(true).build();
    @SuppressWarnings("deprecation")
    CertificateValidationContext certContext =
        CertificateValidationContext.newBuilder().addMatchSubjectAltNames(stringMatcher).build();
    trustManager = new XdsX509TrustManager(certContext, mockDelegate);
    X509Certificate[] certs =
        CertificateUtils.toX509Certificates(TlsTesting.loadCert(SERVER_1_PEM_FILE));
    trustManager.verifySubjectAltNameInChain(certs);
  }

  @Test
  public void oneSanInPeerCerts_safeRegex() throws CertificateException, IOException {
    StringMatcher stringMatcher =
        StringMatcher.newBuilder()
            .setSafeRegex(
                RegexMatcher.newBuilder().setRegex("water[[:alpha:]]{1}ooi\\.test\\.google\\.be"))
            .build();
    @SuppressWarnings("deprecation")
    CertificateValidationContext certContext =
        CertificateValidationContext.newBuilder().addMatchSubjectAltNames(stringMatcher).build();
    trustManager = new XdsX509TrustManager(certContext, mockDelegate);
    X509Certificate[] certs =
        CertificateUtils.toX509Certificates(TlsTesting.loadCert(SERVER_1_PEM_FILE));
    trustManager.verifySubjectAltNameInChain(certs);
  }

  @Test
  public void oneSanInPeerCerts_safeRegex1() throws CertificateException, IOException {
    StringMatcher stringMatcher =
        StringMatcher.newBuilder()
            .setSafeRegex(
                RegexMatcher.newBuilder().setRegex("no-match-string|\\*\\.test\\.youtube\\.com"))
            .build();
    @SuppressWarnings("deprecation")
    CertificateValidationContext certContext =
        CertificateValidationContext.newBuilder().addMatchSubjectAltNames(stringMatcher).build();
    trustManager = new XdsX509TrustManager(certContext, mockDelegate);
    X509Certificate[] certs =
        CertificateUtils.toX509Certificates(TlsTesting.loadCert(SERVER_1_PEM_FILE));
    trustManager.verifySubjectAltNameInChain(certs);
  }

  @Test
  public void oneSanInPeerCerts_safeRegex_ipAddress() throws CertificateException, IOException {
    StringMatcher stringMatcher =
        StringMatcher.newBuilder()
            .setSafeRegex(
                RegexMatcher.newBuilder().setRegex("([[:digit:]]{1,3}\\.){3}[[:digit:]]{1,3}"))
            .build();
    @SuppressWarnings("deprecation")
    CertificateValidationContext certContext =
        CertificateValidationContext.newBuilder().addMatchSubjectAltNames(stringMatcher).build();
    trustManager = new XdsX509TrustManager(certContext, mockDelegate);
    X509Certificate[] certs =
        CertificateUtils.toX509Certificates(TlsTesting.loadCert(SERVER_1_PEM_FILE));
    trustManager.verifySubjectAltNameInChain(certs);
  }

  @Test
  public void oneSanInPeerCerts_safeRegex_noMatch() throws CertificateException, IOException {
    StringMatcher stringMatcher =
        StringMatcher.newBuilder()
            .setSafeRegex(
                RegexMatcher.newBuilder().setRegex("water[[:alpha:]]{2}ooi\\.test\\.google\\.be"))
            .build();
    @SuppressWarnings("deprecation")
    CertificateValidationContext certContext =
        CertificateValidationContext.newBuilder().addMatchSubjectAltNames(stringMatcher).build();
    trustManager = new XdsX509TrustManager(certContext, mockDelegate);
    X509Certificate[] certs =
        CertificateUtils.toX509Certificates(TlsTesting.loadCert(SERVER_1_PEM_FILE));
    try {
      trustManager.verifySubjectAltNameInChain(certs);
      fail("no exception thrown");
    } catch (CertificateException expected) {
      assertThat(expected).hasMessageThat().isEqualTo("Peer certificate SAN check failed");
    }
  }

  @Test
  public void oneSanInPeerCertsVerifiesMultipleVerifySans()
          throws CertificateException, IOException {
    StringMatcher stringMatcher = StringMatcher.newBuilder().setExact("x.foo.com").build();
    StringMatcher stringMatcher1 =
        StringMatcher.newBuilder().setExact("waterzooi.test.google.be").build();
    @SuppressWarnings("deprecation")
    CertificateValidationContext certContext =
        CertificateValidationContext.newBuilder()
            .addMatchSubjectAltNames(stringMatcher)
            .addMatchSubjectAltNames(stringMatcher1)
            .build();
    trustManager = new XdsX509TrustManager(certContext, mockDelegate);
    X509Certificate[] certs =
        CertificateUtils.toX509Certificates(TlsTesting.loadCert(SERVER_1_PEM_FILE));
    trustManager.verifySubjectAltNameInChain(certs);
  }

  @Test
  public void oneSanInPeerCertsNotFoundException()
          throws CertificateException, IOException {
    StringMatcher stringMatcher = StringMatcher.newBuilder().setExact("x.foo.com").build();
    @SuppressWarnings("deprecation")
    CertificateValidationContext certContext =
        CertificateValidationContext.newBuilder().addMatchSubjectAltNames(stringMatcher).build();
    trustManager = new XdsX509TrustManager(certContext, mockDelegate);
    X509Certificate[] certs =
        CertificateUtils.toX509Certificates(TlsTesting.loadCert(SERVER_1_PEM_FILE));
    try {
      trustManager.verifySubjectAltNameInChain(certs);
      fail("no exception thrown");
    } catch (CertificateException expected) {
      assertThat(expected).hasMessageThat().isEqualTo("Peer certificate SAN check failed");
    }
  }

  @Test
  public void wildcardSanInPeerCertsVerifiesMultipleVerifySans()
          throws CertificateException, IOException {
    StringMatcher stringMatcher = StringMatcher.newBuilder().setExact("x.foo.com").build();
    StringMatcher stringMatcher1 =
        StringMatcher.newBuilder().setSuffix("test.youTube.Com").setIgnoreCase(true).build();
    @SuppressWarnings("deprecation")
    CertificateValidationContext certContext =
        CertificateValidationContext.newBuilder()
            .addMatchSubjectAltNames(stringMatcher)
            .addMatchSubjectAltNames(stringMatcher1) // should match suffix test.youTube.Com
            .build();
    trustManager = new XdsX509TrustManager(certContext, mockDelegate);
    X509Certificate[] certs =
        CertificateUtils.toX509Certificates(TlsTesting.loadCert(SERVER_1_PEM_FILE));
    trustManager.verifySubjectAltNameInChain(certs);
  }

  @Test
  public void wildcardSanInPeerCertsVerifiesMultipleVerifySans1()
          throws CertificateException, IOException {
    StringMatcher stringMatcher = StringMatcher.newBuilder().setExact("x.foo.com").build();
    StringMatcher stringMatcher1 =
        StringMatcher.newBuilder().setContains("est.Google.f").setIgnoreCase(true).build();
    @SuppressWarnings("deprecation")
    CertificateValidationContext certContext =
        CertificateValidationContext.newBuilder()
            .addMatchSubjectAltNames(stringMatcher)
            .addMatchSubjectAltNames(stringMatcher1) // should contain est.Google.f
            .build();
    trustManager = new XdsX509TrustManager(certContext, mockDelegate);
    X509Certificate[] certs =
        CertificateUtils.toX509Certificates(TlsTesting.loadCert(SERVER_1_PEM_FILE));
    trustManager.verifySubjectAltNameInChain(certs);
  }

  @Test
  public void wildcardSanInPeerCertsSubdomainMismatch()
          throws CertificateException, IOException {
    // 2. Asterisk (*) cannot match across domain name labels.
    //    For example, *.example.com matches test.example.com but does not match
    //    sub.test.example.com.
    StringMatcher stringMatcher =
        StringMatcher.newBuilder().setExact("sub.abc.test.youtube.com").build();
    @SuppressWarnings("deprecation")
    CertificateValidationContext certContext =
        CertificateValidationContext.newBuilder().addMatchSubjectAltNames(stringMatcher).build();
    trustManager = new XdsX509TrustManager(certContext, mockDelegate);
    X509Certificate[] certs =
        CertificateUtils.toX509Certificates(TlsTesting.loadCert(SERVER_1_PEM_FILE));
    try {
      trustManager.verifySubjectAltNameInChain(certs);
      fail("no exception thrown");
    } catch (CertificateException expected) {
      assertThat(expected).hasMessageThat().isEqualTo("Peer certificate SAN check failed");
    }
  }

  @Test
  public void oneIpAddressInPeerCertsVerifies() throws CertificateException, IOException {
    StringMatcher stringMatcher = StringMatcher.newBuilder().setExact("x.foo.com").build();
    StringMatcher stringMatcher1 = StringMatcher.newBuilder().setExact("192.168.1.3").build();
    @SuppressWarnings("deprecation")
    CertificateValidationContext certContext =
        CertificateValidationContext.newBuilder()
            .addMatchSubjectAltNames(stringMatcher)
            .addMatchSubjectAltNames(stringMatcher1)
            .build();
    trustManager = new XdsX509TrustManager(certContext, mockDelegate);
    X509Certificate[] certs =
        CertificateUtils.toX509Certificates(TlsTesting.loadCert(SERVER_1_PEM_FILE));
    trustManager.verifySubjectAltNameInChain(certs);
  }

  @Test
  public void oneIpAddressInPeerCertsMismatch() throws CertificateException, IOException {
    StringMatcher stringMatcher = StringMatcher.newBuilder().setExact("x.foo.com").build();
    StringMatcher stringMatcher1 = StringMatcher.newBuilder().setExact("192.168.2.3").build();
    @SuppressWarnings("deprecation")
    CertificateValidationContext certContext =
        CertificateValidationContext.newBuilder()
            .addMatchSubjectAltNames(stringMatcher)
            .addMatchSubjectAltNames(stringMatcher1)
            .build();
    trustManager = new XdsX509TrustManager(certContext, mockDelegate);
    X509Certificate[] certs =
        CertificateUtils.toX509Certificates(TlsTesting.loadCert(SERVER_1_PEM_FILE));
    try {
      trustManager.verifySubjectAltNameInChain(certs);
      fail("no exception thrown");
    } catch (CertificateException expected) {
      assertThat(expected).hasMessageThat().isEqualTo("Peer certificate SAN check failed");
    }
  }

  @Test
  public void checkServerTrustedSslEngine()
      throws CertificateException, IOException, CertStoreException {
    TestSslEngine sslEngine = buildTrustManagerAndGetSslEngine();
    X509Certificate[] serverCerts =
        CertificateUtils.toX509Certificates(TlsTesting.loadCert(SERVER_1_PEM_FILE));
    trustManager.checkServerTrusted(serverCerts, "ECDHE_ECDSA", sslEngine);
    verify(sslEngine, times(1)).getHandshakeSession();
  }

  @Test
  public void checkServerTrustedSslEngine_untrustedServer_expectException()
      throws CertificateException, IOException, CertStoreException {
    TestSslEngine sslEngine = buildTrustManagerAndGetSslEngine();
    X509Certificate[] badServerCert =
        CertificateUtils.toX509Certificates(TlsTesting.loadCert(BAD_SERVER_PEM_FILE));
    try {
      trustManager.checkServerTrusted(badServerCert, "ECDHE_ECDSA", sslEngine);
      fail("exception expected");
    } catch (CertificateException expected) {
      assertThat(expected).hasMessageThat()
          .endsWith("unable to find valid certification path to requested target");
    }
    verify(sslEngine, times(1)).getHandshakeSession();
  }

  @Test
  public void checkServerTrustedSslSocket()
      throws CertificateException, IOException, CertStoreException {
    TestSslSocket sslSocket = buildTrustManagerAndGetSslSocket();
    X509Certificate[] serverCerts =
        CertificateUtils.toX509Certificates(TlsTesting.loadCert(SERVER_1_PEM_FILE));
    trustManager.checkServerTrusted(serverCerts, "ECDHE_ECDSA", sslSocket);
    verify(sslSocket, times(1)).isConnected();
    verify(sslSocket, times(1)).getHandshakeSession();
  }

  @Test
  public void checkServerTrustedSslSocket_untrustedServer_expectException()
      throws CertificateException, IOException, CertStoreException {
    TestSslSocket sslSocket = buildTrustManagerAndGetSslSocket();
    X509Certificate[] badServerCert =
        CertificateUtils.toX509Certificates(TlsTesting.loadCert(BAD_SERVER_PEM_FILE));
    try {
      trustManager.checkServerTrusted(badServerCert, "ECDHE_ECDSA", sslSocket);
      fail("exception expected");
    } catch (CertificateException expected) {
      assertThat(expected).hasMessageThat()
          .endsWith("unable to find valid certification path to requested target");
    }
    verify(sslSocket, times(1)).isConnected();
    verify(sslSocket, times(1)).getHandshakeSession();
  }

  @Test
  public void unsupportedAltNameType() throws CertificateException, IOException {
    StringMatcher stringMatcher =
        StringMatcher.newBuilder()
            .setExact("waterzooi.test.google.be")
            .setIgnoreCase(false)
            .build();
    @SuppressWarnings("deprecation")
    CertificateValidationContext certContext =
        CertificateValidationContext.newBuilder().addMatchSubjectAltNames(stringMatcher).build();
    trustManager = new XdsX509TrustManager(certContext, mockDelegate);
    X509Certificate mockCert = mock(X509Certificate.class);

    when(mockCert.getSubjectAlternativeNames())
        .thenReturn(Collections.<List<?>>singleton(ImmutableList.of(Integer.valueOf(1), "foo")));
    X509Certificate[] certs = new X509Certificate[] {mockCert};
    try {
      trustManager.verifySubjectAltNameInChain(certs);
      fail("no exception thrown");
    } catch (CertificateException expected) {
      assertThat(expected).hasMessageThat().isEqualTo("Peer certificate SAN check failed");
    }
  }

  private TestSslEngine buildTrustManagerAndGetSslEngine()
      throws CertificateException, IOException, CertStoreException {
    SSLParameters sslParams = buildTrustManagerAndGetSslParameters();

    TestSslEngine sslEngine = mock(TestSslEngine.class, CALLS_REAL_METHODS);
    sslEngine.setSSLParameters(sslParams);
    doReturn(mockSession).when(sslEngine).getHandshakeSession();
    return sslEngine;
  }

  private TestSslSocket buildTrustManagerAndGetSslSocket()
      throws CertificateException, IOException, CertStoreException {
    SSLParameters sslParams = buildTrustManagerAndGetSslParameters();

    TestSslSocket sslSocket = mock(TestSslSocket.class, CALLS_REAL_METHODS);
    sslSocket.setSSLParameters(sslParams);
    doReturn(true).when(sslSocket).isConnected();
    doReturn(mockSession).when(sslSocket).getHandshakeSession();
    return sslSocket;
  }

  private SSLParameters buildTrustManagerAndGetSslParameters()
      throws CertificateException, IOException, CertStoreException {
    X509Certificate[] caCerts =
        CertificateUtils.toX509Certificates(TlsTesting.loadCert(CA_PEM_FILE));
    trustManager = XdsTrustManagerFactory.createX509TrustManager(caCerts,
        null);
    when(mockSession.getProtocol()).thenReturn("TLSv1.2");
    when(mockSession.getPeerHost()).thenReturn("peer-host-from-mock");
    SSLParameters sslParams = new SSLParameters();
    sslParams.setEndpointIdentificationAlgorithm("HTTPS");
    return sslParams;
  }

  private abstract static class TestSslSocket extends SSLSocket {

    @Override
    public SSLParameters getSSLParameters() {
      return sslParameters;
    }

    @Override
    public void setSSLParameters(SSLParameters sslParameters) {
      this.sslParameters = sslParameters;
    }

    private SSLParameters sslParameters;
  }

  private abstract static class TestSslEngine extends SSLEngine {

    @Override
    public SSLParameters getSSLParameters() {
      return sslParameters;
    }

    @Override
    public void setSSLParameters(SSLParameters sslParameters) {
      this.sslParameters = sslParameters;
    }

    private SSLParameters sslParameters;
  }
}
