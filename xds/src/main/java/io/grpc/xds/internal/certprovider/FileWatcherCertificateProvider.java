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

package io.grpc.xds.internal.certprovider;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.annotations.VisibleForTesting;
import io.grpc.InternalLogId;
import io.grpc.Status;
import io.grpc.SynchronizationContext;
import io.grpc.internal.TimeProvider;
import io.grpc.xds.internal.sds.trust.CertificateUtils;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

// TODO(sanjaypujare): abstract out common functionality into an an abstract superclass
/** Implementation of {@link CertificateProvider} for file watching cert provider. */
final class FileWatcherCertificateProvider extends CertificateProvider {
  private static final Logger logger =
      Logger.getLogger(FileWatcherCertificateProvider.class.getName());

  private final SynchronizationContext syncContext;
  private final ScheduledExecutorService scheduledExecutorService;
  private final TimeProvider timeProvider;
  private final Path certFile;
  private final Path keyFile;
  private final Path trustFile;
  private final long refreshIntervalInSeconds;
  @VisibleForTesting SynchronizationContext.ScheduledHandle scheduledHandle;
  private FileTime lastModifiedTimeCert;
  private FileTime lastModifiedTimeKey;
  private FileTime lastModifiedTimeRoot;

  FileWatcherCertificateProvider(
      DistributorWatcher watcher,
      boolean notifyCertUpdates,
      String certFile,
      String keyFile,
      String trustFile,
      long refreshIntervalInSeconds,
      ScheduledExecutorService scheduledExecutorService,
      TimeProvider timeProvider) {
    super(watcher, notifyCertUpdates);
    this.scheduledExecutorService =
        checkNotNull(scheduledExecutorService, "scheduledExecutorService");
    this.timeProvider = checkNotNull(timeProvider, "timeProvider");
    this.certFile = Paths.get(checkNotNull(certFile, "certFile"));
    this.keyFile = Paths.get(checkNotNull(keyFile, "keyFile"));
    this.trustFile = Paths.get(checkNotNull(trustFile, "trustFile"));
    this.refreshIntervalInSeconds = refreshIntervalInSeconds;
    this.syncContext = createSynchronizationContext(certFile);
  }

  private SynchronizationContext createSynchronizationContext(String details) {
    final InternalLogId logId =
        InternalLogId.allocate("DynamicReloadingCertificateProvider", details);
    return new SynchronizationContext(
        new Thread.UncaughtExceptionHandler() {
          private boolean panicMode;

          @Override
          public void uncaughtException(Thread t, Throwable e) {
            logger.log(
                Level.SEVERE,
                "[" + logId + "] Uncaught exception in the SynchronizationContext. Panic!",
                e);
            panic(e);
          }

          void panic(final Throwable t) {
            if (panicMode) {
              // Preserve the first panic information
              return;
            }
            panicMode = true;
            close();
          }
        });
  }

  @Override
  public void start() {
    scheduleNextRefreshCertificate(/* delayInSeconds= */0);
  }

  @Override
  public void close() {
    if (scheduledHandle != null) {
      scheduledHandle.cancel();
      scheduledHandle = null;
    }
    getWatcher().close();
  }

  private void scheduleNextRefreshCertificate(long delayInSeconds) {
    RefreshCertificateTask runnable = new RefreshCertificateTask();
    scheduledHandle =
        syncContext.schedule(runnable, delayInSeconds, TimeUnit.SECONDS, scheduledExecutorService);
  }

