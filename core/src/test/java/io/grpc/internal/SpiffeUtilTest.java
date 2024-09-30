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
import io.grpc.internal.SpiffeUtil.SpiffeBundle;
import io.grpc.internal.SpiffeUtil.SpiffeId;
import io.grpc.testing.TlsTesting;
import io.grpc.util.CertificateUtils;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
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

      StringBuilder longSpiffe = new StringBuilder(String.format("spiffe://mydomain%scom/", "%21"));
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
      Optional<SpiffeId> spiffeId = SpiffeUtil.extractSpiffeId(spiffeCert);
      assertEquals("foo.bar.com", spiffeId.get().getTrustDomain());
      assertEquals("/client/workload/1", spiffeId.get().getPath());
    }

    @Test
    public void extractSpiffeIdFailureTest() throws CertificateParsingException {
      Optional<SpiffeUtil.SpiffeId> spiffeId = SpiffeUtil.extractSpiffeId(serverCert0);
      assertFalse(spiffeId.isPresent());
      IllegalArgumentException iae = assertThrows(IllegalArgumentException.class, () -> SpiffeUtil
          .extractSpiffeId(spiffeMultiCert));
      assertEquals("Multiple URI SAN values found in the leaf cert.", iae.getMessage());

    }

    @Test
    public void extractSpiffeIdFromChainTest() throws CertificateParsingException {
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
      assertEquals("CertChain can't be empty", iae.getMessage());
    }

    @Test
    public void loadTrustBundleFromFileSuccessTest() throws IOException,
        CertificateParsingException {
      SpiffeBundle tb = SpiffeUtil.loadTrustBundleFromFile(getClass().getClassLoader()
          .getResource(TEST_DIRECTORY_PREFIX + SPIFFE_TRUST_BUNDLE_FILE).getPath());
      assertEquals(4, tb.getSequenceNumbers().size());
      assertEquals(123L, (long) tb.getSequenceNumbers().get("google.com"));
      assertEquals(123L, (long) tb.getSequenceNumbers().get("test.google.com"));
      assertEquals(12035488L, (long) tb.getSequenceNumbers().get("example.com"));
      assertEquals(-1L, (long) tb.getSequenceNumbers().get("test.example.com"));
      assertEquals(5, tb.getBundleMap().size());
      assertEquals(0, tb.getBundleMap().get("google.com").size());
      assertEquals(0, tb.getBundleMap().get("test.google.com").size());
      assertEquals(0, tb.getBundleMap().get("test.google.com.au").size());
      assertEquals(1, tb.getBundleMap().get("example.com").size());
      assertEquals(2, tb.getBundleMap().get("test.example.com").size());
      assertEquals("foo.bar.com", SpiffeUtil.extractSpiffeId(tb.getBundleMap().get("example.com")
          .toArray(new X509Certificate[0])).get().getTrustDomain());
    }

    @Test
    public void loadTrustBundleFromFileFailureTest() throws IOException, CertificateException {
      NullPointerException npe = assertThrows(NullPointerException.class, () -> SpiffeUtil
          .loadTrustBundleFromFile(getClass().getClassLoader().getResource(TEST_DIRECTORY_PREFIX
              + SPIFFE_TRUST_BUNDLE_WITH_WRONG_ROOT).getPath()));
      assertEquals("Mandatory trust_domains element is missing", npe.getMessage());
      IllegalArgumentException iae = assertThrows(IllegalArgumentException.class, () -> SpiffeUtil
          .loadTrustBundleFromFile(getClass().getClassLoader().getResource(TEST_DIRECTORY_PREFIX
              + SPIFFE_TRUST_BUNDLE_MALFORMED).getPath()));
      assertTrue(iae.getMessage().contains("SPIFFE Trust Bundle should be a JSON object."));
      iae = assertThrows(IllegalArgumentException.class, () -> SpiffeUtil
          .loadTrustBundleFromFile(getClass().getClassLoader().getResource(TEST_DIRECTORY_PREFIX
              + SPIFFE_TRUST_BUNDLE_DUPLICATES).getPath()));
      assertTrue(iae.getMessage().contains("Duplicate key found: google.com"));
      SpiffeBundle tb = SpiffeUtil.loadTrustBundleFromFile(getClass().getClassLoader()
          .getResource(TEST_DIRECTORY_PREFIX + SPIFFE_TRUST_BUNDLE_WRONG_ELEMENTS).getPath());
      assertEquals(5, tb.getBundleMap().size());
      for (List<X509Certificate> certs: tb.getBundleMap().values()) {
        assertEquals(0, certs.size());
      }
    }

    @Test
    public void loadTrustBundleFromFileParameterValidityTest() {
      NullPointerException npe = assertThrows(NullPointerException.class, () -> SpiffeUtil
          .loadTrustBundleFromFile(null));
      assertEquals("trustBundleFile", npe.getMessage());
      NoSuchFileException nsfe = assertThrows(NoSuchFileException.class, () -> SpiffeUtil
          .loadTrustBundleFromFile("i_do_not_exist"));
      assertEquals("i_do_not_exist", nsfe.getMessage());
    }
  }
}