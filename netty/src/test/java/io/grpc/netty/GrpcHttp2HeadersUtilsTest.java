/*
 * Copyright 2016 The gRPC Authors
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
import static io.grpc.Metadata.BINARY_BYTE_MARSHALLER;
import static io.grpc.internal.GrpcUtil.DEFAULT_MAX_HEADER_LIST_SIZE;
import static io.netty.util.AsciiString.of;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.common.collect.Iterables;
import com.google.common.io.BaseEncoding;
import io.grpc.Metadata;
import io.grpc.Metadata.Key;
import io.grpc.netty.GrpcHttp2HeadersUtils.GrpcHttp2ClientHeadersDecoder;
import io.grpc.netty.GrpcHttp2HeadersUtils.GrpcHttp2RequestHeaders;
import io.grpc.netty.GrpcHttp2HeadersUtils.GrpcHttp2ServerHeadersDecoder;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersEncoder;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2HeadersDecoder;
import io.netty.handler.codec.http2.Http2HeadersEncoder;
import io.netty.handler.codec.http2.Http2HeadersEncoder.SensitivityDetector;
import io.netty.util.AsciiString;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests for {@link GrpcHttp2HeadersUtils}.
 */
@RunWith(JUnit4.class)
@SuppressWarnings({ "BadImport", "UndefinedEquals" }) // AsciiString.of and AsciiString.equals
public class GrpcHttp2HeadersUtilsTest {

  private static final SensitivityDetector NEVER_SENSITIVE = new SensitivityDetector() {
    @Override
    public boolean isSensitive(CharSequence name, CharSequence value) {
      return false;
    }
  };

  private ByteBuf encodedHeaders;

  @After
  public void tearDown() {
    if (encodedHeaders != null) {
      encodedHeaders.release();
    }
  }

  @Test
  public void decode_requestHeaders() throws Http2Exception {
    Http2HeadersDecoder decoder = new GrpcHttp2ServerHeadersDecoder(DEFAULT_MAX_HEADER_LIST_SIZE);
    Http2HeadersEncoder encoder =
        new DefaultHttp2HeadersEncoder(NEVER_SENSITIVE);

    Http2Headers headers = new DefaultHttp2Headers(false);
    headers.add(of(":scheme"), of("https")).add(of(":method"), of("GET"))
        .add(of(":path"), of("index.html")).add(of(":authority"), of("foo.grpc.io"))
        .add(of("custom"), of("header"));
    encodedHeaders = Unpooled.buffer();
    encoder.encodeHeaders(1 /* randomly chosen */, headers, encodedHeaders);

    Http2Headers decodedHeaders = decoder.decodeHeaders(3 /* randomly chosen */, encodedHeaders);
    assertEquals(headers.get(of(":scheme")), decodedHeaders.scheme());
    assertEquals(headers.get(of(":method")), decodedHeaders.method());
    assertEquals(headers.get(of(":path")), decodedHeaders.path());
    assertEquals(headers.get(of(":authority")), decodedHeaders.authority());
    assertEquals(headers.get(of("custom")), decodedHeaders.get(of("custom")));
    assertEquals(headers.size(), decodedHeaders.size());

    String toString = decodedHeaders.toString();
    assertContainsKeyAndValue(toString, ":scheme", decodedHeaders.scheme());
    assertContainsKeyAndValue(toString, ":method", decodedHeaders.method());
    assertContainsKeyAndValue(toString, ":path", decodedHeaders.path());
    assertContainsKeyAndValue(toString, ":authority", decodedHeaders.authority());
    assertContainsKeyAndValue(toString, "custom", decodedHeaders.get(of("custom")));
  }

