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

import io.netty.handler.codec.http2.DefaultHttp2HeadersEncoder;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2HeadersEncoder;

/** HTTP/2 headers encoder with gRPC's HPACK configuration. */
final class GrpcHttp2HeadersEncoder extends DefaultHttp2HeadersEncoder {
  private static final int DEFAULT_DYNAMIC_TABLE_ARRAY_SIZE_HINT = 16;
  private static final int MIN_DYNAMIC_TABLE_ARRAY_SIZE_HINT = 2;

  private final boolean disableDynamicTable;

  GrpcHttp2HeadersEncoder(boolean disableDynamicTable) {
    super(
        Http2HeadersEncoder.NEVER_SENSITIVE,
        false,
        disableDynamicTable
            ? MIN_DYNAMIC_TABLE_ARRAY_SIZE_HINT : DEFAULT_DYNAMIC_TABLE_ARRAY_SIZE_HINT,
        Integer.MAX_VALUE);
    this.disableDynamicTable = disableDynamicTable;
    if (disableDynamicTable) {
      try {
        super.maxHeaderTableSize(0);
      } catch (Http2Exception e) {
        // Zero is always a valid HPACK dynamic table size.
        throw new AssertionError(e);
      }
    }
  }

  @Override
  public void maxHeaderTableSize(long max) throws Http2Exception {
    super.maxHeaderTableSize(disableDynamicTable ? 0 : max);
  }
}
