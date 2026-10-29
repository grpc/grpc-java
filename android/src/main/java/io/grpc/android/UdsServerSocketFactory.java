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

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import javax.net.ServerSocketFactory;

/**
 * {@link ServerSocketFactory} that creates unbound {@link UdsServerSocket} instances for {@code
 * grpc-okhttp}.
 *
 * <p>{@link UdsServerBuilder} passes it to {@code
 * OkHttpServerBuilder.socketFactory(ServerSocketFactory)} together with a {@link UdsSocketAddress}
 * listen address. Only the no-argument {@link #createServerSocket()} is supported; {@code
 * grpc-okhttp} calls it and then binds the result to the listen address itself. Unix domain sockets
 * have no ports, so the port-taking overloads inherited from {@link ServerSocketFactory} throw
 * {@link UnsupportedOperationException}.
 */
final class UdsServerSocketFactory extends ServerSocketFactory {
  private static final UdsServerSocketFactory INSTANCE = new UdsServerSocketFactory();

  /** Returns a factory for unbound UDS server sockets; it is stateless and may be shared. */
  static UdsServerSocketFactory create() {
    return INSTANCE;
  }

  private UdsServerSocketFactory() {}

  /** Returns an unbound server socket; bind it to a {@link UdsSocketAddress}. */
  @Override
  public ServerSocket createServerSocket() throws IOException {
    return new UdsServerSocket();
  }

  /**
   * Unsupported: Unix domain sockets have no ports.
   *
   * @throws UnsupportedOperationException always
   */
  @Override
  public ServerSocket createServerSocket(int port) throws IOException {
    throw new UnsupportedOperationException("Unix domain sockets have no ports");
  }

  /**
   * Unsupported: Unix domain sockets have no ports.
   *
   * @throws UnsupportedOperationException always
   */
  @Override
  public ServerSocket createServerSocket(int port, int backlog) throws IOException {
    throw new UnsupportedOperationException("Unix domain sockets have no ports");
  }

  /**
   * Unsupported: Unix domain sockets have no ports.
   *
   * @throws UnsupportedOperationException always
   */
  @Override
  public ServerSocket createServerSocket(int port, int backlog, InetAddress ifAddress)
      throws IOException {
    throw new UnsupportedOperationException("Unix domain sockets have no ports");
  }
}
