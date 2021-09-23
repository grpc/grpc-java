/*
 * Copyright 2017 The gRPC Authors
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

package io.grpc.census;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static io.grpc.census.CensusStatsModule.CallAttemptsTracerFactory.RETRIES_PER_CALL;
import static io.grpc.census.CensusStatsModule.CallAttemptsTracerFactory.RETRY_DELAY_PER_CALL;
import static io.grpc.census.CensusStatsModule.CallAttemptsTracerFactory.TRANSPARENT_RETRIES_PER_CALL;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import io.grpc.Attributes;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ClientInterceptors;
import io.grpc.ClientStreamTracer;
import io.grpc.Context;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerServiceDefinition;
import io.grpc.ServerStreamTracer;
import io.grpc.ServerStreamTracer.ServerCallInfo;
import io.grpc.Status;
import io.grpc.census.CensusTracingModule.CallAttemptsTracerFactory;
import io.grpc.census.internal.DeprecatedCensusConstants;
import io.grpc.internal.FakeClock;
import io.grpc.internal.testing.StatsTestUtils;
import io.grpc.internal.testing.StatsTestUtils.FakeStatsRecorder;
import io.grpc.internal.testing.StatsTestUtils.FakeTagContextBinarySerializer;
import io.grpc.internal.testing.StatsTestUtils.FakeTagger;
import io.grpc.internal.testing.StatsTestUtils.MockableSpan;
import io.grpc.testing.GrpcServerRule;
import io.opencensus.common.Function;
import io.opencensus.common.Functions;
import io.opencensus.contrib.grpc.metrics.RpcMeasureConstants;
import io.opencensus.contrib.grpc.metrics.RpcViewConstants;
import io.opencensus.impl.stats.StatsComponentImpl;
import io.opencensus.stats.AggregationData;
import io.opencensus.stats.AggregationData.CountData;
import io.opencensus.stats.AggregationData.LastValueDataDouble;
import io.opencensus.stats.AggregationData.LastValueDataLong;
import io.opencensus.stats.AggregationData.SumDataDouble;
import io.opencensus.stats.Measure;
import io.opencensus.stats.StatsComponent;
import io.opencensus.stats.View;
import io.opencensus.tags.TagContext;
import io.opencensus.tags.TagValue;
import io.opencensus.trace.AttributeValue;
import io.opencensus.trace.BlankSpan;
import io.opencensus.trace.EndSpanOptions;
import io.opencensus.trace.MessageEvent;
import io.opencensus.trace.Span;
import io.opencensus.trace.SpanBuilder;
import io.opencensus.trace.SpanContext;
import io.opencensus.trace.Tracer;
import io.opencensus.trace.propagation.BinaryFormat;
import io.opencensus.trace.propagation.SpanContextParseException;
import io.opencensus.trace.unsafe.ContextUtils;
import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/**
 * Test for {@link CensusStatsModule} and {@link CensusTracingModule}.
 */
@RunWith(JUnit4.class)
public class CensusModulesTest {
  private static final CallOptions.Key<String> CUSTOM_OPTION =
      CallOptions.Key.createWithDefault("option1", "default");
  private static final CallOptions CALL_OPTIONS =
      CallOptions.DEFAULT.withOption(CUSTOM_OPTION, "customvalue");
  private static final ClientStreamTracer.StreamInfo STREAM_INFO =
      ClientStreamTracer.StreamInfo.newBuilder().build();

  private static class StringInputStream extends InputStream {
    final String string;

    StringInputStream(String string) {
      this.string = string;
    }

    @Override
    public int read() {
      // InProcessTransport doesn't actually read bytes from the InputStream.  The InputStream is
      // passed to the InProcess server and consumed by MARSHALLER.parse().
      throw new UnsupportedOperationException("Should not be called");
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

  private final MethodDescriptor<String, String> method =
      MethodDescriptor.<String, String>newBuilder()
          .setType(MethodDescriptor.MethodType.UNKNOWN)
          .setRequestMarshaller(MARSHALLER)
          .setResponseMarshaller(MARSHALLER)
          .setFullMethodName("package1.service2/method3")
          .build();
  private final MethodDescriptor<String, String> sampledMethod =
      method.toBuilder().setSampledToLocalTracing(true).build();

  private final FakeClock fakeClock = new FakeClock();
  private final FakeTagger tagger = new FakeTagger();
  private final FakeTagContextBinarySerializer tagCtxSerializer =
      new FakeTagContextBinarySerializer();
  private final FakeStatsRecorder statsRecorder = new FakeStatsRecorder();
  private final Random random = new Random(1234);
  private final Span fakeClientParentSpan = MockableSpan.generateRandomSpan(random);
  private final Span spyClientSpan = spy(MockableSpan.generateRandomSpan(random));
  private final Span spyAttemptSpan = spy(MockableSpan.generateRandomSpan(random));
  private final SpanContext fakeAttemptSpanContext = spyAttemptSpan.getContext();
  private final Span spyServerSpan = spy(MockableSpan.generateRandomSpan(random));
  private final byte[] binarySpanContext = new byte[]{3, 1, 5};
  private final SpanBuilder spyClientSpanBuilder = spy(new MockableSpan.Builder());
  private final SpanBuilder spyAttemptSpanBuilder = spy(new MockableSpan.Builder());
  private final SpanBuilder spyServerSpanBuilder = spy(new MockableSpan.Builder());

  @Rule
  public final GrpcServerRule grpcServerRule = new GrpcServerRule().directExecutor();

  @Mock
  private Tracer tracer;
  @Mock
  private BinaryFormat mockTracingPropagationHandler;
  @Mock
  private ClientCall.Listener<String> mockClientCallListener;
  @Mock
  private ServerCall.Listener<String> mockServerCallListener;
  @Captor
  private ArgumentCaptor<Status> statusCaptor;
  @Captor
  private ArgumentCaptor<MessageEvent> messageEventCaptor;

  private CensusStatsModule censusStats;
  private CensusTracingModule censusTracing;

  @Before
  public void setUp() throws Exception {
    when(spyClientSpanBuilder.startSpan()).thenReturn(spyClientSpan);
    when(spyAttemptSpanBuilder.startSpan()).thenReturn(spyAttemptSpan);
    when(tracer.spanBuilderWithExplicitParent(
            eq("Sent.package1.service2.method3"), ArgumentMatchers.<Span>any()))
        .thenReturn(spyClientSpanBuilder);
    when(tracer.spanBuilderWithExplicitParent(
            eq("Attempt.package1.service2.method3"), ArgumentMatchers.<Span>any()))
        .thenReturn(spyAttemptSpanBuilder);
    when(spyServerSpanBuilder.startSpan()).thenReturn(spyServerSpan);
    when(tracer.spanBuilderWithRemoteParent(anyString(), ArgumentMatchers.<SpanContext>any()))
        .thenReturn(spyServerSpanBuilder);
    when(mockTracingPropagationHandler.toByteArray(any(SpanContext.class)))
        .thenReturn(binarySpanContext);
    when(mockTracingPropagationHandler.fromByteArray(any(byte[].class)))
        .thenReturn(fakeAttemptSpanContext);
    censusStats =
        new CensusStatsModule(
            tagger, tagCtxSerializer, statsRecorder, fakeClock.getStopwatchSupplier(),
            true, true, true, false /* real-time */, true);
    censusTracing = new CensusTracingModule(tracer, mockTracingPropagationHandler);
  }

  @After
  public void wrapUp() {
    assertNull(statsRecorder.pollRecord());
  }

  @Test
  public void clientInterceptorNoCustomTag() {
    testClientInterceptors(false);
  }

  @Test
  public void clientInterceptorCustomTag() {
    testClientInterceptors(true);
  }

  // Test that Census ClientInterceptors uses the TagContext and Span out of the current Context
  // to create the ClientCallTracer, and that it intercepts ClientCall.Listener.onClose() to call
  // ClientCallTracer.callEnded().
  private void testClientInterceptors(boolean nonDefaultContext) {
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
            censusStats.getClientInterceptor(), censusTracing.getClientInterceptor());
    ClientCall<String, String> call;
    if (nonDefaultContext) {
      Context ctx =
          io.opencensus.tags.unsafe.ContextUtils.withValue(
              Context.ROOT,
              tagger
                  .emptyBuilder()
                  .putLocal(StatsTestUtils.EXTRA_TAG, TagValue.create("extra value"))
                  .build());
      ctx = ContextUtils.withValue(ctx, fakeClientParentSpan);
      Context origCtx = ctx.attach();
      try {
        call = interceptedChannel.newCall(method, CALL_OPTIONS);
      } finally {
        ctx.detach(origCtx);
      }
    } else {
      assertEquals(
          io.opencensus.tags.unsafe.ContextUtils.getValue(Context.ROOT),
          io.opencensus.tags.unsafe.ContextUtils.getValue(Context.current()));
      assertEquals(ContextUtils.getValue(Context.current()), BlankSpan.INSTANCE);
      call = interceptedChannel.newCall(method, CALL_OPTIONS);
    }

    // The interceptor adds tracer factory to CallOptions
    assertEquals("customvalue", capturedCallOptions.get().getOption(CUSTOM_OPTION));
    assertEquals(2, capturedCallOptions.get().getStreamTracerFactories().size());
    assertTrue(
        capturedCallOptions.get().getStreamTracerFactories().get(0)
        instanceof CallAttemptsTracerFactory);
    assertTrue(
        capturedCallOptions.get().getStreamTracerFactories().get(1)
        instanceof CensusStatsModule.CallAttemptsTracerFactory);

    // Make the call
    Metadata headers = new Metadata();
    call.start(mockClientCallListener, headers);

    StatsTestUtils.MetricsRecord record = statsRecorder.pollRecord();
    assertNotNull(record);
    TagValue methodTag = record.tags.get(RpcMeasureConstants.GRPC_CLIENT_METHOD);
    assertEquals(method.getFullMethodName(), methodTag.asString());
    if (nonDefaultContext) {
      TagValue extraTag = record.tags.get(StatsTestUtils.EXTRA_TAG);
      assertEquals("extra value", extraTag.asString());
      assertEquals(2, record.tags.size());
    } else {
      assertNull(record.tags.get(StatsTestUtils.EXTRA_TAG));
      assertEquals(1, record.tags.size());
    }

    if (nonDefaultContext) {
      verify(tracer).spanBuilderWithExplicitParent(
          eq("Sent.package1.service2.method3"), same(fakeClientParentSpan));
      verify(spyClientSpanBuilder).setRecordEvents(eq(true));
    } else {
      verify(tracer).spanBuilderWithExplicitParent(
          eq("Sent.package1.service2.method3"), ArgumentMatchers.<Span>isNotNull());
      verify(spyClientSpanBuilder).setRecordEvents(eq(true));
    }
    verify(spyClientSpan, never()).end(any(EndSpanOptions.class));

    // End the call
    call.halfClose();
    call.request(1);

    verify(mockClientCallListener).onClose(statusCaptor.capture(), any(Metadata.class));
    Status status = statusCaptor.getValue();
    assertEquals(Status.Code.PERMISSION_DENIED, status.getCode());
    assertEquals("No you don't", status.getDescription());

    // The intercepting listener calls callEnded() on ClientCallTracer, which records to Census.
    record = statsRecorder.pollRecord();
    assertNotNull(record);
    methodTag = record.tags.get(RpcMeasureConstants.GRPC_CLIENT_METHOD);
    assertEquals(method.getFullMethodName(), methodTag.asString());
    TagValue statusTag = record.tags.get(RpcMeasureConstants.GRPC_CLIENT_STATUS);
    assertEquals(Status.Code.PERMISSION_DENIED.toString(), statusTag.asString());
    if (nonDefaultContext) {
      TagValue extraTag = record.tags.get(StatsTestUtils.EXTRA_TAG);
      assertEquals("extra value", extraTag.asString());
    } else {
      assertNull(record.tags.get(StatsTestUtils.EXTRA_TAG));
    }
    verify(spyClientSpan).end(
        EndSpanOptions.builder()
            .setStatus(
                io.opencensus.trace.Status.PERMISSION_DENIED
                    .withDescription("No you don't"))
            .setSampleToLocalSpanStore(false)
            .build());
    verify(spyClientSpan, never()).end();
    assertZeroRetryRecorded();
  }

