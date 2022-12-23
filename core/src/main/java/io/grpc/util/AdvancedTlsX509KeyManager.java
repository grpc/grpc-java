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

import static com.google.common.base.Preconditions.checkNotNull;

import io.grpc.ExperimentalApi;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedKeyManager;

/**
 * AdvancedTlsX509KeyManager is an {@code X509ExtendedKeyManager} that allows users to configure
 * advanced TLS features, such as private key and certificate chain reloading, etc.
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/8024")
public final class AdvancedTlsX509KeyManager extends X509ExtendedKeyManager {
  private static final Logger log = Logger.getLogger(AdvancedTlsX509KeyManager.class.getName());

  // The credential information sent to peers to prove our identity.
  private volatile KeyInfo keyInfo;

  /**
   * Constructs an AdvancedTlsX509KeyManager.
   */
  public AdvancedTlsX509KeyManager() throws CertificateException { }

  @Override
  public PrivateKey getPrivateKey(String alias) {
    if (alias.equals("default")) {
      return this.keyInfo.key;
    }
    return null;
  }

  @Override
  public X509Certificate[] getCertificateChain(String alias) {
    if (alias.equals("default")) {
      return Arrays.copyOf(this.keyInfo.certs, this.keyInfo.certs.length);
    }
    return null;
  }

  @Override
  public String[] getClientAliases(String keyType, Principal[] issuers) {
    return new String[] {"default"};
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
    return new String[] {"default"};
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

  /**
   * Updates the current cached private key and cert chains.
   *
   * @param key  the private key that is going to be used
   * @param certs  the certificate chain that is going to be used
   */
  public void updateIdentityCredentials(PrivateKey key, X509Certificate[] certs) {
    // TODO(ZhenLian): explore possibilities to do a crypto check here.
    this.keyInfo = new KeyInfo(checkNotNull(key, "key"), checkNotNull(certs, "certs"));
  }

  /**
   * Schedules a {@code ScheduledExecutorService} to read private key and certificate chains from
   * the local file paths periodically, and update the cached identity credentials if they are both
   * updated.
   *
   * @param keyFile  the file on disk holding the private key
   * @param certFile  the file on disk holding the certificate chain
   * @param period the period between successive read-and-update executions
   * @param unit the time unit of the initialDelay and period parameters
   * @param executor the execute service we use to read and update the credentials
   * @return an object that caller should close when the file refreshes are not needed
   */
  public Closeable updateIdentityCredentialsFromFile(File keyFile, File certFile,
      long period, TimeUnit unit, ScheduledExecutorService executor) throws IOException,
      GeneralSecurityException {
    UpdateResult newResult = readAndUpdate(keyFile, certFile, 0, 0);
    if (!newResult.success) {
      throw new GeneralSecurityException(
          "Files were unmodified before their initial update. Probably a bug.");
    }
    final ScheduledFuture<?> future =
        executor.scheduleWithFixedDelay(
            new LoadFilePathExecution(keyFile, certFile), period, period, unit);
    return new Closeable() {
      @Override public void close() {
        future.cancel(false);
      }
    };
  }

  /**
   * Updates the private key and certificate chains from the local file paths.
   *
   * @param keyFile  the file on disk holding the private key
   * @param certFile  the file on disk holding the certificate chain
   */
  public void updateIdentityCredentialsFromFile(File keyFile, File certFile) throws IOException,
      GeneralSecurityException {
    UpdateResult newResult = readAndUpdate(keyFile, certFile, 0, 0);
    if (!newResult.success) {
      throw new GeneralSecurityException(
          "Files were unmodified before their initial update. Probably a bug.");
    }
  }

  private static class KeyInfo {
    // The private key and the cert chain we will use to send to peers to prove our identity.
    final PrivateKey key;
    final X509Certificate[] certs;

    public KeyInfo(PrivateKey key, X509Certificate[] certs) {
      this.key = key;
      this.certs = certs;
    }
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
      } catch (IOException | GeneralSecurityException e) {
        log.log(Level.SEVERE, "Failed refreshing private key and certificate chain from files. "
            + "Using previous ones", e);
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
   * Reads the private key and certificates specified in the path locations. Updates {@code key} and
   * {@code cert} if both of their modified time changed since last read.
   *
   * @param keyFile  the file on disk holding the private key
   * @param certFile  the file on disk holding the certificate chain
   * @param oldKeyTime the time when the private key file is modified during last execution
   * @param oldCertTime the time when the certificate chain file is modified during last execution
   * @return the result of this update execution
   */
  private UpdateResult readAndUpdate(File keyFile, File certFile, long oldKeyTime, long oldCertTime)
      throws IOException, GeneralSecurityException {
    long newKeyTime = keyFile.lastModified();
    long newCertTime = certFile.lastModified();
    // We only update when both the key and the certs are updated.
    if (newKeyTime != oldKeyTime && newCertTime != oldCertTime) {
      FileInputStream keyInputStream = new FileInputStream(keyFile);
      try {
        PrivateKey key = CertificateUtils.getPrivateKey(keyInputStream);
        FileInputStream certInputStream = new FileInputStream(certFile);
        try {
          X509Certificate[] certs = CertificateUtils.getX509Certificates(certInputStream);
          updateIdentityCredentials(key, certs);
          return new UpdateResult(true, newKeyTime, newCertTime);
        } finally {
          certInputStream.close();
        }
      } finally {
        keyInputStream.close();
      }
    }
    return new UpdateResult(false, oldKeyTime, oldCertTime);
  }

  /**
   * Mainly used to avoid throwing IO Exceptions in java.io.Closeable.
   */
  public interface Closeable extends java.io.Closeable {
    @Override
    void close();
  }
}

