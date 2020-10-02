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

import com.google.common.collect.ImmutableList;
import com.google.protobuf.Any;
import com.google.protobuf.UInt32Value;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.DiscoveryType;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.EdsClusterConfig;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.LbPolicy;
import io.envoyproxy.envoy.config.core.v3.Address;
import io.envoyproxy.envoy.config.core.v3.AggregatedConfigSource;
import io.envoyproxy.envoy.config.core.v3.ApiConfigSource;
import io.envoyproxy.envoy.config.core.v3.ConfigSource;
import io.envoyproxy.envoy.config.core.v3.GrpcService;
import io.envoyproxy.envoy.config.core.v3.GrpcService.GoogleGrpc;
import io.envoyproxy.envoy.config.core.v3.HealthStatus;
import io.envoyproxy.envoy.config.core.v3.Locality;
import io.envoyproxy.envoy.config.core.v3.SelfConfigSource;
import io.envoyproxy.envoy.config.core.v3.SocketAddress;
import io.envoyproxy.envoy.config.core.v3.TransportSocket;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment.Policy;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment.Policy.DropOverload;
import io.envoyproxy.envoy.config.endpoint.v3.Endpoint;
import io.envoyproxy.envoy.config.endpoint.v3.LbEndpoint;
import io.envoyproxy.envoy.config.endpoint.v3.LocalityLbEndpoints;
import io.envoyproxy.envoy.config.listener.v3.ApiListener;
import io.envoyproxy.envoy.config.listener.v3.FilterChain;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.config.route.v3.Route;
import io.envoyproxy.envoy.config.route.v3.RouteAction;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.envoyproxy.envoy.config.route.v3.RouteMatch;
import io.envoyproxy.envoy.config.route.v3.VirtualHost;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.CommonTlsContext;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.SdsSecretConfig;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.UpstreamTlsContext;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryRequest;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryResponse;
import io.envoyproxy.envoy.type.v3.FractionalPercent;
import io.envoyproxy.envoy.type.v3.FractionalPercent.DenominatorType;
import io.grpc.xds.EnvoyProtoData.Node;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;

