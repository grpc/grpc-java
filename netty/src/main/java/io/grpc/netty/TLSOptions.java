package io.grpc.netty;

import java.security.KeyStore;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLEngine;

public abstract class TLSOptions {
  private TLSAuthType authType;
  private TLSHostnameCheckType checkType;
  public TLSOptions(TLSAuthType authType, TLSHostnameCheckType checkType) {
    this.authType = authType;
    this.checkType = checkType;
  }

  public TLSAuthType getAuthType() {
    return this.authType;
  }

  public TLSHostnameCheckType getCheckType() {
    return this.checkType;
  }

  // used to perform server authorization checking
  abstract boolean VerifyPeerCertificate (X509Certificate[] peerCertChain, String authType, SSLEngine engine);

  // used to perform trust CA certificates reloading
  abstract KeyStore getTrustedCerts();
}
