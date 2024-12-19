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
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.google.common.base.Optional;
import com.google.common.io.ByteStreams;
import io.grpc.internal.SpiffeUtil.SpiffeBundle;
import io.grpc.internal.SpiffeUtil.SpiffeId;
import io.grpc.testing.TlsTesting;
import io.grpc.util.CertificateUtils;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Collection;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;


@RunWith(Enclosed.class)
public class SpiffeUtilTest {

  @RunWith(Parameterized.class)
  public static class ParseSuccessTest {
    @Parameter
    public String uri;

    @Parameter(1)
    public String trustDomain;

    @Parameter(2)
    public String path;

    @Test
    public void parseSuccessTest() {
      SpiffeUtil.SpiffeId spiffeId = SpiffeUtil.parse(uri);
      assertEquals(trustDomain, spiffeId.getTrustDomain());
      assertEquals(path, spiffeId.getPath());
    }

    @Parameters(name = "spiffeId={0}")
    public static Collection<String[]> data() {
      return Arrays.asList(new String[][] {
          {"spiffe://example.com", "example.com", ""},
          {"spiffe://example.com/us", "example.com", "/us"},
          {"spIFfe://qa-staging.final_check.example.com/us", "qa-staging.final_check.example.com",
              "/us"},
          {"spiffe://example.com/country/us/state/FL/city/Miami", "example.com",
              "/country/us/state/FL/city/Miami"},
          {"SPIFFE://example.com/Czech.Republic/region0.1/city_of-Prague", "example.com",
              "/Czech.Republic/region0.1/city_of-Prague"},
          {"spiffe://trust-domain-name/path", "trust-domain-name", "/path"},
          {"spiffe://staging.example.com/payments/mysql", "staging.example.com", "/payments/mysql"},
          {"spiffe://staging.example.com/payments/web-fe", "staging.example.com",
              "/payments/web-fe"},
          {"spiffe://k8s-west.example.com/ns/staging/sa/default", "k8s-west.example.com",
              "/ns/staging/sa/default"},
          {"spiffe://example.com/9eebccd2-12bf-40a6-b262-65fe0487d453", "example.com",
              "/9eebccd2-12bf-40a6-b262-65fe0487d453"},
          {"spiffe://trustdomain/.a..", "trustdomain", "/.a.."},
          {"spiffe://trustdomain/...", "trustdomain", "/..."},
          {"spiffe://trustdomain/abcdefghijklmnopqrstuvwxyz", "trustdomain",
              "/abcdefghijklmnopqrstuvwxyz"},
          {"spiffe://trustdomain/abc0123.-_", "trustdomain", "/abc0123.-_"},
          {"spiffe://trustdomain/0123456789", "trustdomain", "/0123456789"},
          {"spiffe://trustdomain0123456789/path", "trustdomain0123456789", "/path"},
      });
    }
  }

  @RunWith(Parameterized.class)
  public static class ParseFailureTest {
    @Parameter
    public String uri;

    @Test
    public void parseFailureTest() {
      assertThrows(IllegalArgumentException.class, () -> SpiffeUtil.parse(uri));
    }

    @Parameters(name = "spiffeId={0}")
    public static Collection<String> data() {
      return Arrays.asList(
          "spiffe:///",
          "spiffe://example!com",
          "spiffe://exampleя.com/workload-1",
          "spiffe://example.com/us/florida/miamiя",
          "spiffe:/trustdomain/path",
          "spiffe:///path",
          "spiffe://trust%20domain/path",
          "spiffe://user@trustdomain/path",
          "spiffe:// /",
          "",
          "http://trustdomain/path",
          "//trustdomain/path",
          "://trustdomain/path",
          "piffe://trustdomain/path",
          "://",
          "://trustdomain",
          "spiff",
          "spiffe",
          "spiffe:////",
          "spiffe://trust.domain/../path"
          );
    }
  }

  public static class ExceptionMessageTest {

    @Test
    public void spiffeUriFormatTest() {
      NullPointerException npe = assertThrows(NullPointerException.class, () ->
          SpiffeUtil.parse(null));
      assertEquals("uri", npe.getMessage());

      IllegalArgumentException iae = assertThrows(IllegalArgumentException.class, () ->
          SpiffeUtil.parse("https://example.com"));
      assertEquals("Spiffe Id must start with spiffe://", iae.getMessage());

      iae = assertThrows(IllegalArgumentException.class, () ->
          SpiffeUtil.parse("spiffe://example.com/workload#1"));
      assertEquals("Spiffe Id must not contain query fragments", iae.getMessage());

      iae = assertThrows(IllegalArgumentException.class, () ->
          SpiffeUtil.parse("spiffe://example.com/workload-1?t=1"));
      assertEquals("Spiffe Id must not contain query parameters", iae.getMessage());
    }

