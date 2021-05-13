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
import io.grpc.util.AdvancedTlsX509TrustManager.Verification;
import io.grpc.util.CertificateUtils;

import io.netty.handler.ssl.OpenSsl;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import java.io.File;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.security.NoSuchAlgorithmException;
import java.security.Security;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import org.junit.After;
import org.junit.Assume;
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

  @Before
  public void setUp() throws NoSuchAlgorithmException {
    executor = Executors.newSingleThreadScheduledExecutor();
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
    File caCert = TestUtils.loadCert("ca.pem");
    File serverKey0 = TestUtils.loadCert("server0.key");
    File serverCert0 = TestUtils.loadCert("server0.pem");
    File clientKey0 = TestUtils.loadCert("client.key");
    File clientCert0 = TestUtils.loadCert("client.pem");

    // Create & start a server.

    ServerCredentials serverCredentials = TlsServerCredentials.newBuilder()
        .keyManager(serverCert0, serverKey0).trustManager(caCert)
        .clientAuth(ClientAuth.REQUIRE).build();
    server = Grpc.newServerBuilderForPort(0, serverCredentials).addService(
        new SimpleServiceImpl()).build().start();

    // Create a client to connect.
    ChannelCredentials channelCredentials = TlsChannelCredentials.newBuilder()
        .keyManager(clientCert0, clientKey0).trustManager(caCert).build();
    channel = Grpc.newChannelBuilderForAddress(
        "localhost", server.getPort(), channelCredentials).build();


    try {
      SimpleServiceGrpc.SimpleServiceBlockingStub client =
          SimpleServiceGrpc.newBlockingStub(channel);
      // Send an actual request, via the full GRPC & network stack, and check that a proper
      // response comes back.
      client.unaryRpc(SimpleRequest.getDefaultInstance());
    } catch (StatusRuntimeException e) {
      fail("Find error: " + e.getMessage());
    }
  }

  /*@Test
  public void AdvancedTlsKeyManagerTrustManagerMutualTlsTest() throws Exception {
    X509Certificate[] caCert = CertificateUtils.getX509Certificates(
        TestUtils.class.getResourceAsStream("/certs/" + CA_PEM_FILE));
    PrivateKey serverKey0 = CertificateUtils.getPrivateKey(
        TestUtils.class.getResourceAsStream("/certs/" + SERVER_0_KEY_FILE));
    X509Certificate[] serverCert0 = CertificateUtils.getX509Certificates(
        TestUtils.class.getResourceAsStream("/certs/" + SERVER_0_PEM_FILE));
    PrivateKey clientKey0 = CertificateUtils.getPrivateKey(
        TestUtils.class.getResourceAsStream("/certs/" + CLIENT_0_KEY_FILE));
    X509Certificate[] clientCert0 = CertificateUtils.getX509Certificates(
        TestUtils.class.getResourceAsStream("/certs/" + CLIENT_0_PEM_FILE));


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
    Server server = Grpc.newServerBuilderForPort(0, serverCredentials).addService(
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
    ManagedChannel channel = Grpc.newChannelBuilderForAddress(
        "localhost", server.getPort(), channelCredentials).build();


    try {
      SimpleServiceGrpc.SimpleServiceBlockingStub client =
          SimpleServiceGrpc.newBlockingStub(channel);
      // Send an actual request, via the full GRPC & network stack, and check that a proper
      // response comes back.
      client.unaryRpc(SimpleRequest.getDefaultInstance());
    } catch (StatusRuntimeException e) {
      fail("Find error: " + e.getMessage());
    }

    server.shutdown();
    channel.shutdown();
  }*/

  private static class SimpleServiceImpl extends SimpleServiceGrpc.SimpleServiceImplBase {
    @Override
    public void unaryRpc(SimpleRequest req, StreamObserver<SimpleResponse> respOb) {
      respOb.onNext(SimpleResponse.getDefaultInstance());
      respOb.onCompleted();
    }
  }
}
