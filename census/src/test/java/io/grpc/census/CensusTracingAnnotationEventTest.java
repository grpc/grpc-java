/*
 * Copyright 2022 The gRPC Authors
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
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import io.grpc.Attributes;
import io.grpc.ClientStreamTracer;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerStreamTracer;
import io.grpc.Status;
import io.grpc.census.CensusTracingModule.CallAttemptsTracerFactory;
import io.grpc.internal.testing.StatsTestUtils.FakeStatsRecorder;
import io.grpc.internal.testing.StatsTestUtils.MockableSpan;
import io.grpc.testing.GrpcServerRule;
import io.opencensus.trace.AttributeValue;
import io.opencensus.trace.MessageEvent;
import io.opencensus.trace.Span;
import io.opencensus.trace.SpanBuilder;
import io.opencensus.trace.SpanContext;
import io.opencensus.trace.Tracer;
import io.opencensus.trace.propagation.BinaryFormat;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Random;
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
 * Test for {@link CensusTracingModule}.
 */
@RunWith(JUnit4.class)
public class CensusTracingAnnotationEventTest {
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

  private final FakeStatsRecorder statsRecorder = new FakeStatsRecorder();
  private final Random random = new Random(1234);
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

  @Captor
  private ArgumentCaptor<MessageEvent> messageEventCaptor;
  @Captor
  private ArgumentCaptor<Map<String, AttributeValue>> annotationAttributesCaptor;
  private ArgumentCaptor<String> stringCaptor;

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
    stringCaptor = ArgumentCaptor.forClass(String.class);

