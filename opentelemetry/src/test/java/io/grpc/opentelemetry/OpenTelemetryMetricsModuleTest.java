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
import static io.grpc.opentelemetry.internal.OpenTelemetryConstants.BACKEND_SERVICE_KEY;
import static io.grpc.opentelemetry.internal.OpenTelemetryConstants.DELAY_TYPE_KEY;
import static io.grpc.opentelemetry.internal.OpenTelemetryConstants.LOCALITY_KEY;
import static io.grpc.opentelemetry.internal.OpenTelemetryConstants.METHOD_KEY;
import static io.grpc.opentelemetry.internal.OpenTelemetryConstants.STATUS_KEY;
import static io.grpc.opentelemetry.internal.OpenTelemetryConstants.TARGET_KEY;
import static io.opentelemetry.sdk.testing.assertj.OpenTelemetryAssertions.assertThat;
import static java.util.Collections.emptyList;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import com.google.common.collect.ImmutableMap;
import io.grpc.Attributes;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ClientInterceptors;
import io.grpc.ClientStreamTracer;
import io.grpc.ConnectivityState;
import io.grpc.EquivalentAddressGroup;
import io.grpc.Grpc;
import io.grpc.KnownLength;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancer.PickResult;
import io.grpc.LoadBalancer.PickSubchannelArgs;
import io.grpc.LoadBalancer.SubchannelPicker;
import io.grpc.LoadBalancerProvider;
import io.grpc.LoadBalancerRegistry;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.NameResolver;
import io.grpc.NameResolverProvider;
import io.grpc.NameResolverRegistry;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerServiceDefinition;
import io.grpc.ServerStreamTracer;
import io.grpc.ServerStreamTracer.ServerCallInfo;
import io.grpc.ServiceDescriptor;
import io.grpc.Status;
import io.grpc.Status.Code;
import io.grpc.StatusOr;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.inprocess.InProcessSocketAddress;
import io.grpc.internal.FakeClock;
import io.grpc.internal.StatsTraceContext.ServerCallMethodListener;
import io.grpc.opentelemetry.GrpcOpenTelemetry.TargetFilter;
import io.grpc.opentelemetry.OpenTelemetryMetricsModule.CallAttemptsTracerFactory;
import io.grpc.opentelemetry.internal.OpenTelemetryConstants;
import io.grpc.stub.ClientCalls;
import io.grpc.testing.GrpcCleanupRule;
import io.grpc.testing.GrpcServerRule;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.InstrumentationScopeInfo;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.junit4.OpenTelemetryRule;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketAddress;
import java.net.URI;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
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

  private static final CallOptions.Key<String> CUSTOM_OPTION =
      CallOptions.Key.createWithDefault("option1", "default");
  private static final CallOptions CALL_OPTIONS =
      CallOptions.DEFAULT.withOption(NAME_RESOLUTION_DELAYED, 10L);
  private static final ClientStreamTracer.StreamInfo STREAM_INFO =
      ClientStreamTracer.StreamInfo.newBuilder().setCallOptions(CALL_OPTIONS).build();
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
  private static final String CLIENT_ATTEMPT_DELAY_DURATION =
      "grpc.client.attempt.delay.duration";
  private static final String CLIENT_CALL_DELAY_DURATION = "grpc.client.call.delay.duration";
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
  public final GrpcCleanupRule grpcCleanup = new GrpcCleanupRule();
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

  private Server server;
  private ManagedChannel channel;

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
    call = interceptedChannel.newCall(
        method, CallOptions.DEFAULT.withOption(CUSTOM_OPTION, "customvalue"));

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
        new CallAttemptsTracerFactory(module, target, CALL_OPTIONS, method.getFullMethodName(),
            emptyList(), Context.root());
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
        new CallAttemptsTracerFactory(module, target, CALL_OPTIONS, method.getFullMethodName(),
             emptyList(), Context.root());
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
        new OpenTelemetryMetricsModule.CallAttemptsTracerFactory(module, target, CALL_OPTIONS,
            method.getFullMethodName(), emptyList(), Context.root());
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
        new OpenTelemetryMetricsModule.CallAttemptsTracerFactory(module, target, CALL_OPTIONS,
            method.getFullMethodName(), emptyList(), Context.root());

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
        new OpenTelemetryMetricsModule.CallAttemptsTracerFactory(module, target, CALL_OPTIONS,
            method.getFullMethodName(), emptyList(), Context.root());

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
        new OpenTelemetryMetricsModule.CallAttemptsTracerFactory(module, target, CALL_OPTIONS,
            method.getFullMethodName(), emptyList(), Context.root());
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
        emptyList());
    OpenTelemetryMetricsModule.CallAttemptsTracerFactory callAttemptsTracerFactory =
        new CallAttemptsTracerFactory(module, target, CALL_OPTIONS, method.getFullMethodName(),
            emptyList(), Context.root());

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
        emptyList());
    OpenTelemetryMetricsModule.CallAttemptsTracerFactory callAttemptsTracerFactory =
        new CallAttemptsTracerFactory(module, target, CALL_OPTIONS, method.getFullMethodName(),
            emptyList(), Context.root());

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
        emptyList());
    OpenTelemetryMetricsModule.CallAttemptsTracerFactory callAttemptsTracerFactory =
        new CallAttemptsTracerFactory(module, target, CALL_OPTIONS, method.getFullMethodName(),
            emptyList(), Context.root());

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
        emptyList());
    OpenTelemetryMetricsModule.CallAttemptsTracerFactory callAttemptsTracerFactory =
        new CallAttemptsTracerFactory(module, target, CALL_OPTIONS, method.getFullMethodName(),
            emptyList(), Context.root());

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
  public void customLabel_present() {
    Map<String, Boolean> enabledMetrics = ImmutableMap.of(
        CLIENT_CALL_HEDGES, true,
        CLIENT_CALL_RETRIES, true,
        CLIENT_CALL_RETRY_DELAY, true,
        CLIENT_CALL_TRANSPARENT_RETRIES, true
    );
    String target = "target:///";
    String customValue = "some-random-value";
    CallOptions callOptions =
        STREAM_INFO.getCallOptions().withOption(Grpc.CALL_OPTION_CUSTOM_LABEL, customValue);
    OpenTelemetryMetricsResource resource = GrpcOpenTelemetry.createMetricInstruments(testMeter,
        enabledMetrics, disableDefaultMetrics);
    String customLabel = "grpc.client.call.custom";
    OpenTelemetryMetricsModule module = new OpenTelemetryMetricsModule(
        fakeClock.getStopwatchSupplier(), resource, Arrays.asList(customLabel),
        emptyList());
    OpenTelemetryMetricsModule.CallAttemptsTracerFactory callAttemptsTracerFactory =
        new CallAttemptsTracerFactory(
            module, target, callOptions, method.getFullMethodName(), emptyList(), Context.root());

    ClientStreamTracer.StreamInfo streamInfo =
        STREAM_INFO.toBuilder().setCallOptions(callOptions).build();
    ClientStreamTracer tracer =
        callAttemptsTracerFactory.newClientStreamTracer(streamInfo, new Metadata());
    tracer.streamClosed(Status.UNAVAILABLE);

    tracer = callAttemptsTracerFactory.newClientStreamTracer(streamInfo, new Metadata());
    tracer.streamClosed(Status.UNAVAILABLE);

    tracer = callAttemptsTracerFactory.newClientStreamTracer(
        streamInfo.toBuilder().setIsTransparentRetry(true).build(), new Metadata());
    tracer.streamClosed(Status.UNAVAILABLE);

    tracer = callAttemptsTracerFactory.newClientStreamTracer(
        streamInfo.toBuilder().setIsHedging(true).build(), new Metadata());
    tracer.streamClosed(Status.OK);
    callAttemptsTracerFactory.callEnded(Status.OK);

    AttributeKey<String> attributeKey = AttributeKey.stringKey(customLabel);

    assertThat(sortByName(openTelemetryTesting.getMetrics()))
        .satisfiesExactly(
            metric -> assertThat(metric)
                .hasName(CLIENT_ATTEMPT_DURATION_INSTRUMENT_NAME)
                .hasHistogramSatisfying(
                    histogram -> histogram.hasPointsSatisfying(
                        point -> point.hasAttribute(attributeKey, customValue),
                        point -> point.hasAttribute(attributeKey, customValue))),
            metric -> assertThat(metric)
                .hasName(CLIENT_ATTEMPT_RECV_TOTAL_COMPRESSED_MESSAGE_SIZE)
                .hasHistogramSatisfying(
                    histogram -> histogram.hasPointsSatisfying(
                        point -> point.hasAttribute(attributeKey, customValue),
                        point -> point.hasAttribute(attributeKey, customValue))),
            metric -> assertThat(metric)
                .hasName(CLIENT_ATTEMPT_SENT_TOTAL_COMPRESSED_MESSAGE_SIZE)
                .hasHistogramSatisfying(
                    histogram -> histogram.hasPointsSatisfying(
                        point -> point.hasAttribute(attributeKey, customValue),
                        point -> point.hasAttribute(attributeKey, customValue))),
            metric -> assertThat(metric)
                .hasName(CLIENT_ATTEMPT_COUNT_INSTRUMENT_NAME)
                .hasLongSumSatisfying(
                    longSum -> longSum.hasPointsSatisfying(
                        point -> point.hasAttribute(attributeKey, customValue))),
            metric -> assertThat(metric)
                .hasName(CLIENT_CALL_DURATION)
                .hasHistogramSatisfying(
                    histogram -> histogram.hasPointsSatisfying(
                        point -> point.hasAttribute(attributeKey, customValue))),
            metric -> assertThat(metric)
                .hasName(CLIENT_CALL_HEDGES)
                .hasHistogramSatisfying(
                    histogram -> histogram.hasPointsSatisfying(
                        point -> point.hasAttribute(attributeKey, customValue))),
            metric -> assertThat(metric)
                .hasName(CLIENT_CALL_RETRIES)
                .hasHistogramSatisfying(
                    histogram -> histogram.hasPointsSatisfying(
                        point -> point.hasAttribute(attributeKey, customValue))),
            metric -> assertThat(metric)
                .hasName(CLIENT_CALL_RETRY_DELAY)
                .hasHistogramSatisfying(
                    histogram -> histogram.hasPointsSatisfying(
                        point -> point.hasAttribute(attributeKey, customValue))),
            metric -> assertThat(metric)
                .hasName(CLIENT_CALL_TRANSPARENT_RETRIES)
                .hasHistogramSatisfying(
                    histogram -> histogram.hasPointsSatisfying(
                        point -> point.hasAttribute(attributeKey, customValue))));
  }


  @Test
  public void delayHistograms_bucketBoundariesAndUnit() {
    String target = "target:///";
    OpenTelemetryMetricsResource resource = GrpcOpenTelemetry.createMetricInstruments(
        testMeter, ImmutableMap.of(
            CLIENT_CALL_DELAY_DURATION, true,
            CLIENT_ATTEMPT_DELAY_DURATION, true), disableDefaultMetrics);
    OpenTelemetryMetricsModule module = newOpenTelemetryMetricsModule(resource);
    CallAttemptsTracerFactory callAttemptsTracerFactory =
        new CallAttemptsTracerFactory(
            module, target, STREAM_INFO.getCallOptions(), method.getFullMethodName(),
            emptyList(), Context.root());

    callAttemptsTracerFactory.recordDelayStart("resolving", "dns resolution pending");
    fakeClock.forwardTime(100, MILLISECONDS);
    callAttemptsTracerFactory.recordDelayEnd("resolving");

    ClientStreamTracer tracer =
        callAttemptsTracerFactory.newClientStreamTracer(STREAM_INFO, new Metadata());
    tracer.recordDelayStart("connecting", "connecting reason");
    fakeClock.forwardTime(250, MILLISECONDS);
    tracer.recordDelayEnd("connecting");

    assertThat(openTelemetryTesting.getMetrics())
        .anySatisfy(metric -> assertThat(metric)
            .hasName(CLIENT_CALL_DELAY_DURATION)
            .hasUnit("s")
            .hasHistogramSatisfying(histogram -> histogram.hasPointsSatisfying(
                point -> point
                    .hasCount(1)
                    .hasSum(0.1)
                    .hasBucketBoundaries(latencyBuckets)
                    .hasAttributes(delayAttributes(target, "resolving")))))
        .anySatisfy(metric -> assertThat(metric)
            .hasName(CLIENT_ATTEMPT_DELAY_DURATION)
            .hasUnit("s")
            .hasHistogramSatisfying(histogram -> histogram.hasPointsSatisfying(
                point -> point
                    .hasCount(1)
                    .hasSum(0.25)
                    .hasBucketBoundaries(latencyBuckets)
                    .hasAttributes(delayAttributes(target, "connecting")))));
  }

  /**
   * gRFC A121 fixes the label set of both delay histograms to exactly {@code grpc.target},
   * {@code grpc.method} and {@code grpc.delay_type}. Labels contributed by an
   * {@link OpenTelemetryPlugin} must therefore <em>not</em> leak into them, even though the same
   * plugin does contribute labels to {@code grpc.client.attempt.duration}.
   */
  @Test
  public void delayHistograms_customPluginLabels_notAppliedToDelayMetrics() {
    String target = "target:///";
    OpenTelemetryMetricsResource resource = GrpcOpenTelemetry.createMetricInstruments(
        testMeter, ImmutableMap.of(
            CLIENT_CALL_DELAY_DURATION, true,
            CLIENT_ATTEMPT_DELAY_DURATION, true), disableDefaultMetrics);

    OpenTelemetryPlugin customPlugin = new OpenTelemetryPlugin() {
      @Override
      public ClientCallPlugin newClientCallPlugin() {
        return new ClientCallPlugin() {
          @Override
          public ClientStreamPlugin newClientStreamPlugin() {
            return new ClientStreamPlugin() {
              @Override
              public void addLabels(io.opentelemetry.api.common.AttributesBuilder to) {
                to.put("custom_key", "custom_val");
              }
            };
          }
        };
      }

      @Override
      public ServerStreamPlugin newServerStreamPlugin(Metadata inboundMetadata) {
        return new ServerStreamPlugin() {};
      }
    };

    OpenTelemetryMetricsModule module = new OpenTelemetryMetricsModule(
        fakeClock.getStopwatchSupplier(), resource, emptyList(),
        Collections.singletonList(customPlugin));

    CallAttemptsTracerFactory callAttemptsTracerFactory =
        new CallAttemptsTracerFactory(
            module, target, STREAM_INFO.getCallOptions(), method.getFullMethodName(),
            Collections.singletonList(customPlugin.newClientCallPlugin()), Context.root());

    callAttemptsTracerFactory.recordDelayStart("resolving", "dns resolution pending");
    fakeClock.forwardTime(100, MILLISECONDS);
    callAttemptsTracerFactory.recordDelayEnd("resolving");

    ClientStreamTracer tracer =
        callAttemptsTracerFactory.newClientStreamTracer(STREAM_INFO, new Metadata());
    tracer.recordDelayStart("connecting", "connecting reason");
    fakeClock.forwardTime(250, MILLISECONDS);
    tracer.recordDelayEnd("connecting");

    // Exactly the A121 label set: the plugin's custom_key is absent from both histograms.
    assertThat(openTelemetryTesting.getMetrics())
        .anySatisfy(metric -> assertThat(metric)
            .hasName(CLIENT_CALL_DELAY_DURATION)
            .hasHistogramSatisfying(histogram -> histogram.hasPointsSatisfying(
                point -> point
                    .hasCount(1)
                    .hasSum(0.1)
                    .hasAttributes(delayAttributes(target, "resolving")))))
        .anySatisfy(metric -> assertThat(metric)
            .hasName(CLIENT_ATTEMPT_DELAY_DURATION)
            .hasHistogramSatisfying(histogram -> histogram.hasPointsSatisfying(
                point -> point
                    .hasCount(1)
                    .hasSum(0.25)
                    .hasAttributes(delayAttributes(target, "connecting")))));

    // The other half of the contract: the very same plugin does label the attempt duration, so
    // the exclusion above is specific to the delay histograms and not a broken plugin wiring.
    tracer.streamClosed(Status.OK);
    callAttemptsTracerFactory.callEnded(Status.OK);

    assertThat(openTelemetryTesting.getMetrics())
        .anySatisfy(metric -> assertThat(metric)
            .hasName(CLIENT_ATTEMPT_DURATION_INSTRUMENT_NAME)
            .hasHistogramSatisfying(histogram -> histogram.hasPointsSatisfying(
                point -> point.hasAttributesSatisfying(
                    attributes -> assertThat(attributes.asMap())
                        .containsEntry(AttributeKey.stringKey("custom_key"), "custom_val")))));
  }


  @Test
  public void clientCallDelayDuration_endToEnd_nameResolutionDelay() throws Exception {
    final CountDownLatch resolutionLatch = new CountDownLatch(1);
    final AtomicReference<NameResolver.Listener2> capturedListener = new AtomicReference<>();

    NameResolverProvider slowResolverProvider = new NameResolverProvider() {
      @Override
      protected boolean isAvailable() {
        return true;
      }

      @Override
      protected int priority() {
        return 5;
      }

      @Override
      public String getDefaultScheme() {
        return "slowresmetric";
      }

      @Override
      public Collection<Class<? extends SocketAddress>> getProducedSocketAddressTypes() {
        return Collections.singleton(InProcessSocketAddress.class);
      }

      @Override
      public NameResolver newNameResolver(URI targetUri, NameResolver.Args args) {
        return new NameResolver() {
          @Override
          public String getServiceAuthority() {
            return "slowresmetric";
          }

          @Override
          public void start(Listener2 listener) {
            capturedListener.set(listener);
            resolutionLatch.countDown();
          }

          @Override
          public void shutdown() {}
        };
      }
    };
    NameResolverRegistry.getDefaultRegistry().register(slowResolverProvider);

    GrpcOpenTelemetry grpcOpenTelemetry = GrpcOpenTelemetry.newBuilder()
        .sdk(openTelemetryTesting.getOpenTelemetry())
        .enableMetrics(Collections.singleton(CLIENT_CALL_DELAY_DURATION))
        .build();

    InProcessChannelBuilder channelBuilder =
        InProcessChannelBuilder.forTarget("slowresmetric:///test-metric-service")
            .defaultLoadBalancingPolicy("pick_first");
    grpcOpenTelemetry.configureChannelBuilder(channelBuilder);
    ManagedChannel channel = channelBuilder.build();
    try {
      ClientCall<String, String> call = channel.newCall(method, CallOptions.DEFAULT);
      call.start(new ClientCall.Listener<String>() {}, new Metadata());
      call.request(1);

      resolutionLatch.await(5, TimeUnit.SECONDS);

      // Complete name resolution
      capturedListener.get().onResult(NameResolver.ResolutionResult.newBuilder()
          .setAddressesOrError(StatusOr.fromValue(Collections.singletonList(
              new EquivalentAddressGroup(new InProcessSocketAddress("test-slow-metric")))))
          .build());

      call.cancel("End test", null);
    } finally {
      channel.shutdownNow();
      channel.awaitTermination(5, TimeUnit.SECONDS);
      NameResolverRegistry.getDefaultRegistry().deregister(slowResolverProvider);
    }

    assertThat(openTelemetryTesting.getMetrics())
        .anySatisfy(
            metric -> assertThat(metric)
                .hasName(CLIENT_CALL_DELAY_DURATION)
                .hasHistogramSatisfying(
                    histogram -> histogram.hasPointsSatisfying(
                        point -> {
                          point.hasAttribute(METHOD_KEY, method.getFullMethodName());
                          point.hasAttribute(
                              DELAY_TYPE_KEY, "resolving");
                        })));
  }

  @Test
  public void clientAttemptDelayDuration_endToEnd_inProcessTransport() throws Exception {
    final CountDownLatch latch = new CountDownLatch(1);
    LoadBalancerProvider slowLbProvider = new LoadBalancerProvider() {
      @Override
      public boolean isAvailable() {
        return true;
      }

      @Override
      public int getPriority() {
        return 5;
      }

      @Override
      public String getPolicyName() {
        return "slow_metrics_connecting_policy";
      }

      @Override
      public LoadBalancer newLoadBalancer(LoadBalancer.Helper helper) {
        return new LoadBalancer() {
          @Override
          public Status acceptResolvedAddresses(LoadBalancer.ResolvedAddresses resolvedAddresses) {
            helper.updateBalancingState(ConnectivityState.CONNECTING, new SubchannelPicker() {
              @Override
              public PickResult pickSubchannel(PickSubchannelArgs args) {
                latch.countDown();
                return PickResult.withNoResult("connecting",
                    "Simulated slow TLS handshake with backend");
              }
            });
            return Status.OK;
          }

          @Override
          public void handleNameResolutionError(Status error) {}

          @Override
          public void shutdown() {}
        };
      }
    };
    LoadBalancerRegistry.getDefaultRegistry().register(slowLbProvider);

    NameResolverProvider customResolverProvider = new NameResolverProvider() {
      @Override
      protected boolean isAvailable() {
        return true;
      }

      @Override
      protected int priority() {
        return 5;
      }

      @Override
      public String getDefaultScheme() {
        return "inprocmetricse2e";
      }

      @Override
      public Collection<Class<? extends SocketAddress>> getProducedSocketAddressTypes() {
        return Collections.singleton(InProcessSocketAddress.class);
      }

      @Override
      public NameResolver newNameResolver(URI targetUri, NameResolver.Args args) {
        return new NameResolver() {
          @Override
          public String getServiceAuthority() {
            return "inprocmetricse2e";
          }

          @Override
          public void start(Listener2 listener) {
            listener.onResult(ResolutionResult.newBuilder()
                .setAddressesOrError(StatusOr.fromValue(Collections.singletonList(
                    new EquivalentAddressGroup(
                        new InProcessSocketAddress("test-metrics-e2e")))))
                .build());
          }

          @Override
          public void shutdown() {}
        };
      }
    };
    NameResolverRegistry.getDefaultRegistry().register(customResolverProvider);

    GrpcOpenTelemetry grpcOpenTelemetry = GrpcOpenTelemetry.newBuilder()
        .sdk(openTelemetryTesting.getOpenTelemetry())
        .enableMetrics(Collections.singleton(CLIENT_ATTEMPT_DELAY_DURATION))
        .build();

    InProcessChannelBuilder channelBuilder =
        InProcessChannelBuilder.forTarget("inprocmetricse2e:///test-metrics-e2e")
            .defaultLoadBalancingPolicy("slow_metrics_connecting_policy");
    grpcOpenTelemetry.configureChannelBuilder(channelBuilder);
    ManagedChannel channel = channelBuilder.build();
    try {
      ClientCall<String, String> call = channel.newCall(method, CallOptions.DEFAULT);
      call.start(new ClientCall.Listener<String>() {}, new Metadata());
      call.request(1);

      latch.await(5, TimeUnit.SECONDS);
      call.cancel("End test delay segment", null);
    } finally {
      channel.shutdownNow();
      channel.awaitTermination(5, TimeUnit.SECONDS);
      LoadBalancerRegistry.getDefaultRegistry().deregister(slowLbProvider);
      NameResolverRegistry.getDefaultRegistry().deregister(customResolverProvider);
    }

    assertThat(openTelemetryTesting.getMetrics())
        .anySatisfy(
            metric -> assertThat(metric)
                .hasName(CLIENT_ATTEMPT_DELAY_DURATION)
                .hasHistogramSatisfying(
                    histogram -> histogram.hasPointsSatisfying(
                        point -> {
                          point.hasAttribute(METHOD_KEY, method.getFullMethodName());
                          point.hasAttribute(TARGET_KEY, "inprocmetricse2e:///test-metrics-e2e");
                          point.hasAttribute(
                              DELAY_TYPE_KEY, "connecting");
                        })));
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
  public void serverMetrics_methodResolvedBeforeStreamClosed_generatedMethodRecordsName() {
    OpenTelemetryMetricsResource resource = GrpcOpenTelemetry.createMetricInstruments(testMeter,
        enabledMetricsMap, disableDefaultMetrics);
    OpenTelemetryMetricsModule module = newOpenTelemetryMetricsModule(resource);
    ServerStreamTracer.Factory tracerFactory = module.getServerTracerFactory();
    ServerStreamTracer tracer =
        tracerFactory.newServerStreamTracer(method.getFullMethodName(), new Metadata());

    ((ServerCallMethodListener) tracer).serverCallMethodResolved(method);
    fakeClock.forwardTime(10, MILLISECONDS);
    tracer.streamClosed(Status.CANCELLED);

    io.opentelemetry.api.common.Attributes serverAttributes =
        io.opentelemetry.api.common.Attributes.of(
            METHOD_KEY, method.getFullMethodName(),
            STATUS_KEY, Code.CANCELLED.toString());

    assertThat(openTelemetryTesting.getMetrics())
        .anySatisfy(
            metric ->
                assertThat(metric)
                    .hasName(SERVER_CALL_DURATION)
                    .hasUnit("s")
                    .hasHistogramSatisfying(
                        histogram ->
                            histogram.hasPointsSatisfying(
                                point ->
                                    point
                                        .hasCount(1)
                                        .hasSum(0.01)
                                        .hasAttributes(serverAttributes))));
  }

  @Test
  public void serverMetrics_methodResolvedBeforeStreamClosed_nonGeneratedMethodRecordsOther() {
    MethodDescriptor<String, String> nonGeneratedMethod =
        method.toBuilder().setSampledToLocalTracing(false).build();
    OpenTelemetryMetricsResource resource = GrpcOpenTelemetry.createMetricInstruments(testMeter,
        enabledMetricsMap, disableDefaultMetrics);
    OpenTelemetryMetricsModule module = newOpenTelemetryMetricsModule(resource);
    ServerStreamTracer.Factory tracerFactory = module.getServerTracerFactory();
    ServerStreamTracer tracer =
        tracerFactory.newServerStreamTracer(nonGeneratedMethod.getFullMethodName(), new Metadata());

    ((ServerCallMethodListener) tracer).serverCallMethodResolved(nonGeneratedMethod);
    fakeClock.forwardTime(10, MILLISECONDS);
    tracer.streamClosed(Status.CANCELLED);

    io.opentelemetry.api.common.Attributes serverAttributes =
        io.opentelemetry.api.common.Attributes.of(
            METHOD_KEY, "other",
            STATUS_KEY, Code.CANCELLED.toString());

    assertThat(openTelemetryTesting.getMetrics())
        .anySatisfy(
            metric ->
                assertThat(metric)
                    .hasName(SERVER_CALL_DURATION)
                    .hasUnit("s")
                    .hasHistogramSatisfying(
                        histogram ->
                            histogram.hasPointsSatisfying(
                                point ->
                                    point
                                        .hasCount(1)
                                        .hasSum(0.01)
                                        .hasAttributes(serverAttributes))));
  }

  @Test
  public void serverMetrics_serverCallStarted_nonGeneratedMethodRecordsOther() {
    MethodDescriptor<String, String> nonGeneratedMethod =
        method.toBuilder().setSampledToLocalTracing(false).build();
    OpenTelemetryMetricsResource resource = GrpcOpenTelemetry.createMetricInstruments(testMeter,
        enabledMetricsMap, disableDefaultMetrics);
    OpenTelemetryMetricsModule module = newOpenTelemetryMetricsModule(resource);
    ServerStreamTracer.Factory tracerFactory = module.getServerTracerFactory();
    ServerStreamTracer tracer =
        tracerFactory.newServerStreamTracer(nonGeneratedMethod.getFullMethodName(), new Metadata());
    tracer.serverCallStarted(
        new CallInfo<>(nonGeneratedMethod, Attributes.EMPTY, null));

    io.opentelemetry.api.common.Attributes startedAttributes =
        io.opentelemetry.api.common.Attributes.of(METHOD_KEY, "other");

    assertThat(openTelemetryTesting.getMetrics())
        .anySatisfy(
            metric ->
                assertThat(metric)
                    .hasName(SERVER_CALL_COUNT)
                    .hasUnit("{call}")
                    .hasLongSumSatisfying(
                        longSum ->
                            longSum.hasPointsSatisfying(
                                point ->
                                    point
                                        .hasAttributes(startedAttributes)
                                        .hasValue(1))));

    fakeClock.forwardTime(10, MILLISECONDS);
    tracer.streamClosed(Status.CANCELLED);

    io.opentelemetry.api.common.Attributes closedAttributes =
        io.opentelemetry.api.common.Attributes.of(
            METHOD_KEY, "other",
            STATUS_KEY, Code.CANCELLED.toString());

    assertThat(openTelemetryTesting.getMetrics())
        .anySatisfy(
            metric ->
                assertThat(metric)
                    .hasName(SERVER_CALL_DURATION)
                    .hasUnit("s")
                    .hasHistogramSatisfying(
                        histogram ->
                            histogram.hasPointsSatisfying(
                                point ->
                                    point
                                        .hasCount(1)
                                        .hasSum(0.01)
                                        .hasAttributes(closedAttributes))));
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
        fakeClock.getStopwatchSupplier(), resource, emptyList(), emptyList());
  }

  private OpenTelemetryMetricsModule newOpenTelemetryMetricsModule(
      OpenTelemetryMetricsResource resource, TargetFilter filter) {
    return new OpenTelemetryMetricsModule(
        fakeClock.getStopwatchSupplier(), resource, emptyList(), emptyList(),
        filter);
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
  public void serverMetrics_recordsBaggage() {
    DoubleHistogram mockDurationHistogram = mock(DoubleHistogram.class);
    OpenTelemetryMetricsResource mockResource = OpenTelemetryMetricsResource.builder()
        .serverCallDurationCounter(mockDurationHistogram)
        .build();

    OpenTelemetryMetricsModule module = newOpenTelemetryMetricsModule(mockResource);
    ServerStreamTracer.Factory tracerFactory = module.getServerTracerFactory();

    Baggage baggage = Baggage.builder()
        .put("baggage-key-1", "baggage-val-1")
        .build();

    io.grpc.Context grpcContext = io.grpc.Context.ROOT
        .withValue(OpenTelemetryConstants.BAGGAGE_KEY, baggage);
    io.grpc.Context previous = grpcContext.attach();

    ServerStreamTracer tracer;
    try {
      tracer = tracerFactory.newServerStreamTracer(
          method.getFullMethodName(), new Metadata());
      tracer.filterContext(grpcContext);
      tracer.serverCallStarted(
          new CallInfo<>(method, Attributes.EMPTY, null));
    } finally {
      grpcContext.detach(previous);
    }

    try (io.opentelemetry.context.Scope scope = Context.root().makeCurrent()) {
      tracer.streamClosed(Status.CANCELLED);
    }

    ArgumentCaptor<Context> contextCaptor = ArgumentCaptor.forClass(Context.class);
    verify(mockDurationHistogram).record(
        anyDouble(),
        any(),
        contextCaptor.capture());

    Baggage capturedBaggage = Baggage.fromContext(contextCaptor.getValue());
    assertNotNull("Captured context should have baggage", capturedBaggage);
    assertEquals(
        "baggage-val-1", capturedBaggage.getEntryValue("baggage-key-1"));
  }

  @Test
  public void clientMetrics_nameResolutionFailure_zeroAttempts() {
    String target = "target:///";
    OpenTelemetryMetricsResource resource = GrpcOpenTelemetry.createMetricInstruments(testMeter,
        enabledMetricsMap, disableDefaultMetrics);
    OpenTelemetryMetricsModule module = newOpenTelemetryMetricsModule(resource);
    OpenTelemetryMetricsModule.CallAttemptsTracerFactory callAttemptsTracerFactory =
        new CallAttemptsTracerFactory(module, target, CALL_OPTIONS, method.getFullMethodName(),
            emptyList(), Context.root());

    fakeClock.forwardTime(50, TimeUnit.MILLISECONDS);
    callAttemptsTracerFactory.callEnded(Status.UNAVAILABLE);

    io.opentelemetry.api.common.Attributes clientAttributes =
        io.opentelemetry.api.common.Attributes.of(
            TARGET_KEY, target,
            METHOD_KEY, method.getFullMethodName(),
            STATUS_KEY, Code.UNAVAILABLE.toString());

    assertThat(openTelemetryTesting.getMetrics())
        .anySatisfy(
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
                                        .hasSum(0.05)
                                        .hasAttributes(clientAttributes)
                                        .hasBucketBoundaries(latencyBuckets))));
  }

  @Test
  public void clientMetrics_endToEnd_nameResolutionFailure_unavailable() throws Exception {
    NameResolverProvider failingProvider = new NameResolverProvider() {
      @Override
      public NameResolver newNameResolver(URI targetUri, NameResolver.Args args) {
        return new NameResolver() {
          @Override
          public String getServiceAuthority() {
            return "failing.authority";
          }

          @Override
          public void start(Listener2 listener) {
            listener.onError(Status.UNAVAILABLE.withDescription("Name resolution failed"));
          }

          @Override
          public void shutdown() {}
        };
      }

      @Override
      protected boolean isAvailable() {
        return true;
      }

      @Override
      protected int priority() {
        return 5;
      }

      @Override
      public String getDefaultScheme() {
        return "failingnr";
      }

      @Override
      public String getScheme() {
        return getDefaultScheme();
      }

      @Override
      public Collection<Class<? extends SocketAddress>> getProducedSocketAddressTypes() {
        return Collections.singleton(InProcessSocketAddress.class);
      }
    };

    NameResolverRegistry.getDefaultRegistry().register(failingProvider);

    try {
      OpenTelemetryMetricsResource resource = GrpcOpenTelemetry.createMetricInstruments(testMeter,
          enabledMetricsMap, disableDefaultMetrics);
      OpenTelemetryMetricsModule module = newOpenTelemetryMetricsModule(resource);

      String target = "failingnr:///test.service";
      ManagedChannel channel = grpcCleanup.register(
          InProcessChannelBuilder.forTarget(target)
              .directExecutor()
              .intercept(module.getClientInterceptor(target))
              .build());

      ClientCall<String, String> call = channel.newCall(method, CallOptions.DEFAULT);
      call.start(mockClientCallListener, new Metadata());

      verify(mockClientCallListener, timeout(5000))
          .onClose(statusCaptor.capture(), any(Metadata.class));
      Status status = statusCaptor.getValue();
      assertEquals(Status.Code.UNAVAILABLE, status.getCode());

      io.opentelemetry.api.common.Attributes clientAttributes =
          io.opentelemetry.api.common.Attributes.of(
              TARGET_KEY, target,
              METHOD_KEY, method.getFullMethodName(),
              STATUS_KEY, Code.UNAVAILABLE.toString());

      assertThat(openTelemetryTesting.getMetrics())
          .anySatisfy(
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
                                          .hasAttributes(clientAttributes))));
    } finally {
      NameResolverRegistry.getDefaultRegistry().deregister(failingProvider);
    }
  }


  @Test
  public void serverMetrics_recordsBaggage_endToEnd() throws Exception {
    DoubleHistogram mockDurationHistogram = mock(DoubleHistogram.class);
    OpenTelemetryMetricsResource mockResource = OpenTelemetryMetricsResource.builder()
        .serverCallDurationCounter(mockDurationHistogram)
        .build();

    OpenTelemetry openTelemetry = OpenTelemetrySdk
        .builder()
        .setPropagators(ContextPropagators.create(
            W3CBaggagePropagator.getInstance()))
        .build();

    OpenTelemetryMetricsModule module = newOpenTelemetryMetricsModule(mockResource);
    OpenTelemetryTracingModule tracingModule = new OpenTelemetryTracingModule(openTelemetry);

    String serverName = InProcessServerBuilder.generateName();
    InProcessServerBuilder serverBuilder = InProcessServerBuilder
        .forName(serverName).directExecutor();

    serverBuilder.addStreamTracerFactory(tracingModule.getServerTracerFactory());
    serverBuilder.intercept(tracingModule.getServerSpanPropagationInterceptor());
    serverBuilder.addStreamTracerFactory(module.getServerTracerFactory());

    serverBuilder.addService(ServerServiceDefinition.builder(
            ServiceDescriptor.newBuilder("package1.service2")
                .addMethod(method)
                .build())
        .addMethod(method, new ServerCallHandler<String, String>() {
          @Override
          public ServerCall.Listener<String> startCall(
              ServerCall<String, String> call, Metadata headers) {
            call.sendHeaders(new Metadata());
            call.sendMessage("response");
            call.close(Status.OK, new Metadata());
            return new ServerCall.Listener<String>() {
            };
          }
        }).build());
    grpcCleanup.register(serverBuilder.build().start());

    InProcessChannelBuilder channelBuilder = InProcessChannelBuilder
        .forName(serverName).directExecutor();
    channelBuilder.intercept(tracingModule.getClientInterceptor());
    channelBuilder.intercept(module.getClientInterceptor(serverName));
    Channel channel = grpcCleanup.register(channelBuilder.intercept(new ClientInterceptor() {
      @Override
      public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
          MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
        return next.newCall(method, callOptions);
      }
    }).build());

    Baggage baggage = Baggage.builder()
        .put("baggage-key-1", "baggage-val-1")
        .build();

    Context otelContext = Context.root().with(baggage);

    try (Scope scope = otelContext.makeCurrent()) {
      ClientCalls.blockingUnaryCall(channel,
          method, CallOptions.DEFAULT, "request");
    }

    ArgumentCaptor<Context> contextCaptor = ArgumentCaptor.forClass(Context.class);
    verify(mockDurationHistogram).record(
        anyDouble(),
        any(),
        contextCaptor.capture());

    Baggage capturedBaggage = Baggage.fromContext(contextCaptor.getValue());
    assertNotNull("Captured context should have baggage", capturedBaggage);
    assertEquals(
        "baggage-val-1", capturedBaggage.getEntryValue("baggage-key-1"));
  }

  @Test
  public void clientCallDelayDuration_redundantStart_doesNotResetStopwatch() {
    String target = "target:///";
    OpenTelemetryMetricsResource resource = GrpcOpenTelemetry.createMetricInstruments(testMeter,
        ImmutableMap.of(CLIENT_CALL_DELAY_DURATION, true), disableDefaultMetrics);
    OpenTelemetryMetricsModule module = newOpenTelemetryMetricsModule(resource);
    CallAttemptsTracerFactory callAttemptsTracerFactory =
        new CallAttemptsTracerFactory(module, target, CALL_OPTIONS, method.getFullMethodName(),
            emptyList(), Context.root());

    callAttemptsTracerFactory.recordDelayStart("resolving", "reason1");
    fakeClock.forwardTime(125, MILLISECONDS);
    // Same delay type: the delay is unchanged, only the channel's reason bookkeeping moved on.
    callAttemptsTracerFactory.recordDelayStart("resolving", "reason2");
    fakeClock.forwardTime(250, MILLISECONDS);
    callAttemptsTracerFactory.recordDelayEnd("resolving");

    // A single data point, timed from the first start rather than restarted by the second one.
    assertThat(openTelemetryTesting.getMetrics())
        .anySatisfy(metric -> assertThat(metric)
            .hasName(CLIENT_CALL_DELAY_DURATION)
            .hasHistogramSatisfying(histogram -> histogram.hasPointsSatisfying(
                point -> point
                    .hasCount(1)
                    .hasSum(0.375)
                    .hasAttributes(delayAttributes(target, "resolving")))));
  }

  @Test
  public void clientCallDelayDuration_delayTypeTransition_recordsOnePointPerType() {
    String target = "target:///";
    OpenTelemetryMetricsResource resource = GrpcOpenTelemetry.createMetricInstruments(testMeter,
        ImmutableMap.of(CLIENT_CALL_DELAY_DURATION, true), disableDefaultMetrics);
    OpenTelemetryMetricsModule module = newOpenTelemetryMetricsModule(resource);
    CallAttemptsTracerFactory callAttemptsTracerFactory =
        new CallAttemptsTracerFactory(module, target, CALL_OPTIONS, method.getFullMethodName(),
            emptyList(), Context.root());

    // How the channel drives a delay type change: it ends the current delay and starts a new one.
    callAttemptsTracerFactory.recordDelayStart("resolving", "waiting for name resolution");
    fakeClock.forwardTime(100, MILLISECONDS);
    callAttemptsTracerFactory.recordDelayEnd("resolving");
    callAttemptsTracerFactory.recordDelayStart("connecting", "waiting for subchannel");
    fakeClock.forwardTime(200, MILLISECONDS);
    callAttemptsTracerFactory.recordDelayEnd("connecting");

    assertThat(openTelemetryTesting.getMetrics())
        .anySatisfy(metric -> assertThat(metric)
            .hasName(CLIENT_CALL_DELAY_DURATION)
            .hasHistogramSatisfying(histogram -> histogram.hasPointsSatisfying(
                point -> point
                    .hasCount(1)
                    .hasSum(0.1)
                    .hasAttributes(delayAttributes(target, "resolving")),
                point -> point
                    .hasCount(1)
                    .hasSum(0.2)
                    .hasAttributes(delayAttributes(target, "connecting")))));
  }

  @Test
  public void clientCallDelayDuration_startOfNewTypeWhileActive_rollsOverPreviousDelay() {
    String target = "target:///";
    OpenTelemetryMetricsResource resource = GrpcOpenTelemetry.createMetricInstruments(testMeter,
        ImmutableMap.of(CLIENT_CALL_DELAY_DURATION, true), disableDefaultMetrics);
    OpenTelemetryMetricsModule module = newOpenTelemetryMetricsModule(resource);
    CallAttemptsTracerFactory callAttemptsTracerFactory =
        new CallAttemptsTracerFactory(module, target, CALL_OPTIONS, method.getFullMethodName(),
            emptyList(), Context.root());

    // The channel normally ends a delay before starting the next one. If it does not, the
    // outstanding delay is still closed out under its own type instead of being mislabeled.
    callAttemptsTracerFactory.recordDelayStart("resolving", "waiting for name resolution");
    fakeClock.forwardTime(100, MILLISECONDS);
    callAttemptsTracerFactory.recordDelayStart("connecting", "waiting for subchannel");
    fakeClock.forwardTime(200, MILLISECONDS);
    callAttemptsTracerFactory.recordDelayEnd("connecting");

    assertThat(openTelemetryTesting.getMetrics())
        .anySatisfy(metric -> assertThat(metric)
            .hasName(CLIENT_CALL_DELAY_DURATION)
            .hasHistogramSatisfying(histogram -> histogram.hasPointsSatisfying(
                point -> point
                    .hasCount(1)
                    .hasSum(0.1)
                    .hasAttributes(delayAttributes(target, "resolving")),
                point -> point
                    .hasCount(1)
                    .hasSum(0.2)
                    .hasAttributes(delayAttributes(target, "connecting")))));
  }

  @Test
  public void clientCallDelayDuration_reasonChanged_doesNotAffectTheDelay() {
    String target = "target:///";
    OpenTelemetryMetricsResource resource = GrpcOpenTelemetry.createMetricInstruments(testMeter,
        ImmutableMap.of(CLIENT_CALL_DELAY_DURATION, true), disableDefaultMetrics);
    OpenTelemetryMetricsModule module = newOpenTelemetryMetricsModule(resource);
    CallAttemptsTracerFactory callAttemptsTracerFactory =
        new CallAttemptsTracerFactory(module, target, CALL_OPTIONS, method.getFullMethodName(),
            emptyList(), Context.root());

    // Reasons are high-cardinality diagnostics for tracing spans only; they neither record a
    // data point of their own nor restart the delay, even when there is no delay in progress.
    callAttemptsTracerFactory.recordDelayReasonChanged("resolving", "before any delay");
    callAttemptsTracerFactory.recordDelayStart("resolving", "waiting for name resolution");
    fakeClock.forwardTime(125, MILLISECONDS);
    callAttemptsTracerFactory.recordDelayReasonChanged("resolving", "resolution failed once");
    fakeClock.forwardTime(250, MILLISECONDS);
    callAttemptsTracerFactory.recordDelayEnd("resolving");

    assertThat(openTelemetryTesting.getMetrics())
        .anySatisfy(metric -> assertThat(metric)
            .hasName(CLIENT_CALL_DELAY_DURATION)
            .hasHistogramSatisfying(histogram -> histogram.hasPointsSatisfying(
                point -> point
                    .hasCount(1)
                    .hasSum(0.375)
                    .hasAttributes(delayAttributes(target, "resolving")))));
  }

  @Test
  public void clientCallDelay_nullDelayType_throwsNullPointerException() {
    String target = "target:///";
    OpenTelemetryMetricsResource resource = GrpcOpenTelemetry.createMetricInstruments(testMeter,
        ImmutableMap.of(CLIENT_CALL_DELAY_DURATION, true), disableDefaultMetrics);
    OpenTelemetryMetricsModule module = newOpenTelemetryMetricsModule(resource);
    CallAttemptsTracerFactory callAttemptsTracerFactory =
        new CallAttemptsTracerFactory(module, target, CALL_OPTIONS, method.getFullMethodName(),
            emptyList(), Context.root());

    // The channel always supplies the delay type; a null one is a caller bug, not a silent drop.
    assertThrows(NullPointerException.class,
        () -> callAttemptsTracerFactory.recordDelayStart(null, "null delay type"));
    assertThrows(NullPointerException.class,
        () -> callAttemptsTracerFactory.recordDelayEnd(null));

    assertThat(openTelemetryTesting.getMetrics())
        .noneSatisfy(metric -> assertThat(metric).hasName(CLIENT_CALL_DELAY_DURATION));
  }

  @Test
  public void clientCallDelayDuration_openDelayAtCallEnded_recordsPartialDuration() {
    String target = "target:///";
    OpenTelemetryMetricsResource resource = GrpcOpenTelemetry.createMetricInstruments(testMeter,
        ImmutableMap.of(CLIENT_CALL_DELAY_DURATION, true), disableDefaultMetrics);
    OpenTelemetryMetricsModule module = newOpenTelemetryMetricsModule(resource);
    CallAttemptsTracerFactory callAttemptsTracerFactory =
        new CallAttemptsTracerFactory(module, target, CALL_OPTIONS, method.getFullMethodName(),
            emptyList(), Context.root());

    callAttemptsTracerFactory.recordDelayStart("resolving", "waiting for name resolution");
    fakeClock.forwardTime(500, MILLISECONDS);
    // Cancelled while still waiting: gRFC A121 has the call tracer terminate the delay itself.
    callAttemptsTracerFactory.callEnded(Status.CANCELLED.withDescription("cancelled"));

    assertThat(openTelemetryTesting.getMetrics())
        .anySatisfy(metric -> assertThat(metric)
            .hasName(CLIENT_CALL_DELAY_DURATION)
            .hasHistogramSatisfying(histogram -> histogram.hasPointsSatisfying(
                point -> point
                    .hasCount(1)
                    .hasSum(0.5)
                    .hasAttributes(delayAttributes(target, "resolving")))));

    // Ending again, or starting a new delay after the call ended, must not record anything more.
    callAttemptsTracerFactory.recordDelayEnd("resolving");
    callAttemptsTracerFactory.recordDelayStart("resolving", "late start attempt");
    fakeClock.forwardTime(200, MILLISECONDS);
    callAttemptsTracerFactory.recordDelayEnd("resolving");

    assertThat(openTelemetryTesting.getMetrics())
        .anySatisfy(metric -> assertThat(metric)
            .hasName(CLIENT_CALL_DELAY_DURATION)
            .hasHistogramSatisfying(histogram -> histogram.hasPointsSatisfying(
                point -> point
                    .hasCount(1)
                    .hasSum(0.5)
                    .hasAttributes(delayAttributes(target, "resolving")))));
  }

  @Test
  public void clientAttemptDelayDuration_redundantStart_doesNotResetStopwatch() {
    String target = "target:///";
    OpenTelemetryMetricsResource resource = GrpcOpenTelemetry.createMetricInstruments(testMeter,
        ImmutableMap.of(CLIENT_ATTEMPT_DELAY_DURATION, true), disableDefaultMetrics);
    OpenTelemetryMetricsModule module = newOpenTelemetryMetricsModule(resource);
    CallAttemptsTracerFactory callAttemptsTracerFactory =
        new CallAttemptsTracerFactory(module, target, CALL_OPTIONS, method.getFullMethodName(),
            emptyList(), Context.root());
    ClientStreamTracer tracer =
        callAttemptsTracerFactory.newClientStreamTracer(STREAM_INFO, new Metadata());

    tracer.recordDelayStart("connecting", "reason1");
    fakeClock.forwardTime(125, MILLISECONDS);
    tracer.recordDelayStart("connecting", "reason2");
    fakeClock.forwardTime(250, MILLISECONDS);
    tracer.recordDelayEnd("connecting");

    assertThat(openTelemetryTesting.getMetrics())
        .anySatisfy(metric -> assertThat(metric)
            .hasName(CLIENT_ATTEMPT_DELAY_DURATION)
            .hasHistogramSatisfying(histogram -> histogram.hasPointsSatisfying(
                point -> point
                    .hasCount(1)
                    .hasSum(0.375)
                    .hasAttributes(delayAttributes(target, "connecting")))));
  }

  @Test
  public void clientAttemptDelayDuration_startOfNewTypeWhileActive_rollsOverPreviousDelay() {
    String target = "target:///";
    OpenTelemetryMetricsResource resource = GrpcOpenTelemetry.createMetricInstruments(testMeter,
        ImmutableMap.of(CLIENT_ATTEMPT_DELAY_DURATION, true), disableDefaultMetrics);
    OpenTelemetryMetricsModule module = newOpenTelemetryMetricsModule(resource);
    CallAttemptsTracerFactory callAttemptsTracerFactory =
        new CallAttemptsTracerFactory(module, target, CALL_OPTIONS, method.getFullMethodName(),
            emptyList(), Context.root());
    ClientStreamTracer tracer =
        callAttemptsTracerFactory.newClientStreamTracer(STREAM_INFO, new Metadata());

    // A priority policy failing over produces a new delay type, e.g. "0:connecting" ->
    // "1:connecting". Each type gets its own data point, whether or not the previous delay was
    // explicitly ended first.
    tracer.recordDelayStart("0:connecting", "waiting for priority 0");
    fakeClock.forwardTime(100, MILLISECONDS);
    tracer.recordDelayStart("1:connecting", "failing over to priority 1");
    fakeClock.forwardTime(200, MILLISECONDS);
    tracer.recordDelayEnd("1:connecting");

    assertThat(openTelemetryTesting.getMetrics())
        .anySatisfy(metric -> assertThat(metric)
            .hasName(CLIENT_ATTEMPT_DELAY_DURATION)
            .hasHistogramSatisfying(histogram -> histogram.hasPointsSatisfying(
                point -> point
                    .hasCount(1)
                    .hasSum(0.1)
                    .hasAttributes(delayAttributes(target, "0:connecting")),
                point -> point
                    .hasCount(1)
                    .hasSum(0.2)
                    .hasAttributes(delayAttributes(target, "1:connecting")))));
  }

  @Test
  public void clientAttemptDelayDuration_recordsOnlyTheSpecifiedLabels() {
    String target = "target:///";
    OpenTelemetryMetricsResource resource = GrpcOpenTelemetry.createMetricInstruments(testMeter,
        ImmutableMap.of(CLIENT_ATTEMPT_DELAY_DURATION, true), disableDefaultMetrics);
    OpenTelemetryMetricsModule module = new OpenTelemetryMetricsModule(
        fakeClock.getStopwatchSupplier(),
        resource,
        Arrays.asList(LOCALITY_KEY.getKey(), BACKEND_SERVICE_KEY.getKey()),
        emptyList());
    CallAttemptsTracerFactory callAttemptsTracerFactory =
        new CallAttemptsTracerFactory(module, target, CALL_OPTIONS, method.getFullMethodName(),
            emptyList(), Context.root());
    ClientStreamTracer tracer =
        callAttemptsTracerFactory.newClientStreamTracer(STREAM_INFO, new Metadata());
    tracer.addOptionalLabel(LOCALITY_KEY.getKey(), "us-east1-a");
    tracer.addOptionalLabel(BACKEND_SERVICE_KEY.getKey(), "backend-service-1");

    tracer.recordDelayStart("connecting", "reason1");
    fakeClock.forwardTime(100, MILLISECONDS);
    tracer.recordDelayEnd("connecting");

    // gRFC A121 defines exactly grpc.target, grpc.method and grpc.delay_type for the delay
    // histograms, so the optional labels are not added even when they are enabled and known.
    assertThat(openTelemetryTesting.getMetrics())
        .anySatisfy(metric -> assertThat(metric)
            .hasName(CLIENT_ATTEMPT_DELAY_DURATION)
            .hasHistogramSatisfying(histogram -> histogram.hasPointsSatisfying(
                point -> point
                    .hasCount(1)
                    .hasSum(0.1)
                    .hasAttributes(delayAttributes(target, "connecting")))));
  }

  @Test
  public void clientAttemptDelay_nullDelayType_throwsNullPointerException() {
    String target = "target:///";
    OpenTelemetryMetricsResource resource = GrpcOpenTelemetry.createMetricInstruments(testMeter,
        ImmutableMap.of(CLIENT_ATTEMPT_DELAY_DURATION, true), disableDefaultMetrics);
    OpenTelemetryMetricsModule module = newOpenTelemetryMetricsModule(resource);
    CallAttemptsTracerFactory callAttemptsTracerFactory =
        new CallAttemptsTracerFactory(module, target, CALL_OPTIONS, method.getFullMethodName(),
            emptyList(), Context.root());
    ClientStreamTracer tracer =
        callAttemptsTracerFactory.newClientStreamTracer(STREAM_INFO, new Metadata());

    assertThrows(NullPointerException.class,
        () -> tracer.recordDelayStart(null, "null delay type"));
    assertThrows(NullPointerException.class, () -> tracer.recordDelayEnd(null));

    assertThat(openTelemetryTesting.getMetrics())
        .noneSatisfy(metric -> assertThat(metric).hasName(CLIENT_ATTEMPT_DELAY_DURATION));
  }

  @Test
  public void clientAttemptDelayDuration_openDelayAtStreamClosed_recordsPartialDuration() {
    String target = "target:///";
    OpenTelemetryMetricsResource resource = GrpcOpenTelemetry.createMetricInstruments(testMeter,
        ImmutableMap.of(CLIENT_ATTEMPT_DELAY_DURATION, true), disableDefaultMetrics);
    OpenTelemetryMetricsModule module = newOpenTelemetryMetricsModule(resource);
    CallAttemptsTracerFactory callAttemptsTracerFactory =
        new CallAttemptsTracerFactory(module, target, CALL_OPTIONS, method.getFullMethodName(),
            emptyList(), Context.root());
    ClientStreamTracer tracer =
        callAttemptsTracerFactory.newClientStreamTracer(STREAM_INFO, new Metadata());

    tracer.recordDelayStart("connecting", "waiting for subchannel");
    fakeClock.forwardTime(250, MILLISECONDS);
    // Cancelled while the pick was still queued.
    tracer.streamClosed(Status.CANCELLED);

    assertThat(openTelemetryTesting.getMetrics())
        .anySatisfy(metric -> assertThat(metric)
            .hasName(CLIENT_ATTEMPT_DELAY_DURATION)
            .hasHistogramSatisfying(histogram -> histogram.hasPointsSatisfying(
                point -> point
                    .hasCount(1)
                    .hasSum(0.25)
                    .hasAttributes(delayAttributes(target, "connecting")))));
  }

  @Test
  public void clientAttemptDelayStart_afterStreamClosed_noOp() {
    String target = "target:///";
    OpenTelemetryMetricsResource resource = GrpcOpenTelemetry.createMetricInstruments(testMeter,
        ImmutableMap.of(CLIENT_ATTEMPT_DELAY_DURATION, true), disableDefaultMetrics);
    OpenTelemetryMetricsModule module = newOpenTelemetryMetricsModule(resource);
    CallAttemptsTracerFactory callAttemptsTracerFactory =
        new CallAttemptsTracerFactory(module, target, CALL_OPTIONS, method.getFullMethodName(),
            emptyList(), Context.root());
    ClientStreamTracer tracer =
        callAttemptsTracerFactory.newClientStreamTracer(STREAM_INFO, new Metadata());

    tracer.streamClosed(Status.OK);
    tracer.recordDelayStart("connecting", "post-close");
    tracer.recordDelayReasonChanged("connecting", "changed");
    fakeClock.forwardTime(100, MILLISECONDS);
    tracer.recordDelayEnd("connecting");
    callAttemptsTracerFactory.callEnded(Status.OK);

    assertThat(openTelemetryTesting.getMetrics())
        .noneSatisfy(metric -> assertThat(metric).hasName(CLIENT_ATTEMPT_DELAY_DURATION));
  }

  @Test
  public void clientCallDelayStart_afterCallEnded_noOp() {
    String target = "target:///";
    OpenTelemetryMetricsResource resource = GrpcOpenTelemetry.createMetricInstruments(testMeter,
        ImmutableMap.of(CLIENT_CALL_DELAY_DURATION, true), disableDefaultMetrics);
    OpenTelemetryMetricsModule module = newOpenTelemetryMetricsModule(resource);
    CallAttemptsTracerFactory callAttemptsTracerFactory =
        new CallAttemptsTracerFactory(module, target, CALL_OPTIONS, method.getFullMethodName(),
            emptyList(), Context.root());

    callAttemptsTracerFactory.callEnded(Status.OK);
    callAttemptsTracerFactory.recordDelayStart("resolving", "post-close");
    callAttemptsTracerFactory.recordDelayReasonChanged("resolving", "changed");
    fakeClock.forwardTime(100, MILLISECONDS);
    callAttemptsTracerFactory.recordDelayEnd("resolving");

    assertThat(openTelemetryTesting.getMetrics())
        .noneSatisfy(metric -> assertThat(metric).hasName(CLIENT_CALL_DELAY_DURATION));
  }

  @Test
  public void delayMetrics_metricsNotEnabled_allMethodsNoOp() {
    // Delay metrics are opt-in via enableMetrics() and off by default, so the resource built with
    // the default (empty) enabled-metrics map has no delay instruments and all calls are no-ops.
    String target = "target:///";
    OpenTelemetryMetricsResource resource = GrpcOpenTelemetry.createMetricInstruments(testMeter,
        enabledMetricsMap, disableDefaultMetrics);
    OpenTelemetryMetricsModule module = newOpenTelemetryMetricsModule(resource);
    CallAttemptsTracerFactory callAttemptsTracerFactory =
        new CallAttemptsTracerFactory(module, target, CALL_OPTIONS, method.getFullMethodName(),
            emptyList(), Context.root());
    ClientStreamTracer tracer =
        callAttemptsTracerFactory.newClientStreamTracer(STREAM_INFO, new Metadata());

    callAttemptsTracerFactory.recordDelayStart("resolving", "reason1");
    callAttemptsTracerFactory.recordDelayReasonChanged("resolving", "reason2");
    fakeClock.forwardTime(100, MILLISECONDS);
    callAttemptsTracerFactory.recordDelayEnd("resolving");

    tracer.recordDelayStart("connecting", "reason1");
    tracer.recordDelayReasonChanged("connecting", "reason2");
    fakeClock.forwardTime(100, MILLISECONDS);
    tracer.recordDelayEnd("connecting");

    assertThat(openTelemetryTesting.getMetrics())
        .extracting("name")
        .doesNotContain(CLIENT_CALL_DELAY_DURATION, CLIENT_ATTEMPT_DELAY_DURATION);
  }

  @Test
  public void callEnded_beforeAttemptEnded_recordsFinishedCall() {
    String target = "target:///";
    OpenTelemetryMetricsResource resource = GrpcOpenTelemetry.createMetricInstruments(testMeter,
        enabledMetricsMap, disableDefaultMetrics);
    OpenTelemetryMetricsModule module = newOpenTelemetryMetricsModule(resource);
    CallAttemptsTracerFactory callAttemptsTracerFactory =
        new CallAttemptsTracerFactory(module, target, CALL_OPTIONS, method.getFullMethodName(),
            emptyList(), Context.root());
    callAttemptsTracerFactory.newClientStreamTracer(STREAM_INFO, new Metadata());

    fakeClock.forwardTime(100, MILLISECONDS);
    // The call ends while an attempt is still active, so the finished call is only recorded once
    // the attempt ends.
    callAttemptsTracerFactory.callEnded(Status.OK);
    callAttemptsTracerFactory.attemptEnded();

    assertThat(openTelemetryTesting.getMetrics())
        .anySatisfy(metric -> assertThat(metric)
            .hasName(CLIENT_CALL_DURATION)
            .hasHistogramSatisfying(histogram -> histogram.hasPointsSatisfying(
                point -> point
                    .hasCount(1)
                    .hasSum(0.1)
                    .hasAttributes(io.opentelemetry.api.common.Attributes.of(
                        METHOD_KEY, method.getFullMethodName(),
                        TARGET_KEY, target,
                        STATUS_KEY, Code.OK.toString())))));
  }

  @Test
  public void serverStreamClosed_calledTwice_secondCallNoOp() {
    OpenTelemetryMetricsResource resource = GrpcOpenTelemetry.createMetricInstruments(testMeter,
        enabledMetricsMap, disableDefaultMetrics);
    OpenTelemetryMetricsModule module = newOpenTelemetryMetricsModule(resource);

    ServerStreamTracer.Factory serverTracerFactory = module.getServerTracerFactory();
    ServerStreamTracer serverTracer =
        serverTracerFactory.newServerStreamTracer(method.getFullMethodName(), new Metadata());

    fakeClock.forwardTime(100, MILLISECONDS);
    serverTracer.streamClosed(Status.OK);
    fakeClock.forwardTime(100, MILLISECONDS);
    serverTracer.streamClosed(Status.CANCELLED);

    // Only the first close is recorded. The method name was never resolved on this tracer, so it
    // is reported as "other".
    assertThat(openTelemetryTesting.getMetrics())
        .anySatisfy(metric -> assertThat(metric)
            .hasName(SERVER_CALL_DURATION)
            .hasHistogramSatisfying(histogram -> histogram.hasPointsSatisfying(
                point -> point
                    .hasCount(1)
                    .hasSum(0.1)
                    .hasAttributes(io.opentelemetry.api.common.Attributes.of(
                        METHOD_KEY, "other",
                        STATUS_KEY, Code.OK.toString())))));
  }

  /** The three labels gRFC A121 defines for both delay histograms. */
  private io.opentelemetry.api.common.Attributes delayAttributes(
      String target, String delayType) {
    return io.opentelemetry.api.common.Attributes.of(
        METHOD_KEY, method.getFullMethodName(),
        TARGET_KEY, target,
        DELAY_TYPE_KEY, delayType);
  }

  private static List<MetricData> sortByName(List<MetricData> metrics) {
    metrics.sort((m1, m2) -> m1.getName().compareTo(m2.getName()));
    return metrics;
  }
}
