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
import static io.grpc.xds.XdsLbPolicies.CLUSTER_RESOLVER_POLICY_NAME;
import static io.grpc.xds.XdsLbPolicies.PRIORITY_POLICY_NAME;
import static io.grpc.xds.XdsTestControlPlaneService.ADS_TYPE_URL_CDS;
import static io.grpc.xds.XdsTestControlPlaneService.ADS_TYPE_URL_EDS;
import static io.grpc.xds.XdsTestControlPlaneService.ADS_TYPE_URL_LDS;
import static io.grpc.xds.XdsTestControlPlaneService.ADS_TYPE_URL_RDS;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.xds.type.v3.TypedStruct;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.protobuf.Any;
import com.google.protobuf.Struct;
import com.google.protobuf.UInt32Value;
import com.google.protobuf.Value;
import io.envoyproxy.envoy.config.cluster.v3.CircuitBreakers;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.cluster.v3.LoadBalancingPolicy;
import io.envoyproxy.envoy.config.cluster.v3.LoadBalancingPolicy.Policy;
import io.envoyproxy.envoy.config.cluster.v3.OutlierDetection;
import io.envoyproxy.envoy.config.core.v3.Address;
import io.envoyproxy.envoy.config.core.v3.AggregatedConfigSource;
import io.envoyproxy.envoy.config.core.v3.ConfigSource;
import io.envoyproxy.envoy.config.core.v3.RoutingPriority;
import io.envoyproxy.envoy.config.core.v3.SelfConfigSource;
import io.envoyproxy.envoy.config.core.v3.SocketAddress;
import io.envoyproxy.envoy.config.core.v3.TransportSocket;
import io.envoyproxy.envoy.config.core.v3.TypedExtensionConfig;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.endpoint.v3.Endpoint;
import io.envoyproxy.envoy.config.endpoint.v3.LbEndpoint;
import io.envoyproxy.envoy.config.endpoint.v3.LocalityLbEndpoints;
import io.envoyproxy.envoy.extensions.clusters.aggregate.v3.ClusterConfig;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.UpstreamTlsContext;
import io.grpc.Attributes;
import io.grpc.ChannelLogger;
import io.grpc.ConnectivityState;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancer.PickResult;
import io.grpc.LoadBalancer.PickSubchannelArgs;
import io.grpc.LoadBalancer.ResolvedAddresses;
import io.grpc.LoadBalancer.SubchannelPicker;
import io.grpc.LoadBalancerProvider;
import io.grpc.LoadBalancerRegistry;
import io.grpc.NameResolver;
import io.grpc.NameResolverRegistry;
import io.grpc.Status;
import io.grpc.Status.Code;
import io.grpc.SynchronizationContext;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.internal.FakeClock;
import io.grpc.testing.GrpcCleanupRule;
import io.grpc.util.GracefulSwitchLoadBalancerAccessor;
import io.grpc.xds.CdsLoadBalancerProvider.CdsConfig;
import io.grpc.xds.ClusterResolverLoadBalancerProvider.ClusterResolverConfig;
import io.grpc.xds.ClusterResolverLoadBalancerProvider.ClusterResolverConfig.DiscoveryMechanism;
import io.grpc.xds.EnvoyServerProtoData.FailurePercentageEjection;
import io.grpc.xds.EnvoyServerProtoData.SuccessRateEjection;
import io.grpc.xds.client.Bootstrapper.ServerInfo;
import io.grpc.xds.client.XdsClient;
import io.grpc.xds.internal.security.CommonTlsContextTestsUtil;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/**
 * Tests for {@link CdsLoadBalancer2}.
 */
@RunWith(JUnit4.class)
public class CdsLoadBalancer2Test {
  @Rule public final MockitoRule mocks = MockitoJUnit.rule();
  @Rule
  public final GrpcCleanupRule cleanupRule = new GrpcCleanupRule();

  private static final String SERVER_NAME = "example.com";
  private static final String CLUSTER = "cluster-foo.googleapis.com";
  private static final String EDS_SERVICE_NAME = "backend-service-1.googleapis.com";
  private static final String NODE_ID = "node-id";
  private final io.grpc.xds.EnvoyServerProtoData.UpstreamTlsContext upstreamTlsContext =
      CommonTlsContextTestsUtil.buildUpstreamTlsContext("cert-instance-name", true);
  private static final Cluster EDS_CLUSTER = Cluster.newBuilder()
      .setName(CLUSTER)
      .setType(Cluster.DiscoveryType.EDS)
      .setEdsClusterConfig(Cluster.EdsClusterConfig.newBuilder()
          .setServiceName(EDS_SERVICE_NAME)
          .setEdsConfig(ConfigSource.newBuilder()
            .setAds(AggregatedConfigSource.newBuilder())))
      .build();