  @Test
  public void clientBasicStatsDefaultContext_starts_finishes_noRealTime() {
    subtestClientBasicStatsDefaultContext(true, true, false);
  }

  @Test
  public void clientBasicStatsDefaultContext_starts_noFinishes_noRealTime() {
    subtestClientBasicStatsDefaultContext(true, false, false);
  }

  @Test
  public void clientBasicStatsDefaultContext_noStarts_finishes_noRealTime() {
    subtestClientBasicStatsDefaultContext(false, true, false);
  }

  @Test
  public void clientBasicStatsDefaultContext_noStarts_noFinishes_noRealTime() {
    subtestClientBasicStatsDefaultContext(false, false, false);
  }

  @Test
  public void clientBasicStatsDefaultContext_starts_finishes_realTime() {
    subtestClientBasicStatsDefaultContext(true, true, true);
  }

  private void subtestClientBasicStatsDefaultContext(
      boolean recordStarts, boolean recordFinishes, boolean recordRealTime) {
    CensusStatsModule localCensusStats =
        new CensusStatsModule(
            tagger, tagCtxSerializer, statsRecorder, fakeClock.getStopwatchSupplier(),
            true, recordStarts, recordFinishes, recordRealTime, true);
    CensusStatsModule.CallAttemptsTracerFactory callAttemptsTracerFactory =
        new CensusStatsModule.CallAttemptsTracerFactory(
            localCensusStats, tagger.empty(), method.getFullMethodName());
    Metadata headers = new Metadata();
    ClientStreamTracer tracer =
        callAttemptsTracerFactory.newClientStreamTracer(STREAM_INFO, headers);

    if (recordStarts) {
      StatsTestUtils.MetricsRecord record = statsRecorder.pollRecord();
      assertNotNull(record);
      assertNoServerContent(record);
      assertEquals(1, record.tags.size());
      TagValue methodTag = record.tags.get(RpcMeasureConstants.GRPC_CLIENT_METHOD);
      assertEquals(method.getFullMethodName(), methodTag.asString());
      assertEquals(
          1, record.getMetricAsLongOrFail(DeprecatedCensusConstants.RPC_CLIENT_STARTED_COUNT));
    } else {
      assertNull(statsRecorder.pollRecord());
    }

    fakeClock.forwardTime(30, MILLISECONDS);
    tracer.outboundHeaders();

    fakeClock.forwardTime(100, MILLISECONDS);

    tracer.outboundMessage(0);
    assertRealTimeMetric(
        RpcMeasureConstants.GRPC_CLIENT_SENT_MESSAGES_PER_METHOD, 1, recordRealTime, true);

    tracer.outboundWireSize(1028);
    assertRealTimeMetric(
        RpcMeasureConstants.GRPC_CLIENT_SENT_BYTES_PER_METHOD, 1028, recordRealTime, true);

    tracer.outboundUncompressedSize(1128);

    fakeClock.forwardTime(16, MILLISECONDS);

    tracer.inboundMessage(0);
    assertRealTimeMetric(
        RpcMeasureConstants.GRPC_CLIENT_RECEIVED_MESSAGES_PER_METHOD, 1, recordRealTime, true);

    tracer.inboundWireSize(33);
    assertRealTimeMetric(
        RpcMeasureConstants.GRPC_CLIENT_RECEIVED_BYTES_PER_METHOD, 33, recordRealTime, true);

    tracer.inboundUncompressedSize(67);

    tracer.outboundMessage(1);
    assertRealTimeMetric(
        RpcMeasureConstants.GRPC_CLIENT_SENT_MESSAGES_PER_METHOD, 1, recordRealTime, true);

    tracer.outboundWireSize(99);
    assertRealTimeMetric(
        RpcMeasureConstants.GRPC_CLIENT_SENT_BYTES_PER_METHOD, 99, recordRealTime, true);

    tracer.outboundUncompressedSize(865);

    fakeClock.forwardTime(24, MILLISECONDS);
    tracer.inboundMessage(1);
    assertRealTimeMetric(
        RpcMeasureConstants.GRPC_CLIENT_RECEIVED_MESSAGES_PER_METHOD, 1, recordRealTime, true);

    tracer.inboundWireSize(154);
    assertRealTimeMetric(
        RpcMeasureConstants.GRPC_CLIENT_RECEIVED_BYTES_PER_METHOD, 154, recordRealTime, true);

    tracer.inboundUncompressedSize(552);
    tracer.streamClosed(Status.OK);
    callAttemptsTracerFactory.callEnded(Status.OK);

    if (recordFinishes) {
      StatsTestUtils.MetricsRecord record = statsRecorder.pollRecord();
      assertNotNull(record);
      assertNoServerContent(record);
      TagValue methodTag = record.tags.get(RpcMeasureConstants.GRPC_CLIENT_METHOD);
      assertEquals(method.getFullMethodName(), methodTag.asString());
      TagValue statusTag = record.tags.get(RpcMeasureConstants.GRPC_CLIENT_STATUS);
      assertEquals(Status.Code.OK.toString(), statusTag.asString());
      assertEquals(
          1, record.getMetricAsLongOrFail(DeprecatedCensusConstants.RPC_CLIENT_FINISHED_COUNT));
      assertNull(record.getMetric(DeprecatedCensusConstants.RPC_CLIENT_ERROR_COUNT));
      assertEquals(
          2, record.getMetricAsLongOrFail(DeprecatedCensusConstants.RPC_CLIENT_REQUEST_COUNT));
      assertEquals(
          1028 + 99,
          record.getMetricAsLongOrFail(DeprecatedCensusConstants.RPC_CLIENT_REQUEST_BYTES));
      assertEquals(
          1128 + 865,
          record.getMetricAsLongOrFail(
              DeprecatedCensusConstants.RPC_CLIENT_UNCOMPRESSED_REQUEST_BYTES));
      assertEquals(
          2, record.getMetricAsLongOrFail(DeprecatedCensusConstants.RPC_CLIENT_RESPONSE_COUNT));
      assertEquals(
          33 + 154,
          record.getMetricAsLongOrFail(DeprecatedCensusConstants.RPC_CLIENT_RESPONSE_BYTES));
      assertEquals(
          67 + 552,
          record.getMetricAsLongOrFail(
              DeprecatedCensusConstants.RPC_CLIENT_UNCOMPRESSED_RESPONSE_BYTES));
      assertEquals(30 + 100 + 16 + 24,
          record.getMetricAsLongOrFail(DeprecatedCensusConstants.RPC_CLIENT_ROUNDTRIP_LATENCY));
      assertZeroRetryRecorded();
    } else {
      assertNull(statsRecorder.pollRecord());
    }
  }

