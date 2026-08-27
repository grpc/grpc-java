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

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.InvalidMarkException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests for {@link CompositeReadableBuffer}.
 */
@RunWith(JUnit4.class)
public class CompositeReadableBufferTest {
  private static final String EXPECTED_VALUE = "hello world";

  private CompositeReadableBuffer composite;

  @Before
  public void setup() {
    composite = new CompositeReadableBuffer();
    splitAndAdd(EXPECTED_VALUE);
  }

  @After
  public void teardown() {
    composite.close();
  }

  @Test
  public void singleBufferShouldSucceed() {
    composite = new CompositeReadableBuffer();
    composite.addBuffer(ReadableBuffers.wrap(EXPECTED_VALUE.getBytes(UTF_8)));
    assertEquals(EXPECTED_VALUE.length(), composite.readableBytes());
    assertEquals(EXPECTED_VALUE, ReadableBuffers.readAsStringUtf8(composite));
    assertEquals(0, composite.readableBytes());
  }

  @Test
  public void readUnsignedByteShouldSucceed() {
    for (int ix = 0; ix < EXPECTED_VALUE.length(); ++ix) {
      int c = composite.readUnsignedByte();
      assertEquals(EXPECTED_VALUE.charAt(ix), (char) c);
    }
    assertEquals(0, composite.readableBytes());
  }

  @Test
  public void readUnsignedByteShouldSkipZeroLengthBuffer() {
    composite = new CompositeReadableBuffer();
    composite.addBuffer(ReadableBuffers.wrap(new byte[0]));
    byte[] in = {1};
    composite.addBuffer(ReadableBuffers.wrap(in));
    assertEquals(1, composite.readUnsignedByte());
    assertEquals(0, composite.readableBytes());
  }

  @Test
  public void skipBytesShouldSucceed() {
    int remaining = EXPECTED_VALUE.length();
    composite.skipBytes(1);
    remaining--;
    assertEquals(remaining, composite.readableBytes());

    composite.skipBytes(5);
    remaining -= 5;
    assertEquals(remaining, composite.readableBytes());

    composite.skipBytes(remaining);
    assertEquals(0, composite.readableBytes());
  }

  @Test
  public void readByteArrayShouldSucceed() {
    byte[] bytes = new byte[composite.readableBytes()];
    int writeIndex = 0;

    composite.readBytes(bytes, writeIndex, 1);
    writeIndex++;
    assertEquals(EXPECTED_VALUE.length() - writeIndex, composite.readableBytes());

    composite.readBytes(bytes, writeIndex, 5);
    writeIndex += 5;
    assertEquals(EXPECTED_VALUE.length() - writeIndex, composite.readableBytes());

    int remaining = composite.readableBytes();
    composite.readBytes(bytes, writeIndex, remaining);
    writeIndex += remaining;
    assertEquals(0, composite.readableBytes());
    assertEquals(bytes.length, writeIndex);
    assertEquals(EXPECTED_VALUE, new String(bytes, UTF_8));
  }

  @Test
  public void readStreamShouldSucceed() throws IOException {
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    int remaining = EXPECTED_VALUE.length();

    composite.readBytes(bos, 1);
    remaining--;
    assertEquals(remaining, composite.readableBytes());

    composite.readBytes(bos, 5);
    remaining -= 5;
    assertEquals(remaining, composite.readableBytes());

    composite.readBytes(bos, remaining);
    assertEquals(0, composite.readableBytes());
    assertEquals(EXPECTED_VALUE, new String(bos.toByteArray(), UTF_8));
  }

  @Test
  public void markSupportedOnlyAllComponentsSupportMark() {
    composite = new CompositeReadableBuffer();
    ReadableBuffer buffer1 = mock(ReadableBuffer.class);
    ReadableBuffer buffer2 = mock(ReadableBuffer.class);
    ReadableBuffer buffer3 = mock(ReadableBuffer.class);
    when(buffer1.markSupported()).thenReturn(true);
    when(buffer2.markSupported()).thenReturn(true);
    when(buffer3.markSupported()).thenReturn(false);
    composite.addBuffer(buffer1);
    assertTrue(composite.markSupported());
    composite.addBuffer(buffer2);
    assertTrue(composite.markSupported());
    composite.addBuffer(buffer3);
    assertFalse(composite.markSupported());
  }

