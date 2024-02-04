/*
 * Copyright 2021 The gRPC Authors
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

package io.grpc;

/**
 * Builder for creating a {@link StatusRuntimeException}.
 *
 * @since 1.62.0
 */
public class StatusRuntimeExceptionBuilder {
  private Status status;
  private Metadata trailers = null;
  private boolean fillInStackTrace = true;

  /**
   * Sets the status.
   *
   * @since 1.62.0
   */
  public StatusRuntimeExceptionBuilder setStatus(final Status status) {
    this.status = status;
    return this;
  }

  /**
   * Sets the trailers.
   *
   * @since 1.62.0
   */
  public StatusRuntimeExceptionBuilder setTrailers(final Metadata trailers) {
    this.trailers = trailers;
    return this;
  }

  /**
   * Sets whether to fill in the stack trace.
   *
   * @since 1.62.0
   */
  public StatusRuntimeExceptionBuilder setFillInStackTrace(final boolean fillInStackTrace) {
    this.fillInStackTrace = fillInStackTrace;
    return this;
  }

  /**
   * Builds the exception.
   *
   * @since 1.62.0
   */
  public StatusRuntimeException build() {
    final StatusRuntimeException statusRuntimeException =
        new StatusRuntimeException(status, trailers, fillInStackTrace);
    statusRuntimeException.fillInStackTrace();
    return statusRuntimeException;
  }
}
