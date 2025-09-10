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

package io.grpc.xds.internal.security.certprovider;

import static com.google.common.truth.Truth.assertThat;
import static io.grpc.xds.internal.security.CommonTlsContextTestsUtil.CA_PEM_FILE;
import static io.grpc.xds.internal.security.CommonTlsContextTestsUtil.CLIENT_KEY_FILE;
import static io.grpc.xds.internal.security.CommonTlsContextTestsUtil.CLIENT_PEM_FILE;
import static io.grpc.xds.internal.security.CommonTlsContextTestsUtil.SERVER_0_KEY_FILE;
import static io.grpc.xds.internal.security.CommonTlsContextTestsUtil.SERVER_0_PEM_FILE;
import static io.grpc.xds.internal.security.CommonTlsContextTestsUtil.SERVER_1_PEM_FILE;
import static io.grpc.xds.internal.security.CommonTlsContextTestsUtil.SPIFFE_TRUST_MAP_1_FILE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.grpc.Status;
import io.grpc.internal.TimeProvider;
import io.grpc.xds.internal.security.CommonTlsContextTestsUtil;
import io.grpc.xds.internal.security.certprovider.CertificateProvider.DistributorWatcher;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.security.PrivateKey;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
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
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/** Unit tests for {@link FileWatcherCertificateProvider}. */
@RunWith(JUnit4.class)
public class FileWatcherCertificateProviderTest {
  /**
   * Expire time of cert SERVER_0_PEM_FILE.
   */
  static final long CERT0_EXPIRY_TIME_MILLIS = 1899853658000L;
  private static final String CERT_FILE = "cert.pem";
  private static final String KEY_FILE = "key.pem";
  private static final String ROOT_FILE = "root.pem";
  private static final String SPIFFE_TRUST_MAP_FILE = "spiffebundle.json";

  @Mock private CertificateProvider.Watcher mockWatcher;
  @Mock private ScheduledExecutorService timeService;
  private final FakeTimeProvider timeProvider = new FakeTimeProvider();

  @Rule public TemporaryFolder tempFolder = new TemporaryFolder();
  @Rule public final MockitoRule mocks = MockitoJUnit.rule();

  private String certFile;
  private String keyFile;
  private String rootFile;
  private String spiffeTrustMapFile;

  private FileWatcherCertificateProvider provider;
  private DistributorWatcher watcher;

  @Before
  public void setUp() throws IOException {
    watcher = new DistributorWatcher();
    watcher.addWatcher(mockWatcher);

    certFile = new File(tempFolder.getRoot(), CERT_FILE).getAbsolutePath();
    keyFile = new File(tempFolder.getRoot(), KEY_FILE).getAbsolutePath();
    rootFile = new File(tempFolder.getRoot(), ROOT_FILE).getAbsolutePath();
    spiffeTrustMapFile = new File(tempFolder.getRoot(), SPIFFE_TRUST_MAP_FILE).getAbsolutePath();
    provider =
        new FileWatcherCertificateProvider(watcher, true, certFile, keyFile, rootFile, null, 600L,
            timeService, timeProvider);
  }