  @Test
  public void resetUnmarkedShouldThrow() {
    try {
      composite.reset();
      fail();
    } catch (InvalidMarkException expected) {
    }
  }

  @Test
  public void markAndResetWithSkipBytesShouldSucceed() {
    composite.mark();
    composite.skipBytes(EXPECTED_VALUE.length() / 2);
    composite.reset();
    assertEquals(EXPECTED_VALUE.length(), composite.readableBytes());
  }

  @Test
  public void markAndResetWithReadUnsignedByteShouldSucceed() {
    composite.readUnsignedByte();
    composite.mark();
    int b = composite.readUnsignedByte();
    composite.reset();
    assertEquals(EXPECTED_VALUE.length() - 1, composite.readableBytes());
    assertEquals(b, composite.readUnsignedByte());
  }

  @Test
  public void markAndResetWithReadByteArrayShouldSucceed() {
    composite.mark();
    byte[] first = new byte[EXPECTED_VALUE.length()];
    composite.readBytes(first, 0, EXPECTED_VALUE.length());
    composite.reset();
    assertEquals(EXPECTED_VALUE.length(), composite.readableBytes());
    byte[] second = new byte[EXPECTED_VALUE.length()];
    composite.readBytes(second, 0, EXPECTED_VALUE.length());
    assertArrayEquals(first, second);
  }

  @Test
  public void markAndResetWithReadStreamShouldSucceed() throws IOException {
    ByteArrayOutputStream first = new ByteArrayOutputStream();
    composite.mark();
    composite.readBytes(first, EXPECTED_VALUE.length() / 2);
    composite.reset();
    assertEquals(EXPECTED_VALUE.length(), composite.readableBytes());
    ByteArrayOutputStream second = new ByteArrayOutputStream();
    composite.readBytes(second, EXPECTED_VALUE.length() / 2);
    assertArrayEquals(first.toByteArray(), second.toByteArray());
  }

  @Test
  public void markAndResetWithReadReadableBufferShouldSucceed() {
    composite.readBytes(EXPECTED_VALUE.length() / 2);
    int remaining = composite.readableBytes();
    composite.mark();
    ReadableBuffer first = composite.readBytes(1);
    composite.reset();
    assertEquals(remaining, composite.readableBytes());
    ReadableBuffer second = composite.readBytes(1);
    assertEquals(first.readUnsignedByte(), second.readUnsignedByte());
  }

  @Test
  public void markAgainShouldOverwritePreviousMark() {
    composite.mark();
    composite.skipBytes(EXPECTED_VALUE.length() / 2);
    int remaining = composite.readableBytes();
    composite.mark();
    composite.skipBytes(1);
    composite.reset();
    assertEquals(remaining, composite.readableBytes());
  }

  @Test
  public void bufferAddedAfterMarkedShouldBeIncluded() {
    composite = new CompositeReadableBuffer();
    composite.mark();
    splitAndAdd(EXPECTED_VALUE);
    composite.skipBytes(EXPECTED_VALUE.length() / 2);
    composite.reset();
    assertEquals(EXPECTED_VALUE.length(), composite.readableBytes());
  }

  @Test
  public void canUseByteBufferOnlyAllComponentsSupportUsingByteBuffer() {
    composite = new CompositeReadableBuffer();
    ReadableBuffer buffer1 = mock(ReadableBuffer.class);
    ReadableBuffer buffer2 = mock(ReadableBuffer.class);
    ReadableBuffer buffer3 = mock(ReadableBuffer.class);
    when(buffer1.byteBufferSupported()).thenReturn(true);
    when(buffer2.byteBufferSupported()).thenReturn(true);
    when(buffer3.byteBufferSupported()).thenReturn(false);
    composite.addBuffer(buffer1);
    assertTrue(composite.byteBufferSupported());
    composite.addBuffer(buffer2);
    assertTrue(composite.byteBufferSupported());
    composite.addBuffer(buffer3);
    assertFalse(composite.byteBufferSupported());
  }

