/*
 * Copyright 2026 The gRPC Authors
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
 * A server interceptor that puts per-call values into the Log4j 2 {@code ThreadContext} so that
 * every log statement made while handling the call is automatically annotated with them.
 *
 * <p>The values are scoped to the thread rather than to an {@link io.grpc.Context}, because that is
 * what logging frameworks read from. Each callback re-populates the {@code ThreadContext} and
 * clears it again on the way out, since gRPC does not guarantee that every callback for a call runs
 * on the same thread.
 *
 * <p>Note that the context is only established for the callbacks below. It is not set while
 * interceptors further down the chain run their {@code interceptCall()}, so a downstream
 * interceptor logging from {@code interceptCall()} will not see these values.
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
    final String headerValue = requestHeaders.get(CLIENT_NAME_KEY);
    final String clientName = headerValue != null ? headerValue : "unknown";

    return new SimpleForwardingServerCallListener<ReqT>(next.startCall(call, requestHeaders)) {

      /**
       * Populates the Log4j 2 {@code ThreadContext} for the duration of the try-with-resources
       * block that calls this method.
       */
      private Instance logContext() {
        return CloseableThreadContext.put(REQUEST_ID_NAME, requestId)
            .put(CLIENT_NAME_KEY.originalName(), clientName);
      }

      @Override
      public void onCancel() {
        try (Instance ignored = logContext()) {
          super.onCancel();
        }
      }

      @Override
      public void onComplete() {
        try (Instance ignored = logContext()) {
          super.onComplete();
        }
      }

      @Override
      public void onMessage(ReqT message) {
        try (Instance ignored = logContext()) {
          super.onMessage(message);
        }
      }

      @Override
      public void onReady() {
        try (Instance ignored = logContext()) {
          super.onReady();
        }
      }

      @Override
      public void onHalfClose() {
        // For unary calls this is the callback that invokes the service method, so this is the one
        // that matters most. The others are here so that the pattern is correct if this code is
        // copied into a streaming service.
        try (Instance ignored = logContext()) {
          super.onHalfClose();
        }
      }
    };
  }
}
