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
import io.grpc.netty.InternalNettyChannelBuilder;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.opentelemetry.GrpcOpenTelemetry;
import io.grpc.opentelemetry.GrpcTraceBinContextPropagator;
import io.grpc.opentelemetry.InternalGrpcOpenTelemetry;
import io.grpc.testing.integration.Messages.SimpleRequest;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class OpenTelemetryContextPropagationTest extends AbstractInteropTest {
  private final OpenTelemetrySdk openTelemetrySdk;
  private final Tracer tracer;
  private final GrpcOpenTelemetry grpcOpenTelemetry;
  private final AtomicReference<Span> applicationSpan = new AtomicReference<>();
  private final boolean censusClient;

  @Parameterized.Parameters(name = "ContextPropagator={0}, CensusClient={1}")
  public static Iterable<Object[]> data() {
    return Arrays.asList(new Object[][] {
        {W3CTraceContextPropagator.getInstance(), false},
        {GrpcTraceBinContextPropagator.defaultInstance(), false},
        {GrpcTraceBinContextPropagator.defaultInstance(), true}
    });
  }

  public OpenTelemetryContextPropagationTest(TextMapPropagator textMapPropagator,
                                             boolean isCensusClient) {
    this.openTelemetrySdk = OpenTelemetrySdk.builder()
        .setTracerProvider(SdkTracerProvider.builder().build())
        .setPropagators(ContextPropagators.create(TextMapPropagator.composite(
            textMapPropagator
        )))
        .build();
    this.tracer = openTelemetrySdk
        .getTracer("grpc-java-interop-test");
    GrpcOpenTelemetry.Builder grpcOpentelemetryBuilder = GrpcOpenTelemetry.newBuilder()
        .sdk(openTelemetrySdk);
    InternalGrpcOpenTelemetry.enableTracing(grpcOpentelemetryBuilder, true);
    grpcOpenTelemetry = grpcOpentelemetryBuilder.build();
    this.censusClient = isCensusClient;
  }

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
            maybeCloseSpan(applicationSpan);
            delegate().onHalfClose();
          }

          @Override
          public void onCancel() {
            maybeCloseSpan(applicationSpan);
            delegate().onCancel();
          }

          @Override
          public void onComplete() {
            maybeCloseSpan(applicationSpan);
            delegate().onComplete();
          }
        };
      }
    });
    // To ensure proper propagation of remote spans from gRPC to your application, this interceptor
    // must be after any application interceptors that interact with spans. This allows the tracing
    // information to be correctly passed along. However, it's fine for application-level onMessage
    // handlers to access the span.
    grpcOpenTelemetry.configureServerBuilder(builder);
    return builder;
  }

  private void maybeCloseSpan(AtomicReference<Span> applicationSpan) {
    Span tmp = applicationSpan.get();
    if (tmp != null) {
      tmp.end();
    }
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
    if (!censusClient) {
      // Disabling census-tracing is necessary to avoid trace ID mismatches.
      // This is because census-tracing overrides the grpc-trace-bin header with
      // OpenTelemetry's GrpcTraceBinPropagator.
      InternalNettyChannelBuilder.setTracingEnabled(builder, false);
      grpcOpenTelemetry.configureChannelBuilder(builder);
    }
    return builder;
  }

  @Test
  public void otelSpanContextPropagation() {
    Assume.assumeFalse(censusClient);
    Span parentSpan = tracer.spanBuilder("Test.interopTest").startSpan();
    try (Scope scope = Context.current().with(parentSpan).makeCurrent()) {
      blockingStub.unaryCall(SimpleRequest.getDefaultInstance());
    }
    assertEquals(parentSpan.getSpanContext().getTraceId(),
        applicationSpan.get().getSpanContext().getTraceId());
  }

  @Test
  @SuppressWarnings("deprecation")
  public void censusToOtelGrpcTraceBinPropagator() {
    Assume.assumeTrue(censusClient);
    io.opencensus.trace.Tracer censusTracer = io.opencensus.trace.Tracing.getTracer();
    io.opencensus.trace.Span parentSpan = censusTracer.spanBuilder("Test.interopTest")
        .startSpan();
    io.grpc.Context context =  io.opencensus.trace.unsafe.ContextUtils.withValue(
        io.grpc.Context.current(), parentSpan);
    io.grpc.Context previous = context.attach();
    try {
      blockingStub.unaryCall(SimpleRequest.getDefaultInstance());
      assertEquals(parentSpan.getContext().getTraceId().toLowerBase16(),
          applicationSpan.get().getSpanContext().getTraceId());
    } finally {
      context.detach(previous);
    }
  }
}
