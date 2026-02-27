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

package io.grpc.xds.internal.matcher;

import static com.google.common.base.Preconditions.checkNotNull;

import com.github.xds.core.v3.TypedExtensionConfig;
import com.google.protobuf.InvalidProtocolBufferException;
import io.envoyproxy.envoy.type.matcher.v3.HttpRequestHeaderMatchInput;
import io.grpc.Metadata;
import io.grpc.xds.internal.matcher.MatcherRunner.MatchContext;

/**
 * MatchInput for extracting HTTP headers.
 */
final class HeaderMatchInput implements MatchInput {
  private final String headerName;
    
  HeaderMatchInput(String headerName) {
    this.headerName = checkNotNull(headerName, "headerName");
    if (headerName.isEmpty() || headerName.length() >= 16384) {
      throw new IllegalArgumentException(
          "Header name length must be in range [1, 16384): " + headerName.length());
    }
    if (!headerName.equals(headerName.toLowerCase(java.util.Locale.ROOT))) {
      throw new IllegalArgumentException("Header name must be lowercase: " + headerName);
    }
    try {
      if (headerName.endsWith(Metadata.BINARY_HEADER_SUFFIX)) {
        Metadata.Key.of(headerName, Metadata.BINARY_BYTE_MARSHALLER);
      } else {
        Metadata.Key.of(headerName, Metadata.ASCII_STRING_MARSHALLER);
      }
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Invalid header name: " + headerName, e);
    }
  }
    
  @Override
  public Object apply(MatchContext context) {
    if ("te".equals(headerName)) {
      return null;
    }
    if (headerName.endsWith(Metadata.BINARY_HEADER_SUFFIX)) {
      Iterable<byte[]> values = context.getMetadata().getAll(
          Metadata.Key.of(headerName, Metadata.BINARY_BYTE_MARSHALLER));
      if (values == null) {
        return null;
      }
      StringBuilder sb = new StringBuilder();
      boolean first = true;
      for (byte[] value : values) {
        if (!first) {
          sb.append(",");
        }
        first = false;
        sb.append(com.google.common.io.BaseEncoding.base64().encode(value));
      }
      return sb.toString();
    }
    Metadata metadata = context.getMetadata();
    Iterable<String> values = metadata.getAll(
        Metadata.Key.of(headerName, Metadata.ASCII_STRING_MARSHALLER));
    if (values == null) {
      return null;
    }
    return String.join(",", values);
  }

  @Override
  public Class<?> outputType() {
    return String.class;
  }

  static final class Provider implements MatchInputProvider {
    @Override
    public MatchInput getInput(TypedExtensionConfig config) {
      try {
        HttpRequestHeaderMatchInput proto = config.getTypedConfig()
            .unpack(HttpRequestHeaderMatchInput.class);
        return new HeaderMatchInput(proto.getHeaderName());
      } catch (InvalidProtocolBufferException e) {
        throw new IllegalArgumentException(
            "Invalid input config: " + config.getTypedConfig().getTypeUrl(), e);
      }
    }

    @Override
    public String typeUrl() {
      return "type.googleapis.com/envoy.type.matcher.v3.HttpRequestHeaderMatchInput";
    }
  }
}
