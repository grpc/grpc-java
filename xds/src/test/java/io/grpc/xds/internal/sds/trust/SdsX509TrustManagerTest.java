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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.envoyproxy.envoy.api.v2.auth.CertificateValidationContext;
import io.grpc.internal.testing.TestUtils;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.security.cert.CertStoreException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
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
import sun.security.validator.ValidatorException;

/**
 * Unit tests for {@link SdsX509TrustManager}.
 */
@RunWith(JUnit4.class)
public class SdsX509TrustManagerTest {

  /** Trust store cert. */
  private static final String CA_PEM_FILE = "ca.pem";

  /** server1 has 4 SANs. */
  private static final String SERVER_1_PEM_FILE = "server1.pem";

  /** client has no SANs. */
  private static final String CLIENT_PEM_FILE = "client.pem";

  /** Untrusted server. */
  private static final String BAD_SERVER_PEM_FILE = "badserver.pem";

  @Rule
  public final MockitoRule mockitoRule = MockitoJUnit.rule();

  @Mock
  private X509ExtendedTrustManager mockDelegate;

  @Mock
  private SSLSession mockSession;

  private SdsX509TrustManager trustManager;

  @Test
  public void nullCertContextTest() throws CertificateException, IOException {
    trustManager = new SdsX509TrustManager(null, mockDelegate);
    X509Certificate[] certs =
        CertificateUtils.toX509Certificates(TestUtils.loadCert(SERVER_1_PEM_FILE));
    trustManager.verifySubjectAltNameInChain(certs);
  }

  @Test
  public void emptySanListContextTest() throws CertificateException, IOException {
    CertificateValidationContext certContext = CertificateValidationContext.getDefaultInstance();
    trustManager = new SdsX509TrustManager(certContext, mockDelegate);
    X509Certificate[] certs =
        CertificateUtils.toX509Certificates(TestUtils.loadCert(SERVER_1_PEM_FILE));
    trustManager.verifySubjectAltNameInChain(certs);
  }

  @Test
  @SuppressWarnings("deprecation")
  public void missingPeerCerts() throws CertificateException, FileNotFoundException {
    CertificateValidationContext certContext =
        CertificateValidationContext.newBuilder().addVerifySubjectAltName("foo.com").build();
    trustManager = new SdsX509TrustManager(certContext, mockDelegate);
    try {
      trustManager.verifySubjectAltNameInChain(null);
      fail("no exception thrown");
    } catch (CertificateException expected) {
      assertThat(expected).hasMessageThat().isEqualTo("Peer certificate(s) missing");
    }
  }

  @Test
  @SuppressWarnings("deprecation")
  public void emptyArrayPeerCerts() throws CertificateException, FileNotFoundException {
    CertificateValidationContext certContext =
        CertificateValidationContext.newBuilder().addVerifySubjectAltName("foo.com").build();
    trustManager = new SdsX509TrustManager(certContext, mockDelegate);
    try {
      trustManager.verifySubjectAltNameInChain(new X509Certificate[0]);
      fail("no exception thrown");
    } catch (CertificateException expected) {
      assertThat(expected).hasMessageThat().isEqualTo("Peer certificate(s) missing");
    }
  }

  @Test
  @SuppressWarnings("deprecation")
  public void noSansInPeerCerts() throws CertificateException, IOException {
    CertificateValidationContext certContext =
        CertificateValidationContext.newBuilder().addVerifySubjectAltName("foo.com").build();
    trustManager = new SdsX509TrustManager(certContext, mockDelegate);
    X509Certificate[] certs =
        CertificateUtils.toX509Certificates(TestUtils.loadCert(CLIENT_PEM_FILE));
    try {
      trustManager.verifySubjectAltNameInChain(certs);
      fail("no exception thrown");
    } catch (CertificateException expected) {
      assertThat(expected).hasMessageThat().isEqualTo("Peer certificate SAN check failed");
    }
  }

