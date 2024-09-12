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

package io.grpc.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.google.common.base.Optional;
import io.grpc.testing.TlsTesting;
import io.grpc.util.CertificateUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.cert.CertificateException;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import org.junit.Before;
import org.junit.Test;

public class SpiffeUtilTest {

  private static final String SPIFFE_PEM_FILE = "spiffe_cert.pem";
  private static final String SERVER_0_PEM_FILE = "server0.pem";


  private X509Certificate[] spiffeCert;
  private X509Certificate[] serverCert0;


  @Before
  public void setUp() throws CertificateException {
    spiffeCert = CertificateUtils.getX509Certificates(TlsTesting.loadCert(SPIFFE_PEM_FILE));
    serverCert0 = CertificateUtils.getX509Certificates(TlsTesting.loadCert(SERVER_0_PEM_FILE));
  }

  @Test
  public void extractSpiffeIdHappyPath() throws CertificateParsingException {
    Optional<SpiffeUtil.SpiffeId> spiffeId = SpiffeUtil.extractSpiffeId(spiffeCert);
    assertEquals("foo.bar.com", spiffeId.get().getTrustDomain());
    assertEquals("client/workload/1", spiffeId.get().getPath());
  }

  @Test
  public void extractSpiffeIdEmpty() throws CertificateParsingException {
    Optional<SpiffeUtil.SpiffeId> spiffeId = SpiffeUtil.extractSpiffeId(serverCert0);
    assertFalse(spiffeId.isPresent());
  }

  @Test
  public void extractSpiffeIdFromChain() throws CertificateParsingException {
    X509Certificate[] leafWithSpiffeChain = new X509Certificate[]{spiffeCert[0], serverCert0[0]};
    assertTrue(SpiffeUtil.extractSpiffeId(leafWithSpiffeChain).isPresent());
    X509Certificate[] leafWithoutSpiffeChain = new X509Certificate[]{serverCert0[0], spiffeCert[0]};
    assertFalse(SpiffeUtil.extractSpiffeId(leafWithoutSpiffeChain).isPresent());
  }

  @Test
  public void extractSpiffeParameterValidity() {
    NullPointerException npe = assertThrows(NullPointerException.class, () -> SpiffeUtil
        .extractSpiffeId(null));
    assertEquals("certChain", npe.getMessage());
    IllegalArgumentException iae = assertThrows(IllegalArgumentException.class, () -> SpiffeUtil
        .extractSpiffeId(new X509Certificate[]{}));
    assertEquals("CertChain can't be empty", iae.getMessage());
  }

  @Test
  public void loadTrustBundleFromFileHappyPath() throws IOException, CertificateParsingException {
    SpiffeUtil.TrustBundle tb = SpiffeUtil.loadTrustBundleFromFile(getClass().getClassLoader()
        .getResource("io/grpc/internal/spiffebundle.txt").getPath());
    assertEquals(1, tb.getTrustBundleMap().get("example.com").size());
    assertEquals("foo.bar.com", SpiffeUtil.extractSpiffeId(tb.getTrustBundleMap().get("example.com")
        .toArray(new X509Certificate[0])).get().getTrustDomain());
  }

}