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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.testing.EqualsTester;
import com.google.protobuf.Any;
import com.google.protobuf.UInt32Value;
import com.google.protobuf.UInt64Value;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
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
import io.grpc.Status.Code;
import io.grpc.StatusOr;
import io.grpc.SynchronizationContext;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.internal.FakeClock;
import io.grpc.internal.FakeClock.ScheduledTask;
import io.grpc.internal.GrpcUtil;
import io.grpc.testing.GrpcCleanupRule;
import io.grpc.util.GracefulSwitchLoadBalancer;
import io.grpc.util.GracefulSwitchLoadBalancerAccessor;
import io.grpc.util.OutlierDetectionLoadBalancerProvider;
import io.grpc.xds.CdsLoadBalancerProvider.CdsConfig;
import io.grpc.xds.ClusterImplLoadBalancerProvider.ClusterImplConfig;
import io.grpc.xds.ClusterResolverLoadBalancerProvider.ClusterResolverConfig;
import io.grpc.xds.ClusterResolverLoadBalancerProvider.ClusterResolverConfig.DiscoveryMechanism;
import io.grpc.xds.Endpoints;
import io.grpc.xds.Endpoints.DropOverload;
import io.grpc.xds.EnvoyServerProtoData.FailurePercentageEjection;
import io.grpc.xds.EnvoyServerProtoData.OutlierDetection;
import io.grpc.xds.EnvoyServerProtoData.SuccessRateEjection;
import io.grpc.xds.EnvoyServerProtoData.UpstreamTlsContext;
import io.grpc.xds.LeastRequestLoadBalancer.LeastRequestConfig;
import io.grpc.xds.PriorityLoadBalancerProvider.PriorityLbConfig;
import io.grpc.xds.PriorityLoadBalancerProvider.PriorityLbConfig.PriorityChildConfig;
import io.grpc.xds.RingHashLoadBalancer.RingHashConfig;
import io.grpc.xds.WrrLocalityLoadBalancer.WrrLocalityConfig;
import io.grpc.xds.XdsEndpointResource.EdsUpdate;
import io.grpc.xds.client.Bootstrapper.ServerInfo;
import io.grpc.xds.client.XdsClient;
import io.grpc.xds.client.XdsResourceType;
import io.grpc.xds.internal.security.CommonTlsContextTestsUtil;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
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
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/** Tests for {@link ClusterResolverLoadBalancer}. */
@RunWith(JUnit4.class)
public class ClusterResolverLoadBalancerTest {
  @Rule public final MockitoRule mocks = MockitoJUnit.rule();
  @Rule
  public final GrpcCleanupRule cleanupRule = new GrpcCleanupRule();

