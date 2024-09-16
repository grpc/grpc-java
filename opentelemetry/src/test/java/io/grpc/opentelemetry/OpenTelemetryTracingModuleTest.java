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

package io.grpc.opentelemetry;

import static io.grpc.ClientStreamTracer.NAME_RESOLUTION_DELAYED;
import static io.grpc.opentelemetry.OpenTelemetryTracingModule.OTEL_TRACING_SCOPE_NAME;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableSet;
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
import io.grpc.Status;
import io.grpc.opentelemetry.OpenTelemetryTracingModule.CallAttemptsTracerFactory;
import io.grpc.testing.GrpcServerRule;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanId;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.TraceId;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.sdk.testing.junit4.OpenTelemetryRule;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@RunWith(JUnit4.class)
public class OpenTelemetryTracingModuleTest {
  @Rule
  public final MockitoRule mocks = MockitoJUnit.rule();

  private static final ClientStreamTracer.StreamInfo STREAM_INFO =
      ClientStreamTracer.StreamInfo.newBuilder()
          .setCallOptions(CallOptions.DEFAULT.withOption(NAME_RESOLUTION_DELAYED, 10L)).build();
  private static final CallOptions.Key<String> CUSTOM_OPTION =
      CallOptions.Key.createWithDefault("option1", "default");
  private static final CallOptions CALL_OPTIONS =
      CallOptions.DEFAULT.withOption(CUSTOM_OPTION, "customvalue");

  private static class StringInputStream extends InputStream implements KnownLength {
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

  private final MethodDescriptor<String, String> method =
      MethodDescriptor.<String, String>newBuilder()
          .setType(MethodDescriptor.MethodType.UNKNOWN)
          .setRequestMarshaller(MARSHALLER)
          .setResponseMarshaller(MARSHALLER)
          .setFullMethodName("package1.service2/method3")
          .build();

  @Rule
  public final OpenTelemetryRule openTelemetryRule = OpenTelemetryRule.create();
  @Rule
  public final GrpcServerRule grpcServerRule = new GrpcServerRule().directExecutor();
  private Tracer tracerRule;
  @Mock
  private Tracer mockTracer;
  @Mock
  TextMapPropagator mockPropagator;
  @Mock
  private Span mockClientSpan;
  @Mock
  private Span mockAttemptSpan;
  @Mock
  private ServerCall.Listener<String> mockServerCallListener;
  @Mock
  private ClientCall.Listener<String> mockClientCallListener;
  @Mock
  private SpanBuilder mockSpanBuilder;
  @Mock
  private OpenTelemetry mockOpenTelemetry;
  @Captor
  private ArgumentCaptor<String> eventNameCaptor;
  @Captor
  private ArgumentCaptor<io.opentelemetry.api.common.Attributes> attributesCaptor;
  @Captor
  private ArgumentCaptor<Status> statusCaptor;

  @Before
  public void setUp() {
    tracerRule = openTelemetryRule.getOpenTelemetry().getTracer(OTEL_TRACING_SCOPE_NAME);
    when(mockOpenTelemetry.getTracer(OTEL_TRACING_SCOPE_NAME)).thenReturn(mockTracer);
    when(mockOpenTelemetry.getPropagators()).thenReturn(ContextPropagators.create(mockPropagator));
    when(mockSpanBuilder.startSpan()).thenReturn(mockAttemptSpan);
    when(mockSpanBuilder.setParent(any())).thenReturn(mockSpanBuilder);
    when(mockTracer.spanBuilder(any())).thenReturn(mockSpanBuilder);
  }

