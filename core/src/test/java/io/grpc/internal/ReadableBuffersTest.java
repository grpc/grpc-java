/*
 * Copyright 2018 The gRPC Authors
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
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.grpc.Detachable;
import io.grpc.HasByteBuffer;
import java.io.IOException;
import java.io.InputStream;
import java.nio.InvalidMarkException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests for {@link ReadableBuffers}.
 * See also: {@link ReadableBuffersArrayTest}, {@link ReadableBuffersByteBufferTest}.
 */
@RunWith(JUnit4.class)
public class ReadableBuffersTest {
  private static final byte[] MSG_BYTES = "hello".getBytes(UTF_8);

  @Test
  public void empty_returnsEmptyBuffer() {
    ReadableBuffer buffer = ReadableBuffers.empty();
    assertArrayEquals(new byte[0], buffer.array());
  }

  @Test(expected = NullPointerException.class)
  public void readArray_checksNotNull() {
    ReadableBuffers.readArray(null);
  }

  @Test
  public void readArray_returnsBufferArray() {
    ReadableBuffer buffer = ReadableBuffers.wrap(MSG_BYTES);
    assertArrayEquals(new byte[]{'h', 'e', 'l', 'l', 'o'}, ReadableBuffers.readArray(buffer));
  }

  @Test
  public void readAsString_returnsString() {
    ReadableBuffer buffer = ReadableBuffers.wrap(MSG_BYTES);
    assertEquals("hello", ReadableBuffers.readAsString(buffer, UTF_8));
  }

  @Test(expected = NullPointerException.class)
  public void readAsString_checksNotNull() {
    ReadableBuffers.readAsString(null, UTF_8);
  }

  @Test
  public void readAsStringUtf8_returnsString() {
    ReadableBuffer buffer = ReadableBuffers.wrap(MSG_BYTES);
    assertEquals("hello", ReadableBuffers.readAsStringUtf8(buffer));
  }

  @Test(expected = NullPointerException.class)
  public void readAsStringUtf8_checksNotNull() {
    ReadableBuffers.readAsStringUtf8(null);
  }

  @Test
  public void openStream_ignoresClose() throws Exception {
    ReadableBuffer buffer = mock(ReadableBuffer.class);
    InputStream stream = ReadableBuffers.openStream(buffer, false);
    stream.close();
    verify(buffer, never()).close();
  }

  @Test
  public void bufferInputStream_available_returnsReadableBytes() throws Exception {
    ReadableBuffer buffer = ReadableBuffers.wrap(MSG_BYTES);
    InputStream inputStream = ReadableBuffers.openStream(buffer, true);
    assertEquals(5, inputStream.available());
    while (inputStream.available() != 0) {
      inputStream.read();
    }
    assertEquals(-1, inputStream.read());
  }

  @Test
  public void bufferInputStream_read_returnsUnsignedByte() throws Exception {
    ReadableBuffer buffer = ReadableBuffers.wrap(MSG_BYTES);
    InputStream inputStream = ReadableBuffers.openStream(buffer, true);
    assertEquals((int) 'h', inputStream.read());
  }

  @Test
  public void bufferInputStream_read_writes() throws Exception {
    ReadableBuffer buffer = ReadableBuffers.wrap(MSG_BYTES);
    InputStream inputStream = ReadableBuffers.openStream(buffer, true);
    byte[] dest = new byte[5];
    assertEquals(5, inputStream.read(dest, /*destOffset*/ 0, /*length*/ 5));
    assertArrayEquals(new byte[]{'h', 'e', 'l', 'l', 'o'}, dest);
    assertEquals(-1, inputStream.read(/*dest*/ new byte[1], /*destOffset*/ 0, /*length*/1));
  }

  @Test
  public void bufferInputStream_read_writesPartially() throws Exception {
    ReadableBuffer buffer = ReadableBuffers.wrap(MSG_BYTES);
    InputStream inputStream = ReadableBuffers.openStream(buffer, true);
    byte[] dest = new byte[3];
    assertEquals(2, inputStream.read(dest, /*destOffset*/ 1, /*length*/ 2));
    assertArrayEquals(new byte[]{0x00, 'h', 'e'}, dest);
  }

  @Test
  public void bufferInputStream_close_closesBuffer() throws Exception {
    ReadableBuffer buffer = mock(ReadableBuffer.class);
    InputStream inputStream = ReadableBuffers.openStream(buffer, true);
    inputStream.close();
    verify(buffer, times(1)).close();
  }

