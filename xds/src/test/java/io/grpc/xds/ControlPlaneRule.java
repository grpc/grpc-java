/*
 * Copyright 2022 The gRPC Authors
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

import static io.grpc.xds.XdsTestControlPlaneService.ADS_TYPE_URL_CDS;
import static io.grpc.xds.XdsTestControlPlaneService.ADS_TYPE_URL_EDS;
import static io.grpc.xds.XdsTestControlPlaneService.ADS_TYPE_URL_LDS;
import static io.grpc.xds.XdsTestControlPlaneService.ADS_TYPE_URL_RDS;

import com.google.common.collect.ImmutableMap;
import com.google.protobuf.Any;
import com.google.protobuf.Message;
import com.google.protobuf.UInt32Value;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.core.v3.Address;
import io.envoyproxy.envoy.config.core.v3.AggregatedConfigSource;
import io.envoyproxy.envoy.config.core.v3.ConfigSource;
import io.envoyproxy.envoy.config.core.v3.HealthStatus;
import io.envoyproxy.envoy.config.core.v3.SocketAddress;
import io.envoyproxy.envoy.config.core.v3.TrafficDirection;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.endpoint.v3.Endpoint;
import io.envoyproxy.envoy.config.endpoint.v3.LbEndpoint;
import io.envoyproxy.envoy.config.endpoint.v3.LocalityLbEndpoints;
import io.envoyproxy.envoy.config.listener.v3.ApiListener;
import io.envoyproxy.envoy.config.listener.v3.Filter;
import io.envoyproxy.envoy.config.listener.v3.FilterChain;
import io.envoyproxy.envoy.config.listener.v3.FilterChainMatch;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.config.route.v3.NonForwardingAction;
import io.envoyproxy.envoy.config.route.v3.Route;
import io.envoyproxy.envoy.config.route.v3.RouteAction;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.envoyproxy.envoy.config.route.v3.RouteMatch;
import io.envoyproxy.envoy.config.route.v3.VirtualHost;
import io.envoyproxy.envoy.extensions.filters.http.router.v3.Router;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpFilter;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.Rds;
import io.grpc.Grpc;
import io.grpc.InsecureServerCredentials;
import io.grpc.NameResolverRegistry;
import io.grpc.Server;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;

/**
 * Starts a control plane server and sets up the test to use it. Initialized with a default
 * configuration, but also provides methods for updating the configuration.
 */
public class ControlPlaneRule extends TestWatcher {
  private static final Logger logger = Logger.getLogger(ControlPlaneRule.class.getName());

  private static final String SCHEME = "test-xds";
  private static final String RDS_NAME = "route-config.googleapis.com";
  private static final String CLUSTER_NAME = "cluster0";
  private static final String EDS_NAME = "eds-service-0";
  private static final String SERVER_LISTENER_TEMPLATE_NO_REPLACEMENT =
      "grpc/server?udpa.resource.listening_address=";
  private static final String HTTP_CONNECTION_MANAGER_TYPE_URL =
      "type.googleapis.com/envoy.extensions.filters.network.http_connection_manager.v3"
          + ".HttpConnectionManager";

  private String serverHostName;
  private Server server;
  private XdsTestControlPlaneService controlPlaneService;
  private XdsTestLoadReportingService loadReportingService;
  private XdsNameResolverProvider nameResolverProvider;

  public ControlPlaneRule() {
    serverHostName = "test-server";
  }

  public ControlPlaneRule setServerHostName(String serverHostName) {
    this.serverHostName = serverHostName;
    return this;
  }

  /**
   * Returns the test control plane service interface.
   */
  public XdsTestControlPlaneService getService() {
    return controlPlaneService;
  }

  /**
   * Returns the server instance.
   */
  public Server getServer() {
    return server;
  }