  private void populateTarget(
      String certFileSource,
      String keyFileSource,
      String rootFileSource,
      String spiffeTrustMapFileSource,
      boolean deleteCurCert,
      boolean deleteCurKey,
      boolean deleteCurSpiffeTrustMap,
      boolean deleteCurRoot)
      throws IOException {
    if (deleteCurCert) {
      Files.delete(Paths.get(certFile));
    }
    if (certFileSource != null) {
      certFileSource = CommonTlsContextTestsUtil.getTempFileNameForResourcesFile(certFileSource);
      Files.copy(Paths.get(certFileSource), Paths.get(certFile), REPLACE_EXISTING);
      Files.setLastModifiedTime(
          Paths.get(certFile), FileTime.fromMillis(timeProvider.currentTimeMillis()));
    }
    if (deleteCurKey) {
      Files.delete(Paths.get(keyFile));
    }
    if (keyFileSource != null) {
      keyFileSource = CommonTlsContextTestsUtil.getTempFileNameForResourcesFile(keyFileSource);
      Files.copy(Paths.get(keyFileSource), Paths.get(keyFile), REPLACE_EXISTING);
      Files.setLastModifiedTime(
          Paths.get(keyFile), FileTime.fromMillis(timeProvider.currentTimeMillis()));
    }
    if (deleteCurRoot) {
      Files.delete(Paths.get(rootFile));
    }
    if (rootFileSource != null) {
      rootFileSource = CommonTlsContextTestsUtil.getTempFileNameForResourcesFile(rootFileSource);
      Files.copy(Paths.get(rootFileSource), Paths.get(rootFile), REPLACE_EXISTING);
      Files.setLastModifiedTime(
          Paths.get(rootFile), FileTime.fromMillis(timeProvider.currentTimeMillis()));
    }
    if (deleteCurSpiffeTrustMap) {
      Files.delete(Paths.get(spiffeTrustMapFile));
    }
    if (spiffeTrustMapFileSource != null) {
      spiffeTrustMapFileSource = CommonTlsContextTestsUtil
          .getTempFileNameForResourcesFile(spiffeTrustMapFileSource);
      Files.copy(Paths.get(spiffeTrustMapFileSource),
          Paths.get(spiffeTrustMapFile), REPLACE_EXISTING);
      Files.setLastModifiedTime(
          Paths.get(spiffeTrustMapFile), FileTime.fromMillis(timeProvider.currentTimeMillis()));
    }
  }

  @Test
  public void getCertificateAndCheckUpdates() throws IOException, CertificateException {
    TestScheduledFuture<?> scheduledFuture =
        new TestScheduledFuture<>();
    doReturn(scheduledFuture)
        .when(timeService)
        .schedule(any(Runnable.class), any(Long.TYPE), eq(TimeUnit.SECONDS));
    populateTarget(CLIENT_PEM_FILE, CLIENT_KEY_FILE, CA_PEM_FILE, null, false, false, false, false);
    provider.checkAndReloadCertificates();
    verifyWatcherUpdates(CLIENT_PEM_FILE, CA_PEM_FILE, null);
    verifyTimeServiceAndScheduledFuture();

    reset(mockWatcher, timeService);
    doReturn(scheduledFuture)
        .when(timeService)
        .schedule(any(Runnable.class), any(Long.TYPE), eq(TimeUnit.SECONDS));
    provider.checkAndReloadCertificates();
    verifyWatcherErrorUpdates(null, null, 0, 0, (String[]) null);
    verifyTimeServiceAndScheduledFuture();
  }

  @Test
  public void allUpdateSecondTime() throws IOException, CertificateException, InterruptedException {
    TestScheduledFuture<?> scheduledFuture =
        new TestScheduledFuture<>();
    doReturn(scheduledFuture)
        .when(timeService)
        .schedule(any(Runnable.class), any(Long.TYPE), eq(TimeUnit.SECONDS));
    populateTarget(CLIENT_PEM_FILE, CLIENT_KEY_FILE, CA_PEM_FILE, null, false, false, false, false);
    provider.checkAndReloadCertificates();

    reset(mockWatcher, timeService);
    doReturn(scheduledFuture)
        .when(timeService)
        .schedule(any(Runnable.class), any(Long.TYPE), eq(TimeUnit.SECONDS));
    timeProvider.forwardTime(1, TimeUnit.SECONDS);
    populateTarget(SERVER_0_PEM_FILE, SERVER_0_KEY_FILE, SERVER_1_PEM_FILE, null, false, false,
        false, false);
    provider.checkAndReloadCertificates();
    verifyWatcherUpdates(SERVER_0_PEM_FILE, SERVER_1_PEM_FILE, null);
    verifyTimeServiceAndScheduledFuture();
  }

  @Test
  public void closeDoesNotScheduleNext() throws IOException, CertificateException {
    TestScheduledFuture<?> scheduledFuture =
            new TestScheduledFuture<>();
    doReturn(scheduledFuture)
            .when(timeService)
            .schedule(any(Runnable.class), any(Long.TYPE), eq(TimeUnit.SECONDS));
    populateTarget(CLIENT_PEM_FILE, CLIENT_KEY_FILE, CA_PEM_FILE, null, false, false, false, false);
    provider.close();
    provider.checkAndReloadCertificates();
    verify(mockWatcher, never())
        .updateCertificate(any(PrivateKey.class), ArgumentMatchers.<X509Certificate>anyList());
    verify(mockWatcher, never()).updateTrustedRoots(ArgumentMatchers.<X509Certificate>anyList());
    verify(mockWatcher, never()).updateSpiffeTrustMap(ArgumentMatchers.anyMap());
    verify(timeService, never()).schedule(any(Runnable.class), any(Long.TYPE), any(TimeUnit.class));
    verify(timeService, times(1)).shutdownNow();
  }


