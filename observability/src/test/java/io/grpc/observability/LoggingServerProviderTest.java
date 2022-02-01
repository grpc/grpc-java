/*
 * Copyright 2022 The gRPC Authors
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

package io.grpc.observability;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.fail;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.Grpc;
import io.grpc.InsecureServerCredentials;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerProvider;
import io.grpc.observability.interceptors.InternalLoggingServerInterceptor;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.GrpcCleanupRule;
import io.grpc.testing.protobuf.SimpleRequest;
import io.grpc.testing.protobuf.SimpleResponse;
import io.grpc.testing.protobuf.SimpleServiceGrpc;
import java.io.IOException;
import java.util.function.Supplier;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentMatchers;

@RunWith(JUnit4.class)
public class LoggingServerProviderTest {
  @Rule public final GrpcCleanupRule cleanupRule = new GrpcCleanupRule();

  @Test
  public void initTwiceCausesException() {
    ServerProvider prevProvider = ServerProvider.provider();
    assertThat(prevProvider).isNotInstanceOf(LoggingServerProvider.class);
    LoggingServerProvider.init(new InternalLoggingServerInterceptor.FactoryImpl());
    assertThat(ServerProvider.provider()).isInstanceOf(ServerProvider.class);
    try {
      LoggingServerProvider.init(new InternalLoggingServerInterceptor.FactoryImpl());
      fail("should have failed for calling init() again");
    } catch (IllegalStateException e) {
      assertThat(e).hasMessageThat().contains("LoggingServerProvider already initialized!");
    }
    LoggingServerProvider.finish();
    assertThat(ServerProvider.provider()).isSameInstanceAs(prevProvider);
  }

  @Test
  public void forPort_interceptorCalled() throws IOException {
    serverBuilder_interceptorCalled(() -> ServerBuilder.forPort(0));
  }

  @Test
  public void newServerBuilder_interceptorCalled() throws IOException {
    serverBuilder_interceptorCalled(
        () -> Grpc.newServerBuilderForPort(0, InsecureServerCredentials.create()));
  }

  @SuppressWarnings("unchecked")
  private void serverBuilder_interceptorCalled(Supplier<ServerBuilder<?>> serverBuilderSupplier)
      throws IOException {
    ServerInterceptor interceptor =
        mock(ServerInterceptor.class, delegatesTo(new NoopInterceptor()));
    InternalLoggingServerInterceptor.Factory factory = mock(
        InternalLoggingServerInterceptor.Factory.class);
    when(factory.create()).thenReturn(interceptor);
    LoggingServerProvider.init(factory);
    Server server = serverBuilderSupplier.get().addService(new SimpleServiceImpl()).build().start();
    int port = cleanupRule.register(server).getPort();
    ManagedChannel channel = ManagedChannelBuilder.forAddress("localhost", port).usePlaintext()
        .build();
    SimpleServiceGrpc.SimpleServiceBlockingStub stub = SimpleServiceGrpc.newBlockingStub(
        cleanupRule.register(channel));
    assertThat(unaryRpc("buddy", stub)).isEqualTo("Hello buddy");
    verify(interceptor).interceptCall(any(ServerCall.class), any(Metadata.class), anyCallHandler());
    LoggingServerProvider.finish();
  }

  private ServerCallHandler<String, Integer> anyCallHandler() {
    return ArgumentMatchers.any();
  }

  private static String unaryRpc(
          String requestMessage, SimpleServiceGrpc.SimpleServiceBlockingStub blockingStub) {
    SimpleRequest request = SimpleRequest.newBuilder().setRequestMessage(requestMessage).build();
    SimpleResponse response = blockingStub.unaryRpc(request);
    return response.getResponseMessage();
  }

  private static class SimpleServiceImpl extends SimpleServiceGrpc.SimpleServiceImplBase {

    @Override
    public void unaryRpc(SimpleRequest req, StreamObserver<SimpleResponse> responseObserver) {
      SimpleResponse response =
              SimpleResponse.newBuilder()
                      .setResponseMessage("Hello " + req.getRequestMessage())
                      .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }

  private static class NoopInterceptor implements ServerInterceptor {
    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call,
            Metadata headers,
            ServerCallHandler<ReqT, RespT> next) {
      return next.startCall(call, headers);
    }
  }
}