    @Test
    public void spiffeTrustDomainFormatTest() {
      IllegalArgumentException iae = assertThrows(IllegalArgumentException.class, () ->
          SpiffeUtil.parse("spiffe://"));
      assertEquals("Trust Domain can't be empty", iae.getMessage());

      iae = assertThrows(IllegalArgumentException.class, () ->
          SpiffeUtil.parse("spiffe://eXample.com"));
      assertEquals(
          "Trust Domain must contain only letters, numbers, dots, dashes, and underscores "
              + "([a-z0-9.-_])",
          iae.getMessage());

      StringBuilder longTrustDomain = new StringBuilder("spiffe://pi.eu.");
      for (int i = 0; i < 50; i++) {
        longTrustDomain.append("pi.eu");
      }
      iae = assertThrows(IllegalArgumentException.class, () ->
          SpiffeUtil.parse(longTrustDomain.toString()));
      assertEquals("Trust Domain maximum length is 255 characters", iae.getMessage());

      @SuppressWarnings("OrphanedFormatString")
      StringBuilder longSpiffe = new StringBuilder("spiffe://mydomain%21com/");
      for (int i = 0; i < 405; i++) {
        longSpiffe.append("qwert");
      }
      iae = assertThrows(IllegalArgumentException.class, () ->
          SpiffeUtil.parse(longSpiffe.toString()));
      assertEquals("Spiffe Id maximum length is 2048 characters", iae.getMessage());
    }

    @Test
    public void spiffePathFormatTest() {
      IllegalArgumentException iae = assertThrows(IllegalArgumentException.class, () ->
          SpiffeUtil.parse("spiffe://example.com//"));
      assertEquals("Path must not include a trailing '/'", iae.getMessage());

      iae = assertThrows(IllegalArgumentException.class, () ->
          SpiffeUtil.parse("spiffe://example.com/"));
      assertEquals("Path must not include a trailing '/'", iae.getMessage());

      iae = assertThrows(IllegalArgumentException.class, () ->
          SpiffeUtil.parse("spiffe://example.com/us//miami"));
      assertEquals("Individual path segments must not be empty", iae.getMessage());

      iae = assertThrows(IllegalArgumentException.class, () ->
          SpiffeUtil.parse("spiffe://example.com/us/."));
      assertEquals("Individual path segments must not be relative path modifiers (i.e. ., ..)",
          iae.getMessage());

      iae = assertThrows(IllegalArgumentException.class, () ->
          SpiffeUtil.parse("spiffe://example.com/us!"));
      assertEquals("Individual path segments must contain only letters, numbers, dots, dashes, and "
          + "underscores ([a-zA-Z0-9.-_])", iae.getMessage());
    }
  }

  public static class CertificateApiTest {
    private static final String SPIFFE_PEM_FILE = "spiffe_cert.pem";
    private static final String MULTI_URI_SAN_PEM_FILE = "spiffe_multi_uri_san_cert.pem";
    private static final String SERVER_0_PEM_FILE = "server0.pem";
    private static final String TEST_DIRECTORY_PREFIX = "io/grpc/internal/";
    private static final String SPIFFE_TRUST_BUNDLE = "spiffebundle.json";
    private static final String SPIFFE_TRUST_BUNDLE_MALFORMED = "spiffebundle_malformed.json";
    private static final String SPIFFE_TRUST_BUNDLE_CORRUPTED_CERT =
        "spiffebundle_corrupted_cert.json";
    private static final String SPIFFE_TRUST_BUNDLE_WRONG_KTY = "spiffebundle_wrong_kty.json";
    private static final String SPIFFE_TRUST_BUNDLE_WRONG_KID = "spiffebundle_wrong_kid.json";
    private static final String SPIFFE_TRUST_BUNDLE_WRONG_USE = "spiffebundle_wrong_use.json";
    private static final String SPIFFE_TRUST_BUNDLE_WRONG_MULTI_CERTS =
        "spiffebundle_wrong_multi_certs.json";
    private static final String SPIFFE_TRUST_BUNDLE_DUPLICATES = "spiffebundle_duplicates.json";
    private static final String SPIFFE_TRUST_BUNDLE_WRONG_ROOT = "spiffebundle_wrong_root.json";
    private static final String SPIFFE_TRUST_BUNDLE_WRONG_SEQ = "spiffebundle_wrong_seq_type.json";
    private static final String DOMAIN_ERROR_MESSAGE =
        " Certificate loading for trust domain 'google.com' failed.";


