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
import android.net.LocalSocketAddress.Namespace;
import java.io.IOException;

/**
 * {@link UdsListeningSocket} bound to a name in Linux's {@linkplain Namespace#ABSTRACT abstract
 * socket namespace}.
 */
final class UdsAbstractListeningSocket extends UdsListeningSocket {
  private UdsAbstractListeningSocket(UdsSocketAddress address, LocalServerSocket serverSocket) {
    super(address, serverSocket);
  }

  static UdsAbstractListeningSocket bindAndListen(UdsSocketAddress address) throws IOException {
    checkNotNull(address, "address");
    checkArgument(
        address.getNamespace() == Namespace.ABSTRACT,
        "Expected ABSTRACT address, got %s",
        address);
    return new UdsAbstractListeningSocket(address, new LocalServerSocket(address.getName()));
  }

  @Override
  void closeEndpoint() throws IOException {
    serverSocket.close();
  }
}
