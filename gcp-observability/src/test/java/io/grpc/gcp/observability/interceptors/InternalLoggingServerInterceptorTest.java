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

package io.grpc.gcp.observability.interceptors;

import static com.google.common.truth.Truth.assertThat;
import static io.grpc.gcp.observability.interceptors.LogHelperTest.BYTEARRAY_MARSHALLER;
import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.google.protobuf.Duration;
import com.google.protobuf.util.Durations;
import io.grpc.Attributes;
import io.grpc.Context;
import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.MethodDescriptor.MethodType;
import io.grpc.ServerCall;
import io.grpc.Status;
import io.grpc.gcp.observability.interceptors.ConfigFilterHelper.FilterParams;
import io.grpc.internal.NoopServerCall;
import io.grpc.observabilitylog.v1.GrpcLogRecord.EventLogger;
import io.grpc.observabilitylog.v1.GrpcLogRecord.EventType;
import io.opencensus.trace.SpanContext;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/**
 * Tests for {@link InternalLoggingServerInterceptor}.
 */
@RunWith(JUnit4.class)
public class InternalLoggingServerInterceptorTest {

  @Rule
  public final MockitoRule mockito = MockitoJUnit.rule();

  private static final Charset US_ASCII = StandardCharsets.US_ASCII;

  private InternalLoggingServerInterceptor.Factory factory;
  private AtomicReference<ServerCall<byte[], byte[]>> interceptedLoggingCall;
  ServerCall.Listener<byte[]> capturedListener;
  private ServerCall.Listener<byte[]> mockListener;
  // capture these manually because ServerCall can not be mocked
  private AtomicReference<Metadata> actualServerInitial;
  private AtomicReference<byte[]> actualResponse;
  private AtomicReference<Status> actualStatus;
  private AtomicReference<Metadata> actualTrailers;
  private LogHelper mockLogHelper;
  private ConfigFilterHelper mockFilterHelper;
  private SocketAddress peer;

