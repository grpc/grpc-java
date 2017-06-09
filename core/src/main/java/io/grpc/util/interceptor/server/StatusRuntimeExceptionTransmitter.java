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

package io.grpc.util.interceptor.server;

import io.grpc.Attributes;
import io.grpc.ExperimentalApi;
import io.grpc.ForwardingServerCall;
import io.grpc.ForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import javax.annotation.Nullable;

/**
 * A class that intercepts uncaught exceptions of type {@link StatusRuntimeException} and handles
 * them by closing the {@link ServerCall}, and transmitting the exception's details to the client.
 *
 * <p>Without this interceptor, gRPC will strip all details and close the {@link ServerCall} with
 * a generic {@link Status#UNKNOWN} code.
 *
 * <p>Security warning: the {@link Status} and {@link Metadata} may contain sensitive server-side
 * state information, and generally should not be sent to clients. Only install this interceptor
 * if all clients are trusted.
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/2189")
public final class StatusRuntimeExceptionTransmitter {
  private StatusRuntimeExceptionTransmitter() {
    // do not instantiate
  }

  public static final ServerInterceptor INSTANCE = new ServerInterceptor() {
    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
        ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
      final ServerCall<ReqT, RespT> syncServerCall =
          new SynchronizedServerCall<ReqT, RespT>(call);
      ServerCall.Listener<ReqT> listener = next.startCall(syncServerCall, headers);
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
          Metadata metadata = Status.trailersFromThrowable(t);
          if (metadata == null) {
            metadata = new Metadata();
          }
          syncServerCall.close(Status.fromThrowable(t), metadata);
        }
      };
    }
  };

  /**
   * A {@link ServerCall} that wraps around a non thread safe delegate and provides thread safe
   * access.
   */
  private static class SynchronizedServerCall<ReqT, RespT> extends
      ForwardingServerCall.SimpleForwardingServerCall<ReqT, RespT> {

    public SynchronizedServerCall(ServerCall<ReqT, RespT> delegate) {
      super(delegate);
    }

    @Override
    public synchronized void sendMessage(RespT message) {
      super.sendMessage(message);
    }

    @Override
    public synchronized void request(int numMessages) {
      super.request(numMessages);
    }

    @Override
    public synchronized void sendHeaders(Metadata headers) {
      super.sendHeaders(headers);
    }

    @Override
    public synchronized boolean isReady() {
      return super.isReady();
    }

    @Override
    public synchronized void close(Status status, Metadata trailers) {
      super.close(status, trailers);
    }

    @Override
    public synchronized boolean isCancelled() {
      return super.isCancelled();
    }

    @Override
    public synchronized void setMessageCompression(boolean enabled) {
      super.setMessageCompression(enabled);
    }

    @Override
    public synchronized void setCompression(String compressor) {
      super.setCompression(compressor);
    }

    @Override
    public synchronized Attributes getAttributes() {
      return super.getAttributes();
    }

    @Nullable
    @Override
    public synchronized String getAuthority() {
      return super.getAuthority();
    }
  }

}