    censusTracing = new CensusTracingModule(tracer, mockTracingPropagationHandler);
  }

  @After
  public void wrapUp() {
    assertNull(statsRecorder.pollRecord());
  }

  @Test
  public void clientBasicTracingUncompressedSizeAnnotation() {
    CallAttemptsTracerFactory callTracer =
        censusTracing.newClientCallTracer(spyClientSpan, method);
    Metadata headers = new Metadata();
    ClientStreamTracer clientStreamTracer = callTracer.newClientStreamTracer(STREAM_INFO, headers);
    clientStreamTracer.streamCreated(Attributes.EMPTY, headers);

    clientStreamTracer.outboundMessage(0);
    clientStreamTracer.outboundMessageSent(0, 882, -1);
    clientStreamTracer.inboundMessage(0);
    clientStreamTracer.outboundMessage(1);
    clientStreamTracer.outboundMessageSent(1, -1, 27);
    clientStreamTracer.inboundMessageRead(0, 255, 90);
    clientStreamTracer.inboundUncompressedSize(90);
    clientStreamTracer.inboundMessage(1);
    clientStreamTracer.inboundMessageRead(1, 128, 60);
    clientStreamTracer.inboundUncompressedSize(60);

    clientStreamTracer.streamClosed(Status.OK);
    callTracer.callEnded(Status.OK);

    InOrder inOrder = inOrder(spyClientSpan, spyAttemptSpan);
    inOrder.verify(spyAttemptSpan, times(2)).addMessageEvent(messageEventCaptor.capture());
    List<MessageEvent> events = messageEventCaptor.getAllValues();
    assertEquals(
        MessageEvent.builder(MessageEvent.Type.SENT, 0).setCompressedMessageSize(882).build(),
        events.get(0));
    assertEquals(
        MessageEvent.builder(MessageEvent.Type.SENT, 1).setUncompressedMessageSize(27).build(),
        events.get(1));

    inOrder
        .verify(spyAttemptSpan, times(1))
        .addAnnotation(stringCaptor.capture(), annotationAttributesCaptor.capture());
    assertEquals("↘ 255 bytes received", stringCaptor.getValue());
    assertThat(annotationAttributesCaptor.getValue().get("id"))
        .isEqualTo(AttributeValue.longAttributeValue(0));
    assertThat(annotationAttributesCaptor.getValue().get("type"))
        .isEqualTo(AttributeValue.stringAttributeValue("compressed"));

    inOrder
        .verify(spyClientSpan, times(1))
        .addAnnotation(stringCaptor.capture(), annotationAttributesCaptor.capture());
    assertEquals("↘ 90 bytes received", stringCaptor.getValue());
    assertThat(annotationAttributesCaptor.getValue().get("id"))
        .isEqualTo(AttributeValue.longAttributeValue(0));
    assertThat(annotationAttributesCaptor.getValue().get("type"))
        .isEqualTo(AttributeValue.stringAttributeValue("uncompressed"));

    inOrder
        .verify(spyAttemptSpan, times(1))
        .addAnnotation(stringCaptor.capture(), annotationAttributesCaptor.capture());
    assertEquals("↘ 128 bytes received", stringCaptor.getValue());
    assertThat(annotationAttributesCaptor.getValue().get("id"))
        .isEqualTo(AttributeValue.longAttributeValue(1));
    assertThat(annotationAttributesCaptor.getValue().get("type"))
        .isEqualTo(AttributeValue.stringAttributeValue("compressed"));

    inOrder
        .verify(spyClientSpan, times(1))
        .addAnnotation(stringCaptor.capture(), annotationAttributesCaptor.capture());
    assertEquals("↘ 60 bytes received", stringCaptor.getValue());
    assertThat(annotationAttributesCaptor.getValue().get("id"))
        .isEqualTo(AttributeValue.longAttributeValue(1));
    assertThat(annotationAttributesCaptor.getValue().get("type"))
        .isEqualTo(AttributeValue.stringAttributeValue("uncompressed"));
  }

  @Test
  public void serverBasicTracingUncompressedSizeAnnotation() {
    ServerStreamTracer.Factory tracerFactory = censusTracing.getServerTracerFactory();
    ServerStreamTracer serverStreamTracer =
        tracerFactory.newServerStreamTracer(method.getFullMethodName(), new Metadata());

    serverStreamTracer.serverCallStarted(
        new CensusModulesTest.CallInfo<>(method, Attributes.EMPTY, null));

    serverStreamTracer.outboundMessage(0);
    serverStreamTracer.outboundMessageSent(0, 882, -1);
    serverStreamTracer.inboundMessage(0);
    serverStreamTracer.outboundMessage(1);
    serverStreamTracer.outboundMessageSent(1, -1, 27);
    serverStreamTracer.inboundMessageRead(0, 255, 90);
    serverStreamTracer.inboundUncompressedSize(90);

    serverStreamTracer.streamClosed(Status.CANCELLED);

    InOrder inOrder = inOrder(spyServerSpan);
    inOrder.verify(spyServerSpan, times(2)).addMessageEvent(messageEventCaptor.capture());
    List<MessageEvent> events = messageEventCaptor.getAllValues();
    assertEquals(
        MessageEvent.builder(MessageEvent.Type.SENT, 0).setCompressedMessageSize(882).build(),
        events.get(0));
    assertEquals(
        MessageEvent.builder(MessageEvent.Type.SENT, 1).setUncompressedMessageSize(27).build(),
        events.get(1));

    inOrder
        .verify(spyServerSpan, times(2))
        .addAnnotation(stringCaptor.capture(), annotationAttributesCaptor.capture());
    List<String> annotationDescriptions = stringCaptor.getAllValues();
    List<Map<String, AttributeValue>> annotationAttributes =
        annotationAttributesCaptor.getAllValues();

    assertEquals("↘ 255 bytes received", annotationDescriptions.get(0));
    assertThat(annotationAttributes.get(0).get("id"))
        .isEqualTo(AttributeValue.longAttributeValue(0));
    assertThat(annotationAttributes.get(0).get("type"))
        .isEqualTo(AttributeValue.stringAttributeValue("compressed"));

    assertEquals("↘ 90 bytes received", annotationDescriptions.get(1));
    assertThat(annotationAttributes.get(1).get("id"))
        .isEqualTo(AttributeValue.longAttributeValue(0));
    assertThat(annotationAttributes.get(1).get("type"))
        .isEqualTo(AttributeValue.stringAttributeValue("uncompressed"));
  }
}
