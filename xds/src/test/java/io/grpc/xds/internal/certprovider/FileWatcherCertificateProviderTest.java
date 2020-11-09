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

import static com.google.common.truth.Truth.assertThat;
import static io.grpc.xds.internal.sds.CommonTlsContextTestsUtil.CA_PEM_FILE;
import static io.grpc.xds.internal.sds.CommonTlsContextTestsUtil.CLIENT_KEY_FILE;
import static io.grpc.xds.internal.sds.CommonTlsContextTestsUtil.CLIENT_PEM_FILE;
import static io.grpc.xds.internal.sds.CommonTlsContextTestsUtil.SERVER_0_KEY_FILE;
import static io.grpc.xds.internal.sds.CommonTlsContextTestsUtil.SERVER_0_PEM_FILE;
import static io.grpc.xds.internal.sds.CommonTlsContextTestsUtil.SERVER_1_PEM_FILE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.Status;
import io.grpc.internal.TimeProvider;
import io.grpc.xds.internal.certprovider.CertificateProvider.DistributorWatcher;
import io.grpc.xds.internal.sds.CommonTlsContextTestsUtil;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Paths;
import java.security.PrivateKey;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/** Unit tests for {@link FileWatcherCertificateProvider}. */
@RunWith(JUnit4.class)
public class FileWatcherCertificateProviderTest {
  private static final String CERT_FILE = "cert.pem";
  private static final String KEY_FILE = "key.pem";
  private static final String ROOT_FILE = "root.pem";

  @Mock private CertificateProvider.Watcher mockWatcher;
  @Mock private ScheduledExecutorService timeService;
  @Mock private TimeProvider timeProvider;

  @Rule public TemporaryFolder tempFolder = new TemporaryFolder();

  private String certFile;
  private String keyFile;
  private String rootFile;

  private FileWatcherCertificateProvider provider;

  @Before
  public void setUp() throws IOException {
    MockitoAnnotations.initMocks(this);

    DistributorWatcher watcher = new DistributorWatcher();
    watcher.addWatcher(mockWatcher);

    certFile = new File(tempFolder.getRoot(), CERT_FILE).getAbsolutePath();
    keyFile = new File(tempFolder.getRoot(), KEY_FILE).getAbsolutePath();
    rootFile = new File(tempFolder.getRoot(), ROOT_FILE).getAbsolutePath();
    provider =
        new FileWatcherCertificateProvider(
            watcher, true, certFile, keyFile, rootFile, 600L, timeService, timeProvider);
  }

  private void populateTarget(
      String certFileSource,
      String keyFileSource,
      String rootFileSource,
      boolean deleteCurCert,
      boolean deleteCurKey,
      boolean deleteCurRoot)
      throws IOException {
    if (deleteCurCert) {
      Files.delete(Paths.get(certFile));
    }
    if (certFileSource != null) {
      certFileSource = CommonTlsContextTestsUtil.getTempFileNameForResourcesFile(certFileSource);
      Files.copy(Paths.get(certFileSource), Paths.get(certFile), REPLACE_EXISTING);
    }
    if (deleteCurKey) {
      Files.delete(Paths.get(keyFile));
    }
    if (keyFileSource != null) {
      keyFileSource = CommonTlsContextTestsUtil.getTempFileNameForResourcesFile(keyFileSource);
      Files.copy(Paths.get(keyFileSource), Paths.get(keyFile), REPLACE_EXISTING);
    }
    if (deleteCurRoot) {
      Files.delete(Paths.get(rootFile));
    }
    if (rootFileSource != null) {
      rootFileSource = CommonTlsContextTestsUtil.getTempFileNameForResourcesFile(rootFileSource);
      Files.copy(Paths.get(rootFileSource), Paths.get(rootFile), REPLACE_EXISTING);
    }
  }

