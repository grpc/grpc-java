/*
 * Copyright 2016 The gRPC Authors
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

package io.grpc.internal;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.Attributes;
import io.grpc.CallCredentials;
import io.grpc.CallCredentials.RequestInfo;
import io.grpc.CallOptions;
import io.grpc.ChannelLogger;
import io.grpc.ClientStreamTracer;
import io.grpc.IntegerMarshaller;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.SecurityLevel;
import io.grpc.Status;
import io.grpc.Status.Code;
import io.grpc.StringMarshaller;
import java.net.SocketAddress;
import java.util.concurrent.Executor;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.mockito.stubbing.Answer;

/**
 * Unit test for {@link CallCredentials} applying functionality implemented by {@link
 * CallCredentialsApplyingTransportFactory} and {@link MetadataApplierImpl}.
 */
@RunWith(JUnit4.class)
public class CallCredentialsApplyingTest {
  @Rule
  public final MockitoRule mocks = MockitoJUnit.rule();

  @Mock
  private ClientTransportFactory mockTransportFactory;

  @Mock
  private ConnectionClientTransport mockTransport;

  @Mock
  private ClientStream mockStream;

  @Mock
  private CallCredentials mockCreds;

  @Mock
  private Executor mockExecutor;

  @Mock
  private SocketAddress address;

  // Noop logger;
  @Mock
  private ChannelLogger channelLogger;

  private static final ClientStreamTracer[] tracers = new ClientStreamTracer[] {
      new ClientStreamTracer() {}
  };
  private static final String AUTHORITY = "testauthority";
  private static final String USER_AGENT = "testuseragent";
  private static final Attributes.Key<String> ATTR_KEY = Attributes.Key.create("somekey");
  private static final String ATTR_VALUE = "somevalue";
  private static final MethodDescriptor<String, Integer> method =
      MethodDescriptor.<String, Integer>newBuilder()
          .setType(MethodDescriptor.MethodType.UNKNOWN)
          .setFullMethodName("service/method")
          .setRequestMarshaller(new StringMarshaller())
          .setResponseMarshaller(new IntegerMarshaller())
          .build();
  private static final Metadata.Key<String> ORIG_HEADER_KEY =
      Metadata.Key.of("header1", Metadata.ASCII_STRING_MARSHALLER);
  private static final String ORIG_HEADER_VALUE = "some original header value";
  private static final Metadata.Key<String> CREDS_KEY =
      Metadata.Key.of("test-creds", Metadata.ASCII_STRING_MARSHALLER);
  private static final String CREDS_VALUE = "some credentials";

  private final ClientTransportFactory.ClientTransportOptions clientTransportOptions =
        new ClientTransportFactory.ClientTransportOptions()
          .setAuthority(AUTHORITY)
          .setUserAgent(USER_AGENT);
  private final Metadata origHeaders = new Metadata();
  private ForwardingConnectionClientTransport transport;
  private CallOptions callOptions;

  @Before
  public void setUp() {
    origHeaders.put(ORIG_HEADER_KEY, ORIG_HEADER_VALUE);
    when(mockTransportFactory.newClientTransport(address, clientTransportOptions, channelLogger))
        .thenReturn(mockTransport);
    when(mockTransport.newStream(
            same(method), any(Metadata.class), any(CallOptions.class),
            ArgumentMatchers.<ClientStreamTracer[]>any()))
        .thenReturn(mockStream);
    ClientTransportFactory transportFactory = new CallCredentialsApplyingTransportFactory(
        mockTransportFactory, null, mockExecutor);
    transport = (ForwardingConnectionClientTransport)
        transportFactory.newClientTransport(address, clientTransportOptions, channelLogger);
    callOptions = CallOptions.DEFAULT.withCallCredentials(mockCreds);
    verify(mockTransportFactory).newClientTransport(address, clientTransportOptions, channelLogger);
    assertSame(mockTransport, transport.delegate());
  }

