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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.grpc.Status;
import io.grpc.internal.ObjectPool;
import io.grpc.xds.EnvoyProtoData.DropOverload;
import io.grpc.xds.EnvoyProtoData.Locality;
import io.grpc.xds.EnvoyProtoData.LocalityLbEndpoints;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nullable;

/**
 * An {@link XdsClient} instance encapsulates all of the logic for communicating with the xDS
 * server. It may create multiple RPC streams (or a single ADS stream) for a series of xDS
 * protocols (e.g., LDS, RDS, VHDS, CDS and EDS) over a single channel. Watch-based interfaces
 * are provided for each set of data needed by gRPC.
 */
abstract class XdsClient {

  /**
   * Data class containing the results of performing a series of resource discovery RPCs via
   * LDS/RDS/VHDS protocols. The results may include configurations for path/host rewriting,
   * traffic mirroring, retry or hedging, default timeouts and load balancing policy that will
   * be used to generate a service config.
   */
  static final class ConfigUpdate {
    private final String clusterName;

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

      // Use ConfigUpdate.newBuilder().
      private Builder() {
      }

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
    private final String clusterName;
    private final String edsServiceName;
    private final String lbPolicy;
    private final boolean enableLrs;
    private final String lrsServerName;

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
     * Returns the policy of balancing loads to endpoints. Only "round_robin" is supported
     * as of now.
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

      // Use ClusterUpdate.newBuilder().
      private Builder() {
      }

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
    private final String clusterName;
    private final Map<Locality, LocalityLbEndpoints> localityLbEndpointsMap;
    private final List<DropOverload> dropPolicies;

    private EndpointUpdate(
        String clusterName,
        Map<Locality, LocalityLbEndpoints> localityLbEndpoints,
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

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      EndpointUpdate that = (EndpointUpdate) o;
      return clusterName.equals(that.clusterName)
          && localityLbEndpointsMap.equals(that.localityLbEndpointsMap)
          && dropPolicies.equals(that.dropPolicies);
    }

    @Override
    public int hashCode() {
      return Objects.hash(clusterName, localityLbEndpointsMap, dropPolicies);
    }

    static final class Builder {
      private String clusterName;
      private Map<Locality, LocalityLbEndpoints> localityLbEndpointsMap = new LinkedHashMap<>();
      private List<DropOverload> dropPolicies = new ArrayList<>();

      // Use EndpointUpdate.newBuilder().
      private Builder() {
      }

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
        return
            new EndpointUpdate(
                clusterName,
                ImmutableMap.copyOf(localityLbEndpointsMap),
                ImmutableList.copyOf(dropPolicies));
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
   * Shutdown this {@link XdsClient} and release resources.
   */
  abstract void shutdown();

  /**
   * Registers a watcher to receive {@link ConfigUpdate} for service with the given hostname and
   * port.
   *
   * <p>Unlike watchers for cluster data and endpoint data, at any point of time at most one config
   * watcher is allowed.
   *
   * @param hostName the host name part of the "xds:" URI for the server name that the gRPC client
   *     targets for. Must NOT contain port.
   * @param port the port part of the "xds:" URI for the server name that the gRPC client targets
   *     for. -1 if not specified.
   * @param watcher the {@link ConfigWatcher} to receive {@link ConfigUpdate}.
   */
  void watchConfigData(String hostName, int port, ConfigWatcher watcher) {
  }

  /**
   * Unregisters the existing config watcher. The previously registered config watcher will no
   * longer receive {@link ConfigUpdate}. Noop if no config watcher has been registered.
   */
  void cancelConfigDataWatch() {
  }

  /**
   * Registers a data watcher for the given cluster.
   */
  void watchClusterData(String clusterName, ClusterWatcher watcher) {
  }

  /**
   * Unregisters the given cluster watcher, which was registered to receive updates for the
   * given cluster.
   */
  void cancelClusterDataWatch(String clusterName, ClusterWatcher watcher) {
  }

  /**
   * Registers a data watcher for endpoints in the given cluster.
   */
  void watchEndpointData(String clusterName, EndpointWatcher watcher) {
  }

  /**
   * Unregisters the given endpoints watcher, which was registered to receive updates for
   * endpoints information in the given cluster.
   */
  void cancelEndpointDataWatch(String clusterName, EndpointWatcher watcher) {
  }

  /**
   * Starts reporting client load stats to a remote server for the given cluster.
   *
   * @param loadStatsStore  a in-memory data store containing loads recorded by gRPC client.
   */
  void reportClientStats(String clusterName, String serverUri, LoadStatsStore loadStatsStore) {
  }

  /**
   * Stops reporting client load stats to the remote server for the given cluster.
   */
  void cancelClientStatsReport(String clusterName) {
  }

  abstract static class XdsClientFactory {
    abstract XdsClient createXdsClient();
  }

  /**
   * An {@link ObjectPool} holding reference and ref-count of an {@link XdsClient} instance.
   * Initially the instance is null and the ref-count is zero. {@link #getObject()} will create a
   * new XdsClient instance if the ref-count is zero when calling the method. {@code #getObject()}
   * increments the ref-count and {@link #returnObject(Object)} decrements it. Anytime when the
   * ref-count gets back to zero, the XdsClient instance will be shutdown and de-referenced.
   */
  static final class RefCountedXdsClientObjectPool implements ObjectPool<XdsClient> {

    private final XdsClientFactory xdsClientFactory;

    @VisibleForTesting
    @Nullable
    XdsClient xdsClient;

    private int refCount;

    RefCountedXdsClientObjectPool(XdsClientFactory xdsClientFactory) {
      this.xdsClientFactory = Preconditions.checkNotNull(xdsClientFactory, "xdsClientFactory");
    }

    /**
     * See {@link RefCountedXdsClientObjectPool}.
     */
    @Override
    public synchronized XdsClient getObject() {
      if (xdsClient == null) {
        Preconditions.checkState(
            refCount == 0,
            "Bug: refCount should be zero while xdsClient is null");
        xdsClient = xdsClientFactory.createXdsClient();
      }
      refCount++;
      return xdsClient;
    }

    /**
     * See {@link RefCountedXdsClientObjectPool}.
     */
    @Override
    public synchronized XdsClient returnObject(Object object) {
      Preconditions.checkState(
          object == xdsClient,
          "Bug: the returned object '%s' does not match current XdsClient '%s'",
          object,
          xdsClient);

      refCount--;
      Preconditions.checkState(refCount >= 0, "Bug: refCount of XdsClient less than 0");
      if (refCount == 0) {
        xdsClient.shutdown();
        xdsClient = null;
      }

      return null;
    }
  }
}
