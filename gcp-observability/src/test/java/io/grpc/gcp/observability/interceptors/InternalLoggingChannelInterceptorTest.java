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
import static io.grpc.census.internal.ObservabilityCensusConstants.CLIENT_TRACE_SPAN_CONTEXT_KEY;
import static io.grpc.gcp.observability.interceptors.LogHelperTest.BYTEARRAY_MARSHALLER;
import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
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

import com.google.common.util.concurrent.SettableFuture;
import com.google.protobuf.Duration;
import com.google.protobuf.util.Durations;
import io.grpc.Attributes;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.Context;
import io.grpc.Deadline;
import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.MethodDescriptor.MethodType;
import io.grpc.Status;
import io.grpc.gcp.observability.interceptors.ConfigFilterHelper.FilterParams;
import io.grpc.internal.NoopClientCall;
import io.grpc.observabilitylog.v1.GrpcLogRecord;
import io.grpc.observabilitylog.v1.GrpcLogRecord.EventLogger;
import io.grpc.observabilitylog.v1.GrpcLogRecord.EventType;
import io.opencensus.trace.SpanContext;
import io.opencensus.trace.SpanId;
import io.opencensus.trace.TraceId;
import io.opencensus.trace.TraceOptions;
import io.opencensus.trace.Tracestate;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.AdditionalMatchers;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/**
 * Tests for {@link InternalLoggingChannelInterceptor}.
 */
@RunWith(JUnit4.class)
public class InternalLoggingChannelInterceptorTest {

  @Rule
  public final MockitoRule mockito = MockitoJUnit.rule();

  private static final Charset US_ASCII = StandardCharsets.US_ASCII;
  private static final SpanContext DEFAULT_CLIENT_SPAN_CONTEXT = SpanContext.INVALID;
  private static final SpanContext SPAN_CONTEXT = SpanContext.create(
      TraceId.fromLowerBase16("4c6af40c499951eb7de2777ba1e4fefa"),
      SpanId.fromLowerBase16("de52e84d13dd232d"),
      TraceOptions.builder().setIsSampled(true).build(),
      Tracestate.builder().build());
  private static final CallOptions CALL_OPTIONS_WITH_SPAN_CONTEXT =
      CallOptions.DEFAULT.withOption(CLIENT_TRACE_SPAN_CONTEXT_KEY, SPAN_CONTEXT);

  private InternalLoggingChannelInterceptor.Factory factory;
  private AtomicReference<ClientCall.Listener<byte[]>> interceptedListener;
  private AtomicReference<Metadata> actualClientInitial;
  private AtomicReference<Object> actualRequest;
  private SettableFuture<Void> halfCloseCalled;
  private SettableFuture<Void> cancelCalled;
  private SocketAddress peer;
  private LogHelper mockLogHelper;
  private ConfigFilterHelper mockFilterHelper;
  private FilterParams filterParams;

