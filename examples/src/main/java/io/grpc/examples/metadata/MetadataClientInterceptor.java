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
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall.SimpleForwardingClientCall;
import io.grpc.ForwardingClientCallListener.SimpleForwardingClientCallListener;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

/**
 * A client-side interceptor that sends custom headers and receives custom headers and trailing
 * metadata.
 */
public class MetadataClientInterceptor implements ClientInterceptor {

  private static final Logger logger = Logger.getLogger(MetadataClientInterceptor.class.getName());

  static final Metadata.Key<String> CUSTOM_HEADER_KEY =
      Metadata.Key.of("custom_client_header_key", Metadata.ASCII_STRING_MARSHALLER);

  final AtomicReference<String> outgoingHeader = new AtomicReference<>();
  final BlockingQueue<String> receivedHeaders = new LinkedBlockingQueue<>();
  final BlockingQueue<String> receivedTrailers = new LinkedBlockingQueue<>();

  @Override
  public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(MethodDescriptor<ReqT, RespT> method,
      CallOptions callOptions, Channel next) {
    return new SimpleForwardingClientCall<ReqT, RespT>(next.newCall(method, callOptions)) {

      @Override
      public void start(Listener<RespT> responseListener, Metadata headers) {
        /* put custom header */
        String outboundHeader = outgoingHeader.get();
        if (outboundHeader != null) {
          headers.put(CUSTOM_HEADER_KEY, outboundHeader);
        }
        super.start(new SimpleForwardingClientCallListener<RespT>(responseListener) {
          @Override
          public void onHeaders(Metadata headers) {
            /**
             * if you don't need receive header from server,
             * you can use {@link io.grpc.stub.MetadataUtils#attachHeaders}
             * directly to send header
             */
            String inboundHeader = headers.get(MetadataServerInterceptor.CUSTOM_HEADER_KEY);
            if (inboundHeader != null) {
              receivedHeaders.add(inboundHeader);
            }
            super.onHeaders(headers);
          }

          @Override
          public void onClose(Status status, Metadata trailers) {
            String inboundTrailer = trailers.get(MetadataServerInterceptor.CUSTOM_TRAILER_KEY);
            if (inboundTrailer != null) {
              receivedTrailers.add(inboundTrailer);
            }
            super.onClose(status, trailers);
          }
        }, headers);
      }
    };
  }
}
