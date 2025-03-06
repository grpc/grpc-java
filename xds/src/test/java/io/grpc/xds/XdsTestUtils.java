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

import static com.google.common.truth.Truth.assertThat;
import static io.grpc.xds.XdsTestControlPlaneService.ADS_TYPE_URL_CDS;
import static io.grpc.xds.XdsTestControlPlaneService.ADS_TYPE_URL_EDS;
import static io.grpc.xds.XdsTestControlPlaneService.ADS_TYPE_URL_LDS;
import static io.grpc.xds.XdsTestControlPlaneService.ADS_TYPE_URL_RDS;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.protobuf.Any;
import com.google.protobuf.BoolValue;
import com.google.protobuf.Message;
import com.google.protobuf.util.Durations;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterStats;
import io.envoyproxy.envoy.config.listener.v3.ApiListener;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.config.route.v3.Route;
import io.envoyproxy.envoy.config.route.v3.RouteAction;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.envoyproxy.envoy.config.route.v3.RouteMatch;
import io.envoyproxy.envoy.extensions.clusters.aggregate.v3.ClusterConfig;
import io.envoyproxy.envoy.extensions.filters.http.router.v3.Router;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpFilter;
import io.envoyproxy.envoy.service.load_stats.v3.LoadReportingServiceGrpc;
import io.envoyproxy.envoy.service.load_stats.v3.LoadStatsRequest;
import io.envoyproxy.envoy.service.load_stats.v3.LoadStatsResponse;
import io.grpc.BindableService;
import io.grpc.Context;
import io.grpc.Context.CancellationListener;
import io.grpc.Status;
import io.grpc.StatusOr;
import io.grpc.internal.JsonParser;
import io.grpc.stub.StreamObserver;
import io.grpc.xds.Endpoints.LbEndpoint;
import io.grpc.xds.Endpoints.LocalityLbEndpoints;
import io.grpc.xds.XdsConfig.XdsClusterConfig.EndpointConfig;
import io.grpc.xds.client.Bootstrapper;
import io.grpc.xds.client.Locality;
import io.grpc.xds.client.XdsResourceType;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.mockito.ArgumentMatcher;
import org.mockito.InOrder;

public class XdsTestUtils {
  private static final Logger log = Logger.getLogger(XdsTestUtils.class.getName());
  static final String RDS_NAME = "route-config.googleapis.com";
  static final String CLUSTER_NAME = "cluster0";
  static final String EDS_NAME = "eds-service-0";
  static final String SERVER_LISTENER = "grpc/server?udpa.resource.listening_address=";
  static final String HTTP_CONNECTION_MANAGER_TYPE_URL =
      "type.googleapis.com/envoy.extensions.filters.network.http_connection_manager.v3"
          + ".HttpConnectionManager";
  public static final String ENDPOINT_HOSTNAME = "data-host";
  public static final int ENDPOINT_PORT = 1234;

  static BindableService createLrsService(AtomicBoolean lrsEnded,
                                          Queue<LrsRpcCall> loadReportCalls) {
    return new LoadReportingServiceGrpc.LoadReportingServiceImplBase() {
      @Override
      public StreamObserver<LoadStatsRequest> streamLoadStats(
          StreamObserver<LoadStatsResponse> responseObserver) {
        assertThat(lrsEnded.get()).isTrue();
        lrsEnded.set(false);
        @SuppressWarnings("unchecked")
        StreamObserver<LoadStatsRequest> requestObserver = mock(StreamObserver.class);
        LrsRpcCall call = new LrsRpcCall(requestObserver, responseObserver);
        Context.current().addListener(
            new CancellationListener() {
              @Override
              public void cancelled(Context context) {
                lrsEnded.set(true);
              }
            }, MoreExecutors.directExecutor());
        loadReportCalls.offer(call);
        return requestObserver;
      }
    };
  }

  static boolean matchErrorDetail(
      com.google.rpc.Status errorDetail, int expectedCode, List<String> expectedMessages) {
    if (expectedCode != errorDetail.getCode()) {
      return false;
    }
    List<String> errors = Splitter.on('\n').splitToList(errorDetail.getMessage());
    if (errors.size() != expectedMessages.size()) {
      return false;
    }
    for (int i = 0; i < errors.size(); i++) {
      if (!errors.get(i).startsWith(expectedMessages.get(i))) {
        return false;
      }
    }
    return true;
  }

  static void setAdsConfig(XdsTestControlPlaneService service, String serverName) {
    setAdsConfig(service, serverName, RDS_NAME, CLUSTER_NAME, EDS_NAME, ENDPOINT_HOSTNAME,
        ENDPOINT_PORT);
  }

