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
  private static final String CA_PEM_FILE = "src/test/certs/ca.pem";

  /** server cert. */
  private static final String SERVER_1_PEM_FILE = "src/test/certs/server1.pem";

  /** client cert. */
  private static final String CLIENT_PEM_FILE = "src/test/certs/client.pem";

  /** bad server cert. */
  private static final String BAD_SERVER_PEM_FILE = "src/test/certs/badserver.pem";

  /** bad client cert. */
  private static final String BAD_CLIENT_PEM_FILE = "src/test/certs/badclient.pem";

  @Test
  public void factoryConstructorFromFileTest()
      throws CertificateException, IOException, CertStoreException {
    SdsTrustManagerFactory factory =
        new SdsTrustManagerFactory(CA_PEM_FILE, /* certContext= */ null);
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
    assertThat(caCert).isEqualTo(CertificateUtils.toX509Certificates(CA_PEM_FILE)[0]);
  }

  @Test
  public void goodCertCheckServerTrusted()
      throws CertificateException, IOException, CertStoreException {
    SdsTrustManagerFactory factory =
        new SdsTrustManagerFactory(CA_PEM_FILE, /* certContext= */ null);
    SdsX509TrustManager sdsX509TrustManager = (SdsX509TrustManager) factory.getTrustManagers()[0];
    X509Certificate[] serverChain = CertificateUtils.toX509Certificates(SERVER_1_PEM_FILE);
    sdsX509TrustManager.checkServerTrusted(serverChain, "RSA");
  }

  @Test
  public void goodCertCheckClientTrusted()
      throws CertificateException, IOException, CertStoreException {
    SdsTrustManagerFactory factory =
        new SdsTrustManagerFactory(CA_PEM_FILE, /* certContext= */ null);
    SdsX509TrustManager sdsX509TrustManager = (SdsX509TrustManager) factory.getTrustManagers()[0];
    X509Certificate[] clientChain = CertificateUtils.toX509Certificates(CLIENT_PEM_FILE);
    sdsX509TrustManager.checkClientTrusted(clientChain, "RSA");
  }

  @Test
  public void badCertCheckServerTrusted_ExpectException()
      throws CertificateException, IOException, CertStoreException {
    SdsTrustManagerFactory factory =
        new SdsTrustManagerFactory(CA_PEM_FILE, /* certContext= */ null);
    SdsX509TrustManager sdsX509TrustManager = (SdsX509TrustManager) factory.getTrustManagers()[0];
    X509Certificate[] serverChain = CertificateUtils.toX509Certificates(BAD_SERVER_PEM_FILE);
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
  public void badCertCheckClientTrusted_ExpectException()
      throws CertificateException, IOException, CertStoreException {
    SdsTrustManagerFactory factory =
        new SdsTrustManagerFactory(CA_PEM_FILE, /* certContext= */ null);
    SdsX509TrustManager sdsX509TrustManager = (SdsX509TrustManager) factory.getTrustManagers()[0];
    X509Certificate[] clientChain = CertificateUtils.toX509Certificates(BAD_CLIENT_PEM_FILE);
    try {
      sdsX509TrustManager.checkClientTrusted(clientChain, "RSA");
      Assert.fail("no exception thrown");
    } catch (CertificateException expected) {
      assertThat(expected)
          .hasMessageThat()
          .contains("unable to find valid certification path to requested target");
    }
  }
}
