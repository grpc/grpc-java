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

package io.grpc.servlet;

import io.grpc.ExperimentalApi;
import io.grpc.ForwardingServerCall;
import io.grpc.ForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;

/**
 * Some servlet containers don't support sending trailers only (Tomcat).
 * They send an empty data frame with an end_stream flag.
 * This is not supported by gRPC as is expects end_stream flag in trailer or trailer-only frame
 * To avoid this empty data frame, use this interceptor to force the servlet container to either
 *   - send a header frame, an empty data frame and a trailer frame with end_stream (Tomcat)
 *   - send a header frame and a trailer frame with end_stream (Jetty, Undertow)
 * This interceptor is added when forcing trailers in the server builder
 * {@link ServletServerBuilder#forceTrailers(boolean)}
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/10124")
public class ForceTrailersServerInterceptor implements ServerInterceptor {
  @Override
  public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
      ServerCall<ReqT, RespT> call,
      Metadata headers,
      ServerCallHandler<ReqT, RespT> next) {
    ForceTrailersServerCall<ReqT, RespT> interceptedCall = new ForceTrailersServerCall<>(call);
    try {
      return new ForwardingServerCallListener.SimpleForwardingServerCallListener<ReqT>(
          next.startCall(interceptedCall, headers)) {
        @Override
        public void onMessage(ReqT message) {
          try {
            super.onMessage(message);
          } catch (Throwable t) {
            sendHeaders();
            throw t;
          }
        }

        @Override
        public void onHalfClose() {
          try {
            super.onHalfClose();
          } catch (Throwable t) {
            sendHeaders();
            throw t;
          }
        }

        @Override
        public void onCancel() {
          try {
            super.onCancel();
          } catch (Throwable t) {
            sendHeaders();
            throw t;
          }
        }

        @Override
        public void onComplete() {
          try {
            super.onComplete();
          } catch (Throwable t) {
            sendHeaders();
            throw t;
          }
        }

        @Override
        public void onReady() {
          try {
            super.onReady();
          } catch (Throwable t) {
            sendHeaders();
            throw t;
          }
        }

        private void sendHeaders() {
          interceptedCall.maybeSendEmptyHeaders();
        }
      };
    } catch (RuntimeException e) {
      interceptedCall.maybeSendEmptyHeaders();
      throw e;
    }
  }

  static class ForceTrailersServerCall<ReqT, RespT> extends
      ForwardingServerCall.SimpleForwardingServerCall<ReqT, RespT> {

    private volatile boolean headersSent = false;

    ForceTrailersServerCall(ServerCall<ReqT, RespT> delegate) {
      super(delegate);
    }

    void maybeSendEmptyHeaders() {
      if (!headersSent) {
        this.sendHeaders(new Metadata());
      }
    }

    @Override
    public void sendHeaders(Metadata headers) {
      headersSent = true;
      super.sendHeaders(headers);
    }

    @Override
    public void close(Status status, Metadata trailers) {
      maybeSendEmptyHeaders();
      super.close(status, trailers);
    }
  }
}
