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

package io.grpc.xds.internal;

/**
 * Utility for validating header keys and values against xDS and Envoy specifications.
 */
public final class XdsHeaderValidator {

  private XdsHeaderValidator() {}

  /**
   * Returns whether the header parameter is valid. The length to check is either the
   * length of the string value or the size of the binary raw value.
   */
  public static boolean isValid(String key, int valueLength) {
    if (key.isEmpty() || !key.equals(key.toLowerCase(java.util.Locale.ROOT)) || key.length() > 16384
        || key.equals("host") || key.startsWith(":")) {
      return false;
    }
    if (valueLength > 16384) {
      return false;
    }
    return true;
  }
}