  @Test
  public void rootFileUpdateOnly() throws IOException, CertificateException, InterruptedException {
    TestScheduledFuture<?> scheduledFuture =
        new TestScheduledFuture<>();
    doReturn(scheduledFuture)
        .when(timeService)
        .schedule(any(Runnable.class), any(Long.TYPE), eq(TimeUnit.SECONDS));
    populateTarget(CLIENT_PEM_FILE, CLIENT_KEY_FILE, CA_PEM_FILE, null, false, false, false, false);
    provider.checkAndReloadCertificates();

    reset(mockWatcher, timeService);
    doReturn(scheduledFuture)
        .when(timeService)
        .schedule(any(Runnable.class), any(Long.TYPE), eq(TimeUnit.SECONDS));
    timeProvider.forwardTime(1, TimeUnit.SECONDS);
    populateTarget(null, null, SERVER_1_PEM_FILE, null, false, false, false, false);
    provider.checkAndReloadCertificates();
    verifyWatcherUpdates(null, SERVER_1_PEM_FILE, null);
    verifyTimeServiceAndScheduledFuture();
  }

  @Test
  public void certAndKeyFileUpdateOnly()
      throws IOException, CertificateException, InterruptedException {
    TestScheduledFuture<?> scheduledFuture =
        new TestScheduledFuture<>();
    doReturn(scheduledFuture)
        .when(timeService)
        .schedule(any(Runnable.class), any(Long.TYPE), eq(TimeUnit.SECONDS));
    populateTarget(CLIENT_PEM_FILE, CLIENT_KEY_FILE, CA_PEM_FILE, null, false, false, false, false);
    provider.checkAndReloadCertificates();

    reset(mockWatcher, timeService);
    doReturn(scheduledFuture)
        .when(timeService)
        .schedule(any(Runnable.class), any(Long.TYPE), eq(TimeUnit.SECONDS));
    timeProvider.forwardTime(1, TimeUnit.SECONDS);
    populateTarget(SERVER_0_PEM_FILE, SERVER_0_KEY_FILE, null, null, false, false, false, false);
    provider.checkAndReloadCertificates();
    verifyWatcherUpdates(SERVER_0_PEM_FILE, null, null);
    verifyTimeServiceAndScheduledFuture();
  }

  @Test
  public void spiffeTrustMapFileUpdateOnly() throws Exception {
    provider = new FileWatcherCertificateProvider(watcher, true, certFile, keyFile, null,
        spiffeTrustMapFile, 600L, timeService, timeProvider);
    TestScheduledFuture<?> scheduledFuture =
        new TestScheduledFuture<>();
    doReturn(scheduledFuture)
        .when(timeService)
        .schedule(any(Runnable.class), any(Long.TYPE), eq(TimeUnit.SECONDS));
    populateTarget(CLIENT_PEM_FILE, CLIENT_KEY_FILE, null, null, false, false, false, false);
    provider.checkAndReloadCertificates();
    verify(mockWatcher, never()).updateSpiffeTrustMap(ArgumentMatchers.anyMap());

    reset(timeService);
    doReturn(scheduledFuture)
        .when(timeService)
        .schedule(any(Runnable.class), any(Long.TYPE), eq(TimeUnit.SECONDS));
    timeProvider.forwardTime(1, TimeUnit.SECONDS);
    populateTarget(CLIENT_PEM_FILE, CLIENT_KEY_FILE, null, SPIFFE_TRUST_MAP_FILE, false,
        false, false, false);
    provider.checkAndReloadCertificates();
    verify(mockWatcher, times(1)).updateSpiffeTrustMap(ArgumentMatchers.anyMap());

    reset(timeService);
    doReturn(scheduledFuture)
        .when(timeService)
        .schedule(any(Runnable.class), any(Long.TYPE), eq(TimeUnit.SECONDS));
    timeProvider.forwardTime(1, TimeUnit.SECONDS);
    populateTarget(CLIENT_PEM_FILE, CLIENT_KEY_FILE, null, SPIFFE_TRUST_MAP_1_FILE, false,
        false, false, false);
    provider.checkAndReloadCertificates();
    verify(mockWatcher, times(2)).updateSpiffeTrustMap(ArgumentMatchers.anyMap());
    verifyTimeServiceAndScheduledFuture();
  }

