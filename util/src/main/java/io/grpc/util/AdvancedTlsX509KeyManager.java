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

import com.google.errorprone.annotations.InlineMe;
import io.grpc.ExperimentalApi;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.Principal;
import java.security.PrivateKey;
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
 * advanced TLS features, such as private key and certificate chain reloading.
 */
public final class AdvancedTlsX509KeyManager extends X509ExtendedKeyManager {
  private static final Logger log = Logger.getLogger(AdvancedTlsX509KeyManager.class.getName());
  // Minimum allowed period for refreshing files with credential information.
  private static final int MINIMUM_REFRESH_PERIOD_IN_MINUTES = 1 ;
  // The credential information to be sent to peers to prove our identity.
  private volatile KeyInfo keyInfo;

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
   * @deprecated Use {@link #updateIdentityCredentials(X509Certificate[], PrivateKey)}
   */
  @Deprecated
  @InlineMe(replacement = "this.updateIdentityCredentials(certs, key)")
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/8024")
  public void updateIdentityCredentials(PrivateKey key, X509Certificate[] certs) {
    updateIdentityCredentials(certs, key);
  }

  /**
   * Updates the current cached private key and cert chains.
   *
   * @param certs  the certificate chain that is going to be used
   * @param key  the private key that is going to be used
   */
  public void updateIdentityCredentials(X509Certificate[] certs, PrivateKey key) {
    this.keyInfo = new KeyInfo(checkNotNull(certs, "certs"), checkNotNull(key, "key"));
  }

  /**
   * Schedules a {@code ScheduledExecutorService} to read certificate chains and private key from
   * the local file paths periodically, and update the cached identity credentials if they are both
   * updated. You must close the returned Closeable before calling this method again or other update
   * methods ({@link AdvancedTlsX509KeyManager#updateIdentityCredentials}, {@link
   * AdvancedTlsX509KeyManager#updateIdentityCredentials(File, File)}).
   * Before scheduling the task, the method synchronously executes {@code  readAndUpdate} once. The
   * minimum refresh period of 1 minute is enforced.
   *
   * @param certFile  the file on disk holding the certificate chain
   * @param keyFile  the file on disk holding the private key
   * @param period the period between successive read-and-update executions
   * @param unit the time unit of the initialDelay and period parameters
   * @param executor the executor service we use to read and update the credentials
   * @return an object that caller should close when the file refreshes are not needed
   */
  public Closeable updateIdentityCredentials(File certFile, File keyFile,
      long period, TimeUnit unit, ScheduledExecutorService executor) throws IOException,
      GeneralSecurityException {
    UpdateResult newResult = readAndUpdate(certFile, keyFile, 0, 0);
    if (!newResult.success) {
      throw new GeneralSecurityException(
          "Files were unmodified before their initial update. Probably a bug.");
    }
    if (checkNotNull(unit, "unit").toMinutes(period) < MINIMUM_REFRESH_PERIOD_IN_MINUTES) {
      log.log(Level.FINE,
          "Provided refresh period of {0} {1} is too small. Default value of {2} minute(s) "
          + "will be used.", new Object[] {period, unit.name(), MINIMUM_REFRESH_PERIOD_IN_MINUTES});
      period = MINIMUM_REFRESH_PERIOD_IN_MINUTES;
      unit = TimeUnit.MINUTES;
    }
    final ScheduledFuture<?> future =
        checkNotNull(executor, "executor").scheduleWithFixedDelay(
            new LoadFilePathExecution(certFile, keyFile), period, period, unit);
    return () -> future.cancel(false);
  }

  /**
   * Updates certificate chains and the private key from the local file paths.
   *
   * @param certFile  the file on disk holding the certificate chain
   * @param keyFile  the file on disk holding the private key
   */
  public void updateIdentityCredentials(File certFile, File keyFile) throws IOException,
      GeneralSecurityException {
    UpdateResult newResult = readAndUpdate(certFile, keyFile, 0, 0);
    if (!newResult.success) {
      throw new GeneralSecurityException(
          "Files were unmodified before their initial update. Probably a bug.");
    }
  }

  /**
   * Updates the private key and certificate chains from the local file paths.
   *
   * @param keyFile  the file on disk holding the private key
   * @param certFile  the file on disk holding the certificate chain
   * @deprecated Use {@link #updateIdentityCredentials(File, File)} instead.
   */
  @Deprecated
  @InlineMe(replacement = "this.updateIdentityCredentials(certFile, keyFile)")
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/8024")
  public void updateIdentityCredentialsFromFile(File keyFile, File certFile) throws IOException,
      GeneralSecurityException {
    updateIdentityCredentials(certFile, keyFile);
  }

  /**
   * Schedules a {@code ScheduledExecutorService} to read private key and certificate chains from
   * the local file paths periodically, and update the cached identity credentials if they are both
   * updated. You must close the returned Closeable before calling this method again or other update
   * methods ({@link AdvancedTlsX509KeyManager#updateIdentityCredentials}, {@link
   * AdvancedTlsX509KeyManager#updateIdentityCredentials(File, File)}).
   * Before scheduling the task, the method synchronously executes {@code  readAndUpdate} once. The
   * minimum refresh period of 1 minute is enforced.
   *
   * @param keyFile  the file on disk holding the private key
   * @param certFile  the file on disk holding the certificate chain
   * @param period the period between successive read-and-update executions
   * @param unit the time unit of the initialDelay and period parameters
   * @param executor the executor service we use to read and update the credentials
   * @return an object that caller should close when the file refreshes are not needed
   * @deprecated Use {@link
   * #updateIdentityCredentials(File, File, long, TimeUnit, ScheduledExecutorService)} instead.
   */
  @Deprecated
  @InlineMe(replacement =
      "this.updateIdentityCredentials(certFile, keyFile, period, unit, executor)")
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/8024")
  public Closeable updateIdentityCredentialsFromFile(File keyFile, File certFile,
      long period, TimeUnit unit, ScheduledExecutorService executor) throws IOException,
      GeneralSecurityException {
    return updateIdentityCredentials(certFile, keyFile, period, unit, executor);
  }

  private static class KeyInfo {
    // The private key and the cert chain we will use to send to peers to prove our identity.
    final X509Certificate[] certs;
    final PrivateKey key;

    public KeyInfo(X509Certificate[] certs, PrivateKey key) {
      this.certs = certs;
      this.key = key;
    }
  }

  private class LoadFilePathExecution implements Runnable {
    File keyFile;
    File certFile;
    long currentCertTime;
    long currentKeyTime;

    public LoadFilePathExecution(File certFile, File keyFile) {
      this.certFile = certFile;
      this.keyFile = keyFile;
      this.currentCertTime = 0;
      this.currentKeyTime = 0;
    }

    @Override
    public void run() {
      try {
        UpdateResult newResult = readAndUpdate(this.certFile, this.keyFile, this.currentKeyTime,
            this.currentCertTime);
        if (newResult.success) {
          this.currentCertTime = newResult.certTime;
          this.currentKeyTime = newResult.keyTime;
        }
      } catch (IOException | GeneralSecurityException e) {
        log.log(Level.SEVERE, String.format("Failed refreshing certificate and private key"
                + " chain from files. Using previous ones (certFile lastModified = %s, keyFile "
                + "lastModified = %s)", certFile.lastModified(), keyFile.lastModified()), e);
      }
    }
  }

  private static class UpdateResult {
    boolean success;
    long certTime;
    long keyTime;

    public UpdateResult(boolean success, long certTime, long keyTime) {
      this.success = success;
      this.certTime = certTime;
      this.keyTime = keyTime;
    }
  }

  /**
   * Reads the private key and certificates specified in the path locations. Updates {@code key} and
   * {@code cert} if both of their modified time changed since last read.
   *
   * @param certFile  the file on disk holding the certificate chain
   * @param keyFile  the file on disk holding the private key
   * @param oldKeyTime the time when the private key file is modified during last execution
   * @param oldCertTime the time when the certificate chain file is modified during last execution
   * @return the result of this update execution
   */
  private UpdateResult readAndUpdate(File certFile, File keyFile, long oldKeyTime, long oldCertTime)
      throws IOException, GeneralSecurityException {
    long newKeyTime = checkNotNull(keyFile, "keyFile").lastModified();
    long newCertTime = checkNotNull(certFile, "certFile").lastModified();
    // We only update when both the key and the certs are updated.
    if (newKeyTime != oldKeyTime && newCertTime != oldCertTime) {
      FileInputStream keyInputStream = new FileInputStream(keyFile);
      try {
        PrivateKey key = CertificateUtils.getPrivateKey(keyInputStream);
        FileInputStream certInputStream = new FileInputStream(certFile);
        try {
          X509Certificate[] certs = CertificateUtils.getX509Certificates(certInputStream);
          updateIdentityCredentials(certs, key);
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

