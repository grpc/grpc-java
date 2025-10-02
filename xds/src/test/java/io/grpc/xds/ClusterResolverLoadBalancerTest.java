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
import static io.grpc.xds.XdsLbPolicies.CLUSTER_IMPL_POLICY_NAME;
import static io.grpc.xds.XdsLbPolicies.PRIORITY_POLICY_NAME;
import static io.grpc.xds.XdsLbPolicies.WEIGHTED_TARGET_POLICY_NAME;
import static io.grpc.xds.XdsLbPolicies.WRR_LOCALITY_POLICY_NAME;
import static io.grpc.xds.XdsTestControlPlaneService.ADS_TYPE_URL_CDS;
import static io.grpc.xds.XdsTestControlPlaneService.ADS_TYPE_URL_EDS;
import static io.grpc.xds.XdsTestControlPlaneService.ADS_TYPE_URL_LDS;
import static io.grpc.xds.XdsTestControlPlaneService.ADS_TYPE_URL_RDS;
import static java.util.stream.Collectors.toList;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.testing.EqualsTester;
import com.google.protobuf.Any;
import com.google.protobuf.Duration;
import com.google.protobuf.UInt32Value;
import com.google.protobuf.UInt64Value;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.cluster.v3.OutlierDetection;
import io.envoyproxy.envoy.config.core.v3.Address;
import io.envoyproxy.envoy.config.core.v3.AggregatedConfigSource;
import io.envoyproxy.envoy.config.core.v3.ConfigSource;
import io.envoyproxy.envoy.config.core.v3.HealthStatus;
import io.envoyproxy.envoy.config.core.v3.Locality;
import io.envoyproxy.envoy.config.core.v3.Metadata;
import io.envoyproxy.envoy.config.core.v3.SocketAddress;
import io.envoyproxy.envoy.config.core.v3.TransportSocket;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.endpoint.v3.Endpoint;
import io.envoyproxy.envoy.config.endpoint.v3.LbEndpoint;
import io.envoyproxy.envoy.config.endpoint.v3.LocalityLbEndpoints;
import io.envoyproxy.envoy.extensions.transport_sockets.http_11_proxy.v3.Http11ProxyUpstreamTransport;
import io.grpc.Attributes;
import io.grpc.ChannelLogger;
import io.grpc.ConnectivityState;
import io.grpc.EquivalentAddressGroup;
import io.grpc.HttpConnectProxiedSocketAddress;
import io.grpc.InsecureChannelCredentials;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancer.PickResult;
import io.grpc.LoadBalancer.PickSubchannelArgs;
import io.grpc.LoadBalancer.ResolvedAddresses;
import io.grpc.LoadBalancer.Subchannel;
import io.grpc.LoadBalancer.SubchannelPicker;
import io.grpc.LoadBalancerProvider;
import io.grpc.LoadBalancerRegistry;
import io.grpc.NameResolver;
import io.grpc.NameResolver.ServiceConfigParser;
import io.grpc.NameResolverProvider;
import io.grpc.NameResolverRegistry;
import io.grpc.Status;
import io.grpc.StatusOr;
import io.grpc.SynchronizationContext;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.internal.FakeClock;
import io.grpc.internal.GrpcUtil;
import io.grpc.testing.GrpcCleanupRule;
import io.grpc.util.GracefulSwitchLoadBalancer;
import io.grpc.util.GracefulSwitchLoadBalancerAccessor;
import io.grpc.util.OutlierDetectionLoadBalancer.OutlierDetectionLoadBalancerConfig;
import io.grpc.util.OutlierDetectionLoadBalancerProvider;
import io.grpc.xds.CdsLoadBalancerProvider.CdsConfig;
import io.grpc.xds.ClusterImplLoadBalancerProvider.ClusterImplConfig;
import io.grpc.xds.ClusterResolverLoadBalancerProvider.ClusterResolverConfig;
import io.grpc.xds.ClusterResolverLoadBalancerProvider.ClusterResolverConfig.DiscoveryMechanism;
import io.grpc.xds.Endpoints.DropOverload;
import io.grpc.xds.EnvoyServerProtoData.FailurePercentageEjection;
import io.grpc.xds.EnvoyServerProtoData.SuccessRateEjection;
import io.grpc.xds.EnvoyServerProtoData.UpstreamTlsContext;
import io.grpc.xds.LeastRequestLoadBalancer.LeastRequestConfig;
import io.grpc.xds.PriorityLoadBalancerProvider.PriorityLbConfig;
import io.grpc.xds.PriorityLoadBalancerProvider.PriorityLbConfig.PriorityChildConfig;
import io.grpc.xds.RingHashLoadBalancer.RingHashConfig;
import io.grpc.xds.WrrLocalityLoadBalancer.WrrLocalityConfig;
import io.grpc.xds.client.BackendMetricPropagation;
import io.grpc.xds.client.Bootstrapper.ServerInfo;
import io.grpc.xds.client.XdsClient;
import io.grpc.xds.internal.XdsInternalAttributes;
import io.grpc.xds.internal.security.CommonTlsContextTestsUtil;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
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

/** Tests for {@link ClusterResolverLoadBalancer}. */
@RunWith(JUnit4.class)
public class ClusterResolverLoadBalancerTest {
  @Rule public final MockitoRule mocks = MockitoJUnit.rule();
  @Rule
  public final GrpcCleanupRule cleanupRule = new GrpcCleanupRule();

  private static final String SERVER_NAME = "example.com";
  private static final String CLUSTER = "cluster-foo.googleapis.com";
  private static final String EDS_SERVICE_NAME = "backend-service-foo.googleapis.com";
  private static final String DNS_HOST_NAME = "dns-service.googleapis.com";
  private final BackendMetricPropagation backendMetricPropagation =
      BackendMetricPropagation.fromMetricSpecs(Arrays.asList("cpu_utilization"));
  private static final Cluster EDS_CLUSTER = Cluster.newBuilder()
      .setName(CLUSTER)
      .setType(Cluster.DiscoveryType.EDS)
      .setEdsClusterConfig(Cluster.EdsClusterConfig.newBuilder()
          .setServiceName(EDS_SERVICE_NAME)
          .setEdsConfig(ConfigSource.newBuilder()
            .setAds(AggregatedConfigSource.newBuilder())))
      .build();
  private static final Cluster LOGICAL_DNS_CLUSTER = Cluster.newBuilder()
      .setName(CLUSTER)
      .setType(Cluster.DiscoveryType.LOGICAL_DNS)
      .setLoadAssignment(ClusterLoadAssignment.newBuilder()
          .addEndpoints(LocalityLbEndpoints.newBuilder()
            .addLbEndpoints(newSocketLbEndpoint(DNS_HOST_NAME, 9000))))
      .build();
  private static final Locality LOCALITY1 = Locality.newBuilder()
      .setRegion("test-region-1")
      .setZone("test-zone-1")
      .setSubZone("test-subzone-1")
      .build();
  private static final Locality LOCALITY2 = Locality.newBuilder()
      .setRegion("test-region-2")
      .setZone("test-zone-2")
      .setSubZone("test-subzone-2")
      .build();
  private static final Locality LOCALITY3 = Locality.newBuilder()
      .setRegion("test-region-3")
      .setZone("test-zone-3")
      .setSubZone("test-subzone-3")
      .build();

  private final SynchronizationContext syncContext = new SynchronizationContext(
      new Thread.UncaughtExceptionHandler() {
        @Override
        public void uncaughtException(Thread t, Throwable e) {
          throw new AssertionError(e);
        }
      });
  private final FakeClock fakeClock = new FakeClock();
  private final LoadBalancerRegistry lbRegistry = new LoadBalancerRegistry();
  private final NameResolverRegistry nsRegistry = new NameResolverRegistry();
  private final List<FakeLoadBalancer> childBalancers = new ArrayList<>();
  private final List<FakeNameResolver> resolvers = new ArrayList<>();
  private final XdsTestControlPlaneService controlPlaneService = new XdsTestControlPlaneService();
  private final XdsClient xdsClient = XdsTestUtils.createXdsClient(
      Arrays.asList("control-plane.example.com"),
      serverInfo -> new GrpcXdsTransportFactory.GrpcXdsTransport(
          InProcessChannelBuilder
            .forName(serverInfo.target())
            .directExecutor()
            .build()),
      fakeClock);


