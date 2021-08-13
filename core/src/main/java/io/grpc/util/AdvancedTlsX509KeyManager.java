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
import java.security.KeyStore;
import java.security.KeyStore.PrivateKeyEntry;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.UnrecoverableEntryException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.security.spec.InvalidKeySpecException;
import java.util.ArrayList;
import java.util.List;
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

  // The key store that is used to hold the private key and the certificate chain.
  private volatile  KeyStore ks;
  // The password associated with the private key.
  private volatile String password = null;

  /**
   * Constructs an AdvancedTlsX509KeyManager.
   */
  public AdvancedTlsX509KeyManager() throws CertificateException {
    try {
      ks = KeyStore.getInstance(KeyStore.getDefaultType());
      ks.load(null, null);
    } catch (KeyStoreException | IOException | NoSuchAlgorithmException | CertificateException e) {
      throw new CertificateException("Failed to create KeyStore", e);
    }
  }

  @Override
  public PrivateKey getPrivateKey(String alias) {
    KeyStore.Entry entry = null;
    try {
      entry = ks.getEntry(alias, new KeyStore.PasswordProtection(this.password.toCharArray()));
    } catch (NoSuchAlgorithmException | UnrecoverableEntryException | KeyStoreException e) {
      log.log(Level.SEVERE, "Unable to get the key entry from the key store", e);
      return null;
    }
    if (entry == null || !(entry instanceof PrivateKeyEntry)) {
      log.log(Level.SEVERE, "Failed to get the actual entry");
      return null;
    }
    PrivateKeyEntry privateKeyEntry = (PrivateKeyEntry) entry;
    return privateKeyEntry.getPrivateKey();
  }

  @Override
  public X509Certificate[] getCertificateChain(String alias) {
    KeyStore.Entry entry = null;
    try {
      entry = ks.getEntry(alias, new KeyStore.PasswordProtection(this.password.toCharArray()));
    } catch (NoSuchAlgorithmException | UnrecoverableEntryException | KeyStoreException e) {
      log.log(Level.SEVERE, "Unable to get the key entry from the key store", e);
      return null;
    }
    if (entry == null || !(entry instanceof PrivateKeyEntry)) {
      log.log(Level.SEVERE, "Failed to get the actual entry");
      return null;
    }
    PrivateKeyEntry privateKeyEntry = (PrivateKeyEntry) entry;
    Certificate[] certs = privateKeyEntry.getCertificateChain();
    List<X509Certificate> returnedCerts = new ArrayList<>();
    for (int i = 0; i < certs.length; ++i) {
      if (certs[i] instanceof X509Certificate) {
        returnedCerts.add((X509Certificate) certs[i]);
      } else {
        log.log(Level.SEVERE, "The certificate is not type of X509Certificate. Skip it");
      }
    }
    return returnedCerts.toArray(new X509Certificate[0]);
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
   * @param password  the password associated with the key
   */
  public void updateIdentityCredentials(PrivateKey key, X509Certificate[] certs, String password)
      throws CertificateException {
    // TODO(ZhenLian): explore possibilities to do a crypto check here.
    // Clear the key store by re-creating it.
    KeyStore newKeyStore = null;
    try {
      newKeyStore = KeyStore.getInstance(KeyStore.getDefaultType());
      newKeyStore.load(null, null);
    } catch (KeyStoreException | IOException | NoSuchAlgorithmException | CertificateException e) {
      throw new CertificateException("Failed to create KeyStore", e);
    }
    if (newKeyStore != null) {
      this.ks = newKeyStore;
    }
    this.password = password;
    // Update the ks with the new credential contents.
    try {
      PrivateKeyEntry privateKeyEntry = new PrivateKeyEntry(key, certs);
      ks.setEntry("default", privateKeyEntry, new KeyStore.PasswordProtection(
          this.password.toCharArray()));
    } catch (KeyStoreException e) {
      throw new CertificateException(
          "Failed to load private key and certificates into KeyStore", e);
    }
  }

  /**
   * Schedules a {@code ScheduledExecutorService} to read private key and certificate chains from
   * the local file paths periodically, and update the cached identity credentials if they are both
   * updated.
   *
   * @param keyFile  the file on disk holding the private key
   * @param certFile  the file on disk holding the certificate chain
   * @param password the password associated with the key
   * @param period the period between successive read-and-update executions
   * @param unit the time unit of the initialDelay and period parameters
   * @param executor the execute service we use to read and update the credentials
   * @return an object that caller should close when the file refreshes are not needed
   */
  public Closeable updateIdentityCredentialsFromFile(File keyFile, File certFile,
      String password, long period, TimeUnit unit, ScheduledExecutorService executor) {
    this.password = password;
    final ScheduledFuture<?> future =
        executor.scheduleWithFixedDelay(
            new LoadFilePathExecution(keyFile, certFile), 0, period, unit);
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
      } catch (CertificateException | IOException | NoSuchAlgorithmException
          | InvalidKeySpecException e) {
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
      throws IOException, CertificateException, NoSuchAlgorithmException, InvalidKeySpecException {
    long newKeyTime = keyFile.lastModified();
    long newCertTime = certFile.lastModified();
    // We only update when both the key and the certs are updated.
    if (newKeyTime != oldKeyTime && newCertTime != oldCertTime) {
      FileInputStream keyInputStream = null;
      try {
        keyInputStream = new FileInputStream(keyFile);
        PrivateKey key = CertificateUtils.getPrivateKey(keyInputStream);
        FileInputStream certInputStream = null;
        try {
          certInputStream = new FileInputStream(certFile);
          X509Certificate[] certs = CertificateUtils.getX509Certificates(certInputStream);
          updateIdentityCredentials(key, certs, this.password);
          return new UpdateResult(true, newKeyTime, newCertTime);
        } finally {
          if (certInputStream != null) {
            certInputStream.close();
          }
        }
      } finally {
        if (keyInputStream != null) {
          keyInputStream.close();
        }
      }
    }
    return new UpdateResult(false, oldKeyTime, oldCertTime);
  }

  /**
   * Mainly used to avoid throwing IO Exceptions in java.io.Closeable.
   */
  public interface Closeable extends java.io.Closeable {
    @Override public void close();
  }
}

