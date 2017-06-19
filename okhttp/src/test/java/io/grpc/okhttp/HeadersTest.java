/*
 * Copyright 2017, gRPC Authors All rights reserved.
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

package io.grpc.okhttp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.grpc.Metadata;
import io.grpc.internal.GrpcUtil;
import io.grpc.okhttp.internal.framed.Header;
import java.util.List;
import org.junit.Test;

public class HeadersTest {
  @Test
  public void createRequestHeaders_sanitizes() {
    Metadata metaData = new Metadata();

    // Intentionally being explicit here rather than relying on any pre-defined lists of headers,
    // since the goal of this test is to validate the correctness of such lists in the first place.
    metaData.put(GrpcUtil.CONTENT_TYPE_KEY, "to-be-removed");
    metaData.put(GrpcUtil.USER_AGENT_KEY, "to-be-removed");
    metaData.put(GrpcUtil.TE_HEADER, "to-be-removed");


    Metadata.Key<String> userKey = Metadata.Key.of("user-key", Metadata.ASCII_STRING_MARSHALLER);
    String userValue =  "user-value";
    metaData.put(userKey, userValue);

    final String path = "//testServerice/test";
    final String authority = "localhost";
    final String userAgent = "useragent";

    List<Header> headers = Headers.createRequestHeaders(metaData, path, authority, userAgent);

    // 7 reserved headers, 1 user header
    assertEquals(7 + 1, headers.size());
    assertTrue(headers.contains(Headers.CONTENT_TYPE_HEADER));
    assertTrue(headers.contains(new Header(GrpcUtil.USER_AGENT_KEY.name(), userAgent)));
    assertTrue(headers.contains(new Header(GrpcUtil.TE_HEADER.name(), GrpcUtil.TE_TRAILERS)));
    assertTrue(headers.contains(new Header(userKey.name(), userValue)));
  }
}
