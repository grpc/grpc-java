/*
 * Copyright 2017 The gRPC Authors
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

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.base.MoreObjects;
import javax.annotation.concurrent.ThreadSafe;

/**
 * {@link StreamTracer} for the client-side.
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/2861")
@ThreadSafe
public abstract class ClientStreamTracer extends StreamTracer {

  /**
   * The stream is being created on a ready transport.
   *
   * @param headers the mutable initial metadata. Modifications to it will be sent to the socket but
   *     not be seen by client interceptors and the application.
   *
   * @since 1.40.0
   */
  public void streamCreated(@Grpc.TransportAttr Attributes transportAttrs, Metadata headers) {
  }

  /**
   * Headers has been sent to the socket.
   */
  public void outboundHeaders() {
  }

  /**
   * Headers has been received from the server.
   */
  public void inboundHeaders() {
  }

  /**
   * Trailing metadata has been received from the server.
   *
   * @param trailers the mutable trailing metadata.  Modifications to it will be seen by
   *                 interceptors and the application.
   * @since 1.17.0
   */
  public void inboundTrailers(Metadata trailers) {
  }

  /**
   * Factory class for {@link ClientStreamTracer}.
   */
  public abstract static class Factory {
    /**
     * Creates a {@link ClientStreamTracer} for a new client stream.  This is called inside the
     * transport when it's creating the stream.
     *
     * @param info information about the stream
     * @param headers the mutable headers of the stream. It can be safely mutated within this
     *        method.  Changes made to it will be sent by the stream.  It should not be saved
     *        because it is not safe for read or write after the method returns.
     *
     * @since 1.20.0
     */
    public ClientStreamTracer newClientStreamTracer(StreamInfo info, Metadata headers) {
      throw new UnsupportedOperationException("Not implemented");
    }
  }

  /**
   * Information about a stream.
   *
   * <p>Note this class doesn't override {@code equals()} and {@code hashCode}, as is the case for
   * {@link CallOptions}.
   *
   * @since 1.20.0
   */
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/2861")
  public static final class StreamInfo {
    private final CallOptions callOptions;
    private final int previousAttempts;
    private final boolean isTransparentRetry;

    StreamInfo(
        CallOptions callOptions, int previousAttempts, boolean isTransparentRetry) {
      this.callOptions = checkNotNull(callOptions, "callOptions");
      this.previousAttempts = previousAttempts;
      this.isTransparentRetry = isTransparentRetry;
    }

    /**
     * Returns the effective CallOptions of the call.
     */
    public CallOptions getCallOptions() {
      return callOptions;
    }

    /**
     * Returns the number of preceding attempts for the RPC.
     *
     * @since 1.40.0
     */
    public int getPreviousAttempts() {
      return previousAttempts;
    }

    /**
     * Whether the stream is a transparent retry.
     *
     * @since 1.40.0
     */
    public boolean isTransparentRetry() {
      return isTransparentRetry;
    }

    /**
     * Converts this StreamInfo into a new Builder.
     *
     * @since 1.21.0
     */
    public Builder toBuilder() {
      return new Builder()
          .setCallOptions(callOptions)
          .setPreviousAttempts(previousAttempts)
          .setIsTransparentRetry(isTransparentRetry);
    }

    /**
     * Creates an empty Builder.
     *
     * @since 1.21.0
     */
    public static Builder newBuilder() {
      return new Builder();
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("callOptions", callOptions)
          .add("previousAttempts", previousAttempts)
          .add("isTransparentRetry", isTransparentRetry)
          .toString();
    }

    /**
     * Builds {@link StreamInfo} objects.
     *
     * @since 1.21.0
     */
    public static final class Builder {
      private CallOptions callOptions = CallOptions.DEFAULT;
      private int previousAttempts;
      private boolean isTransparentRetry;

      Builder() {
      }

      /**
       * Sets the effective CallOptions of the call.  This field is optional.
       */
      public Builder setCallOptions(CallOptions callOptions) {
        this.callOptions = checkNotNull(callOptions, "callOptions cannot be null");
        return this;
      }

      /**
       * Set the number of preceding attempts of the RPC.
       *
       * @since 1.40.0
       */
      public Builder setPreviousAttempts(int previousAttempts) {
        this.previousAttempts = previousAttempts;
        return this;
      }

      /**
       * Sets whether the stream is a transparent retry.
       *
       * @since 1.40.0
       */
      public Builder setIsTransparentRetry(boolean isTransparentRetry) {
        this.isTransparentRetry = isTransparentRetry;
        return this;
      }

      /**
       * Builds a new StreamInfo.
       */
      public StreamInfo build() {
        return new StreamInfo(callOptions, previousAttempts, isTransparentRetry);
      }
    }
  }
}
