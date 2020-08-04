/*
 * Copyright 2014 The gRPC Authors
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

import com.google.common.base.Preconditions;
import io.grpc.internal.AbstractReadableBuffer;
import io.netty.buffer.ByteBuf;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * A {@link java.nio.Buffer} implementation that is backed by a Netty {@link ByteBuf}. This class
 * does not call {@link ByteBuf#retain}, so if that is needed it should be called prior to creating
 * this buffer.
 */
class NettyReadableBuffer extends AbstractReadableBuffer {
  private final ByteBuf buffer;
  private boolean closed;

  NettyReadableBuffer(ByteBuf buffer) {
    this.buffer = Preconditions.checkNotNull(buffer, "buffer");
  }

  ByteBuf buffer() {
    return buffer;
  }

  @Override
  public int readableBytes() {
    return buffer.readableBytes();
  }

  @Override
  public void skipBytes(int length) {
    buffer.skipBytes(length);
  }

  @Override
  public int readUnsignedByte() {
    return buffer.readUnsignedByte();
  }

  @Override
  public void readBytes(byte[] dest, int index, int length) {
    buffer.readBytes(dest, index, length);
  }

  @Override
  public void readBytes(ByteBuffer dest) {
    buffer.readBytes(dest);
  }

  @Override
  public void readBytes(OutputStream dest, int length) {
    try {
      buffer.readBytes(dest, length);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public NettyReadableBuffer readBytes(int length) {
    return new NettyReadableBuffer(buffer.readRetainedSlice(length));
  }

  @Override
  public boolean shouldUseByteBuffer() {
    return buffer.nioBufferCount() > 0;
  }

  @Override
  public List<ByteBuffer> readByteBuffers(int length) {
    if (buffer.readableBytes() < length) {
      throw new IndexOutOfBoundsException();
    }
    List<ByteBuffer> res = buffer.nioBufferCount() == 1
        ? Collections.singletonList(buffer.nioBuffer(buffer.readerIndex(), length))
        : Arrays.asList(buffer.nioBuffers(buffer.readerIndex(), length));
    buffer.skipBytes(length);
    return res;
  }

  @Override
  public boolean hasArray() {
    return buffer.hasArray();
  }

  @Override
  public byte[] array() {
    return buffer.array();
  }

  @Override
  public int arrayOffset() {
    return buffer.arrayOffset() + buffer.readerIndex();
  }

  /**
   * If the first call to close, calls {@link ByteBuf#release} to release the internal Netty buffer.
   */
  @Override
  public void close() {
    // Don't allow slices to close. Also, only allow close to be called once.
    if (!closed) {
      closed = true;
      buffer.release();
    }
  }
}