  private final FakeClock fakeClock = new FakeClock();
  private final LoadBalancerRegistry lbRegistry = new LoadBalancerRegistry();
  private final List<FakeLoadBalancer> childBalancers = new ArrayList<>();
  private final XdsTestControlPlaneService controlPlaneService = new XdsTestControlPlaneService();
  private final XdsClient xdsClient = XdsTestUtils.createXdsClient(
      Arrays.asList("control-plane.example.com"),
      serverInfo -> new GrpcXdsTransportFactory.GrpcXdsTransport(
          InProcessChannelBuilder
            .forName(serverInfo.target())
            .directExecutor()
            .build()),
      fakeClock);
  private final ServerInfo lrsServerInfo = xdsClient.getBootstrapInfo().servers().get(0);
  private XdsDependencyManager xdsDepManager;

  @Mock
  private Helper helper;
  @Captor
  private ArgumentCaptor<SubchannelPicker> pickerCaptor;
  private CdsLoadBalancer2 loadBalancer;
  private XdsConfig lastXdsConfig;

  @Before
  public void setUp() throws Exception {
    lbRegistry.register(new FakeLoadBalancerProvider(CLUSTER_RESOLVER_POLICY_NAME));
    lbRegistry.register(new FakeLoadBalancerProvider("round_robin"));
    lbRegistry.register(
        new FakeLoadBalancerProvider("ring_hash_experimental", new RingHashLoadBalancerProvider()));
    lbRegistry.register(new FakeLoadBalancerProvider("least_request_experimental",
        new LeastRequestLoadBalancerProvider()));
    lbRegistry.register(new FakeLoadBalancerProvider("wrr_locality_experimental",
          new WrrLocalityLoadBalancerProvider()));
    CdsLoadBalancerProvider cdsLoadBalancerProvider = new CdsLoadBalancerProvider(lbRegistry);
    lbRegistry.register(cdsLoadBalancerProvider);
    loadBalancer = (CdsLoadBalancer2) cdsLoadBalancerProvider.newLoadBalancer(helper);

    cleanupRule.register(InProcessServerBuilder
        .forName("control-plane.example.com")
        .addService(controlPlaneService)
        .directExecutor()
        .build()
        .start());

    SynchronizationContext syncContext = new SynchronizationContext((t, e) -> {
      throw new AssertionError(e);
    });
    when(helper.getSynchronizationContext()).thenReturn(syncContext);
    when(helper.getScheduledExecutorService()).thenReturn(fakeClock.getScheduledExecutorService());

    NameResolver.Args nameResolverArgs = NameResolver.Args.newBuilder()
        .setDefaultPort(8080)
        .setProxyDetector((address) -> null)
        .setSynchronizationContext(syncContext)
        .setServiceConfigParser(mock(NameResolver.ServiceConfigParser.class))
        .setChannelLogger(mock(ChannelLogger.class))
        .setScheduledExecutorService(fakeClock.getScheduledExecutorService())
        .setNameResolverRegistry(new NameResolverRegistry())
        .build();

    xdsDepManager = new XdsDependencyManager(
        xdsClient,
        syncContext,
        SERVER_NAME,
        SERVER_NAME,
        nameResolverArgs);

    controlPlaneService.setXdsConfig(ADS_TYPE_URL_LDS, ImmutableMap.of(
        SERVER_NAME, ControlPlaneRule.buildClientListener(SERVER_NAME, "my-route")));
    controlPlaneService.setXdsConfig(ADS_TYPE_URL_RDS, ImmutableMap.of(
        "my-route", XdsTestUtils.buildRouteConfiguration(SERVER_NAME, "my-route", CLUSTER)));
    controlPlaneService.setXdsConfig(ADS_TYPE_URL_EDS, ImmutableMap.of(
        EDS_SERVICE_NAME, ControlPlaneRule.buildClusterLoadAssignment(
            "127.0.0.1", "", 1234, EDS_SERVICE_NAME)));
  }

  @After
  public void tearDown() {
    if (loadBalancer != null) {
      shutdownLoadBalancer();
    }
    assertThat(childBalancers).isEmpty();

    if (xdsDepManager != null) {
      xdsDepManager.shutdown();
    }
    xdsClient.shutdown();
  }

  private void shutdownLoadBalancer() {
    LoadBalancer lb = this.loadBalancer;
    this.loadBalancer = null; // Must avoid calling acceptResolvedAddresses after shutdown
    lb.shutdown();
  }