  @Test
  public void parameterPropagation_base() {
    Attributes transportAttrs = Attributes.newBuilder().set(ATTR_KEY, ATTR_VALUE).build();
    when(mockTransport.getAttributes()).thenReturn(transportAttrs);

    transport.newStream(method, origHeaders, callOptions, tracers);

    ArgumentCaptor<RequestInfo> infoCaptor = ArgumentCaptor.forClass(RequestInfo.class);
    verify(mockCreds).applyRequestMetadata(infoCaptor.capture(), same(mockExecutor),
        any(CallCredentials.MetadataApplier.class));
    RequestInfo info = infoCaptor.getValue();
    assertSame(transportAttrs, info.getTransportAttrs());
    assertSame(method, info.getMethodDescriptor());
    assertSame(callOptions, info.getCallOptions());
    assertSame(AUTHORITY, info.getAuthority());
    assertSame(SecurityLevel.NONE, info.getSecurityLevel());
  }

  @Test
  public void parameterPropagation_overrideByCallOptions() {
    Attributes transportAttrs = Attributes.newBuilder()
        .set(ATTR_KEY, ATTR_VALUE)
        .set(GrpcAttributes.ATTR_SECURITY_LEVEL, SecurityLevel.INTEGRITY)
        .build();
    when(mockTransport.getAttributes()).thenReturn(transportAttrs);
    Executor anotherExecutor = mock(Executor.class);

    transport.newStream(
        method, origHeaders,
        callOptions.withAuthority("calloptions-authority").withExecutor(anotherExecutor),
        tracers);

    ArgumentCaptor<RequestInfo> infoCaptor = ArgumentCaptor.forClass(RequestInfo.class);
    verify(mockCreds).applyRequestMetadata(infoCaptor.capture(),
        same(mockExecutor), any(CallCredentials.MetadataApplier.class));
    RequestInfo info = infoCaptor.getValue();
    assertSame(transportAttrs, info.getTransportAttrs());
    assertSame(method, info.getMethodDescriptor());
    assertEquals("calloptions-authority", info.getAuthority());
    assertSame(SecurityLevel.INTEGRITY, info.getSecurityLevel());
  }

  @Test
  public void parameterPropagation_transportSetSecurityLevel() {
    Attributes transportAttrs = Attributes.newBuilder()
            .set(ATTR_KEY, ATTR_VALUE)
            .set(GrpcAttributes.ATTR_SECURITY_LEVEL, SecurityLevel.INTEGRITY)
            .build();
    when(mockTransport.getAttributes()).thenReturn(transportAttrs);

    transport.newStream(method, origHeaders, callOptions, tracers);

    ArgumentCaptor<RequestInfo> infoCaptor = ArgumentCaptor.forClass(RequestInfo.class);
    verify(mockCreds).applyRequestMetadata(
            infoCaptor.capture(), same(mockExecutor),
            any(io.grpc.CallCredentials.MetadataApplier.class));
    RequestInfo info = infoCaptor.getValue();
    assertSame(method, info.getMethodDescriptor());
    assertSame(ATTR_VALUE, info.getTransportAttrs().get(ATTR_KEY));
    assertSame(AUTHORITY, info.getAuthority());
    assertSame(SecurityLevel.INTEGRITY, info.getSecurityLevel());
  }

  @Test
  public void parameterPropagation_callOptionsSetAuthority() {
    Attributes transportAttrs = Attributes.newBuilder()
            .set(ATTR_KEY, ATTR_VALUE)
            .build();
    when(mockTransport.getAttributes()).thenReturn(transportAttrs);
    Executor anotherExecutor = mock(Executor.class);

    transport.newStream(
            method, origHeaders,
            callOptions.withAuthority("calloptions-authority").withExecutor(anotherExecutor),
            tracers);

    ArgumentCaptor<RequestInfo> infoCaptor = ArgumentCaptor.forClass(RequestInfo.class);
    verify(mockCreds).applyRequestMetadata(
            infoCaptor.capture(), same(mockExecutor),
            any(io.grpc.CallCredentials.MetadataApplier.class));
    RequestInfo info = infoCaptor.getValue();
    assertSame(method, info.getMethodDescriptor());
    assertSame(ATTR_VALUE, info.getTransportAttrs().get(ATTR_KEY));
    assertEquals("calloptions-authority", info.getAuthority());
    assertSame(SecurityLevel.NONE, info.getSecurityLevel());
  }