  // This test is only unit-testing the stat recording logic. The retry behavior is faked.
  @Test
  public void recordRetryStats() {
    CensusStatsModule localCensusStats =
        new CensusStatsModule(
            tagger, tagCtxSerializer, statsRecorder, fakeClock.getStopwatchSupplier(),
            true, true, true, true, true);
    CensusStatsModule.CallAttemptsTracerFactory callAttemptsTracerFactory =
        new CensusStatsModule.CallAttemptsTracerFactory(
            localCensusStats, tagger.empty(), method.getFullMethodName());
    ClientStreamTracer tracer =
        callAttemptsTracerFactory.newClientStreamTracer(STREAM_INFO,  new Metadata());

    StatsTestUtils.MetricsRecord record = statsRecorder.pollRecord();
    assertEquals(1, record.tags.size());
    TagValue methodTag = record.tags.get(RpcMeasureConstants.GRPC_CLIENT_METHOD);
    assertEquals(method.getFullMethodName(), methodTag.asString());
    assertEquals(
          1, record.getMetricAsLongOrFail(DeprecatedCensusConstants.RPC_CLIENT_STARTED_COUNT));

    fakeClock.forwardTime(30, MILLISECONDS);
    tracer.outboundHeaders();
    fakeClock.forwardTime(100, MILLISECONDS);
    tracer.outboundMessage(0);
    assertRealTimeMetric(
        RpcMeasureConstants.GRPC_CLIENT_SENT_MESSAGES_PER_METHOD, 1, true, true);
    tracer.outboundMessage(1);
    assertRealTimeMetric(
        RpcMeasureConstants.GRPC_CLIENT_SENT_MESSAGES_PER_METHOD, 1, true, true);
    tracer.outboundWireSize(1028);
    assertRealTimeMetric(
        RpcMeasureConstants.GRPC_CLIENT_SENT_BYTES_PER_METHOD, 1028, true, true);
    tracer.outboundUncompressedSize(1128);
    fakeClock.forwardTime(24, MILLISECONDS);
    tracer.streamClosed(Status.UNAVAILABLE);
    record = statsRecorder.pollRecord();
    methodTag = record.tags.get(RpcMeasureConstants.GRPC_CLIENT_METHOD);
    assertEquals(method.getFullMethodName(), methodTag.asString());
    TagValue statusTag = record.tags.get(RpcMeasureConstants.GRPC_CLIENT_STATUS);
    assertEquals(Status.Code.UNAVAILABLE.toString(), statusTag.asString());
    assertEquals(
        1, record.getMetricAsLongOrFail(DeprecatedCensusConstants.RPC_CLIENT_FINISHED_COUNT));
    assertEquals(1, record.getMetricAsLongOrFail(DeprecatedCensusConstants.RPC_CLIENT_ERROR_COUNT));
    assertEquals(
        2, record.getMetricAsLongOrFail(DeprecatedCensusConstants.RPC_CLIENT_REQUEST_COUNT));
    assertEquals(
        1028, record.getMetricAsLongOrFail(DeprecatedCensusConstants.RPC_CLIENT_REQUEST_BYTES));
    assertEquals(
        1128,
        record.getMetricAsLongOrFail(
            DeprecatedCensusConstants.RPC_CLIENT_UNCOMPRESSED_REQUEST_BYTES));
    assertEquals(
        30 + 100 + 24,
        record.getMetricAsLongOrFail(DeprecatedCensusConstants.RPC_CLIENT_ROUNDTRIP_LATENCY));

    // faking retry
    fakeClock.forwardTime(1000, MILLISECONDS);
    tracer = callAttemptsTracerFactory.newClientStreamTracer(STREAM_INFO, new Metadata());
    record = statsRecorder.pollRecord();
    assertEquals(1, record.tags.size());
    methodTag = record.tags.get(RpcMeasureConstants.GRPC_CLIENT_METHOD);
    assertEquals(method.getFullMethodName(), methodTag.asString());
    assertEquals(
        1, record.getMetricAsLongOrFail(DeprecatedCensusConstants.RPC_CLIENT_STARTED_COUNT));
    tracer.outboundHeaders();
    tracer.outboundMessage(0);
    assertRealTimeMetric(
        RpcMeasureConstants.GRPC_CLIENT_SENT_MESSAGES_PER_METHOD, 1, true, true);
    tracer.outboundMessage(1);
    assertRealTimeMetric(
        RpcMeasureConstants.GRPC_CLIENT_SENT_MESSAGES_PER_METHOD, 1, true, true);
    tracer.outboundWireSize(1028);
    assertRealTimeMetric(
        RpcMeasureConstants.GRPC_CLIENT_SENT_BYTES_PER_METHOD, 1028, true, true);
    tracer.outboundUncompressedSize(1128);
    fakeClock.forwardTime(100, MILLISECONDS);
    tracer.streamClosed(Status.NOT_FOUND);
    record = statsRecorder.pollRecord();
    methodTag = record.tags.get(RpcMeasureConstants.GRPC_CLIENT_METHOD);
    assertEquals(method.getFullMethodName(), methodTag.asString());
    statusTag = record.tags.get(RpcMeasureConstants.GRPC_CLIENT_STATUS);
    assertEquals(Status.Code.NOT_FOUND.toString(), statusTag.asString());
    assertEquals(
        1, record.getMetricAsLongOrFail(DeprecatedCensusConstants.RPC_CLIENT_FINISHED_COUNT));
    assertEquals(1, record.getMetricAsLongOrFail(DeprecatedCensusConstants.RPC_CLIENT_ERROR_COUNT));
    assertEquals(
        2, record.getMetricAsLongOrFail(DeprecatedCensusConstants.RPC_CLIENT_REQUEST_COUNT));
    assertEquals(
        1028, record.getMetricAsLongOrFail(DeprecatedCensusConstants.RPC_CLIENT_REQUEST_BYTES));
    assertEquals(
        1128,
        record.getMetricAsLongOrFail(
            DeprecatedCensusConstants.RPC_CLIENT_UNCOMPRESSED_REQUEST_BYTES));
    assertEquals(
        100 ,
        record.getMetricAsLongOrFail(DeprecatedCensusConstants.RPC_CLIENT_ROUNDTRIP_LATENCY));

    // fake transparent retry
    fakeClock.forwardTime(10, MILLISECONDS);
    tracer = callAttemptsTracerFactory.newClientStreamTracer(
        STREAM_INFO.toBuilder().setIsTransparentRetry(true).build(), new Metadata());
    record = statsRecorder.pollRecord();
    assertEquals(1, record.tags.size());
    methodTag = record.tags.get(RpcMeasureConstants.GRPC_CLIENT_METHOD);
    assertEquals(method.getFullMethodName(), methodTag.asString());
    assertEquals(
        1, record.getMetricAsLongOrFail(DeprecatedCensusConstants.RPC_CLIENT_STARTED_COUNT));
    tracer.streamClosed(Status.UNAVAILABLE);
    record = statsRecorder.pollRecord();
    statusTag = record.tags.get(RpcMeasureConstants.GRPC_CLIENT_STATUS);
    assertEquals(Status.Code.UNAVAILABLE.toString(), statusTag.asString());
    assertEquals(
        1, record.getMetricAsLongOrFail(DeprecatedCensusConstants.RPC_CLIENT_FINISHED_COUNT));
    assertEquals(1, record.getMetricAsLongOrFail(DeprecatedCensusConstants.RPC_CLIENT_ERROR_COUNT));
    assertEquals(
        0, record.getMetricAsLongOrFail(DeprecatedCensusConstants.RPC_CLIENT_REQUEST_COUNT));
    assertEquals(
        0, record.getMetricAsLongOrFail(DeprecatedCensusConstants.RPC_CLIENT_REQUEST_BYTES));

    // fake another transparent retry
    fakeClock.forwardTime(10, MILLISECONDS);
    tracer = callAttemptsTracerFactory.newClientStreamTracer(
        STREAM_INFO.toBuilder().setIsTransparentRetry(true).build(), new Metadata());
    record = statsRecorder.pollRecord();
    assertEquals(1, record.tags.size());
    assertEquals(
        1, record.getMetricAsLongOrFail(DeprecatedCensusConstants.RPC_CLIENT_STARTED_COUNT));
    tracer.outboundHeaders();
    tracer.outboundMessage(0);
    assertRealTimeMetric(
        RpcMeasureConstants.GRPC_CLIENT_SENT_MESSAGES_PER_METHOD, 1, true, true);
    tracer.outboundMessage(1);
    assertRealTimeMetric(
        RpcMeasureConstants.GRPC_CLIENT_SENT_MESSAGES_PER_METHOD, 1, true, true);
    tracer.outboundWireSize(1028);
    assertRealTimeMetric(
        RpcMeasureConstants.GRPC_CLIENT_SENT_BYTES_PER_METHOD, 1028, true, true);
    tracer.outboundUncompressedSize(1128);
    fakeClock.forwardTime(16, MILLISECONDS);
    tracer.inboundMessage(0);
    assertRealTimeMetric(
        RpcMeasureConstants.GRPC_CLIENT_RECEIVED_MESSAGES_PER_METHOD, 1, true, true);
    tracer.inboundWireSize(33);
    assertRealTimeMetric(
        RpcMeasureConstants.GRPC_CLIENT_RECEIVED_BYTES_PER_METHOD, 33, true, true);
    tracer.inboundUncompressedSize(67);
    fakeClock.forwardTime(24, MILLISECONDS);
    // RPC succeeded
    tracer.streamClosed(Status.OK);
    callAttemptsTracerFactory.callEnded(Status.OK);

    record = statsRecorder.pollRecord();
    statusTag = record.tags.get(RpcMeasureConstants.GRPC_CLIENT_STATUS);
    assertEquals(Status.Code.OK.toString(), statusTag.asString());
    assertEquals(
        1, record.getMetricAsLongOrFail(DeprecatedCensusConstants.RPC_CLIENT_FINISHED_COUNT));
    assertThat(record.getMetric(DeprecatedCensusConstants.RPC_CLIENT_ERROR_COUNT)).isNull();
    assertEquals(
        2, record.getMetricAsLongOrFail(DeprecatedCensusConstants.RPC_CLIENT_REQUEST_COUNT));
    assertEquals(
        1028, record.getMetricAsLongOrFail(DeprecatedCensusConstants.RPC_CLIENT_REQUEST_BYTES));
    assertEquals(
        1128,
        record.getMetricAsLongOrFail(
            DeprecatedCensusConstants.RPC_CLIENT_UNCOMPRESSED_REQUEST_BYTES));
    assertEquals(
        1, record.getMetricAsLongOrFail(DeprecatedCensusConstants.RPC_CLIENT_RESPONSE_COUNT));
    assertEquals(
        33,
        record.getMetricAsLongOrFail(DeprecatedCensusConstants.RPC_CLIENT_RESPONSE_BYTES));
    assertEquals(
        67,
        record.getMetricAsLongOrFail(
            DeprecatedCensusConstants.RPC_CLIENT_UNCOMPRESSED_RESPONSE_BYTES));
    assertEquals(
        16 + 24 ,
        record.getMetricAsLongOrFail(DeprecatedCensusConstants.RPC_CLIENT_ROUNDTRIP_LATENCY));

    record = statsRecorder.pollRecord();
    methodTag = record.tags.get(RpcMeasureConstants.GRPC_CLIENT_METHOD);
    assertEquals(method.getFullMethodName(), methodTag.asString());
    statusTag = record.tags.get(RpcMeasureConstants.GRPC_CLIENT_STATUS);
    assertEquals(Status.Code.OK.toString(), statusTag.asString());
    assertThat(record.getMetric(RETRIES_PER_CALL)).isEqualTo(1);
    assertThat(record.getMetric(TRANSPARENT_RETRIES_PER_CALL)).isEqualTo(2);
    assertThat(record.getMetric(RETRY_DELAY_PER_CALL)).isEqualTo(1000D + 10 + 10);
  }