  @Test
  @SuppressWarnings("deprecation")
  public void oneSanInPeerCertsVerifies() throws CertificateException, IOException {
    CertificateValidationContext certContext =
        CertificateValidationContext.newBuilder()
            .addVerifySubjectAltName("waterzooi.test.google.be")
            .build();
    trustManager = new SdsX509TrustManager(certContext, mockDelegate);
    X509Certificate[] certs =
        CertificateUtils.toX509Certificates(TestUtils.loadCert(SERVER_1_PEM_FILE));
    trustManager.verifySubjectAltNameInChain(certs);
  }

  @Test
  @SuppressWarnings("deprecation")
  public void oneSanInPeerCertsVerifiesMultipleVerifySans()
      throws CertificateException, IOException {
    CertificateValidationContext certContext =
        CertificateValidationContext.newBuilder()
            .addVerifySubjectAltName("x.foo.com")
            .addVerifySubjectAltName("waterzooi.test.google.be")
            .build();
    trustManager = new SdsX509TrustManager(certContext, mockDelegate);
    X509Certificate[] certs =
        CertificateUtils.toX509Certificates(TestUtils.loadCert(SERVER_1_PEM_FILE));
    trustManager.verifySubjectAltNameInChain(certs);
  }

  @Test
  @SuppressWarnings("deprecation")
  public void oneSanInPeerCertsNotFoundException()
      throws CertificateException, IOException {
    CertificateValidationContext certContext =
        CertificateValidationContext.newBuilder().addVerifySubjectAltName("x.foo.com").build();
    trustManager = new SdsX509TrustManager(certContext, mockDelegate);
    X509Certificate[] certs =
        CertificateUtils.toX509Certificates(TestUtils.loadCert(SERVER_1_PEM_FILE));
    try {
      trustManager.verifySubjectAltNameInChain(certs);
      fail("no exception thrown");
    } catch (CertificateException expected) {
      assertThat(expected).hasMessageThat().isEqualTo("Peer certificate SAN check failed");
    }
  }

  @Test
  @SuppressWarnings("deprecation")
  public void wildcardSanInPeerCertsVerifiesMultipleVerifySans()
      throws CertificateException, IOException {
    CertificateValidationContext certContext =
        CertificateValidationContext.newBuilder()
            .addVerifySubjectAltName("x.foo.com")
            .addVerifySubjectAltName("abc.test.youtube.com") // should match *.test.youtube.com
            .build();
    trustManager = new SdsX509TrustManager(certContext, mockDelegate);
    X509Certificate[] certs =
        CertificateUtils.toX509Certificates(TestUtils.loadCert(SERVER_1_PEM_FILE));
    trustManager.verifySubjectAltNameInChain(certs);
  }

  @Test
  @SuppressWarnings("deprecation")
  public void wildcardSanInPeerCertsVerifiesMultipleVerifySans1()
      throws CertificateException, IOException {
    CertificateValidationContext certContext =
        CertificateValidationContext.newBuilder()
            .addVerifySubjectAltName("x.foo.com")
            .addVerifySubjectAltName("abc.test.google.fr") // should match *.test.google.fr
            .build();
    trustManager = new SdsX509TrustManager(certContext, mockDelegate);
    X509Certificate[] certs =
        CertificateUtils.toX509Certificates(TestUtils.loadCert(SERVER_1_PEM_FILE));
    trustManager.verifySubjectAltNameInChain(certs);
  }

