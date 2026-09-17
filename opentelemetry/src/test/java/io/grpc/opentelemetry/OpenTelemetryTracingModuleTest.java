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
import static io.grpc.opentelemetry.internal.OpenTelemetryConstants.BAGGAGE_KEY;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
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
import io.grpc.ConnectivityState;
import io.grpc.EquivalentAddressGroup;
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
import io.grpc.NoopServerCall;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.ServerServiceDefinition;
import io.grpc.ServerStreamTracer;
import io.grpc.Status;
import io.grpc.StatusOr;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.inprocess.InProcessSocketAddress;
import io.grpc.opentelemetry.OpenTelemetryTracingModule.CallAttemptsTracerFactory;
import io.grpc.opentelemetry.internal.OpenTelemetryConstants;
import io.grpc.testing.GrpcCleanupRule;
import io.grpc.testing.GrpcServerRule;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanId;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceId;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.TracerBuilder;
import io.opentelemetry.api.trace.TracerProvider;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.junit4.OpenTelemetryRule;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
      // InProcessTransport doesn't actually read bytes from the InputStream. The InputStream is
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
  @Rule
  public final GrpcCleanupRule grpcCleanupRule = new GrpcCleanupRule();
  private Tracer tracerRule;
  @Mock
  private Tracer mockTracer;
  @Mock
  private Span mockClientSpan;
  @Mock
  private ServerCall.Listener<String> mockServerCallListener;
  @Mock
  private ClientCall.Listener<String> mockClientCallListener;
  @Mock
  private SpanBuilder mockSpanBuilder;
  @Mock
  private OpenTelemetry mockOpenTelemetry;
  @Captor
  private ArgumentCaptor<Status> statusCaptor;

  @Before
  public void setUp() {
    tracerRule = openTelemetryRule.getOpenTelemetry().getTracer(
        OpenTelemetryConstants.INSTRUMENTATION_SCOPE);
    TracerProvider mockTracerProvider = mock(TracerProvider.class);
    when(mockOpenTelemetry.getTracerProvider()).thenReturn(mockTracerProvider);
    TracerBuilder mockTracerBuilder = mock(TracerBuilder.class);
    when(mockTracerProvider.tracerBuilder(OpenTelemetryConstants.INSTRUMENTATION_SCOPE))
        .thenReturn(mockTracerBuilder);
    when(mockTracerBuilder.setInstrumentationVersion(any())).thenReturn(mockTracerBuilder);
    when(mockTracerBuilder.build()).thenReturn(mockTracer);
    when(mockSpanBuilder.setParent(any())).thenReturn(mockSpanBuilder);
    when(mockTracer.spanBuilder(any())).thenReturn(mockSpanBuilder);
  }

  @Test
  public void clientCallDelayTracing_endToEnd_nameResolutionDelay() throws Exception {
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
        return "slowres";
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
            return "slowres";
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
        .sdk(openTelemetryRule.getOpenTelemetry())
        .enableTracing(true)
        .build();

    InProcessChannelBuilder channelBuilder =
        InProcessChannelBuilder.forTarget("slowres:///test-service")
            .defaultLoadBalancingPolicy("pick_first");
    grpcOpenTelemetry.configureChannelBuilder(channelBuilder);
    ManagedChannel channel = channelBuilder.build();
    try {
      ClientCall<String, String> call = channel.newCall(method, CallOptions.DEFAULT);
      call.start(new ClientCall.Listener<String>() {}, new Metadata());
      call.request(1);

      resolutionLatch.await(5, TimeUnit.SECONDS);

      // Now complete name resolution
      capturedListener.get().onResult(NameResolver.ResolutionResult.newBuilder()
          .setAddressesOrError(StatusOr.fromValue(Collections.singletonList(
              new EquivalentAddressGroup(new InProcessSocketAddress("test-slow-res")))))
          .build());

      call.cancel("End test", null);
    } finally {
      channel.shutdownNow();
      channel.awaitTermination(5, TimeUnit.SECONDS);
      NameResolverRegistry.getDefaultRegistry().deregister(slowResolverProvider);
    }

    List<SpanData> spans = openTelemetryRule.getSpans();
    SpanData callDelaySpan = null;
    for (SpanData s : spans) {
      if ("Delay".equals(s.getName())) {
        callDelaySpan = s;
        break;
      }
    }
    assertNotNull(callDelaySpan);
    assertEquals("resolving",
        callDelaySpan.getAttributes().get(OpenTelemetryConstants.DELAY_TYPE_KEY));

    boolean foundDelayTriggered = false;
    for (EventData event : callDelaySpan.getEvents()) {
      if ("Delay triggered".equals(event.getName())) {
        String delayReason = event.getAttributes().get(OpenTelemetryConstants.DELAY_REASON_KEY);
        assertNotNull(delayReason);
        // The exact wording belongs to the channel; only require that it describes the wait.
        assertTrue(delayReason, delayReason.contains("name resolution"));
        assertNull(event.getAttributes().get(OpenTelemetryConstants.DELAY_TYPE_KEY));
        foundDelayTriggered = true;
      }
    }
    assertTrue(foundDelayTriggered);
  }

  @Test
  public void clientAttemptDelayTracing_endToEnd_inProcessTransport() throws Exception {
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
        return "slow_connecting_policy";
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
        return "inproce2e";
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
            return "inproce2e";
          }

          @Override
          public void start(Listener2 listener) {
            listener.onResult(NameResolver.ResolutionResult.newBuilder()
                .setAddressesOrError(StatusOr.fromValue(Collections.singletonList(
                    new EquivalentAddressGroup(new InProcessSocketAddress("test-e2e")))))
                .build());
          }

          @Override
          public void shutdown() {}
        };
      }
    };
    NameResolverRegistry.getDefaultRegistry().register(customResolverProvider);

    GrpcOpenTelemetry grpcOpenTelemetry = GrpcOpenTelemetry.newBuilder()
        .sdk(openTelemetryRule.getOpenTelemetry())
        .enableTracing(true)
        .build();

    InProcessChannelBuilder channelBuilder =
        InProcessChannelBuilder.forTarget("inproce2e:///test-e2e")
            .defaultLoadBalancingPolicy("slow_connecting_policy");
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

    List<SpanData> spans = openTelemetryRule.getSpans();
    SpanData delaySpanData = null;
    for (SpanData s : spans) {
      if ("Delay".equals(s.getName())) {
        delaySpanData = s;
        break;
      }
    }
    assertNotNull(delaySpanData);
    assertEquals("connecting",
        delaySpanData.getAttributes().get(OpenTelemetryConstants.DELAY_TYPE_KEY));

    boolean foundTransition = false;
    for (EventData event : delaySpanData.getEvents()) {
      if ("Delay triggered".equals(event.getName())
          && "Simulated slow TLS handshake with backend".equals(
              event.getAttributes().get(OpenTelemetryConstants.DELAY_REASON_KEY))) {
        foundTransition = true;
        break;
      }
    }
    assertTrue(foundTransition);
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
        "Inbound message" ,
        clientSpanEvents.get(1).getName());
    assertEquals(
        io.opentelemetry.api.common.Attributes.builder()
            .put("sequence-number", 0)
            .put("message-size", 288)
            .build(),
        clientSpanEvents.get(1).getAttributes());

    assertEquals(
        "Inbound message" ,
        clientSpanEvents.get(2).getName());
    assertEquals(
        io.opentelemetry.api.common.Attributes.builder()
            .put("sequence-number", 1)
            .put("message-size", 128)
            .build(),
        clientSpanEvents.get(2).getAttributes());
    assertEquals(StatusCode.OK, clientSpanData.getStatus().getStatusCode());
    assertEquals(clientSpanData.hasEnded(), true);

    // child(attempt) span data
    List<EventData> attemptSpanEvents = attemptSpanData.getEvents();
    assertEquals(clientSpanEvents.size(), 3);
    assertEquals(
        "Delayed LB pick complete",
        attemptSpanEvents.get(0).getName());
    assertTrue(clientSpanEvents.get(0).getAttributes().isEmpty());

    assertEquals(
        "Outbound message" ,
        attemptSpanEvents.get(1).getName());
    assertEquals(
        io.opentelemetry.api.common.Attributes.builder()
            .put("sequence-number", 0)
            .put("message-size-compressed", 882)
            .build(),
        attemptSpanEvents.get(1).getAttributes());

    assertEquals(
        "Outbound message" ,
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
    assertEquals(StatusCode.OK, attemptSpanData.getStatus().getStatusCode());
    assertEquals(0L,
        (long) attemptSpanData.getAttributes().get(AttributeKey.longKey("previous-rpc-attempts")));
    assertEquals(false,
        attemptSpanData.getAttributes().get(AttributeKey.booleanKey("transparent-retry")));
  }

  @Test
  public void clientAttemptDelayTracing_reasonChangedInvariant() {
    OpenTelemetryTracingModule tracingModule = new OpenTelemetryTracingModule(
        openTelemetryRule.getOpenTelemetry());
    Span clientSpan = tracerRule.spanBuilder("test-client-span").startSpan();
    CallAttemptsTracerFactory callTracer =
        tracingModule.newClientCallTracer(clientSpan, method);
    ClientStreamTracer clientStreamTracer =
        callTracer.newClientStreamTracer(STREAM_INFO, new Metadata());

    clientStreamTracer.recordDelayStart("connecting", "reason1");
    clientStreamTracer.recordDelayReasonChanged("connecting", "reason2");
    clientStreamTracer.recordDelayReasonChanged("connecting", "reason3");
    clientStreamTracer.recordDelayEnd("connecting");
    clientStreamTracer.streamClosed(Status.OK);
    callTracer.callEnded(Status.OK);
    clientSpan.end();

    List<SpanData> spans = openTelemetryRule.getSpans();
    assertEquals(3, spans.size());
    SpanData delaySpanData = spans.get(0);

    assertEquals("Delay", delaySpanData.getName());
    assertEquals("connecting", delaySpanData.getAttributes().get(
        OpenTelemetryConstants.DELAY_TYPE_KEY));
    assertEquals(3, delaySpanData.getEvents().size());

    EventData event1 = delaySpanData.getEvents().get(0);
    assertEquals("Delay triggered", event1.getName());
    assertEquals("reason1", event1.getAttributes().get(
        OpenTelemetryConstants.DELAY_REASON_KEY));
    assertNull(event1.getAttributes().get(OpenTelemetryConstants.DELAY_TYPE_KEY));

    EventData event2 = delaySpanData.getEvents().get(1);
    assertEquals("Delay triggered", event2.getName());
    assertEquals("reason2", event2.getAttributes().get(
        OpenTelemetryConstants.DELAY_REASON_KEY));
    assertNull(event2.getAttributes().get(OpenTelemetryConstants.DELAY_TYPE_KEY));

    EventData event3 = delaySpanData.getEvents().get(2);
    assertEquals("Delay triggered", event3.getName());
    assertEquals("reason3", event3.getAttributes().get(
        OpenTelemetryConstants.DELAY_REASON_KEY));
    assertNull(event3.getAttributes().get(OpenTelemetryConstants.DELAY_TYPE_KEY));
  }

  @Test
  public void clientCallDelayTracing_reasonChangedInvariant() {
    OpenTelemetryTracingModule tracingModule = new OpenTelemetryTracingModule(
        openTelemetryRule.getOpenTelemetry());
    Span clientSpan = tracerRule.spanBuilder("test-client-span").startSpan();
    CallAttemptsTracerFactory callTracer =
        tracingModule.newClientCallTracer(clientSpan, method);

    callTracer.recordDelayStart("resolving", "reason1");
    callTracer.recordDelayReasonChanged("resolving", "reason2");
    callTracer.recordDelayReasonChanged("resolving", "reason3");
    callTracer.recordDelayEnd("resolving");
    callTracer.callEnded(Status.OK);
    clientSpan.end();

    List<SpanData> spans = openTelemetryRule.getSpans();
    assertEquals(2, spans.size());
    SpanData callDelaySpan = spans.stream()
        .filter(s -> "Delay".equals(s.getName()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Expected 'Delay' span not found"));

    assertEquals("resolving", callDelaySpan.getAttributes().get(
        OpenTelemetryConstants.DELAY_TYPE_KEY));
    assertEquals(3, callDelaySpan.getEvents().size());

    EventData event1 = callDelaySpan.getEvents().get(0);
    assertEquals("Delay triggered", event1.getName());
    assertEquals("reason1",
        event1.getAttributes().get(OpenTelemetryConstants.DELAY_REASON_KEY));
    assertNull(event1.getAttributes().get(OpenTelemetryConstants.DELAY_TYPE_KEY));

    EventData event2 = callDelaySpan.getEvents().get(1);
    assertEquals("Delay triggered", event2.getName());
    assertEquals("reason2",
        event2.getAttributes().get(OpenTelemetryConstants.DELAY_REASON_KEY));
    assertNull(event2.getAttributes().get(OpenTelemetryConstants.DELAY_TYPE_KEY));

    EventData event3 = callDelaySpan.getEvents().get(2);
    assertEquals("Delay triggered", event3.getName());
    assertEquals("reason3",
        event3.getAttributes().get(OpenTelemetryConstants.DELAY_REASON_KEY));
    assertNull(event3.getAttributes().get(OpenTelemetryConstants.DELAY_TYPE_KEY));
  }

  @Test
  public void clientCallDelayStart_afterCallEnded_noSpansRecorded() {
    OpenTelemetryTracingModule tracingModule = new OpenTelemetryTracingModule(
        openTelemetryRule.getOpenTelemetry());
    Span clientSpan = tracerRule.spanBuilder("test-client-span").startSpan();
    CallAttemptsTracerFactory callTracer =
        tracingModule.newClientCallTracer(clientSpan, method);

    callTracer.callEnded(Status.OK);
    callTracer.recordDelayStart("resolving", "reasonAfterCallEnded");
    callTracer.recordDelayReasonChanged("resolving", "reason2");
    callTracer.recordDelayEnd("resolving");
    clientSpan.end();

    assertTrue(delaySpans(openTelemetryRule.getSpans()).isEmpty());
  }

  @Test
  public void clientAttemptDelayStart_afterStreamClosed_noSpansRecorded() {
    OpenTelemetryTracingModule tracingModule = new OpenTelemetryTracingModule(
        openTelemetryRule.getOpenTelemetry());
    Span clientSpan = tracerRule.spanBuilder("test-client-span").startSpan();
    CallAttemptsTracerFactory callTracer =
        tracingModule.newClientCallTracer(clientSpan, method);
    ClientStreamTracer clientStreamTracer =
        callTracer.newClientStreamTracer(STREAM_INFO, new Metadata());

    clientStreamTracer.streamClosed(Status.OK);
    clientStreamTracer.recordDelayStart("connecting", "reasonAfterStreamClosed");
    clientStreamTracer.recordDelayReasonChanged("connecting", "reason2");
    clientStreamTracer.recordDelayEnd("connecting");
    callTracer.callEnded(Status.OK);
    clientSpan.end();

    assertTrue(delaySpans(openTelemetryRule.getSpans()).isEmpty());
  }

  /**
   * The sequence the channel actually produces when the delay type changes: it ends the current
   * delay and then starts the next one.
   */
  @Test
  public void clientCallDelay_delayTypeChange_producesOneSpanPerDelayType() {
    OpenTelemetryTracingModule tracingModule = new OpenTelemetryTracingModule(
        openTelemetryRule.getOpenTelemetry());
    Span clientSpan = tracerRule.spanBuilder("test-client-span").startSpan();
    CallAttemptsTracerFactory callTracer =
        tracingModule.newClientCallTracer(clientSpan, method);

    callTracer.recordDelayStart("resolving", "waiting for DNS query for example.com");
    callTracer.recordDelayEnd("resolving");
    callTracer.recordDelayStart("connecting", "waiting for subchannel to connect");
    callTracer.recordDelayEnd("connecting");
    callTracer.callEnded(Status.OK);
    clientSpan.end();

    List<SpanData> delaySpans = delaySpans(openTelemetryRule.getSpans());
    assertEquals(2, delaySpans.size());
    SpanData resolvingSpan = delaySpanWithType(delaySpans, "resolving");
    SpanData connectingSpan = delaySpanWithType(delaySpans, "connecting");
    assertDelayTriggeredEvent(resolvingSpan, "waiting for DNS query for example.com");
    assertDelayTriggeredEvent(connectingSpan, "waiting for subchannel to connect");
    // Both delays are call-scoped, so both hang off the call span.
    assertEquals(clientSpan.getSpanContext().getSpanId(), resolvingSpan.getParentSpanId());
    assertEquals(clientSpan.getSpanContext().getSpanId(), connectingSpan.getParentSpanId());
    assertTrue(resolvingSpan.getEndEpochNanos() <= connectingSpan.getStartEpochNanos());
  }

  /**
   * Defensive rollover: a new delay type arrives while the previous delay is still open. The
   * previous span must be ended before the new one is opened, so the two never overlap.
   */
  @Test
  public void clientCallDelayStart_delayTypeTransition_rollsOverToNewSpan() {
    OpenTelemetryTracingModule tracingModule = new OpenTelemetryTracingModule(
        openTelemetryRule.getOpenTelemetry());
    Span clientSpan = tracerRule.spanBuilder("test-client-span").startSpan();
    CallAttemptsTracerFactory callTracer =
        tracingModule.newClientCallTracer(clientSpan, method);

    callTracer.recordDelayStart("resolving", "dns lookup");
    callTracer.recordDelayStart("connecting", "pick first connect");
    callTracer.recordDelayEnd("connecting");
    callTracer.callEnded(Status.OK);
    clientSpan.end();

    List<SpanData> delaySpans = delaySpans(openTelemetryRule.getSpans());
    assertEquals(2, delaySpans.size());
    SpanData resolvingSpan = delaySpanWithType(delaySpans, "resolving");
    SpanData connectingSpan = delaySpanWithType(delaySpans, "connecting");
    assertDelayTriggeredEvent(resolvingSpan, "dns lookup");
    assertDelayTriggeredEvent(connectingSpan, "pick first connect");
    assertEquals(clientSpan.getSpanContext().getSpanId(), resolvingSpan.getParentSpanId());
    assertEquals(clientSpan.getSpanContext().getSpanId(), connectingSpan.getParentSpanId());
    assertTrue(resolvingSpan.getEndEpochNanos() <= connectingSpan.getStartEpochNanos());
  }

  /**
   * The sequence the channel actually produces at the attempt level when a pick is re-queued with
   * a different delay type: end the current delay, then start the next one.
   */
  @Test
  public void clientAttemptDelay_delayTypeChange_producesOneSpanPerDelayType() {
    OpenTelemetryTracingModule tracingModule = new OpenTelemetryTracingModule(
        openTelemetryRule.getOpenTelemetry());
    Span clientSpan = tracerRule.spanBuilder("test-client-span").startSpan();
    CallAttemptsTracerFactory callTracer =
        tracingModule.newClientCallTracer(clientSpan, method);
    ClientStreamTracer clientStreamTracer =
        callTracer.newClientStreamTracer(STREAM_INFO, new Metadata());

    clientStreamTracer.recordDelayStart(
        "rls_lookup_pending", "Route Lookup Service query pending on rls-server:8080");
    clientStreamTracer.recordDelayEnd("rls_lookup_pending");
    clientStreamTracer.recordDelayStart(
        "connecting", "waiting for subchannel to connect to 192.168.1.50:8080");
    clientStreamTracer.recordDelayEnd("connecting");
    clientStreamTracer.streamClosed(Status.OK);
    callTracer.callEnded(Status.OK);
    clientSpan.end();

    List<SpanData> spans = openTelemetryRule.getSpans();
    List<SpanData> delaySpans = delaySpans(spans);
    assertEquals(2, delaySpans.size());
    SpanData rlsSpan = delaySpanWithType(delaySpans, "rls_lookup_pending");
    SpanData connectingSpan = delaySpanWithType(delaySpans, "connecting");
    assertDelayTriggeredEvent(rlsSpan, "Route Lookup Service query pending on rls-server:8080");
    assertDelayTriggeredEvent(
        connectingSpan, "waiting for subchannel to connect to 192.168.1.50:8080");
    // Attempt-scoped delays hang off the attempt span, not the call span.
    String attemptSpanId = spanWithName(spans, "Attempt.package1.service2.method3").getSpanId();
    assertEquals(attemptSpanId, rlsSpan.getParentSpanId());
    assertEquals(attemptSpanId, connectingSpan.getParentSpanId());
    assertTrue(rlsSpan.getEndEpochNanos() <= connectingSpan.getStartEpochNanos());
  }

  /**
   * Defensive rollover at the attempt level, e.g. A121's {@code rls_lookup_pending -> connecting}
   * transition arriving without an intervening end.
   */
  @Test
  public void clientAttemptDelayStart_delayTypeTransition_rollsOverToNewSpan() {
    OpenTelemetryTracingModule tracingModule = new OpenTelemetryTracingModule(
        openTelemetryRule.getOpenTelemetry());
    Span clientSpan = tracerRule.spanBuilder("test-client-span").startSpan();
    CallAttemptsTracerFactory callTracer =
        tracingModule.newClientCallTracer(clientSpan, method);
    ClientStreamTracer clientStreamTracer =
        callTracer.newClientStreamTracer(STREAM_INFO, new Metadata());

    clientStreamTracer.recordDelayStart(
        "rls_lookup_pending", "Route Lookup Service query pending on rls-server:8080");
    clientStreamTracer.recordDelayStart(
        "connecting", "waiting for subchannel to connect to 192.168.1.50:8080");
    clientStreamTracer.recordDelayEnd("connecting");
    clientStreamTracer.streamClosed(Status.OK);
    callTracer.callEnded(Status.OK);
    clientSpan.end();

    List<SpanData> spans = openTelemetryRule.getSpans();
    List<SpanData> delaySpans = delaySpans(spans);
    assertEquals(2, delaySpans.size());
    SpanData rlsSpan = delaySpanWithType(delaySpans, "rls_lookup_pending");
    SpanData connectingSpan = delaySpanWithType(delaySpans, "connecting");
    assertDelayTriggeredEvent(rlsSpan, "Route Lookup Service query pending on rls-server:8080");
    assertDelayTriggeredEvent(
        connectingSpan, "waiting for subchannel to connect to 192.168.1.50:8080");
    String attemptSpanId = spanWithName(spans, "Attempt.package1.service2.method3").getSpanId();
    assertEquals(attemptSpanId, rlsSpan.getParentSpanId());
    assertEquals(attemptSpanId, connectingSpan.getParentSpanId());
    // The first delay is closed before the second one opens: the spans never overlap.
    assertTrue(rlsSpan.getEndEpochNanos() <= connectingSpan.getStartEpochNanos());
  }

  @Test
  public void clientCallDelayStart_sameTypeWhileActive_keepsExistingSpanAndAddsEvent() {
    OpenTelemetryTracingModule tracingModule = new OpenTelemetryTracingModule(
        openTelemetryRule.getOpenTelemetry());
    Span clientSpan = tracerRule.spanBuilder("test-client-span").startSpan();
    CallAttemptsTracerFactory callTracer =
        tracingModule.newClientCallTracer(clientSpan, method);

    callTracer.recordDelayStart("resolving", "reason1");
    callTracer.recordDelayStart("resolving", "reason2");
    callTracer.recordDelayEnd("resolving");
    callTracer.callEnded(Status.OK);
    clientSpan.end();

    List<SpanData> delaySpans = delaySpans(openTelemetryRule.getSpans());
    assertEquals(1, delaySpans.size());
    SpanData delaySpan = delaySpans.get(0);
    assertEquals("resolving", delaySpan.getAttributes().get(OpenTelemetryConstants.DELAY_TYPE_KEY));
    assertEquals(2, delaySpan.getEvents().size());
    assertEquals("reason1", delaySpan.getEvents().get(0).getAttributes().get(
        OpenTelemetryConstants.DELAY_REASON_KEY));
    assertEquals("reason2", delaySpan.getEvents().get(1).getAttributes().get(
        OpenTelemetryConstants.DELAY_REASON_KEY));
  }

  @Test
  public void clientAttemptDelayStart_sameTypeWhileActive_keepsExistingSpanAndAddsEvent() {
    OpenTelemetryTracingModule tracingModule = new OpenTelemetryTracingModule(
        openTelemetryRule.getOpenTelemetry());
    Span clientSpan = tracerRule.spanBuilder("test-client-span").startSpan();
    CallAttemptsTracerFactory callTracer =
        tracingModule.newClientCallTracer(clientSpan, method);
    ClientStreamTracer clientStreamTracer =
        callTracer.newClientStreamTracer(STREAM_INFO, new Metadata());

    clientStreamTracer.recordDelayStart("connecting", "reason1");
    clientStreamTracer.recordDelayStart("connecting", "reason2");
    clientStreamTracer.recordDelayEnd("connecting");
    clientStreamTracer.streamClosed(Status.OK);
    callTracer.callEnded(Status.OK);
    clientSpan.end();

    List<SpanData> delaySpans = delaySpans(openTelemetryRule.getSpans());
    assertEquals(1, delaySpans.size());
    SpanData delaySpan = delaySpans.get(0);
    assertEquals(
        "connecting", delaySpan.getAttributes().get(OpenTelemetryConstants.DELAY_TYPE_KEY));
    assertEquals(2, delaySpan.getEvents().size());
    assertEquals("reason1", delaySpan.getEvents().get(0).getAttributes().get(
        OpenTelemetryConstants.DELAY_REASON_KEY));
    assertEquals("reason2", delaySpan.getEvents().get(1).getAttributes().get(
        OpenTelemetryConstants.DELAY_REASON_KEY));
  }

  /**
   * A121: an open delay is terminated by the call tracer itself when the RPC is cancelled or
   * reaches its deadline, so the channel does not end it explicitly on those paths.
   */
  @Test
  public void clientCallEnded_withOpenDelay_endsDelaySpan() {
    OpenTelemetryTracingModule tracingModule = new OpenTelemetryTracingModule(
        openTelemetryRule.getOpenTelemetry());
    Span clientSpan = tracerRule.spanBuilder("test-client-span").startSpan();
    CallAttemptsTracerFactory callTracer =
        tracingModule.newClientCallTracer(clientSpan, method);

    callTracer.recordDelayStart("resolving", "waiting for DNS query");
    callTracer.callEnded(Status.CANCELLED);
    clientSpan.end();

    List<SpanData> delaySpans = delaySpans(openTelemetryRule.getSpans());
    assertEquals(1, delaySpans.size());
    SpanData delaySpan = delaySpans.get(0);
    assertTrue(delaySpan.hasEnded());
    assertEquals("resolving", delaySpan.getAttributes().get(
        OpenTelemetryConstants.DELAY_TYPE_KEY));
    assertDelayTriggeredEvent(delaySpan, "waiting for DNS query");
  }

  /** Attempt-level twin of {@link #clientCallEnded_withOpenDelay_endsDelaySpan}. */
  @Test
  public void clientStreamClosed_withOpenDelay_endsDelaySpan() {
    OpenTelemetryTracingModule tracingModule = new OpenTelemetryTracingModule(
        openTelemetryRule.getOpenTelemetry());
    Span clientSpan = tracerRule.spanBuilder("test-client-span").startSpan();
    CallAttemptsTracerFactory callTracer =
        tracingModule.newClientCallTracer(clientSpan, method);
    ClientStreamTracer clientStreamTracer =
        callTracer.newClientStreamTracer(STREAM_INFO, new Metadata());

    clientStreamTracer.recordDelayStart("connecting", "waiting for subchannel to connect");
    clientStreamTracer.streamClosed(Status.CANCELLED);
    callTracer.callEnded(Status.CANCELLED);
    clientSpan.end();

    List<SpanData> spans = openTelemetryRule.getSpans();
    List<SpanData> delaySpans = delaySpans(spans);
    assertEquals(1, delaySpans.size());
    SpanData delaySpan = delaySpans.get(0);
    assertTrue(delaySpan.hasEnded());
    assertEquals("connecting", delaySpan.getAttributes().get(
        OpenTelemetryConstants.DELAY_TYPE_KEY));
    assertDelayTriggeredEvent(delaySpan, "waiting for subchannel to connect");
    assertEquals(
        spanWithName(spans, "Attempt.package1.service2.method3").getSpanId(),
        delaySpan.getParentSpanId());
  }

  @Test
  public void clientCallEnded_calledTwice_secondCallNoOp() {
    OpenTelemetryTracingModule tracingModule = new OpenTelemetryTracingModule(
        openTelemetryRule.getOpenTelemetry());
    Span clientSpan = tracerRule.spanBuilder("test-client-span").startSpan();
    CallAttemptsTracerFactory callTracer =
        tracingModule.newClientCallTracer(clientSpan, method);

    callTracer.callEnded(Status.OK);
    callTracer.callEnded(Status.CANCELLED);

    List<SpanData> spans = openTelemetryRule.getSpans();
    assertEquals(1, spans.size());
    assertEquals(StatusCode.OK, spans.get(0).getStatus().getStatusCode());
  }

  @Test
  public void clientStreamClosed_calledTwice_secondCallNoOp() {
    OpenTelemetryTracingModule tracingModule = new OpenTelemetryTracingModule(
        openTelemetryRule.getOpenTelemetry());
    Span clientSpan = tracerRule.spanBuilder("test-client-span").startSpan();
    CallAttemptsTracerFactory callTracer =
        tracingModule.newClientCallTracer(clientSpan, method);
    ClientStreamTracer clientStreamTracer =
        callTracer.newClientStreamTracer(STREAM_INFO, new Metadata());

    clientStreamTracer.streamClosed(Status.OK);
    clientStreamTracer.streamClosed(Status.CANCELLED);
    callTracer.callEnded(Status.OK);

    List<SpanData> spans = openTelemetryRule.getSpans();
    SpanData attemptSpan = spanWithName(spans, "Attempt.package1.service2.method3");
    assertEquals(StatusCode.OK, attemptSpan.getStatus().getStatusCode());
  }

  @Test
  public void serverStreamClosed_calledTwice_secondCallNoOp() {
    OpenTelemetryTracingModule tracingModule = new OpenTelemetryTracingModule(
        openTelemetryRule.getOpenTelemetry());
    ServerStreamTracer.Factory serverTracerFactory =
        tracingModule.getServerTracerFactory();
    ServerStreamTracer serverTracer =
        serverTracerFactory.newServerStreamTracer(method.getFullMethodName(), new Metadata());

    serverTracer.streamClosed(Status.OK);
    serverTracer.streamClosed(Status.CANCELLED);

    List<SpanData> spans = openTelemetryRule.getSpans();
    assertEquals(1, spans.size());
    assertEquals(StatusCode.OK, spans.get(0).getStatus().getStatusCode());
  }

  @Test
  public void clientCallDelayReasonChanged_noActiveSpan_noOp() {
    OpenTelemetryTracingModule tracingModule = new OpenTelemetryTracingModule(
        openTelemetryRule.getOpenTelemetry());
    Span clientSpan = tracerRule.spanBuilder("test-client-span").startSpan();
    CallAttemptsTracerFactory callTracer =
        tracingModule.newClientCallTracer(clientSpan, method);

    callTracer.recordDelayReasonChanged("resolving", "reasonWithoutSpan");
    callTracer.callEnded(Status.OK);
    clientSpan.end();

    // No delay was started, so the reason change is dropped instead of creating a span.
    assertTrue(delaySpans(openTelemetryRule.getSpans()).isEmpty());
  }

  @Test
  public void clientAttemptDelayReasonChanged_noActiveSpan_noOp() {
    OpenTelemetryTracingModule tracingModule = new OpenTelemetryTracingModule(
        openTelemetryRule.getOpenTelemetry());
    Span clientSpan = tracerRule.spanBuilder("test-client-span").startSpan();
    CallAttemptsTracerFactory callTracer =
        tracingModule.newClientCallTracer(clientSpan, method);
    ClientStreamTracer clientStreamTracer =
        callTracer.newClientStreamTracer(STREAM_INFO, new Metadata());

    clientStreamTracer.recordDelayReasonChanged("connecting", "reasonWithoutSpan");
    clientStreamTracer.streamClosed(Status.OK);
    callTracer.callEnded(Status.OK);
    clientSpan.end();

    // No delay was started, so the reason change is dropped instead of creating a span.
    assertTrue(delaySpans(openTelemetryRule.getSpans()).isEmpty());
  }

  @Test
  public void clientCallDelay_nullArguments_throwNullPointerException() {
    OpenTelemetryTracingModule tracingModule = new OpenTelemetryTracingModule(
        openTelemetryRule.getOpenTelemetry());
    Span clientSpan = tracerRule.spanBuilder("test-client-span").startSpan();
    CallAttemptsTracerFactory callTracer =
        tracingModule.newClientCallTracer(clientSpan, method);

    assertEquals("delayType",
        assertThrows(
            NullPointerException.class,
            () -> callTracer.recordDelayStart(null, "reason")).getMessage());
    assertEquals("delayReason",
        assertThrows(
            NullPointerException.class,
            () -> callTracer.recordDelayStart("resolving", null)).getMessage());
    assertEquals("delayType",
        assertThrows(
            NullPointerException.class,
            () -> callTracer.recordDelayReasonChanged(null, "reason")).getMessage());
    assertEquals("delayReason",
        assertThrows(
            NullPointerException.class,
            () -> callTracer.recordDelayReasonChanged("resolving", null)).getMessage());
    assertEquals("delayType",
        assertThrows(
            NullPointerException.class, () -> callTracer.recordDelayEnd(null)).getMessage());

    callTracer.callEnded(Status.OK);
    clientSpan.end();

    // A rejected call must not leave a half-initialized delay span behind.
    assertTrue(delaySpans(openTelemetryRule.getSpans()).isEmpty());
  }

  @Test
  public void clientAttemptDelay_nullArguments_throwNullPointerException() {
    OpenTelemetryTracingModule tracingModule = new OpenTelemetryTracingModule(
        openTelemetryRule.getOpenTelemetry());
    Span clientSpan = tracerRule.spanBuilder("test-client-span").startSpan();
    CallAttemptsTracerFactory callTracer =
        tracingModule.newClientCallTracer(clientSpan, method);
    ClientStreamTracer clientStreamTracer =
        callTracer.newClientStreamTracer(STREAM_INFO, new Metadata());

    assertEquals("delayType",
        assertThrows(
            NullPointerException.class,
            () -> clientStreamTracer.recordDelayStart(null, "reason")).getMessage());
    assertEquals("delayReason",
        assertThrows(
            NullPointerException.class,
            () -> clientStreamTracer.recordDelayStart("connecting", null)).getMessage());
    assertEquals("delayType",
        assertThrows(
            NullPointerException.class,
            () -> clientStreamTracer.recordDelayReasonChanged(null, "reason")).getMessage());
    assertEquals("delayReason",
        assertThrows(
            NullPointerException.class,
            () -> clientStreamTracer.recordDelayReasonChanged("connecting", null)).getMessage());
    assertEquals("delayType",
        assertThrows(
            NullPointerException.class,
            () -> clientStreamTracer.recordDelayEnd(null)).getMessage());

    clientStreamTracer.streamClosed(Status.OK);
    callTracer.callEnded(Status.OK);
    clientSpan.end();

    assertTrue(delaySpans(openTelemetryRule.getSpans()).isEmpty());
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
    OpenTelemetryTracingModule tracingModule = new OpenTelemetryTracingModule(
        openTelemetryRule.getOpenTelemetry());
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
        "Outbound message" ,
        events.get(0).getName());
    assertEquals(
        io.opentelemetry.api.common.Attributes.builder()
            .put("sequence-number", 0)
            .put("message-size-compressed", 882)
            .put("message-size", 998)
            .build(),
        events.get(0).getAttributes());

    assertEquals(
        "Outbound message" ,
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
        "Inbound message" ,
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
  public void testServerParentSpanPropagation() throws Exception {
    final AtomicReference<Span> applicationSpan = new AtomicReference<>();
    OpenTelemetryTracingModule tracingModule = new OpenTelemetryTracingModule(
        openTelemetryRule.getOpenTelemetry());
    ServerServiceDefinition serviceDefinition =
        ServerServiceDefinition.builder("package1.service2").addMethod(
            method, new ServerCallHandler<String, String>() {
              @Override
              public ServerCall.Listener<String> startCall(
                  ServerCall<String, String> call, Metadata headers) {
                applicationSpan.set(Span.fromContext(Context.current()));
                call.sendHeaders(new Metadata());
                call.sendMessage("Hello");
                call.close(
                    Status.PERMISSION_DENIED.withDescription("No you don't"), new Metadata());
                return mockServerCallListener;
              }
            }).build();

    Server server = InProcessServerBuilder.forName("test-server-span")
        .addService(
            ServerInterceptors.intercept(serviceDefinition,
                tracingModule.getServerSpanPropagationInterceptor()))
        .addStreamTracerFactory(tracingModule.getServerTracerFactory())
        .directExecutor().build().start();
    grpcCleanupRule.register(server);

    ManagedChannel channel = InProcessChannelBuilder.forName("test-server-span")
        .directExecutor().build();
    grpcCleanupRule.register(channel);

    Span parentSpan = tracerRule.spanBuilder("test-parent-span").startSpan();
    try (Scope scope = Context.current().with(parentSpan).makeCurrent()) {
      Channel interceptedChannel =
          ClientInterceptors.intercept(
              channel, tracingModule.getClientInterceptor());
      ClientCall<String, String> call = interceptedChannel.newCall(method, CALL_OPTIONS);
      Metadata headers = new Metadata();
      call.start(mockClientCallListener, headers);

      // End the call
      call.halfClose();
      call.request(1);
      parentSpan.end();
    }

    verify(mockClientCallListener).onClose(statusCaptor.capture(), any(Metadata.class));
    Status rpcStatus = statusCaptor.getValue();
    assertEquals(rpcStatus.getCode(), Status.Code.PERMISSION_DENIED);
    assertEquals(rpcStatus.getDescription(), "No you don't");
    assertEquals(applicationSpan.get().getSpanContext().getTraceId(),
        parentSpan.getSpanContext().getTraceId());

    List<SpanData> spans = openTelemetryRule.getSpans();
    assertEquals(spans.size(), 4);
    SpanData clientSpan = spans.get(2);
    SpanData attemptSpan = spans.get(1);

    assertEquals(clientSpan.getName(), "Sent.package1.service2.method3");
    assertTrue(clientSpan.hasEnded());
    assertEquals(clientSpan.getStatus().getStatusCode(), StatusCode.ERROR);
    assertEquals(clientSpan.getStatus().getDescription(), "PERMISSION_DENIED: No you don't");

    assertEquals(attemptSpan.getName(), "Attempt.package1.service2.method3");
    assertTrue(attemptSpan.hasEnded());
    assertEquals(attemptSpan.getStatus().getStatusCode(), StatusCode.ERROR);
    assertEquals(attemptSpan.getStatus().getDescription(), "PERMISSION_DENIED: No you don't");

    SpanData serverSpan = spans.get(0);
    assertEquals(serverSpan.getName(), "Recv.package1.service2.method3");
    assertTrue(serverSpan.hasEnded());
    assertEquals(serverSpan.getStatus().getStatusCode(), StatusCode.ERROR);
    assertEquals(serverSpan.getStatus().getDescription(), "PERMISSION_DENIED: No you don't");
  }

  @Test
  public void serverSpanPropagationInterceptor() throws Exception {
    OpenTelemetryTracingModule tracingModule = new OpenTelemetryTracingModule(
        openTelemetryRule.getOpenTelemetry());
    Server server = InProcessServerBuilder.forName("test-span-propagation-interceptor")
        .directExecutor().build().start();
    grpcCleanupRule.register(server);
    final AtomicReference<Span> callbackSpan = new AtomicReference<>();
    ServerCall.Listener<Integer> getContextListener = new ServerCall.Listener<Integer>() {
      @Override
      public void onMessage(Integer message) {
        callbackSpan.set(Span.fromContext(Context.current()));
      }

      @Override
      public void onHalfClose() {
        callbackSpan.set(Span.fromContext(Context.current()));
      }

      @Override
      public void onCancel() {
        callbackSpan.set(Span.fromContext(Context.current()));
      }

      @Override
      public void onComplete() {
        callbackSpan.set(Span.fromContext(Context.current()));
      }
    };
    ServerInterceptor interceptor = tracingModule.getServerSpanPropagationInterceptor();
    @SuppressWarnings("unchecked")
    ServerCallHandler<Integer, Integer> handler = mock(ServerCallHandler.class);
    when(handler.startCall(any(), any())).thenReturn(getContextListener);
    ServerCall<Integer, Integer> call = new NoopServerCall<>();
    Metadata metadata = new Metadata();
    ServerCall.Listener<Integer> listener = interceptor.interceptCall(call, metadata, handler);
    verify(handler).startCall(same(call), same(metadata));
    listener.onMessage(1);
    assertEquals(callbackSpan.get(), Span.getInvalid());
    listener.onReady();
    assertEquals(callbackSpan.get(), Span.getInvalid());
    listener.onCancel();
    assertEquals(callbackSpan.get(), Span.getInvalid());
    listener.onHalfClose();
    assertEquals(callbackSpan.get(), Span.getInvalid());
    listener.onComplete();
    assertEquals(callbackSpan.get(), Span.getInvalid());

    Span parentSpan = tracerRule.spanBuilder("parent-span").startSpan();
    io.grpc.Context context = io.grpc.Context.current().withValue(
        tracingModule.otelSpan, parentSpan);
    io.grpc.Context previous = context.attach();
    try {
      listener = interceptor.interceptCall(call, metadata, handler);
      verify(handler, times(2)).startCall(same(call), same(metadata));
      listener.onMessage(1);
      assertEquals(callbackSpan.get().getSpanContext().getTraceId(),
          parentSpan.getSpanContext().getTraceId());
      listener.onReady();
      assertEquals(callbackSpan.get().getSpanContext().getTraceId(),
          parentSpan.getSpanContext().getTraceId());
      listener.onCancel();
      assertEquals(callbackSpan.get().getSpanContext().getTraceId(),
          parentSpan.getSpanContext().getTraceId());
      listener.onHalfClose();
      assertEquals(callbackSpan.get().getSpanContext().getTraceId(),
          parentSpan.getSpanContext().getTraceId());
      listener.onComplete();
      assertEquals(callbackSpan.get().getSpanContext().getTraceId(),
          parentSpan.getSpanContext().getTraceId());
    } finally {
      context.detach(previous);
    }
  }

  /**
   * Tests that baggage from the initial context is propagated
   * to the context active during the next handler's execution.
   */
  @Test
  public void testBaggageIsPropagatedToHandlerContext() {
    // 1. ARRANGE
    OpenTelemetryTracingModule tracingModule = new OpenTelemetryTracingModule(
        openTelemetryRule.getOpenTelemetry());
    ServerInterceptor interceptor = tracingModule.getServerSpanPropagationInterceptor();

    // Create mocks for the gRPC call chain
    @SuppressWarnings("unchecked")
    ServerCallHandler<String, String> mockHandler = mock(ServerCallHandler.class);
    @SuppressWarnings("unchecked")
    ServerCall.Listener<String> mockListener = mock(ServerCall.Listener.class);
    ServerCall<String, String> mockCall = new NoopServerCall<>();
    Metadata mockHeaders = new Metadata();

    // Create a non-null Span (required to pass the first 'if' check)
    Span testSpan = Span.wrap(
        SpanContext.create("time-period", "star-wars",
            TraceFlags.getSampled(), TraceState.getDefault()));

    // Create the test Baggage
    Baggage testBaggage = Baggage.builder().put("best-bot", "R2D2").build();

    // Create the initial gRPC context that the interceptor will read from
    io.grpc.Context initialGrpcContext = io.grpc.Context.current()
        .withValue(tracingModule.otelSpan, testSpan)
        .withValue(BAGGAGE_KEY, testBaggage);

    // This AtomicReference will capture the Baggage from *within* the handler
    final AtomicReference<Baggage> capturedBaggage = new AtomicReference<>();

    // Stub the handler to capture the *current* context when it's called
    doAnswer(invocation -> {
      // Baggage.current() gets baggage from io.opentelemetry.context.Context.current()
      capturedBaggage.set(Baggage.current());
      return mockListener;
    }).when(mockHandler).startCall(any(), any());

    // 2. ACT
    // Run the interceptCall method within the prepared context
    io.grpc.Context previous = initialGrpcContext.attach();
    try {
      interceptor.interceptCall(mockCall, mockHeaders, mockHandler);
    } finally {
      initialGrpcContext.detach(previous);
    }

    // 3. ASSERT
    // Verify the next handler was called
    verify(mockHandler).startCall(same(mockCall), same(mockHeaders));

    // Check the baggage that was captured
    assertNotNull("Baggage should not be null in handler context", capturedBaggage.get());
    assertEquals("Baggage was not correctly propagated to the handler's context",
        "R2D2", capturedBaggage.get().getEntryValue("best-bot"));
  }

  /**
   * Tests that the interceptor proceeds correctly if baggage is null or empty.
   */
  @Test
  public void testNullBaggageIsHandledGracefully() {
    // 1. ARRANGE
    OpenTelemetryTracingModule tracingModule = new OpenTelemetryTracingModule(
        openTelemetryRule.getOpenTelemetry());
    ServerInterceptor interceptor = tracingModule.getServerSpanPropagationInterceptor();

    @SuppressWarnings("unchecked")
    ServerCallHandler<String, String> mockHandler = mock(ServerCallHandler.class);
    @SuppressWarnings("unchecked")
    ServerCall.Listener<String> mockListener = mock(ServerCall.Listener.class);
    ServerCall<String, String> mockCall = new NoopServerCall<>();
    Metadata mockHeaders = new Metadata();

    Span testSpan = Span.getInvalid(); // A non-null span

    // No baggage is set in the context
    io.grpc.Context initialGrpcContext = io.grpc.Context.current()
        .withValue(tracingModule.otelSpan, testSpan);

    final AtomicReference<Baggage> capturedBaggage = new AtomicReference<>();

    // Stub the handler to capture the *current* context when it's called
    doAnswer(invocation -> {
      // Baggage.current() gets baggage from io.opentelemetry.context.Context.current()
      capturedBaggage.set(Baggage.current());
      return mockListener;
    }).when(mockHandler).startCall(any(), any());

    // 2. ACT
    io.grpc.Context previous = initialGrpcContext.attach();
    try {
      interceptor.interceptCall(mockCall, mockHeaders, mockHandler);
    } finally {
      initialGrpcContext.detach(previous);
    }

    // 3. ASSERT
    verify(mockHandler).startCall(same(mockCall), same(mockHeaders));

    // Baggage should be null in the downstream context
    assertEquals("Baggage should be empty when not provided",
        Baggage.empty(), capturedBaggage.get());
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

  /**
   * Counts span starts and ends through a real SDK {@link SpanProcessor}. A121 delay spans are
   * built <em>outside</em> the tracer's monitor, so a concurrent call end or delay-type rollover
   * can race the publication of a freshly created span. The production code detects that case
   * ("stale") and ends the orphan itself. If it did not, the span would be started and never
   * ended. Start/end balance therefore holds for every possible interleaving, which makes this a
   * deterministic assertion over non-deterministic execution.
   */
  private static final class SpanBalanceProcessor implements SpanProcessor {
    final AtomicInteger started = new AtomicInteger();
    final AtomicInteger ended = new AtomicInteger();

    @Override
    public void onStart(Context parentContext, ReadWriteSpan span) {
      started.incrementAndGet();
    }

    @Override
    public boolean isStartRequired() {
      return true;
    }

    @Override
    public void onEnd(ReadableSpan span) {
      ended.incrementAndGet();
    }

    @Override
    public boolean isEndRequired() {
      return true;
    }
  }

  private static void runRacing(Runnable a, Runnable b, List<Throwable> failures)
      throws InterruptedException {
    CyclicBarrier barrier = new CyclicBarrier(2);
    Thread ta = new Thread(() -> {
      try {
        barrier.await();
        a.run();
      } catch (Throwable t) {
        failures.add(t);
      }
    }, "racer-a");
    Thread tb = new Thread(() -> {
      try {
        barrier.await();
        b.run();
      } catch (Throwable t) {
        failures.add(t);
      }
    }, "racer-b");
    ta.start();
    tb.start();
    ta.join(TimeUnit.SECONDS.toMillis(10));
    tb.join(TimeUnit.SECONDS.toMillis(10));
    assertTrue("racer-a did not finish; likely deadlock", !ta.isAlive());
    assertTrue("racer-b did not finish; likely deadlock", !tb.isAlive());
  }

  @Test
  public void clientCallDelay_startRacesCallEnd_neverLeaksASpan() throws Exception {
    SpanBalanceProcessor balance = new SpanBalanceProcessor();
    OpenTelemetry otel = OpenTelemetrySdk.builder()
        .setTracerProvider(SdkTracerProvider.builder().addSpanProcessor(balance).build())
        .build();
    Tracer tracer = otel.getTracerProvider().get("grpc-java-test");
    List<Throwable> failures = Collections.synchronizedList(new ArrayList<Throwable>());

    for (int i = 0; i < 300; i++) {
      OpenTelemetryTracingModule tracingModule = new OpenTelemetryTracingModule(otel);
      Span clientSpan = tracer.spanBuilder("test-client-span").startSpan();
      CallAttemptsTracerFactory callTracer = tracingModule.newClientCallTracer(clientSpan, method);

      runRacing(
          () -> {
            callTracer.recordDelayStart("resolving", "waiting for DNS");
            callTracer.recordDelayReasonChanged("resolving", "DNS retry");
            // A type rollover bumps the epoch, which is the other way a span goes stale.
            callTracer.recordDelayStart("connecting", "waiting for subchannel");
          },
          () -> callTracer.callEnded(Status.OK),
          failures);

      // Idempotent: whichever thread lost the race, the call is closed out here.
      callTracer.callEnded(Status.OK);
    }

    assertTrue("racing threads threw: " + failures, failures.isEmpty());
    assertEquals(
        "every started span must also be ended, otherwise a delay span leaked",
        balance.started.get(), balance.ended.get());
  }

  @Test
  public void clientAttemptDelay_startRacesStreamClose_neverLeaksASpan() throws Exception {
    SpanBalanceProcessor balance = new SpanBalanceProcessor();
    OpenTelemetry otel = OpenTelemetrySdk.builder()
        .setTracerProvider(SdkTracerProvider.builder().addSpanProcessor(balance).build())
        .build();
    Tracer tracer = otel.getTracerProvider().get("grpc-java-test");
    List<Throwable> failures = Collections.synchronizedList(new ArrayList<Throwable>());

    for (int i = 0; i < 300; i++) {
      OpenTelemetryTracingModule tracingModule = new OpenTelemetryTracingModule(otel);
      Span clientSpan = tracer.spanBuilder("test-client-span").startSpan();
      CallAttemptsTracerFactory callTracer = tracingModule.newClientCallTracer(clientSpan, method);
      ClientStreamTracer attemptTracer =
          callTracer.newClientStreamTracer(STREAM_INFO, new Metadata());

      runRacing(
          () -> {
            attemptTracer.recordDelayStart("connecting", "waiting for subchannel");
            attemptTracer.recordDelayReasonChanged("connecting", "still waiting");
            attemptTracer.recordDelayStart("queued", "waiting for a pick");
          },
          () -> attemptTracer.streamClosed(Status.CANCELLED),
          failures);

      attemptTracer.streamClosed(Status.CANCELLED);
      callTracer.callEnded(Status.CANCELLED);
    }

    assertTrue("racing threads threw: " + failures, failures.isEmpty());
    assertEquals(
        "every started span must also be ended, otherwise a delay span leaked",
        balance.started.get(), balance.ended.get());
  }

  @Test
  public void clientDelay_concurrentTypeRollovers_neverLeakASpan() throws Exception {
    SpanBalanceProcessor balance = new SpanBalanceProcessor();
    OpenTelemetry otel = OpenTelemetrySdk.builder()
        .setTracerProvider(SdkTracerProvider.builder().addSpanProcessor(balance).build())
        .build();
    Tracer tracer = otel.getTracerProvider().get("grpc-java-test");
    List<Throwable> failures = Collections.synchronizedList(new ArrayList<Throwable>());

    // Two threads driving delay transitions at once: only one span may be published per epoch,
    // and every span the losing thread created must still be ended by that thread.
    for (int i = 0; i < 300; i++) {
      OpenTelemetryTracingModule tracingModule = new OpenTelemetryTracingModule(otel);
      Span clientSpan = tracer.spanBuilder("test-client-span").startSpan();
      CallAttemptsTracerFactory callTracer = tracingModule.newClientCallTracer(clientSpan, method);

      runRacing(
          () -> {
            callTracer.recordDelayStart("resolving", "waiting for DNS");
            callTracer.recordDelayEnd("resolving");
          },
          () -> {
            callTracer.recordDelayStart("connecting", "waiting for subchannel");
            callTracer.recordDelayEnd("connecting");
          },
          failures);

      callTracer.callEnded(Status.OK);
    }

    assertTrue("racing threads threw: " + failures, failures.isEmpty());
    assertEquals(
        "every started span must also be ended, otherwise a delay span leaked",
        balance.started.get(), balance.ended.get());
  }

  private static List<SpanData> delaySpans(List<SpanData> spans) {
    List<SpanData> delaySpans = new ArrayList<>();
    for (SpanData span : spans) {
      if ("Delay".equals(span.getName())) {
        delaySpans.add(span);
      }
    }
    return delaySpans;
  }

  private static SpanData delaySpanWithType(List<SpanData> delaySpans, String delayType) {
    for (SpanData span : delaySpans) {
      if (delayType.equals(span.getAttributes().get(OpenTelemetryConstants.DELAY_TYPE_KEY))) {
        return span;
      }
    }
    throw new AssertionError("No 'Delay' span with grpc.delay_type " + delayType);
  }

  private static SpanData spanWithName(List<SpanData> spans, String name) {
    for (SpanData span : spans) {
      if (name.equals(span.getName())) {
        return span;
      }
    }
    throw new AssertionError("No span named " + name);
  }

  /** Asserts that the span carries exactly one "Delay triggered" event with the given reason. */
  private static void assertDelayTriggeredEvent(SpanData delaySpan, String delayReason) {
    assertEquals(1, delaySpan.getEvents().size());
    EventData event = delaySpan.getEvents().get(0);
    assertEquals("Delay triggered", event.getName());
    assertEquals(delayReason, event.getAttributes().get(
        OpenTelemetryConstants.DELAY_REASON_KEY));
    // A121: the event carries only the reason; the type is an attribute of the span.
    assertNull(event.getAttributes().get(OpenTelemetryConstants.DELAY_TYPE_KEY));
  }
}