  @Override protected void starting(Description description) {
    // Start the control plane server.
    try {
      controlPlaneService = new XdsTestControlPlaneService();
      loadReportingService = new XdsTestLoadReportingService();
      server = Grpc.newServerBuilderForPort(0, InsecureServerCredentials.create())
          .addService(controlPlaneService)
          .addService(loadReportingService)
          .build()
          .start();
    } catch (Exception e) {
      throw new AssertionError("unable to start the control plane server", e);
    }

    // Configure and register an xDS name resolver so that gRPC knows how to connect to the server.
    nameResolverProvider = XdsNameResolverProvider.createForTest(SCHEME,
        defaultBootstrapOverride());
    NameResolverRegistry.getDefaultRegistry().register(nameResolverProvider);
  }

  @Override protected void finished(Description description) {
    if (server != null) {
      server.shutdownNow();
      try {
        if (!server.awaitTermination(5, TimeUnit.SECONDS)) {
          logger.log(Level.SEVERE, "Timed out waiting for server shutdown");
        }
      } catch (InterruptedException e) {
        throw new AssertionError("unable to shut down control plane server", e);
      }
    }
    NameResolverRegistry.getDefaultRegistry().deregister(nameResolverProvider);
  }

  /**
   * For test purpose, use boostrapOverride to programmatically provide bootstrap info.
   */
  public Map<String, ?> defaultBootstrapOverride() {
    return ImmutableMap.of(
        "node", ImmutableMap.of(
            "id", UUID.randomUUID().toString(),
            "cluster", "cluster0"),
        "xds_servers", Collections.singletonList(

            ImmutableMap.of(
                "server_uri", "localhost:" + server.getPort(),
                "channel_creds", Collections.singletonList(
                    ImmutableMap.of("type", "insecure")
                ),
                "server_features", Collections.singletonList("xds_v3")
            )
        ),
        "server_listener_resource_name_template", SERVER_LISTENER_TEMPLATE_NO_REPLACEMENT
    );
  }

  void setLdsConfig(Listener serverListener, Listener clientListener) {
    getService().setXdsConfig(ADS_TYPE_URL_LDS,
        ImmutableMap.of(SERVER_LISTENER_TEMPLATE_NO_REPLACEMENT, serverListener,
                        serverHostName, clientListener));
  }

  void setRdsConfig(RouteConfiguration routeConfiguration) {
    getService().setXdsConfig(ADS_TYPE_URL_RDS, ImmutableMap.of(RDS_NAME, routeConfiguration));
  }

  void setCdsConfig(Cluster cluster) {
    getService().setXdsConfig(ADS_TYPE_URL_CDS,
        ImmutableMap.<String, Message>of(CLUSTER_NAME, cluster));
  }

  void setEdsConfig(ClusterLoadAssignment clusterLoadAssignment) {
    getService().setXdsConfig(ADS_TYPE_URL_EDS,
        ImmutableMap.<String, Message>of(EDS_NAME, clusterLoadAssignment));
  }

  /**
   * Builds a new default RDS configuration.
   */
  static RouteConfiguration buildRouteConfiguration(String authority) {
    io.envoyproxy.envoy.config.route.v3.VirtualHost virtualHost = VirtualHost.newBuilder()
        .addDomains(authority)
        .addRoutes(
            Route.newBuilder()
                .setMatch(
                    RouteMatch.newBuilder().setPrefix("/").build())
                .setRoute(
                    RouteAction.newBuilder().setCluster(CLUSTER_NAME).build()).build()).build();
    return RouteConfiguration.newBuilder().setName(RDS_NAME).addVirtualHosts(virtualHost).build();
  }

  /**
   * Builds a new default CDS configuration.
   */
  static Cluster buildCluster() {
    return Cluster.newBuilder()
        .setName(CLUSTER_NAME)
        .setType(Cluster.DiscoveryType.EDS)
        .setEdsClusterConfig(
            Cluster.EdsClusterConfig.newBuilder()
                .setServiceName(EDS_NAME)
                .setEdsConfig(
                    ConfigSource.newBuilder()
                        .setAds(AggregatedConfigSource.newBuilder().build())
                        .build())
                .build())
        .setLbPolicy(Cluster.LbPolicy.ROUND_ROBIN)
        .build();
  }

