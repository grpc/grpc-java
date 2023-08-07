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

package io.grpc;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import io.grpc.stub.ServerCalls;
import io.grpc.stub.StreamObserver;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link ServerCallTimeoutInterceptor}. */
@RunWith(JUnit4.class)
public class ServerCallTimeoutInterceptorTest {
  static final MethodDescriptor<Integer, Integer> STREAMING_METHOD =
      MethodDescriptor.<Integer, Integer>newBuilder()
          .setType(MethodDescriptor.MethodType.BIDI_STREAMING)
          .setFullMethodName("some/bidi_streaming")
          .setRequestMarshaller(new IntegerMarshaller())
          .setResponseMarshaller(new IntegerMarshaller())
          .build();

  static final MethodDescriptor<Integer, Integer> UNARY_METHOD =
      STREAMING_METHOD.toBuilder()
          .setType(MethodDescriptor.MethodType.UNARY)
          .setFullMethodName("some/unary")
          .build();

  @Test
  public void unaryServerCallWithinTimeout() {
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
    StringBuffer logBuf = new StringBuffer();

    ServerTimeoutManager serverTimeoutManager = new ServerTimeoutManager(
        10, TimeUnit.MILLISECONDS, logBuf::append);
    ServerCall.Listener<Integer> listener = new ServerCallTimeoutInterceptor(serverTimeoutManager)
        .interceptCall(serverCall, new Metadata(), callHandler);
    listener.onMessage(42);
    listener.onHalfClose();
    listener.onComplete();

    assertThat(serverCall.responses).isEqualTo(Collections.singletonList(42));
    assertEquals(Status.Code.OK, serverCall.status.getCode());
    assertThat(logBuf.toString()).isEmpty();
  }

  @Test
  public void unaryServerCallExceedsTimeout() {
    ServerCallRecorder serverCall = new ServerCallRecorder(UNARY_METHOD);
    ServerCallHandler<Integer, Integer> callHandler =
        ServerCalls.asyncUnaryCall(
            new ServerCalls.UnaryMethod<Integer, Integer>() {
              @Override
              public void invoke(Integer req, StreamObserver<Integer> responseObserver) {
                try {
                  Thread.sleep(10);
                  responseObserver.onNext(req);
                  responseObserver.onCompleted();
                } catch (InterruptedException e) {
                  Status status = Status.ABORTED.withDescription(e.getMessage());
                  responseObserver.onError(new StatusRuntimeException(status));
                }
              }
            });
    StringBuffer logBuf = new StringBuffer();

    ServerTimeoutManager serverTimeoutManager = new ServerTimeoutManager(
        1, TimeUnit.NANOSECONDS, logBuf::append);
    ServerCall.Listener<Integer> listener = new ServerCallTimeoutInterceptor(serverTimeoutManager)
        .interceptCall(serverCall, new Metadata(), callHandler);
    listener.onMessage(42);
    listener.onHalfClose();
    listener.onComplete();

    assertThat(serverCall.responses).isEmpty();
    assertEquals(Status.Code.ABORTED, serverCall.status.getCode());
    assertEquals("sleep interrupted", serverCall.status.getDescription());
    assertThat(logBuf.toString()).startsWith("Interrupted RPC thread ");
  }

  @Test
  public void unaryServerCallSkipsZeroTimeout() {
    ServerCallRecorder serverCall = new ServerCallRecorder(UNARY_METHOD);
    ServerCallHandler<Integer, Integer> callHandler =
        ServerCalls.asyncUnaryCall(
            new ServerCalls.UnaryMethod<Integer, Integer>() {
              @Override
              public void invoke(Integer req, StreamObserver<Integer> responseObserver) {
                try {
                  Thread.sleep(10);
                  responseObserver.onNext(req);
                  responseObserver.onCompleted();
                } catch (InterruptedException e) {
                  Status status = Status.ABORTED.withDescription(e.getMessage());
                  responseObserver.onError(new StatusRuntimeException(status));
                }
              }
            });
    AtomicBoolean timeoutScheduled = new AtomicBoolean(false);

    ServerTimeoutManager serverTimeoutManager = new ServerTimeoutManager(
        0, TimeUnit.MILLISECONDS, null) {
      @Override
      public boolean withTimeout(Runnable invocation) {
        boolean result = super.withTimeout(invocation);
        timeoutScheduled.set(result);
        return result;
      }
    };
    ServerCall.Listener<Integer> listener = new ServerCallTimeoutInterceptor(serverTimeoutManager)
        .interceptCall(serverCall, new Metadata(), callHandler);
    listener.onMessage(42);
    listener.onHalfClose();
    listener.onComplete();

    assertThat(serverCall.responses).isEqualTo(Collections.singletonList(42));
    assertEquals(Status.Code.OK, serverCall.status.getCode());
    assertFalse(timeoutScheduled.get());
  }

  @Test
  public void streamingServerCallIsNotIntercepted() {
    ServerCallRecorder serverCall = new ServerCallRecorder(STREAMING_METHOD);
    ServerCallHandler<Integer, Integer> callHandler =
        ServerCalls.asyncBidiStreamingCall(new ServerCalls.BidiStreamingMethod<Integer, Integer>() {
          @Override
          public StreamObserver<Integer> invoke(StreamObserver<Integer> responseObserver) {
            return new EchoStreamObserver<>(responseObserver);
          }
        });
    AtomicBoolean interceptMethodCalled = new AtomicBoolean(false);

    ServerTimeoutManager serverTimeoutManager = new ServerTimeoutManager(
        10, TimeUnit.MILLISECONDS, null) {
      @Override
      public boolean withTimeout(Runnable invocation) {
        interceptMethodCalled.set(true);
        return true;
      }
    };
    ServerCall.Listener<Integer> listener = new ServerCallTimeoutInterceptor(serverTimeoutManager)
        .interceptCall(serverCall, new Metadata(), callHandler);
    listener.onMessage(42);
    listener.onHalfClose();
    listener.onComplete();

    assertThat(serverCall.responses).isEqualTo(Collections.singletonList(42));
    assertEquals(Status.Code.OK, serverCall.status.getCode());
    assertFalse(interceptMethodCalled.get());
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
