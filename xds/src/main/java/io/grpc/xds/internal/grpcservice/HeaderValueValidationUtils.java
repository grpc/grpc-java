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

import java.util.Locale;

/**
 * Utility class for validating HTTP headers.
 */
public final class HeaderValueValidationUtils {
  public static final int MAX_HEADER_LENGTH = 16384;

  private HeaderValueValidationUtils() {}

  /**
   * Returns true if the header key should be ignored for mutations or validation.
   *
   * @param key The header key (e.g., "content-type")
   */
  public static boolean shouldIgnore(String key) {
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
   * Returns true if the header value should be ignored.
   *
   * @param header The HeaderValue containing key and values
   */
  public static boolean shouldIgnore(HeaderValue header) {
    if (shouldIgnore(header.key())) {
      return true;
    }
    if (header.value().isPresent() && header.value().get().length() > MAX_HEADER_LENGTH) {
      return true;
    }
    if (header.rawValue().isPresent() && header.rawValue().get().size() > MAX_HEADER_LENGTH) {
      return true;
    }
    return false;
  }
}