  @Test
  public void credentialThrows() {
    final RuntimeException ex = new RuntimeException();
    when(mockTransport.getAttributes()).thenReturn(Attributes.EMPTY);
    doThrow(ex).when(mockCreds).applyRequestMetadata(
        any(RequestInfo.class), same(mockExecutor),
        any(CallCredentials.MetadataApplier.class));

    FailingClientStream stream = (FailingClientStream) transport.newStream(
        method, origHeaders, callOptions, tracers);

    verify(mockTransport, never()).newStream(
        any(MethodDescriptor.class), any(Metadata.class), any(CallOptions.class),
        ArgumentMatchers.<ClientStreamTracer[]>any());
    assertEquals(Status.Code.UNAUTHENTICATED, stream.getError().getCode());
    assertSame(ex, stream.getError().getCause());

    transport.shutdown(Status.UNAVAILABLE);
    assertTrue(transport.newStream(method, origHeaders, callOptions, tracers)
        instanceof FailingClientStream);
    verify(mockTransport).shutdown(Status.UNAVAILABLE);
  }

  @Test
  public void applyMetadata_inline() {
    when(mockTransport.getAttributes()).thenReturn(Attributes.EMPTY);

    callOptions = callOptions.withCallCredentials(new FakeCallCredentials(CREDS_KEY, CREDS_VALUE));
    ClientStream stream = transport.newStream(
        method, origHeaders, callOptions, tracers);

    verify(mockTransport).newStream(method, origHeaders, callOptions, tracers);
    assertSame(mockStream, stream);
    assertEquals(CREDS_VALUE, origHeaders.get(CREDS_KEY));
    assertEquals(ORIG_HEADER_VALUE, origHeaders.get(ORIG_HEADER_KEY));
    transport.shutdown(Status.UNAVAILABLE);
    assertTrue(transport.newStream(method, origHeaders, callOptions, tracers)
        instanceof FailingClientStream);
    verify(mockTransport).shutdown(Status.UNAVAILABLE);
  }

  @Test
  public void fail_inline() {
    final Status error = Status.UNAVAILABLE.withDescription("channel not secure for creds");
    when(mockTransport.getAttributes()).thenReturn(Attributes.EMPTY);
    doAnswer(new Answer<Void>() {
        @Override
        public Void answer(InvocationOnMock invocation) throws Throwable {
          CallCredentials.MetadataApplier applier =
              (CallCredentials.MetadataApplier) invocation.getArguments()[2];
          applier.fail(error);
          return null;
        }
      }).when(mockCreds).applyRequestMetadata(any(RequestInfo.class),
          same(mockExecutor), any(CallCredentials.MetadataApplier.class));

    FailingClientStream stream = (FailingClientStream) transport.newStream(
        method, origHeaders, callOptions, tracers);

    verify(mockTransport, never()).newStream(
        any(MethodDescriptor.class), any(Metadata.class), any(CallOptions.class),
        ArgumentMatchers.<ClientStreamTracer[]>any());
    assertSame(error, stream.getError());
    transport.shutdownNow(Status.UNAVAILABLE);
    assertTrue(transport.newStream(method, origHeaders, callOptions, tracers)
        instanceof FailingClientStream);
    verify(mockTransport).shutdownNow(Status.UNAVAILABLE);
  }

