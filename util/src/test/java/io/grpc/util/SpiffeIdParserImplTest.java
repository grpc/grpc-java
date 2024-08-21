package io.grpc.util;

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
}