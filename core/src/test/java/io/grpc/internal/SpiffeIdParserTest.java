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
import static org.junit.Assert.assertThrows;

import java.util.Arrays;
import java.util.Collection;
import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;


@RunWith(Enclosed.class)
public class SpiffeIdParserTest {

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
      SpiffeIdParser.SpiffeId spiffeId = SpiffeIdParser.parse(uri);
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
          {"spiffe://trustdomain/abc0123.-_", "trustdomain", "/abc0123.-_"},
          {"spiffe://trustdomain/.a..", "trustdomain", "/.a.."},
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
      assertThrows(IllegalArgumentException.class, () -> SpiffeIdParser.parse(uri));
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
          "spiffe://trust.domain/../path"
          );
    }
  }

  public static class ExceptionMessageTest {

    @Test
    public void spiffeUriFormatTest() {
      NullPointerException npe = assertThrows(NullPointerException.class, () ->
          SpiffeIdParser.parse(null));
      assertEquals("uri", npe.getMessage());

      IllegalArgumentException iae = assertThrows(IllegalArgumentException.class, () ->
          SpiffeIdParser.parse("https://example.com"));
      assertEquals("Spiffe Id must start with spiffe://", iae.getMessage());

      iae = assertThrows(IllegalArgumentException.class, () ->
          SpiffeIdParser.parse("spiffe://example.com/workload#1"));
      assertEquals("Spiffe Id must not contain query fragments", iae.getMessage());

      iae = assertThrows(IllegalArgumentException.class, () ->
          SpiffeIdParser.parse("spiffe://example.com/workload-1?t=1"));
      assertEquals("Spiffe Id must not contain query parameters", iae.getMessage());
    }

    @Test
    public void spiffeTrustDomainFormatTest() {
      IllegalArgumentException iae = assertThrows(IllegalArgumentException.class, () ->
          SpiffeIdParser.parse("spiffe://"));
      assertEquals("Trust Domain can't be empty", iae.getMessage());

      iae = assertThrows(IllegalArgumentException.class, () ->
          SpiffeIdParser.parse("spiffe://eXample.com"));
      assertEquals(
          "Trust Domain must contain only letters, numbers, dots, dashes, and underscores "
              + "([a-z0-9.-_])",
          iae.getMessage());

      StringBuilder longTrustDomain = new StringBuilder("spiffe://pi.eu.");
      for (int i = 0; i < 50; i++) {
        longTrustDomain.append("pi.eu");
      }
      iae = assertThrows(IllegalArgumentException.class, () ->
          SpiffeIdParser.parse(longTrustDomain.toString()));
      assertEquals("Trust Domain maximum length is 255 characters", iae.getMessage());
    }

    @Test
    public void spiffePathFormatTest() {
      IllegalArgumentException iae = assertThrows(IllegalArgumentException.class, () ->
          SpiffeIdParser.parse("spiffe://example.com//"));
      assertEquals("Path must not include a trailing '/'", iae.getMessage());

      iae = assertThrows(IllegalArgumentException.class, () ->
          SpiffeIdParser.parse("spiffe://example.com/us//miami"));
      assertEquals("Individual path segments must not be empty", iae.getMessage());

      iae = assertThrows(IllegalArgumentException.class, () ->
          SpiffeIdParser.parse("spiffe://example.com/us/."));
      assertEquals("Individual path segments must not be relative path modifiers (i.e. ., ..)",
          iae.getMessage());

      iae = assertThrows(IllegalArgumentException.class, () ->
          SpiffeIdParser.parse("spiffe://example.com/us!"));
      assertEquals("Individual path segments must contain only letters, numbers, dots, dashes, and "
          + "underscores ([a-zA-Z0-9.-_])", iae.getMessage());
    }
  }
}