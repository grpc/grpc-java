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
import io.grpc.testing.protobuf.SimpleRequest;
import io.grpc.testing.protobuf.SimpleResponse;
import io.grpc.testing.protobuf.SimpleServiceGrpc;
import io.grpc.util.AdvancedTlsX509KeyManager;
import io.grpc.util.AdvancedTlsX509TrustManager;
import io.grpc.util.AdvancedTlsX509TrustManager.PeerVerifier;
import io.grpc.util.AdvancedTlsX509TrustManager.SSLEnginePeerVerifier;
import io.grpc.util.AdvancedTlsX509TrustManager.SSLSocketPeerVerifier;
import io.grpc.util.AdvancedTlsX509TrustManager.Verification;
import io.grpc.util.CertificateUtils;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.net.Socket;
import java.security.PrivateKey;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLEngine;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class AdvancedTlsTest {
  public static final String SERVER_0_KEY_FILE = "server0.key";
  public static final String SERVER_0_PEM_FILE = "server0.pem";
  public static final String CLIENT_0_KEY_FILE = "client.key";
  public static final String CLIENT_0_PEM_FILE = "client.pem";
  public static final String CA_PEM_FILE = "ca.pem";

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

  @Before
  public void setUp()
      throws NoSuchAlgorithmException, IOException, CertificateException, InvalidKeySpecException {
    executor = Executors.newSingleThreadScheduledExecutor();
    caCertFile = TestUtils.loadCert(CA_PEM_FILE);
    serverKey0File = TestUtils.loadCert(SERVER_0_KEY_FILE);
    serverCert0File = TestUtils.loadCert(SERVER_0_PEM_FILE);
    clientKey0File = TestUtils.loadCert(CLIENT_0_KEY_FILE);
    clientCert0File = TestUtils.loadCert(CLIENT_0_PEM_FILE);
    caCert = CertificateUtils.getX509Certificates(
        TestUtils.class.getResourceAsStream("/certs/" + CA_PEM_FILE));
    serverKey0 = CertificateUtils.getPrivateKey(
        TestUtils.class.getResourceAsStream("/certs/" + SERVER_0_KEY_FILE));
    serverCert0 = CertificateUtils.getX509Certificates(
        TestUtils.class.getResourceAsStream("/certs/" + SERVER_0_PEM_FILE));
    clientKey0 = CertificateUtils.getPrivateKey(
        TestUtils.class.getResourceAsStream("/certs/" + CLIENT_0_KEY_FILE));
    clientCert0 = CertificateUtils.getX509Certificates(
        TestUtils.class.getResourceAsStream("/certs/" + CLIENT_0_PEM_FILE));
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
  public void AdvancedTlsKeyManagerTrustManagerMutualTlsTest() throws Exception {
    // Create & start a server.
    AdvancedTlsX509KeyManager serverKeyManager = new AdvancedTlsX509KeyManager();
    serverKeyManager.updateIdentityCredentials(serverKey0, serverCert0);
    AdvancedTlsX509TrustManager serverTrustManager = AdvancedTlsX509TrustManager.newBuilder()
        .setVerification(Verification.CertificateOnlyVerification)
        .build();
    serverTrustManager.updateTrustCredentials(caCert);
    ServerCredentials serverCredentials = TlsServerCredentials.newBuilder()
        .keyManager(serverKeyManager).trustManager(serverTrustManager)
        .clientAuth(ClientAuth.REQUIRE).build();
    server = Grpc.newServerBuilderForPort(0, serverCredentials).addService(
        new SimpleServiceImpl()).build().start();
    // Create a client to connect.
    AdvancedTlsX509KeyManager clientKeyManager = new AdvancedTlsX509KeyManager();
    clientKeyManager.updateIdentityCredentials(clientKey0, clientCert0);
    AdvancedTlsX509TrustManager clientTrustManager = AdvancedTlsX509TrustManager.newBuilder()
        .setVerification(Verification.CertificateAndHostNameVerification)
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
      // Send an actual request, via the full GRPC & network stack, and check that a proper
      // response comes back.
      client.unaryRpc(SimpleRequest.getDefaultInstance());
    } catch (StatusRuntimeException e) {
      fail("Failed to make a connection");
      e.printStackTrace();
    }
  }

  @Test
  public void TrustManagerCustomVerifierMutualTlsTest() throws Exception {
    // Create & start a server.
    AdvancedTlsX509KeyManager serverKeyManager = new AdvancedTlsX509KeyManager();
    serverKeyManager.updateIdentityCredentials(serverKey0, serverCert0);
    AdvancedTlsX509TrustManager serverTrustManager = AdvancedTlsX509TrustManager.newBuilder()
        .setVerification(Verification.CertificateOnlyVerification)
        .setSSLEnginePeerVerifier(
            new SSLEnginePeerVerifier() {
              @Override
              public void verifyPeerCertificate(X509Certificate[] peerCertChain, String authType,
                  SSLEngine engine) throws CertificateException {
                if (peerCertChain == null || peerCertChain.length == 0) {
                  throw new CertificateException("peerCertChain is empty");
                }
                X509Certificate leafCert = peerCertChain[0];
                if (!leafCert.getSubjectDN().getName().contains("testclient")) {
                  throw new CertificateException("SSLEnginePeerVerifier failed");
                }
              }
            })
        .setSSLSocketPeerVerifier(new SSLSocketPeerVerifier() {
          @Override
          public void verifyPeerCertificate(X509Certificate[] peerCertChain, String authType,
              Socket socket) throws CertificateException {
            if (peerCertChain == null || peerCertChain.length == 0) {
              throw new CertificateException("peerCertChain is empty");
            }
            X509Certificate leafCert = peerCertChain[0];
            if (!leafCert.getSubjectDN().getName().contains("testclient")) {
              throw new CertificateException("SSLSocketPeerVerifier failed");
            }
          }
        })
        .setPeerVerifier(new PeerVerifier() {
          @Override
          public void verifyPeerCertificate(X509Certificate[] peerCertChain, String authType)
              throws CertificateException {
            if (peerCertChain == null || peerCertChain.length == 0) {
              throw new CertificateException("peerCertChain is empty");
            }
            X509Certificate leafCert = peerCertChain[0];
            if (!leafCert.getSubjectDN().getName().contains("testclient")) {
              throw new CertificateException("PeerVerifier failed");
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
    // Create a client to connect.
    AdvancedTlsX509KeyManager clientKeyManager = new AdvancedTlsX509KeyManager();
    clientKeyManager.updateIdentityCredentials(clientKey0, clientCert0);
    AdvancedTlsX509TrustManager clientTrustManager = AdvancedTlsX509TrustManager.newBuilder()
        .setVerification(Verification.CertificateOnlyVerification)
        .setSSLEnginePeerVerifier(
            new SSLEnginePeerVerifier() {
              @Override
              public void verifyPeerCertificate(X509Certificate[] peerCertChain, String authType,
                  SSLEngine engine) throws CertificateException {
                if (peerCertChain == null || peerCertChain.length == 0) {
                  throw new CertificateException("peerCertChain is empty");
                }
                X509Certificate leafCert = peerCertChain[0];
                if (!leafCert.getSubjectDN().getName().contains("*.test.google.com.au")) {
                  throw new CertificateException("SSLEnginePeerVerifier failed");
                }
              }
            })
        .setSSLSocketPeerVerifier(new SSLSocketPeerVerifier() {
          @Override
          public void verifyPeerCertificate(X509Certificate[] peerCertChain, String authType,
              Socket socket) throws CertificateException {
            if (peerCertChain == null || peerCertChain.length == 0) {
              throw new CertificateException("peerCertChain is empty");
            }
            X509Certificate leafCert = peerCertChain[0];
            if (!leafCert.getSubjectDN().getName().contains("*.test.google.com.au")) {
              throw new CertificateException("SSLSocketPeerVerifier failed");
            }
          }
        })
        .setPeerVerifier(new PeerVerifier() {
          @Override
          public void verifyPeerCertificate(X509Certificate[] peerCertChain, String authType)
              throws CertificateException {
            if (peerCertChain == null || peerCertChain.length == 0) {
              throw new CertificateException("peerCertChain is empty");
            }
            X509Certificate leafCert = peerCertChain[0];
            if (!leafCert.getSubjectDN().getName().contains("*.test.google.com.au")) {
              throw new CertificateException("PeerVerifier failed");
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
      // Send an actual request, via the full GRPC & network stack, and check that a proper
      // response comes back.
      client.unaryRpc(SimpleRequest.getDefaultInstance());
    } catch (StatusRuntimeException e) {
      fail("Failed to make a connection");
      e.printStackTrace();
    }
  }

  @Test
  public void OnFileReloadingKeyManagerTrustManagerTest() throws Exception {
    // Create & start a server.
    AdvancedTlsX509KeyManager serverKeyManager = new AdvancedTlsX509KeyManager();
    Closeable serverKeyShutdown = serverKeyManager.updateIdentityCredentialsFromFile(serverKey0File,
        serverCert0File, 0, 100, TimeUnit.MILLISECONDS, executor);
    AdvancedTlsX509TrustManager serverTrustManager = AdvancedTlsX509TrustManager.newBuilder()
        .setVerification(Verification.CertificateOnlyVerification)
        .build();
    Closeable serverTrustShutdown = serverTrustManager.updateTrustCredentialsFromFile(caCertFile,
        0, 100, TimeUnit.MILLISECONDS, executor);
    ServerCredentials serverCredentials = TlsServerCredentials.newBuilder()
        .keyManager(serverKeyManager).trustManager(serverTrustManager)
        .clientAuth(ClientAuth.REQUIRE).build();
    server = Grpc.newServerBuilderForPort(0, serverCredentials).addService(
        new SimpleServiceImpl()).build().start();
    // Create a client to connect.
    AdvancedTlsX509KeyManager clientKeyManager = new AdvancedTlsX509KeyManager();
    Closeable clientKeyShutdown = clientKeyManager.updateIdentityCredentialsFromFile(clientKey0File,
        clientCert0File, 0, 100, TimeUnit.MILLISECONDS, executor);
    AdvancedTlsX509TrustManager clientTrustManager = AdvancedTlsX509TrustManager.newBuilder()
        .setVerification(Verification.CertificateAndHostNameVerification)
        .build();
    Closeable clientTrustShutdown = clientTrustManager.updateTrustCredentialsFromFile(caCertFile,
        0, 100, TimeUnit.MILLISECONDS, executor);
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

  private static class SimpleServiceImpl extends SimpleServiceGrpc.SimpleServiceImplBase {
    @Override
    public void unaryRpc(SimpleRequest req, StreamObserver<SimpleResponse> respOb) {
      respOb.onNext(SimpleResponse.getDefaultInstance());
      respOb.onCompleted();
    }
  }
}