  static void setAdsConfig(XdsTestControlPlaneService service, String serverName, String rdsName,
                           String clusterName, String edsName, String endpointHostname,
                           int endpointPort) {

    Listener serverListener = ControlPlaneRule.buildServerListener();
    Listener clientListener = ControlPlaneRule.buildClientListener(serverName, serverName, rdsName);
    service.setXdsConfig(ADS_TYPE_URL_LDS,
        ImmutableMap.of(SERVER_LISTENER, serverListener, serverName, clientListener));

    RouteConfiguration routeConfig =
        buildRouteConfiguration(serverName, rdsName, clusterName);
    service.setXdsConfig(ADS_TYPE_URL_RDS, ImmutableMap.of(rdsName, routeConfig));;

    Cluster cluster = ControlPlaneRule.buildCluster(clusterName, edsName);
    service.setXdsConfig(ADS_TYPE_URL_CDS, ImmutableMap.<String, Message>of(clusterName, cluster));

    ClusterLoadAssignment clusterLoadAssignment = ControlPlaneRule.buildClusterLoadAssignment(
        serverName, endpointHostname, endpointPort, edsName);
    service.setXdsConfig(ADS_TYPE_URL_EDS,
        ImmutableMap.<String, Message>of(edsName, clusterLoadAssignment));

    log.log(Level.FINE, String.format("Set ADS config for %s with address %s:%d",
        serverName, endpointHostname, endpointPort));

  }

  static String getEdsNameForCluster(String clusterName) {
    return "eds_" + clusterName;
  }

  static void setAggregateCdsConfig(XdsTestControlPlaneService service, String serverName,
                                    String clusterName, List<String> children) {
    Map<String, Message> clusterMap = new HashMap<>();

    ClusterConfig rootConfig = ClusterConfig.newBuilder().addAllClusters(children).build();
    Cluster.CustomClusterType type =
        Cluster.CustomClusterType.newBuilder()
            .setName(XdsClusterResource.AGGREGATE_CLUSTER_TYPE_NAME)
            .setTypedConfig(Any.pack(rootConfig))
            .build();
    Cluster.Builder builder = Cluster.newBuilder().setName(clusterName).setClusterType(type);
    builder.setLbPolicy(Cluster.LbPolicy.ROUND_ROBIN);
    Cluster cluster = builder.build();
    clusterMap.put(clusterName, cluster);

    for (String child : children) {
      Cluster childCluster = ControlPlaneRule.buildCluster(child, getEdsNameForCluster(child));
      clusterMap.put(child, childCluster);
    }

    service.setXdsConfig(ADS_TYPE_URL_CDS, clusterMap);

    Map<String, Message> edsMap = new HashMap<>();
    for (String child : children) {
      ClusterLoadAssignment clusterLoadAssignment = ControlPlaneRule.buildClusterLoadAssignment(
          serverName, ENDPOINT_HOSTNAME, ENDPOINT_PORT, getEdsNameForCluster(child));
      edsMap.put(getEdsNameForCluster(child), clusterLoadAssignment);
    }
    service.setXdsConfig(ADS_TYPE_URL_EDS, edsMap);
  }

  static void addAggregateToExistingConfig(XdsTestControlPlaneService service, String rootName,
                                           List<String> children) {
    Map<String, Message> clusterMap = new HashMap<>(service.getCurrentConfig(ADS_TYPE_URL_CDS));
    if (clusterMap.containsKey(rootName)) {
      throw new IllegalArgumentException("Root cluster " + rootName + " already exists");
    }
    ClusterConfig rootConfig = ClusterConfig.newBuilder().addAllClusters(children).build();
    Cluster.CustomClusterType type =
        Cluster.CustomClusterType.newBuilder()
            .setName(XdsClusterResource.AGGREGATE_CLUSTER_TYPE_NAME)
            .setTypedConfig(Any.pack(rootConfig))
            .build();
    Cluster.Builder builder = Cluster.newBuilder().setName(rootName).setClusterType(type);
    builder.setLbPolicy(Cluster.LbPolicy.ROUND_ROBIN);
    Cluster cluster = builder.build();
    clusterMap.put(rootName, cluster);

    for (String child : children) {
      if (clusterMap.containsKey(child)) {
        continue;
      }
      Cluster childCluster = ControlPlaneRule.buildCluster(child, getEdsNameForCluster(child));
      clusterMap.put(child, childCluster);
    }

    service.setXdsConfig(ADS_TYPE_URL_CDS, clusterMap);

    Map<String, Message> edsMap = new HashMap<>(service.getCurrentConfig(ADS_TYPE_URL_EDS));
    for (String child : children) {
      if (edsMap.containsKey(getEdsNameForCluster(child))) {
        continue;
      }
      ClusterLoadAssignment clusterLoadAssignment = ControlPlaneRule.buildClusterLoadAssignment(
          child, ENDPOINT_HOSTNAME, ENDPOINT_PORT, getEdsNameForCluster(child));
      edsMap.put(getEdsNameForCluster(child), clusterLoadAssignment);
    }
    service.setXdsConfig(ADS_TYPE_URL_EDS, edsMap);
  }

