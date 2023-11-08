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

package io.grpc.netty;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.fail;

import com.google.common.util.concurrent.MoreExecutors;
import io.grpc.ChannelCredentials;
import io.grpc.Grpc;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.ServerCredentials;
import io.grpc.StatusRuntimeException;
import io.grpc.TlsChannelCredentials;
import io.grpc.TlsServerCredentials;
import io.grpc.TlsServerCredentials.ClientAuth;
import io.grpc.internal.testing.TestUtils;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.TlsTesting;
import io.grpc.testing.protobuf.SimpleRequest;
import io.grpc.testing.protobuf.SimpleResponse;
import io.grpc.testing.protobuf.SimpleServiceGrpc;
import io.grpc.util.AdvancedTlsX509KeyManager;
import io.grpc.util.AdvancedTlsX509TrustManager;
import io.grpc.util.AdvancedTlsX509TrustManager.SslSocketAndEnginePeerVerifier;
import io.grpc.util.AdvancedTlsX509TrustManager.Verification;
import io.grpc.util.CertificateUtils;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.security.spec.InvalidKeySpecException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLEngine;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class AdvancedTlsTest {
  public static final String SERVER_0_KEY_FILE = "server0.key";
  public static final String SERVER_0_PEM_FILE = "server0.pem";
  public static final String CLIENT_0_KEY_FILE = "client.key";
  public static final String CLIENT_0_PEM_FILE = "client.pem";
  public static final String CA_PEM_FILE = "ca.pem";
  public static final String SERVER_BAD_KEY_FILE = "badserver.key";
  public static final String SERVER_BAD_PEM_FILE = "badserver.pem";

  private ScheduledExecutorService executor;
  private Server server;
  private ManagedChannel channel;

  private File caCertFile;
  private File serverKey0File;
  private File serverCert0File;
  private File clientKey0File;
  private File clientCert0File;
  private X509Certificate[] caCert;
  private PrivateKey serverKey0;
  private X509Certificate[] serverCert0;
  private PrivateKey clientKey0;
  private X509Certificate[] clientCert0;
  private PrivateKey serverKeyBad;
  private X509Certificate[] serverCertBad;

  @Before
  public void setUp()
      throws NoSuchAlgorithmException, IOException, CertificateException, InvalidKeySpecException {
    executor = Executors.newSingleThreadScheduledExecutor();
    caCertFile = TestUtils.loadCert(CA_PEM_FILE);
    serverKey0File = TestUtils.loadCert(SERVER_0_KEY_FILE);
    serverCert0File = TestUtils.loadCert(SERVER_0_PEM_FILE);
    clientKey0File = TestUtils.loadCert(CLIENT_0_KEY_FILE);
    clientCert0File = TestUtils.loadCert(CLIENT_0_PEM_FILE);
    caCert = CertificateUtils.getX509Certificates(TlsTesting.loadCert(CA_PEM_FILE));
    serverKey0 = CertificateUtils.getPrivateKey(TlsTesting.loadCert(SERVER_0_KEY_FILE));
    serverCert0 = CertificateUtils.getX509Certificates(TlsTesting.loadCert(SERVER_0_PEM_FILE));
    clientKey0 = CertificateUtils.getPrivateKey(TlsTesting.loadCert(CLIENT_0_KEY_FILE));
    clientCert0 = CertificateUtils.getX509Certificates(TlsTesting.loadCert(CLIENT_0_PEM_FILE));
    serverKeyBad = CertificateUtils.getPrivateKey(TlsTesting.loadCert(SERVER_BAD_KEY_FILE));
    serverCertBad = CertificateUtils.getX509Certificates(TlsTesting.loadCert(SERVER_BAD_PEM_FILE));
  }

  @After
  public void tearDown() {
    if (server != null) {
      server.shutdown();
    }
    if (channel != null) {
      channel.shutdown();
    }
    MoreExecutors.shutdownAndAwaitTermination(executor, 5, TimeUnit.SECONDS);
  }

  @Test
  public void basicMutualTlsTest() throws Exception {
    // Create & start a server.
    ServerCredentials serverCredentials = TlsServerCredentials.newBuilder()
        .keyManager(serverCert0File, serverKey0File).trustManager(caCertFile)
        .clientAuth(ClientAuth.REQUIRE).build();
    server = Grpc.newServerBuilderForPort(0, serverCredentials).addService(
        new SimpleServiceImpl()).build().start();
    // Create a client to connect.
    ChannelCredentials channelCredentials = TlsChannelCredentials.newBuilder()
        .keyManager(clientCert0File, clientKey0File).trustManager(caCertFile).build();
    channel = Grpc.newChannelBuilderForAddress("localhost", server.getPort(), channelCredentials)
        .overrideAuthority("foo.test.google.com.au").build();
    // Start the connection.
    try {
      SimpleServiceGrpc.SimpleServiceBlockingStub client =
          SimpleServiceGrpc.newBlockingStub(channel);
      // Send an actual request, via the full GRPC & network stack, and check that a proper
      // response comes back.
      client.unaryRpc(SimpleRequest.getDefaultInstance());
    } catch (StatusRuntimeException e) {
      e.printStackTrace();
      fail("Failed to make a connection");
      e.printStackTrace();
    }
  }

  @Test
  public void advancedTlsKeyManagerTrustManagerMutualTlsTest() throws Exception {
    // Create a server with the key manager and trust manager.
    AdvancedTlsX509KeyManager serverKeyManager = new AdvancedTlsX509KeyManager();
    serverKeyManager.updateIdentityCredentials(serverKey0, serverCert0);
    AdvancedTlsX509TrustManager serverTrustManager = AdvancedTlsX509TrustManager.newBuilder()
        .setVerification(Verification.CERTIFICATE_ONLY_VERIFICATION)
        .build();
    serverTrustManager.updateTrustCredentials(caCert);
    ServerCredentials serverCredentials = TlsServerCredentials.newBuilder()
        .keyManager(serverKeyManager).trustManager(serverTrustManager)
        .clientAuth(ClientAuth.REQUIRE).build();
    server = Grpc.newServerBuilderForPort(0, serverCredentials).addService(
        new SimpleServiceImpl()).build().start();
    // Create a client with the key manager and trust manager.
    AdvancedTlsX509KeyManager clientKeyManager = new AdvancedTlsX509KeyManager();
    clientKeyManager.updateIdentityCredentials(clientKey0, clientCert0);
    AdvancedTlsX509TrustManager clientTrustManager = AdvancedTlsX509TrustManager.newBuilder()
        .setVerification(Verification.CERTIFICATE_AND_HOST_NAME_VERIFICATION)
        .build();
    clientTrustManager.updateTrustCredentials(caCert);
    ChannelCredentials channelCredentials = TlsChannelCredentials.newBuilder()
        .keyManager(clientKeyManager).trustManager(clientTrustManager).build();
    channel = Grpc.newChannelBuilderForAddress("localhost", server.getPort(), channelCredentials)
        .overrideAuthority("foo.test.google.com.au").build();
    // Start the connection.
    try {
      SimpleServiceGrpc.SimpleServiceBlockingStub client =
          SimpleServiceGrpc.newBlockingStub(channel);
      client.unaryRpc(SimpleRequest.getDefaultInstance());
    } catch (StatusRuntimeException e) {
      fail("Failed to make a connection");
      e.printStackTrace();
    }
  }

  @Test
  public void trustManagerCustomVerifierMutualTlsTest() throws Exception {
    AdvancedTlsX509KeyManager serverKeyManager = new AdvancedTlsX509KeyManager();
    serverKeyManager.updateIdentityCredentials(serverKey0, serverCert0);
    // Set server's custom verification based on the information of clientCert0.
    AdvancedTlsX509TrustManager serverTrustManager = AdvancedTlsX509TrustManager.newBuilder()
        .setVerification(Verification.CERTIFICATE_ONLY_VERIFICATION)
        .setSslSocketAndEnginePeerVerifier(
            new SslSocketAndEnginePeerVerifier() {
              @Override
              public void verifyPeerCertificate(X509Certificate[] peerCertChain, String authType,
                  Socket socket) throws CertificateException {
                if (peerCertChain == null || peerCertChain.length == 0) {
                  throw new CertificateException("peerCertChain is empty");
                }
                X509Certificate leafCert = peerCertChain[0];
                if (!leafCert.getSubjectDN().getName().contains("testclient")) {
                  throw new CertificateException("SslSocketAndEnginePeerVerifier failed");
                }
              }

              @Override
              public void verifyPeerCertificate(X509Certificate[] peerCertChain, String authType,
                  SSLEngine engine) throws CertificateException {
                if (peerCertChain == null || peerCertChain.length == 0) {
                  throw new CertificateException("peerCertChain is empty");
                }
                X509Certificate leafCert = peerCertChain[0];
                if (!leafCert.getSubjectDN().getName().contains("testclient")) {
                  throw new CertificateException("SslSocketAndEnginePeerVerifier failed");
                }
              }
            })
        .build();
    serverTrustManager.updateTrustCredentials(caCert);
    ServerCredentials serverCredentials = TlsServerCredentials.newBuilder()
        .keyManager(serverKeyManager).trustManager(serverTrustManager)
        .clientAuth(ClientAuth.REQUIRE).build();
    server = Grpc.newServerBuilderForPort(0, serverCredentials).addService(
        new SimpleServiceImpl()).build().start();

    AdvancedTlsX509KeyManager clientKeyManager = new AdvancedTlsX509KeyManager();
    clientKeyManager.updateIdentityCredentials(clientKey0, clientCert0);
    // Set client's custom verification based on the information of serverCert0.
    AdvancedTlsX509TrustManager clientTrustManager = AdvancedTlsX509TrustManager.newBuilder()
        .setVerification(Verification.CERTIFICATE_ONLY_VERIFICATION)
        .setSslSocketAndEnginePeerVerifier(
            new SslSocketAndEnginePeerVerifier() {
              @Override
              public void verifyPeerCertificate(X509Certificate[] peerCertChain, String authType,
                  Socket socket) throws CertificateException {
                if (peerCertChain == null || peerCertChain.length == 0) {
                  throw new CertificateException("peerCertChain is empty");
                }
                X509Certificate leafCert = peerCertChain[0];
                if (!leafCert.getSubjectDN().getName().contains("*.test.google.com.au")) {
                  throw new CertificateException("SslSocketAndEnginePeerVerifier failed");
                }
              }
              
              @Override
              public void verifyPeerCertificate(X509Certificate[] peerCertChain, String authType,
                  SSLEngine engine) throws CertificateException {
                if (peerCertChain == null || peerCertChain.length == 0) {
                  throw new CertificateException("peerCertChain is empty");
                }
                X509Certificate leafCert = peerCertChain[0];
                if (!leafCert.getSubjectDN().getName().contains("*.test.google.com.au")) {
                  throw new CertificateException("SslSocketAndEnginePeerVerifier failed");
                }
              }
            })
        .build();
    clientTrustManager.updateTrustCredentials(caCert);
    ChannelCredentials channelCredentials = TlsChannelCredentials.newBuilder()
        .keyManager(clientKeyManager).trustManager(clientTrustManager).build();
    channel = Grpc.newChannelBuilderForAddress(
        "localhost", server.getPort(), channelCredentials).build();
    // Start the connection.
    try {
      SimpleServiceGrpc.SimpleServiceBlockingStub client =
          SimpleServiceGrpc.newBlockingStub(channel);
      client.unaryRpc(SimpleRequest.getDefaultInstance());
    } catch (StatusRuntimeException e) {
      fail("Failed to make a connection");
      e.printStackTrace();
    }
  }

  @Test
  public void trustManagerInsecurelySkipAllTest() throws Exception {
    AdvancedTlsX509KeyManager serverKeyManager = new AdvancedTlsX509KeyManager();
    // Even if we provide bad credentials for the server, the test should still pass, because we
    // will configure the client to skip all checks later.
    serverKeyManager.updateIdentityCredentials(serverKeyBad, serverCertBad);
    AdvancedTlsX509TrustManager serverTrustManager = AdvancedTlsX509TrustManager.newBuilder()
        .setVerification(Verification.CERTIFICATE_ONLY_VERIFICATION)
        .setSslSocketAndEnginePeerVerifier(
            new SslSocketAndEnginePeerVerifier() {
              @Override
              public void verifyPeerCertificate(X509Certificate[] peerCertChain, String authType,
                  Socket socket) throws CertificateException { }

              @Override
              public void verifyPeerCertificate(X509Certificate[] peerCertChain, String authType,
                  SSLEngine engine) throws CertificateException { }
            })
        .build();
    serverTrustManager.updateTrustCredentials(caCert);
    ServerCredentials serverCredentials = TlsServerCredentials.newBuilder()
        .keyManager(serverKeyManager).trustManager(serverTrustManager)
        .clientAuth(ClientAuth.REQUIRE).build();
    server = Grpc.newServerBuilderForPort(0, serverCredentials).addService(
        new SimpleServiceImpl()).build().start();

    AdvancedTlsX509KeyManager clientKeyManager = new AdvancedTlsX509KeyManager();
    clientKeyManager.updateIdentityCredentials(clientKey0, clientCert0);
    // Set the client to skip all checks, including traditional certificate verification.
    // Note this is very dangerous in production environment - only do so if you are confident on
    // what you are doing!
    AdvancedTlsX509TrustManager clientTrustManager = AdvancedTlsX509TrustManager.newBuilder()
        .setVerification(Verification.INSECURELY_SKIP_ALL_VERIFICATION)
        .setSslSocketAndEnginePeerVerifier(
            new SslSocketAndEnginePeerVerifier() {
              @Override
              public void verifyPeerCertificate(X509Certificate[] peerCertChain, String authType,
                  Socket socket) throws CertificateException { }

              @Override
              public void verifyPeerCertificate(X509Certificate[] peerCertChain, String authType,
                  SSLEngine engine) throws CertificateException { }
            })
        .build();
    clientTrustManager.updateTrustCredentials(caCert);
    ChannelCredentials channelCredentials = TlsChannelCredentials.newBuilder()
        .keyManager(clientKeyManager).trustManager(clientTrustManager).build();
    channel = Grpc.newChannelBuilderForAddress(
        "localhost", server.getPort(), channelCredentials).build();
    // Start the connection.
    try {
      SimpleServiceGrpc.SimpleServiceBlockingStub client =
          SimpleServiceGrpc.newBlockingStub(channel);
      client.unaryRpc(SimpleRequest.getDefaultInstance());
    } catch (StatusRuntimeException e) {
      fail("Failed to make a connection");
      e.printStackTrace();
    }
  }

  @Test
  public void onFileReloadingKeyManagerTrustManagerTest() throws Exception {
    // Create & start a server.
    AdvancedTlsX509KeyManager serverKeyManager = new AdvancedTlsX509KeyManager();
    Closeable serverKeyShutdown = serverKeyManager.updateIdentityCredentialsFromFile(serverKey0File,
        serverCert0File, 100, TimeUnit.MILLISECONDS, executor);
    AdvancedTlsX509TrustManager serverTrustManager = AdvancedTlsX509TrustManager.newBuilder()
        .setVerification(Verification.CERTIFICATE_ONLY_VERIFICATION)
        .build();
    Closeable serverTrustShutdown = serverTrustManager.updateTrustCredentialsFromFile(caCertFile,
        100, TimeUnit.MILLISECONDS, executor);
    ServerCredentials serverCredentials = TlsServerCredentials.newBuilder()
        .keyManager(serverKeyManager).trustManager(serverTrustManager)
        .clientAuth(ClientAuth.REQUIRE).build();
    server = Grpc.newServerBuilderForPort(0, serverCredentials).addService(
        new SimpleServiceImpl()).build().start();
    // Create a client to connect.
    AdvancedTlsX509KeyManager clientKeyManager = new AdvancedTlsX509KeyManager();
    Closeable clientKeyShutdown = clientKeyManager.updateIdentityCredentialsFromFile(clientKey0File,
        clientCert0File,100, TimeUnit.MILLISECONDS, executor);
    AdvancedTlsX509TrustManager clientTrustManager = AdvancedTlsX509TrustManager.newBuilder()
        .setVerification(Verification.CERTIFICATE_AND_HOST_NAME_VERIFICATION)
        .build();
    Closeable clientTrustShutdown = clientTrustManager.updateTrustCredentialsFromFile(caCertFile,
        100, TimeUnit.MILLISECONDS, executor);
    ChannelCredentials channelCredentials = TlsChannelCredentials.newBuilder()
        .keyManager(clientKeyManager).trustManager(clientTrustManager).build();
    channel = Grpc.newChannelBuilderForAddress("localhost", server.getPort(), channelCredentials)
        .overrideAuthority("foo.test.google.com.au").build();
    // Start the connection.
    try {
      SimpleServiceGrpc.SimpleServiceBlockingStub client =
          SimpleServiceGrpc.newBlockingStub(channel);
      // Send an actual request, via the full GRPC & network stack, and check that a proper
      // response comes back.
      client.unaryRpc(SimpleRequest.getDefaultInstance());
    } catch (StatusRuntimeException e) {
      e.printStackTrace();
      fail("Find error: " + e.getMessage());
    }
    // Clean up.
    serverKeyShutdown.close();
    serverTrustShutdown.close();
    clientKeyShutdown.close();
    clientTrustShutdown.close();
  }

  @Test
  public void onFileLoadingKeyManagerTrustManagerTest() throws Exception {
    // Create & start a server.
    AdvancedTlsX509KeyManager serverKeyManager = new AdvancedTlsX509KeyManager();
    serverKeyManager.updateIdentityCredentialsFromFile(serverKey0File, serverCert0File);
    AdvancedTlsX509TrustManager serverTrustManager = AdvancedTlsX509TrustManager.newBuilder()
        .setVerification(Verification.CERTIFICATE_ONLY_VERIFICATION)
        .build();
    serverTrustManager.updateTrustCredentialsFromFile(caCertFile);
    ServerCredentials serverCredentials = TlsServerCredentials.newBuilder()
        .keyManager(serverKeyManager).trustManager(serverTrustManager)
        .clientAuth(ClientAuth.REQUIRE).build();
    server = Grpc.newServerBuilderForPort(0, serverCredentials).addService(
        new SimpleServiceImpl()).build().start();
    // Create a client to connect.
    AdvancedTlsX509KeyManager clientKeyManager = new AdvancedTlsX509KeyManager();
    clientKeyManager.updateIdentityCredentialsFromFile(clientKey0File, clientCert0File);
    AdvancedTlsX509TrustManager clientTrustManager = AdvancedTlsX509TrustManager.newBuilder()
        .setVerification(Verification.CERTIFICATE_AND_HOST_NAME_VERIFICATION)
        .build();
    clientTrustManager.updateTrustCredentialsFromFile(caCertFile);
    ChannelCredentials channelCredentials = TlsChannelCredentials.newBuilder()
        .keyManager(clientKeyManager).trustManager(clientTrustManager).build();
    channel = Grpc.newChannelBuilderForAddress("localhost", server.getPort(), channelCredentials)
        .overrideAuthority("foo.test.google.com.au").build();
    // Start the connection.
    try {
      SimpleServiceGrpc.SimpleServiceBlockingStub client =
          SimpleServiceGrpc.newBlockingStub(channel);
      // Send an actual request, via the full GRPC & network stack, and check that a proper
      // response comes back.
      client.unaryRpc(SimpleRequest.getDefaultInstance());
    } catch (StatusRuntimeException e) {
      e.printStackTrace();
      fail("Find error: " + e.getMessage());
    }
  }

  @Test
  public void onFileReloadingKeyManagerBadInitialContentTest() throws Exception {
    AdvancedTlsX509KeyManager keyManager = new AdvancedTlsX509KeyManager();
    // We swap the order of key and certificates to intentionally create an exception.
    assertThrows(GeneralSecurityException.class,
        () -> keyManager.updateIdentityCredentialsFromFile(serverCert0File,
          serverKey0File, 100, TimeUnit.MILLISECONDS, executor));
  }

  @Test
  public void onFileReloadingTrustManagerBadInitialContentTest() throws Exception {
    AdvancedTlsX509TrustManager trustManager = AdvancedTlsX509TrustManager.newBuilder()
        .setVerification(Verification.CERTIFICATE_ONLY_VERIFICATION)
        .build();
    // We pass in a key as the trust certificates to intentionally create an exception.
    assertThrows(GeneralSecurityException.class,
        () -> trustManager.updateTrustCredentialsFromFile(serverKey0File,
          100, TimeUnit.MILLISECONDS, executor));
  }

  @Test
  public void keyManagerAliasesTest() throws Exception {
    AdvancedTlsX509KeyManager km = new AdvancedTlsX509KeyManager();
    assertArrayEquals(
        new String[] {"default"}, km.getClientAliases("", null));
    assertEquals(
        "default", km.chooseClientAlias(new String[] {"default"}, null, null));
    assertArrayEquals(
        new String[] {"default"}, km.getServerAliases("", null));
    assertEquals(
        "default", km.chooseServerAlias("default", null, null));
  }

  @Test
  public void trustManagerCheckTrustedWithSocketTest() throws Exception {
    AdvancedTlsX509TrustManager tm = AdvancedTlsX509TrustManager.newBuilder()
        .setVerification(Verification.INSECURELY_SKIP_ALL_VERIFICATION).build();
    tm.updateTrustCredentials(caCert);
    tm.checkClientTrusted(serverCert0, "RSA", new Socket());
    tm.useSystemDefaultTrustCerts();
    tm.checkServerTrusted(clientCert0, "RSA", new Socket());
  }

  @Test
  public void trustManagerCheckClientTrustedWithoutParameterTest() throws Exception {
    AdvancedTlsX509TrustManager tm = AdvancedTlsX509TrustManager.newBuilder()
        .setVerification(Verification.INSECURELY_SKIP_ALL_VERIFICATION).build();
    CertificateException ex =
        assertThrows(CertificateException.class, () -> tm.checkClientTrusted(serverCert0, "RSA"));
    assertThat(ex).hasMessageThat()
        .isEqualTo("Not enough information to validate peer. SSLEngine or Socket required.");
  }

  @Test
  public void trustManagerCheckServerTrustedWithoutParameterTest() throws Exception {
    AdvancedTlsX509TrustManager tm = AdvancedTlsX509TrustManager.newBuilder()
        .setVerification(Verification.INSECURELY_SKIP_ALL_VERIFICATION).build();
    CertificateException ex =
        assertThrows(CertificateException.class, () -> tm.checkServerTrusted(serverCert0, "RSA"));
    assertThat(ex).hasMessageThat()
        .isEqualTo("Not enough information to validate peer. SSLEngine or Socket required.");
  }

  @Test
  public void trustManagerEmptyChainTest() throws Exception {
    AdvancedTlsX509TrustManager tm = AdvancedTlsX509TrustManager.newBuilder()
        .setVerification(Verification.CERTIFICATE_ONLY_VERIFICATION)
        .build();
    tm.updateTrustCredentials(caCert);
    IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
        () -> tm.checkClientTrusted(null, "RSA", (SSLEngine) null));
    assertThat(ex).hasMessageThat()
        .isEqualTo("Want certificate verification but got null or empty certificates");
  }

  @Test
  public void trustManagerBadCustomVerificationTest() throws Exception {
    AdvancedTlsX509TrustManager tm = AdvancedTlsX509TrustManager.newBuilder()
        .setVerification(Verification.CERTIFICATE_ONLY_VERIFICATION)
        .setSslSocketAndEnginePeerVerifier(
            new SslSocketAndEnginePeerVerifier() {
              @Override
              public void verifyPeerCertificate(X509Certificate[] peerCertChain, String authType,
                  Socket socket) throws CertificateException {
                throw new CertificateException("Bad Custom Verification");
              }

              @Override
              public void verifyPeerCertificate(X509Certificate[] peerCertChain, String authType,
                  SSLEngine engine) throws CertificateException {
                throw new CertificateException("Bad Custom Verification");
              }
            }).build();
    tm.updateTrustCredentials(caCert);
    CertificateException ex = assertThrows(
        CertificateException.class,
        () -> tm.checkClientTrusted(serverCert0, "RSA", new Socket()));
    assertThat(ex).hasMessageThat().isEqualTo("Bad Custom Verification");
  }

  private static class SimpleServiceImpl extends SimpleServiceGrpc.SimpleServiceImplBase {
    @Override
    public void unaryRpc(SimpleRequest req, StreamObserver<SimpleResponse> respOb) {
      respOb.onNext(SimpleResponse.getDefaultInstance());
      respOb.onCompleted();
    }
  }
}
