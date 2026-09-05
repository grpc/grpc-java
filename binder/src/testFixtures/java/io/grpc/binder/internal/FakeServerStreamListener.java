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

import io.grpc.Status;
import io.grpc.internal.ServerStreamListener;

/** Fake {@link ServerStreamListener} for capturing half-close, status, and messages. */
public final class FakeServerStreamListener extends FakeStreamListener
    implements ServerStreamListener {
  private boolean halfClosed;

  @Override
  public void halfClosed() {
    checkState(!isClosed(), "halfClosed invoked after closed");
    checkState(!halfClosed, "halfClosed invoked more than once");
    this.halfClosed = true;
  }

  @Override
  public void closed(Status status) {
    checkState(!isClosed(), "closed invoked more than once");
    this.closedStatus = status;
  }

  /** Returns whether {@link #halfClosed} was called. */
  public boolean isHalfClosed() {
    return halfClosed;
  }
}