  @Before
  @SuppressWarnings("unchecked")
  public void setup() throws Exception {
    mockLogHelper = mock(LogHelper.class);
    mockFilterHelper = mock(ConfigFilterHelper.class);
    factory = new InternalLoggingServerInterceptor.FactoryImpl(mockLogHelper, mockFilterHelper);
    interceptedLoggingCall = new AtomicReference<>();
    mockListener = mock(ServerCall.Listener.class);
    actualServerInitial = new AtomicReference<>();
    actualResponse = new AtomicReference<>();
    actualStatus = new AtomicReference<>();
    actualTrailers = new AtomicReference<>();
    peer = new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 1234);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void internalLoggingServerInterceptor() {
    Metadata clientInitial = new Metadata();
    final MethodDescriptor<byte[], byte[]> method =
        MethodDescriptor.<byte[], byte[]>newBuilder()
            .setType(MethodType.UNKNOWN)
            .setFullMethodName("service/method")
            .setRequestMarshaller(BYTEARRAY_MARSHALLER)
            .setResponseMarshaller(BYTEARRAY_MARSHALLER)
            .build();
    FilterParams filterParams = FilterParams.create(true, 0, 0);
    when(mockFilterHelper.logRpcMethod(method.getFullMethodName(), false)).thenReturn(filterParams);
    capturedListener =
        factory.create()
            .interceptCall(
                new NoopServerCall<byte[], byte[]>() {
                  @Override
                  public void sendHeaders(Metadata headers) {
                    actualServerInitial.set(headers);
                  }

                  @Override
                  public void sendMessage(byte[] message) {
                    actualResponse.set(message);
                  }

                  @Override
                  public void close(Status status, Metadata trailers) {
                    actualStatus.set(status);
                    actualTrailers.set(trailers);
                  }

                  @Override
                  public MethodDescriptor<byte[], byte[]> getMethodDescriptor() {
                    return method;
                  }

                  @Override
                  public Attributes getAttributes() {
                    return Attributes
                        .newBuilder()
                        .set(Grpc.TRANSPORT_ATTR_REMOTE_ADDR, peer)
                        .build();
                  }

                  @Override
                  public String getAuthority() {
                    return "the-authority";
                  }
                },
                clientInitial,
                (call, headers) -> {
                  interceptedLoggingCall.set(call);
                  return mockListener;
                });
    // receive request header
    {
      verify(mockLogHelper).logClientHeader(
          /*seq=*/ eq(1L),
          eq("service"),
          eq("method"),
          eq("the-authority"),
          ArgumentMatchers.isNull(),
          same(clientInitial),
          eq(filterParams.headerBytes()),
          eq(EventLogger.SERVER),
          anyString(),
          same(peer),
          eq(SpanContext.INVALID));
      verifyNoMoreInteractions(mockLogHelper);
    }

    reset(mockLogHelper);
    reset(mockListener);

    // send response header
    {
      Metadata serverInitial = new Metadata();
      interceptedLoggingCall.get().sendHeaders(serverInitial);
      verify(mockLogHelper).logServerHeader(
          /*seq=*/ eq(2L),
          eq("service"),
          eq("method"),
          eq("the-authority"),
          same(serverInitial),
          eq(filterParams.headerBytes()),
          eq(EventLogger.SERVER),
          anyString(),
          ArgumentMatchers.isNull(),
          eq(SpanContext.INVALID));
      verifyNoMoreInteractions(mockLogHelper);
      assertSame(serverInitial, actualServerInitial.get());
    }

    reset(mockLogHelper);
    reset(mockListener);

    // receive request message
    {
      byte[] request = "this is a request".getBytes(US_ASCII);
      capturedListener.onMessage(request);
      verify(mockLogHelper).logRpcMessage(
          /*seq=*/ eq(3L),
          eq("service"),
          eq("method"),
          eq("the-authority"),
          eq(EventType.CLIENT_MESSAGE),
          same(request),
          eq(filterParams.messageBytes()),
          eq(EventLogger.SERVER),
          anyString(),
          eq(SpanContext.INVALID));
      verifyNoMoreInteractions(mockLogHelper);
      verify(mockListener).onMessage(same(request));
    }

    reset(mockLogHelper);
    reset(mockListener);

    // client half close
    {
      capturedListener.onHalfClose();
      verify(mockLogHelper).logHalfClose(
          /*seq=*/ eq(4L),
          eq("service"),
          eq("method"),
          eq("the-authority"),
          eq(EventLogger.SERVER),
          anyString(),
          eq(SpanContext.INVALID));
      verifyNoMoreInteractions(mockLogHelper);
      verify(mockListener).onHalfClose();
    }

    reset(mockLogHelper);
    reset(mockListener);

    // send response message
    {
      byte[] response = "this is a response".getBytes(US_ASCII);
      interceptedLoggingCall.get().sendMessage(response);
      verify(mockLogHelper).logRpcMessage(
          /*seq=*/ eq(5L),
          eq("service"),
          eq("method"),
          eq("the-authority"),
          eq(EventType.SERVER_MESSAGE),
          same(response),
          eq(filterParams.messageBytes()),
          eq(EventLogger.SERVER),
          anyString(),
          eq(SpanContext.INVALID));
      verifyNoMoreInteractions(mockLogHelper);
      assertSame(response, actualResponse.get());
    }

    reset(mockLogHelper);
    reset(mockListener);

    // send trailer
    {
      Status status = Status.INTERNAL.withDescription("trailer description");
      Metadata trailers = new Metadata();
      interceptedLoggingCall.get().close(status, trailers);
      verify(mockLogHelper).logTrailer(
          /*seq=*/ eq(6L),
          eq("service"),
          eq("method"),
          eq("the-authority"),
          same(status),
          same(trailers),
          eq(filterParams.headerBytes()),
          eq(EventLogger.SERVER),
          anyString(),
          ArgumentMatchers.isNull(),
          eq(SpanContext.INVALID));
      verifyNoMoreInteractions(mockLogHelper);
      assertSame(status, actualStatus.get());
      assertSame(trailers, actualTrailers.get());
    }

    reset(mockLogHelper);
    reset(mockListener);

    // cancel
    {
      capturedListener.onCancel();
      verify(mockLogHelper).logCancel(
          /*seq=*/ eq(7L),
          eq("service"),
          eq("method"),
          eq("the-authority"),
          eq(EventLogger.SERVER),
          anyString(),
          eq(SpanContext.INVALID));
      verify(mockListener).onCancel();
    }
  }

  @Test
  public void serverDeadlineLogged() {
    final MethodDescriptor<byte[], byte[]> method =
        MethodDescriptor.<byte[], byte[]>newBuilder()
            .setType(MethodType.UNKNOWN)
            .setFullMethodName("service/method")
            .setRequestMarshaller(BYTEARRAY_MARSHALLER)
            .setResponseMarshaller(BYTEARRAY_MARSHALLER)
            .build();
    FilterParams filterParams = FilterParams.create(true, 0, 0);
    when(mockFilterHelper.logRpcMethod(method.getFullMethodName(), false)).thenReturn(filterParams);
    final ServerCall<byte[], byte[]> noopServerCall = new NoopServerCall<byte[], byte[]>() {
      @Override
      public MethodDescriptor<byte[], byte[]> getMethodDescriptor() {
        return method;
      }

      @Override
      public String getAuthority() {
        return "the-authority";
      }
    };

    // We expect the contents of the "grpc-timeout" header to be installed the context
    Context.current()
        .withDeadlineAfter(1, TimeUnit.SECONDS, Executors.newSingleThreadScheduledExecutor())
        .run(
            () -> {
              ServerCall.Listener<byte[]> unused =
                  factory.create()
                      .interceptCall(noopServerCall,
                          new Metadata(),
                          (call, headers) -> new ServerCall.Listener<byte[]>() {});
            });
    ArgumentCaptor<Duration> timeoutCaptor = ArgumentCaptor.forClass(Duration.class);
    verify(mockLogHelper, times(1))
        .logClientHeader(
        /*seq=*/ eq(1L),
        eq("service"),
        eq("method"),
        eq("the-authority"),
        timeoutCaptor.capture(),
        any(Metadata.class),
        eq(filterParams.headerBytes()),
        eq(EventLogger.SERVER),
        anyString(),
        ArgumentMatchers.isNull(),
            eq(SpanContext.INVALID));
    verifyNoMoreInteractions(mockLogHelper);
    Duration timeout = timeoutCaptor.getValue();
    assertThat(Math.abs(TimeUnit.SECONDS.toNanos(1) - Durations.toNanos(timeout)))
        .isAtMost(TimeUnit.MILLISECONDS.toNanos(250));
  }

