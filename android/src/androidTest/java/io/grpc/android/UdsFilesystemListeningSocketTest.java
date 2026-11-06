/*
 * Copyright 2026 The gRPC Authors
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

package io.grpc.android;

import static com.google.common.truth.Truth.assertThat;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.assertThrows;

import android.content.Context;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.net.LocalSocketAddress.Namespace;
import android.os.Process;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

/** On-device tests for {@link UdsFilesystemListeningSocket}. */
@RunWith(AndroidJUnit4.class)
public final class UdsFilesystemListeningSocketTest {
  private final Context appContext = ApplicationProvider.getApplicationContext();
  private final List<Closeable> closeables = new ArrayList<>();
  private final List<File> tempPaths = new ArrayList<>();

  @After
  public void tearDown() {
    for (int i = closeables.size() - 1; i >= 0; i--) {
      try {
        closeables.get(i).close();
      } catch (IOException ignored) {
        // Best-effort test cleanup.
      }
    }
    for (int i = tempPaths.size() - 1; i >= 0; i--) {
      tempPaths.get(i).delete();
    }
  }

  @Test
  public void bindAndListen_abstractAddress_throwsIllegalArgumentException() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            UdsFilesystemListeningSocket.bindAndListen(
                UdsSocketAddress.forAbstractName("abstract")));
  }

  @Test
  public void bindAndListen_createsSocketFileWithMode0600() throws Exception {
    File socketFile = newSocketFile();
    UdsSocketAddress address = UdsSocketAddress.forFile(socketFile);

    UdsFilesystemListeningSocket listening =
        register(UdsFilesystemListeningSocket.bindAndListen(address));

    assertThat(listening.getAddress()).isEqualTo(address);
    StructStat stat = Os.lstat(socketFile.getAbsolutePath());
    assertThat(OsConstants.S_ISSOCK(stat.st_mode)).isTrue();
    assertThat(stat.st_mode & 0777).isEqualTo(0600);
    assertThat(stat.st_uid).isEqualTo(Process.myUid());
  }

  @Test
  public void bindAndListen_staleSocketFileWithBroadMode_replacesAndResetsModeTo0600()
      throws Exception {
    File socketFile = newSocketFile();
    LocalSocketAddress rawAddress =
        new LocalSocketAddress(socketFile.getAbsolutePath(), Namespace.FILESYSTEM);
    LocalSocket stale = new LocalSocket();
    stale.bind(rawAddress);
    stale.close();
    Os.chmod(socketFile.getAbsolutePath(), 0777);
    LocalSocket probe = register(new LocalSocket());
    assertThrows(IOException.class, () -> probe.connect(rawAddress));

    UdsFilesystemListeningSocket listening =
        register(UdsFilesystemListeningSocket.bindAndListen(UdsSocketAddress.forFile(socketFile)));

    StructStat replacement = Os.lstat(socketFile.getAbsolutePath());
    assertThat(replacement.st_mode & 0777).isEqualTo(0600);
    LocalSocket client = register(new LocalSocket());
    client.connect(rawAddress);
    register(listening.accept());
  }

  @Test
  public void bindAndListen_missingParentDirectory_throwsIOException() {
    File missingDir = new File(appContext.getFilesDir(), "missing-" + UUID.randomUUID());
    File socketFile = new File(missingDir, "test.sock");

    assertThrows(
        IOException.class,
        () ->
            UdsFilesystemListeningSocket.bindAndListen(UdsSocketAddress.forFile(socketFile)));
    assertThat(socketFile.exists()).isFalse();
  }

  @Test
  public void bindAndListen_directoryAtPath_refusesAndLeavesDirectoryIntact() throws Exception {
    File dir = newSocketFile();
    assertThat(dir.mkdir()).isTrue();

    IOException e =
        assertThrows(
            IOException.class,
            () -> UdsFilesystemListeningSocket.bindAndListen(UdsSocketAddress.forFile(dir)));

    assertThat(e).hasMessageThat().contains("non-socket");
    assertThat(dir.isDirectory()).isTrue();
  }

  @Test
  public void accept_returnsConnectedUdsSocketWithListenAddress() throws Exception {
    File socketFile = newSocketFile();
    UdsSocketAddress address = UdsSocketAddress.forFile(socketFile);
    UdsFilesystemListeningSocket listening =
        register(UdsFilesystemListeningSocket.bindAndListen(address));

    LocalSocket client = register(new LocalSocket());
    client.connect(new LocalSocketAddress(socketFile.getAbsolutePath(), Namespace.FILESYSTEM));

    Socket accepted = register(listening.accept());
    assertThat(accepted).isInstanceOf(UdsSocket.Accepted.class);
    assertThat(accepted.getLocalSocketAddress()).isEqualTo(address);

    client.getOutputStream().write(99);
    assertThat(accepted.getInputStream().read()).isEqualTo(99);
  }

  @Test
  public void close_unlinksSocketFileAndWakesBlockedAccept() throws Exception {
    File socketFile = newSocketFile();
    UdsFilesystemListeningSocket listening =
        register(UdsFilesystemListeningSocket.bindAndListen(UdsSocketAddress.forFile(socketFile)));
    CountDownLatch callingAccept = new CountDownLatch(1);
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      Future<Socket> acceptFuture =
          executor.submit(
              () -> {
                callingAccept.countDown();
                return listening.accept();
              });
      assertThat(callingAccept.await(5, SECONDS)).isTrue();
      Thread.sleep(50);

      listening.close();

      ExecutionException e =
          assertThrows(ExecutionException.class, () -> acceptFuture.get(5, SECONDS));
      assertThat(e).hasCauseThat().isInstanceOf(IOException.class);
      assertThat(socketFile.exists()).isFalse();
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  public void close_socketFileReplacedByNewerSocket_leavesReplacementInode() throws Exception {
    File socketFile = newSocketFile();
    UdsSocketAddress address = UdsSocketAddress.forFile(socketFile);
    UdsFilesystemListeningSocket older =
        register(UdsFilesystemListeningSocket.bindAndListen(address));
    UdsFilesystemListeningSocket newer =
        register(UdsFilesystemListeningSocket.bindAndListen(address));
    long newerInode = Os.lstat(socketFile.getAbsolutePath()).st_ino;

    older.close();

    assertThat(Os.lstat(socketFile.getAbsolutePath()).st_ino).isEqualTo(newerInode);
  }

  @Test
  public void close_socketFileAlreadyGone_doesNotThrow() throws Exception {
    File socketFile = newSocketFile();
    UdsFilesystemListeningSocket listening =
        register(UdsFilesystemListeningSocket.bindAndListen(UdsSocketAddress.forFile(socketFile)));
    Os.remove(socketFile.getAbsolutePath());

    listening.close();

    assertThat(socketFile.exists()).isFalse();
  }

  private <T extends Closeable> T register(T closeable) {
    closeables.add(closeable);
    return closeable;
  }

  private File newSocketFile() {
    File file = new File(appContext.getFilesDir(), "uds-fs-test-" + UUID.randomUUID());
    tempPaths.add(file);
    return file;
  }
}
