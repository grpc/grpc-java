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
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

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
import io.grpc.MethodDescriptor.Marshaller;
import io.grpc.MethodDescriptor.MethodType;
import io.grpc.Status;
import io.grpc.internal.NoopClientCall;
import io.grpc.observability.interceptors.LogHelper.ByteArrayMarshaller;
import io.grpc.observability.logging.GcpLogSink;
import io.grpc.observability.logging.Sink;
import io.grpc.observabilitylog.v1.GrpcLogRecord;
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
import org.mockito.ArgumentCaptor;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/**
 * Tests for {@link InternalLoggingChannelInterceptor}.
 */
@RunWith(JUnit4.class)
public class InternalLoggingChannelInterceptorTest {

  @Rule
  public final MockitoRule mockito = MockitoJUnit.rule();

  public static final Marshaller<byte[]> BYTEARRAY_MARSHALLER = new ByteArrayMarshaller();
  private static final Charset US_ASCII = Charset.forName("US-ASCII");

  private InternalLoggingChannelInterceptor.Factory factory;
  private AtomicReference<ClientCall.Listener<byte[]>> interceptedListener;
  private AtomicReference<Metadata> actualClientInitial;
  private AtomicReference<Object> actualRequest;
  private SettableFuture<Void> halfCloseCalled;
  private SettableFuture<Void> cancelCalled;
  private SocketAddress peer;
  private final Sink mockSink = mock(GcpLogSink.class);

  @Before
  public void setup() throws Exception {
    factory = new InternalLoggingChannelInterceptor.FactoryImpl(mockSink);
    interceptedListener = new AtomicReference<>();
    actualClientInitial = new AtomicReference<>();
    actualRequest = new AtomicReference<>();
    halfCloseCalled = SettableFuture.create();
    cancelCalled = SettableFuture.create();
    peer = new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 1234);
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
      EventType expectedRequestHeaderEvent = EventType.GRPC_CALL_REQUEST_HEADER;
      ArgumentCaptor<GrpcLogRecord> captor = ArgumentCaptor.forClass(GrpcLogRecord.class);
      Metadata clientInitial = new Metadata();
      interceptedLoggingCall.start(mockListener, clientInitial);
      verify(mockSink).write(captor.capture());
      assertEquals(captor.getValue().getEventType(),
          expectedRequestHeaderEvent);
      verifyNoMoreInteractions(mockSink);
      assertSame(clientInitial, actualClientInitial.get());
    }

    // receive server header
    {
      EventType expectedResponseHeaderEvent = EventType.GRPC_CALL_RESPONSE_HEADER;
      ArgumentCaptor<GrpcLogRecord> captor = ArgumentCaptor.forClass(GrpcLogRecord.class);
      Metadata serverInitial = new Metadata();
      interceptedListener.get().onHeaders(serverInitial);
      verify(mockSink, times(2)).write(captor.capture());
      assertEquals(captor.getValue().getEventType(),
          expectedResponseHeaderEvent);
      verifyNoMoreInteractions(mockSink);
      verify(mockListener).onHeaders(same(serverInitial));
    }

    // send client message
    {
      EventType expectedRequestMessageEvent = EventType.GRPC_CALL_REQUEST_MESSAGE;
      ArgumentCaptor<GrpcLogRecord> captor = ArgumentCaptor.forClass(GrpcLogRecord.class);
      byte[] request = "this is a request".getBytes(US_ASCII);
      interceptedLoggingCall.sendMessage(request);
      verify(mockSink, times(3)).write(captor.capture());
      assertEquals(captor.getValue().getEventType(),
          expectedRequestMessageEvent);
      verifyNoMoreInteractions(mockSink);
      assertSame(request, actualRequest.get());
    }

    // client half close
    {
      EventType expectedHalfCloseEvent = EventType.GRPC_CALL_HALF_CLOSE;
      ArgumentCaptor<GrpcLogRecord> captor = ArgumentCaptor.forClass(GrpcLogRecord.class);
      interceptedLoggingCall.halfClose();
      verify(mockSink, times(4)).write(captor.capture());
      assertEquals(captor.getValue().getEventType(),
          expectedHalfCloseEvent);
      halfCloseCalled.get(1, TimeUnit.SECONDS);
      verifyNoMoreInteractions(mockSink);
    }

    // receive server message
    {
      EventType expectedResponseMessageEvent = EventType.GRPC_CALL_RESPONSE_MESSAGE;
      ArgumentCaptor<GrpcLogRecord> captor = ArgumentCaptor.forClass(GrpcLogRecord.class);
      byte[] response = "this is a response".getBytes(US_ASCII);
      interceptedListener.get().onMessage(response);
      verify(mockSink, times(5)).write(captor.capture());
      assertEquals(captor.getValue().getEventType(),
          expectedResponseMessageEvent);
      verifyNoMoreInteractions(mockSink);
      verify(mockListener).onMessage(same(response));
    }

    // receive trailer
    {
      EventType expectedTrailerEvent = EventType.GRPC_CALL_TRAILER;
      ArgumentCaptor<GrpcLogRecord> captor = ArgumentCaptor.forClass(GrpcLogRecord.class);
      Status status = Status.INTERNAL.withDescription("trailer description");
      Metadata trailers = new Metadata();

      interceptedListener.get().onClose(status, trailers);
      verify(mockSink, times(6)).write(captor.capture());
      assertEquals(captor.getValue().getEventType(),
          expectedTrailerEvent);
      verifyNoMoreInteractions(mockSink);
      verify(mockListener).onClose(same(status), same(trailers));
    }

    // cancel
    {
      EventType expectedCancelEvent = EventType.GRPC_CALL_CANCEL;
      ArgumentCaptor<GrpcLogRecord> captor = ArgumentCaptor.forClass(GrpcLogRecord.class);
      interceptedLoggingCall.cancel(null, null);
      verify(mockSink, times(7)).write(captor.capture());
      assertEquals(captor.getValue().getEventType(),
          expectedCancelEvent);
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
    ArgumentCaptor<GrpcLogRecord> captor = ArgumentCaptor.forClass(GrpcLogRecord.class);
    verify(mockSink, times(1)).write(captor.capture());
    Duration timeout = captor.getValue().getTimeout();
    assertThat(TimeUnit.SECONDS.toNanos(1) - Durations.toNanos(timeout))
        .isAtMost(TimeUnit.MILLISECONDS.toNanos(250));
  }

  @Test
  public void clientDeadlineLogged_deadlineSetViaContext() throws Exception {
    final SettableFuture<ClientCall<byte[], byte[]>> callFuture = SettableFuture.create();
    Context.current()
        .withDeadline(
            Deadline.after(1, TimeUnit.SECONDS), Executors.newSingleThreadScheduledExecutor())
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
    ArgumentCaptor<GrpcLogRecord> captor = ArgumentCaptor.forClass(GrpcLogRecord.class);
    verify(mockSink, times(1)).write(captor.capture());
    Duration timeout = captor.getValue().getTimeout();
    assertThat(TimeUnit.SECONDS.toNanos(1) - Durations.toNanos(timeout))
        .isAtMost(TimeUnit.MILLISECONDS.toNanos(250));
  }
}
