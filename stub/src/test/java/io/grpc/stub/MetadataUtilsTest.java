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
import static io.grpc.stub.MetadataUtils.newAttachMetadataServerInterceptor;
import static io.grpc.stub.MetadataUtils.newCaptureMetadataInterceptor;
import static org.junit.Assert.fail;

import com.google.common.collect.ImmutableList;
import io.grpc.CallOptions;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptors;
import io.grpc.ServerMethodDefinition;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.Status.Code;
import io.grpc.StatusRuntimeException;
import io.grpc.StringMarshaller;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.testing.GrpcCleanupRule;
import java.io.IOException;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class MetadataUtilsTest {

  @Rule public final GrpcCleanupRule grpcCleanup = new GrpcCleanupRule();

  private static final String SERVER_NAME = "test";
  private static final Metadata.Key<String> FOO_KEY =
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

  MethodDescriptor<String, String> echoServerStreamingMethod =
      MethodDescriptor.newBuilder(StringMarshaller.INSTANCE, StringMarshaller.INSTANCE)
          .setFullMethodName("test/echoStream")
          .setType(MethodDescriptor.MethodType.SERVER_STREAMING)
          .build();

  private final AtomicReference<Metadata> trailersCapture = new AtomicReference<>();
  private final AtomicReference<Metadata> headersCapture = new AtomicReference<>();

  @Test
  public void shouldAttachHeadersToResponse() throws IOException {
    Metadata extras = new Metadata();
    extras.put(FOO_KEY, "foo-value");

    ServerServiceDefinition serviceDef =
        ServerInterceptors.intercept(
            ServerServiceDefinition.builder("test").addMethod(echoMethod, echoCallHandler).build(),
            ImmutableList.of(newAttachMetadataServerInterceptor(extras)));

    grpcCleanup.register(newInProcessServerBuilder().addService(serviceDef).build().start());
    ManagedChannel channel =
        grpcCleanup.register(
            newInProcessChannelBuilder()
                .intercept(newCaptureMetadataInterceptor(headersCapture, trailersCapture))
                .build());

    String response =
        ClientCalls.blockingUnaryCall(channel, echoMethod, CallOptions.DEFAULT, "hello");
    assertThat(response).isEqualTo("hello");
    assertThat(trailersCapture.get() == null || !trailersCapture.get().containsKey(FOO_KEY))
        .isTrue();
    assertThat(headersCapture.get().get(FOO_KEY)).isEqualTo("foo-value");
  }

  @Test
  public void shouldAttachTrailersWhenNoResponse() throws IOException {
    Metadata extras = new Metadata();
    extras.put(FOO_KEY, "foo-value");

    ServerServiceDefinition serviceDef =
        ServerInterceptors.intercept(
            ServerServiceDefinition.builder("test")
                .addMethod(
                    ServerMethodDefinition.create(
                        echoServerStreamingMethod,
                        ServerCalls.asyncUnaryCall(
                            (req, respObserver) -> respObserver.onCompleted())))
                .build(),
            ImmutableList.of(newAttachMetadataServerInterceptor(extras)));
    grpcCleanup.register(newInProcessServerBuilder().addService(serviceDef).build().start());

    ManagedChannel channel =
        grpcCleanup.register(
            newInProcessChannelBuilder()
                .intercept(newCaptureMetadataInterceptor(headersCapture, trailersCapture))
                .build());

    Iterator<String> response =
        ClientCalls.blockingServerStreamingCall(
            channel, echoServerStreamingMethod, CallOptions.DEFAULT, "hello");
    assertThat(response.hasNext()).isFalse();
    assertThat(headersCapture.get() == null || !headersCapture.get().containsKey(FOO_KEY)).isTrue();
    assertThat(trailersCapture.get().get(FOO_KEY)).isEqualTo("foo-value");
  }

  @Test
  public void shouldAttachTrailersToErrorResponse() throws IOException {
    Metadata extras = new Metadata();
    extras.put(FOO_KEY, "foo-value");

    ServerServiceDefinition serviceDef =
        ServerInterceptors.intercept(
            ServerServiceDefinition.builder("test")
                .addMethod(
                    echoMethod,
                    ServerCalls.asyncUnaryCall(
                        (req, respObserver) ->
                            respObserver.onError(Status.INVALID_ARGUMENT.asRuntimeException())))
                .build(),
            ImmutableList.of(newAttachMetadataServerInterceptor(extras)));
    grpcCleanup.register(newInProcessServerBuilder().addService(serviceDef).build().start());

    ManagedChannel channel =
        grpcCleanup.register(
            newInProcessChannelBuilder()
                .intercept(newCaptureMetadataInterceptor(headersCapture, trailersCapture))
                .build());
    try {
      ClientCalls.blockingUnaryCall(channel, echoMethod, CallOptions.DEFAULT, "hello");
      fail();
    } catch (StatusRuntimeException e) {
      assertThat(e.getStatus()).isNotNull();
      assertThat(e.getStatus().getCode()).isEqualTo(Code.INVALID_ARGUMENT);
    }
    assertThat(headersCapture.get() == null || !headersCapture.get().containsKey(FOO_KEY)).isTrue();
    assertThat(trailersCapture.get().get(FOO_KEY)).isEqualTo("foo-value");
  }

  private static InProcessServerBuilder newInProcessServerBuilder() {
    return InProcessServerBuilder.forName(SERVER_NAME).directExecutor();
  }

  private static InProcessChannelBuilder newInProcessChannelBuilder() {
    return InProcessChannelBuilder.forName(SERVER_NAME).directExecutor();
  }
}
