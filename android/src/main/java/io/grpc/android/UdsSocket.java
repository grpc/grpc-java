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
class UdsSocket extends Socket {

  private final LocalSocket localSocket;
  private final LocalSocketAddress localSocketAddress;

  @GuardedBy("this")
  private boolean closed = false;

  @GuardedBy("this")
  private boolean inputShutdown = false;

  @GuardedBy("this")
  private boolean outputShutdown = false;

  public UdsSocket(LocalSocketAddress localSocketAddress) {
    this.localSocketAddress = localSocketAddress;
    localSocket = new LocalSocket();
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
  public void connect(SocketAddress endpoint) throws IOException {
    localSocket.connect(localSocketAddress);
  }

  @Override
  public void connect(SocketAddress endpoint, int timeout) throws IOException {
    localSocket.connect(localSocketAddress, timeout);
  }

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

  @Override
  public boolean getKeepAlive() {
    throw new UnsupportedOperationException("Unsupported operation getKeepAlive()");
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

  @Override
  public boolean getOOBInline() {
    throw new UnsupportedOperationException("Unsupported operation getOOBInline()");
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

  @Override
  public boolean getReuseAddress() {
    throw new UnsupportedOperationException("Unsupported operation getReuseAddress()");
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

  @Override
  public int getTrafficClass() {
    throw new UnsupportedOperationException("Unsupported operation getTrafficClass()");
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
