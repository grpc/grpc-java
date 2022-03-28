/*
 * Copyright 2022 The gRPC Authors
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

package io.grpc.xds;

import io.grpc.Internal;
import java.util.Map;

/**
 * Accessor for global factory for managing XdsClient instance.
 */
@Internal
public final class InternalSharedXdsClientPoolProvider {
  // Prevent instantiation
  private InternalSharedXdsClientPoolProvider() {}

  public static void setDefaultProviderBootstrapOverride(Map<String, ?> bootstrap) {
    SharedXdsClientPoolProvider.getDefaultProvider().setBootstrapOverride(bootstrap);
  }
}