  @Test
  public void discoverTopLevelEdsCluster() {
    Cluster cluster = Cluster.newBuilder()
        .setName(CLUSTER)
        .setType(Cluster.DiscoveryType.EDS)
        .setEdsClusterConfig(Cluster.EdsClusterConfig.newBuilder()
          .setServiceName(EDS_SERVICE_NAME)
          .setEdsConfig(ConfigSource.newBuilder()
            .setAds(AggregatedConfigSource.newBuilder())))
        .setLbPolicy(Cluster.LbPolicy.ROUND_ROBIN)
        .setLrsServer(ConfigSource.newBuilder()
          .setSelf(SelfConfigSource.getDefaultInstance()))
        .setCircuitBreakers(CircuitBreakers.newBuilder()
            .addThresholds(CircuitBreakers.Thresholds.newBuilder()
              .setPriority(RoutingPriority.DEFAULT)
              .setMaxRequests(UInt32Value.newBuilder().setValue(100))))
        .setTransportSocket(TransportSocket.newBuilder()
            .setName("envoy.transport_sockets.tls")
            .setTypedConfig(Any.pack(UpstreamTlsContext.newBuilder()
                .setCommonTlsContext(upstreamTlsContext.getCommonTlsContext())
                .build())))
        .setOutlierDetection(OutlierDetection.getDefaultInstance())
        .build();
    controlPlaneService.setXdsConfig(ADS_TYPE_URL_CDS, ImmutableMap.of(CLUSTER, cluster));
    startXdsDepManager();

    verify(helper, never()).updateBalancingState(eq(ConnectivityState.TRANSIENT_FAILURE), any());
    assertThat(childBalancers).hasSize(1);
    FakeLoadBalancer childBalancer = Iterables.getOnlyElement(childBalancers);
    assertThat(childBalancer.name).isEqualTo(CLUSTER_RESOLVER_POLICY_NAME);
    ClusterResolverConfig childLbConfig = (ClusterResolverConfig) childBalancer.config;
    assertThat(childLbConfig.discoveryMechanism).isEqualTo(
        DiscoveryMechanism.forEds(
          CLUSTER, EDS_SERVICE_NAME, lrsServerInfo, 100L, upstreamTlsContext,
          Collections.emptyMap(), io.grpc.xds.EnvoyServerProtoData.OutlierDetection.create(
              null, null, null, null, SuccessRateEjection.create(null, null, null, null),
              FailurePercentageEjection.create(null, null, null, null)), null));
    assertThat(
        GracefulSwitchLoadBalancerAccessor.getChildProvider(childLbConfig.lbConfig).getPolicyName())
        .isEqualTo("wrr_locality_experimental");
  }

  @Test
  public void discoverTopLevelLogicalDnsCluster() {
    Cluster cluster = Cluster.newBuilder()
        .setName(CLUSTER)
        .setType(Cluster.DiscoveryType.LOGICAL_DNS)
        .setLoadAssignment(ClusterLoadAssignment.newBuilder()
          .addEndpoints(LocalityLbEndpoints.newBuilder()
            .addLbEndpoints(LbEndpoint.newBuilder()
              .setEndpoint(Endpoint.newBuilder()
                .setAddress(Address.newBuilder()
                  .setSocketAddress(SocketAddress.newBuilder()
                    .setAddress("dns.example.com")
                    .setPortValue(1111)))))))
        .setEdsClusterConfig(Cluster.EdsClusterConfig.newBuilder()
          .setServiceName(EDS_SERVICE_NAME)
          .setEdsConfig(ConfigSource.newBuilder()
            .setAds(AggregatedConfigSource.newBuilder())))
        .setLbPolicy(Cluster.LbPolicy.LEAST_REQUEST)
        .setLrsServer(ConfigSource.newBuilder()
          .setSelf(SelfConfigSource.getDefaultInstance()))
        .setCircuitBreakers(CircuitBreakers.newBuilder()
            .addThresholds(CircuitBreakers.Thresholds.newBuilder()
              .setPriority(RoutingPriority.DEFAULT)
              .setMaxRequests(UInt32Value.newBuilder().setValue(100))))
        .setTransportSocket(TransportSocket.newBuilder()
            .setName("envoy.transport_sockets.tls")
            .setTypedConfig(Any.pack(UpstreamTlsContext.newBuilder()
                .setCommonTlsContext(upstreamTlsContext.getCommonTlsContext())
                .build())))
        .build();
    controlPlaneService.setXdsConfig(ADS_TYPE_URL_CDS, ImmutableMap.of(CLUSTER, cluster));
    startXdsDepManager();

    verify(helper, never()).updateBalancingState(eq(ConnectivityState.TRANSIENT_FAILURE), any());
    assertThat(childBalancers).hasSize(1);
    FakeLoadBalancer childBalancer = Iterables.getOnlyElement(childBalancers);
    assertThat(childBalancer.name).isEqualTo(CLUSTER_RESOLVER_POLICY_NAME);
    ClusterResolverConfig childLbConfig = (ClusterResolverConfig) childBalancer.config;
    assertThat(childLbConfig.discoveryMechanism).isEqualTo(
        DiscoveryMechanism.forLogicalDns(
          CLUSTER, "dns.example.com:1111", lrsServerInfo, 100L, upstreamTlsContext,
          Collections.emptyMap(), null));
    assertThat(
        GracefulSwitchLoadBalancerAccessor.getChildProvider(childLbConfig.lbConfig).getPolicyName())
        .isEqualTo("wrr_locality_experimental");
  }

