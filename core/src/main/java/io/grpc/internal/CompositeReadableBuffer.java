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

package io.grpc.internal;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Queue;

/**
 * A {@link ReadableBuffer} that is composed of 0 or more {@link ReadableBuffer}s. This provides a
 * facade that allows multiple buffers to be treated as one.
 *
 * <p>When a buffer is added to a composite, its life cycle is controlled by the composite. Once
 * the composite has read past the end of a given buffer, that buffer is automatically closed and
 * removed from the composite.
 */
public class CompositeReadableBuffer extends AbstractReadableBuffer {

  private int readableBytes;
  private final Queue<ReadableBuffer> buffers;

  public CompositeReadableBuffer(int initialCapacity) {
    buffers = new ArrayDeque<>(initialCapacity);
  }

  public CompositeReadableBuffer() {
    buffers = new ArrayDeque<>();
  }

  /**
   * Adds a new {@link ReadableBuffer} at the end of the buffer list. After a buffer is added, it is
   * expected that this {@code CompositeBuffer} has complete ownership. Any attempt to modify the
   * buffer (i.e. modifying the readable bytes) may result in corruption of the internal state of
   * this {@code CompositeBuffer}.
   */
  public void addBuffer(ReadableBuffer buffer) {
    if (!(buffer instanceof CompositeReadableBuffer)) {
      buffers.add(buffer);
      readableBytes += buffer.readableBytes();
      return;
    }

    CompositeReadableBuffer compositeBuffer = (CompositeReadableBuffer) buffer;
    while (!compositeBuffer.buffers.isEmpty()) {
      ReadableBuffer subBuffer = compositeBuffer.buffers.remove();
      buffers.add(subBuffer);
    }
    readableBytes += compositeBuffer.readableBytes;
    compositeBuffer.readableBytes = 0;
    compositeBuffer.close();
  }

  @Override
  public int readableBytes() {
    return readableBytes;
  }

  private static final NoThrowReadOperation<Void> UBYTE_OP =
      new NoThrowReadOperation<Void>() {
        @Override
        public int read(ReadableBuffer buffer, int length, Void unused, int value) {
          return buffer.readUnsignedByte();
        }
      };

  @Override
  public int readUnsignedByte() {
    return executeNoThrow(UBYTE_OP, 1, null, 0);
  }

  private static final NoThrowReadOperation<Void> SKIP_OP =
      new NoThrowReadOperation<Void>() {
        @Override
        public int read(ReadableBuffer buffer, int length, Void unused, int unused2) {
          buffer.skipBytes(length);
          return 0;
        }
      };

  @Override
  public void skipBytes(int length) {
    executeNoThrow(SKIP_OP, length, null, 0);
  }

  private static final NoThrowReadOperation<byte[]> BYTE_ARRAY_OP =
      new NoThrowReadOperation<byte[]>() {
        @Override
        public int read(ReadableBuffer buffer, int length, byte[] dest, int offset) {
          buffer.readBytes(dest, offset, length);
          return offset + length;
        }
      };

  @Override
  public void readBytes(byte[] dest, int destOffset, int length) {
    executeNoThrow(BYTE_ARRAY_OP, length, dest, destOffset);
  }

  private static final NoThrowReadOperation<ByteBuffer> BYTE_BUF_OP =
      new NoThrowReadOperation<ByteBuffer>() {
        @Override
        public int read(ReadableBuffer buffer, int length, ByteBuffer dest, int unused) {
          // Change the limit so that only lengthToCopy bytes are available.
          int prevLimit = dest.limit();
          ((Buffer) dest).limit(dest.position() + length);
          // Write the bytes and restore the original limit.
          buffer.readBytes(dest);
          ((Buffer) dest).limit(prevLimit);
          return 0;
        }
      };

  @Override
  public void readBytes(ByteBuffer dest) {
    executeNoThrow(BYTE_BUF_OP, dest.remaining(), dest, 0);
  }