  @Before
  public void setup() throws Exception {
    mockLogHelper = mock(LogHelper.class);
    mockFilterHelper = mock(ConfigFilterHelper.class);
    factory = new InternalLoggingChannelInterceptor.FactoryImpl(mockLogHelper, mockFilterHelper);
    interceptedListener = new AtomicReference<>();
    actualClientInitial = new AtomicReference<>();
    actualRequest = new AtomicReference<>();
    halfCloseCalled = SettableFuture.create();
    cancelCalled = SettableFuture.create();
    peer = new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 1234);
    filterParams = FilterParams.create(true, 0, 0);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void internalLoggingChannelInterceptor() throws Exception {
    Channel channel = new Channel() {
      @Override
      public <RequestT, ResponseT> ClientCall<RequestT, ResponseT> newCall(
          MethodDescriptor<RequestT, ResponseT> methodDescriptor, CallOptions callOptions) {
        return new NoopClientCall<RequestT, ResponseT>() {
          @Override
          @SuppressWarnings("unchecked")
          public void start(Listener<ResponseT> responseListener, Metadata headers) {
            interceptedListener.set((Listener<byte[]>) responseListener);
            actualClientInitial.set(headers);
          }

          @Override
          public void sendMessage(RequestT message) {
            actualRequest.set(message);
          }

          @Override
          public void cancel(String message, Throwable cause) {
            cancelCalled.set(null);
          }

          @Override
          public void halfClose() {
            halfCloseCalled.set(null);
          }

          @Override
          public Attributes getAttributes() {
            return Attributes.newBuilder().set(Grpc.TRANSPORT_ATTR_REMOTE_ADDR, peer).build();
          }
        };
      }

      @Override
      public String authority() {
        return "the-authority";
      }
    };

    ClientCall.Listener<byte[]> mockListener = mock(ClientCall.Listener.class);

    MethodDescriptor<byte[], byte[]> method =
        MethodDescriptor.<byte[], byte[]>newBuilder()
            .setType(MethodType.UNKNOWN)
            .setFullMethodName("service/method")
            .setRequestMarshaller(BYTEARRAY_MARSHALLER)
            .setResponseMarshaller(BYTEARRAY_MARSHALLER)
            .build();
    when(mockFilterHelper.logRpcMethod(method.getFullMethodName(), true))
        .thenReturn(filterParams);

    ClientCall<byte[], byte[]> interceptedLoggingCall =
        factory.create()
            .interceptCall(method,
                CallOptions.DEFAULT,
                channel);

    // send request header
    {
      Metadata clientInitial = new Metadata();
      String dataA = "aaaaaaaaa";
      String dataB = "bbbbbbbbb";
      Metadata.Key<String> keyA =
          Metadata.Key.of("a", Metadata.ASCII_STRING_MARSHALLER);
      Metadata.Key<String> keyB =
          Metadata.Key.of("b", Metadata.ASCII_STRING_MARSHALLER);
      clientInitial.put(keyA, dataA);
      clientInitial.put(keyB, dataB);
      interceptedLoggingCall.start(mockListener, clientInitial);
      verify(mockLogHelper).logClientHeader(
          /*seq=*/ eq(1L),
          eq("service"),
          eq("method"),
          eq("the-authority"),
          ArgumentMatchers.isNull(),
          same(clientInitial),
          eq(filterParams.headerBytes()),
          eq(EventLogger.CLIENT),
          anyString(),
          ArgumentMatchers.isNull(),
          eq(DEFAULT_CLIENT_SPAN_CONTEXT));
      verifyNoMoreInteractions(mockLogHelper);
      assertSame(clientInitial, actualClientInitial.get());
    }

    reset(mockLogHelper);
    reset(mockListener);

    // receive response header
    {
      Metadata serverInitial = new Metadata();
      interceptedListener.get().onHeaders(serverInitial);
      verify(mockLogHelper).logServerHeader(
          /*seq=*/ eq(2L),
          eq("service"),
          eq("method"),
          eq("the-authority"),
          same(serverInitial),
          eq(filterParams.headerBytes()),
          eq(EventLogger.CLIENT),
          anyString(),
          same(peer),
          any(SpanContext.class));
      verifyNoMoreInteractions(mockLogHelper);
      verify(mockListener).onHeaders(same(serverInitial));
    }

    reset(mockLogHelper);
    reset(mockListener);

    // send request message
    {
      byte[] request = "this is a request".getBytes(US_ASCII);
      interceptedLoggingCall.sendMessage(request);
      verify(mockLogHelper).logRpcMessage(
          /*seq=*/ eq(3L),
          eq("service"),
          eq("method"),
          eq("the-authority"),
          eq(EventType.CLIENT_MESSAGE),
          same(request),
          eq(filterParams.messageBytes()),
          eq(EventLogger.CLIENT),
          anyString(),
          any(SpanContext.class));
      verifyNoMoreInteractions(mockLogHelper);
      assertSame(request, actualRequest.get());
    }

    reset(mockLogHelper);
    reset(mockListener);

    // client half close
    {
      interceptedLoggingCall.halfClose();
      verify(mockLogHelper).logHalfClose(
          /*seq=*/ eq(4L),
          eq("service"),
          eq("method"),
          eq("the-authority"),
          eq(EventLogger.CLIENT),
          anyString(),
          any(SpanContext.class));
      halfCloseCalled.get(1, TimeUnit.MILLISECONDS);
      verifyNoMoreInteractions(mockLogHelper);
    }

    reset(mockLogHelper);
    reset(mockListener);

    // receive response message
    {
      byte[] response = "this is a response".getBytes(US_ASCII);
      interceptedListener.get().onMessage(response);
      verify(mockLogHelper).logRpcMessage(
          /*seq=*/ eq(5L),
          eq("service"),
          eq("method"),
          eq("the-authority"),
          eq(EventType.SERVER_MESSAGE),
          same(response),
          eq(filterParams.messageBytes()),
          eq(EventLogger.CLIENT),
          anyString(),
          any(SpanContext.class));
      verifyNoMoreInteractions(mockLogHelper);
      verify(mockListener).onMessage(same(response));
    }

    reset(mockLogHelper);
    reset(mockListener);

    // receive trailer
    {
      Status status = Status.INTERNAL.withDescription("trailer description");
      Metadata trailers = new Metadata();
      interceptedListener.get().onClose(status, trailers);
      verify(mockLogHelper).logTrailer(
          /*seq=*/ eq(6L),
          eq("service"),
          eq("method"),
          eq("the-authority"),
          same(status),
          same(trailers),
          eq(filterParams.headerBytes()),
          eq(EventLogger.CLIENT),
          anyString(),
          same(peer),
          any(SpanContext.class));
      verifyNoMoreInteractions(mockLogHelper);
      verify(mockListener).onClose(same(status), same(trailers));
    }

    reset(mockLogHelper);
    reset(mockListener);

    // cancel
    {
      interceptedLoggingCall.cancel(null, null);
      verify(mockLogHelper).logCancel(
          /*seq=*/ eq(7L),
          eq("service"),
          eq("method"),
          eq("the-authority"),
          eq(EventLogger.CLIENT),
          anyString(),
          any(SpanContext.class));
      cancelCalled.get(1, TimeUnit.MILLISECONDS);
    }
  }

