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

package io.grpc.xds.sds.trust;

import static com.google.common.truth.Truth.assertThat;

import io.envoyproxy.envoy.api.v2.auth.CertificateValidationContext;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import javax.net.ssl.X509ExtendedTrustManager;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/**
 * Unit tests for {@link SdsX509TrustManager}.
 */
@RunWith(JUnit4.class)
public class SdsX509TrustManagerTest {
  /**
   * server1 has 4 SANs.
   */
  private static final String SERVER_1_PEM_FILE = "src/test/certs/server1.pem";

  /**
   * client has no SANs.
   */
  private static final String CLIENT_PEM_FILE = "src/test/certs/client.pem";

  @Rule
  public final MockitoRule mockitoRule = MockitoJUnit.rule();

  @Mock
  private X509ExtendedTrustManager mockDelegate;

  @Ignore("test fails on blaze")
  @Test
  public void nullCertContextTest() throws CertificateException, IOException {
    SdsX509TrustManager trustManager = new SdsX509TrustManager(null, mockDelegate);
    X509Certificate[] certs = CertificateUtils.toX509Certificates(SERVER_1_PEM_FILE);
    trustManager.verifySubjectAltNameInChain(certs);
  }

  @Ignore("test fails on blaze")
  @Test
  public void emptySanListContextTest() throws CertificateException, IOException {
    CertificateValidationContext certContext = CertificateValidationContext.getDefaultInstance();
    SdsX509TrustManager trustManager = new SdsX509TrustManager(certContext, mockDelegate);
    X509Certificate[] certs = CertificateUtils.toX509Certificates(SERVER_1_PEM_FILE);
    trustManager.verifySubjectAltNameInChain(certs);
  }

  @Test
  public void missingPeerCerts() throws CertificateException, FileNotFoundException {
    CertificateValidationContext certContext = CertificateValidationContext
            .newBuilder()
            .addVerifySubjectAltName("foo.com")
            .build();
    SdsX509TrustManager trustManager = new SdsX509TrustManager(certContext, mockDelegate);
    try {
      trustManager.verifySubjectAltNameInChain(null);
      Assert.fail("no exception thrown");
    } catch (CertificateException expected) {
      assertThat(expected).hasMessageThat()
              .isEqualTo("Peer certificate(s) missing");
    }
  }

  @Test
  public void emptyArrayPeerCerts() throws CertificateException, FileNotFoundException {
    CertificateValidationContext certContext = CertificateValidationContext
            .newBuilder()
            .addVerifySubjectAltName("foo.com")
            .build();
    SdsX509TrustManager trustManager = new SdsX509TrustManager(certContext, mockDelegate);
    try {
      trustManager.verifySubjectAltNameInChain(new X509Certificate[0]);
      Assert.fail("no exception thrown");
    } catch (CertificateException expected) {
      assertThat(expected).hasMessageThat()
              .isEqualTo("Peer certificate(s) missing");
    }
  }

  @Ignore("test fails on blaze")
  @Test
  public void noSansInPeerCerts() throws CertificateException, IOException {
    CertificateValidationContext certContext = CertificateValidationContext
            .newBuilder()
            .addVerifySubjectAltName("foo.com")
            .build();
    SdsX509TrustManager trustManager = new SdsX509TrustManager(certContext, mockDelegate);
    X509Certificate[] certs = CertificateUtils.toX509Certificates(CLIENT_PEM_FILE);
    try {
      trustManager.verifySubjectAltNameInChain(certs);
      Assert.fail("no exception thrown");
    } catch (CertificateException expected) {
      assertThat(expected).hasMessageThat()
              .isEqualTo("Peer certificate SAN check failed");
    }
  }

  @Ignore("test fails on blaze")
  @Test
  public void oneSanInPeerCertsVerifies() throws CertificateException, IOException {
    CertificateValidationContext certContext = CertificateValidationContext
            .newBuilder()
            .addVerifySubjectAltName("waterzooi.test.google.be")
            .build();
    SdsX509TrustManager trustManager = new SdsX509TrustManager(certContext, mockDelegate);
    X509Certificate[] certs = CertificateUtils.toX509Certificates(SERVER_1_PEM_FILE);
    trustManager.verifySubjectAltNameInChain(certs);
  }

  @Test
  public void oneSanInPeerCertsVerifiesMultipleVerifySans()
      throws CertificateException, IOException {
    CertificateValidationContext certContext =
        CertificateValidationContext.newBuilder()
            .addVerifySubjectAltName("x.foo.com")
            .addVerifySubjectAltName("waterzooi.test.google.be")
            .build();
    SdsX509TrustManager trustManager = new SdsX509TrustManager(certContext, mockDelegate);
    X509Certificate[] certs = CertificateUtils.toX509Certificates(SERVER_1_PEM_FILE);
    trustManager.verifySubjectAltNameInChain(certs);
  }

