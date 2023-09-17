/*
 * Copyright 2023 The gRPC Authors
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

package io.grpc.util;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import io.grpc.Context;
import io.grpc.IntegerMarshaller;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.ServerCalls;
import io.grpc.stub.StreamObserver;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Unit tests for {@link ServerCallTimeoutInterceptor}.
 */
@RunWith(JUnit4.class)
public class ServerCallTimeoutInterceptorTest {
  private static final MethodDescriptor<Integer, Integer> STREAMING_METHOD =
      MethodDescriptor.<Integer, Integer>newBuilder()
          .setType(MethodDescriptor.MethodType.BIDI_STREAMING)
          .setFullMethodName("some/bidi_streaming")
          .setRequestMarshaller(new IntegerMarshaller())
          .setResponseMarshaller(new IntegerMarshaller())
          .build();

  private static final MethodDescriptor<Integer, Integer> UNARY_METHOD =
      STREAMING_METHOD.toBuilder()
          .setType(MethodDescriptor.MethodType.UNARY)
          .setFullMethodName("some/unary")
          .build();

  private static ServerCalls.UnaryMethod<Integer, Integer> sleepingUnaryMethod(int sleepMillis) {
    return new ServerCalls.UnaryMethod<Integer, Integer>() {
      @Override
      public void invoke(Integer req, StreamObserver<Integer> responseObserver) {
        try {
          Thread.sleep(sleepMillis);
          if (Context.current().isCancelled()) {
            responseObserver.onError(new StatusRuntimeException(Status.CANCELLED));
            return;
          }
          responseObserver.onNext(req);
          responseObserver.onCompleted();
        } catch (InterruptedException e) {
          Status status = Context.current().isCancelled() ?
              Status.CANCELLED : Status.INTERNAL;
          responseObserver.onError(
              new StatusRuntimeException(status.withDescription(e.getMessage())));
        }
      }
    };
  }

  @Test
  public void unary_setShouldInterrupt_exceedingTimeout_isInterrupted() {
    ServerCallRecorder serverCall = new ServerCallRecorder(UNARY_METHOD);
    ServerCallHandler<Integer, Integer> callHandler =
        ServerCalls.asyncUnaryCall(sleepingUnaryMethod(100));
    StringBuffer logBuf = new StringBuffer();

    ServerTimeoutManager serverTimeoutManager =
        ServerTimeoutManager.newBuilder(1, TimeUnit.NANOSECONDS)
            .setShouldInterrupt(true)
            .setLogFunction(logBuf::append)
            .build();
    ServerCall.Listener<Integer> listener = new ServerCallTimeoutInterceptor(serverTimeoutManager)
        .interceptCall(serverCall, new Metadata(), callHandler);
    listener.onMessage(42);
    listener.onHalfClose();
    listener.onComplete();
    serverTimeoutManager.shutdown();

    assertThat(serverCall.responses).isEmpty();
    assertEquals(Status.Code.CANCELLED, serverCall.status.getCode());
    assertEquals("server call timeout", serverCall.status.getDescription());
    assertThat(logBuf.toString()).startsWith("Interrupted RPC thread ");
  }

  @Test
  public void unary_byDefault_exceedingTimeout_isCancelledButNotInterrupted() {
    ServerCallRecorder serverCall = new ServerCallRecorder(UNARY_METHOD);
    ServerCallHandler<Integer, Integer> callHandler =
        ServerCalls.asyncUnaryCall(sleepingUnaryMethod(100));
    StringBuffer logBuf = new StringBuffer();

    ServerTimeoutManager serverTimeoutManager =
        ServerTimeoutManager.newBuilder(1, TimeUnit.NANOSECONDS)
            .setLogFunction(logBuf::append)
            .build();
    ServerCall.Listener<Integer> listener = new ServerCallTimeoutInterceptor(serverTimeoutManager)
        .interceptCall(serverCall, new Metadata(), callHandler);
    listener.onMessage(42);
    listener.onHalfClose();
    listener.onComplete();
    serverTimeoutManager.shutdown();

    assertThat(serverCall.responses).isEmpty();
    assertEquals(Status.Code.CANCELLED, serverCall.status.getCode());
    assertEquals("server call timeout", serverCall.status.getDescription());
    assertThat(logBuf.toString()).isEmpty();
  }

