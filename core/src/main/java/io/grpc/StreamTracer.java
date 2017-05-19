/*
 * Copyright 2017, Google Inc. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 *    * Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 *    * Redistributions in binary form must reproduce the above
 * copyright notice, this list of conditions and the following disclaimer
 * in the documentation and/or other materials provided with the
 * distribution.
 *
 *    * Neither the name of Google Inc. nor the names of its
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package io.grpc;

import javax.annotation.concurrent.ThreadSafe;

/**
 * Listens to events on a stream to collect metrics.
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/2861")
@ThreadSafe
public abstract class StreamTracer {
  /**
   * Stream is closed.  This will be called exactly once.
   */
  public void streamClosed(Status status) {
  }

  /**
   * An outbound message has been passed to the stream.  This is called as soon as the stream knows
   * about the message, but doesn't have further guarantee such as whether the message is serialized
   * or not.
   */
  public void outboundMessage() {
  }

  /**
   * An inbound message has been received by the stream.  This is called as soon as the stream knows
   * about the message, but doesn't have further guarantee such as whether the message is
   * deserialized or not.
   */
  public void inboundMessage() {
  }

  /**
   * An outbound message has been completely serialized, revealing its wire size.
   */
  public void outboundMessageSerialized(long wireSize) {
  }

  /**
   * An inbound message has been completely read from the wire, revealing its wire size.
   */
  public void inboundMessageReceived(long wireSize) {
  }

  /**
   * The wire size of some outbound data is revealed. This can only used to record the accumulative
   * outbound wire size.  gRPC makes the best effort to call it as soon the information is
   * available, although there is not guarantee wrt timing or granularity.
   */
  public void outboundWireSize(long bytes) {
  }

  /**
   * The uncompressed size of some outbound data is revealed. This can only used to record the
   * accumulative outbound uncompressed size. gRPC makes the best effort to call it as soon as the
   * information is available, although there is not guarantee wrt timing or granularity.
   */
  public void outboundUncompressedSize(long bytes) {
  }

  /**
   * The wire size of some inbound data is revealed. This can only be used to record the
   * accumulative received wire size. gRPC makes the best effort to call it as soon as the
   * information is available, although there is not guarantee wrt timing or granularity.
   */
  public void inboundWireSize(long bytes) {
  }

  /**
   * The uncompressed size of some inbound data is revealed. This can only used to record the
   * accumulative received uncompressed size. gRPC makes the best effort to call it as soon as the
   * information is available, although there is not guarantee wrt timing or granularity.
   */
  public void inboundUncompressedSize(long bytes) {
  }
}
