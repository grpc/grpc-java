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

import android.net.LocalSocketAddress;
import android.net.LocalSocketAddress.Namespace;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import javax.net.SocketFactory;

/**
 * A SocketFactory that produces {@link UdsSocket} instances. This is used to provide support for
 * gRPC channels with an underlying Unix Domain Socket transport.
 */
class UdsSocketFactory extends SocketFactory {

  private final LocalSocketAddress localSocketAddress;

  public UdsSocketFactory(String path, Namespace namespace) {
    localSocketAddress = new LocalSocketAddress(path, namespace);
  }

  @Override
  public Socket createSocket() throws IOException {
    return create();
  }

  @Override
  public Socket createSocket(String host, int port) throws IOException {
    return createAndConnect();
  }

  @Override
  public Socket createSocket(String host, int port, InetAddress localHost, int localPort)
      throws IOException {
    return createAndConnect();
  }

  @Override
  public Socket createSocket(InetAddress host, int port) throws IOException {
    return createAndConnect();
  }

  @Override
  public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort)
      throws IOException {
    return createAndConnect();
  }

  private Socket create() {
    return new UdsSocket(localSocketAddress);
  }

  private Socket createAndConnect() throws IOException {
    Socket socket = create();
    SocketAddress unusedAddress = new InetSocketAddress(0);
    socket.connect(unusedAddress);
    return socket;
  }
}
