package io.grpc.util;

import static com.google.common.base.Preconditions.checkArgument;
import static org.junit.Assert.*;

import io.grpc.util.SpiffeIdParser.SpiffeIdInfo;
import org.junit.Test;

public class SpiffeIdParserImplTest {

  private SpiffeIdParserImpl spiffeIdParser = new SpiffeIdParserImpl();

  @Test
  public void parseHappyPathTest() {
    SpiffeIdInfo spiffeIdInfo = spiffeIdParser.parse("spiffe://example.com");
    assertEquals("example.com", spiffeIdInfo.getTrustDomain());
    assertEquals("", spiffeIdInfo.getPath());
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
  }
}