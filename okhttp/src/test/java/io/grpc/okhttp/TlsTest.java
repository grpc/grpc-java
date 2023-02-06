/*
 * Copyright 2015 The gRPC Authors
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

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import com.google.common.base.Throwables;
import io.grpc.ChannelCredentials;
import io.grpc.ConnectivityState;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.ServerCredentials;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.TlsChannelCredentials;
import io.grpc.TlsServerCredentials;
import io.grpc.internal.testing.TestUtils;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.GrpcCleanupRule;
import io.grpc.testing.TlsTesting;
import io.grpc.testing.protobuf.SimpleRequest;
import io.grpc.testing.protobuf.SimpleResponse;
import io.grpc.testing.protobuf.SimpleServiceGrpc;
import java.io.IOException;
import java.io.InputStream;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Verify OkHttp's TLS integration. */
@RunWith(JUnit4.class)
public class TlsTest {
  @Rule
  public final GrpcCleanupRule grpcCleanupRule = new GrpcCleanupRule();

  @Before
  public void checkForAlpnApi() throws Exception {
    // This checks for the "Java 9 ALPN API" which was backported to Java 8u252. The Kokoro Windows
    // CI is on too old of a JDK for us to assume this is available.
    SSLContext context = SSLContext.getInstance("TLS");
    context.init(null, null, null);
    SSLEngine engine = context.createSSLEngine();
    try {
      SSLEngine.class.getMethod("getApplicationProtocol").invoke(engine);
    } catch (NoSuchMethodException | UnsupportedOperationException ex) {
      Assume.assumeNoException(ex);
    }
  }

  @Test
  public void basicTls_succeeds() throws Exception {
    ServerCredentials serverCreds;
    try (InputStream serverCert = TlsTesting.loadCert("server1.pem");
         InputStream serverPrivateKey = TlsTesting.loadCert("server1.key")) {
      serverCreds = TlsServerCredentials.newBuilder()
          .keyManager(serverCert, serverPrivateKey)
          .build();
    }
    ChannelCredentials channelCreds;
    try (InputStream caCert = TlsTesting.loadCert("ca.pem")) {
      channelCreds = TlsChannelCredentials.newBuilder()
          .trustManager(caCert)
          .build();
    }
    Server server = grpcCleanupRule.register(server(serverCreds));
    ManagedChannel channel = grpcCleanupRule.register(clientChannel(server, channelCreds));

    SimpleServiceGrpc.newBlockingStub(channel).unaryRpc(SimpleRequest.getDefaultInstance());
  }

  @Test
  public void mtls_succeeds() throws Exception {
    ServerCredentials serverCreds;
    try (InputStream serverCert = TlsTesting.loadCert("server1.pem");
         InputStream serverPrivateKey = TlsTesting.loadCert("server1.key");
         InputStream caCert = TlsTesting.loadCert("ca.pem")) {
      serverCreds = TlsServerCredentials.newBuilder()
          .keyManager(serverCert, serverPrivateKey)
          .trustManager(caCert)
          .clientAuth(TlsServerCredentials.ClientAuth.REQUIRE)
          .build();
    }
    ChannelCredentials channelCreds;
    try (InputStream clientCertChain = TlsTesting.loadCert("client.pem");
         InputStream clientPrivateKey = TlsTesting.loadCert("client.key");
         InputStream caCert = TlsTesting.loadCert("ca.pem")) {
      channelCreds = TlsChannelCredentials.newBuilder()
          .keyManager(clientCertChain, clientPrivateKey)
          .trustManager(caCert)
          .build();
    }
    Server server = grpcCleanupRule.register(server(serverCreds));
    ManagedChannel channel = grpcCleanupRule.register(clientChannel(server, channelCreds));

    SimpleServiceGrpc.newBlockingStub(channel).unaryRpc(SimpleRequest.getDefaultInstance());
  }

  @Test
  public void untrustedClient_fails() throws Exception {
    ServerCredentials serverCreds;
    try (InputStream serverCert = TlsTesting.loadCert("server1.pem");
         InputStream serverPrivateKey = TlsTesting.loadCert("server1.key");
         InputStream caCert = TlsTesting.loadCert("ca.pem")) {
      serverCreds = TlsServerCredentials.newBuilder()
          .keyManager(serverCert, serverPrivateKey)
          .trustManager(caCert)
          .clientAuth(TlsServerCredentials.ClientAuth.REQUIRE)
          .build();
    }
    ChannelCredentials channelCreds;
    try (InputStream clientCertChain = TlsTesting.loadCert("badclient.pem");
         InputStream clientPrivateKey = TlsTesting.loadCert("badclient.key");
         InputStream caCert = TlsTesting.loadCert("ca.pem")) {
      channelCreds = TlsChannelCredentials.newBuilder()
          .keyManager(clientCertChain, clientPrivateKey)
          .trustManager(caCert)
          .build();
    }
    Server server = grpcCleanupRule.register(server(serverCreds));
    ManagedChannel channel = grpcCleanupRule.register(clientChannel(server, channelCreds));

    assertRpcFails(channel);
  }

