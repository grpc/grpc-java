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
import static org.junit.Assert.fail;

import com.google.common.base.Throwables;
import io.grpc.CallOptions;
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
import io.grpc.okhttp.internal.Platform;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.GrpcCleanupRule;
import io.grpc.testing.TlsTesting;
import io.grpc.testing.protobuf.SimpleRequest;
import io.grpc.testing.protobuf.SimpleResponse;
import io.grpc.testing.protobuf.SimpleServiceGrpc;
import io.grpc.util.CertificateUtils;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Optional;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;
import javax.net.ssl.X509TrustManager;
import javax.security.auth.x500.X500Principal;
import org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Verify OkHttp's TLS integration. */
@RunWith(JUnit4.class)
@IgnoreJRERequirement
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
  public void perRpcAuthorityOverride_hostnameVerifier_goodAuthority_succeeds() throws Exception {
    OkHttpClientTransport.enablePerRpcAuthorityCheck = true;
    try {
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

      ClientCalls.blockingUnaryCall(channel, SimpleServiceGrpc.getUnaryRpcMethod(),
              CallOptions.DEFAULT.withAuthority("good.test.google.fr"),
              SimpleRequest.getDefaultInstance());
    } finally {
      OkHttpClientTransport.enablePerRpcAuthorityCheck = false;
    }
  }

  @Test
  public void perRpcAuthorityOverride_hostnameVerifier_badAuthority_fails()
          throws Exception {
    OkHttpClientTransport.enablePerRpcAuthorityCheck = true;
    try {
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

      try {
        ClientCalls.blockingUnaryCall(channel, SimpleServiceGrpc.getUnaryRpcMethod(),
              CallOptions.DEFAULT.withAuthority("disallowed.name.com"),
              SimpleRequest.getDefaultInstance());
        fail("Expected exception for hostname verifier failure.");
      } catch (StatusRuntimeException ex) {
        assertThat(ex.getStatus().getCode()).isEqualTo(Status.Code.UNAVAILABLE);
        assertThat(ex.getStatus().getDescription()).isEqualTo(
                "HostNameVerifier verification failed for authority 'disallowed.name.com'");
      }
    } finally {
      OkHttpClientTransport.enablePerRpcAuthorityCheck = false;
    }
  }

  @Test
  public void perRpcAuthorityOverride_hostnameVerifier_badAuthority_flagDisabled_succeeds()
          throws Exception {
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

    ClientCalls.blockingUnaryCall(channel, SimpleServiceGrpc.getUnaryRpcMethod(),
            CallOptions.DEFAULT.withAuthority("disallowed.name.com"),
            SimpleRequest.getDefaultInstance());
  }

  @Test
  public void perRpcAuthorityOverride_noTlsCredentialsUsedToBuildChannel_fails() throws Exception {
    OkHttpClientTransport.enablePerRpcAuthorityCheck = true;
    try {
      ServerCredentials serverCreds;
      try (InputStream serverCert = TlsTesting.loadCert("server1.pem");
           InputStream serverPrivateKey = TlsTesting.loadCert("server1.key")) {
        serverCreds = TlsServerCredentials.newBuilder()
                .keyManager(serverCert, serverPrivateKey)
                .build();
      }
      Server server = grpcCleanupRule.register(server(serverCreds));
      SSLSocketFactory sslSocketFactory = TestUtils.newSslSocketFactoryForCa(
              Platform.get().getProvider(), TestUtils.loadCert("ca.pem"));
      ManagedChannel channel = grpcCleanupRule.register(
              OkHttpChannelBuilder.forAddress("localhost", server.getPort())
                      .overrideAuthority(TestUtils.TEST_SERVER_HOST)
                      .directExecutor()
                      .sslSocketFactory(sslSocketFactory)
                      .build());

      try {
        ClientCalls.blockingUnaryCall(channel, SimpleServiceGrpc.getUnaryRpcMethod(),
            CallOptions.DEFAULT.withAuthority("bar.test.google.fr"),
            SimpleRequest.getDefaultInstance());
        fail("Expected exception for authority verification failure.");
      } catch (StatusRuntimeException ex) {
        assertThat(ex.getStatus().getCode()).isEqualTo(Status.Code.UNAVAILABLE);
        assertThat(ex.getStatus().getDescription()).isEqualTo(
                "Could not verify authority 'bar.test.google.fr' for the rpc with no "
                        + "X509TrustManager available");
      }
    } finally {
      OkHttpClientTransport.enablePerRpcAuthorityCheck = false;
    }
  }

  @Test
  public void perRpcAuthorityOverride_trustManager_permitted_succeeds() throws Exception {
    OkHttpClientTransport.enablePerRpcAuthorityCheck = true;
    try {
      ServerCredentials serverCreds;
      try (InputStream serverCert = TlsTesting.loadCert("server1.pem");
           InputStream serverPrivateKey = TlsTesting.loadCert("server1.key")) {
        serverCreds = TlsServerCredentials.newBuilder()
                .keyManager(serverCert, serverPrivateKey)
                .build();
      }
      ChannelCredentials channelCreds;
      try (InputStream caCert = TlsTesting.loadCert("ca.pem")) {
        X509ExtendedTrustManager regularTrustManager =
                (X509ExtendedTrustManager) getX509ExtendedTrustManager(caCert).get();
        channelCreds = TlsChannelCredentials.newBuilder()
                .trustManager(new HostnameCheckingX509ExtendedTrustManager(regularTrustManager))
                .build();
      }
      Server server = grpcCleanupRule.register(server(serverCreds));
      ManagedChannel channel = grpcCleanupRule.register(clientChannel(server, channelCreds));

      ClientCalls.blockingUnaryCall(channel, SimpleServiceGrpc.getUnaryRpcMethod(),
              CallOptions.DEFAULT.withAuthority("good.test.google.fr"),
              SimpleRequest.getDefaultInstance());
    } finally {
      OkHttpClientTransport.enablePerRpcAuthorityCheck = false;
    }
  }

  @Test
  public void perRpcAuthorityOverride_trustManager_denied_fails() throws Exception {
    OkHttpClientTransport.enablePerRpcAuthorityCheck = true;
    try {
      ServerCredentials serverCreds;
      try (InputStream serverCert = TlsTesting.loadCert("server1.pem");
           InputStream serverPrivateKey = TlsTesting.loadCert("server1.key")) {
        serverCreds = TlsServerCredentials.newBuilder()
                .keyManager(serverCert, serverPrivateKey)
                .build();
      }
      ChannelCredentials channelCreds;
      try (InputStream caCert = TlsTesting.loadCert("ca.pem")) {
        X509ExtendedTrustManager regularTrustManager =
                (X509ExtendedTrustManager) getX509ExtendedTrustManager(caCert).get();
        channelCreds = TlsChannelCredentials.newBuilder()
                .trustManager(new HostnameCheckingX509ExtendedTrustManager(regularTrustManager))
                .build();
      }
      Server server = grpcCleanupRule.register(server(serverCreds));
      ManagedChannel channel = grpcCleanupRule.register(clientChannel(server, channelCreds));

      try {
        ClientCalls.blockingUnaryCall(channel, SimpleServiceGrpc.getUnaryRpcMethod(),
                CallOptions.DEFAULT.withAuthority("bad.test.google.fr"),
                SimpleRequest.getDefaultInstance());
        fail("Expected exception for authority verification failure.");
      } catch (StatusRuntimeException ex) {
        assertThat(ex.getStatus().getCode()).isEqualTo(Status.Code.UNAVAILABLE);
        assertThat(ex.getCause().getCause()).isInstanceOf(CertificateException.class);
      }
    } finally {
      OkHttpClientTransport.enablePerRpcAuthorityCheck = false;
    }
  }

  @Test
  public void perRpcAuthorityOverride_trustManager_denied_flagDisabled_succeeds()
          throws Exception {
    ServerCredentials serverCreds;
    try (InputStream serverCert = TlsTesting.loadCert("server1.pem");
         InputStream serverPrivateKey = TlsTesting.loadCert("server1.key")) {
      serverCreds = TlsServerCredentials.newBuilder()
              .keyManager(serverCert, serverPrivateKey)
              .build();
    }
    ChannelCredentials channelCreds;
    try (InputStream caCert = TlsTesting.loadCert("ca.pem")) {
      X509ExtendedTrustManager regularTrustManager =
              (X509ExtendedTrustManager) getX509ExtendedTrustManager(caCert).get();
      channelCreds = TlsChannelCredentials.newBuilder()
              .trustManager(new HostnameCheckingX509ExtendedTrustManager(regularTrustManager))
              .build();
    }
    Server server = grpcCleanupRule.register(server(serverCreds));
    ManagedChannel channel = grpcCleanupRule.register(clientChannel(server, channelCreds));

    ClientCalls.blockingUnaryCall(channel, SimpleServiceGrpc.getUnaryRpcMethod(),
            CallOptions.DEFAULT.withAuthority("bad.test.google.fr"),
            SimpleRequest.getDefaultInstance());
  }

  /**
   * This test simulates the absence of X509ExtendedTrustManager while still using the
   * real trust manager for the connection handshake to happen. When the TrustManager is not an
   * X509ExtendedTrustManager, the per-rpc check ignores the trust manager. However, the
   * HostnameVerifier is still used, so only valid authorities are permitted.
   */
  @Test
  public void perRpcAuthorityOverride_notX509ExtendedTrustManager_goodAuthority_succeeds()
          throws Exception {
    OkHttpClientTransport.enablePerRpcAuthorityCheck = true;
    try {
      ServerCredentials serverCreds;
      try (InputStream serverCert = TlsTesting.loadCert("server1.pem");
           InputStream serverPrivateKey = TlsTesting.loadCert("server1.key")) {
        serverCreds = TlsServerCredentials.newBuilder()
                .keyManager(serverCert, serverPrivateKey)
                .build();
      }
      ChannelCredentials channelCreds;
      try (InputStream caCert = TlsTesting.loadCert("ca.pem")) {
        X509TrustManager x509ExtendedTrustManager =
                (X509TrustManager) getX509ExtendedTrustManager(caCert).get();
        channelCreds = TlsChannelCredentials.newBuilder()
                .trustManager(new FakeTrustManager(x509ExtendedTrustManager))
                .build();
      }
      Server server = grpcCleanupRule.register(server(serverCreds));
      ManagedChannel channel = grpcCleanupRule.register(clientChannel(server, channelCreds));

      ClientCalls.blockingUnaryCall(channel, SimpleServiceGrpc.getUnaryRpcMethod(),
              CallOptions.DEFAULT.withAuthority("foo.test.google.fr"),
              SimpleRequest.getDefaultInstance());
    } finally {
      OkHttpClientTransport.enablePerRpcAuthorityCheck = false;
    }
  }

  @Test
  public void perRpcAuthorityOverride_notX509ExtendedTrustManager_badAuthority_fails()
          throws Exception {
    OkHttpClientTransport.enablePerRpcAuthorityCheck = true;
    try {
      ServerCredentials serverCreds;
      try (InputStream serverCert = TlsTesting.loadCert("server1.pem");
           InputStream serverPrivateKey = TlsTesting.loadCert("server1.key")) {
        serverCreds = TlsServerCredentials.newBuilder()
                .keyManager(serverCert, serverPrivateKey)
                .build();
      }
      ChannelCredentials channelCreds;
      try (InputStream caCert = TlsTesting.loadCert("ca.pem")) {
        X509TrustManager x509ExtendedTrustManager =
                (X509TrustManager) getX509ExtendedTrustManager(caCert).get();
        channelCreds = TlsChannelCredentials.newBuilder()
                .trustManager(new FakeTrustManager(x509ExtendedTrustManager))
                .build();
      }
      Server server = grpcCleanupRule.register(server(serverCreds));
      ManagedChannel channel = grpcCleanupRule.register(clientChannel(server, channelCreds));

      try {
        ClientCalls.blockingUnaryCall(channel, SimpleServiceGrpc.getUnaryRpcMethod(),
                CallOptions.DEFAULT.withAuthority("disallowed.name.com"),
                SimpleRequest.getDefaultInstance());
        fail("Expected exception for authority verification failure.");
      } catch (StatusRuntimeException ex) {
        assertThat(ex.getStatus().getCode()).isEqualTo(Status.Code.UNAVAILABLE);
        assertThat(ex.getStatus().getDescription())
            .isEqualTo("HostNameVerifier verification failed for authority 'disallowed.name.com'");
      }
    } finally {
      OkHttpClientTransport.enablePerRpcAuthorityCheck = false;
    }
  }

  @Test
  public void
      perRpcAuthorityOverride_notX509ExtendedTrustManager_badAuthority_flagDisabled_succeeds()
      throws Exception {
    ServerCredentials serverCreds;
    try (InputStream serverCert = TlsTesting.loadCert("server1.pem");
         InputStream serverPrivateKey = TlsTesting.loadCert("server1.key")) {
      serverCreds = TlsServerCredentials.newBuilder()
              .keyManager(serverCert, serverPrivateKey)
              .build();
    }
    ChannelCredentials channelCreds;
    try (InputStream caCert = TlsTesting.loadCert("ca.pem")) {
      X509TrustManager x509ExtendedTrustManager =
              (X509TrustManager) getX509ExtendedTrustManager(caCert).get();
      channelCreds = TlsChannelCredentials.newBuilder()
              .trustManager(new FakeTrustManager(x509ExtendedTrustManager))
              .build();
    }
    Server server = grpcCleanupRule.register(server(serverCreds));
    ManagedChannel channel = grpcCleanupRule.register(clientChannel(server, channelCreds));

    ClientCalls.blockingUnaryCall(channel, SimpleServiceGrpc.getUnaryRpcMethod(),
            CallOptions.DEFAULT.withAuthority("disallowed.name.com"),
            SimpleRequest.getDefaultInstance());
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

  @Test
  public void hostnameVerifierTrusts_succeeds()
      throws Exception {
    ServerCredentials serverCreds;
    try (InputStream serverCert = TlsTesting.loadCert("server1.pem");
         InputStream serverPrivateKey = TlsTesting.loadCert("server1.key")) {
      serverCreds = TlsServerCredentials.newBuilder()
          .keyManager(serverCert, serverPrivateKey)
          .build();
    }
    SSLSocketFactory sslSocketFactory = TestUtils.newSslSocketFactoryForCa(
        Platform.get().getProvider(), TestUtils.loadCert("ca.pem"));
    Server server = grpcCleanupRule.register(server(serverCreds));
    ManagedChannel channel = grpcCleanupRule.register(
        OkHttpChannelBuilder.forAddress("localhost", server.getPort())
          .directExecutor()
          .overrideAuthority("notgonnamatch.example.com")
          .sslSocketFactory(sslSocketFactory)
          .hostnameVerifier((hostname, session) -> true)
          .build());

    SimpleServiceGrpc.newBlockingStub(channel).unaryRpc(SimpleRequest.getDefaultInstance());
  }

  @Test
  public void hostnameVerifierFails_fails()
      throws Exception {
    ServerCredentials serverCreds;
    try (InputStream serverCert = TlsTesting.loadCert("server1.pem");
         InputStream serverPrivateKey = TlsTesting.loadCert("server1.key")) {
      serverCreds = TlsServerCredentials.newBuilder()
          .keyManager(serverCert, serverPrivateKey)
          .build();
    }
    SSLSocketFactory sslSocketFactory = TestUtils.newSslSocketFactoryForCa(
        Platform.get().getProvider(), TestUtils.loadCert("ca.pem"));
    Server server = grpcCleanupRule.register(server(serverCreds));
    ManagedChannel channel = grpcCleanupRule.register(
        OkHttpChannelBuilder.forAddress("localhost", server.getPort())
          .directExecutor()
          .overrideAuthority(TestUtils.TEST_SERVER_HOST)
          .sslSocketFactory(sslSocketFactory)
          .hostnameVerifier((hostname, session) -> false)
          .build());

    Status status = assertRpcFails(channel);
    assertThat(status.getCause()).isInstanceOf(SSLPeerUnverifiedException.class);
  }

  /** Used to simulate the case of X509ExtendedTrustManager not present. */
  private static class FakeTrustManager implements X509TrustManager {

    private final X509TrustManager delegate;

    public FakeTrustManager(X509TrustManager x509ExtendedTrustManager) {
      this.delegate = x509ExtendedTrustManager;
    }

    @Override
    public void checkClientTrusted(X509Certificate[] x509Certificates, String s)
            throws CertificateException {
      delegate.checkClientTrusted(x509Certificates, s);
    }

    @Override
    public void checkServerTrusted(X509Certificate[] x509Certificates, String s)
            throws CertificateException {
      delegate.checkServerTrusted(x509Certificates, s);
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
      return delegate.getAcceptedIssuers();
    }
  }

  /**
   * Checks against a limited set of hostnames. In production, EndpointIdentificationAlgorithm is
   * unset so the default trust manager will not fail based on the hostname. This class is used to
   * test user-provided trust managers that may have their own behavior.
   */
  private static class HostnameCheckingX509ExtendedTrustManager
      extends ForwardingX509ExtendedTrustManager {
    public HostnameCheckingX509ExtendedTrustManager(X509ExtendedTrustManager tm) {
      super(tm);
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket)
        throws CertificateException {
      String peer = ((SSLSocket) socket).getHandshakeSession().getPeerHost();
      if (!"foo.test.google.fr".equals(peer) && !"good.test.google.fr".equals(peer)) {
        throw new CertificateException("Peer verification failed.");
      }
      super.checkServerTrusted(chain, authType, socket);
    }
  }

  @IgnoreJRERequirement
  private static class ForwardingX509ExtendedTrustManager extends X509ExtendedTrustManager {
    private final X509ExtendedTrustManager delegate;

    private ForwardingX509ExtendedTrustManager(X509ExtendedTrustManager delegate) {
      this.delegate = delegate;
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket)
            throws CertificateException {
      delegate.checkServerTrusted(chain, authType, socket);
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
            throws CertificateException {
      delegate.checkServerTrusted(chain, authType, engine);
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType)
            throws CertificateException {
      delegate.checkServerTrusted(chain, authType);
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
            throws CertificateException {
      delegate.checkClientTrusted(chain, authType, engine);
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType)
            throws CertificateException {
      delegate.checkClientTrusted(chain, authType);
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket)
            throws CertificateException {
      delegate.checkClientTrusted(chain, authType, socket);
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
      return delegate.getAcceptedIssuers();
    }
  }

  private static Optional<TrustManager> getX509ExtendedTrustManager(InputStream rootCerts)
          throws GeneralSecurityException {
    KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
    try {
      ks.load(null, null);
    } catch (IOException ex) {
      // Shouldn't really happen, as we're not loading any data.
      throw new GeneralSecurityException(ex);
    }
    X509Certificate[] certs = CertificateUtils.getX509Certificates(rootCerts);
    for (X509Certificate cert : certs) {
      X500Principal principal = cert.getSubjectX500Principal();
      ks.setCertificateEntry(principal.getName("RFC2253"), cert);
    }

    TrustManagerFactory trustManagerFactory =
            TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    trustManagerFactory.init(ks);
    return Arrays.stream(trustManagerFactory.getTrustManagers())
            .filter(trustManager -> trustManager instanceof X509ExtendedTrustManager).findFirst();
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

  private static Status assertRpcFails(ManagedChannel channel) {
    Status status = null;
    SimpleServiceGrpc.SimpleServiceBlockingStub stub = SimpleServiceGrpc.newBlockingStub(channel);
    try {
      stub.unaryRpc(SimpleRequest.getDefaultInstance());
      assertWithMessage("TLS handshake should have failed, but didn't; received RPC response")
          .fail();
    } catch (StatusRuntimeException e) {
      assertWithMessage(Throwables.getStackTraceAsString(e))
          .that(e.getStatus().getCode()).isEqualTo(Status.Code.UNAVAILABLE);
      status = e.getStatus();
    }
    // We really want to see TRANSIENT_FAILURE here, but if the test runs slowly the 1s backoff
    // may be exceeded by the time the failure happens (since it counts from the start of the
    // attempt). Even so, CONNECTING is a strong indicator that the handshake failed; otherwise we'd
    // expect READY or IDLE.
    assertThat(channel.getState(false))
        .isAnyOf(ConnectivityState.TRANSIENT_FAILURE, ConnectivityState.CONNECTING);
    return status;
  }

  private static final class SimpleServiceImpl extends SimpleServiceGrpc.SimpleServiceImplBase {
    @Override
    public void unaryRpc(SimpleRequest req, StreamObserver<SimpleResponse> respOb) {
      respOb.onNext(SimpleResponse.getDefaultInstance());
      respOb.onCompleted();
    }
  }
}
