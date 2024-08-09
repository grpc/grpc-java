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

package io.grpc.stub;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableList;
import io.grpc.CallOptions;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.ServerServiceDefinition;
import io.grpc.StringMarshaller;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.testing.GrpcCleanupRule;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class MetadataUtilsTest {

  @Rule public final GrpcCleanupRule grpcCleanup = new GrpcCleanupRule();
  private static Metadata.Key<String> FOO_KEY =
      Metadata.Key.of("foo-key", Metadata.ASCII_STRING_MARSHALLER);

  private final MethodDescriptor<String, String> echoMethod =
      MethodDescriptor.newBuilder(StringMarshaller.INSTANCE, StringMarshaller.INSTANCE)
          .setFullMethodName("test/echo")
          .setType(MethodDescriptor.MethodType.UNARY)
          .build();

  private final ServerCallHandler<String, String> echoCallHandler =
      ServerCalls.asyncUnaryCall(
          (req, respObserver) -> {
            respObserver.onNext(req);
            respObserver.onCompleted();
          });

  @Test
  public void testAttachHeadersServerInterceptor() throws IOException {
    Metadata extraHeaders = new Metadata();
    extraHeaders.put(FOO_KEY, "foo-value");

    ImmutableList<ServerInterceptor> interceptors =
        ImmutableList.of(MetadataUtils.newAttachHeadersServerInterceptor(extraHeaders));
    ServerServiceDefinition serviceDef =
        ServerInterceptors.intercept(
            ServerServiceDefinition.builder("test").addMethod(echoMethod, echoCallHandler).build(),
            interceptors);

    InProcessServerBuilder server =
        InProcessServerBuilder.forName("test").directExecutor().addService(serviceDef);
    grpcCleanup.register(server.build().start());

    AtomicReference<Metadata> trailersCapture = new AtomicReference<>();
    AtomicReference<Metadata> headersCapture = new AtomicReference<>();
    ManagedChannel channel =
        InProcessChannelBuilder.forName("test")
            .directExecutor()
            .intercept(MetadataUtils.newCaptureMetadataInterceptor(headersCapture, trailersCapture))
            .build();

    String response =
        ClientCalls.blockingUnaryCall(channel, echoMethod, CallOptions.DEFAULT, "hello");
    assertThat(response).isEqualTo("hello");
    Metadata headers = headersCapture.get();
    assertThat(headers.get(FOO_KEY)).isEqualTo("foo-value");
  }
}
