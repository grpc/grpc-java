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

package io.grpc.android;

import static com.google.common.base.Preconditions.checkNotNull;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import com.google.errorprone.annotations.concurrent.GuardedBy;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.channels.SocketChannel;

/**
 * Adapter from Android's LocalSocket to Socket. This class is only needed by grpc-okhttp, so the
 * adapter only has to support the things that grcp-okhttp uses. It is fine to support trivial
 * things unused by the transport, to be less likely to break as the transport usage changes, but it
 * is also unnecessary. It's okay to stretch the truth or lie when necessary. For example, little
 * hurts with {@link #setTcpNoDelay(boolean)} being a noop since unix domain sockets don't have such
 * unnecessary delays.
 */
@SuppressWarnings("UnsynchronizedOverridesSynchronized") // Rely on LocalSocket's synchronization
abstract class UdsSocket extends Socket {

  /** Client-side {@link UdsSocket} that connects to a preconfigured {@link LocalSocketAddress}. */
  static final class Client extends UdsSocket {
    private final LocalSocketAddress connectAddress;

    Client(LocalSocketAddress connectAddress) {
      super(new LocalSocket());
      this.connectAddress = checkNotNull(connectAddress, "connectAddress");
    }

    @Override
    public void connect(SocketAddress endpoint) throws IOException {
      localSocket.connect(connectAddress);
    }

    @Override
    public void connect(SocketAddress endpoint, int timeout) throws IOException {
      localSocket.connect(connectAddress, timeout);
    }
  }

  /**
   * Already-connected server-side {@link UdsSocket} returned by {@link
   * UdsListeningSocket#accept()}. Cannot be {@link #connect connected} again.
   */
  static final class Accepted extends UdsSocket {
    private final UdsSocketAddress localAddress;

    Accepted(LocalSocket connectedLocalSocket, UdsSocketAddress localAddress) {
      super(connectedLocalSocket);
      this.localAddress = checkNotNull(localAddress, "localAddress");
    }

    @Override
    public void connect(SocketAddress endpoint) throws IOException {
      // Same exception and message as java.net.Socket.connect() on a connected socket.
      throw new SocketException("already connected");
    }

    @Override
    public void connect(SocketAddress endpoint, int timeout) throws IOException {
      throw new SocketException("already connected");
    }

    @Override
    public SocketAddress getLocalSocketAddress() {
      return localAddress;
    }
  }

  final LocalSocket localSocket;

  @GuardedBy("this")
  private boolean closed = false;

  @GuardedBy("this")
  private boolean inputShutdown = false;

  @GuardedBy("this")
  private boolean outputShutdown = false;

  private UdsSocket(LocalSocket localSocket) {
    this.localSocket = checkNotNull(localSocket, "localSocket");
  }

  @Override
  public void bind(SocketAddress bindpoint) {
    // no-op
  }

  @Override
  public synchronized void close() throws IOException {
    if (closed) {
      return;
    }
    if (!inputShutdown) {
      shutdownInput();
    }
    if (!outputShutdown) {
      shutdownOutput();
    }
    localSocket.close();
    closed = true;
  }

  @Override
  public abstract void connect(SocketAddress endpoint) throws IOException;

  @Override
  public abstract void connect(SocketAddress endpoint, int timeout) throws IOException;

  @Override
  public SocketChannel getChannel() {
    throw new UnsupportedOperationException("getChannel() not supported");
  }

  @Override
  public InetAddress getInetAddress() {
    throw new UnsupportedOperationException("getInetAddress() not supported");
  }

  @Override
  public InputStream getInputStream() throws IOException {
    return new FilterInputStream(localSocket.getInputStream()) {
      @Override
      public void close() throws IOException {
        UdsSocket.this.close();
      }
    };
  }

  // Matches the kernel: SO_KEEPALIVE is a SOL_SOCKET option that getsockopt() reports as 0 (off)
  // on an AF_UNIX socket, so this is a real value, not a stub.
  @Override
  public boolean getKeepAlive() {
    return false;
  }

  @Override
  public InetAddress getLocalAddress() {
    throw new UnsupportedOperationException("Unsupported operation getLocalAddress()");
  }

  @Override
  public int getLocalPort() {
    throw new UnsupportedOperationException("Unsupported operation getLocalPort()");
  }

  @Override
  public SocketAddress getLocalSocketAddress() {
    return new SocketAddress() {};
  }

  // Matches the kernel: SO_OOBINLINE is a SOL_SOCKET option that getsockopt() reports as 0 (off)
  // on an AF_UNIX socket, so this is a real value, not a stub.
  @Override
  public boolean getOOBInline() {
    return false;
  }

