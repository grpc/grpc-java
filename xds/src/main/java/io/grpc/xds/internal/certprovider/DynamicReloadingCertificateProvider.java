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
import java.io.File;
import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Implementation of {@link CertificateProvider} for dynamic reloading cert provider. */
final class DynamicReloadingCertificateProvider extends CertificateProvider {
  private static final Logger logger =
      Logger.getLogger(DynamicReloadingCertificateProvider.class.getName());

  private final SynchronizationContext syncContext;
  private final ScheduledExecutorService scheduledExecutorService;
  private final TimeProvider timeProvider;
  private final Path directory;
  private final String certFile;
  private final String privateKeyFile;
  private final String trustFile;
  private final long refreshIntervalInSeconds;
  @VisibleForTesting SynchronizationContext.ScheduledHandle scheduledHandle;
  private Path lastModifiedTarget;

  DynamicReloadingCertificateProvider(
      DistributorWatcher watcher,
      boolean notifyCertUpdates,
      String directory,
      String certFile,
      String privateKeyFile,
      String trustFile,
      long refreshIntervalInSeconds,
      ScheduledExecutorService scheduledExecutorService,
      TimeProvider timeProvider) {
    super(watcher, notifyCertUpdates);
    this.scheduledExecutorService =
        checkNotNull(scheduledExecutorService, "scheduledExecutorService");
    this.timeProvider = checkNotNull(timeProvider, "timeProvider");
    this.directory = Paths.get(checkNotNull(directory, "diretory"));
    this.certFile = checkNotNull(certFile, "certFile");
    this.privateKeyFile = checkNotNull(privateKeyFile, "privateKeyFile");
    this.trustFile = checkNotNull(trustFile, "trustFile");
    this.refreshIntervalInSeconds = refreshIntervalInSeconds;
    this.syncContext = createSynchronizationContext(directory);
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
      Path targetPath = Files.readSymbolicLink(directory);
      if (targetPath.equals(lastModifiedTarget)) {
        return;
      }
      try (FileInputStream privateKeyStream =
              new FileInputStream(new File(targetPath.toFile(), privateKeyFile));
          FileInputStream certsStream =
              new FileInputStream(new File(targetPath.toFile(), certFile));
          FileInputStream caCertsStream =
              new FileInputStream(new File(targetPath.toFile(), trustFile))) {
        PrivateKey privateKey = CertificateUtils.getPrivateKey(privateKeyStream);
        X509Certificate[] certs = CertificateUtils.toX509Certificates(certsStream);
        X509Certificate[] caCerts = CertificateUtils.toX509Certificates(caCertsStream);
        getWatcher().updateCertificate(privateKey, Arrays.asList(certs));
        getWatcher().updateTrustedRoots(Arrays.asList(caCerts));
      }
      lastModifiedTarget = targetPath;
    } catch (Throwable t) {
      generateErrorIfCurrentCertExpired(t);
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
          DynamicReloadingCertificateProvider create(
              DistributorWatcher watcher,
              boolean notifyCertUpdates,
              String directory,
              String certFile,
              String privateKeyFile,
              String trustFile,
              long refreshIntervalInSeconds,
              ScheduledExecutorService scheduledExecutorService,
              TimeProvider timeProvider) {
            return new DynamicReloadingCertificateProvider(
                watcher,
                notifyCertUpdates,
                directory,
                certFile,
                privateKeyFile,
                trustFile,
                refreshIntervalInSeconds,
                scheduledExecutorService,
                timeProvider);
          }
        };

    static Factory getInstance() {
      return DEFAULT_INSTANCE;
    }

    abstract DynamicReloadingCertificateProvider create(
        DistributorWatcher watcher,
        boolean notifyCertUpdates,
        String directory,
        String certFile,
        String privateKeyFile,
        String trustFile,
        long refreshIntervalInSeconds,
        ScheduledExecutorService scheduledExecutorService,
        TimeProvider timeProvider);
  }
}
