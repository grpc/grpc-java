package io.grpc.netty;

import io.grpc.Status;
import io.grpc.internal.AuthorityVerifier;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import javax.annotation.Nonnull;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.X509TrustManager;

public class X509AuthorityVerifier implements AuthorityVerifier {
  private final SSLEngine sslEngine;
  private final X509TrustManager x509ExtendedTrustManager;

  private static final Method checkServerTrustedMethod;

  static {
    Method method = null;
    try {
      Class<?> x509ExtendedTrustManagerClass =
              Class.forName("javax.net.ssl.X509ExtendedTrustManager");
      method = x509ExtendedTrustManagerClass.getMethod("checkServerTrusted",
              X509Certificate[].class, String.class, SSLEngine.class);
    } catch (ClassNotFoundException e) {
      // Per-rpc authority overriding via call options will be disallowed.
    } catch (NoSuchMethodException e) {
      // Should never happen since X509ExtendedTrustManager was introduced in Android API level 24
      // along with checkServerTrusted.
    }
    checkServerTrustedMethod = method;
  }

  public X509AuthorityVerifier(SSLEngine sslEngine, X509TrustManager x509ExtendedTrustManager) {
    this.sslEngine = sslEngine;
    this.x509ExtendedTrustManager = x509ExtendedTrustManager;
  }

  public Status verifyAuthority(@Nonnull String authority) {
    // sslEngine won't be set when creating ClientTlsHandler from InternalProtocolNegotiators
    // for example.
    if (sslEngine == null || x509ExtendedTrustManager == null) {
      return Status.FAILED_PRECONDITION.withDescription(
              "Can't allow authority override in rpc when SslEngine or X509ExtendedTrustManager"
                      + " is not available");
    }
    Status peerVerificationStatus;
    try {
      verifyAuthorityAllowedForPeerCert(authority);
      peerVerificationStatus = Status.OK;
    } catch (SSLPeerUnverifiedException | CertificateException | InvocationTargetException
             | IllegalAccessException | IllegalStateException e) {
      peerVerificationStatus = Status.UNAVAILABLE.withDescription(
              String.format("Peer hostname verification during rpc failed for authority '%s'",
                      authority)).withCause(e);
    }
    return peerVerificationStatus;
  }

  private void verifyAuthorityAllowedForPeerCert(String authority)
          throws SSLPeerUnverifiedException, CertificateException, InvocationTargetException,
          IllegalAccessException {
    SSLEngine sslEngineWrapper = new ProtocolNegotiators.SslEngineWrapper(sslEngine, authority);
    // The typecasting of Certificate to X509Certificate should work because this method will only
    // be called when using TLS and thus X509.
    Certificate[] peerCertificates = sslEngine.getSession().getPeerCertificates();
    X509Certificate[] x509PeerCertificates = new X509Certificate[peerCertificates.length];
    for (int i = 0; i < peerCertificates.length; i++) {
      x509PeerCertificates[i] = (X509Certificate) peerCertificates[i];
    }
    checkServerTrustedMethod.invoke(
            x509ExtendedTrustManager, x509PeerCertificates, "RSA", sslEngineWrapper);
  }
}
