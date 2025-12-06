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

package io.grpc.internal;

import io.grpc.ClientCall;
import io.grpc.Metadata;
import io.grpc.Status;
import javax.annotation.Nullable;

/**
 * A {@link ClientCall} that fails immediately upon starting.
 */
public final class FailingClientCall<ReqT, RespT> extends ClientCall<ReqT, RespT> {

  private final Status error;

  /**
   * Creates a new call that will fail with the given error.
   */
  public FailingClientCall(Status error) {
    this.error = error;
  }

  /**
   * Immediately fails the call by calling {@link Listener#onClose}.
   */
  @Override
  public void start(Listener<RespT> responseListener, Metadata headers) {
    responseListener.onClose(error, new Metadata());
  }

  @Override
  public void request(int numMessages) {}

  @Override
  public void cancel(@Nullable String message, @Nullable Throwable cause) {}

  @Override
  public void halfClose() {}

  @Override
  public void sendMessage(ReqT message) {}
}
