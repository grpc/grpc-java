/*
 * Copyright 2017 The gRPC Authors
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

import io.grpc.ExperimentalApi;
import io.grpc.ForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;

/**
 * A class that intercepts uncaught exceptions of type {@link StatusRuntimeException} and handles
 * them by closing the {@link ServerCall}, and transmitting the exception's status and metadata
 * to the client.
 *
 * <p>Without this interceptor, gRPC will strip all details and close the {@link ServerCall} with
 * a generic {@link Status#UNKNOWN} code.
 *
 * <p>Security warning: the {@link Status} and {@link Metadata} may contain sensitive server-side
 * state information, and generally should not be sent to clients. Only install this interceptor
 * if all clients are trusted.
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/2189")
public final class TransmitStatusRuntimeExceptionInterceptor implements ServerInterceptor {
  private TransmitStatusRuntimeExceptionInterceptor() {
  }

  public static ServerInterceptor instance() {
    return new TransmitStatusRuntimeExceptionInterceptor();
  }

  @Override
  public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
      ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
    final ServerCall<ReqT, RespT> serverCall = new SerializingServerCall<>(call);
    ServerCall.Listener<ReqT> listener = next.startCall(serverCall, headers);
    return new ForwardingServerCallListener.SimpleForwardingServerCallListener<ReqT>(listener) {
      @Override
      public void onMessage(ReqT message) {
        try {
          super.onMessage(message);
        } catch (StatusRuntimeException e) {
          closeWithException(e);
        }
      }

      @Override
      public void onHalfClose() {
        try {
          super.onHalfClose();
        } catch (StatusRuntimeException e) {
          closeWithException(e);
        }
      }

      @Override
      public void onCancel() {
        try {
          super.onCancel();
        } catch (StatusRuntimeException e) {
          closeWithException(e);
        }
      }

      @Override
      public void onComplete() {
        try {
          super.onComplete();
        } catch (StatusRuntimeException e) {
          closeWithException(e);
        }
      }

      @Override
      public void onReady() {
        try {
          super.onReady();
        } catch (StatusRuntimeException e) {
          closeWithException(e);
        }
      }

      private void closeWithException(StatusRuntimeException t) {
        Metadata metadata = t.getTrailers();
        if (metadata == null) {
          metadata = new Metadata();
        }
        serverCall.close(t.getStatus(), metadata);
      }
    };
  }
}
