package io.grpc.util;

public interface SpiffeIdParser {
  interface SpiffeIdInfo {
    String getTrustDomain();
    String getPath();
  }

  SpiffeIdInfo parse(String uri);
}
