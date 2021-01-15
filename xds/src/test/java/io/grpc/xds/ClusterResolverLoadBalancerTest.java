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
import static io.grpc.xds.XdsLbPolicies.LRS_POLICY_NAME;
import static io.grpc.xds.XdsLbPolicies.PRIORITY_POLICY_NAME;
import static io.grpc.xds.XdsLbPolicies.WEIGHTED_TARGET_POLICY_NAME;
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
import io.grpc.xds.ClusterImplLoadBalancerProvider.ClusterImplConfig;
import io.grpc.xds.ClusterResolverLoadBalancerProvider.ClusterResolverConfig;
import io.grpc.xds.ClusterResolverLoadBalancerProvider.ClusterResolverConfig.DiscoveryMechanism;
import io.grpc.xds.EnvoyProtoData.DropOverload;
import io.grpc.xds.EnvoyProtoData.LbEndpoint;
import io.grpc.xds.EnvoyProtoData.Locality;
import io.grpc.xds.EnvoyProtoData.LocalityLbEndpoints;
import io.grpc.xds.EnvoyServerProtoData.UpstreamTlsContext;
import io.grpc.xds.LrsLoadBalancerProvider.LrsConfig;
import io.grpc.xds.PriorityLoadBalancerProvider.PriorityLbConfig;
import io.grpc.xds.PriorityLoadBalancerProvider.PriorityLbConfig.PriorityChildConfig;
import io.grpc.xds.WeightedTargetLoadBalancerProvider.WeightedPolicySelection;
import io.grpc.xds.WeightedTargetLoadBalancerProvider.WeightedTargetConfig;
import io.grpc.xds.internal.sds.CommonTlsContextTestsUtil;
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
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

/** Tests for {@link ClusterResolverLoadBalancer}. */
@RunWith(JUnit4.class)
public class ClusterResolverLoadBalancerTest {
  private static final String AUTHORITY = "api.google.com";
  private static final String CLUSTER1 = "cluster-foo.googleapis.com";
  private static final String CLUSTER2 = "cluster-bar.googleapis.com";
  private static final String CLUSTER_DNS = "cluster-dns.googleapis.com";
  private static final String EDS_SERVICE_NAME1 = "backend-service-foo.googleapis.com";
  private static final String EDS_SERVICE_NAME2 = "backend-service-bar.googleapis.com";
  private static final String LRS_SERVER_NAME = "lrs.googleapis.com";
  private final Locality locality1 =
      new Locality("test-region-1", "test-zone-1", "test-subzone-1");
  private final Locality locality2 =
      new Locality("test-region-2", "test-zone-2", "test-subzone-2");
  private final Locality locality3 =
      new Locality("test-region-3", "test-zone-3", "test-subzone-3");
  private final UpstreamTlsContext tlsContext =
      CommonTlsContextTestsUtil.buildUpstreamTlsContextFromFilenames(
          CommonTlsContextTestsUtil.CLIENT_KEY_FILE,
          CommonTlsContextTestsUtil.CLIENT_PEM_FILE,
          CommonTlsContextTestsUtil.CA_PEM_FILE);

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
  private final PolicySelection roundRobin =
      new PolicySelection(new FakeLoadBalancerProvider("round_robin"), null);
  private final PolicySelection weightedTarget =
      new PolicySelection(new FakeLoadBalancerProvider(WEIGHTED_TARGET_POLICY_NAME), null);
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
    MockitoAnnotations.initMocks(this);

