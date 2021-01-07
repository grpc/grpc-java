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

package io.grpc.xds;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.google.common.util.concurrent.MoreExecutors;
import com.google.protobuf.Any;
import com.google.protobuf.Message;
import com.google.protobuf.UInt32Value;
import com.google.protobuf.util.Durations;
import io.envoyproxy.envoy.api.v2.Cluster;
import io.envoyproxy.envoy.api.v2.Cluster.CustomClusterType;
import io.envoyproxy.envoy.api.v2.Cluster.DiscoveryType;
import io.envoyproxy.envoy.api.v2.Cluster.EdsClusterConfig;
import io.envoyproxy.envoy.api.v2.Cluster.LbPolicy;
import io.envoyproxy.envoy.api.v2.ClusterLoadAssignment;
import io.envoyproxy.envoy.api.v2.ClusterLoadAssignment.Policy;
import io.envoyproxy.envoy.api.v2.ClusterLoadAssignment.Policy.DropOverload;
import io.envoyproxy.envoy.api.v2.DiscoveryRequest;
import io.envoyproxy.envoy.api.v2.DiscoveryResponse;
import io.envoyproxy.envoy.api.v2.Listener;
import io.envoyproxy.envoy.api.v2.RouteConfiguration;
import io.envoyproxy.envoy.api.v2.auth.CommonTlsContext;
import io.envoyproxy.envoy.api.v2.auth.SdsSecretConfig;
import io.envoyproxy.envoy.api.v2.auth.UpstreamTlsContext;
import io.envoyproxy.envoy.api.v2.cluster.CircuitBreakers;
import io.envoyproxy.envoy.api.v2.cluster.CircuitBreakers.Thresholds;
import io.envoyproxy.envoy.api.v2.core.Address;
import io.envoyproxy.envoy.api.v2.core.AggregatedConfigSource;
import io.envoyproxy.envoy.api.v2.core.ApiConfigSource;
import io.envoyproxy.envoy.api.v2.core.ConfigSource;
import io.envoyproxy.envoy.api.v2.core.GrpcService;
import io.envoyproxy.envoy.api.v2.core.GrpcService.GoogleGrpc;
import io.envoyproxy.envoy.api.v2.core.HealthStatus;
import io.envoyproxy.envoy.api.v2.core.Locality;
import io.envoyproxy.envoy.api.v2.core.Node;
import io.envoyproxy.envoy.api.v2.core.RoutingPriority;
import io.envoyproxy.envoy.api.v2.core.SelfConfigSource;
import io.envoyproxy.envoy.api.v2.core.SocketAddress;
import io.envoyproxy.envoy.api.v2.core.TransportSocket;
import io.envoyproxy.envoy.api.v2.endpoint.ClusterStats;
import io.envoyproxy.envoy.api.v2.endpoint.Endpoint;
import io.envoyproxy.envoy.api.v2.endpoint.LbEndpoint;
import io.envoyproxy.envoy.api.v2.endpoint.LocalityLbEndpoints;
import io.envoyproxy.envoy.api.v2.listener.FilterChain;
import io.envoyproxy.envoy.api.v2.route.Route;
import io.envoyproxy.envoy.api.v2.route.RouteAction;
import io.envoyproxy.envoy.api.v2.route.VirtualHost;
import io.envoyproxy.envoy.config.cluster.aggregate.v2alpha.ClusterConfig;
import io.envoyproxy.envoy.config.filter.network.http_connection_manager.v2.HttpConnectionManager;
import io.envoyproxy.envoy.config.filter.network.http_connection_manager.v2.Rds;
import io.envoyproxy.envoy.config.listener.v2.ApiListener;
import io.envoyproxy.envoy.service.discovery.v2.AggregatedDiscoveryServiceGrpc.AggregatedDiscoveryServiceImplBase;
import io.envoyproxy.envoy.service.load_stats.v2.LoadReportingServiceGrpc.LoadReportingServiceImplBase;
import io.envoyproxy.envoy.service.load_stats.v2.LoadStatsRequest;
import io.envoyproxy.envoy.service.load_stats.v2.LoadStatsResponse;
import io.envoyproxy.envoy.type.FractionalPercent;
import io.envoyproxy.envoy.type.FractionalPercent.DenominatorType;
import io.grpc.BindableService;
import io.grpc.Context;
import io.grpc.Context.CancellationListener;
import io.grpc.stub.StreamObserver;
import io.grpc.xds.AbstractXdsClient.ResourceType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.annotation.Nullable;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentMatcher;
import org.mockito.InOrder;

/**
 * Tests for {@link ClientXdsClient} with protocol version v2.
 */
