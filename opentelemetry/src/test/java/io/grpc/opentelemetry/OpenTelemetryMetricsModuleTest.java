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
import static org.mockito.Mockito.verify;

import com.google.common.collect.ImmutableMap;
import io.grpc.Attributes;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ClientInterceptors;
import io.grpc.ClientStreamTracer;
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
import io.grpc.internal.FakeClock;
import io.grpc.opentelemetry.OpenTelemetryMetricsModule.CallAttemptsTracerFactory;
import io.grpc.opentelemetry.internal.OpenTelemetryConstants;
import io.grpc.testing.GrpcServerRule;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.sdk.common.InstrumentationScopeInfo;
import io.opentelemetry.sdk.testing.junit4.OpenTelemetryRule;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;
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
    ClientInterceptor callOptionsCatureInterceptor = new ClientInterceptor() {
      @Override
      public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
          MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
        capturedCallOptions.set(callOptions);
        return next.newCall(method, callOptions);
      }
    };
    Channel interceptedChannel =
        ClientInterceptors.intercept(
            grpcServerRule.getChannel(), callOptionsCatureInterceptor,
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
        fakeClock.getStopwatchSupplier(), resource, Arrays.asList("grpc.lb.locality"), emptyList());
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
        fakeClock.getStopwatchSupplier(), resource, Arrays.asList("grpc.lb.locality"), emptyList());
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
        emptyList());
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
        emptyList());
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

  private OpenTelemetryMetricsModule newOpenTelemetryMetricsModule(
      OpenTelemetryMetricsResource resource) {
    return new OpenTelemetryMetricsModule(
        fakeClock.getStopwatchSupplier(), resource, emptyList(), emptyList());
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
}
