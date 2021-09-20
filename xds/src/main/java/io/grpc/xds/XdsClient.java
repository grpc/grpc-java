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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.base.MoreObjects.ToStringHelper;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.internal.ObjectPool;
import io.grpc.xds.EnvoyProtoData.DropOverload;
import io.grpc.xds.EnvoyProtoData.Locality;
import io.grpc.xds.EnvoyProtoData.LocalityLbEndpoints;
import io.grpc.xds.EnvoyProtoData.Route;
import io.grpc.xds.EnvoyProtoData.VirtualHost;
import io.grpc.xds.EnvoyServerProtoData.Listener;
import io.grpc.xds.EnvoyServerProtoData.UpstreamTlsContext;
import io.grpc.xds.LoadStatsManager.LoadStatsStore;
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

  /**
   * Data class containing the results of performing a series of resource discovery RPCs via
   * LDS/RDS/VHDS protocols. The results may include configurations for path/host rewriting,
   * traffic mirroring, retry or hedging, default timeouts and load balancing policy that will
   * be used to generate a service config.
   */
  // TODO(chengyuanzhang): delete me.
  static final class ConfigUpdate {
    private final List<Route> routes;

    private ConfigUpdate(List<Route> routes) {
      this.routes = routes;
    }

    List<Route> getRoutes() {
      return routes;
    }

    @Override
    public String toString() {
      return
          MoreObjects
              .toStringHelper(this)
              .add("routes", routes)
              .toString();
    }

    static Builder newBuilder() {
      return new Builder();
    }

    static final class Builder {
      private final List<Route> routes = new ArrayList<>();

      // Use ConfigUpdate.newBuilder().
      private Builder() {
      }


      Builder addRoutes(Collection<Route> route) {
        routes.addAll(route);
        return this;
      }

      ConfigUpdate build() {
        checkState(!routes.isEmpty(), "routes is empty");
        return new ConfigUpdate(Collections.unmodifiableList(routes));
      }
    }
  }

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
    private final String clusterName;
    @Nullable
    private final String edsServiceName;
    private final String lbPolicy;
    @Nullable
    private final String lrsServerName;
    private final UpstreamTlsContext upstreamTlsContext;

    private CdsUpdate(
        String clusterName,
        @Nullable String edsServiceName,
        String lbPolicy,
        @Nullable String lrsServerName,
        @Nullable UpstreamTlsContext upstreamTlsContext) {
      this.clusterName = clusterName;
      this.edsServiceName = edsServiceName;
      this.lbPolicy = lbPolicy;
      this.lrsServerName = lrsServerName;
      this.upstreamTlsContext = upstreamTlsContext;
    }

    String getClusterName() {
      return clusterName;
    }

    /**
     * Returns the resource name for EDS requests.
     */
    @Nullable
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
     * Returns the server name to send client load reports to if LRS is enabled. {@code null} if
     * load reporting is disabled for this cluster.
     */
    @Nullable
    String getLrsServerName() {
      return lrsServerName;
    }

    /** Returns the {@link UpstreamTlsContext} for this cluster if present, else null. */
    @Nullable
    UpstreamTlsContext getUpstreamTlsContext() {
      return upstreamTlsContext;
    }

    @Override
    public String toString() {
      return
          MoreObjects
              .toStringHelper(this)
              .add("clusterName", clusterName)
              .add("edsServiceName", edsServiceName)
              .add("lbPolicy", lbPolicy)
              .add("lrsServerName", lrsServerName)
              .add("upstreamTlsContext", upstreamTlsContext)
              .toString();
    }

    @Override
    public int hashCode() {
      return Objects.hash(
          clusterName, edsServiceName, lbPolicy, lrsServerName, upstreamTlsContext);
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
          && Objects.equals(edsServiceName, that.edsServiceName)
          && Objects.equals(lbPolicy, that.lbPolicy)
          && Objects.equals(lrsServerName, that.lrsServerName)
          && Objects.equals(upstreamTlsContext, that.upstreamTlsContext);
    }

    static Builder newBuilder() {
      return new Builder();
    }

    static final class Builder {
      private String clusterName;
      @Nullable
      private String edsServiceName;
      private String lbPolicy;
      @Nullable
      private String lrsServerName;
      @Nullable
      private UpstreamTlsContext upstreamTlsContext;

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

      Builder setLrsServerName(String lrsServerName) {
        this.lrsServerName = lrsServerName;
        return this;
      }

      Builder setUpstreamTlsContext(UpstreamTlsContext upstreamTlsContext) {
        this.upstreamTlsContext = upstreamTlsContext;
        return this;
      }

      CdsUpdate build() {
        checkState(clusterName != null, "clusterName is not set");
        checkState(lbPolicy != null, "lbPolicy is not set");

        return
            new CdsUpdate(
                clusterName, edsServiceName, lbPolicy, lrsServerName, upstreamTlsContext);
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
   * Config watcher interface. To be implemented by the xDS resolver.
   */
  // TODO(chengyuanzhang): delete me.
  interface ConfigWatcher extends ResourceWatcher {

    /**
     * Called when receiving an update on virtual host configurations.
     */
    void onConfigChanged(ConfigUpdate update);
  }

  /**
   * Listener watcher interface. To be used by {@link io.grpc.xds.internal.sds.XdsServerBuilder}.
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
  abstract void shutdown();

  /**
   * Registers a watcher to receive {@link ConfigUpdate} for service with the given target
   * authority.
   *
   * <p>Unlike watchers for cluster data and endpoint data, at most one ConfigWatcher can be
   * registered. Once it is registered, it cannot be unregistered.
   *
   * @param targetAuthority authority of the "xds:" URI for the server name that the gRPC client
   *     targets for.
   * @param watcher the {@link ConfigWatcher} to receive {@link ConfigUpdate}.
   */
  // TODO(chengyuanzhang): delete me.
  void watchConfigData(String targetAuthority, ConfigWatcher watcher) {
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
   * Starts client side load reporting via LRS. All clusters report load through one LRS stream,
   * only the first call of this method effectively starts the LRS stream.
   */
  void reportClientStats() {
  }

  /**
   * Stops client side load reporting via LRS. All clusters report load through one LRS stream,
   * only the last call of this method effectively stops the LRS stream.
   */
  void cancelClientStatsReport() {
  }

  /**
   * Starts recording client load stats for the given cluster:cluster_service. Caller should use
   * the returned {@link LoadStatsStore} to record and aggregate stats for load sent to the given
   * cluster:cluster_service. Recorded stats may be reported to a load reporting server if enabled.
   */
  LoadStatsStore addClientStats(String clusterName, @Nullable String clusterServiceName) {
    throw new UnsupportedOperationException();
  }

  /**
   * Stops recording client load stats for the given cluster:cluster_service. The load reporting
   * server will no longer receive stats for the given cluster:cluster_service after this call.
   */
  void removeClientStats(String clusterName, @Nullable String clusterServiceName) {
    throw new UnsupportedOperationException();
  }

  // TODO(chengyuanzhang): eliminate this factory
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
        checkState(
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
      checkState(
          object == xdsClient,
          "Bug: the returned object '%s' does not match current XdsClient '%s'",
          object,
          xdsClient);

      refCount--;
      checkState(refCount >= 0, "Bug: refCount of XdsClient less than 0");
      if (refCount == 0) {
        xdsClient.shutdown();
        xdsClient = null;
      }

      return null;
    }
  }

  static final class XdsChannel {
    private final ManagedChannel managedChannel;
    private final boolean useProtocolV3;

    @VisibleForTesting
    XdsChannel(ManagedChannel managedChannel, boolean useProtocolV3) {
      this.managedChannel = managedChannel;
      this.useProtocolV3 = useProtocolV3;
    }

    ManagedChannel getManagedChannel() {
      return managedChannel;
    }

    boolean isUseProtocolV3() {
      return useProtocolV3;
    }
  }
}