  @Test
  @SuppressWarnings("deprecation")
  public void wildcardSanInPeerCertsSubdomainMismatch()
      throws CertificateException, IOException {
    // 2. Asterisk (*) cannot match across domain name labels.
    //    For example, *.example.com matches test.example.com but does not match
    //    sub.test.example.com.
    CertificateValidationContext certContext =
        CertificateValidationContext.newBuilder()
            .addVerifySubjectAltName("sub.abc.test.youtube.com")
            .build();
    trustManager = new SdsX509TrustManager(certContext, mockDelegate);
    X509Certificate[] certs =
        CertificateUtils.toX509Certificates(TestUtils.loadCert(SERVER_1_PEM_FILE));
    try {
      trustManager.verifySubjectAltNameInChain(certs);
      fail("no exception thrown");
    } catch (CertificateException expected) {
      assertThat(expected).hasMessageThat().isEqualTo("Peer certificate SAN check failed");
    }
  }

  @Test
  @SuppressWarnings("deprecation")
  public void oneIpAddressInPeerCertsVerifies() throws CertificateException, IOException {
    CertificateValidationContext certContext =
        CertificateValidationContext.newBuilder()
            .addVerifySubjectAltName("x.foo.com")
            .addVerifySubjectAltName("192.168.1.3")
            .build();
    trustManager = new SdsX509TrustManager(certContext, mockDelegate);
    X509Certificate[] certs =
        CertificateUtils.toX509Certificates(TestUtils.loadCert(SERVER_1_PEM_FILE));
    trustManager.verifySubjectAltNameInChain(certs);
  }

  @Test
  @SuppressWarnings("deprecation")
  public void oneIpAddressInPeerCertsMismatch() throws CertificateException, IOException {
    CertificateValidationContext certContext =
        CertificateValidationContext.newBuilder()
            .addVerifySubjectAltName("x.foo.com")
            .addVerifySubjectAltName("192.168.2.3")
            .build();
    trustManager = new SdsX509TrustManager(certContext, mockDelegate);
    X509Certificate[] certs =
        CertificateUtils.toX509Certificates(TestUtils.loadCert(SERVER_1_PEM_FILE));
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
        CertificateUtils.toX509Certificates(TestUtils.loadCert(SERVER_1_PEM_FILE));
    trustManager.checkServerTrusted(serverCerts, "ECDHE_ECDSA", sslEngine);
    verify(sslEngine, times(1)).getHandshakeSession();
  }

  @Test
  public void checkServerTrustedSslEngine_untrustedServer_expectException()
      throws CertificateException, IOException, CertStoreException {
    TestSslEngine sslEngine = buildTrustManagerAndGetSslEngine();
    X509Certificate[] badServerCert =
        CertificateUtils.toX509Certificates(TestUtils.loadCert(BAD_SERVER_PEM_FILE));
    try {
      trustManager.checkServerTrusted(badServerCert, "ECDHE_ECDSA", sslEngine);
      fail("exception expected");
    } catch (ValidatorException expected) {
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
        CertificateUtils.toX509Certificates(TestUtils.loadCert(SERVER_1_PEM_FILE));
    trustManager.checkServerTrusted(serverCerts, "ECDHE_ECDSA", sslSocket);
    verify(sslSocket, times(1)).isConnected();
    verify(sslSocket, times(1)).getHandshakeSession();
  }

  @Test
  public void checkServerTrustedSslSocket_untrustedServer_expectException()
      throws CertificateException, IOException, CertStoreException {
    TestSslSocket sslSocket = buildTrustManagerAndGetSslSocket();
    X509Certificate[] badServerCert =
        CertificateUtils.toX509Certificates(TestUtils.loadCert(BAD_SERVER_PEM_FILE));
    try {
      trustManager.checkServerTrusted(badServerCert, "ECDHE_ECDSA", sslSocket);
      fail("exception expected");
    } catch (ValidatorException expected) {
      assertThat(expected).hasMessageThat()
          .endsWith("unable to find valid certification path to requested target");
    }
    verify(sslSocket, times(1)).isConnected();
    verify(sslSocket, times(1)).getHandshakeSession();
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
        CertificateUtils.toX509Certificates(TestUtils.loadCert(CA_PEM_FILE));
    trustManager = SdsTrustManagerFactory.createSdsX509TrustManager(caCerts,
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
