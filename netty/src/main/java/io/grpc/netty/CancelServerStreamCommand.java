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

package io.grpc.netty;

import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import io.grpc.Status;

/**
 * Command sent from a Netty server stream to the handler to cancel the stream.
 */
final class CancelServerStreamCommand extends WriteQueue.AbstractQueuedCommand {
  private final NettyServerStream.TransportState stream;
  private final Status reason;
  private final PeerNotify peerNotify;

  private CancelServerStreamCommand(
          NettyServerStream.TransportState stream, Status reason, PeerNotify peerNotify) {
    this.stream = Preconditions.checkNotNull(stream, "stream");
    this.reason = Preconditions.checkNotNull(reason, "reason");
    this.peerNotify = Preconditions.checkNotNull(peerNotify, "peerNotify");
  }

  static CancelServerStreamCommand withReset(
          NettyServerStream.TransportState stream, Status reason) {
    return new CancelServerStreamCommand(stream, reason, PeerNotify.RESET);
  }

  static CancelServerStreamCommand withReason(
          NettyServerStream.TransportState stream, Status reason) {
    return new CancelServerStreamCommand(stream, reason, PeerNotify.BEST_EFFORT_STATUS);
  }

  NettyServerStream.TransportState stream() {
    return stream;
  }

  Status reason() {
    return reason;
  }

  boolean wantsHeaders() {
    return peerNotify == PeerNotify.BEST_EFFORT_STATUS;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }

    CancelServerStreamCommand that = (CancelServerStreamCommand) o;

    return this.stream.equals(that.stream)
        && this.reason.equals(that.reason)
        && this.peerNotify.equals(that.peerNotify);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(stream, reason, peerNotify);
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
        .add("stream", stream)
        .add("reason", reason)
        .add("peerNotify", peerNotify)
        .toString();
  }

  private enum PeerNotify {
    /** Notify the peer by sending a RST_STREAM with no other information. */
    RESET,
    /** Notify the peer about the {@link #reason} by sending structured headers, if possible. */
    BEST_EFFORT_STATUS,
  }
}
