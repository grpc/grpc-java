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
  public void streamCreated(Attributes transportAttrs, Metadata headers) {
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
     * Creates a {@link ClientStreamTracer} for a new client stream.
     *
     * @param info information about the stream
     *
     * @since 1.40.0
     */
    public ClientStreamTracer newClientStreamTracer(final StreamInfo info) {
      // Provide a default implementation of this method for compatibility with old Factory
      // implementations.
      return new ClientStreamTracer() {
        volatile ClientStreamTracer delegate = new ClientStreamTracer() {};

        @Override
        public void streamCreated(Attributes transportAttrs, Metadata headers) {
          delegate = newClientStreamTracer(info, headers);
          delegate.streamCreated(transportAttrs, headers);
        }

        @Override
        public void outboundHeaders() {
          delegate.outboundHeaders();
        }

        @Override
        public void inboundHeaders() {
          delegate.inboundHeaders();
        }

        @Override
        public void inboundTrailers(Metadata trailers) {
          delegate.inboundTrailers(trailers);
        }

        @Override
        public void streamClosed(Status status) {
          delegate.streamClosed(status);
        }

        @Override
        public void outboundMessage(int seqNo) {
          delegate.outboundMessage(seqNo);
        }

        @Override
        public void inboundMessage(int seqNo) {
          delegate.inboundMessage(seqNo);
        }

        @Override
        public void outboundMessageSent(int seqNo, long optionalWireSize,
            long optionalUncompressedSize) {
          delegate.outboundMessageSent(seqNo, optionalWireSize, optionalUncompressedSize);
        }

        @Override
        public void inboundMessageRead(int seqNo, long optionalWireSize,
            long optionalUncompressedSize) {
          delegate.inboundMessageRead(seqNo, optionalWireSize, optionalUncompressedSize);
        }

        @Override
        public void outboundWireSize(long bytes) {
          delegate.outboundWireSize(bytes);
        }

        @Override
        public void outboundUncompressedSize(long bytes) {
          delegate.outboundUncompressedSize(bytes);
        }

        @Override
        public void inboundWireSize(long bytes) {
          delegate.inboundWireSize(bytes);
        }

        @Override
        public void inboundUncompressedSize(long bytes) {
          delegate.inboundUncompressedSize(bytes);
        }
      };
    }

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
     * @deprecated Use {@link ClientStreamTracer#streamCreated(Attributes, Metadata)} of the
     *             returned tracer to handle the headers instead.
     */
    @Deprecated
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
    private final Attributes transportAttrs;
    private final CallOptions callOptions;
    private final boolean isTransparentRetry;

    StreamInfo(Attributes transportAttrs, CallOptions callOptions, boolean isTransparentRetry) {
      this.transportAttrs = checkNotNull(transportAttrs, "transportAttrs");
      this.callOptions = checkNotNull(callOptions, "callOptions");
      this.isTransparentRetry = isTransparentRetry;
    }

    /**
     * Returns the attributes of the transport that this stream was created on.
     *
     * @deprecated Use {@link ClientStreamTracer#streamCreated(Attributes, Metadata)} to handle
     *             the transport Attributes instead.
     */
    @Deprecated
    @Grpc.TransportAttr
    public Attributes getTransportAttrs() {
      return transportAttrs;
    }

    /**
     * Returns the effective CallOptions of the call.
     */
    public CallOptions getCallOptions() {
      return callOptions;
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
          .setTransportAttrs(transportAttrs)
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
          .toString();
    }

    /**
     * Builds {@link StreamInfo} objects.
     *
     * @since 1.21.0
     */
    public static final class Builder {
      private Attributes transportAttrs = Attributes.EMPTY;
      private CallOptions callOptions = CallOptions.DEFAULT;
      private boolean isTransparentRetry;

      Builder() {
      }

      /**
       * Sets the attributes of the transport that this stream was created on.  This field is
       * optional.
       *
       * @deprecated Use {@link ClientStreamTracer#streamCreated(Attributes, Metadata)} to handle
       *             the transport Attributes instead.
       */
      @Deprecated
      @Grpc.TransportAttr
      public Builder setTransportAttrs(Attributes transportAttrs) {
        this.transportAttrs = checkNotNull(transportAttrs, "transportAttrs cannot be null");
        return this;
      }

      /**
       * Sets the effective CallOptions of the call.  This field is optional.
       */
      public Builder setCallOptions(CallOptions callOptions) {
        this.callOptions = checkNotNull(callOptions, "callOptions cannot be null");
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
        return new StreamInfo(transportAttrs, callOptions, isTransparentRetry);
      }
    }
  }
}
