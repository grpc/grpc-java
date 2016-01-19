/*
 * Copyright 2015, Google Inc. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 *    * Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 *    * Redistributions in binary form must reproduce the above
 * copyright notice, this list of conditions and the following disclaimer
 * in the documentation and/or other materials provided with the
 * distribution.
 *
 *    * Neither the name of Google Inc. nor the names of its
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package io.grpc.testing.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.NegotiationType;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.TestUtils;
import io.grpc.testing.integration.EchoServiceGrpc.EchoServiceBlockingStub;
import io.grpc.testing.integration.EchoServiceOuterClass.EchoRequest;
import io.grpc.testing.integration.EchoServiceOuterClass.EchoResponse;
import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.SslContext;

import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.File;
import java.io.IOException;
import java.security.cert.X509Certificate;

/**
 * Integration tests for GRPC's TLS support.
 */
// TODO: Use @RunWith(Parameterized.class) to run these tests for all TLS providers. Doing so will
// require changes to allow programmatically choosing which TLS provider to use.
@RunWith(JUnit4.class)
public class TlsTest {
  private static class DummyEchoRpcService implements EchoServiceGrpc.EchoService {
    @Override
    public void echo(EchoRequest request, StreamObserver<EchoResponse> responseObserver) {
      EchoResponse response = EchoResponse.newBuilder()
          .setText("Request said: " + request.getText())
          .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }


  /**
   * Tests that a client and a server configured using GrpcSslContexts can successfully
   * communicate with each other.
   */
  // TODO: Fix whatever causes this test to fail, then remove the @Ignore annotation.
  @Ignore
  @Test
  public void basicClientServerIntegrationTest() throws Exception {
    int port = TestUtils.pickUnusedPort();

    // Create & start a server.
    File serverCertFile = TestUtils.loadCert("localhost_server.pem");
    File serverPrivateKeyFile = TestUtils.loadCert("localhost_server.key");
    X509Certificate[] serverTrustedCaCerts = {
      TestUtils.loadX509Cert("ca.pem")
    };
    Server server = serverBuilder(port, serverCertFile, serverPrivateKeyFile, serverTrustedCaCerts)
        .addService(EchoServiceGrpc.bindService(new DummyEchoRpcService()))
        .build()
        .start();

    try {
      // Create a client.
      File clientCertFile = TestUtils.loadCert("client.pem");
      File clientPrivateKeyFile = TestUtils.loadCert("client.key");
      X509Certificate[] clientTrustedCaCerts = {
        TestUtils.loadX509Cert("ca.pem")
      };
      ManagedChannel channel = clientChannel("localhost", port, clientCertFile,
                                             clientPrivateKeyFile, clientTrustedCaCerts);
      EchoServiceBlockingStub client = EchoServiceGrpc.newBlockingStub(channel);

      // Send an actual request, via the full GRPC & network stack, and check that a proper
      // response comes back.
      EchoRequest request = EchoRequest.newBuilder()
          .setText("dummy text")
          .build();
      EchoResponse response = client.echo(request);
      assertEquals("Request said: dummy text", response.getText());
    } finally {
      server.shutdown();
    }
  }