  @Test
  public void decode_responseHeaders() throws Http2Exception {
    Http2HeadersDecoder decoder = new GrpcHttp2ClientHeadersDecoder(DEFAULT_MAX_HEADER_LIST_SIZE);
    Http2HeadersEncoder encoder =
        new DefaultHttp2HeadersEncoder(NEVER_SENSITIVE);

    Http2Headers headers = new DefaultHttp2Headers(false);
    headers.add(of(":status"), of("200")).add(of("custom"), of("header"));
    encodedHeaders = Unpooled.buffer();
    encoder.encodeHeaders(1 /* randomly chosen */, headers, encodedHeaders);

    Http2Headers decodedHeaders = decoder.decodeHeaders(3 /* randomly chosen */, encodedHeaders);
    assertEquals(headers.get(of(":status")), decodedHeaders.get(of(":status")));
    assertEquals(headers.get(of("custom")), decodedHeaders.get(of("custom")));
    assertEquals(headers.size(), decodedHeaders.size());

    String toString = decodedHeaders.toString();
    assertContainsKeyAndValue(toString, ":status", decodedHeaders.get(of(":status")));
    assertContainsKeyAndValue(toString, "custom", decodedHeaders.get(of("custom")));
  }

  @Test
  public void decode_emptyHeaders() throws Http2Exception {
    Http2HeadersDecoder decoder = new GrpcHttp2ClientHeadersDecoder(8192);
    Http2HeadersEncoder encoder =
        new DefaultHttp2HeadersEncoder(NEVER_SENSITIVE);

    ByteBuf encodedHeaders = Unpooled.buffer();
    encoder.encodeHeaders(1 /* randomly chosen */, new DefaultHttp2Headers(false), encodedHeaders);

    Http2Headers decodedHeaders = decoder.decodeHeaders(3 /* randomly chosen */, encodedHeaders);
    assertEquals(0, decodedHeaders.size());
    assertThat(decodedHeaders.toString()).contains("[]");
  }

  // contains() is used by Netty 4.1.75+. https://github.com/grpc/grpc-java/issues/8981
  // Just implement everything pseudo headers for all methods; too many recent breakages.
  @Test
  public void grpcHttp2RequestHeaders_pseudoHeaders_notPresent() {
    Http2Headers http2Headers = new GrpcHttp2RequestHeaders(2);
    assertThat(http2Headers.get(AsciiString.of(":path"))).isNull();
    assertThat(http2Headers.get(AsciiString.of(":authority"))).isNull();
    assertThat(http2Headers.get(AsciiString.of(":method"))).isNull();
    assertThat(http2Headers.get(AsciiString.of(":scheme"))).isNull();
    assertThat(http2Headers.get(AsciiString.of(":status"))).isNull();

    assertThat(http2Headers.getAll(AsciiString.of(":path"))).isEmpty();
    assertThat(http2Headers.getAll(AsciiString.of(":authority"))).isEmpty();
    assertThat(http2Headers.getAll(AsciiString.of(":method"))).isEmpty();
    assertThat(http2Headers.getAll(AsciiString.of(":scheme"))).isEmpty();
    assertThat(http2Headers.getAll(AsciiString.of(":status"))).isEmpty();

    assertThat(http2Headers.contains(AsciiString.of(":path"))).isFalse();
    assertThat(http2Headers.contains(AsciiString.of(":authority"))).isFalse();
    assertThat(http2Headers.contains(AsciiString.of(":method"))).isFalse();
    assertThat(http2Headers.contains(AsciiString.of(":scheme"))).isFalse();
    assertThat(http2Headers.contains(AsciiString.of(":status"))).isFalse();

    assertThat(http2Headers.remove(AsciiString.of(":path"))).isFalse();
    assertThat(http2Headers.remove(AsciiString.of(":authority"))).isFalse();
    assertThat(http2Headers.remove(AsciiString.of(":method"))).isFalse();
    assertThat(http2Headers.remove(AsciiString.of(":scheme"))).isFalse();
    assertThat(http2Headers.remove(AsciiString.of(":status"))).isFalse();
  }

