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

import static com.google.common.base.Preconditions.checkArgument;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
// TODO(sanjaypujare): remove dependency on envoy data types.
import io.envoyproxy.envoy.api.v2.auth.UpstreamTlsContext;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.alts.GoogleDefaultChannelBuilder;
import io.grpc.internal.ObjectPool;
import io.grpc.xds.Bootstrapper.ChannelCreds;
import io.grpc.xds.Bootstrapper.ServerInfo;
import io.grpc.xds.EnvoyProtoData.DropOverload;
import io.grpc.xds.EnvoyProtoData.Locality;
import io.grpc.xds.EnvoyProtoData.LocalityLbEndpoints;
import io.grpc.xds.EnvoyServerProtoData.Listener;
import io.grpc.xds.XdsLogger.XdsLogLevel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
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
    private final Listener listener;

    private ConfigUpdate(String clusterName, @Nullable Listener listener) {
      this.clusterName = clusterName;
      this.listener = listener;
    }

    String getClusterName() {
      return clusterName;
    }

    @Nullable
    public Listener getListener() {
      return listener;
    }

    @Override
    public String toString() {
      return
          MoreObjects
              .toStringHelper(this)
              .add("clusterName", clusterName)
              .toString();
    }

    static Builder newBuilder() {
      return new Builder();
    }

    static final class Builder {
      private String clusterName;
      @Nullable private Listener listener;

      // Use ConfigUpdate.newBuilder().
      private Builder() {
      }

      Builder setClusterName(String clusterName) {
        this.clusterName = clusterName;
        return this;
      }

      Builder setListener(Listener listener) {
        this.listener = listener;
        return this;
      }

      ConfigUpdate build() {
        Preconditions.checkState(clusterName != null, "clusterName is not set");
        return new ConfigUpdate(clusterName, listener);
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
    @Nullable
    private final String edsServiceName;
    private final String lbPolicy;
    @Nullable
    private final String lrsServerName;
    private final UpstreamTlsContext upstreamTlsContext;

    private ClusterUpdate(
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

      Builder setLrsServerName(String lrsServerName) {
        this.lrsServerName = lrsServerName;
        return this;
      }

      Builder setUpstreamTlsContext(UpstreamTlsContext upstreamTlsContext) {
        this.upstreamTlsContext = upstreamTlsContext;
        return this;
      }

      ClusterUpdate build() {
        Preconditions.checkState(clusterName != null, "clusterName is not set");
        Preconditions.checkState(lbPolicy != null, "lbPolicy is not set");

        return
            new ClusterUpdate(
                clusterName, edsServiceName, lbPolicy, lrsServerName, upstreamTlsContext);
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
  void watchConfigData(String targetAuthority, ConfigWatcher watcher) {
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
   * Report client load stats to a remote server for the given cluster:cluster_service.
   *
   * <p>Note: currently we can only report loads for a single cluster:cluster_service,
   * as the design for adding clusters to report loads for while load reporting is
   * happening is undefined.
   */
  void reportClientStats(
      String clusterName, @Nullable String clusterServiceName, LoadStatsStore loadStatsStore) {
    throw new UnsupportedOperationException();
  }

  /**
   * Stops reporting client load stats to the remote server for the given cluster:cluster_service.
   */
  void cancelClientStatsReport(String clusterName, @Nullable String clusterServiceName) {
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

  /**
   * Factory for creating channels to xDS severs.
   */
  abstract static class XdsChannelFactory {
    private static final XdsChannelFactory DEFAULT_INSTANCE = new XdsChannelFactory() {

      /**
       * Creates a channel to the first server in the given list.
       */
      @Override
      ManagedChannel createChannel(List<ServerInfo> servers) {
        checkArgument(!servers.isEmpty(), "No management server provided.");
        XdsLogger logger = XdsLogger.withPrefix("xds-client-channel-factory");
        ServerInfo serverInfo = servers.get(0);
        String serverUri = serverInfo.getServerUri();
        logger.log(XdsLogLevel.INFO, "Creating channel to {0}", serverUri);
        List<ChannelCreds> channelCredsList = serverInfo.getChannelCredentials();
        ManagedChannelBuilder<?> channelBuilder = null;
        // Use the first supported channel credentials configuration.
        // Currently, only "google_default" is supported.
        for (ChannelCreds creds : channelCredsList) {
          if (creds.getType().equals("google_default")) {
            logger.log(XdsLogLevel.INFO, "Using channel credentials: google_default");
            channelBuilder = GoogleDefaultChannelBuilder.forTarget(serverUri);
            break;
          }
        }
        if (channelBuilder == null) {
          logger.log(XdsLogLevel.INFO, "Using default channel credentials");
          channelBuilder = ManagedChannelBuilder.forTarget(serverUri);
        }

        return channelBuilder
            .keepAliveTime(5, TimeUnit.MINUTES)
            .build();
      }
    };

    static XdsChannelFactory getInstance() {
      return DEFAULT_INSTANCE;
    }

    /**
     * Creates a channel to one of the provided management servers.
     */
    abstract ManagedChannel createChannel(List<ServerInfo> servers);
  }
}
