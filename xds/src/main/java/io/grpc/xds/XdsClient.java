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
import io.grpc.Status;
import io.grpc.xds.Endpoints.DropOverload;
import io.grpc.xds.Endpoints.LocalityLbEndpoints;
import io.grpc.xds.EnvoyServerProtoData.Listener;
import io.grpc.xds.EnvoyServerProtoData.UpstreamTlsContext;
import io.grpc.xds.LoadStatsManager2.ClusterDropStats;
import io.grpc.xds.LoadStatsManager2.ClusterLocalityStats;
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
    final long httpMaxStreamDurationNano;
    // The name of the route configuration to be used for RDS resource discovery.
    @Nullable
    final String rdsName;
    // The list virtual hosts that make up the route table.
    @Nullable
    final List<VirtualHost> virtualHosts;
    // Listener contains the HttpFault filter.
    final boolean hasFaultInjection;
    @Nullable // Can be null even if hasFaultInjection is true.
    final HttpFault httpFault;

    LdsUpdate(
        long httpMaxStreamDurationNano, String rdsName, boolean hasFaultInjection,
        @Nullable HttpFault httpFault) {
      this(httpMaxStreamDurationNano, rdsName, null, hasFaultInjection, httpFault);
    }

    LdsUpdate(
        long httpMaxStreamDurationNano, List<VirtualHost> virtualHosts,
        boolean hasFaultInjection, @Nullable HttpFault httpFault) {
      this(httpMaxStreamDurationNano, null, virtualHosts, hasFaultInjection, httpFault);
    }

    private LdsUpdate(
        long httpMaxStreamDurationNano, @Nullable String rdsName,
        @Nullable List<VirtualHost> virtualHosts, boolean hasFaultInjection,
        @Nullable HttpFault httpFault) {
      this.httpMaxStreamDurationNano = httpMaxStreamDurationNano;
      this.rdsName = rdsName;
      this.virtualHosts = virtualHosts == null
          ? null : Collections.unmodifiableList(new ArrayList<>(virtualHosts));
      this.hasFaultInjection = hasFaultInjection;
      this.httpFault = httpFault;
    }

    @Override
    public int hashCode() {
      return Objects.hash(
          httpMaxStreamDurationNano, rdsName, virtualHosts, hasFaultInjection, httpFault);
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
      return httpMaxStreamDurationNano == that.httpMaxStreamDurationNano
          && Objects.equals(rdsName, that.rdsName)
          && Objects.equals(virtualHosts, that.virtualHosts)
          && hasFaultInjection == that.hasFaultInjection
          && Objects.equals(httpFault, that.httpFault);
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
      if (hasFaultInjection) {
        toStringHelper.add("faultInjectionEnabled", true)
            .add("httpFault", httpFault);
      }
      return toStringHelper.toString();
    }
  }

  static final class RdsUpdate implements ResourceUpdate {
    // The list virtual hosts that make up the route table.
    final List<VirtualHost> virtualHosts;

    RdsUpdate(List<VirtualHost> virtualHosts) {
      this.virtualHosts = Collections.unmodifiableList(
          new ArrayList<>(checkNotNull(virtualHosts, "virtualHosts")));
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

  /** xDS resource update for cluster-level configuration. */
  static final class CdsUpdate implements ResourceUpdate {
    final String clusterName;
    final ClusterType clusterType;
    // Endpoint-level load balancing policy.
    final String lbPolicy;
    // Only valid if lbPolicy is "ring_hash".
    final long minRingSize;
    // Only valid if lbPolicy is "ring_hash".
    final long maxRingSize;
    // Only valid if lbPolicy is "ring_hash".
    @Nullable
    final HashFunction hashFunction;
    // Alternative resource name to be used in EDS requests.
    /// Only valid for EDS cluster.
    @Nullable
    final String edsServiceName;
    // Load report server name for reporting loads via LRS.
    // Only valid for EDS or LOGICAL_DNS cluster.
    @Nullable
    final String lrsServerName;
    // Max number of concurrent requests can be sent to this cluster.
    // Only valid for EDS or LOGICAL_DNS cluster.
    @Nullable
    final Long maxConcurrentRequests;
    // TLS context used to connect to connect to this cluster.
    // Only valid for EDS or LOGICAL_DNS cluster.
    @Nullable
    final UpstreamTlsContext upstreamTlsContext;
    // List of underlying clusters making of this aggregate cluster.
    // Only valid for AGGREGATE cluster.
    @Nullable
    final List<String> prioritizedClusterNames;

    static CdsUpdate forAggregate(String clusterName, String lbPolicy, long minRingSize,
        long maxRingSize, @Nullable HashFunction hashFunction,
        List<String> prioritizedClusterNames) {
      return new CdsUpdate(clusterName, ClusterType.AGGREGATE, lbPolicy, minRingSize, maxRingSize,
          hashFunction, null, null, null, null,
          checkNotNull(prioritizedClusterNames, "prioritizedClusterNames"));
    }

    static CdsUpdate forEds(String clusterName, String lbPolicy, long minRingSize,
        long maxRingSize, @Nullable HashFunction hashFunction, @Nullable String edsServiceName,
        @Nullable String lrsServerName, @Nullable Long maxConcurrentRequests,
        @Nullable UpstreamTlsContext upstreamTlsContext) {
      return new CdsUpdate(clusterName, ClusterType.EDS, lbPolicy, minRingSize, maxRingSize,
          hashFunction, edsServiceName, lrsServerName, maxConcurrentRequests, upstreamTlsContext,
          null);
    }

    static CdsUpdate forLogicalDns(String clusterName, String lbPolicy, long minRingSize,
        long maxRingSize, @Nullable HashFunction hashFunction, @Nullable String lrsServerName,
        @Nullable Long maxConcurrentRequests, @Nullable UpstreamTlsContext upstreamTlsContext) {
      return new CdsUpdate(clusterName, ClusterType.LOGICAL_DNS, lbPolicy, minRingSize,
          maxRingSize, hashFunction, null, lrsServerName, maxConcurrentRequests,
          upstreamTlsContext, null);
    }

    CdsUpdate(String clusterName, ClusterType clusterType, @Nullable String lbPolicy,
        long minRingSize, long maxRingSize, @Nullable HashFunction hashFunction,
        @Nullable String edsServiceName, @Nullable String lrsServerName,
        @Nullable Long maxConcurrentRequests, @Nullable UpstreamTlsContext upstreamTlsContext,
        @Nullable List<String> prioritizedClusterNames) {
      this.clusterName = checkNotNull(clusterName, "clusterName");
      this.clusterType = checkNotNull(clusterType, "clusterType");
      this.lbPolicy = checkNotNull(lbPolicy, "lbPolicy");
      this.minRingSize = minRingSize;
      this.maxRingSize = maxRingSize;
      this.hashFunction = hashFunction;
      this.edsServiceName = edsServiceName;
      this.lrsServerName = lrsServerName;
      this.maxConcurrentRequests = maxConcurrentRequests;
      this.upstreamTlsContext = upstreamTlsContext;
      this.prioritizedClusterNames = prioritizedClusterNames != null
          ? Collections.unmodifiableList(new ArrayList<>(prioritizedClusterNames)) : null;
    }

    @Override
    public int hashCode() {
      return Objects.hash(clusterName, clusterType, lbPolicy, minRingSize, maxRingSize,
          hashFunction, edsServiceName, lrsServerName, maxConcurrentRequests, upstreamTlsContext,
          prioritizedClusterNames);
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
          && Objects.equals(lbPolicy, that.lbPolicy)
          && minRingSize == that.minRingSize
          && maxRingSize == that.maxRingSize
          && Objects.equals(hashFunction, that.hashFunction)
          && Objects.equals(edsServiceName, that.edsServiceName)
          && Objects.equals(lrsServerName, that.lrsServerName)
          && Objects.equals(maxConcurrentRequests, that.maxConcurrentRequests)
          && Objects.equals(upstreamTlsContext, that.upstreamTlsContext)
          && Objects.equals(prioritizedClusterNames, that.prioritizedClusterNames);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("clusterName", clusterName)
          .add("clusterType", clusterType)
          .add("lbPolicy", lbPolicy)
          .add("minRingSize", minRingSize)
          .add("maxRingSize", maxRingSize)
          .add("hashFunction", hashFunction)
          .add("edsServiceName", edsServiceName)
          .add("lrsServerName", lrsServerName)
          .add("maxConcurrentRequests", maxConcurrentRequests)
          // Exclude upstreamTlsContext as its string representation is cumbersome.
          .add("prioritizedClusterNames", prioritizedClusterNames)
          .toString();
    }

    enum ClusterType {
      EDS, LOGICAL_DNS, AGGREGATE
    }

    enum HashFunction {
      XX_HASH, MURMUR_HASH_2
    }
  }

  static final class EdsUpdate implements ResourceUpdate {
    final String clusterName;
    final Map<Locality, LocalityLbEndpoints> localityLbEndpointsMap;
    final List<DropOverload> dropPolicies;

    EdsUpdate(String clusterName, Map<Locality, LocalityLbEndpoints> localityLbEndpoints,
        List<DropOverload> dropPolicies) {
      this.clusterName = checkNotNull(clusterName, "clusterName");
      this.localityLbEndpointsMap = Collections.unmodifiableMap(
          new LinkedHashMap<>(checkNotNull(localityLbEndpoints, "localityLbEndpoints")));
      this.dropPolicies = Collections.unmodifiableList(
          new ArrayList<>(checkNotNull(dropPolicies, "dropPolicies")));
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
    throw new UnsupportedOperationException();
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
    throw new UnsupportedOperationException();
  }

  /**
   * Unregisters the given LDS resource watcher.
   */
  void cancelLdsResourceWatch(String resourceName, LdsResourceWatcher watcher) {
    throw new UnsupportedOperationException();
  }

  /**
   * Registers a data watcher for the given RDS resource.
   */
  void watchRdsResource(String resourceName, RdsResourceWatcher watcher) {
    throw new UnsupportedOperationException();
  }

  /**
   * Unregisters the given RDS resource watcher.
   */
  void cancelRdsResourceWatch(String resourceName, RdsResourceWatcher watcher) {
    throw new UnsupportedOperationException();
  }

  /**
   * Registers a data watcher for the given CDS resource.
   */
  void watchCdsResource(String resourceName, CdsResourceWatcher watcher) {
    throw new UnsupportedOperationException();
  }

  /**
   * Unregisters the given CDS resource watcher.
   */
  void cancelCdsResourceWatch(String resourceName, CdsResourceWatcher watcher) {
    throw new UnsupportedOperationException();
  }

  /**
   * Registers a data watcher for the given EDS resource.
   */
  void watchEdsResource(String resourceName, EdsResourceWatcher watcher) {
    throw new UnsupportedOperationException();
  }

  /**
   * Unregisters the given EDS resource watcher.
   */
  void cancelEdsResourceWatch(String resourceName, EdsResourceWatcher watcher) {
    throw new UnsupportedOperationException();
  }

  /**
   * Registers a watcher for a Listener with the given port.
   */
  void watchListenerData(int port, ListenerWatcher watcher) {
    throw new UnsupportedOperationException();
  }

  /**
   * Adds drop stats for the specified cluster with edsServiceName by using the returned object
   * to record dropped requests. Drop stats recorded with the returned object will be reported
   * to the load reporting server. The returned object is reference counted and the caller should
   * use {@link ClusterDropStats#release} to release its <i>hard</i> reference when it is safe to
   * stop reporting dropped RPCs for the specified cluster in the future.
   */
  ClusterDropStats addClusterDropStats(String clusterName, @Nullable String edsServiceName) {
    throw new UnsupportedOperationException();
  }

  /**
   * Adds load stats for the specified locality (in the specified cluster with edsServiceName) by
   * using the returned object to record RPCs. Load stats recorded with the returned object will
   * be reported to the load reporting server. The returned object is reference counted and the
   * caller should use {@link ClusterLocalityStats#release} to release its <i>hard</i>
   * reference when it is safe to stop reporting RPC loads for the specified locality in the
   * future.
   */
  ClusterLocalityStats addClusterLocalityStats(
      String clusterName, @Nullable String edsServiceName, Locality locality) {
    throw new UnsupportedOperationException();
  }
}