  @Test
  public void getByteBufferDelegatesToComponents() {
    composite = new CompositeReadableBuffer();
    ReadableBuffer buffer = mock(ReadableBuffer.class);
    composite.addBuffer(buffer);
    composite.getByteBuffer();
    verify(buffer).getByteBuffer();
  }

  @Test
  public void closeShouldCloseBuffers() {
    composite = new CompositeReadableBuffer();
    ReadableBuffer mock1 = mock(ReadableBuffer.class);
    ReadableBuffer mock2 = mock(ReadableBuffer.class);
    composite.addBuffer(mock1);
    composite.addBuffer(mock2);

    composite.close();
    verify(mock1).close();
    verify(mock2).close();
  }

  private void splitAndAdd(String value) {
    int partLength = Math.max(1, value.length() / 4);
    for (int startIndex = 0, endIndex = 0; startIndex < value.length(); startIndex = endIndex) {
      endIndex = Math.min(value.length(), startIndex + partLength);
      String part = value.substring(startIndex, endIndex);
      composite.addBuffer(ReadableBuffers.wrap(part.getBytes(UTF_8)));
    }

    assertEquals(value.length(), composite.readableBytes());
  }

  @Test
  public void coalesceOnMaxSmallBuffers() {
    composite = new CompositeReadableBuffer();
    // 1000 1-byte buffers
    for (int i = 0; i < 1000; i++) {
      composite.addBuffer(ReadableBuffers.wrap(new byte[] {1}));
    }
    assertEquals(1, composite.getBufferCount());
    assertEquals(1000, composite.readableBytes());
  }

  @Test
  public void coalesceBeyondMaxSmallBuffers() {
    composite = new CompositeReadableBuffer();
    // 1001 1-byte buffers
    for (int i = 0; i < 1001; i++) {
      composite.addBuffer(ReadableBuffers.wrap(new byte[] {1}));
    }
    assertEquals(2, composite.getBufferCount());
    assertEquals(1001, composite.readableBytes());
  }

  @Test
  public void coalesceMultipleBatchesOfSmallBuffers() {
    composite = new CompositeReadableBuffer();
    // 2000 1-byte buffers
    for (int i = 0; i < 2000; i++) {
      composite.addBuffer(ReadableBuffers.wrap(new byte[] {1}));
    }
    assertEquals(2, composite.getBufferCount());
    assertEquals(2000, composite.readableBytes());
  }

  @Test
  public void coalesceBeforeLargeBuffer() {
    composite = new CompositeReadableBuffer();
    // 500 small frames
    for (int i = 0; i < 500; i++) {
      composite.addBuffer(ReadableBuffers.wrap(new byte[] {1}));
    }
    // 1 large frame
    composite.addBuffer(ReadableBuffers.wrap(new byte[1024]));

    // The 500 small frames should be coalesced into 1, followed by the 1 large frame
    assertEquals(2, composite.getBufferCount());
    assertEquals(1524, composite.readableBytes());
  }

  @Test
  public void largeBufferResetsTailSmallBufferCount() {
    composite = new CompositeReadableBuffer();
    // Add 1 large buffer
    composite.addBuffer(ReadableBuffers.wrap(new byte[1024]));
    assertEquals(1, composite.getBufferCount());

    // Add 999 small buffers right after the large buffer (leaving total small tail at 999)
    for (int i = 0; i < 999; i++) {
      composite.addBuffer(ReadableBuffers.wrap(new byte[] {1}));
    }

    // Since only 999 small buffers are at the tail right after the large buffer,
    // they must NOT be coalesced yet. Total buffers in queue should be 1 + 999 = 1000.
    assertEquals(1000, composite.getBufferCount());
    assertEquals(1024 + 999, composite.readableBytes());
  }

