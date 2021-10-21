/*
 * Copyright 2020 The gRPC Authors
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


package io.grpc.testing.integration;

import com.google.common.collect.ImmutableMap;
import com.google.protobuf.Any;
import com.google.protobuf.UInt32Value;
import io.grpc.SynchronizationContext;
import io.grpc.stub.StreamObserver;
import io.grpc.xds.shaded.io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.grpc.xds.shaded.io.envoyproxy.envoy.config.core.v3.Address;
import io.grpc.xds.shaded.io.envoyproxy.envoy.config.core.v3.AggregatedConfigSource;
import io.grpc.xds.shaded.io.envoyproxy.envoy.config.core.v3.ConfigSource;
import io.grpc.xds.shaded.io.envoyproxy.envoy.config.core.v3.HealthStatus;
import io.grpc.xds.shaded.io.envoyproxy.envoy.config.core.v3.SocketAddress;
import io.grpc.xds.shaded.io.envoyproxy.envoy.config.core.v3.TrafficDirection;
import io.grpc.xds.shaded.io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.grpc.xds.shaded.io.envoyproxy.envoy.config.endpoint.v3.Endpoint;
import io.grpc.xds.shaded.io.envoyproxy.envoy.config.endpoint.v3.LbEndpoint;
import io.grpc.xds.shaded.io.envoyproxy.envoy.config.endpoint.v3.LocalityLbEndpoints;
import io.grpc.xds.shaded.io.envoyproxy.envoy.config.listener.v3.ApiListener;
import io.grpc.xds.shaded.io.envoyproxy.envoy.config.listener.v3.Filter;
import io.grpc.xds.shaded.io.envoyproxy.envoy.config.listener.v3.FilterChain;
import io.grpc.xds.shaded.io.envoyproxy.envoy.config.listener.v3.FilterChainMatch;
import io.grpc.xds.shaded.io.envoyproxy.envoy.config.listener.v3.Listener;
import io.grpc.xds.shaded.io.envoyproxy.envoy.config.route.v3.NonForwardingAction;
import io.grpc.xds.shaded.io.envoyproxy.envoy.config.route.v3.Route;
import io.grpc.xds.shaded.io.envoyproxy.envoy.config.route.v3.RouteAction;
import io.grpc.xds.shaded.io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.grpc.xds.shaded.io.envoyproxy.envoy.config.route.v3.RouteMatch;
import io.grpc.xds.shaded.io.envoyproxy.envoy.config.route.v3.VirtualHost;
import io.grpc.xds.shaded.io.envoyproxy.envoy.extensions.filters.http.router.v3.Router;
import io.grpc.xds.shaded.io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager;
import io.grpc.xds.shaded.io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpFilter;
import io.grpc.xds.shaded.io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.Rds;
import io.grpc.xds.shaded.io.envoyproxy.envoy.service.discovery.v3.AggregatedDiscoveryServiceGrpc;
import io.grpc.xds.shaded.io.envoyproxy.envoy.service.discovery.v3.DiscoveryRequest;
import io.grpc.xds.shaded.io.envoyproxy.envoy.service.discovery.v3.DiscoveryResponse;

import java.util.Collections;
import java.util.logging.Level;
import java.util.logging.Logger;

public class XdsTestControlPlaneService extends
    AggregatedDiscoveryServiceGrpc.AggregatedDiscoveryServiceImplBase {
  private static final Logger logger = Logger.getLogger(XdsInteropTest.class.getName());

  private final SynchronizationContext syncContext = new SynchronizationContext(
      new Thread.UncaughtExceptionHandler() {
        @Override
        public void uncaughtException(Thread t, Throwable e) {
          logger.log(Level.SEVERE, "Exception!" + e);
        }
      });

  private static final String ADS_TYPE_URL_LDS =
      "type.googleapis.com/envoy.config.listener.v3.Listener";
  private static final String ADS_TYPE_URL_RDS =
      "type.googleapis.com/envoy.config.route.v3.RouteConfiguration";
  private static final String ADS_TYPE_URL_CDS =
      "type.googleapis.com/envoy.config.cluster.v3.Cluster";
  private static final String ADS_TYPE_URL_EDS =
      "type.googleapis.com/envoy.config.endpoint.v3.ClusterLoadAssignment";
  private static final String HTTP_CONNECTION_MANAGER_TYPE_URL =
      "type.googleapis.com/envoy.extensions.filters.network.http_connection_manager.v3"
          + ".HttpConnectionManager";

  private static final String rdsName = "route-config.googleapis.com";
  private static final String clusterName = "cluster0";
  private static final String edsName = "eds-service-0";
  private final ImmutableMap<String, Listener> ldsResources;
  private final ImmutableMap<String, RouteConfiguration> rdsResources;
  private final ImmutableMap<String, Cluster> cdsResources;
  private final ImmutableMap<String, ClusterLoadAssignment> edsResources;
  private int versionNo = 0;

  /**
   * Create a control plane service for testing, with static xds configurations.
   */
  public XdsTestControlPlaneService(XdsTestControlPlaneConfig config) {
    this.ldsResources = ImmutableMap.of(config.apiListener.getName(), config.apiListener,
        config.tcpListener.getName(), config.tcpListener);
    this.rdsResources = ImmutableMap.of(rdsName, config.rds);
    this.cdsResources = ImmutableMap.of(clusterName, config.cds);
    this.edsResources = ImmutableMap.of(edsName, config.eds);
  }

  public static class XdsTestControlPlaneConfig {
    Listener tcpListener;
    Listener apiListener;
    RouteConfiguration rds;
    Cluster cds;
    ClusterLoadAssignment eds;

    /**
     * Provide control plane xds configurations.
     */
    public XdsTestControlPlaneConfig(Listener tcpListener, Listener apiListener,
         RouteConfiguration rds, Cluster cds, ClusterLoadAssignment eds) {
      this.tcpListener = tcpListener;
      this.apiListener = apiListener;
      this.rds = rds;
      this.cds = cds;
      this.eds = eds;
    }
  }

  @Override
  public StreamObserver<DiscoveryRequest> streamAggregatedResources(
      final StreamObserver<DiscoveryResponse> responseObserver) {
    final StreamObserver<DiscoveryRequest> requestObserver =
        new StreamObserver<DiscoveryRequest>() {
      @Override
      public void onNext(final DiscoveryRequest value) {
        syncContext.execute(new Runnable() {
          @Override
          public void run() {
            logger.log(Level.FINEST, "control plane received request {0}", value);
            if (!value.getResponseNonce().isEmpty()) {
              logger.log(Level.FINEST, "control plane received ack for resource:  {0}",
                  value.getResourceNamesList());
              return;
            }
            if (value.hasErrorDetail()) {
              logger.log(Level.FINE, "control plane received nack resource {0}, error {1}",
                  new Object[]{value.getResourceNamesList(), value.getErrorDetail()});
              return;
            }
            if (value.getResourceNamesCount() <= 0) {
              return;
            }
            switch (value.getTypeUrl()) {
              case ADS_TYPE_URL_LDS:
                DiscoveryResponse.Builder responseBuilder = DiscoveryResponse.newBuilder()
                    .setTypeUrl(ADS_TYPE_URL_LDS)
                    .setVersionInfo(String.valueOf(versionNo++))
                    .setNonce("0");
                for (String ldsName: value.getResourceNamesList()) {
                  if (ldsResources.containsKey(ldsName)) {
                    responseBuilder.addResources(Any.pack(
                        ldsResources.get(ldsName),
                        ADS_TYPE_URL_LDS
                    ));
                  }
                }
                responseObserver.onNext(responseBuilder.build());
                break;
              case ADS_TYPE_URL_RDS:
                responseBuilder = DiscoveryResponse.newBuilder()
                    .setTypeUrl(ADS_TYPE_URL_RDS)
                    .setVersionInfo(String.valueOf(versionNo++))
                    .setNonce("0");
                for (String rdsName: value.getResourceNamesList()) {
                  if (rdsResources.containsKey(rdsName)) {
                    responseBuilder.addResources(Any.pack(
                        rdsResources.get(rdsName),
                        ADS_TYPE_URL_RDS
                    ));
                  }
                }
                responseObserver.onNext(responseBuilder.build());
                break;
              case ADS_TYPE_URL_CDS:
                responseBuilder = DiscoveryResponse.newBuilder()
                    .setTypeUrl(ADS_TYPE_URL_CDS)
                    .setVersionInfo(String.valueOf(versionNo++))
                    .setNonce("0");
                for (String cdsName: value.getResourceNamesList()) {
                  if (cdsResources.containsKey(cdsName)) {
                    responseBuilder.addResources(Any.pack(
                        cdsResources.get(cdsName),
                        ADS_TYPE_URL_CDS
                    ));
                  }
                }
                responseObserver.onNext(responseBuilder.build());
                break;
              case ADS_TYPE_URL_EDS:
                responseBuilder = DiscoveryResponse.newBuilder()
                    .setTypeUrl(ADS_TYPE_URL_EDS)
                    .setVersionInfo(String.valueOf(versionNo++))
                    .setNonce("0");
                for (String edsName: value.getResourceNamesList()) {
                  if (edsResources.containsKey(edsName)) {
                    responseBuilder.addResources(Any.pack(
                            edsResources.get(value.getResourceNames(0)),
                            ADS_TYPE_URL_EDS
                    ));
                  }
                }
                responseObserver.onNext(responseBuilder.build());
                break;
              default:
                logger.log(Level.WARNING, "unrecognized typeUrl in discoveryRequest: {0}",
                    value.getTypeUrl());
            }
          }
        });
      }

      @Override
      public void onError(Throwable t) {
        logger.log(Level.FINE, "Control plane error: {0} ", t);
      }

      @Override
      public void onCompleted() {
        responseObserver.onCompleted();
      }
    };
    return requestObserver;
  }

  static Listener clientListener(String name) {
    HttpFilter httpFilter = HttpFilter.newBuilder()
        .setName("terminal-filter")
        .setTypedConfig(Any.pack(Router.newBuilder().build()))
        .setIsOptional(true)
        .build();
    ApiListener apiListener = ApiListener.newBuilder().setApiListener(Any.pack(
        HttpConnectionManager.newBuilder()
            .setRds(
                Rds.newBuilder()
                    .setRouteConfigName(rdsName)
                    .setConfigSource(
                        ConfigSource.newBuilder()
                            .setAds(AggregatedConfigSource.getDefaultInstance())))
            .addAllHttpFilters(Collections.singletonList(httpFilter))
            .build(),
        HTTP_CONNECTION_MANAGER_TYPE_URL)
    ).build();
    Listener listener = Listener.newBuilder()
        .setName(name)
        .setApiListener(apiListener).build();
    return listener;
  }

  static Listener serverListener(String name, String authority) {
    HttpFilter routerFilter = HttpFilter.newBuilder()
        .setName("terminal-filter")
        .setTypedConfig(
            Any.pack(Router.newBuilder().build()))
        .setIsOptional(true)
        .build();
    VirtualHost virtualHost = VirtualHost.newBuilder()
        .setName("virtual-host-0")
        .addDomains(authority)
        .addRoutes(
            Route.newBuilder()
                .setMatch(
                    RouteMatch.newBuilder().setPrefix("/").build()
                )
                .setNonForwardingAction(NonForwardingAction.newBuilder().build())
                .build()
        ).build();
    RouteConfiguration routeConfig = RouteConfiguration.newBuilder()
        .addVirtualHosts(virtualHost)
        .build();
    Filter filter = Filter.newBuilder()
        .setName("network-filter-0")
        .setTypedConfig(
            Any.pack(
                HttpConnectionManager.newBuilder()
                    .setRouteConfig(routeConfig)
                    .addAllHttpFilters(Collections.singletonList(routerFilter))
                    .build()
            )
        ).build();
    FilterChainMatch filterChainMatch = FilterChainMatch.newBuilder()
        .setSourceType(FilterChainMatch.ConnectionSourceType.ANY)
        .build();
    FilterChain filterChain = FilterChain.newBuilder()
        .setName("filter-chain-0")
        .setFilterChainMatch(filterChainMatch)
        .addFilters(filter)
        .build();
    return Listener.newBuilder()
        .setName(name)
        .setTrafficDirection(TrafficDirection.INBOUND)
        .addFilterChains(filterChain)
        .build();
  }

  static RouteConfiguration rds(String authority) {
    VirtualHost virtualHost = VirtualHost.newBuilder()
        .addDomains(authority)
        .addRoutes(
            Route.newBuilder()
                .setMatch(
                    RouteMatch.newBuilder().setPrefix("/").build()
                )
                .setRoute(
                    RouteAction.newBuilder().setCluster(clusterName).build()
                )
                .build())
        .build();
    return RouteConfiguration.newBuilder().setName(rdsName).addVirtualHosts(virtualHost).build();
  }

  static Cluster cds() {
    return Cluster.newBuilder()
        .setName(clusterName)
        .setType(Cluster.DiscoveryType.EDS)
        .setEdsClusterConfig(
            Cluster.EdsClusterConfig.newBuilder()
                .setServiceName(edsName)
                .setEdsConfig(
                    ConfigSource.newBuilder()
                        .setAds(AggregatedConfigSource.newBuilder().build())
                        .build())
                .build()
        )
        .setLbPolicy(Cluster.LbPolicy.ROUND_ROBIN)
        .build();
  }

  static ClusterLoadAssignment eds(int port) {
    Address address = Address.newBuilder()
        .setSocketAddress(
            SocketAddress.newBuilder().setAddress("0.0.0.0").setPortValue(port).build()
        )
        .build();
    LocalityLbEndpoints endpoints = LocalityLbEndpoints.newBuilder()
        .setLoadBalancingWeight(UInt32Value.of(10))
        .setPriority(0)
        .addLbEndpoints(
            LbEndpoint.newBuilder()
                .setEndpoint(
                    Endpoint.newBuilder().setAddress(address).build())
                .setHealthStatus(HealthStatus.HEALTHY)
                .build()
        )
        .build();
    return ClusterLoadAssignment.newBuilder()
        .setClusterName(edsName)
        .addEndpoints(endpoints)
        .build();
  }
}