  @Test
  public void serverMethodOrServiceFilter_disabled() {
    Metadata clientInitial = new Metadata();
    final MethodDescriptor<byte[], byte[]> method =
        MethodDescriptor.<byte[], byte[]>newBuilder()
            .setType(MethodType.UNKNOWN)
            .setFullMethodName("service/method")
            .setRequestMarshaller(BYTEARRAY_MARSHALLER)
            .setResponseMarshaller(BYTEARRAY_MARSHALLER)
            .build();
    when(mockFilterHelper.logRpcMethod(method.getFullMethodName(), false))
        .thenReturn(FilterParams.create(false, 0, 0));
    capturedListener =
        factory.create()
            .interceptCall(
                new NoopServerCall<byte[], byte[]>() {
                  @Override
                  public void sendHeaders(Metadata headers) {
                    actualServerInitial.set(headers);
                  }

                  @Override
                  public void sendMessage(byte[] message) {
                    actualResponse.set(message);
                  }

                  @Override
                  public void close(Status status, Metadata trailers) {
                    actualStatus.set(status);
                    actualTrailers.set(trailers);
                  }

                  @Override
                  public MethodDescriptor<byte[], byte[]> getMethodDescriptor() {
                    return method;
                  }

                  @Override
                  public Attributes getAttributes() {
                    return Attributes
                        .newBuilder()
                        .set(Grpc.TRANSPORT_ATTR_REMOTE_ADDR, peer)
                        .build();
                  }

                  @Override
                  public String getAuthority() {
                    return "the-authority";
                  }
                },
                clientInitial,
                (call, headers) -> {
                  interceptedLoggingCall.set(call);
                  return mockListener;
                });
    verifyNoInteractions(mockLogHelper);
  }

  @Test
  public void serverMethodOrServiceFilter_enabled() {
    Metadata clientInitial = new Metadata();
    final MethodDescriptor<byte[], byte[]> method =
        MethodDescriptor.<byte[], byte[]>newBuilder()
            .setType(MethodType.UNKNOWN)
            .setFullMethodName("service/method")
            .setRequestMarshaller(BYTEARRAY_MARSHALLER)
            .setResponseMarshaller(BYTEARRAY_MARSHALLER)
            .build();
    when(mockFilterHelper.logRpcMethod(method.getFullMethodName(), false))
        .thenReturn(FilterParams.create(true, 10, 10));

    capturedListener =
        factory.create()
            .interceptCall(
                new NoopServerCall<byte[], byte[]>() {
                  @Override
                  public void sendHeaders(Metadata headers) {
                    actualServerInitial.set(headers);
                  }

                  @Override
                  public void sendMessage(byte[] message) {
                    actualResponse.set(message);
                  }

                  @Override
                  public void close(Status status, Metadata trailers) {
                    actualStatus.set(status);
                    actualTrailers.set(trailers);
                  }

                  @Override
                  public MethodDescriptor<byte[], byte[]> getMethodDescriptor() {
                    return method;
                  }

                  @Override
                  public Attributes getAttributes() {
                    return Attributes
                        .newBuilder()
                        .set(Grpc.TRANSPORT_ATTR_REMOTE_ADDR, peer)
                        .build();
                  }

                  @Override
                  public String getAuthority() {
                    return "the-authority";
                  }
                },
                clientInitial,
                (call, headers) -> {
                  interceptedLoggingCall.set(call);
                  return mockListener;
                });

    {
      interceptedLoggingCall.get().sendHeaders(new Metadata());
      byte[] request = "this is a request".getBytes(US_ASCII);
      capturedListener.onMessage(request);
      capturedListener.onHalfClose();
      byte[] response = "this is a response".getBytes(US_ASCII);
      interceptedLoggingCall.get().sendMessage(response);
      Status status = Status.INTERNAL.withDescription("trailer description");
      Metadata trailers = new Metadata();
      interceptedLoggingCall.get().close(status, trailers);
      capturedListener.onCancel();
      assertThat(Mockito.mockingDetails(mockLogHelper).getInvocations().size()).isEqualTo(7);
    }
  }
}