  @Test
  public void noCoalesceOnLargeFrames() {
    composite = new CompositeReadableBuffer();
    // 1001 1024-byte frames
    for (int i = 0; i < 1001; i++) {
      composite.addBuffer(ReadableBuffers.wrap(new byte[1024]));
    }
    assertEquals(1001, composite.getBufferCount());
    assertEquals(1001 * 1024, composite.readableBytes());
  }

  @Test
  public void skipCoalesceIfMarked() {
    composite = new CompositeReadableBuffer();
    composite.addBuffer(ReadableBuffers.wrap(new byte[] {1}));
    composite.mark();

    // Add 1000 more 1-byte buffers, reaching 1001 total
    for (int i = 0; i < 1000; i++) {
      composite.addBuffer(ReadableBuffers.wrap(new byte[] {1}));
    }

    // Should skip coalescing due to marked=true
    assertEquals(1001, composite.getBufferCount());
    assertEquals(1001, composite.readableBytes());
  }

  @Test
  public void readBytesAdjustsTailSmallBufferCount() {
    composite = new CompositeReadableBuffer();
    // Add 500 1-byte buffers
    for (int i = 0; i < 500; i++) {
      composite.addBuffer(ReadableBuffers.wrap(new byte[] {1}));
    }
    // Read 200 buffers via readBytes(int) without mark
    ReadableBuffer read = composite.readBytes(200);
    read.close();

    // Now add 500 more 1-byte buffers
    for (int i = 0; i < 500; i++) {
      composite.addBuffer(ReadableBuffers.wrap(new byte[] {1}));
    }
    assertEquals(800, composite.getBufferCount());
    assertEquals(800, composite.readableBytes());
  }

  @Test
  public void advanceBufferAdjustsTailSmallBufferCount() {
    composite = new CompositeReadableBuffer();
    // Add 500 1-byte buffers
    for (int i = 0; i < 500; i++) {
      composite.addBuffer(ReadableBuffers.wrap(new byte[] {1}));
    }
    // Read 200 buffers via skipBytes (which calls advanceBuffer)
    composite.skipBytes(200);

    // Now add 500 more 1-byte buffers
    for (int i = 0; i < 500; i++) {
      composite.addBuffer(ReadableBuffers.wrap(new byte[] {1}));
    }
    assertEquals(800, composite.getBufferCount());
    assertEquals(800, composite.readableBytes());
  }

  @Test
  public void coalesceClosesCoalescedBuffers() {
    composite = new CompositeReadableBuffer();
    ReadableBuffer mock1 = mock(ReadableBuffer.class);
    when(mock1.readableBytes()).thenReturn(1);
    ReadableBuffer mock2 = mock(ReadableBuffer.class);
    when(mock2.readableBytes()).thenReturn(1);
    composite.addBuffer(mock1);
    composite.addBuffer(mock2);

    // Large buffer triggers coalesce of mock1 and mock2 around line 76
    composite.addBuffer(ReadableBuffers.wrap(new byte[1024]));

    verify(mock1).close();
    verify(mock2).close();
  }

  @Test
  public void closeResetsTailSmallBufferCount() {
    composite = new CompositeReadableBuffer();
    // Add 500 small buffers
    for (int i = 0; i < 500; i++) {
      composite.addBuffer(ReadableBuffers.wrap(new byte[] {1}));
    }
    composite.close();
    assertEquals(0, composite.getBufferCount());

    // After close(), tailSmallBufferCount should be exactly 0.
    // Adding 999 (MAX_SMALL_BUFFERS - 1) small buffers right after close should NOT trigger
    // coalescing, resulting in exactly 999 buffers.
    for (int i = 0; i < 999; i++) {
      composite.addBuffer(ReadableBuffers.wrap(new byte[] {1}));
    }
    assertEquals(999, composite.getBufferCount());

    // Adding the 1000th small buffer should trigger coalescing down to 1 buffer.
    composite.addBuffer(ReadableBuffers.wrap(new byte[] {1}));
    assertEquals(1, composite.getBufferCount());
  }
}