  static XdsConfig getDefaultXdsConfig(String serverHostName)
      throws XdsResourceType.ResourceInvalidException, IOException {
    XdsConfig.XdsConfigBuilder builder = new XdsConfig.XdsConfigBuilder();

    Filter.NamedFilterConfig routerFilterConfig = new Filter.NamedFilterConfig(
        serverHostName, RouterFilter.ROUTER_CONFIG);

    HttpConnectionManager httpConnectionManager = HttpConnectionManager.forRdsName(
        0L, RDS_NAME, Collections.singletonList(routerFilterConfig));
    XdsListenerResource.LdsUpdate ldsUpdate =
        XdsListenerResource.LdsUpdate.forApiListener(httpConnectionManager);

    RouteConfiguration routeConfiguration =
        buildRouteConfiguration(serverHostName, RDS_NAME, CLUSTER_NAME);
    Bootstrapper.ServerInfo serverInfo = null;
    XdsResourceType.Args args = new XdsResourceType.Args(serverInfo, "0", "0", null, null, null);
    XdsRouteConfigureResource.RdsUpdate rdsUpdate =
        XdsRouteConfigureResource.getInstance().doParse(args, routeConfiguration);

    // Take advantage of knowing that there is only 1 virtual host in the route configuration
    assertThat(rdsUpdate.virtualHosts).hasSize(1);
    VirtualHost virtualHost = rdsUpdate.virtualHosts.get(0);

    // Need to create endpoints to create locality endpoints map to create edsUpdate
    Map<Locality, LocalityLbEndpoints> lbEndpointsMap = new HashMap<>();
    LbEndpoint lbEndpoint = LbEndpoint.create(
        serverHostName, ENDPOINT_PORT, 0, true, ENDPOINT_HOSTNAME, ImmutableMap.of());
    lbEndpointsMap.put(
        Locality.create("", "", ""),
        LocalityLbEndpoints.create(ImmutableList.of(lbEndpoint), 10, 0, ImmutableMap.of()));

    // Need to create EdsUpdate to create CdsUpdate to create XdsClusterConfig for builder
    XdsEndpointResource.EdsUpdate edsUpdate = new XdsEndpointResource.EdsUpdate(
        EDS_NAME, lbEndpointsMap, Collections.emptyList());
    XdsClusterResource.CdsUpdate cdsUpdate = XdsClusterResource.CdsUpdate.forEds(
        CLUSTER_NAME, EDS_NAME, serverInfo, null, null, null, false)
        .lbPolicyConfig(getWrrLbConfigAsMap()).build();
    XdsConfig.XdsClusterConfig clusterConfig = new XdsConfig.XdsClusterConfig(
        CLUSTER_NAME, cdsUpdate, new EndpointConfig(StatusOr.fromValue(edsUpdate)));

    builder
        .setListener(ldsUpdate)
        .setRoute(rdsUpdate)
        .setVirtualHost(virtualHost)
        .addCluster(CLUSTER_NAME, StatusOr.fromValue(clusterConfig));

    return builder.build();
  }

  @SuppressWarnings("unchecked")
  private static ImmutableMap<String, ?> getWrrLbConfigAsMap() throws IOException {
    String lbConfigStr = "{\"wrr_locality_experimental\" : "
        + "{ \"childPolicy\" : [{\"round_robin\" : {}}]}}";

    return ImmutableMap.copyOf((Map<String, ?>) JsonParser.parse(lbConfigStr));
  }

  static RouteConfiguration buildRouteConfiguration(String authority, String rdsName,
                                                    String clusterName) {
    io.envoyproxy.envoy.config.route.v3.VirtualHost.Builder vhBuilder =
        io.envoyproxy.envoy.config.route.v3.VirtualHost.newBuilder()
            .setName(rdsName)
            .addDomains(authority)
            .addRoutes(
                Route.newBuilder()
                    .setMatch(
                        RouteMatch.newBuilder().setPrefix("/").build())
                    .setRoute(
                        RouteAction.newBuilder().setCluster(clusterName)
                            .setAutoHostRewrite(BoolValue.newBuilder().setValue(true).build())
                            .build()));
    io.envoyproxy.envoy.config.route.v3.VirtualHost virtualHost = vhBuilder.build();
    return RouteConfiguration.newBuilder().setName(rdsName).addVirtualHosts(virtualHost).build();
  }

