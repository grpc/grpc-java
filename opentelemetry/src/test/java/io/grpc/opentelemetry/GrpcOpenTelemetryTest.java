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
import static io.grpc.ClientStreamTracer.NAME_RESOLUTION_DELAYED;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Collections.emptyList;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.ByteStreams;
import io.grpc.CallOptions;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ClientStreamTracer;
import io.grpc.ForwardingChannelBuilder2;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.MetricSink;
import io.grpc.ServerBuilder;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.internal.FakeClock;
import io.grpc.internal.GrpcUtil;
import io.grpc.opentelemetry.GrpcOpenTelemetry.TargetFilter;
import io.grpc.testing.GrpcCleanupRule;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.assertj.OpenTelemetryAssertions;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import io.opentelemetry.sdk.testing.junit4.OpenTelemetryRule;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class GrpcOpenTelemetryTest {
  @Rule
  public final OpenTelemetryRule openTelemetryRule = OpenTelemetryRule.create();
  @Rule
  public final GrpcCleanupRule grpcCleanupRule = new GrpcCleanupRule();

  private static final MethodDescriptor.Marshaller<String> MARSHALLER =
      new MethodDescriptor.Marshaller<String>() {
        @Override
        public InputStream stream(String value) {
          return new ByteArrayInputStream(value.getBytes(UTF_8));
        }

        @Override
        public String parse(InputStream stream) {
          try {
            return new String(ByteStreams.toByteArray(stream), UTF_8);
          } catch (IOException ex) {
            throw new RuntimeException(ex);
          }
        }
      };

  private final MethodDescriptor<String, String> method =
      MethodDescriptor.<String, String>newBuilder()
          .setType(MethodDescriptor.MethodType.UNARY)
          .setRequestMarshaller(MARSHALLER)
          .setResponseMarshaller(MARSHALLER)
          .setFullMethodName("test.service/method")
          .build();

  private final InMemoryMetricReader inMemoryMetricReader = InMemoryMetricReader.create();
  private final SdkMeterProvider meterProvider =
      SdkMeterProvider.builder().registerMetricReader(inMemoryMetricReader).build();
  private final SdkTracerProvider tracerProvider = SdkTracerProvider.builder().build();
  private final OpenTelemetry noopOpenTelemetry = OpenTelemetry.noop();
  private boolean originalEnableOtelTracing;

  @Before
  public void setup() {
    originalEnableOtelTracing = GrpcOpenTelemetry.ENABLE_OTEL_TRACING;
    System.setProperty("GRPC_EXPERIMENTAL_ENABLE_DELAY_OBSERVABILITY", "true");
  }

  @After
  public void tearDown() {
    GrpcOpenTelemetry.ENABLE_OTEL_TRACING = originalEnableOtelTracing;
    System.clearProperty("GRPC_EXPERIMENTAL_ENABLE_DELAY_OBSERVABILITY");
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
    verify(mockServerBuiler).addMetricSink(any());
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

    assertThat(module.getTracer()).isSameInstanceAs(noopOpenTelemetry
        .getTracerProvider()
        .tracerBuilder("grpc-java")
        .setInstrumentationVersion(GrpcUtil.IMPLEMENTATION_VERSION)
        .build()
    );
  }

  @Test
  public void builderTargetAttributeFilter() {
    GrpcOpenTelemetry module = GrpcOpenTelemetry.newBuilder()
        .targetAttributeFilter(t -> t.contains("allowed.com"))
        .build();

    TargetFilter internalFilter = module.getTargetAttributeFilter();

    assertThat(internalFilter.test("allowed.com")).isTrue();
    assertThat(internalFilter.test("example.com")).isFalse();
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

  @Test
  public void configureChannelBuilder_registersMetricSink() {
    GrpcOpenTelemetry grpcOpenTelemetry = GrpcOpenTelemetry.newBuilder().build();
    TestChannelBuilder testBuilder = new TestChannelBuilder();
    grpcOpenTelemetry.configureChannelBuilder(testBuilder);
    assertThat(testBuilder.metricSink).isSameInstanceAs(grpcOpenTelemetry.getSink());
    assertThat(testBuilder.interceptorFactory).isNotNull();
  }

  @Test
  public void nameResolutionDelay_endToEndClientServerSimulation() throws Exception {
    String serverName = InProcessServerBuilder.generateName();
    ServerServiceDefinition serviceDef = ServerServiceDefinition.builder("test.service")
        .addMethod(method, new ServerCallHandler<String, String>() {
          @Override
          public ServerCall.Listener<String> startCall(
              ServerCall<String, String> call, Metadata headers) {
            call.sendHeaders(new Metadata());
            call.sendMessage("response_payload");
            call.close(Status.OK, new Metadata());
            return new ServerCall.Listener<String>() {};
          }
        })
        .build();

    grpcCleanupRule.register(
        InProcessServerBuilder.forName(serverName).directExecutor().addService(serviceDef).build()
            .start());

    OpenTelemetrySdk sdk = (OpenTelemetrySdk) openTelemetryRule.getOpenTelemetry();
    GrpcOpenTelemetry grpcOpenTelemetry = GrpcOpenTelemetry.newBuilder()
        .sdk(sdk)
        .enableMetrics(Arrays.asList(
            "grpc.client.attempt.delay.duration",
            "grpc.client.call.delay.duration",
            "grpc.client.attempt.started"))
        .enableTracing(true)
        .addOptionalLabel("grpc.delay_type")
        .build();

    ManagedChannelBuilder<?> channelBuilder = InProcessChannelBuilder.forName(serverName)
        .directExecutor();
    grpcOpenTelemetry.configureChannelBuilder(channelBuilder);
    ManagedChannel channel = grpcCleanupRule.register(channelBuilder.build());

    // Simulate Name Resolution delay on call options and stream tracer
    CallOptions callOptions = CallOptions.DEFAULT.withOption(
        NAME_RESOLUTION_DELAYED, TimeUnit.MILLISECONDS.toNanos(120));

    OpenTelemetryMetricsResource resource = GrpcOpenTelemetry.createMetricInstruments(
        sdk.getMeterProvider().get("grpc-java"),
        ImmutableMap.of(
            "grpc.client.attempt.delay.duration", true,
            "grpc.client.call.delay.duration", true),
        false);
    OpenTelemetryMetricsModule module = new OpenTelemetryMetricsModule(
        new FakeClock().getStopwatchSupplier(), resource, emptyList(), emptyList());
    OpenTelemetryMetricsModule.CallAttemptsTracerFactory factory =
        new OpenTelemetryMetricsModule.CallAttemptsTracerFactory(
            module, "target:///", callOptions, method.getFullMethodName(),
            emptyList(), io.opentelemetry.context.Context.root());
    ClientStreamTracer delayTracer = factory.newClientStreamTracer(
        ClientStreamTracer.StreamInfo.newBuilder().setCallOptions(callOptions).build(),
        new Metadata());
    delayTracer.recordAttemptDelayStart("connecting", "DNS server unreachable temporarily");
    delayTracer.recordAttemptDelayEnd();
    factory.recordCallDelayStart("resolving", "DNS resolution pending");
    factory.recordCallDelayEnd();

    final CountDownLatch latch = new CountDownLatch(1);
    ClientCall<String, String> call = channel.newCall(method, callOptions);
    call.start(new ClientCall.Listener<String>() {
      @Override
      public void onClose(Status status, Metadata trailers) {
        latch.countDown();
      }
    }, new Metadata());
    call.sendMessage("request_payload");
    call.halfClose();
    call.request(1);
    assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();

    OpenTelemetryAssertions.assertThat(openTelemetryRule.getMetrics())
        .anySatisfy(
            metric -> OpenTelemetryAssertions.assertThat(metric)
                .hasName("grpc.client.attempt.delay.duration")
                .hasHistogramSatisfying(
                    histogram -> histogram.hasPointsSatisfying(
                        point -> {
                          point.hasAttribute(
                              AttributeKey.stringKey("grpc.delay_type"), "connecting");
                        })));
    OpenTelemetryAssertions.assertThat(openTelemetryRule.getMetrics())
        .anySatisfy(
            metric -> OpenTelemetryAssertions.assertThat(metric)
                .hasName("grpc.client.call.delay.duration"));
  }

  @Test
  public void lbPolicyDelay_endToEndClientServerSimulation() throws Exception {
    String serverName = InProcessServerBuilder.generateName();
    ServerServiceDefinition serviceDef = ServerServiceDefinition.builder("test.service")
        .addMethod(method, new ServerCallHandler<String, String>() {
          @Override
          public ServerCall.Listener<String> startCall(
              ServerCall<String, String> call, Metadata headers) {
            call.sendHeaders(new Metadata());
            call.sendMessage("response_payload");
            call.close(Status.OK, new Metadata());
            return new ServerCall.Listener<String>() {};
          }
        })
        .build();

    grpcCleanupRule.register(
        InProcessServerBuilder.forName(serverName).directExecutor().addService(serviceDef).build()
            .start());

    OpenTelemetrySdk sdk = (OpenTelemetrySdk) openTelemetryRule.getOpenTelemetry();
    GrpcOpenTelemetry grpcOpenTelemetry = GrpcOpenTelemetry.newBuilder()
        .sdk(sdk)
        .enableMetrics(Arrays.asList(
            "grpc.client.attempt.delay.duration",
            "grpc.client.call.delay.duration",
            "grpc.client.attempt.started"))
        .enableTracing(true)
        .addOptionalLabel("grpc.delay_type")
        .build();

    ManagedChannelBuilder<?> channelBuilder = InProcessChannelBuilder.forName(serverName)
        .directExecutor();
    grpcOpenTelemetry.configureChannelBuilder(channelBuilder);
    ManagedChannel channel = grpcCleanupRule.register(channelBuilder.build());

    OpenTelemetryMetricsResource resource = GrpcOpenTelemetry.createMetricInstruments(
        sdk.getMeterProvider().get("grpc-java"),
        ImmutableMap.of("grpc.client.attempt.delay.duration", true),
        false);
    OpenTelemetryMetricsModule module = new OpenTelemetryMetricsModule(
        new FakeClock().getStopwatchSupplier(), resource, emptyList(), emptyList());
    OpenTelemetryMetricsModule.CallAttemptsTracerFactory factory =
        new OpenTelemetryMetricsModule.CallAttemptsTracerFactory(
            module, "target:///", CallOptions.DEFAULT, method.getFullMethodName(),
            emptyList(), io.opentelemetry.context.Context.root());
    ClientStreamTracer tracer = factory.newClientStreamTracer(
        ClientStreamTracer.StreamInfo.newBuilder().setCallOptions(CallOptions.DEFAULT).build(),
        new Metadata());
    tracer.recordAttemptDelayStart("rls_lookup_pending", "Route Lookup Service query pending");
    tracer.recordAttemptDelayEnd();

    final CountDownLatch latch = new CountDownLatch(1);
    ClientCall<String, String> call = channel.newCall(method, CallOptions.DEFAULT);
    call.start(new ClientCall.Listener<String>() {
      @Override
      public void onClose(Status status, Metadata trailers) {
        latch.countDown();
      }
    }, new Metadata());
    call.sendMessage("request_payload");
    call.halfClose();
    call.request(1);
    assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();

    io.opentelemetry.sdk.testing.assertj.OpenTelemetryAssertions
        .assertThat(openTelemetryRule.getMetrics())
        .anySatisfy(
            metric -> io.opentelemetry.sdk.testing.assertj.OpenTelemetryAssertions
                .assertThat(metric)
                .hasName("grpc.client.attempt.delay.duration")
                .hasHistogramSatisfying(
                    histogram -> histogram.hasPointsSatisfying(
                        point -> {
                          point.hasAttribute(
                              AttributeKey.stringKey("grpc.delay_type"), "rls_lookup_pending");
                        })));
  }

  @Test
  public void baselineNoDelay_endToEndClientServerSimulation() throws Exception {
    String serverName = InProcessServerBuilder.generateName();
    ServerServiceDefinition serviceDef = ServerServiceDefinition.builder("test.service")
        .addMethod(method, new ServerCallHandler<String, String>() {
          @Override
          public ServerCall.Listener<String> startCall(
              ServerCall<String, String> call, Metadata headers) {
            call.sendHeaders(new Metadata());
            call.sendMessage("response_payload");
            call.close(Status.OK, new Metadata());
            return new ServerCall.Listener<String>() {};
          }
        })
        .build();

    grpcCleanupRule.register(
        InProcessServerBuilder.forName(serverName).directExecutor().addService(serviceDef).build()
            .start());

    OpenTelemetrySdk sdk = (OpenTelemetrySdk) openTelemetryRule.getOpenTelemetry();
    GrpcOpenTelemetry grpcOpenTelemetry = GrpcOpenTelemetry.newBuilder()
        .sdk(sdk)
        .enableMetrics(Arrays.asList(
            "grpc.client.attempt.delay.duration",
            "grpc.client.call.delay.duration",
            "grpc.client.attempt.started"))
        .enableTracing(true)
        .build();

    ManagedChannelBuilder<?> channelBuilder = InProcessChannelBuilder.forName(serverName)
        .directExecutor();
    grpcOpenTelemetry.configureChannelBuilder(channelBuilder);
    ManagedChannel channel = grpcCleanupRule.register(channelBuilder.build());

    final CountDownLatch latch = new CountDownLatch(1);
    ClientCall<String, String> call = channel.newCall(method, CallOptions.DEFAULT);
    call.start(new ClientCall.Listener<String>() {
      @Override
      public void onClose(Status status, Metadata trailers) {
        latch.countDown();
      }
    }, new Metadata());
    call.sendMessage("request_payload");
    call.halfClose();
    call.request(1);
    assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();

    boolean hasAttemptDelay = false;
    boolean hasCallDelay = false;
    for (MetricData m : openTelemetryRule.getMetrics()) {
      if ("grpc.client.attempt.delay.duration".equals(m.getName())
          && !m.getHistogramData().getPoints().isEmpty()) {
        hasAttemptDelay = true;
      }
      if ("grpc.client.call.delay.duration".equals(m.getName())
          && !m.getHistogramData().getPoints().isEmpty()) {
        hasCallDelay = true;
      }
    }
    assertThat(hasAttemptDelay).isFalse();
    assertThat(hasCallDelay).isFalse();
  }

  private static class TestChannelBuilder extends ForwardingChannelBuilder2<TestChannelBuilder> {
    Object interceptorFactory;
    MetricSink metricSink;

    @Override
    protected ManagedChannelBuilder<?> delegate() {
      return null;
    }

    @Override
    protected TestChannelBuilder interceptWithTarget(InterceptorFactory factory) {
      this.interceptorFactory = factory;
      return this;
    }

    @Override
    public TestChannelBuilder intercept(java.util.List<ClientInterceptor> interceptors) {
      return this;
    }

    @Override
    public TestChannelBuilder addMetricSink(MetricSink metricSink) {
      this.metricSink = metricSink;
      return this;
    }
  }

  // TODO(dnvindhya): Add tests for configurator
}