  private XdsDependencyManager xdsDepManager;
  @Mock
  private Helper helper;
  @Captor
  private ArgumentCaptor<SubchannelPicker> pickerCaptor;
  private CdsLoadBalancer2 loadBalancer;
  private boolean originalIsEnabledXdsHttpConnect;

  @Before
  public void setUp() throws Exception {
    lbRegistry.register(new ClusterResolverLoadBalancerProvider(lbRegistry));
    lbRegistry.register(new RingHashLoadBalancerProvider());
    lbRegistry.register(new WrrLocalityLoadBalancerProvider());
    lbRegistry.register(new FakeLoadBalancerProvider(PRIORITY_POLICY_NAME));
    lbRegistry.register(new FakeLoadBalancerProvider(CLUSTER_IMPL_POLICY_NAME));
    lbRegistry.register(new FakeLoadBalancerProvider(WEIGHTED_TARGET_POLICY_NAME));
    lbRegistry.register(
        new FakeLoadBalancerProvider("pick_first")); // needed by logical_dns
    lbRegistry.register(new OutlierDetectionLoadBalancerProvider());
    NameResolver.Args args = NameResolver.Args.newBuilder()
        .setDefaultPort(8080)
        .setProxyDetector(GrpcUtil.NOOP_PROXY_DETECTOR)
        .setSynchronizationContext(syncContext)
        .setServiceConfigParser(mock(ServiceConfigParser.class))
        .setChannelLogger(mock(ChannelLogger.class))
        .setScheduledExecutorService(fakeClock.getScheduledExecutorService())
        .setNameResolverRegistry(nsRegistry)
        .build();

    xdsDepManager = new XdsDependencyManager(
        xdsClient,
        syncContext,
        SERVER_NAME,
        SERVER_NAME,
        args);

    cleanupRule.register(InProcessServerBuilder
        .forName("control-plane.example.com")
        .addService(controlPlaneService)
        .directExecutor()
        .build()
        .start());

    controlPlaneService.setXdsConfig(ADS_TYPE_URL_LDS, ImmutableMap.of(
        SERVER_NAME, ControlPlaneRule.buildClientListener(SERVER_NAME, "my-route")));
    controlPlaneService.setXdsConfig(ADS_TYPE_URL_RDS, ImmutableMap.of(
        "my-route", XdsTestUtils.buildRouteConfiguration(SERVER_NAME, "my-route", CLUSTER)));
    controlPlaneService.setXdsConfig(ADS_TYPE_URL_CDS, ImmutableMap.of(
        CLUSTER, EDS_CLUSTER));
    controlPlaneService.setXdsConfig(ADS_TYPE_URL_EDS, ImmutableMap.of(
        EDS_SERVICE_NAME, ControlPlaneRule.buildClusterLoadAssignment(
            "127.0.0.1", "", 8080, EDS_SERVICE_NAME)));

    nsRegistry.register(new FakeNameResolverProvider());
    when(helper.getAuthority()).thenReturn("api.google.com");
    doAnswer((inv) -> {
      xdsDepManager.requestReresolution();
      return null;
    }).when(helper).refreshNameResolution();
    loadBalancer = new CdsLoadBalancer2(helper, lbRegistry);

    originalIsEnabledXdsHttpConnect = XdsClusterResource.isEnabledXdsHttpConnect;
  }

  @After
  public void tearDown() throws Exception {
    XdsClusterResource.isEnabledXdsHttpConnect = originalIsEnabledXdsHttpConnect;
    loadBalancer.shutdown();
    if (xdsDepManager != null) {
      xdsDepManager.shutdown();
    }
    assertThat(xdsClient.getSubscribedResourcesMetadataSnapshot().get()).isEmpty();
    xdsClient.shutdown();
    assertThat(childBalancers).isEmpty();
    assertThat(resolvers).isEmpty();
    assertThat(fakeClock.getPendingTasks()).isEmpty();
  }

  @Test
  public void edsClustersWithRingHashEndpointLbPolicy() throws Exception {
    Cluster cluster = EDS_CLUSTER.toBuilder()
        .setLbPolicy(Cluster.LbPolicy.RING_HASH)
        .setRingHashLbConfig(Cluster.RingHashLbConfig.newBuilder()
            .setMinimumRingSize(UInt64Value.of(10))
            .setMaximumRingSize(UInt64Value.of(100))
            .build())
        .build();
    // One priority with two localities of different weights.
    ClusterLoadAssignment clusterLoadAssignment = ClusterLoadAssignment.newBuilder()
        .setClusterName(EDS_SERVICE_NAME)
        .addEndpoints(LocalityLbEndpoints.newBuilder()
          .setLoadBalancingWeight(UInt32Value.of(10))
          .setLocality(LOCALITY1)
          .addLbEndpoints(newSocketLbEndpoint("127.0.0.1", 8080))
          .addLbEndpoints(newSocketLbEndpoint("127.0.0.2", 8080)))
        .addEndpoints(LocalityLbEndpoints.newBuilder()
          .setLoadBalancingWeight(UInt32Value.of(50))
          .setLocality(LOCALITY2)
          .addLbEndpoints(newSocketLbEndpoint("127.0.1.1", 8080)
            .setLoadBalancingWeight(UInt32Value.of(60))))
        .build();
    controlPlaneService.setXdsConfig(ADS_TYPE_URL_CDS, ImmutableMap.of(
        CLUSTER, cluster));
    controlPlaneService.setXdsConfig(ADS_TYPE_URL_EDS, ImmutableMap.of(
        EDS_SERVICE_NAME, clusterLoadAssignment));
    startXdsDepManager();

    verify(helper, never()).updateBalancingState(eq(ConnectivityState.TRANSIENT_FAILURE), any());
    assertThat(childBalancers).hasSize(1);
    FakeLoadBalancer childBalancer = Iterables.getOnlyElement(childBalancers);
    assertThat(childBalancer.addresses).hasSize(3);
    EquivalentAddressGroup addr1 = childBalancer.addresses.get(0);
    EquivalentAddressGroup addr2 = childBalancer.addresses.get(1);
    EquivalentAddressGroup addr3 = childBalancer.addresses.get(2);
    // Endpoints in LOCALITY1 have no endpoint-level weight specified, so all endpoints within
    // LOCALITY1 are equally weighted.
    assertThat(addr1.getAddresses())
        .isEqualTo(Arrays.asList(newInetSocketAddress("127.0.0.1", 8080)));
    assertThat(addr1.getAttributes().get(io.grpc.xds.XdsAttributes.ATTR_SERVER_WEIGHT))
        .isEqualTo(10);
    assertThat(addr2.getAddresses())
        .isEqualTo(Arrays.asList(newInetSocketAddress("127.0.0.2", 8080)));
    assertThat(addr2.getAttributes().get(io.grpc.xds.XdsAttributes.ATTR_SERVER_WEIGHT))
        .isEqualTo(10);
    assertThat(addr3.getAddresses())
        .isEqualTo(Arrays.asList(newInetSocketAddress("127.0.1.1", 8080)));
    assertThat(addr3.getAttributes().get(io.grpc.xds.XdsAttributes.ATTR_SERVER_WEIGHT))
        .isEqualTo(50 * 60);
    assertThat(childBalancer.name).isEqualTo(PRIORITY_POLICY_NAME);
    PriorityLbConfig priorityLbConfig = (PriorityLbConfig) childBalancer.config;
    assertThat(priorityLbConfig.priorities).containsExactly(CLUSTER + "[child1]");
    PriorityChildConfig priorityChildConfig =
        Iterables.getOnlyElement(priorityLbConfig.childConfigs.values());
    assertThat(priorityChildConfig.ignoreReresolution).isTrue();
    assertThat(GracefulSwitchLoadBalancerAccessor.getChildProvider(priorityChildConfig.childConfig)
          .getPolicyName())
        .isEqualTo(CLUSTER_IMPL_POLICY_NAME);
    ClusterImplConfig clusterImplConfig = (ClusterImplConfig)
        GracefulSwitchLoadBalancerAccessor.getChildConfig(priorityChildConfig.childConfig);
    assertClusterImplConfig(clusterImplConfig, CLUSTER, EDS_SERVICE_NAME, null, null,
        null, Collections.emptyList(), "ring_hash_experimental");
    // assertThat(clusterImplConfig.backendMetricPropagation).isEqualTo(backendMetricPropagation);
    RingHashConfig ringHashConfig = (RingHashConfig)
        GracefulSwitchLoadBalancerAccessor.getChildConfig(clusterImplConfig.childConfig);
    assertThat(ringHashConfig.minRingSize).isEqualTo(10L);
    assertThat(ringHashConfig.maxRingSize).isEqualTo(100L);
  }

