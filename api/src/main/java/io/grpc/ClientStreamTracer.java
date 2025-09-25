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
   * Indicates how long the call was delayed, in nanoseconds, due to waiting for name resolution
   * result. If the call option is not set, the call did not experience name resolution delay.
   */
  public static final CallOptions.Key<Long> NAME_RESOLUTION_DELAYED =
      CallOptions.Key.create("io.grpc.ClientStreamTracer.NAME_RESOLUTION_DELAYED");

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
   * Name resolution is completed and the connection starts getting established. This method is only
   * invoked on the streams that encounter such delay.
   *
   * </p>gRPC buffers the client call if the remote address and configurations, e.g. timeouts and
   * retry policy, are not ready. Asynchronously gRPC internally does the name resolution to get
   * this information. The streams that are processed immediately on ready transports by the time
   * the RPC comes do not go through the pending process, thus this callback will not be invoked.
   */
  public void createPendingStream() {
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
   * Headers has been received from the server. This method does not pass ownership to {@code
   * headers}, so implementations must not access the metadata after returning. Modifications to the
   * metadata within this method will be seen by interceptors and the application.
   *
   * @param headers the received header metadata
   */
  public void inboundHeaders(Metadata headers) {
    inboundHeaders();
  }

  /**
   * Trailing metadata has been received from the server. This method does not pass ownership to
   * {@code trailers}, so implementations must not access the metadata after returning.
   * Modifications to the metadata within this method will be seen by interceptors and the
   * application.
   *
   * @param trailers the received trailing metadata
   * @since 1.17.0
   */
  public void inboundTrailers(Metadata trailers) {
  }

  /**
   * Information providing context to the call became available.
   */
  @Internal
  public void addOptionalLabel(String key, String value) {
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
    private final boolean isHedging;

    StreamInfo(
        CallOptions callOptions, int previousAttempts, boolean isTransparentRetry,
        boolean isHedging) {
      this.callOptions = checkNotNull(callOptions, "callOptions");
      this.previousAttempts = previousAttempts;
      this.isTransparentRetry = isTransparentRetry;
      this.isHedging = isHedging;
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
     * Whether the stream is hedging.
     *
     * @since 1.74.0
     */
    public boolean isHedging() {
      return isHedging;
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
          .setIsTransparentRetry(isTransparentRetry)
          .setIsHedging(isHedging);

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
          .add("isHedging", isHedging)
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
      private boolean isHedging;

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
       * Sets whether the stream is hedging.
       *
       * @since 1.74.0
       */
      public Builder setIsHedging(boolean isHedging) {
        this.isHedging = isHedging;
        return this;
      }

      /**
       * Builds a new StreamInfo.
       */
      public StreamInfo build() {
        return new StreamInfo(callOptions, previousAttempts, isTransparentRetry, isHedging);
      }
    }
  }
}