  // Use mock instead of OpenTelemetryRule to verify inOrder and propagator.
  @Test
  public void clientBasicTracingMocking() {
    OpenTelemetryTracingModule tracingModule = new OpenTelemetryTracingModule(mockOpenTelemetry);
    CallAttemptsTracerFactory callTracer =
        tracingModule.newClientCallTracer(mockClientSpan, method);
    Metadata headers = new Metadata();
    ClientStreamTracer clientStreamTracer = callTracer.newClientStreamTracer(STREAM_INFO, headers);
    clientStreamTracer.createPendingStream();
    clientStreamTracer.streamCreated(Attributes.EMPTY, headers);

    verify(mockTracer).spanBuilder(eq("Attempt.package1.service2.method3"));
    verify(mockPropagator).inject(any(), eq(headers), eq(MetadataSetter.getInstance()));
    verify(mockClientSpan, never()).end();
    verify(mockAttemptSpan, never()).end();

    clientStreamTracer.outboundMessage(0);
    clientStreamTracer.outboundMessageSent(0, 882, -1);
    clientStreamTracer.inboundMessage(0);
    clientStreamTracer.outboundMessage(1);
    clientStreamTracer.outboundMessageSent(1, -1, 27);
    clientStreamTracer.inboundMessageRead(0, 255, 90);

    clientStreamTracer.streamClosed(Status.OK);
    callTracer.callEnded(Status.OK);

    InOrder inOrder = inOrder(mockClientSpan, mockAttemptSpan);
    inOrder.verify(mockAttemptSpan)
        .setAttribute("previous-rpc-attempts", 0);
    inOrder.verify(mockAttemptSpan)
        .setAttribute("transparent-retry", false);
    inOrder.verify(mockClientSpan).addEvent("Delayed name resolution complete");
    inOrder.verify(mockAttemptSpan).addEvent("Delayed LB pick complete");
    inOrder.verify(mockAttemptSpan, times(3)).addEvent(
        eventNameCaptor.capture(), attributesCaptor.capture()
    );
    List<String> events = eventNameCaptor.getAllValues();
    List<io.opentelemetry.api.common.Attributes> attributes = attributesCaptor.getAllValues();
    assertEquals(
        "Outbound message sent" ,
        events.get(0));
    assertEquals(
        io.opentelemetry.api.common.Attributes.builder()
            .put("sequence-number", 0)
            .put("message-size-compressed", 882)
            .build(),
        attributes.get(0));

    assertEquals(
        "Outbound message sent" ,
        events.get(1));
    assertEquals(
        io.opentelemetry.api.common.Attributes.builder()
            .put("sequence-number", 1)
            .put("message-size", 27)
            .build(),
        attributes.get(1));

    assertEquals(
        "Inbound compressed message" ,
        events.get(2));
    assertEquals(
        io.opentelemetry.api.common.Attributes.builder()
            .put("sequence-number", 0)
            .put("message-size-compressed", 255)
            .build(),
        attributes.get(2));

    inOrder.verify(mockAttemptSpan).setStatus(StatusCode.OK);
    inOrder.verify(mockAttemptSpan).end();
    inOrder.verify(mockClientSpan).setStatus(StatusCode.OK);
    inOrder.verify(mockClientSpan).end();
    inOrder.verifyNoMoreInteractions();
  }