  @Override
  public OutputStream getOutputStream() throws IOException {
    return new FilterOutputStream(localSocket.getOutputStream()) {
      @Override
      public void close() throws IOException {
        UdsSocket.this.close();
      }
    };
  }

  @Override
  public int getPort() {
    throw new UnsupportedOperationException("Unsupported operation getPort()");
  }

  @Override
  public int getReceiveBufferSize() throws SocketException {
    try {
      return localSocket.getReceiveBufferSize();
    } catch (IOException e) {
      throw toSocketException(e);
    }
  }

  @Override
  public SocketAddress getRemoteSocketAddress() {
    return new SocketAddress() {};
  }

  // Matches the kernel: SO_REUSEADDR is a SOL_SOCKET option that getsockopt() reports as 0 (off)
  // on an AF_UNIX socket, so this is a real value, not a stub.
  @Override
  public boolean getReuseAddress() {
    return false;
  }

  @Override
  public int getSendBufferSize() throws SocketException {
    try {
      return localSocket.getSendBufferSize();
    } catch (IOException e) {
      throw toSocketException(e);
    }
  }

  @Override
  public int getSoLinger() {
    return -1; // unsupported
  }

  @Override
  public int getSoTimeout() throws SocketException {
    try {
      return localSocket.getSoTimeout();
    } catch (IOException e) {
      throw toSocketException(e);
    }
  }

  @Override
  public boolean getTcpNoDelay() {
    return true;
  }

  // IP_TOS is an IPPROTO_IP option that does not apply to AF_UNIX. Return 0 (like getTcpNoDelay()
  // returns a synthetic true) rather than throwing SocketException, which okhttp's
  // Utils.getSocketOptions() logs at Level.SEVERE on every channelz poll.
  @Override
  public int getTrafficClass() {
    return 0;
  }

  @Override
  public boolean isBound() {
    return localSocket.isBound();
  }

  @Override
  public synchronized boolean isClosed() {
    return closed;
  }

  @Override
  public boolean isConnected() {
    return localSocket.isConnected();
  }

  @Override
  public synchronized boolean isInputShutdown() {
    return inputShutdown;
  }

  @Override
  public synchronized boolean isOutputShutdown() {
    return outputShutdown;
  }

  @Override
  public void sendUrgentData(int data) {
    throw new UnsupportedOperationException("Unsupported operation sendUrgentData()");
  }

  @Override
  public void setKeepAlive(boolean on) {
    throw new UnsupportedOperationException("Unsupported operation setKeepAlive()");
  }

  @Override
  public void setOOBInline(boolean on) {
    throw new UnsupportedOperationException("Unsupported operation setOOBInline()");
  }

  @Override
  public void setPerformancePreferences(int connectionTime, int latency, int bandwidth) {
    throw new UnsupportedOperationException("Unsupported operation setPerformancePreferences()");
  }

  @Override
  public void setReceiveBufferSize(int size) throws SocketException {
    try {
      localSocket.setReceiveBufferSize(size);
    } catch (IOException e) {
      throw toSocketException(e);
    }
  }

  @Override
  public void setReuseAddress(boolean on) {
    throw new UnsupportedOperationException("Unsupported operation setReuseAddress()");
  }

  @Override
  public void setSendBufferSize(int size) throws SocketException {
    try {
      localSocket.setSendBufferSize(size);
    } catch (IOException e) {
      throw toSocketException(e);
    }
  }

  @Override
  public void setSoLinger(boolean on, int linger) {
    throw new UnsupportedOperationException("Unsupported operation setSoLinger()");
  }

  @Override
  public void setSoTimeout(int timeout) throws SocketException {
    try {
      localSocket.setSoTimeout(timeout);
    } catch (IOException e) {
      throw toSocketException(e);
    }
  }

  @Override
  public void setTcpNoDelay(boolean on) {
    // no-op
  }

  @Override
  public void setTrafficClass(int tc) {
    throw new UnsupportedOperationException("Unsupported operation setTrafficClass()");
  }

  @Override
  public synchronized void shutdownInput() throws IOException {
    localSocket.shutdownInput();
    inputShutdown = true;
  }

  @Override
  public synchronized void shutdownOutput() throws IOException {
    localSocket.shutdownOutput();
    outputShutdown = true;
  }

  private static SocketException toSocketException(Throwable e) {
    SocketException se = new SocketException();
    se.initCause(e);
    return se;
  }
}
