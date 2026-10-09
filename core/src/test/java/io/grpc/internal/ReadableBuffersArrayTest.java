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

import static io.grpc.internal.ReadableBuffers.wrap;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.nio.ByteBuffer;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests for the array-backed {@link ReadableBuffer} returned by {@link ReadableBuffers#wrap(byte[],
 * int, int)}.
 */
@RunWith(JUnit4.class)
public class ReadableBuffersArrayTest extends ReadableBufferTestBase {

  @Test
  public void bufferShouldExposeArray() {
    byte[] array = msg.getBytes(UTF_8);
    ReadableBuffer buffer = wrap(array, 1, msg.length() - 1);
    assertTrue(buffer.hasArray());
    assertSame(array, buffer.array());
    assertEquals(1, buffer.arrayOffset());

    // Now read a byte and verify that the offset changes.
    buffer.readUnsignedByte();
    assertEquals(2, buffer.arrayOffset());
  }

  @Test
  public void getByteBufferShouldExposeArrayWithoutCopying() {
    byte[] array = msg.getBytes(UTF_8);
    ReadableBuffer buffer = wrap(array, 1, msg.length() - 2);
    assertTrue(buffer.byteBufferSupported());

    ByteBuffer byteBuffer = buffer.getByteBuffer();
    assertEquals(ByteBuffer.wrap(array, 1, msg.length() - 2), byteBuffer);
    assertSame(array, byteBuffer.array());
    assertEquals(1, byteBuffer.arrayOffset() + byteBuffer.position());
    // Bytes outside of the wrapped range are not reachable through the ByteBuffer.
    assertEquals(msg.length() - 2, byteBuffer.capacity());
  }

  @Test
  public void getByteBufferShouldStartAtReadPosition() {
    ReadableBuffer buffer = buffer();
    buffer.readUnsignedByte();
    buffer.readBytes(new byte[2], 0, 2);
    buffer.skipBytes(2);
    assertEquals(ByteBuffer.wrap(msg.substring(5).getBytes(UTF_8)), buffer.getByteBuffer());

    buffer.skipBytes(buffer.readableBytes());
    assertFalse(buffer.getByteBuffer().hasRemaining());
  }

  @Test
  public void getByteBufferShouldBeLimitedToReadBytesSlice() {
    ReadableBuffer buffer = buffer();
    buffer.skipBytes(5);
    ReadableBuffer slice = buffer.readBytes(10);
    assertEquals(ByteBuffer.wrap(msg.substring(5, 15).getBytes(UTF_8)), slice.getByteBuffer());
  }

  @Test
  public void getByteBufferShouldFollowMarkAndReset() {
    ReadableBuffer buffer = buffer();
    buffer.skipBytes(5);
    buffer.mark();
    buffer.skipBytes(10);
    assertEquals(ByteBuffer.wrap(msg.substring(15).getBytes(UTF_8)), buffer.getByteBuffer());

    buffer.reset();
    assertEquals(ByteBuffer.wrap(msg.substring(5).getBytes(UTF_8)), buffer.getByteBuffer());
  }

  @Override
  protected ReadableBuffer buffer() {
    return ReadableBuffers.wrap(msg.getBytes(UTF_8), 0, msg.length());
  }
}
