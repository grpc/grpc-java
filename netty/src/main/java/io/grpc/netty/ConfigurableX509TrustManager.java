/*
 * Copyright 2020 The gRPC Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.grpc.netty;

import io.grpc.netty.TlsOptions.VerificationAuthType;
import java.net.Socket;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;

public class ConfigurableX509TrustManager extends X509ExtendedTrustManager {

  private TlsOptions tlsOptions;

  public ConfigurableX509TrustManager(TlsOptions tlsOptions) {
    this.tlsOptions = tlsOptions;
  }

  @Override
  public void checkClientTrusted(X509Certificate[] x509Certificates, String s, Socket socket)
      throws CertificateException {

  }

  @Override
  public void checkClientTrusted(X509Certificate[] x509Certificates, String s, SSLEngine sslEngine)
      throws CertificateException {
    checkTrusted(x509Certificates, s, sslEngine, false);
  }

  @Override
  public void checkClientTrusted(X509Certificate[] x509Certificates, String s)
      throws CertificateException {

  }

  @Override
  public void checkServerTrusted(X509Certificate[] x509Certificates, String s, Socket socket)
      throws CertificateException {

  }

  @Override
  public void checkServerTrusted(X509Certificate[] x509Certificates, String s, SSLEngine sslEngine)
      throws CertificateException {
    checkTrusted(x509Certificates, s, sslEngine, true);
  }

  @Override
  public void checkServerTrusted(X509Certificate[] x509Certificates, String s)
      throws CertificateException {

  }

  @Override
  public X509Certificate[] getAcceptedIssuers() {
    return new X509Certificate[0];
  }

  private void checkTrusted(X509Certificate[] x509Certificates, String s, SSLEngine sslEngine,
      boolean isClient) throws CertificateException {
    VerificationAuthType authType = this.tlsOptions.getVerificationAuthType();
    if (authType == VerificationAuthType.CertificateAndHostNameVerification
        || authType == VerificationAuthType.CertificateVerification) {
      if (x509Certificates == null || x509Certificates.length == 0) {
        throw new CertificateException(
            "Client side requires certificate but got null or empty certificates");
      }
      KeyStore ks;
      try {
        ks = this.tlsOptions.getTrustedCerts();
      } catch (Exception e) {
        throw new CertificateException("Function getTrustedCerts fails, error: " + e.getMessage());
      }
      X509ExtendedTrustManager delegateManager = null;
      try {
        final TrustManagerFactory tmf = TrustManagerFactory
            .getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);
        TrustManager[] tms = tmf.getTrustManagers();
        // Iterate over the returned trust managers, looking for an instance of X509TrustManager.
        // If found, use that as the delegate trust manager.
        for (int i = 0; i < tms.length; i++) {
          if (tms[i] instanceof X509ExtendedTrustManager) {
            delegateManager = (X509ExtendedTrustManager) tms[i];
            break;
          }
        }
        if (delegateManager == null) {
          throw new CertificateException(
              "Instance delegateX509TrustManager is null. Failed to initialize");
        }
      } catch (Exception e) {
        throw new CertificateException("Failed to initialize delegateX509TrustManager, error: "
            + e.getMessage());
      }
      if (isClient) {
        String algorithm = authType == VerificationAuthType.CertificateAndHostNameVerification
            ? "HTTPS" : "";
        SSLParameters sslParams = sslEngine.getSSLParameters();
        sslParams.setEndpointIdentificationAlgorithm(algorithm);
        sslEngine.setSSLParameters(sslParams);
        delegateManager.checkServerTrusted(x509Certificates, s, sslEngine);
      } else {
        delegateManager.checkClientTrusted(x509Certificates, s, sslEngine);
      }
    }
    // Perform custom check
    try {
      this.tlsOptions.verifyPeerCertificate(x509Certificates, s, sslEngine);
    } catch (Exception e) {
      throw new CertificateException("Custom authorization check fails, error: " + e.getMessage());
    }
  }
}