@RunWith(JUnit4.class)
public class ClientXdsClientV2Test extends ClientXdsClientTestBase {

  @Override
  protected BindableService createAdsService() {
    return new AggregatedDiscoveryServiceImplBase() {
      @Override
      public StreamObserver<DiscoveryRequest> streamAggregatedResources(
          final StreamObserver<DiscoveryResponse> responseObserver) {
        assertThat(adsEnded.get()).isTrue();  // ensure previous call was ended
        adsEnded.set(false);
        @SuppressWarnings("unchecked")
        StreamObserver<DiscoveryRequest> requestObserver = mock(StreamObserver.class);
        DiscoveryRpcCall call = new DiscoveryRpcCallV2(requestObserver, responseObserver);
        resourceDiscoveryCalls.offer(call);
        Context.current().addListener(
            new CancellationListener() {
              @Override
              public void cancelled(Context context) {
                adsEnded.set(true);
              }
            }, MoreExecutors.directExecutor());
        return requestObserver;
      }
    };
  }

  @Override
  protected BindableService createLrsService() {
    return new LoadReportingServiceImplBase() {
      @Override
      public StreamObserver<LoadStatsRequest> streamLoadStats(
          StreamObserver<LoadStatsResponse> responseObserver) {
        assertThat(lrsEnded.get()).isTrue();
        lrsEnded.set(false);
        @SuppressWarnings("unchecked")
        StreamObserver<LoadStatsRequest> requestObserver = mock(StreamObserver.class);
        LrsRpcCall call = new LrsRpcCallV2(requestObserver, responseObserver);
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

  @Override
  protected MessageFactory createMessageFactory() {
    return new MessageFactoryV2();
  }

  @Override
  protected boolean useProtocolV3() {
    return false;
  }

  private static class DiscoveryRpcCallV2 extends DiscoveryRpcCall {
    StreamObserver<DiscoveryRequest> requestObserver;
    StreamObserver<DiscoveryResponse> responseObserver;

    private DiscoveryRpcCallV2(StreamObserver<DiscoveryRequest> requestObserver,
        StreamObserver<DiscoveryResponse> responseObserver) {
      this.requestObserver = requestObserver;
      this.responseObserver = responseObserver;
    }

    @Override
    protected void verifyRequest(EnvoyProtoData.Node node, String versionInfo,
        List<String> resources, ResourceType type, String nonce) {
      verify(requestObserver).onNext(argThat(new DiscoveryRequestMatcher(
          node.toEnvoyProtoNodeV2(), versionInfo, resources, type.typeUrlV2(), nonce)));
    }

    @Override
    protected void verifyNoMoreRequest() {
      verifyNoMoreInteractions(requestObserver);
    }

    @Override
    protected void sendResponse(String versionInfo, List<Any> resources, ResourceType type,
        String nonce) {
      DiscoveryResponse response =
          DiscoveryResponse.newBuilder()
              .setVersionInfo(versionInfo)
              .addAllResources(resources)
              .setTypeUrl(type.typeUrl())
              .setNonce(nonce)
              .build();
      responseObserver.onNext(response);
    }

    @Override
    protected void sendError(Throwable t) {
      responseObserver.onError(t);
    }

    @Override
    protected void sendCompleted() {
      responseObserver.onCompleted();
    }
  }

  private static class LrsRpcCallV2 extends LrsRpcCall {
    private final StreamObserver<LoadStatsRequest> requestObserver;
    private final StreamObserver<LoadStatsResponse> responseObserver;
    private final InOrder inOrder;

    private LrsRpcCallV2(StreamObserver<LoadStatsRequest> requestObserver,
        StreamObserver<LoadStatsResponse> responseObserver) {
      this.requestObserver = requestObserver;
      this.responseObserver = responseObserver;
      inOrder = inOrder(requestObserver);
    }

    @Override
    protected void verifyNextReportClusters(List<String[]> clusters) {
      inOrder.verify(requestObserver).onNext(argThat(new LrsRequestMatcher(clusters)));
    }

    @Override
    protected void sendResponse(List<String> clusters, long loadReportIntervalNano) {
      LoadStatsResponse response =
          LoadStatsResponse.newBuilder()
              .addAllClusters(clusters)
              .setLoadReportingInterval(Durations.fromNanos(loadReportIntervalNano))
              .build();
      responseObserver.onNext(response);
    }
  }

  private static class MessageFactoryV2 extends MessageFactory {

    @Override
    protected Message buildListener(String name, Message routeConfiguration) {
      return Listener.newBuilder()
          .setName(name)
          .setAddress(Address.getDefaultInstance())
          .addFilterChains(FilterChain.getDefaultInstance())
          .setApiListener(
              ApiListener.newBuilder().setApiListener(Any.pack(
                  HttpConnectionManager.newBuilder()
                      .setRouteConfig((RouteConfiguration) routeConfiguration).build())))
          .build();
    }

    @Override
    protected Message buildListenerForRds(String name, String rdsResourceName) {
      return Listener.newBuilder()
          .setName(name)
          .setAddress(Address.getDefaultInstance())
          .addFilterChains(FilterChain.getDefaultInstance())
          .setApiListener(
              ApiListener.newBuilder().setApiListener(Any.pack(
                  HttpConnectionManager.newBuilder()
                      .setRds(
                          Rds.newBuilder()
                              .setRouteConfigName(rdsResourceName)
                              .setConfigSource(
                                  ConfigSource.newBuilder()
                                      .setAds(AggregatedConfigSource.getDefaultInstance())))
                      .build())))
          .build();
    }

    @Override
    protected Message buildRouteConfiguration(String name, List<Message> virtualHostList) {
      RouteConfiguration.Builder builder = RouteConfiguration.newBuilder();
      builder.setName(name);
      for (Message virtualHost : virtualHostList) {
        builder.addVirtualHosts((VirtualHost) virtualHost);
      }
      return builder.build();
    }

    @Override
    protected List<Message> buildOpaqueVirtualHosts(int num) {
      List<Message> virtualHosts = new ArrayList<>(num);
      for (int i = 0; i < num; i++) {
        VirtualHost virtualHost =
            VirtualHost.newBuilder()
                .setName(num + ": do not care")
                .addDomains("do not care")
                .addRoutes(
                    Route.newBuilder()
                        .setRoute(RouteAction.newBuilder().setCluster("do not care"))
                        .setMatch(io.envoyproxy.envoy.api.v2.route.RouteMatch.newBuilder()
                            .setPrefix("do not care")))
                .build();
        virtualHosts.add(virtualHost);
      }
      return virtualHosts;
    }

    @Override
    protected Message buildEdsCluster(String clusterName, @Nullable String edsServiceName,
        boolean enableLrs, @Nullable Message upstreamTlsContext,
        @Nullable Message circuitBreakers) {
      Cluster.Builder builder =
          initClusterBuilder(clusterName, enableLrs, upstreamTlsContext, circuitBreakers);
      builder.setType(DiscoveryType.EDS);
      EdsClusterConfig.Builder edsClusterConfigBuilder = EdsClusterConfig.newBuilder();
      edsClusterConfigBuilder.setEdsConfig(
          ConfigSource.newBuilder().setAds(AggregatedConfigSource.getDefaultInstance()));  // ADS
      if (edsServiceName != null) {
        edsClusterConfigBuilder.setServiceName(edsServiceName);
      }
      builder.setEdsClusterConfig(edsClusterConfigBuilder);
      return builder.build();
    }

    @Override
    protected Message buildLogicalDnsCluster(String clusterName, boolean enableLrs,
        @Nullable Message upstreamTlsContext, @Nullable Message circuitBreakers) {
      Cluster.Builder builder =
          initClusterBuilder(clusterName, enableLrs, upstreamTlsContext, circuitBreakers);
      builder.setType(DiscoveryType.LOGICAL_DNS);
      return builder.build();
    }

    @Override
    protected Message buildAggregateCluster(String clusterName, List<String> clusters) {
      ClusterConfig clusterConfig = ClusterConfig.newBuilder().addAllClusters(clusters).build();
      CustomClusterType type =
          CustomClusterType.newBuilder()
              .setName(ClientXdsClient.AGGREGATE_CLUSTER_TYPE_NAME)
              .setTypedConfig(Any.pack(clusterConfig))
              .build();
      return Cluster.newBuilder()
          .setName(clusterName)
          .setLbPolicy(LbPolicy.ROUND_ROBIN)
          .setClusterType(type)
          .build();
    }

    private Cluster.Builder initClusterBuilder(String clusterName, boolean enableLrs,
        @Nullable Message upstreamTlsContext, @Nullable Message circuitBreakers) {
      Cluster.Builder builder = Cluster.newBuilder();
      builder.setName(clusterName);
      builder.setLbPolicy(LbPolicy.ROUND_ROBIN);
      if (enableLrs) {
        builder.setLrsServer(
            ConfigSource.newBuilder()
                .setSelf(SelfConfigSource.getDefaultInstance()));
      }
      if (upstreamTlsContext != null) {
        builder.setTransportSocket(
            TransportSocket.newBuilder()
                .setName("envoy.transport_sockets.tls")
                .setTypedConfig(Any.pack(upstreamTlsContext)));
      }
      if (circuitBreakers != null) {
        builder.setCircuitBreakers((CircuitBreakers) circuitBreakers);
      }
      return builder;
    }

    @Override
    protected Message buildUpstreamTlsContext(String secretName, String targetUri) {
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

    @Override
    protected Message buildCircuitBreakers(int highPriorityMaxRequests,
        int defaultPriorityMaxRequests) {
      return CircuitBreakers.newBuilder()
          .addThresholds(
              Thresholds.newBuilder()
                  .setPriority(RoutingPriority.HIGH)
                  .setMaxRequests(UInt32Value.newBuilder().setValue(highPriorityMaxRequests)))
          .addThresholds(
              Thresholds.newBuilder()
                  .setPriority(RoutingPriority.DEFAULT)
                  .setMaxRequests(UInt32Value.newBuilder().setValue(defaultPriorityMaxRequests)))
          .build();
    }

    @Override
    protected Message buildClusterLoadAssignment(String cluster,
        List<Message> localityLbEndpointsList, List<Message> dropOverloadList) {
      ClusterLoadAssignment.Builder builder = ClusterLoadAssignment.newBuilder();
      builder.setClusterName(cluster);
      for (Message localityLbEndpoints : localityLbEndpointsList) {
        builder.addEndpoints((LocalityLbEndpoints) localityLbEndpoints);
      }
      Policy.Builder policyBuilder = Policy.newBuilder();
      for (Message dropOverload : dropOverloadList) {
        policyBuilder.addDropOverloads((DropOverload) dropOverload);
      }
      builder.setPolicy(policyBuilder);
      return builder.build();
    }

    @Override
    protected Message buildLocalityLbEndpoints(String region, String zone, String subZone,
        List<Message> lbEndpointList, int loadBalancingWeight, int priority) {
      LocalityLbEndpoints.Builder builder = LocalityLbEndpoints.newBuilder();
      builder.setLocality(
          Locality.newBuilder().setRegion(region).setZone(zone).setSubZone(subZone));
      for (Message lbEndpoint : lbEndpointList) {
        builder.addLbEndpoints((LbEndpoint) lbEndpoint);
      }
      builder.setLoadBalancingWeight(UInt32Value.of(loadBalancingWeight));
      builder.setPriority(priority);
      return builder.build();
    }

    @Override
    protected Message buildLbEndpoint(String address, int port, String healthStatus,
        int lbWeight) {
      HealthStatus status;
      switch (healthStatus) {
        case "unknown":
          status = HealthStatus.UNKNOWN;
          break;
        case "healthy":
          status = HealthStatus.HEALTHY;
          break;
        case "unhealthy":
          status = HealthStatus.UNHEALTHY;
          break;
        case "draining":
          status = HealthStatus.DRAINING;
          break;
        case "timeout":
          status = HealthStatus.TIMEOUT;
          break;
        case "degraded":
          status = HealthStatus.DEGRADED;
          break;
        default:
          status = HealthStatus.UNRECOGNIZED;
      }
      return LbEndpoint.newBuilder()
          .setEndpoint(
              Endpoint.newBuilder().setAddress(
                  Address.newBuilder().setSocketAddress(
                      SocketAddress.newBuilder().setAddress(address).setPortValue(port))))
          .setHealthStatus(status)
          .setLoadBalancingWeight(UInt32Value.of(lbWeight))
          .build();
    }

    @Override
    protected Message buildDropOverload(String category, int dropPerMillion) {
      return DropOverload.newBuilder()
          .setCategory(category)
          .setDropPercentage(
              FractionalPercent.newBuilder()
                  .setNumerator(dropPerMillion)
                  .setDenominator(DenominatorType.MILLION))
          .build();
    }
  }

  /**
   * Matches a {@link DiscoveryRequest} with the same node metadata, versionInfo, typeUrl,
   * response nonce and collection of resource names regardless of order.
   */
  private static class DiscoveryRequestMatcher implements ArgumentMatcher<DiscoveryRequest> {
    private final Node node;
    private final String versionInfo;
    private final String typeUrl;
    private final Set<String> resources;
    private final String responseNonce;

    private DiscoveryRequestMatcher(Node node, String versionInfo, List<String> resources,
        String typeUrl, String responseNonce) {
      this.node = node;
      this.versionInfo = versionInfo;
      this.resources = new HashSet<>(resources);
      this.typeUrl = typeUrl;
      this.responseNonce = responseNonce;
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
      return node.equals(argument.getNode());
    }
  }

  /**
   * Matches a {@link LoadStatsRequest} containing a collection of {@link ClusterStats} with
   * the same list of clusterName:clusterServiceName pair.
   */
  private static class LrsRequestMatcher implements ArgumentMatcher<LoadStatsRequest> {
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
}
