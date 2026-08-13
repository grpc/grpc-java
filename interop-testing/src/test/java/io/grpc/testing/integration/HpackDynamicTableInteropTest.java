/*
 * Copyright 2026 The gRPC Authors
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

package io.grpc.testing.integration;

import static com.google.common.truth.Truth.assertThat;

import io.grpc.Attributes;
import io.grpc.InsecureServerCredentials;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.ServerTransportFilter;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.okhttp.OkHttpChannelBuilder;
import io.grpc.okhttp.OkHttpServerBuilder;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.GrpcCleanupRule;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Interoperability tests for configuring the HPACK dynamic table. */
@RunWith(JUnit4.class)
public final class HpackDynamicTableInteropTest {
  private static final int CALL_COUNT = 3;
  private static final String REQUEST_METADATA_VALUE = "repeated-request-metadata-value";
  private static final String RESPONSE_METADATA_VALUE = "repeated-response-metadata-value";
  private static final Metadata.Key<String> REQUEST_METADATA_KEY =
      Metadata.Key.of("hpack-request-metadata", Metadata.ASCII_STRING_MARSHALLER);
  private static final Metadata.Key<String> RESPONSE_METADATA_KEY =
      Metadata.Key.of("hpack-response-metadata", Metadata.ASCII_STRING_MARSHALLER);
  private static final EmptyProtos.Empty EMPTY = EmptyProtos.Empty.getDefaultInstance();

  @Rule public final GrpcCleanupRule grpcCleanup = new GrpcCleanupRule();

  private final AtomicInteger serverTransportCount = new AtomicInteger();
  private final AtomicInteger requestsWithExpectedMetadata = new AtomicInteger();

  @Test
  public void defaultOkHttpClient_interoperatesWithZeroTableNettyServer() throws Exception {
    Server server = startServer(
        NettyServerBuilder.forPort(0, InsecureServerCredentials.create())
            .hpackDynamicTableSize(0));
    ManagedChannel channel = grpcCleanup.register(
        OkHttpChannelBuilder.forAddress("localhost", server.getPort())
            .usePlaintext()
            .build());

    makeRepeatedCalls(channel);
  }

  @Test
  public void zeroTableNettyClient_interoperatesWithDefaultOkHttpServer() throws Exception {
    Server server = startServer(
        OkHttpServerBuilder.forPort(0, InsecureServerCredentials.create()));
    ManagedChannel channel = grpcCleanup.register(
        NettyChannelBuilder.forAddress("localhost", server.getPort())
            .usePlaintext()
            .hpackDynamicTableSize(0)
            .build());

    makeRepeatedCalls(channel);
  }

  private Server startServer(ServerBuilder<?> serverBuilder) throws Exception {
    Metadata responseMetadata = new Metadata();
    responseMetadata.put(RESPONSE_METADATA_KEY, RESPONSE_METADATA_VALUE);

    Server server = serverBuilder
        .addTransportFilter(new ServerTransportFilter() {
          @Override
          public Attributes transportReady(Attributes transportAttrs) {
            serverTransportCount.incrementAndGet();
            return transportAttrs;
          }
        })
        .addService(ServerInterceptors.intercept(
            new TestService(),
            new ServerInterceptor() {
              @Override
              public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                  ServerCall<ReqT, RespT> call,
                  Metadata headers,
                  ServerCallHandler<ReqT, RespT> next) {
                if (REQUEST_METADATA_VALUE.equals(headers.get(REQUEST_METADATA_KEY))) {
                  requestsWithExpectedMetadata.incrementAndGet();
                }
                return next.startCall(call, headers);
              }
            },
            MetadataUtils.newAttachMetadataServerInterceptor(responseMetadata)))
        .build();
    return grpcCleanup.register(server).start();
  }

  private void makeRepeatedCalls(ManagedChannel channel) {
    Metadata requestMetadata = new Metadata();
    requestMetadata.put(REQUEST_METADATA_KEY, REQUEST_METADATA_VALUE);
    AtomicReference<Metadata> responseHeaders = new AtomicReference<>();
    AtomicReference<Metadata> responseTrailers = new AtomicReference<>();
    TestServiceGrpc.TestServiceBlockingStub stub = TestServiceGrpc.newBlockingStub(channel)
        .withInterceptors(
            MetadataUtils.newAttachHeadersInterceptor(requestMetadata),
            MetadataUtils.newCaptureMetadataInterceptor(responseHeaders, responseTrailers));

    for (int i = 0; i < CALL_COUNT; i++) {
      assertThat(stub.withDeadlineAfter(10, TimeUnit.SECONDS).emptyCall(EMPTY)).isEqualTo(EMPTY);
      assertThat(responseHeaders.get()).isNotNull();
      assertThat(responseHeaders.get().get(RESPONSE_METADATA_KEY))
          .isEqualTo(RESPONSE_METADATA_VALUE);
    }
    assertThat(requestsWithExpectedMetadata.get()).isEqualTo(CALL_COUNT);
    assertThat(serverTransportCount.get()).isEqualTo(1);
  }

  private static final class TestService extends TestServiceGrpc.TestServiceImplBase {
    @Override
    public void emptyCall(
        EmptyProtos.Empty request, StreamObserver<EmptyProtos.Empty> responseObserver) {
      responseObserver.onNext(EMPTY);
      responseObserver.onCompleted();
    }
  }
}
