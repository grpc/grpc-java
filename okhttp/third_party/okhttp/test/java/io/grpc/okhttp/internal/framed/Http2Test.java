/*
 * Copyright (C) 2023 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.grpc.okhttp.internal.framed;

import static io.grpc.okhttp.internal.framed.Http2.FLAG_END_HEADERS;
import static io.grpc.okhttp.internal.framed.Http2.FLAG_NONE;
import static io.grpc.okhttp.internal.framed.Http2.FLAG_PADDED;
import static io.grpc.okhttp.internal.framed.Http2.TYPE_DATA;
import static io.grpc.okhttp.internal.framed.Http2.TYPE_HEADERS;
import static io.grpc.okhttp.internal.framed.Http2.TYPE_SETTINGS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import okio.Buffer;
import okio.BufferedSink;
import okio.ByteString;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@RunWith(JUnit4.class)
public class Http2Test {
  @Rule
  public final MockitoRule mocks = MockitoJUnit.rule();
  private FrameReader http2FrameReader;
  @Mock
  private FrameReader.Handler mockHandler;
  private final int STREAM_ID = 6;

  @Test
  public void dataFrameNoPadding() throws IOException {
    Buffer bufferIn = createData(FLAG_NONE, 3239, 0 );
    http2FrameReader = new Http2.Reader(bufferIn, 100, true);
    http2FrameReader.nextFrame(mockHandler);

    verify(mockHandler).data(eq(false), eq(STREAM_ID), eq(bufferIn), eq(3239), eq(3239));
    assertEquals(3239, bufferIn.size());
  }

  @Test
  public void dataFrameOneLengthPadding() throws IOException {
    Buffer bufferIn = createData(FLAG_PADDED, 1876, 0);
    http2FrameReader = new Http2.Reader(bufferIn, 100, true);
    http2FrameReader.nextFrame(mockHandler);

    verify(mockHandler).data(eq(false), eq(STREAM_ID), eq(bufferIn), eq(1875), eq(1876));
    assertEquals(1876, bufferIn.size());
  }

  @Test
  public void dataFramePadding() throws IOException {
    Buffer bufferIn = createData(FLAG_PADDED, 2037, 125);
    http2FrameReader = new Http2.Reader(bufferIn, 100, true);
    http2FrameReader.nextFrame(mockHandler);

    verify(mockHandler).data(eq(false), eq(STREAM_ID), eq(bufferIn), eq(2037 - 126), eq(2037));
    assertEquals(2037 - 125, bufferIn.size());
  }

  @Test
  public void writerAckSettingsResizesHpackWriter() throws IOException {
    Buffer sink = new Buffer();
    Http2.Writer writer = new Http2.Writer(sink, true);

    // Peer sends SETTINGS_HEADER_TABLE_SIZE = 2048
    Settings peerSettings = new Settings();
    peerSettings.set(Settings.HEADER_TABLE_SIZE, 0, 2048);

    writer.ackSettings(peerSettings);

    // Verify ACK frame is written (9 bytes)
    ByteString expectedAck = ByteString.decodeHex("000000040100000000");
    assertEquals(expectedAck, sink.readByteString());

    // Write headers to trigger dynamic table size update
    List<Header> headers = Arrays.asList(new Header("foo", "bar"));
    writer.headers(STREAM_ID, headers);

    // Read back the HEADERS frame and verify it has the dynamic table size update
    Buffer buffer = sink;
    int length = ((buffer.readByte() & 0xff) << 16)
        | ((buffer.readByte() & 0xff) << 8)
        | (buffer.readByte() & 0xff);
    int type = buffer.readByte() & 0xff;
    int flags = buffer.readByte() & 0xff;
    int streamId = buffer.readInt() & 0x7fffffff;

    assertEquals(Http2.TYPE_HEADERS, type);
    assertEquals(12, length);
    assertEquals(Http2.FLAG_END_HEADERS, flags);
    assertEquals(STREAM_ID, streamId);

    // Verify first bytes of payload are the dynamic table size update (2048)
    // In HPACK, table size 2048 update is 0x3F, 0xE1, 0x0F
    assertEquals(0x3F, buffer.readByte() & 0xff);
    assertEquals(0xE1, buffer.readByte() & 0xff);
    assertEquals(0x0F, buffer.readByte() & 0xff);
  }

  @Test
  public void peerSettingsHeaderTableSizeZero_doesNotShrinkDecoder() throws IOException {
    Buffer wire = new Buffer();
    // Peer sends SETTINGS(HEADER_TABLE_SIZE = 0). That caps OUR encoder, NOT our decoder.
    writeSettingsFrame(wire, Settings.HEADER_TABLE_SIZE, 0);
    // Peer then sends HEADERS using our advertised 4096-byte decoder dynamic table.
    Buffer headerBlock1 = new Buffer();
    headerBlock1.writeByte(0x40); // Incremental indexing, new name.
    headerBlock1.writeByte(3).writeUtf8("foo");
    headerBlock1.writeByte(3).writeUtf8("bar");
    writeHeadersFrame(wire, 1, headerBlock1);
    Buffer headerBlock2 = new Buffer();
    headerBlock2.writeByte(0xbe); // Indexed header field 62 (first dynamic table entry).
    writeHeadersFrame(wire, 3, headerBlock2);

    Http2.Reader reader = new Http2.Reader(wire, 4096, true);
    assertTrue(reader.nextFrame(mockHandler)); // SETTINGS(HEADER_TABLE_SIZE = 0)
    assertEquals(4096, reader.hpackReader.maxDynamicTableByteCount());
    assertTrue(reader.nextFrame(mockHandler)); // HEADERS #1
    assertTrue(reader.nextFrame(mockHandler)); // HEADERS #2 referencing dynamic index 62
    verify(mockHandler).headers(
        eq(false), eq(false), eq(3), eq(-1),
        eq(Collections.singletonList(new Header("foo", "bar"))),
        eq(HeadersMode.HTTP_20_HEADERS));
  }

  @Test
  public void peerSettingsHeaderTableSizeLarge_doesNotExpandDecoder() throws IOException {
    Buffer wire = new Buffer();
    // Malicious peer advertises SETTINGS(HEADER_TABLE_SIZE = 0x7FFFFFFF).
    writeSettingsFrame(wire, Settings.HEADER_TABLE_SIZE, Integer.MAX_VALUE);
    // Peer attempts HPACK dynamic table size update to 8192 (> our 4096 limit).
    Buffer headerBlock = new Buffer();
    headerBlock.writeByte(0x3f).writeByte(0xe1).writeByte(0x3f); // size = 8192
    writeHeadersFrame(wire, 1, headerBlock);

    Http2.Reader reader = new Http2.Reader(wire, 4096, true);
    assertTrue(reader.nextFrame(mockHandler)); // SETTINGS
    assertEquals(4096, reader.hpackReader.maxDynamicTableByteCount());
    IOException ex = assertThrows(IOException.class, () -> reader.nextFrame(mockHandler));
    assertTrue(ex.getMessage().contains("Invalid dynamic table size update"));
  }

  @Test
  public void ackSettingsHeaderTableSizeZero_updatesEncoder() throws IOException {
    Buffer clientToPeer = new Buffer();
    Http2.Writer writer = new Http2.Writer(clientToPeer, true);
    List<Header> customHeader = Collections.singletonList(new Header("custom-key", "custom-val"));

    // Populate encoder dynamic table before peer changes table size to 0.
    writer.headers(1, customHeader);
    writer.flush();
    // Skip first HEADERS frame via peer reader initialized at 4096.
    Http2.Reader peerReader = new Http2.Reader(clientToPeer, 4096, false);
    assertTrue(peerReader.nextFrame(mockHandler));

    // Peer sends SETTINGS(HEADER_TABLE_SIZE = 0) and writer ACKs it.
    Settings peerSettings = readSingleSettings(Settings.HEADER_TABLE_SIZE, 0);
    writer.ackSettings(peerSettings);
    assertTrue(peerReader.nextFrame(mockHandler)); // SETTINGS ACK

    // Next two HEADERS frames must decode cleanly with a strict 0-byte decoder dynamic table.
    writer.headers(3, customHeader);
    writer.headers(5, customHeader);
    writer.flush();

    Buffer encodedHeaders3 = readHeadersPayload(clientToPeer);
    assertEquals(0x20, encodedHeaders3.getByte(0) & 0xff); // Dynamic table size update = 0.
    Hpack.Reader zeroDecoder = new Hpack.Reader(0, encodedHeaders3);
    zeroDecoder.readHeaders();
    assertEquals(customHeader, zeroDecoder.getAndResetHeaderList());

    Buffer encodedHeaders5 = readHeadersPayload(clientToPeer);
    zeroDecoder = new Hpack.Reader(0, encodedHeaders5);
    zeroDecoder.readHeaders();
    assertEquals(customHeader, zeroDecoder.getAndResetHeaderList());
  }

  @Test
  public void ackSettingsHeaderTableSize_uint32MaxNotTreatedAsUnset() throws IOException {
    Buffer wire = new Buffer();
    Http2.Writer writer = new Http2.Writer(wire, true);

    // 0xFFFFFFFF == -1 as signed int; must be handled as set and saturated to 16384.
    Settings maxSettings = readSingleSettings(Settings.HEADER_TABLE_SIZE, 0xFFFFFFFF);
    assertTrue(maxSettings.isSet(Settings.HEADER_TABLE_SIZE));
    writer.ackSettings(maxSettings);
    consumeFrame(wire); // SETTINGS ACK

    writer.headers(1, Collections.singletonList(new Header("foo", "bar")));
    writer.flush();
    Buffer payload = readHeadersPayload(wire);
    // Must begin with dynamic table size update to 16384 (0x3F, 0xE1, 0x7F).
    assertEquals(0x3f, payload.readByte() & 0xff);
    assertEquals(0xe1, payload.readByte() & 0xff);
    assertEquals(0x7f, payload.readByte() & 0xff);
  }

  @Test
  public void ackSettingsEmpty_preservesEncoderHeaderTableSize() throws IOException {
    Buffer wire = new Buffer();
    Http2.Writer writer = new Http2.Writer(wire, true);

    writer.ackSettings(readSingleSettings(Settings.HEADER_TABLE_SIZE, 2048));
    consumeFrame(wire); // SETTINGS ACK
    writer.headers(1, Collections.singletonList(new Header("foo", "bar")));
    writer.flush();
    consumeFrame(wire); // HEADERS #1 (emits update 2048)

    // Empty SETTINGS frame leaves HEADER_TABLE_SIZE unset; no update should be emitted.
    writer.ackSettings(new Settings());
    consumeFrame(wire); // SETTINGS ACK
    writer.headers(3, Collections.singletonList(new Header("baz", "qux")));
    writer.flush();
    Buffer payload = readHeadersPayload(wire);
    // Starts with literal header (0x40), not a dynamic table size update (0x20..0x3f).
    assertEquals(0x40, payload.getByte(0) & 0xff);
  }

  @Test
  public void ackSettingsHeaderTableSize_consecutiveShrinkThenGrow() throws IOException {
    Buffer wire = new Buffer();
    Http2.Writer writer = new Http2.Writer(wire, true);

    writer.ackSettings(readSingleSettings(Settings.HEADER_TABLE_SIZE, 100));
    consumeFrame(wire); // SETTINGS ACK
    writer.ackSettings(readSingleSettings(Settings.HEADER_TABLE_SIZE, 2000));
    consumeFrame(wire); // SETTINGS ACK

    writer.headers(1, Collections.singletonList(new Header("foo", "bar")));
    writer.flush();
    Buffer payload = readHeadersPayload(wire);
    Buffer copy = payload.clone();
    // Expect two dynamic table size updates per RFC 7541 §4.2: 100 (0x3f 0x45), then 2000.
    assertEquals(0x3f, copy.readByte() & 0xff);
    assertEquals(0x45, copy.readByte() & 0xff);
    assertEquals(0x3f, copy.readByte() & 0xff);
    assertEquals(0xb1, copy.readByte() & 0xff);
    assertEquals(0x0f, copy.readByte() & 0xff);
    Hpack.Reader decoder = new Hpack.Reader(4096, payload);
    decoder.readHeaders();
    assertEquals(2000, decoder.maxDynamicTableByteCount());
    assertEquals(Collections.singletonList(new Header("foo", "bar")),
        decoder.getAndResetHeaderList());
  }

  private Settings readSingleSettings(int id, int value) throws IOException {
    Buffer buf = new Buffer();
    writeSettingsFrame(buf, id, value);
    Http2.Reader reader = new Http2.Reader(buf, 4096, true);
    FrameReader.Handler handler = mock(FrameReader.Handler.class);
    assertTrue(reader.nextFrame(handler));
    ArgumentCaptor<Settings> captor = ArgumentCaptor.forClass(Settings.class);
    verify(handler).settings(eq(false), captor.capture());
    return captor.getValue();
  }

  private void writeSettingsFrame(Buffer sink, int id, int value) throws IOException {
    writeLength(sink, 6);
    sink.writeByte(TYPE_SETTINGS);
    sink.writeByte(FLAG_NONE);
    sink.writeInt(0);
    sink.writeShort(id);
    sink.writeInt(value);
  }

  private void writeHeadersFrame(Buffer sink, int streamId, Buffer headerBlock) throws IOException {
    writeLength(sink, (int) headerBlock.size());
    sink.writeByte(TYPE_HEADERS);
    sink.writeByte(FLAG_END_HEADERS);
    sink.writeInt(streamId);
    sink.writeAll(headerBlock);
  }

  private static Buffer readHeadersPayload(Buffer wire) throws IOException {
    int len = ((wire.readByte() & 0xff) << 16)
        | ((wire.readByte() & 0xff) << 8)
        | (wire.readByte() & 0xff);
    int type = wire.readByte() & 0xff;
    wire.readByte(); // flags
    wire.readInt(); // streamId
    assertEquals(TYPE_HEADERS, type);
    Buffer payload = new Buffer();
    payload.write(wire, len);
    return payload;
  }

  private static void consumeFrame(Buffer wire) throws IOException {
    int len = ((wire.readByte() & 0xff) << 16)
        | ((wire.readByte() & 0xff) << 8)
        | (wire.readByte() & 0xff);
    wire.skip(6L + len); // type(1) + flags(1) + streamId(4) + payload(len)
  }

  private Buffer createData(int flag, int length, int paddingLength) throws IOException {
    Buffer sink = new Buffer();
    writeLength(sink, length);
    sink.writeByte(TYPE_DATA);
    sink.writeByte(flag);
    sink.writeInt(STREAM_ID);
    if ((flag & FLAG_PADDED) != 0) {
      sink.writeByte((short)paddingLength);
    }
    char[] value = new char[length];
    Arrays.fill(value, '!');
    sink.write(new String(value).getBytes(StandardCharsets.UTF_8));
    return sink;
  }

  private void writeLength(BufferedSink sink, int length) throws IOException {
    sink.writeByte((length >>> 16 ) & 0xff);
    sink.writeByte((length >>> 8 ) & 0xff);
    sink.writeByte(length & 0xff);
  }
}
