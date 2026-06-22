/*
 * Copyright 2025 The gRPC Authors
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

package io.grpc.xds.internal.grpcservice;

import com.google.protobuf.ByteString;
import java.util.Locale;

/**
 * Utility class for validating HTTP headers.
 */
public final class HeaderValueValidationUtils {
  public static final int MAX_HEADER_LENGTH = 16384;

  private HeaderValueValidationUtils() {}

  /**
   * Returns true if the header key is disallowed for mutations or validation.
   *
   * @param key The header key (e.g., "content-type")
   */
  public static boolean isDisallowed(String key) {
    if (key.isEmpty() || key.length() > MAX_HEADER_LENGTH) {
      return true;
    }
    if (!key.equals(key.toLowerCase(Locale.ROOT))) {
      return true;
    }
    if (key.startsWith("grpc-")) {
      return true;
    }
    if (key.startsWith(":") || key.equals("host")) {
      return true;
    }
    return false;
  }

  /**
   * Returns true if the header value is disallowed.
   *
   * @param header The HeaderValue containing key and values
   */
  public static boolean isDisallowed(HeaderValue header) {
    if (!header.isValid()) {
      return true;
    }
    if (isDisallowed(header.key())) {
      return true;
    }
    if (header.value().isPresent()) {
      String val = header.value().get();
      if (!header.key().endsWith("-bin") && !isValidAsciiHeaderValue(val)) {
        return true;
      }
    }
    if (header.rawValue().isPresent()) {
      ByteString rawVal = header.rawValue().get();
      if (!header.key().endsWith("-bin") && !isValidAsciiHeaderValue(rawVal.toStringUtf8())) {
        return true;
      }
    }
    return false;
  }

  /**
   * Validates that the header value contains only allowed ASCII characters as specified by
   * {@link io.grpc.Metadata.AsciiMarshaller}: horizontal tab (0x09), space (0x20), and visible
   * ASCII characters (0x21 - 0x7E).
   */
  private static boolean isValidAsciiHeaderValue(String value) {
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c != 0x09 && (c < 0x20 || c > 0x7E)) {
        return false;
      }
    }
    return true;
  }
}