  @Test
  public void getCertificateAndCheckUpdates() throws IOException, CertificateException {
    MeshCaCertificateProviderTest.TestScheduledFuture<?> scheduledFuture =
        new MeshCaCertificateProviderTest.TestScheduledFuture<>();
    doReturn(scheduledFuture)
        .when(timeService)
        .schedule(any(Runnable.class), any(Long.TYPE), eq(TimeUnit.SECONDS));
    populateTarget(CLIENT_PEM_FILE, CLIENT_KEY_FILE, CA_PEM_FILE, false, false, false);
    provider.checkAndReloadCertificates();
    verifyWatcherUpdates(CLIENT_PEM_FILE, CA_PEM_FILE);
    verifyTimeServiceAndScheduledHandle();

    reset(mockWatcher, timeService);
    doReturn(scheduledFuture)
        .when(timeService)
        .schedule(any(Runnable.class), any(Long.TYPE), eq(TimeUnit.SECONDS));
    provider.checkAndReloadCertificates();
    verifyWatcherErrorUpdates(null, null, 0, 0, (String[]) null);
    verifyTimeServiceAndScheduledHandle();
  }

  @Test
  public void allUpdateSecondTime() throws IOException, CertificateException, InterruptedException {
    MeshCaCertificateProviderTest.TestScheduledFuture<?> scheduledFuture =
        new MeshCaCertificateProviderTest.TestScheduledFuture<>();
    doReturn(scheduledFuture)
        .when(timeService)
        .schedule(any(Runnable.class), any(Long.TYPE), eq(TimeUnit.SECONDS));
    populateTarget(CLIENT_PEM_FILE, CLIENT_KEY_FILE, CA_PEM_FILE, false, false, false);
    provider.checkAndReloadCertificates();

    reset(mockWatcher, timeService);
    doReturn(scheduledFuture)
        .when(timeService)
        .schedule(any(Runnable.class), any(Long.TYPE), eq(TimeUnit.SECONDS));
    Thread.sleep(1000L);
    populateTarget(SERVER_0_PEM_FILE, SERVER_0_KEY_FILE, SERVER_1_PEM_FILE, false, false, false);
    provider.checkAndReloadCertificates();
    verifyWatcherUpdates(SERVER_0_PEM_FILE, SERVER_1_PEM_FILE);
    verifyTimeServiceAndScheduledHandle();
  }

  @Test
  public void rootFileUpdateOnly() throws IOException, CertificateException, InterruptedException {
    MeshCaCertificateProviderTest.TestScheduledFuture<?> scheduledFuture =
        new MeshCaCertificateProviderTest.TestScheduledFuture<>();
    doReturn(scheduledFuture)
        .when(timeService)
        .schedule(any(Runnable.class), any(Long.TYPE), eq(TimeUnit.SECONDS));
    populateTarget(CLIENT_PEM_FILE, CLIENT_KEY_FILE, CA_PEM_FILE, false, false, false);
    provider.checkAndReloadCertificates();

    reset(mockWatcher, timeService);
    doReturn(scheduledFuture)
        .when(timeService)
        .schedule(any(Runnable.class), any(Long.TYPE), eq(TimeUnit.SECONDS));
    Thread.sleep(1000L);
    populateTarget(null, null, SERVER_1_PEM_FILE, false, false, false);
    provider.checkAndReloadCertificates();
    verifyWatcherUpdates(null, SERVER_1_PEM_FILE);
    verifyTimeServiceAndScheduledHandle();
  }

  @Test
  public void certAndKeyFileUpdateOnly()
      throws IOException, CertificateException, InterruptedException {
    MeshCaCertificateProviderTest.TestScheduledFuture<?> scheduledFuture =
        new MeshCaCertificateProviderTest.TestScheduledFuture<>();
    doReturn(scheduledFuture)
        .when(timeService)
        .schedule(any(Runnable.class), any(Long.TYPE), eq(TimeUnit.SECONDS));
    populateTarget(CLIENT_PEM_FILE, CLIENT_KEY_FILE, CA_PEM_FILE, false, false, false);
    provider.checkAndReloadCertificates();

    reset(mockWatcher, timeService);
    doReturn(scheduledFuture)
        .when(timeService)
        .schedule(any(Runnable.class), any(Long.TYPE), eq(TimeUnit.SECONDS));
    Thread.sleep(1000L);
    populateTarget(SERVER_0_PEM_FILE, SERVER_0_KEY_FILE, null, false, false, false);
    provider.checkAndReloadCertificates();
    verifyWatcherUpdates(SERVER_0_PEM_FILE, null);
    verifyTimeServiceAndScheduledHandle();
  }

