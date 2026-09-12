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

package io.grpc.binder.internal;

import static com.google.common.base.Preconditions.checkState;

import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.internal.ClientStreamListener;
import javax.annotation.Nullable;

/** Fake {@link ClientStreamListener} for capturing headers, trailers, status, and messages. */
public final class FakeClientStreamListener extends FakeStreamListener
    implements ClientStreamListener {
  @Nullable private Metadata headers;
  @Nullable private RpcProgress closedProgress;
  @Nullable private Metadata closedTrailers;

  @Override
  public void headersRead(Metadata headers) {
    checkState(!isClosed(), "headersRead invoked after closed");
    checkState(this.headers == null, "headersRead invoked more than once");
    this.headers = headers;
  }

  @Override
  public void closed(Status status, RpcProgress rpcProgress, Metadata trailers) {
    checkState(!isClosed(), "closed invoked more than once");
    this.closedStatus = status;
    this.closedProgress = rpcProgress;
    this.closedTrailers = trailers;
  }

  /** Returns the initial metadata headers received, or {@code null} if none. */
  @Nullable
  public Metadata getHeaders() {
    return headers;
  }

  /** Returns the RPC progress passed to {@link #closed}, or {@code null} if not closed. */
  @Nullable
  public RpcProgress getClosedProgress() {
    return closedProgress;
  }

  /** Returns the trailing metadata passed to {@link #closed}, or {@code null} if not closed. */
  @Nullable
  public Metadata getClosedTrailers() {
    return closedTrailers;
  }
}