  /**
   * Builds a new default EDS configuration.
   */
  static ClusterLoadAssignment buildClusterLoadAssignment(String hostName, int port) {
    Address address = Address.newBuilder()
        .setSocketAddress(
            SocketAddress.newBuilder().setAddress(hostName).setPortValue(port).build()).build();
    LocalityLbEndpoints endpoints = LocalityLbEndpoints.newBuilder()
        .setLoadBalancingWeight(UInt32Value.of(10))
        .setPriority(0)
        .addLbEndpoints(
            LbEndpoint.newBuilder()
                .setEndpoint(
                    Endpoint.newBuilder().setAddress(address).build())
                .setHealthStatus(HealthStatus.HEALTHY)
                .build()).build();
    return ClusterLoadAssignment.newBuilder()
        .setClusterName(EDS_NAME)
        .addEndpoints(endpoints)
        .build();
  }

  /**
   * Builds a new client listener.
   */
  static Listener buildClientListener(String name) {
    HttpFilter httpFilter = HttpFilter.newBuilder()
        .setName("terminal-filter")
        .setTypedConfig(Any.pack(Router.newBuilder().build()))
        .setIsOptional(true)
        .build();
    ApiListener apiListener = ApiListener.newBuilder().setApiListener(Any.pack(
        io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3
            .HttpConnectionManager.newBuilder()
            .setRds(
                Rds.newBuilder()
                    .setRouteConfigName(RDS_NAME)
                    .setConfigSource(
                        ConfigSource.newBuilder()
                            .setAds(AggregatedConfigSource.getDefaultInstance())))
            .addAllHttpFilters(Collections.singletonList(httpFilter))
            .build(),
        HTTP_CONNECTION_MANAGER_TYPE_URL)).build();
    return Listener.newBuilder()
        .setName(name)
        .setApiListener(apiListener).build();
  }

  /**
   * Builds a new server listener.
   */
  static Listener buildServerListener() {
    HttpFilter routerFilter = HttpFilter.newBuilder()
        .setName("terminal-filter")
        .setTypedConfig(
            Any.pack(Router.newBuilder().build()))
        .setIsOptional(true)
        .build();
    VirtualHost virtualHost = io.envoyproxy.envoy.config.route.v3.VirtualHost.newBuilder()
        .setName("virtual-host-0")
        .addDomains("*")
        .addRoutes(
            Route.newBuilder()
                .setMatch(
                    RouteMatch.newBuilder().setPrefix("/").build())
                .setNonForwardingAction(NonForwardingAction.newBuilder().build())
                .build()).build();
    RouteConfiguration routeConfig = RouteConfiguration.newBuilder()
        .addVirtualHosts(virtualHost)
        .build();
    io.envoyproxy.envoy.config.listener.v3.Filter filter = Filter.newBuilder()
        .setName("network-filter-0")
        .setTypedConfig(
            Any.pack(
                HttpConnectionManager.newBuilder()
                    .setRouteConfig(routeConfig)
                    .addAllHttpFilters(Collections.singletonList(routerFilter))
                    .build())).build();
    FilterChainMatch filterChainMatch = FilterChainMatch.newBuilder()
        .setSourceType(FilterChainMatch.ConnectionSourceType.ANY)
        .build();
    FilterChain filterChain = FilterChain.newBuilder()
        .setName("filter-chain-0")
        .setFilterChainMatch(filterChainMatch)
        .addFilters(filter)
        .build();
    return Listener.newBuilder()
        .setName(SERVER_LISTENER_TEMPLATE_NO_REPLACEMENT)
        .setTrafficDirection(TrafficDirection.INBOUND)
        .addFilterChains(filterChain)
        .build();
  }
}