  @Test
  public void nonAggregateCluster_resourceNotExist_returnErrorPicker() {
    startXdsDepManager();
    verify(helper).updateBalancingState(
        eq(ConnectivityState.TRANSIENT_FAILURE), pickerCaptor.capture());
    Status unavailable = Status.UNAVAILABLE.withDescription(
        "CDS resource " + CLUSTER + " does not exist nodeID: " + NODE_ID);
    assertPickerStatus(pickerCaptor.getValue(), unavailable);
    assertThat(childBalancers).isEmpty();
  }

  @Test
  public void nonAggregateCluster_resourceUpdate() {
    Cluster cluster = EDS_CLUSTER.toBuilder()
        .setCircuitBreakers(CircuitBreakers.newBuilder()
            .addThresholds(CircuitBreakers.Thresholds.newBuilder()
              .setPriority(RoutingPriority.DEFAULT)
              .setMaxRequests(UInt32Value.newBuilder().setValue(100))))
        .build();
    controlPlaneService.setXdsConfig(ADS_TYPE_URL_CDS, ImmutableMap.of(CLUSTER, cluster));
    startXdsDepManager();

    verify(helper, never()).updateBalancingState(eq(ConnectivityState.TRANSIENT_FAILURE), any());
    assertThat(childBalancers).hasSize(1);
    FakeLoadBalancer childBalancer = Iterables.getOnlyElement(childBalancers);
    ClusterResolverConfig childLbConfig = (ClusterResolverConfig) childBalancer.config;
    assertThat(childLbConfig.discoveryMechanism).isEqualTo(
          DiscoveryMechanism.forEds(
            CLUSTER, EDS_SERVICE_NAME, null, 100L, null, Collections.emptyMap(), null, null));

    cluster = EDS_CLUSTER.toBuilder()
        .setCircuitBreakers(CircuitBreakers.newBuilder()
            .addThresholds(CircuitBreakers.Thresholds.newBuilder()
              .setPriority(RoutingPriority.DEFAULT)
              .setMaxRequests(UInt32Value.newBuilder().setValue(200))))
        .build();
    controlPlaneService.setXdsConfig(ADS_TYPE_URL_CDS, ImmutableMap.of(CLUSTER, cluster));
    verify(helper, never()).updateBalancingState(eq(ConnectivityState.TRANSIENT_FAILURE), any());
    assertThat(childBalancers).hasSize(1);
    childBalancer = Iterables.getOnlyElement(childBalancers);
    childLbConfig = (ClusterResolverConfig) childBalancer.config;
    assertThat(childLbConfig.discoveryMechanism).isEqualTo(
          DiscoveryMechanism.forEds(
            CLUSTER, EDS_SERVICE_NAME, null, 200L, null, Collections.emptyMap(), null, null));
  }

  @Test
  public void nonAggregateCluster_resourceRevoked() {
    controlPlaneService.setXdsConfig(ADS_TYPE_URL_CDS, ImmutableMap.of(CLUSTER, EDS_CLUSTER));
    startXdsDepManager();

    verify(helper, never()).updateBalancingState(eq(ConnectivityState.TRANSIENT_FAILURE), any());
    assertThat(childBalancers).hasSize(1);
    FakeLoadBalancer childBalancer = Iterables.getOnlyElement(childBalancers);
    ClusterResolverConfig childLbConfig = (ClusterResolverConfig) childBalancer.config;
    assertThat(childLbConfig.discoveryMechanism).isEqualTo(
          DiscoveryMechanism.forEds(
            CLUSTER, EDS_SERVICE_NAME, null, null, null, Collections.emptyMap(), null, null));

    controlPlaneService.setXdsConfig(ADS_TYPE_URL_CDS, ImmutableMap.of());

    assertThat(childBalancer.shutdown).isTrue();
    Status unavailable = Status.UNAVAILABLE.withDescription(
        "CDS resource " + CLUSTER + " does not exist nodeID: " + NODE_ID);
    verify(helper).updateBalancingState(
        eq(ConnectivityState.TRANSIENT_FAILURE), pickerCaptor.capture());
    assertPickerStatus(pickerCaptor.getValue(), unavailable);
    assertThat(childBalancer.shutdown).isTrue();
    assertThat(childBalancers).isEmpty();
  }

