package io.grpc;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSession;
import javax.security.cert.X509Certificate;

import java.net.SocketAddress;

/**
 * Holds state pertaining to a single transport connection
 */
public class GrpcSession {

  private final SocketAddress remoteAddress;
  private final SSLEngine sslEngine;

  public GrpcSession(SocketAddress remoteAddress, SSLEngine sslEngine) {
    this.remoteAddress = remoteAddress;
    this.sslEngine = sslEngine;
  }

  public SocketAddress getRemoteAddress() {
    return remoteAddress;
  }

  public SSLSession getSslSession() {
    SSLSession session = null;
    if (sslEngine != null) {
      session = sslEngine.getSession();
    }
    return session;
  }
}
