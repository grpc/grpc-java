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
   * Validates that the header key is non-empty and within allowed length.
   * Throws {@link IllegalArgumentException} if invalid.
   */
  public static void validateHeaderKey(String key) {
    if (key == null || key.isEmpty() || key.length() > MAX_HEADER_LENGTH) {
      throw new IllegalArgumentException("Invalid header key: " + key);
    }
  }

  /**
   * Validates that the header value is within allowed length and contains valid ASCII characters.
   * Throws {@link IllegalArgumentException} if invalid.
   */
  public static void validateHeaderValue(String key, String value) {
    validateHeaderKey(key);
    if (value == null || value.length() > MAX_HEADER_LENGTH) {
      throw new IllegalArgumentException("Header value length exceeds maximum allowed length");
    }
    if (!key.endsWith("-bin") && !isValidAsciiHeaderValue(value)) {
      throw new IllegalArgumentException(
          "Invalid ASCII characters in header value for key: " + key);
    }
  }

  /**
   * Validates that the raw header value is within allowed length and contains valid ASCII
   * characters. Throws {@link IllegalArgumentException} if invalid.
   */
  public static void validateHeaderValue(String key, ByteString rawValue) {
    validateHeaderKey(key);
    if (rawValue == null || rawValue.size() > MAX_HEADER_LENGTH) {
      throw new IllegalArgumentException("Header value length exceeds maximum allowed length");
    }
    if (!key.endsWith("-bin") && !isValidAsciiHeaderValue(rawValue.toStringUtf8())) {
      throw new IllegalArgumentException(
          "Invalid ASCII characters in header value for key: " + key);
    }
  }

  /**
   * Returns true if the header key is disallowed for mutations.
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
   * Returns true if the header is disallowed for mutations.
   *
   * @param header The HeaderValue
   */
  public static boolean isDisallowed(HeaderValue header) {
    return isDisallowed(header.key());
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