  @Test
  public void edsClustersWithLeastRequestEndpointLbPolicy() {
    Cluster cluster = EDS_CLUSTER.toBuilder()
        .setLbPolicy(Cluster.LbPolicy.LEAST_REQUEST)
        .build();
    // Simple case with one priority and one locality
    ClusterLoadAssignment clusterLoadAssignment = ClusterLoadAssignment.newBuilder()
        .setClusterName(EDS_SERVICE_NAME)
        .addEndpoints(LocalityLbEndpoints.newBuilder()
          .setLoadBalancingWeight(UInt32Value.of(100))
          .setLocality(LOCALITY1)
          .addLbEndpoints(newSocketLbEndpoint("127.0.0.1", 8080)))
        .build();
    controlPlaneService.setXdsConfig(ADS_TYPE_URL_CDS, ImmutableMap.of(
        CLUSTER, cluster));
    controlPlaneService.setXdsConfig(ADS_TYPE_URL_EDS, ImmutableMap.of(
        EDS_SERVICE_NAME, clusterLoadAssignment));
    startXdsDepManager();

    verify(helper, never()).updateBalancingState(eq(ConnectivityState.TRANSIENT_FAILURE), any());
    assertThat(childBalancers).hasSize(1);
    FakeLoadBalancer childBalancer = Iterables.getOnlyElement(childBalancers);
    assertThat(childBalancer.addresses).hasSize(1);
    EquivalentAddressGroup addr = childBalancer.addresses.get(0);
    assertThat(addr.getAddresses())
        .isEqualTo(Arrays.asList(newInetSocketAddress("127.0.0.1", 8080)));
    assertThat(childBalancer.name).isEqualTo(PRIORITY_POLICY_NAME);
    PriorityLbConfig priorityLbConfig = (PriorityLbConfig) childBalancer.config;
    assertThat(priorityLbConfig.priorities).containsExactly(CLUSTER + "[child1]");
    PriorityChildConfig priorityChildConfig =
        Iterables.getOnlyElement(priorityLbConfig.childConfigs.values());
    assertThat(GracefulSwitchLoadBalancerAccessor.getChildProvider(priorityChildConfig.childConfig)
        .getPolicyName())
        .isEqualTo(CLUSTER_IMPL_POLICY_NAME);
    ClusterImplConfig clusterImplConfig = (ClusterImplConfig)
        GracefulSwitchLoadBalancerAccessor.getChildConfig(priorityChildConfig.childConfig);
    assertClusterImplConfig(clusterImplConfig, CLUSTER, EDS_SERVICE_NAME, null, null,
        null, Collections.emptyList(), WRR_LOCALITY_POLICY_NAME);
    WrrLocalityConfig wrrLocalityConfig = (WrrLocalityConfig)
        GracefulSwitchLoadBalancerAccessor.getChildConfig(clusterImplConfig.childConfig);
    LoadBalancerProvider childProvider =
        GracefulSwitchLoadBalancerAccessor.getChildProvider(wrrLocalityConfig.childConfig);
    assertThat(childProvider.getPolicyName()).isEqualTo("least_request_experimental");

    assertThat(
        childBalancer.addresses.get(0).getAttributes()
            .get(io.grpc.xds.XdsAttributes.ATTR_LOCALITY_WEIGHT)).isEqualTo(100);
  }

  @Test
  public void edsClustersEndpointHostname_addedToAddressAttribute() {
    // Simple case with one priority and one locality
    ClusterLoadAssignment clusterLoadAssignment = ClusterLoadAssignment.newBuilder()
        .setClusterName(EDS_SERVICE_NAME)
        .addEndpoints(LocalityLbEndpoints.newBuilder()
          .setLoadBalancingWeight(UInt32Value.of(100))
          .setLocality(LOCALITY1)
          .addLbEndpoints(LbEndpoint.newBuilder()
            .setEndpoint(Endpoint.newBuilder()
              .setHostname("hostname1")
              .setAddress(newAddress("127.0.0.1", 8000)))))
        .build();
    controlPlaneService.setXdsConfig(ADS_TYPE_URL_EDS, ImmutableMap.of(
        EDS_SERVICE_NAME, clusterLoadAssignment));
    startXdsDepManager();

    verify(helper, never()).updateBalancingState(eq(ConnectivityState.TRANSIENT_FAILURE), any());
    assertThat(childBalancers).hasSize(1);
    FakeLoadBalancer childBalancer = Iterables.getOnlyElement(childBalancers);

    assertThat(
        childBalancer.addresses.get(0).getAttributes()
            .get(XdsInternalAttributes.ATTR_ADDRESS_NAME)).isEqualTo("hostname1");
  }

  @Test
  public void endpointAddressRewritten_whenProxyMetadataIsInEndpointMetadata() {
    XdsClusterResource.isEnabledXdsHttpConnect = true;
    Cluster cluster = EDS_CLUSTER.toBuilder()
        .setTransportSocket(TransportSocket.newBuilder()
            .setName(
                "type.googleapis.com/" + Http11ProxyUpstreamTransport.getDescriptor().getFullName())
            .setTypedConfig(Any.pack(Http11ProxyUpstreamTransport.getDefaultInstance())))
        .build();
    // Proxy address in endpointMetadata, and no proxy in locality metadata
    ClusterLoadAssignment clusterLoadAssignment = ClusterLoadAssignment.newBuilder()
        .setClusterName(EDS_SERVICE_NAME)
        .addEndpoints(LocalityLbEndpoints.newBuilder()
          .setLoadBalancingWeight(UInt32Value.of(100))
          .setLocality(LOCALITY1)
          .addLbEndpoints(newSocketLbEndpoint("127.0.0.1", 8080)
            .setMetadata(Metadata.newBuilder()
              .putTypedFilterMetadata(
                  "envoy.http11_proxy_transport_socket.proxy_address",
                  Any.pack(newAddress("127.0.0.2", 8081).build()))))
          .addLbEndpoints(newSocketLbEndpoint("127.0.0.3", 8082)))
        .build();
    controlPlaneService.setXdsConfig(ADS_TYPE_URL_CDS, ImmutableMap.of(
        CLUSTER, cluster));
    controlPlaneService.setXdsConfig(ADS_TYPE_URL_EDS, ImmutableMap.of(
        EDS_SERVICE_NAME, clusterLoadAssignment));
    startXdsDepManager();

    verify(helper, never()).updateBalancingState(eq(ConnectivityState.TRANSIENT_FAILURE), any());
    assertThat(childBalancers).hasSize(1);
    FakeLoadBalancer childBalancer = Iterables.getOnlyElement(childBalancers);

    // Get the rewritten address
    java.net.SocketAddress rewrittenAddress =
        childBalancer.addresses.get(0).getAddresses().get(0);
    assertThat(rewrittenAddress).isInstanceOf(HttpConnectProxiedSocketAddress.class);
    HttpConnectProxiedSocketAddress proxiedSocket =
        (HttpConnectProxiedSocketAddress) rewrittenAddress;

    // Assert that the target address is the original address
    assertThat(proxiedSocket.getTargetAddress()).isEqualTo(newInetSocketAddress("127.0.0.1", 8080));

    // Assert that the proxy address is correctly set
    assertThat(proxiedSocket.getProxyAddress()).isEqualTo(newInetSocketAddress("127.0.0.2", 8081));

    // Check the non-rewritten address
    java.net.SocketAddress normalAddress = childBalancer.addresses.get(1).getAddresses().get(0);
    assertThat(normalAddress).isEqualTo(newInetSocketAddress("127.0.0.3", 8082));
  }