  @Test
  public void unary_setShouldInterrupt_withinTimeout_isNotCancelledOrInterrupted() {
    ServerCallRecorder serverCall = new ServerCallRecorder(UNARY_METHOD);
    ServerCallHandler<Integer, Integer> callHandler =
        ServerCalls.asyncUnaryCall(sleepingUnaryMethod(0));
    StringBuffer logBuf = new StringBuffer();

    ServerTimeoutManager serverTimeoutManager =
        ServerTimeoutManager.newBuilder(100, TimeUnit.MILLISECONDS)
            .setShouldInterrupt(true)
            .setLogFunction(logBuf::append)
            .build();
    ServerCall.Listener<Integer> listener = new ServerCallTimeoutInterceptor(serverTimeoutManager)
        .interceptCall(serverCall, new Metadata(), callHandler);
    listener.onMessage(42);
    listener.onHalfClose();
    listener.onComplete();
    serverTimeoutManager.shutdown();

    assertThat(serverCall.responses).isEqualTo(Collections.singletonList(42));
    assertEquals(Status.Code.OK, serverCall.status.getCode());
    assertThat(logBuf.toString()).isEmpty();
  }

  @Test
  public void unary_setZeroTimeout_isNotIntercepted() {
    ServerCallRecorder serverCall = new ServerCallRecorder(UNARY_METHOD);
    ServerCallHandler<Integer, Integer> callHandler =
        ServerCalls.asyncUnaryCall(
            new ServerCalls.UnaryMethod<Integer, Integer>() {
              @Override
              public void invoke(Integer req, StreamObserver<Integer> responseObserver) {
                responseObserver.onNext(req);
                responseObserver.onCompleted();
              }
            });

    ServerTimeoutManager serverTimeoutManager =
        ServerTimeoutManager.newBuilder(0, TimeUnit.NANOSECONDS)
            .setShouldInterrupt(true)
            .build();
    ServerCall.Listener<Integer> listener = new ServerCallTimeoutInterceptor(serverTimeoutManager)
        .interceptCall(serverCall, new Metadata(), callHandler);
    serverTimeoutManager.shutdown();

    assertNotEquals(
        ServerCallTimeoutInterceptor.TimeoutServerCallListener.class, listener.getClass());
  }

  @Test
  public void streaming_isNotIntercepted() {
    ServerCallRecorder serverCall = new ServerCallRecorder(STREAMING_METHOD);
    ServerCallHandler<Integer, Integer> callHandler =
        ServerCalls.asyncBidiStreamingCall(
            new ServerCalls.BidiStreamingMethod<Integer, Integer>() {
              @Override
              public StreamObserver<Integer> invoke(StreamObserver<Integer> responseObserver) {
                return new EchoStreamObserver<>(responseObserver);
              }
            });

    ServerTimeoutManager serverTimeoutManager =
        ServerTimeoutManager.newBuilder(1, TimeUnit.NANOSECONDS).build();
    ServerCall.Listener<Integer> listener = new ServerCallTimeoutInterceptor(serverTimeoutManager)
        .interceptCall(serverCall, new Metadata(), callHandler);
    serverTimeoutManager.shutdown();

    assertNotEquals(
        ServerCallTimeoutInterceptor.TimeoutServerCallListener.class, listener.getClass());
  }

  private static class ServerCallRecorder extends ServerCall<Integer, Integer> {
    private final MethodDescriptor<Integer, Integer> methodDescriptor;
    private final List<Integer> requestCalls = new ArrayList<>();
    private final List<Integer> responses = new ArrayList<>();
    private Status status;
    private boolean isCancelled;
    private boolean isReady;

    public ServerCallRecorder(MethodDescriptor<Integer, Integer> methodDescriptor) {
      this.methodDescriptor = methodDescriptor;
    }

    @Override
    public void request(int numMessages) {
      requestCalls.add(numMessages);
    }

    @Override
    public void sendHeaders(Metadata headers) {
    }

    @Override
    public void sendMessage(Integer message) {
      this.responses.add(message);
    }

    @Override
    public void close(Status status, Metadata trailers) {
      this.status = status;
    }

    @Override
    public boolean isCancelled() {
      return isCancelled;
    }

    @Override
    public boolean isReady() {
      return isReady;
    }

    @Override
    public MethodDescriptor<Integer, Integer> getMethodDescriptor() {
      return methodDescriptor;
    }
  }

  private static class EchoStreamObserver<T> implements StreamObserver<T> {
    private final StreamObserver<T> responseObserver;

    public EchoStreamObserver(StreamObserver<T> responseObserver) {
      this.responseObserver = responseObserver;
    }

    @Override
    public void onNext(T value) {
      responseObserver.onNext(value);
    }

    @Override
    public void onError(Throwable t) {
      responseObserver.onError(t);
    }

    @Override
    public void onCompleted() {
      responseObserver.onCompleted();
    }
  }
}
