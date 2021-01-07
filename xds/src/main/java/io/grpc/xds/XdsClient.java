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

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.google.common.base.MoreObjects;
import com.google.common.base.MoreObjects.ToStringHelper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.grpc.Status;
import io.grpc.xds.EnvoyProtoData.DropOverload;
import io.grpc.xds.EnvoyProtoData.Locality;
import io.grpc.xds.EnvoyProtoData.LocalityLbEndpoints;
import io.grpc.xds.EnvoyProtoData.VirtualHost;
import io.grpc.xds.EnvoyServerProtoData.Listener;
import io.grpc.xds.EnvoyServerProtoData.UpstreamTlsContext;
import io.grpc.xds.LoadStatsManager.LoadStatsStore;
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

  static final class LdsUpdate implements ResourceUpdate {
    // Total number of nanoseconds to keep alive an HTTP request/response stream.
    private final long httpMaxStreamDurationNano;
    // The name of the route configuration to be used for RDS resource discovery.
    @Nullable
    private final String rdsName;
    // The list virtual hosts that make up the route table.
    @Nullable
    private final List<VirtualHost> virtualHosts;

    private LdsUpdate(long httpMaxStreamDurationNano, @Nullable String rdsName,
        @Nullable List<VirtualHost> virtualHosts) {
      this.httpMaxStreamDurationNano = httpMaxStreamDurationNano;
      this.rdsName = rdsName;
      this.virtualHosts = virtualHosts == null
          ? null : Collections.unmodifiableList(new ArrayList<>(virtualHosts));
    }

    long getHttpMaxStreamDurationNano() {
      return httpMaxStreamDurationNano;
    }

    @Nullable
    String getRdsName() {
      return rdsName;
    }

    @Nullable
    List<VirtualHost> getVirtualHosts() {
      return virtualHosts;
    }

    @Override
    public int hashCode() {
      return Objects.hash(httpMaxStreamDurationNano, rdsName, virtualHosts);
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      LdsUpdate that = (LdsUpdate) o;
      return Objects.equals(httpMaxStreamDurationNano, that.httpMaxStreamDurationNano)
          && Objects.equals(rdsName, that.rdsName)
          && Objects.equals(virtualHosts, that.virtualHosts);
    }

    @Override
    public String toString() {
      ToStringHelper toStringHelper = MoreObjects.toStringHelper(this);
      toStringHelper.add("httpMaxStreamDurationNano", httpMaxStreamDurationNano);
      if (rdsName != null) {
        toStringHelper.add("rdsName", rdsName);
      } else {
        toStringHelper.add("virtualHosts", virtualHosts);
      }
      return toStringHelper.toString();
    }

    static Builder newBuilder() {
      return new Builder();
    }

    static class Builder {
      private long httpMaxStreamDurationNano;
      @Nullable
      private String rdsName;
      @Nullable
      private List<VirtualHost> virtualHosts;

      private Builder() {
      }

      Builder setHttpMaxStreamDurationNano(long httpMaxStreamDurationNano) {
        this.httpMaxStreamDurationNano = httpMaxStreamDurationNano;
        return this;
      }

      Builder setRdsName(String rdsName) {
        this.rdsName = rdsName;
        return this;
      }

      Builder addVirtualHost(VirtualHost virtualHost) {
        if (virtualHosts == null) {
          virtualHosts = new ArrayList<>();
        }
        virtualHosts.add(virtualHost);
        return this;
      }

      LdsUpdate build() {
        checkState((rdsName == null) != (virtualHosts == null), "one of rdsName and virtualHosts");
        return new LdsUpdate(httpMaxStreamDurationNano, rdsName, virtualHosts);
      }
    }
  }

  static final class RdsUpdate implements ResourceUpdate {
    // The list virtual hosts that make up the route table.
    private final List<VirtualHost> virtualHosts;

    private RdsUpdate(List<VirtualHost> virtualHosts) {
      this.virtualHosts = Collections.unmodifiableList(
          new ArrayList<>(checkNotNull(virtualHosts, "virtualHosts")));
    }

    static RdsUpdate fromVirtualHosts(List<VirtualHost> virtualHosts) {
      return new RdsUpdate(virtualHosts);
    }

    List<VirtualHost> getVirtualHosts() {
      return virtualHosts;
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("virtualHosts", virtualHosts)
          .toString();
    }

    @Override
    public int hashCode() {
      return Objects.hash(virtualHosts);
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      RdsUpdate that = (RdsUpdate) o;
      return Objects.equals(virtualHosts, that.virtualHosts);
    }
  }

  static final class CdsUpdate implements ResourceUpdate {
    final String clusterName;
    final ClusterType clusterType;
    final ClusterConfig clusterConfig;

    CdsUpdate(String clusterName, ClusterType clusterType, ClusterConfig clusterConfig) {
      this.clusterName = checkNotNull(clusterName, "clusterName");
      this.clusterType = checkNotNull(clusterType, "clusterType");
      this.clusterConfig = checkNotNull(clusterConfig, "clusterConfig");
    }

    @Override
    public int hashCode() {
      return Objects.hash(clusterName, clusterType, clusterConfig);
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      CdsUpdate that = (CdsUpdate) o;
      return Objects.equals(clusterName, that.clusterName)
          && Objects.equals(clusterType, that.clusterType)
          && Objects.equals(clusterConfig, that.clusterConfig);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("clusterName", clusterName)
          .add("clusterType", clusterType)
          .add("clusterConfig", clusterConfig)
          .toString();
    }

    enum ClusterType {
      EDS, LOGICAL_DNS, AGGREGATE
    }

    abstract static class ClusterConfig {
      // Endpoint level load balancing policy.
      final String lbPolicy;

      private ClusterConfig(String lbPolicy) {
        this.lbPolicy = checkNotNull(lbPolicy, "lbPolicy");
      }
    }

    static final class AggregateClusterConfig extends ClusterConfig {
      // List of underlying clusters making of this aggregate cluster.
      final List<String> prioritizedClusterNames;

      AggregateClusterConfig(String lbPolicy, List<String> prioritizedClusterNames) {
        super(lbPolicy);
        this.prioritizedClusterNames =
            Collections.unmodifiableList(new ArrayList<>(prioritizedClusterNames));
      }

      @Override
      public int hashCode() {
        return Objects.hash(lbPolicy, prioritizedClusterNames);
      }

      @Override
      public boolean equals(Object o) {
        if (this == o) {
          return true;
        }
        if (o == null || getClass() != o.getClass()) {
          return false;
        }
        AggregateClusterConfig that = (AggregateClusterConfig) o;
        return Objects.equals(lbPolicy, that.lbPolicy)
            && Objects.equals(prioritizedClusterNames, that.prioritizedClusterNames);
      }

      @Override
      public String toString() {
        return MoreObjects.toStringHelper(this)
            .add("lbPolicy", lbPolicy)
            .add("prioritizedClusterNames", prioritizedClusterNames)
            .toString();
      }
    }

    private abstract static class NonAggregateClusterConfig extends ClusterConfig {
      // Load report server name for reporting loads via LRS.
      @Nullable
      final String lrsServerName;
      // Max number of concurrent requests can be sent to this cluster.
      // FIXME(chengyuanzhang): protobuf uint32 is int in Java, so this field can be Integer.
      @Nullable
      final Long maxConcurrentRequests;
      // TLS context used to connect to connect to this cluster.
      @Nullable
      final UpstreamTlsContext upstreamTlsContext;

      private NonAggregateClusterConfig(String lbPolicy, @Nullable String lrsServerName,
          @Nullable Long maxConcurrentRequests, @Nullable UpstreamTlsContext upstreamTlsContext) {
        super(lbPolicy);
        this.lrsServerName = lrsServerName;
        this.maxConcurrentRequests = maxConcurrentRequests;
        this.upstreamTlsContext = upstreamTlsContext;
      }
    }

    static final class EdsClusterConfig extends NonAggregateClusterConfig {
      // Alternative resource name to be used in EDS requests.
      @Nullable
      final String edsServiceName;

      EdsClusterConfig(String lbPolicy, @Nullable String edsServiceName,
          @Nullable String lrsServerName, @Nullable Long maxConcurrentRequests,
          @Nullable UpstreamTlsContext upstreamTlsContext) {
        super(lbPolicy, lrsServerName, maxConcurrentRequests, upstreamTlsContext);
        this.edsServiceName = edsServiceName;
      }

      @Override
      public int hashCode() {
        return Objects.hash(lbPolicy, edsServiceName, lrsServerName, maxConcurrentRequests,
            upstreamTlsContext);
      }

      @Override
      public boolean equals(Object o) {
        if (this == o) {
          return true;
        }
        if (o == null || getClass() != o.getClass()) {
          return false;
        }
        EdsClusterConfig that = (EdsClusterConfig) o;
        return Objects.equals(lbPolicy, that.lbPolicy)
            && Objects.equals(edsServiceName, that.edsServiceName)
            && Objects.equals(lrsServerName, that.lrsServerName)
            && Objects.equals(maxConcurrentRequests, that.maxConcurrentRequests)
            && Objects.equals(upstreamTlsContext, that.upstreamTlsContext);
      }

      @Override
      public String toString() {
        return MoreObjects.toStringHelper(this)
            .add("lbPolicy", lbPolicy)
            .add("edsServiceName", edsServiceName)
            .add("lrsServerName", lrsServerName)
            .add("maxConcurrentRequests", maxConcurrentRequests)
            // Exclude upstreamTlsContext as its string representation is cumbersome.
            .toString();
      }
    }

    static final class LogicalDnsClusterConfig extends NonAggregateClusterConfig {
      LogicalDnsClusterConfig(String lbPolicy, @Nullable String lrsServerName,
          @Nullable Long maxConcurrentRequests, @Nullable UpstreamTlsContext upstreamTlsContext) {
        super(lbPolicy, lrsServerName, maxConcurrentRequests, upstreamTlsContext);
      }

      @Override
      public int hashCode() {
        return Objects.hash(lbPolicy, lrsServerName, maxConcurrentRequests, upstreamTlsContext);
      }

      @Override
      public boolean equals(Object o) {
        if (this == o) {
          return true;
        }
        if (o == null || getClass() != o.getClass()) {
          return false;
        }
        LogicalDnsClusterConfig that = (LogicalDnsClusterConfig) o;
        return Objects.equals(lbPolicy, that.lbPolicy)
            && Objects.equals(lrsServerName, that.lrsServerName)
            && Objects.equals(maxConcurrentRequests, that.maxConcurrentRequests)
            && Objects.equals(upstreamTlsContext, that.upstreamTlsContext);
      }

      @Override
      public String toString() {
        return MoreObjects.toStringHelper(this)
            .add("lbPolicy", lbPolicy)
            .add("lrsServerName", lrsServerName)
            .add("maxConcurrentRequests", maxConcurrentRequests)
            // Exclude upstreamTlsContext as its string representation is cumbersome.
            .toString();
      }
    }
  }

  static final class EdsUpdate implements ResourceUpdate {
    private final String clusterName;
    private final Map<Locality, LocalityLbEndpoints> localityLbEndpointsMap;
    private final List<DropOverload> dropPolicies;

    private EdsUpdate(
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
      EdsUpdate that = (EdsUpdate) o;
      return Objects.equals(clusterName, that.clusterName)
          && Objects.equals(localityLbEndpointsMap, that.localityLbEndpointsMap)
          && Objects.equals(dropPolicies, that.dropPolicies);
    }

    @Override
    public int hashCode() {
      return Objects.hash(clusterName, localityLbEndpointsMap, dropPolicies);
    }

    @Override
    public String toString() {
      return
          MoreObjects
              .toStringHelper(this)
              .add("clusterName", clusterName)
              .add("localityLbEndpointsMap", localityLbEndpointsMap)
              .add("dropPolicies", dropPolicies)
              .toString();
    }

    static final class Builder {
      private String clusterName;
      private Map<Locality, LocalityLbEndpoints> localityLbEndpointsMap = new LinkedHashMap<>();
      private List<DropOverload> dropPolicies = new ArrayList<>();

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

      EdsUpdate build() {
        checkState(clusterName != null, "clusterName is not set");
        return
            new EdsUpdate(
                clusterName,
                ImmutableMap.copyOf(localityLbEndpointsMap),
                ImmutableList.copyOf(dropPolicies));
      }
    }
  }

  /**
   * Updates via resource discovery RPCs using LDS. Includes {@link Listener} object containing
   * config for security, RBAC or other server side features such as rate limit.
   */
  static final class ListenerUpdate implements ResourceUpdate {
    // TODO(sanjaypujare): flatten structure by moving Listener class members here.
    private final Listener listener;

    private ListenerUpdate(Listener listener) {
      this.listener = listener;
    }

    public Listener getListener() {
      return listener;
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("listener", listener)
          .toString();
    }

    static Builder newBuilder() {
      return new Builder();
    }

    static final class Builder {
      private Listener listener;

      // Use ListenerUpdate.newBuilder().
      private Builder() {
      }

      Builder setListener(Listener listener) {
        this.listener = listener;
        return this;
      }

      ListenerUpdate build() {
        checkState(listener != null, "listener is not set");
        return new ListenerUpdate(listener);
      }
    }
  }

  interface ResourceUpdate {
  }

  /**
   * Watcher interface for a single requested xDS resource.
   */
  interface ResourceWatcher {

    /**
     * Called when the resource discovery RPC encounters some transient error.
     */
    void onError(Status error);

    /**
     * Called when the requested resource is not available.
     *
     * @param resourceName name of the resource requested in discovery request.
     */
    void onResourceDoesNotExist(String resourceName);
  }

  interface LdsResourceWatcher extends ResourceWatcher {
    void onChanged(LdsUpdate update);
  }

  interface RdsResourceWatcher extends ResourceWatcher {
    void onChanged(RdsUpdate update);
  }

  interface CdsResourceWatcher extends ResourceWatcher {
    void onChanged(CdsUpdate update);
  }

  interface EdsResourceWatcher extends ResourceWatcher {
    void onChanged(EdsUpdate update);
  }

  /**
   * Listener watcher interface. To be used by {@link XdsServerBuilder}.
   */
  interface ListenerWatcher extends ResourceWatcher {

    /**
     * Called when receiving an update on Listener configuration.
     */
    void onListenerChanged(ListenerUpdate update);
  }

  /**
   * Shutdown this {@link XdsClient} and release resources.
   */
  void shutdown() {
  }

  /**
   * Returns {@code true} if {@link #shutdown()} has been called.
   */
  boolean isShutDown() {
    throw new UnsupportedOperationException();
  }

  /**
   * Registers a data watcher for the given LDS resource.
   */
  void watchLdsResource(String resourceName, LdsResourceWatcher watcher) {
  }

  /**
   * Unregisters the given LDS resource watcher.
   */
  void cancelLdsResourceWatch(String resourceName, LdsResourceWatcher watcher) {
  }

  /**
   * Registers a data watcher for the given RDS resource.
   */
  void watchRdsResource(String resourceName, RdsResourceWatcher watcher) {
  }

  /**
   * Unregisters the given RDS resource watcher.
   */
  void cancelRdsResourceWatch(String resourceName, RdsResourceWatcher watcher) {
  }

  /**
   * Registers a data watcher for the given CDS resource.
   */
  void watchCdsResource(String resourceName, CdsResourceWatcher watcher) {
  }

  /**
   * Unregisters the given CDS resource watcher.
   */
  void cancelCdsResourceWatch(String resourceName, CdsResourceWatcher watcher) {
  }

  /**
   * Registers a data watcher for the given EDS resource.
   */
  void watchEdsResource(String resourceName, EdsResourceWatcher watcher) {
  }

  /**
   * Unregisters the given EDS resource watcher.
   */
  void cancelEdsResourceWatch(String resourceName, EdsResourceWatcher watcher) {
  }

  /**
   * Registers a watcher for a Listener with the given port.
   */
  void watchListenerData(int port, ListenerWatcher watcher) {
  }

  /**
   * Starts recording client load stats for the given cluster:cluster_service. Caller should use
   * the returned {@link LoadStatsStore} to record and aggregate stats for load sent to the given
   * cluster:cluster_service. The first call of this method starts load reporting via LRS.
   */
  LoadStatsStore addClientStats(String clusterName, @Nullable String clusterServiceName) {
    throw new UnsupportedOperationException();
  }

  /**
   * Stops recording client load stats for the given cluster:cluster_service. The load reporting
   * server will no longer receive stats for the given cluster:cluster_service after this call.
   * Load reporting may be terminated if there is no stats to be reported.
   */
  void removeClientStats(String clusterName, @Nullable String clusterServiceName) {
    throw new UnsupportedOperationException();
  }
}