  @Test
  public void getCertificate_initialMissingCertFile() throws IOException {
    MeshCaCertificateProviderTest.TestScheduledFuture<?> scheduledFuture =
        new MeshCaCertificateProviderTest.TestScheduledFuture<>();
    doReturn(scheduledFuture)
        .when(timeService)
        .schedule(any(Runnable.class), any(Long.TYPE), eq(TimeUnit.SECONDS));
    populateTarget(null, CLIENT_KEY_FILE, CA_PEM_FILE, false, false, false);
    when(timeProvider.currentTimeNanos())
        .thenReturn(TimeProvider.SYSTEM_TIME_PROVIDER.currentTimeNanos());
    provider.checkAndReloadCertificates();
    verifyWatcherErrorUpdates(Status.Code.UNKNOWN, NoSuchFileException.class, 0, 1, "cert.pem");
  }

  @Test
  public void getCertificate_missingCertFile() throws IOException, InterruptedException {
    commonErrorTest(
        null, CLIENT_KEY_FILE, CA_PEM_FILE, NoSuchFileException.class, 0, 1, 0, 0, "cert.pem");
  }

  @Test
  public void getCertificate_missingKeyFile() throws IOException, InterruptedException {
    commonErrorTest(
        CLIENT_PEM_FILE, null, CA_PEM_FILE, NoSuchFileException.class, 0, 1, 0, 0, "key.pem");
  }

  @Test
  public void getCertificate_badKeyFile() throws IOException, InterruptedException {
    commonErrorTest(
        CLIENT_PEM_FILE,
        SERVER_0_PEM_FILE,
        CA_PEM_FILE,
        java.security.KeyException.class,
        0,
        1,
        0,
        0,
        "could not find a PKCS #8 private key in input stream");
  }

  @Test
  public void getCertificate_missingRootFile() throws IOException, InterruptedException {
    MeshCaCertificateProviderTest.TestScheduledFuture<?> scheduledFuture =
        new MeshCaCertificateProviderTest.TestScheduledFuture<>();
    doReturn(scheduledFuture)
        .when(timeService)
        .schedule(any(Runnable.class), any(Long.TYPE), eq(TimeUnit.SECONDS));
    populateTarget(SERVER_0_PEM_FILE, SERVER_0_KEY_FILE, SERVER_1_PEM_FILE, false, false, false);
    provider.checkAndReloadCertificates();

    reset(mockWatcher);
    Thread.sleep(1000L);
    populateTarget(CLIENT_PEM_FILE, CLIENT_KEY_FILE, null, false, false, true);
    when(timeProvider.currentTimeNanos())
        .thenReturn(
            TimeUnit.MILLISECONDS.toNanos(
                MeshCaCertificateProviderTest.CERT0_EXPIRY_TIME_MILLIS - 610_000L));
    provider.checkAndReloadCertificates();
    verifyWatcherErrorUpdates(Status.Code.UNKNOWN, NoSuchFileException.class, 1, 0, "root.pem");
  }