  // If the creds return an error that is inappropriate to directly propagate from the control plane
  // to the call, it should be converted to an INTERNAL error.
  @Test
  public void fail_inline_inappropriate_error() {
    final Status error = Status.NOT_FOUND.withDescription("channel not secure for creds");
    when(mockTransport.getAttributes()).thenReturn(Attributes.EMPTY);
    doAnswer(new Answer<Void>() {
      @Override
      public Void answer(InvocationOnMock invocation) throws Throwable {
        CallCredentials.MetadataApplier applier =
            (CallCredentials.MetadataApplier) invocation.getArguments()[2];
        applier.fail(error);
        return null;
      }
    }).when(mockCreds).applyRequestMetadata(any(RequestInfo.class),
        same(mockExecutor), any(CallCredentials.MetadataApplier.class));

    FailingClientStream stream = (FailingClientStream) transport.newStream(
        method, origHeaders, callOptions, tracers);

    verify(mockTransport, never()).newStream(
        any(MethodDescriptor.class), any(Metadata.class), any(CallOptions.class),
        ArgumentMatchers.<ClientStreamTracer[]>any());
    assertThat(stream.getError().getCode()).isEqualTo(Code.INTERNAL);
    assertThat(stream.getError().getDescription()).contains("Inappropriate");
    assertThat(stream.getError().getCause()).isNull();
    transport.shutdownNow(Status.UNAVAILABLE);
    assertTrue(transport.newStream(method, origHeaders, callOptions, tracers)
        instanceof FailingClientStream);
    verify(mockTransport).shutdownNow(Status.UNAVAILABLE);
  }

  @Test
  public void applyMetadata_delayed() {
    when(mockTransport.getAttributes()).thenReturn(Attributes.EMPTY);

    // Will call applyRequestMetadata(), which is no-op.
    DelayedStream stream = (DelayedStream) transport.newStream(
        method, origHeaders, callOptions, tracers);

    ArgumentCaptor<CallCredentials.MetadataApplier> applierCaptor =
        ArgumentCaptor.forClass(CallCredentials.MetadataApplier.class);
    verify(mockCreds).applyRequestMetadata(any(RequestInfo.class),
        same(mockExecutor), applierCaptor.capture());
    verify(mockTransport, never()).newStream(
        any(MethodDescriptor.class), any(Metadata.class), any(CallOptions.class),
        ArgumentMatchers.<ClientStreamTracer[]>any());

    transport.shutdown(Status.UNAVAILABLE);
    verify(mockTransport, never()).shutdown(Status.UNAVAILABLE);
    assertTrue(transport.newStream(method, origHeaders, callOptions, tracers)
        instanceof FailingClientStream);

    Metadata headers = new Metadata();
    headers.put(CREDS_KEY, CREDS_VALUE);
    applierCaptor.getValue().apply(headers);

    verify(mockTransport).newStream(method, origHeaders, callOptions, tracers);
    assertSame(mockStream, stream.getRealStream());
    assertEquals(CREDS_VALUE, origHeaders.get(CREDS_KEY));
    assertEquals(ORIG_HEADER_VALUE, origHeaders.get(ORIG_HEADER_KEY));
    verify(mockTransport).shutdown(Status.UNAVAILABLE);
  }

  @Test
  public void delayedShutdown_shutdownShutdownNowThenApply() {
    transport.newStream(method, origHeaders, callOptions, tracers);
    ArgumentCaptor<CallCredentials.MetadataApplier> applierCaptor =
        ArgumentCaptor.forClass(CallCredentials.MetadataApplier.class);
    verify(mockCreds).applyRequestMetadata(any(RequestInfo.class),
        same(mockExecutor), applierCaptor.capture());
    transport.shutdown(Status.UNAVAILABLE);
    transport.shutdownNow(Status.ABORTED);
    assertTrue(transport.newStream(method, origHeaders, callOptions, tracers)
        instanceof FailingClientStream);
    verify(mockTransport, never()).shutdown(any(Status.class));
    verify(mockTransport, never()).shutdownNow(any(Status.class));
    Metadata headers = new Metadata();
    headers.put(CREDS_KEY, CREDS_VALUE);
    applierCaptor.getValue().apply(headers);
    assertTrue(transport.newStream(method, origHeaders, callOptions, tracers)
        instanceof FailingClientStream);
    verify(mockTransport).shutdown(Status.UNAVAILABLE);
    verify(mockTransport).shutdownNow(Status.ABORTED);
  }

