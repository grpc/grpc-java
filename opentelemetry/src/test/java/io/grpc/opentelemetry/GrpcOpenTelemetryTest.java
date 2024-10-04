/*
 * Copyright 2023 The gRPC Authors
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

package io.grpc.opentelemetry;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.google.common.collect.ImmutableList;
import io.grpc.ClientInterceptor;
import io.grpc.ManagedChannelBuilder;
import io.grpc.MetricSink;
import io.grpc.ServerBuilder;
import io.grpc.internal.GrpcUtil;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import java.util.Arrays;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class GrpcOpenTelemetryTest {
  private final InMemoryMetricReader inMemoryMetricReader = InMemoryMetricReader.create();
  private final SdkMeterProvider meterProvider =
      SdkMeterProvider.builder().registerMetricReader(inMemoryMetricReader).build();
  private final SdkTracerProvider tracerProvider = SdkTracerProvider.builder().build();
  private final OpenTelemetry noopOpenTelemetry = OpenTelemetry.noop();
  private boolean originalEnableOtelTracing;

  @Before
  public void setup() {
    originalEnableOtelTracing = GrpcOpenTelemetry.ENABLE_OTEL_TRACING;
  }

  @After
  public void tearDown() {
    GrpcOpenTelemetry.ENABLE_OTEL_TRACING = originalEnableOtelTracing;
  }

  @Test
  public void build() {
    OpenTelemetrySdk sdk =
        OpenTelemetrySdk.builder().setMeterProvider(meterProvider).build();

    GrpcOpenTelemetry openTelemetryModule = GrpcOpenTelemetry.newBuilder()
        .sdk(sdk)
        .addOptionalLabel("version")
        .build();

    assertThat(openTelemetryModule.getOpenTelemetryInstance()).isSameInstanceAs(sdk);
    assertThat(openTelemetryModule.getMeterProvider()).isNotNull();
    assertThat(openTelemetryModule.getMeter()).isSameInstanceAs(
        meterProvider.meterBuilder("grpc-java")
            .setInstrumentationVersion(GrpcUtil.IMPLEMENTATION_VERSION)
            .build());
    assertThat(openTelemetryModule.getOptionalLabels()).isEqualTo(ImmutableList.of("version"));
  }

  @Test
  public void buildTracer() {
    OpenTelemetrySdk sdk =
        OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build();

    GrpcOpenTelemetry grpcOpenTelemetry = GrpcOpenTelemetry.newBuilder()
        .enableTracing(true)
        .sdk(sdk).build();

    assertThat(grpcOpenTelemetry.getOpenTelemetryInstance()).isSameInstanceAs(sdk);
    assertThat(grpcOpenTelemetry.getTracer()).isSameInstanceAs(
        tracerProvider.tracerBuilder("grpc-java")
            .setInstrumentationVersion(GrpcUtil.IMPLEMENTATION_VERSION)
            .build());
    ServerBuilder<?> mockServerBuiler = mock(ServerBuilder.class);
    grpcOpenTelemetry.configureServerBuilder(mockServerBuiler);
    verify(mockServerBuiler, times(2)).addStreamTracerFactory(any());
    verify(mockServerBuiler).intercept(any());
    verifyNoMoreInteractions(mockServerBuiler);

    ManagedChannelBuilder<?> mockChannelBuilder = mock(ManagedChannelBuilder.class);
    grpcOpenTelemetry.configureChannelBuilder(mockChannelBuilder);
    verify(mockChannelBuilder).intercept(any(ClientInterceptor.class));
  }

  @Test
  public void builderDefaults() {
    GrpcOpenTelemetry module = GrpcOpenTelemetry.newBuilder().build();

    assertThat(module.getOpenTelemetryInstance()).isNotNull();
    assertThat(module.getOpenTelemetryInstance()).isSameInstanceAs(noopOpenTelemetry);
    assertThat(module.getMeterProvider()).isNotNull();
    assertThat(module.getMeterProvider())
        .isSameInstanceAs(noopOpenTelemetry.getMeterProvider());
    assertThat(module.getMeter()).isSameInstanceAs(noopOpenTelemetry
        .getMeterProvider()
        .meterBuilder("grpc-java")
        .setInstrumentationVersion(GrpcUtil.IMPLEMENTATION_VERSION)
        .build());
    assertThat(module.getEnableMetrics()).isEmpty();
    assertThat(module.getOptionalLabels()).isEmpty();
    assertThat(module.getSink()).isInstanceOf(MetricSink.class);

    assertThat(module.getTracer()).isSameInstanceAs(noopOpenTelemetry
        .getTracerProvider()
        .tracerBuilder("grpc-java")
        .setInstrumentationVersion(GrpcUtil.IMPLEMENTATION_VERSION)
        .build()
    );
  }

  @Test
  public void enableDisableMetrics() {
    GrpcOpenTelemetry.Builder builder = GrpcOpenTelemetry.newBuilder();
    builder.enableMetrics(Arrays.asList("metric1", "metric4"));
    builder.disableMetrics(Arrays.asList("metric2", "metric3"));

    GrpcOpenTelemetry module = builder.build();

    assertThat(module.getEnableMetrics().get("metric1")).isTrue();
    assertThat(module.getEnableMetrics().get("metric4")).isTrue();
    assertThat(module.getEnableMetrics().get("metric2")).isFalse();
    assertThat(module.getEnableMetrics().get("metric3")).isFalse();
  }

  @Test
  public void disableAllMetrics() {
    GrpcOpenTelemetry.Builder builder = GrpcOpenTelemetry.newBuilder();
    builder.enableMetrics(Arrays.asList("metric1", "metric4"));
    builder.disableMetrics(Arrays.asList("metric2", "metric3"));
    builder.disableAllMetrics();

    GrpcOpenTelemetry module = builder.build();

    assertThat(module.getEnableMetrics()).isEmpty();
  }

  // TODO(dnvindhya): Add tests for configurator

}
