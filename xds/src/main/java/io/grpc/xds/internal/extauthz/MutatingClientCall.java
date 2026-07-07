/*
 * Copyright 2025 The gRPC Authors
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

package io.grpc.xds.internal.extauthz;

import io.grpc.ClientCall;
import io.grpc.ForwardingClientCall;
import io.grpc.ForwardingClientCallListener;
import io.grpc.Metadata;
import io.grpc.xds.internal.headermutations.HeaderMutations;
import io.grpc.xds.internal.headermutations.HeaderMutator;

/**
 * A {@link ForwardingClientCall} that unifies both request and response header mutations
 * symmetrically.
 */
final class MutatingClientCall<ReqT, RespT>
    extends ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT> {

  private final HeaderMutations requestHeaderMutations;
  private final HeaderMutations responseHeaderMutations;
  private final HeaderMutator headerMutator;

  MutatingClientCall(
      ClientCall<ReqT, RespT> delegate,
      HeaderMutations requestHeaderMutations,
      HeaderMutations responseHeaderMutations,
      HeaderMutator headerMutator) {
    super(delegate);
    this.requestHeaderMutations = requestHeaderMutations;
    this.responseHeaderMutations = responseHeaderMutations;
    this.headerMutator = headerMutator;
  }

  @Override
  public void start(Listener<RespT> responseListener, Metadata headers) {
    // 1. Apply allowed request header mutations lazily before forwarding start!
    headerMutator.applyMutations(requestHeaderMutations, headers);

    if (responseHeaderMutations.headers().isEmpty()
        && responseHeaderMutations.headersToRemove().isEmpty()) {
      super.start(responseListener, headers);
      return;
    }

    Listener<RespT> wrappedListener = new ForwardingClientCallListener
        .SimpleForwardingClientCallListener<RespT>(responseListener) {
      @Override
      public void onHeaders(Metadata headers) {
        // 2. Apply allowed response header mutations when headers are returned by the server
        headerMutator.applyMutations(responseHeaderMutations, headers);
        super.onHeaders(headers);
      }
    };
    super.start(wrappedListener, headers);
  }
}
