/*
 * Copyright 2016 The gRPC Authors
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

package io.grpc.examples.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import io.grpc.ClientInterceptors;
import io.grpc.ForwardingServerCall.SimpleForwardingServerCall;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCall.Listener;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.examples.helloworld.GreeterGrpc;
import io.grpc.examples.helloworld.GreeterGrpc.GreeterBlockingStub;
import io.grpc.examples.helloworld.GreeterGrpc.GreeterImplBase;
import io.grpc.examples.helloworld.HelloReply;
import io.grpc.examples.helloworld.HelloRequest;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.GrpcCleanupRule;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Unit tests for {@link MetadataClientInterceptor}.
 * For demonstrating how to write gRPC unit test only.
 * Not intended to provide a high code coverage or to test every major usecase.
 *
 * <p>For basic unit test examples see {@link io.grpc.examples.helloworld.HelloWorldClientTest} and
 * {@link io.grpc.examples.helloworld.HelloWorldServerTest}.
 */
@RunWith(JUnit4.class)
public class MetadataClientInterceptorTest {
  /**
   * This rule manages automatic graceful shutdown for the registered servers and channels at the
   * end of test.
   */
  @Rule
  public final GrpcCleanupRule grpcCleanup = new GrpcCleanupRule();

  private final BlockingQueue<Metadata> capturedClientHeaders = new LinkedBlockingQueue<>();

  @Test
  public void works() throws Exception {
    GreeterImplBase greeterImpl =
        new GreeterImplBase() {
          @Override
          public void sayHello(HelloRequest request, StreamObserver<HelloReply> responseObserver) {
            responseObserver.onNext(HelloReply.getDefaultInstance());
            responseObserver.onCompleted();
          }
        };
    ServerInterceptor serverInterceptor = new ServerInterceptor() {
        @Override
        public <ReqT, RespT> Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
          capturedClientHeaders.add(headers);
          ServerCall<ReqT, RespT> interceptedCall =
              new SimpleForwardingServerCall<ReqT, RespT>(call) {
                @Override
                public void sendHeaders(Metadata responseHeaders) {
                  responseHeaders.put(
                      MetadataServerInterceptor.CUSTOM_HEADER_KEY, "Server->Client header value");
                  super.sendHeaders(responseHeaders);
                }

                @Override
                public void close(Status status, Metadata trailers) {
                  trailers.put(
                      MetadataServerInterceptor.CUSTOM_TRAILER_KEY, "Server->Client trailer value");
                  super.close(status, trailers);
                }
              };
          return next.startCall(interceptedCall, headers);
        }
      };
    // Generate a unique in-process server name.
    String serverName = InProcessServerBuilder.generateName();
    // Create a server, add service, start, and register for automatic graceful shutdown.
    grpcCleanup.register(InProcessServerBuilder.forName(serverName).directExecutor()
        .addService(ServerInterceptors.intercept(greeterImpl, serverInterceptor))
        .build().start());
    // Create a client channel and register for automatic graceful shutdown.
    ManagedChannel channel = grpcCleanup.register(
        InProcessChannelBuilder.forName(serverName).directExecutor().build());
    MetadataClientInterceptor interceptor = new MetadataClientInterceptor();
    interceptor.outgoingHeader.set("Client->Server header value");
    GreeterBlockingStub blockingStub = GreeterGrpc.newBlockingStub(
        ClientInterceptors.intercept(channel, interceptor));

    blockingStub.sayHello(HelloRequest.getDefaultInstance());

    // Verify that client headers are delivered to the server
    Metadata capturedClientHeader = capturedClientHeaders.poll();
    assertNotNull(capturedClientHeader);
    assertEquals(
        "Client->Server header value",
        capturedClientHeader.get(MetadataClientInterceptor.CUSTOM_HEADER_KEY));

    // Verify that server headers and trailers are read by the client interceptor
    assertEquals(
        "Server->Client header value", interceptor.receivedHeaders.poll());
    assertEquals(
        "Server->Client trailer value", interceptor.receivedTrailers.poll());
  }
}
