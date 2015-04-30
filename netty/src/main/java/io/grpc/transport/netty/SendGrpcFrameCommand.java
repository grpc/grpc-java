/*
 * Copyright 2014, Google Inc. All rights reserved.
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

package io.grpc.transport.netty;

import io.grpc.transport.AbstractStream;
import io.netty.buffer.ByteBuf;
import io.netty.util.IllegalReferenceCountException;

/**
 * Command sent from the transport to the Netty channel to send a GRPC frame to the remote endpoint.
 */
class SendGrpcFrameCommand extends AbstractNettyCommand {
  private final ByteBuf content;
  private final AbstractStream<Integer> stream;
  private final boolean endStream;

  SendGrpcFrameCommand(boolean flush, AbstractStream<Integer> stream, ByteBuf content,
                       boolean endStream) {
    super(flush);
    this.content = content;
    this.stream = stream;
    this.endStream = endStream;
  }

  int streamId() {
    return stream.id();
  }

  boolean endStream() {
    return endStream;
  }

  public ByteBuf content() {
    if (content.refCnt() <= 0) {
      throw new IllegalReferenceCountException(content.refCnt());
    }
    return content;
  }

  @Override
  public boolean equals(Object that) {
    if (that == null || !super.equals(that)) {
      return false;
    }
    SendGrpcFrameCommand thatCmd = (SendGrpcFrameCommand) that;
    return thatCmd.stream.equals(stream) && thatCmd.endStream == endStream
        && thatCmd.content().equals(content());
  }

  @Override
  public String toString() {
    return getClass().getSimpleName() + "(streamId=" + streamId()
        + ", endStream=" + endStream + ", content=" + content()
        + ", flush=" + flush + ")";
  }

  @Override
  public int hashCode() {
    int hash = content().hashCode();
    hash = hash * 31 + stream.hashCode();
    if (endStream) {
      hash = -hash;
    }
    return hash;
  }
}