    lbRegistry.register(new FakeLoadBalancerProvider(PRIORITY_POLICY_NAME));
    lbRegistry.register(new FakeLoadBalancerProvider(CLUSTER_IMPL_POLICY_NAME));
    lbRegistry.register(new FakeLoadBalancerProvider(LRS_POLICY_NAME));
    lbRegistry.register(
        new FakeLoadBalancerProvider("pick_first")); // needed by logical_dns
    URI targetUri = new URI(AUTHORITY);
    NameResolver.Args args = NameResolver.Args.newBuilder()
        .setDefaultPort(8080)
        .setProxyDetector(GrpcUtil.NOOP_PROXY_DETECTOR)
        .setSynchronizationContext(syncContext)
        .setServiceConfigParser(mock(ServiceConfigParser.class))
        .setChannelLogger(mock(ChannelLogger.class))
        .build();
    nsRegistry.register(new FakeNameResolverProvider(targetUri));
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
  public void onlyEdsClusters_receivedEndpoints() {
    deliverConfigWithEdsClusters();  // CLUSTER1 and CLUSTER2
    // CLUSTER1 has priority 1 (priority3), which has locality 2, which has endpoint3.
    // CLUSTER2 has priority 1 (priority1) and 2 (priority2); priority1 has locality1,
    // which has endpoint1 and endpoint2; priority2 has locality3, which has endpoint4.
    EquivalentAddressGroup endpoint1 = makeAddress("endpoint-addr-1");
    EquivalentAddressGroup endpoint2 = makeAddress("endpoint-addr-2");
    EquivalentAddressGroup endpoint3 = makeAddress("endpoint-addr-3");
    EquivalentAddressGroup endpoint4 = makeAddress("endpoint-addr-4");
    LocalityLbEndpoints localityLbEndpoints1 =
        buildLocalityLbEndpoints(1, 70, ImmutableMap.of(endpoint1, true, endpoint2, true));
    LocalityLbEndpoints localityLbEndpoints2 =
        buildLocalityLbEndpoints(1, 10, Collections.singletonMap(endpoint3, true));
    LocalityLbEndpoints localityLbEndpoints3 =
        buildLocalityLbEndpoints(2, 20, Collections.singletonMap(endpoint4, true));
    String priority1 = CLUSTER2 + "[priority1]";
    String priority2 = CLUSTER2 + "[priority2]";
    String priority3 = CLUSTER1 + "[priority1]";

    // First deliver CLUSTER2's endpoints, two priorities with each has one locality.
    xdsClient.deliverClusterLoadAssignment(
        EDS_SERVICE_NAME2,
        ImmutableMap.of(locality1, localityLbEndpoints1, locality3, localityLbEndpoints3));
    assertThat(childBalancers).hasSize(1);
    FakeLoadBalancer childBalancer = Iterables.getOnlyElement(childBalancers);
    assertThat(childBalancer.name).isEqualTo(PRIORITY_POLICY_NAME);
    PriorityLbConfig priorityLbConfig = (PriorityLbConfig) childBalancer.config;
    assertThat(priorityLbConfig.priorities).containsExactly(priority1, priority2).inOrder();
    PriorityChildConfig priorityChildConfig = priorityLbConfig.childConfigs.get(priority1);
    assertThat(priorityChildConfig.ignoreReresolution).isTrue();
    assertThat(priorityChildConfig.policySelection.getProvider().getPolicyName())
        .isEqualTo(CLUSTER_IMPL_POLICY_NAME);
    ClusterImplConfig clusterImplConfig =
        (ClusterImplConfig) priorityChildConfig.policySelection.getConfig();
    assertClusterImplConfig(clusterImplConfig, CLUSTER2, EDS_SERVICE_NAME2, LRS_SERVER_NAME, 200L,
        tlsContext, Collections.<DropOverload>emptyList(), WEIGHTED_TARGET_POLICY_NAME);
    WeightedTargetConfig weightedTargetConfig =
        (WeightedTargetConfig) clusterImplConfig.childPolicy.getConfig();
    assertThat(weightedTargetConfig.targets.keySet()).containsExactly(locality1.toString());
    WeightedPolicySelection target = weightedTargetConfig.targets.get(locality1.toString());
    assertThat(target.weight).isEqualTo(70);
    assertThat(target.policySelection.getProvider().getPolicyName()).isEqualTo(LRS_POLICY_NAME);
    assertLrsConfig((LrsConfig) target.policySelection.getConfig(), CLUSTER2, EDS_SERVICE_NAME2,
        LRS_SERVER_NAME, locality1, "round_robin");

    priorityChildConfig = priorityLbConfig.childConfigs.get(priority2);
    assertThat(priorityChildConfig.ignoreReresolution).isTrue();
    assertThat(priorityChildConfig.policySelection.getProvider().getPolicyName())
        .isEqualTo(CLUSTER_IMPL_POLICY_NAME);
    clusterImplConfig = (ClusterImplConfig) priorityChildConfig.policySelection.getConfig();
    assertClusterImplConfig(clusterImplConfig, CLUSTER2, EDS_SERVICE_NAME2, LRS_SERVER_NAME, 200L,
        tlsContext, Collections.<DropOverload>emptyList(), WEIGHTED_TARGET_POLICY_NAME);
    weightedTargetConfig = (WeightedTargetConfig) clusterImplConfig.childPolicy.getConfig();
    assertThat(weightedTargetConfig.targets.keySet()).containsExactly(locality3.toString());
    target = weightedTargetConfig.targets.get(locality3.toString());
    assertThat(target.weight).isEqualTo(20);
    assertThat(target.policySelection.getProvider().getPolicyName()).isEqualTo(LRS_POLICY_NAME);
    assertLrsConfig((LrsConfig) target.policySelection.getConfig(), CLUSTER2, EDS_SERVICE_NAME2,
        LRS_SERVER_NAME, locality3, "round_robin");
    List<EquivalentAddressGroup> priorityAddrs1 =
        AddressFilter.filter(childBalancer.addresses, priority1);
    assertThat(priorityAddrs1).hasSize(2);
    assertAddressesEqual(Arrays.asList(endpoint1, endpoint2), priorityAddrs1);
    List<EquivalentAddressGroup> priorityAddrs2 =
        AddressFilter.filter(childBalancer.addresses, priority2);
    assertThat(priorityAddrs2).hasSize(1);
    assertAddressesEqual(Collections.singletonList(endpoint4), priorityAddrs2);

    // Then deliver CLUSTER1's endpoints, one priority with one locality.
    xdsClient.deliverClusterLoadAssignment(
        EDS_SERVICE_NAME1, Collections.singletonMap(locality2, localityLbEndpoints2));

    priorityLbConfig = (PriorityLbConfig) childBalancer.config;
    assertThat(priorityLbConfig.priorities)
        .containsExactly(priority3, priority1, priority2).inOrder();

    priorityChildConfig = priorityLbConfig.childConfigs.get(priority3);
    assertThat(priorityChildConfig.ignoreReresolution).isTrue();
    assertThat(priorityChildConfig.policySelection.getProvider().getPolicyName())
        .isEqualTo(CLUSTER_IMPL_POLICY_NAME);
    clusterImplConfig = (ClusterImplConfig) priorityChildConfig.policySelection.getConfig();
    assertClusterImplConfig(clusterImplConfig, CLUSTER1, EDS_SERVICE_NAME1, LRS_SERVER_NAME, 100L,
        tlsContext, Collections.<DropOverload>emptyList(), WEIGHTED_TARGET_POLICY_NAME);
    weightedTargetConfig = (WeightedTargetConfig) clusterImplConfig.childPolicy.getConfig();
    assertThat(weightedTargetConfig.targets.keySet()).containsExactly(locality2.toString());
    target = weightedTargetConfig.targets.get(locality2.toString());
    assertThat(target.weight).isEqualTo(10);
    assertThat(target.policySelection.getProvider().getPolicyName()).isEqualTo(LRS_POLICY_NAME);
    assertLrsConfig((LrsConfig) target.policySelection.getConfig(), CLUSTER1, EDS_SERVICE_NAME1,
        LRS_SERVER_NAME, locality2, "round_robin");
    List<EquivalentAddressGroup> priorityAddrs3 =
        AddressFilter.filter(childBalancer.addresses, priority3);
    assertThat(priorityAddrs3).hasSize(1);
    assertAddressesEqual(Collections.singletonList(endpoint3), priorityAddrs3);
  }

