package io.grpc;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import javax.security.cert.X509Certificate;

import java.net.SocketAddress;
import java.security.cert.Certificate;

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

  /**
   * Gets the SSLSession, throwing if not available.
   *
   * Clients should call isSsl first.
   */
  protected SSLSession getSslSession() {
    SSLSession session = null;
    if (sslEngine != null) {
      session = sslEngine.getSession();
    }
    if (session == null) {
      throw new IllegalStateException("Not using SSL");
    }
    return session;
  }

  /**
   * Check if we are using SSL/TLS
   *
   * @return true if this connection is using TLS/SSL
   */
  public boolean isSsl() {
    if (sslEngine == null) {
      return false;
    }
    SSLSession session = sslEngine.getSession();
    return session != null;
  }

  /**
   * Gets the peer's SSL certificate chain.
   *
   * Clients should call isSsl first, to check that we are using SSL.
   *
   * @return peer SSL certificate chain
   * @throws SSLPeerUnverifiedException if the peer has not presented a valid client certificate.
   */
  public Certificate[] getSslPeerCertificates() throws SSLPeerUnverifiedException {
    return getSslSession().getPeerCertificates();
  }

  /**
   * Returns the SSL protocol in use
   *
   * Clients should call isSsl first, to check that we are using SSL.
   */
  public String getSslProtocol() {
    return getSslSession().getProtocol();
  }

  /**
   * Returns the SSL cipher suite in use
   *
   * Clients should call isSsl first, to check that we are using SSL.
   */
  public String getSslCipherSuite() {
    return getSslSession().getCipherSuite();
  }
}
