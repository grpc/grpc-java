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

/** Implementation of {@link CertificateProvider} for Zatar cert provider. */
final class ZatarCertificateProvider extends CertificateProvider {
  private static final Logger logger = Logger.getLogger(ZatarCertificateProvider.class.getName());

  /**
   * After the previous cert has expired, if we are unable to get new certificates we will report
   * errors. We will start doing this a few seconds before the previous cert expiry whose value is
   * given by this constant.
   */
  @VisibleForTesting static final long GRACE_INTERVAL_IN_SECONDS = 4L;

  @VisibleForTesting
  ZatarCertificateProvider(
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
    final InternalLogId logId = InternalLogId.allocate("ZatarCertificateProvider", details);
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
    if (scheduledHandle != null && scheduledHandle.isPending()) {
      logger.log(Level.SEVERE, "Pending task found: inconsistent state in scheduledHandle!");
      scheduledHandle.cancel();
    }
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
      PrivateKey privateKey =
          CertificateUtils.getPrivateKey(
              new FileInputStream(new File(targetPath.toFile(), privateKeyFile)));
      X509Certificate[] certs =
          CertificateUtils.toX509Certificates(
              new FileInputStream(new File(targetPath.toFile(), certFile)));
      X509Certificate[] caCerts =
          CertificateUtils.toX509Certificates(
              new FileInputStream(new File(targetPath.toFile(), trustFile)));
      getWatcher().updateCertificate(privateKey, Arrays.asList(certs));
      getWatcher().updateTrustedRoots(Arrays.asList(caCerts));
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
      if (delaySeconds > GRACE_INTERVAL_IN_SECONDS) {
        logger.log(Level.FINER, "reload certificate error", t);
        return;
      }
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
}