  @Test
  public void onlyEdsClusters_resourceNeverExist_returnErrorPicker() {
    deliverConfigWithEdsClusters();  // CLUSTER1 and CLUSTER2
    reset(helper);
    xdsClient.deliverResourceNotFound(EDS_SERVICE_NAME1);
    verify(helper).updateBalancingState(eq(ConnectivityState.CONNECTING), pickerCaptor.capture());
    PickResult result = pickerCaptor.getValue().pickSubchannel(mock(PickSubchannelArgs.class));
    assertThat(result.getStatus().isOk()).isTrue();
    assertThat(result.getSubchannel()).isNull();  // buffer picker expected

    xdsClient.deliverResourceNotFound(EDS_SERVICE_NAME2);
    verify(helper).updateBalancingState(
        eq(ConnectivityState.TRANSIENT_FAILURE), pickerCaptor.capture());
    Status expectedError = Status.UNAVAILABLE.withDescription("No usable endpoint");
    assertPicker(pickerCaptor.getValue(), expectedError, null);
  }

  @Test
  public void onlyEdsClusters_allResourcesRevoked_shutDownChildLbPolicy() {
    deliverConfigWithEdsClusters();  // CLUSTER1 and CLUSTER2
    reset(helper);
    EquivalentAddressGroup endpoint1 = makeAddress("endpoint-addr-1");
    EquivalentAddressGroup endpoint2 = makeAddress("endpoint-addr-2");
    LocalityLbEndpoints localityLbEndpoints1 =
        buildLocalityLbEndpoints(1, 10, Collections.singletonMap(endpoint1, true));
    LocalityLbEndpoints localityLbEndpoints2 =
        buildLocalityLbEndpoints(2, 20, Collections.singletonMap(endpoint2, true));
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
    Status expectedError = Status.UNAVAILABLE.withDescription("No usable endpoint");
    assertPicker(pickerCaptor.getValue(), expectedError, null);
  }

