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
 * We need this indirection, to de couple Xds from OpenTelemetry
 */
@Internal
public interface XdsClientMetricReporter {

  /**
   * Reports resource update counts.
   *
   * @param validResourceCount the number of resources that were successfully updated.
   * @param invalidResourceCount the number of resources that failed to update.
   * @param target the xDS management server name for the load balancing policy this update is for.
   * @param xdsServer the xDS management server address for this update.
   * @param resourceType the type of resource (e.g., "LDS", "RDS", "CDS", "EDS").
   */
  default void reportResourceUpdates(long validResourceCount, long invalidResourceCount,
      String target, String xdsServer, String resourceType) {
  }

  /**
   * Reports xDS server failure counts.
   *
   * @param serverFailure the number of times the xDS server has failed.
   * @param target the xDS management server name for the load balancing policy this failure is for.
   * @param xdsServer the xDS management server address for this failure.
   */
  default void reportServerFailure(long serverFailure, String target, String xdsServer) {
  }

  /**
   * Sets the {@link XdsClient} instance to the reporter.
   *
   * @param xdsClient the {@link XdsClient} instance.
   */
  default void setXdsClient(XdsClient xdsClient) {
  }

  /**
   * Closes the metric reporter.
   */
  default void close() {
  }

  /**
   * Interface for reporting metrics from the xDS client callbacks.
   *
   */
  interface CallbackMetricReporter {

    /**
     * Reports resource counts in the cache.
     *
     * @param resourceCount the number of resources in the cache.
     * @param cacheState the state of the cache (e.g., "SYNCED", "DOES_NOT_EXIST").
     * @param resourceType the type of resource (e.g., "LDS", "RDS", "CDS", "EDS").
     * @param target the xDS management server name for the load balancing policy this count is
     *     for.
     */
    // TODO(@dnvindhya): include the "authority" label once authority is available.
    default void reportResourceCounts(long resourceCount, String cacheState, String resourceType,
        String target) {
    }

    /**
     * Reports server connection status.
     *
     * @param isConnected {@code true} if the client is connected to the xDS server, {@code false}
     *        otherwise.
     * @param target the xDS management server name for the load balancing policy this connection
     *        is for.
     * @param xdsServer the xDS management server address for this connection.
     * @since 0.1.0
     */
    default void reportServerConnections(int isConnected, String target, String xdsServer) {
    }
  }
}
