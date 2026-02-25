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

import static io.grpc.ClientStreamTracer.NAME_RESOLUTION_DELAYED;
import static io.grpc.opentelemetry.internal.OpenTelemetryConstants.LOCALITY_KEY;
import static io.grpc.opentelemetry.internal.OpenTelemetryConstants.METHOD_KEY;
import static io.grpc.opentelemetry.internal.OpenTelemetryConstants.STATUS_KEY;
import static io.grpc.opentelemetry.internal.OpenTelemetryConstants.TARGET_KEY;
import static io.opentelemetry.sdk.testing.assertj.OpenTelemetryAssertions.assertThat;
import static java.util.Collections.emptyList;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.grpc.Attributes;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ClientInterceptors;
import io.grpc.ClientStreamTracer;
import io.grpc.ForwardingClientCall;
import io.grpc.KnownLength;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerServiceDefinition;
import io.grpc.ServerStreamTracer;
import io.grpc.ServerStreamTracer.ServerCallInfo;
import io.grpc.Status;
import io.grpc.Status.Code;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.internal.FakeClock;
import io.grpc.opentelemetry.GrpcOpenTelemetry.TargetFilter;
import io.grpc.opentelemetry.OpenTelemetryMetricsModule.CallAttemptsTracerFactory;
import io.grpc.opentelemetry.internal.OpenTelemetryConstants;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.GrpcServerRule;
import io.grpc.testing.protobuf.SimpleRequest;
import io.grpc.testing.protobuf.SimpleResponse;
import io.grpc.testing.protobuf.SimpleServiceGrpc;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongHistogram;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.context.propagation.TextMapSetter;
import io.opentelemetry.sdk.common.InstrumentationScopeInfo;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.junit4.OpenTelemetryRule;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/**
 * Test for {@link OpenTelemetryMetricsModule}.
 */
@RunWith(JUnit4.class)
public class OpenTelemetryMetricsModuleTest {
  // ... existing code ...

  private static final CallOptions.Key<String> CUSTOM_OPTION =
      CallOptions.Key.createWithDefault("option1", "default");
  private static final CallOptions CALL_OPTIONS =
      CallOptions.DEFAULT.withOption(CUSTOM_OPTION, "customvalue");
  private static final ClientStreamTracer.StreamInfo STREAM_INFO =
      ClientStreamTracer.StreamInfo.newBuilder()
          .setCallOptions(CallOptions.DEFAULT.withOption(NAME_RESOLUTION_DELAYED, 10L)).build();
  private static final String CLIENT_ATTEMPT_COUNT_INSTRUMENT_NAME = "grpc.client.attempt.started";
  private static final String CLIENT_ATTEMPT_DURATION_INSTRUMENT_NAME
      = "grpc.client.attempt.duration";
  private static final String CLIENT_ATTEMPT_SENT_TOTAL_COMPRESSED_MESSAGE_SIZE
      = "grpc.client.attempt.sent_total_compressed_message_size";
  private static final String CLIENT_ATTEMPT_RECV_TOTAL_COMPRESSED_MESSAGE_SIZE
      = "grpc.client.attempt.rcvd_total_compressed_message_size";
  private static final String CLIENT_CALL_DURATION = "grpc.client.call.duration";
  private static final String CLIENT_CALL_RETRIES = "grpc.client.call.retries";
  private static final String CLIENT_CALL_TRANSPARENT_RETRIES =
      "grpc.client.call.transparent_retries";
  private static final String CLIENT_CALL_HEDGES = "grpc.client.call.hedges";
  private static final String CLIENT_CALL_RETRY_DELAY = "grpc.client.call.retry_delay";
  private static final String SERVER_CALL_COUNT = "grpc.server.call.started";
  private static final String SERVER_CALL_DURATION = "grpc.server.call.duration";
  private static final String SERVER_CALL_SENT_TOTAL_COMPRESSED_MESSAGE_SIZE
      = "grpc.server.call.sent_total_compressed_message_size";
  private static final String SERVER_CALL_RECV_TOTAL_COMPRESSED_MESSAGE_SIZE
      = "grpc.server.call.rcvd_total_compressed_message_size";
  private static final double[] latencyBuckets =
      {   0d,     0.00001d, 0.00005d, 0.0001d, 0.0003d, 0.0006d, 0.0008d, 0.001d, 0.002d,
          0.003d, 0.004d,   0.005d,   0.006d,  0.008d,  0.01d,   0.013d,  0.016d, 0.02d,
          0.025d, 0.03d,    0.04d,    0.05d,   0.065d,  0.08d,   0.1d,    0.13d,  0.16d,
          0.2d,   0.25d,    0.3d,     0.4d,    0.5d,    0.65d,   0.8d,    1d,     2d,
          5d,     10d,      20d,      50d,     100d };
  private static final double[] sizeBuckets =
      { 0L, 1024L, 2048L, 4096L, 16384L, 65536L, 262144L, 1048576L, 4194304L, 16777216L,
      67108864L, 268435456L, 1073741824L, 4294967296L };

  private static final class StringInputStream extends InputStream implements KnownLength {
    final String string;

    StringInputStream(String string) {
      this.string = string;
    }

    @Override
    public int read() {
      throw new UnsupportedOperationException("should not be called");
    }

    @Override
    public int available() throws IOException {
      return string == null ? 0 : string.length();
    }
  }

  private static final MethodDescriptor.Marshaller<String> MARSHALLER =
      new MethodDescriptor.Marshaller<String>() {
        @Override
        public InputStream stream(String value) {
          return new StringInputStream(value);
        }

        @Override
        public String parse(InputStream stream) {
          return ((StringInputStream) stream).string;
        }
      };

  @Rule
  public final MockitoRule mocks = MockitoJUnit.rule();
  @Rule
  public final GrpcServerRule grpcServerRule = new GrpcServerRule().directExecutor();
  @Rule
  public final OpenTelemetryRule openTelemetryTesting = OpenTelemetryRule.create();
  @Mock
  private ClientCall.Listener<String> mockClientCallListener;
  @Mock
  private ServerCall.Listener<String> mockServerCallListener;
  @Captor
  private ArgumentCaptor<Status> statusCaptor;
  @Mock
  private DoubleHistogram mockServerCallDurationHistogram;
  @Captor
  private ArgumentCaptor<io.opentelemetry.context.Context> contextCaptor;
  private io.grpc.Server server;
  private io.grpc.ManagedChannel channel;
  private OpenTelemetryMetricsResource resource;
  private final String serverName = "E2ETestServer-" + Math.random();

  private final FakeClock fakeClock = new FakeClock();
  private final MethodDescriptor<String, String> method =
      MethodDescriptor.<String, String>newBuilder()
          .setType(MethodDescriptor.MethodType.UNKNOWN)
          .setRequestMarshaller(MARSHALLER)
          .setResponseMarshaller(MARSHALLER)
          .setFullMethodName("package1.service2/method3")
          .setSampledToLocalTracing(true)
          .build();
  private Meter testMeter;
  private final Map<String, Boolean> enabledMetricsMap = ImmutableMap.of();

  private final boolean disableDefaultMetrics = false;

  @Before
  public void setUp() throws Exception {
    testMeter = openTelemetryTesting.getOpenTelemetry()
        .getMeter(OpenTelemetryConstants.INSTRUMENTATION_SCOPE);
    resource = OpenTelemetryMetricsResource.builder()
        .serverCallDurationCounter(mockServerCallDurationHistogram)
        .build();
  }

  @After
  public void tearDown() {
    if (channel != null) {
      channel.shutdownNow();
    }
    if (server != null) {
      server.shutdownNow();
    }
  }

  @Test
  public void testClientInterceptors() {
    OpenTelemetryMetricsResource resource = GrpcOpenTelemetry.createMetricInstruments(testMeter,
        enabledMetricsMap, disableDefaultMetrics);
    OpenTelemetryMetricsModule module = newOpenTelemetryMetricsModule(resource);
    grpcServerRule.getServiceRegistry().addService(
        ServerServiceDefinition.builder("package1.service2").addMethod(
            method, new ServerCallHandler<String, String>() {
              @Override
              public ServerCall.Listener<String> startCall(
                  ServerCall<String, String> call, Metadata headers) {
                call.sendHeaders(new Metadata());
                call.sendMessage("Hello");
                call.close(
                    Status.PERMISSION_DENIED.withDescription("No you don't"), new Metadata());
                return mockServerCallListener;
              }
            }).build());

    final AtomicReference<CallOptions> capturedCallOptions = new AtomicReference<>();
    ClientInterceptor callOptionsCaptureInterceptor = new ClientInterceptor() {
      @Override
      public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
          MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
        capturedCallOptions.set(callOptions);
        return next.newCall(method, callOptions);
      }
    };
    Channel interceptedChannel =
        ClientInterceptors.intercept(
            grpcServerRule.getChannel(), callOptionsCaptureInterceptor,
            module.getClientInterceptor("target:///"));
    ClientCall<String, String> call;
    call = interceptedChannel.newCall(method, CALL_OPTIONS);

    assertEquals("customvalue", capturedCallOptions.get().getOption(CUSTOM_OPTION));
    assertEquals(1, capturedCallOptions.get().getStreamTracerFactories().size());
    assertTrue(
        capturedCallOptions.get().getStreamTracerFactories().get(0)
            instanceof OpenTelemetryMetricsModule.CallAttemptsTracerFactory);

    // Make the call
    Metadata headers = new Metadata();
    call.start(mockClientCallListener, headers);

    // End the call
    call.halfClose();
    call.request(1);

