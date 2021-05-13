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
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.Socket;
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
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;

@ExperimentalApi("https://github.com/grpc/grpc-java/issues/8024")
/**
 * AdvancedTlsX509TrustManager is an {@code X509ExtendedTrustManager} that allows users to configure
 * advanced TLS features, such as root certificate reloading, peer cert custom verification, etc.
 */
public final class AdvancedTlsX509TrustManager extends X509ExtendedTrustManager {
  private static final Logger log = Logger.getLogger(AdvancedTlsX509TrustManager.class.getName());

  private Verification verification;
  private PeerVerifier peerVerifier;
  private SSLSocketPeerVerifier sslSocketPeerVerifier;
  private SSLEnginePeerVerifier sslEnginePeerVerifier;
  // The trust certs we will use to verify peer certificate chain.
  private volatile X509Certificate[] trustCerts;

  private AdvancedTlsX509TrustManager(Verification verification,  PeerVerifier peerVerifier,
      SSLSocketPeerVerifier socketPeerVerifier, SSLEnginePeerVerifier enginePeerVerifier) {
    this.verification = verification;
    this.peerVerifier = peerVerifier;
    this.sslSocketPeerVerifier = socketPeerVerifier;
    this.sslEnginePeerVerifier = enginePeerVerifier;
  }

  @Override
  public void checkClientTrusted(X509Certificate[] chain, String authType)
      throws CertificateException {
    checkTrusted(chain, authType, null, null, false);
  }

  @Override
  public void checkServerTrusted(X509Certificate[] chain, String authType)
      throws CertificateException {
    checkTrusted(chain, authType, null, null, true);
  }