  @Test
  public void endpointAddressRewritten_whenProxyMetadataIsInLocalityMetadata() {
    XdsClusterResource.isEnabledXdsHttpConnect = true;
    Cluster cluster = EDS_CLUSTER.toBuilder()
        .setTransportSocket(TransportSocket.newBuilder()
            .setName(
                "type.googleapis.com/" + Http11ProxyUpstreamTransport.getDescriptor().getFullName())
            .setTypedConfig(Any.pack(Http11ProxyUpstreamTransport.getDefaultInstance())))
        .build();
    // No proxy address in endpointMetadata, and proxy in locality metadata
    ClusterLoadAssignment clusterLoadAssignment = ClusterLoadAssignment.newBuilder()
        .setClusterName(EDS_SERVICE_NAME)
        .addEndpoints(LocalityLbEndpoints.newBuilder()
          .setLoadBalancingWeight(UInt32Value.of(100))
          .setLocality(LOCALITY1)
          .addLbEndpoints(newSocketLbEndpoint("127.0.0.1", 8080))
          .setMetadata(Metadata.newBuilder()
            .putTypedFilterMetadata(
                "envoy.http11_proxy_transport_socket.proxy_address",
                Any.pack(newAddress("127.0.0.2", 8081).build()))))
        .build();
    controlPlaneService.setXdsConfig(ADS_TYPE_URL_CDS, ImmutableMap.of(
        CLUSTER, cluster));
    controlPlaneService.setXdsConfig(ADS_TYPE_URL_EDS, ImmutableMap.of(
        EDS_SERVICE_NAME, clusterLoadAssignment));
    startXdsDepManager();

    verify(helper, never()).updateBalancingState(eq(ConnectivityState.TRANSIENT_FAILURE), any());
    assertThat(childBalancers).hasSize(1);
    FakeLoadBalancer childBalancer = Iterables.getOnlyElement(childBalancers);

    // Get the rewritten address
    java.net.SocketAddress rewrittenAddress = childBalancer.addresses.get(0).getAddresses().get(0);

    // Assert that the address was rewritten
    assertThat(rewrittenAddress).isInstanceOf(HttpConnectProxiedSocketAddress.class);
    HttpConnectProxiedSocketAddress proxiedSocket =
        (HttpConnectProxiedSocketAddress) rewrittenAddress;

    // Assert that the target address is the original address
    assertThat(proxiedSocket.getTargetAddress()).isEqualTo(newInetSocketAddress("127.0.0.1", 8080));

    // Assert that the proxy address is correctly set from locality metadata
    assertThat(proxiedSocket.getProxyAddress()).isEqualTo(newInetSocketAddress("127.0.0.2", 8081));
  }

  @Test
  public void onlyEdsClusters_receivedEndpoints() {
    // Has two localities with different priorities
    ClusterLoadAssignment clusterLoadAssignment = ClusterLoadAssignment.newBuilder()
        .setClusterName(EDS_SERVICE_NAME)
        .addEndpoints(LocalityLbEndpoints.newBuilder()
          .setLoadBalancingWeight(UInt32Value.of(70))
          .setPriority(0)
          .setLocality(LOCALITY1)
          .addLbEndpoints(newSocketLbEndpoint("127.0.0.1", 8080))
          .addLbEndpoints(newSocketLbEndpoint("127.0.0.2", 8080)))
        .addEndpoints(LocalityLbEndpoints.newBuilder()
          .setLoadBalancingWeight(UInt32Value.of(30))
          .setPriority(1)
          .setLocality(LOCALITY2)
          .addLbEndpoints(newSocketLbEndpoint("127.0.0.3", 8080)))
        .build();
    controlPlaneService.setXdsConfig(ADS_TYPE_URL_EDS, ImmutableMap.of(
        EDS_SERVICE_NAME, clusterLoadAssignment));
    startXdsDepManager();

    String priority1 = CLUSTER + "[child1]";
    String priority2 = CLUSTER + "[child2]";

    verify(helper, never()).updateBalancingState(eq(ConnectivityState.TRANSIENT_FAILURE), any());
    assertThat(childBalancers).hasSize(1);
    FakeLoadBalancer childBalancer = Iterables.getOnlyElement(childBalancers);
    assertThat(childBalancer.name).isEqualTo(PRIORITY_POLICY_NAME);
    PriorityLbConfig priorityLbConfig = (PriorityLbConfig) childBalancer.config;
    assertThat(priorityLbConfig.priorities)
        .containsExactly(priority1, priority2).inOrder();

    PriorityChildConfig priorityChildConfig1 = priorityLbConfig.childConfigs.get(priority1);
    assertThat(priorityChildConfig1.ignoreReresolution).isTrue();
    assertThat(GracefulSwitchLoadBalancerAccessor.getChildProvider(priorityChildConfig1.childConfig)
        .getPolicyName())
        .isEqualTo(CLUSTER_IMPL_POLICY_NAME);
    ClusterImplConfig clusterImplConfig1 = (ClusterImplConfig)
        GracefulSwitchLoadBalancerAccessor.getChildConfig(priorityChildConfig1.childConfig);
    assertClusterImplConfig(clusterImplConfig1, CLUSTER, EDS_SERVICE_NAME, null, null,
        null, Collections.emptyList(), WRR_LOCALITY_POLICY_NAME);
    WrrLocalityConfig wrrLocalityConfig1 = (WrrLocalityConfig)
        GracefulSwitchLoadBalancerAccessor.getChildConfig(clusterImplConfig1.childConfig);
    LoadBalancerProvider childProvider1 =
        GracefulSwitchLoadBalancerAccessor.getChildProvider(wrrLocalityConfig1.childConfig);
    assertThat(childProvider1.getPolicyName()).isEqualTo("round_robin");

    PriorityChildConfig priorityChildConfig2 = priorityLbConfig.childConfigs.get(priority2);
    assertThat(priorityChildConfig2.ignoreReresolution).isTrue();
    assertThat(GracefulSwitchLoadBalancerAccessor.getChildProvider(priorityChildConfig2.childConfig)
        .getPolicyName())
        .isEqualTo(CLUSTER_IMPL_POLICY_NAME);
    ClusterImplConfig clusterImplConfig2 = (ClusterImplConfig)
        GracefulSwitchLoadBalancerAccessor.getChildConfig(priorityChildConfig2.childConfig);
    assertClusterImplConfig(clusterImplConfig2, CLUSTER, EDS_SERVICE_NAME, null, null,
        null, Collections.emptyList(), WRR_LOCALITY_POLICY_NAME);
    WrrLocalityConfig wrrLocalityConfig2 = (WrrLocalityConfig)
        GracefulSwitchLoadBalancerAccessor.getChildConfig(clusterImplConfig1.childConfig);
    LoadBalancerProvider childProvider2 =
        GracefulSwitchLoadBalancerAccessor.getChildProvider(wrrLocalityConfig2.childConfig);
    assertThat(childProvider2.getPolicyName()).isEqualTo("round_robin");

    WrrLocalityConfig wrrLocalityConfig3 = (WrrLocalityConfig)
        GracefulSwitchLoadBalancerAccessor.getChildConfig(clusterImplConfig1.childConfig);
    LoadBalancerProvider childProvider3 =
        GracefulSwitchLoadBalancerAccessor.getChildProvider(wrrLocalityConfig3.childConfig);
    assertThat(childProvider3.getPolicyName()).isEqualTo("round_robin");

    io.grpc.xds.client.Locality locality1 = io.grpc.xds.client.Locality.create(
        LOCALITY1.getRegion(), LOCALITY1.getZone(), LOCALITY1.getSubZone());
    io.grpc.xds.client.Locality locality2 = io.grpc.xds.client.Locality.create(
        LOCALITY2.getRegion(), LOCALITY2.getZone(), LOCALITY2.getSubZone());
    for (EquivalentAddressGroup eag : childBalancer.addresses) {
      io.grpc.xds.client.Locality locality =
          eag.getAttributes().get(io.grpc.xds.XdsAttributes.ATTR_LOCALITY);
      if (locality.equals(locality1)) {
        assertThat(eag.getAttributes().get(io.grpc.xds.XdsAttributes.ATTR_LOCALITY_WEIGHT))
            .isEqualTo(70);
      } else if (locality.equals(locality2)) {
        assertThat(eag.getAttributes().get(io.grpc.xds.XdsAttributes.ATTR_LOCALITY_WEIGHT))
            .isEqualTo(30);
      } else {
        throw new AssertionError("Unexpected locality region: " + locality.region());
      }
    }
  }

