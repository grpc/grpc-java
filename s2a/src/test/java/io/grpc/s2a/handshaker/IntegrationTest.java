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

package io.grpc.s2a.handshaker;

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.SECONDS;

import io.grpc.ChannelCredentials;
import io.grpc.Grpc;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerCredentials;
import io.grpc.TlsServerCredentials;
import io.grpc.benchmarks.Utils;
import io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.s2a.MtlsToS2AChannelCredentials;
import io.grpc.s2a.S2AChannelCredentials;
import io.grpc.s2a.handshaker.FakeS2AServer;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.protobuf.SimpleRequest;
import io.grpc.testing.protobuf.SimpleResponse;
import io.grpc.testing.protobuf.SimpleServiceGrpc;
import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.OpenSslSessionContext;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
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

  private static final String CERT_CHAIN =
      "-----BEGIN CERTIFICATE-----\n"
        + "MIICkDCCAjagAwIBAgIUSAtcrPhNNs1zxv51lIfGOVtkw6QwCgYIKoZIzj0EAwIw\n"
        + "QTEXMBUGA1UECgwOc2VjdXJpdHktcmVhbG0xEDAOBgNVBAsMB2NvbnRleHQxFDAS\n"
        + "BgorBgEEAdZ5AggBDAQyMDIyMCAXDTIzMDcxNDIyMzYwNFoYDzIwNTAxMTI5MjIz\n"
        + "NjA0WjARMQ8wDQYDVQQDDAZ1bnVzZWQwWTATBgcqhkjOPQIBBggqhkjOPQMBBwNC\n"
        + "AAQGFlJpLxJMh4HuUm0DKjnUF7larH3tJvroQ12xpk+pPKQepn4ILoq9lZ8Xd3jz\n"
        + "U98eDRXG5f4VjnX98DDHE4Ido4IBODCCATQwDgYDVR0PAQH/BAQDAgeAMCAGA1Ud\n"
        + "JQEB/wQWMBQGCCsGAQUFBwMCBggrBgEFBQcDATAMBgNVHRMBAf8EAjAAMIGxBgNV\n"
        + "HREBAf8EgaYwgaOGSnNwaWZmZTovL3NpZ25lci1yb2xlLmNvbnRleHQuc2VjdXJp\n"
        + "dHktcmVhbG0ucHJvZC5nb29nbGUuY29tL3JvbGUvbGVhZi1yb2xlgjNzaWduZXIt\n"
        + "cm9sZS5jb250ZXh0LnNlY3VyaXR5LXJlYWxtLnByb2Quc3BpZmZlLmdvb2eCIGZx\n"
        + "ZG4tb2YtdGhlLW5vZGUucHJvZC5nb29nbGUuY29tMB0GA1UdDgQWBBSWSd5Fw6dI\n"
        + "TGpt0m1Uxwf0iKqebzAfBgNVHSMEGDAWgBRm5agVVdpWfRZKM7u6OMuzHhqPcDAK\n"
        + "BggqhkjOPQQDAgNIADBFAiB0sjRPSYy2eFq8Y0vQ8QN4AZ2NMajskvxnlifu7O4U\n"
        + "RwIhANTh5Fkyx2nMYFfyl+W45dY8ODTw3HnlZ4b51hTAdkWl\n"
        + "-----END CERTIFICATE-----\n"
        + "-----BEGIN CERTIFICATE-----\n"
        + "MIICQjCCAeigAwIBAgIUKxXRDlnWXefNV5lj5CwhDuXEq7MwCgYIKoZIzj0EAwIw\n"
        + "OzEXMBUGA1UECgwOc2VjdXJpdHktcmVhbG0xEDAOBgNVBAsMB2NvbnRleHQxDjAM\n"
        + "BgNVBAMMBTEyMzQ1MCAXDTIzMDcxNDIyMzYwNFoYDzIwNTAxMTI5MjIzNjA0WjBB\n"
        + "MRcwFQYDVQQKDA5zZWN1cml0eS1yZWFsbTEQMA4GA1UECwwHY29udGV4dDEUMBIG\n"
        + "CisGAQQB1nkCCAEMBDIwMjIwWTATBgcqhkjOPQIBBggqhkjOPQMBBwNCAAT/Zu7x\n"
        + "UYVyg+T/vg2H+y4I6t36Kc4qxD0eqqZjRLYBVKkUQHxBqc14t0DpoROMYQCNd4DF\n"
        + "pcxv/9m6DaJbRk6Ao4HBMIG+MA4GA1UdDwEB/wQEAwIBBjASBgNVHRMBAf8ECDAG\n"
        + "AQH/AgEBMFgGA1UdHgEB/wROMEygSjA1gjNzaWduZXItcm9sZS5jb250ZXh0LnNl\n"
        + "Y3VyaXR5LXJlYWxtLnByb2Quc3BpZmZlLmdvb2cwEYIPcHJvZC5nb29nbGUuY29t\n"
        + "MB0GA1UdDgQWBBRm5agVVdpWfRZKM7u6OMuzHhqPcDAfBgNVHSMEGDAWgBQcjNAh\n"
        + "SCHTj+BW8KrzSSLo2ASEgjAKBggqhkjOPQQDAgNIADBFAiEA6KyGd9VxXDZceMZG\n"
        + "IsbC40rtunFjLYI0mjZw9RcRWx8CIHCIiIHxafnDaCi+VB99NZfzAdu37g6pJptB\n"
        + "gjIY71MO\n"
        + "-----END CERTIFICATE-----\n"
        + "-----BEGIN CERTIFICATE-----\n"
        + "MIICODCCAd6gAwIBAgIUXtZECORWRSKnS9rRTJYkiALUXswwCgYIKoZIzj0EAwIw\n"
        + "NzEXMBUGA1UECgwOc2VjdXJpdHktcmVhbG0xDTALBgNVBAsMBHJvb3QxDTALBgNV\n"
        + "BAMMBDEyMzQwIBcNMjMwNzE0MjIzNjA0WhgPMjA1MDExMjkyMjM2MDRaMDsxFzAV\n"
        + "BgNVBAoMDnNlY3VyaXR5LXJlYWxtMRAwDgYDVQQLDAdjb250ZXh0MQ4wDAYDVQQD\n"
        + "DAUxMjM0NTBZMBMGByqGSM49AgEGCCqGSM49AwEHA0IABAycVTZrjockbpD59f1a\n"
        + "4l1SNL7nSyXz66Guz4eDveQqLmaMBg7vpACfO4CtiAGnolHEffuRtSkdM434m5En\n"
        + "bXCjgcEwgb4wDgYDVR0PAQH/BAQDAgEGMBIGA1UdEwEB/wQIMAYBAf8CAQIwWAYD\n"
        + "VR0eAQH/BE4wTKBKMDWCM3NpZ25lci1yb2xlLmNvbnRleHQuc2VjdXJpdHktcmVh\n"
        + "bG0ucHJvZC5zcGlmZmUuZ29vZzARgg9wcm9kLmdvb2dsZS5jb20wHQYDVR0OBBYE\n"
        + "FByM0CFIIdOP4FbwqvNJIujYBISCMB8GA1UdIwQYMBaAFMX+vebuj/lYfYEC23IA\n"
        + "8HoIW0HsMAoGCCqGSM49BAMCA0gAMEUCIQCfxeXEBd7UPmeImT16SseCRu/6cHxl\n"
        + "kTDsq9sKZ+eXBAIgA+oViAVOUhUQO1/6Mjlczg8NmMy2vNtG4V/7g9dMMVU=\n"
        + "-----END CERTIFICATE-----";
  private static final String ROOT_PEM =
      "-----BEGIN CERTIFICATE-----\n"
        + "MIIBtTCCAVqgAwIBAgIUbAe+8OocndQXRBCElLBxBSdfdV8wCgYIKoZIzj0EAwIw\n"
        + "NzEXMBUGA1UECgwOc2VjdXJpdHktcmVhbG0xDTALBgNVBAsMBHJvb3QxDTALBgNV\n"
        + "BAMMBDEyMzQwIBcNMjMwNzE0MjIzNjA0WhgPMjA1MDExMjkyMjM2MDRaMDcxFzAV\n"
        + "BgNVBAoMDnNlY3VyaXR5LXJlYWxtMQ0wCwYDVQQLDARyb290MQ0wCwYDVQQDDAQx\n"
        + "MjM0MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEaMY2tBW5r1t0+vhayz0ZoGMF\n"
        + "boX/ZmmCmIh0iTWg4madvwNOh74CMVVvDUlXZcuVqZ3vVIX/a7PTFVqUwQlKW6NC\n"
        + "MEAwDgYDVR0PAQH/BAQDAgGGMA8GA1UdEwEB/wQFMAMBAf8wHQYDVR0OBBYEFMX+\n"
        + "vebuj/lYfYEC23IA8HoIW0HsMAoGCCqGSM49BAMCA0kAMEYCIQDETd27nsUTXKWY\n"
        + "CiOno78O09gK95NoTkPU5e2chJYMqAIhALYFAyh7PU5xgFQsN9hiqgsHUc5/pmBG\n"
        + "BGjJ1iz8rWGJ\n"
        + "-----END CERTIFICATE-----";
  private static final String PRIVATE_KEY =
      "-----BEGIN PRIVATE KEY-----\n"
        + "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgqA2U0ld1OOHLMXWf\n"
        + "uyN4GSaqhhudEIaKkll3rdIq0M+hRANCAAQGFlJpLxJMh4HuUm0DKjnUF7larH3t\n"
        + "JvroQ12xpk+pPKQepn4ILoq9lZ8Xd3jzU98eDRXG5f4VjnX98DDHE4Id\n"
        + "-----END PRIVATE KEY-----";

  private String s2aAddress;
  private int s2aPort;
  private Server s2aServer;
  private String s2aDelayAddress;
  private int s2aDelayPort;
  private Server s2aDelayServer;
  private String mtlsS2AAddress;
  private int mtlsS2APort;
  private Server mtlsS2AServer;
  private int serverPort;
  private String serverAddress;
  private Server server;

  @Before
  public void setUp() throws Exception {
    s2aPort = Utils.pickUnusedPort();
    s2aAddress = "localhost:" + s2aPort;
    s2aServer = ServerBuilder.forPort(s2aPort).addService(new FakeS2AServer()).build();
    logger.info("S2A service listening on localhost:" + s2aPort);
    s2aServer.start();

    mtlsS2APort = Utils.pickUnusedPort();
    mtlsS2AAddress = "localhost:" + mtlsS2APort;
    File s2aCert = new File("src/test/resources/server_cert.pem");
    File s2aKey = new File("src/test/resources/server_key.pem");
    File rootCert = new File("src/test/resources/root_cert.pem");
    ServerCredentials s2aCreds =
        TlsServerCredentials.newBuilder()
            .keyManager(s2aCert, s2aKey)
            .trustManager(rootCert)
            .clientAuth(TlsServerCredentials.ClientAuth.REQUIRE)
            .build();
    mtlsS2AServer =
        NettyServerBuilder.forPort(mtlsS2APort, s2aCreds).addService(new FakeS2AServer()).build();
    logger.info("mTLS S2A service listening on localhost:" + mtlsS2APort);
    mtlsS2AServer.start();

    s2aDelayPort = Utils.pickUnusedPort();
    s2aDelayAddress = "localhost:" + s2aDelayPort;
    s2aDelayServer = ServerBuilder.forPort(s2aDelayPort).addService(new FakeS2AServer()).build();

    serverPort = Utils.pickUnusedPort();
    serverAddress = "localhost:" + serverPort;
    server =
        NettyServerBuilder.forPort(serverPort)
            .addService(new SimpleServiceImpl())
            .sslContext(buildSslContext())
            .build();
    logger.info("Simple Service listening on localhost:" + serverPort);
    server.start();
  }

  @After
  public void tearDown() throws Exception {
    server.awaitTermination(10, SECONDS);
    server.shutdown();
    s2aServer.awaitTermination(10, SECONDS);
    s2aServer.shutdown();
    s2aDelayServer.awaitTermination(10, SECONDS);
    s2aDelayServer.shutdown();
    mtlsS2AServer.awaitTermination(10, SECONDS);
    mtlsS2AServer.shutdown();
  }

  @Test
  public void clientCommunicateUsingS2ACredentials_succeeds() throws Exception {
    ExecutorService executor = Executors.newSingleThreadExecutor();
    ChannelCredentials credentials =
        S2AChannelCredentials.newBuilder(s2aAddress).setLocalSpiffeId("test-spiffe-id").build();
    ManagedChannel channel =
        Grpc.newChannelBuilder(serverAddress, credentials).executor(executor).build();

    assertThat(doUnaryRpc(executor, channel)).isTrue();
  }

  @Test
  public void clientCommunicateUsingS2ACredentialsNoLocalIdentity_succeeds() throws Exception {
    ExecutorService executor = Executors.newSingleThreadExecutor();
    ChannelCredentials credentials = S2AChannelCredentials.newBuilder(s2aAddress).build();
    ManagedChannel channel =
        Grpc.newChannelBuilder(serverAddress, credentials).executor(executor).build();

    assertThat(doUnaryRpc(executor, channel)).isTrue();
  }

  @Test
  public void clientCommunicateUsingMtlsToS2ACredentials_succeeds() throws Exception {
    ExecutorService executor = Executors.newSingleThreadExecutor();
    ChannelCredentials credentials =
        MtlsToS2AChannelCredentials.newBuilder(
                /* s2aAddress= */ mtlsS2AAddress,
                /* privateKeyPath= */ "src/test/resources/client_key.pem",
                /* certChainPath= */ "src/test/resources/client_cert.pem",
                /* trustBundlePath= */ "src/test/resources/root_cert.pem")
            .build()
            .setLocalSpiffeId("test-spiffe-id")
            .build();
    ManagedChannel channel =
        Grpc.newChannelBuilder(serverAddress, credentials).executor(executor).build();

    assertThat(doUnaryRpc(executor, channel)).isTrue();
  }

  @Test
  public void clientCommunicateUsingS2ACredentials_s2AdelayStart_succeeds() throws Exception {
    DoUnaryRpc doUnaryRpc = new DoUnaryRpc();
    doUnaryRpc.start();
    Thread.sleep(2000);
    s2aDelayServer.start();
    doUnaryRpc.join();
  }

  private class DoUnaryRpc extends Thread {
    @Override
    public void run() {
      ExecutorService executor = Executors.newSingleThreadExecutor();
      ChannelCredentials credentials = S2AChannelCredentials.newBuilder(s2aDelayAddress).build();
      ManagedChannel channel =
          Grpc.newChannelBuilder(serverAddress, credentials).executor(executor).build();
      boolean result = false;
      try {
        result = doUnaryRpc(executor, channel);
      } catch (InterruptedException e) {
        logger.log(Level.SEVERE, "Failed to do unary rpc", e);
        result = false;
      }
      assertThat(result).isTrue();
    }
  }

  public static boolean doUnaryRpc(ExecutorService executor, ManagedChannel channel)
      throws InterruptedException {
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
      executor.shutdown();
      executor.awaitTermination(1, SECONDS);
    }
  }

  private static SslContext buildSslContext() throws SSLException {
    SslContextBuilder sslServerContextBuilder =
        SslContextBuilder.forServer(
            new ByteArrayInputStream(CERT_CHAIN.getBytes(UTF_8)),
            new ByteArrayInputStream(PRIVATE_KEY.getBytes(UTF_8)));
    SslContext sslServerContext =
        GrpcSslContexts.configure(sslServerContextBuilder, SslProvider.OPENSSL)
            .protocols("TLSv1.3", "TLSv1.2")
            .trustManager(new ByteArrayInputStream(ROOT_PEM.getBytes(UTF_8)))
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