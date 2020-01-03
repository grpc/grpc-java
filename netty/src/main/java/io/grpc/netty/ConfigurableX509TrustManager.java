package io.grpc.netty;

import java.net.Socket;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;
import javax.net.ssl.X509TrustManager;

public class ConfigurableX509TrustManager extends X509ExtendedTrustManager {

  private TLSOptions tlsOptions;

  public ConfigurableX509TrustManager(TLSOptions tlsOptions) {
    this.tlsOptions = tlsOptions;
  }

  @Override
  public void checkClientTrusted(X509Certificate[] x509Certificates, String s, Socket socket)
      throws CertificateException {

  }

  @Override
  public void checkServerTrusted(X509Certificate[] x509Certificates, String s, Socket socket)
      throws CertificateException {

  }

  @Override
  public void checkClientTrusted(X509Certificate[] x509Certificates, String s, SSLEngine sslEngine)
      throws CertificateException {
    checkTrusted(x509Certificates, s, sslEngine, false);
  }

  @Override
  public void checkServerTrusted(X509Certificate[] x509Certificates, String s, SSLEngine sslEngine)
      throws CertificateException {
    checkTrusted(x509Certificates, s, sslEngine, true);
  }

  @Override
  public void checkClientTrusted(X509Certificate[] x509Certificates, String s)
      throws CertificateException {

  }

  @Override
  public void checkServerTrusted(X509Certificate[] x509Certificates, String s)
      throws CertificateException {

  }

  @Override
  public X509Certificate[] getAcceptedIssuers() {
    return new X509Certificate[0];
  }

  private void checkTrusted(X509Certificate[] x509Certificates, String s, SSLEngine sslEngine, boolean isClient)
      throws CertificateException {
    if (!isClient && this.tlsOptions.getAuthType() == TLSAuthType.NoPeerCert) {
      return;
    }
    if (x509Certificates == null || x509Certificates.length == 0) {
      throw new CertificateException("Client side requires certificate but got null or empty certificates");
    }
    if (this.tlsOptions.getAuthType() == TLSAuthType.RequireAndVerifyPeerCert) {
      KeyStore ks = this.tlsOptions.getTrustedCerts();
      try {
        final TrustManagerFactory tmf = TrustManagerFactory
            .getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);
        TrustManager[] tms = tmf.getTrustManagers();
        X509TrustManager delegateX509TrustManager = null;
        /*
         * Iterate over the returned trust managers, looking
         * for an instance of X509TrustManager.  If found,
         * use that as the delegate trust manager.
         */
        for (int i = 0; i < tms.length; i++) {
          if (tms[i] instanceof X509TrustManager) {
            delegateX509TrustManager = (X509TrustManager) tms[i];
            break;
          }
        }
        if (delegateX509TrustManager == null) {
          throw new CertificateException("failed to initialize delegateX509TrustManager, error: delegateX509TrustManager is null");
        }
        delegateX509TrustManager.checkServerTrusted(x509Certificates, s);
      } catch (Exception e) {
        throw new CertificateException("failed to initialize delegateX509TrustManager, error: " + e.getMessage());
      }
    }
    if (isClient && this.tlsOptions.getCheckType() == TLSHostnameCheckType.CustomCheck) {
      // Disable default hostname check
      sslEngine.getSSLParameters().setEndpointIdentificationAlgorithm(null);
      // Perform custom check
      if (!this.tlsOptions.VerifyPeerCertificate(x509Certificates, s, sslEngine)) {
        throw new CertificateException("custom authorization error");
      }
    }
  }
}