  private void deliverConfigWithEdsClusters() {
    DiscoveryMechanism instance1 =
        DiscoveryMechanism.forEds(CLUSTER1, EDS_SERVICE_NAME1, LRS_SERVER_NAME, 100L, tlsContext);
    DiscoveryMechanism instance2 =
        DiscoveryMechanism.forEds(CLUSTER2, EDS_SERVICE_NAME2, LRS_SERVER_NAME, 200L, tlsContext);
    ClusterResolverConfig config =
        new ClusterResolverConfig(Arrays.asList(instance1, instance2), weightedTarget, roundRobin);
    deliverLbConfig(config);
    assertThat(xdsClient.watchers.keySet()).containsExactly(EDS_SERVICE_NAME1, EDS_SERVICE_NAME2);
    assertThat(childBalancers).isEmpty();
  }

  @Test
  public void handleEdsResource_ignoreUnhealthyEndpoints() {
    deliverConfigWithSingleEdsCluster();  // CLUSTER1
    EquivalentAddressGroup endpoint1 = makeAddress("endpoint-addr-1");
    EquivalentAddressGroup endpoint2 = makeAddress("endpoint-addr-2");
    LocalityLbEndpoints localityLbEndpoints =
        buildLocalityLbEndpoints(1, 10, ImmutableMap.of(endpoint1, false, endpoint2, true));
    xdsClient.deliverClusterLoadAssignment(
        EDS_SERVICE_NAME1, Collections.singletonMap(locality1, localityLbEndpoints));
    FakeLoadBalancer childBalancer = Iterables.getOnlyElement(childBalancers);
    assertThat(childBalancer.addresses).hasSize(1);
    assertAddressesEqual(Collections.singletonList(endpoint2), childBalancer.addresses);
  }

  @Test
  public void handleEdsResource_ignoreLocalitiesWithNoHealthyEndpoints() {
    deliverConfigWithSingleEdsCluster();  // CLUSTER1
    EquivalentAddressGroup endpoint1 = makeAddress("endpoint-addr-1");
    EquivalentAddressGroup endpoint2 = makeAddress("endpoint-addr-2");
    LocalityLbEndpoints localityLbEndpoints1 =
        buildLocalityLbEndpoints(1, 10, Collections.singletonMap(endpoint1, false));
    LocalityLbEndpoints localityLbEndpoints2 =
        buildLocalityLbEndpoints(1, 10, Collections.singletonMap(endpoint2, true));
    String priority = CLUSTER1 + "[priority1]";
    xdsClient.deliverClusterLoadAssignment(
        EDS_SERVICE_NAME1,
        ImmutableMap.of(locality1, localityLbEndpoints1, locality2, localityLbEndpoints2));

    FakeLoadBalancer childBalancer = Iterables.getOnlyElement(childBalancers);
    PriorityLbConfig priorityLbConfig = (PriorityLbConfig) childBalancer.config;
    PriorityChildConfig priorityChildConfig = priorityLbConfig.childConfigs.get(priority);
    ClusterImplConfig clusterImplConfig =
        (ClusterImplConfig) priorityChildConfig.policySelection.getConfig();
    WeightedTargetConfig weightedTargetConfig =
        (WeightedTargetConfig) clusterImplConfig.childPolicy.getConfig();
    assertThat(weightedTargetConfig.targets.keySet()).containsExactly(locality2.toString());
  }

  @Test
  public void handleEdsResource_ignorePrioritiesWithNoHealthyEndpoints() {
    deliverConfigWithSingleEdsCluster();   // CLUSTER1
    EquivalentAddressGroup endpoint1 = makeAddress("endpoint-addr-1");
    EquivalentAddressGroup endpoint2 = makeAddress("endpoint-addr-2");
    LocalityLbEndpoints localityLbEndpoints1 =
        buildLocalityLbEndpoints(1, 10, Collections.singletonMap(endpoint1, false));
    LocalityLbEndpoints localityLbEndpoints2 =
        buildLocalityLbEndpoints(2, 10, Collections.singletonMap(endpoint2, true));
    String priority2 = CLUSTER1 + "[priority2]";
    xdsClient.deliverClusterLoadAssignment(
        EDS_SERVICE_NAME1,
        ImmutableMap.of(locality1, localityLbEndpoints1, locality2, localityLbEndpoints2));

    FakeLoadBalancer childBalancer = Iterables.getOnlyElement(childBalancers);
    PriorityLbConfig config = (PriorityLbConfig) childBalancer.config;
    assertThat(config.priorities).containsExactly(priority2);
  }

