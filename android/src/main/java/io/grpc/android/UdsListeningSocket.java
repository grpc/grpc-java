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

import static com.google.common.base.Preconditions.checkNotNull;

import android.net.LocalServerSocket;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import java.io.Closeable;
import java.io.FileDescriptor;
import java.io.IOException;

/**
 * Bound, listening Unix domain socket backed by {@link LocalServerSocket}.
 *
 * <p>Separates the bound socket's kernel lifecycle ({@link #accept()} and wake-on-{@link #close()})
 * from {@link UdsServerSocket}'s unbound-to-closed {@link java.net.ServerSocket} state machine.
 */
abstract class UdsListeningSocket implements Closeable {
  private final UdsSocketAddress address;
  final LocalServerSocket serverSocket;

  UdsListeningSocket(UdsSocketAddress address, LocalServerSocket serverSocket) {
    this.address = checkNotNull(address, "address");
    this.serverSocket = checkNotNull(serverSocket, "serverSocket");
  }

  /** Returns the address this socket is bound to. */
  final UdsSocketAddress getAddress() {
    return address;
  }

  /** Blocks until a client connects and returns the accepted {@link UdsSocket.Accepted}. */
  final UdsSocket.Accepted accept() throws IOException {
    return new UdsSocket.Accepted(serverSocket.accept(), address);
  }

  /**
   * Wakes any thread blocked in {@link #accept()} and releases the underlying socket and any
   * associated resources.
   */
  @Override
  public final void close() throws IOException {
    // grpc-okhttp relies on close() making a concurrent accept() throw. Merely closing the
    // listening descriptor does not wake a thread blocked in LocalServerSocket.accept() (verified
    // against AOSP at API 24, 26 and 33), which would leave grpc-okhttp's accept loop stuck
    // forever. shutdown(2) on a listening socket does wake it: accept() fails immediately with
    // EINVAL. That is kernel behaviour, not Android's. Do that first (while the descriptor is still
    // open), then release the endpoint.
    IOException failure = null;
    FileDescriptor fd = serverSocket.getFileDescriptor();
    if (fd != null && fd.valid()) {
      try {
        Os.shutdown(fd, OsConstants.SHUT_RDWR);
      } catch (ErrnoException e) {
        failure = new IOException("shutdown() of listening socket failed", e);
      }
    }
    try {
      closeEndpoint();
    } catch (IOException e) {
      if (failure == null) {
        failure = e;
      } else {
        failure.addSuppressed(e);
      }
    }
    if (failure != null) {
      throw failure;
    }
  }

  /** Releases the underlying socket descriptor and any associated endpoint state. */
  abstract void closeEndpoint() throws IOException;
}
