/*
 * Copyright 2021 The gRPC Authors
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

package io.grpc.util;

import io.grpc.ExperimentalApi;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;
import org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement;

/**
 * AdvancedTlsX509TrustManager is an {@code X509ExtendedTrustManager} that allows users to configure
 * advanced TLS features, such as root certificate reloading, peer cert custom verification, etc.
 * For Android users: this class is only supported in API level 24 and above.
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/8024")
@IgnoreJRERequirement
public final class AdvancedTlsX509TrustManager extends X509ExtendedTrustManager {
  private static final Logger log = Logger.getLogger(AdvancedTlsX509TrustManager.class.getName());

  private final Verification verification;
  private final SslSocketAndEnginePeerVerifier socketAndEnginePeerVerifier;

  // The delegated trust manager used to perform traditional certificate verification.
  private volatile X509ExtendedTrustManager delegateManager = null;

  private AdvancedTlsX509TrustManager(Verification verification,
      SslSocketAndEnginePeerVerifier socketAndEnginePeerVerifier) throws CertificateException {
    this.verification = verification;
    this.socketAndEnginePeerVerifier = socketAndEnginePeerVerifier;
  }

  @Override
  public void checkClientTrusted(X509Certificate[] chain, String authType)
      throws CertificateException {
    throw new CertificateException(
        "Not enough information to validate peer. SSLEngine or Socket required.");
  }

  @Override
  public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket)
      throws CertificateException {
    checkTrusted(chain, authType, null, socket, false);
  }

  @Override
  public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
      throws CertificateException {
    checkTrusted(chain, authType, engine, null, false);
  }

  @Override
  public void checkServerTrusted(X509Certificate[] chain,  String authType, SSLEngine engine)
      throws CertificateException {
    checkTrusted(chain, authType, engine, null, true);
  }

  @Override
  public void checkServerTrusted(X509Certificate[] chain, String authType)
      throws CertificateException {
    throw new CertificateException(
        "Not enough information to validate peer. SSLEngine or Socket required.");
  }

  @Override
  public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket)
      throws CertificateException {
    checkTrusted(chain, authType, null, socket, true);
  }

  @Override
  public X509Certificate[] getAcceptedIssuers() {
    if (this.delegateManager == null) {
      return new X509Certificate[0];
    }
    return this.delegateManager.getAcceptedIssuers();
  }

  /**
   * Uses the default trust certificates stored on user's local system.
   * After this is used, functions that will provide new credential
   * data(e.g. updateTrustCredentials(), updateTrustCredentialsFromFile()) should not be called.
   */
  public void useSystemDefaultTrustCerts() throws CertificateException, KeyStoreException,
      NoSuchAlgorithmException {
    // Passing a null value of KeyStore would make {@code TrustManagerFactory} attempt to use
    // system-default trust CA certs.
    this.delegateManager = createDelegateTrustManager(null);
  }

  /**
   * Updates the current cached trust certificates as well as the key store.
   *
   * @param trustCerts the trust certificates that are going to be used
   */
  public void updateTrustCredentials(X509Certificate[] trustCerts) throws IOException,
      GeneralSecurityException {
    KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
    keyStore.load(null, null);
    int i = 1;
    for (X509Certificate cert: trustCerts) {
      String alias = Integer.toString(i);
      keyStore.setCertificateEntry(alias, cert);
      i++;
    }
    X509ExtendedTrustManager newDelegateManager = createDelegateTrustManager(keyStore);
    this.delegateManager = newDelegateManager;
  }

  private static X509ExtendedTrustManager createDelegateTrustManager(KeyStore keyStore)
      throws CertificateException, KeyStoreException, NoSuchAlgorithmException {
    TrustManagerFactory tmf = TrustManagerFactory.getInstance(
        TrustManagerFactory.getDefaultAlgorithm());
    tmf.init(keyStore);
    X509ExtendedTrustManager delegateManager = null;
    TrustManager[] tms = tmf.getTrustManagers();
    // Iterate over the returned trust managers, looking for an instance of X509TrustManager.
    // If found, use that as the delegate trust manager.
    for (int j = 0; j < tms.length; j++) {
      if (tms[j] instanceof X509ExtendedTrustManager) {
        delegateManager = (X509ExtendedTrustManager) tms[j];
        break;
      }
    }
    if (delegateManager == null) {
      throw new CertificateException(
          "Failed to find X509ExtendedTrustManager with default TrustManager algorithm "
              + TrustManagerFactory.getDefaultAlgorithm());
    }
    return delegateManager;
  }

  private void checkTrusted(X509Certificate[] chain, String authType, SSLEngine sslEngine,
      Socket socket, boolean checkingServer) throws CertificateException {
    if (chain == null || chain.length == 0) {
      throw new IllegalArgumentException(
          "Want certificate verification but got null or empty certificates");
    }
    if (sslEngine == null && socket == null) {
      throw new CertificateException(
          "Not enough information to validate peer. SSLEngine or Socket required.");
    }
    if (this.verification != Verification.INSECURELY_SKIP_ALL_VERIFICATION) {
      X509ExtendedTrustManager currentDelegateManager = this.delegateManager;
      if (currentDelegateManager == null) {
        throw new CertificateException("No trust roots configured");
      }
      if (checkingServer) {
        String algorithm = this.verification == Verification.CERTIFICATE_AND_HOST_NAME_VERIFICATION
            ? "HTTPS" : "";
        if (sslEngine != null) {
          SSLParameters sslParams = sslEngine.getSSLParameters();
          sslParams.setEndpointIdentificationAlgorithm(algorithm);
          sslEngine.setSSLParameters(sslParams);
          currentDelegateManager.checkServerTrusted(chain, authType, sslEngine);
        } else {
          if (!(socket instanceof SSLSocket)) {
            throw new CertificateException("socket is not a type of SSLSocket");
          }
          SSLSocket sslSocket = (SSLSocket)socket;
          SSLParameters sslParams = sslSocket.getSSLParameters();
          sslParams.setEndpointIdentificationAlgorithm(algorithm);
          sslSocket.setSSLParameters(sslParams);
          currentDelegateManager.checkServerTrusted(chain, authType, sslSocket);
        }
      } else {
        currentDelegateManager.checkClientTrusted(chain, authType, sslEngine);
      }
    }
    // Perform the additional peer cert check.
    if (socketAndEnginePeerVerifier != null) {
      if (sslEngine != null) {
        socketAndEnginePeerVerifier.verifyPeerCertificate(chain, authType, sslEngine);
      } else {
        socketAndEnginePeerVerifier.verifyPeerCertificate(chain, authType, socket);
      }
    }
  }

  /**
   * Schedules a {@code ScheduledExecutorService} to read trust certificates from a local file path
   * periodically, and update the cached trust certs if there is an update.
   *
   * @param trustCertFile  the file on disk holding the trust certificates
   * @param period the period between successive read-and-update executions
   * @param unit the time unit of the initialDelay and period parameters
   * @param executor the execute service we use to read and update the credentials
   * @return an object that caller should close when the file refreshes are not needed
   */
  public Closeable updateTrustCredentialsFromFile(File trustCertFile, long period, TimeUnit unit,
      ScheduledExecutorService executor) throws IOException, GeneralSecurityException {
    long updatedTime = readAndUpdate(trustCertFile, 0);
    if (updatedTime == 0) {
      throw new GeneralSecurityException(
          "Files were unmodified before their initial update. Probably a bug.");
    }
    final ScheduledFuture<?> future =
        executor.scheduleWithFixedDelay(
            new LoadFilePathExecution(trustCertFile), period, period, unit);
    return new Closeable() {
      @Override public void close() {
        future.cancel(false);
      }
    };
  }

  private class LoadFilePathExecution implements Runnable {
    File file;
    long currentTime;

    public LoadFilePathExecution(File file) {
      this.file = file;
      this.currentTime = 0;
    }

    @Override
    public void run() {
      try {
        this.currentTime = readAndUpdate(this.file, this.currentTime);
      } catch (IOException | GeneralSecurityException e) {
        log.log(Level.SEVERE, "Failed refreshing trust CAs from file. Using previous CAs", e);
      }
    }
  }

  /**
   * Updates the trust certificates from a local file path.
   *
   * @param trustCertFile  the file on disk holding the trust certificates
   */
  public void updateTrustCredentialsFromFile(File trustCertFile) throws IOException,
      GeneralSecurityException {
    long updatedTime = readAndUpdate(trustCertFile, 0);
    if (updatedTime == 0) {
      throw new GeneralSecurityException(
          "Files were unmodified before their initial update. Probably a bug.");
    }
  }

  /**
   * Reads the trust certificates specified in the path location, and update the key store if the
   * modified time has changed since last read.
   *
   * @param trustCertFile  the file on disk holding the trust certificates
   * @param oldTime the time when the trust file is modified during last execution
   * @return oldTime if failed or the modified time is not changed, otherwise the new modified time
   */
  private long readAndUpdate(File trustCertFile, long oldTime)
      throws IOException, GeneralSecurityException {
    long newTime = trustCertFile.lastModified();
    if (newTime == oldTime) {
      return oldTime;
    }
    FileInputStream inputStream = new FileInputStream(trustCertFile);
    try {
      X509Certificate[] certificates = CertificateUtils.getX509Certificates(inputStream);
      updateTrustCredentials(certificates);
      return newTime;
    } finally {
      inputStream.close();
    }
  }

  // Mainly used to avoid throwing IO Exceptions in java.io.Closeable.
  public interface Closeable extends java.io.Closeable {
    @Override
    void close();
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  // The verification mode when authenticating the peer certificate.
  public enum Verification {
    // This is the DEFAULT and RECOMMENDED mode for most applications.
    // Setting this on the client side will do the certificate and hostname verification, while
    // setting this on the server side will only do the certificate verification.
    CERTIFICATE_AND_HOST_NAME_VERIFICATION,
    // This SHOULD be chosen only when you know what the implication this will bring, and have a
    // basic understanding about TLS.
    // It SHOULD be accompanied with proper additional peer identity checks set through
    // {@code PeerVerifier}(nit: why this @code not working?). Failing to do so will leave
    // applications to MITM attack.
    // Also note that this will only take effect if the underlying SDK implementation invokes
    // checkClientTrusted/checkServerTrusted with the {@code SSLEngine} parameter while doing
    // verification.
    // Setting this on either side will only do the certificate verification.
    CERTIFICATE_ONLY_VERIFICATION,
    // Setting is very DANGEROUS. Please try to avoid this in a real production environment, unless
    // you are a super advanced user intended to re-implement the whole verification logic on your
    // own. A secure verification might include:
    // 1. proper verification on the peer certificate chain
    // 2. proper checks on the identity of the peer certificate
    INSECURELY_SKIP_ALL_VERIFICATION,
  }

  // Additional custom peer verification check.
  // It will be used when checkClientTrusted/checkServerTrusted is called with the {@code Socket} or
  // the {@code SSLEngine} parameter.
  public interface SslSocketAndEnginePeerVerifier {
    /**
     * Verifies the peer certificate chain. For more information, please refer to
     * {@code X509ExtendedTrustManager}.
     *
     * @param  peerCertChain  the certificate chain sent from the peer
     * @param  authType the key exchange algorithm used, e.g. "RSA", "DHE_DSS", etc
     * @param  socket the socket used for this connection. This parameter can be null, which
     *         indicates that implementations need not check the ssl parameters
     */
    void verifyPeerCertificate(X509Certificate[] peerCertChain,  String authType, Socket socket)
        throws CertificateException;

    /**
     * Verifies the peer certificate chain. For more information, please refer to
     * {@code X509ExtendedTrustManager}.
     *
     * @param  peerCertChain  the certificate chain sent from the peer
     * @param  authType the key exchange algorithm used, e.g. "RSA", "DHE_DSS", etc
     * @param  engine the engine used for this connection. This parameter can be null, which
     *         indicates that implementations need not check the ssl parameters
     */
    void verifyPeerCertificate(X509Certificate[] peerCertChain, String authType, SSLEngine engine)
        throws CertificateException;
  }

  public static final class Builder {

    private Verification verification = Verification.CERTIFICATE_AND_HOST_NAME_VERIFICATION;
    private SslSocketAndEnginePeerVerifier socketAndEnginePeerVerifier;

    private Builder() {}

    public Builder setVerification(Verification verification) {
      this.verification = verification;
      return this;
    }

    public Builder setSslSocketAndEnginePeerVerifier(SslSocketAndEnginePeerVerifier verifier) {
      this.socketAndEnginePeerVerifier = verifier;
      return this;
    }

    public AdvancedTlsX509TrustManager build() throws CertificateException {
      return new AdvancedTlsX509TrustManager(this.verification, this.socketAndEnginePeerVerifier);
    }
  }
}