  @Test
  public void bufferInputStream_markAndReset() throws IOException {
    ReadableBuffer buffer = ReadableBuffers.wrap(MSG_BYTES);
    InputStream inputStream = ReadableBuffers.openStream(buffer, true);
    assertTrue(inputStream.markSupported());
    inputStream.mark(2);
    byte[] first = new byte[5];
    inputStream.read(first);
    assertEquals(0, inputStream.available());
    inputStream.reset();
    assertEquals(5, inputStream.available());
    byte[] second = new byte[5];
    inputStream.read(second);
    assertArrayEquals(first, second);
  }

  @Test
  public void bufferInputStream_getByteBufferDelegatesToBuffer() {
    ReadableBuffer buffer = mock(ReadableBuffer.class);
    when(buffer.byteBufferSupported()).thenReturn(true);
    InputStream inputStream = ReadableBuffers.openStream(buffer, true);
    assertTrue(((HasByteBuffer) inputStream).byteBufferSupported());
    ((HasByteBuffer) inputStream).getByteBuffer();
    verify(buffer).getByteBuffer();
  }

  @Test
  public void bufferInputStream_availableAfterDetached_returnsZeroByte() throws IOException {
    ReadableBuffer buffer = ReadableBuffers.wrap(MSG_BYTES);
    InputStream inputStream = ReadableBuffers.openStream(buffer, true);
    assertEquals(5, inputStream.available());
    InputStream detachedStream = ((Detachable) inputStream).detach();
    assertEquals(0, inputStream.available());
    assertEquals(5, detachedStream.available());
  }

  @Test
  public void bufferInputStream_skipAfterDetached() throws IOException {
    ReadableBuffer buffer = ReadableBuffers.wrap(MSG_BYTES);
    InputStream inputStream = ReadableBuffers.openStream(buffer, true);
    assertEquals(3, inputStream.skip(3));
    InputStream detachedStream = ((Detachable) inputStream).detach();
    assertEquals(0, inputStream.skip(2));
    assertEquals(2, detachedStream.skip(2));
  }

  @Test
  public void bufferInputStream_readUnsignedByteAfterDetached() throws IOException {
    ReadableBuffer buffer = ReadableBuffers.wrap(MSG_BYTES);
    InputStream inputStream = ReadableBuffers.openStream(buffer, true);
    assertEquals((int) 'h', inputStream.read());
    InputStream detachedStream = ((Detachable) inputStream).detach();
    assertEquals(-1, inputStream.read());
    assertEquals((int) 'e', detachedStream.read());
  }

  @Test
  public void bufferInputStream_partialReadAfterDetached() throws IOException {
    ReadableBuffer buffer = ReadableBuffers.wrap(MSG_BYTES);
    InputStream inputStream = ReadableBuffers.openStream(buffer, true);
    byte[] dest = new byte[3];
    assertEquals(3, inputStream.read(dest, /*destOffset*/ 0, /*length*/ 3));
    assertArrayEquals(new byte[]{'h', 'e', 'l'}, dest);
    InputStream detachedStream = ((Detachable) inputStream).detach();
    byte[] newDest = new byte[2];
    assertEquals(2, detachedStream.read(newDest, /*destOffset*/ 0, /*length*/ 2));
    assertArrayEquals(new byte[]{'l', 'o'}, newDest);
  }

  @Test
  public void bufferInputStream_markDiscardedAfterDetached() throws IOException {
    ReadableBuffer buffer = ReadableBuffers.wrap(MSG_BYTES);
    InputStream inputStream = ReadableBuffers.openStream(buffer, true);
    inputStream.mark(5);
    ((Detachable) inputStream).detach();
    assertThrows(InvalidMarkException.class, () -> inputStream.reset());
  }

  @Test
  public void bufferInputStream_markPreservedInForkedInputStream() throws IOException {
    ReadableBuffer buffer = ReadableBuffers.wrap(MSG_BYTES);
    InputStream inputStream = ReadableBuffers.openStream(buffer, true);
    inputStream.skip(2);
    inputStream.mark(3);
    InputStream detachedStream = ((Detachable) inputStream).detach();
    detachedStream.skip(3);
    assertEquals(0, detachedStream.available());
    detachedStream.reset();
    assertEquals(3, detachedStream.available());
  }

  @Test
  public void bufferInputStream_closeAfterDetached() throws IOException {
    ReadableBuffer buffer = mock(ReadableBuffer.class);
    when(buffer.readBytes(anyInt())).thenReturn(mock(ReadableBuffer.class));
    InputStream inputStream = ReadableBuffers.openStream(buffer, true);
    InputStream detachedStream = ((Detachable) inputStream).detach();
    inputStream.close();
    verify(buffer, never()).close();
    detachedStream.close();
    verify(buffer).close();
  }
}
