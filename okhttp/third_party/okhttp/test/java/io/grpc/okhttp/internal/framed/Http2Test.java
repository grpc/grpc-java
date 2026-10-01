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
import static io.grpc.okhttp.internal.framed.Http2.TYPE_WINDOW_UPDATE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import okio.Buffer;
import okio.BufferedSink;
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

  // RFC 9113 section 6.9: a WINDOW_UPDATE with a flow-control window increment of 0 is a
  // protocol error, but the *scope* of that error (stream vs connection) depends on whether
  // streamId is 0. The parser's job is only to preserve and dispatch streamId + increment;
  // scope classification is the Handler's responsibility. These tests pin that contract at the
  // parser layer, independent of any particular Handler's classification logic.
  @Test
  public void windowUpdateStreamScopedZeroIncrementIsDispatchedToHandler() throws IOException {
    Buffer bufferIn = createWindowUpdate(STREAM_ID, 0);
    http2FrameReader = new Http2.Reader(bufferIn, 100, true);

    assertTrue(http2FrameReader.nextFrame(mockHandler));

    verify(mockHandler).windowUpdate(STREAM_ID, 0);
  }

  @Test
  public void windowUpdateConnectionScopedZeroIncrementIsDispatchedToHandler() throws IOException {
    Buffer bufferIn = createWindowUpdate(0, 0);
    http2FrameReader = new Http2.Reader(bufferIn, 100, true);

    assertTrue(http2FrameReader.nextFrame(mockHandler));

    verify(mockHandler).windowUpdate(0, 0);
  }

  private Buffer createWindowUpdate(int streamId, long increment) throws IOException {
    Buffer sink = new Buffer();
    writeLength(sink, 4);
    sink.writeByte(TYPE_WINDOW_UPDATE);
    sink.writeByte(FLAG_NONE);
    sink.writeInt(streamId);
    sink.writeInt((int) increment);
    return sink;
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
