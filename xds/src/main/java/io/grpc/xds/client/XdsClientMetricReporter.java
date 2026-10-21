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

package io.grpc.xds.client;

import io.grpc.Internal;

/**
 * Interface for reporting metrics from the xDS client.
 */
@Internal
public interface XdsClientMetricReporter {

  /**
   * Reports number of valid and invalid resources.
   *
   * @param validResourceCount Number of resources that were valid.
   * @param invalidResourceCount Number of resources that were invalid.
   * @param xdsServer Target URI of the xDS server with which the XdsClient is communicating.
   * @param resourceType Type of XDS resource (e.g., "envoy.config.listener.v3.Listener").
   */
  default void reportResourceUpdates(long validResourceCount, long invalidResourceCount,
      String xdsServer, String resourceType) {
  }

  /**
   * Reports number of xDS servers going from healthy to unhealthy.
   *
   * @param serverFailure Number of xDS server failures.
   * @param xdsServer Target URI of the xDS server with which the XdsClient is communicating.
   */
  default void reportServerFailure(long serverFailure, String xdsServer) {
  }

}
