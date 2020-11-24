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

import com.google.protobuf.Any;
import io.envoyproxy.envoy.config.core.v3.Address;
import io.envoyproxy.envoy.config.listener.v3.ApiListener;
import io.envoyproxy.envoy.config.listener.v3.FilterChain;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.config.route.v3.Route;
import io.envoyproxy.envoy.config.route.v3.RouteAction;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.envoyproxy.envoy.config.route.v3.RouteMatch;
import io.envoyproxy.envoy.config.route.v3.VirtualHost;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryRequest;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryResponse;
import io.grpc.xds.EnvoyProtoData.Node;
import java.util.List;

/**
 * Helper methods for building protobuf messages with custom data for xDS protocols.
 */
// TODO(chengyuanzhang, sanjaypujare): delete this class, should not dump everything here.
class XdsClientTestHelper {
  static DiscoveryResponse buildDiscoveryResponse(String versionInfo,
      List<Any> resources, String typeUrl, String nonce) {
    return
        DiscoveryResponse.newBuilder()
            .setVersionInfo(versionInfo)
            .setTypeUrl(typeUrl)
            .addAllResources(resources)
            .setNonce(nonce)
            .build();
  }

  static io.envoyproxy.envoy.api.v2.DiscoveryResponse buildDiscoveryResponseV2(String versionInfo,
      List<Any> resources, String typeUrl, String nonce) {
    return
        io.envoyproxy.envoy.api.v2.DiscoveryResponse.newBuilder()
            .setVersionInfo(versionInfo)
            .setTypeUrl(typeUrl)
            .addAllResources(resources)
            .setNonce(nonce)
            .build();
  }

  static DiscoveryRequest buildDiscoveryRequest(Node node, String versionInfo,
      List<String> resourceNames, String typeUrl, String nonce) {
    return
        DiscoveryRequest.newBuilder()
            .setVersionInfo(versionInfo)
            .setNode(node.toEnvoyProtoNode())
            .setTypeUrl(typeUrl)
            .addAllResourceNames(resourceNames)
            .setResponseNonce(nonce)
            .build();
  }

  static Listener buildListener(String name, com.google.protobuf.Any apiListener) {
    return
        Listener.newBuilder()
            .setName(name)
            .setAddress(Address.getDefaultInstance())
            .addFilterChains(FilterChain.getDefaultInstance())
            .setApiListener(ApiListener.newBuilder().setApiListener(apiListener))
            .build();
  }

  static io.envoyproxy.envoy.api.v2.Listener buildListenerV2(
      String name, com.google.protobuf.Any apiListener) {
    return
        io.envoyproxy.envoy.api.v2.Listener.newBuilder()
            .setName(name)
            .setAddress(io.envoyproxy.envoy.api.v2.core.Address.getDefaultInstance())
            .addFilterChains(io.envoyproxy.envoy.api.v2.listener.FilterChain.getDefaultInstance())
            .setApiListener(io.envoyproxy.envoy.config.listener.v2.ApiListener.newBuilder()
                .setApiListener(apiListener))
            .build();
  }

  static RouteConfiguration buildRouteConfiguration(String name,
      List<VirtualHost> virtualHosts) {
    return
        RouteConfiguration.newBuilder()
            .setName(name)
            .addAllVirtualHosts(virtualHosts)
            .build();
  }

  static io.envoyproxy.envoy.api.v2.RouteConfiguration buildRouteConfigurationV2(String name,
      List<io.envoyproxy.envoy.api.v2.route.VirtualHost> virtualHosts) {
    return
        io.envoyproxy.envoy.api.v2.RouteConfiguration.newBuilder()
            .setName(name)
            .addAllVirtualHosts(virtualHosts)
            .build();
  }

  static VirtualHost buildVirtualHost(List<String> domains, String clusterName) {
    return VirtualHost.newBuilder()
        .setName("virtualhost00.googleapis.com") // don't care
        .addAllDomains(domains)
        .addRoutes(
            Route.newBuilder()
                .setRoute(RouteAction.newBuilder().setCluster(clusterName))
                .setMatch(RouteMatch.newBuilder().setPrefix("")))
        .build();
  }

  static io.envoyproxy.envoy.api.v2.route.VirtualHost buildVirtualHostV2(
      List<String> domains, String clusterName) {
    return io.envoyproxy.envoy.api.v2.route.VirtualHost.newBuilder()
        .setName("virtualhost00.googleapis.com") // don't care
        .addAllDomains(domains)
        .addRoutes(
            io.envoyproxy.envoy.api.v2.route.Route.newBuilder()
                .setRoute(
                    io.envoyproxy.envoy.api.v2.route.RouteAction.newBuilder()
                        .setCluster(clusterName))
                .setMatch(io.envoyproxy.envoy.api.v2.route.RouteMatch.newBuilder().setPrefix("")))
        .build();
  }
}
