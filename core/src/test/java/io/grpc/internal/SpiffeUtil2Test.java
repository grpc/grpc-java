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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.google.common.base.Optional;
import io.grpc.internal.SpiffeUtil2.SpiffeBundle;
import io.grpc.testing.TlsTesting;
import io.grpc.util.CertificateUtils;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

public class SpiffeUtil2Test {

  private static final String SPIFFE_PEM_FILE = "spiffe_cert.pem";
  private static final String SPIFFE_MULTI_VALUES_PEM_FILE = "spiffe_cert_multi.pem";
  private static final String SERVER_0_PEM_FILE = "server0.pem";
  private static final String TEST_DIRECTORY_PREFIX = "io/grpc/internal/";
  private static final String SPIFFE_TRUST_BUNDLE_FILE = "spiffebundle.json";
  private static final String SPIFFE_TRUST_BUNDLE_MALFORMED = "spiffebundle_malformed.json";
  private static final String SPIFFE_TRUST_BUNDLE_WRONG_ELEMENTS =
      "spiffebundle_wrong_elements.json";
  private static final String SPIFFE_TRUST_BUNDLE_DUPLICATES = "spiffebundle_duplicates.json";
  private static final String SPIFFE_TRUST_BUNDLE_WITH_WRONG_ROOT =
      "spiffebundle_wrong_root.json";


  private X509Certificate[] spiffeCert;
  private X509Certificate[] spiffeMultiCert;
  private X509Certificate[] serverCert0;


  @Before
  public void setUp() throws CertificateException {
    spiffeCert = CertificateUtils.getX509Certificates(TlsTesting.loadCert(SPIFFE_PEM_FILE));
    spiffeMultiCert = CertificateUtils.getX509Certificates(TlsTesting
        .loadCert(SPIFFE_MULTI_VALUES_PEM_FILE));
    serverCert0 = CertificateUtils.getX509Certificates(TlsTesting.loadCert(SERVER_0_PEM_FILE));
  }

  @Test
  public void extractSpiffeIdSuccessTest() throws CertificateParsingException {
    Optional<SpiffeUtil2.SpiffeId> spiffeId = SpiffeUtil2.extractSpiffeId(spiffeCert);
    assertEquals("foo.bar.com", spiffeId.get().getTrustDomain());
    assertEquals("client/workload/1", spiffeId.get().getPath());
  }

  @Test
  public void extractSpiffeIdFailureTest() throws CertificateParsingException {
    Optional<SpiffeUtil2.SpiffeId> spiffeId = SpiffeUtil2.extractSpiffeId(serverCert0);
    assertFalse(spiffeId.isPresent());
    IllegalArgumentException iae = assertThrows(IllegalArgumentException.class, () -> SpiffeUtil2
        .extractSpiffeId(spiffeMultiCert));
    assertEquals("Multiple URI SAN values found in the leaf cert.", iae.getMessage());

  }

  @Test
  public void extractSpiffeIdFromChainTest() throws CertificateParsingException {
    X509Certificate[] leafWithSpiffeChain = new X509Certificate[]{spiffeCert[0], serverCert0[0]};
    assertTrue(SpiffeUtil2.extractSpiffeId(leafWithSpiffeChain).isPresent());
    X509Certificate[] leafWithoutSpiffeChain = new X509Certificate[]{serverCert0[0], spiffeCert[0]};
    assertFalse(SpiffeUtil2.extractSpiffeId(leafWithoutSpiffeChain).isPresent());
  }

  @Test
  public void extractSpiffeIdParameterValidityTest() {
    NullPointerException npe = assertThrows(NullPointerException.class, () -> SpiffeUtil2
        .extractSpiffeId(null));
    assertEquals("certChain", npe.getMessage());
    IllegalArgumentException iae = assertThrows(IllegalArgumentException.class, () -> SpiffeUtil2
        .extractSpiffeId(new X509Certificate[]{}));
    assertEquals("CertChain can't be empty", iae.getMessage());
  }

  @Test
  public void loadTrustBundleFromFileSuccessTest() throws IOException, CertificateParsingException {
    SpiffeBundle tb = SpiffeUtil2.loadTrustBundleFromFile(getClass().getClassLoader()
        .getResource(TEST_DIRECTORY_PREFIX + SPIFFE_TRUST_BUNDLE_FILE).getPath());
    assertEquals(3, tb.getSequenceNumbers().size());
    assertEquals(123L, (long) tb.getSequenceNumbers().get("google.com"));
    assertEquals(123L, (long) tb.getSequenceNumbers().get("test.google.com"));
    assertEquals(12035488L, (long) tb.getSequenceNumbers().get("example.com"));
    assertEquals(4, tb.getBundleMap().size());
    assertEquals(0, tb.getBundleMap().get("google.com").size());
    assertEquals(0, tb.getBundleMap().get("test.google.com").size());
    assertEquals(0, tb.getBundleMap().get("test.google.com.au").size());
    assertEquals(1, tb.getBundleMap().get("example.com").size());
    assertEquals("foo.bar.com", SpiffeUtil2.extractSpiffeId(tb.getBundleMap().get("example.com")
        .toArray(new X509Certificate[0])).get().getTrustDomain());
  }

  @Test
  public void loadTrustBundleFromFileFailureTest() throws IOException {
    NullPointerException npe = assertThrows(NullPointerException.class, () -> SpiffeUtil2.
        loadTrustBundleFromFile(getClass().getClassLoader().getResource(TEST_DIRECTORY_PREFIX
            + SPIFFE_TRUST_BUNDLE_WITH_WRONG_ROOT).getPath()));
    assertEquals("Mandatory trust_domains element is missing", npe.getMessage());
    IllegalArgumentException iae = assertThrows(IllegalArgumentException.class, () -> SpiffeUtil2.
        loadTrustBundleFromFile(getClass().getClassLoader().getResource(TEST_DIRECTORY_PREFIX
            + SPIFFE_TRUST_BUNDLE_MALFORMED).getPath()));
    assertTrue(iae.getMessage().contains("SPIFFE Trust Bundle should be a JSON object."));
    iae = assertThrows(IllegalArgumentException.class, () -> SpiffeUtil2.
        loadTrustBundleFromFile(getClass().getClassLoader().getResource(TEST_DIRECTORY_PREFIX
            + SPIFFE_TRUST_BUNDLE_DUPLICATES).getPath()));
    assertTrue(iae.getMessage().contains("Duplicate key found: google.com"));
    SpiffeBundle tb = SpiffeUtil2.loadTrustBundleFromFile(getClass().getClassLoader()
        .getResource(TEST_DIRECTORY_PREFIX + SPIFFE_TRUST_BUNDLE_WRONG_ELEMENTS).getPath());
    assertEquals(4, tb.getBundleMap().size());
    for (List<X509Certificate> certs: tb.getBundleMap().values()){
      assertEquals(0, certs.size());
    }
  }

  @Test
  public void loadTrustBundleFromFileParameterValidityTest() {
    NullPointerException npe = assertThrows(NullPointerException.class, () -> SpiffeUtil2
        .loadTrustBundleFromFile(null));
    assertEquals("trustBundleFile", npe.getMessage());
    NoSuchFileException nsfe = assertThrows(NoSuchFileException.class, () -> SpiffeUtil2
        .loadTrustBundleFromFile("i_do_not_exist"));
    assertEquals("i_do_not_exist", nsfe.getMessage());
  }

}