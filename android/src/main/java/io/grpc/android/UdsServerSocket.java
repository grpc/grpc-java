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

import com.google.errorprone.annotations.concurrent.GuardedBy;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import javax.annotation.Nullable;

/**
 * Adapter from {@link UdsListeningSocket} to {@link ServerSocket}. Like {@link UdsSocket}, this
 * class only needs to support what grpc-okhttp's server uses: {@link #bind}, {@link #accept},
 * {@link #close} and {@link #getLocalSocketAddress}.
 *
 * <p>{@link UdsServerSocketFactory#createServerSocket()} must return an unbound {@link
 * ServerSocket} that {@code grpc-okhttp} later binds, whereas Android's {@link
 * android.net.LocalServerSocket} only exists once bound and listening. This class bridges that
 * lifecycle gap ({@code unbound -> bound -> closed}) while delegating the bound socket's kernel
 * operations to {@link UdsListeningSocket}.
 */
final class UdsServerSocket extends ServerSocket {
  @GuardedBy("this")
  @Nullable
  private UdsListeningSocket listening;

  @GuardedBy("this")
  private boolean closed;

  UdsServerSocket() throws IOException {
    // ServerSocket() creates an unbound socket but also allocates a platform SocketImpl. We never
    // use it. The protected ServerSocket(SocketImpl) constructor that would avoid the allocation
    // (JDK 12+) is not in Android's public SDK, so just accept the tiny overhead.
  }

  @Override
  public void bind(SocketAddress endpoint) throws IOException {
    bind(endpoint, 0);
  }

  /**
   * {@inheritDoc}
   *
   * @param backlog ignored; {@link android.net.LocalServerSocket} always listens with a backlog of
   *     50
   */
  @Override
  public synchronized void bind(SocketAddress endpoint, int backlog) throws IOException {
    if (closed) {
      throw new SocketException("Socket is closed");
    }
    if (listening != null) {
      throw new SocketException("Already bound");
    }
    if (!(endpoint instanceof UdsSocketAddress)) {
      throw new IllegalArgumentException(
          "Unsupported address type " + (endpoint == null ? null : endpoint.getClass().getName()));
    }
    listening = UdsAbstractListeningSocket.bindAndListen((UdsSocketAddress) endpoint);
  }

  @Override
  public Socket accept() throws IOException {
    UdsListeningSocket socket;
    synchronized (this) {
      if (closed) {
        throw new SocketException("Socket is closed");
      }
      if (listening == null) {
        throw new SocketException("Socket is not bound yet");
      }
      socket = listening;
    }
    // Blocks without holding the lock so that close() can run concurrently and wake this accept().
    return socket.accept();
  }

  @Override
  public synchronized void close() throws IOException {
    if (closed) {
      return;
    }
    closed = true;
    if (listening != null) {
      listening.close();
    }
  }

  @Override
  @Nullable
  public InetAddress getInetAddress() {
    return null; // Not an IP socket.
  }

  @Override
  public int getLocalPort() {
    return -1; // Not an IP socket.
  }

  @Override
  @Nullable
  public synchronized SocketAddress getLocalSocketAddress() {
    return listening != null ? listening.getAddress() : null;
  }

  @Override
  public int getReceiveBufferSize() {
    throw new UnsupportedOperationException("Unsupported operation getReceiveBufferSize()");
  }

  @Override
  public boolean getReuseAddress() {
    throw new UnsupportedOperationException("Unsupported operation getReuseAddress()");
  }

  @Override
  public int getSoTimeout() {
    return 0; // Infinite; we never set one.
  }

  @Override
  public synchronized boolean isBound() {
    return listening != null;
  }

  @Override
  public synchronized boolean isClosed() {
    return closed;
  }

  @Override
  public void setPerformancePreferences(int connectionTime, int latency, int bandwidth) {
    // no-op
  }

  @Override
  public void setReceiveBufferSize(int size) {
    throw new UnsupportedOperationException("Unsupported operation setReceiveBufferSize()");
  }

  @Override
  public void setReuseAddress(boolean on) {
    // no-op: unix domain sockets have no TIME_WAIT.
  }

  @Override
  public void setSoTimeout(int timeout) {
    throw new UnsupportedOperationException("Unsupported operation setSoTimeout()");
  }

  @Override
  public synchronized String toString() {
    if (listening == null) {
      return "UdsServerSocket[unbound]";
    }
    return "UdsServerSocket[addr=" + listening.getAddress() + "]";
  }
}
