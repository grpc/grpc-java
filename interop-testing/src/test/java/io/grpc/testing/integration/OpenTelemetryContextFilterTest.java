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

package io.grpc.testing.integration;

import static org.junit.Assert.assertEquals;

import io.grpc.ForwardingServerCallListener;
import io.grpc.InsecureServerCredentials;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.ServerBuilder;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.opentelemetry.GrpcOpenTelemetry;
import io.grpc.testing.integration.Messages.SimpleRequest;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class OpenTelemetryContextFilterTest extends AbstractInteropTest {
  private final OpenTelemetrySdk openTelemetrySdk = OpenTelemetrySdk.builder()
      .setTracerProvider(SdkTracerProvider.builder().build())
      .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
      .build();
  private final Tracer tracer = openTelemetrySdk.getTracer("grpc-java");
  private final GrpcOpenTelemetry grpcOpenTelemetry = GrpcOpenTelemetry.newBuilder()
      .sdk(openTelemetrySdk).build();
  private final AtomicReference<Span> applicationSpan = new AtomicReference<>();

  @Override
  protected ServerBuilder<?> getServerBuilder() {
    NettyServerBuilder builder = NettyServerBuilder.forPort(0, InsecureServerCredentials.create())
        .maxInboundMessageSize(AbstractInteropTest.MAX_MESSAGE_SIZE);
    builder.intercept(new ServerInterceptor() {
      @Override
      public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call,
          Metadata headers, ServerCallHandler<ReqT, RespT> next) {
        ServerCall.Listener<ReqT> listener = next.startCall(call, headers);
        return new ForwardingServerCallListener<ReqT>() {
          @Override
          protected ServerCall.Listener<ReqT> delegate() {
            return listener;
          }

          @Override
          public void onMessage(ReqT request) {
            applicationSpan.set(tracer.spanBuilder("InteropTest.Application.Span").startSpan());
            delegate().onMessage(request);
          }

          @Override
          public void onHalfClose() {
            if (applicationSpan.get() != null) {
              applicationSpan.get().end();
            }
            delegate().onHalfClose();
          }

          @Override
          public void onCancel() {
            if (applicationSpan.get() != null) {
              applicationSpan.get().end();
            }
            delegate().onCancel();
          }

          @Override
          public void onComplete() {
            if (applicationSpan.get() != null) {
              applicationSpan.get().end();
            }
            delegate().onComplete();
          }
        };
      }
    });
    grpcOpenTelemetry.configureServerBuilder(builder);
    return builder;
  }

  @Override
  protected boolean metricsExpected() {
    return false;
  }

  @Override
  protected ManagedChannelBuilder<?> createChannelBuilder() {
    NettyChannelBuilder builder = NettyChannelBuilder.forAddress(getListenAddress())
        .maxInboundMessageSize(AbstractInteropTest.MAX_MESSAGE_SIZE)
        .usePlaintext();
    grpcOpenTelemetry.configureChannelBuilder(builder);
    return builder;
  }

  @Test
  public void otelSpanContextPropagation() {
    Span parentSpan = tracer.spanBuilder("Test.interopTest").startSpan();
    try (Scope scope = Context.current().with(parentSpan).makeCurrent()) {
      blockingStub.unaryCall(SimpleRequest.getDefaultInstance());
    }
    assertEquals(parentSpan.getSpanContext().getTraceId(),
        applicationSpan.get().getSpanContext().getTraceId());
  }
}