  @Test
  public void clientDeadLineLogged_deadlineSetViaCallOption() {
    MethodDescriptor<byte[], byte[]> method =
        MethodDescriptor.<byte[], byte[]>newBuilder()
            .setType(MethodType.UNKNOWN)
            .setFullMethodName("service/method")
            .setRequestMarshaller(BYTEARRAY_MARSHALLER)
            .setResponseMarshaller(BYTEARRAY_MARSHALLER)
            .build();
    when(mockFilterHelper.logRpcMethod(method.getFullMethodName(), true))
        .thenReturn(filterParams);
    @SuppressWarnings("unchecked")
    ClientCall.Listener<byte[]> mockListener = mock(ClientCall.Listener.class);

    int durationSecs = 5;
    ClientCall<byte[], byte[]> interceptedLoggingCall =
        factory.create()
            .interceptCall(
                method,
                CallOptions.DEFAULT.withDeadlineAfter(durationSecs, TimeUnit.SECONDS),
                new Channel() {
                  @Override
                  public <RequestT, ResponseT> ClientCall<RequestT, ResponseT> newCall(
                      MethodDescriptor<RequestT, ResponseT> methodDescriptor,
                      CallOptions callOptions) {
                    return new NoopClientCall<>();
                  }

                  @Override
                  public String authority() {
                    return "the-authority";
                  }
                });
    interceptedLoggingCall.start(mockListener, new Metadata());
    ArgumentCaptor<Duration> callOptTimeoutCaptor = ArgumentCaptor.forClass(Duration.class);
    verify(mockLogHelper, times(1))
        .logClientHeader(
            anyLong(),
            AdditionalMatchers.or(ArgumentMatchers.isNull(), anyString()),
            AdditionalMatchers.or(ArgumentMatchers.isNull(), anyString()),
            AdditionalMatchers.or(ArgumentMatchers.isNull(), anyString()),
            callOptTimeoutCaptor.capture(),
            any(Metadata.class),
            anyInt(),
            any(GrpcLogRecord.EventLogger.class),
            anyString(),
            AdditionalMatchers.or(ArgumentMatchers.isNull(),
                ArgumentMatchers.any()),
            any(SpanContext.class));
    Duration timeout = callOptTimeoutCaptor.getValue();
    assertThat(Math.abs(TimeUnit.SECONDS.toNanos(durationSecs) - Durations.toNanos(timeout)))
        .isAtMost(TimeUnit.MILLISECONDS.toNanos(750));
  }