  @Test
  public void dynamicCluster() {
    String clusterName = "cluster2";
    Cluster cluster = EDS_CLUSTER.toBuilder()
        .setName(clusterName)
        .build();
    controlPlaneService.setXdsConfig(ADS_TYPE_URL_CDS, ImmutableMap.of(
        clusterName, cluster,
        CLUSTER, Cluster.newBuilder().setName(CLUSTER).build()));
    startXdsDepManager(new CdsConfig(clusterName, /*dynamic=*/ true));

    verify(helper, never()).updateBalancingState(eq(ConnectivityState.TRANSIENT_FAILURE), any());
    assertThat(childBalancers).hasSize(1);
    FakeLoadBalancer childBalancer = Iterables.getOnlyElement(childBalancers);
    ClusterResolverConfig childLbConfig = (ClusterResolverConfig) childBalancer.config;
    assertThat(childLbConfig.discoveryMechanism).isEqualTo(
          DiscoveryMechanism.forEds(
            clusterName, EDS_SERVICE_NAME, null, null, null, Collections.emptyMap(), null, null));

    assertThat(this.lastXdsConfig.getClusters()).containsKey(clusterName);
    shutdownLoadBalancer();
    assertThat(this.lastXdsConfig.getClusters()).doesNotContainKey(clusterName);
  }

  @Test
  public void discoverAggregateCluster_createsPriorityLbPolicy() {
    lbRegistry.register(new FakeLoadBalancerProvider(PRIORITY_POLICY_NAME));
    CdsLoadBalancerProvider cdsLoadBalancerProvider = new CdsLoadBalancerProvider(lbRegistry);
    lbRegistry.register(cdsLoadBalancerProvider);
    loadBalancer = (CdsLoadBalancer2) cdsLoadBalancerProvider.newLoadBalancer(helper);

    String cluster1 = "cluster-01.googleapis.com";
    String cluster2 = "cluster-02.googleapis.com";
    String cluster3 = "cluster-03.googleapis.com";
    String cluster4 = "cluster-04.googleapis.com";
    controlPlaneService.setXdsConfig(ADS_TYPE_URL_CDS, ImmutableMap.of(
        // CLUSTER (aggr.) -> [cluster1 (aggr.), cluster2 (logical DNS), cluster3 (EDS)]
        CLUSTER, Cluster.newBuilder()
          .setName(CLUSTER)
          .setClusterType(Cluster.CustomClusterType.newBuilder()
            .setName("envoy.clusters.aggregate")
            .setTypedConfig(Any.pack(ClusterConfig.newBuilder()
                .addClusters(cluster1)
                .addClusters(cluster2)
                .addClusters(cluster3)
                .build())))
          .setLbPolicy(Cluster.LbPolicy.RING_HASH)
          .build(),
        // cluster1 (aggr.) -> [cluster3 (EDS), cluster4 (EDS)]
        cluster1, Cluster.newBuilder()
          .setName(cluster1)
          .setClusterType(Cluster.CustomClusterType.newBuilder()
            .setName("envoy.clusters.aggregate")
            .setTypedConfig(Any.pack(ClusterConfig.newBuilder()
                .addClusters(cluster3)
                .addClusters(cluster4)
                .build())))
          .build(),
        cluster2, Cluster.newBuilder()
          .setName(cluster2)
          .setType(Cluster.DiscoveryType.LOGICAL_DNS)
          .setLoadAssignment(ClusterLoadAssignment.newBuilder()
            .addEndpoints(LocalityLbEndpoints.newBuilder()
              .addLbEndpoints(LbEndpoint.newBuilder()
                .setEndpoint(Endpoint.newBuilder()
                  .setAddress(Address.newBuilder()
                    .setSocketAddress(SocketAddress.newBuilder()
                      .setAddress("dns.example.com")
                      .setPortValue(1111)))))))
          .build(),
        cluster3, EDS_CLUSTER.toBuilder()
            .setName(cluster3)
            .setCircuitBreakers(CircuitBreakers.newBuilder()
                .addThresholds(CircuitBreakers.Thresholds.newBuilder()
                  .setPriority(RoutingPriority.DEFAULT)
                  .setMaxRequests(UInt32Value.newBuilder().setValue(100))))
            .build(),
        cluster4, EDS_CLUSTER.toBuilder().setName(cluster4).build()));
    startXdsDepManager();

    verify(helper, never()).updateBalancingState(eq(ConnectivityState.TRANSIENT_FAILURE), any());
    assertThat(childBalancers).hasSize(1);
    FakeLoadBalancer childBalancer = Iterables.getOnlyElement(childBalancers);
    assertThat(childBalancer.name).isEqualTo(PRIORITY_POLICY_NAME);
    PriorityLoadBalancerProvider.PriorityLbConfig childLbConfig =
            (PriorityLoadBalancerProvider.PriorityLbConfig) childBalancer.config;
    assertThat(childLbConfig.priorities).hasSize(3);
    assertThat(childLbConfig.priorities.get(0)).isEqualTo(cluster3);
    assertThat(childLbConfig.priorities.get(1)).isEqualTo(cluster4);
    assertThat(childLbConfig.priorities.get(2)).isEqualTo(cluster2);
    assertThat(childLbConfig.childConfigs).hasSize(3);
    PriorityLoadBalancerProvider.PriorityLbConfig.PriorityChildConfig childConfig3 =
            childLbConfig.childConfigs.get(cluster3);
    assertThat(
        GracefulSwitchLoadBalancerAccessor.getChildProvider(childConfig3.childConfig)
            .getPolicyName())
        .isEqualTo("cds_experimental");
    PriorityLoadBalancerProvider.PriorityLbConfig.PriorityChildConfig childConfig4 =
        childLbConfig.childConfigs.get(cluster4);
    assertThat(
        GracefulSwitchLoadBalancerAccessor.getChildProvider(childConfig4.childConfig)
            .getPolicyName())
        .isEqualTo("cds_experimental");
    PriorityLoadBalancerProvider.PriorityLbConfig.PriorityChildConfig childConfig2 =
            childLbConfig.childConfigs.get(cluster2);
    assertThat(
        GracefulSwitchLoadBalancerAccessor.getChildProvider(childConfig2.childConfig)
            .getPolicyName())
        .isEqualTo("cds_experimental");
  }

