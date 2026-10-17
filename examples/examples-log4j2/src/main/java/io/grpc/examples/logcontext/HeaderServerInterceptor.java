/*
 * Copyright 2015 The gRPC Authors
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

package io.grpc.examples.logcontext;

import io.grpc.ForwardingServerCallListener.SimpleForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import java.util.UUID;
import org.apache.logging.log4j.CloseableThreadContext;
import org.apache.logging.log4j.CloseableThreadContext.Instance;

/**
 * A interceptor to handle server header.
 */
public class HeaderServerInterceptor implements ServerInterceptor {
  
  private static final String REQUEST_ID_NAME = "requestId";

  static final Metadata.Key<String> CLIENT_NAME_KEY =
      Metadata.Key.of("clientName", Metadata.ASCII_STRING_MARSHALLER);

  @Override
  public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
      ServerCall<ReqT, RespT> call,
      final Metadata requestHeaders,
      ServerCallHandler<ReqT, RespT> next) {

    final String requestId = UUID.randomUUID().toString();
    final String clientName = String.valueOf(requestHeaders.get(CLIENT_NAME_KEY));

    return new SimpleForwardingServerCallListener<ReqT>(next.startCall(call, requestHeaders)) {
      @Override
      public void onCancel() {
        try (Instance closeable = CloseableThreadContext.put(REQUEST_ID_NAME, requestId)
            .put(CLIENT_NAME_KEY.originalName(), clientName)) {
          super.onCancel();
        }
      }

      @Override
      public void onComplete() {
        try (Instance closeable = CloseableThreadContext.put(REQUEST_ID_NAME, requestId)
            .put(CLIENT_NAME_KEY.originalName(), clientName)) {
          super.onComplete();
        }
      }

      @Override
      public void onMessage(ReqT message) {
        try (Instance closeable = CloseableThreadContext.put(REQUEST_ID_NAME, requestId)
            .put(CLIENT_NAME_KEY.originalName(), clientName)) {
          super.onMessage(message);
        }
      }

      @Override
      public void onReady() {
        try (Instance closeable = CloseableThreadContext.put(REQUEST_ID_NAME, requestId)
            .put(CLIENT_NAME_KEY.originalName(), clientName)) {
          super.onReady();
        }
      }

      @Override
      public void onHalfClose() {
        try (Instance closeable = CloseableThreadContext.put(REQUEST_ID_NAME, requestId)
            .put(CLIENT_NAME_KEY.originalName(), clientName)) {
          super.onHalfClose();
        }
      }
    };
  }
}
