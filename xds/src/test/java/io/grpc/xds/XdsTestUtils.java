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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.protobuf.Any;
import com.google.protobuf.BoolValue;
import com.google.protobuf.Message;
import com.google.protobuf.util.Durations;
import com.google.rpc.Code;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.core.v3.Node;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterStats;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.config.route.v3.Route;
import io.envoyproxy.envoy.config.route.v3.RouteAction;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.envoyproxy.envoy.config.route.v3.RouteMatch;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryRequest;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryResponse;
import io.envoyproxy.envoy.service.load_stats.v3.LoadReportingServiceGrpc;
import io.envoyproxy.envoy.service.load_stats.v3.LoadStatsRequest;
import io.envoyproxy.envoy.service.load_stats.v3.LoadStatsResponse;
import io.grpc.BindableService;
import io.grpc.Context;
import io.grpc.Context.CancellationListener;
import io.grpc.StatusOr;
import io.grpc.internal.JsonParser;
import io.grpc.internal.ServiceConfigUtil;
import io.grpc.internal.ServiceConfigUtil.LbConfig;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import io.grpc.xds.Endpoints.LbEndpoint;
import io.grpc.xds.Endpoints.LocalityLbEndpoints;
import io.grpc.xds.client.Bootstrapper;
import io.grpc.xds.client.EnvoyProtoData;
import io.grpc.xds.client.Locality;
import io.grpc.xds.client.XdsResourceType;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;
import org.mockito.ArgumentMatcher;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.mockito.verification.VerificationMode;

