/*
 * Copyright 2026 The gRPC Authors
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

import static com.google.common.truth.Truth.assertThat;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersDecoder;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.util.AsciiString;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class GrpcHttp2HeadersEncoderTest {
  private static final AsciiString CUSTOM_NAME = AsciiString.cached("custom-key");
  private static final AsciiString CUSTOM_VALUE = AsciiString.cached("custom-value");

  @Test
  public void dynamicTableEnabledByDefault() throws Exception {
    GrpcHttp2HeadersEncoder encoder = new GrpcHttp2HeadersEncoder(false);
    ByteBuf first = Unpooled.buffer();
    ByteBuf second = Unpooled.buffer();
    try {
      Http2Headers headers = new DefaultHttp2Headers().add(CUSTOM_NAME, CUSTOM_VALUE);

      encoder.encodeHeaders(1, headers, first);
      encoder.encodeHeaders(3, headers, second);

      assertThat(first.getUnsignedByte(first.readerIndex()) & 0xC0).isEqualTo(0x40);
      assertThat(second.getUnsignedByte(second.readerIndex()) & 0x80).isEqualTo(0x80);
    } finally {
      first.release();
      second.release();
      encoder.close();
    }
  }

  @Test
  public void dynamicTableDisabledPermanently_staticTableStillUsed() throws Exception {
    GrpcHttp2HeadersEncoder encoder = new GrpcHttp2HeadersEncoder(true);
    DefaultHttp2HeadersDecoder decoder = new DefaultHttp2HeadersDecoder();
    ByteBuf first = Unpooled.buffer();
    ByteBuf second = Unpooled.buffer();
    ByteBuf staticHeader = Unpooled.buffer();
    try {
      assertThat(encoder.maxHeaderTableSize()).isEqualTo(0);
      encoder.maxHeaderTableSize(4096);
      assertThat(encoder.maxHeaderTableSize()).isEqualTo(0);

      Http2Headers headers = new DefaultHttp2Headers().add(CUSTOM_NAME, CUSTOM_VALUE);
      encoder.encodeHeaders(1, headers, first);
      Http2Headers firstDecoded = decoder.decodeHeaders(1, first);
      assertThat(firstDecoded.get(CUSTOM_NAME).toString()).isEqualTo(CUSTOM_VALUE.toString());
      assertThat(decoder.configuration().maxHeaderTableSize()).isEqualTo(0);

      encoder.encodeHeaders(3, headers, second);
      assertThat(second.getUnsignedByte(second.readerIndex()) & 0x80).isEqualTo(0);
      Http2Headers secondDecoded = decoder.decodeHeaders(3, second);
      assertThat(secondDecoded.get(CUSTOM_NAME).toString()).isEqualTo(CUSTOM_VALUE.toString());

      encoder.encodeHeaders(5, new DefaultHttp2Headers().method(AsciiString.cached("GET")),
          staticHeader);
      assertThat(staticHeader.getUnsignedByte(staticHeader.readerIndex())).isEqualTo(0x82);
    } finally {
      first.release();
      second.release();
      staticHeader.release();
      encoder.close();
    }
  }
}