  private void commonErrorTest(
      String certFile,
      String keyFile,
      String rootFile,
      Class<?> throwableType,
      int firstUpdateCertCount,
      int firstUpdateRootCount,
      int secondUpdateCertCount,
      int secondUpdateRootCount,
      String... causeMessages)
      throws IOException, InterruptedException {
    MeshCaCertificateProviderTest.TestScheduledFuture<?> scheduledFuture =
        new MeshCaCertificateProviderTest.TestScheduledFuture<>();
    doReturn(scheduledFuture)
        .when(timeService)
        .schedule(any(Runnable.class), any(Long.TYPE), eq(TimeUnit.SECONDS));
    populateTarget(SERVER_0_PEM_FILE, SERVER_0_KEY_FILE, SERVER_1_PEM_FILE, false, false, false);
    provider.checkAndReloadCertificates();

    reset(mockWatcher);
    Thread.sleep(1000L);
    populateTarget(
        certFile, keyFile, rootFile, certFile == null, keyFile == null, rootFile == null);
    when(timeProvider.currentTimeNanos())
        .thenReturn(
            TimeUnit.MILLISECONDS.toNanos(
                MeshCaCertificateProviderTest.CERT0_EXPIRY_TIME_MILLIS - 610_000L));
    provider.checkAndReloadCertificates();
    verifyWatcherErrorUpdates(
        null, null, firstUpdateCertCount, firstUpdateRootCount, (String[]) null);

    reset(mockWatcher, timeProvider);
    when(timeProvider.currentTimeNanos())
        .thenReturn(
            TimeUnit.MILLISECONDS.toNanos(
                MeshCaCertificateProviderTest.CERT0_EXPIRY_TIME_MILLIS - 590_000L));
    provider.checkAndReloadCertificates();
    verifyWatcherErrorUpdates(
        Status.Code.UNKNOWN,
        throwableType,
        secondUpdateCertCount,
        secondUpdateRootCount,
        causeMessages);
  }

  private void verifyWatcherErrorUpdates(
      Status.Code code,
      Class<?> throwableType,
      int updateCertCount,
      int updateRootCount,
      String... causeMessages) {
    verify(mockWatcher, times(updateCertCount))
        .updateCertificate(any(PrivateKey.class), ArgumentMatchers.<X509Certificate>anyList());
    verify(mockWatcher, times(updateRootCount))
        .updateTrustedRoots(ArgumentMatchers.<X509Certificate>anyList());
    if (code == null && throwableType == null && causeMessages == null) {
      verify(mockWatcher, never()).onError(any(Status.class));
    } else {
      ArgumentCaptor<Status> statusCaptor = ArgumentCaptor.forClass(null);
      verify(mockWatcher, times(1)).onError(statusCaptor.capture());
      Status status = statusCaptor.getValue();
      assertThat(status.getCode()).isEqualTo(code);
      Throwable cause = status.getCause();
      assertThat(cause).isInstanceOf(throwableType);
      for (String causeMessage : causeMessages) {
        assertThat(cause).hasMessageThat().contains(causeMessage);
        cause = cause.getCause();
      }
    }
  }

  private void verifyTimeServiceAndScheduledHandle() {
    verify(timeService, times(1)).schedule(any(Runnable.class), eq(600L), eq(TimeUnit.SECONDS));
    assertThat(provider.scheduledHandle).isNotNull();
    assertThat(provider.scheduledHandle.isPending()).isTrue();
  }

  private void verifyWatcherUpdates(String certPemFile, String rootPemFile)
      throws IOException, CertificateException {
    if (certPemFile != null) {
      ArgumentCaptor<List<X509Certificate>> certChainCaptor = ArgumentCaptor.forClass(null);
      verify(mockWatcher, times(1))
          .updateCertificate(any(PrivateKey.class), certChainCaptor.capture());
      List<X509Certificate> certChain = certChainCaptor.getValue();
      assertThat(certChain).hasSize(1);
      assertThat(certChain.get(0))
          .isEqualTo(CommonTlsContextTestsUtil.getCertFromResourceName(certPemFile));
    } else {
      verify(mockWatcher, never())
          .updateCertificate(any(PrivateKey.class), ArgumentMatchers.<X509Certificate>anyList());
    }
    if (rootPemFile != null) {
      ArgumentCaptor<List<X509Certificate>> rootsCaptor = ArgumentCaptor.forClass(null);
      verify(mockWatcher, times(1)).updateTrustedRoots(rootsCaptor.capture());
      List<X509Certificate> roots = rootsCaptor.getValue();
      assertThat(roots).hasSize(1);
      assertThat(roots.get(0))
          .isEqualTo(CommonTlsContextTestsUtil.getCertFromResourceName(rootPemFile));
      verify(mockWatcher, never()).onError(any(Status.class));
    } else {
      verify(mockWatcher, never()).updateTrustedRoots(ArgumentMatchers.<X509Certificate>anyList());
    }
  }
}
