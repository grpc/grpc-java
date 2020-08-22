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

import io.grpc.BufferDrainable;
import io.grpc.Drainable;
import io.grpc.internal.WritableBuffer;
import io.netty.buffer.ByteBuf;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;

/**
 * The {@link WritableBuffer} used by the Netty transport.
 */
class NettyWritableBuffer extends OutputStream implements WritableBuffer {

  private final ByteBuf bytebuf;

  NettyWritableBuffer(ByteBuf bytebuf) {
    this.bytebuf = bytebuf;
  }

  @Override
  public void write(byte[] src, int srcIndex, int length) {
    bytebuf.writeBytes(src, srcIndex, length);
  }

  @Override
  public void write(int b) {
    bytebuf.writeByte(b);
  }

  @Override
  public int write(InputStream stream) throws IOException {
    if (!bytebuf.hasArray()) {
      if (stream instanceof BufferDrainable && bytebuf.nioBufferCount() == 1) {
        ByteBuffer bb = bytebuf.internalNioBuffer(bytebuf.writerIndex(), bytebuf.writableBytes());
        int positionBefore = bb.position();
        ((BufferDrainable) stream).drainTo(bb);
        int written = bb.position() - positionBefore;
        bytebuf.writerIndex(bytebuf.writerIndex() + written);
        return written;
        // Could potentially also include composite here if stream is known length
        // and less than first ByteBuf size. But shouldn't encounter those since
        // output buffers come straight from the allocator
      }
      if (stream instanceof Drainable) {
        return ((Drainable) stream).drainTo(this);
      }
    }
    int writable = bytebuf.writableBytes();
    int written = bytebuf.writeBytes(stream, writable);
    if (written == writable && stream.available() != 0) {
      throw new BufferOverflowException();
    }
    return written;
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
  public void close() {
    bytebuf.release();
  }

  ByteBuf bytebuf() {
    return bytebuf;
  }
}
