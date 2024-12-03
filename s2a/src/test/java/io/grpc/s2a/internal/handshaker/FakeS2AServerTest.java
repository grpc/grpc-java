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

import static com.google.common.truth.extensions.proto.ProtoTruth.assertThat;
import static java.util.concurrent.TimeUnit.SECONDS;

import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.SettableFuture;
import com.google.protobuf.ByteString;
import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.s2a.internal.handshaker.ValidatePeerCertificateChainReq.VerificationMode;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link FakeS2AServer}. */
@RunWith(JUnit4.class)
public final class FakeS2AServerTest {
  private static final Logger logger = Logger.getLogger(FakeS2AServerTest.class.getName());

  private static final ImmutableList<ByteString> FAKE_CERT_DER_CHAIN =
      ImmutableList.of(ByteString.copyFrom("fake-der-chain".getBytes(StandardCharsets.US_ASCII)));
  private String serverAddress;
  private Server fakeS2AServer;

  @Before
  public void setUp() throws Exception {
    fakeS2AServer = ServerBuilder.forPort(0).addService(new FakeS2AServer()).build();
    fakeS2AServer.start();
    serverAddress = String.format("localhost:%d", fakeS2AServer.getPort());
  }

  @After
  public void tearDown() throws Exception {
    fakeS2AServer.shutdown();
    fakeS2AServer.awaitTermination(10, SECONDS);
  }

  @Test
  public void callS2AServerOnce_getTlsConfiguration_returnsValidResult()
      throws InterruptedException, IOException, java.util.concurrent.ExecutionException {
    ExecutorService executor = Executors.newSingleThreadExecutor();
    logger.info("Client connecting to: " + serverAddress);
    ManagedChannel channel =
        Grpc.newChannelBuilder(serverAddress, InsecureChannelCredentials.create())
            .executor(executor)
            .build();
    SettableFuture<SessionResp> respFuture = SettableFuture.create();
    try {
      S2AServiceGrpc.S2AServiceStub asyncStub = S2AServiceGrpc.newStub(channel);
      StreamObserver<SessionReq> requestObserver =
          asyncStub.setUpSession(
              new StreamObserver<SessionResp>() {
                SessionResp recvResp;
                @Override
                public void onNext(SessionResp resp) {
                  recvResp = resp;
                }

                @Override
                public void onError(Throwable t) {
                  respFuture.setException(t);
                }

                @Override
                public void onCompleted() {
                  respFuture.set(recvResp);
                }
              });
      try {
        requestObserver.onNext(
            SessionReq.newBuilder()
                .setGetTlsConfigurationReq(
                    GetTlsConfigurationReq.newBuilder()
                        .setConnectionSide(ConnectionSide.CONNECTION_SIDE_CLIENT))
                .build());
      } catch (RuntimeException e) {
        // Cancel the RPC.
        requestObserver.onError(e);
        throw e;
      }
      // Mark the end of requests.
      requestObserver.onCompleted();
      // Wait for receiving to happen.
    } finally {
      channel.shutdown();
      channel.awaitTermination(1, SECONDS);
      executor.shutdown();
      executor.awaitTermination(1, SECONDS);
    }

    String leafCertString = "";
    String cert2String = "";
    String cert1String = "";
    ClassLoader classLoader = FakeS2AServerTest.class.getClassLoader();
    try (
        InputStream leafCert = classLoader.getResourceAsStream("leaf_cert_ec.pem");
        InputStream cert2 = classLoader.getResourceAsStream("int_cert2_ec.pem");
        InputStream cert1 = classLoader.getResourceAsStream("int_cert1_ec.pem");
    ) {
      leafCertString = FakeWriter.convertInputStreamToString(leafCert);
      cert2String = FakeWriter.convertInputStreamToString(cert2);
      cert1String = FakeWriter.convertInputStreamToString(cert1);
    }

    SessionResp expected =
        SessionResp.newBuilder()
            .setGetTlsConfigurationResp(
                GetTlsConfigurationResp.newBuilder()
                    .setClientTlsConfiguration(
                        GetTlsConfigurationResp.ClientTlsConfiguration.newBuilder()
                            .addCertificateChain(leafCertString)
                            .addCertificateChain(cert1String)
                            .addCertificateChain(cert2String)
                            .setMinTlsVersion(TLSVersion.TLS_VERSION_1_3)
                            .setMaxTlsVersion(TLSVersion.TLS_VERSION_1_3)
                            .addCiphersuites(
                                Ciphersuite.CIPHERSUITE_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256)
                            .addCiphersuites(
                                Ciphersuite.CIPHERSUITE_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384)
                            .addCiphersuites(
                                Ciphersuite.CIPHERSUITE_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256)))
            .build();
    assertThat(respFuture.get()).ignoringRepeatedFieldOrder().isEqualTo(expected);
  }

