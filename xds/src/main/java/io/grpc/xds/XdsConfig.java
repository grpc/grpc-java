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

import io.grpc.StatusOr;
import java.util.Map;

public class XdsConfig {
  public final XdsListenerResource listener;
  public final XdsRouteConfigureResource route;
  public final Map<String, StatusOr<XdsClusterConfig>> clusters;

  public XdsConfig(XdsListenerResource listener, XdsRouteConfigureResource route,
                   Map<String, StatusOr<XdsClusterConfig>> clusters) {
    this.listener = listener;
    this.route = route;
    this.clusters = clusters;
  }

  public static class XdsClusterConfig {
    public final String clusterName;
    public final XdsClusterResource clusterResource;
    public final XdsEndpointResource endpoint;

    public XdsClusterConfig(String clusterName, XdsClusterResource clusterResource,
                            XdsEndpointResource endpoint) {
      this.clusterName = clusterName;
      this.clusterResource = clusterResource;
      this.endpoint = endpoint;
    }
  }
}
