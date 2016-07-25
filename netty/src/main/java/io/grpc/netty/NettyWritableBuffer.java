/*
 * Copyright 2015, Google Inc. All rights reserved.
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

package io.grpc.netty;

import io.grpc.internal.WritableBuffer;
import io.netty.buffer.ByteBuf;

/**
 * The {@link WritableBuffer} used by the Netty transport.
 */
class NettyWritableBuffer implements WritableBuffer {

  private final ByteBuf bytebuf;

  NettyWritableBuffer(ByteBuf bytebuf) {
    this.bytebuf = bytebuf;
  }

  @Override
  public void write(byte[] src, int srcIndex, int length) {
    bytebuf.writeBytes(src, srcIndex, length);
  }

  @Override
  public void write(byte b) {
    bytebuf.writeByte(b);
  }

  @Override
  public int writableBytes() {
    return bytebuf.writableBytes();
  }

  @Override
  public int readableBytes() {
    return bytebuf.readableBytes();
  }

  @Override
  public void release() {
    bytebuf.release();
  }

  ByteBuf bytebuf() {
    return bytebuf;
  }

  @Override
  public WritableBuffer snip() {
    // If there isn't much room left, don't bother.
    if (bytebuf.writableBytes() < 16) {
      return null;
    }
    ByteBuf remaining = bytebuf.slice(bytebuf.writerIndex(), bytebuf.writableBytes()).retain();
    remaining.writerIndex(0);
    return new NettyWritableBuffer(remaining);
  }
}
