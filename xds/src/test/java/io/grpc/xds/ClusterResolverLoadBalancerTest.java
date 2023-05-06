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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import io.grpc.Attributes;
import io.grpc.ChannelLogger;
import io.grpc.ConnectivityState;
import io.grpc.EquivalentAddressGroup;
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
import io.grpc.SynchronizationContext;
import io.grpc.internal.BackoffPolicy;
import io.grpc.internal.FakeClock;
import io.grpc.internal.FakeClock.ScheduledTask;
import io.grpc.internal.GrpcUtil;
import io.grpc.internal.ObjectPool;
import io.grpc.internal.ServiceConfigUtil.PolicySelection;
import io.grpc.util.OutlierDetectionLoadBalancer.OutlierDetectionLoadBalancerConfig;
import io.grpc.util.OutlierDetectionLoadBalancerProvider;
import io.grpc.xds.Bootstrapper.ServerInfo;
import io.grpc.xds.ClusterImplLoadBalancerProvider.ClusterImplConfig;
import io.grpc.xds.ClusterResolverLoadBalancerProvider.ClusterResolverConfig;
import io.grpc.xds.ClusterResolverLoadBalancerProvider.ClusterResolverConfig.DiscoveryMechanism;
import io.grpc.xds.Endpoints.DropOverload;
import io.grpc.xds.Endpoints.LbEndpoint;
import io.grpc.xds.Endpoints.LocalityLbEndpoints;
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
import io.grpc.xds.internal.security.CommonTlsContextTestsUtil;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

  private static final String AUTHORITY = "api.google.com";
  private static final String CLUSTER1 = "cluster-foo.googleapis.com";
  private static final String CLUSTER2 = "cluster-bar.googleapis.com";
  private static final String CLUSTER_DNS = "cluster-dns.googleapis.com";
  private static final String EDS_SERVICE_NAME1 = "backend-service-foo.googleapis.com";
  private static final String EDS_SERVICE_NAME2 = "backend-service-bar.googleapis.com";
  private static final String DNS_HOST_NAME = "dns-service.googleapis.com";
  private static final ServerInfo LRS_SERVER_INFO =
      ServerInfo.create("lrs.googleapis.com", InsecureChannelCredentials.create());
  private final Locality locality1 =
      Locality.create("test-region-1", "test-zone-1", "test-subzone-1");
  private final Locality locality2 =
      Locality.create("test-region-2", "test-zone-2", "test-subzone-2");
  private final Locality locality3 =
      Locality.create("test-region-3", "test-zone-3", "test-subzone-3");
  private final UpstreamTlsContext tlsContext =
      CommonTlsContextTestsUtil.buildUpstreamTlsContext("google_cloud_private_spiffe", true);
  private final OutlierDetection outlierDetection = OutlierDetection.create(
      100L, 100L, 100L, 100, SuccessRateEjection.create(100, 100, 100, 100),
      FailurePercentageEjection.create(100, 100, 100, 100));
  private final DiscoveryMechanism edsDiscoveryMechanism1 =
      DiscoveryMechanism.forEds(CLUSTER1, EDS_SERVICE_NAME1, LRS_SERVER_INFO, 100L, tlsContext,
          null);
  private final DiscoveryMechanism edsDiscoveryMechanism2 =
      DiscoveryMechanism.forEds(CLUSTER2, EDS_SERVICE_NAME2, LRS_SERVER_INFO, 200L, tlsContext,
          null);
  private final DiscoveryMechanism edsDiscoveryMechanismWithOutlierDetection =
      DiscoveryMechanism.forEds(CLUSTER1, EDS_SERVICE_NAME1, LRS_SERVER_INFO, 100L, tlsContext,
          outlierDetection);
  private final DiscoveryMechanism logicalDnsDiscoveryMechanism =
      DiscoveryMechanism.forLogicalDns(CLUSTER_DNS, DNS_HOST_NAME, LRS_SERVER_INFO, 300L, null);

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
  private final PolicySelection roundRobin = new PolicySelection(
      new FakeLoadBalancerProvider("wrr_locality_experimental"), new WrrLocalityConfig(
      new PolicySelection(new FakeLoadBalancerProvider("round_robin"), null)));
  private final PolicySelection ringHash = new PolicySelection(
      new FakeLoadBalancerProvider("ring_hash_experimental"), new RingHashConfig(10L, 100L));
  private final PolicySelection leastRequest = new PolicySelection(
      new FakeLoadBalancerProvider("wrr_locality_experimental"), new WrrLocalityConfig(
      new PolicySelection(new FakeLoadBalancerProvider("least_request_experimental"),
          new LeastRequestConfig(3))));
  private final List<FakeLoadBalancer> childBalancers = new ArrayList<>();
  private final List<FakeNameResolver> resolvers = new ArrayList<>();
  private final FakeXdsClient xdsClient = new FakeXdsClient();
  private final ObjectPool<XdsClient> xdsClientPool = new ObjectPool<XdsClient>() {
    @Override
    public XdsClient getObject() {
      xdsClientRefs++;
      return xdsClient;
    }

    @Override
    public XdsClient returnObject(Object object) {
      xdsClientRefs--;
      return null;
    }
  };

  @Mock
  private Helper helper;
  @Mock
  private BackoffPolicy.Provider backoffPolicyProvider;
  @Mock
  private BackoffPolicy backoffPolicy1;
  @Mock
  private BackoffPolicy backoffPolicy2;
  @Captor
  private ArgumentCaptor<SubchannelPicker> pickerCaptor;
  private int xdsClientRefs;
  private ClusterResolverLoadBalancer loadBalancer;

  @Before
  public void setUp() throws URISyntaxException {
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
        .build();
    nsRegistry.register(new FakeNameResolverProvider());
    when(helper.getNameResolverRegistry()).thenReturn(nsRegistry);
    when(helper.getNameResolverArgs()).thenReturn(args);
    when(helper.getSynchronizationContext()).thenReturn(syncContext);
    when(helper.getScheduledExecutorService()).thenReturn(fakeClock.getScheduledExecutorService());
    when(helper.getAuthority()).thenReturn(AUTHORITY);
    when(backoffPolicyProvider.get()).thenReturn(backoffPolicy1, backoffPolicy2);
    when(backoffPolicy1.nextBackoffNanos())
        .thenReturn(TimeUnit.SECONDS.toNanos(1L), TimeUnit.SECONDS.toNanos(10L));
    when(backoffPolicy2.nextBackoffNanos())
        .thenReturn(TimeUnit.SECONDS.toNanos(5L), TimeUnit.SECONDS.toNanos(50L));
    loadBalancer = new ClusterResolverLoadBalancer(helper, lbRegistry, backoffPolicyProvider);
  }

  @After
  public void tearDown() {
    loadBalancer.shutdown();
    assertThat(resolvers).isEmpty();
    assertThat(xdsClient.watchers).isEmpty();
    assertThat(xdsClientRefs).isEqualTo(0);
    assertThat(fakeClock.getPendingTasks()).isEmpty();
  }

  @Test
  public void edsClustersWithRingHashEndpointLbPolicy() {
    ClusterResolverConfig config = new ClusterResolverConfig(
        Collections.singletonList(edsDiscoveryMechanism1), ringHash);
    deliverLbConfig(config);
    assertThat(xdsClient.watchers.keySet()).containsExactly(EDS_SERVICE_NAME1);
    assertThat(childBalancers).isEmpty();

    // One priority with two localities of different weights.
    EquivalentAddressGroup endpoint1 = makeAddress("endpoint-addr-1");
    EquivalentAddressGroup endpoint2 = makeAddress("endpoint-addr-2");
    EquivalentAddressGroup endpoint3 = makeAddress("endpoint-addr-3");
    LocalityLbEndpoints localityLbEndpoints1 =
        LocalityLbEndpoints.create(
            Arrays.asList(
                LbEndpoint.create(endpoint1, 0 /* loadBalancingWeight */, true),
                LbEndpoint.create(endpoint2, 0 /* loadBalancingWeight */, true)),
            10 /* localityWeight */, 1 /* priority */);
    LocalityLbEndpoints localityLbEndpoints2 =
        LocalityLbEndpoints.create(
            Collections.singletonList(
                LbEndpoint.create(endpoint3, 60 /* loadBalancingWeight */, true)),
            50 /* localityWeight */, 1 /* priority */);
    xdsClient.deliverClusterLoadAssignment(
        EDS_SERVICE_NAME1,
        ImmutableMap.of(locality1, localityLbEndpoints1, locality2, localityLbEndpoints2));
    assertThat(childBalancers).hasSize(1);
    FakeLoadBalancer childBalancer = Iterables.getOnlyElement(childBalancers);
    assertThat(childBalancer.addresses).hasSize(3);
    EquivalentAddressGroup addr1 = childBalancer.addresses.get(0);
    EquivalentAddressGroup addr2 = childBalancer.addresses.get(1);
    EquivalentAddressGroup addr3 = childBalancer.addresses.get(2);
    // Endpoints in locality1 have no endpoint-level weight specified, so all endpoints within
    // locality1 are equally weighted.
    assertThat(addr1.getAddresses()).isEqualTo(endpoint1.getAddresses());
    assertThat(addr1.getAttributes().get(InternalXdsAttributes.ATTR_SERVER_WEIGHT))
        .isEqualTo(10);
    assertThat(addr2.getAddresses()).isEqualTo(endpoint2.getAddresses());
    assertThat(addr2.getAttributes().get(InternalXdsAttributes.ATTR_SERVER_WEIGHT))
        .isEqualTo(10);
    assertThat(addr3.getAddresses()).isEqualTo(endpoint3.getAddresses());
    assertThat(addr3.getAttributes().get(InternalXdsAttributes.ATTR_SERVER_WEIGHT))
        .isEqualTo(50 * 60);
    assertThat(childBalancer.name).isEqualTo(PRIORITY_POLICY_NAME);
    PriorityLbConfig priorityLbConfig = (PriorityLbConfig) childBalancer.config;
    assertThat(priorityLbConfig.priorities).containsExactly(CLUSTER1 + "[child1]");
    PriorityChildConfig priorityChildConfig =
        Iterables.getOnlyElement(priorityLbConfig.childConfigs.values());
    assertThat(priorityChildConfig.ignoreReresolution).isTrue();
    assertThat(priorityChildConfig.policySelection.getProvider().getPolicyName())
        .isEqualTo(CLUSTER_IMPL_POLICY_NAME);
    ClusterImplConfig clusterImplConfig =
        (ClusterImplConfig) priorityChildConfig.policySelection.getConfig();
    assertClusterImplConfig(clusterImplConfig, CLUSTER1, EDS_SERVICE_NAME1, LRS_SERVER_INFO, 100L,
        tlsContext, Collections.<DropOverload>emptyList(), "ring_hash_experimental");
    RingHashConfig ringHashConfig =
        (RingHashConfig) clusterImplConfig.childPolicy.getConfig();
    assertThat(ringHashConfig.minRingSize).isEqualTo(10L);
    assertThat(ringHashConfig.maxRingSize).isEqualTo(100L);
  }

  @Test
  public void edsClustersWithLeastRequestEndpointLbPolicy() {
    ClusterResolverConfig config = new ClusterResolverConfig(
        Collections.singletonList(edsDiscoveryMechanism1), leastRequest);
    deliverLbConfig(config);
    assertThat(xdsClient.watchers.keySet()).containsExactly(EDS_SERVICE_NAME1);
    assertThat(childBalancers).isEmpty();

    // Simple case with one priority and one locality
    EquivalentAddressGroup endpoint = makeAddress("endpoint-addr-1");
    LocalityLbEndpoints localityLbEndpoints =
        LocalityLbEndpoints.create(
            Arrays.asList(
                LbEndpoint.create(endpoint, 0 /* loadBalancingWeight */, true)),
            100 /* localityWeight */, 1 /* priority */);
    xdsClient.deliverClusterLoadAssignment(
        EDS_SERVICE_NAME1,
        ImmutableMap.of(locality1, localityLbEndpoints));
    assertThat(childBalancers).hasSize(1);
    FakeLoadBalancer childBalancer = Iterables.getOnlyElement(childBalancers);
    assertThat(childBalancer.addresses).hasSize(1);
    EquivalentAddressGroup addr = childBalancer.addresses.get(0);
    assertThat(addr.getAddresses()).isEqualTo(endpoint.getAddresses());
    assertThat(childBalancer.name).isEqualTo(PRIORITY_POLICY_NAME);
    PriorityLbConfig priorityLbConfig = (PriorityLbConfig) childBalancer.config;
    assertThat(priorityLbConfig.priorities).containsExactly(CLUSTER1 + "[child1]");
    PriorityChildConfig priorityChildConfig =
        Iterables.getOnlyElement(priorityLbConfig.childConfigs.values());
    assertThat(priorityChildConfig.policySelection.getProvider().getPolicyName())
        .isEqualTo(CLUSTER_IMPL_POLICY_NAME);
    ClusterImplConfig clusterImplConfig =
        (ClusterImplConfig) priorityChildConfig.policySelection.getConfig();
    assertClusterImplConfig(clusterImplConfig, CLUSTER1, EDS_SERVICE_NAME1, LRS_SERVER_INFO, 100L,
        tlsContext, Collections.<DropOverload>emptyList(), WRR_LOCALITY_POLICY_NAME);
    WrrLocalityConfig wrrLocalityConfig =
        (WrrLocalityConfig) clusterImplConfig.childPolicy.getConfig();
    assertThat(wrrLocalityConfig.childPolicy.getProvider().getPolicyName()).isEqualTo(
        "least_request_experimental");

    assertThat(
        childBalancer.addresses.get(0).getAttributes()
            .get(InternalXdsAttributes.ATTR_LOCALITY_WEIGHT)).isEqualTo(100);
  }

  @Test
  public void edsClustersWithOutlierDetection() {
    ClusterResolverConfig config = new ClusterResolverConfig(
        Collections.singletonList(edsDiscoveryMechanismWithOutlierDetection), leastRequest);
    deliverLbConfig(config);
    assertThat(xdsClient.watchers.keySet()).containsExactly(EDS_SERVICE_NAME1);
    assertThat(childBalancers).isEmpty();

    // Simple case with one priority and one locality
    EquivalentAddressGroup endpoint = makeAddress("endpoint-addr-1");
    LocalityLbEndpoints localityLbEndpoints =
        LocalityLbEndpoints.create(
            Arrays.asList(
                LbEndpoint.create(endpoint, 0 /* loadBalancingWeight */, true)),
            100 /* localityWeight */, 1 /* priority */);
    xdsClient.deliverClusterLoadAssignment(
        EDS_SERVICE_NAME1,
        ImmutableMap.of(locality1, localityLbEndpoints));
    assertThat(childBalancers).hasSize(1);
    FakeLoadBalancer childBalancer = Iterables.getOnlyElement(childBalancers);
    assertThat(childBalancer.addresses).hasSize(1);
    EquivalentAddressGroup addr = childBalancer.addresses.get(0);
    assertThat(addr.getAddresses()).isEqualTo(endpoint.getAddresses());
    assertThat(childBalancer.name).isEqualTo(PRIORITY_POLICY_NAME);
    PriorityLbConfig priorityLbConfig = (PriorityLbConfig) childBalancer.config;
    assertThat(priorityLbConfig.priorities).containsExactly(CLUSTER1 + "[child1]");
    PriorityChildConfig priorityChildConfig =
        Iterables.getOnlyElement(priorityLbConfig.childConfigs.values());

    // The child config for priority should be outlier detection.
    assertThat(priorityChildConfig.policySelection.getProvider().getPolicyName())
        .isEqualTo("outlier_detection_experimental");
    OutlierDetectionLoadBalancerConfig outlierDetectionConfig =
        (OutlierDetectionLoadBalancerConfig) priorityChildConfig.policySelection.getConfig();

    // The outlier detection config should faithfully represent what came down from xDS.
    assertThat(outlierDetectionConfig.intervalNanos).isEqualTo(outlierDetection.intervalNanos());
    assertThat(outlierDetectionConfig.baseEjectionTimeNanos).isEqualTo(
        outlierDetection.baseEjectionTimeNanos());
    assertThat(outlierDetectionConfig.baseEjectionTimeNanos).isEqualTo(
        outlierDetection.baseEjectionTimeNanos());
    assertThat(outlierDetectionConfig.maxEjectionTimeNanos).isEqualTo(
        outlierDetection.maxEjectionTimeNanos());
    assertThat(outlierDetectionConfig.maxEjectionPercent).isEqualTo(
        outlierDetection.maxEjectionPercent());

    OutlierDetectionLoadBalancerConfig.SuccessRateEjection successRateEjection
        = outlierDetectionConfig.successRateEjection;
    assertThat(successRateEjection.stdevFactor).isEqualTo(
        outlierDetection.successRateEjection().stdevFactor());
    assertThat(successRateEjection.enforcementPercentage).isEqualTo(
        outlierDetection.successRateEjection().enforcementPercentage());
    assertThat(successRateEjection.minimumHosts).isEqualTo(
        outlierDetection.successRateEjection().minimumHosts());
    assertThat(successRateEjection.requestVolume).isEqualTo(
        outlierDetection.successRateEjection().requestVolume());

    OutlierDetectionLoadBalancerConfig.FailurePercentageEjection failurePercentageEjection
        = outlierDetectionConfig.failurePercentageEjection;
    assertThat(failurePercentageEjection.threshold).isEqualTo(
        outlierDetection.failurePercentageEjection().threshold());
    assertThat(failurePercentageEjection.enforcementPercentage).isEqualTo(
        outlierDetection.failurePercentageEjection().enforcementPercentage());
    assertThat(failurePercentageEjection.minimumHosts).isEqualTo(
        outlierDetection.failurePercentageEjection().minimumHosts());
    assertThat(failurePercentageEjection.requestVolume).isEqualTo(
        outlierDetection.failurePercentageEjection().requestVolume());

    // The wrapped configuration should not have been tampered with.
    ClusterImplConfig clusterImplConfig =
        (ClusterImplConfig) outlierDetectionConfig.childPolicy.getConfig();
    assertClusterImplConfig(clusterImplConfig, CLUSTER1, EDS_SERVICE_NAME1, LRS_SERVER_INFO, 100L,
        tlsContext, Collections.<DropOverload>emptyList(), WRR_LOCALITY_POLICY_NAME);
    WrrLocalityConfig wrrLocalityConfig =
        (WrrLocalityConfig) clusterImplConfig.childPolicy.getConfig();
    assertThat(wrrLocalityConfig.childPolicy.getProvider().getPolicyName()).isEqualTo(
        "least_request_experimental");

    assertThat(
        childBalancer.addresses.get(0).getAttributes()
            .get(InternalXdsAttributes.ATTR_LOCALITY_WEIGHT)).isEqualTo(100);
  }


  @Test
  public void onlyEdsClusters_receivedEndpoints() {
    ClusterResolverConfig config = new ClusterResolverConfig(
        Arrays.asList(edsDiscoveryMechanism1, edsDiscoveryMechanism2), roundRobin);
    deliverLbConfig(config);
    assertThat(xdsClient.watchers.keySet()).containsExactly(EDS_SERVICE_NAME1, EDS_SERVICE_NAME2);
    assertThat(childBalancers).isEmpty();
    // CLUSTER1 has priority 1 (priority3), which has locality 2, which has endpoint3.
    // CLUSTER2 has priority 1 (priority1) and 2 (priority2); priority1 has locality1,
    // which has endpoint1 and endpoint2; priority2 has locality3, which has endpoint4.
    EquivalentAddressGroup endpoint1 = makeAddress("endpoint-addr-1");
    EquivalentAddressGroup endpoint2 = makeAddress("endpoint-addr-2");
    EquivalentAddressGroup endpoint3 = makeAddress("endpoint-addr-3");
    EquivalentAddressGroup endpoint4 = makeAddress("endpoint-addr-4");
    LocalityLbEndpoints localityLbEndpoints1 =
        LocalityLbEndpoints.create(
            Arrays.asList(
                LbEndpoint.create(endpoint1, 100, true),
                LbEndpoint.create(endpoint2, 100, true)),
            70 /* localityWeight */, 1 /* priority */);
    LocalityLbEndpoints localityLbEndpoints2 =
        LocalityLbEndpoints.create(
            Collections.singletonList(LbEndpoint.create(endpoint3, 100, true)),
            10 /* localityWeight */, 1 /* priority */);
    LocalityLbEndpoints localityLbEndpoints3 =
        LocalityLbEndpoints.create(
            Collections.singletonList(LbEndpoint.create(endpoint4, 100, true)),
            20 /* localityWeight */, 2 /* priority */);
    String priority1 = CLUSTER2 + "[child1]";
    String priority2 = CLUSTER2 + "[child2]";
    String priority3 = CLUSTER1 + "[child1]";

    // CLUSTER2: locality1 with priority 1 and locality3 with priority 2.
    xdsClient.deliverClusterLoadAssignment(
        EDS_SERVICE_NAME2,
        ImmutableMap.of(locality1, localityLbEndpoints1, locality3, localityLbEndpoints3));
    assertThat(childBalancers).isEmpty();  // not created until all clusters resolved

    // CLUSTER1: locality2 with priority 1.
    xdsClient.deliverClusterLoadAssignment(
        EDS_SERVICE_NAME1, Collections.singletonMap(locality2, localityLbEndpoints2));

    // Endpoints of all clusters have been resolved.
    assertThat(childBalancers).hasSize(1);
    FakeLoadBalancer childBalancer = Iterables.getOnlyElement(childBalancers);
    assertThat(childBalancer.name).isEqualTo(PRIORITY_POLICY_NAME);
    PriorityLbConfig priorityLbConfig = (PriorityLbConfig) childBalancer.config;
    assertThat(priorityLbConfig.priorities)
        .containsExactly(priority3, priority1, priority2).inOrder();

    PriorityChildConfig priorityChildConfig1 = priorityLbConfig.childConfigs.get(priority1);
    assertThat(priorityChildConfig1.ignoreReresolution).isTrue();
    assertThat(priorityChildConfig1.policySelection.getProvider().getPolicyName())
        .isEqualTo(CLUSTER_IMPL_POLICY_NAME);
    ClusterImplConfig clusterImplConfig1 =
        (ClusterImplConfig) priorityChildConfig1.policySelection.getConfig();
    assertClusterImplConfig(clusterImplConfig1, CLUSTER2, EDS_SERVICE_NAME2, LRS_SERVER_INFO, 200L,
        tlsContext, Collections.<DropOverload>emptyList(), WRR_LOCALITY_POLICY_NAME);
    assertThat(clusterImplConfig1.childPolicy.getConfig()).isInstanceOf(WrrLocalityConfig.class);
    WrrLocalityConfig wrrLocalityConfig1 =
        (WrrLocalityConfig) clusterImplConfig1.childPolicy.getConfig();
    assertThat(wrrLocalityConfig1.childPolicy.getProvider().getPolicyName()).isEqualTo(
        "round_robin");

    PriorityChildConfig priorityChildConfig2 = priorityLbConfig.childConfigs.get(priority2);
    assertThat(priorityChildConfig2.ignoreReresolution).isTrue();
    assertThat(priorityChildConfig2.policySelection.getProvider().getPolicyName())
        .isEqualTo(CLUSTER_IMPL_POLICY_NAME);
    ClusterImplConfig clusterImplConfig2 =
        (ClusterImplConfig) priorityChildConfig2.policySelection.getConfig();
    assertClusterImplConfig(clusterImplConfig2, CLUSTER2, EDS_SERVICE_NAME2, LRS_SERVER_INFO, 200L,
        tlsContext, Collections.<DropOverload>emptyList(), WRR_LOCALITY_POLICY_NAME);
    assertThat(clusterImplConfig2.childPolicy.getConfig()).isInstanceOf(WrrLocalityConfig.class);
    WrrLocalityConfig wrrLocalityConfig2 =
        (WrrLocalityConfig) clusterImplConfig1.childPolicy.getConfig();
    assertThat(wrrLocalityConfig2.childPolicy.getProvider().getPolicyName()).isEqualTo(
        "round_robin");

    PriorityChildConfig priorityChildConfig3 = priorityLbConfig.childConfigs.get(priority3);
    assertThat(priorityChildConfig3.ignoreReresolution).isTrue();
    assertThat(priorityChildConfig3.policySelection.getProvider().getPolicyName())
        .isEqualTo(CLUSTER_IMPL_POLICY_NAME);
    ClusterImplConfig clusterImplConfig3 =
        (ClusterImplConfig) priorityChildConfig3.policySelection.getConfig();
    assertClusterImplConfig(clusterImplConfig3, CLUSTER1, EDS_SERVICE_NAME1, LRS_SERVER_INFO, 100L,
        tlsContext, Collections.<DropOverload>emptyList(), WRR_LOCALITY_POLICY_NAME);
    assertThat(clusterImplConfig3.childPolicy.getConfig()).isInstanceOf(WrrLocalityConfig.class);
    WrrLocalityConfig wrrLocalityConfig3 =
        (WrrLocalityConfig) clusterImplConfig1.childPolicy.getConfig();
    assertThat(wrrLocalityConfig3.childPolicy.getProvider().getPolicyName()).isEqualTo(
        "round_robin");

    for (EquivalentAddressGroup eag : childBalancer.addresses) {
      if (eag.getAttributes().get(InternalXdsAttributes.ATTR_LOCALITY) == locality1) {
        assertThat(eag.getAttributes().get(InternalXdsAttributes.ATTR_LOCALITY_WEIGHT))
            .isEqualTo(70);
      }
      if (eag.getAttributes().get(InternalXdsAttributes.ATTR_LOCALITY) == locality2) {
        assertThat(eag.getAttributes().get(InternalXdsAttributes.ATTR_LOCALITY_WEIGHT))
            .isEqualTo(10);
      }
      if (eag.getAttributes().get(InternalXdsAttributes.ATTR_LOCALITY) == locality3) {
        assertThat(eag.getAttributes().get(InternalXdsAttributes.ATTR_LOCALITY_WEIGHT))
            .isEqualTo(20);
      }
    }
  }

  @SuppressWarnings("unchecked")
  private void verifyEdsPriorityNames(List<String> want,
                                      Map<Locality, LocalityLbEndpoints>... updates) {
    ClusterResolverConfig config = new ClusterResolverConfig(
        Arrays.asList(edsDiscoveryMechanism2), roundRobin);
    deliverLbConfig(config);
    assertThat(xdsClient.watchers.keySet()).containsExactly(EDS_SERVICE_NAME2);
    assertThat(childBalancers).isEmpty();

    for (Map<Locality, LocalityLbEndpoints> update: updates) {
      xdsClient.deliverClusterLoadAssignment(
          EDS_SERVICE_NAME2,
          update);
    }
    assertThat(childBalancers).hasSize(1);
    FakeLoadBalancer childBalancer = Iterables.getOnlyElement(childBalancers);
    assertThat(childBalancer.name).isEqualTo(PRIORITY_POLICY_NAME);
    PriorityLbConfig priorityLbConfig = (PriorityLbConfig) childBalancer.config;
    assertThat(priorityLbConfig.priorities).isEqualTo(want);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void edsUpdatePriorityName_twoPriorities() {
    verifyEdsPriorityNames(Arrays.asList(CLUSTER2 + "[child1]", CLUSTER2 + "[child2]"),
        ImmutableMap.of(locality1, createEndpoints(1),
            locality2, createEndpoints(2)
        ));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void edsUpdatePriorityName_addOnePriority() {
    verifyEdsPriorityNames(Arrays.asList(CLUSTER2 + "[child2]"),
        ImmutableMap.of(locality1, createEndpoints(1)),
        ImmutableMap.of(locality2, createEndpoints(1)
        ));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void edsUpdatePriorityName_swapTwoPriorities() {
    verifyEdsPriorityNames(Arrays.asList(CLUSTER2 + "[child2]", CLUSTER2 + "[child1]",
            CLUSTER2 + "[child3]"),
        ImmutableMap.of(locality1, createEndpoints(1),
            locality2, createEndpoints(2),
            locality3, createEndpoints(3)
        ),
        ImmutableMap.of(locality1, createEndpoints(2),
            locality2, createEndpoints(1),
            locality3, createEndpoints(3))
    );
  }

  @Test
  @SuppressWarnings("unchecked")
  public void edsUpdatePriorityName_mergeTwoPriorities() {
    verifyEdsPriorityNames(Arrays.asList(CLUSTER2 + "[child3]", CLUSTER2 + "[child1]"),
        ImmutableMap.of(locality1, createEndpoints(1),
          locality3, createEndpoints(3),
          locality2, createEndpoints(2)),
        ImmutableMap.of(locality1, createEndpoints(2),
            locality3, createEndpoints(1),
            locality2, createEndpoints(1)
        ));
  }

  private LocalityLbEndpoints createEndpoints(int priority) {
    return LocalityLbEndpoints.create(
        Arrays.asList(
            LbEndpoint.create(makeAddress("endpoint-addr-1"), 100, true),
            LbEndpoint.create(makeAddress("endpoint-addr-2"), 100, true)),
        70 /* localityWeight */, priority /* priority */);
  }

  @Test
  public void onlyEdsClusters_resourceNeverExist_returnErrorPicker() {
    ClusterResolverConfig config = new ClusterResolverConfig(
        Arrays.asList(edsDiscoveryMechanism1, edsDiscoveryMechanism2), roundRobin);
    deliverLbConfig(config);
    assertThat(xdsClient.watchers.keySet()).containsExactly(EDS_SERVICE_NAME1, EDS_SERVICE_NAME2);
    assertThat(childBalancers).isEmpty();
    reset(helper);
    xdsClient.deliverResourceNotFound(EDS_SERVICE_NAME1);
    verify(helper, never()).updateBalancingState(
        any(ConnectivityState.class), any(SubchannelPicker.class));  // wait for CLUSTER2's results

    xdsClient.deliverResourceNotFound(EDS_SERVICE_NAME2);
    verify(helper).updateBalancingState(
        eq(ConnectivityState.TRANSIENT_FAILURE), pickerCaptor.capture());
    assertPicker(
        pickerCaptor.getValue(),
        Status.UNAVAILABLE.withDescription(
            "No usable endpoint from cluster(s): " + Arrays.asList(CLUSTER1, CLUSTER2)),
        null);
  }

  @Test
  public void onlyEdsClusters_allResourcesRevoked_shutDownChildLbPolicy() {
    ClusterResolverConfig config = new ClusterResolverConfig(
        Arrays.asList(edsDiscoveryMechanism1, edsDiscoveryMechanism2), roundRobin);
    deliverLbConfig(config);
    assertThat(xdsClient.watchers.keySet()).containsExactly(EDS_SERVICE_NAME1, EDS_SERVICE_NAME2);
    assertThat(childBalancers).isEmpty();
    reset(helper);
    EquivalentAddressGroup endpoint1 = makeAddress("endpoint-addr-1");
    EquivalentAddressGroup endpoint2 = makeAddress("endpoint-addr-2");
    LocalityLbEndpoints localityLbEndpoints1 =
        LocalityLbEndpoints.create(
            Collections.singletonList(LbEndpoint.create(endpoint1, 100, true)),
            10 /* localityWeight */, 1 /* priority */);
    LocalityLbEndpoints localityLbEndpoints2 =
        LocalityLbEndpoints.create(
            Collections.singletonList(LbEndpoint.create(endpoint2, 100, true)),
            20 /* localityWeight */, 2 /* priority */);
    xdsClient.deliverClusterLoadAssignment(
        EDS_SERVICE_NAME1, Collections.singletonMap(locality1, localityLbEndpoints1));
    xdsClient.deliverClusterLoadAssignment(
        EDS_SERVICE_NAME2, Collections.singletonMap(locality2, localityLbEndpoints2));
    assertThat(childBalancers).hasSize(1);  // child LB policy created
    FakeLoadBalancer childBalancer = Iterables.getOnlyElement(childBalancers);
    assertThat(((PriorityLbConfig) childBalancer.config).priorities).hasSize(2);
    assertAddressesEqual(Arrays.asList(endpoint1, endpoint2), childBalancer.addresses);

    xdsClient.deliverResourceNotFound(EDS_SERVICE_NAME2);
    xdsClient.deliverResourceNotFound(EDS_SERVICE_NAME1);
    verify(helper).updateBalancingState(
        eq(ConnectivityState.TRANSIENT_FAILURE), pickerCaptor.capture());
    Status expectedError = Status.UNAVAILABLE.withDescription(
        "No usable endpoint from cluster(s): " + Arrays.asList(CLUSTER1, CLUSTER2));
    assertPicker(pickerCaptor.getValue(), expectedError, null);
  }

  @Test
  public void handleEdsResource_ignoreUnhealthyEndpoints() {
    ClusterResolverConfig config =
        new ClusterResolverConfig(Collections.singletonList(edsDiscoveryMechanism1), roundRobin);
    deliverLbConfig(config);
    EquivalentAddressGroup endpoint1 = makeAddress("endpoint-addr-1");
    EquivalentAddressGroup endpoint2 = makeAddress("endpoint-addr-2");
    LocalityLbEndpoints localityLbEndpoints =
        LocalityLbEndpoints.create(
            Arrays.asList(
                LbEndpoint.create(endpoint1, 100, false /* isHealthy */),
                LbEndpoint.create(endpoint2, 100, true /* isHealthy */)),
            10 /* localityWeight */, 1 /* priority */);
    xdsClient.deliverClusterLoadAssignment(
        EDS_SERVICE_NAME1, Collections.singletonMap(locality1, localityLbEndpoints));
    FakeLoadBalancer childBalancer = Iterables.getOnlyElement(childBalancers);
    assertThat(childBalancer.addresses).hasSize(1);
    assertAddressesEqual(Collections.singletonList(endpoint2), childBalancer.addresses);
  }

  @Test
  public void handleEdsResource_ignoreLocalitiesWithNoHealthyEndpoints() {
    ClusterResolverConfig config =
        new ClusterResolverConfig(Collections.singletonList(edsDiscoveryMechanism1), roundRobin);
    deliverLbConfig(config);
    EquivalentAddressGroup endpoint1 = makeAddress("endpoint-addr-1");
    EquivalentAddressGroup endpoint2 = makeAddress("endpoint-addr-2");
    LocalityLbEndpoints localityLbEndpoints1 =
        LocalityLbEndpoints.create(
            Collections.singletonList(LbEndpoint.create(endpoint1, 100, false /* isHealthy */)),
            10 /* localityWeight */, 1 /* priority */);
    LocalityLbEndpoints localityLbEndpoints2 =
        LocalityLbEndpoints.create(
            Collections.singletonList(LbEndpoint.create(endpoint2, 100, true /* isHealthy */)),
            10 /* localityWeight */, 1 /* priority */);
    xdsClient.deliverClusterLoadAssignment(
        EDS_SERVICE_NAME1,
        ImmutableMap.of(locality1, localityLbEndpoints1, locality2, localityLbEndpoints2));

    FakeLoadBalancer childBalancer = Iterables.getOnlyElement(childBalancers);
    for (EquivalentAddressGroup eag : childBalancer.addresses) {
      assertThat(eag.getAttributes().get(InternalXdsAttributes.ATTR_LOCALITY)).isEqualTo(locality2);
    }
  }

  @Test
  public void handleEdsResource_ignorePrioritiesWithNoHealthyEndpoints() {
    ClusterResolverConfig config =
        new ClusterResolverConfig(Collections.singletonList(edsDiscoveryMechanism1), roundRobin);
    deliverLbConfig(config);
    EquivalentAddressGroup endpoint1 = makeAddress("endpoint-addr-1");
    EquivalentAddressGroup endpoint2 = makeAddress("endpoint-addr-2");
    LocalityLbEndpoints localityLbEndpoints1 =
        LocalityLbEndpoints.create(
            Collections.singletonList(LbEndpoint.create(endpoint1, 100, false /* isHealthy */)),
            10 /* localityWeight */, 1 /* priority */);
    LocalityLbEndpoints localityLbEndpoints2 =
        LocalityLbEndpoints.create(
            Collections.singletonList(LbEndpoint.create(endpoint2, 200, true /* isHealthy */)),
            10 /* localityWeight */, 2 /* priority */);
    String priority2 = CLUSTER1 + "[child2]";
    xdsClient.deliverClusterLoadAssignment(
        EDS_SERVICE_NAME1,
        ImmutableMap.of(locality1, localityLbEndpoints1, locality2, localityLbEndpoints2));

    FakeLoadBalancer childBalancer = Iterables.getOnlyElement(childBalancers);
    assertThat(((PriorityLbConfig) childBalancer.config).priorities).containsExactly(priority2);
  }

  @Test
  public void handleEdsResource_noHealthyEndpoint() {
    ClusterResolverConfig config =
        new ClusterResolverConfig(Collections.singletonList(edsDiscoveryMechanism1), roundRobin);
    deliverLbConfig(config);
    EquivalentAddressGroup endpoint = makeAddress("endpoint-addr-1");
    LocalityLbEndpoints localityLbEndpoints =
        LocalityLbEndpoints.create(
            Collections.singletonList(LbEndpoint.create(endpoint, 100, false /* isHealthy */)),
            10 /* localityWeight */, 1 /* priority */);
    xdsClient.deliverClusterLoadAssignment(EDS_SERVICE_NAME1,
        Collections.singletonMap(locality1, localityLbEndpoints));  // single endpoint, unhealthy

    assertThat(childBalancers).isEmpty();
    verify(helper).updateBalancingState(
        eq(ConnectivityState.TRANSIENT_FAILURE), pickerCaptor.capture());
    assertPicker(
        pickerCaptor.getValue(),
        Status.UNAVAILABLE.withDescription(
            "No usable endpoint from cluster(s): " + Collections.singleton(CLUSTER1)),
        null);
  }

  @Test
  public void onlyLogicalDnsCluster_endpointsResolved() {
    ClusterResolverConfig config = new ClusterResolverConfig(
        Collections.singletonList(logicalDnsDiscoveryMechanism), roundRobin);
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
    assertThat(priorityChildConfig.policySelection.getProvider().getPolicyName())
        .isEqualTo(CLUSTER_IMPL_POLICY_NAME);
    ClusterImplConfig clusterImplConfig =
        (ClusterImplConfig) priorityChildConfig.policySelection.getConfig();
    assertClusterImplConfig(clusterImplConfig, CLUSTER_DNS, null, LRS_SERVER_INFO, 300L, null,
        Collections.<DropOverload>emptyList(), "pick_first");
    assertAddressesEqual(Arrays.asList(endpoint1, endpoint2), childBalancer.addresses);
  }

  @Test
  public void onlyLogicalDnsCluster_handleRefreshNameResolution() {
    ClusterResolverConfig config = new ClusterResolverConfig(
        Collections.singletonList(logicalDnsDiscoveryMechanism), roundRobin);
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
  public void onlyLogicalDnsCluster_resolutionError_backoffAndRefresh() {
    InOrder inOrder = Mockito.inOrder(helper, backoffPolicyProvider,
        backoffPolicy1, backoffPolicy2);
    ClusterResolverConfig config = new ClusterResolverConfig(
        Collections.singletonList(logicalDnsDiscoveryMechanism), roundRobin);
    deliverLbConfig(config);
    FakeNameResolver resolver = assertResolverCreated("/" + DNS_HOST_NAME);
    assertThat(childBalancers).isEmpty();
    Status error = Status.UNAVAILABLE.withDescription("cannot reach DNS server");
    resolver.deliverError(error);
    inOrder.verify(helper).updateBalancingState(
        eq(ConnectivityState.TRANSIENT_FAILURE), pickerCaptor.capture());
    assertPicker(pickerCaptor.getValue(), error, null);
    assertThat(resolver.refreshCount).isEqualTo(0);
    inOrder.verify(backoffPolicyProvider).get();
    inOrder.verify(backoffPolicy1).nextBackoffNanos();
    assertThat(fakeClock.getPendingTasks()).hasSize(1);
    assertThat(Iterables.getOnlyElement(fakeClock.getPendingTasks()).getDelay(TimeUnit.SECONDS))
        .isEqualTo(1L);
    fakeClock.forwardTime(1L, TimeUnit.SECONDS);
    assertThat(resolver.refreshCount).isEqualTo(1);

    error = Status.UNKNOWN.withDescription("I am lost");
    resolver.deliverError(error);
    inOrder.verify(helper).updateBalancingState(
        eq(ConnectivityState.TRANSIENT_FAILURE), pickerCaptor.capture());
    inOrder.verify(backoffPolicy1).nextBackoffNanos();
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
    InOrder inOrder = Mockito.inOrder(backoffPolicyProvider, backoffPolicy1, backoffPolicy2);
    ClusterResolverConfig config = new ClusterResolverConfig(
        Collections.singletonList(logicalDnsDiscoveryMechanism), roundRobin);
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
    inOrder.verify(backoffPolicyProvider).get();
    inOrder.verify(backoffPolicy1).nextBackoffNanos();
    assertThat(fakeClock.getPendingTasks()).hasSize(1);
    ScheduledTask task = Iterables.getOnlyElement(fakeClock.getPendingTasks());
    assertThat(task.getDelay(TimeUnit.SECONDS)).isEqualTo(1L);

    fakeClock.forwardTime( 100L, TimeUnit.MILLISECONDS);
    childBalancer.helper.refreshNameResolution();
    assertThat(resolver.refreshCount).isEqualTo(2);
    assertThat(task.isCancelled()).isTrue();
    assertThat(fakeClock.getPendingTasks()).isEmpty();
    resolver.deliverError(Status.UNAVAILABLE.withDescription("I am still lost"));
    inOrder.verify(backoffPolicyProvider).get();  // active refresh resets backoff sequence
    inOrder.verify(backoffPolicy2).nextBackoffNanos();
    task = Iterables.getOnlyElement(fakeClock.getPendingTasks());
    assertThat(task.getDelay(TimeUnit.SECONDS)).isEqualTo(5L);

    fakeClock.forwardTime(5L, TimeUnit.SECONDS);
    assertThat(resolver.refreshCount).isEqualTo(3);
    inOrder.verifyNoMoreInteractions();
  }

  @Test
  public void edsClustersAndLogicalDnsCluster_receivedEndpoints() {
    ClusterResolverConfig config = new ClusterResolverConfig(
        Arrays.asList(edsDiscoveryMechanism1, logicalDnsDiscoveryMechanism), roundRobin);
    deliverLbConfig(config);
    assertThat(xdsClient.watchers.keySet()).containsExactly(EDS_SERVICE_NAME1);
    FakeNameResolver resolver = assertResolverCreated("/" + DNS_HOST_NAME);
    assertThat(childBalancers).isEmpty();
    EquivalentAddressGroup endpoint1 = makeAddress("endpoint-addr-1");  // DNS endpoint
    EquivalentAddressGroup endpoint2 = makeAddress("endpoint-addr-2");  // DNS endpoint
    EquivalentAddressGroup endpoint3 = makeAddress("endpoint-addr-3");  // EDS endpoint
    resolver.deliverEndpointAddresses(Arrays.asList(endpoint1, endpoint2));
    LocalityLbEndpoints localityLbEndpoints =
        LocalityLbEndpoints.create(
            Collections.singletonList(LbEndpoint.create(endpoint3, 100, true)),
            10 /* localityWeight */, 1 /* priority */);
    xdsClient.deliverClusterLoadAssignment(
        EDS_SERVICE_NAME1, Collections.singletonMap(locality1, localityLbEndpoints));

    assertThat(childBalancers).hasSize(1);
    FakeLoadBalancer childBalancer = Iterables.getOnlyElement(childBalancers);
    assertThat(((PriorityLbConfig) childBalancer.config).priorities)
        .containsExactly(CLUSTER1 + "[child1]", CLUSTER_DNS + "[child0]").inOrder();
    assertAddressesEqual(Arrays.asList(endpoint3, endpoint1, endpoint2),
        childBalancer.addresses);  // ordered by cluster then addresses
    assertAddressesEqual(AddressFilter.filter(AddressFilter.filter(
        childBalancer.addresses, CLUSTER1 + "[child1]"), locality1.toString()),
        Collections.singletonList(endpoint3));
    assertAddressesEqual(AddressFilter.filter(AddressFilter.filter(
        childBalancer.addresses, CLUSTER_DNS + "[child0]"),
        Locality.create("", "", "").toString()),
        Arrays.asList(endpoint1, endpoint2));
  }

  @Test
  public void noEdsResourceExists_useDnsResolutionResults() {
    ClusterResolverConfig config = new ClusterResolverConfig(
        Arrays.asList(edsDiscoveryMechanism1, logicalDnsDiscoveryMechanism), roundRobin);
    deliverLbConfig(config);
    assertThat(xdsClient.watchers.keySet()).containsExactly(EDS_SERVICE_NAME1);
    FakeNameResolver resolver = assertResolverCreated("/" + DNS_HOST_NAME);
    assertThat(childBalancers).isEmpty();
    reset(helper);
    xdsClient.deliverResourceNotFound(EDS_SERVICE_NAME1);
    verify(helper, never()).updateBalancingState(
        any(ConnectivityState.class), any(SubchannelPicker.class));  // wait for DNS results

    EquivalentAddressGroup endpoint1 = makeAddress("endpoint-addr-1");
    EquivalentAddressGroup endpoint2 = makeAddress("endpoint-addr-2");
    resolver.deliverEndpointAddresses(Arrays.asList(endpoint1, endpoint2));
    assertThat(childBalancers).hasSize(1);
    FakeLoadBalancer childBalancer = Iterables.getOnlyElement(childBalancers);
    String priority = Iterables.getOnlyElement(
        ((PriorityLbConfig) childBalancer.config).priorities);
    assertThat(priority).isEqualTo(CLUSTER_DNS + "[child0]");
    assertAddressesEqual(Arrays.asList(endpoint1, endpoint2), childBalancer.addresses);
  }

  @Test
  public void edsResourceRevoked_dnsResolutionError_shutDownChildLbPolicyAndReturnErrorPicker() {
    ClusterResolverConfig config = new ClusterResolverConfig(
        Arrays.asList(edsDiscoveryMechanism1, logicalDnsDiscoveryMechanism), roundRobin);
    deliverLbConfig(config);
    assertThat(xdsClient.watchers.keySet()).containsExactly(EDS_SERVICE_NAME1);
    FakeNameResolver resolver = assertResolverCreated("/" + DNS_HOST_NAME);
    assertThat(childBalancers).isEmpty();
    reset(helper);
    EquivalentAddressGroup endpoint = makeAddress("endpoint-addr-1");
    LocalityLbEndpoints localityLbEndpoints =
        LocalityLbEndpoints.create(
            Collections.singletonList(LbEndpoint.create(endpoint, 100, true)),
            10 /* localityWeight */, 1 /* priority */);
    xdsClient.deliverClusterLoadAssignment(
        EDS_SERVICE_NAME1, Collections.singletonMap(locality1, localityLbEndpoints));
    resolver.deliverError(Status.UNKNOWN.withDescription("I am lost"));
    assertThat(childBalancers).hasSize(1);
    FakeLoadBalancer childBalancer = Iterables.getOnlyElement(childBalancers);
    assertThat(((PriorityLbConfig) childBalancer.config).priorities)
        .containsExactly(CLUSTER1 + "[child1]");
    assertAddressesEqual(Collections.singletonList(endpoint), childBalancer.addresses);
    assertThat(childBalancer.shutdown).isFalse();
    xdsClient.deliverResourceNotFound(EDS_SERVICE_NAME1);
    assertThat(childBalancer.shutdown).isTrue();
    verify(helper).updateBalancingState(
        eq(ConnectivityState.TRANSIENT_FAILURE), pickerCaptor.capture());
    assertPicker(pickerCaptor.getValue(),
        Status.UNAVAILABLE.withDescription("I am lost"), null);
  }

  @Test
  public void resolutionErrorAfterChildLbCreated_propagateErrorIfAllClustersEncounterError() {
    ClusterResolverConfig config = new ClusterResolverConfig(
        Arrays.asList(edsDiscoveryMechanism1, logicalDnsDiscoveryMechanism), roundRobin);
    deliverLbConfig(config);
    assertThat(xdsClient.watchers.keySet()).containsExactly(EDS_SERVICE_NAME1);
    FakeNameResolver resolver = assertResolverCreated("/" + DNS_HOST_NAME);
    assertThat(childBalancers).isEmpty();
    reset(helper);
    EquivalentAddressGroup endpoint = makeAddress("endpoint-addr-1");
    LocalityLbEndpoints localityLbEndpoints =
        LocalityLbEndpoints.create(
            Collections.singletonList(LbEndpoint.create(endpoint, 100, true)),
            10 /* localityWeight */, 1 /* priority */);
    xdsClient.deliverClusterLoadAssignment(
        EDS_SERVICE_NAME1, Collections.singletonMap(locality1, localityLbEndpoints));
    assertThat(childBalancers).isEmpty();  // not created until all clusters resolved.

    resolver.deliverError(Status.UNKNOWN.withDescription("I am lost"));

    // DNS resolution failed, but there are EDS endpoints can be used.
    assertThat(childBalancers).hasSize(1);
    FakeLoadBalancer childBalancer = Iterables.getOnlyElement(childBalancers);  // child LB created
    assertThat(childBalancer.upstreamError).isNull();  // should not propagate error to child LB
    assertAddressesEqual(Collections.singletonList(endpoint), childBalancer.addresses);

    xdsClient.deliverError(Status.RESOURCE_EXHAUSTED.withDescription("out of memory"));
    assertThat(childBalancer.upstreamError).isNotNull();  // last cluster's (DNS) error propagated
    assertThat(childBalancer.upstreamError.getCode()).isEqualTo(Code.UNKNOWN);
    assertThat(childBalancer.upstreamError.getDescription()).isEqualTo("I am lost");
    assertThat(childBalancer.shutdown).isFalse();
    verify(helper, never()).updateBalancingState(
        eq(ConnectivityState.TRANSIENT_FAILURE), any(SubchannelPicker.class));
  }

  @Test
  public void resolutionErrorBeforeChildLbCreated_returnErrorPickerIfAllClustersEncounterError() {
    ClusterResolverConfig config = new ClusterResolverConfig(
        Arrays.asList(edsDiscoveryMechanism1, logicalDnsDiscoveryMechanism), roundRobin);
    deliverLbConfig(config);
    assertThat(xdsClient.watchers.keySet()).containsExactly(EDS_SERVICE_NAME1);
    FakeNameResolver resolver = assertResolverCreated("/" + DNS_HOST_NAME);
    assertThat(childBalancers).isEmpty();
    reset(helper);
    xdsClient.deliverError(Status.UNIMPLEMENTED.withDescription("not found"));
    assertThat(childBalancers).isEmpty();
    verify(helper, never()).updateBalancingState(
        eq(ConnectivityState.TRANSIENT_FAILURE), any(SubchannelPicker.class));  // wait for DNS
    Status dnsError = Status.UNKNOWN.withDescription("I am lost");
    resolver.deliverError(dnsError);
    verify(helper).updateBalancingState(
        eq(ConnectivityState.TRANSIENT_FAILURE), pickerCaptor.capture());
    assertPicker(
        pickerCaptor.getValue(),
        Status.UNAVAILABLE.withDescription(dnsError.getDescription()),
        null);
  }

  @Test
  public void resolutionErrorBeforeChildLbCreated_edsOnly_returnErrorPicker() {
    ClusterResolverConfig config = new ClusterResolverConfig(
        Arrays.asList(edsDiscoveryMechanism1), roundRobin);
    deliverLbConfig(config);
    assertThat(xdsClient.watchers.keySet()).containsExactly(EDS_SERVICE_NAME1);
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
  public void handleNameResolutionErrorFromUpstream_beforeChildLbCreated_returnErrorPicker() {
    ClusterResolverConfig config = new ClusterResolverConfig(
        Arrays.asList(edsDiscoveryMechanism1, logicalDnsDiscoveryMechanism), roundRobin);
    deliverLbConfig(config);
    assertThat(xdsClient.watchers.keySet()).containsExactly(EDS_SERVICE_NAME1);
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
  public void handleNameResolutionErrorFromUpstream_afterChildLbCreated_fallThrough() {
    ClusterResolverConfig config = new ClusterResolverConfig(
        Arrays.asList(edsDiscoveryMechanism1, logicalDnsDiscoveryMechanism), roundRobin);
    deliverLbConfig(config);
    assertThat(xdsClient.watchers.keySet()).containsExactly(EDS_SERVICE_NAME1);
    FakeNameResolver resolver = assertResolverCreated("/" + DNS_HOST_NAME);
    assertThat(childBalancers).isEmpty();
    reset(helper);
    EquivalentAddressGroup endpoint1 = makeAddress("endpoint-addr-1");
    EquivalentAddressGroup endpoint2 = makeAddress("endpoint-addr-2");
    LocalityLbEndpoints localityLbEndpoints =
        LocalityLbEndpoints.create(
            Collections.singletonList(LbEndpoint.create(endpoint1, 100, true)),
            10 /* localityWeight */, 1 /* priority */);
    xdsClient.deliverClusterLoadAssignment(
        EDS_SERVICE_NAME1, Collections.singletonMap(locality1, localityLbEndpoints));
    resolver.deliverEndpointAddresses(Collections.singletonList(endpoint2));
    assertThat(childBalancers).hasSize(1);
    FakeLoadBalancer childBalancer = Iterables.getOnlyElement(childBalancers);
    assertThat(((PriorityLbConfig) childBalancer.config).priorities)
        .containsExactly(CLUSTER1 + "[child1]", CLUSTER_DNS + "[child0]");
    assertAddressesEqual(Arrays.asList(endpoint1, endpoint2), childBalancer.addresses);

    loadBalancer.handleNameResolutionError(Status.UNAVAILABLE.withDescription("unreachable"));
    assertThat(childBalancer.upstreamError.getCode()).isEqualTo(Code.UNAVAILABLE);
    assertThat(childBalancer.upstreamError.getDescription()).isEqualTo("unreachable");
    verify(helper, never()).updateBalancingState(
        any(ConnectivityState.class), any(SubchannelPicker.class));
  }

  private void deliverLbConfig(ClusterResolverConfig config) {
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder()
            .setAddresses(Collections.<EquivalentAddressGroup>emptyList())
            .setAttributes(
                // Other attributes not used by cluster_resolver LB are omitted.
                Attributes.newBuilder()
                    .set(InternalXdsAttributes.XDS_CLIENT_POOL, xdsClientPool)
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
    assertThat(config.childPolicy.getProvider().getPolicyName()).isEqualTo(childPolicy);
  }

  /** Asserts two list of EAGs contains same addresses, regardless of attributes. */
  private static void assertAddressesEqual(
      List<EquivalentAddressGroup> expected, List<EquivalentAddressGroup> actual) {
    assertThat(actual.size()).isEqualTo(expected.size());
    for (int i = 0; i < actual.size(); i++) {
      assertThat(actual.get(i).getAddresses()).isEqualTo(expected.get(i).getAddresses());
    }
  }

  private static EquivalentAddressGroup makeAddress(final String name) {
    class FakeSocketAddress extends SocketAddress {
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

    return new EquivalentAddressGroup(new FakeSocketAddress(name));
  }

  private static final class FakeXdsClient extends XdsClient {
    private final Map<String, ResourceWatcher<EdsUpdate>> watchers = new HashMap<>();


    @Override
    @SuppressWarnings("unchecked")
    <T extends ResourceUpdate> void watchXdsResource(XdsResourceType<T> type, String resourceName,
                          ResourceWatcher<T> watcher) {
      assertThat(type.typeName()).isEqualTo("EDS");
      assertThat(watchers).doesNotContainKey(resourceName);
      watchers.put(resourceName, (ResourceWatcher<EdsUpdate>) watcher);
    }

    @Override
    @SuppressWarnings("unchecked")
    <T extends ResourceUpdate> void cancelXdsResourceWatch(XdsResourceType<T> type,
                                                           String resourceName,
                                                           ResourceWatcher<T> watcher) {
      assertThat(type.typeName()).isEqualTo("EDS");
      assertThat(watchers).containsKey(resourceName);
      watchers.remove(resourceName);
    }

    void deliverClusterLoadAssignment(
        String resource, Map<Locality, LocalityLbEndpoints> localityLbEndpointsMap) {
      deliverClusterLoadAssignment(
          resource, Collections.<DropOverload>emptyList(), localityLbEndpointsMap);
    }

    void deliverClusterLoadAssignment(String resource, List<DropOverload> dropOverloads,
        Map<Locality, LocalityLbEndpoints> localityLbEndpointsMap) {
      if (watchers.containsKey(resource)) {
        watchers.get(resource).onChanged(
            new XdsEndpointResource.EdsUpdate(resource, localityLbEndpointsMap, dropOverloads));
      }
    }

    void deliverResourceNotFound(String resource) {
      if (watchers.containsKey(resource)) {
        watchers.get(resource).onResourceDoesNotExist(resource);
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
    private Listener2 listener;
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

    private void deliverEndpointAddresses(List<EquivalentAddressGroup> addresses) {
      listener.onResult(ResolutionResult.newBuilder().setAddresses(addresses).build());
    }

    private void deliverError(Status error) {
      listener.onError(error);
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
    public boolean acceptResolvedAddresses(ResolvedAddresses resolvedAddresses) {
      addresses = resolvedAddresses.getAddresses();
      config = resolvedAddresses.getLoadBalancingPolicyConfig();
      return true;
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
