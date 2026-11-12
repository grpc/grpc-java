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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.net.LocalSocketAddress.Namespace;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;

/**
 * {@link UdsListeningSocket} bound to a {@linkplain Namespace#FILESYSTEM filesystem} socket file.
 *
 * <p>{@link #bindAndListen(UdsSocketAddress)} creates a {@code 0600} socket file at the target path
 * (replacing an existing socket file, or failing if a non-socket exists there), and {@link
 * #closeEndpoint()} closes the descriptor owner and best-effort unlinks the socket file if its
 * device/inode is unchanged.
 */
final class UdsFilesystemListeningSocket extends UdsListeningSocket {
  private static final Logger logger =
      Logger.getLogger(UdsFilesystemListeningSocket.class.getName());

  private final LocalSocket fdOwner;
  private final StructStat boundFile;

  private UdsFilesystemListeningSocket(
      UdsSocketAddress address,
      LocalServerSocket serverSocket,
      LocalSocket fdOwner,
      StructStat boundFile) {
    super(address, serverSocket);
    this.fdOwner = fdOwner;
    this.boundFile = boundFile;
  }

  static UdsFilesystemListeningSocket bindAndListen(UdsSocketAddress address) throws IOException {
    checkNotNull(address, "address");
    checkArgument(
        address.getNamespace() == Namespace.FILESYSTEM,
        "Expected FILESYSTEM address, got %s",
        address);
    String path = address.getName();

    // LocalServerSocket(String) is ABSTRACT-only, and neither android.system.UnixSocketAddress nor
    // JDK 16's UnixDomainSocketAddress is in Android's public SDK. Instead, bind a LocalSocket and
    // wrap its fd in LocalServerSocket(FileDescriptor), which calls listen() without taking fd
    // ownership (owner must close it).
    //
    // LocalSocket.bind() (socket_local_server_bind in libcutils) unlinks whatever is at path
    // before bind(2); lstat (not stat, so symlinks to sockets aren't followed) refuses non-sockets
    // first.
    StructStat existing = lstatOrNull(path);
    if (existing != null && !OsConstants.S_ISSOCK(existing.st_mode)) {
      throw new IOException("Refusing to replace non-socket file at " + path);
    }
    LocalSocket owner = new LocalSocket();
    StructStat created = null;
    LocalServerSocket serverSocket;
    try {
      owner.bind(new LocalSocketAddress(path, Namespace.FILESYSTEM));
      StructStat afterBind = lstatOrNull(path);
      if (afterBind == null || !OsConstants.S_ISSOCK(afterBind.st_mode)) {
        throw new IOException(
            "Socket file at " + path + " is missing or was replaced after bind()");
      }
      created = afterBind;
      // Don't rely on Zygote's 0077 umask: explicitly set 0600 before listen(), then re-lstat
      // because chmod(2) follows symlinks (there is no lchmod).
      Os.chmod(path, OsConstants.S_IRUSR | OsConstants.S_IWUSR);
      StructStat afterChmod = lstatOrNull(path);
      if (afterChmod == null || !isSameFile(afterChmod, created)) {
        throw new IOException("Socket file at " + path + " was replaced during bind()");
      }
      serverSocket = new LocalServerSocket(owner.getFileDescriptor());
    } catch (ErrnoException e) {
      IOException wrapped = new IOException("chmod() of socket file failed", e);
      closeQuietly(owner, wrapped);
      if (created != null) {
        unlinkIfUnchanged(path, created);
      }
      throw wrapped;
    } catch (IOException | RuntimeException e) {
      closeQuietly(owner, e);
      if (created != null) {
        unlinkIfUnchanged(path, created);
      }
      throw e;
    }
    return new UdsFilesystemListeningSocket(address, serverSocket, owner, created);
  }

  @Override
  void closeEndpoint() throws IOException {
    IOException failure = null;
    try {
      try {
        serverSocket.close(); // No-op when constructed from a FileDescriptor.
      } catch (IOException e) {
        failure = e;
      }
      try {
        fdOwner.close();
      } catch (IOException e) {
        if (failure == null) {
          failure = e;
        } else {
          failure.addSuppressed(e);
        }
      }
    } finally {
      unlinkIfUnchanged(getAddress().getName(), boundFile);
    }
    if (failure != null) {
      throw failure;
    }
  }

  private static void closeQuietly(LocalSocket socket, Exception primary) {
    try {
      socket.close();
    } catch (IOException suppressed) {
      primary.addSuppressed(suppressed);
    }
  }

  private static boolean isSameFile(StructStat a, StructStat b) {
    return a.st_dev == b.st_dev && a.st_ino == b.st_ino;
  }

  @Nullable
  private static StructStat lstatOrNull(String path) throws IOException {
    try {
      return Os.lstat(path);
    } catch (ErrnoException e) {
      if (e.errno == OsConstants.ENOENT) {
        return null;
      }
      throw new IOException("lstat() of " + path + " failed", e);
    }
  }

  private static void unlinkIfUnchanged(String path, StructStat expected) {
    // LocalSocket.close() does not unlink its bound path. lstat-then-remove is not atomic (there
    // is no unlink-if-inode-matches syscall), which is only a same-uid self-race in a private dir.
    try {
      StructStat actual = lstatOrNull(path);
      if (actual == null || !isSameFile(actual, expected)) {
        return;
      }
      Os.remove(path);
    } catch (IOException | ErrnoException e) {
      logger.log(Level.WARNING, "Could not remove socket file " + path, e);
    }
  }
}