  @Test
  // Both priorities will get tried using real priority LB policy.
  public void discoverAggregateCluster_testChildCdsLbPolicyParsing() {
    lbRegistry.register(new PriorityLoadBalancerProvider());
    CdsLoadBalancerProvider cdsLoadBalancerProvider = new CdsLoadBalancerProvider(lbRegistry);
    lbRegistry.register(cdsLoadBalancerProvider);
    loadBalancer = (CdsLoadBalancer2) cdsLoadBalancerProvider.newLoadBalancer(helper);

    String cluster1 = "cluster-01.googleapis.com";
    String cluster2 = "cluster-02.googleapis.com";
    controlPlaneService.setXdsConfig(ADS_TYPE_URL_CDS, ImmutableMap.of(
        // CLUSTER (aggr.) -> [cluster1 (EDS), cluster2 (EDS)]
        CLUSTER, Cluster.newBuilder()
            .setName(CLUSTER)
            .setClusterType(Cluster.CustomClusterType.newBuilder()
                .setName("envoy.clusters.aggregate")
                .setTypedConfig(Any.pack(ClusterConfig.newBuilder()
                    .addClusters(cluster1)
                    .addClusters(cluster2)
                    .build())))
            .build(),
        cluster1, EDS_CLUSTER.toBuilder().setName(cluster1).build(),
        cluster2, EDS_CLUSTER.toBuilder().setName(cluster2).build()));
    startXdsDepManager();

    verify(helper, never()).updateBalancingState(eq(ConnectivityState.TRANSIENT_FAILURE), any());
    assertThat(childBalancers).hasSize(2);
    ClusterResolverConfig cluster1ResolverConfig =
        (ClusterResolverConfig) childBalancers.get(0).config;
    assertThat(cluster1ResolverConfig.discoveryMechanism.cluster)
        .isEqualTo("cluster-01.googleapis.com");
    assertThat(cluster1ResolverConfig.discoveryMechanism.type)
        .isEqualTo(DiscoveryMechanism.Type.EDS);
    assertThat(cluster1ResolverConfig.discoveryMechanism.edsServiceName)
        .isEqualTo("backend-service-1.googleapis.com");
    ClusterResolverConfig cluster2ResolverConfig =
        (ClusterResolverConfig) childBalancers.get(1).config;
    assertThat(cluster2ResolverConfig.discoveryMechanism.cluster)
        .isEqualTo("cluster-02.googleapis.com");
    assertThat(cluster2ResolverConfig.discoveryMechanism.type)
        .isEqualTo(DiscoveryMechanism.Type.EDS);
    assertThat(cluster2ResolverConfig.discoveryMechanism.edsServiceName)
        .isEqualTo("backend-service-1.googleapis.com");
  }

  @Test
  public void aggregateCluster_noChildren() {
    controlPlaneService.setXdsConfig(ADS_TYPE_URL_CDS, ImmutableMap.of(
        // CLUSTER (aggr.) -> []
        CLUSTER, Cluster.newBuilder()
          .setName(CLUSTER)
          .setClusterType(Cluster.CustomClusterType.newBuilder()
            .setName("envoy.clusters.aggregate")
            .setTypedConfig(Any.pack(ClusterConfig.newBuilder()
                .build())))
          .build()));
    startXdsDepManager();

    verify(helper)
        .updateBalancingState(eq(ConnectivityState.TRANSIENT_FAILURE), pickerCaptor.capture());
    PickResult result = pickerCaptor.getValue().pickSubchannel(mock(PickSubchannelArgs.class));
    Status actualStatus = result.getStatus();
    assertThat(actualStatus.getCode()).isEqualTo(Status.Code.UNAVAILABLE);
    assertThat(actualStatus.getDescription())
        .contains("aggregate ClusterConfig.clusters must not be empty");
    assertThat(childBalancers).isEmpty();
  }