  private void assertRealTimeMetric(
      Measure measure, long expectedValue, boolean recordRealTimeMetrics, boolean clientSide) {
    StatsTestUtils.MetricsRecord record = statsRecorder.pollRecord();
    if (!recordRealTimeMetrics) {
      assertNull(record);
      return;
    }
    assertNotNull(record);
    if (clientSide) {
      assertNoServerContent(record);

      TagValue methodTag = record.tags.get(RpcMeasureConstants.GRPC_CLIENT_METHOD);
      assertEquals(method.getFullMethodName(), methodTag.asString());
    } else {
      assertNoClientContent(record);

      TagValue methodTag = record.tags.get(RpcMeasureConstants.GRPC_SERVER_METHOD);
      assertEquals(method.getFullMethodName(), methodTag.asString());
    }

    assertEquals(expectedValue, record.getMetricAsLongOrFail(measure));
  }

  private void assertZeroRetryRecorded() {
    StatsTestUtils.MetricsRecord record = statsRecorder.pollRecord();
    TagValue methodTag = record.tags.get(RpcMeasureConstants.GRPC_CLIENT_METHOD);
    assertEquals(method.getFullMethodName(), methodTag.asString());
    assertThat(record.getMetric(RETRIES_PER_CALL)).isEqualTo(0);
    assertThat(record.getMetric(TRANSPARENT_RETRIES_PER_CALL)).isEqualTo(0);
    assertThat(record.getMetric(RETRY_DELAY_PER_CALL)).isEqualTo(0D);
  }

  @Test
  public void clientBasicTracingDefaultSpan() {
    CallAttemptsTracerFactory callTracer =
        censusTracing.newClientCallTracer(null, method);
    Metadata headers = new Metadata();
    ClientStreamTracer clientStreamTracer = callTracer.newClientStreamTracer(STREAM_INFO, headers);
    clientStreamTracer.streamCreated(Attributes.EMPTY, headers);
    verify(tracer).spanBuilderWithExplicitParent(
        eq("Sent.package1.service2.method3"), ArgumentMatchers.<Span>isNull());
    verify(tracer).spanBuilderWithExplicitParent(
        eq("Attempt.package1.service2.method3"), eq(spyClientSpan));
    verify(spyClientSpan, never()).end(any(EndSpanOptions.class));
    verify(spyAttemptSpan, never()).end(any(EndSpanOptions.class));

    clientStreamTracer.outboundMessage(0);
    clientStreamTracer.outboundMessageSent(0, 882, -1);
    clientStreamTracer.inboundMessage(0);
    clientStreamTracer.outboundMessage(1);
    clientStreamTracer.outboundMessageSent(1, -1, 27);
    clientStreamTracer.inboundMessageRead(0, 255, 90);

    clientStreamTracer.streamClosed(Status.OK);
    callTracer.callEnded(Status.OK);

    InOrder inOrder = inOrder(spyClientSpan, spyAttemptSpan);
    inOrder.verify(spyAttemptSpan)
        .putAttribute("previous-rpc-attempts", AttributeValue.longAttributeValue(0));
    inOrder.verify(spyAttemptSpan)
        .putAttribute("transparent-retry", AttributeValue.booleanAttributeValue(false));
    inOrder.verify(spyAttemptSpan, times(3)).addMessageEvent(messageEventCaptor.capture());
    List<MessageEvent> events = messageEventCaptor.getAllValues();
    assertEquals(
        MessageEvent.builder(MessageEvent.Type.SENT, 0).setCompressedMessageSize(882).build(),
        events.get(0));
    assertEquals(
        MessageEvent.builder(MessageEvent.Type.SENT, 1).setUncompressedMessageSize(27).build(),
        events.get(1));
    assertEquals(
        MessageEvent.builder(MessageEvent.Type.RECEIVED, 0)
            .setCompressedMessageSize(255)
            .setUncompressedMessageSize(90)
            .build(),
        events.get(2));
    inOrder.verify(spyAttemptSpan).end(
        EndSpanOptions.builder()
            .setStatus(io.opencensus.trace.Status.OK)
            .setSampleToLocalSpanStore(false)
            .build());
    inOrder.verify(spyClientSpan).end(
        EndSpanOptions.builder()
            .setStatus(io.opencensus.trace.Status.OK)
            .setSampleToLocalSpanStore(false)
            .build());
    inOrder.verifyNoMoreInteractions();
    verifyNoMoreInteractions(tracer);
  }

