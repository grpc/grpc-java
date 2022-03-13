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

import com.google.protobuf.Duration;
import com.google.protobuf.util.Durations;
import io.grpc.Attributes;
import io.grpc.Context;
import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.MethodDescriptor.Marshaller;
import io.grpc.MethodDescriptor.MethodType;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.Status;
import io.grpc.internal.NoopServerCall;
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
 * Tests for {@link InternalLoggingServerInterceptor}.
 */
@RunWith(JUnit4.class)
public class InternalLoggingServerInterceptorTest {

  @Rule
  public final MockitoRule mockito = MockitoJUnit.rule();

  public static final Marshaller<byte[]> BYTEARRAY_MARSHALLER = new ByteArrayMarshaller();
  private static final Charset US_ASCII = Charset.forName("US-ASCII");

  private InternalLoggingServerInterceptor.Factory factory;
  private AtomicReference<ServerCall<byte[], byte[]>> interceptedLoggingCall;
  ServerCall.Listener<byte[]> capturedListener;
  private ServerCall.Listener<byte[]> mockListener;
  // capture these manually because ServerCall can not be mocked
  private AtomicReference<Metadata> actualServerInitial;
  private AtomicReference<byte[]> actualResponse;
  private AtomicReference<Status> actualStatus;
  private AtomicReference<Metadata> actualTrailers;
  private final Sink mockSink = mock(GcpLogSink.class);
  private SocketAddress peer;