  @Test
  public void aggregateCluster_noNonAggregateClusterExits_returnErrorPicker() {
    lbRegistry.register(new PriorityLoadBalancerProvider());
    CdsLoadBalancerProvider cdsLoadBalancerProvider = new CdsLoadBalancerProvider(lbRegistry);
    lbRegistry.register(cdsLoadBalancerProvider);
    loadBalancer = (CdsLoadBalancer2) cdsLoadBalancerProvider.newLoadBalancer(helper);

    String cluster1 = "cluster-01.googleapis.com";
    controlPlaneService.setXdsConfig(ADS_TYPE_URL_CDS, ImmutableMap.of(
        // CLUSTER (aggr.) -> [cluster1 (missing)]
        CLUSTER, Cluster.newBuilder()
          .setName(CLUSTER)
          .setClusterType(Cluster.CustomClusterType.newBuilder()
            .setName("envoy.clusters.aggregate")
            .setTypedConfig(Any.pack(ClusterConfig.newBuilder()
                .addClusters(cluster1)
                .build())))
          .setLbPolicy(Cluster.LbPolicy.RING_HASH)
          .build()));
    startXdsDepManager();

    verify(helper).updateBalancingState(
        eq(ConnectivityState.TRANSIENT_FAILURE), pickerCaptor.capture());
    Status status = Status.UNAVAILABLE.withDescription(
        "CDS resource " + cluster1 + " does not exist nodeID: " + NODE_ID);
    assertPickerStatus(pickerCaptor.getValue(), status);
    assertThat(childBalancers).isEmpty();
  }

  @Test
  public void handleNameResolutionErrorFromUpstream_beforeChildLbCreated_failingPicker() {
    Status status = Status.UNAVAILABLE.withDescription("unreachable");
    loadBalancer.handleNameResolutionError(status);
    verify(helper).updateBalancingState(
        eq(ConnectivityState.TRANSIENT_FAILURE), pickerCaptor.capture());
    assertPickerStatus(pickerCaptor.getValue(), status);
  }

  @Test
  public void handleNameResolutionErrorFromUpstream_afterChildLbCreated_fallThrough() {
    Cluster cluster = Cluster.newBuilder()
        .setName(CLUSTER)
        .setType(Cluster.DiscoveryType.EDS)
        .setEdsClusterConfig(Cluster.EdsClusterConfig.newBuilder()
          .setServiceName(EDS_SERVICE_NAME)
          .setEdsConfig(ConfigSource.newBuilder()
            .setAds(AggregatedConfigSource.newBuilder())))
        .build();
    controlPlaneService.setXdsConfig(ADS_TYPE_URL_CDS, ImmutableMap.of(CLUSTER, cluster));
    startXdsDepManager();
    verify(helper, never()).updateBalancingState(eq(ConnectivityState.TRANSIENT_FAILURE), any());
    FakeLoadBalancer childBalancer = Iterables.getOnlyElement(childBalancers);
    assertThat(childBalancer.shutdown).isFalse();

    loadBalancer.handleNameResolutionError(Status.UNAVAILABLE.withDescription("unreachable"));
    assertThat(childBalancer.upstreamError.getCode()).isEqualTo(Code.UNAVAILABLE);
    assertThat(childBalancer.upstreamError.getDescription()).isEqualTo("unreachable");
    verify(helper).updateBalancingState(
        eq(ConnectivityState.CONNECTING), any(SubchannelPicker.class));
  }

  @Test
  public void unknownLbProvider() {
    Cluster cluster = Cluster.newBuilder()
        .setName(CLUSTER)
        .setType(Cluster.DiscoveryType.EDS)
        .setEdsClusterConfig(Cluster.EdsClusterConfig.newBuilder()
          .setServiceName(EDS_SERVICE_NAME)
          .setEdsConfig(ConfigSource.newBuilder()
            .setAds(AggregatedConfigSource.newBuilder())))
        .setLoadBalancingPolicy(LoadBalancingPolicy.newBuilder()
            .addPolicies(Policy.newBuilder()
              .setTypedExtensionConfig(TypedExtensionConfig.newBuilder()
                .setTypedConfig(Any.pack(TypedStruct.newBuilder()
                    .setTypeUrl("type.googleapis.com/unknownLb")
                    .setValue(Struct.getDefaultInstance())
                    .build())))))
        .build();
    controlPlaneService.setXdsConfig(ADS_TYPE_URL_CDS, ImmutableMap.of(CLUSTER, cluster));
    startXdsDepManager();
    verify(helper).updateBalancingState(
        eq(ConnectivityState.TRANSIENT_FAILURE), pickerCaptor.capture());
    PickResult result = pickerCaptor.getValue().pickSubchannel(mock(PickSubchannelArgs.class));
    Status actualStatus = result.getStatus();
    assertThat(actualStatus.getCode()).isEqualTo(Status.Code.UNAVAILABLE);
    assertThat(actualStatus.getDescription()).contains("Invalid LoadBalancingPolicy");
  }

