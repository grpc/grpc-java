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

import static io.grpc.okhttp.internal.framed.Http2.FLAG_NONE;
import static io.grpc.okhttp.internal.framed.Http2.FLAG_PADDED;
import static io.grpc.okhttp.internal.framed.Http2.TYPE_DATA;
import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import okio.Buffer;
import okio.BufferedSink;
import okio.ByteString;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
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
