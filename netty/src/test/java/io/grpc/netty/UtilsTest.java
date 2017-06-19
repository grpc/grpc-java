/*
 * Copyright 2015, gRPC Authors All rights reserved.
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.internal.GrpcUtil;
import io.netty.channel.ConnectTimeoutException;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.util.AsciiString;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link Utils}. */
@RunWith(JUnit4.class)
public class UtilsTest {
  private final Metadata.Key<String> userKey =
      Metadata.Key.of("user-key", Metadata.ASCII_STRING_MARSHALLER);
  private final String userValue =  "user-value";

  @Test
  public void testStatusFromThrowable() {
    Status s = Status.CANCELLED.withDescription("msg");
    assertSame(s, Utils.statusFromThrowable(new Exception(s.asException())));
    Throwable t;
    t = new ConnectTimeoutException("msg");
    assertStatusEquals(Status.UNAVAILABLE.withCause(t), Utils.statusFromThrowable(t));
    t = new Http2Exception(Http2Error.INTERNAL_ERROR, "msg");
    assertStatusEquals(Status.INTERNAL.withCause(t), Utils.statusFromThrowable(t));
    t = new Exception("msg");
    assertStatusEquals(Status.UNKNOWN.withCause(t), Utils.statusFromThrowable(t));
  }

  @Test
  public void convertClientHeaders_sanitizes() {
    Metadata metaData = new Metadata();

    // Intentionally being explicit here rather than relying on any pre-defined lists of headers,
    // since the goal of this test is to validate the correctness of such lists in the first place.
    metaData.put(GrpcUtil.CONTENT_TYPE_KEY, "to-be-removed");
    metaData.put(GrpcUtil.USER_AGENT_KEY, "to-be-removed");
    metaData.put(GrpcUtil.TE_HEADER, "to-be-removed");
    metaData.put(userKey, userValue);

    final String scheme = "https";
    final String userAgent = "user-agent";
    final String method = "POST";
    final String authority = "authority";
    final String path = "//testService/test";

    Http2Headers output =
        Utils.convertClientHeaders(
            metaData,
            new AsciiString(scheme),
            new AsciiString(path),
            new AsciiString(authority),
            new AsciiString(method),
            new AsciiString(userAgent));
    // 7 reserved headers, 1 user header
    assertEquals(7 + 1, output.size());
    assertEquals(GrpcUtil.CONTENT_TYPE_GRPC,
        output.get(GrpcUtil.CONTENT_TYPE_KEY.name()).toString());
    assertEquals(userAgent, output.get(GrpcUtil.USER_AGENT_KEY.name()).toString());
    assertEquals(GrpcUtil.TE_TRAILERS, output.get(GrpcUtil.TE_HEADER.name()).toString());
    assertEquals(userValue, output.get(userKey.name()).toString());
  }

  @Test
  public void convertServerHeaders_sanitizes() {
    Metadata metaData = new Metadata();

    // Intentionally being explicit here rather than relying on any pre-defined lists of headers,
    // since the goal of this test is to validate the correctness of such lists in the first place.
    metaData.put(GrpcUtil.CONTENT_TYPE_KEY, "to-be-removed");
    metaData.put(userKey, userValue);

    Http2Headers output = Utils.convertServerHeaders(metaData);
    // 2 reserved headers, 1 user header
    assertEquals(2 + 1, output.size());
    assertEquals(Utils.CONTENT_TYPE_GRPC.toString(),
        output.get(GrpcUtil.CONTENT_TYPE_KEY.name()).toString());
  }

  private static void assertStatusEquals(Status expected, Status actual) {
    assertEquals(expected.getCode(), actual.getCode());
    assertEquals(expected.getDescription(), actual.getDescription());
    assertEquals(expected.getCause(), actual.getCause());
  }
}
