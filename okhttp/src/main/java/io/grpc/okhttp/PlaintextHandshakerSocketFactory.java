/*
 * Copyright 2022 The gRPC Authors
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

package io.grpc.okhttp;

import io.grpc.Attributes;
import io.grpc.Grpc;
import io.grpc.SecurityLevel;
import io.grpc.internal.GrpcAttributes;
import java.io.IOException;
import java.net.Socket;

/**
 * No-thrills plaintext handshaker.
 */
final class PlaintextHandshakerSocketFactory implements HandshakerSocketFactory {
  @Override
  public HandshakeResult handshake(Socket socket, Attributes attributes) throws IOException {
    attributes = attributes.toBuilder()
        .set(Grpc.TRANSPORT_ATTR_LOCAL_ADDR, socket.getLocalSocketAddress())
        .set(Grpc.TRANSPORT_ATTR_REMOTE_ADDR, socket.getRemoteSocketAddress())
        .set(GrpcAttributes.ATTR_SECURITY_LEVEL, SecurityLevel.NONE)
        .build();
    return new HandshakeResult(socket, attributes, null);
  }
}