  @Test
  public void clientDeadlineLogged_deadlineSetViaContext() throws Exception {
    final SettableFuture<ClientCall<byte[], byte[]>> callFuture = SettableFuture.create();
    Context.current()
        .withDeadline(
            Deadline.after(2, TimeUnit.SECONDS),
            Executors.newSingleThreadScheduledExecutor())
        .run(() -> {
          MethodDescriptor<byte[], byte[]> method =
              MethodDescriptor.<byte[], byte[]>newBuilder()
                  .setType(MethodType.UNKNOWN)
                  .setFullMethodName("service/method")
                  .setRequestMarshaller(BYTEARRAY_MARSHALLER)
                  .setResponseMarshaller(BYTEARRAY_MARSHALLER)
                  .build();
          when(mockFilterHelper.logRpcMethod(method.getFullMethodName(), true))
              .thenReturn(filterParams);

          callFuture.set(
              factory.create()
                  .interceptCall(
                      method,
                      CallOptions.DEFAULT.withDeadlineAfter(5, TimeUnit.SECONDS),
                      new Channel() {
                        @Override
                        public <RequestT, ResponseT> ClientCall<RequestT, ResponseT> newCall(
                            MethodDescriptor<RequestT, ResponseT> methodDescriptor,
                            CallOptions callOptions) {
                          return new NoopClientCall<>();
                        }

                        @Override
                        public String authority() {
                          return "the-authority";
                        }
                      }));
        });
    @SuppressWarnings("unchecked")
    ClientCall.Listener<byte[]> mockListener = mock(ClientCall.Listener.class);
    Objects.requireNonNull(callFuture.get()).start(mockListener, new Metadata());
    ArgumentCaptor<Duration> contextTimeoutCaptor = ArgumentCaptor.forClass(Duration.class);
    verify(mockLogHelper, times(1))
        .logClientHeader(
            anyLong(),
            AdditionalMatchers.or(ArgumentMatchers.isNull(), anyString()),
            AdditionalMatchers.or(ArgumentMatchers.isNull(), anyString()),
            AdditionalMatchers.or(ArgumentMatchers.isNull(), anyString()),
            contextTimeoutCaptor.capture(),
            any(Metadata.class),
            anyInt(),
            any(GrpcLogRecord.EventLogger.class),
            anyString(),
            AdditionalMatchers.or(ArgumentMatchers.isNull(),
                ArgumentMatchers.any()),
            any(SpanContext.class));
    Duration timeout = contextTimeoutCaptor.getValue();
    assertThat(Math.abs(TimeUnit.SECONDS.toNanos(2) - Durations.toNanos(timeout)))
        .isAtMost(TimeUnit.MILLISECONDS.toNanos(750));
  }

