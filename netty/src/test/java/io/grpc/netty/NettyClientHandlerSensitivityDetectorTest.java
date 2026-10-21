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
import io.netty.handler.codec.http2.DefaultHttp2HeadersEncoder;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2HeadersEncoder;
import io.netty.util.AsciiString;
import java.util.Collections;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class NettyClientHandlerSensitivityDetectorTest {
  private static final AsciiString CUSTOM_NAME = AsciiString.cached("custom-key");
  private static final AsciiString CUSTOM_VALUE = AsciiString.cached("custom-value");

  @Test
  public void emptyConfigurationUsesDefaultPolicy() {
    assertThat(NettyClientHandler.sensitivityDetector(Collections.<AsciiString>emptySet()))
        .isSameInstanceAs(Http2HeadersEncoder.NEVER_SENSITIVE);
  }

  @Test
  public void configuredHeaderIsNeverIndexed() throws Exception {
    DefaultHttp2HeadersEncoder encoder = new DefaultHttp2HeadersEncoder(
        NettyClientHandler.sensitivityDetector(Collections.singleton(CUSTOM_NAME)),
        false,
        16,
        Integer.MAX_VALUE);
    DefaultHttp2HeadersDecoder decoder = new DefaultHttp2HeadersDecoder();
    ByteBuf first = Unpooled.buffer();
    ByteBuf second = Unpooled.buffer();
    try {
      Http2Headers headers = new DefaultHttp2Headers().add(CUSTOM_NAME, CUSTOM_VALUE);

      encoder.encodeHeaders(1, headers, first);
      encoder.encodeHeaders(3, headers, second);

      assertThat(first.getUnsignedByte(first.readerIndex()) & 0xF0).isEqualTo(0x10);
      assertThat(second.getUnsignedByte(second.readerIndex()) & 0xF0).isEqualTo(0x10);
      assertThat(decoder.decodeHeaders(1, first).get(CUSTOM_NAME).toString())
          .isEqualTo(CUSTOM_VALUE.toString());
      assertThat(decoder.decodeHeaders(3, second).get(CUSTOM_NAME).toString())
          .isEqualTo(CUSTOM_VALUE.toString());
    } finally {
      first.release();
      second.release();
      encoder.close();
    }
  }
}
