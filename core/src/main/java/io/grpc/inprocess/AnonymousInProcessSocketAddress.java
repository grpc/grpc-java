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

package io.grpc.inprocess;

import static com.google.common.base.Preconditions.checkState;

import io.grpc.ExperimentalApi;
import java.io.IOException;
import java.net.SocketAddress;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;

/**
 * Custom SocketAddress class for {@link InProcessTransport}, for 
 * a server which can only be referenced via this address instance.
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/8626")
public final class AnonymousInProcessSocketAddress extends SocketAddress {
  private static final long serialVersionUID = -8567592561863414695L;

  @Nullable
  @GuardedBy("this")
  private InProcessServer server;

  /** Creates a new AnonymousInProcessSocketAddress. */
  public AnonymousInProcessSocketAddress() { }

  @Nullable
  synchronized InProcessServer getServer() {
    return server;
  }

  synchronized void setServer(InProcessServer server) throws IOException {
    if (this.server != null) {
      throw new IOException("Server instance already registered");
    }
    this.server = server;
  }

  synchronized void clearServer(InProcessServer server) {
    checkState(this.server == server);
    this.server = null;
  }
}