  @Test
  public void clientTracingSampledToLocalSpanStore() {
    CallAttemptsTracerFactory callTracer =
        censusTracing.newClientCallTracer(null, sampledMethod);
    callTracer.callEnded(Status.OK);

    verify(spyClientSpan).end(
        EndSpanOptions.builder()
            .setStatus(io.opencensus.trace.Status.OK)
            .setSampleToLocalSpanStore(true)
            .build());
  }

  @Test
  public void clientStreamNeverCreatedStillRecordStats() {
    CensusStatsModule.CallAttemptsTracerFactory callAttemptsTracerFactory =
        new CensusStatsModule.CallAttemptsTracerFactory(
            censusStats, tagger.empty(), method.getFullMethodName());
    ClientStreamTracer streamTracer =
        callAttemptsTracerFactory.newClientStreamTracer(STREAM_INFO, new Metadata());
    fakeClock.forwardTime(3000, MILLISECONDS);
    Status status = Status.DEADLINE_EXCEEDED.withDescription("3 seconds");
    streamTracer.streamClosed(status);
    callAttemptsTracerFactory.callEnded(status);

    // Upstart record
    StatsTestUtils.MetricsRecord record = statsRecorder.pollRecord();
    assertNotNull(record);
    assertNoServerContent(record);
    assertEquals(1, record.tags.size());
    TagValue methodTag = record.tags.get(RpcMeasureConstants.GRPC_CLIENT_METHOD);
    assertEquals(method.getFullMethodName(), methodTag.asString());
    assertEquals(
        1,
        record.getMetricAsLongOrFail(DeprecatedCensusConstants.RPC_CLIENT_STARTED_COUNT));

    // Completion record
    record = statsRecorder.pollRecord();
    assertNotNull(record);
    assertNoServerContent(record);
    methodTag = record.tags.get(RpcMeasureConstants.GRPC_CLIENT_METHOD);
    assertEquals(method.getFullMethodName(), methodTag.asString());
    TagValue statusTag = record.tags.get(RpcMeasureConstants.GRPC_CLIENT_STATUS);
    assertEquals(Status.Code.DEADLINE_EXCEEDED.toString(), statusTag.asString());
    assertEquals(
        1,
        record.getMetricAsLongOrFail(DeprecatedCensusConstants.RPC_CLIENT_FINISHED_COUNT));
    assertEquals(
        1,
        record.getMetricAsLongOrFail(DeprecatedCensusConstants.RPC_CLIENT_ERROR_COUNT));
    assertEquals(
        0, record.getMetricAsLongOrFail(DeprecatedCensusConstants.RPC_CLIENT_REQUEST_COUNT));
    assertEquals(
        0, record.getMetricAsLongOrFail(DeprecatedCensusConstants.RPC_CLIENT_REQUEST_BYTES));
    assertEquals(
        0,
        record.getMetricAsLongOrFail(
            DeprecatedCensusConstants.RPC_CLIENT_UNCOMPRESSED_REQUEST_BYTES));
    assertEquals(
        0, record.getMetricAsLongOrFail(DeprecatedCensusConstants.RPC_CLIENT_RESPONSE_COUNT));
    assertEquals(
        0, record.getMetricAsLongOrFail(DeprecatedCensusConstants.RPC_CLIENT_RESPONSE_BYTES));
    assertEquals(0,
        record.getMetricAsLongOrFail(
            DeprecatedCensusConstants.RPC_CLIENT_UNCOMPRESSED_RESPONSE_BYTES));
    assertEquals(
        3000,
        record.getMetricAsLongOrFail(DeprecatedCensusConstants.RPC_CLIENT_ROUNDTRIP_LATENCY));
    assertNull(record.getMetric(DeprecatedCensusConstants.RPC_CLIENT_SERVER_ELAPSED_TIME));
    assertZeroRetryRecorded();
  }

  @Test
  public void clientStreamNeverCreatedStillRecordTracing() {
    CallAttemptsTracerFactory callTracer =
        censusTracing.newClientCallTracer(fakeClientParentSpan, method);
    verify(tracer).spanBuilderWithExplicitParent(
        eq("Sent.package1.service2.method3"), same(fakeClientParentSpan));
    verify(spyClientSpanBuilder).setRecordEvents(eq(true));

    callTracer.callEnded(Status.DEADLINE_EXCEEDED.withDescription("3 seconds"));
    verify(spyClientSpan).end(
        EndSpanOptions.builder()
            .setStatus(
                io.opencensus.trace.Status.DEADLINE_EXCEEDED
                    .withDescription("3 seconds"))
            .setSampleToLocalSpanStore(false)
            .build());
    verifyNoMoreInteractions(spyClientSpan);
  }

  @Test
  public void statsHeadersPropagateTags_record() {
    subtestStatsHeadersPropagateTags(true, true);
  }

  @Test
  public void statsHeadersPropagateTags_notRecord() {
    subtestStatsHeadersPropagateTags(true, false);
  }

  @Test
  public void statsHeadersNotPropagateTags_record() {
    subtestStatsHeadersPropagateTags(false, true);
  }

  @Test
  public void statsHeadersNotPropagateTags_notRecord() {
    subtestStatsHeadersPropagateTags(false, false);
  }

  private void subtestStatsHeadersPropagateTags(boolean propagate, boolean recordStats) {
    // EXTRA_TAG is propagated by the FakeStatsContextFactory. Note that not all tags are
    // propagated.  The StatsContextFactory decides which tags are to propagated.  gRPC facilitates
    // the propagation by putting them in the headers.
    TagContext clientCtx = tagger.emptyBuilder().putLocal(
        StatsTestUtils.EXTRA_TAG, TagValue.create("extra-tag-value-897")).build();
    CensusStatsModule census =
        new CensusStatsModule(
            tagger,
            tagCtxSerializer,
            statsRecorder,
            fakeClock.getStopwatchSupplier(),
            propagate, recordStats, recordStats, recordStats, recordStats);
    Metadata headers = new Metadata();
    CensusStatsModule.CallAttemptsTracerFactory callAttemptsTracerFactory =
        new CensusStatsModule.CallAttemptsTracerFactory(
            census, clientCtx, method.getFullMethodName());
    // This propagates clientCtx to headers if propagates==true
    ClientStreamTracer streamTracer =
        callAttemptsTracerFactory.newClientStreamTracer(STREAM_INFO, headers);
    streamTracer.streamCreated(Attributes.EMPTY, headers);
    if (recordStats) {
      // Client upstart record
      StatsTestUtils.MetricsRecord clientRecord = statsRecorder.pollRecord();
      assertNotNull(clientRecord);
      assertNoServerContent(clientRecord);
      assertEquals(2, clientRecord.tags.size());
      TagValue clientMethodTag = clientRecord.tags.get(RpcMeasureConstants.GRPC_CLIENT_METHOD);
      assertEquals(method.getFullMethodName(), clientMethodTag.asString());
      TagValue clientPropagatedTag = clientRecord.tags.get(StatsTestUtils.EXTRA_TAG);
      assertEquals("extra-tag-value-897", clientPropagatedTag.asString());
    }

    if (propagate) {
      assertTrue(headers.containsKey(census.statsHeader));
    } else {
      assertFalse(headers.containsKey(census.statsHeader));
      return;
    }

    ServerStreamTracer serverTracer =
        census.getServerTracerFactory().newServerStreamTracer(method.getFullMethodName(), headers);
    // Server tracer deserializes clientCtx from the headers, so that it records stats with the
    // propagated tags.
    Context serverContext = serverTracer.filterContext(Context.ROOT);
    // It also put clientCtx in the Context seen by the call handler
    assertEquals(
        tagger.toBuilder(clientCtx)
            .putLocal(
                RpcMeasureConstants.GRPC_SERVER_METHOD,
                TagValue.create(method.getFullMethodName()))
            .build(),
        io.opencensus.tags.unsafe.ContextUtils.getValue(serverContext));

    // Verifies that the server tracer records the status with the propagated tag
    serverTracer.streamClosed(Status.OK);

    if (recordStats) {
      // Server upstart record
      StatsTestUtils.MetricsRecord serverRecord = statsRecorder.pollRecord();
      assertNotNull(serverRecord);
      assertNoClientContent(serverRecord);
      assertEquals(2, serverRecord.tags.size());
      TagValue serverMethodTag = serverRecord.tags.get(RpcMeasureConstants.GRPC_SERVER_METHOD);
      assertEquals(method.getFullMethodName(), serverMethodTag.asString());
      TagValue serverPropagatedTag = serverRecord.tags.get(StatsTestUtils.EXTRA_TAG);
      assertEquals("extra-tag-value-897", serverPropagatedTag.asString());

      // Server completion record
      serverRecord = statsRecorder.pollRecord();
      assertNotNull(serverRecord);
      assertNoClientContent(serverRecord);
      serverMethodTag = serverRecord.tags.get(RpcMeasureConstants.GRPC_SERVER_METHOD);
      assertEquals(method.getFullMethodName(), serverMethodTag.asString());
      TagValue serverStatusTag = serverRecord.tags.get(RpcMeasureConstants.GRPC_SERVER_STATUS);
      assertEquals(Status.Code.OK.toString(), serverStatusTag.asString());
      assertNull(serverRecord.getMetric(DeprecatedCensusConstants.RPC_SERVER_ERROR_COUNT));
      serverPropagatedTag = serverRecord.tags.get(StatsTestUtils.EXTRA_TAG);
      assertEquals("extra-tag-value-897", serverPropagatedTag.asString());
    }

    // Verifies that the client tracer factory uses clientCtx, which includes the custom tags, to
    // record stats.
    streamTracer.streamClosed(Status.OK);
    callAttemptsTracerFactory.callEnded(Status.OK);

    if (recordStats) {
      // Client completion record
      StatsTestUtils.MetricsRecord clientRecord = statsRecorder.pollRecord();
      assertNotNull(clientRecord);
      assertNoServerContent(clientRecord);
      TagValue clientMethodTag = clientRecord.tags.get(RpcMeasureConstants.GRPC_CLIENT_METHOD);
      assertEquals(method.getFullMethodName(), clientMethodTag.asString());
      TagValue clientStatusTag = clientRecord.tags.get(RpcMeasureConstants.GRPC_CLIENT_STATUS);
      assertEquals(Status.Code.OK.toString(), clientStatusTag.asString());
      assertNull(clientRecord.getMetric(DeprecatedCensusConstants.RPC_CLIENT_ERROR_COUNT));
      TagValue clientPropagatedTag = clientRecord.tags.get(StatsTestUtils.EXTRA_TAG);
      assertEquals("extra-tag-value-897", clientPropagatedTag.asString());
      assertZeroRetryRecorded();
    }

    if (!recordStats) {
      assertNull(statsRecorder.pollRecord());
    }
  }