  @Test
  public void grpcHttp2RequestHeaders_pseudoHeaders_present() {
    Http2Headers http2Headers = new GrpcHttp2RequestHeaders(2);
    http2Headers.add(AsciiString.of(":path"), AsciiString.of("mypath"));
    http2Headers.add(AsciiString.of(":authority"), AsciiString.of("myauthority"));
    http2Headers.add(AsciiString.of(":method"), AsciiString.of("mymethod"));
    http2Headers.add(AsciiString.of(":scheme"), AsciiString.of("myscheme"));

    assertThat(http2Headers.get(AsciiString.of(":path"))).isEqualTo(AsciiString.of("mypath"));
    assertThat(http2Headers.get(AsciiString.of(":authority")))
        .isEqualTo(AsciiString.of("myauthority"));
    assertThat(http2Headers.get(AsciiString.of(":method"))).isEqualTo(AsciiString.of("mymethod"));
    assertThat(http2Headers.get(AsciiString.of(":scheme"))).isEqualTo(AsciiString.of("myscheme"));

    assertThat(http2Headers.getAll(AsciiString.of(":path")))
        .containsExactly(AsciiString.of("mypath"));
    assertThat(http2Headers.getAll(AsciiString.of(":authority")))
        .containsExactly(AsciiString.of("myauthority"));
    assertThat(http2Headers.getAll(AsciiString.of(":method")))
        .containsExactly(AsciiString.of("mymethod"));
    assertThat(http2Headers.getAll(AsciiString.of(":scheme")))
        .containsExactly(AsciiString.of("myscheme"));

    assertThat(http2Headers.contains(AsciiString.of(":path"))).isTrue();
    assertThat(http2Headers.contains(AsciiString.of(":authority"))).isTrue();
    assertThat(http2Headers.contains(AsciiString.of(":method"))).isTrue();
    assertThat(http2Headers.contains(AsciiString.of(":scheme"))).isTrue();

    assertThat(http2Headers.remove(AsciiString.of(":path"))).isTrue();
    assertThat(http2Headers.remove(AsciiString.of(":authority"))).isTrue();
    assertThat(http2Headers.remove(AsciiString.of(":method"))).isTrue();
    assertThat(http2Headers.remove(AsciiString.of(":scheme"))).isTrue();

    assertThat(http2Headers.contains(AsciiString.of(":path"))).isFalse();
    assertThat(http2Headers.contains(AsciiString.of(":authority"))).isFalse();
    assertThat(http2Headers.contains(AsciiString.of(":method"))).isFalse();
    assertThat(http2Headers.contains(AsciiString.of(":scheme"))).isFalse();
  }

  @Test
  public void grpcHttp2RequestHeaders_pseudoHeaders_set() {
    Http2Headers http2Headers = new GrpcHttp2RequestHeaders(2);
    http2Headers.set(AsciiString.of(":path"), AsciiString.of("mypath"));
    http2Headers.set(AsciiString.of(":authority"), AsciiString.of("myauthority"));
    http2Headers.set(AsciiString.of(":method"), AsciiString.of("mymethod"));
    http2Headers.set(AsciiString.of(":scheme"), AsciiString.of("myscheme"));

    assertThat(http2Headers.getAll(AsciiString.of(":path")))
        .containsExactly(AsciiString.of("mypath"));
    assertThat(http2Headers.getAll(AsciiString.of(":authority")))
        .containsExactly(AsciiString.of("myauthority"));
    assertThat(http2Headers.getAll(AsciiString.of(":method")))
        .containsExactly(AsciiString.of("mymethod"));
    assertThat(http2Headers.getAll(AsciiString.of(":scheme")))
        .containsExactly(AsciiString.of("myscheme"));

    http2Headers.set(AsciiString.of(":path"), AsciiString.of("mypath2"));
    http2Headers.set(AsciiString.of(":authority"), AsciiString.of("myauthority2"));
    http2Headers.set(AsciiString.of(":method"), AsciiString.of("mymethod2"));
    http2Headers.set(AsciiString.of(":scheme"), AsciiString.of("myscheme2"));

    assertThat(http2Headers.getAll(AsciiString.of(":path")))
        .containsExactly(AsciiString.of("mypath2"));
    assertThat(http2Headers.getAll(AsciiString.of(":authority")))
        .containsExactly(AsciiString.of("myauthority2"));
    assertThat(http2Headers.getAll(AsciiString.of(":method")))
        .containsExactly(AsciiString.of("mymethod2"));
    assertThat(http2Headers.getAll(AsciiString.of(":scheme")))
        .containsExactly(AsciiString.of("myscheme2"));
  }

