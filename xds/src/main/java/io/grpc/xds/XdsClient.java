/*
 * Copyright 2019 The gRPC Authors
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

import io.grpc.Status;

/**
 * An {@link XdsClient} instance encapsulates all of the logic for communicating with the xDS
 * server. It may create multiple RPC streams (or a single ADS stream) for a series of xDS
 * protocols (e.g., LDS, RDS, VHDS, CDS and EDS) over a single channel. Watch-based interfaces
 * are provided for each set of data needed by gRPC.
 *
 * <p>This class should only be instantiated by the xDS resolver but can be passed to load
 * balancing policies.
 */
abstract class XdsClient {

  /**
   * Data class containing the results of performing a series of resource discovery RPCs via
   * LDS/RDS/VHDS protocols. The results may include configurations for path/host rewriting,
   * traffic mirroring, retry or hedging, default timeouts and load balancing policy that will
   * be used to generate a service config.
   */
  // TODO(chengyuanzhang): content TBD, most information comes from VirtualHost proto.
  static final class ConfigUpdate {
    private String clusterName;

    String getClusterName() {
      return clusterName;
    }

    static Builder newBuilder() {
      return new Builder();
    }

    static final class Builder {
      private ConfigUpdate base;

      private Builder() {
        base = new ConfigUpdate();
      }

      Builder setClusterName(String clusterName) {
        base.clusterName = clusterName;
        return this;
      }

      ConfigUpdate build() {
        return base;
      }
    }
  }

  /**
   * Data class containing the results of performing a resource discovery RPC via CDS protocol.
   * The results include configurations for a single upstream cluster, such as endpoint discovery
   * type, load balancing policy, connection timeout and etc.
   */
  // TODO(zdapeng): content TBD.
  static final class ClusterUpdate {

  }

  /**
   * Data class containing the results of performing a resource discovery RPC via EDS protocol.
   * The results include endpoint addresses running the requested service, as well as
   * configurations for traffic control such as drop overloads, inter-cluster load balancing
   * policy and etc.
   */
  // TODO(zdapeng): content TBD.
  static final class EndpointUpdate {

  }

  /**
   * Config watcher interface. To be implemented by the xDS resolver.
   */
  interface ConfigWatcher {

    /**
     * Called when receiving an update on virtual host configurations.
     */
    void onConfigChanged(ConfigUpdate update);

    void onError(Status error);
  }

  /**
   * Cluster watcher interface.
   */
  interface ClusterWatcher {

    void onClusterChanged(ClusterUpdate update);

    void onError(Status error);
  }

  /**
   * Endpoint watcher interface.
   */
  interface EndpointWatcher {

    void onEndpointChanged(EndpointUpdate update);

    void onError(Status error);
  }

  /**
   * Starts resource discovery with xDS protocol. This should be the first method to be called in
   * this class. It should only be called once.
   */
  abstract void start();

  /**
   * Stops resource discovery. No method in this class should be called after this point.
   */
  abstract void shutdown();

  /**
   * Registers a data watcher for the given cluster.
   */
  void watchClusterData(String clusterName, ClusterWatcher watcher) {
  }

  /**
   * Unregisters the given cluster watcher.
   */
  void cancelClusterDataWatch(ClusterWatcher watcher) {
  }

  /**
   * Registers a data watcher for endpoints in the given cluster.
   */
  void watchEndpointData(String clusterName, EndpointWatcher watcher) {
  }

  /**
   * Unregisters the given endpoints watcher.
   */
  void cancelEndpointDataWatch(EndpointWatcher watcher) {
  }
}
