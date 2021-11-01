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

import com.google.auto.value.AutoValue;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import com.google.protobuf.Any;
import io.grpc.Status;
import io.grpc.xds.AbstractXdsClient.ResourceType;
import io.grpc.xds.Bootstrapper.ServerInfo;
import io.grpc.xds.Endpoints.DropOverload;
import io.grpc.xds.Endpoints.LocalityLbEndpoints;
import io.grpc.xds.EnvoyServerProtoData.Listener;
import io.grpc.xds.EnvoyServerProtoData.UpstreamTlsContext;
import io.grpc.xds.LoadStatsManager2.ClusterDropStats;
import io.grpc.xds.LoadStatsManager2.ClusterLocalityStats;
import java.util.ArrayList;
import java.util.Collection;
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

  @AutoValue
  abstract static class LdsUpdate implements ResourceUpdate {
    // Http level api listener configuration.
    @Nullable
    abstract HttpConnectionManager httpConnectionManager();

    // Tcp level listener configuration.
    @Nullable
    abstract Listener listener();

    static LdsUpdate forApiListener(HttpConnectionManager httpConnectionManager) {
      checkNotNull(httpConnectionManager, "httpConnectionManager");
      return new AutoValue_XdsClient_LdsUpdate(httpConnectionManager, null);
    }

    static LdsUpdate forTcpListener(Listener listener) {
      checkNotNull(listener, "listener");
      return new AutoValue_XdsClient_LdsUpdate(null, listener);
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
  @AutoValue
  abstract static class CdsUpdate implements ResourceUpdate {
    abstract String clusterName();

    abstract ClusterType clusterType();

    // Endpoint-level load balancing policy.
    abstract LbPolicy lbPolicy();

    // Only valid if lbPolicy is "ring_hash".
    abstract long minRingSize();

    // Only valid if lbPolicy is "ring_hash".
    abstract long maxRingSize();

    // Alternative resource name to be used in EDS requests.
    /// Only valid for EDS cluster.
    @Nullable
    abstract String edsServiceName();

    // Corresponding DNS name to be used if upstream endpoints of the cluster is resolvable
    // via DNS.
    // Only valid for LOGICAL_DNS cluster.
    @Nullable
    abstract String dnsHostName();

    // Load report server name for reporting loads via LRS.
    // Only valid for EDS or LOGICAL_DNS cluster.
    @Nullable
    abstract String lrsServerName();

    // Max number of concurrent requests can be sent to this cluster.
    // Only valid for EDS or LOGICAL_DNS cluster.
    @Nullable
    abstract Long maxConcurrentRequests();

    // TLS context used to connect to connect to this cluster.
    // Only valid for EDS or LOGICAL_DNS cluster.
    @Nullable
    abstract UpstreamTlsContext upstreamTlsContext();

    // List of underlying clusters making of this aggregate cluster.
    // Only valid for AGGREGATE cluster.
    @Nullable
    abstract ImmutableList<String> prioritizedClusterNames();

    static Builder forAggregate(String clusterName, List<String> prioritizedClusterNames) {
      checkNotNull(prioritizedClusterNames, "prioritizedClusterNames");
      return new AutoValue_XdsClient_CdsUpdate.Builder()
          .clusterName(clusterName)
          .clusterType(ClusterType.AGGREGATE)
          .minRingSize(0)
          .maxRingSize(0)
          .prioritizedClusterNames(ImmutableList.copyOf(prioritizedClusterNames));
    }

    static Builder forEds(String clusterName, @Nullable String edsServiceName,
        @Nullable String lrsServerName, @Nullable Long maxConcurrentRequests,
        @Nullable UpstreamTlsContext upstreamTlsContext) {
      return new AutoValue_XdsClient_CdsUpdate.Builder()
          .clusterName(clusterName)
          .clusterType(ClusterType.EDS)
          .minRingSize(0)
          .maxRingSize(0)
          .edsServiceName(edsServiceName)
          .lrsServerName(lrsServerName)
          .maxConcurrentRequests(maxConcurrentRequests)
          .upstreamTlsContext(upstreamTlsContext);
    }

    static Builder forLogicalDns(String clusterName, String dnsHostName,
        @Nullable String lrsServerName, @Nullable Long maxConcurrentRequests,
        @Nullable UpstreamTlsContext upstreamTlsContext) {
      return new AutoValue_XdsClient_CdsUpdate.Builder()
          .clusterName(clusterName)
          .clusterType(ClusterType.LOGICAL_DNS)
          .minRingSize(0)
          .maxRingSize(0)
          .dnsHostName(dnsHostName)
          .lrsServerName(lrsServerName)
          .maxConcurrentRequests(maxConcurrentRequests)
          .upstreamTlsContext(upstreamTlsContext);
    }

    enum ClusterType {
      EDS, LOGICAL_DNS, AGGREGATE
    }

    enum LbPolicy {
      ROUND_ROBIN, RING_HASH
    }

    // FIXME(chengyuanzhang): delete this after UpstreamTlsContext's toString() is fixed.
    @Override
    public final String toString() {
      return MoreObjects.toStringHelper(this)
          .add("clusterName", clusterName())
          .add("clusterType", clusterType())
          .add("lbPolicy", lbPolicy())
          .add("minRingSize", minRingSize())
          .add("maxRingSize", maxRingSize())
          .add("edsServiceName", edsServiceName())
          .add("dnsHostName", dnsHostName())
          .add("lrsServerName", lrsServerName())
          .add("maxConcurrentRequests", maxConcurrentRequests())
          // Exclude upstreamTlsContext as its string representation is cumbersome.
          .add("prioritizedClusterNames", prioritizedClusterNames())
          .toString();
    }

    @AutoValue.Builder
    abstract static class Builder {
      // Private, use one of the static factory methods instead.
      protected abstract Builder clusterName(String clusterName);

      // Private, use one of the static factory methods instead.
      protected abstract Builder clusterType(ClusterType clusterType);

      // Private, use roundRobinLbPolicy() or ringHashLbPolicy(long, long).
      protected abstract Builder lbPolicy(LbPolicy lbPolicy);

      Builder roundRobinLbPolicy() {
        return this.lbPolicy(LbPolicy.ROUND_ROBIN);
      }

      Builder ringHashLbPolicy(long minRingSize, long maxRingSize) {
        return this.lbPolicy(LbPolicy.RING_HASH).minRingSize(minRingSize).maxRingSize(maxRingSize);
      }

      // Private, use ringHashLbPolicy(long, long).
      protected abstract Builder minRingSize(long minRingSize);

      // Private, use ringHashLbPolicy(long, long).
      protected abstract Builder maxRingSize(long maxRingSize);

      // Private, use CdsUpdate.forEds() instead.
      protected abstract Builder edsServiceName(String edsServiceName);

      // Private, use CdsUpdate.forLogicalDns() instead.
      protected abstract Builder dnsHostName(String dnsHostName);

      // Private, use one of the static factory methods instead.
      protected abstract Builder lrsServerName(String lrsServerName);

      // Private, use one of the static factory methods instead.
      protected abstract Builder maxConcurrentRequests(Long maxConcurrentRequests);

      // Private, use one of the static factory methods instead.
      protected abstract Builder upstreamTlsContext(UpstreamTlsContext upstreamTlsContext);

      // Private, use CdsUpdate.forAggregate() instead.
      protected abstract Builder prioritizedClusterNames(List<String> prioritizedClusterNames);

      abstract CdsUpdate build();
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
   * The metadata of the xDS resource; used by the xDS config dump.
   */
  static final class ResourceMetadata {
    private final String version;
    private final ResourceMetadataStatus status;
    private final long updateTimeNanos;
    @Nullable private final Any rawResource;
    @Nullable private final UpdateFailureState errorState;

    private ResourceMetadata(
        ResourceMetadataStatus status, String version, long updateTimeNanos,
        @Nullable Any rawResource, @Nullable UpdateFailureState errorState) {
      this.status = checkNotNull(status, "status");
      this.version = checkNotNull(version, "version");
      this.updateTimeNanos = updateTimeNanos;
      this.rawResource = rawResource;
      this.errorState = errorState;
    }

    static ResourceMetadata newResourceMetadataUnknown() {
      return new ResourceMetadata(ResourceMetadataStatus.UNKNOWN, "", 0, null, null);
    }

    static ResourceMetadata newResourceMetadataRequested() {
      return new ResourceMetadata(ResourceMetadataStatus.REQUESTED, "", 0, null, null);
    }

    static ResourceMetadata newResourceMetadataDoesNotExist() {
      return new ResourceMetadata(ResourceMetadataStatus.DOES_NOT_EXIST, "", 0, null, null);
    }

    static ResourceMetadata newResourceMetadataAcked(
        Any rawResource, String version, long updateTimeNanos) {
      checkNotNull(rawResource, "rawResource");
      return new ResourceMetadata(
          ResourceMetadataStatus.ACKED, version, updateTimeNanos, rawResource, null);
    }

    static ResourceMetadata newResourceMetadataNacked(
        ResourceMetadata metadata, String failedVersion, long failedUpdateTime,
        String failedDetails) {
      checkNotNull(metadata, "metadata");
      return new ResourceMetadata(ResourceMetadataStatus.NACKED,
          metadata.getVersion(), metadata.getUpdateTimeNanos(), metadata.getRawResource(),
          new UpdateFailureState(failedVersion, failedUpdateTime, failedDetails));
    }

    /** The last successfully updated version of the resource. */
    String getVersion() {
      return version;
    }

    /** The client status of this resource. */
    ResourceMetadataStatus getStatus() {
      return status;
    }

    /** The timestamp when the resource was last successfully updated. */
    long getUpdateTimeNanos() {
      return updateTimeNanos;
    }

    /** The last successfully updated xDS resource as it was returned by the server. */
    @Nullable
    Any getRawResource() {
      return rawResource;
    }

    /** The metadata capturing the error details of the last rejected update of the resource. */
    @Nullable
    UpdateFailureState getErrorState() {
      return errorState;
    }

    /**
     * Resource status from the view of a xDS client, which tells the synchronization
     * status between the xDS client and the xDS server.
     *
     * <p>This is a native representation of xDS ConfigDump ClientResourceStatus, see
     * <a href="https://github.com/envoyproxy/envoy/blob/main/api/envoy/admin/v3/config_dump.proto">
     * config_dump.proto</a>
     */
    enum ResourceMetadataStatus {
      UNKNOWN, REQUESTED, DOES_NOT_EXIST, ACKED, NACKED
    }

    /**
     * Captures error metadata of failed resource updates.
     *
     * <p>This is a native representation of xDS ConfigDump UpdateFailureState, see
     * <a href="https://github.com/envoyproxy/envoy/blob/main/api/envoy/admin/v3/config_dump.proto">
     * config_dump.proto</a>
     */
    static final class UpdateFailureState {
      private final String failedVersion;
      private final long failedUpdateTimeNanos;
      private final String failedDetails;

      private UpdateFailureState(
          String failedVersion, long failedUpdateTimeNanos, String failedDetails) {
        this.failedVersion = checkNotNull(failedVersion, "failedVersion");
        this.failedUpdateTimeNanos = failedUpdateTimeNanos;
        this.failedDetails = checkNotNull(failedDetails, "failedDetails");
      }

      /** The rejected version string of the last failed update attempt. */
      String getFailedVersion() {
        return failedVersion;
      }

      /** Details about the last failed update attempt. */
      long getFailedUpdateTimeNanos() {
        return failedUpdateTimeNanos;
      }

      /** Timestamp of the last failed update attempt. */
      String getFailedDetails() {
        return failedDetails;
      }
    }
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
   * Returns the config used to bootstrap this XdsClient {@link Bootstrapper.BootstrapInfo}.
   */
  Bootstrapper.BootstrapInfo getBootstrapInfo() {
    throw new UnsupportedOperationException();
  }

  /**
   * Returns the {@link TlsContextManager} used in this XdsClient.
   */
  TlsContextManager getTlsContextManager() {
    throw new UnsupportedOperationException();
  }

  /**
   * Returns the latest accepted version of the given resource type.
   */
  // TODO(https://github.com/grpc/grpc-java/issues/8629): remove this
  String getCurrentVersion(ResourceType type) {
    throw new UnsupportedOperationException();
  }

  Map<String, ResourceMetadata> getSubscribedResourcesMetadata(ResourceType type) {
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
   * Adds drop stats for the specified cluster with edsServiceName by using the returned object
   * to record dropped requests. Drop stats recorded with the returned object will be reported
   * to the load reporting server. The returned object is reference counted and the caller should
   * use {@link ClusterDropStats#release} to release its <i>hard</i> reference when it is safe to
   * stop reporting dropped RPCs for the specified cluster in the future.
   */
  // TODO(https://github.com/grpc/grpc-java/issues/8628): add ServerInfo arg
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
  // TODO(https://github.com/grpc/grpc-java/issues/8628): add ServerInfo arg
  ClusterLocalityStats addClusterLocalityStats(
      String clusterName, @Nullable String edsServiceName, Locality locality) {
    throw new UnsupportedOperationException();
  }

  interface XdsResponseHandler {
    /** Called when an LDS response is received. */
    void handleLdsResponse(
        ServerInfo serverInfo, String versionInfo, List<Any> resources, String nonce);

    /** Called when an RDS response is received. */
    void handleRdsResponse(
        ServerInfo serverInfo, String versionInfo, List<Any> resources, String nonce);

    /** Called when an CDS response is received. */
    void handleCdsResponse(
        ServerInfo serverInfo, String versionInfo, List<Any> resources, String nonce);

    /** Called when an EDS response is received. */
    void handleEdsResponse(
        ServerInfo serverInfo, String versionInfo, List<Any> resources, String nonce);

    /** Called when the ADS stream is closed passively. */
    // Must be synchronized.
    void handleStreamClosed(Status error);

    /** Called when the ADS stream has been recreated. */
    // Must be synchronized.
    void handleStreamRestarted(ServerInfo serverInfo);
  }

  interface ResourceStore {
    /**
     * Returns the collection of resources currently subscribing to or {@code null} if not
     * subscribing to any resources for the given type.
     *
     * <p>Note an empty collection indicates subscribing to resources of the given type with
     * wildcard mode.
     */
    // Must be synchronized.
    @Nullable
    Collection<String> getSubscribedResources(ServerInfo serverInfo, ResourceType type);
  }
}