  @Before
  @SuppressWarnings("unchecked")
  public void setup() throws Exception {
    factory = new InternalLoggingServerInterceptor.FactoryImpl(mockSink);
    interceptedLoggingCall = new AtomicReference<>();
    mockListener = mock(ServerCall.Listener.class);
    actualServerInitial = new AtomicReference<>();
    actualResponse = new AtomicReference<>();
    actualStatus = new AtomicReference<>();
    actualTrailers = new AtomicReference<>();
    peer = new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 1234);
  }

  @Test
  public void internalLoggingServerInterceptor() {
    Metadata clientInitial = new Metadata();
    final MethodDescriptor<byte[], byte[]> method =
        MethodDescriptor.<byte[], byte[]>newBuilder()
            .setType(MethodType.UNKNOWN)
            .setFullMethodName("service/method")
            .setRequestMarshaller(BYTEARRAY_MARSHALLER)
            .setResponseMarshaller(BYTEARRAY_MARSHALLER)
            .build();
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
                new ServerCallHandler<byte[], byte[]>() {
                  @Override
                  public ServerCall.Listener<byte[]> startCall(
                      ServerCall<byte[], byte[]> call,
                      Metadata headers) {
                    interceptedLoggingCall.set(call);
                    return mockListener;
                  }
                });
    // receive request header
    {
      EventType expectedRequestHeaderEvent = EventType.GRPC_CALL_REQUEST_HEADER;
      ArgumentCaptor<GrpcLogRecord> captor = ArgumentCaptor.forClass(GrpcLogRecord.class);
      verify(mockSink).write(captor.capture());
      assertEquals(captor.getValue().getEventType(),
          expectedRequestHeaderEvent);
      verifyNoMoreInteractions(mockSink);
    }

    // send response header
    {
      EventType expectedResponseHeaderEvent = EventType.GRPC_CALL_RESPONSE_HEADER;
      ArgumentCaptor<GrpcLogRecord> captor = ArgumentCaptor.forClass(GrpcLogRecord.class);
      Metadata serverInital = new Metadata();
      interceptedLoggingCall.get().sendHeaders(serverInital);
      verify(mockSink, times(2)).write(captor.capture());
      assertEquals(captor.getValue().getEventType(),
          expectedResponseHeaderEvent);
      verifyNoMoreInteractions(mockSink);
      assertSame(serverInital, actualServerInitial.get());
    }

    // receive request message
    {
      EventType expectedRequestMessageEvent = EventType.GRPC_CALL_REQUEST_MESSAGE;
      ArgumentCaptor<GrpcLogRecord> captor = ArgumentCaptor.forClass(GrpcLogRecord.class);
      byte[] request = "this is a request".getBytes(US_ASCII);
      capturedListener.onMessage(request);
      verify(mockSink, times(3)).write(captor.capture());
      assertEquals(captor.getValue().getEventType(),
          expectedRequestMessageEvent);
      verifyNoMoreInteractions(mockSink);
      verify(mockListener).onMessage(same(request));
    }

    // client half close
    {
      EventType expectedHalfCloseEvent = EventType.GRPC_CALL_HALF_CLOSE;
      ArgumentCaptor<GrpcLogRecord> captor = ArgumentCaptor.forClass(GrpcLogRecord.class);
      capturedListener.onHalfClose();
      verify(mockSink, times(4)).write(captor.capture());
      assertEquals(captor.getValue().getEventType(),
          expectedHalfCloseEvent);
      verifyNoMoreInteractions(mockSink);
      verify(mockListener).onHalfClose();
    }

    // send response message
    {
      EventType expectedResponseMessageEvent = EventType.GRPC_CALL_RESPONSE_MESSAGE;
      ArgumentCaptor<GrpcLogRecord> captor = ArgumentCaptor.forClass(GrpcLogRecord.class);
      byte[] response = "this is a response".getBytes(US_ASCII);
      interceptedLoggingCall.get().sendMessage(response);
      verify(mockSink, times(5)).write(captor.capture());
      assertEquals(captor.getValue().getEventType(),
          expectedResponseMessageEvent);
      verifyNoMoreInteractions(mockSink);
      assertSame(response, actualResponse.get());
    }

    // send trailer
    {
      EventType expectedTrailerEvent = EventType.GRPC_CALL_TRAILER;
      ArgumentCaptor<GrpcLogRecord> captor = ArgumentCaptor.forClass(GrpcLogRecord.class);
      Status status = Status.INTERNAL.withDescription("trailer description");
      Metadata trailers = new Metadata();

      interceptedLoggingCall.get().close(status, trailers);
      verify(mockSink, times(6)).write(captor.capture());
      assertEquals(captor.getValue().getEventType(),
          expectedTrailerEvent);
      verifyNoMoreInteractions(mockSink);
      assertSame(status, actualStatus.get());
      assertSame(trailers, actualTrailers.get());
    }

    // cancel
    {
      EventType expectedCancelEvent = EventType.GRPC_CALL_CANCEL;
      ArgumentCaptor<GrpcLogRecord> captor = ArgumentCaptor.forClass(GrpcLogRecord.class);
      capturedListener.onCancel();
      verify(mockSink, times(7)).write(captor.capture());
      assertEquals(captor.getValue().getEventType(),
          expectedCancelEvent);
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
    final ServerCallHandler<byte[], byte[]> noopHandler = new ServerCallHandler<byte[], byte[]>() {
      @Override
      public ServerCall.Listener<byte[]> startCall(
          ServerCall<byte[], byte[]> call,
          Metadata headers) {
        return new ServerCall.Listener<byte[]>() {};
      }
    };

    // We expect the contents of the "grpc-timeout" header to be installed the context
    Context.current()
        .withDeadlineAfter(1, TimeUnit.SECONDS, Executors.newSingleThreadScheduledExecutor())
        .run(new Runnable() {
          @Override
          public void run() {
            ServerCall.Listener<byte[]> unused =
                factory.create()
                    .interceptCall(noopServerCall, new Metadata(), noopHandler);
          }
        });
    ArgumentCaptor<GrpcLogRecord> captor = ArgumentCaptor.forClass(GrpcLogRecord.class);
    verify(mockSink, times(1)).write(captor.capture());
    verifyNoMoreInteractions(mockSink);
    Duration timeout = captor.getValue().getTimeout();
    assertThat(TimeUnit.SECONDS.toNanos(1) - Durations.toNanos(timeout))
        .isAtMost(TimeUnit.MILLISECONDS.toNanos(250));
  }
}