  @Test
  public void clientDeadlineLogged_deadlineSetViaContextAndCallOptions() throws Exception {
    Deadline contextDeadline = Deadline.after(10, TimeUnit.SECONDS);
    Deadline callOptionsDeadline = CallOptions.DEFAULT
        .withDeadlineAfter(15, TimeUnit.SECONDS).getDeadline();

    final SettableFuture<ClientCall<byte[], byte[]>> callFuture = SettableFuture.create();
    Context.current()
        .withDeadline(
            contextDeadline, Executors.newSingleThreadScheduledExecutor())
        .run(() -> {
          MethodDescriptor<byte[], byte[]> method =
              MethodDescriptor.<byte[], byte[]>newBuilder()
                  .setType(MethodType.UNKNOWN)
                  .setFullMethodName("service/method")
                  .setRequestMarshaller(BYTEARRAY_MARSHALLER)
                  .setResponseMarshaller(BYTEARRAY_MARSHALLER)
                  .build();
          when(mockFilterHelper.logRpcMethod(method.getFullMethodName(), true))
              .thenReturn(filterParams);

          callFuture.set(
              factory.create()
                  .interceptCall(
                      method,
                      CallOptions.DEFAULT.withDeadlineAfter(15, TimeUnit.SECONDS),
                      new Channel() {
                        @Override
                        public <RequestT, ResponseT> ClientCall<RequestT, ResponseT> newCall(
                            MethodDescriptor<RequestT, ResponseT> methodDescriptor,
                            CallOptions callOptions) {
                          return new NoopClientCall<>();
                        }

                        @Override
                        public String authority() {
                          return "the-authority";
                        }
                      }));
        });
    @SuppressWarnings("unchecked")
    ClientCall.Listener<byte[]> mockListener = mock(ClientCall.Listener.class);
    Objects.requireNonNull(callFuture.get()).start(mockListener, new Metadata());
    ArgumentCaptor<Duration> timeoutCaptor = ArgumentCaptor.forClass(Duration.class);
    verify(mockLogHelper, times(1))
        .logClientHeader(
            anyLong(),
            AdditionalMatchers.or(ArgumentMatchers.isNull(), anyString()),
            AdditionalMatchers.or(ArgumentMatchers.isNull(), anyString()),
            AdditionalMatchers.or(ArgumentMatchers.isNull(), anyString()),
            timeoutCaptor.capture(),
            any(Metadata.class),
            anyInt(),
            any(GrpcLogRecord.EventLogger.class),
            anyString(),
            AdditionalMatchers.or(ArgumentMatchers.isNull(),
                ArgumentMatchers.any()),
            any(SpanContext.class));
    Duration timeout = timeoutCaptor.getValue();
    assertThat(LogHelper.min(contextDeadline, callOptionsDeadline))
        .isSameInstanceAs(contextDeadline);
    assertThat(Math.abs(TimeUnit.SECONDS.toNanos(10) - Durations.toNanos(timeout)))
        .isAtMost(TimeUnit.MILLISECONDS.toNanos(750));
  }

  @Test
  public void clientMethodOrServiceFilter_disabled() {
    Channel channel = new Channel() {
      @Override
      public <RequestT, ResponseT> ClientCall<RequestT, ResponseT> newCall(
          MethodDescriptor<RequestT, ResponseT> methodDescriptor, CallOptions callOptions) {
        return new NoopClientCall<RequestT, ResponseT>() {
          @Override
          @SuppressWarnings("unchecked")
          public void start(Listener<ResponseT> responseListener, Metadata headers) {
            interceptedListener.set((Listener<byte[]>) responseListener);
            actualClientInitial.set(headers);
          }

          @Override
          public void sendMessage(RequestT message) {
            actualRequest.set(message);
          }

          @Override
          public void cancel(String message, Throwable cause) {
            cancelCalled.set(null);
          }

          @Override
          public void halfClose() {
            halfCloseCalled.set(null);
          }

          @Override
          public Attributes getAttributes() {
            return Attributes.newBuilder().set(Grpc.TRANSPORT_ATTR_REMOTE_ADDR, peer).build();
          }
        };
      }

      @Override
      public String authority() {
        return "the-authority";
      }
    };

    @SuppressWarnings("unchecked")
    ClientCall.Listener<byte[]> mockListener = mock(ClientCall.Listener.class);

    MethodDescriptor<byte[], byte[]> method =
        MethodDescriptor.<byte[], byte[]>newBuilder()
            .setType(MethodType.UNKNOWN)
            .setFullMethodName("service/method")
            .setRequestMarshaller(BYTEARRAY_MARSHALLER)
            .setResponseMarshaller(BYTEARRAY_MARSHALLER)
            .build();
    when(mockFilterHelper.logRpcMethod(method.getFullMethodName(), true))
        .thenReturn(FilterParams.create(false, 0, 0));

    ClientCall<byte[], byte[]> interceptedLoggingCall =
        factory.create()
            .interceptCall(method,
                CallOptions.DEFAULT,
                channel);

    interceptedLoggingCall.start(mockListener, new Metadata());
    verifyNoInteractions(mockLogHelper);
  }