  static Cluster buildAggCluster(String name, List<String> childNames) {
    ClusterConfig rootConfig = ClusterConfig.newBuilder().addAllClusters(childNames).build();
    Cluster.CustomClusterType type =
        Cluster.CustomClusterType.newBuilder()
            .setName(XdsClusterResource.AGGREGATE_CLUSTER_TYPE_NAME)
            .setTypedConfig(Any.pack(rootConfig))
            .build();
    Cluster.Builder builder =
        Cluster.newBuilder().setName(name).setClusterType(type);
    builder.setLbPolicy(Cluster.LbPolicy.ROUND_ROBIN);
    Cluster cluster = builder.build();
    return cluster;
  }

  static void addEdsClusters(Map<String, Message> clusterMap, Map<String, Message> edsMap,
                             String... clusterNames) {
    for (String clusterName : clusterNames) {
      String edsName = getEdsNameForCluster(clusterName);
      Cluster cluster = ControlPlaneRule.buildCluster(clusterName, edsName);
      clusterMap.put(clusterName, cluster);

      ClusterLoadAssignment clusterLoadAssignment = ControlPlaneRule.buildClusterLoadAssignment(
          clusterName, ENDPOINT_HOSTNAME, ENDPOINT_PORT, edsName);
      edsMap.put(edsName, clusterLoadAssignment);
    }
  }

  static Listener buildInlineClientListener(String rdsName, String clusterName, String serverName) {
    HttpFilter
        httpFilter = HttpFilter.newBuilder()
        .setName(serverName)
        .setTypedConfig(Any.pack(Router.newBuilder().build()))
        .setIsOptional(true)
        .build();
    ApiListener.Builder clientListenerBuilder =
        ApiListener.newBuilder().setApiListener(Any.pack(
            io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3
                .HttpConnectionManager.newBuilder()
                .setRouteConfig(
                    buildRouteConfiguration(serverName, rdsName, clusterName))
                .addAllHttpFilters(Collections.singletonList(httpFilter))
                .build(),
            HTTP_CONNECTION_MANAGER_TYPE_URL));
    return Listener.newBuilder()
        .setName(serverName)
        .setApiListener(clientListenerBuilder.build()).build();

  }

  /**
   * Matches a {@link LoadStatsRequest} containing a collection of {@link ClusterStats} with
   * the same list of clusterName:clusterServiceName pair.
   */
  static class LrsRequestMatcher implements ArgumentMatcher<LoadStatsRequest> {
    private final List<String> expected;

    private LrsRequestMatcher(List<String[]> clusterNames) {
      expected = new ArrayList<>();
      for (String[] pair : clusterNames) {
        expected.add(pair[0] + ":" + (pair[1] == null ? "" : pair[1]));
      }
      Collections.sort(expected);
    }

    @Override
    public boolean matches(LoadStatsRequest argument) {
      List<String> actual = new ArrayList<>();
      for (ClusterStats clusterStats : argument.getClusterStatsList()) {
        actual.add(clusterStats.getClusterName() + ":" + clusterStats.getClusterServiceName());
      }
      Collections.sort(actual);
      return actual.equals(expected);
    }
  }

  static class LrsRpcCall  {
    private final StreamObserver<LoadStatsRequest> requestObserver;
    private final StreamObserver<LoadStatsResponse> responseObserver;
    private final InOrder inOrder;

    private LrsRpcCall(StreamObserver<LoadStatsRequest> requestObserver,
                         StreamObserver<LoadStatsResponse> responseObserver) {
      this.requestObserver = requestObserver;
      this.responseObserver = responseObserver;
      inOrder = inOrder(requestObserver);
    }

    protected void verifyNextReportClusters(List<String[]> clusters) {
      inOrder.verify(requestObserver).onNext(argThat(new LrsRequestMatcher(clusters)));
    }

    protected void sendResponse(List<String> clusters, long loadReportIntervalNano) {
      LoadStatsResponse response =
          LoadStatsResponse.newBuilder()
              .addAllClusters(clusters)
              .setLoadReportingInterval(Durations.fromNanos(loadReportIntervalNano))
              .build();
      responseObserver.onNext(response);
    }
  }

  static class StatusMatcher implements ArgumentMatcher<Status> {
    private final Status expectedStatus;

    StatusMatcher(Status expectedStatus) {
      this.expectedStatus = expectedStatus;
    }

    @Override
    public boolean matches(Status status) {
      return status != null && expectedStatus.getCode().equals(status.getCode())
          && expectedStatus.getDescription().equals(status.getDescription());
    }
  }
}