  @Ignore("test fails on blaze")
  @Test
  public void oneSanInPeerCertsNotFoundException()
      throws CertificateException, IOException {
    CertificateValidationContext certContext =
        CertificateValidationContext.newBuilder().addVerifySubjectAltName("x.foo.com").build();
    SdsX509TrustManager trustManager = new SdsX509TrustManager(certContext, mockDelegate);
    X509Certificate[] certs = CertificateUtils.toX509Certificates(SERVER_1_PEM_FILE);
    try {
      trustManager.verifySubjectAltNameInChain(certs);
      Assert.fail("no exception thrown");
    } catch (CertificateException expected) {
      assertThat(expected).hasMessageThat().isEqualTo("Peer certificate SAN check failed");
    }
  }

  @Ignore("test fails on blaze")
  @Test
  public void wildcardSanInPeerCertsVerifiesMultipleVerifySans()
      throws CertificateException, IOException {
    CertificateValidationContext certContext = CertificateValidationContext
            .newBuilder()
            .addVerifySubjectAltName("x.foo.com")
            .addVerifySubjectAltName("abc.test.youtube.com")  // should match *.test.youtube.com
            .build();
    SdsX509TrustManager trustManager = new SdsX509TrustManager(certContext, mockDelegate);
    X509Certificate[] certs = CertificateUtils.toX509Certificates(SERVER_1_PEM_FILE);
    trustManager.verifySubjectAltNameInChain(certs);
  }

  @Ignore("test fails on blaze")
  @Test
  public void wildcardSanInPeerCertsVerifiesMultipleVerifySans1()
      throws CertificateException, IOException {
    CertificateValidationContext certContext = CertificateValidationContext
            .newBuilder()
            .addVerifySubjectAltName("x.foo.com")
            .addVerifySubjectAltName("abc.test.google.fr")  // should match *.test.google.fr
            .build();
    SdsX509TrustManager trustManager = new SdsX509TrustManager(certContext, mockDelegate);
    X509Certificate[] certs = CertificateUtils.toX509Certificates(SERVER_1_PEM_FILE);
    trustManager.verifySubjectAltNameInChain(certs);
  }

  @Ignore("test fails on blaze")
  @Test
  public void wildcardSanInPeerCertsSubdomainMismatch()
      throws CertificateException, IOException {
    // 2. Asterisk (*) cannot match across domain name labels.
    //    For example, *.example.com matches test.example.com but does not match
    //    sub.test.example.com.
    CertificateValidationContext certContext = CertificateValidationContext
            .newBuilder()
            .addVerifySubjectAltName("sub.abc.test.youtube.com")
            .build();
    SdsX509TrustManager trustManager = new SdsX509TrustManager(certContext, mockDelegate);
    X509Certificate[] certs = CertificateUtils.toX509Certificates(SERVER_1_PEM_FILE);
    try {
      trustManager.verifySubjectAltNameInChain(certs);
      Assert.fail("no exception thrown");
    } catch (CertificateException expected) {
      assertThat(expected).hasMessageThat()
              .isEqualTo("Peer certificate SAN check failed");
    }
  }

  @Ignore("test fails on blaze")
  @Test
  public void oneIpAddressInPeerCertsVerifies() throws CertificateException, IOException {
    CertificateValidationContext certContext = CertificateValidationContext
            .newBuilder()
            .addVerifySubjectAltName("x.foo.com")
            .addVerifySubjectAltName("192.168.1.3")
            .build();
    SdsX509TrustManager trustManager = new SdsX509TrustManager(certContext, mockDelegate);
    X509Certificate[] certs = CertificateUtils.toX509Certificates(SERVER_1_PEM_FILE);
    trustManager.verifySubjectAltNameInChain(certs);
  }

  @Ignore("test fails on blaze")
  @Test
  public void oneIpAddressInPeerCertsMismatch() throws CertificateException, IOException {
    CertificateValidationContext certContext = CertificateValidationContext
            .newBuilder()
            .addVerifySubjectAltName("x.foo.com")
            .addVerifySubjectAltName("192.168.2.3")
            .build();
    SdsX509TrustManager trustManager = new SdsX509TrustManager(certContext, mockDelegate);
    X509Certificate[] certs = CertificateUtils.toX509Certificates(SERVER_1_PEM_FILE);
    try {
      trustManager.verifySubjectAltNameInChain(certs);
      Assert.fail("no exception thrown");
    } catch (CertificateException expected) {
      assertThat(expected).hasMessageThat()
              .isEqualTo("Peer certificate SAN check failed");
    }
  }
}