  @Test
  public void statsHeadersNotPropagateDefaultContext() {
    CensusStatsModule.CallAttemptsTracerFactory callAttemptsTracerFactory =
        new CensusStatsModule.CallAttemptsTracerFactory(
            censusStats, tagger.empty(), method.getFullMethodName());
    Metadata headers = new Metadata();
    callAttemptsTracerFactory.newClientStreamTracer(STREAM_INFO, headers)
        .streamCreated(Attributes.EMPTY, headers);
    assertFalse(headers.containsKey(censusStats.statsHeader));
    // Clear recorded stats to satisfy the assertions in wrapUp()
    statsRecorder.rolloverRecords();
  }

  @Test
  public void statsHeaderMalformed() {
    // Construct a malformed header and make sure parsing it will throw
    byte[] statsHeaderValue = new byte[]{1};
    Metadata.Key<byte[]> arbitraryStatsHeader =
        Metadata.Key.of("grpc-tags-bin", Metadata.BINARY_BYTE_MARSHALLER);
    try {
      tagCtxSerializer.fromByteArray(statsHeaderValue);
      fail("Should have thrown");
    } catch (Exception e) {
      // Expected
    }

    // But the header key will return a default context for it
    Metadata headers = new Metadata();
    assertNull(headers.get(censusStats.statsHeader));
    headers.put(arbitraryStatsHeader, statsHeaderValue);
    assertSame(tagger.empty(), headers.get(censusStats.statsHeader));
  }

  @Test
  public void traceHeadersPropagateSpanContext() throws Exception {
    CallAttemptsTracerFactory callTracer =
        censusTracing.newClientCallTracer(fakeClientParentSpan, method);
    Metadata headers = new Metadata();
    ClientStreamTracer streamTracer = callTracer.newClientStreamTracer(STREAM_INFO, headers);
    streamTracer.streamCreated(Attributes.EMPTY, headers);

    verify(mockTracingPropagationHandler).toByteArray(same(fakeAttemptSpanContext));
    verifyNoMoreInteractions(mockTracingPropagationHandler);
    verify(tracer).spanBuilderWithExplicitParent(
        eq("Sent.package1.service2.method3"), same(fakeClientParentSpan));
    verify(tracer).spanBuilderWithExplicitParent(
        eq("Attempt.package1.service2.method3"), same(spyClientSpan));
    verify(spyClientSpanBuilder).setRecordEvents(eq(true));
    verifyNoMoreInteractions(tracer);
    assertTrue(headers.containsKey(censusTracing.tracingHeader));

    ServerStreamTracer serverTracer =
        censusTracing.getServerTracerFactory().newServerStreamTracer(
            method.getFullMethodName(), headers);
    verify(mockTracingPropagationHandler).fromByteArray(same(binarySpanContext));
    verify(tracer).spanBuilderWithRemoteParent(
        eq("Recv.package1.service2.method3"), same(spyAttemptSpan.getContext()));
    verify(spyServerSpanBuilder).setRecordEvents(eq(true));

    Context filteredContext = serverTracer.filterContext(Context.ROOT);
    assertSame(spyServerSpan, ContextUtils.getValue(filteredContext));
  }

  @Test
  public void traceHeaders_propagateSpanContext() throws Exception {
    CallAttemptsTracerFactory callTracer =
        censusTracing.newClientCallTracer(fakeClientParentSpan, method);
    Metadata headers = new Metadata();

    ClientStreamTracer streamTracer = callTracer.newClientStreamTracer(STREAM_INFO, headers);
    streamTracer.streamCreated(Attributes.EMPTY, headers);

    assertThat(headers.keys()).isNotEmpty();
  }

  @Test
  public void traceHeaders_missingCensusImpl_notPropagateSpanContext()
      throws Exception {
    reset(spyClientSpanBuilder);
    reset(spyAttemptSpanBuilder);
    when(spyClientSpanBuilder.startSpan()).thenReturn(BlankSpan.INSTANCE);
    when(spyAttemptSpanBuilder.startSpan()).thenReturn(BlankSpan.INSTANCE);
    Metadata headers = new Metadata();

    CallAttemptsTracerFactory callTracer =
        censusTracing.newClientCallTracer(BlankSpan.INSTANCE, method);
    callTracer.newClientStreamTracer(STREAM_INFO, headers).streamCreated(Attributes.EMPTY, headers);

    assertThat(headers.keys()).isEmpty();
  }

  @Test
  public void traceHeaders_clientMissingCensusImpl_preservingHeaders() throws Exception {
    reset(spyClientSpanBuilder);
    reset(spyAttemptSpanBuilder);
    when(spyClientSpanBuilder.startSpan()).thenReturn(BlankSpan.INSTANCE);
    when(spyAttemptSpanBuilder.startSpan()).thenReturn(BlankSpan.INSTANCE);
    Metadata headers = new Metadata();
    headers.put(
        Metadata.Key.of("never-used-key-bin", Metadata.BINARY_BYTE_MARSHALLER),
        new byte[] {});
    Set<String> originalHeaderKeys = new HashSet<>(headers.keys());

    CallAttemptsTracerFactory callTracer =
        censusTracing.newClientCallTracer(BlankSpan.INSTANCE, method);
    callTracer.newClientStreamTracer(STREAM_INFO, headers).streamCreated(Attributes.EMPTY, headers);

    assertThat(headers.keys()).containsExactlyElementsIn(originalHeaderKeys);
  }

  @Test
  public void traceHeaderMalformed() throws Exception {
    // As comparison, normal header parsing
    Metadata headers = new Metadata();
    headers.put(censusTracing.tracingHeader, fakeAttemptSpanContext);
    // mockTracingPropagationHandler was stubbed to always return fakeServerParentSpanContext
    assertSame(spyAttemptSpan.getContext(), headers.get(censusTracing.tracingHeader));

    // Make BinaryPropagationHandler always throw when parsing the header
    when(mockTracingPropagationHandler.fromByteArray(any(byte[].class)))
        .thenThrow(new SpanContextParseException("Malformed header"));

    headers = new Metadata();
    assertNull(headers.get(censusTracing.tracingHeader));
    headers.put(censusTracing.tracingHeader, fakeAttemptSpanContext);
    assertSame(SpanContext.INVALID, headers.get(censusTracing.tracingHeader));
    assertNotSame(spyClientSpan.getContext(), SpanContext.INVALID);

    // A null Span is used as the parent in this case
    censusTracing.getServerTracerFactory().newServerStreamTracer(
        method.getFullMethodName(), headers);
    verify(tracer).spanBuilderWithRemoteParent(
        eq("Recv.package1.service2.method3"), ArgumentMatchers.<SpanContext>isNull());
    verify(spyServerSpanBuilder).setRecordEvents(eq(true));
  }

