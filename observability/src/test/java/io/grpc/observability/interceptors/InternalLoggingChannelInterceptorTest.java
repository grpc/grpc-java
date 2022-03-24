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

package io.grpc.observability.interceptors;

import static com.google.common.truth.Truth.assertThat;
import static io.grpc.observability.interceptors.LogHelperTest.BYTEARRAY_MARSHALLER;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
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
import io.grpc.internal.NoopClientCall;
import io.grpc.observability.interceptors.ConfigFilterHelper.MethodFilterParams;
import io.grpc.observabilitylog.v1.GrpcLogRecord;
import io.grpc.observabilitylog.v1.GrpcLogRecord.EventLogger;
import io.grpc.observabilitylog.v1.GrpcLogRecord.EventType;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.Charset;
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

  private static final Charset US_ASCII = Charset.forName("US-ASCII");

  private InternalLoggingChannelInterceptor.Factory factory;
  private AtomicReference<ClientCall.Listener<byte[]>> interceptedListener;
  private AtomicReference<Metadata> actualClientInitial;
  private AtomicReference<Object> actualRequest;
  private SettableFuture<Void> halfCloseCalled;
  private SettableFuture<Void> cancelCalled;
  private SocketAddress peer;
  private final LogHelper mockLogHelper = mock(LogHelper.class);
  private final ConfigFilterHelper mockFilterHelper = mock(ConfigFilterHelper.class);
  private MethodFilterParams filterParams;


  @Before
  public void setup() throws Exception {
    factory = new InternalLoggingChannelInterceptor.FactoryImpl(mockLogHelper, mockFilterHelper);
    interceptedListener = new AtomicReference<>();
    actualClientInitial = new AtomicReference<>();
    actualRequest = new AtomicReference<>();
    halfCloseCalled = SettableFuture.create();
    cancelCalled = SettableFuture.create();
    peer = new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 1234);
    filterParams = new MethodFilterParams(true, 0, 0);
    when(mockFilterHelper.isMethodToBeLogged("service/method"))
        .thenReturn(filterParams);
  }

  @Test
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

    @SuppressWarnings("unchecked")
    ClientCall.Listener<byte[]> mockListener = mock(ClientCall.Listener.class);

    MethodDescriptor<byte[], byte[]> method =
        MethodDescriptor.<byte[], byte[]>newBuilder()
            .setType(MethodType.UNKNOWN)
            .setFullMethodName("service/method")
            .setRequestMarshaller(BYTEARRAY_MARSHALLER)
            .setResponseMarshaller(BYTEARRAY_MARSHALLER)
            .build();

    ClientCall<byte[], byte[]> interceptedLoggingCall =
        factory.create()
            .interceptCall(method,
                CallOptions.DEFAULT,
                channel);

    // send client header
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
      verify(mockLogHelper).logRequestHeader(
          /*seq=*/ eq(1L),
          eq("service"),
          eq("method"),
          eq("the-authority"),
          ArgumentMatchers.<Duration>isNull(),
          same(clientInitial),
          eq(filterParams.headerBytes),
          eq(EventLogger.LOGGER_CLIENT),
          anyString(),
          ArgumentMatchers.<SocketAddress>isNull());
      verifyNoMoreInteractions(mockLogHelper);
      assertSame(clientInitial, actualClientInitial.get());
    }

    // receive server header
    {
      Metadata serverInitial = new Metadata();
      interceptedListener.get().onHeaders(serverInitial);
      verify(mockLogHelper).logResponseHeader(
          /*seq=*/ eq(2L),
          eq("service"),
          eq("method"),
          same(serverInitial),
          eq(filterParams.headerBytes),
          eq(EventLogger.LOGGER_CLIENT),
          anyString(),
          same(peer));
      verifyNoMoreInteractions(mockLogHelper);
      verify(mockListener).onHeaders(same(serverInitial));
    }

    // send client message
    {
      byte[] request = "this is a request".getBytes(US_ASCII);
      interceptedLoggingCall.sendMessage(request);
      verify(mockLogHelper).logRpcMessage(
          /*seq=*/ eq(3L),
          eq("service"),
          eq("method"),
          eq(EventType.GRPC_CALL_REQUEST_MESSAGE),
          same(request),
          eq(filterParams.messageBytes),
          eq(EventLogger.LOGGER_CLIENT),
          anyString());
      verifyNoMoreInteractions(mockLogHelper);
      assertSame(request, actualRequest.get());
    }

    // client half close
    {
      interceptedLoggingCall.halfClose();
      verify(mockLogHelper).logHalfClose(
          /*seq=*/ eq(4L),
          eq("service"),
          eq("method"),
          eq(EventLogger.LOGGER_CLIENT),
          anyString());
      halfCloseCalled.get(1, TimeUnit.SECONDS);
      verifyNoMoreInteractions(mockLogHelper);
    }

    // receive server message
    {
      byte[] response = "this is a response".getBytes(US_ASCII);
      interceptedListener.get().onMessage(response);
      verify(mockLogHelper).logRpcMessage(
          /*seq=*/ eq(5L),
          eq("service"),
          eq("method"),
          eq(EventType.GRPC_CALL_RESPONSE_MESSAGE),
          same(response),
          eq(filterParams.messageBytes),
          eq(EventLogger.LOGGER_CLIENT),
          anyString());
      verifyNoMoreInteractions(mockLogHelper);
      verify(mockListener).onMessage(same(response));
    }

    // receive trailer
    {
      Status status = Status.INTERNAL.withDescription("trailer description");
      Metadata trailers = new Metadata();
      interceptedListener.get().onClose(status, trailers);
      verify(mockLogHelper).logTrailer(
          /*seq=*/ eq(6L),
          eq("service"),
          eq("method"),
          same(status),
          same(trailers),
          eq(filterParams.headerBytes),
          eq(EventLogger.LOGGER_CLIENT),
          anyString(),
          same(peer));
      verifyNoMoreInteractions(mockLogHelper);
      verify(mockListener).onClose(same(status), same(trailers));
    }

    // cancel
    {
      interceptedLoggingCall.cancel(null, null);
      verify(mockLogHelper).logCancel(
          /*seq=*/ eq(7L),
          eq("service"),
          eq("method"),
          eq(EventLogger.LOGGER_CLIENT),
          anyString());
      cancelCalled.get(1, TimeUnit.SECONDS);
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
    @SuppressWarnings("unchecked")
    ClientCall.Listener<byte[]> mockListener = mock(ClientCall.Listener.class);

    ClientCall<byte[], byte[]> interceptedLoggingCall =
        factory.create()
            .interceptCall(
                method,
                CallOptions.DEFAULT.withDeadlineAfter(1, TimeUnit.SECONDS),
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
        .logRequestHeader(
            anyLong(),
            AdditionalMatchers.or(ArgumentMatchers.<String>isNull(), anyString()),
            AdditionalMatchers.or(ArgumentMatchers.<String>isNull(), anyString()),
            AdditionalMatchers.or(ArgumentMatchers.<String>isNull(), anyString()),
            callOptTimeoutCaptor.capture(),
            any(Metadata.class),
            anyInt(),
            any(GrpcLogRecord.EventLogger.class),
            anyString(),
            AdditionalMatchers.or(ArgumentMatchers.<SocketAddress>isNull(),
                ArgumentMatchers.<SocketAddress>any()));
    Duration timeout = callOptTimeoutCaptor.getValue();
    assertThat(TimeUnit.SECONDS.toNanos(1) - Durations.toNanos(timeout))
        .isAtMost(TimeUnit.MILLISECONDS.toNanos(250));
  }

  @Test
  public void clientDeadlineLogged_deadlineSetViaContext() throws Exception {
    final SettableFuture<ClientCall<byte[], byte[]>> callFuture = SettableFuture.create();
    Context.current()
        .withDeadline(
            Deadline.after(1, TimeUnit.SECONDS),
            Executors.newSingleThreadScheduledExecutor())
        .run(new Runnable() {
          @Override
          public void run() {
            MethodDescriptor<byte[], byte[]> method =
                MethodDescriptor.<byte[], byte[]>newBuilder()
                    .setType(MethodType.UNKNOWN)
                    .setFullMethodName("service/method")
                    .setRequestMarshaller(BYTEARRAY_MARSHALLER)
                    .setResponseMarshaller(BYTEARRAY_MARSHALLER)
                    .build();

            callFuture.set(
                factory.create()
                    .interceptCall(
                        method,
                        CallOptions.DEFAULT.withDeadlineAfter(1, TimeUnit.SECONDS),
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
          }
        });
    @SuppressWarnings("unchecked")
    ClientCall.Listener<byte[]> mockListener = mock(ClientCall.Listener.class);
    callFuture.get().start(mockListener, new Metadata());
    ArgumentCaptor<Duration> contextTimeoutCaptor = ArgumentCaptor.forClass(Duration.class);
    verify(mockLogHelper, times(1))
        .logRequestHeader(
            anyLong(),
            AdditionalMatchers.or(ArgumentMatchers.<String>isNull(), anyString()),
            AdditionalMatchers.or(ArgumentMatchers.<String>isNull(), anyString()),
            AdditionalMatchers.or(ArgumentMatchers.<String>isNull(), anyString()),
            contextTimeoutCaptor.capture(),
            any(Metadata.class),
            anyInt(),
            any(GrpcLogRecord.EventLogger.class),
            anyString(),
            AdditionalMatchers.or(ArgumentMatchers.<SocketAddress>isNull(),
                ArgumentMatchers.<SocketAddress>any()));
    Duration timeout = contextTimeoutCaptor.getValue();
    assertThat(TimeUnit.SECONDS.toNanos(1) - Durations.toNanos(timeout))
        .isAtMost(TimeUnit.MILLISECONDS.toNanos(250));
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
        .run(new Runnable() {
          @Override
          public void run() {
            MethodDescriptor<byte[], byte[]> method =
                MethodDescriptor.<byte[], byte[]>newBuilder()
                    .setType(MethodType.UNKNOWN)
                    .setFullMethodName("service/method")
                    .setRequestMarshaller(BYTEARRAY_MARSHALLER)
                    .setResponseMarshaller(BYTEARRAY_MARSHALLER)
                    .build();

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
          }
        });
    @SuppressWarnings("unchecked")
    ClientCall.Listener<byte[]> mockListener = mock(ClientCall.Listener.class);
    callFuture.get().start(mockListener, new Metadata());
    ArgumentCaptor<Duration> timeoutCaptor = ArgumentCaptor.forClass(Duration.class);
    verify(mockLogHelper, times(1))
        .logRequestHeader(
            anyLong(),
            AdditionalMatchers.or(ArgumentMatchers.<String>isNull(), anyString()),
            AdditionalMatchers.or(ArgumentMatchers.<String>isNull(), anyString()),
            AdditionalMatchers.or(ArgumentMatchers.<String>isNull(), anyString()),
            timeoutCaptor.capture(),
            any(Metadata.class),
            anyInt(),
            any(GrpcLogRecord.EventLogger.class),
            anyString(),
            AdditionalMatchers.or(ArgumentMatchers.<SocketAddress>isNull(),
                ArgumentMatchers.<SocketAddress>any()));
    Duration timeout = timeoutCaptor.getValue();
    assertThat(LogHelper.min(contextDeadline, callOptionsDeadline))
        .isSameInstanceAs(contextDeadline);
    assertThat(TimeUnit.SECONDS.toNanos(10) - Durations.toNanos(timeout))
        .isAtMost(TimeUnit.MILLISECONDS.toNanos(250));
  }

  @Test
  public void clientMethodOrServiceFilter_disabled() {
    MethodFilterParams noFilterParams
        = new MethodFilterParams(false, 0, 0);
    when(mockFilterHelper.isMethodToBeLogged("service/method"))
        .thenReturn(noFilterParams);

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
    MethodFilterParams noFilterParams
        = new MethodFilterParams(true, 10, 10);
    when(mockFilterHelper.isMethodToBeLogged("service/method"))
        .thenReturn(noFilterParams);

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
      assertTrue("LogHelper should be invoked seven times equal to number of events",
          Mockito.mockingDetails(mockLogHelper).getInvocations().size() == 7);
    }
  }
}
