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
import io.envoyproxy.envoy.api.v2.Cluster;
import io.envoyproxy.envoy.api.v2.Cluster.DiscoveryType;
import io.envoyproxy.envoy.api.v2.Cluster.EdsClusterConfig;
import io.envoyproxy.envoy.api.v2.Cluster.LbPolicy;
import io.envoyproxy.envoy.api.v2.ClusterLoadAssignment;
import io.envoyproxy.envoy.api.v2.ClusterLoadAssignment.Policy;
import io.envoyproxy.envoy.api.v2.DiscoveryRequest;
import io.envoyproxy.envoy.api.v2.DiscoveryResponse;
import io.envoyproxy.envoy.api.v2.Listener;
import io.envoyproxy.envoy.api.v2.RouteConfiguration;
import io.envoyproxy.envoy.api.v2.auth.CommonTlsContext;
import io.envoyproxy.envoy.api.v2.auth.SdsSecretConfig;
import io.envoyproxy.envoy.api.v2.auth.UpstreamTlsContext;
import io.envoyproxy.envoy.api.v2.core.Address;
import io.envoyproxy.envoy.api.v2.core.AggregatedConfigSource;
import io.envoyproxy.envoy.api.v2.core.ApiConfigSource;
import io.envoyproxy.envoy.api.v2.core.ConfigSource;
import io.envoyproxy.envoy.api.v2.core.GrpcService;
import io.envoyproxy.envoy.api.v2.core.GrpcService.GoogleGrpc;
import io.envoyproxy.envoy.api.v2.core.HealthStatus;
import io.envoyproxy.envoy.api.v2.core.Node;
import io.envoyproxy.envoy.api.v2.core.SelfConfigSource;
import io.envoyproxy.envoy.api.v2.core.SocketAddress;
import io.envoyproxy.envoy.api.v2.listener.FilterChain;
import io.envoyproxy.envoy.api.v2.route.Route;
import io.envoyproxy.envoy.api.v2.route.RouteAction;
import io.envoyproxy.envoy.api.v2.route.RouteMatch;
import io.envoyproxy.envoy.api.v2.route.VirtualHost;
import io.envoyproxy.envoy.config.listener.v2.ApiListener;
import io.envoyproxy.envoy.type.FractionalPercent;
import io.envoyproxy.envoy.type.FractionalPercent.DenominatorType;
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

  static DiscoveryRequest buildDiscoveryRequest(Node node, String versionInfo,
      String resourceName, String typeUrl, String nonce) {
    return buildDiscoveryRequest(node, versionInfo, ImmutableList.of(resourceName), typeUrl, nonce);
  }

  static DiscoveryRequest buildDiscoveryRequest(Node node, String versionInfo,
      List<String> resourceNames, String typeUrl, String nonce) {
    return
        DiscoveryRequest.newBuilder()
            .setVersionInfo(versionInfo)
            .setNode(node)
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

  static RouteConfiguration buildRouteConfiguration(String name,
      List<VirtualHost> virtualHosts) {
    return
        RouteConfiguration.newBuilder()
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
                .setRoute(RouteAction.newBuilder().setCluster("whatever cluster"))
                .setMatch(RouteMatch.newBuilder().setPrefix("")))
        .addRoutes(
            // Only the last (default) route matters.
            Route.newBuilder()
                .setRoute(RouteAction.newBuilder().setCluster(clusterName))
                .setMatch(RouteMatch.newBuilder().setPrefix("")))
        .build();
  }

  static Cluster buildCluster(String clusterName, @Nullable String edsServiceName,
      boolean enableLrs) {
    return buildSecureCluster(clusterName, edsServiceName, enableLrs, null);
  }

  @SuppressWarnings("deprecation")
  static Cluster buildSecureCluster(String clusterName, @Nullable String edsServiceName,
      boolean enableLrs, @Nullable UpstreamTlsContext upstreamTlsContext) {
    Cluster.Builder clusterBuilder = Cluster.newBuilder();
    clusterBuilder.setName(clusterName);
    clusterBuilder.setType(DiscoveryType.EDS);
    EdsClusterConfig.Builder edsClusterConfigBuilder = EdsClusterConfig.newBuilder();
    edsClusterConfigBuilder.setEdsConfig(
        ConfigSource.newBuilder().setAds(AggregatedConfigSource.getDefaultInstance()));
    if (edsServiceName != null) {
      edsClusterConfigBuilder.setServiceName(edsServiceName);
    }
    clusterBuilder.setEdsClusterConfig(edsClusterConfigBuilder);
    clusterBuilder.setLbPolicy(LbPolicy.ROUND_ROBIN);
    if (enableLrs) {
      clusterBuilder.setLrsServer(
          ConfigSource.newBuilder().setSelf(SelfConfigSource.getDefaultInstance()));
    }
    if (upstreamTlsContext != null) {
      clusterBuilder.setTlsContext(upstreamTlsContext);
    }
    return clusterBuilder.build();
  }

  static ClusterLoadAssignment buildClusterLoadAssignment(String clusterName,
      List<io.envoyproxy.envoy.api.v2.endpoint.LocalityLbEndpoints> localityLbEndpoints,
      List<Policy.DropOverload> dropOverloads) {
    return
        ClusterLoadAssignment.newBuilder()
            .setClusterName(clusterName)
            .addAllEndpoints(localityLbEndpoints)
            .setPolicy(
                Policy.newBuilder()
                    .setDisableOverprovisioning(true)
                    .addAllDropOverloads(dropOverloads))
            .build();
  }

  static Policy.DropOverload buildDropOverload(String category, int dropPerMillion) {
    return
        Policy.DropOverload.newBuilder()
            .setCategory(category)
            .setDropPercentage(
                FractionalPercent.newBuilder()
                    .setNumerator(dropPerMillion)
                    .setDenominator(DenominatorType.MILLION))
            .build();
  }

  static io.envoyproxy.envoy.api.v2.endpoint.LocalityLbEndpoints buildLocalityLbEndpoints(
      String region, String zone, String subzone,
      List<io.envoyproxy.envoy.api.v2.endpoint.LbEndpoint> lbEndpoints,
      int loadBalancingWeight, int priority) {
    return
        io.envoyproxy.envoy.api.v2.endpoint.LocalityLbEndpoints.newBuilder()
            .setLocality(
                io.envoyproxy.envoy.api.v2.core.Locality.newBuilder()
                    .setRegion(region)
                    .setZone(zone)
                    .setSubZone(subzone))
            .addAllLbEndpoints(lbEndpoints)
            .setLoadBalancingWeight(UInt32Value.newBuilder().setValue(loadBalancingWeight))
            .setPriority(priority)
            .build();
  }

  static io.envoyproxy.envoy.api.v2.endpoint.LbEndpoint buildLbEndpoint(String address,
      int port, HealthStatus healthStatus, int loadbalancingWeight) {
    return
        io.envoyproxy.envoy.api.v2.endpoint.LbEndpoint.newBuilder()
            .setEndpoint(
                io.envoyproxy.envoy.api.v2.endpoint.Endpoint.newBuilder().setAddress(
                    Address.newBuilder().setSocketAddress(
                        SocketAddress.newBuilder().setAddress(address).setPortValue(port))))
            .setHealthStatus(healthStatus).setLoadBalancingWeight(
            UInt32Value.newBuilder().setValue(loadbalancingWeight))
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
}
