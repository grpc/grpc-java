/*
 * Copyright 2024 The gRPC Authors
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

package io.grpc.s2a.internal.handshaker;

import static com.google.common.truth.Truth.assertThat;
import static java.util.concurrent.TimeUnit.SECONDS;

import io.grpc.Channel;
import io.grpc.ChannelCredentials;
import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerCredentials;
import io.grpc.TlsChannelCredentials;
import io.grpc.TlsServerCredentials;
import io.grpc.benchmarks.Utils;
import io.grpc.internal.ObjectPool;
import io.grpc.internal.SharedResourcePool;
import io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.s2a.S2AChannelCredentials;
import io.grpc.s2a.internal.channel.S2AHandshakerServiceChannel;
import io.grpc.s2a.internal.handshaker.FakeS2AServer;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.protobuf.SimpleRequest;
import io.grpc.testing.protobuf.SimpleResponse;
import io.grpc.testing.protobuf.SimpleServiceGrpc;
import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.OpenSslSessionContext;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import java.io.InputStream;
import java.util.concurrent.FutureTask;
import java.util.logging.Logger;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSessionContext;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class IntegrationTest {
  private static final Logger logger = Logger.getLogger(FakeS2AServer.class.getName());
  private String s2aAddress;
  private Server s2aServer;
  private String s2aDelayAddress;
  private Server s2aDelayServer;
  private String mtlsS2AAddress;
  private Server mtlsS2AServer;
  private String serverAddress;
  private Server server;

  @Before
  public void setUp() throws Exception {
    s2aServer = ServerBuilder.forPort(0).addService(new FakeS2AServer()).build().start();
    int s2aPort = s2aServer.getPort();
    s2aAddress = "localhost:" + s2aPort;
    logger.info("S2A service listening on localhost:" + s2aPort);
    ClassLoader classLoader = IntegrationTest.class.getClassLoader();
    InputStream s2aCert = classLoader.getResourceAsStream("server_cert.pem");
    InputStream s2aKey = classLoader.getResourceAsStream("server_key.pem");
    InputStream rootCert = classLoader.getResourceAsStream("root_cert.pem");
    ServerCredentials s2aCreds =
        TlsServerCredentials.newBuilder()
            .keyManager(s2aCert, s2aKey)
            .trustManager(rootCert)
            .clientAuth(TlsServerCredentials.ClientAuth.REQUIRE)
            .build();
    mtlsS2AServer = NettyServerBuilder.forPort(0, s2aCreds).addService(new FakeS2AServer()).build();
    mtlsS2AServer.start();
    int mtlsS2APort = mtlsS2AServer.getPort();
    mtlsS2AAddress = "localhost:" + mtlsS2APort;
    logger.info("mTLS S2A service listening on localhost:" + mtlsS2APort);

    int s2aDelayPort = Utils.pickUnusedPort();
    s2aDelayAddress = "localhost:" + s2aDelayPort;
    s2aDelayServer = ServerBuilder.forPort(s2aDelayPort).addService(new FakeS2AServer()).build();

    server =
        NettyServerBuilder.forPort(0)
            .addService(new SimpleServiceImpl())
            .sslContext(buildSslContext())
            .build()
            .start();
    int serverPort = server.getPort();
    serverAddress = "localhost:" + serverPort;
    logger.info("Simple Service listening on localhost:" + serverPort);
  }

  @After
  public void tearDown() throws Exception {
    server.shutdown();
    s2aServer.shutdown();
    s2aDelayServer.shutdown();
    mtlsS2AServer.shutdown();

    server.awaitTermination(10, SECONDS);
    s2aServer.awaitTermination(10, SECONDS);
    s2aDelayServer.awaitTermination(10, SECONDS);
    mtlsS2AServer.awaitTermination(10, SECONDS);
  }

  @Test
  public void clientCommunicateUsingS2ACredentials_succeeds() throws Exception {
    ChannelCredentials credentials =
        S2AChannelCredentials.newBuilder(s2aAddress, InsecureChannelCredentials.create())
            .setLocalSpiffeId("test-spiffe-id").build();
    ManagedChannel channel = Grpc.newChannelBuilder(serverAddress, credentials).build();

    assertThat(doUnaryRpc(channel)).isTrue();
  }

  @Test
  public void clientCommunicateUsingS2ACredentialsNoLocalIdentity_succeeds() throws Exception {
    ChannelCredentials credentials = S2AChannelCredentials.newBuilder(s2aAddress,
        InsecureChannelCredentials.create()).build();
    ManagedChannel channel = Grpc.newChannelBuilder(serverAddress, credentials).build();

    assertThat(doUnaryRpc(channel)).isTrue();
  }

  @Test
  public void clientCommunicateUsingS2ACredentialsSucceeds_verifyStreamToS2AClosed()
      throws Exception {
    ObjectPool<Channel> s2aChannelPool =
          SharedResourcePool.forResource(
              S2AHandshakerServiceChannel.getChannelResource(s2aAddress,
              InsecureChannelCredentials.create()));
    Channel ch = s2aChannelPool.getObject();
    S2AStub stub = S2AStub.newInstance(S2AServiceGrpc.newStub(ch));
    ChannelCredentials credentials =
        S2AChannelCredentials.newBuilder(s2aAddress, InsecureChannelCredentials.create())
            .setLocalSpiffeId("test-spiffe-id").setStub(stub).build();
    ManagedChannel channel = Grpc.newChannelBuilder(serverAddress, credentials).build();

    s2aChannelPool.returnObject(ch);
    assertThat(doUnaryRpc(channel)).isTrue();
    assertThat(stub.isClosed()).isTrue();
  }

  @Test
  public void clientCommunicateUsingMtlsToS2ACredentials_succeeds() throws Exception {
    ClassLoader classLoader = IntegrationTest.class.getClassLoader();
    InputStream privateKey = classLoader.getResourceAsStream("client_key.pem");
    InputStream certChain = classLoader.getResourceAsStream("client_cert.pem");
    InputStream trustBundle = classLoader.getResourceAsStream("root_cert.pem");
    ChannelCredentials s2aChannelCredentials =
        TlsChannelCredentials.newBuilder()
          .keyManager(certChain, privateKey)
          .trustManager(trustBundle)
          .build();

    ChannelCredentials credentials =
        S2AChannelCredentials.newBuilder(mtlsS2AAddress, s2aChannelCredentials)
          .setLocalSpiffeId("test-spiffe-id")
          .build();
    ManagedChannel channel = Grpc.newChannelBuilder(serverAddress, credentials).build();

    assertThat(doUnaryRpc(channel)).isTrue();
  }

  @Test
  public void clientCommunicateUsingS2ACredentials_s2AdelayStart_succeeds() throws Exception {
    ChannelCredentials credentials = S2AChannelCredentials.newBuilder(s2aDelayAddress,
        InsecureChannelCredentials.create()).build();
    ManagedChannel channel = Grpc.newChannelBuilder(serverAddress, credentials).build();

    FutureTask<Boolean> rpc = new FutureTask<>(() -> doUnaryRpc(channel));
    new Thread(rpc).start();
    Thread.sleep(2000);
    s2aDelayServer.start();
    assertThat(rpc.get()).isTrue();
  }

  public static boolean doUnaryRpc(ManagedChannel channel) throws InterruptedException {
    try {
      SimpleServiceGrpc.SimpleServiceBlockingStub stub =
          SimpleServiceGrpc.newBlockingStub(channel);
      SimpleResponse resp = stub.unaryRpc(SimpleRequest.newBuilder()
                                                       .setRequestMessage("S2A team")
                                                       .build());
      if (!resp.getResponseMessage().equals("Hello, S2A team!")) {
        logger.info(
            "Received unexpected message from the Simple Service: " + resp.getResponseMessage());
        throw new RuntimeException();
      } else {
        System.out.println(
            "We received this message from the Simple Service: " + resp.getResponseMessage());
        return true;
      }
    } finally {
      channel.shutdown();
      channel.awaitTermination(1, SECONDS);
    }
  }

  private static SslContext buildSslContext() throws SSLException {
    ClassLoader classLoader = IntegrationTest.class.getClassLoader();
    InputStream privateKey = classLoader.getResourceAsStream("leaf_key_ec.pem");
    InputStream rootCert = classLoader.getResourceAsStream("root_cert_ec.pem");
    InputStream certChain = classLoader.getResourceAsStream("cert_chain_ec.pem");
    SslContextBuilder sslServerContextBuilder =
          SslContextBuilder.forServer(certChain, privateKey);
    SslContext sslServerContext =
        GrpcSslContexts.configure(sslServerContextBuilder, SslProvider.OPENSSL)
            .protocols("TLSv1.3", "TLSv1.2")
            .trustManager(rootCert)
            .clientAuth(ClientAuth.REQUIRE)
            .build();

    // Enable TLS resumption. This requires using the OpenSSL provider, since the JDK provider does
    // not allow a server to send session tickets.
    SSLSessionContext sslSessionContext = sslServerContext.sessionContext();
    if (!(sslSessionContext instanceof OpenSslSessionContext)) {
      throw new SSLException("sslSessionContext does not use OpenSSL.");
    }
    OpenSslSessionContext openSslSessionContext = (OpenSslSessionContext) sslSessionContext;
    // Calling {@code setTicketKeys} without specifying any keys means that the SSL libraries will
    // handle the generation of the resumption master secret.
    openSslSessionContext.setTicketKeys();

    return sslServerContext;
  }

  public static class SimpleServiceImpl extends SimpleServiceGrpc.SimpleServiceImplBase {
    @Override
    public void unaryRpc(SimpleRequest request, StreamObserver<SimpleResponse> observer) {
      observer.onNext(
          SimpleResponse.newBuilder()
                      .setResponseMessage("Hello, " + request.getRequestMessage() + "!")
                      .build());
      observer.onCompleted();
    }
  }
}