  @Test
  public void getCertificate_initialMissingCertFile() throws IOException {
    TestScheduledFuture<?> scheduledFuture =
        new TestScheduledFuture<>();
    doReturn(scheduledFuture)
        .when(timeService)
        .schedule(any(Runnable.class), any(Long.TYPE), eq(TimeUnit.SECONDS));
    populateTarget(null, CLIENT_KEY_FILE, CA_PEM_FILE, null, false, false, false, false);
    provider.checkAndReloadCertificates();
    verifyWatcherErrorUpdates(Status.Code.UNKNOWN, NoSuchFileException.class, 0, 1, "cert.pem");
  }

  @Test
  public void getCertificate_missingCertFile() throws IOException, InterruptedException {
    commonErrorTest(
        null, CLIENT_KEY_FILE, CA_PEM_FILE, null, NoSuchFileException.class, 0, 1, 0, 0,
        "cert.pem");
  }

  @Test
  public void getCertificate_missingKeyFile() throws IOException, InterruptedException {
    commonErrorTest(
        CLIENT_PEM_FILE, null, CA_PEM_FILE, null, NoSuchFileException.class, 0, 1, 0, 0, "key.pem");
  }

  @Test
  public void getCertificate_badKeyFile() throws IOException, InterruptedException {
    commonErrorTest(
        CLIENT_PEM_FILE,
        SERVER_0_PEM_FILE,
        CA_PEM_FILE,
        null,
        java.security.spec.InvalidKeySpecException.class,
        0,
        1,
        0,
        0,
        "Neither RSA nor EC worked");
  }

  @Test
  public void getCertificate_missingRootFile() throws IOException, InterruptedException {
    TestScheduledFuture<?> scheduledFuture =
        new TestScheduledFuture<>();
    doReturn(scheduledFuture)
        .when(timeService)
        .schedule(any(Runnable.class), any(Long.TYPE), eq(TimeUnit.SECONDS));
    populateTarget(SERVER_0_PEM_FILE, SERVER_0_KEY_FILE, SERVER_1_PEM_FILE, null, false, false,
        false, false);
    provider.checkAndReloadCertificates();

    reset(mockWatcher);
    timeProvider.forwardTime(1, TimeUnit.SECONDS);
    populateTarget(CLIENT_PEM_FILE, CLIENT_KEY_FILE, null, null, false, false, false, true);
    timeProvider.forwardTime(
        CERT0_EXPIRY_TIME_MILLIS - 610_000L - timeProvider.currentTimeMillis(),
        TimeUnit.MILLISECONDS);
    provider.checkAndReloadCertificates();
    verifyWatcherErrorUpdates(Status.Code.UNKNOWN, NoSuchFileException.class, 1, 0, "root.pem");
  }

