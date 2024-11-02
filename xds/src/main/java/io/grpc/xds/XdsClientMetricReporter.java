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

package io.grpc.xds;

import io.grpc.Internal;
import io.grpc.xds.client.XdsClient;

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
   * @param target Target of the gRPC channel.
   * @param xdsServer Target URI of the xDS server with which the XdsClient is communicating.
   * @param resourceType Type of XDS resource (e.g., "envoy.config.listener.v3.Listener").
   */
  default void reportResourceUpdates(long validResourceCount, long invalidResourceCount,
      String target, String xdsServer, String resourceType) {
  }

  /**
   * Reports number of xDS servers going from healthy to unhealthy.
   *
   * @param serverFailure Number of xDS server failures.
   * @param target Target of the gRPC channel.
   * @param xdsServer Target URI of the xDS server with which the XdsClient is communicating.
   */
  default void reportServerFailure(long serverFailure, String target, String xdsServer) {
  }

  /**
   * Sets the {@link XdsClient} instance.
   */
  default void setXdsClient(XdsClient xdsClient) {
  }

  /**
   * Closes the metric reporter.
   */
  default void close() {
  }

  /**
   * Interface for reporting metrics through callback.
   *
   */
  interface CallbackMetricReporter {

    /**
     * Reports number of resources in each cache state.
     *
     * @param resourceCount Number of resources.
     * @param cacheState Status of the resource metadata
     *     {@link io.grpc.xds.client.XdsClient.ResourceMetadata.ResourceMetadataStatus}.
     * @param resourceType Type of XDS resource (e.g., "envoy.config.listener.v3.Listener").
     * @param target Target of the gRPC channel.
     */
    // TODO(@dnvindhya): include the "authority" label once xds.authority is available.
    default void reportResourceCounts(long resourceCount, String cacheState, String resourceType,
        String target) {
    }

    /**
     * Reports whether xDS client has a working ADS stream to the xDS server.
     *
     * @param isConnected 1 if the client is connected to the xDS server, 0 otherwise.
     * @param target Target of the gRPC channel.
     * @param xdsServer Target URI of the xDS server with which the XdsClient is communicating.
     */
    default void reportServerConnections(int isConnected, String target, String xdsServer) {
    }
  }
}
