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

  /**
   * Constructor.
   *
   * @param remoteAddress address of the remote peer
   */
  public GrpcSession(SocketAddress remoteAddress) {
    this.remoteAddress = remoteAddress;
  }

  /**
   * Gets the address of the remote peer of this connection.
   *
   * @return address of remote peer
   */
  public SocketAddress getRemoteAddress() {
    return remoteAddress;
  }

}
