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

import io.grpc.ByteBufferBacked;
import io.grpc.internal.WritableBuffer;
import io.netty.buffer.ByteBuf;

import java.nio.ByteBuffer;

/**
 * The {@link WritableBuffer} used by the Netty transport.
 */
class NettyWritableBuffer implements WritableBuffer, ByteBufferBacked {

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
  public ByteBuffer getWritableBuffer(int size) {
    if (bytebuf.writableBytes() >= size && size > 0) {
      try {
        return bytebuf.internalNioBuffer(bytebuf.writerIndex(), size);
      } catch (UnsupportedOperationException uoe) {
        // fall-through
      }
    }
    return null;
  }

  @Override
  public void bufferBytesWritten(int size) {
    if (size < 0 || size > bytebuf.writableBytes()) {
      throw new IllegalStateException();
    }
    bytebuf.writerIndex(bytebuf.writerIndex() + size);
  }

  @Override
  public void release() {
    bytebuf.release();
  }

  ByteBuf bytebuf() {
    return bytebuf;
  }
}
