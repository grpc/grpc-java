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

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.collect.ImmutableMap;
import io.grpc.StatusOr;
import io.grpc.xds.XdsClusterResource.CdsUpdate;
import io.grpc.xds.XdsEndpointResource.EdsUpdate;
import io.grpc.xds.XdsListenerResource.LdsUpdate;
import io.grpc.xds.XdsRouteConfigureResource.RdsUpdate;
import java.io.Closeable;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Represents the xDS configuration tree for a specified Listener.
 */
final class XdsConfig {
  private final LdsUpdate listener;
  private final RdsUpdate route;
  private final VirtualHost virtualHost;
  private final ImmutableMap<String, StatusOr<XdsClusterConfig>> clusters;
  private final int hashCode;

  XdsConfig(LdsUpdate listener, RdsUpdate route, Map<String, StatusOr<XdsClusterConfig>> clusters,
            VirtualHost virtualHost) {
    this(listener, route, virtualHost, ImmutableMap.copyOf(clusters));
  }

  public XdsConfig(LdsUpdate listener, RdsUpdate route, VirtualHost virtualHost,
                   ImmutableMap<String, StatusOr<XdsClusterConfig>> clusters) {
    this.listener = listener;
    this.route = route;
    this.virtualHost = virtualHost;
    this.clusters = clusters;

    hashCode = Objects.hash(listener, route, virtualHost, clusters);
  }

  @Override
  public boolean equals(Object obj) {
    if (!(obj instanceof XdsConfig)) {
      return false;
    }

    XdsConfig o = (XdsConfig) obj;

    return hashCode() == o.hashCode() && Objects.equals(listener, o.listener)
        && Objects.equals(route, o.route) && Objects.equals(virtualHost, o.virtualHost)
        && Objects.equals(clusters, o.clusters);
  }

  @Override
  public int hashCode() {
    return hashCode;
  }

  @Override
  public String toString() {
    StringBuilder builder = new StringBuilder();
    builder.append("XdsConfig{")
        .append("\n  listener=").append(listener)
        .append(",\n  route=").append(route)
        .append(",\n  virtualHost=").append(virtualHost)
        .append(",\n  clusters=").append(clusters)
        .append("\n}");
    return builder.toString();
  }

  public LdsUpdate getListener() {
    return listener;
  }

  public RdsUpdate getRoute() {
    return route;
  }

  public VirtualHost getVirtualHost() {
    return virtualHost;
  }

  public ImmutableMap<String, StatusOr<XdsClusterConfig>> getClusters() {
    return clusters;
  }

  public XdsConfigBuilder toBuilder() {
    XdsConfigBuilder builder = new XdsConfigBuilder()
        .setVirtualHost(getVirtualHost())
        .setRoute(getRoute())
        .setListener(getListener());

    if (clusters != null) {
      for (Map.Entry<String, StatusOr<XdsClusterConfig>> entry : clusters.entrySet()) {
        builder.addCluster(entry.getKey(), entry.getValue());
      }
    }

    return builder;
  }

  static final class XdsClusterConfig {
    private final String clusterName;
    private final CdsUpdate clusterResource;
    private final StatusOr<EdsUpdate> endpoint; //Will be null for non-EDS clusters

    XdsClusterConfig(String clusterName, CdsUpdate clusterResource,
                      StatusOr<EdsUpdate> endpoint) {
      this.clusterName = checkNotNull(clusterName, "clusterName");
      this.clusterResource = checkNotNull(clusterResource, "clusterResource");
      this.endpoint = endpoint;
    }

    @Override
    public int hashCode() {
      int endpointHash = (endpoint != null) ? endpoint.hashCode() : 0;
      return clusterName.hashCode() + clusterResource.hashCode() + endpointHash;
    }

    @Override
    public boolean equals(Object obj) {
      if (!(obj instanceof XdsClusterConfig)) {
        return false;
      }
      XdsClusterConfig o = (XdsClusterConfig) obj;
      return Objects.equals(clusterName, o.clusterName)
          && Objects.equals(clusterResource, o.clusterResource)
          && Objects.equals(endpoint, o.endpoint);
    }

    @Override
    public String toString() {
      StringBuilder builder = new StringBuilder();
      builder.append("XdsClusterConfig{clusterName=").append(clusterName)
          .append(", clusterResource=").append(clusterResource)
          .append(", endpoint=").append(endpoint).append("}");
      return builder.toString();
    }

    public String getClusterName() {
      return clusterName;
    }

    public CdsUpdate getClusterResource() {
      return clusterResource;
    }

    public StatusOr<EdsUpdate> getEndpoint() {
      return endpoint;
    }
  }

  static final class XdsConfigBuilder {
    private LdsUpdate listener;
    private RdsUpdate route;
    private Map<String, StatusOr<XdsClusterConfig>> clusters = new HashMap<>();
    private VirtualHost virtualHost;

    XdsConfigBuilder setListener(LdsUpdate listener) {
      this.listener = checkNotNull(listener, "listener");
      return this;
    }

    XdsConfigBuilder setRoute(RdsUpdate route) {
      this.route = checkNotNull(route, "route");
      return this;
    }

    XdsConfigBuilder addCluster(String name, StatusOr<XdsClusterConfig> clusterConfig) {
      checkNotNull(name, "name");
      checkNotNull(clusterConfig, "clusterConfig");
      clusters.put(name, clusterConfig);
      return this;
    }

    XdsConfigBuilder setVirtualHost(VirtualHost virtualHost) {
      this.virtualHost = checkNotNull(virtualHost, "virtualHost");
      return this;
    }

    XdsConfig build() {
      checkNotNull(listener, "listener");
      return new XdsConfig(listener, route, clusters, virtualHost);
    }
  }

  public interface XdsClusterSubscriptionRegistry {
    Closeable subscribeToCluster(String clusterName);
  }
}