  @Test
  public void delayedShutdown_shutdownThenApplyThenShutdownNow() {
    transport.newStream(method, origHeaders, callOptions, tracers);
    ArgumentCaptor<CallCredentials.MetadataApplier> applierCaptor =
        ArgumentCaptor.forClass(CallCredentials.MetadataApplier.class);
    verify(mockCreds).applyRequestMetadata(any(RequestInfo.class),
        same(mockExecutor), applierCaptor.capture());
    transport.shutdown(Status.UNAVAILABLE);
    assertTrue(transport.newStream(method, origHeaders, callOptions, tracers)
        instanceof FailingClientStream);
    verify(mockTransport, never()).shutdown(any(Status.class));
    Metadata headers = new Metadata();
    headers.put(CREDS_KEY, CREDS_VALUE);
    applierCaptor.getValue().apply(headers);
    verify(mockTransport).shutdown(Status.UNAVAILABLE);
    transport.shutdownNow(Status.ABORTED);
    verify(mockTransport).shutdownNow(Status.ABORTED);

    transport.shutdown(Status.UNAVAILABLE);
    verify(mockTransport).shutdown(Status.UNAVAILABLE);
    transport.shutdownNow(Status.ABORTED);
    verify(mockTransport, times(2)).shutdownNow(Status.ABORTED);
  }

  @Test
  public void delayedShutdown_shutdownMulti() {
    Metadata headers = new Metadata();
    headers.put(CREDS_KEY, CREDS_VALUE);

    transport.newStream(method, origHeaders, callOptions, tracers);
    transport.newStream(method, origHeaders, callOptions, tracers);
    transport.newStream(method, origHeaders, callOptions, tracers);
    ArgumentCaptor<CallCredentials.MetadataApplier> applierCaptor =
        ArgumentCaptor.forClass(CallCredentials.MetadataApplier.class);
    verify(mockCreds, times(3)).applyRequestMetadata(any(RequestInfo.class),
        same(mockExecutor), applierCaptor.capture());
    applierCaptor.getAllValues().get(1).apply(headers);
    transport.shutdown(Status.UNAVAILABLE);
    assertTrue(transport.newStream(method, origHeaders, callOptions, tracers)
        instanceof FailingClientStream);
    verify(mockTransport, never()).shutdown(Status.UNAVAILABLE);

    applierCaptor.getAllValues().get(0).apply(headers);
    assertTrue(transport.newStream(method, origHeaders, callOptions, tracers)
        instanceof FailingClientStream);
    verify(mockTransport, never()).shutdown(Status.UNAVAILABLE);

    applierCaptor.getAllValues().get(2).apply(headers);
    assertTrue(transport.newStream(method, origHeaders, callOptions, tracers)
        instanceof FailingClientStream);
    verify(mockTransport).shutdown(Status.UNAVAILABLE);
  }