    verify(mockClientCallListener).onClose(statusCaptor.capture(), any(Metadata.class));
    Status status = statusCaptor.getValue();
    assertEquals(Status.Code.PERMISSION_DENIED, status.getCode());
    assertEquals("No you don't", status.getDescription());
  }

  @Test
  public void clientBasicMetrics() {
    String target = "target:///";
    OpenTelemetryMetricsResource resource = GrpcOpenTelemetry.createMetricInstruments(testMeter,
        enabledMetricsMap, disableDefaultMetrics);
    OpenTelemetryMetricsModule module = newOpenTelemetryMetricsModule(resource);
    OpenTelemetryMetricsModule.CallAttemptsTracerFactory callAttemptsTracerFactory =
        new CallAttemptsTracerFactory(module, target, method.getFullMethodName(), emptyList());
    Metadata headers = new Metadata();
    ClientStreamTracer tracer =
        callAttemptsTracerFactory.newClientStreamTracer(STREAM_INFO, headers);
    io.opentelemetry.api.common.Attributes attributes = io.opentelemetry.api.common.Attributes.of(
        TARGET_KEY, target,
        METHOD_KEY, method.getFullMethodName());

    assertThat(openTelemetryTesting.getMetrics())
        .satisfiesExactly(
            metric ->
                assertThat(metric)
                    .hasInstrumentationScope(InstrumentationScopeInfo.create(
                        OpenTelemetryConstants.INSTRUMENTATION_SCOPE))
                    .hasName(CLIENT_ATTEMPT_COUNT_INSTRUMENT_NAME)
                    .hasUnit("{attempt}")
                    .hasLongSumSatisfying(
                        longSum ->
                            longSum
                                .hasPointsSatisfying(
                                    point ->
                                        point
                                            .hasAttributes(attributes)
                                            .hasValue(1))));

    tracer.addOptionalLabel("grpc.lb.locality", "should-be-ignored");

    fakeClock.forwardTime(30, TimeUnit.MILLISECONDS);
    tracer.outboundHeaders();

    fakeClock.forwardTime(100, TimeUnit.MILLISECONDS);
    tracer.outboundMessage(0);
    tracer.outboundWireSize(1028);

    fakeClock.forwardTime(16, TimeUnit.MILLISECONDS);

    tracer.inboundMessage(0);
    tracer.inboundMessage(33);
    tracer.outboundMessage(1);
    tracer.outboundWireSize(99);

    fakeClock.forwardTime(24, TimeUnit.MILLISECONDS);
    tracer.inboundMessage(1);
    tracer.inboundWireSize(154);
    tracer.streamClosed(Status.OK);
    callAttemptsTracerFactory.callEnded(Status.OK);

    io.opentelemetry.api.common.Attributes clientAttributes
        = io.opentelemetry.api.common.Attributes.of(
        TARGET_KEY, target,
        METHOD_KEY, method.getFullMethodName(),
        STATUS_KEY, Status.Code.OK.toString());

    assertThat(openTelemetryTesting.getMetrics())
        .satisfiesExactlyInAnyOrder(
            metric ->
                assertThat(metric)
                    .hasInstrumentationScope(InstrumentationScopeInfo.create(
                        OpenTelemetryConstants.INSTRUMENTATION_SCOPE))
                    .hasName(CLIENT_ATTEMPT_COUNT_INSTRUMENT_NAME)
                    .hasUnit("{attempt}")
                    .hasLongSumSatisfying(
                        longSum ->
                            longSum
                                .hasPointsSatisfying(
                                    point ->
                                        point
                                            .hasAttributes(attributes)
                                            .hasValue(1))),
            metric ->
                assertThat(metric)
                    .hasInstrumentationScope(InstrumentationScopeInfo.create(
                        OpenTelemetryConstants.INSTRUMENTATION_SCOPE))
                    .hasName(CLIENT_ATTEMPT_DURATION_INSTRUMENT_NAME)
                    .hasUnit("s")
                    .hasHistogramSatisfying(
                        histogram ->
                            histogram.hasPointsSatisfying(
                                point ->
                                    point
                                        .hasCount(1)
                                        .hasSum(0.03 + 0.1 + 0.016 + 0.024)
                                        .hasAttributes(clientAttributes)
                                        .hasBucketBoundaries(latencyBuckets)
                                        .hasBucketCounts(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                                            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0,
                                            0, 0, 0, 0, 0, 0, 0, 0, 0))),
            metric ->
                assertThat(metric)
                    .hasInstrumentationScope(InstrumentationScopeInfo.create(
                        OpenTelemetryConstants.INSTRUMENTATION_SCOPE))
                    .hasName(
                        CLIENT_ATTEMPT_SENT_TOTAL_COMPRESSED_MESSAGE_SIZE)
                    .hasUnit("By")
                    .hasHistogramSatisfying(
                        histogram ->
                            histogram.hasPointsSatisfying(
                                point ->
                                    point
                                        .hasCount(1)
                                        .hasSum(1028L + 99)
                                        .hasAttributes(clientAttributes)
                                        .hasBucketBoundaries(sizeBuckets)
                                        .hasBucketCounts(0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                                            0))),
            metric ->
                assertThat(metric)
                    .hasInstrumentationScope(InstrumentationScopeInfo.create(
                        OpenTelemetryConstants.INSTRUMENTATION_SCOPE))
                    .hasName(
                        CLIENT_ATTEMPT_RECV_TOTAL_COMPRESSED_MESSAGE_SIZE)
                    .hasUnit("By")
                    .hasHistogramSatisfying(
                        histogram ->
                            histogram
                                .isCumulative()
                                .hasPointsSatisfying(
                                    point ->
                                        point
                                            .hasCount(1)
                                            .hasSum(154)
                                            .hasAttributes(clientAttributes)
                                            .hasBucketCounts(0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                                                0, 0))),
            metric ->
                assertThat(metric)
                    .hasInstrumentationScope(InstrumentationScopeInfo.create(
                        OpenTelemetryConstants.INSTRUMENTATION_SCOPE))
                    .hasName(CLIENT_CALL_DURATION)
                    .hasUnit("s")
                    .hasHistogramSatisfying(
                        histogram ->
                            histogram.hasPointsSatisfying(
                                point ->
                                    point
                                        .hasCount(1)
                                        .hasSum(0.03 + 0.1 + 0.016 + 0.024)
                                        .hasAttributes(clientAttributes)
                                        .hasBucketBoundaries(latencyBuckets)
                                        .hasBucketCounts(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                                            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0,
                                            0, 0, 0, 0, 0, 0, 0, 0, 0))));

    assertThat(openTelemetryTesting.getMetrics())
        .extracting("name")
        .doesNotContain(
            CLIENT_CALL_RETRIES,
            CLIENT_CALL_TRANSPARENT_RETRIES,
            CLIENT_CALL_HEDGES,
            CLIENT_CALL_RETRY_DELAY);
  }

  @Test
  public void clientBasicMetrics_withRetryMetricsEnabled_shouldRecordZeroOrBeAbsent() {
    // Explicitly enable the retry metrics
    Map<String, Boolean> enabledMetrics = ImmutableMap.of(
        CLIENT_CALL_RETRIES, true,
        CLIENT_CALL_TRANSPARENT_RETRIES, true,
        CLIENT_CALL_HEDGES, true,
        CLIENT_CALL_RETRY_DELAY, true
    );

    String target = "target:///";
    OpenTelemetryMetricsResource resource = GrpcOpenTelemetry.createMetricInstruments(testMeter,
        enabledMetrics, disableDefaultMetrics);
    OpenTelemetryMetricsModule module = newOpenTelemetryMetricsModule(resource);
    OpenTelemetryMetricsModule.CallAttemptsTracerFactory callAttemptsTracerFactory =
        new CallAttemptsTracerFactory(module, target, method.getFullMethodName(), emptyList());
    ClientStreamTracer tracer =
        callAttemptsTracerFactory.newClientStreamTracer(STREAM_INFO, new Metadata());

    fakeClock.forwardTime(30, TimeUnit.MILLISECONDS);
    tracer.outboundHeaders();
    fakeClock.forwardTime(100, TimeUnit.MILLISECONDS);
    tracer.outboundMessage(0);
    tracer.streamClosed(Status.OK);
    callAttemptsTracerFactory.callEnded(Status.OK);

    io.opentelemetry.api.common.Attributes finalAttributes
        = io.opentelemetry.api.common.Attributes.of(
        TARGET_KEY, target,
        METHOD_KEY, method.getFullMethodName());

    assertThat(openTelemetryTesting.getMetrics())
        .satisfiesExactlyInAnyOrder(
            metric -> assertThat(metric).hasName(CLIENT_ATTEMPT_COUNT_INSTRUMENT_NAME),
            metric -> assertThat(metric).hasName(CLIENT_ATTEMPT_DURATION_INSTRUMENT_NAME),
            metric -> assertThat(metric).hasName(CLIENT_ATTEMPT_SENT_TOTAL_COMPRESSED_MESSAGE_SIZE),
            metric -> assertThat(metric).hasName(CLIENT_ATTEMPT_RECV_TOTAL_COMPRESSED_MESSAGE_SIZE),
            metric -> assertThat(metric).hasName(CLIENT_CALL_DURATION),
            metric -> assertThat(metric)
                .hasName(CLIENT_CALL_RETRY_DELAY)
                .hasHistogramSatisfying(
                    histogram ->
                        histogram.hasPointsSatisfying(
                            point ->
                                point
                                    .hasSum(0)
                                    .hasCount(1)
                                    .hasAttributes(finalAttributes)))

        );

    List<String> optionalMetricNames = Arrays.asList(
        CLIENT_CALL_RETRIES,
        CLIENT_CALL_TRANSPARENT_RETRIES,
        CLIENT_CALL_HEDGES);

    for (String metricName : optionalMetricNames) {
      Optional<MetricData> metric = openTelemetryTesting.getMetrics().stream()
          .filter(m -> m.getName().equals(metricName))
          .findFirst();
      if (metric.isPresent()) {
        assertThat(metric.get())
            .hasHistogramSatisfying(
                histogram ->
                    histogram.hasPointsSatisfying(
                        point ->
                            point
                                .hasSum(0)
                                .hasCount(1)
                                .hasAttributes(finalAttributes)));
      }
    }
  }

  // This test is only unit-testing the metrics recording logic. The retry behavior is faked.
  @Test
  public void recordAttemptMetrics() {
    String target = "dns:///example.com";
    OpenTelemetryMetricsResource resource = GrpcOpenTelemetry.createMetricInstruments(testMeter,
        enabledMetricsMap, disableDefaultMetrics);
    OpenTelemetryMetricsModule module = newOpenTelemetryMetricsModule(resource);
    OpenTelemetryMetricsModule.CallAttemptsTracerFactory callAttemptsTracerFactory =
        new OpenTelemetryMetricsModule.CallAttemptsTracerFactory(module, target,
            method.getFullMethodName(), emptyList());
    ClientStreamTracer tracer =
        callAttemptsTracerFactory.newClientStreamTracer(STREAM_INFO, new Metadata());

    io.opentelemetry.api.common.Attributes attributes = io.opentelemetry.api.common.Attributes.of(
        TARGET_KEY, target,
        METHOD_KEY, method.getFullMethodName());

    assertThat(openTelemetryTesting.getMetrics())
        .satisfiesExactly(
            metric ->
                assertThat(metric)
                    .hasInstrumentationScope(InstrumentationScopeInfo.create(
                        OpenTelemetryConstants.INSTRUMENTATION_SCOPE))
                    .hasName(CLIENT_ATTEMPT_COUNT_INSTRUMENT_NAME)
                    .hasUnit("{attempt}")
                    .hasLongSumSatisfying(
                        longSum ->
                            longSum
                                .hasPointsSatisfying(
                                    point ->
                                        point
                                            .hasAttributes(attributes)
                                            .hasValue(1))));

    fakeClock.forwardTime(30, TimeUnit.MILLISECONDS);
    tracer.outboundHeaders();
    fakeClock.forwardTime(100, TimeUnit.MILLISECONDS);
    tracer.outboundMessage(0);
    tracer.outboundMessage(1);
    tracer.outboundWireSize(1028);
    fakeClock.forwardTime(24, TimeUnit.MILLISECONDS);
    tracer.streamClosed(Status.UNAVAILABLE);

    io.opentelemetry.api.common.Attributes clientAttributes
        = io.opentelemetry.api.common.Attributes.of(
        TARGET_KEY, target,
        METHOD_KEY, method.getFullMethodName(),
        STATUS_KEY, Code.UNAVAILABLE.toString());

    assertThat(openTelemetryTesting.getMetrics())
        .satisfiesExactlyInAnyOrder(
            metric ->
                assertThat(metric)
                    .hasInstrumentationScope(InstrumentationScopeInfo.create(
                        OpenTelemetryConstants.INSTRUMENTATION_SCOPE))
                    .hasName(CLIENT_ATTEMPT_COUNT_INSTRUMENT_NAME)
                    .hasUnit("{attempt}")
                    .hasLongSumSatisfying(
                        longSum ->
                            longSum
                                .hasPointsSatisfying(
                                    point ->
                                        point
                                            .hasAttributes(attributes)
                                            .hasValue(1))),
            metric ->
                assertThat(metric)
                    .hasInstrumentationScope(InstrumentationScopeInfo.create(
                        OpenTelemetryConstants.INSTRUMENTATION_SCOPE))
                    .hasName(CLIENT_ATTEMPT_DURATION_INSTRUMENT_NAME)
                    .hasUnit("s")
                    .hasHistogramSatisfying(
                        histogram ->
                            histogram.hasPointsSatisfying(
                                point ->
                                    point
                                        .hasCount(1)
                                        .hasSum(0.03 + 0.1 + 0.024)
                                        .hasAttributes(clientAttributes)
                                        .hasBucketBoundaries(latencyBuckets))),
            metric ->
                assertThat(metric)
                    .hasInstrumentationScope(InstrumentationScopeInfo.create(
                        OpenTelemetryConstants.INSTRUMENTATION_SCOPE))
                    .hasName(
                        CLIENT_ATTEMPT_SENT_TOTAL_COMPRESSED_MESSAGE_SIZE)
                    .hasUnit("By")
                    .hasHistogramSatisfying(
                        histogram ->
                            histogram.hasPointsSatisfying(
                                point ->
                                    point
                                        .hasCount(1)
                                        .hasSum(1028L)
                                        .hasAttributes(clientAttributes)
                                        .hasBucketBoundaries(sizeBuckets))),
            metric ->
                assertThat(metric)
                    .hasInstrumentationScope(InstrumentationScopeInfo.create(
                        OpenTelemetryConstants.INSTRUMENTATION_SCOPE))
                    .hasName(
                        CLIENT_ATTEMPT_RECV_TOTAL_COMPRESSED_MESSAGE_SIZE)
                    .hasUnit("By")
                    .hasHistogramSatisfying(
                        histogram ->
                            histogram
                                .isCumulative()
                                .hasPointsSatisfying(
                                    point ->
                                        point
                                            .hasCount(1)
                                            .hasSum(0)
                                            .hasAttributes(clientAttributes)
                                            .hasBucketBoundaries(sizeBuckets))));


    // faking retry
    fakeClock.forwardTime(1000, TimeUnit.MILLISECONDS);
    tracer = callAttemptsTracerFactory.newClientStreamTracer(STREAM_INFO, new Metadata());

    tracer.outboundHeaders();
    tracer.outboundMessage(0);
    tracer.outboundMessage(1);
    tracer.outboundWireSize(1028);
    fakeClock.forwardTime(100, TimeUnit.MILLISECONDS);
    tracer.streamClosed(Status.NOT_FOUND);

    io.opentelemetry.api.common.Attributes clientAttributes1
        = io.opentelemetry.api.common.Attributes.of(
        TARGET_KEY, target,
        METHOD_KEY, method.getFullMethodName(),
        STATUS_KEY, Code.NOT_FOUND.toString());

    // Histograms are cumulative by default.
    assertThat(openTelemetryTesting.getMetrics())
        .satisfiesExactlyInAnyOrder(
            metric ->
                assertThat(metric)
                    .hasInstrumentationScope(InstrumentationScopeInfo.create(
                        OpenTelemetryConstants.INSTRUMENTATION_SCOPE))
                    .hasName(CLIENT_ATTEMPT_COUNT_INSTRUMENT_NAME)
                    .hasUnit("{attempt}")
                    .hasLongSumSatisfying(
                        longSum ->
                            longSum
                                .hasPointsSatisfying(
                                    point ->
                                        point
                                            .hasValue(2)
                                            .hasAttributes(attributes))),
            metric ->
                assertThat(metric)
                    .hasInstrumentationScope(InstrumentationScopeInfo.create(
                        OpenTelemetryConstants.INSTRUMENTATION_SCOPE))
                    .hasName(CLIENT_ATTEMPT_DURATION_INSTRUMENT_NAME)
                    .hasUnit("s")
                    .hasHistogramSatisfying(
                        histogram ->
                            histogram.hasPointsSatisfying(
                                point ->
                                    point
                                        .hasCount(1)
                                        .hasSum(0.1)
                                        .hasAttributes(clientAttributes1)
                                        .hasBucketBoundaries(latencyBuckets),
                                point ->
                                    point
                                        .hasCount(1)
                                        .hasSum(0.154)
                                        .hasAttributes(clientAttributes)
                                        .hasBucketBoundaries(latencyBuckets))),
            metric ->
                assertThat(metric)
                    .hasInstrumentationScope(InstrumentationScopeInfo.create(
                        OpenTelemetryConstants.INSTRUMENTATION_SCOPE))
                    .hasName(
                        CLIENT_ATTEMPT_RECV_TOTAL_COMPRESSED_MESSAGE_SIZE)
                    .hasUnit("By")
                    .hasHistogramSatisfying(
                        histogram ->
                            histogram
                                .isCumulative()
                                .hasPointsSatisfying(
                                    point ->
                                        point
                                            .hasCount(1)
                                            .hasSum(0)
                                            .hasAttributes(clientAttributes1)
                                            .hasBucketBoundaries(sizeBuckets),
                                    point ->
                                        point
                                            .hasCount(1)
                                            .hasSum(0)
                                            .hasAttributes(clientAttributes)
                                            .hasBucketBoundaries(sizeBuckets))),
            metric ->
                assertThat(metric)
                    .hasInstrumentationScope(InstrumentationScopeInfo.create(
                        OpenTelemetryConstants.INSTRUMENTATION_SCOPE))
                    .hasName(
                        CLIENT_ATTEMPT_SENT_TOTAL_COMPRESSED_MESSAGE_SIZE)
                    .hasUnit("By")
                    .hasHistogramSatisfying(
                        histogram ->
                            histogram.hasPointsSatisfying(
                                point ->
                                    point
                                        .hasCount(1)
                                        .hasSum(1028L)
                                        .hasAttributes(clientAttributes1)
                                        .hasBucketBoundaries(sizeBuckets),
                                point ->
                                    point
                                        .hasCount(1)
                                        .hasSum(1028L)
                                        .hasAttributes(clientAttributes)
                                        .hasBucketBoundaries(sizeBuckets))));

    // fake transparent retry
    fakeClock.forwardTime(10, TimeUnit.MILLISECONDS);
    tracer = callAttemptsTracerFactory.newClientStreamTracer(
        STREAM_INFO.toBuilder().setIsTransparentRetry(true).build(), new Metadata());
    fakeClock.forwardTime(32, MILLISECONDS);
    tracer.streamClosed(Status.UNAVAILABLE);


    // Histograms are cumulative by default.
    assertThat(openTelemetryTesting.getMetrics())
        .satisfiesExactlyInAnyOrder(
            metric ->
                assertThat(metric)
                    .hasInstrumentationScope(InstrumentationScopeInfo.create(
                        OpenTelemetryConstants.INSTRUMENTATION_SCOPE))
                    .hasName(CLIENT_ATTEMPT_COUNT_INSTRUMENT_NAME)
                    .hasUnit("{attempt}")
                    .hasLongSumSatisfying(
                        longSum ->
                            longSum
                                .hasPointsSatisfying(
                                    point ->
                                        point
                                            .hasValue(3)
                                            .hasAttributes(attributes))),
            metric ->
                assertThat(metric)
                    .hasInstrumentationScope(InstrumentationScopeInfo.create(
                        OpenTelemetryConstants.INSTRUMENTATION_SCOPE))
                    .hasName(CLIENT_ATTEMPT_DURATION_INSTRUMENT_NAME)
                    .hasUnit("s")
                    .hasHistogramSatisfying(
                        histogram ->
                            histogram.hasPointsSatisfying(
                                point ->
                                    point
                                        .hasCount(1)
                                        .hasSum(0.1)
                                        .hasAttributes(clientAttributes1)
                                        .hasBucketBoundaries(latencyBuckets),
                                point ->
                                    point
                                        .hasCount(2)
                                        .hasSum(0.154 + 0.032)
                                        .hasAttributes(clientAttributes)
                                        .hasBucketBoundaries(latencyBuckets))),
            metric ->
                assertThat(metric)
                    .hasInstrumentationScope(InstrumentationScopeInfo.create(
                        OpenTelemetryConstants.INSTRUMENTATION_SCOPE))
                    .hasName(
                        CLIENT_ATTEMPT_RECV_TOTAL_COMPRESSED_MESSAGE_SIZE)
                    .hasUnit("By")
                    .hasHistogramSatisfying(
                        histogram ->
                            histogram
                                .isCumulative()
                                .hasPointsSatisfying(
                                    point ->
                                        point
                                            .hasCount(1)
                                            .hasSum(0)
                                            .hasAttributes(clientAttributes1)
                                            .hasBucketBoundaries(sizeBuckets),
                                    point ->
                                        point
                                            .hasCount(2)
                                            .hasSum(0 + 0)
                                            .hasAttributes(clientAttributes)
                                            .hasBucketBoundaries(sizeBuckets))),
            metric ->
                assertThat(metric)
                    .hasInstrumentationScope(InstrumentationScopeInfo.create(
                        OpenTelemetryConstants.INSTRUMENTATION_SCOPE))
                    .hasName(
                        CLIENT_ATTEMPT_SENT_TOTAL_COMPRESSED_MESSAGE_SIZE)
                    .hasUnit("By")
                    .hasHistogramSatisfying(
                        histogram ->
                            histogram.hasPointsSatisfying(
                                point ->
                                    point
                                        .hasCount(1)
                                        .hasSum(1028L)
                                        .hasAttributes(clientAttributes1)
                                        .hasBucketBoundaries(sizeBuckets),
                                point ->
                                    point
                                        .hasCount(2)
                                        .hasSum(1028L + 0)
                                        .hasAttributes(clientAttributes)
                                        .hasBucketBoundaries(sizeBuckets))));

    // fake another transparent retry
    fakeClock.forwardTime(10, MILLISECONDS);
    tracer = callAttemptsTracerFactory.newClientStreamTracer(
        STREAM_INFO.toBuilder().setIsTransparentRetry(true).build(), new Metadata());
    tracer.outboundHeaders();
    tracer.outboundMessage(0);
    tracer.outboundMessage(1);
    tracer.outboundWireSize(1028);
    tracer.inboundMessage(0);
    tracer.inboundWireSize(33);
    fakeClock.forwardTime(24, MILLISECONDS);
    // RPC succeeded
    tracer.streamClosed(Status.OK);
    callAttemptsTracerFactory.callEnded(Status.OK);

    io.opentelemetry.api.common.Attributes clientAttributes2
        = io.opentelemetry.api.common.Attributes.of(
        TARGET_KEY, target,
        METHOD_KEY, method.getFullMethodName(),
        STATUS_KEY, Code.OK.toString());

    assertThat(openTelemetryTesting.getMetrics())
        .satisfiesExactlyInAnyOrder(
            metric ->
                assertThat(metric)
                    .hasInstrumentationScope(InstrumentationScopeInfo.create(
                        OpenTelemetryConstants.INSTRUMENTATION_SCOPE))
                    .hasName(CLIENT_ATTEMPT_COUNT_INSTRUMENT_NAME)
                    .hasUnit("{attempt}")
                    .hasLongSumSatisfying(
                        longSum ->
                            longSum
                                .hasPointsSatisfying(
                                    point ->
                                        point
                                            .hasValue(4)
                                            .hasAttributes(attributes))),
            metric ->
                assertThat(metric)
                    .hasInstrumentationScope(InstrumentationScopeInfo.create(
                        OpenTelemetryConstants.INSTRUMENTATION_SCOPE))
                    .hasName(
                        CLIENT_ATTEMPT_SENT_TOTAL_COMPRESSED_MESSAGE_SIZE)
                    .hasUnit("By")
                    .hasHistogramSatisfying(
                        histogram ->
                            histogram.hasPointsSatisfying(
                                point ->
                                    point
                                        .hasCount(1)
                                        .hasSum(1028L)
                                        .hasAttributes(clientAttributes1)
                                        .hasBucketBoundaries(sizeBuckets),
                                point ->
                                    point
                                        .hasCount(2)
                                        .hasSum(1028L + 0)
                                        .hasAttributes(clientAttributes)
                                        .hasBucketBoundaries(sizeBuckets),
                                point ->
                                    point
                                        .hasCount(1)
                                        .hasSum(1028L)
                                        .hasAttributes(clientAttributes2)
                                        .hasBucketBoundaries(sizeBuckets))),
            metric ->
                assertThat(metric)
                    .hasInstrumentationScope(InstrumentationScopeInfo.create(
                        OpenTelemetryConstants.INSTRUMENTATION_SCOPE))
                    .hasName(CLIENT_CALL_DURATION)
                    .hasUnit("s")
                    .hasHistogramSatisfying(
                        histogram ->
                            histogram.hasPointsSatisfying(
                                point ->
                                    point
                                        .hasCount(1)
                                        .hasSum(0.03 + 0.1 + 0.024 + 1 + 0.1 + 0.01 + 0.032 + 0.01
                                            + 0.024)
                                        .hasAttributes(clientAttributes2)
                                        .hasBucketBoundaries(latencyBuckets))),
            metric ->
                assertThat(metric)
                    .hasInstrumentationScope(InstrumentationScopeInfo.create(
                        OpenTelemetryConstants.INSTRUMENTATION_SCOPE))
                    .hasName(CLIENT_ATTEMPT_DURATION_INSTRUMENT_NAME)
                    .hasUnit("s")
                    .hasHistogramSatisfying(
                        histogram ->
                            histogram.hasPointsSatisfying(
                                point ->
                                    point
                                        .hasCount(1)
                                        .hasSum(0.100)
                                        .hasAttributes(clientAttributes1)
                                        .hasBucketBoundaries(latencyBuckets),
                                point ->
                                    point
                                        .hasCount(2)
                                        .hasSum(0.154 + 0.032)
                                        .hasAttributes(clientAttributes)
                                        .hasBucketBoundaries(latencyBuckets),
                                point ->
                                    point
                                        .hasCount(1)
                                        .hasSum(0.024)
                                        .hasAttributes(clientAttributes2)
                                        .hasBucketBoundaries(latencyBuckets))),
            metric ->
                assertThat(metric)
                    .hasInstrumentationScope(InstrumentationScopeInfo.create(
                        OpenTelemetryConstants.INSTRUMENTATION_SCOPE))
                    .hasName(
                        CLIENT_ATTEMPT_RECV_TOTAL_COMPRESSED_MESSAGE_SIZE)
                    .hasUnit("By")
                    .hasHistogramSatisfying(
                        histogram ->
                            histogram
                                .isCumulative()
                                .hasPointsSatisfying(
                                    point ->
                                        point
                                            .hasCount(1)
                                            .hasSum(0)
                                            .hasAttributes(clientAttributes1)
                                            .hasBucketBoundaries(sizeBuckets),
                                    point ->
                                        point
                                            .hasCount(2)
                                            .hasSum(0 + 0)
                                            .hasAttributes(clientAttributes)
                                            .hasBucketBoundaries(sizeBuckets),
                                    point ->
                                        point
                                            .hasCount(1)
                                            .hasSum(33D)
                                            .hasAttributes(clientAttributes2)
                                            .hasBucketBoundaries(sizeBuckets))));
  }

  @Test
  public void recordAttemptMetrics_withRetryMetricsEnabled() {
    Map<String, Boolean> enabledMetrics = ImmutableMap.of(
        CLIENT_CALL_RETRIES, true,
        CLIENT_CALL_TRANSPARENT_RETRIES, true,
        CLIENT_CALL_HEDGES, true,
        CLIENT_CALL_RETRY_DELAY, true
    );

    String target = "dns:///example.com";
    OpenTelemetryMetricsResource resource = GrpcOpenTelemetry.createMetricInstruments(testMeter,
        enabledMetrics, disableDefaultMetrics);
    OpenTelemetryMetricsModule module = newOpenTelemetryMetricsModule(resource);
    OpenTelemetryMetricsModule.CallAttemptsTracerFactory callAttemptsTracerFactory =
        new OpenTelemetryMetricsModule.CallAttemptsTracerFactory(module, target,
            method.getFullMethodName(), emptyList());

    ClientStreamTracer tracer =
        callAttemptsTracerFactory.newClientStreamTracer(STREAM_INFO, new Metadata());
    fakeClock.forwardTime(154, TimeUnit.MILLISECONDS);
    tracer.streamClosed(Status.UNAVAILABLE);

    fakeClock.forwardTime(1000, TimeUnit.MILLISECONDS);
    tracer = callAttemptsTracerFactory.newClientStreamTracer(STREAM_INFO, new Metadata());
    fakeClock.forwardTime(100, TimeUnit.MILLISECONDS);
    tracer.streamClosed(Status.NOT_FOUND);

    fakeClock.forwardTime(10, TimeUnit.MILLISECONDS);
    tracer = callAttemptsTracerFactory.newClientStreamTracer(
        STREAM_INFO.toBuilder().setIsTransparentRetry(true).build(), new Metadata());
    fakeClock.forwardTime(32, MILLISECONDS);
    tracer.streamClosed(Status.UNAVAILABLE);

    fakeClock.forwardTime(10, MILLISECONDS);
    tracer = callAttemptsTracerFactory.newClientStreamTracer(
        STREAM_INFO.toBuilder().setIsTransparentRetry(true).build(), new Metadata());
    tracer.inboundWireSize(33);
    fakeClock.forwardTime(24, MILLISECONDS);
    tracer.streamClosed(Status.OK); // RPC succeeded

    // --- The overall call ends ---
    callAttemptsTracerFactory.callEnded(Status.OK);

    // Define attributes for assertions
    io.opentelemetry.api.common.Attributes finalAttributes
        = io.opentelemetry.api.common.Attributes.of(
        TARGET_KEY, target,
        METHOD_KEY, method.getFullMethodName());

    // FINAL ASSERTION BLOCK
    assertThat(openTelemetryTesting.getMetrics())
        .satisfiesExactlyInAnyOrder(
            // Default metrics
            metric -> assertThat(metric).hasName(CLIENT_ATTEMPT_COUNT_INSTRUMENT_NAME),
            metric -> assertThat(metric).hasName(CLIENT_ATTEMPT_DURATION_INSTRUMENT_NAME),
            metric -> assertThat(metric).hasName(CLIENT_ATTEMPT_SENT_TOTAL_COMPRESSED_MESSAGE_SIZE),
            metric -> assertThat(metric).hasName(CLIENT_ATTEMPT_RECV_TOTAL_COMPRESSED_MESSAGE_SIZE),
            metric -> assertThat(metric).hasName(CLIENT_CALL_DURATION),

            // --- Assertions for the retry metrics ---
            metric -> assertThat(metric)
                .hasName(CLIENT_CALL_RETRIES)
                .hasUnit("{retry}")
                .hasHistogramSatisfying(histogram -> histogram.hasPointsSatisfying(
                    point -> point
                        .hasCount(1)
                        .hasSum(1) // We faked one standard retry
                        .hasAttributes(finalAttributes))),
            metric -> assertThat(metric)
                .hasName(CLIENT_CALL_TRANSPARENT_RETRIES)
                .hasUnit("{transparent_retry}")
                .hasHistogramSatisfying(histogram -> histogram.hasPointsSatisfying(
                    point -> point
                        .hasCount(1)
                        .hasSum(2) // We faked two transparent retries
                        .hasAttributes(finalAttributes))),
            metric -> assertThat(metric)
                .hasName(CLIENT_CALL_RETRY_DELAY)
                .hasUnit("s")
                .hasHistogramSatisfying(histogram -> histogram.hasPointsSatisfying(
                    point -> point
                        .hasCount(1)
                        .hasSum(1.02) // 1000ms + 10ms + 10ms
                        .hasAttributes(finalAttributes)))
        );
  }

  @Test
  public void recordAttemptMetrics_withHedgedCalls() {
    // Enable the retry metrics, including hedges
    Map<String, Boolean> enabledMetrics = ImmutableMap.of(
        CLIENT_CALL_RETRIES, true,
        CLIENT_CALL_TRANSPARENT_RETRIES, true,
        CLIENT_CALL_HEDGES, true,
        CLIENT_CALL_RETRY_DELAY, true
    );

    String target = "dns:///example.com";
    OpenTelemetryMetricsResource resource = GrpcOpenTelemetry.createMetricInstruments(testMeter,
        enabledMetrics, disableDefaultMetrics);
    OpenTelemetryMetricsModule module = newOpenTelemetryMetricsModule(resource);
    OpenTelemetryMetricsModule.CallAttemptsTracerFactory callAttemptsTracerFactory =
        new OpenTelemetryMetricsModule.CallAttemptsTracerFactory(module, target,
            method.getFullMethodName(), emptyList());

    // Create a StreamInfo specifically for hedged attempts
    final ClientStreamTracer.StreamInfo hedgedStreamInfo =
        STREAM_INFO.toBuilder().setIsHedging(true).build();

    // --- First attempt starts ---
    ClientStreamTracer tracer =
        callAttemptsTracerFactory.newClientStreamTracer(STREAM_INFO, new Metadata());

    // --- Faking a hedged attempt ---
    fakeClock.forwardTime(10, TimeUnit.MILLISECONDS); // Hedging delay
    ClientStreamTracer hedgeTracer1 =
        callAttemptsTracerFactory.newClientStreamTracer(hedgedStreamInfo, new Metadata());

    // --- Faking a second hedged attempt ---
    fakeClock.forwardTime(20, TimeUnit.MILLISECONDS); // Another hedging delay
    ClientStreamTracer hedgeTracer2 =
        callAttemptsTracerFactory.newClientStreamTracer(hedgedStreamInfo, new Metadata());

    // --- Let the attempts resolve ---
    fakeClock.forwardTime(50, TimeUnit.MILLISECONDS);
    // Initial attempt is cancelled because a hedge will succeed
    tracer.streamClosed(Status.CANCELLED);
    hedgeTracer1.streamClosed(Status.UNAVAILABLE); // First hedge fails

    fakeClock.forwardTime(30, TimeUnit.MILLISECONDS);
    hedgeTracer2.streamClosed(Status.OK); // Second hedge succeeds

    // --- The overall call ends ---
    callAttemptsTracerFactory.callEnded(Status.OK);

    // Define attributes for assertions
    io.opentelemetry.api.common.Attributes finalAttributes
        = io.opentelemetry.api.common.Attributes.of(
        TARGET_KEY, target,
        METHOD_KEY, method.getFullMethodName());

    // FINAL ASSERTION BLOCK
    // We expect 7 metrics: 5 default + hedges + retry_delay.
    // Retries and transparent_retries are 0 and will not be reported.
    assertThat(openTelemetryTesting.getMetrics())
        .satisfiesExactlyInAnyOrder(
            // Default metrics
            metric -> assertThat(metric).hasName(CLIENT_ATTEMPT_COUNT_INSTRUMENT_NAME),
            metric -> assertThat(metric).hasName(CLIENT_ATTEMPT_DURATION_INSTRUMENT_NAME),
            metric -> assertThat(metric).hasName(CLIENT_ATTEMPT_SENT_TOTAL_COMPRESSED_MESSAGE_SIZE),
            metric -> assertThat(metric).hasName(CLIENT_ATTEMPT_RECV_TOTAL_COMPRESSED_MESSAGE_SIZE),
            metric -> assertThat(metric).hasName(CLIENT_CALL_DURATION),

            // --- Assertions for the NEW metrics ---
            metric -> assertThat(metric)
                .hasName(CLIENT_CALL_HEDGES)
                .hasUnit("{hedge}")
                .hasHistogramSatisfying(histogram -> histogram.hasPointsSatisfying(
                    point -> point
                        .hasCount(1)
                        .hasSum(2)
                        .hasAttributes(finalAttributes))),
            metric -> assertThat(metric)
                .hasName(CLIENT_CALL_RETRY_DELAY)
                .hasUnit("s")
                .hasHistogramSatisfying(
                    histogram ->
                        histogram.hasPointsSatisfying(
                            point ->
                                point
                                    .hasCount(1)
                                    .hasSum(0)
                                    .hasAttributes(finalAttributes)))
        );
  }

  @Test
  public void clientStreamNeverCreatedStillRecordMetrics() {
    String target = "dns:///foo.example.com";
    OpenTelemetryMetricsResource resource = GrpcOpenTelemetry.createMetricInstruments(testMeter,
        enabledMetricsMap, disableDefaultMetrics);
    OpenTelemetryMetricsModule module = newOpenTelemetryMetricsModule(resource);
    OpenTelemetryMetricsModule.CallAttemptsTracerFactory callAttemptsTracerFactory =
        new OpenTelemetryMetricsModule.CallAttemptsTracerFactory(module, target,
            method.getFullMethodName(), emptyList());
    fakeClock.forwardTime(3000, MILLISECONDS);
    Status status = Status.DEADLINE_EXCEEDED.withDescription("5 seconds");
    callAttemptsTracerFactory.callEnded(status);

    io.opentelemetry.api.common.Attributes attemptStartedAttributes
        = io.opentelemetry.api.common.Attributes.of(
        TARGET_KEY, target,
        METHOD_KEY, method.getFullMethodName());

    io.opentelemetry.api.common.Attributes clientAttributes
        = io.opentelemetry.api.common.Attributes.of(
        TARGET_KEY, target,
        METHOD_KEY, method.getFullMethodName(),
        STATUS_KEY,
        Code.DEADLINE_EXCEEDED.toString());

    assertThat(openTelemetryTesting.getMetrics())
        .satisfiesExactlyInAnyOrder(
            metric ->
                assertThat(metric)
                    .hasInstrumentationScope(InstrumentationScopeInfo.create(
                        OpenTelemetryConstants.INSTRUMENTATION_SCOPE))
                    .hasName(CLIENT_ATTEMPT_COUNT_INSTRUMENT_NAME)
                    .hasUnit("{attempt}")
                    .hasLongSumSatisfying(
                        longSum ->
                            longSum
                                .hasPointsSatisfying(
                                    point ->
                                        point
                                            .hasValue(1)
                                            .hasAttributes(attemptStartedAttributes))),
            metric ->
                assertThat(metric)
                    .hasInstrumentationScope(InstrumentationScopeInfo.create(
                        OpenTelemetryConstants.INSTRUMENTATION_SCOPE))
                    .hasName(
                        CLIENT_ATTEMPT_SENT_TOTAL_COMPRESSED_MESSAGE_SIZE)
                    .hasUnit("By")
                    .hasHistogramSatisfying(
                        histogram ->
                            histogram.hasPointsSatisfying(
                                point ->
                                    point
                                        .hasCount(1)
                                        .hasSum(0)
                                        .hasAttributes(clientAttributes)
                                        .hasBucketBoundaries(sizeBuckets))),
            metric ->
                assertThat(metric)
                    .hasInstrumentationScope(InstrumentationScopeInfo.create(
                        OpenTelemetryConstants.INSTRUMENTATION_SCOPE))
                    .hasName(CLIENT_CALL_DURATION)
                    .hasUnit("s")
                    .hasHistogramSatisfying(
                        histogram ->
                            histogram.hasPointsSatisfying(
                                point ->
                                    point
                                        .hasCount(1)
                                        .hasSum(3D)
                                        .hasAttributes(clientAttributes)
                                        .hasBucketBoundaries(latencyBuckets))),
            metric ->
                assertThat(metric)
                    .hasInstrumentationScope(InstrumentationScopeInfo.create(
                        OpenTelemetryConstants.INSTRUMENTATION_SCOPE))
                    .hasName(CLIENT_ATTEMPT_DURATION_INSTRUMENT_NAME)
                    .hasUnit("s")
                    .hasHistogramSatisfying(
                        histogram ->
                            histogram.hasPointsSatisfying(
                                point ->
                                    point
                                        .hasCount(1)
                                        .hasSum(0)
                                        .hasAttributes(clientAttributes)
                                        .hasBucketBoundaries(latencyBuckets))),
            metric ->
                assertThat(metric)
                    .hasInstrumentationScope(InstrumentationScopeInfo.create(
                        OpenTelemetryConstants.INSTRUMENTATION_SCOPE))
                    .hasName(
                        CLIENT_ATTEMPT_RECV_TOTAL_COMPRESSED_MESSAGE_SIZE)
                    .hasUnit("By")
                    .hasHistogramSatisfying(
                        histogram ->
                            histogram
                                .isCumulative()
                                .hasPointsSatisfying(
                                    point ->
                                        point
                                            .hasCount(1)
                                            .hasSum(0)
                                            .hasAttributes(clientAttributes)
                                            .hasBucketBoundaries(sizeBuckets))));

  }

  @Test
  public void clientLocalityMetrics_present() {
    String target = "target:///";
    OpenTelemetryMetricsResource resource = GrpcOpenTelemetry.createMetricInstruments(testMeter,
        enabledMetricsMap, disableDefaultMetrics);
    OpenTelemetryMetricsModule module = new OpenTelemetryMetricsModule(
            fakeClock.getStopwatchSupplier(), resource, Arrays.asList("grpc.lb.locality"),
            emptyList(), openTelemetryTesting.getOpenTelemetry().getPropagators());
    OpenTelemetryMetricsModule.CallAttemptsTracerFactory callAttemptsTracerFactory =
        new CallAttemptsTracerFactory(module, target, method.getFullMethodName(), emptyList());

    ClientStreamTracer tracer =
        callAttemptsTracerFactory.newClientStreamTracer(STREAM_INFO, new Metadata());
    tracer.addOptionalLabel("grpc.lb.foo", "unimportant");
    tracer.addOptionalLabel("grpc.lb.locality", "should-be-overwritten");
    tracer.addOptionalLabel("grpc.lb.locality", "the-moon");
    tracer.addOptionalLabel("grpc.lb.foo", "thats-no-moon");
    tracer.streamClosed(Status.OK);
    callAttemptsTracerFactory.callEnded(Status.OK);

    io.opentelemetry.api.common.Attributes attributes = io.opentelemetry.api.common.Attributes.of(
        TARGET_KEY, target,
        METHOD_KEY, method.getFullMethodName());

    io.opentelemetry.api.common.Attributes clientAttributes
        = io.opentelemetry.api.common.Attributes.of(
        TARGET_KEY, target,
        METHOD_KEY, method.getFullMethodName(),
        STATUS_KEY, Status.Code.OK.toString());

    io.opentelemetry.api.common.Attributes clientAttributesWithLocality
        = clientAttributes.toBuilder()
        .put(LOCALITY_KEY, "the-moon")
        .build();

    assertThat(openTelemetryTesting.getMetrics())
        .satisfiesExactlyInAnyOrder(
            metric ->
                assertThat(metric)
                    .hasName(CLIENT_ATTEMPT_COUNT_INSTRUMENT_NAME)
                    .hasLongSumSatisfying(
                        longSum -> longSum.hasPointsSatisfying(
                            point -> point.hasAttributes(attributes))),
            metric ->
                assertThat(metric)
                    .hasName(CLIENT_ATTEMPT_DURATION_INSTRUMENT_NAME)
                    .hasHistogramSatisfying(
                        histogram -> histogram.hasPointsSatisfying(
                            point -> point.hasAttributes(clientAttributesWithLocality))),
            metric ->
                assertThat(metric)
                    .hasName(CLIENT_ATTEMPT_SENT_TOTAL_COMPRESSED_MESSAGE_SIZE)
                    .hasHistogramSatisfying(
                        histogram -> histogram.hasPointsSatisfying(
                            point -> point.hasAttributes(clientAttributesWithLocality))),
            metric ->
                assertThat(metric)
                    .hasName(CLIENT_ATTEMPT_RECV_TOTAL_COMPRESSED_MESSAGE_SIZE)
                    .hasHistogramSatisfying(
                        histogram -> histogram.hasPointsSatisfying(
                            point -> point.hasAttributes(clientAttributesWithLocality))),
            metric ->
                assertThat(metric)
                    .hasName(CLIENT_CALL_DURATION)
                    .hasHistogramSatisfying(
                        histogram -> histogram.hasPointsSatisfying(
                            point -> point.hasAttributes(clientAttributes))));
  }

  @Test
  public void clientLocalityMetrics_missing() {
    String target = "target:///";
    OpenTelemetryMetricsResource resource = GrpcOpenTelemetry.createMetricInstruments(testMeter,
        enabledMetricsMap, disableDefaultMetrics);
    OpenTelemetryMetricsModule module = new OpenTelemetryMetricsModule(
            fakeClock.getStopwatchSupplier(), resource, Arrays.asList("grpc.lb.locality"),
            emptyList(), openTelemetryTesting.getOpenTelemetry().getPropagators());
    OpenTelemetryMetricsModule.CallAttemptsTracerFactory callAttemptsTracerFactory =
        new CallAttemptsTracerFactory(module, target, method.getFullMethodName(), emptyList());

    ClientStreamTracer tracer =
        callAttemptsTracerFactory.newClientStreamTracer(STREAM_INFO, new Metadata());
    tracer.streamClosed(Status.OK);
    callAttemptsTracerFactory.callEnded(Status.OK);

    io.opentelemetry.api.common.Attributes attributes = io.opentelemetry.api.common.Attributes.of(
        TARGET_KEY, target,
        METHOD_KEY, method.getFullMethodName());

    io.opentelemetry.api.common.Attributes clientAttributes
        = io.opentelemetry.api.common.Attributes.of(
        TARGET_KEY, target,
        METHOD_KEY, method.getFullMethodName(),
        STATUS_KEY, Status.Code.OK.toString());

    io.opentelemetry.api.common.Attributes clientAttributesWithLocality
        = clientAttributes.toBuilder()
        .put(LOCALITY_KEY, "")
        .build();

    assertThat(openTelemetryTesting.getMetrics())
        .satisfiesExactlyInAnyOrder(
            metric ->
                assertThat(metric)
                    .hasName(CLIENT_ATTEMPT_COUNT_INSTRUMENT_NAME)
                    .hasLongSumSatisfying(
                        longSum -> longSum.hasPointsSatisfying(
                            point -> point.hasAttributes(attributes))),
            metric ->
                assertThat(metric)
                    .hasName(CLIENT_ATTEMPT_DURATION_INSTRUMENT_NAME)
                    .hasHistogramSatisfying(
                        histogram -> histogram.hasPointsSatisfying(
                            point -> point.hasAttributes(clientAttributesWithLocality))),
            metric ->
                assertThat(metric)
                    .hasName(CLIENT_ATTEMPT_SENT_TOTAL_COMPRESSED_MESSAGE_SIZE)
                    .hasHistogramSatisfying(
                        histogram -> histogram.hasPointsSatisfying(
                            point -> point.hasAttributes(clientAttributesWithLocality))),
            metric ->
                assertThat(metric)
                    .hasName(CLIENT_ATTEMPT_RECV_TOTAL_COMPRESSED_MESSAGE_SIZE)
                    .hasHistogramSatisfying(
                        histogram -> histogram.hasPointsSatisfying(
                            point -> point.hasAttributes(clientAttributesWithLocality))),
            metric ->
                assertThat(metric)
                    .hasName(CLIENT_CALL_DURATION)
                    .hasHistogramSatisfying(
                        histogram -> histogram.hasPointsSatisfying(
                            point -> point.hasAttributes(clientAttributes))));
  }

  @Test
  public void clientBackendServiceMetrics_present() {
    String target = "target:///";
    OpenTelemetryMetricsResource resource = GrpcOpenTelemetry.createMetricInstruments(testMeter,
        enabledMetricsMap, disableDefaultMetrics);
    OpenTelemetryMetricsModule module = new OpenTelemetryMetricsModule(
        fakeClock.getStopwatchSupplier(), resource, Arrays.asList("grpc.lb.backend_service"),
            emptyList(), openTelemetryTesting.getOpenTelemetry().getPropagators());
    OpenTelemetryMetricsModule.CallAttemptsTracerFactory callAttemptsTracerFactory =
        new CallAttemptsTracerFactory(module, target, method.getFullMethodName(), emptyList());

    ClientStreamTracer tracer =
        callAttemptsTracerFactory.newClientStreamTracer(STREAM_INFO, new Metadata());
    tracer.addOptionalLabel("grpc.lb.foo", "unimportant");
    tracer.addOptionalLabel("grpc.lb.backend_service", "should-be-overwritten");
    tracer.addOptionalLabel("grpc.lb.backend_service", "the-moon");
    tracer.addOptionalLabel("grpc.lb.foo", "thats-no-moon");
    tracer.streamClosed(Status.OK);
    callAttemptsTracerFactory.callEnded(Status.OK);

    io.opentelemetry.api.common.Attributes attributes = io.opentelemetry.api.common.Attributes.of(
        TARGET_KEY, target,
        METHOD_KEY, method.getFullMethodName());

    io.opentelemetry.api.common.Attributes clientAttributes
        = io.opentelemetry.api.common.Attributes.of(
        TARGET_KEY, target,
        METHOD_KEY, method.getFullMethodName(),
        STATUS_KEY, Status.Code.OK.toString());

    io.opentelemetry.api.common.Attributes clientAttributesWithBackendService
        = clientAttributes.toBuilder()
        .put(AttributeKey.stringKey("grpc.lb.backend_service"), "the-moon")
        .build();

    assertThat(openTelemetryTesting.getMetrics())
        .satisfiesExactlyInAnyOrder(
            metric ->
                assertThat(metric)
                    .hasName(CLIENT_ATTEMPT_COUNT_INSTRUMENT_NAME)
                    .hasLongSumSatisfying(
                        longSum -> longSum.hasPointsSatisfying(
                            point -> point.hasAttributes(attributes))),
            metric ->
                assertThat(metric)
                    .hasName(CLIENT_ATTEMPT_DURATION_INSTRUMENT_NAME)
                    .hasHistogramSatisfying(
                        histogram -> histogram.hasPointsSatisfying(
                            point -> point.hasAttributes(clientAttributesWithBackendService))),
            metric ->
                assertThat(metric)
                    .hasName(CLIENT_ATTEMPT_SENT_TOTAL_COMPRESSED_MESSAGE_SIZE)
                    .hasHistogramSatisfying(
                        histogram -> histogram.hasPointsSatisfying(
                            point -> point.hasAttributes(clientAttributesWithBackendService))),
            metric ->
                assertThat(metric)
                    .hasName(CLIENT_ATTEMPT_RECV_TOTAL_COMPRESSED_MESSAGE_SIZE)
                    .hasHistogramSatisfying(
                        histogram -> histogram.hasPointsSatisfying(
                            point -> point.hasAttributes(clientAttributesWithBackendService))),
            metric ->
                assertThat(metric)
                    .hasName(CLIENT_CALL_DURATION)
                    .hasHistogramSatisfying(
                        histogram -> histogram.hasPointsSatisfying(
                            point -> point.hasAttributes(clientAttributes))));
  }

  @Test
  public void clientBackendServiceMetrics_missing() {
    String target = "target:///";
    OpenTelemetryMetricsResource resource = GrpcOpenTelemetry.createMetricInstruments(testMeter,
        enabledMetricsMap, disableDefaultMetrics);
    OpenTelemetryMetricsModule module = new OpenTelemetryMetricsModule(
        fakeClock.getStopwatchSupplier(), resource, Arrays.asList("grpc.lb.backend_service"),
            emptyList(), openTelemetryTesting.getOpenTelemetry().getPropagators());
    OpenTelemetryMetricsModule.CallAttemptsTracerFactory callAttemptsTracerFactory =
        new CallAttemptsTracerFactory(module, target, method.getFullMethodName(), emptyList());

    ClientStreamTracer tracer =
        callAttemptsTracerFactory.newClientStreamTracer(STREAM_INFO, new Metadata());
    tracer.streamClosed(Status.OK);
    callAttemptsTracerFactory.callEnded(Status.OK);

    io.opentelemetry.api.common.Attributes attributes = io.opentelemetry.api.common.Attributes.of(
        TARGET_KEY, target,
        METHOD_KEY, method.getFullMethodName());

    io.opentelemetry.api.common.Attributes clientAttributes
        = io.opentelemetry.api.common.Attributes.of(
        TARGET_KEY, target,
        METHOD_KEY, method.getFullMethodName(),
        STATUS_KEY, Status.Code.OK.toString());

    io.opentelemetry.api.common.Attributes clientAttributesWithBackendService
        = clientAttributes.toBuilder()
        .put(AttributeKey.stringKey("grpc.lb.backend_service"), "")
        .build();

    assertThat(openTelemetryTesting.getMetrics())
        .satisfiesExactlyInAnyOrder(
            metric ->
                assertThat(metric)
                    .hasName(CLIENT_ATTEMPT_COUNT_INSTRUMENT_NAME)
                    .hasLongSumSatisfying(
                        longSum -> longSum.hasPointsSatisfying(
                            point -> point.hasAttributes(attributes))),
            metric ->
                assertThat(metric)
                    .hasName(CLIENT_ATTEMPT_DURATION_INSTRUMENT_NAME)
                    .hasHistogramSatisfying(
                        histogram -> histogram.hasPointsSatisfying(
                            point -> point.hasAttributes(clientAttributesWithBackendService))),
            metric ->
                assertThat(metric)
                    .hasName(CLIENT_ATTEMPT_SENT_TOTAL_COMPRESSED_MESSAGE_SIZE)
                    .hasHistogramSatisfying(
                        histogram -> histogram.hasPointsSatisfying(
                            point -> point.hasAttributes(clientAttributesWithBackendService))),
            metric ->
                assertThat(metric)
                    .hasName(CLIENT_ATTEMPT_RECV_TOTAL_COMPRESSED_MESSAGE_SIZE)
                    .hasHistogramSatisfying(
                        histogram -> histogram.hasPointsSatisfying(
                            point -> point.hasAttributes(clientAttributesWithBackendService))),
            metric ->
                assertThat(metric)
                    .hasName(CLIENT_CALL_DURATION)
                    .hasHistogramSatisfying(
                        histogram -> histogram.hasPointsSatisfying(
                            point -> point.hasAttributes(clientAttributes))));
  }

  @Test
  public void serverBasicMetrics() {
    OpenTelemetryMetricsResource resource = GrpcOpenTelemetry.createMetricInstruments(testMeter,
        enabledMetricsMap, disableDefaultMetrics);
    OpenTelemetryMetricsModule module = newOpenTelemetryMetricsModule(resource);
    ServerStreamTracer.Factory tracerFactory = module.getServerTracerFactory();
    ServerStreamTracer tracer =
        tracerFactory.newServerStreamTracer(method.getFullMethodName(), new Metadata());
    tracer.serverCallStarted(
        new CallInfo<>(method, Attributes.EMPTY, null));

    io.opentelemetry.api.common.Attributes attributes = io.opentelemetry.api.common.Attributes.of(
        METHOD_KEY, method.getFullMethodName());

    assertThat(openTelemetryTesting.getMetrics())
        .satisfiesExactly(
            metric ->
                assertThat(metric)
                    .hasInstrumentationScope(InstrumentationScopeInfo.create(
                        OpenTelemetryConstants.INSTRUMENTATION_SCOPE))
                    .hasName(SERVER_CALL_COUNT)
                    .hasUnit("{call}")
                    .hasLongSumSatisfying(
                        longSum ->
                            longSum
                                .hasPointsSatisfying(
                                    point ->
                                        point
                                            .hasAttributes(attributes)
                                            .hasValue(1))));

    tracer.inboundMessage(0);
    tracer.inboundWireSize(34);
    fakeClock.forwardTime(100, MILLISECONDS);
    tracer.outboundMessage(0);
    tracer.outboundWireSize(1028);
    fakeClock.forwardTime(16, MILLISECONDS);
    tracer.inboundMessage(1);
    tracer.inboundWireSize(154);
    tracer.outboundMessage(1);
    tracer.outboundWireSize(99);
    fakeClock.forwardTime(24, MILLISECONDS);
    tracer.streamClosed(Status.CANCELLED);

    io.opentelemetry.api.common.Attributes serverAttributes
        = io.opentelemetry.api.common.Attributes.of(
        METHOD_KEY, method.getFullMethodName(),
        STATUS_KEY, Code.CANCELLED.toString());

    assertThat(openTelemetryTesting.getMetrics())
        .satisfiesExactlyInAnyOrder(
            metric ->
                assertThat(metric)
                    .hasInstrumentationScope(InstrumentationScopeInfo.create(
                        OpenTelemetryConstants.INSTRUMENTATION_SCOPE))
                    .hasName(
                        SERVER_CALL_SENT_TOTAL_COMPRESSED_MESSAGE_SIZE)
                    .hasUnit("By")
                    .hasHistogramSatisfying(
                        histogram ->
                            histogram.hasPointsSatisfying(
                                point ->
                                    point
                                        .hasCount(1)
                                        .hasSum(1028L + 99)
                                        .hasAttributes(serverAttributes)
                                        .hasBucketBoundaries(sizeBuckets)
                                        .hasBucketCounts(0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                                            0))),
            metric ->
                assertThat(metric)
                    .hasInstrumentationScope(InstrumentationScopeInfo.create(
                        OpenTelemetryConstants.INSTRUMENTATION_SCOPE))
                    .hasName(SERVER_CALL_COUNT)
                    .hasUnit("{call}")
                    .hasLongSumSatisfying(
                        longSum ->
                            longSum
                                .hasPointsSatisfying(
                                    point ->
                                        point
                                            .hasAttributes(attributes)
                                            .hasValue(1))),
            metric ->
                assertThat(metric)
                    .hasInstrumentationScope(InstrumentationScopeInfo.create(
                        OpenTelemetryConstants.INSTRUMENTATION_SCOPE))
                    .hasName(SERVER_CALL_DURATION)
                    .hasUnit("s")
                    .hasHistogramSatisfying(
                        histogram ->
                            histogram.hasPointsSatisfying(
                                point ->
                                    point
                                        .hasCount(1)
                                        .hasSum(0.1 + 0.016 + 0.024)
                                        .hasAttributes(serverAttributes)
                                        .hasBucketBoundaries(latencyBuckets)
                                        .hasBucketCounts(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                                            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0,
                                            0, 0, 0, 0, 0, 0, 0, 0, 0))),
            metric ->
                assertThat(metric)
                    .hasInstrumentationScope(InstrumentationScopeInfo.create(
                        OpenTelemetryConstants.INSTRUMENTATION_SCOPE))
                    .hasName(
                        SERVER_CALL_RECV_TOTAL_COMPRESSED_MESSAGE_SIZE)
                    .hasUnit("By")
                    .hasHistogramSatisfying(
                        histogram ->
                            histogram
                                .isCumulative()
                                .hasPointsSatisfying(
                                    point ->
                                        point
                                            .hasCount(1)
                                            .hasSum(34L + 154)
                                            .hasAttributes(serverAttributes)
                                            .hasBucketBoundaries(sizeBuckets)
                                            .hasBucketCounts(0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                                                0, 0))));

  }

  @Test
  public void serverBaggagePropagationToMetrics() {
    // 1. Define the test baggage
    Baggage testBaggage = Baggage.builder()
        .put("user-id", "67")
        .build();

    // 2. Inject baggage into headers
    Metadata headers = new Metadata();
    openTelemetryTesting.getOpenTelemetry().getPropagators().getTextMapPropagator()
            .inject(Context.root().with(testBaggage), headers, new TextMapSetter<Metadata>() {
                @Override
                public void set(Metadata carrier, String key, String value) {
                    carrier.put(Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER), value);
                }
            });

    // 3. Create module and tracer factory using the mock resource
    OpenTelemetryMetricsModule module = new OpenTelemetryMetricsModule(
            fakeClock.getStopwatchSupplier(), resource, emptyList(), emptyList(),
            openTelemetryTesting.getOpenTelemetry().getPropagators());
    ServerStreamTracer.Factory tracerFactory = module.getServerTracerFactory();
    ServerStreamTracer tracer = tracerFactory.newServerStreamTracer(method.getFullMethodName(),
            headers);

    // 4. Trigger metric recording
    tracer.streamClosed(Status.OK);

    // 5. Verify the record call and capture the OTel Context
    verify(mockServerCallDurationHistogram).record(
        anyDouble(),
        any(io.opentelemetry.api.common.Attributes.class),
        contextCaptor.capture());

    // 6. Assert on the captured OTel Context
    io.opentelemetry.context.Context capturedOtelContext = contextCaptor.getValue();
    Baggage capturedBaggage = Baggage.fromContext(capturedOtelContext);

    assertEquals("67", capturedBaggage.getEntryValue("user-id"));
  }

  @Test
  public void targetAttributeFilter_notSet_usesOriginalTarget() {
    // Test that when no filter is set, the original target is used
    String target = "dns:///example.com";
    OpenTelemetryMetricsResource resource = GrpcOpenTelemetry.createMetricInstruments(testMeter,
        enabledMetricsMap, disableDefaultMetrics);
    OpenTelemetryMetricsModule module = newOpenTelemetryMetricsModule(resource);

    Channel interceptedChannel =
        ClientInterceptors.intercept(
            grpcServerRule.getChannel(), module.getClientInterceptor(target));

    ClientCall<String, String> call = interceptedChannel.newCall(method, CALL_OPTIONS);

    // Make the call
    Metadata headers = new Metadata();
    call.start(mockClientCallListener, headers);

    // End the call
    call.halfClose();
    call.request(1);

    io.opentelemetry.api.common.Attributes attributes = io.opentelemetry.api.common.Attributes.of(
        TARGET_KEY, target,
        METHOD_KEY, method.getFullMethodName());

    assertThat(openTelemetryTesting.getMetrics())
        .anySatisfy(
            metric ->
                assertThat(metric)
                    .hasInstrumentationScope(InstrumentationScopeInfo.create(
                        OpenTelemetryConstants.INSTRUMENTATION_SCOPE))
                    .hasName(CLIENT_ATTEMPT_COUNT_INSTRUMENT_NAME)
                    .hasUnit("{attempt}")
                    .hasLongSumSatisfying(
                        longSum ->
                            longSum
                                .hasPointsSatisfying(
                                    point ->
                                        point
                                            .hasAttributes(attributes))));
  }

  @Test
  public void targetAttributeFilter_allowsTarget_usesOriginalTarget() {
    // Test that when filter allows the target, the original target is used
    String target = "dns:///example.com";
    OpenTelemetryMetricsResource resource = GrpcOpenTelemetry.createMetricInstruments(testMeter,
        enabledMetricsMap, disableDefaultMetrics);
    OpenTelemetryMetricsModule module = newOpenTelemetryMetricsModule(resource,
        t -> t.contains("example.com"));

    Channel interceptedChannel =
        ClientInterceptors.intercept(
            grpcServerRule.getChannel(), module.getClientInterceptor(target));

    ClientCall<String, String> call = interceptedChannel.newCall(method, CALL_OPTIONS);

    // Make the call
    Metadata headers = new Metadata();
    call.start(mockClientCallListener, headers);

    // End the call
    call.halfClose();
    call.request(1);

    io.opentelemetry.api.common.Attributes attributes = io.opentelemetry.api.common.Attributes.of(
        TARGET_KEY, target,
        METHOD_KEY, method.getFullMethodName());

    assertThat(openTelemetryTesting.getMetrics())
        .anySatisfy(
            metric ->
                assertThat(metric)
                    .hasInstrumentationScope(InstrumentationScopeInfo.create(
                        OpenTelemetryConstants.INSTRUMENTATION_SCOPE))
                    .hasName(CLIENT_ATTEMPT_COUNT_INSTRUMENT_NAME)
                    .hasUnit("{attempt}")
                    .hasLongSumSatisfying(
                        longSum ->
                            longSum
                                .hasPointsSatisfying(
                                    point ->
                                        point
                                            .hasAttributes(attributes))));
  }

  @Test
  public void targetAttributeFilter_rejectsTarget_mapsToOther() {
    // Test that when filter rejects the target, it is mapped to "other"
    String target = "dns:///example.com";
    OpenTelemetryMetricsResource resource = GrpcOpenTelemetry.createMetricInstruments(testMeter,
        enabledMetricsMap, disableDefaultMetrics);
    OpenTelemetryMetricsModule module = newOpenTelemetryMetricsModule(resource,
        t -> t.contains("allowed.com"));

    Channel interceptedChannel =
        ClientInterceptors.intercept(
            grpcServerRule.getChannel(), module.getClientInterceptor(target));

    ClientCall<String, String> call = interceptedChannel.newCall(method, CALL_OPTIONS);

    // Make the call
    Metadata headers = new Metadata();
    call.start(mockClientCallListener, headers);

    // End the call
    call.halfClose();
    call.request(1);

    io.opentelemetry.api.common.Attributes attributes = io.opentelemetry.api.common.Attributes.of(
        TARGET_KEY, "other",
        METHOD_KEY, method.getFullMethodName());

    assertThat(openTelemetryTesting.getMetrics())
        .anySatisfy(
            metric ->
                assertThat(metric)
                    .hasInstrumentationScope(InstrumentationScopeInfo.create(
                        OpenTelemetryConstants.INSTRUMENTATION_SCOPE))
                    .hasName(CLIENT_ATTEMPT_COUNT_INSTRUMENT_NAME)
                    .hasUnit("{attempt}")
                    .hasLongSumSatisfying(
                        longSum ->
                            longSum
                                .hasPointsSatisfying(
                                    point ->
                                        point
                                            .hasAttributes(attributes))));
  }

  private OpenTelemetryMetricsModule newOpenTelemetryMetricsModule(
      OpenTelemetryMetricsResource resource) {
    return new OpenTelemetryMetricsModule(
            fakeClock.getStopwatchSupplier(), resource, emptyList(), emptyList(),
            openTelemetryTesting.getOpenTelemetry().getPropagators());
  }

  private OpenTelemetryMetricsModule newOpenTelemetryMetricsModule(
      OpenTelemetryMetricsResource resource, TargetFilter filter) {
    return new OpenTelemetryMetricsModule(
            fakeClock.getStopwatchSupplier(), resource, emptyList(), emptyList(),
            openTelemetryTesting.getOpenTelemetry().getPropagators(), filter);
  }

  static class CallInfo<ReqT, RespT> extends ServerCallInfo<ReqT, RespT> {
    private final MethodDescriptor<ReqT, RespT> methodDescriptor;
    private final Attributes attributes;
    private final String authority;

    CallInfo(
        MethodDescriptor<ReqT, RespT> methodDescriptor,
        Attributes attributes,
        @Nullable String authority) {
      this.methodDescriptor = methodDescriptor;
      this.attributes = attributes;
      this.authority = authority;
    }

    @Override
    public MethodDescriptor<ReqT, RespT> getMethodDescriptor() {
      return methodDescriptor;
    }

    @Override
    public Attributes getAttributes() {
      return attributes;
    }

    @Nullable
    @Override
    public String getAuthority() {
      return authority;
    }
  }

  @Test
  public void serverBaggagePropagation_EndToEnd() throws Exception {
    // 1. Create Both Modules
    OpenTelemetry otel = openTelemetryTesting.getOpenTelemetry();
    OpenTelemetryTracingModule tracingModule = new OpenTelemetryTracingModule(otel);
    OpenTelemetryMetricsModule metricsModule = new OpenTelemetryMetricsModule(
            fakeClock.getStopwatchSupplier(), resource, emptyList(), emptyList(),
            otel.getPropagators());

    // 2. Create Server with *both* tracer factories
    server = InProcessServerBuilder.forName(serverName)
        .addService(new SimpleServiceImpl()) // <-- Uses the helper class below
        .addStreamTracerFactory(tracingModule.getServerTracerFactory())
        .addStreamTracerFactory(metricsModule.getServerTracerFactory())
        .build()
        .start();

    // 3. Create Client Channel
    channel = InProcessChannelBuilder.forName(serverName).directExecutor().build();

    // 4. Manually create baggage headers
    Metadata headers = new Metadata();
    headers.put(Metadata.Key.of("baggage", Metadata.ASCII_STRING_MARSHALLER),
        "choice=red_pill_or_blue_pill");

    // 5. Make the gRPC call with these headers
    ClientInterceptor headerAttachingInterceptor =
        MetadataUtils.newAttachHeadersInterceptor(headers);

    // Now, create the stub and apply that interceptor
    SimpleServiceGrpc.SimpleServiceBlockingStub stub =
        SimpleServiceGrpc.newBlockingStub(channel)
            .withInterceptors(headerAttachingInterceptor);

    // Use the imported SimpleRequest
    stub.unaryRpc(SimpleRequest.getDefaultInstance());

    // 6. Verify the Mock
    verify(mockServerCallDurationHistogram).record(
        anyDouble(),
        any(io.opentelemetry.api.common.Attributes.class),
        contextCaptor.capture());

    // 7. Assert on the captured Context
    io.opentelemetry.context.Context capturedOtelContext = contextCaptor.getValue();
    Baggage capturedBaggage = Baggage.fromContext(capturedOtelContext);

    assertEquals("red_pill_or_blue_pill", capturedBaggage.getEntryValue("choice"));
  }

  /**
   * A simple service implementation for the E2E test.
   */
  private static class SimpleServiceImpl extends SimpleServiceGrpc.SimpleServiceImplBase {
    @Override
    public void unaryRpc(SimpleRequest request, StreamObserver<SimpleResponse> responseObserver) {
      responseObserver.onNext(SimpleResponse.getDefaultInstance());
      responseObserver.onCompleted();
    }
  }

  @Test
  public void serverMetricsShouldRecordContextWithBaggage() {
    // Mocks
    DoubleHistogram serverCallDurationCounter = mock(DoubleHistogram.class);
    OpenTelemetryMetricsResource resource = mock(OpenTelemetryMetricsResource.class);
    when(resource.serverCallDurationCounter()).thenReturn(serverCallDurationCounter);

    // ContextPropagators with Baggage
    ContextPropagators propagators = ContextPropagators.create(
            TextMapPropagator.composite(W3CBaggagePropagator.getInstance()));

    // Module
    OpenTelemetryMetricsModule module = new OpenTelemetryMetricsModule(
            Stopwatch::createUnstarted,
            resource,
            ImmutableList.of(),
            ImmutableList.of(),
            propagators);

    // Baggage to inject
    Baggage baggage = Baggage.builder().put("my-baggage-key", "my-baggage-value").build();
    Metadata headers = new Metadata();
    propagators.getTextMapPropagator().inject(Context.root().with(baggage), headers,
            new MetadataSetter());

    // Create Tracer
    io.grpc.ServerStreamTracer.Factory factory = module.getServerTracerFactory();
    io.grpc.ServerStreamTracer tracer = factory.newServerStreamTracer("test/method", headers);

    // Close stream logic
    tracer.streamClosed(Status.OK);

    // Verify record called with context (which should have baggage)
    verify(serverCallDurationCounter).record(
            anyDouble(),
            any(),
            org.mockito.ArgumentMatchers.argThat(ctx -> {
              Baggage b = Baggage.fromContext(ctx);
              return "my-baggage-value".equals(b.getEntryValue("my-baggage-key"));
            }));
  }

  @Test
  public void serverMetrics_withExternalExecutor_propagatesBaggage() throws Exception {
    // Setup Mocks & Resource
    DoubleHistogram serverCallDurationCounter = mock(DoubleHistogram.class);
    LongCounter serverCallCountCounter = mock(LongCounter.class);
    LongHistogram serverTotalSentCompressedMessageSizeCounter = mock(LongHistogram.class);
    LongHistogram serverTotalReceivedCompressedMessageSizeCounter = mock(LongHistogram.class);
    OpenTelemetryMetricsResource resource = mock(OpenTelemetryMetricsResource.class);
    when(resource.serverCallDurationCounter()).thenReturn(serverCallDurationCounter);
    when(resource.serverCallCountCounter()).thenReturn(serverCallCountCounter);
    when(resource.serverTotalSentCompressedMessageSizeCounter())
            .thenReturn(serverTotalSentCompressedMessageSizeCounter);
    when(resource.serverTotalReceivedCompressedMessageSizeCounter())
            .thenReturn(serverTotalReceivedCompressedMessageSizeCounter);

    // Setup Propagators
    ContextPropagators propagators = ContextPropagators.create(W3CBaggagePropagator.getInstance());

    // Initialize Module
    OpenTelemetryMetricsModule module = new OpenTelemetryMetricsModule(
            Stopwatch::createUnstarted,
            resource,
            ImmutableList.of(),
            ImmutableList.of(),
            propagators);

    // Setup Server with Wrapped Executor (Custom Executor)
    ExecutorService customExecutor = Executors.newFixedThreadPool(2);
    java.util.concurrent.Executor rawExecutor = customExecutor;

    String serverName = InProcessServerBuilder.generateName();
    io.grpc.Server server = InProcessServerBuilder.forName(serverName)
            .executor(rawExecutor)
            .addService(new SimpleServiceGrpc.SimpleServiceImplBase() {
                @Override
                public void unaryRpc(
                        SimpleRequest request,
                        StreamObserver<SimpleResponse> responseObserver) {
                    responseObserver.onNext(SimpleResponse.getDefaultInstance());
                    responseObserver.onCompleted();
                }
            })
            .addStreamTracerFactory(module.getServerTracerFactory())
            .build().start();

    // Client Interceptor to inject baggage
    ClientInterceptor baggageInterceptor = new ClientInterceptor() {
        @Override
        public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
                MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
            return new ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(
                    next.newCall(method, callOptions)) {
                @Override
                public void start(Listener<RespT> responseListener, Metadata headers) {
                    propagators.getTextMapPropagator().inject(Context.current(), headers,
                        new MetadataSetter());
                    super.start(responseListener, headers);
                }
            };
        }
    };

    // Setup Client and Inject Baggage
    io.grpc.ManagedChannel channel = InProcessChannelBuilder.forName(serverName)
            .intercept(baggageInterceptor)
            .directExecutor()
            .build();
    SimpleServiceGrpc.SimpleServiceBlockingStub stub = SimpleServiceGrpc
            .newBlockingStub(channel);

    // Define multiple Baggage items
    Baggage testBaggage = Baggage.builder()
            .put("key1", "value1")
            .put("key2", "value/with/special:chars")
            .build();

    // Make the call with Baggage in Context
    try (io.opentelemetry.context.Scope scope = Context.current().with(testBaggage).makeCurrent()) {
      stub.unaryRpc(SimpleRequest.getDefaultInstance());
    }

    // Shutdown and Wait
    channel.shutdownNow();
    server.shutdown().awaitTermination(5, TimeUnit.SECONDS);
    customExecutor.shutdownNow();

    // Verification Logic for Baggage
    org.mockito.ArgumentMatcher<Context> baggageMatcher = ctx -> {
      Baggage b = Baggage.fromContext(ctx);
      return "value1".equals(b.getEntryValue("key1"))
              && "value/with/special:chars".equals(b.getEntryValue("key2"));
    };

    // Verify all metrics recorded with correct baggage
    // Use timeout to avoid race conditions as metrics might be recorded
    // asynchronously
    verify(serverCallDurationCounter, timeout(5000)).record(
            anyDouble(),
            any(), // Attributes
            org.mockito.ArgumentMatchers.argThat(baggageMatcher));

    verify(serverCallCountCounter, timeout(5000)).add(
            org.mockito.ArgumentMatchers.eq(1L),
            any(), // Attributes
            org.mockito.ArgumentMatchers.argThat(baggageMatcher));

    verify(serverTotalSentCompressedMessageSizeCounter, timeout(5000)).record(
            org.mockito.ArgumentMatchers.anyLong(),
            any(), // Attributes
            org.mockito.ArgumentMatchers.argThat(baggageMatcher));

    verify(serverTotalReceivedCompressedMessageSizeCounter, timeout(5000)).record(
            org.mockito.ArgumentMatchers.anyLong(),
            any(), // Attributes
            org.mockito.ArgumentMatchers.argThat(baggageMatcher));
  }

  private static class MetadataSetter implements TextMapSetter<Metadata> {
    @Override
    public void set(Metadata carrier, String key, String value) {
      carrier.put(Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER), value);
    }
  }
}
