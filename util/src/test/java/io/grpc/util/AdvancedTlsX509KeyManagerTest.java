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
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import io.grpc.internal.FakeClock;
import io.grpc.internal.testing.TestUtils;
import io.grpc.testing.TlsTesting;
import java.io.File;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link AdvancedTlsX509KeyManager}. */
@RunWith(JUnit4.class)
public class AdvancedTlsX509KeyManagerTest {
  private static final String SERVER_0_KEY_FILE = "server0.key";
  private static final String SERVER_0_PEM_FILE = "server0.pem";
  private static final String CLIENT_0_KEY_FILE = "client.key";
  private static final String CLIENT_0_PEM_FILE = "client.pem";
  private static final String ALIAS = "default";

  private ScheduledExecutorService executor;

  private File serverKey0File;
  private File serverCert0File;
  private File clientKey0File;
  private File clientCert0File;

  private PrivateKey serverKey0;
  private X509Certificate[] serverCert0;
  private PrivateKey clientKey0;
  private X509Certificate[] clientCert0;

  @Before
  public void setUp() throws Exception {
    executor = new FakeClock().getScheduledExecutorService();
    serverKey0File = TestUtils.loadCert(SERVER_0_KEY_FILE);
    serverCert0File = TestUtils.loadCert(SERVER_0_PEM_FILE);
    clientKey0File = TestUtils.loadCert(CLIENT_0_KEY_FILE);
    clientCert0File = TestUtils.loadCert(CLIENT_0_PEM_FILE);
    serverKey0 = CertificateUtils.getPrivateKey(TlsTesting.loadCert(SERVER_0_KEY_FILE));
    serverCert0 = CertificateUtils.getX509Certificates(TlsTesting.loadCert(SERVER_0_PEM_FILE));
    clientKey0 = CertificateUtils.getPrivateKey(TlsTesting.loadCert(CLIENT_0_KEY_FILE));
    clientCert0 = CertificateUtils.getX509Certificates(TlsTesting.loadCert(CLIENT_0_PEM_FILE));
  }

  @Test
  public void updateTrustCredentials_replacesIssuers() throws Exception {
    // Overall happy path checking of public API.
    AdvancedTlsX509KeyManager serverKeyManager = new AdvancedTlsX509KeyManager();
    serverKeyManager.updateIdentityCredentials(serverCert0, serverKey0);
    assertEquals(serverKey0, serverKeyManager.getPrivateKey(ALIAS));
    assertArrayEquals(serverCert0, serverKeyManager.getCertificateChain(ALIAS));

    serverKeyManager.updateIdentityCredentials(clientCert0File, clientKey0File);
    assertEquals(clientKey0, serverKeyManager.getPrivateKey(ALIAS));
    assertArrayEquals(clientCert0, serverKeyManager.getCertificateChain(ALIAS));

    serverKeyManager.updateIdentityCredentials(serverCert0File, serverKey0File,1,
        TimeUnit.MINUTES, executor);
    assertEquals(serverKey0, serverKeyManager.getPrivateKey(ALIAS));
    assertArrayEquals(serverCert0, serverKeyManager.getCertificateChain(ALIAS));

    serverKeyManager.updateIdentityCredentials(serverCert0, serverKey0);
    assertEquals(serverKey0, serverKeyManager.getPrivateKey(ALIAS));
    assertArrayEquals(serverCert0, serverKeyManager.getCertificateChain(ALIAS));
  }

  @Test
  public void credentialSettingParameterValidity() throws Exception {
    // Checking edge cases of public API parameter setting.
    AdvancedTlsX509KeyManager serverKeyManager = new AdvancedTlsX509KeyManager();
    NullPointerException npe = assertThrows(NullPointerException.class, () -> serverKeyManager
        .updateIdentityCredentials(serverCert0, null));
    assertEquals("key", npe.getMessage());

    npe = assertThrows(NullPointerException.class, () -> serverKeyManager
        .updateIdentityCredentials(null, serverKey0));
    assertEquals("certs", npe.getMessage());

    npe = assertThrows(NullPointerException.class, () -> serverKeyManager
        .updateIdentityCredentials(null, serverKey0File));
    assertEquals("certFile", npe.getMessage());

    npe = assertThrows(NullPointerException.class, () -> serverKeyManager
        .updateIdentityCredentials(serverCert0File, null));
    assertEquals("keyFile", npe.getMessage());

    npe = assertThrows(NullPointerException.class, () -> serverKeyManager
        .updateIdentityCredentials(serverCert0File, serverKey0File, 1, null,
            executor));
    assertEquals("unit", npe.getMessage());

    npe = assertThrows(NullPointerException.class, () -> serverKeyManager
        .updateIdentityCredentials(serverCert0File, serverKey0File, 1,
            TimeUnit.MINUTES, null));
    assertEquals("executor", npe.getMessage());

    Logger log = Logger.getLogger(AdvancedTlsX509KeyManager.class.getName());
    TestHandler handler = new TestHandler();
    log.addHandler(handler);
    log.setUseParentHandlers(false);
    log.setLevel(Level.FINE);
    serverKeyManager.updateIdentityCredentials(serverCert0File, serverKey0File, -1,
            TimeUnit.SECONDS, executor);
    log.removeHandler(handler);
    for (LogRecord record : handler.getRecords()) {
      if (record.getMessage().contains("Default value of ")) {
        assertTrue(true);
        return;
      }
    }
    fail("Log message related to setting default values not found");
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