    @Rule public TemporaryFolder tempFolder = new TemporaryFolder();

    private X509Certificate[] spiffeCert;
    private X509Certificate[] multipleUriSanCert;
    private X509Certificate[] serverCert0;

    @Before
    public void setUp() throws Exception {
      spiffeCert = CertificateUtils.getX509Certificates(TlsTesting.loadCert(SPIFFE_PEM_FILE));
      multipleUriSanCert = CertificateUtils.getX509Certificates(TlsTesting
          .loadCert(MULTI_URI_SAN_PEM_FILE));
      serverCert0 = CertificateUtils.getX509Certificates(TlsTesting.loadCert(SERVER_0_PEM_FILE));
    }

    private String copyFileToTmp(String fileName) throws Exception {
      File tempFile = tempFolder.newFile(fileName);
      try (InputStream resourceStream = SpiffeUtilTest.class.getClassLoader()
            .getResourceAsStream(TEST_DIRECTORY_PREFIX + fileName);
          OutputStream fileStream = new FileOutputStream(tempFile)) {
        ByteStreams.copy(resourceStream, fileStream);
        fileStream.flush();
      }
      return tempFile.toString();
    }

    @Test
    public void extractSpiffeIdSuccessTest() throws Exception {
      Optional<SpiffeId> spiffeId = SpiffeUtil.extractSpiffeId(spiffeCert);
      assertTrue(spiffeId.isPresent());
      assertEquals("foo.bar.com", spiffeId.get().getTrustDomain());
      assertEquals("/client/workload/1", spiffeId.get().getPath());
    }

    @Test
    public void extractSpiffeIdFailureTest() throws Exception {
      Optional<SpiffeUtil.SpiffeId> spiffeId = SpiffeUtil.extractSpiffeId(serverCert0);
      assertFalse(spiffeId.isPresent());
      IllegalArgumentException iae = assertThrows(IllegalArgumentException.class, () -> SpiffeUtil
          .extractSpiffeId(multipleUriSanCert));
      assertEquals("Multiple URI SAN values found in the leaf cert.", iae.getMessage());

    }

    @Test
    public void extractSpiffeIdFromChainTest() throws Exception {
      // Check that the SPIFFE ID is extracted only from the leaf cert in the chain (spiffeCert
      // contains it, but serverCert0 does not).
      X509Certificate[] leafWithSpiffeChain = new X509Certificate[]{spiffeCert[0], serverCert0[0]};
      assertTrue(SpiffeUtil.extractSpiffeId(leafWithSpiffeChain).isPresent());
      X509Certificate[] leafWithoutSpiffeChain =
          new X509Certificate[]{serverCert0[0], spiffeCert[0]};
      assertFalse(SpiffeUtil.extractSpiffeId(leafWithoutSpiffeChain).isPresent());
    }

    @Test
    public void extractSpiffeIdParameterValidityTest() {
      NullPointerException npe = assertThrows(NullPointerException.class, () -> SpiffeUtil
          .extractSpiffeId(null));
      assertEquals("certChain", npe.getMessage());
      IllegalArgumentException iae = assertThrows(IllegalArgumentException.class, () -> SpiffeUtil
          .extractSpiffeId(new X509Certificate[]{}));
      assertEquals("certChain can't be empty", iae.getMessage());
    }

    @Test
    public void loadTrustBundleFromFileSuccessTest() throws Exception {
      SpiffeBundle tb = SpiffeUtil.loadTrustBundleFromFile(copyFileToTmp(SPIFFE_TRUST_BUNDLE));
      assertEquals(2, tb.getSequenceNumbers().size());
      assertEquals(12035488L, (long) tb.getSequenceNumbers().get("example.com"));
      assertEquals(-1L, (long) tb.getSequenceNumbers().get("test.example.com"));
      assertEquals(3, tb.getBundleMap().size());
      assertEquals(0, tb.getBundleMap().get("test.google.com.au").size());
      assertEquals(1, tb.getBundleMap().get("example.com").size());
      assertEquals(2, tb.getBundleMap().get("test.example.com").size());
      Optional<SpiffeId> spiffeId = SpiffeUtil.extractSpiffeId(tb.getBundleMap().get("example.com")
              .toArray(new X509Certificate[0]));
      assertTrue(spiffeId.isPresent());
      assertEquals("foo.bar.com", spiffeId.get().getTrustDomain());
    }

