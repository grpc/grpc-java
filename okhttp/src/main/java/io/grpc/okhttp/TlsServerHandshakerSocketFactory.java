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
import io.grpc.InternalChannelz;
import io.grpc.SecurityLevel;
import io.grpc.internal.GrpcAttributes;
import io.grpc.okhttp.internal.ConnectionSpec;
import io.grpc.okhttp.internal.Protocol;
import java.io.IOException;
import java.net.Socket;
import java.util.Arrays;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * TLS handshaker.
 */
final class TlsServerHandshakerSocketFactory implements HandshakerSocketFactory {
  private final PlaintextHandshakerSocketFactory delegate = new PlaintextHandshakerSocketFactory();
  private final SSLSocketFactory socketFactory;
  private final ConnectionSpec connectionSpec;

  public TlsServerHandshakerSocketFactory(
      SslSocketFactoryServerCredentials.ServerCredentials credentials) {
    this.socketFactory = credentials.getFactory();
    this.connectionSpec = credentials.getConnectionSpec();
  }

  @Override
  public HandshakeResult handshake(Socket socket, Attributes attributes) throws IOException {
    HandshakeResult result = delegate.handshake(socket, attributes);
    socket = socketFactory.createSocket(result.socket, null, -1, true);
    if (!(socket instanceof SSLSocket)) {
      throw new IOException(
          "SocketFactory " + socketFactory + " did not produce an SSLSocket: " + socket.getClass());
    }
    SSLSocket sslSocket = (SSLSocket) socket;
    sslSocket.setUseClientMode(false);
    connectionSpec.apply(sslSocket, false);
    Protocol expectedProtocol = Protocol.HTTP_2;
    String negotiatedProtocol = OkHttpProtocolNegotiator.get().negotiate(
        sslSocket,
        null,
        connectionSpec.supportsTlsExtensions() ? Arrays.asList(expectedProtocol) : null);
    if (!expectedProtocol.toString().equals(negotiatedProtocol)) {
      throw new IOException("Expected NPN/ALPN " + expectedProtocol + ": " + negotiatedProtocol);
    }
    attributes = result.attributes.toBuilder()
        .set(GrpcAttributes.ATTR_SECURITY_LEVEL, SecurityLevel.PRIVACY_AND_INTEGRITY)
        .set(Grpc.TRANSPORT_ATTR_SSL_SESSION, sslSocket.getSession())
        .build();
    return new HandshakeResult(socket, attributes,
        new InternalChannelz.Security(new InternalChannelz.Tls(sslSocket.getSession())));
  }
}