  private static final ReadOperation<OutputStream> STREAM_OP =
      new ReadOperation<OutputStream>() {
        @Override
        public int read(ReadableBuffer buffer, int length, OutputStream dest, int unused)
            throws IOException {
          buffer.readBytes(dest, length);
          return 0;
        }
      };

  @Override
  public void readBytes(OutputStream dest, int length) throws IOException {
    execute(STREAM_OP, length, dest, 0);
  }

  @Override
  public ReadableBuffer readBytes(int length) {
    if (length <= 0) {
      return ReadableBuffers.empty();
    }
    checkReadable(length);
    readableBytes -= length;

    ReadableBuffer newBuffer = null;
    CompositeReadableBuffer newComposite = null;
    do {
      ReadableBuffer buffer = buffers.peek();
      int readable = buffer.readableBytes();
      ReadableBuffer readBuffer;
      if (readable > length) {
        readBuffer = buffer.readBytes(length);
        length = 0;
      } else {
        readBuffer = buffers.poll();
        length -= readable;
      }
      if (newBuffer == null) {
        newBuffer = readBuffer;
      } else {
        if (newComposite == null) {
          newComposite =
              new CompositeReadableBuffer(length == 0 ? 2 : Math.min(buffers.size() + 2, 16));
          newComposite.addBuffer(newBuffer);
          newBuffer = newComposite;
        }
        newComposite.addBuffer(readBuffer);
      }
    } while (length > 0);
    return newBuffer;
  }

  @Override
  public void close() {
    while (!buffers.isEmpty()) {
      buffers.remove().close();
    }
  }

  /**
   * Executes the given {@link ReadOperation} against the {@link ReadableBuffer}s required to
   * satisfy the requested {@code length}.
   */
  private <T> int execute(ReadOperation<T> op, int length, T dest, int value) throws IOException {
    checkReadable(length);

    if (!buffers.isEmpty()) {
      advanceBufferIfNecessary();
    }

    for (; length > 0 && !buffers.isEmpty(); advanceBufferIfNecessary()) {
      ReadableBuffer buffer = buffers.peek();
      int lengthToCopy = Math.min(length, buffer.readableBytes());

      // Perform the read operation for this buffer.
      value = op.read(buffer, lengthToCopy, dest, value);

      length -= lengthToCopy;
      readableBytes -= lengthToCopy;
    }

    if (length > 0) {
      // Should never get here.
      throw new AssertionError("Failed executing read operation");
    }

    return value;
  }

  private <T> int executeNoThrow(NoThrowReadOperation<T> op, int length, T dest, int value) {
    try {
      return execute(op, length, dest, value);
    } catch (IOException e) {
      throw new AssertionError(e); // shouldn't happen
    }
  }

  /**
   * If the current buffer is exhausted, removes and closes it.
   */
  private void advanceBufferIfNecessary() {
    ReadableBuffer buffer = buffers.peek();
    if (buffer.readableBytes() == 0) {
      buffers.remove().close();
    }
  }

  /**
   * A simple read operation to perform on a single {@link ReadableBuffer}.
   * All state management for the buffers is done by
   * {@link CompositeReadableBuffer#execute(ReadOperation, int, Object, int)}.
   */
  private interface ReadOperation<T> {
    /**
     * This method can also be used to simultaneously perform operation-specific int-valued
     * aggregation over the sequence of buffers in a {@link CompositeReadableBuffer}.
     * {@code value} is the return value from the prior buffer, or the "initial" value passed
     * to {@code execute()} in the case of the first buffer. {@code execute()} returns the value
     * returned by the operation called on the last buffer.
     */
    int read(ReadableBuffer buffer, int length, T dest, int value) throws IOException;
  }

  private interface NoThrowReadOperation<T> extends ReadOperation<T> {
    @Override
    int read(ReadableBuffer buffer, int length, T dest, int value);
  }
}
