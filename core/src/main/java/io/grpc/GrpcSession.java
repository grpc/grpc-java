package io.grpc;

import javax.security.cert.X509Certificate;
import java.net.SocketAddress;

/**
 * Holds state pertaining to a single transport connection
 */
public class GrpcSession {

  private final SocketAddress remoteAddress;
  private X509Certificate[] peerCertificateChain;

  public GrpcSession(SocketAddress remoteAddress) {
    this.remoteAddress = remoteAddress;
  }

  public void setPeerCertificateChain(X509Certificate[] peerCertificateChain) {
    this.peerCertificateChain = peerCertificateChain;
  }

  public X509Certificate[] getPeerCertificateChain() {
    return peerCertificateChain;
  }

  static final ThreadLocal<GrpcSession> THREAD_LOCAL = new ThreadLocal<GrpcSession>();

  /**
   * Gets the active GrpcSession (from the ThreadLocal)
   *
   * @return active GrpcSession
   */
  public static GrpcSession get() {
    return THREAD_LOCAL.get();
  }

  /**
   * Sets the active GrpcSession (sets the ThreadLocal)
   *
   * Should only be called when no GrpcSession is active.
   *
   * @param session GrpcSession to set as active
   */
  static void enter(GrpcSession session) {
    assert THREAD_LOCAL.get() == null;
    THREAD_LOCAL.set(session);
  }

  /**
   * Gets the active GrpcSession (clears the ThreadLocal)
   *
   * Should only be called when a GrpcSession is active.
   */
  static void exit() {
    assert THREAD_LOCAL.get() != null;
    THREAD_LOCAL.set(null);
  }

  public SocketAddress getRemoteAddress() {
    return remoteAddress;
  }
}
