package io.grpc.util;

import static org.junit.Assert.*;

import io.grpc.util.SpiffeIdParser.SpiffeIdInfo;
import java.util.Arrays;
import java.util.Collection;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;


@RunWith(Parameterized.class)
public class SpiffeIdParserImplTest {

  private SpiffeIdParserImpl spiffeIdParser = new SpiffeIdParserImpl();


  @Parameter
  public String uri;

  @Parameter(1)
  public String trustDomain;

  @Parameter(2)
  public String path;

  @Test
  public void parseSuccessTest() {
    SpiffeIdInfo spiffeIdInfo = spiffeIdParser.parse(uri);
    assertEquals(trustDomain, spiffeIdInfo.getTrustDomain());
    assertEquals(path, spiffeIdInfo.getPath());
  }

  @Parameters(name = "spiffeId={0}")
  public static Collection<String[]> successData() {
    return Arrays.asList(new String[][] {
        { "spiffe://example.com", "example.com", ""},
        { "spiffe://example.com/us", "example.com", "/us"},
        { "spIFfe://qa-staging.final_check.example.com/us", "qa-staging.final_check.example.com",
            "/us"},
        { "spiffe://example.com/country/us/state/FL/city/Miami", "example.com",
            "/country/us/state/FL/city/Miami"},
        { "SPIFFE://example.com/Czech.Republic/region0.1/city_of-Prague", "example.com",
            "/Czech.Republic/region0.1/city_of-Prague"},
        { "spiffe://trust-domain-name/path", "trust-domain-name", "/path"},
        { "spiffe://staging.example.com/payments/mysql", "staging.example.com", "/payments/mysql"},
        { "spiffe://staging.example.com/payments/web-fe", "staging.example.com", "/payments/web-fe"},
        { "spiffe://k8s-west.example.com/ns/staging/sa/default", "k8s-west.example.com",
            "/ns/staging/sa/default"},
        { "spiffe://example.com/9eebccd2-12bf-40a6-b262-65fe0487d453", "example.com",
            "/9eebccd2-12bf-40a6-b262-65fe0487d453"},
    });
  }

  @Test
  public void spiffeUriFormatTest() {
    NullPointerException npe = assertThrows(NullPointerException.class, () ->
        spiffeIdParser.parse(null));
    assertEquals("uri", npe.getMessage());

    IllegalArgumentException iae = assertThrows(IllegalArgumentException.class, () ->
        spiffeIdParser.parse("https://example.com"));
    assertEquals("Spiffe Id must start with spiffe://", iae.getMessage());

    iae = assertThrows(IllegalArgumentException.class, () ->
        spiffeIdParser.parse("spiffe://example.com/workload#1"));
    assertEquals("Spiffe Id must not contain query fragments", iae.getMessage());

    iae = assertThrows(IllegalArgumentException.class, () ->
        spiffeIdParser.parse("spiffe://example.com/workload-1?t=1"));
    assertEquals("Spiffe Id must not contain query parameters", iae.getMessage());
  }

  @Test
  public void spiffeTrustDomainFormatTest() {
    IllegalArgumentException iae = assertThrows(IllegalArgumentException.class, () ->
        spiffeIdParser.parse("spiffe://"));
    assertEquals("Trust Domain can't be empty", iae.getMessage());

    iae = assertThrows(IllegalArgumentException.class, () ->
        spiffeIdParser.parse("spiffe:///"));
    assertEquals("Trust Domain can't be empty", iae.getMessage());

    iae = assertThrows(IllegalArgumentException.class, () ->
        spiffeIdParser.parse("spiffe://eXample.com"));
    assertEquals("Trust Domain must contain only letters, numbers, dots, dashes, and underscores ([a-z0-9.-_])", iae.getMessage());

    iae = assertThrows(IllegalArgumentException.class, () ->
        spiffeIdParser.parse("spiffe://example!com"));
    assertEquals("Trust Domain must contain only letters, numbers, dots, dashes, and underscores ([a-z0-9.-_])", iae.getMessage());

    iae = assertThrows(IllegalArgumentException.class, () ->
        spiffeIdParser.parse("spiffe://exampleя.com/workload-1"));
    assertEquals("Trust Domain must contain only letters, numbers, dots, dashes, and underscores ([a-z0-9.-_])", iae.getMessage());

    StringBuilder longTrustDomain = new StringBuilder("spiffe://pi.eu.");
    for (int i = 0; i<50; i++) {
      longTrustDomain.append("pi.eu");
    }
    iae = assertThrows(IllegalArgumentException.class, () ->
        spiffeIdParser.parse(longTrustDomain.toString()));
    assertEquals("Trust Domain maximum length is 255 characters", iae.getMessage());
  }

  @Test
  public void spiffePathFormatTest() {
    IllegalArgumentException iae = assertThrows(IllegalArgumentException.class, () ->
        spiffeIdParser.parse("spiffe://example.com//"));
    assertEquals("Path must not include a trailing '/'", iae.getMessage());

    iae = assertThrows(IllegalArgumentException.class, () ->
        spiffeIdParser.parse("spiffe://example.com/us//miami"));
    assertEquals("Individual path segments must not be empty", iae.getMessage());

    iae = assertThrows(IllegalArgumentException.class, () ->
        spiffeIdParser.parse("spiffe://example.com/us/."));
    assertEquals("Individual path segments must not be relative path modifiers (i.e. ., ..)",
        iae.getMessage());

    iae = assertThrows(IllegalArgumentException.class, () ->
        spiffeIdParser.parse("spiffe://example.com/us!"));
    assertEquals("Individual path segments must contain only letters, numbers, dots, dashes, and "
            + "underscores ([a-zA-Z0-9.-_])", iae.getMessage());

    iae = assertThrows(IllegalArgumentException.class, () ->
        spiffeIdParser.parse("spiffe://example.com/us/florida/miamiя"));
    assertEquals("Individual path segments must contain only letters, numbers, dots, dashes, and "
        + "underscores ([a-zA-Z0-9.-_])", iae.getMessage());

  }
}