  @Test
  public void grpcHttp2RequestHeaders_pseudoHeaders_addWhenPresent_throws() {
    Http2Headers http2Headers = new GrpcHttp2RequestHeaders(2);
    http2Headers.add(AsciiString.of(":path"), AsciiString.of("mypath"));
    try {
      http2Headers.add(AsciiString.of(":path"), AsciiString.of("mypath2"));
      fail("Expected exception");
    } catch (Exception ex) {
      // expected
    }
  }

  @Test
  public void grpcHttp2RequestHeaders_pseudoHeaders_addInvalid_throws() {
    Http2Headers http2Headers = new GrpcHttp2RequestHeaders(2);
    try {
      http2Headers.add(AsciiString.of(":status"), AsciiString.of("mystatus"));
      fail("Expected exception");
    } catch (Exception ex) {
      // expected
    }
  }

  @Test
  public void dupBinHeadersWithComma() {
    Key<byte[]> key = Key.of("bytes-bin", BINARY_BYTE_MARSHALLER);
    Http2Headers http2Headers = new GrpcHttp2RequestHeaders(2);
    http2Headers.add(AsciiString.of("bytes-bin"), AsciiString.of("BaS,e6,,4+,padding=="));
    http2Headers.add(AsciiString.of("bytes-bin"), AsciiString.of("more"));
    http2Headers.add(AsciiString.of("bytes-bin"), AsciiString.of(""));
    Metadata recoveredHeaders = Utils.convertHeaders(http2Headers);
    byte[][] values = Iterables.toArray(recoveredHeaders.getAll(key), byte[].class);

    assertTrue(Arrays.deepEquals(
        new byte[][] {
            BaseEncoding.base64().decode("BaS"),
            BaseEncoding.base64().decode("e6"),
            BaseEncoding.base64().decode(""),
            BaseEncoding.base64().decode("4+"),
            BaseEncoding.base64().decode("padding"),
            BaseEncoding.base64().decode("more"),
            BaseEncoding.base64().decode("")},
        values));
  }

  @Test
  public void headerGetAll_notPresent() {
    Http2Headers http2Headers = new GrpcHttp2RequestHeaders(2);
    http2Headers.add(AsciiString.of("notit"), AsciiString.of("val"));
    assertThat(http2Headers.getAll(AsciiString.of("dont-care"))).isEmpty();
  }

  @Test
  public void headerGetAll_multiplePresent() {
    // getAll is used by Netty 4.1.60+. https://github.com/grpc/grpc-java/issues/7953
    Http2Headers http2Headers = new GrpcHttp2RequestHeaders(2);
    http2Headers.add(AsciiString.of("notit1"), AsciiString.of("val1"));
    http2Headers.add(AsciiString.of("multiple"), AsciiString.of("value1"));
    http2Headers.add(AsciiString.of("notit2"), AsciiString.of("val2"));
    http2Headers.add(AsciiString.of("multiple"), AsciiString.of("value2"));
    http2Headers.add(AsciiString.of("notit3"), AsciiString.of("val3"));
    assertThat(http2Headers.size()).isEqualTo(5);
    assertThat(http2Headers.getAll(AsciiString.of("multiple")))
        .containsExactly(AsciiString.of("value1"), AsciiString.of("value2"));
  }

  @Test
  public void headerIterator_empty() {
    Http2Headers http2Headers = new GrpcHttp2RequestHeaders(2);
    Iterator<Map.Entry<CharSequence, CharSequence>> it = http2Headers.iterator();
    assertThat(it.hasNext()).isFalse();
  }

