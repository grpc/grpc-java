/*
 * Copyright 2024 The gRPC Authors
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

package io.grpc.xds.internal;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.BaseEncoding;
import io.grpc.Metadata;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;


public class MetadataHelper {
  public static ImmutableMap<String, String> metadataToHeaders(Metadata metadata) {
    return metadata.keys().stream().collect(ImmutableMap.toImmutableMap(
        headerName -> headerName,
        headerName -> Strings.nullToEmpty(deserializeHeader(metadata, headerName))));
  }

  @Nullable
  public static String deserializeHeader(Metadata metadata, String headerName) {
    if (headerName.endsWith(Metadata.BINARY_HEADER_SUFFIX)) {
      Metadata.Key<byte[]> key;
      try {
        key = Metadata.Key.of(headerName, Metadata.BINARY_BYTE_MARSHALLER);
      } catch (IllegalArgumentException e) {
        return null;
      }
      Iterable<byte[]> values = metadata.getAll(key);
      if (values == null) {
        return null;
      }
      List<String> encoded = new ArrayList<>();
      for (byte[] v : values) {
        encoded.add(BaseEncoding.base64().omitPadding().encode(v));
      }
      return String.join(",", encoded);
    }
    Metadata.Key<String> key;
    try {
      key = Metadata.Key.of(headerName, Metadata.ASCII_STRING_MARSHALLER);
    } catch (IllegalArgumentException e) {
      return null;
    }
    Iterable<String> values = metadata.getAll(key);
    return values == null ? null : String.join(",", values);
  }

  public static boolean containsHeader(Metadata metadata, String headerName) {
    return metadata.containsKey(Metadata.Key.of(headerName, Metadata.ASCII_STRING_MARSHALLER));
  }
}