  @Test
  public void missingOptionalClientCert_succeeds() throws Exception {
    ServerCredentials serverCreds;
    try (InputStream serverCert = TlsTesting.loadCert("server1.pem");
         InputStream serverPrivateKey = TlsTesting.loadCert("server1.key");
         InputStream caCert = TlsTesting.loadCert("ca.pem")) {
      serverCreds = TlsServerCredentials.newBuilder()
          .keyManager(serverCert, serverPrivateKey)
          .trustManager(caCert)
          .clientAuth(TlsServerCredentials.ClientAuth.OPTIONAL)
          .build();
    }
    ChannelCredentials channelCreds;
    try (InputStream caCert = TlsTesting.loadCert("ca.pem")) {
      channelCreds = TlsChannelCredentials.newBuilder()
          .trustManager(caCert)
          .build();
    }
    Server server = grpcCleanupRule.register(server(serverCreds));
    ManagedChannel channel = grpcCleanupRule.register(clientChannel(server, channelCreds));

    SimpleServiceGrpc.newBlockingStub(channel).unaryRpc(SimpleRequest.getDefaultInstance());
  }

  @Test
  public void missingRequiredClientCert_fails() throws Exception {
    ServerCredentials serverCreds;
    try (InputStream serverCert = TlsTesting.loadCert("server1.pem");
         InputStream serverPrivateKey = TlsTesting.loadCert("server1.key");
         InputStream caCert = TlsTesting.loadCert("ca.pem")) {
      serverCreds = TlsServerCredentials.newBuilder()
          .keyManager(serverCert, serverPrivateKey)
          .trustManager(caCert)
          .clientAuth(TlsServerCredentials.ClientAuth.REQUIRE)
          .build();
    }
    ChannelCredentials channelCreds;
    try (InputStream caCert = TlsTesting.loadCert("ca.pem")) {
      channelCreds = TlsChannelCredentials.newBuilder()
          .trustManager(caCert)
          .build();
    }
    Server server = grpcCleanupRule.register(server(serverCreds));
    ManagedChannel channel = grpcCleanupRule.register(clientChannel(server, channelCreds));

    assertRpcFails(channel);
  }

  @Test
  public void untrustedServer_fails() throws Exception {
    ServerCredentials serverCreds;
    try (InputStream serverCert = TlsTesting.loadCert("server1.pem");
         InputStream serverPrivateKey = TlsTesting.loadCert("server1.key")) {
      serverCreds = TlsServerCredentials.newBuilder()
          .keyManager(serverCert, serverPrivateKey)
          .build();
    }
    ChannelCredentials channelCreds = TlsChannelCredentials.create();
    Server server = grpcCleanupRule.register(server(serverCreds));
    ManagedChannel channel = grpcCleanupRule.register(clientChannel(server, channelCreds));

    assertRpcFails(channel);
  }

  @Test
  public void unmatchedServerSubjectAlternativeNames_fails() throws Exception {
    ServerCredentials serverCreds;
    try (InputStream serverCert = TlsTesting.loadCert("server1.pem");
         InputStream serverPrivateKey = TlsTesting.loadCert("server1.key")) {
      serverCreds = TlsServerCredentials.newBuilder()
          .keyManager(serverCert, serverPrivateKey)
          .build();
    }
    ChannelCredentials channelCreds;
    try (InputStream caCert = TlsTesting.loadCert("ca.pem")) {
      channelCreds = TlsChannelCredentials.newBuilder()
          .trustManager(caCert)
          .build();
    }
    Server server = grpcCleanupRule.register(server(serverCreds));
    ManagedChannel channel = grpcCleanupRule.register(clientChannelBuilder(server, channelCreds)
        .overrideAuthority("notgonnamatch.example.com")
        .build());

    assertRpcFails(channel);
  }

  private static Server server(ServerCredentials creds) throws IOException {
    return OkHttpServerBuilder.forPort(0, creds)
        .directExecutor()
        .addService(new SimpleServiceImpl())
        .build()
        .start();
  }

  private static ManagedChannelBuilder<?> clientChannelBuilder(
      Server server, ChannelCredentials creds) {
    return OkHttpChannelBuilder.forAddress("localhost", server.getPort(), creds)
        .directExecutor()
        .overrideAuthority(TestUtils.TEST_SERVER_HOST);
  }

  private static ManagedChannel clientChannel(Server server, ChannelCredentials creds) {
    return clientChannelBuilder(server, creds).build();
  }

  private static void assertRpcFails(ManagedChannel channel) {
    SimpleServiceGrpc.SimpleServiceBlockingStub stub = SimpleServiceGrpc.newBlockingStub(channel);
    try {
      stub.unaryRpc(SimpleRequest.getDefaultInstance());
      assertWithMessage("TLS handshake should have failed, but didn't; received RPC response")
          .fail();
    } catch (StatusRuntimeException e) {
      assertWithMessage(Throwables.getStackTraceAsString(e))
          .that(e.getStatus().getCode()).isEqualTo(Status.Code.UNAVAILABLE);
    }
    // We really want to see TRANSIENT_FAILURE here, but if the test runs slowly the 1s backoff
    // may be exceeded by the time the failure happens (since it counts from the start of the
    // attempt). Even so, CONNECTING is a strong indicator that the handshake failed; otherwise we'd
    // expect READY or IDLE.
    assertThat(channel.getState(false))
        .isAnyOf(ConnectivityState.TRANSIENT_FAILURE, ConnectivityState.CONNECTING);
  }

  private static final class SimpleServiceImpl extends SimpleServiceGrpc.SimpleServiceImplBase {
    @Override
    public void unaryRpc(SimpleRequest req, StreamObserver<SimpleResponse> respOb) {
      respOb.onNext(SimpleResponse.getDefaultInstance());
      respOb.onCompleted();
    }
  }
}