  @Test
  public void handleEdsResource_noHealthyEndpoint() {
    deliverConfigWithSingleEdsCluster();   // CLUSTER1
    EquivalentAddressGroup endpoint = makeAddress("endpoint-addr-1");
    LocalityLbEndpoints localityLbEndpoints =
        buildLocalityLbEndpoints(1, 10, Collections.singletonMap(endpoint, false));
    xdsClient.deliverClusterLoadAssignment(EDS_SERVICE_NAME1,
        Collections.singletonMap(locality1, localityLbEndpoints));  // single endpoint, unhealthy

    assertThat(childBalancers).isEmpty();
    verify(helper).updateBalancingState(
        eq(ConnectivityState.TRANSIENT_FAILURE), pickerCaptor.capture());
    assertPicker(pickerCaptor.getValue(),
        Status.UNAVAILABLE.withDescription("No usable endpoint"), null);
  }

  private void deliverConfigWithSingleEdsCluster() {
    DiscoveryMechanism instance =
        DiscoveryMechanism.forEds(CLUSTER1, EDS_SERVICE_NAME1, LRS_SERVER_NAME, 100L, null);
    ClusterResolverConfig config =
        new ClusterResolverConfig(Collections.singletonList(instance), weightedTarget, roundRobin);
    deliverLbConfig(config);
    assertThat(xdsClient.watchers.keySet()).containsExactly(EDS_SERVICE_NAME1);
    assertThat(childBalancers).isEmpty();
  }

  @Test
  public void onlyLogicalDnsCluster_endpointsResolved() {
    deliverConfigWithSingleLogicalDnsCluster();
    EquivalentAddressGroup endpoint1 = makeAddress("endpoint-addr-1");
    EquivalentAddressGroup endpoint2 = makeAddress("endpoint-addr-2");
    FakeNameResolver resolver = Iterables.getOnlyElement(resolvers);
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
    assertClusterImplConfig(clusterImplConfig, CLUSTER_DNS, null, LRS_SERVER_NAME, 100L, null,
        Collections.<DropOverload>emptyList(), LRS_POLICY_NAME);
    LrsConfig lrsConfig = (LrsConfig) clusterImplConfig.childPolicy.getConfig();
    assertLrsConfig(lrsConfig, CLUSTER_DNS, null, LRS_SERVER_NAME,
        new Locality("", "", ""), "pick_first");  // hardcoded override
    assertAddressesEqual(Arrays.asList(endpoint1, endpoint2), childBalancer.addresses);
  }