  @Test
  public void clientMethodOrServiceFilter_enabled() {
    Channel channel = new Channel() {
      @Override
      public <RequestT, ResponseT> ClientCall<RequestT, ResponseT> newCall(
          MethodDescriptor<RequestT, ResponseT> methodDescriptor, CallOptions callOptions) {
        return new NoopClientCall<RequestT, ResponseT>() {
          @Override
          @SuppressWarnings("unchecked")
          public void start(Listener<ResponseT> responseListener, Metadata headers) {
            interceptedListener.set((Listener<byte[]>) responseListener);
            actualClientInitial.set(headers);
          }

          @Override
          public void sendMessage(RequestT message) {
            actualRequest.set(message);
          }

          @Override
          public void cancel(String message, Throwable cause) {
            cancelCalled.set(null);
          }

          @Override
          public void halfClose() {
            halfCloseCalled.set(null);
          }

          @Override
          public Attributes getAttributes() {
            return Attributes.newBuilder().set(Grpc.TRANSPORT_ATTR_REMOTE_ADDR, peer).build();
          }
        };
      }

      @Override
      public String authority() {
        return "the-authority";
      }
    };

    @SuppressWarnings("unchecked")
    ClientCall.Listener<byte[]> mockListener = mock(ClientCall.Listener.class);

    MethodDescriptor<byte[], byte[]> method =
        MethodDescriptor.<byte[], byte[]>newBuilder()
            .setType(MethodType.UNKNOWN)
            .setFullMethodName("service/method")
            .setRequestMarshaller(BYTEARRAY_MARSHALLER)
            .setResponseMarshaller(BYTEARRAY_MARSHALLER)
            .build();
    when(mockFilterHelper.logRpcMethod(method.getFullMethodName(), true))
        .thenReturn(FilterParams.create(true, 10, 10));

    ClientCall<byte[], byte[]> interceptedLoggingCall =
        factory.create()
            .interceptCall(method,
                CallOptions.DEFAULT,
                channel);

    {
      interceptedLoggingCall.start(mockListener, new Metadata());
      interceptedListener.get().onHeaders(new Metadata());
      byte[] request = "this is a request".getBytes(US_ASCII);
      interceptedLoggingCall.sendMessage(request);
      interceptedLoggingCall.halfClose();
      byte[] response = "this is a response".getBytes(US_ASCII);
      interceptedListener.get().onMessage(response);
      Status status = Status.INTERNAL.withDescription("trailer description");
      Metadata trailers = new Metadata();
      interceptedListener.get().onClose(status, trailers);
      interceptedLoggingCall.cancel(null, null);
      assertThat(Mockito.mockingDetails(mockLogHelper).getInvocations().size()).isEqualTo(7);
    }
  }

  @Test
  public void clientSpanContextLogged_contextSetViaCallOption() {
    Channel channel = new Channel() {
      @Override
      public <RequestT, ResponseT> ClientCall<RequestT, ResponseT> newCall(
          MethodDescriptor<RequestT, ResponseT> methodDescriptor, CallOptions callOptions) {
        return new NoopClientCall<RequestT, ResponseT>() {
          @Override
          @SuppressWarnings("unchecked")
          public void start(Listener<ResponseT> responseListener, Metadata headers) {
            interceptedListener.set((Listener<byte[]>) responseListener);
            actualClientInitial.set(headers);
          }

          @Override
          public void sendMessage(RequestT message) {
            actualRequest.set(message);
          }

          @Override
          public void cancel(String message, Throwable cause) {
            cancelCalled.set(null);
          }

          @Override
          public void halfClose() {
            halfCloseCalled.set(null);
          }

          @Override
          public Attributes getAttributes() {
            return Attributes.newBuilder().set(Grpc.TRANSPORT_ATTR_REMOTE_ADDR, peer).build();
          }
        };
      }

      @Override
      public String authority() {
        return "the-authority";
      }
    };

    @SuppressWarnings("unchecked")
    ClientCall.Listener<byte[]> mockListener = mock(ClientCall.Listener.class);

    MethodDescriptor<byte[], byte[]> method =
        MethodDescriptor.<byte[], byte[]>newBuilder()
            .setType(MethodType.UNKNOWN)
            .setFullMethodName("service/method")
            .setRequestMarshaller(BYTEARRAY_MARSHALLER)
            .setResponseMarshaller(BYTEARRAY_MARSHALLER)
            .build();
    when(mockFilterHelper.logRpcMethod(method.getFullMethodName(), true))
        .thenReturn(FilterParams.create(true, 10, 10));

    ClientCall<byte[], byte[]> interceptedLoggingCall =
        factory.create()
            .interceptCall(method,
                CALL_OPTIONS_WITH_SPAN_CONTEXT,
                channel);

    {
      interceptedLoggingCall.start(mockListener, new Metadata());
      ArgumentCaptor<SpanContext> callOptSpanContextCaptor = ArgumentCaptor.forClass(
          SpanContext.class);
      verify(mockLogHelper, times(1))
          .logClientHeader(
              anyLong(),
              AdditionalMatchers.or(ArgumentMatchers.isNull(), anyString()),
              AdditionalMatchers.or(ArgumentMatchers.isNull(), anyString()),
              AdditionalMatchers.or(ArgumentMatchers.isNull(), anyString()),
              ArgumentMatchers.isNull(),
              any(Metadata.class),
              anyInt(),
              any(GrpcLogRecord.EventLogger.class),
              anyString(),
              AdditionalMatchers.or(ArgumentMatchers.isNull(),
                  ArgumentMatchers.any()),
              callOptSpanContextCaptor.capture());
      SpanContext spanContext = callOptSpanContextCaptor.getValue();
      assertThat(spanContext).isEqualTo(SPAN_CONTEXT);
    }
  }