  @VisibleForTesting
  void checkAndReloadCertificates() {
    try {
      try {
        FileTime currentCertTime = Files.getLastModifiedTime(certFile);
        FileTime currentKeyTime = Files.getLastModifiedTime(keyFile);
        if (!currentCertTime.equals(lastModifiedTimeCert)
            && !currentKeyTime.equals(lastModifiedTimeKey)) {
          byte[] certFileContents = Files.readAllBytes(certFile);
          byte[] keyFileContents = Files.readAllBytes(keyFile);
          FileTime currentCertTime2 = Files.getLastModifiedTime(certFile);
          FileTime currentKeyTime2 = Files.getLastModifiedTime(keyFile);
          if (!currentCertTime2.equals(currentCertTime)) {
            return;
          }
          if (!currentKeyTime2.equals(currentKeyTime)) {
            return;
          }
          try (ByteArrayInputStream certStream = new ByteArrayInputStream(certFileContents);
              ByteArrayInputStream keyStream = new ByteArrayInputStream(keyFileContents)) {
            PrivateKey privateKey = CertificateUtils.getPrivateKey(keyStream);
            X509Certificate[] certs = CertificateUtils.toX509Certificates(certStream);
            getWatcher().updateCertificate(privateKey, Arrays.asList(certs));
          }
          lastModifiedTimeCert = currentCertTime;
          lastModifiedTimeKey = currentKeyTime;
        }
      } catch (Throwable t) {
        generateErrorIfCurrentCertExpired(t);
      }
      try {
        FileTime currentRootTime = Files.getLastModifiedTime(trustFile);
        if (currentRootTime.equals(lastModifiedTimeRoot)) {
          return;
        }
        byte[] rootFileContents = Files.readAllBytes(trustFile);
        FileTime currentRootTime2 = Files.getLastModifiedTime(trustFile);
        if (!currentRootTime2.equals(currentRootTime)) {
          return;
        }
        try (ByteArrayInputStream rootStream = new ByteArrayInputStream(rootFileContents)) {
          X509Certificate[] caCerts = CertificateUtils.toX509Certificates(rootStream);
          getWatcher().updateTrustedRoots(Arrays.asList(caCerts));
        }
        lastModifiedTimeRoot = currentRootTime;
      } catch (Throwable t) {
        getWatcher().onError(Status.fromThrowable(t));
      }
    } finally {
      scheduleNextRefreshCertificate(refreshIntervalInSeconds);
    }
  }

  private void generateErrorIfCurrentCertExpired(Throwable t) {
    X509Certificate currentCert = getWatcher().getLastIdentityCert();
    if (currentCert != null) {
      long delaySeconds = computeDelaySecondsToCertExpiry(currentCert);
      if (delaySeconds > refreshIntervalInSeconds) {
        logger.log(Level.FINER, "reload certificate error", t);
        return;
      }
      // The current cert is going to expire in less than {@link refreshIntervalInSeconds}
      // Clear the current cert and notify our watchers thru {@code onError}
      getWatcher().clearValues();
    }
    getWatcher().onError(Status.fromThrowable(t));
  }

  @SuppressWarnings("JdkObsolete")
  private long computeDelaySecondsToCertExpiry(X509Certificate lastCert) {
    checkNotNull(lastCert, "lastCert");
    return TimeUnit.NANOSECONDS.toSeconds(
        TimeUnit.MILLISECONDS.toNanos(lastCert.getNotAfter().getTime())
            - timeProvider.currentTimeNanos());
  }

  @VisibleForTesting
  class RefreshCertificateTask implements Runnable {
    @Override
    public void run() {
      checkAndReloadCertificates();
    }
  }

  abstract static class Factory {
    private static final Factory DEFAULT_INSTANCE =
        new Factory() {
          @Override
          FileWatcherCertificateProvider create(
              DistributorWatcher watcher,
              boolean notifyCertUpdates,
              String certFile,
              String keyFile,
              String trustFile,
              long refreshIntervalInSeconds,
              ScheduledExecutorService scheduledExecutorService,
              TimeProvider timeProvider) {
            return new FileWatcherCertificateProvider(
                watcher,
                notifyCertUpdates,
                certFile,
                keyFile,
                trustFile,
                refreshIntervalInSeconds,
                scheduledExecutorService,
                timeProvider);
          }
        };

    static Factory getInstance() {
      return DEFAULT_INSTANCE;
    }

    abstract FileWatcherCertificateProvider create(
        DistributorWatcher watcher,
        boolean notifyCertUpdates,
        String certFile,
        String keyFile,
        String trustFile,
        long refreshIntervalInSeconds,
        ScheduledExecutorService scheduledExecutorService,
        TimeProvider timeProvider);
  }
}
