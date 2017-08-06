/*
 * Copyright 2015, gRPC Authors All rights reserved.
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

package io.grpc.examples.header;

import com.google.common.annotations.VisibleForTesting;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall.SimpleForwardingClientCall;
import io.grpc.ForwardingClientCallListener.SimpleForwardingClientCallListener;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import java.util.logging.Logger;

/**
 * A interceptor to handle client header.
 */
public class HeaderClientInterceptor implements ClientInterceptor {

  private static final Logger logger = Logger.getLogger(HeaderClientInterceptor.class.getName());

  @VisibleForTesting
  static final Metadata.Key<String> CUSTOM_HEADER_KEY =
      Metadata.Key.of("custom_client_header_key", Metadata.ASCII_STRING_MARSHALLER);

  @Override
  public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(MethodDescriptor<ReqT, RespT> method,
      CallOptions callOptions, Channel next) {
    ClientCall<ReqT, RespT> clientCall = next.newCall(method, callOptions);

    // Decorate the ClientCall that will be registered in the outbound path. In this example we
    // are only interested in overriding the start() method, which is the place to intercept the
    // inbound ClientCall.Listener.
    SimpleForwardingClientCall<ReqT, RespT> decoratedClientCall =
        new SimpleForwardingClientCall<ReqT, RespT>(clientCall) {
          @Override
          public void start(Listener<RespT> responseListener, Metadata headers) {
            /* put custom header */
            headers.put(CUSTOM_HEADER_KEY, "customRequestValue");

            // Decorate the Listener before passing it into grpc.
            // Note that because the inbound and outbound handlers are independent,
            // it is not safe for the ClientCall (outbound) and ClientCall.Listener (inbound)
            // to interact here without synchronization.
            SimpleForwardingClientCallListener<RespT> decoratedListener =
                new SimpleForwardingClientCallListener<RespT>(responseListener) {
                  @Override
                  public void onHeaders(Metadata headers) {
                    /**
                     * if you don't need receive header from server,
                     * you can use {@link MetadataUtils#attachHeaders}
                     * directly to send header
                     */
                    logger.info("header received from server:" + headers);
                    super.onHeaders(headers);
                  }
                };
            super.start(decoratedListener, headers);
          }
        };
    return decoratedClientCall;
  }
}