  @Test
  public void serverBasicStatsNoHeaders_starts_finishes_noRealTime() {
    subtestServerBasicStatsNoHeaders(true, true, false);
  }

  @Test
  public void serverBasicStatsNoHeaders_starts_noFinishes_noRealTime() {
    subtestServerBasicStatsNoHeaders(true, false, false);
  }

  @Test
  public void serverBasicStatsNoHeaders_noStarts_finishes_noRealTime() {
    subtestServerBasicStatsNoHeaders(false, true, false);
  }

  @Test
  public void serverBasicStatsNoHeaders_noStarts_noFinishes_noRealTime() {
    subtestServerBasicStatsNoHeaders(false, false, false);
  }

  @Test
  public void serverBasicStatsNoHeaders_starts_finishes_realTime() {
    subtestServerBasicStatsNoHeaders(true, true, true);
  }

  private void subtestServerBasicStatsNoHeaders(
      boolean recordStarts, boolean recordFinishes, boolean recordRealTime) {
    CensusStatsModule localCensusStats =
        new CensusStatsModule(
            tagger, tagCtxSerializer, statsRecorder, fakeClock.getStopwatchSupplier(),
            true, recordStarts, recordFinishes, recordRealTime, true);
    ServerStreamTracer.Factory tracerFactory = localCensusStats.getServerTracerFactory();
    ServerStreamTracer tracer =
        tracerFactory.newServerStreamTracer(method.getFullMethodName(), new Metadata());

    if (recordStarts) {
      StatsTestUtils.MetricsRecord record = statsRecorder.pollRecord();
      assertNotNull(record);
      assertNoClientContent(record);
      assertEquals(1, record.tags.size());
      TagValue methodTag = record.tags.get(RpcMeasureConstants.GRPC_SERVER_METHOD);
      assertEquals(method.getFullMethodName(), methodTag.asString());
      assertEquals(
          1,
          record.getMetricAsLongOrFail(DeprecatedCensusConstants.RPC_SERVER_STARTED_COUNT));
    } else {
      assertNull(statsRecorder.pollRecord());
    }

    Context filteredContext = tracer.filterContext(Context.ROOT);
    TagContext statsCtx = io.opencensus.tags.unsafe.ContextUtils.getValue(filteredContext);
    assertEquals(
        tagger
            .emptyBuilder()
            .putLocal(
                RpcMeasureConstants.GRPC_SERVER_METHOD,
                TagValue.create(method.getFullMethodName()))
            .build(),
        statsCtx);

    tracer.inboundMessage(0);
    assertRealTimeMetric(
        RpcMeasureConstants.GRPC_SERVER_RECEIVED_MESSAGES_PER_METHOD, 1, recordRealTime, false);

    tracer.inboundWireSize(34);
    assertRealTimeMetric(
        RpcMeasureConstants.GRPC_SERVER_RECEIVED_BYTES_PER_METHOD, 34, recordRealTime, false);

    tracer.inboundUncompressedSize(67);

    fakeClock.forwardTime(100, MILLISECONDS);
    tracer.outboundMessage(0);
    assertRealTimeMetric(
        RpcMeasureConstants.GRPC_SERVER_SENT_MESSAGES_PER_METHOD, 1, recordRealTime, false);

    tracer.outboundWireSize(1028);
    assertRealTimeMetric(
        RpcMeasureConstants.GRPC_SERVER_SENT_BYTES_PER_METHOD, 1028, recordRealTime, false);

    tracer.outboundUncompressedSize(1128);

    fakeClock.forwardTime(16, MILLISECONDS);
    tracer.inboundMessage(1);
    assertRealTimeMetric(
        RpcMeasureConstants.GRPC_SERVER_RECEIVED_MESSAGES_PER_METHOD, 1, recordRealTime, false);

    tracer.inboundWireSize(154);
    assertRealTimeMetric(
        RpcMeasureConstants.GRPC_SERVER_RECEIVED_BYTES_PER_METHOD, 154, recordRealTime, false);

    tracer.inboundUncompressedSize(552);
    tracer.outboundMessage(1);
    assertRealTimeMetric(
        RpcMeasureConstants.GRPC_SERVER_SENT_MESSAGES_PER_METHOD, 1, recordRealTime, false);

    tracer.outboundWireSize(99);
    assertRealTimeMetric(
        RpcMeasureConstants.GRPC_SERVER_SENT_BYTES_PER_METHOD, 99, recordRealTime, false);

    tracer.outboundUncompressedSize(865);

    fakeClock.forwardTime(24, MILLISECONDS);

    tracer.streamClosed(Status.CANCELLED);

    if (recordFinishes) {
      StatsTestUtils.MetricsRecord record = statsRecorder.pollRecord();
      assertNotNull(record);
      assertNoClientContent(record);
      TagValue methodTag = record.tags.get(RpcMeasureConstants.GRPC_SERVER_METHOD);
      assertEquals(method.getFullMethodName(), methodTag.asString());
      TagValue statusTag = record.tags.get(RpcMeasureConstants.GRPC_SERVER_STATUS);
      assertEquals(Status.Code.CANCELLED.toString(), statusTag.asString());
      assertEquals(
          1, record.getMetricAsLongOrFail(DeprecatedCensusConstants.RPC_SERVER_FINISHED_COUNT));
      assertEquals(
          1, record.getMetricAsLongOrFail(DeprecatedCensusConstants.RPC_SERVER_ERROR_COUNT));
      assertEquals(
          2, record.getMetricAsLongOrFail(DeprecatedCensusConstants.RPC_SERVER_RESPONSE_COUNT));
      assertEquals(
          1028 + 99,
          record.getMetricAsLongOrFail(DeprecatedCensusConstants.RPC_SERVER_RESPONSE_BYTES));
      assertEquals(
          1128 + 865,
          record.getMetricAsLongOrFail(
              DeprecatedCensusConstants.RPC_SERVER_UNCOMPRESSED_RESPONSE_BYTES));
      assertEquals(
          2, record.getMetricAsLongOrFail(DeprecatedCensusConstants.RPC_SERVER_REQUEST_COUNT));
      assertEquals(
          34 + 154,
          record.getMetricAsLongOrFail(DeprecatedCensusConstants.RPC_SERVER_REQUEST_BYTES));
      assertEquals(67 + 552,
          record.getMetricAsLongOrFail(
              DeprecatedCensusConstants.RPC_SERVER_UNCOMPRESSED_REQUEST_BYTES));
      assertEquals(100 + 16 + 24,
          record.getMetricAsLongOrFail(DeprecatedCensusConstants.RPC_SERVER_SERVER_LATENCY));
    } else {
      assertNull(statsRecorder.pollRecord());
    }
  }

  @Test
  public void serverBasicTracingNoHeaders() {
    ServerStreamTracer.Factory tracerFactory = censusTracing.getServerTracerFactory();
    ServerStreamTracer serverStreamTracer =
        tracerFactory.newServerStreamTracer(method.getFullMethodName(), new Metadata());
    verifyNoInteractions(mockTracingPropagationHandler);
    verify(tracer).spanBuilderWithRemoteParent(
        eq("Recv.package1.service2.method3"), ArgumentMatchers.<SpanContext>isNull());
    verify(spyServerSpanBuilder).setRecordEvents(eq(true));

    Context filteredContext = serverStreamTracer.filterContext(Context.ROOT);
    assertSame(spyServerSpan, ContextUtils.getValue(filteredContext));

    serverStreamTracer.serverCallStarted(
        new CallInfo<>(method, Attributes.EMPTY, null));

    verify(spyServerSpan, never()).end(any(EndSpanOptions.class));

    serverStreamTracer.outboundMessage(0);
    serverStreamTracer.outboundMessageSent(0, 882, -1);
    serverStreamTracer.inboundMessage(0);
    serverStreamTracer.outboundMessage(1);
    serverStreamTracer.outboundMessageSent(1, -1, 27);
    serverStreamTracer.inboundMessageRead(0, 255, 90);

    serverStreamTracer.streamClosed(Status.CANCELLED);

    InOrder inOrder = inOrder(spyServerSpan);
    inOrder.verify(spyServerSpan, times(3)).addMessageEvent(messageEventCaptor.capture());
    List<MessageEvent> events = messageEventCaptor.getAllValues();
    assertEquals(
        MessageEvent.builder(MessageEvent.Type.SENT, 0).setCompressedMessageSize(882).build(),
        events.get(0));
    assertEquals(
        MessageEvent.builder(MessageEvent.Type.SENT, 1).setUncompressedMessageSize(27).build(),
        events.get(1));
    assertEquals(
        MessageEvent.builder(MessageEvent.Type.RECEIVED, 0)
            .setCompressedMessageSize(255)
            .setUncompressedMessageSize(90)
            .build(),
        events.get(2));
    inOrder.verify(spyServerSpan).end(
        EndSpanOptions.builder()
            .setStatus(io.opencensus.trace.Status.CANCELLED)
            .setSampleToLocalSpanStore(false)
            .build());
    verifyNoMoreInteractions(spyServerSpan);
  }