  @Test
  public void clientBasicTracingRule() {
    OpenTelemetryTracingModule tracingModule = new OpenTelemetryTracingModule(
        openTelemetryRule.getOpenTelemetry());
    Span clientSpan = tracerRule.spanBuilder("test-client-span").startSpan();
    CallAttemptsTracerFactory callTracer =
        tracingModule.newClientCallTracer(clientSpan, method);
    Metadata headers = new Metadata();
    ClientStreamTracer clientStreamTracer = callTracer.newClientStreamTracer(STREAM_INFO, headers);
    clientStreamTracer.createPendingStream();
    clientStreamTracer.streamCreated(Attributes.EMPTY, headers);
    clientStreamTracer.outboundMessage(0);
    clientStreamTracer.outboundMessageSent(0, 882, -1);
    clientStreamTracer.inboundMessage(0);
    clientStreamTracer.outboundMessage(1);
    clientStreamTracer.outboundMessageSent(1, -1, 27);
    clientStreamTracer.inboundMessageRead(0, 255, -1);
    clientStreamTracer.inboundUncompressedSize(288);
    clientStreamTracer.inboundMessageRead(1, 128, 128);
    clientStreamTracer.inboundMessage(1);
    clientStreamTracer.inboundUncompressedSize(128);

    clientStreamTracer.streamClosed(Status.OK);
    callTracer.callEnded(Status.OK);

    List<SpanData> spans = openTelemetryRule.getSpans();
    assertEquals(spans.size(), 2);
    SpanData attemptSpanData = spans.get(0);
    SpanData clientSpanData = spans.get(1);
    assertEquals(attemptSpanData.getName(), "Attempt.package1.service2.method3");
    assertEquals(clientSpanData.getName(), "test-client-span");
    assertEquals(headers.keys(), ImmutableSet.of("traceparent"));
    String spanContext = headers.get(
        Metadata.Key.of("traceparent", Metadata.ASCII_STRING_MARSHALLER));
    assertEquals(spanContext.substring(3, 3 + TraceId.getLength()),
        spans.get(1).getSpanContext().getTraceId());

    // parent(client) span data
    List<EventData> clientSpanEvents = clientSpanData.getEvents();
    assertEquals(clientSpanEvents.size(), 3);
    assertEquals(
        "Delayed name resolution complete",
        clientSpanEvents.get(0).getName());
    assertTrue(clientSpanEvents.get(0).getAttributes().isEmpty());

    assertEquals(
        "Inbound message received" ,
        clientSpanEvents.get(1).getName());
    assertEquals(
        io.opentelemetry.api.common.Attributes.builder()
            .put("sequence-number", 0)
            .put("message-size", 288)
            .build(),
        clientSpanEvents.get(1).getAttributes());

    assertEquals(
        "Inbound message received" ,
        clientSpanEvents.get(2).getName());
    assertEquals(
        io.opentelemetry.api.common.Attributes.builder()
            .put("sequence-number", 1)
            .put("message-size", 128)
            .build(),
        clientSpanEvents.get(2).getAttributes());
    assertEquals(clientSpanData.hasEnded(), true);

    // child(attempt) span data
    List<EventData> attemptSpanEvents = attemptSpanData.getEvents();
    assertEquals(clientSpanEvents.size(), 3);
    assertEquals(
        "Delayed LB pick complete",
        attemptSpanEvents.get(0).getName());
    assertTrue(clientSpanEvents.get(0).getAttributes().isEmpty());

    assertEquals(
        "Outbound message sent" ,
        attemptSpanEvents.get(1).getName());
    assertEquals(
        io.opentelemetry.api.common.Attributes.builder()
            .put("sequence-number", 0)
            .put("message-size-compressed", 882)
            .build(),
        attemptSpanEvents.get(1).getAttributes());

    assertEquals(
        "Outbound message sent" ,
        attemptSpanEvents.get(2).getName());
    assertEquals(
        io.opentelemetry.api.common.Attributes.builder()
            .put("sequence-number", 1)
            .put("message-size", 27)
            .build(),
        attemptSpanEvents.get(2).getAttributes());

    assertEquals(
        "Inbound compressed message" ,
        attemptSpanEvents.get(3).getName());
    assertEquals(
        io.opentelemetry.api.common.Attributes.builder()
            .put("sequence-number", 0)
            .put("message-size-compressed", 255)
            .build(),
        attemptSpanEvents.get(3).getAttributes());

    assertEquals(attemptSpanData.hasEnded(), true);
  }

  @Test
  public void clientInterceptor() {
    testClientInterceptors(false);
  }

  @Test
  public void clientInterceptorNonDefaultOtelContext() {
    testClientInterceptors(true);
  }

