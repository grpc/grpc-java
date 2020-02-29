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

import com.google.protobuf.ByteString;
import io.envoyproxy.envoy.api.v2.auth.CertificateValidationContext;
import io.envoyproxy.envoy.api.v2.core.DataSource;
import io.grpc.internal.testing.TestUtils;
import java.io.IOException;
import java.security.cert.CertStoreException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import javax.net.ssl.TrustManager;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link SdsTrustManagerFactory}. */
@RunWith(JUnit4.class)
public class SdsTrustManagerFactoryTest {

  /** Trust store cert. */
  private static final String CA_PEM_FILE = "ca.pem";

  /** server cert. */
  private static final String SERVER_1_PEM_FILE = "server1.pem";

  /** client cert. */
  private static final String CLIENT_PEM_FILE = "client.pem";

  /** bad server cert. */
  private static final String BAD_SERVER_PEM_FILE = "badserver.pem";

  /** bad client cert. */
  private static final String BAD_CLIENT_PEM_FILE = "badclient.pem";

  @Test
  public void constructor_fromFile() throws CertificateException, IOException, CertStoreException {
    SdsTrustManagerFactory factory =
        new SdsTrustManagerFactory(getCertContextFromPath(CA_PEM_FILE));
    assertThat(factory).isNotNull();
    TrustManager[] tms = factory.getTrustManagers();
    assertThat(tms).isNotNull();
    assertThat(tms).hasLength(1);
    TrustManager myTm = tms[0];
    assertThat(myTm).isInstanceOf(SdsX509TrustManager.class);
    SdsX509TrustManager sdsX509TrustManager = (SdsX509TrustManager) myTm;
    X509Certificate[] acceptedIssuers = sdsX509TrustManager.getAcceptedIssuers();
    assertThat(acceptedIssuers).isNotNull();
    assertThat(acceptedIssuers).hasLength(1);
    X509Certificate caCert = acceptedIssuers[0];
    assertThat(caCert)
        .isEqualTo(CertificateUtils.toX509Certificates(TestUtils.loadCert(CA_PEM_FILE))[0]);
  }

  @Test
  public void constructor_fromInlineBytes()
      throws CertificateException, IOException, CertStoreException {
    SdsTrustManagerFactory factory =
        new SdsTrustManagerFactory(getCertContextFromPathAsInlineBytes(CA_PEM_FILE));
    assertThat(factory).isNotNull();
    TrustManager[] tms = factory.getTrustManagers();
    assertThat(tms).isNotNull();
    assertThat(tms).hasLength(1);
    TrustManager myTm = tms[0];
    assertThat(myTm).isInstanceOf(SdsX509TrustManager.class);
    SdsX509TrustManager sdsX509TrustManager = (SdsX509TrustManager) myTm;
    X509Certificate[] acceptedIssuers = sdsX509TrustManager.getAcceptedIssuers();
    assertThat(acceptedIssuers).isNotNull();
    assertThat(acceptedIssuers).hasLength(1);
    X509Certificate caCert = acceptedIssuers[0];
    assertThat(caCert)
        .isEqualTo(CertificateUtils.toX509Certificates(TestUtils.loadCert(CA_PEM_FILE))[0]);
  }

  @Test
  public void checkServerTrusted_goodCert()
      throws CertificateException, IOException, CertStoreException {
    SdsTrustManagerFactory factory =
        new SdsTrustManagerFactory(getCertContextFromPath(CA_PEM_FILE));
    SdsX509TrustManager sdsX509TrustManager = (SdsX509TrustManager) factory.getTrustManagers()[0];
    X509Certificate[] serverChain =
        CertificateUtils.toX509Certificates(TestUtils.loadCert(SERVER_1_PEM_FILE));
    sdsX509TrustManager.checkServerTrusted(serverChain, "RSA");
  }

  @Test
  public void checkClientTrusted_goodCert()
      throws CertificateException, IOException, CertStoreException {
    SdsTrustManagerFactory factory =
        new SdsTrustManagerFactory(getCertContextFromPath(CA_PEM_FILE));
    SdsX509TrustManager sdsX509TrustManager = (SdsX509TrustManager) factory.getTrustManagers()[0];
    X509Certificate[] clientChain =
        CertificateUtils.toX509Certificates(TestUtils.loadCert(CLIENT_PEM_FILE));
    sdsX509TrustManager.checkClientTrusted(clientChain, "RSA");
  }

  @Test
  public void checkServerTrusted_badCert_throwsException()
      throws CertificateException, IOException, CertStoreException {
    SdsTrustManagerFactory factory =
        new SdsTrustManagerFactory(getCertContextFromPath(CA_PEM_FILE));
    SdsX509TrustManager sdsX509TrustManager = (SdsX509TrustManager) factory.getTrustManagers()[0];
    X509Certificate[] serverChain =
        CertificateUtils.toX509Certificates(TestUtils.loadCert(BAD_SERVER_PEM_FILE));
    try {
      sdsX509TrustManager.checkServerTrusted(serverChain, "RSA");
      Assert.fail("no exception thrown");
    } catch (CertificateException expected) {
      assertThat(expected)
          .hasMessageThat()
          .contains("unable to find valid certification path to requested target");
    }
  }

  @Test
  public void checkClientTrusted_badCert_throwsException()
      throws CertificateException, IOException, CertStoreException {
    SdsTrustManagerFactory factory =
        new SdsTrustManagerFactory(getCertContextFromPath(CA_PEM_FILE));
    SdsX509TrustManager sdsX509TrustManager = (SdsX509TrustManager) factory.getTrustManagers()[0];
    X509Certificate[] clientChain =
        CertificateUtils.toX509Certificates(TestUtils.loadCert(BAD_CLIENT_PEM_FILE));
    try {
      sdsX509TrustManager.checkClientTrusted(clientChain, "RSA");
      Assert.fail("no exception thrown");
    } catch (CertificateException expected) {
      assertThat(expected)
          .hasMessageThat()
          .contains("unable to find valid certification path to requested target");
    }
  }

  /** constructs CertificateValidationContext from the resources file-path. */
  private static final CertificateValidationContext getCertContextFromPath(String pemFilePath)
      throws IOException {
    return CertificateValidationContext.newBuilder()
        .setTrustedCa(
            DataSource.newBuilder().setFilename(TestUtils.loadCert(pemFilePath).getAbsolutePath()))
        .build();
  }

  /** constructs CertificateValidationContext from pemFilePath and sets contents as inline-bytes. */
  private static final CertificateValidationContext getCertContextFromPathAsInlineBytes(
      String pemFilePath) throws IOException, CertificateException {
    X509Certificate x509Cert = TestUtils.loadX509Cert(pemFilePath);
    return CertificateValidationContext.newBuilder()
        .setTrustedCa(
            DataSource.newBuilder().setInlineBytes(ByteString.copyFrom(x509Cert.getEncoded())))
        .build();
  }
}