    @Test
    public void loadTrustBundleFromFileFailureTest() {
      // Check the exception if JSON root element is different from 'trust_domains'
      NullPointerException npe = assertThrows(NullPointerException.class, () -> SpiffeUtil
          .loadTrustBundleFromFile(copyFileToTmp(SPIFFE_TRUST_BUNDLE_WRONG_ROOT)));
      assertEquals("Mandatory trust_domains element is missing", npe.getMessage());
      // Check the exception if JSON root element is different from 'trust_domains'
      ClassCastException cce = assertThrows(ClassCastException.class, () -> SpiffeUtil
          .loadTrustBundleFromFile(copyFileToTmp(SPIFFE_TRUST_BUNDLE_WRONG_SEQ)));
      assertTrue(cce.getMessage().contains("Number expected to be long"));
      // Check the exception if JSON file doesn't contain an object
      IllegalArgumentException iae = assertThrows(IllegalArgumentException.class, () -> SpiffeUtil
          .loadTrustBundleFromFile(copyFileToTmp(SPIFFE_TRUST_BUNDLE_MALFORMED)));
      assertTrue(iae.getMessage().contains("SPIFFE Trust Bundle should be a JSON object."));
      // Check the exception if JSON contains duplicates
      iae = assertThrows(IllegalArgumentException.class, () -> SpiffeUtil
          .loadTrustBundleFromFile(copyFileToTmp(SPIFFE_TRUST_BUNDLE_DUPLICATES)));
      assertEquals("Duplicate key found: google.com", iae.getMessage());
      // Check the exception if 'x5c' value cannot be parsed
      iae = assertThrows(IllegalArgumentException.class, () -> SpiffeUtil
          .loadTrustBundleFromFile(copyFileToTmp(SPIFFE_TRUST_BUNDLE_CORRUPTED_CERT)));
      assertEquals("Certificate can't be parsed." + DOMAIN_ERROR_MESSAGE, iae.getMessage());
      // Check the exception if 'kty' value differs from 'RSA'
      iae = assertThrows(IllegalArgumentException.class, () -> SpiffeUtil
          .loadTrustBundleFromFile(copyFileToTmp(SPIFFE_TRUST_BUNDLE_WRONG_KTY)));
      assertEquals("'kty' parameter must be 'RSA' but 'null' found." + DOMAIN_ERROR_MESSAGE,
          iae.getMessage());
      // Check the exception if 'kid' has a value
      iae = assertThrows(IllegalArgumentException.class, () -> SpiffeUtil
          .loadTrustBundleFromFile(copyFileToTmp(SPIFFE_TRUST_BUNDLE_WRONG_KID)));
      assertEquals("'kid' parameter must not be set." + DOMAIN_ERROR_MESSAGE, iae.getMessage());
      // Check the exception if 'use' value differs from 'x509-svid'
      iae = assertThrows(IllegalArgumentException.class, () -> SpiffeUtil
          .loadTrustBundleFromFile(copyFileToTmp(SPIFFE_TRUST_BUNDLE_WRONG_USE)));
      assertEquals("'use' parameter must be 'x509-svid' but 'i_am_not_x509-svid' found."
          + DOMAIN_ERROR_MESSAGE, iae.getMessage());
      // Check the exception if multiple certs are provided for 'x5c'
      iae = assertThrows(IllegalArgumentException.class, () -> SpiffeUtil
          .loadTrustBundleFromFile(copyFileToTmp(SPIFFE_TRUST_BUNDLE_WRONG_MULTI_CERTS)));
      assertEquals("Exactly 1 certificate is expected, but 2 found." + DOMAIN_ERROR_MESSAGE,
          iae.getMessage());
    }

    @Test
    public void loadTrustBundleFromFileParameterValidityTest() {
      NullPointerException npe = assertThrows(NullPointerException.class, () -> SpiffeUtil
          .loadTrustBundleFromFile(null));
      assertEquals("trustBundleFile", npe.getMessage());
      FileNotFoundException nsfe = assertThrows(FileNotFoundException.class, () -> SpiffeUtil
          .loadTrustBundleFromFile("i_do_not_exist"));
      assertTrue(
          "Did not contain expected substring: " + nsfe.getMessage(),
          nsfe.getMessage().contains("i_do_not_exist"));
    }
  }
}