  @Test
  public void headerIteartor_nonEmpty() {
    Http2Headers http2Headers = new GrpcHttp2RequestHeaders(2);
    http2Headers.add(AsciiString.of("notit1"), AsciiString.of("val1"));
    http2Headers.add(AsciiString.of("multiple"), AsciiString.of("value1"));
    http2Headers.add(AsciiString.of("notit2"), AsciiString.of("val2"));
    http2Headers.add(AsciiString.of("multiple"), AsciiString.of("value2"));
    http2Headers.add(AsciiString.of("notit3"), AsciiString.of("val3"));
    Iterator<Map.Entry<CharSequence, CharSequence>> it = http2Headers.iterator();

    assertNextEntry(it, "notit1", AsciiString.of("val1"));
    assertNextEntry(it, "multiple", AsciiString.of("value1"));
    assertNextEntry(it, "notit2", AsciiString.of("val2"));
    assertNextEntry(it, "multiple", AsciiString.of("value2"));
    assertNextEntry(it, "notit3", AsciiString.of("val3"));
    assertThat(it.hasNext()).isFalse();
  }

  private static void assertNextEntry(
      Iterator<Map.Entry<CharSequence, CharSequence>> it, CharSequence key, CharSequence value) {
    Map.Entry<CharSequence, CharSequence> entry = it.next();
    assertThat(entry.getKey()).isEqualTo(key);
    assertThat(entry.getValue()).isEqualTo(value);
  }

  @Test
  public void headerRemove_notPresent() {
    Http2Headers http2Headers = new GrpcHttp2RequestHeaders(2);
    http2Headers.add(AsciiString.of("dont-care"), AsciiString.of("value"));
    assertThat(http2Headers.remove(AsciiString.of("not-seen"))).isFalse();
    assertThat(http2Headers.size()).isEqualTo(1);
    assertThat(http2Headers.getAll(AsciiString.of("dont-care")))
        .containsExactly(AsciiString.of("value"));
  }

  @Test
  public void headerRemove_multiplePresent() {
    Http2Headers http2Headers = new GrpcHttp2RequestHeaders(2);
    http2Headers.add(AsciiString.of("notit1"), AsciiString.of("val1"));
    http2Headers.add(AsciiString.of("multiple"), AsciiString.of("value1"));
    http2Headers.add(AsciiString.of("notit2"), AsciiString.of("val2"));
    http2Headers.add(AsciiString.of("multiple"), AsciiString.of("value2"));
    http2Headers.add(AsciiString.of("notit3"), AsciiString.of("val3"));
    assertThat(http2Headers.remove(AsciiString.of("multiple"))).isTrue();
    assertThat(http2Headers.size()).isEqualTo(3);
    assertThat(http2Headers.getAll(AsciiString.of("notit1")))
        .containsExactly(AsciiString.of("val1"));
    assertThat(http2Headers.getAll(AsciiString.of("notit2")))
        .containsExactly(AsciiString.of("val2"));
    assertThat(http2Headers.getAll(AsciiString.of("notit3")))
        .containsExactly(AsciiString.of("val3"));
  }

  @Test
  public void headerSetLong() {
    // setLong is used by Netty 4.1.60+. https://github.com/grpc/grpc-java/issues/7953
    Http2Headers http2Headers = new GrpcHttp2RequestHeaders(2);
    http2Headers.add(AsciiString.of("long-header"), AsciiString.of("1"));
    http2Headers.add(AsciiString.of("long-header"), AsciiString.of("2"));
    http2Headers.setLong(AsciiString.of("long-header"), 3);
    assertThat(http2Headers.size()).isEqualTo(1);
    assertThat(http2Headers.getAll(AsciiString.of("long-header")))
        .containsExactly(AsciiString.of("3"));
  }

  private static void assertContainsKeyAndValue(String str, CharSequence key, CharSequence value) {
    assertThat(str).contains(key.toString());
    assertThat(str).contains(value.toString());
  }
}
