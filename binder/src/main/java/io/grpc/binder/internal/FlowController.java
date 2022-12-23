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
package io.grpc.binder.internal;

import javax.annotation.concurrent.GuardedBy;

/** Keeps track of the number of bytes on the wire in a single direction. */
final class FlowController {
  private final int maxUnackedBytes;

  @GuardedBy("this")
  private long totalBytesSent;

  @GuardedBy("this")
  private long totalBytesAckedByPeer;

  // @GuardedBy("this") for writes but not reads.
  private volatile boolean transmitWindowFull;

  /**
   * Creates a new instance of {@link FlowController}.
   *
   * @param maxUnackedBytes a weak limit on the number of bytes sent but not yet acknowledged
   */
  public FlowController(int maxUnackedBytes) {
    this.maxUnackedBytes = maxUnackedBytes;
  }

  /**
   * Returns true iff the number of reported bytes sent but not yet acknowledged is greater than or
   * equal to {@code maxUnackedBytes}.
   */
  public boolean isTransmitWindowFull() {
    return transmitWindowFull;
  }

  /**
   * Informs this flow controller that more data has been sent.
   *
   * @param numBytesSent a non-negative number of additional bytes sent
   * @return true iff this report caused {@link #isTransmitWindowFull()} to transition to true
   */
  public synchronized boolean notifyBytesSent(long numBytesSent) {
    totalBytesSent += numBytesSent;
    if ((totalBytesSent - totalBytesAckedByPeer) >= maxUnackedBytes && !transmitWindowFull) {
      transmitWindowFull = true;
      return true;
    }
    return false;
  }

  /**
   * Processes an acknowledgement from our peer.
   *
   * @param numBytesAcked the total number of bytes ever received over this connection modulo 2^64,
   *     with the most significant bit of this value encoded in this argument's sign bit
   * @return true iff this report caused {@link #isTransmitWindowFull()} to transition to false
   */
  public synchronized boolean handleAcknowledgedBytes(long numBytesAcked) {
    totalBytesAckedByPeer = wrapAwareMax(totalBytesAckedByPeer, numBytesAcked);
    if ((totalBytesSent - totalBytesAckedByPeer) < maxUnackedBytes && transmitWindowFull) {
      transmitWindowFull = false;
      return true;
    }
    return false;
  }

  private static final long wrapAwareMax(long a, long b) {
    return a - b < 0 ? b : a;
  }
}