  private void testClientInterceptors(boolean nonDefaultOtelContext) {
    final AtomicReference<Metadata> capturedMetadata = new AtomicReference<>();
    grpcServerRule.getServiceRegistry().addService(
        ServerServiceDefinition.builder("package1.service2").addMethod(
            method, new ServerCallHandler<String, String>() {
              @Override
              public ServerCall.Listener<String> startCall(
                  ServerCall<String, String> call, Metadata headers) {
                capturedMetadata.set(headers);
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
    OpenTelemetryTracingModule tracingModule = new OpenTelemetryTracingModule(
        openTelemetryRule.getOpenTelemetry());
    Channel interceptedChannel =
        ClientInterceptors.intercept(
            grpcServerRule.getChannel(), callOptionsCaptureInterceptor,
            tracingModule.getClientInterceptor());
    Span parentSpan = tracerRule.spanBuilder("test-parent-span").startSpan();
    ClientCall<String, String> call;

    if (nonDefaultOtelContext) {
      try (Scope scope = io.opentelemetry.context.Context.current().with(parentSpan)
          .makeCurrent()) {
        call = interceptedChannel.newCall(method, CALL_OPTIONS);
      }
    } else {
      call = interceptedChannel.newCall(method, CALL_OPTIONS);
    }
    assertEquals("customvalue", capturedCallOptions.get().getOption(CUSTOM_OPTION));
    assertEquals(1, capturedCallOptions.get().getStreamTracerFactories().size());
    assertTrue(
        capturedCallOptions.get().getStreamTracerFactories().get(0)
            instanceof CallAttemptsTracerFactory);

    // Make the call
    Metadata headers = new Metadata();
    call.start(mockClientCallListener, headers);

    // End the call
    call.halfClose();
    call.request(1);
    parentSpan.end();

    verify(mockClientCallListener).onClose(statusCaptor.capture(), any(Metadata.class));
    Status status = statusCaptor.getValue();
    assertEquals(Status.Code.PERMISSION_DENIED, status.getCode());
    assertEquals("No you don't", status.getDescription());

    List<SpanData> spans = openTelemetryRule.getSpans();
    assertEquals(spans.size(), 3);

    SpanData clientSpan = spans.get(1);
    SpanData attemptSpan = spans.get(0);
    if (nonDefaultOtelContext) {
      assertEquals(clientSpan.getParentSpanContext(), parentSpan.getSpanContext());
    } else {
      assertEquals(clientSpan.getParentSpanContext(),
          Span.fromContext(Context.root()).getSpanContext());
    }
    String spanContext = capturedMetadata.get().get(
        Metadata.Key.of("traceparent", Metadata.ASCII_STRING_MARSHALLER));
    // W3C format: 00-<trace id>-<span id>-<trace flag>
    assertEquals(spanContext.substring(3, 3 + TraceId.getLength()),
        attemptSpan.getSpanContext().getTraceId());
    assertEquals(spanContext.substring(3 + TraceId.getLength() + 1,
        3 + TraceId.getLength() + 1 + SpanId.getLength()),
        attemptSpan.getSpanContext().getSpanId());

    assertEquals(attemptSpan.getParentSpanContext(), clientSpan.getSpanContext());
    assertTrue(clientSpan.hasEnded());
    assertEquals(clientSpan.getStatus().getStatusCode(), StatusCode.ERROR);
    assertEquals(clientSpan.getStatus().getDescription(), "PERMISSION_DENIED: No you don't");
    assertTrue(attemptSpan.hasEnded());
    assertTrue(attemptSpan.hasEnded());
    assertEquals(attemptSpan.getStatus().getStatusCode(), StatusCode.ERROR);
    assertEquals(attemptSpan.getStatus().getDescription(), "PERMISSION_DENIED: No you don't");
  }

  @Test
  public void clientStreamNeverCreatedStillRecordTracing() {
    OpenTelemetryTracingModule tracingModule = new OpenTelemetryTracingModule(mockOpenTelemetry);
    CallAttemptsTracerFactory callTracer =
        tracingModule.newClientCallTracer(mockClientSpan, method);

    callTracer.callEnded(Status.DEADLINE_EXCEEDED.withDescription("3 seconds"));
    verify(mockClientSpan).end();
    verify(mockClientSpan).setStatus(eq(StatusCode.ERROR),
        eq("DEADLINE_EXCEEDED: 3 seconds"));
    verifyNoMoreInteractions(mockClientSpan);
  }

  @Test
  public void serverBasicTracingNoHeaders() {
    OpenTelemetryTracingModule tracingModule = new OpenTelemetryTracingModule(
        openTelemetryRule.getOpenTelemetry());
    ServerStreamTracer.Factory tracerFactory = tracingModule.getServerTracerFactory();
    ServerStreamTracer serverStreamTracer =
        tracerFactory.newServerStreamTracer(method.getFullMethodName(), new Metadata());
    assertSame(Span.fromContext(Context.current()), Span.getInvalid());

    serverStreamTracer.outboundMessage(0);
    serverStreamTracer.outboundMessageSent(0, 882, 998);
    serverStreamTracer.inboundMessage(0);
    serverStreamTracer.outboundMessage(1);
    serverStreamTracer.outboundMessageSent(1, -1, 27);
    serverStreamTracer.inboundMessageRead(0, 90, -1);
    serverStreamTracer.inboundUncompressedSize(255);

    serverStreamTracer.streamClosed(Status.CANCELLED);

    List<SpanData> spans = openTelemetryRule.getSpans();
    assertEquals(spans.size(), 1);
    assertEquals(spans.get(0).getName(), "Recv.package1.service2.method3");
    assertEquals(spans.get(0).getParentSpanContext(), Span.getInvalid().getSpanContext());

    List<EventData> events = spans.get(0).getEvents();
    assertEquals(events.size(), 4);
    assertEquals(
        "Outbound message sent" ,
        events.get(0).getName());
    assertEquals(
        io.opentelemetry.api.common.Attributes.builder()
            .put("sequence-number", 0)
            .put("message-size-compressed", 882)
            .put("message-size", 998)
            .build(),
        events.get(0).getAttributes());

    assertEquals(
        "Outbound message sent" ,
        events.get(1).getName());
    assertEquals(
        io.opentelemetry.api.common.Attributes.builder()
            .put("sequence-number", 1)
            .put("message-size", 27)
            .build(),
        events.get(1).getAttributes());

    assertEquals(
        "Inbound compressed message" ,
        events.get(2).getName());
    assertEquals(
        io.opentelemetry.api.common.Attributes.builder()
            .put("sequence-number", 0)
            .put("message-size-compressed", 90)
            .build(),
        events.get(2).getAttributes());

    assertEquals(
        "Inbound message received" ,
        events.get(3).getName());
    assertEquals(
        io.opentelemetry.api.common.Attributes.builder()
            .put("sequence-number", 0)
            .put("message-size", 255)
            .build(),
        events.get(3).getAttributes());

    assertEquals(spans.get(0).hasEnded(), true);
  }

  @Test
  public void grpcTraceBinPropagator() {
    when(mockOpenTelemetry.getPropagators()).thenReturn(
        ContextPropagators.create(GrpcTraceBinContextPropagator.defaultInstance()));
    ArgumentCaptor<Context> contextArgumentCaptor = ArgumentCaptor.forClass(Context.class);
    OpenTelemetryTracingModule tracingModule = new OpenTelemetryTracingModule(mockOpenTelemetry);
    Span testClientSpan = tracerRule.spanBuilder("test-client-span").startSpan();
    CallAttemptsTracerFactory callTracer =
        tracingModule.newClientCallTracer(testClientSpan, method);
    Span testAttemptSpan = tracerRule.spanBuilder("test-attempt-span").startSpan();
    when(mockSpanBuilder.startSpan()).thenReturn(testAttemptSpan);

    Metadata headers = new Metadata();
    ClientStreamTracer clientStreamTracer = callTracer.newClientStreamTracer(STREAM_INFO, headers);
    clientStreamTracer.streamCreated(Attributes.EMPTY, headers);
    clientStreamTracer.streamClosed(Status.CANCELLED);

    Metadata.Key<byte[]> key = Metadata.Key.of(
        GrpcTraceBinContextPropagator.GRPC_TRACE_BIN_HEADER, Metadata.BINARY_BYTE_MARSHALLER);
    assertTrue(Arrays.equals(BinaryFormat.getInstance().toBytes(testAttemptSpan.getSpanContext()),
        headers.get(key)
    ));
    verify(mockSpanBuilder).setParent(contextArgumentCaptor.capture());
    assertEquals(testClientSpan, Span.fromContext(contextArgumentCaptor.getValue()));

    Span serverSpan = tracerRule.spanBuilder("test-server-span").startSpan();
    when(mockSpanBuilder.startSpan()).thenReturn(serverSpan);
    ServerStreamTracer.Factory tracerFactory = tracingModule.getServerTracerFactory();
    ServerStreamTracer serverStreamTracer =
        tracerFactory.newServerStreamTracer(method.getFullMethodName(), headers);
    serverStreamTracer.streamClosed(Status.CANCELLED);

    verify(mockSpanBuilder, times(2))
        .setParent(contextArgumentCaptor.capture());
    assertEquals(testAttemptSpan.getSpanContext(),
        Span.fromContext(contextArgumentCaptor.getValue()).getSpanContext());
  }

  @Test
  public void generateTraceSpanName() {
    assertEquals(
        "Sent.io.grpc.Foo", OpenTelemetryTracingModule.generateTraceSpanName(
            false, "io.grpc/Foo"));
    assertEquals(
        "Recv.io.grpc.Bar", OpenTelemetryTracingModule.generateTraceSpanName(
            true, "io.grpc/Bar"));
  }
}