  private void commonErrorTest(
      String certFile,
      String keyFile,
      String rootFile,
      String spiffeFile,
      Class<?> throwableType,
      int firstUpdateCertCount,
      int firstUpdateRootCount,
      int secondUpdateCertCount,
      int secondUpdateRootCount,
      String... causeMessages)
      throws IOException, InterruptedException {
    TestScheduledFuture<?> scheduledFuture =
        new TestScheduledFuture<>();
    doReturn(scheduledFuture)
        .when(timeService)
        .schedule(any(Runnable.class), any(Long.TYPE), eq(TimeUnit.SECONDS));
    populateTarget(SERVER_0_PEM_FILE, SERVER_0_KEY_FILE, SERVER_1_PEM_FILE,
        SPIFFE_TRUST_MAP_1_FILE, false, false, false, false);
    provider.checkAndReloadCertificates();

    reset(mockWatcher);
    timeProvider.forwardTime(1, TimeUnit.SECONDS);
    populateTarget(
        certFile, keyFile, rootFile, spiffeFile, certFile == null, keyFile == null,
        rootFile == null, spiffeFile == null);
    timeProvider.forwardTime(
        CERT0_EXPIRY_TIME_MILLIS - 610_000L - timeProvider.currentTimeMillis(),
        TimeUnit.MILLISECONDS);
    provider.checkAndReloadCertificates();
    verifyWatcherErrorUpdates(
        null, null, firstUpdateCertCount, firstUpdateRootCount, (String[]) null);

    reset(mockWatcher);
    timeProvider.forwardTime(20, TimeUnit.SECONDS);
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
      ArgumentCaptor<Status> statusCaptor = ArgumentCaptor.forClass(Status.class);
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

  private void verifyTimeServiceAndScheduledFuture() {
    verify(timeService, times(1)).schedule(any(Runnable.class), eq(600L), eq(TimeUnit.SECONDS));
    assertThat(provider.scheduledFuture).isNotNull();
    assertThat(provider.scheduledFuture.isDone()).isFalse();
    assertThat(provider.scheduledFuture.isCancelled()).isFalse();
  }

  private void verifyWatcherUpdates(String certPemFile, String rootPemFile, String spiffeFile)
      throws IOException, CertificateException {
    if (certPemFile != null) {
      @SuppressWarnings("unchecked")
      ArgumentCaptor<List<X509Certificate>> certChainCaptor = ArgumentCaptor.forClass(List.class);
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
      @SuppressWarnings("unchecked")
      ArgumentCaptor<List<X509Certificate>> rootsCaptor = ArgumentCaptor.forClass(List.class);
      verify(mockWatcher, times(1)).updateTrustedRoots(rootsCaptor.capture());
      List<X509Certificate> roots = rootsCaptor.getValue();
      assertThat(roots).hasSize(1);
      assertThat(roots.get(0))
          .isEqualTo(CommonTlsContextTestsUtil.getCertFromResourceName(rootPemFile));
      verify(mockWatcher, never()).onError(any(Status.class));
    } else {
      verify(mockWatcher, never()).updateTrustedRoots(ArgumentMatchers.<X509Certificate>anyList());
    }
    if (spiffeFile != null) {
      @SuppressWarnings("unchecked")
      ArgumentCaptor<Map<String, List<X509Certificate>>> spiffeCaptor =
          ArgumentCaptor.forClass(Map.class);
      verify(mockWatcher, times(1)).updateSpiffeTrustMap(spiffeCaptor.capture());
      Map<String, List<X509Certificate>> trustMap = spiffeCaptor.getValue();
      assertThat(trustMap).hasSize(2);
      verify(mockWatcher, never()).onError(any(Status.class));
    } else {
      verify(mockWatcher, never()).updateSpiffeTrustMap(ArgumentMatchers.anyMap());
    }
  }

  static class TestScheduledFuture<V> implements ScheduledFuture<V> {

    static class Record {
      long timeout;
      TimeUnit unit;

      Record(long timeout, TimeUnit unit) {
        this.timeout = timeout;
        this.unit = unit;
      }
    }

    ArrayList<Record> calls = new ArrayList<>();

    @Override
    public long getDelay(TimeUnit unit) {
      return 0;
    }

    @Override
    public int compareTo(Delayed o) {
      return 0;
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
      return false;
    }

    @Override
    public boolean isCancelled() {
      return false;
    }

    @Override
    public boolean isDone() {
      return false;
    }

    @Override
    public V get() {
      return null;
    }

    @Override
    public V get(long timeout, TimeUnit unit) {
      calls.add(new Record(timeout, unit));
      return null;
    }
  }

  /**
   * Fake TimeProvider that roughly mirrors FakeClock. Not using FakeClock because it incorrectly
   * fails to align the wall-time API TimeProvider.currentTimeNanos() with currentTimeMillis() and
   * fixing it upsets a _lot_ of tests.
   */
  static class FakeTimeProvider implements TimeProvider {
    public long currentTimeNanos = TimeUnit.SECONDS.toNanos(1262332800); /* 2010-01-01 */

    @Override public long currentTimeNanos() {
      return currentTimeNanos;
    }

    public void forwardTime(long duration, TimeUnit unit) {
      currentTimeNanos += unit.toNanos(duration);
    }

    public long currentTimeMillis() {
      return TimeUnit.NANOSECONDS.toMillis(currentTimeNanos);
    }
  }
}
