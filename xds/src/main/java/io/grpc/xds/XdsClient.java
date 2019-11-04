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

import com.google.common.base.Preconditions;
import io.grpc.Status;
import io.grpc.xds.EnvoyProtoData.DropOverload;
import io.grpc.xds.EnvoyProtoData.Locality;
import io.grpc.xds.EnvoyProtoData.LocalityLbEndpoints;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

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
  static final class ConfigUpdate {
    private String clusterName;

    private ConfigUpdate(String clusterName) {
      this.clusterName = clusterName;
    }

    String getClusterName() {
      return clusterName;
    }

    static Builder newBuilder() {
      return new Builder();
    }

    static final class Builder {
      private String clusterName;

      Builder setClusterName(String clusterName) {
        this.clusterName = clusterName;
        return this;
      }

      ConfigUpdate build() {
        Preconditions.checkState(clusterName != null, "clusterName is not set");
        return new ConfigUpdate(clusterName);
      }
    }
  }

  /**
   * Data class containing the results of performing a resource discovery RPC via CDS protocol.
   * The results include configurations for a single upstream cluster, such as endpoint discovery
   * type, load balancing policy, connection timeout and etc.
   */
  static final class ClusterUpdate {
    private String clusterName;
    private String edsServiceName;
    private String lbPolicy;
    private boolean enableLrs;
    private String lrsServerName;

    private ClusterUpdate(String clusterName, String edsServiceName, String lbPolicy,
        boolean enableLrs, @Nullable String lrsServerName) {
      this.clusterName = clusterName;
      this.edsServiceName = edsServiceName;
      this.lbPolicy = lbPolicy;
      this.enableLrs = enableLrs;
      this.lrsServerName = lrsServerName;
    }

    String getClusterName() {
      return clusterName;
    }

    /**
     * Returns the resource name for EDS requests.
     */
    String getEdsServiceName() {
      return edsServiceName;
    }

    /**
     * Returns the policy of balancing loads to endpoints. Always returns "round_robin".
     */
    String getLbPolicy() {
      return lbPolicy;
    }

    /**
     * Returns true if LRS is enabled.
     */
    boolean isEnableLrs() {
      return enableLrs;
    }

    /**
     * Returns the server name to send client load reports to if LRS is enabled. {@code null} if
     * {@link #isEnableLrs()} returns {@code false}.
     */
    @Nullable
    String getLrsServerName() {
      return lrsServerName;
    }

    static Builder newBuilder() {
      return new Builder();
    }

    static final class Builder {
      private String clusterName;
      private String edsServiceName;
      private String lbPolicy;
      private boolean enableLrs;
      @Nullable
      private String lrsServerName;

      Builder setClusterName(String clusterName) {
        this.clusterName = clusterName;
        return this;
      }

      Builder setEdsServiceName(String edsServiceName) {
        this.edsServiceName = edsServiceName;
        return this;
      }

      Builder setLbPolicy(String lbPolicy) {
        this.lbPolicy = lbPolicy;
        return this;
      }

      Builder setEnableLrs(boolean enableLrs) {
        this.enableLrs = enableLrs;
        return this;
      }

      Builder setLrsServerName(String lrsServerName) {
        this.lrsServerName = lrsServerName;
        return this;
      }

      ClusterUpdate build() {
        Preconditions.checkState(clusterName != null, "clusterName is not set");
        Preconditions.checkState(lbPolicy != null, "lbPolicy is not set");
        Preconditions.checkState(
            (enableLrs && lrsServerName != null) || (!enableLrs && lrsServerName == null),
            "lrsServerName is not set while LRS is enabled "
                + "OR lrsServerName is set while LRS is not enabled");
        return
            new ClusterUpdate(clusterName, edsServiceName == null ? clusterName : edsServiceName,
                lbPolicy, enableLrs, lrsServerName);
      }
    }
  }

  /**
   * Data class containing the results of performing a resource discovery RPC via EDS protocol.
   * The results include endpoint addresses running the requested service, as well as
   * configurations for traffic control such as drop overloads, inter-cluster load balancing
   * policy and etc.
   */
  static final class EndpointUpdate {
    private String clusterName;
    private Map<Locality, LocalityLbEndpoints> localityLbEndpointsMap;
    private List<DropOverload> dropPolicies;

    private EndpointUpdate(
        String clusterName, Map<Locality,
        LocalityLbEndpoints> localityLbEndpoints,
        List<DropOverload> dropPolicies) {
      this.clusterName = clusterName;
      this.localityLbEndpointsMap = localityLbEndpoints;
      this.dropPolicies = dropPolicies;
    }

    static Builder newBuilder() {
      return new Builder();
    }

    String getClusterName() {
      return clusterName;
    }

    /**
     * Returns a map of localities with endpoints load balancing information in each locality.
     */
    Map<Locality, LocalityLbEndpoints> getLocalityLbEndpointsMap() {
      return Collections.unmodifiableMap(localityLbEndpointsMap);
    }

    /**
     * Returns a list of drop policies to be applied to outgoing requests.
     */
    List<DropOverload> getDropPolicies() {
      return Collections.unmodifiableList(dropPolicies);
    }

    static final class Builder {
      private String clusterName;
      private Map<Locality, LocalityLbEndpoints> localityLbEndpointsMap = new HashMap<>();
      private List<DropOverload> dropPolicies = new ArrayList<>();

      Builder setClusterName(String clusterName) {
        this.clusterName = clusterName;
        return this;
      }

      Builder addLocalityLbEndpoints(Locality locality, LocalityLbEndpoints info) {
        localityLbEndpointsMap.put(locality, info);
        return this;
      }

      Builder addDropPolicy(DropOverload policy) {
        dropPolicies.add(policy);
        return this;
      }

      EndpointUpdate build() {
        Preconditions.checkState(clusterName != null, "clusterName is not set");
        return new EndpointUpdate(clusterName, localityLbEndpointsMap, dropPolicies);
      }
    }
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