  @Override
  public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket)
      throws CertificateException {
    checkTrusted(chain, authType, null, socket, false);
  }

  @Override
  public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket)
      throws CertificateException {
    checkTrusted(chain, authType, null, socket, true);
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
  public X509Certificate[] getAcceptedIssuers() {
    return trustCerts;
  }

  // If this is set, we will use the default trust certificates stored on user's local system.
  // After this is used, functions that will update this.trustCerts(e.g. updateTrustCredentials(),
  // updateTrustCredentialsFromFile()) should not be called.
  public void useSystemDefaultTrustCerts() {
    this.trustCerts = new X509Certificate[0];
  }

  // Update the current cached trust certs.
  public void updateTrustCredentials(X509Certificate[] trustCerts) {
    // Question: in design, we will do an copy of trustCerts, but that seems not easy, since
    // X509Certificate is an abstract class without copy constructor.
    // Can we document in the API that trustCerts shouldn't be modified once set?
    this.trustCerts = trustCerts;
  }

  public void checkTrusted(X509Certificate[] chain, String authType, SSLEngine sslEngine,
      Socket socket, boolean checkingServer) throws CertificateException {
    if (this.verification == Verification.CertificateAndHostNameVerification
        || this.verification == Verification.CertificateOnlyVerification) {
      if (chain == null || chain.length == 0) {
        throw new CertificateException(
            "Want certificate verification but got null or empty certificates");
      }
      // Set the cached trust certificates into a key store.
      KeyStore ks;
      try {
        ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, null);
      } catch (KeyStoreException | IOException | NoSuchAlgorithmException e) {
        throw new CertificateException("Failed to create KeyStore", e);
      }
      int i = 1;
      // Question: I know we've already made this.trustCerts volatile, but shouldn't we require a
      // lock around it as well? Otherwise, while we are reading here, the updating thread might
      // change it as well?
      // I could be wrong, but my current understanding about "volatile" is that it guarantees we
      // store value in memory instead of cache, so there wouldn't be problems like dirty read,
      // etc, but it doesn't eliminate race conditions.
      for (X509Certificate cert: this.trustCerts) {
        String alias = Integer.toString(i);
        try {
          ks.setCertificateEntry(alias, cert);
        } catch (KeyStoreException e) {
          throw new CertificateException("Failed to load trust certificate into KeyStore", e);
        }
        i++;
      }
      // Use the key store to create a delegated trust manager.
      X509ExtendedTrustManager delegateManager = null;
      TrustManagerFactory tmf;
      try {
        tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);
      } catch (KeyStoreException | NoSuchAlgorithmException e) {
        throw new CertificateException("Failed to create delegate TrustManagerFactory", e);
      }
      TrustManager[] tms = tmf.getTrustManagers();
      // Iterate over the returned trust managers, looking for an instance of X509TrustManager.
      // If found, use that as the delegate trust manager.
      for (i = 0; i < tms.length; i++) {
        if (tms[i] instanceof X509ExtendedTrustManager) {
          delegateManager = (X509ExtendedTrustManager) tms[i];
          break;
        }
      }
      if (delegateManager == null) {
        throw new CertificateException(
            "Instance delegateX509TrustManager is null. Failed to initialize");
      }
      // Configure the delegated trust manager based on users' input.
      if (checkingServer) {
        if (this.verification == Verification.CertificateAndHostNameVerification ||
            this.verification == Verification.CertificateOnlyVerification) {
          if (sslEngine == null) {
            throw new CertificateException(
                "SSLEngine is null. Couldn't check host name");
          }
          String algorithm = this.verification == Verification.CertificateAndHostNameVerification
              ? "HTTPS" : "";
          SSLParameters sslParams = sslEngine.getSSLParameters();
          sslParams.setEndpointIdentificationAlgorithm(algorithm);
          sslEngine.setSSLParameters(sslParams);
        }
        delegateManager.checkServerTrusted(chain, authType, sslEngine);
      } else {
        delegateManager.checkClientTrusted(chain, authType, sslEngine);
      }
    }
    // Perform the additional peer cert check.
    if (sslEngine != null && sslEnginePeerVerifier != null) {
      sslEnginePeerVerifier.verifyPeerCertificate(chain, authType, sslEngine);
    }
    if (socket != null && sslSocketPeerVerifier != null) {
      sslSocketPeerVerifier.verifyPeerCertificate(chain, authType, socket);
    }
    if (peerVerifier != null) {
      peerVerifier.verifyPeerCertificate(chain, authType);
    }
    if (sslEnginePeerVerifier == null && sslSocketPeerVerifier == null && peerVerifier == null) {
      log.log(Level.INFO, "No peer verifier is specified");
    }
  }

  /**
   * starts a {@code ScheduledExecutorService} to read trust certificates from a local file path
   * periodically, and update the cached trust certs if there is an update.
   *
   * @param trustCertFile  the file on disk holding the trust certificates
   * @param initialDelay the time to delay first read-and-update execution
   * @param delay the period between successive read-and-update executions
   * @param unit the time unit of the initialDelay and period parameters
   * @param executor the execute service we use to start the read-and-update thread
   * @return an object that caller should close when the scheduled execution is not needed
   */
  public Closeable updateTrustCredentialsFromFile(File trustCertFile, long initialDelay,
      long delay, TimeUnit unit, ScheduledExecutorService executor) {
    final ScheduledFuture<?> future =
        executor.scheduleWithFixedDelay(
            new LoadFilePathExecution(trustCertFile),
            initialDelay, delay, unit);
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
        long newUpdateTime = readAndUpdate(this.file, this.currentTime);
        if (newUpdateTime != 0) {
          this.currentTime = newUpdateTime;
        }
      } catch (FileNotFoundException | CertificateException e) {
        log.log(Level.SEVERE, "readAndUpdate() execution thread failed", e);

      }
    }
  }

  /**
   * reads the trust certificates specified in the path location, and update |trustCerts| if the
   * modified time has changed since last read.
   *
   * @param trustCertFile  the file on disk holding the trust certificates
   * @param oldTime the time when the trust file is modified during last execution
   * @return 0 if something wrong with this execution or the modified time is not changed since last
   * execution, otherwise the modified time during this execution
   */
  private long readAndUpdate(File trustCertFile, long oldTime)
      throws FileNotFoundException, CertificateException {
    long newTime = trustCertFile.lastModified();
    if (newTime != oldTime) {
      X509Certificate[] certificates = CertificateUtils.getX509Certificates(
          new FileInputStream(trustCertFile));
      updateTrustCredentials(certificates);
      return newTime;
    }
    return 0;
  }

  // Mainly used to avoid throwing IO Exceptions in java.io.Closeable.
  public interface Closeable extends java.io.Closeable {
    @Override public void close();
  }

  public static Builder newBuilder() {
    return new Builder().setVerification(Verification.CertificateAndHostNameVerification);
  }

  // The verification mode when authenticating the peer certificate.
  public enum Verification {
    // This is the DEFAULT and RECOMMENDED mode for most applications.
    // Setting this on the client side will do the certificate and hostname verification, while
    // setting this on the server side will only do the certificate verification.
    CertificateAndHostNameVerification,
    // This SHOULD be chosen only when you know what the implication this will bring, and have a
    // basic understanding about TLS.
    // It SHOULD be accompanied with proper additional peer identity checks set through
    // {@code PeerVerifier}(nit: why this @code not working?). Failing to do so will leave
    // applications to MITM attack.
    // Also note that this will only take effect if the underlying SDK implementation invokes
    // checkClientTrusted/checkServerTrusted with the {@code SSLEngine} parameter while doing
    // verification.
    // Setting this on either side will only do the certificate verification.
    CertificateOnlyVerification,
    // Setting is very DANGEROUS. Please try to avoid this in a real production environment, unless
    // you are a super advanced user intended to re-implement the whole verification logic on your
    // own. A secure verification might include:
    // 1. proper verification on the peer certificate chain
    // 2. proper checks on the identity of the peer certificate
    InsecurelySkipAllVerification,
  }

  // Additional custom peer verification check.
  // It will be used when checkClientTrusted/checkServerTrusted is called without
  // {@code Socket}/{@code SSLEngine} parameter.
  public interface PeerVerifier {
    /**
     * Verifies the peer certificate chain. For more information, please refer to
     * {@code X509ExtendedTrustManager}.
     *
     * @param  peerCertChain  the certificate chain sent from the peer
     * @param  authType the key exchange algorithm used, e.g. "RSA", "DHE_DSS", etc
     */
    void verifyPeerCertificate(X509Certificate[] peerCertChain, String authType)
        throws CertificateException;
  }
  // Additional custom peer verification check.
  // It will be used when checkClientTrusted/checkServerTrusted is called with the {@code Socket}
  // parameter.
  public interface SSLSocketPeerVerifier {
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
  }
  // Additional custom peer verification check.
  // It will be used when checkClientTrusted/checkServerTrusted is called with the {@code SSLEngine}
  // parameter.
  public interface SSLEnginePeerVerifier {
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

    private Verification verification;
    private PeerVerifier peerVerifier;
    private SSLSocketPeerVerifier socketPeerVerifier;
    private SSLEnginePeerVerifier enginePeerVerifier;

    public Builder setVerification(Verification verification) {
      this.verification = verification;
      return this;
    }

    public Builder setPeerVerifier(PeerVerifier peerVerifier) {
      this.peerVerifier = peerVerifier;
      return this;
    }

    public Builder setSSLSocketPeerVerifier(SSLSocketPeerVerifier peerVerifier) {
      this.socketPeerVerifier = peerVerifier;
      return this;
    }

    public Builder setSSLEnginePeerVerifier(SSLEnginePeerVerifier peerVerifier) {
      this.enginePeerVerifier = peerVerifier;
      return this;
    }

    // Question: shall we do some sanity checks here, to make sure all three verifiers are set, to
    // be more secure?
    public AdvancedTlsX509TrustManager build() {
      return new AdvancedTlsX509TrustManager(this.verification, this.peerVerifier,
          this.socketPeerVerifier, this.enginePeerVerifier);
    }
  }
}