public class XdsTestUtils {
  private static final Logger log = Logger.getLogger(XdsTestUtils.class.getName());
  private static final String RDS_NAME = "route-config.googleapis.com";
  private static final String CLUSTER_NAME = "cluster0";
  private static final String EDS_NAME = "eds-service-0";
  private static final String SERVER_LISTENER = "grpc/server?udpa.resource.listening_address=";
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
        serverName, endpointHostname, endpointPort);
    service.setXdsConfig(ADS_TYPE_URL_EDS,
        ImmutableMap.<String, Message>of(edsName, clusterLoadAssignment));

    log.log(Level.FINE, String.format("Set ADS config for %s with address %s:%d",
        serverName, endpointHostname, endpointPort));

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
        XdsRouteConfigureResource.processRouteConfiguration(
        routeConfiguration, FilterRegistry.getDefaultRegistry(), args);

    // Need to create endpoints to create locality endpoints map to create edsUpdate
    Map<Locality, LocalityLbEndpoints> lbEndpointsMap = new HashMap<>();
    LbEndpoint lbEndpoint =
        LbEndpoint.create(serverHostName, ENDPOINT_PORT, 0, true, ENDPOINT_HOSTNAME);
    lbEndpointsMap.put(
        Locality.create("", "", ""),
        LocalityLbEndpoints.create(ImmutableList.of(lbEndpoint), 10, 0));

    // Need to create EdsUpdate to create CdsUpdate to create XdsClusterConfig for builder
    XdsEndpointResource.EdsUpdate edsUpdate = new XdsEndpointResource.EdsUpdate(
        EDS_NAME, lbEndpointsMap, Collections.emptyList());
    XdsClusterResource.CdsUpdate cdsUpdate = XdsClusterResource.CdsUpdate.forEds(
        CLUSTER_NAME, EDS_NAME, serverInfo, null, null, null)
        .lbPolicyConfig(getWrrLbConfigAsMap()).build();
    XdsConfig.XdsClusterConfig clusterConfig = new XdsConfig.XdsClusterConfig(
        CLUSTER_NAME, cdsUpdate, StatusOr.fromValue(edsUpdate));

    builder.setListener(ldsUpdate)
        .setRoute(rdsUpdate)
        .addCluster(CLUSTER_NAME, StatusOr.fromValue(clusterConfig));

    return builder.build();
  }

  private static ConfigOrError<LbConfig> getWrrLbConfig() throws IOException {
    Map<String, ?> lbParsed = getWrrLbConfigAsMap();
    LbConfig lbConfig = ServiceConfigUtil.unwrapLoadBalancingConfig(lbParsed);

    return ConfigOrError.fromConfig(lbConfig);
  }

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

  /**
   * Matches a {@link DiscoveryRequest} with the same node metadata, versionInfo, typeUrl,
   * response nonce and collection of resource names regardless of order.
   */
  static class DiscoveryRequestMatcher implements ArgumentMatcher<DiscoveryRequest> {
    private final Node node;
    private final String versionInfo;
    private final String typeUrl;
    private final Set<String> resources;
    private final String responseNonce;
    @Nullable
    private final Integer errorCode;
    private final List<String> errorMessages;

    private DiscoveryRequestMatcher(
        Node node, String versionInfo, List<String> resources,
        String typeUrl, String responseNonce, @Nullable Integer errorCode,
        @Nullable List<String> errorMessages) {
      this.node = node;
      this.versionInfo = versionInfo;
      this.resources = new HashSet<>(resources);
      this.typeUrl = typeUrl;
      this.responseNonce = responseNonce;
      this.errorCode = errorCode;
      this.errorMessages = errorMessages != null ? errorMessages : ImmutableList.<String>of();
    }

    @Override
    public boolean matches(DiscoveryRequest argument) {
      if (!typeUrl.equals(argument.getTypeUrl())) {
        return false;
      }
      if (!versionInfo.equals(argument.getVersionInfo())) {
        return false;
      }
      if (!responseNonce.equals(argument.getResponseNonce())) {
        return false;
      }
      if (!resources.equals(new HashSet<>(argument.getResourceNamesList()))) {
        return false;
      }
      if (errorCode == null && argument.hasErrorDetail()) {
        return false;
      }
      if (errorCode != null
          && !matchErrorDetail(argument.getErrorDetail(), errorCode, errorMessages)) {
        return false;
      }
      return node.equals(argument.getNode());
    }

    @Override
    public String toString() {
      return "DiscoveryRequestMatcher{"
          + "node=" + node
          + ", versionInfo='" + versionInfo + '\''
          + ", typeUrl='" + typeUrl + '\''
          + ", resources=" + resources
          + ", responseNonce='" + responseNonce + '\''
          + ", errorCode=" + errorCode
          + ", errorMessages=" + errorMessages
          + '}';
    }
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

  static class DiscoveryRpcCall  {
    StreamObserver<DiscoveryRequest> requestObserver;
    StreamObserver<DiscoveryResponse> responseObserver;

    private DiscoveryRpcCall(StreamObserver<DiscoveryRequest> requestObserver,
                               StreamObserver<DiscoveryResponse> responseObserver) {
      this.requestObserver = requestObserver;
      this.responseObserver = responseObserver;
    }

    protected void verifyRequest(
        XdsResourceType<?> type, List<String> resources, String versionInfo, String nonce,
        EnvoyProtoData.Node node, VerificationMode verificationMode) {
      verify(requestObserver, verificationMode).onNext(argThat(new DiscoveryRequestMatcher(
          node.toEnvoyProtoNode(), versionInfo, resources, type.typeUrl(), nonce, null, null)));
    }

    protected void verifyRequestNack(
        XdsResourceType<?> type, List<String> resources, String versionInfo, String nonce,
        EnvoyProtoData.Node node, List<String> errorMessages) {
      verify(requestObserver, Mockito.timeout(2000)).onNext(argThat(new DiscoveryRequestMatcher(
          node.toEnvoyProtoNode(), versionInfo, resources, type.typeUrl(), nonce,
          Code.INVALID_ARGUMENT_VALUE, errorMessages)));
    }

    protected void verifyNoMoreRequest() {
      verifyNoMoreInteractions(requestObserver);
    }

    protected void sendResponse(
        XdsResourceType<?> type, List<Any> resources, String versionInfo, String nonce) {
      DiscoveryResponse response =
          DiscoveryResponse.newBuilder()
              .setVersionInfo(versionInfo)
              .addAllResources(resources)
              .setTypeUrl(type.typeUrl())
              .setNonce(nonce)
              .build();
      responseObserver.onNext(response);
    }

    protected void sendError(Throwable t) {
      responseObserver.onError(t);
    }

    protected void sendCompleted() {
      responseObserver.onCompleted();
    }

    protected boolean isReady() {
      return ((ServerCallStreamObserver)responseObserver).isReady();
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
}
