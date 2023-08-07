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

package io.grpc;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertEquals;

import io.grpc.stub.ServerCalls;
import io.grpc.stub.StreamObserver;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
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
                responseObserver.onNext(42);
                responseObserver.onCompleted();
              }
            });
    StringBuffer logBuf = new StringBuffer();

    ServerTimeoutManager serverTimeoutManager = new ServerTimeoutManager(
        10, TimeUnit.MILLISECONDS, logBuf::append);
    ServerCall.Listener<Integer> listener = new ServerCallTimeoutInterceptor(serverTimeoutManager)
        .interceptCall(serverCall, new Metadata(), callHandler);
    listener.onMessage(1);
    listener.onHalfClose();

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
                  responseObserver.onNext(42);
                  responseObserver.onCompleted();
                } catch (InterruptedException e) {
                  Status status = Status.ABORTED.withDescription(e.getMessage());
                  responseObserver.onError(new StatusRuntimeException(status));
                }
              }
            });
    StringBuffer logBuf = new StringBuffer();

    ServerTimeoutManager serverTimeoutManager = new ServerTimeoutManager(
        1, TimeUnit.MILLISECONDS, logBuf::append);
    ServerCall.Listener<Integer> listener = new ServerCallTimeoutInterceptor(serverTimeoutManager)
        .interceptCall(serverCall, new Metadata(), callHandler);
    listener.onMessage(1);
    listener.onHalfClose();

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
                  Thread.sleep(1);
                  responseObserver.onNext(42);
                  responseObserver.onCompleted();
                } catch (InterruptedException e) {
                  Status status = Status.ABORTED.withDescription(e.getMessage());
                  responseObserver.onError(new StatusRuntimeException(status));
                }
              }
            });
    StringBuffer logBuf = new StringBuffer();

    ServerTimeoutManager serverTimeoutManager = new ServerTimeoutManager(
        0, TimeUnit.MILLISECONDS, logBuf::append);
    ServerCall.Listener<Integer> listener = new ServerCallTimeoutInterceptor(serverTimeoutManager)
        .interceptCall(serverCall, new Metadata(), callHandler);
    listener.onMessage(1);
    listener.onHalfClose();

    assertThat(serverCall.responses).isEqualTo(Collections.singletonList(42));
    assertEquals(Status.Code.OK, serverCall.status.getCode());
    assertThat(logBuf.toString()).isEmpty();
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
}
