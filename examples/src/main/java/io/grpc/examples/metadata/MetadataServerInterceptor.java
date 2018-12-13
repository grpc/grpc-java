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

package io.grpc.examples.metadata;

import com.google.common.annotations.VisibleForTesting;
import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.ForwardingServerCall.SimpleForwardingServerCall;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

/**
 * A server-side interceptor that puts received custom headers in the Context, and sends custom
 * headers and trailing metadata.
 */
public class MetadataServerInterceptor implements ServerInterceptor {

  private static final Logger logger = Logger.getLogger(MetadataServerInterceptor.class.getName());

  final AtomicReference<String> outgoingHeader = new AtomicReference<>();
  final AtomicReference<String> outgoingTrailer = new AtomicReference<>();

  static final Metadata.Key<String> CUSTOM_HEADER_KEY =
      Metadata.Key.of("custom_server_header_key", Metadata.ASCII_STRING_MARSHALLER);

  static final Metadata.Key<String> CUSTOM_TRAILER_KEY =
      Metadata.Key.of("custom_server_trailer_key", Metadata.ASCII_STRING_MARSHALLER);

  static final Context.Key<String> CLIENT_HEADER_CTX_KEY = Context.key("examples.client-header");

  @Override
  public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
      ServerCall<ReqT, RespT> call,
      final Metadata requestHeaders,
      ServerCallHandler<ReqT, RespT> next) {
    ServerCall<ReqT, RespT> interceptedCall =
        new SimpleForwardingServerCall<ReqT, RespT>(call) {
            @Override
            public void sendHeaders(Metadata responseHeaders) {
              String outboundHeader = outgoingHeader.get();
              if (outboundHeader != null) {
                responseHeaders.put(CUSTOM_HEADER_KEY, outboundHeader);
              }
              super.sendHeaders(responseHeaders);
            }

            @Override
            public void close(Status status, Metadata trailers) {
              String outboundTrailer = outgoingTrailer.get();
              if (outboundTrailer != null) {
                trailers.put(CUSTOM_TRAILER_KEY, outboundTrailer);
              }
              super.close(status, trailers);
            }
        };
    String inboundHeader = requestHeaders.get(MetadataClientInterceptor.CUSTOM_HEADER_KEY);
    if (inboundHeader != null) {
      // Create a new Context with the information from the received header, and make the
      // CallHandler and the ServerCall.Listener run under this new Context.
      return Contexts.interceptCall(
          Context.current().withValue(CLIENT_HEADER_CTX_KEY, inboundHeader),
          interceptedCall, requestHeaders, next);
    } else {
      // Not changing the Context.
      return next.startCall(interceptedCall, requestHeaders);
    }
  }
}