  @Test
  public void fail_delayed() {
    when(mockTransport.getAttributes()).thenReturn(Attributes.EMPTY);

    // Will call applyRequestMetadata(), which is no-op.
    DelayedStream stream = (DelayedStream) transport.newStream(
        method, origHeaders, callOptions, tracers);

    ArgumentCaptor<CallCredentials.MetadataApplier> applierCaptor =
        ArgumentCaptor.forClass(CallCredentials.MetadataApplier.class);
    verify(mockCreds).applyRequestMetadata(any(RequestInfo.class),
        same(mockExecutor), applierCaptor.capture());

    Status error = Status.UNAVAILABLE.withDescription("channel not secure for creds");
    applierCaptor.getValue().fail(error);

    verify(mockTransport, never()).newStream(
        any(MethodDescriptor.class), any(Metadata.class), any(CallOptions.class),
        ArgumentMatchers.<ClientStreamTracer[]>any());
    FailingClientStream failingStream = (FailingClientStream) stream.getRealStream();
    assertSame(error, failingStream.getError());
    transport.shutdown(Status.UNAVAILABLE);
    assertTrue(transport.newStream(method, origHeaders, callOptions, tracers)
        instanceof FailingClientStream);
    verify(mockTransport).shutdown(Status.UNAVAILABLE);
  }

  @Test
  public void noCreds() {
    callOptions = callOptions.withCallCredentials(null);
    ClientStream stream = transport.newStream(
        method, origHeaders, callOptions, tracers);

    verify(mockTransport).newStream(method, origHeaders, callOptions, tracers);
    assertSame(mockStream, stream);
    assertNull(origHeaders.get(CREDS_KEY));
    assertEquals(ORIG_HEADER_VALUE, origHeaders.get(ORIG_HEADER_KEY));
    transport.shutdown(Status.UNAVAILABLE);
    assertTrue(transport.newStream(method, origHeaders, callOptions, tracers)
        instanceof FailingClientStream);
    verify(mockTransport).shutdown(Status.UNAVAILABLE);
  }

  @Test
  public void justCallOptionCreds() {
    callOptions = callOptions.withCallCredentials(new FakeCallCredentials(CREDS_KEY, CREDS_VALUE));

    ClientStream stream = transport.newStream(
        method, origHeaders, callOptions, tracers);

    assertSame(mockStream, stream);
    assertEquals(CREDS_VALUE, origHeaders.get(CREDS_KEY));
    assertEquals(ORIG_HEADER_VALUE, origHeaders.get(ORIG_HEADER_KEY));
  }

  @Test
  public void justChannelCreds() {
    ClientTransportFactory transportFactory = new CallCredentialsApplyingTransportFactory(
        mockTransportFactory, new FakeCallCredentials(CREDS_KEY, CREDS_VALUE), mockExecutor);
    transport = (ForwardingConnectionClientTransport)
        transportFactory.newClientTransport(address, clientTransportOptions, channelLogger);
    callOptions = callOptions.withCallCredentials(null);

    ClientStream stream = transport.newStream(
        method, origHeaders, callOptions, tracers);

    assertSame(mockStream, stream);
    assertEquals(CREDS_VALUE, origHeaders.get(CREDS_KEY));
    assertEquals(ORIG_HEADER_VALUE, origHeaders.get(ORIG_HEADER_KEY));
  }

  @Test
  public void callOptionAndChanelCreds() {
    ClientTransportFactory transportFactory = new CallCredentialsApplyingTransportFactory(
        mockTransportFactory, new FakeCallCredentials(CREDS_KEY, CREDS_VALUE), mockExecutor);
    transport = (ForwardingConnectionClientTransport)
        transportFactory.newClientTransport(address, clientTransportOptions, channelLogger);
    Metadata.Key<String> creds2Key =
        Metadata.Key.of("test-creds2", Metadata.ASCII_STRING_MARSHALLER);
    String creds2Value = "some more credentials";
    callOptions = callOptions.withCallCredentials(new FakeCallCredentials(creds2Key, creds2Value));

    ClientStream stream = transport.newStream(
        method, origHeaders, callOptions, tracers);

    assertSame(mockStream, stream);
    assertEquals(CREDS_VALUE, origHeaders.get(CREDS_KEY));
    assertEquals(creds2Value, origHeaders.get(creds2Key));
    assertEquals(ORIG_HEADER_VALUE, origHeaders.get(ORIG_HEADER_KEY));
  }
}