  @Test
  public void invalidLbConfig() {
    Cluster cluster = Cluster.newBuilder()
        .setName(CLUSTER)
        .setType(Cluster.DiscoveryType.EDS)
        .setEdsClusterConfig(Cluster.EdsClusterConfig.newBuilder()
          .setServiceName(EDS_SERVICE_NAME)
          .setEdsConfig(ConfigSource.newBuilder()
            .setAds(AggregatedConfigSource.newBuilder())))
        .setLoadBalancingPolicy(LoadBalancingPolicy.newBuilder()
            .addPolicies(Policy.newBuilder()
              .setTypedExtensionConfig(TypedExtensionConfig.newBuilder()
                .setTypedConfig(Any.pack(TypedStruct.newBuilder()
                    .setTypeUrl("type.googleapis.com/ring_hash_experimental")
                    .setValue(Struct.newBuilder()
                      .putFields("minRingSize", Value.newBuilder().setNumberValue(-1).build()))
                    .build())))))
        .build();
    controlPlaneService.setXdsConfig(ADS_TYPE_URL_CDS, ImmutableMap.of(CLUSTER, cluster));
    startXdsDepManager();
    verify(helper).updateBalancingState(
        eq(ConnectivityState.TRANSIENT_FAILURE), pickerCaptor.capture());
    PickResult result = pickerCaptor.getValue().pickSubchannel(mock(PickSubchannelArgs.class));
    Status actualStatus = result.getStatus();
    assertThat(actualStatus.getCode()).isEqualTo(Status.Code.UNAVAILABLE);
    assertThat(actualStatus.getDescription()).contains("Invalid 'minRingSize'");
  }

  private void startXdsDepManager() {
    startXdsDepManager(new CdsConfig(CLUSTER));
  }

  private void startXdsDepManager(final CdsConfig cdsConfig) {
    xdsDepManager.start(
        xdsConfig -> {
          if (!xdsConfig.hasValue()) {
            throw new AssertionError("" + xdsConfig.getStatus());
          }
          this.lastXdsConfig = xdsConfig.getValue();
          if (loadBalancer == null) {
            return;
          }
          loadBalancer.acceptResolvedAddresses(ResolvedAddresses.newBuilder()
              .setAddresses(Collections.emptyList())
              .setAttributes(Attributes.newBuilder()
                .set(XdsAttributes.XDS_CONFIG, xdsConfig.getValue())
                .set(XdsAttributes.XDS_CLUSTER_SUBSCRIPT_REGISTRY, xdsDepManager)
                .build())
              .setLoadBalancingPolicyConfig(cdsConfig)
              .build());
        });
    // trigger does not exist timer, so broken config is more obvious
    fakeClock.forwardTime(10, TimeUnit.MINUTES);
  }

  private static void assertPickerStatus(SubchannelPicker picker, Status expectedStatus)  {
    PickResult result = picker.pickSubchannel(mock(PickSubchannelArgs.class));
    Status actualStatus = result.getStatus();
    assertThat(actualStatus.getCode()).isEqualTo(expectedStatus.getCode());
    assertThat(actualStatus.getDescription()).isEqualTo(expectedStatus.getDescription());
  }

  private final class FakeLoadBalancerProvider extends LoadBalancerProvider {
    private final String policyName;
    private final LoadBalancerProvider configParsingDelegate;

    FakeLoadBalancerProvider(String policyName) {
      this(policyName, null);
    }

    FakeLoadBalancerProvider(String policyName, LoadBalancerProvider configParsingDelegate) {
      this.policyName = policyName;
      this.configParsingDelegate = configParsingDelegate;
    }

    @Override
    public LoadBalancer newLoadBalancer(Helper helper) {
      FakeLoadBalancer balancer = new FakeLoadBalancer(policyName);
      childBalancers.add(balancer);
      return balancer;
    }

    @Override
    public boolean isAvailable() {
      return true;
    }

    @Override
    public int getPriority() {
      return 0;  // doesn't matter
    }

    @Override
    public String getPolicyName() {
      return policyName;
    }

    @Override
    public NameResolver.ConfigOrError parseLoadBalancingPolicyConfig(
        Map<String, ?> rawLoadBalancingPolicyConfig) {
      if (configParsingDelegate != null) {
        return configParsingDelegate.parseLoadBalancingPolicyConfig(rawLoadBalancingPolicyConfig);
      }
      return super.parseLoadBalancingPolicyConfig(rawLoadBalancingPolicyConfig);
    }
  }

  private final class FakeLoadBalancer extends LoadBalancer {
    private final String name;
    private Object config;
    private Status upstreamError;
    private boolean shutdown;

    FakeLoadBalancer(String name) {
      this.name = name;
    }

    @Override
    public Status acceptResolvedAddresses(ResolvedAddresses resolvedAddresses) {
      config = resolvedAddresses.getLoadBalancingPolicyConfig();
      return Status.OK;
    }

    @Override
    public void handleNameResolutionError(Status error) {
      upstreamError = error;
    }

    @Override
    public void shutdown() {
      shutdown = true;
      childBalancers.remove(this);
    }
  }
}