  private static final String SERVER_NAME = "example.com";
  private static final String AUTHORITY = "api.google.com";
  private static final String CLUSTER = "cluster-foo.googleapis.com";
  private static final String CLUSTER_DNS = "cluster-dns.googleapis.com";
  private static final String EDS_SERVICE_NAME = "backend-service-foo.googleapis.com";
  private static final String DNS_HOST_NAME = "dns-service.googleapis.com";
  private static final Cluster EDS_CLUSTER = Cluster.newBuilder()
      .setName(CLUSTER)
      .setType(Cluster.DiscoveryType.EDS)
      .setEdsClusterConfig(Cluster.EdsClusterConfig.newBuilder()
          .setServiceName(EDS_SERVICE_NAME)
          .setEdsConfig(ConfigSource.newBuilder()
            .setAds(AggregatedConfigSource.newBuilder())))
      .build();
  private static final ServerInfo LRS_SERVER_INFO =
      ServerInfo.create("lrs.googleapis.com", InsecureChannelCredentials.create());
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
  private final io.grpc.xds.client.Locality locality1 =
      io.grpc.xds.client.Locality.create("test-region-1", "test-zone-1", "test-subzone-1");
  private final io.grpc.xds.client.Locality locality2 =
      io.grpc.xds.client.Locality.create("test-region-2", "test-zone-2", "test-subzone-2");
  private final io.grpc.xds.client.Locality locality3 =
      io.grpc.xds.client.Locality.create("test-region-3", "test-zone-3", "test-subzone-3");
  private final UpstreamTlsContext tlsContext =
      CommonTlsContextTestsUtil.buildUpstreamTlsContext("google_cloud_private_spiffe", true);
  private final OutlierDetection outlierDetection = OutlierDetection.create(
      100L, 100L, 100L, 100, SuccessRateEjection.create(100, 100, 100, 100),
      FailurePercentageEjection.create(100, 100, 100, 100));
  private final DiscoveryMechanism edsDiscoveryMechanism1 =
      DiscoveryMechanism.forEds(CLUSTER, EDS_SERVICE_NAME, LRS_SERVER_INFO, 100L, tlsContext,
          Collections.emptyMap(), null);
  private final DiscoveryMechanism edsDiscoveryMechanismWithOutlierDetection =
      DiscoveryMechanism.forEds(CLUSTER, EDS_SERVICE_NAME, LRS_SERVER_INFO, 100L, tlsContext,
          Collections.emptyMap(), outlierDetection);
  private final DiscoveryMechanism logicalDnsDiscoveryMechanism =
      DiscoveryMechanism.forLogicalDns(CLUSTER_DNS, DNS_HOST_NAME, LRS_SERVER_INFO, 300L, null,
          Collections.emptyMap());

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
  private final Object roundRobin = GracefulSwitchLoadBalancer.createLoadBalancingPolicyConfig(
      new FakeLoadBalancerProvider("wrr_locality_experimental"), new WrrLocalityConfig(
          GracefulSwitchLoadBalancer.createLoadBalancingPolicyConfig(
              new FakeLoadBalancerProvider("round_robin"), null)));
  //private final Object ringHash = GracefulSwitchLoadBalancer.createLoadBalancingPolicyConfig(
  //    new FakeLoadBalancerProvider("ring_hash_experimental"), new RingHashConfig(10L, 100L, ""));
  private final Object leastRequest = GracefulSwitchLoadBalancer.createLoadBalancingPolicyConfig(
      new FakeLoadBalancerProvider("wrr_locality_experimental"), new WrrLocalityConfig(
          GracefulSwitchLoadBalancer.createLoadBalancingPolicyConfig(
              new FakeLoadBalancerProvider("least_request_experimental"),
              new LeastRequestConfig(3))));
  private final List<FakeLoadBalancer> childBalancers = new ArrayList<>();
  private final List<FakeNameResolver> resolvers = new ArrayList<>();
  private final FakeXdsClient xdsClient = new FakeXdsClient();
  private final XdsTestControlPlaneService controlPlaneService = new XdsTestControlPlaneService();
  private final XdsClient xdsClient2 = XdsTestUtils.createXdsClient(
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
  private NameResolverProvider fakeNameResolverProvider = new FakeNameResolverProvider();
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
        xdsClient2,
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
            "127.0.0.1", "", 1234, EDS_SERVICE_NAME)));

    nsRegistry.register(fakeNameResolverProvider);
    when(helper.getAuthority()).thenReturn(AUTHORITY);
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
    assertThat(xdsClient2.getSubscribedResourcesMetadataSnapshot().get()).isEmpty();
    xdsClient2.shutdown();
    assertThat(resolvers).isEmpty();
    assertThat(xdsClient.watchers).isEmpty();
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
          .addLbEndpoints(newSocketLbEndpoint("127.0.0.1", 1234))
          .addLbEndpoints(newSocketLbEndpoint("127.0.0.2", 1234)))
        .addEndpoints(LocalityLbEndpoints.newBuilder()
          .setLoadBalancingWeight(UInt32Value.of(50))
          .setLocality(LOCALITY2)
          .addLbEndpoints(newSocketLbEndpoint("127.0.1.1", 1234)
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
    // Endpoints in locality1 have no endpoint-level weight specified, so all endpoints within
    // locality1 are equally weighted.
    assertThat(addr1.getAddresses())
        .isEqualTo(Arrays.asList(newInetSocketAddress("127.0.0.1", 1234)));
    assertThat(addr1.getAttributes().get(XdsAttributes.ATTR_SERVER_WEIGHT))
        .isEqualTo(10);
    assertThat(addr2.getAddresses())
        .isEqualTo(Arrays.asList(newInetSocketAddress("127.0.0.2", 1234)));
    assertThat(addr2.getAttributes().get(XdsAttributes.ATTR_SERVER_WEIGHT))
        .isEqualTo(10);
    assertThat(addr3.getAddresses())
        .isEqualTo(Arrays.asList(newInetSocketAddress("127.0.1.1", 1234)));
    assertThat(addr3.getAttributes().get(XdsAttributes.ATTR_SERVER_WEIGHT))
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
          .addLbEndpoints(newSocketLbEndpoint("127.0.0.1", 1234)))
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
        .isEqualTo(Arrays.asList(newInetSocketAddress("127.0.0.1", 1234)));
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
            .get(XdsAttributes.ATTR_LOCALITY_WEIGHT)).isEqualTo(100);
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
              .setAddress(newAddress("127.0.0.1", 1234)))))
        .build();
    controlPlaneService.setXdsConfig(ADS_TYPE_URL_EDS, ImmutableMap.of(
        EDS_SERVICE_NAME, clusterLoadAssignment));
    startXdsDepManager();

    verify(helper, never()).updateBalancingState(eq(ConnectivityState.TRANSIENT_FAILURE), any());
    assertThat(childBalancers).hasSize(1);
    FakeLoadBalancer childBalancer = Iterables.getOnlyElement(childBalancers);

    assertThat(
        childBalancer.addresses.get(0).getAttributes()
            .get(XdsAttributes.ATTR_ADDRESS_NAME)).isEqualTo("hostname1");
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
                  Any.pack(newAddress("127.0.0.2", 8081).build())))))
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

    for (EquivalentAddressGroup eag : childBalancer.addresses) {
      if (eag.getAttributes().get(XdsAttributes.ATTR_LOCALITY) == locality1) {
        assertThat(eag.getAttributes().get(XdsAttributes.ATTR_LOCALITY_WEIGHT))
            .isEqualTo(70);
      }
      if (eag.getAttributes().get(XdsAttributes.ATTR_LOCALITY) == locality2) {
        assertThat(eag.getAttributes().get(XdsAttributes.ATTR_LOCALITY_WEIGHT))
            .isEqualTo(10);
      }
      if (eag.getAttributes().get(XdsAttributes.ATTR_LOCALITY) == locality3) {
        assertThat(eag.getAttributes().get(XdsAttributes.ATTR_LOCALITY_WEIGHT))
            .isEqualTo(20);
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
  public void edsCluster_resourcesRevoked_shutDownChildLbPolicy() {
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
        Arrays.asList(new EquivalentAddressGroup(newInetSocketAddress("127.0.0.1", 8000))),
        childBalancer.addresses);

    controlPlaneService.setXdsConfig(ADS_TYPE_URL_CDS, ImmutableMap.of());
    verify(helper).updateBalancingState(
        eq(ConnectivityState.TRANSIENT_FAILURE), pickerCaptor.capture());
    Status expectedError = Status.UNAVAILABLE.withDescription(
        "CDS resource " + CLUSTER + " does not exist nodeID: node-id");
    assertPicker(pickerCaptor.getValue(), expectedError, null);
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
    for (EquivalentAddressGroup eag : childBalancer.addresses) {
      assertThat(eag.getAttributes().get(XdsAttributes.ATTR_LOCALITY)).isEqualTo(locality2);
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
    ClusterResolverConfig config = new ClusterResolverConfig(
        logicalDnsDiscoveryMechanism, roundRobin, false);
    deliverLbConfig(config);
    FakeNameResolver resolver = assertResolverCreated("/" + DNS_HOST_NAME);
    assertThat(childBalancers).isEmpty();
    EquivalentAddressGroup endpoint1 = makeAddress("endpoint-addr-1");
    EquivalentAddressGroup endpoint2 = makeAddress("endpoint-addr-2");
    resolver.deliverEndpointAddresses(Arrays.asList(endpoint1, endpoint2));

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
    assertClusterImplConfig(clusterImplConfig, CLUSTER_DNS, null, LRS_SERVER_INFO, 300L, null,
        Collections.<DropOverload>emptyList(), "pick_first");
    assertAddressesEqual(Arrays.asList(endpoint1, endpoint2), childBalancer.addresses);
    assertThat(childBalancer.addresses.get(0).getAttributes()
        .get(XdsAttributes.ATTR_ADDRESS_NAME)).isEqualTo(DNS_HOST_NAME);
    assertThat(childBalancer.addresses.get(1).getAttributes()
        .get(XdsAttributes.ATTR_ADDRESS_NAME)).isEqualTo(DNS_HOST_NAME);
  }

  @Test
  public void onlyLogicalDnsCluster_handleRefreshNameResolution() {
    ClusterResolverConfig config = new ClusterResolverConfig(
        logicalDnsDiscoveryMechanism, roundRobin, false);
    deliverLbConfig(config);
    FakeNameResolver resolver = assertResolverCreated("/" + DNS_HOST_NAME);
    assertThat(childBalancers).isEmpty();
    EquivalentAddressGroup endpoint1 = makeAddress("endpoint-addr-1");
    EquivalentAddressGroup endpoint2 = makeAddress("endpoint-addr-2");
    resolver.deliverEndpointAddresses(Arrays.asList(endpoint1, endpoint2));
    assertThat(resolver.refreshCount).isEqualTo(0);
    FakeLoadBalancer childBalancer = Iterables.getOnlyElement(childBalancers);
    childBalancer.helper.refreshNameResolution();
    assertThat(resolver.refreshCount).isEqualTo(1);
  }

  @Test
  public void resolutionError_backoffAndRefresh() {
    do_onlyLogicalDnsCluster_resolutionError_backoffAndRefresh();
  }

  void do_onlyLogicalDnsCluster_resolutionError_backoffAndRefresh() {
    InOrder inOrder = Mockito.inOrder(helper);
    ClusterResolverConfig config = new ClusterResolverConfig(
        logicalDnsDiscoveryMechanism, roundRobin, false);
    deliverLbConfig(config);
    FakeNameResolver resolver = assertResolverCreated("/" + DNS_HOST_NAME);
    assertThat(childBalancers).isEmpty();
    Status error = Status.UNAVAILABLE.withDescription("cannot reach DNS server");
    resolver.deliverError(error);
    inOrder.verify(helper).updateBalancingState(
            eq(ConnectivityState.TRANSIENT_FAILURE), pickerCaptor.capture());
    assertPicker(pickerCaptor.getValue(), error, null);
    assertThat(resolver.refreshCount).isEqualTo(0);
    assertThat(fakeClock.getPendingTasks()).hasSize(1);
    assertThat(Iterables.getOnlyElement(fakeClock.getPendingTasks()).getDelay(TimeUnit.SECONDS))
            .isEqualTo(1L);
    fakeClock.forwardTime(1L, TimeUnit.SECONDS);
    assertThat(resolver.refreshCount).isEqualTo(1);

    error = Status.UNKNOWN.withDescription("I am lost");
    resolver.deliverError(error);
    inOrder.verify(helper).updateBalancingState(
            eq(ConnectivityState.TRANSIENT_FAILURE), pickerCaptor.capture());
    assertPicker(pickerCaptor.getValue(), error, null);
    assertThat(fakeClock.getPendingTasks()).hasSize(1);
    assertThat(Iterables.getOnlyElement(fakeClock.getPendingTasks()).getDelay(TimeUnit.SECONDS))
            .isEqualTo(10L);
    fakeClock.forwardTime(10L, TimeUnit.SECONDS);
    assertThat(resolver.refreshCount).isEqualTo(2);

    // Succeed.
    EquivalentAddressGroup endpoint1 = makeAddress("endpoint-addr-1");
    EquivalentAddressGroup endpoint2 = makeAddress("endpoint-addr-2");
    resolver.deliverEndpointAddresses(Arrays.asList(endpoint1, endpoint2));
    assertThat(childBalancers).hasSize(1);
    assertAddressesEqual(Arrays.asList(endpoint1, endpoint2),
            Iterables.getOnlyElement(childBalancers).addresses);

    assertThat(fakeClock.getPendingTasks()).isEmpty();
    inOrder.verifyNoMoreInteractions();
  }

  @Test
  public void onlyLogicalDnsCluster_refreshNameResolutionRaceWithResolutionError() {
    ClusterResolverConfig config = new ClusterResolverConfig(
        logicalDnsDiscoveryMechanism, roundRobin, false);
    deliverLbConfig(config);
    FakeNameResolver resolver = assertResolverCreated("/" + DNS_HOST_NAME);
    assertThat(childBalancers).isEmpty();
    EquivalentAddressGroup endpoint = makeAddress("endpoint-addr");
    resolver.deliverEndpointAddresses(Collections.singletonList(endpoint));
    FakeLoadBalancer childBalancer = Iterables.getOnlyElement(childBalancers);
    assertAddressesEqual(Collections.singletonList(endpoint), childBalancer.addresses);
    assertThat(resolver.refreshCount).isEqualTo(0);

    childBalancer.helper.refreshNameResolution();
    assertThat(resolver.refreshCount).isEqualTo(1);
    resolver.deliverError(Status.UNAVAILABLE.withDescription("I am lost"));
    assertThat(fakeClock.getPendingTasks()).hasSize(1);
    ScheduledTask task = Iterables.getOnlyElement(fakeClock.getPendingTasks());
    assertThat(task.getDelay(TimeUnit.SECONDS)).isEqualTo(1L);

    fakeClock.forwardTime( 100L, TimeUnit.MILLISECONDS);
    childBalancer.helper.refreshNameResolution();
    assertThat(resolver.refreshCount).isEqualTo(2);
    assertThat(task.isCancelled()).isTrue();
    assertThat(fakeClock.getPendingTasks()).isEmpty();
    resolver.deliverError(Status.UNAVAILABLE.withDescription("I am still lost"));
    task = Iterables.getOnlyElement(fakeClock.getPendingTasks());
    assertThat(task.getDelay(TimeUnit.SECONDS)).isEqualTo(5L);

    fakeClock.forwardTime(5L, TimeUnit.SECONDS);
    assertThat(resolver.refreshCount).isEqualTo(3);
  }

  @Test
  public void resolutionErrorAfterChildLbCreated_propagateError() {
    ClusterResolverConfig config = new ClusterResolverConfig(
        edsDiscoveryMechanism1, roundRobin, false);
    deliverLbConfig(config);
    assertThat(xdsClient.watchers.keySet()).containsExactly(EDS_SERVICE_NAME);
    assertThat(childBalancers).isEmpty();
    reset(helper);
    EquivalentAddressGroup endpoint = makeAddress("endpoint-addr-1");
    Endpoints.LocalityLbEndpoints localityLbEndpoints =
        Endpoints.LocalityLbEndpoints.create(
            Collections.singletonList(Endpoints.LbEndpoint.create(
                endpoint, 100, true, "hostname1", ImmutableMap.of())),
            10 /* localityWeight */, 1 /* priority */, ImmutableMap.of());
    xdsClient.deliverClusterLoadAssignment(
            EDS_SERVICE_NAME, Collections.singletonMap(locality1, localityLbEndpoints));

    assertThat(childBalancers).hasSize(1);
    FakeLoadBalancer childBalancer = Iterables.getOnlyElement(childBalancers);  // child LB created
    assertThat(childBalancer.upstreamError).isNull();  // should not propagate error to child LB
    assertAddressesEqual(Collections.singletonList(endpoint), childBalancer.addresses);

    xdsClient.deliverError(Status.RESOURCE_EXHAUSTED.withDescription("out of memory"));
    assertThat(childBalancer.upstreamError).isNotNull();  // last cluster's (DNS) error propagated
    assertThat(childBalancer.upstreamError.getCode()).isEqualTo(Code.UNAVAILABLE);
    assertThat(childBalancer.upstreamError.getDescription())
        .isEqualTo("Unable to load EDS backend-service-foo.googleapis.com. xDS server returned: "
            + "RESOURCE_EXHAUSTED: out of memory");
    assertThat(childBalancer.shutdown).isFalse();
    verify(helper, never()).updateBalancingState(
        eq(ConnectivityState.TRANSIENT_FAILURE), any(SubchannelPicker.class));
  }

  @Test
  public void resolutionErrorBeforeChildLbCreated_returnErrorPicker() {
    ClusterResolverConfig config = new ClusterResolverConfig(
        edsDiscoveryMechanism1, roundRobin, false);
    deliverLbConfig(config);
    assertThat(xdsClient.watchers.keySet()).containsExactly(EDS_SERVICE_NAME);
    assertThat(childBalancers).isEmpty();
    reset(helper);
    xdsClient.deliverError(Status.RESOURCE_EXHAUSTED.withDescription("OOM"));
    assertThat(childBalancers).isEmpty();
    verify(helper).updateBalancingState(
        eq(ConnectivityState.TRANSIENT_FAILURE), pickerCaptor.capture());
    PickResult result = pickerCaptor.getValue().pickSubchannel(mock(PickSubchannelArgs.class));
    Status actualStatus = result.getStatus();
    assertThat(actualStatus.getCode()).isEqualTo(Status.Code.UNAVAILABLE);
    assertThat(actualStatus.getDescription()).contains("RESOURCE_EXHAUSTED: OOM");
  }

  @Test
  public void handleNameResolutionErrorFromUpstream_eds_beforeChildLbCreated_returnErrorPicker() {
    ClusterResolverConfig config = new ClusterResolverConfig(
        edsDiscoveryMechanism1, roundRobin, false);
    deliverLbConfig(config);
    assertThat(xdsClient.watchers.keySet()).containsExactly(EDS_SERVICE_NAME);
    assertThat(childBalancers).isEmpty();
    reset(helper);
    Status upstreamError = Status.UNAVAILABLE.withDescription("unreachable");
    loadBalancer.handleNameResolutionError(upstreamError);
    verify(helper).updateBalancingState(
        eq(ConnectivityState.TRANSIENT_FAILURE), pickerCaptor.capture());
    assertPicker(pickerCaptor.getValue(), upstreamError, null);
  }

  @Test
  public void handleNameResolutionErrorFromUpstream_lDns_beforeChildLbCreated_returnErrorPicker() {
    ClusterResolverConfig config = new ClusterResolverConfig(
        logicalDnsDiscoveryMechanism, roundRobin, false);
    deliverLbConfig(config);
    assertResolverCreated("/" + DNS_HOST_NAME);
    assertThat(childBalancers).isEmpty();
    reset(helper);
    Status upstreamError = Status.UNAVAILABLE.withDescription("unreachable");
    loadBalancer.handleNameResolutionError(upstreamError);
    verify(helper).updateBalancingState(
        eq(ConnectivityState.TRANSIENT_FAILURE), pickerCaptor.capture());
    assertPicker(pickerCaptor.getValue(), upstreamError, null);
  }

  @Test
  public void handleNameResolutionErrorFromUpstream_afterChildLbCreated_eds_fallThrough() {
    ClusterResolverConfig config = new ClusterResolverConfig(
        edsDiscoveryMechanism1, roundRobin, false);
    deliverLbConfig(config);
    assertThat(xdsClient.watchers.keySet()).containsExactly(EDS_SERVICE_NAME);
    assertThat(childBalancers).isEmpty();
    reset(helper);
    EquivalentAddressGroup endpoint1 = makeAddress("endpoint-addr-1");
    Endpoints.LocalityLbEndpoints localityLbEndpoints =
        Endpoints.LocalityLbEndpoints.create(
            Collections.singletonList(Endpoints.LbEndpoint.create(
                endpoint1, 100, true, "hostname1", ImmutableMap.of())),
            10 /* localityWeight */, 1 /* priority */, ImmutableMap.of());
    xdsClient.deliverClusterLoadAssignment(
        EDS_SERVICE_NAME, Collections.singletonMap(locality1, localityLbEndpoints));
    assertThat(childBalancers).hasSize(1);
    FakeLoadBalancer childBalancer = Iterables.getOnlyElement(childBalancers);
    assertThat(((PriorityLbConfig) childBalancer.config).priorities)
        .containsExactly(CLUSTER + "[child1]");
    assertAddressesEqual(Arrays.asList(endpoint1), childBalancer.addresses);

    loadBalancer.handleNameResolutionError(Status.UNAVAILABLE.withDescription("unreachable"));
    assertThat(childBalancer.upstreamError.getCode()).isEqualTo(Code.UNAVAILABLE);
    assertThat(childBalancer.upstreamError.getDescription()).isEqualTo("unreachable");
    verify(helper, never()).updateBalancingState(
        any(ConnectivityState.class), any(SubchannelPicker.class));
  }

  @Test
  public void handleNameResolutionErrorFromUpstream_afterChildLbCreated_logicalDns_fallThrough() {
    ClusterResolverConfig config = new ClusterResolverConfig(
        logicalDnsDiscoveryMechanism, roundRobin, false);
    deliverLbConfig(config);
    FakeNameResolver resolver = assertResolverCreated("/" + DNS_HOST_NAME);
    assertThat(childBalancers).isEmpty();
    reset(helper);
    EquivalentAddressGroup endpoint2 = makeAddress("endpoint-addr-2");
    resolver.deliverEndpointAddresses(Collections.singletonList(endpoint2));
    assertThat(childBalancers).hasSize(1);
    FakeLoadBalancer childBalancer = Iterables.getOnlyElement(childBalancers);
    assertThat(((PriorityLbConfig) childBalancer.config).priorities)
        .containsExactly(CLUSTER_DNS + "[child0]");
    assertAddressesEqual(Arrays.asList(endpoint2), childBalancer.addresses);

    loadBalancer.handleNameResolutionError(Status.UNAVAILABLE.withDescription("unreachable"));
    assertThat(childBalancer.upstreamError.getCode()).isEqualTo(Code.UNAVAILABLE);
    assertThat(childBalancer.upstreamError.getDescription()).isEqualTo("unreachable");
    verify(helper, never()).updateBalancingState(
        any(ConnectivityState.class), any(SubchannelPicker.class));
  }

  @Test
  public void config_equalsTester() {
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
                .set(XdsAttributes.XDS_CONFIG, xdsConfig.getValue())
                .set(XdsAttributes.XDS_CLUSTER_SUBSCRIPT_REGISTRY, xdsDepManager)
                .build())
              .setLoadBalancingPolicyConfig(cdsConfig)
              .build());
        });
    // trigger does not exist timer, so broken config is more obvious
    fakeClock.forwardTime(10, TimeUnit.MINUTES);
  }

  private void deliverLbConfig(ClusterResolverConfig config) {
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder()
            .setAddresses(Collections.<EquivalentAddressGroup>emptyList())
            .setAttributes(
                Attributes.newBuilder()
                    .set(XdsAttributes.XDS_CONFIG, null)
                    .build())
            .setLoadBalancingPolicyConfig(config)
            .build());
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

  private static EquivalentAddressGroup makeAddress(final String name) {
    return new EquivalentAddressGroup(new FakeSocketAddress(name));
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

  static class FakeSocketAddress extends java.net.SocketAddress {
    private final String name;

    private FakeSocketAddress(String name) {
      this.name = name;
    }

    @Override
    public int hashCode() {
      return Objects.hash(name);
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof FakeSocketAddress)) {
        return false;
      }
      FakeSocketAddress that = (FakeSocketAddress) o;
      return Objects.equals(name, that.name);
    }

    @Override
    public String toString() {
      return name;
    }
  }

  private static final class FakeXdsClient extends XdsClient {

    private final Map<String, ResourceWatcher<EdsUpdate>> watchers = new HashMap<>();

    @Override
    @SuppressWarnings("unchecked")
    public <T extends ResourceUpdate> void watchXdsResource(XdsResourceType<T> type,
            String resourceName,
            ResourceWatcher<T> watcher,
            Executor syncContext) {
      assertThat(type.typeName()).isEqualTo("EDS");
      assertThat(watchers).doesNotContainKey(resourceName);
      watchers.put(resourceName, (ResourceWatcher<EdsUpdate>) watcher);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends ResourceUpdate> void cancelXdsResourceWatch(XdsResourceType<T> type,
                                                                  String resourceName,
                                                                  ResourceWatcher<T> watcher) {
      assertThat(type.typeName()).isEqualTo("EDS");
      assertThat(watchers).containsKey(resourceName);
      watchers.remove(resourceName);
    }

    void deliverClusterLoadAssignment(
        String resource,
        Map<io.grpc.xds.client.Locality, Endpoints.LocalityLbEndpoints> localityLbEndpointsMap) {
      deliverClusterLoadAssignment(
          resource, Collections.<DropOverload>emptyList(), localityLbEndpointsMap);
    }

    void deliverClusterLoadAssignment(String resource, List<DropOverload> dropOverloads,
        Map<io.grpc.xds.client.Locality, Endpoints.LocalityLbEndpoints> localityLbEndpointsMap) {
      if (watchers.containsKey(resource)) {
        watchers.get(resource).onChanged(
            new XdsEndpointResource.EdsUpdate(resource, localityLbEndpointsMap, dropOverloads));
      }
    }

    void deliverError(Status error) {
      for (ResourceWatcher<EdsUpdate> watcher : watchers.values()) {
        watcher.onError(error);
      }
    }
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
