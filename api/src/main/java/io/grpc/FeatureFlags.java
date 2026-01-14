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

package io.grpc;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;

class FeatureFlags {
  private static boolean enableRfc3986Uris = getFlag("GRPC_ENABLE_RFC3986_URIS", false);

  /** Whether to parse targets as RFC 3986 URIs (true), or use {@link java.net.URI} (false). */
  @VisibleForTesting
  static boolean setRfc3986UrisEnabled(boolean value) {
    boolean prevValue = enableRfc3986Uris;
    enableRfc3986Uris = value;
    return prevValue;
  }

  /** Whether to parse targets as RFC 3986 URIs (true), or use {@link java.net.URI} (false). */
  static boolean getRfc3986UrisEnabled() {
    return enableRfc3986Uris;
  }

  static boolean getFlag(String envVarName, boolean enableByDefault) {
    String envVar = System.getenv(envVarName);
    if (envVar == null) {
      envVar = System.getProperty(envVarName);
    }
    if (envVar != null) {
      envVar = envVar.trim();
    }
    if (enableByDefault) {
      return Strings.isNullOrEmpty(envVar) || Boolean.parseBoolean(envVar);
    } else {
      return !Strings.isNullOrEmpty(envVar) && Boolean.parseBoolean(envVar);
    }
  }

  private FeatureFlags() {}
}
