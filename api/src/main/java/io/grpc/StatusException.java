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

package io.grpc;

import javax.annotation.Nullable;

/**
 * {@link Status} in Exception form, for propagating Status information via exceptions. This is
 * semantically equivalent to {@link StatusRuntimeException}, except for usage in APIs that promote
 * checked exceptions. gRPC's stubs favor {@code StatusRuntimeException}.
 */
public class StatusException extends Exception {
  private static final long serialVersionUID = -660954903976144640L;
  private final Status status;
  private final Metadata trailers;
  private final boolean fillInStackTrace;

  /**
   * Constructs an exception with status, trailers, and whether to fill in the stack trace.
   * See also {@link Status#asException()} and {@link Status#asException(Metadata)}.
   *
   * @since 1.0.0
   */
  protected StatusException(Status status, @Nullable Metadata trailers, boolean fillInStackTrace) {
    super(Status.formatThrowableMessage(status), status.getCause());
    this.status = status;
    this.trailers = trailers;
    this.fillInStackTrace = fillInStackTrace;
  }

  @Override
  public synchronized Throwable fillInStackTrace() {
    // Let's observe final variables in two states!  This works because Throwable will invoke this
    // method before fillInStackTrace is set, thus doing nothing.  After the constructor has set
    // fillInStackTrace, this method will properly fill it in.  Additionally, sub classes may call
    // this normally, because fillInStackTrace will either be set, or this method will be
    // overriden.
    return fillInStackTrace ? super.fillInStackTrace() : this;
  }

  /**
   * Returns the status code as a {@link Status} object.
   *
   * @since 1.0.0
   */
  public final Status getStatus() {
    return status;
  }

  /**
   * Returns the received trailers.
   *
   * @since 1.0.0
   */
  public final Metadata getTrailers() {
    return trailers;
  }

  /**
   * Builder for creating a {@link StatusException}.
   *
   * @since 1.62.0
   */
  public static class Builder {
    private Status status;
    private Metadata trailers = null;
    private boolean fillInStackTrace = true;

    /**
     * Sets the status.
     *
     * @since 1.62.0
     */
    public Builder setStatus(final Status status) {
      this.status = status;
      return this;
    }

    /**
     * Sets the trailers.
     *
     * @since 1.62.0
     */
    public Builder setTrailers(final Metadata trailers) {
      this.trailers = trailers;
      return this;
    }

    /**
     * Sets whether to fill in the stack trace.
     *
     * @since 1.62.0
     */
    public Builder setFillInStackTrace(final boolean fillInStackTrace) {
      this.fillInStackTrace = fillInStackTrace;
      return this;
    }

    /**
     * Builds the exception.
     *
     * @since 1.62.0
     */
    public StatusException build() {
      final StatusException statusException =
          new StatusException(status, trailers, fillInStackTrace);
      statusException.fillInStackTrace();
      return statusException;
    }
  }
}
