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

import static org.junit.Assert.assertTrue;

import io.grpc.InsecureServerCredentials;
import io.grpc.ManagedChannelBuilder;
import io.grpc.ServerBuilder;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.opentelemetry.GrpcOpenTelemetry;
import io.grpc.opentelemetry.InternalGrpcOpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class OpenTelemetryTracingInteropPocTest extends AbstractInteropTest {

  private TestSpanExporter spanExporter;
  private OpenTelemetrySdk openTelemetrySdk;
  private GrpcOpenTelemetry grpcOpenTelemetry;

  private static class TestSpanExporter implements SpanExporter {
    private final List<SpanData> spans = new ArrayList<>();

    @Override
    public CompletableResultCode export(Collection<SpanData> spans) {
      this.spans.addAll(spans);
      return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode flush() {
      return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode shutdown() {
      return CompletableResultCode.ofSuccess();
    }

    public List<SpanData> getSpans() {
      return spans;
    }
  }

  @Before
  @Override
  public void setUp() {
    spanExporter = new TestSpanExporter();
    openTelemetrySdk = OpenTelemetrySdk.builder()
        .setTracerProvider(SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
            .build())
        .build();

    GrpcOpenTelemetry.Builder grpcOpentelemetryBuilder = GrpcOpenTelemetry.newBuilder()
        .sdk(openTelemetrySdk);
    InternalGrpcOpenTelemetry.enableTracing(grpcOpentelemetryBuilder, true);
    grpcOpenTelemetry = grpcOpentelemetryBuilder.build();

    super.setUp();
  }

  @After
  @Override
  public void tearDown() {
    super.tearDown();
    if (openTelemetrySdk != null) {
      openTelemetrySdk.close();
    }
  }

  @Override
  protected ServerBuilder<?> getServerBuilder() {
    NettyServerBuilder builder = NettyServerBuilder.forPort(0, InsecureServerCredentials.create())
        .maxInboundMessageSize(AbstractInteropTest.MAX_MESSAGE_SIZE);
    grpcOpenTelemetry.configureServerBuilder(builder);
    return builder;
  }

  @Override
  protected ManagedChannelBuilder<?> createChannelBuilder() {
    NettyChannelBuilder builder = NettyChannelBuilder.forAddress(getListenAddress())
        .maxInboundMessageSize(AbstractInteropTest.MAX_MESSAGE_SIZE)
        .usePlaintext();
    grpcOpenTelemetry.configureChannelBuilder(builder);
    return builder;
  }

  @Override
  protected boolean metricsExpected() {
    return false;
  }

  @Test
  public void verifySpansGenerated() throws Exception {
    blockingStub.emptyCall(io.grpc.testing.integration.EmptyProtos.Empty.getDefaultInstance());
    
    // Wait a bit for spans to be exported (SimpleSpanProcessor is synchronous, so they should be there)
    Thread.sleep(500);
    
    List<SpanData> spans = spanExporter.getSpans();
    System.out.println("Captured spans: " + spans.size());
    for (SpanData span : spans) {
      System.out.println("Span: " + span.getName());
    }
    
    assertTrue("Expected at least one span", spans.size() > 0);
  }
}