  /**
   * Tests that a server configured to require client authentication refuses to accept connections
   * from a client that has an untrusted certificate.
   */
  @Test
  public void serverRejectsUntrustedClientCert() throws Exception {
    int port = TestUtils.pickUnusedPort();

    // Create & start a server. It requires client authentication and trusts only the test CA.
    File serverCertFile = TestUtils.loadCert("localhost_server.pem");
    File serverPrivateKeyFile = TestUtils.loadCert("localhost_server.key");
    X509Certificate[] serverTrustedCaCerts = {
      TestUtils.loadX509Cert("ca.pem")
    };
    Server server = serverBuilder(port, serverCertFile, serverPrivateKeyFile, serverTrustedCaCerts)
        .addService(EchoServiceGrpc.bindService(new DummyEchoRpcService()))
        .build()
        .start();

    try {
      // Create a client. Its credentials come from a CA that the server does not trust. The client
      // trusts both test CAs, so we can be sure that the handshake failure is due to the server
      // rejecting the client's cert, not the client rejecting the server's cert.
      File clientCertFile = TestUtils.loadCert("badclient.pem");
      File clientPrivateKeyFile = TestUtils.loadCert("badclient.key");
      X509Certificate[] clientTrustedCaCerts = {
        TestUtils.loadX509Cert("ca.pem"),
        TestUtils.loadX509Cert("badclient.pem")  // Cert is self-signed, and so is its own issuer.
      };
      ManagedChannel channel = clientChannel("localhost", port, clientCertFile,
                                             clientPrivateKeyFile, clientTrustedCaCerts);
      EchoServiceBlockingStub client = EchoServiceGrpc.newBlockingStub(channel);

      // Check that the TLS handshake fails.
      EchoRequest request = EchoRequest.newBuilder()
          .setText("dummy text")
          .build();
      try {
        EchoResponse response = client.echo(request);
        fail("TLS handshake should have failed, but didn't; received RPC response: " + response);
      } catch (StatusRuntimeException e) {
        // GRPC reports this situation by throwing a StatusRuntimeException that wraps either a
        // javax.net.ssl.SSLHandshakeException or a java.nio.channels.ClosedChannelException.
        // Thus, reliably detecting the underlying cause is not feasible.
        assertEquals(Status.Code.UNAVAILABLE, e.getStatus().getCode());
      }
    } finally {
      server.shutdown();
    }
  }


  /**
   * Tests that a server configured to require client authentication actually does require client
   * authentication.
   */
  @Test
  public void noClientAuthFailure() throws Exception {
    int port = TestUtils.pickUnusedPort();

     // Create & start a server.
    File serverCertFile = TestUtils.loadCert("localhost_server.pem");
    File serverPrivateKeyFile = TestUtils.loadCert("localhost_server.key");
    X509Certificate[] serverTrustedCaCerts = {
      TestUtils.loadX509Cert("ca.pem")
    };
    Server server = serverBuilder(port, serverCertFile, serverPrivateKeyFile, serverTrustedCaCerts)
        .addService(EchoServiceGrpc.bindService(new DummyEchoRpcService()))
        .build()
        .start();

    try {
      // Create a client. It has no credentials.
      ManagedChannel channel = NettyChannelBuilder.forAddress("localhost", port)
          .negotiationType(NegotiationType.TLS)
          .build();
      EchoServiceBlockingStub client = EchoServiceGrpc.newBlockingStub(channel);

      // Check that the TLS handshake fails.
      EchoRequest request = EchoRequest.newBuilder()
          .setText("dummy text")
          .build();
      try {
        EchoResponse response = client.echo(request);
        fail("TLS handshake should have failed, but didn't; received RPC response: " + response);
      } catch (StatusRuntimeException e) {
        // GRPC reports this situation by throwing a StatusRuntimeException that wraps either a
        // javax.net.ssl.SSLHandshakeException or a java.nio.channels.ClosedChannelException.
        // Thus, reliably detecting the underlying cause is not feasible.
        assertEquals(Status.Code.UNAVAILABLE, e.getStatus().getCode());
      }
    } finally {
      server.shutdown();
    }
  }


  private static ServerBuilder<?> serverBuilder(int port, File serverCertChainFile,
                                                File serverPrivateKeyFile,
                                                X509Certificate[] serverTrustedCaCerts)
      throws IOException {
    SslContext sslContext = GrpcSslContexts.forServer(serverCertChainFile, serverPrivateKeyFile)
        .trustManager(serverTrustedCaCerts)
        .clientAuth(ClientAuth.REQUIRE)
        .build();

    return NettyServerBuilder.forPort(port)
        .sslContext(sslContext);
  }


  private static ManagedChannel clientChannel(String serverHost, int serverPort,
                                              File clientCertChainFile,
                                              File clientPrivateKeyFile,
                                              X509Certificate[] clientTrustedCaCerts)
      throws IOException {
    SslContext sslContext = GrpcSslContexts.forClient()
        .keyManager(clientCertChainFile, clientPrivateKeyFile)
        .trustManager(clientTrustedCaCerts)
        .build();

    return NettyChannelBuilder.forAddress(serverHost, serverPort)
        .negotiationType(NegotiationType.TLS)
        .sslContext(sslContext)
        .build();
  }
}