  @Test
  public void onlyLogicalDnsCluster_handleRefreshNameResolution() {
    deliverConfigWithSingleLogicalDnsCluster();
    EquivalentAddressGroup endpoint1 = makeAddress("endpoint-addr-1");
    EquivalentAddressGroup endpoint2 = makeAddress("endpoint-addr-2");
    FakeNameResolver resolver = Iterables.getOnlyElement(resolvers);
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
    deliverConfigWithSingleLogicalDnsCluster();
    FakeNameResolver resolver = Iterables.getOnlyElement(resolvers);
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
    deliverConfigWithSingleLogicalDnsCluster();
    EquivalentAddressGroup endpoint = makeAddress("endpoint-addr");
    FakeNameResolver resolver = Iterables.getOnlyElement(resolvers);
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

  private void deliverConfigWithSingleLogicalDnsCluster() {
    DiscoveryMechanism instance =
        DiscoveryMechanism.forLogicalDns(CLUSTER_DNS, LRS_SERVER_NAME, 100L, null);
    ClusterResolverConfig config =
        new ClusterResolverConfig(Collections.singletonList(instance), weightedTarget, roundRobin);
    deliverLbConfig(config);
    assertThat(resolvers).hasSize(1);
    assertThat(childBalancers).isEmpty();
  }

  @Test
  public void edsClustersAndLogicalDnsCluster_receivedEndpoints() {
    deliverConfigWithEdsAndLogicalDnsClusters();  // CLUSTER1 and CLUSTER_DNS
    EquivalentAddressGroup endpoint1 = makeAddress("endpoint-addr-1");  // DNS endpoint
    EquivalentAddressGroup endpoint2 = makeAddress("endpoint-addr-2");  // DNS endpoint
    EquivalentAddressGroup endpoint3 = makeAddress("endpoint-addr-3");  // EDS endpoint
    FakeNameResolver resolver = Iterables.getOnlyElement(resolvers);
    resolver.deliverEndpointAddresses(Arrays.asList(endpoint1, endpoint2));
    LocalityLbEndpoints localityLbEndpoints =
        buildLocalityLbEndpoints(1, 10, Collections.singletonMap(endpoint3, true));
    xdsClient.deliverClusterLoadAssignment(
        EDS_SERVICE_NAME1, Collections.singletonMap(locality1, localityLbEndpoints));

    assertThat(childBalancers).hasSize(1);
    FakeLoadBalancer childBalancer = Iterables.getOnlyElement(childBalancers);
    assertThat(((PriorityLbConfig) childBalancer.config).priorities)
        .containsExactly(CLUSTER1 + "[priority1]", CLUSTER_DNS + "[priority0]").inOrder();
    assertAddressesEqual(Arrays.asList(endpoint3, endpoint1, endpoint2),
        childBalancer.addresses);  // ordered by cluster then addresses
    assertAddressesEqual(AddressFilter.filter(AddressFilter.filter(
        childBalancer.addresses, CLUSTER1 + "[priority1]"), locality1.toString()),
        Collections.singletonList(endpoint3));
    assertAddressesEqual(AddressFilter.filter(AddressFilter.filter(
        childBalancer.addresses, CLUSTER_DNS + "[priority0]"),
        new Locality("", "", "").toString()),
        Arrays.asList(endpoint1, endpoint2));
  }

  @Test
  public void noEdsResourceExists_useDnsResolutionResults() {
    deliverConfigWithEdsAndLogicalDnsClusters();
    reset(helper);
    xdsClient.deliverResourceNotFound(EDS_SERVICE_NAME1);
    verify(helper).updateBalancingState(eq(ConnectivityState.CONNECTING), pickerCaptor.capture());
    PickResult result = pickerCaptor.getValue().pickSubchannel(mock(PickSubchannelArgs.class));
    assertThat(result.getStatus().isOk()).isTrue();
    assertThat(result.getSubchannel()).isNull();  // buffer picker expected, waiting for DNS

    FakeNameResolver resolver = Iterables.getOnlyElement(resolvers);
    EquivalentAddressGroup endpoint1 = makeAddress("endpoint-addr-1");
    EquivalentAddressGroup endpoint2 = makeAddress("endpoint-addr-2");
    resolver.deliverEndpointAddresses(Arrays.asList(endpoint1, endpoint2));
    assertThat(childBalancers).hasSize(1);
    FakeLoadBalancer childBalancer = Iterables.getOnlyElement(childBalancers);
    String priority = Iterables.getOnlyElement(
        ((PriorityLbConfig) childBalancer.config).priorities);
    assertThat(priority).isEqualTo(CLUSTER_DNS + "[priority0]");
    assertAddressesEqual(Arrays.asList(endpoint1, endpoint2), childBalancer.addresses);
  }

  @Test
  public void edsResourceRevoked_dnsResolutionError_shutDownChildLbPolicyAndReturnErrorPicker() {
    deliverConfigWithEdsAndLogicalDnsClusters();
    reset(helper);
    EquivalentAddressGroup endpoint = makeAddress("endpoint-addr-1");
    LocalityLbEndpoints localityLbEndpoints =
        buildLocalityLbEndpoints(1, 10, Collections.singletonMap(endpoint, true));
    xdsClient.deliverClusterLoadAssignment(
        EDS_SERVICE_NAME1, Collections.singletonMap(locality1, localityLbEndpoints));
    FakeNameResolver resolver = Iterables.getOnlyElement(resolvers);
    resolver.deliverError(Status.UNKNOWN.withDescription("I am lost"));
    assertThat(childBalancers).hasSize(1);
    FakeLoadBalancer childBalancer = Iterables.getOnlyElement(childBalancers);
    assertThat(((PriorityLbConfig) childBalancer.config).priorities)
        .containsExactly(CLUSTER1 + "[priority1]");
    assertAddressesEqual(Collections.singletonList(endpoint), childBalancer.addresses);
    assertThat(childBalancer.shutdown).isFalse();
    xdsClient.deliverResourceNotFound(EDS_SERVICE_NAME1);
    assertThat(childBalancer.shutdown).isTrue();
    verify(helper).updateBalancingState(
        eq(ConnectivityState.TRANSIENT_FAILURE), pickerCaptor.capture());
    assertPicker(pickerCaptor.getValue(),
        Status.UNAVAILABLE.withDescription("No usable endpoint"), null);
  }

  @Test
  public void resolutionErrorAfterChildLbCreated_propagateErrorIfAllClustersEncounterError() {
    deliverConfigWithEdsAndLogicalDnsClusters();
    reset(helper);
    EquivalentAddressGroup endpoint = makeAddress("endpoint-addr-1");
    LocalityLbEndpoints localityLbEndpoints =
        buildLocalityLbEndpoints(1, 10, Collections.singletonMap(endpoint, true));
    xdsClient.deliverClusterLoadAssignment(
        EDS_SERVICE_NAME1, Collections.singletonMap(locality1, localityLbEndpoints));
    FakeLoadBalancer childBalancer = Iterables.getOnlyElement(childBalancers);  // child LB created
    FakeNameResolver resolver = Iterables.getOnlyElement(resolvers);
    resolver.deliverError(Status.UNKNOWN.withDescription("I am lost"));
    assertThat(childBalancer.upstreamError).isNull();  // should not propagate error to child LB
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
    deliverConfigWithEdsAndLogicalDnsClusters();
    reset(helper);
    xdsClient.deliverError(Status.UNIMPLEMENTED.withDescription("not found"));
    assertThat(childBalancers).isEmpty();
    verify(helper, never()).updateBalancingState(
        eq(ConnectivityState.TRANSIENT_FAILURE), any(SubchannelPicker.class));  // wait for DNS
    FakeNameResolver resolver = Iterables.getOnlyElement(resolvers);
    Status dnsError = Status.UNKNOWN.withDescription("I am lost");
    resolver.deliverError(dnsError);
    verify(helper).updateBalancingState(
        eq(ConnectivityState.TRANSIENT_FAILURE), pickerCaptor.capture());
    assertPicker(pickerCaptor.getValue(), dnsError, null);
  }

  @Test
  public void handleNameResolutionErrorFromUpstream_beforeChildLbCreated_returnErrorPicker() {
    deliverConfigWithEdsAndLogicalDnsClusters();
    reset(helper);
    Status upstreamError = Status.UNAVAILABLE.withDescription("unreachable");
    loadBalancer.handleNameResolutionError(upstreamError);
    verify(helper).updateBalancingState(
        eq(ConnectivityState.TRANSIENT_FAILURE), pickerCaptor.capture());
    assertPicker(pickerCaptor.getValue(), upstreamError, null);
  }

  @Test
  public void handleNameResolutionErrorFromUpstream_afterChildLbCreated_fallThrough() {
    deliverConfigWithEdsAndLogicalDnsClusters();
    reset(helper);
    EquivalentAddressGroup endpoint1 = makeAddress("endpoint-addr-1");
    EquivalentAddressGroup endpoint2 = makeAddress("endpoint-addr-2");
    LocalityLbEndpoints localityLbEndpoints =
        buildLocalityLbEndpoints(1, 10, Collections.singletonMap(endpoint1, true));
    xdsClient.deliverClusterLoadAssignment(
        EDS_SERVICE_NAME1, Collections.singletonMap(locality1, localityLbEndpoints));
    FakeNameResolver resolver = Iterables.getOnlyElement(resolvers);
    resolver.deliverEndpointAddresses(Collections.singletonList(endpoint2));
    assertThat(childBalancers).hasSize(1);
    FakeLoadBalancer childBalancer = Iterables.getOnlyElement(childBalancers);
    assertThat(((PriorityLbConfig) childBalancer.config).priorities)
        .containsExactly(CLUSTER1 + "[priority1]", CLUSTER_DNS + "[priority0]");
    assertAddressesEqual(Arrays.asList(endpoint1, endpoint2), childBalancer.addresses);

    loadBalancer.handleNameResolutionError(Status.UNAVAILABLE.withDescription("unreachable"));
    assertThat(childBalancer.upstreamError.getCode()).isEqualTo(Code.UNAVAILABLE);
    assertThat(childBalancer.upstreamError.getDescription()).isEqualTo("unreachable");
    verify(helper, never()).updateBalancingState(
        any(ConnectivityState.class), any(SubchannelPicker.class));
  }

  private void deliverConfigWithEdsAndLogicalDnsClusters() {
    DiscoveryMechanism instance1 =
        DiscoveryMechanism.forEds(CLUSTER1, EDS_SERVICE_NAME1, LRS_SERVER_NAME, 100L, null);
    DiscoveryMechanism instance2 =
        DiscoveryMechanism.forLogicalDns(CLUSTER_DNS, LRS_SERVER_NAME, 200L, null);
    ClusterResolverConfig config =
        new ClusterResolverConfig(Arrays.asList(instance1, instance2), weightedTarget, roundRobin);
    deliverLbConfig(config);
    assertThat(xdsClient.watchers.keySet()).containsExactly(EDS_SERVICE_NAME1);
    assertThat(resolvers).hasSize(1);
    assertThat(childBalancers).isEmpty();
  }

  private void deliverLbConfig(ClusterResolverConfig config) {
    loadBalancer.handleResolvedAddresses(
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
      @Nullable String edsServiceName, String lrsServerName, Long maxConcurrentRequests,
      @Nullable UpstreamTlsContext tlsContext, List<DropOverload> dropCategories,
      String childPolicy) {
    assertThat(config.cluster).isEqualTo(cluster);
    assertThat(config.edsServiceName).isEqualTo(edsServiceName);
    assertThat(config.lrsServerName).isEqualTo(lrsServerName);
    assertThat(config.maxConcurrentRequests).isEqualTo(maxConcurrentRequests);
    assertThat(config.tlsContext).isEqualTo(tlsContext);
    assertThat(config.dropCategories).isEqualTo(dropCategories);
    assertThat(config.childPolicy.getProvider().getPolicyName()).isEqualTo(childPolicy);
  }

  private static void assertLrsConfig(LrsConfig config, String cluster,
      @Nullable String edsServiceName, String lrsServerName, Locality locality,
      String childPolicy) {
    assertThat(config.clusterName).isEqualTo(cluster);
    assertThat(config.edsServiceName).isEqualTo(edsServiceName);
    assertThat(config.lrsServerName).isEqualTo(lrsServerName);
    assertThat(config.locality).isEqualTo(locality);
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

  private static LocalityLbEndpoints buildLocalityLbEndpoints(
      int priority, int localityWeight, Map<EquivalentAddressGroup, Boolean> managedEndpoints) {
    List<LbEndpoint> endpoints = new ArrayList<>();
    for (EquivalentAddressGroup addr : managedEndpoints.keySet()) {
      boolean status = managedEndpoints.get(addr);
      endpoints.add(new LbEndpoint(addr, 100 /* unused */, status));
    }
    return new LocalityLbEndpoints(endpoints, localityWeight, priority);
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
    private final Map<String, EdsResourceWatcher> watchers = new HashMap<>();

    @Override
    void watchEdsResource(String resourceName, EdsResourceWatcher watcher) {
      assertThat(watchers).doesNotContainKey(resourceName);
      watchers.put(resourceName, watcher);
    }

    @Override
    void cancelEdsResourceWatch(String resourceName, EdsResourceWatcher watcher) {
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
            new EdsUpdate(resource, localityLbEndpointsMap, dropOverloads));
      }
    }

    void deliverResourceNotFound(String resource) {
      if (watchers.containsKey(resource)) {
        watchers.get(resource).onResourceDoesNotExist(resource);
      }
    }

    void deliverError(Status error) {
      for (EdsResourceWatcher watcher : watchers.values()) {
        watcher.onError(error);
      }
    }
  }

  private class FakeNameResolverProvider extends NameResolverProvider {
    private final URI expectedUri;

    FakeNameResolverProvider(URI expectedUri) {
      this.expectedUri = expectedUri;
    }

    @Override
    public NameResolver newNameResolver(URI targetUri, NameResolver.Args args) {
      if (expectedUri.equals(targetUri)) {
        FakeNameResolver resolver = new FakeNameResolver(targetUri);
        resolvers.add(resolver);
        return resolver;
      }
      return null;
    }

    @Override
    public String getDefaultScheme() {
      return "fake";
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
    private final URI uri;
    private Listener2 listener;
    private int refreshCount;

    FakeNameResolver(URI uri) {
      this.uri = uri;
    }

    @Override
    public String getServiceAuthority() {
      return uri.getAuthority();
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
    public void handleResolvedAddresses(ResolvedAddresses resolvedAddresses) {
      addresses = resolvedAddresses.getAddresses();
      config = resolvedAddresses.getLoadBalancingPolicyConfig();
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

    void deliverSubchannelState(final Subchannel subchannel, ConnectivityState state) {
      SubchannelPicker picker = new SubchannelPicker() {
        @Override
        public PickResult pickSubchannel(PickSubchannelArgs args) {
          return PickResult.withSubchannel(subchannel);
        }
      };
      helper.updateBalancingState(state, picker);
    }
  }
}
