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

import com.google.common.collect.ImmutableSet;
import com.google.common.io.BaseEncoding;
import com.google.errorprone.annotations.DoNotCall;
import io.grpc.Metadata;
import java.util.AbstractMap;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * A Map view over Metadata and MatchContext for CEL attribute resolution.
 * Supports efficient lookup of headers and pseudo-headers without unnecessary copying.
 */
final class HeadersWrapper extends AbstractMap<String, String> {
  private static final ImmutableSet<String> PSEUDO_HEADERS =
      ImmutableSet.of(":method", ":authority", ":path");
  private final MatchContext context;

  HeadersWrapper(MatchContext context) {
    this.context = context;
  }

  @Override
  @Nullable
  public String get(Object key) {
    if (!(key instanceof String)) {
      return null;
    }
    String headerName = ((String) key).toLowerCase(java.util.Locale.ROOT);
    // The "te" header is a hop-by-hop header used for protocol signaling (trailers).
    // Per gRFC A41, it must be treated as not present to prevent matching logic
    // from depending on transport-level semantics.
    if (headerName.equals("te")) {
      return null;
    }
    switch (headerName) {
      case ":method": return context.getMethod();
      case ":authority": return context.getHost();
      case "host": return context.getHost();
      case ":path": return context.getPath();
      default: return getHeader(headerName);
    }
  }

  @Nullable
  private String getHeader(String headerName) {
    try {
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
          sb.append(BaseEncoding.base64().omitPadding().encode(value));
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
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  @Override
  public boolean containsKey(Object key) {
    if (!(key instanceof String)) {
      return false;
    }
    String headerName = ((String) key).toLowerCase(java.util.Locale.ROOT);
    if (headerName.equals("te")) {
      return false;
    }
    if (PSEUDO_HEADERS.contains(headerName) || headerName.equals("host")) {
      return true;
    }
    try {
      if (headerName.endsWith(Metadata.BINARY_HEADER_SUFFIX)) {
        return context.getMetadata().containsKey(
            Metadata.Key.of(headerName, Metadata.BINARY_BYTE_MARSHALLER));
      }
      return context.getMetadata().containsKey(
          Metadata.Key.of(headerName, Metadata.ASCII_STRING_MARSHALLER));
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  @Override
  public Set<String> keySet() {
    ImmutableSet.Builder<String> builder = ImmutableSet.builder();
    for (String key : context.getMetadata().keys()) {
      String lowerKey = key.toLowerCase(java.util.Locale.ROOT);
      // Filter out any keys we provide specialized aliases/values for.
      if (!lowerKey.equals("te") 
          && !lowerKey.equals("host") 
          && !PSEUDO_HEADERS.contains(lowerKey)) {
        builder.add(key);
      }
    }
    builder.addAll(PSEUDO_HEADERS);
    builder.add("host");
    return builder.build();
  }

  @Override
  public int size() {
    return keySet().size();
  }

  @Override
  @DoNotCall("Always throws UnsupportedOperationException")
  public Set<Entry<String, String>> entrySet() {
    throw new UnsupportedOperationException(
        "Should not be called to prevent resolving all header values.");
  }
}