  @Test
  public void clientSpanContextLogged_contextNotSetViaCallOption() {
    Channel channel = new Channel() {
      @Override
      public <RequestT, ResponseT> ClientCall<RequestT, ResponseT> newCall(
          MethodDescriptor<RequestT, ResponseT> methodDescriptor, CallOptions callOptions) {
        return new NoopClientCall<RequestT, ResponseT>() {
          @Override
          @SuppressWarnings("unchecked")
          public void start(Listener<ResponseT> responseListener, Metadata headers) {
            interceptedListener.set((Listener<byte[]>) responseListener);
            actualClientInitial.set(headers);
          }

          @Override
          public void sendMessage(RequestT message) {
            actualRequest.set(message);
          }

          @Override
          public void cancel(String message, Throwable cause) {
            cancelCalled.set(null);
          }

          @Override
          public void halfClose() {
            halfCloseCalled.set(null);
          }

          @Override
          public Attributes getAttributes() {
            return Attributes.newBuilder().set(Grpc.TRANSPORT_ATTR_REMOTE_ADDR, peer).build();
          }
        };
      }

      @Override
      public String authority() {
        return "the-authority";
      }
    };

    @SuppressWarnings("unchecked")
    ClientCall.Listener<byte[]> mockListener = mock(ClientCall.Listener.class);

    MethodDescriptor<byte[], byte[]> method =
        MethodDescriptor.<byte[], byte[]>newBuilder()
            .setType(MethodType.UNKNOWN)
            .setFullMethodName("service/method")
            .setRequestMarshaller(BYTEARRAY_MARSHALLER)
            .setResponseMarshaller(BYTEARRAY_MARSHALLER)
            .build();
    when(mockFilterHelper.logRpcMethod(method.getFullMethodName(), true))
        .thenReturn(FilterParams.create(true, 10, 10));

    ClientCall<byte[], byte[]> interceptedLoggingCall =
        factory.create()
            .interceptCall(method,
                CallOptions.DEFAULT,
                channel);

    {
      interceptedLoggingCall.start(mockListener, new Metadata());
      ArgumentCaptor<SpanContext> callOptSpanContextCaptor = ArgumentCaptor.forClass(
          SpanContext.class);
      verify(mockLogHelper, times(1))
          .logClientHeader(
              anyLong(),
              AdditionalMatchers.or(ArgumentMatchers.isNull(), anyString()),
              AdditionalMatchers.or(ArgumentMatchers.isNull(), anyString()),
              AdditionalMatchers.or(ArgumentMatchers.isNull(), anyString()),
              ArgumentMatchers.isNull(),
              any(Metadata.class),
              anyInt(),
              any(GrpcLogRecord.EventLogger.class),
              anyString(),
              AdditionalMatchers.or(ArgumentMatchers.isNull(),
                  ArgumentMatchers.any()),
              callOptSpanContextCaptor.capture());
      SpanContext spanContext = callOptSpanContextCaptor.getValue();
      assertThat(spanContext).isEqualTo(DEFAULT_CLIENT_SPAN_CONTEXT);
    }
  }
}
