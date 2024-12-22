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

import io.grpc.StatusOr;
import io.grpc.xds.XdsClusterResource.CdsUpdate;
import io.grpc.xds.XdsEndpointResource.EdsUpdate;
import io.grpc.xds.XdsListenerResource.LdsUpdate;
import io.grpc.xds.XdsRouteConfigureResource.RdsUpdate;
import java.io.Closeable;
import java.util.HashMap;
import java.util.Map;

/**
 * Represents the xDS configuration tree for a specified Listener.
 */
public class XdsConfig {
  final LdsUpdate listener;
  final RdsUpdate route;
  final Map<String, StatusOr<XdsClusterConfig>> clusters;

  XdsConfig(LdsUpdate listener, RdsUpdate route, Map<String, StatusOr<XdsClusterConfig>> clusters) {
    this.listener = listener;
    this.route = route;
    this.clusters = clusters;
  }

  public static class XdsClusterConfig {
    final String clusterName;
    final CdsUpdate clusterResource;
    final StatusOr<EdsUpdate> endpoint;

    XdsClusterConfig(String clusterName, CdsUpdate clusterResource,
                      StatusOr<EdsUpdate> endpoint) {
      this.clusterName = clusterName;
      this.clusterResource = clusterResource;
      this.endpoint = endpoint;
    }
  }

  static class XdsConfigBuilder {
    private LdsUpdate listener;
    private RdsUpdate route;
    private Map<String, StatusOr<XdsClusterConfig>> clusters = new HashMap<>();

    XdsConfigBuilder setListener(LdsUpdate listener) {
      this.listener = listener;
      return this;
    }

    XdsConfigBuilder setRoute(RdsUpdate route) {
      this.route = route;
      return this;
    }

    XdsConfigBuilder addCluster(String name, StatusOr<XdsClusterConfig> clusterConfig) {
      clusters.put(name, clusterConfig);
      return this;
    }

    XdsConfig build() {
      checkNotNull(listener, "listener");
      checkNotNull(route, "route");
      return new XdsConfig(listener, route, clusters);
    }
  }

  public interface XdsClusterSubscriptionRegistry {
    Closeable subscribeToCluster(String clusterName);
  }
}
