/*
 * Copyright 2017, gRPC Authors All rights reserved.
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

package io.grpc.testing.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptors;
import io.grpc.ForwardingServerCall.SimpleForwardingServerCall;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCall.Listener;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.ClientCalls;
import io.grpc.testing.integration.Messages.EchoStatus;
import io.grpc.testing.integration.Messages.SimpleRequest;
import io.grpc.testing.integration.Messages.SimpleResponse;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;

/**
 * More interop tests that are not defined in
 * https://github.com/grpc/grpc/blob/master/doc/interop-test-descriptions.md
 */
abstract class MoreInteropTests extends AbstractInteropTest {
  /**
   * The test should be launched with the default Server and Channel executor. The test uses a
   * custom call executor with a latch, which can block the thread that has just called {@link
   * io.grpc.internal.ClientStreamListener#closed} when the server sends out failure status to the
   * client, letting the client exception handling task win the race with the blocked thread's later
   * tasks that are being held off by the latch.
   */
  @Test
  public void statusCodeAndMessage_withLatchedCallExecutor() {
    final CountDownLatch latch = new CountDownLatch(1);
    final AtomicBoolean latchTimeout = new AtomicBoolean();
    final AtomicBoolean serverCallOnClose = new AtomicBoolean();
    ServerInterceptor serverCallOnCloseInterceptor = new ServerInterceptor() {
      @Override
      public <ReqT, RespT> Listener<ReqT> interceptCall(
          ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
        return next.startCall(
            new SimpleForwardingServerCall<ReqT, RespT>(call) {
              @Override
              public void close(Status status, Metadata trailers) {
                serverCallOnClose.set(true);
                delegate().close(status, trailers);
              }
            },
            headers);
      }
    };
    dynamicServerInterceptorRef.set(serverCallOnCloseInterceptor);
    Executor latchedExecutor =
        new Executor() {
          @Override
          public void execute(Runnable command) {

            command.run(); // The client thread will get the failure status by future.get()

            if (serverCallOnClose.get()) {
              try {
                // Blocks the current thread, and let the thread that is calling future.get() win
                // the race.
                latch.await(operationTimeoutMillis(), TimeUnit.MILLISECONDS);
              } catch (InterruptedException ignorable) {
                latch.countDown();
                latchTimeout.set(true);
              }
            }
          }
        };
    CallOptions callOptions = CallOptions.DEFAULT.withExecutor(latchedExecutor);
    Channel channel =
        ClientInterceptors.intercept(this.channel, tracerSetupInterceptor);
    ClientCall<SimpleRequest, SimpleResponse> call =
        channel.newCall(TestServiceGrpc.METHOD_UNARY_CALL, callOptions);

    int errorCode = 2;
    String errorMessage = "test status message";
    EchoStatus responseStatus = EchoStatus.newBuilder()
        .setCode(errorCode)
        .setMessage(errorMessage)
        .build();
    SimpleRequest simpleRequest = SimpleRequest.newBuilder()
        .setResponseStatus(responseStatus)
        .build();

    try {
      ClientCalls.blockingUnaryCall(call, simpleRequest);
      fail();
    } catch (StatusRuntimeException e) {
      assertEquals(Status.Code.UNKNOWN, e.getStatus().getCode());
      assertEquals(errorMessage, e.getStatus().getDescription());
    } finally {
      latch.countDown(); // Release the latch after exception handling is done.
    }

    assertServerStatsTrace("grpc.testing.TestService/UnaryCall", Status.Code.UNKNOWN, null, null);
    assertFalse(latchTimeout.get());
  }
}
