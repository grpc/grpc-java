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

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.Context;
import io.grpc.ForwardingClientCall.SimpleForwardingClientCall;
import io.grpc.ForwardingClientCallListener.SimpleForwardingClientCallListener;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
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
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Unit tests for {@link HeaderClientInterceptor}.
 * For demonstrating how to write gRPC unit test only.
 * Not intended to provide a high code coverage or to test every major usecase.
 *
 * <p>For basic unit test examples see {@link io.grpc.examples.helloworld.HelloWorldClientTest} and
 * {@link io.grpc.examples.helloworld.HelloWorldServerTest}.
 */
@RunWith(JUnit4.class)
public class MetadataServerInterceptorTest {
  /**
   * This rule manages automatic graceful shutdown for the registered servers and channels at the
   * end of test.
   */
  @Rule
  public final GrpcCleanupRule grpcCleanup = new GrpcCleanupRule();

  private final BlockingQueue<Context> capturedContexts = new LinkedBlockingQueue<>();
  private final BlockingQueue<Metadata> capturedServerHeaders = new LinkedBlockingQueue<>();
  private final BlockingQueue<Metadata> capturedServerTrailers = new LinkedBlockingQueue<>();

  private Channel channel;

  @Before
  public void setUp() throws Exception {
    GreeterImplBase greeterImpl =
        new GreeterImplBase() {
          @Override
          public void sayHello(HelloRequest request, StreamObserver<HelloReply> responseObserver) {
            capturedContexts.add(Context.current());
            responseObserver.onNext(HelloReply.getDefaultInstance());
            responseObserver.onCompleted();
          }
        };
    // Generate a unique in-process server name.
    String serverName = InProcessServerBuilder.generateName();
    MetadataServerInterceptor interceptor = new MetadataServerInterceptor();
    interceptor.outgoingHeader.set("Server->Client header value");
    interceptor.outgoingTrailer.set("Server->Client trailer value");
    // Create a server, add service, start, and register for automatic graceful shutdown.
    grpcCleanup.register(InProcessServerBuilder.forName(serverName).directExecutor()
        .addService(ServerInterceptors.intercept(greeterImpl, interceptor))
        .build().start());
    // Create a client channel and register for automatic graceful shutdown.
    channel =
        grpcCleanup.register(InProcessChannelBuilder.forName(serverName).directExecutor().build());
  }

  @Test
  public void works() {
    ClientInterceptor clientInterceptor = new ClientInterceptor() {
        @Override
        public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
            MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
          return new SimpleForwardingClientCall<ReqT, RespT>(next.newCall(method, callOptions)) {
            @Override
            public void start(Listener<RespT> responseListener, Metadata headers) {
              headers.put(MetadataClientInterceptor.CUSTOM_HEADER_KEY, "Client->Server header value");
              super.start(new SimpleForwardingClientCallListener<RespT>(responseListener) {
                  @Override
                  public void onHeaders(Metadata headers) {
                    capturedServerHeaders.add(headers);
                    super.onHeaders(headers);
                  }

                  @Override
                  public void onClose(Status status, Metadata trailers) {
                    capturedServerTrailers.add(trailers);
                    super.onClose(status, trailers);
                  }
                }, headers);
            }
          };
        }
      };

    GreeterBlockingStub blockingStub = GreeterGrpc.newBlockingStub(channel)
        .withInterceptors(clientInterceptor);

    blockingStub.sayHello(HelloRequest.getDefaultInstance());

    // Verify that server headers and trailers are delivered to the client
    Metadata capturedServerHeader = capturedServerHeaders.poll();
    assertNotNull(capturedServerHeader);
    assertEquals(
        "Server->Client header value",
        capturedServerHeader.get(MetadataServerInterceptor.CUSTOM_HEADER_KEY));
    Metadata capturedServerTrailer = capturedServerTrailers.poll();
    assertNotNull(capturedServerTrailer);
    assertEquals(
        "Server->Client trailer value",
        capturedServerTrailer.get(MetadataServerInterceptor.CUSTOM_TRAILER_KEY));

    // Verify that client headers is read by the server interceptor and installed on the
    // Context that the service handler sees.
    Context capturedContext = capturedContexts.poll();
    assertNotNull(capturedContext);
    assertEquals(
        "Client->Server header value",
        MetadataServerInterceptor.CLIENT_HEADER_CTX_KEY.get(capturedContext));
  }
}