  @Test
  public void callS2AServerOnce_validatePeerCertifiate_returnsValidResult()
      throws InterruptedException, java.util.concurrent.ExecutionException {
    ExecutorService executor = Executors.newSingleThreadExecutor();
    logger.info("Client connecting to: " + serverAddress);
    ManagedChannel channel =
        Grpc.newChannelBuilder(serverAddress, InsecureChannelCredentials.create())
            .executor(executor)
            .build();
    SettableFuture<SessionResp> respFuture = SettableFuture.create();
    try {
      S2AServiceGrpc.S2AServiceStub asyncStub = S2AServiceGrpc.newStub(channel);
      StreamObserver<SessionReq> requestObserver =
          asyncStub.setUpSession(
              new StreamObserver<SessionResp>() {
                private SessionResp recvResp;
                @Override
                public void onNext(SessionResp resp) {
                  recvResp = resp;
                }

                @Override
                public void onError(Throwable t) {
                  respFuture.setException(t);
                }

                @Override
                public void onCompleted() {
                  respFuture.set(recvResp);
                }
              });
      try {
        requestObserver.onNext(
            SessionReq.newBuilder()
                .setValidatePeerCertificateChainReq(
                    ValidatePeerCertificateChainReq.newBuilder()
                        .setMode(VerificationMode.UNSPECIFIED)
                        .setClientPeer(
                            ValidatePeerCertificateChainReq.ClientPeer.newBuilder()
                                .addAllCertificateChain(FAKE_CERT_DER_CHAIN)))
                .build());
      } catch (RuntimeException e) {
        // Cancel the RPC.
        requestObserver.onError(e);
        throw e;
      }
      // Mark the end of requests.
      requestObserver.onCompleted();
      // Wait for receiving to happen.
    } finally {
      channel.shutdown();
      channel.awaitTermination(1, SECONDS);
      executor.shutdown();
      executor.awaitTermination(1, SECONDS);
    }

    SessionResp expected =
        SessionResp.newBuilder()
            .setValidatePeerCertificateChainResp(
                ValidatePeerCertificateChainResp.newBuilder()
                    .setValidationResult(ValidatePeerCertificateChainResp.ValidationResult.SUCCESS))
            .build();
    assertThat(respFuture.get()).ignoringRepeatedFieldOrder().isEqualTo(expected);
  }

  @Test
  public void callS2AServerRepeatedly_returnsValidResult() throws InterruptedException {
    final int numberOfRequests = 10;
    ExecutorService executor = Executors.newSingleThreadExecutor();
    logger.info("Client connecting to: " + serverAddress);
    ManagedChannel channel =
        Grpc.newChannelBuilder(serverAddress, InsecureChannelCredentials.create())
            .executor(executor)
            .build();

    try {
      S2AServiceGrpc.S2AServiceStub asyncStub = S2AServiceGrpc.newStub(channel);
      CountDownLatch finishLatch = new CountDownLatch(1);
      StreamObserver<SessionReq> requestObserver =
          asyncStub.setUpSession(
              new StreamObserver<SessionResp>() {
                private int expectedNumberOfReplies = numberOfRequests;

                @Override
                public void onNext(SessionResp reply) {
                  System.out.println("Received a message from the S2AService service.");
                  expectedNumberOfReplies -= 1;
                }

                @Override
                public void onError(Throwable t) {
                  finishLatch.countDown();
                  if (expectedNumberOfReplies != 0) {
                    throw new RuntimeException(t);
                  }
                }
                
                @Override
                public void onCompleted() {
                  finishLatch.countDown();
                  if (expectedNumberOfReplies != 0) {
                    throw new RuntimeException();
                  }
                }
              });
      try {
        for (int i = 0; i < numberOfRequests; i++) {
          requestObserver.onNext(SessionReq.getDefaultInstance());
        }
      } catch (RuntimeException e) {
        // Cancel the RPC.
        requestObserver.onError(e);
        throw e;
      }
      // Mark the end of requests.
      requestObserver.onCompleted();
      // Wait for receiving to happen.
      if (!finishLatch.await(10, SECONDS)) {
        throw new RuntimeException();
      }
    } finally {
      channel.shutdown();
      channel.awaitTermination(1, SECONDS);
      executor.shutdown();
      executor.awaitTermination(1, SECONDS);
    }
  }

}