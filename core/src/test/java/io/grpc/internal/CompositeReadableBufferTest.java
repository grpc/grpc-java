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

import static com.google.common.base.Charsets.UTF_8;
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
import java.nio.Buffer;
import java.nio.ByteBuffer;
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
  public void readByteBufferShouldSucceed() {
    ByteBuffer byteBuffer = ByteBuffer.allocate(EXPECTED_VALUE.length());
    int remaining = EXPECTED_VALUE.length();

    ((Buffer) byteBuffer).limit(1);
    composite.readBytes(byteBuffer);
    remaining--;
    assertEquals(remaining, composite.readableBytes());

    ((Buffer) byteBuffer).limit(byteBuffer.limit() + 5);
    composite.readBytes(byteBuffer);
    remaining -= 5;
    assertEquals(remaining, composite.readableBytes());

    ((Buffer) byteBuffer).limit(byteBuffer.limit() + remaining);
    composite.readBytes(byteBuffer);
    assertEquals(0, composite.readableBytes());
    assertEquals(EXPECTED_VALUE, new String(byteBuffer.array(), UTF_8));
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
  public void markAndResetWithReadByteBufferShouldSucceed() {
    byte[] first = new byte[EXPECTED_VALUE.length()];
    composite.mark();
    composite.readBytes(ByteBuffer.wrap(first));
    composite.reset();
    byte[] second = new byte[EXPECTED_VALUE.length()];
    assertEquals(EXPECTED_VALUE.length(), composite.readableBytes());
    composite.readBytes(ByteBuffer.wrap(second));
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
}
