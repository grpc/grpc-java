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

/** Global variables that govern major changes to the behavior of more than one grpc module. */
@Internal
public class InternalFeatureFlags {

  /** Whether to parse targets as RFC 3986 URIs (true), or use {@link java.net.URI} (false). */
  @VisibleForTesting
  public static boolean setRfc3986UrisEnabled(boolean value) {
    return FeatureFlags.setRfc3986UrisEnabled(value);
  }

  /** Whether to parse targets as RFC 3986 URIs (true), or use {@link java.net.URI} (false). */
  public static boolean getRfc3986UrisEnabled() {
    return FeatureFlags.getRfc3986UrisEnabled();
  }

  public static boolean getFlag(String envVarName, boolean enableByDefault) {
    return FeatureFlags.getFlag(envVarName, enableByDefault);
  }

  private InternalFeatureFlags() {}
}
