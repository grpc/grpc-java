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

package io.grpc.util;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.collect.Iterables;
import io.grpc.internal.FakeClock;
import io.grpc.internal.testing.TestUtils;
import io.grpc.testing.TlsTesting;
import io.grpc.util.AdvancedTlsX509TrustManager.Verification;
import java.io.File;
import java.io.IOException;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import javax.net.ssl.SSLSocket;
import org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link AdvancedTlsX509TrustManager}. */
@RunWith(JUnit4.class)
@IgnoreJRERequirement
public class AdvancedTlsX509TrustManagerTest {

  private static final String CA_PEM_FILE = "ca.pem";
  private static final String SERVER_0_PEM_FILE = "server0.pem";
  private File caCertFile;
  private File serverCert0File;

  private X509Certificate[] caCert;
  private X509Certificate[] serverCert0;

  private ScheduledExecutorService executor;

  @Before
  public void setUp() throws IOException, GeneralSecurityException {
    executor = new FakeClock().getScheduledExecutorService();
    caCertFile = TestUtils.loadCert(CA_PEM_FILE);
    caCert = CertificateUtils.getX509Certificates(TlsTesting.loadCert(CA_PEM_FILE));
    serverCert0File = TestUtils.loadCert(SERVER_0_PEM_FILE);
    serverCert0 = CertificateUtils.getX509Certificates(TlsTesting.loadCert(SERVER_0_PEM_FILE));
  }

  @Test
  public void updateTrustCredentials_replacesIssuers() throws Exception {
    // Overall happy path checking of public API.
    AdvancedTlsX509TrustManager trustManager = AdvancedTlsX509TrustManager.newBuilder().build();
    trustManager.updateTrustCredentials(serverCert0File);
    assertArrayEquals(serverCert0, trustManager.getAcceptedIssuers());

    trustManager.updateTrustCredentials(caCert);
    assertArrayEquals(caCert, trustManager.getAcceptedIssuers());

    trustManager.updateTrustCredentials(serverCert0File, 1, TimeUnit.MINUTES,
        executor);
    assertArrayEquals(serverCert0, trustManager.getAcceptedIssuers());

    trustManager.updateTrustCredentials(serverCert0File);
    assertArrayEquals(serverCert0, trustManager.getAcceptedIssuers());
  }

  @Test
  public void systemDefaultDelegateManagerInstantiation() throws Exception {
    AdvancedTlsX509TrustManager trustManager = AdvancedTlsX509TrustManager.newBuilder().build();
    trustManager.useSystemDefaultTrustCerts();
    CertificateException ce = assertThrows(CertificateException.class, () -> trustManager
        .checkServerTrusted(serverCert0, "RSA", new Socket()));
    assertEquals("socket is not a type of SSLSocket", ce.getMessage());
  }

  @Test
  public void credentialSettingParameterValidity() throws Exception {
    // Checking edge cases of public API parameter setting.
    AdvancedTlsX509TrustManager trustManager = AdvancedTlsX509TrustManager.newBuilder().build();

    NullPointerException npe = assertThrows(NullPointerException.class, () -> trustManager
        .updateTrustCredentials(null, 1, null, null));
    assertEquals("trustCertFile", npe.getMessage());

    npe = assertThrows(NullPointerException.class, () -> trustManager
        .updateTrustCredentials(caCertFile, 1, null, null));
    assertEquals("unit", npe.getMessage());

    npe = assertThrows(NullPointerException.class, () -> trustManager
        .updateTrustCredentials(caCertFile, 1, TimeUnit.MINUTES, null));
    assertEquals("executor", npe.getMessage());

    Logger log = Logger.getLogger(AdvancedTlsX509TrustManager.class.getName());
    TestHandler handler = new TestHandler();
    log.addHandler(handler);
    log.setUseParentHandlers(false);
    log.setLevel(Level.FINE);
    trustManager.updateTrustCredentials(serverCert0File, -1, TimeUnit.SECONDS, executor);
    log.removeHandler(handler);
    try {
      LogRecord logRecord = Iterables.find(handler.getRecords(),
          record -> record.getMessage().contains("Default value of "));
      assertNotNull(logRecord);
    } catch (NoSuchElementException e) {
      throw new AssertionError("Log message related to setting default values not found");
    }
  }

  @Test
  public void clientTrustedWithSocketTest() throws Exception {
    AdvancedTlsX509TrustManager trustManager = AdvancedTlsX509TrustManager.newBuilder()
        .setVerification(Verification.CERTIFICATE_ONLY_VERIFICATION).build();
    trustManager.updateTrustCredentials(caCert);
    SSLSocket sslSocket = mock(SSLSocket.class);
    when(sslSocket.isConnected()).thenReturn(true);
    when(sslSocket.getHandshakeSession()).thenReturn(null);
    CertificateException ce = assertThrows(CertificateException.class, () -> trustManager
        .checkClientTrusted(serverCert0, "RSA", sslSocket));
    assertEquals("No handshake session", ce.getMessage());
  }

  private static class TestHandler extends Handler {
    private final List<LogRecord> records = new ArrayList<>();

    @Override
    public void publish(LogRecord record) {
      records.add(record);
    }

    @Override
    public void flush() {
    }

    @Override
    public void close() throws SecurityException {
    }

    public List<LogRecord> getRecords() {
      return records;
    }
  }

}
