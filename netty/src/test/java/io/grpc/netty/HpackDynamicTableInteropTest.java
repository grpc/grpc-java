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
import io.grpc.ServerInterceptors;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.protobuf.SimpleRequest;
import io.grpc.testing.protobuf.SimpleResponse;
import io.grpc.testing.protobuf.SimpleServiceGrpc;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class HpackDynamicTableInteropTest {
  private static final int RPC_COUNT = 10;
  private static final Metadata.Key<String> REQUEST_METADATA_KEY =
      Metadata.Key.of("x-hpack-request", Metadata.ASCII_STRING_MARSHALLER);
  private static final Metadata.Key<String> RESPONSE_METADATA_KEY =
      Metadata.Key.of("x-hpack-response", Metadata.ASCII_STRING_MARSHALLER);
  private static final String REQUEST_METADATA_VALUE = "repeated-request-metadata-value";
  private static final String RESPONSE_METADATA_VALUE = "repeated-response-metadata-value";

  @Parameters(name = "clientTableSize={0}, serverTableSize={1}")
  public static Iterable<Object[]> data() {
    return Arrays.asList(new Object[][] {
        {4096, 4096}, {4096, 8192}, {8192, 4096}, {8192, 8192},
        {0, 4096}, {4096, 0}, {0, 0}
    });
  }

  @Parameter(0)
  public int clientTableSize;

  @Parameter(1)
  public int serverTableSize;

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
  public void unaryRpcInteroperates() throws Exception {
    Metadata responseMetadata = new Metadata();
    responseMetadata.put(RESPONSE_METADATA_KEY, RESPONSE_METADATA_VALUE);
    NettyServerBuilder serverBuilder = NettyServerBuilder.forPort(0)
        .addService(
            ServerInterceptors.intercept(
                new SimpleServiceImpl(),
                MetadataUtils.newAttachMetadataServerInterceptor(responseMetadata)));
    serverBuilder.hpackDynamicTableSize(serverTableSize);
    server = serverBuilder.build().start();

    NettyChannelBuilder channelBuilder = NettyChannelBuilder
        .forAddress("localhost", server.getPort())
        .usePlaintext();
    channelBuilder.hpackDynamicTableSize(clientTableSize);
    channel = channelBuilder.build();

    Metadata requestMetadata = new Metadata();
    requestMetadata.put(REQUEST_METADATA_KEY, REQUEST_METADATA_VALUE);
    AtomicReference<Metadata> headersCapture = new AtomicReference<>();
    AtomicReference<Metadata> trailersCapture = new AtomicReference<>();
    SimpleServiceGrpc.SimpleServiceBlockingStub stub =
        SimpleServiceGrpc.newBlockingStub(channel)
            .withInterceptors(
                MetadataUtils.newAttachHeadersInterceptor(requestMetadata),
                MetadataUtils.newCaptureMetadataInterceptor(headersCapture, trailersCapture));

    for (int i = 0; i < RPC_COUNT; i++) {
      SimpleResponse response =
          stub.withDeadlineAfter(10, TimeUnit.SECONDS)
              .unaryRpc(SimpleRequest.getDefaultInstance());
      assertEquals(SimpleResponse.getDefaultInstance(), response);
      assertNotNull(headersCapture.get());
      assertEquals(RESPONSE_METADATA_VALUE, headersCapture.get().get(RESPONSE_METADATA_KEY));
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