/**
 * Helper methods for building protobuf messages with custom data for xDS protocols.
 */
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
      String resourceName, String typeUrl, String nonce) {
    return buildDiscoveryRequest(node, versionInfo, ImmutableList.of(resourceName), typeUrl, nonce);
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

  static io.envoyproxy.envoy.api.v2.DiscoveryRequest buildDiscoveryRequestV2(
      Node node, String versionInfo, String resourceName, String typeUrl, String nonce) {
    return buildDiscoveryRequestV2(
        node, versionInfo, ImmutableList.of(resourceName), typeUrl, nonce);
  }

  static io.envoyproxy.envoy.api.v2.DiscoveryRequest buildDiscoveryRequestV2(
      Node node, String versionInfo, List<String> resourceNames, String typeUrl, String nonce) {
    return
        io.envoyproxy.envoy.api.v2.DiscoveryRequest.newBuilder()
            .setVersionInfo(versionInfo)
            .setNode(node.toEnvoyProtoNodeV2())
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

  static List<VirtualHost> buildVirtualHosts(int num) {
    List<VirtualHost> virtualHosts = new ArrayList<>(num);
    for (int i = 0; i < num; i++) {
      VirtualHost virtualHost =
          VirtualHost.newBuilder()
              .setName(num + ": do not care")
              .addDomains("do not care")
              .addRoutes(
                  Route.newBuilder()
                      .setRoute(RouteAction.newBuilder().setCluster("do not care"))
                      .setMatch(RouteMatch.newBuilder().setPrefix("do not care")))
              .build();
      virtualHosts.add(virtualHost);
    }
    return virtualHosts;
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

  static Cluster buildCluster(String clusterName, @Nullable String edsServiceName,
      boolean enableLrs) {
    return buildSecureCluster(clusterName, edsServiceName, enableLrs, null);
  }

  static io.envoyproxy.envoy.api.v2.Cluster buildClusterV2(
      String clusterName, @Nullable String edsServiceName, boolean enableLrs) {
    return buildSecureClusterV2(clusterName, edsServiceName, enableLrs, null);
  }

  static Cluster buildSecureCluster(
      String clusterName, @Nullable String edsServiceName, boolean enableLrs,
      @Nullable UpstreamTlsContext upstreamTlsContext) {
    Cluster.Builder clusterBuilder = Cluster.newBuilder();
    clusterBuilder.setName(clusterName);
    clusterBuilder.setType(DiscoveryType.EDS);
    EdsClusterConfig.Builder edsClusterConfigBuilder = EdsClusterConfig.newBuilder();
    edsClusterConfigBuilder.setEdsConfig(
        ConfigSource.newBuilder()
            .setAds(AggregatedConfigSource.getDefaultInstance()));
    if (edsServiceName != null) {
      edsClusterConfigBuilder.setServiceName(edsServiceName);
    }
    clusterBuilder.setEdsClusterConfig(edsClusterConfigBuilder);
    clusterBuilder.setLbPolicy(LbPolicy.ROUND_ROBIN);
    if (enableLrs) {
      clusterBuilder.setLrsServer(
          ConfigSource.newBuilder()
              .setSelf(SelfConfigSource.getDefaultInstance()));
    }
    if (upstreamTlsContext != null) {
      clusterBuilder.setTransportSocket(
          TransportSocket.newBuilder().setName("tls").setTypedConfig(Any.pack(upstreamTlsContext)));
    }
    return clusterBuilder.build();
  }

  static io.envoyproxy.envoy.api.v2.Cluster buildSecureClusterV2(
      String clusterName, @Nullable String edsServiceName, boolean enableLrs,
      @Nullable io.envoyproxy.envoy.api.v2.auth.UpstreamTlsContext upstreamTlsContext) {
    io.envoyproxy.envoy.api.v2.Cluster.Builder clusterBuilder =
        io.envoyproxy.envoy.api.v2.Cluster.newBuilder()
            .setName(clusterName)
            .setType(io.envoyproxy.envoy.api.v2.Cluster.DiscoveryType.EDS);
    io.envoyproxy.envoy.api.v2.Cluster.EdsClusterConfig.Builder edsClusterConfigBuilder =
        io.envoyproxy.envoy.api.v2.Cluster.EdsClusterConfig.newBuilder()
            .setEdsConfig(
                io.envoyproxy.envoy.api.v2.core.ConfigSource.newBuilder().setAds(
                    io.envoyproxy.envoy.api.v2.core.AggregatedConfigSource.getDefaultInstance()));
    if (edsServiceName != null) {
      edsClusterConfigBuilder.setServiceName(edsServiceName);
    }
    clusterBuilder
        .setEdsClusterConfig(edsClusterConfigBuilder)
        .setLbPolicy(io.envoyproxy.envoy.api.v2.Cluster.LbPolicy.ROUND_ROBIN);
    if (enableLrs) {
      clusterBuilder.setLrsServer(
          io.envoyproxy.envoy.api.v2.core.ConfigSource.newBuilder()
              .setSelf(io.envoyproxy.envoy.api.v2.core.SelfConfigSource.getDefaultInstance()));
    }
    if (upstreamTlsContext != null) {
      clusterBuilder.setTransportSocket(
          io.envoyproxy.envoy.api.v2.core.TransportSocket.newBuilder()
              .setName("tls").setTypedConfig(Any.pack(upstreamTlsContext)));
    }
    return clusterBuilder.build();
  }

  static ClusterLoadAssignment buildClusterLoadAssignment(String clusterName,
      List<LocalityLbEndpoints> localityLbEndpoints, List<DropOverload> dropOverloads) {
    return
        ClusterLoadAssignment.newBuilder()
            .setClusterName(clusterName)
            .addAllEndpoints(localityLbEndpoints)
            .setPolicy(Policy.newBuilder().addAllDropOverloads(dropOverloads))
            .build();
  }

  @SuppressWarnings("deprecation") // disableOverprovisioning is deprecated by needed for v2
  static io.envoyproxy.envoy.api.v2.ClusterLoadAssignment buildClusterLoadAssignmentV2(
      String clusterName,
      List<io.envoyproxy.envoy.api.v2.endpoint.LocalityLbEndpoints> localityLbEndpoints,
      List<io.envoyproxy.envoy.api.v2.ClusterLoadAssignment.Policy.DropOverload> dropOverloads) {
    return
        io.envoyproxy.envoy.api.v2.ClusterLoadAssignment.newBuilder()
            .setClusterName(clusterName)
            .addAllEndpoints(localityLbEndpoints)
            .setPolicy(
                io.envoyproxy.envoy.api.v2.ClusterLoadAssignment.Policy.newBuilder()
                    .setDisableOverprovisioning(true)
                    .addAllDropOverloads(dropOverloads))
            .build();
  }

  static DropOverload buildDropOverload(String category, int dropPerMillion) {
    return
        DropOverload.newBuilder()
            .setCategory(category)
            .setDropPercentage(
                FractionalPercent.newBuilder()
                    .setNumerator(dropPerMillion)
                    .setDenominator(DenominatorType.MILLION))
            .build();
  }

  static io.envoyproxy.envoy.api.v2.ClusterLoadAssignment.Policy.DropOverload buildDropOverloadV2(
      String category, int dropPerMillion) {
    return
        io.envoyproxy.envoy.api.v2.ClusterLoadAssignment.Policy.DropOverload.newBuilder()
            .setCategory(category)
            .setDropPercentage(
                io.envoyproxy.envoy.type.FractionalPercent.newBuilder()
                    .setNumerator(dropPerMillion)
                    .setDenominator(
                        io.envoyproxy.envoy.type.FractionalPercent.DenominatorType.MILLION))
            .build();
  }

  static LocalityLbEndpoints buildLocalityLbEndpoints(
      String region, String zone, String subZone, List<LbEndpoint> lbEndpoints,
      int loadBalancingWeight, int priority) {
    return
        LocalityLbEndpoints.newBuilder()
            .setLocality(
                Locality.newBuilder()
                    .setRegion(region)
                    .setZone(zone)
                    .setSubZone(subZone))
            .addAllLbEndpoints(lbEndpoints)
            .setLoadBalancingWeight(UInt32Value.of(loadBalancingWeight))
            .setPriority(priority)
            .build();
  }

  static io.envoyproxy.envoy.api.v2.endpoint.LocalityLbEndpoints buildLocalityLbEndpointsV2(
      String region, String zone, String subZone,
      List<io.envoyproxy.envoy.api.v2.endpoint.LbEndpoint> lbEndpoints,
      int loadBalancingWeight, int priority) {
    return
        io.envoyproxy.envoy.api.v2.endpoint.LocalityLbEndpoints.newBuilder()
            .setLocality(
                io.envoyproxy.envoy.api.v2.core.Locality.newBuilder()
                    .setRegion(region)
                    .setZone(zone)
                    .setSubZone(subZone))
            .addAllLbEndpoints(lbEndpoints)
            .setLoadBalancingWeight(UInt32Value.of(loadBalancingWeight))
            .setPriority(priority)
            .build();
  }

  static LbEndpoint buildLbEndpoint(
      String address, int port, HealthStatus healthStatus, int loadbalancingWeight) {
    return
        LbEndpoint.newBuilder()
            .setEndpoint(
                Endpoint.newBuilder().setAddress(
                    Address.newBuilder().setSocketAddress(
                        SocketAddress.newBuilder().setAddress(address).setPortValue(port))))
            .setHealthStatus(healthStatus)
            .setLoadBalancingWeight(UInt32Value.of(loadbalancingWeight))
            .build();
  }

  static io.envoyproxy.envoy.api.v2.endpoint.LbEndpoint buildLbEndpointV2(
      String address, int port, io.envoyproxy.envoy.api.v2.core.HealthStatus healthStatus,
      int loadbalancingWeight) {
    return
        io.envoyproxy.envoy.api.v2.endpoint.LbEndpoint.newBuilder()
            .setEndpoint(
                io.envoyproxy.envoy.api.v2.endpoint.Endpoint.newBuilder().setAddress(
                    io.envoyproxy.envoy.api.v2.core.Address.newBuilder().setSocketAddress(
                        io.envoyproxy.envoy.api.v2.core.SocketAddress.newBuilder()
                            .setAddress(address)
                            .setPortValue(port))))
            .setHealthStatus(healthStatus)
            .setLoadBalancingWeight(UInt32Value.of(loadbalancingWeight))
            .build();
  }

  static UpstreamTlsContext buildUpstreamTlsContext(String secretName, String targetUri) {
    GrpcService grpcService =
        GrpcService.newBuilder()
            .setGoogleGrpc(GoogleGrpc.newBuilder().setTargetUri(targetUri))
            .build();
    ConfigSource sdsConfig =
        ConfigSource.newBuilder()
            .setApiConfigSource(ApiConfigSource.newBuilder().addGrpcServices(grpcService))
            .build();
    SdsSecretConfig validationContextSdsSecretConfig =
        SdsSecretConfig.newBuilder()
            .setName(secretName)
            .setSdsConfig(sdsConfig)
            .build();
    return UpstreamTlsContext.newBuilder()
        .setCommonTlsContext(
            CommonTlsContext.newBuilder()
                .setValidationContextSdsSecretConfig(validationContextSdsSecretConfig))
        .build();
  }

  static io.envoyproxy.envoy.api.v2.auth.UpstreamTlsContext buildUpstreamTlsContextV2(
      String secretName, String targetUri) {
    io.envoyproxy.envoy.api.v2.core.GrpcService grpcService =
        io.envoyproxy.envoy.api.v2.core.GrpcService.newBuilder()
            .setGoogleGrpc(io.envoyproxy.envoy.api.v2.core.GrpcService.GoogleGrpc.newBuilder()
                .setTargetUri(targetUri))
            .build();
    io.envoyproxy.envoy.api.v2.core.ConfigSource sdsConfig =
        io.envoyproxy.envoy.api.v2.core.ConfigSource.newBuilder()
            .setApiConfigSource(io.envoyproxy.envoy.api.v2.core.ApiConfigSource.newBuilder()
                .addGrpcServices(grpcService))
            .build();
    io.envoyproxy.envoy.api.v2.auth.SdsSecretConfig validationContextSdsSecretConfig =
        io.envoyproxy.envoy.api.v2.auth.SdsSecretConfig.newBuilder()
            .setName(secretName)
            .setSdsConfig(sdsConfig)
            .build();
    return io.envoyproxy.envoy.api.v2.auth.UpstreamTlsContext.newBuilder()
        .setCommonTlsContext(
            io.envoyproxy.envoy.api.v2.auth.CommonTlsContext.newBuilder()
                .setValidationContextSdsSecretConfig(validationContextSdsSecretConfig))
        .build();
  }
}