  @Test
  public void serverTracingSampledToLocalSpanStore() {
    ServerStreamTracer.Factory tracerFactory = censusTracing.getServerTracerFactory();
    ServerStreamTracer serverStreamTracer =
        tracerFactory.newServerStreamTracer(sampledMethod.getFullMethodName(), new Metadata());

    serverStreamTracer.filterContext(Context.ROOT);

    serverStreamTracer.serverCallStarted(
        new CallInfo<>(sampledMethod, Attributes.EMPTY, null));

    serverStreamTracer.streamClosed(Status.CANCELLED);

    verify(spyServerSpan).end(
        EndSpanOptions.builder()
            .setStatus(io.opencensus.trace.Status.CANCELLED)
            .setSampleToLocalSpanStore(true)
            .build());
  }

  @Test
  public void serverTracingNotSampledToLocalSpanStore_whenServerCallNotCreated() {
    ServerStreamTracer.Factory tracerFactory = censusTracing.getServerTracerFactory();
    ServerStreamTracer serverStreamTracer =
        tracerFactory.newServerStreamTracer(sampledMethod.getFullMethodName(), new Metadata());

    serverStreamTracer.streamClosed(Status.CANCELLED);

    verify(spyServerSpan).end(
        EndSpanOptions.builder()
            .setStatus(io.opencensus.trace.Status.CANCELLED)
            .setSampleToLocalSpanStore(false)
            .build());
  }

  @Test
  public void convertToTracingStatus() {
    // Without description
    for (Status.Code grpcCode : Status.Code.values()) {
      Status grpcStatus = Status.fromCode(grpcCode);
      io.opencensus.trace.Status tracingStatus =
          CensusTracingModule.convertStatus(grpcStatus);
      assertEquals(grpcCode.toString(), tracingStatus.getCanonicalCode().toString());
      assertNull(tracingStatus.getDescription());
    }

    // With description
    for (Status.Code grpcCode : Status.Code.values()) {
      Status grpcStatus = Status.fromCode(grpcCode).withDescription("This is my description");
      io.opencensus.trace.Status tracingStatus =
          CensusTracingModule.convertStatus(grpcStatus);
      assertEquals(grpcCode.toString(), tracingStatus.getCanonicalCode().toString());
      assertEquals(grpcStatus.getDescription(), tracingStatus.getDescription());
    }
  }


  @Test
  public void generateTraceSpanName() {
    assertEquals(
        "Sent.io.grpc.Foo", CensusTracingModule.generateTraceSpanName(false, "io.grpc/Foo"));
    assertEquals(
        "Recv.io.grpc.Bar", CensusTracingModule.generateTraceSpanName(true, "io.grpc/Bar"));
  }

  private static void assertNoServerContent(StatsTestUtils.MetricsRecord record) {
    assertNull(record.getMetric(DeprecatedCensusConstants.RPC_SERVER_ERROR_COUNT));
    assertNull(record.getMetric(DeprecatedCensusConstants.RPC_SERVER_REQUEST_COUNT));
    assertNull(record.getMetric(DeprecatedCensusConstants.RPC_SERVER_RESPONSE_COUNT));
    assertNull(record.getMetric(DeprecatedCensusConstants.RPC_SERVER_REQUEST_BYTES));
    assertNull(record.getMetric(DeprecatedCensusConstants.RPC_SERVER_RESPONSE_BYTES));
    assertNull(record.getMetric(DeprecatedCensusConstants.RPC_SERVER_SERVER_ELAPSED_TIME));
    assertNull(record.getMetric(DeprecatedCensusConstants.RPC_SERVER_SERVER_LATENCY));
    assertNull(record.getMetric(DeprecatedCensusConstants.RPC_SERVER_UNCOMPRESSED_REQUEST_BYTES));
    assertNull(record.getMetric(DeprecatedCensusConstants.RPC_SERVER_UNCOMPRESSED_RESPONSE_BYTES));
  }

  private static void assertNoClientContent(StatsTestUtils.MetricsRecord record) {
    assertNull(record.getMetric(DeprecatedCensusConstants.RPC_CLIENT_ERROR_COUNT));
    assertNull(record.getMetric(DeprecatedCensusConstants.RPC_CLIENT_REQUEST_COUNT));
    assertNull(record.getMetric(DeprecatedCensusConstants.RPC_CLIENT_RESPONSE_COUNT));
    assertNull(record.getMetric(DeprecatedCensusConstants.RPC_CLIENT_REQUEST_BYTES));
    assertNull(record.getMetric(DeprecatedCensusConstants.RPC_CLIENT_RESPONSE_BYTES));
    assertNull(record.getMetric(DeprecatedCensusConstants.RPC_CLIENT_ROUNDTRIP_LATENCY));
    assertNull(record.getMetric(DeprecatedCensusConstants.RPC_CLIENT_SERVER_ELAPSED_TIME));
    assertNull(record.getMetric(DeprecatedCensusConstants.RPC_CLIENT_UNCOMPRESSED_REQUEST_BYTES));
    assertNull(record.getMetric(DeprecatedCensusConstants.RPC_CLIENT_UNCOMPRESSED_RESPONSE_BYTES));
  }

  @Deprecated
  @Test
  public void newTagsPopulateOldViews() throws InterruptedException {
    StatsComponent localStats = new StatsComponentImpl();

    // Test views that contain both of the remap tags: method & status.
    localStats.getViewManager().registerView(RpcViewConstants.RPC_CLIENT_ERROR_COUNT_VIEW);
    localStats.getViewManager().registerView(RpcViewConstants.GRPC_CLIENT_COMPLETED_RPC_VIEW);

    CensusStatsModule localCensusStats = new CensusStatsModule(
        tagger, tagCtxSerializer, localStats.getStatsRecorder(), fakeClock.getStopwatchSupplier(),
        false, false, true, false /* real-time */, true);

    CensusStatsModule.CallAttemptsTracerFactory callAttemptsTracerFactory =
        new CensusStatsModule.CallAttemptsTracerFactory(
            localCensusStats, tagger.empty(), method.getFullMethodName());

    Metadata headers = new Metadata();
    ClientStreamTracer tracer =
        callAttemptsTracerFactory.newClientStreamTracer(STREAM_INFO, headers);
    tracer.streamCreated(Attributes.EMPTY, headers);
    fakeClock.forwardTime(30, MILLISECONDS);
    Status status = Status.PERMISSION_DENIED.withDescription("No you don't");
    tracer.streamClosed(status);
    callAttemptsTracerFactory.callEnded(status);

    // Give OpenCensus a chance to update the views asynchronously.
    Thread.sleep(100);

    assertWithMessage("Legacy error count view had unexpected count")
        .that(
          getAggregationValueAsLong(
              localStats,
              RpcViewConstants.RPC_CLIENT_ERROR_COUNT_VIEW,
              ImmutableList.of(
                  TagValue.create("PERMISSION_DENIED"),
                  TagValue.create(method.getFullMethodName()))))
        .isEqualTo(1);

    assertWithMessage("New error count view had unexpected count")
        .that(
          getAggregationValueAsLong(
              localStats,
              RpcViewConstants.GRPC_CLIENT_COMPLETED_RPC_VIEW,
              ImmutableList.of(
                  TagValue.create(method.getFullMethodName()),
                  TagValue.create("PERMISSION_DENIED"))))
        .isEqualTo(1);
  }

  @Deprecated
  private long getAggregationValueAsLong(StatsComponent localStats, View view,
      List<TagValue> dimension) {
    AggregationData aggregationData = localStats.getViewManager()
        .getView(view.getName())
        .getAggregationMap()
        .get(dimension);

    return aggregationData.match(
        new Function<SumDataDouble, Long>() {
          @Override
          public Long apply(SumDataDouble arg) {
            return (long) arg.getSum();
          }
        },
        Functions.<Long>throwAssertionError(),
        new Function<CountData, Long>() {
          @Override
          public Long apply(CountData arg) {
            return arg.getCount();
          }
        },
        Functions.<Long>throwAssertionError(),
        new Function<LastValueDataDouble, Long>() {
          @Override
          public Long apply(LastValueDataDouble arg) {
            return (long) arg.getLastValue();
          }
        },
        new Function<LastValueDataLong, Long>() {
          @Override
          public Long apply(LastValueDataLong arg) {
            return arg.getLastValue();
          }
        },
        new Function<AggregationData, Long>() {
          @Override
          public Long apply(AggregationData arg) {
            return ((AggregationData.MeanData) arg).getCount();
          }
        });
  }

  private static class CallInfo<ReqT, RespT> extends ServerCallInfo<ReqT, RespT> {
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
