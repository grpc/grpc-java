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

package io.grpc.netty;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.protobuf.SimpleRequest;
import io.grpc.testing.protobuf.SimpleResponse;
import io.grpc.testing.protobuf.SimpleServiceGrpc;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class NeverIndexMetadataInteropTest {
  private static final int RPC_COUNT = 10;
  private static final Metadata.Key<String> NEVER_INDEXED_REQUEST_METADATA_KEY =
      Metadata.Key.of("x-hpack-never-indexed-request", Metadata.ASCII_STRING_MARSHALLER);

  private Server server;
  private ManagedChannel channel;

  @After
  public void tearDown() throws Exception {
    if (channel != null) {
      channel.shutdownNow();
      channel.awaitTermination(5, TimeUnit.SECONDS);
    }
    if (server != null) {
      server.shutdownNow();
      server.awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  @Test
  public void neverIndexedRequestMetadataInteroperates() throws Exception {
    AtomicReference<Metadata> requestMetadataCapture = new AtomicReference<>();
    server = NettyServerBuilder.forPort(0)
        .addService(
            ServerInterceptors.intercept(
                new SimpleServiceImpl(),
                new CapturingServerInterceptor(requestMetadataCapture)))
        .build()
        .start();

    channel = NettyChannelBuilder.forAddress("localhost", server.getPort())
        .usePlaintext()
        .neverIndexMetadataKey(NEVER_INDEXED_REQUEST_METADATA_KEY)
        .build();

    SimpleServiceGrpc.SimpleServiceBlockingStub baseStub =
        SimpleServiceGrpc.newBlockingStub(channel);
    for (int i = 0; i < RPC_COUNT; i++) {
      String neverIndexedValue = "high-cardinality-value-" + i;
      Metadata requestMetadata = new Metadata();
      requestMetadata.put(NEVER_INDEXED_REQUEST_METADATA_KEY, neverIndexedValue);

      SimpleResponse response =
          baseStub.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(requestMetadata))
              .withDeadlineAfter(10, TimeUnit.SECONDS)
              .unaryRpc(SimpleRequest.getDefaultInstance());

      assertEquals(SimpleResponse.getDefaultInstance(), response);
      assertNotNull(requestMetadataCapture.get());
      assertEquals(
          neverIndexedValue,
          requestMetadataCapture.get().get(NEVER_INDEXED_REQUEST_METADATA_KEY));
    }
  }

  private static final class CapturingServerInterceptor implements ServerInterceptor {
    private final AtomicReference<Metadata> requestMetadataCapture;

    CapturingServerInterceptor(AtomicReference<Metadata> requestMetadataCapture) {
      this.requestMetadataCapture = requestMetadataCapture;
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
        ServerCall<ReqT, RespT> call,
        Metadata headers,
        ServerCallHandler<ReqT, RespT> next) {
      requestMetadataCapture.set(headers);
      return next.startCall(call, headers);
    }
  }

  private static final class SimpleServiceImpl extends SimpleServiceGrpc.SimpleServiceImplBase {
    @Override
    public void unaryRpc(SimpleRequest request, StreamObserver<SimpleResponse> responseObserver) {
      responseObserver.onNext(SimpleResponse.getDefaultInstance());
      responseObserver.onCompleted();
    }
  }
}
