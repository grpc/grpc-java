package io.grpc;

import java.net.SocketAddress;
import java.security.cert.Certificate;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;

/**
 * Holds state pertaining to a single transport connection.
 */
public class GrpcSession {

  private final SocketAddress remoteAddress;
  private final SSLEngine sslEngine;

  /**
   * Constructor.
   *
   * @param remoteAddress address of the remote peer
   * @param sslEngine     the SSLEngine used for this connection, or null if SSL not used
   */
  public GrpcSession(SocketAddress remoteAddress, SSLEngine sslEngine) {
    this.remoteAddress = remoteAddress;
    this.sslEngine = sslEngine;
  }

  /**
   * Gets the address of the remote peer of this connection.
   *
   * @return address of remote peer
   */
  public SocketAddress getRemoteAddress() {
    return remoteAddress;
  }

  /**
   * Gets the SSLSession, throwing if not available.
   * Clients should call isSsl first, to check that we are using SSL.
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
   * Check if we are using SSL/TLS.
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
   * Clients should call isSsl first, to check that we are using SSL.
   *
   * @return peer SSL certificate chain
   * @throws SSLPeerUnverifiedException if the peer has not presented a valid client certificate.
   */
  public Certificate[] getSslPeerCertificates() throws SSLPeerUnverifiedException {
    return getSslSession().getPeerCertificates();
  }

  /**
   * Returns the SSL protocol in use.
   * Clients should call isSsl first, to check that we are using SSL.
   */
  public String getSslProtocol() {
    return getSslSession().getProtocol();
  }

  /**
   * Returns the SSL cipher suite in use.
   * Clients should call isSsl first, to check that we are using SSL.
   */
  public String getSslCipherSuite() {
    return getSslSession().getCipherSuite();
  }
}
