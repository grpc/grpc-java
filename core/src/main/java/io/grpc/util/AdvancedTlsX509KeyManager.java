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
import java.io.InputStream;
import java.net.Socket;
import java.security.NoSuchAlgorithmException;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.security.spec.InvalidKeySpecException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedKeyManager;

@ExperimentalApi("https://github.com/grpc/grpc-java/issues/8024")
/**
 * AdvancedTlsX509KeyManager is an {@code X509ExtendedKeyManager} that allows users to configure
 * advanced TLS features, such as private key and certificate chain reloading, etc.
 */
public final class AdvancedTlsX509KeyManager extends X509ExtendedKeyManager {
  private static final Logger log = Logger.getLogger(AdvancedTlsX509KeyManager.class.getName());

  // The private key and the cert chain we will use to send to peers to prove our identity.
  private volatile PrivateKey key;
  private volatile X509Certificate[] certs;

  public AdvancedTlsX509KeyManager() { }

  @Override
  public PrivateKey getPrivateKey(String alias) {
    // Same question as the trust manager: is it really thread-safe to do this?
    return this.key;
  }

  @Override
  public X509Certificate[] getCertificateChain(String alias) {
    return this.certs;
  }

  @Override
  public String[] getClientAliases(String keyType, Principal[] issuers) {
    return new String[0];
  }

  @Override
  public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket socket) {
    return "default";
  }

  @Override
  public String chooseEngineClientAlias(String[] keyType, Principal[] issuers, SSLEngine engine) {
    return "default";
  }

  @Override
  public String[] getServerAliases(String keyType, Principal[] issuers) {
    return new String[0];
  }

  @Override
  public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
    return "default";
  }

  @Override
  public String chooseEngineServerAlias(String keyType, Principal[] issuers,
      SSLEngine engine) {
    return "default";
  }

  // Update the current cached private key and cert chains.
  // Users should make sure the private key matches the public key on the leaf certificate of the
  // certificate chain.
  // TODO(ZhenLian): explore possibilities to do a crypto check here.
  public void updateIdentityCredentials(PrivateKey key, X509Certificate[] certs) {
    // Same question as the trust manager: what to do if copy is not feasible here?
    this.key = key;
    this.certs = certs;
  }

  /**
   * starts a {@code ScheduledExecutorService} to read private key and certificate chains from the
   * local file paths periodically, and update the cached identity credentials if they are both
   * updated.
   *
   * @param keyFile  the file on disk holding the private key
   * @param certFile  the file on disk holding the certificate chain
   * @param initialDelay the time to delay first read-and-update execution
   * @param delay the period between successive read-and-update executions
   * @param unit the time unit of the initialDelay and period parameters
   * @param executor the execute service we use to start the read-and-update thread
   * @return an object that caller should close when the scheduled execution is not needed
   */
  public Closeable updateIdentityCredentialsFromFile(File keyFile, File certFile,
      long initialDelay, long delay, TimeUnit unit, ScheduledExecutorService executor) {
    final ScheduledFuture<?> future =
        executor.scheduleWithFixedDelay(
            new LoadFilePathExecution(keyFile, certFile),
            initialDelay, delay, unit);
    return new Closeable() {
      @Override public void close() {
        future.cancel(false);
      }
    };
  }

  private class LoadFilePathExecution implements Runnable {
    File keyFile;
    File certFile;
    long currentKeyTime;
    long currentCertTime;

    public LoadFilePathExecution(File keyFile, File certFile) {
      this.keyFile = keyFile;
      this.certFile = certFile;
      this.currentKeyTime = 0;
      this.currentCertTime = 0;
    }

    @Override
    public void run() {
      try {
        UpdateResult newResult = readAndUpdate(this.keyFile, this.certFile, this.currentKeyTime,
            this.currentCertTime);
        if (newResult.success) {
          this.currentKeyTime = newResult.keyTime;
          this.currentCertTime = newResult.certTime;
        }
      } catch (CertificateException | IOException | NoSuchAlgorithmException |
          InvalidKeySpecException e) {
        log.log(Level.SEVERE, "readAndUpdate() execution thread failed", e);
      }
    }
  }

  private static class UpdateResult {
    boolean success;
    long keyTime;
    long certTime;

    public UpdateResult(boolean success, long keyTime, long certTime) {
      this.success = success;
      this.keyTime = keyTime;
      this.certTime = certTime;
    }
  }

  /**
   * reads the private key and certificates specified in the path locations. Updates |key| and |cert|
   * if both of their modified time changed since last read.
   *
   * @param keyFile  the file on disk holding the private key
   * @param certFile  the file on disk holding the certificate chain
   * @param oldKeyTime the time when the private key file is modified during last execution
   * @param oldCertTime the time when the certificate chain file is modified during last execution
   * @return the result of this update execution
   */
  private UpdateResult readAndUpdate(File keyFile, File certFile, long oldKeyTime, long oldCertTime)
      throws IOException, CertificateException, NoSuchAlgorithmException, InvalidKeySpecException {
    long newKeyTime = keyFile.lastModified();
    long newCertTime = certFile.lastModified();
    // We only update when both the key and the certs are updated.
    if (newKeyTime != oldKeyTime && newCertTime != oldCertTime) {
      PrivateKey key = CertificateUtils.getPrivateKey(new FileInputStream(keyFile));
      X509Certificate[] certs = CertificateUtils.getX509Certificates(new FileInputStream(certFile));
      updateIdentityCredentials(key, certs);
      return new UpdateResult(true, newKeyTime, newCertTime);
    }
    return new UpdateResult(false, -1, -1);
  }

  // Mainly used to avoid throwing IO Exceptions in java.io.Closeable.
  public interface Closeable extends java.io.Closeable {
    @Override public void close();
  }
}

