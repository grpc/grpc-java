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
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.xds.internal.headermutations.HeaderMutations;
import io.grpc.xds.internal.headermutations.HeaderMutator;

/**
 * A simple failing client call that lazily applies response mutations to trailers during start.
 */
final class FailingCallWithTrailerMutations<ReqT, RespT> extends ClientCall<ReqT, RespT> {
  private final Status status;
  private final HeaderMutations responseHeaderMutations;
  private final HeaderMutator headerMutator;

  FailingCallWithTrailerMutations(
      Status status,
      HeaderMutations responseHeaderMutations,
      HeaderMutator headerMutator) {
    this.status = status;
    this.responseHeaderMutations = responseHeaderMutations;
    this.headerMutator = headerMutator;
  }

  @Override
  public void start(Listener<RespT> responseListener, Metadata headers) {
    // Lazily allocate and apply response mutations to trailers copy on start!
    Metadata trailers = new Metadata();
    headerMutator.applyMutations(responseHeaderMutations, trailers);
    responseListener.onClose(status, trailers);
  }

  @Override
  public void request(int numMessages) {}

  @Override
  public void cancel(String message, Throwable cause) {}

  @Override
  public void halfClose() {}

  @Override
  public void sendMessage(ReqT message) {}
}