  @SuppressWarnings("unchecked")
  private void verifyEdsPriorityNames(List<String> want, List<LocalityLbEndpoints>... updates) {
    Iterator<ClusterLoadAssignment> edsUpdates = Arrays.asList(updates).stream()
        .map(update -> ClusterLoadAssignment.newBuilder()
            .setClusterName(EDS_SERVICE_NAME)
            .addAllEndpoints(update)
            .build())
        .iterator();
    controlPlaneService.setXdsConfig(ADS_TYPE_URL_EDS, ImmutableMap.of(
        EDS_SERVICE_NAME, edsUpdates.next()));
    startXdsDepManager();

    while (edsUpdates.hasNext()) {
      controlPlaneService.setXdsConfig(ADS_TYPE_URL_EDS, ImmutableMap.of(
          EDS_SERVICE_NAME, edsUpdates.next()));
    }
    verify(helper, never()).updateBalancingState(eq(ConnectivityState.TRANSIENT_FAILURE), any());
    assertThat(childBalancers).hasSize(1);
    FakeLoadBalancer childBalancer = Iterables.getOnlyElement(childBalancers);
    assertThat(childBalancer.name).isEqualTo(PRIORITY_POLICY_NAME);
    PriorityLbConfig priorityLbConfig = (PriorityLbConfig) childBalancer.config;
    assertThat(priorityLbConfig.priorities).isEqualTo(want);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void edsUpdatePriorityName_twoPriorities() {
    verifyEdsPriorityNames(Arrays.asList(CLUSTER + "[child1]", CLUSTER + "[child2]"),
        Arrays.asList(createEndpoints(LOCALITY1, 0), createEndpoints(LOCALITY2, 1)));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void edsUpdatePriorityName_addOnePriority() {
    verifyEdsPriorityNames(Arrays.asList(CLUSTER + "[child2]"),
        Arrays.asList(createEndpoints(LOCALITY1, 0)),
        Arrays.asList(createEndpoints(LOCALITY2, 0)));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void edsUpdatePriorityName_swapTwoPriorities() {
    verifyEdsPriorityNames(Arrays.asList(CLUSTER + "[child2]", CLUSTER + "[child1]",
            CLUSTER + "[child3]"),
        Arrays.asList(
            createEndpoints(LOCALITY1, 0),
            createEndpoints(LOCALITY2, 1),
            createEndpoints(LOCALITY3, 2)),
        Arrays.asList(
            createEndpoints(LOCALITY1, 1),
            createEndpoints(LOCALITY2, 0),
            createEndpoints(LOCALITY3, 2)));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void edsUpdatePriorityName_mergeTwoPriorities() {
    verifyEdsPriorityNames(Arrays.asList(CLUSTER + "[child3]", CLUSTER + "[child1]"),
        Arrays.asList(
            createEndpoints(LOCALITY1, 0),
            createEndpoints(LOCALITY3, 2),
            createEndpoints(LOCALITY2, 1)),
        Arrays.asList(
            createEndpoints(LOCALITY1, 1),
            createEndpoints(LOCALITY3, 0),
            createEndpoints(LOCALITY2, 0)));
  }

  private LocalityLbEndpoints createEndpoints(Locality locality, int priority) {
    return LocalityLbEndpoints.newBuilder()
        .setLoadBalancingWeight(UInt32Value.of(70))
        .setLocality(locality)
        .setPriority(priority)
        .addLbEndpoints(newSocketLbEndpoint("127.0." + priority + ".1", 8080))
        .build();
  }

  @Test
  public void onlyEdsClusters_resourceNeverExist_returnErrorPicker() {
    controlPlaneService.setXdsConfig(ADS_TYPE_URL_CDS, ImmutableMap.of());
    startXdsDepManager();

    verify(helper).updateBalancingState(
        eq(ConnectivityState.TRANSIENT_FAILURE), pickerCaptor.capture());
    assertPicker(
        pickerCaptor.getValue(),
        Status.UNAVAILABLE.withDescription(
            "CDS resource " + CLUSTER + " does not exist nodeID: node-id"),
        null);
  }

  @Test
  public void cdsMissing_handledDirectly() {
    ClusterLoadAssignment clusterLoadAssignment = ClusterLoadAssignment.newBuilder()
        .setClusterName(EDS_SERVICE_NAME)
        .addEndpoints(LocalityLbEndpoints.newBuilder()
          .setLoadBalancingWeight(UInt32Value.of(100))
          .setLocality(LOCALITY1)
          .addLbEndpoints(newSocketLbEndpoint("127.0.0.1", 8000)))
        .build();
    controlPlaneService.setXdsConfig(ADS_TYPE_URL_CDS, ImmutableMap.of());
    controlPlaneService.setXdsConfig(ADS_TYPE_URL_EDS, ImmutableMap.of(
        EDS_SERVICE_NAME, clusterLoadAssignment));

    startXdsDepManager();
    assertThat(childBalancers).hasSize(0);  // no child LB policy created
    verify(helper).updateBalancingState(
        eq(ConnectivityState.TRANSIENT_FAILURE), pickerCaptor.capture());
    Status expectedError = Status.UNAVAILABLE.withDescription(
        "CDS resource " + CLUSTER + " does not exist nodeID: node-id");
    assertPicker(pickerCaptor.getValue(), expectedError, null);
  }

  @Test
  public void cdsRevoked_handledDirectly() {
    ClusterLoadAssignment clusterLoadAssignment = ClusterLoadAssignment.newBuilder()
        .setClusterName(EDS_SERVICE_NAME)
        .addEndpoints(LocalityLbEndpoints.newBuilder()
          .setLoadBalancingWeight(UInt32Value.of(100))
          .setLocality(LOCALITY1)
          .addLbEndpoints(newSocketLbEndpoint("127.0.0.1", 8000)))
        .build();
    controlPlaneService.setXdsConfig(ADS_TYPE_URL_EDS, ImmutableMap.of(
        EDS_SERVICE_NAME, clusterLoadAssignment));

    startXdsDepManager();
    assertThat(childBalancers).hasSize(1);  // child LB policy created
    FakeLoadBalancer childBalancer = Iterables.getOnlyElement(childBalancers);
    assertThat(((PriorityLbConfig) childBalancer.config).priorities).hasSize(1);
    assertThat(childBalancer.addresses).hasSize(1);
    assertAddressesEqual(
        Arrays.asList(newInetSocketAddressEag("127.0.0.1", 8000)),
        childBalancer.addresses);

    controlPlaneService.setXdsConfig(ADS_TYPE_URL_CDS, ImmutableMap.of());
    verify(helper).updateBalancingState(
        eq(ConnectivityState.TRANSIENT_FAILURE), pickerCaptor.capture());
    Status expectedError = Status.UNAVAILABLE.withDescription(
        "CDS resource " + CLUSTER + " does not exist nodeID: node-id");
    assertPicker(pickerCaptor.getValue(), expectedError, null);
    assertThat(childBalancer.shutdown).isTrue();
  }

  @Test
  public void edsMissing_handledByChildPolicy() {
    controlPlaneService.setXdsConfig(ADS_TYPE_URL_EDS, ImmutableMap.of());

    startXdsDepManager();
    assertThat(childBalancers).hasSize(1);  // child LB policy created
    FakeLoadBalancer childBalancer = Iterables.getOnlyElement(childBalancers);
    assertThat(childBalancer.upstreamError).isNotNull();
    assertThat(childBalancer.upstreamError.getCode()).isEqualTo(Status.Code.UNAVAILABLE);
    assertThat(childBalancer.upstreamError.getDescription())
        .isEqualTo("EDS resource " + EDS_SERVICE_NAME + " does not exist nodeID: node-id");
    assertThat(childBalancer.shutdown).isFalse();
  }

  @Test
  public void logicalDnsLookupFailed_handledByChildPolicy() {
    controlPlaneService.setXdsConfig(ADS_TYPE_URL_CDS, ImmutableMap.of(
        CLUSTER, LOGICAL_DNS_CLUSTER));
    startXdsDepManager(new CdsConfig(CLUSTER), /* forwardTime= */ false);
    FakeNameResolver resolver = assertResolverCreated("/" + DNS_HOST_NAME + ":9000");
    assertThat(childBalancers).isEmpty();
    resolver.deliverError(Status.UNAVAILABLE.withDescription("OH NO! Who would have guessed?"));

    assertThat(childBalancers).hasSize(1);  // child LB policy created
    FakeLoadBalancer childBalancer = Iterables.getOnlyElement(childBalancers);
    assertThat(childBalancer.upstreamError).isNotNull();
    assertThat(childBalancer.upstreamError.getCode()).isEqualTo(Status.Code.UNAVAILABLE);
    assertThat(childBalancer.upstreamError.getDescription())
        .isEqualTo("OH NO! Who would have guessed?");
    assertThat(childBalancer.shutdown).isFalse();
  }

  @Test
  public void handleEdsResource_ignoreUnhealthyEndpoints() {
    ClusterLoadAssignment clusterLoadAssignment = ClusterLoadAssignment.newBuilder()
        .setClusterName(EDS_SERVICE_NAME)
        .addEndpoints(LocalityLbEndpoints.newBuilder()
          .setLoadBalancingWeight(UInt32Value.of(100))
          .setLocality(LOCALITY1)
          .addLbEndpoints(newSocketLbEndpoint("127.0.0.1", 8000)
            .setHealthStatus(HealthStatus.UNHEALTHY))
          .addLbEndpoints(newSocketLbEndpoint("127.0.0.2", 8000)))
        .build();
    controlPlaneService.setXdsConfig(ADS_TYPE_URL_EDS, ImmutableMap.of(
        EDS_SERVICE_NAME, clusterLoadAssignment));
    startXdsDepManager();

    verify(helper, never()).updateBalancingState(eq(ConnectivityState.TRANSIENT_FAILURE), any());
    FakeLoadBalancer childBalancer = Iterables.getOnlyElement(childBalancers);
    assertAddressesEqual(
        Arrays.asList(new EquivalentAddressGroup(newInetSocketAddress("127.0.0.2", 8000))),
        childBalancer.addresses);
  }

  @Test
  public void handleEdsResource_ignoreLocalitiesWithNoHealthyEndpoints() {
    ClusterLoadAssignment clusterLoadAssignment = ClusterLoadAssignment.newBuilder()
        .setClusterName(EDS_SERVICE_NAME)
        .addEndpoints(LocalityLbEndpoints.newBuilder()
          .setLoadBalancingWeight(UInt32Value.of(100))
          .setLocality(LOCALITY1)
          .addLbEndpoints(newSocketLbEndpoint("127.0.0.1", 8000)
            .setHealthStatus(HealthStatus.UNHEALTHY)))
        .addEndpoints(LocalityLbEndpoints.newBuilder()
          .setLoadBalancingWeight(UInt32Value.of(100))
          .setLocality(LOCALITY2)
          .addLbEndpoints(newSocketLbEndpoint("127.0.0.2", 8000)))
        .build();
    controlPlaneService.setXdsConfig(ADS_TYPE_URL_EDS, ImmutableMap.of(
        EDS_SERVICE_NAME, clusterLoadAssignment));
    startXdsDepManager();

    verify(helper, never()).updateBalancingState(eq(ConnectivityState.TRANSIENT_FAILURE), any());
    FakeLoadBalancer childBalancer = Iterables.getOnlyElement(childBalancers);
    io.grpc.xds.client.Locality locality2 = io.grpc.xds.client.Locality.create(
        LOCALITY2.getRegion(), LOCALITY2.getZone(), LOCALITY2.getSubZone());
    for (EquivalentAddressGroup eag : childBalancer.addresses) {
      assertThat(eag.getAttributes().get(io.grpc.xds.XdsAttributes.ATTR_LOCALITY))
          .isEqualTo(locality2);
    }
  }

  @Test
  public void handleEdsResource_ignorePrioritiesWithNoHealthyEndpoints() {
    ClusterLoadAssignment clusterLoadAssignment = ClusterLoadAssignment.newBuilder()
        .setClusterName(EDS_SERVICE_NAME)
        .addEndpoints(LocalityLbEndpoints.newBuilder()
          .setLoadBalancingWeight(UInt32Value.of(100))
          .setLocality(LOCALITY1)
          .setPriority(0)
          .addLbEndpoints(newSocketLbEndpoint("127.0.0.1", 8000)
            .setHealthStatus(HealthStatus.UNHEALTHY)))
        .addEndpoints(LocalityLbEndpoints.newBuilder()
          .setLoadBalancingWeight(UInt32Value.of(100))
          .setLocality(LOCALITY2)
          .setPriority(1)
          .addLbEndpoints(newSocketLbEndpoint("127.0.0.2", 8000)))
        .build();
    controlPlaneService.setXdsConfig(ADS_TYPE_URL_EDS, ImmutableMap.of(
        EDS_SERVICE_NAME, clusterLoadAssignment));
    startXdsDepManager();

    verify(helper, never()).updateBalancingState(eq(ConnectivityState.TRANSIENT_FAILURE), any());
    String priority2 = CLUSTER + "[child2]";
    FakeLoadBalancer childBalancer = Iterables.getOnlyElement(childBalancers);
    assertThat(((PriorityLbConfig) childBalancer.config).priorities).containsExactly(priority2);
  }

  @Test
  public void handleEdsResource_noHealthyEndpoint() {
    ClusterLoadAssignment clusterLoadAssignment = ClusterLoadAssignment.newBuilder()
        .setClusterName(EDS_SERVICE_NAME)
        .addEndpoints(LocalityLbEndpoints.newBuilder()
          .setLoadBalancingWeight(UInt32Value.of(100))
          .setLocality(LOCALITY1)
          .addLbEndpoints(newSocketLbEndpoint("127.0.0.1", 8000)
            .setHealthStatus(HealthStatus.UNHEALTHY)))
        .build();
    controlPlaneService.setXdsConfig(ADS_TYPE_URL_EDS, ImmutableMap.of(
        EDS_SERVICE_NAME, clusterLoadAssignment));
    startXdsDepManager();

    verify(helper, never()).updateBalancingState(eq(ConnectivityState.TRANSIENT_FAILURE), any());
    assertThat(childBalancers).hasSize(1);
    FakeLoadBalancer childBalancer = Iterables.getOnlyElement(childBalancers);
    assertThat(childBalancer.upstreamError).isNotNull();
    assertThat(childBalancer.upstreamError.getCode()).isEqualTo(Status.Code.UNAVAILABLE);
    assertThat(childBalancer.upstreamError.getDescription())
        .isEqualTo("No usable endpoint from cluster: " + CLUSTER);
  }

  @Test
  public void onlyLogicalDnsCluster_endpointsResolved() {
    controlPlaneService.setXdsConfig(ADS_TYPE_URL_CDS, ImmutableMap.of(
        CLUSTER, LOGICAL_DNS_CLUSTER));
    startXdsDepManager(new CdsConfig(CLUSTER), /* forwardTime= */ false);
    FakeNameResolver resolver = assertResolverCreated("/" + DNS_HOST_NAME + ":9000");
    assertThat(childBalancers).isEmpty();
    resolver.deliverEndpointAddresses(Arrays.asList(
        newInetSocketAddressEag("127.0.2.1", 9000), newInetSocketAddressEag("127.0.2.2", 9000)));
    fakeClock.forwardTime(10, TimeUnit.MINUTES);

    verify(helper, never()).updateBalancingState(eq(ConnectivityState.TRANSIENT_FAILURE), any());
    assertThat(childBalancers).hasSize(1);
    FakeLoadBalancer childBalancer = Iterables.getOnlyElement(childBalancers);
    assertThat(childBalancer.name).isEqualTo(PRIORITY_POLICY_NAME);
    PriorityLbConfig priorityLbConfig = (PriorityLbConfig) childBalancer.config;
    String priority = Iterables.getOnlyElement(priorityLbConfig.priorities);
    PriorityChildConfig priorityChildConfig = priorityLbConfig.childConfigs.get(priority);
    assertThat(priorityChildConfig.ignoreReresolution).isFalse();
    assertThat(GracefulSwitchLoadBalancerAccessor.getChildProvider(priorityChildConfig.childConfig)
        .getPolicyName())
        .isEqualTo(CLUSTER_IMPL_POLICY_NAME);
    ClusterImplConfig clusterImplConfig = (ClusterImplConfig)
        GracefulSwitchLoadBalancerAccessor.getChildConfig(priorityChildConfig.childConfig);
    assertClusterImplConfig(clusterImplConfig, CLUSTER, null, null, null, null,
        Collections.<DropOverload>emptyList(), "wrr_locality_experimental");
    // assertThat(clusterImplConfig.backendMetricPropagation).isEqualTo(backendMetricPropagation);
    assertAddressesEqual(
        Arrays.asList(new EquivalentAddressGroup(Arrays.asList(
            newInetSocketAddress("127.0.2.1", 9000), newInetSocketAddress("127.0.2.2", 9000)))),
        childBalancer.addresses);
    assertThat(childBalancer.addresses.get(0).getAttributes()
        .get(XdsInternalAttributes.ATTR_ADDRESS_NAME)).isEqualTo(DNS_HOST_NAME + ":9000");
  }

  @Test
  public void onlyLogicalDnsCluster_handleRefreshNameResolution() {
    controlPlaneService.setXdsConfig(ADS_TYPE_URL_CDS, ImmutableMap.of(
        CLUSTER, LOGICAL_DNS_CLUSTER));
    startXdsDepManager(new CdsConfig(CLUSTER), /* forwardTime= */ false);
    FakeNameResolver resolver = assertResolverCreated("/" + DNS_HOST_NAME + ":9000");
    assertThat(childBalancers).isEmpty();
    resolver.deliverEndpointAddresses(Arrays.asList(newInetSocketAddressEag("127.0.2.1", 9000)));
    fakeClock.forwardTime(10, TimeUnit.MINUTES);

    verify(helper, never()).updateBalancingState(eq(ConnectivityState.TRANSIENT_FAILURE), any());
    assertThat(resolver.refreshCount).isEqualTo(0);
    FakeLoadBalancer childBalancer = Iterables.getOnlyElement(childBalancers);
    childBalancer.helper.refreshNameResolution();
    assertThat(resolver.refreshCount).isEqualTo(1);
  }

  @Test
  public void outlierDetection_disabledConfig() {
    Cluster cluster = EDS_CLUSTER.toBuilder()
        .setOutlierDetection(OutlierDetection.newBuilder()
            .setEnforcingSuccessRate(UInt32Value.of(0))
            .setEnforcingFailurePercentage(UInt32Value.of(0)))
        .build();
    controlPlaneService.setXdsConfig(ADS_TYPE_URL_CDS, ImmutableMap.of(
        CLUSTER, cluster));
    startXdsDepManager();

    verify(helper, never()).updateBalancingState(eq(ConnectivityState.TRANSIENT_FAILURE), any());
    assertThat(childBalancers).hasSize(1);
    FakeLoadBalancer childBalancer = Iterables.getOnlyElement(childBalancers);
    assertThat(childBalancer.name).isEqualTo(PRIORITY_POLICY_NAME);
    PriorityLbConfig priorityLbConfig = (PriorityLbConfig) childBalancer.config;
    PriorityChildConfig priorityChildConfig =
        Iterables.getOnlyElement(priorityLbConfig.childConfigs.values());
    OutlierDetectionLoadBalancerConfig outlier = (OutlierDetectionLoadBalancerConfig)
        GracefulSwitchLoadBalancerAccessor.getChildConfig(priorityChildConfig.childConfig);
    assertThat(outlier.successRateEjection).isNull();
    assertThat(outlier.failurePercentageEjection).isNull();
  }

  @Test
  public void outlierDetection_fullConfig() {
    Cluster cluster = EDS_CLUSTER.toBuilder()
        .setLbPolicy(Cluster.LbPolicy.ROUND_ROBIN)
        .setOutlierDetection(OutlierDetection.newBuilder()
            .setInterval(Duration.newBuilder().setNanos(101))
            .setBaseEjectionTime(Duration.newBuilder().setNanos(102))
            .setMaxEjectionTime(Duration.newBuilder().setNanos(103))
            .setMaxEjectionPercent(UInt32Value.of(80))
            .setSuccessRateStdevFactor(UInt32Value.of(105))
            .setEnforcingSuccessRate(UInt32Value.of(81))
            .setSuccessRateMinimumHosts(UInt32Value.of(107))
            .setSuccessRateRequestVolume(UInt32Value.of(108))
            .setFailurePercentageThreshold(UInt32Value.of(82))
            .setEnforcingFailurePercentage(UInt32Value.of(83))
            .setFailurePercentageMinimumHosts(UInt32Value.of(111))
            .setFailurePercentageRequestVolume(UInt32Value.of(112)))
        .build();
    controlPlaneService.setXdsConfig(ADS_TYPE_URL_CDS, ImmutableMap.of(
        CLUSTER, cluster));
    startXdsDepManager();

    verify(helper, never()).updateBalancingState(eq(ConnectivityState.TRANSIENT_FAILURE), any());
    assertThat(childBalancers).hasSize(1);
    FakeLoadBalancer childBalancer = Iterables.getOnlyElement(childBalancers);
    assertThat(childBalancer.name).isEqualTo(PRIORITY_POLICY_NAME);
    PriorityLbConfig priorityLbConfig = (PriorityLbConfig) childBalancer.config;
    PriorityChildConfig priorityChildConfig =
        Iterables.getOnlyElement(priorityLbConfig.childConfigs.values());
    OutlierDetectionLoadBalancerConfig outlier = (OutlierDetectionLoadBalancerConfig)
        GracefulSwitchLoadBalancerAccessor.getChildConfig(priorityChildConfig.childConfig);
    assertThat(outlier.intervalNanos).isEqualTo(101);
    assertThat(outlier.baseEjectionTimeNanos).isEqualTo(102);
    assertThat(outlier.maxEjectionTimeNanos).isEqualTo(103);
    assertThat(outlier.maxEjectionPercent).isEqualTo(80);
    assertThat(outlier.successRateEjection.stdevFactor).isEqualTo(105);
    assertThat(outlier.successRateEjection.enforcementPercentage).isEqualTo(81);
    assertThat(outlier.successRateEjection.minimumHosts).isEqualTo(107);
    assertThat(outlier.successRateEjection.requestVolume).isEqualTo(108);
    assertThat(outlier.failurePercentageEjection.threshold).isEqualTo(82);
    assertThat(outlier.failurePercentageEjection.enforcementPercentage).isEqualTo(83);
    assertThat(outlier.failurePercentageEjection.minimumHosts).isEqualTo(111);
    assertThat(outlier.failurePercentageEjection.requestVolume).isEqualTo(112);
    assertClusterImplConfig(
        (ClusterImplConfig) GracefulSwitchLoadBalancerAccessor.getChildConfig(outlier.childConfig),
        CLUSTER, EDS_SERVICE_NAME, null, null, null, Collections.emptyList(),
        "wrr_locality_experimental");
  }

  @Test
  public void config_equalsTester() {
    ServerInfo lrsServerInfo =
        ServerInfo.create("lrs.googleapis.com", InsecureChannelCredentials.create());
    UpstreamTlsContext tlsContext =
        CommonTlsContextTestsUtil.buildUpstreamTlsContext(
            "google_cloud_private_spiffe", true);
    DiscoveryMechanism edsDiscoveryMechanism1 =
        DiscoveryMechanism.forEds(CLUSTER, EDS_SERVICE_NAME, lrsServerInfo, 100L, tlsContext,
            Collections.emptyMap(), null, null);
    io.grpc.xds.EnvoyServerProtoData.OutlierDetection outlierDetection =
        io.grpc.xds.EnvoyServerProtoData.OutlierDetection.create(
            100L, 100L, 100L, 100, SuccessRateEjection.create(100, 100, 100, 100),
            FailurePercentageEjection.create(100, 100, 100, 100));
    DiscoveryMechanism edsDiscoveryMechanismWithOutlierDetection =
        DiscoveryMechanism.forEds(CLUSTER, EDS_SERVICE_NAME, lrsServerInfo, 100L, tlsContext,
            Collections.emptyMap(), outlierDetection, null);
    Object roundRobin = GracefulSwitchLoadBalancer.createLoadBalancingPolicyConfig(
        new FakeLoadBalancerProvider("wrr_locality_experimental"), new WrrLocalityConfig(
            GracefulSwitchLoadBalancer.createLoadBalancingPolicyConfig(
                new FakeLoadBalancerProvider("round_robin"), null)));
    Object leastRequest = GracefulSwitchLoadBalancer.createLoadBalancingPolicyConfig(
        new FakeLoadBalancerProvider("wrr_locality_experimental"), new WrrLocalityConfig(
            GracefulSwitchLoadBalancer.createLoadBalancingPolicyConfig(
                new FakeLoadBalancerProvider("least_request_experimental"),
                new LeastRequestConfig(3))));

    new EqualsTester()
        .addEqualityGroup(
            new ClusterResolverConfig(
                edsDiscoveryMechanism1, leastRequest, false),
            new ClusterResolverConfig(
                edsDiscoveryMechanism1, leastRequest, false))
        .addEqualityGroup(new ClusterResolverConfig(
            edsDiscoveryMechanism1, roundRobin, false))
        .addEqualityGroup(new ClusterResolverConfig(
            edsDiscoveryMechanism1, leastRequest, true))
        .addEqualityGroup(new ClusterResolverConfig(
            edsDiscoveryMechanismWithOutlierDetection,
            leastRequest,
            false))
        .testEquals();
  }

  private void startXdsDepManager() {
    startXdsDepManager(new CdsConfig(CLUSTER));
  }

  private void startXdsDepManager(final CdsConfig cdsConfig) {
    startXdsDepManager(cdsConfig, true);
  }

  private void startXdsDepManager(final CdsConfig cdsConfig, boolean forwardTime) {
    xdsDepManager.start(
        xdsConfig -> {
          if (!xdsConfig.hasValue()) {
            throw new AssertionError("" + xdsConfig.getStatus());
          }
          if (loadBalancer == null) {
            return;
          }
          loadBalancer.acceptResolvedAddresses(ResolvedAddresses.newBuilder()
              .setAddresses(Collections.emptyList())
              .setAttributes(Attributes.newBuilder()
                .set(io.grpc.xds.XdsAttributes.XDS_CONFIG, xdsConfig.getValue())
                .set(io.grpc.xds.XdsAttributes.XDS_CLUSTER_SUBSCRIPT_REGISTRY, xdsDepManager)
                .build())
              .setLoadBalancingPolicyConfig(cdsConfig)
              .build());
        });
    if (forwardTime) {
      // trigger does not exist timer, so broken config is more obvious
      fakeClock.forwardTime(10, TimeUnit.MINUTES);
    }
  }

  private FakeNameResolver assertResolverCreated(String uriPath) {
    assertThat(resolvers).hasSize(1);
    FakeNameResolver resolver = Iterables.getOnlyElement(resolvers);
    assertThat(resolver.targetUri.getPath()).isEqualTo(uriPath);
    return resolver;
  }

  private static void assertPicker(SubchannelPicker picker, Status expectedStatus,
      @Nullable Subchannel expectedSubchannel)  {
    PickResult result = picker.pickSubchannel(mock(PickSubchannelArgs.class));
    Status actualStatus = result.getStatus();
    assertThat(actualStatus.getCode()).isEqualTo(expectedStatus.getCode());
    assertThat(actualStatus.getDescription()).isEqualTo(expectedStatus.getDescription());
    if (actualStatus.isOk()) {
      assertThat(result.getSubchannel()).isSameInstanceAs(expectedSubchannel);
    }
  }

  private static void assertClusterImplConfig(ClusterImplConfig config, String cluster,
      @Nullable String edsServiceName, ServerInfo lrsServerInfo, Long maxConcurrentRequests,
      @Nullable UpstreamTlsContext tlsContext, List<DropOverload> dropCategories,
      String childPolicy) {
    assertThat(config.cluster).isEqualTo(cluster);
    assertThat(config.edsServiceName).isEqualTo(edsServiceName);
    assertThat(config.lrsServerInfo).isEqualTo(lrsServerInfo);
    assertThat(config.maxConcurrentRequests).isEqualTo(maxConcurrentRequests);
    assertThat(config.tlsContext).isEqualTo(tlsContext);
    assertThat(config.dropCategories).isEqualTo(dropCategories);
    assertThat(
          GracefulSwitchLoadBalancerAccessor.getChildProvider(config.childConfig).getPolicyName())
        .isEqualTo(childPolicy);
  }

  /** Asserts two list of EAGs contains same addresses, regardless of attributes. */
  private static void assertAddressesEqual(
      List<EquivalentAddressGroup> expected, List<EquivalentAddressGroup> actual) {
    List<List<java.net.SocketAddress>> expectedAddresses
        = expected.stream().map(EquivalentAddressGroup::getAddresses).collect(toList());
    List<List<java.net.SocketAddress>> actualAddresses
        = actual.stream().map(EquivalentAddressGroup::getAddresses).collect(toList());
    assertThat(actualAddresses).isEqualTo(expectedAddresses);
  }

  @SuppressWarnings("AddressSelection")
  private static InetSocketAddress newInetSocketAddress(String ip, int port) {
    return new InetSocketAddress(ip, port);
  }

  private static EquivalentAddressGroup newInetSocketAddressEag(String ip, int port) {
    return new EquivalentAddressGroup(newInetSocketAddress(ip, port));
  }

  private static LbEndpoint.Builder newSocketLbEndpoint(String ip, int port) {
    return LbEndpoint.newBuilder()
        .setEndpoint(Endpoint.newBuilder()
          .setAddress(newAddress(ip, port)))
        .setHealthStatus(HealthStatus.HEALTHY);
  }

  private static Address.Builder newAddress(String ip, int port) {
    return Address.newBuilder()
        .setSocketAddress(SocketAddress.newBuilder()
            .setAddress(ip)
            .setPortValue(port));
  }

  private class FakeNameResolverProvider extends NameResolverProvider {
    @Override
    public NameResolver newNameResolver(URI targetUri, NameResolver.Args args) {
      assertThat(targetUri.getScheme()).isEqualTo("dns");
      FakeNameResolver resolver = new FakeNameResolver(targetUri);
      resolvers.add(resolver);
      return resolver;
    }

    @Override
    public String getDefaultScheme() {
      return "dns";
    }

    @Override
    protected boolean isAvailable() {
      return true;
    }

    @Override
    protected int priority() {
      return 0;  // doesn't matter
    }
  }


  private class FakeNameResolver extends NameResolver {
    private final URI targetUri;
    protected Listener2 listener;
    private int refreshCount;

    private FakeNameResolver(URI targetUri) {
      this.targetUri = targetUri;
    }

    @Override
    public String getServiceAuthority() {
      throw new UnsupportedOperationException("should not be called");
    }

    @Override
    public void start(final Listener2 listener) {
      this.listener = listener;
    }

    @Override
    public void refresh() {
      refreshCount++;
    }

    @Override
    public void shutdown() {
      resolvers.remove(this);
    }

    protected void deliverEndpointAddresses(List<EquivalentAddressGroup> addresses) {
      syncContext.execute(() -> {
        Status ret = listener.onResult2(ResolutionResult.newBuilder()
                .setAddressesOrError(StatusOr.fromValue(addresses)).build());
        assertThat(ret.getCode()).isEqualTo(Status.Code.OK);
      });
    }

    protected void deliverError(Status error) {
      syncContext.execute(() -> listener.onResult2(ResolutionResult.newBuilder()
                      .setAddressesOrError(StatusOr.fromStatus(error)).build()));
    }
  }

  private final class FakeLoadBalancerProvider extends LoadBalancerProvider {
    private final String policyName;

    FakeLoadBalancerProvider(String policyName) {
      this.policyName = policyName;
    }

    @Override
    public LoadBalancer newLoadBalancer(Helper helper) {
      FakeLoadBalancer balancer = new FakeLoadBalancer(policyName, helper);
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
  }

  private final class FakeLoadBalancer extends LoadBalancer {
    private final String name;
    private final Helper helper;
    private List<EquivalentAddressGroup> addresses;
    private Object config;
    private Status upstreamError;
    private boolean shutdown;

    FakeLoadBalancer(String name, Helper helper) {
      this.name = name;
      this.helper = helper;
    }

    @Override
    public Status acceptResolvedAddresses(ResolvedAddresses resolvedAddresses) {
      addresses = resolvedAddresses.getAddresses();
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
