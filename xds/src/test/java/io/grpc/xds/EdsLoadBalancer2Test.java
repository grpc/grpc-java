/*
 * Copyright 2019 The gRPC Authors
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
import static io.grpc.xds.XdsLbPolicies.LRS_POLICY_NAME;
import static io.grpc.xds.XdsLbPolicies.PRIORITY_POLICY_NAME;
import static io.grpc.xds.XdsLbPolicies.WEIGHTED_TARGET_POLICY_NAME;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import io.grpc.Attributes;
import io.grpc.ConnectivityState;
import io.grpc.EquivalentAddressGroup;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancer.CreateSubchannelArgs;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancer.PickResult;
import io.grpc.LoadBalancer.PickSubchannelArgs;
import io.grpc.LoadBalancer.ResolvedAddresses;
import io.grpc.LoadBalancer.Subchannel;
import io.grpc.LoadBalancer.SubchannelPicker;
import io.grpc.LoadBalancerProvider;
import io.grpc.LoadBalancerRegistry;
import io.grpc.ManagedChannel;
import io.grpc.NameResolver;
import io.grpc.Status;
import io.grpc.Status.Code;
import io.grpc.SynchronizationContext;
import io.grpc.internal.FakeClock;
import io.grpc.internal.ObjectPool;
import io.grpc.internal.ServiceConfigUtil.PolicySelection;
import io.grpc.xds.EdsLoadBalancerProvider.EdsConfig;
import io.grpc.xds.EnvoyProtoData.DropOverload;
import io.grpc.xds.EnvoyProtoData.LbEndpoint;
import io.grpc.xds.EnvoyProtoData.Locality;
import io.grpc.xds.EnvoyProtoData.LocalityLbEndpoints;
import io.grpc.xds.LoadStatsManager.LoadStatsStore;
import io.grpc.xds.LrsLoadBalancerProvider.LrsConfig;
import io.grpc.xds.PriorityLoadBalancerProvider.PriorityLbConfig;
import io.grpc.xds.WeightedTargetLoadBalancerProvider.WeightedPolicySelection;
import io.grpc.xds.WeightedTargetLoadBalancerProvider.WeightedTargetConfig;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Tests for {@link EdsLoadBalancer2}.
 */
@RunWith(JUnit4.class)
public class EdsLoadBalancer2Test {
  private static final String CLUSTER = "cluster-foo.googleapis.com";
  private static final String AUTHORITY = "api.google.com";
  private static final String EDS_SERVICE_NAME = "service.googleapis.com";
  private static final String LRS_SERVER_NAME = "lrs.googleapis.com";
  private final Locality locality1 =
      new Locality("test-region-1", "test-zone-1", "test-subzone-1");
  private final Locality locality2 =
      new Locality("test-region-2", "test-zone-2", "test-subzone-2");
  private final Locality locality3 =
      new Locality("test-region-3", "test-zone-3", "test-subzone-3");
  private final SynchronizationContext syncContext = new SynchronizationContext(
      new Thread.UncaughtExceptionHandler() {
        @Override
        public void uncaughtException(Thread t, Throwable e) {
          throw new AssertionError(e);
        }
      });

  private final FakeClock fakeClock = new FakeClock();
  private final LoadBalancerRegistry registry = new LoadBalancerRegistry();
  private final PolicySelection roundRobin =
      new PolicySelection(new FakeLoadBalancerProvider("round_robin"), null);
  private final PolicySelection weightedTarget =
      new PolicySelection(new FakeLoadBalancerProvider(WEIGHTED_TARGET_POLICY_NAME), null);
  private final List<FakeLoadBalancer> downstreamBalancers = new ArrayList<>();
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
  private LoadBalancer.Helper helper = new FakeLbHelper();
  @Mock
  private ThreadSafeRandom mockRandom;
  private int xdsClientRefs;
  private ConnectivityState currentState;
  private SubchannelPicker currentPicker;
  private EdsLoadBalancer2 loadBalancer;

  @Before
  public void setUp() {
    MockitoAnnotations.initMocks(this);

    registry.register(new FakeLoadBalancerProvider(PRIORITY_POLICY_NAME));
    registry.register(new FakeLoadBalancerProvider(LRS_POLICY_NAME));
    loadBalancer = new EdsLoadBalancer2(helper, registry, mockRandom);
    loadBalancer.handleResolvedAddresses(
        ResolvedAddresses.newBuilder()
            .setAddresses(Collections.<EquivalentAddressGroup>emptyList())
            .setAttributes(
                Attributes.newBuilder().set(XdsAttributes.XDS_CLIENT_POOL, xdsClientPool).build())
            .setLoadBalancingPolicyConfig(
                new EdsConfig(
                    CLUSTER, EDS_SERVICE_NAME, LRS_SERVER_NAME, weightedTarget, roundRobin))
            .build());
  }

  @After
  public void tearDown() {
    loadBalancer.shutdown();
    assertThat(xdsClient.watchers).isEmpty();
    assertThat(xdsClient.dropStats).isEmpty();
    assertThat(xdsClientRefs).isEqualTo(0);
    assertThat(downstreamBalancers).isEmpty();
  }

  @Test
  public void receiveFirstEndpointResource() {
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
    xdsClient.deliverClusterLoadAssignment(
        EDS_SERVICE_NAME,
        ImmutableMap.of(
            locality1, localityLbEndpoints1,
            locality2, localityLbEndpoints2,
            locality3, localityLbEndpoints3));
    assertThat(downstreamBalancers).hasSize(1);
    FakeLoadBalancer childBalancer = Iterables.getOnlyElement(downstreamBalancers);
    assertThat(childBalancer.name).isEqualTo(PRIORITY_POLICY_NAME);
    PriorityLbConfig config = (PriorityLbConfig) childBalancer.config;
    assertThat(config.priorities).containsExactly("priority1", "priority2");
    PolicySelection child1 = config.childConfigs.get("priority1");
    assertThat(child1.getProvider().getPolicyName()).isEqualTo(WEIGHTED_TARGET_POLICY_NAME);
    WeightedTargetConfig childConfig1 = (WeightedTargetConfig) child1.getConfig();
    assertThat(childConfig1.targets).hasSize(2);
    WeightedPolicySelection target1 = childConfig1.targets.get(locality1.toString());
    assertThat(target1.weight).isEqualTo(70);
    assertThat(target1.policySelection.getProvider().getPolicyName()).isEqualTo(LRS_POLICY_NAME);
    assertLrsConfig((LrsConfig) target1.policySelection.getConfig(), CLUSTER, EDS_SERVICE_NAME,
        LRS_SERVER_NAME, locality1, "round_robin");
    WeightedPolicySelection target2 = childConfig1.targets.get(locality2.toString());
    assertThat(target2.weight).isEqualTo(10);
    assertThat(target2.policySelection.getProvider().getPolicyName()).isEqualTo(LRS_POLICY_NAME);
    assertLrsConfig((LrsConfig) target2.policySelection.getConfig(), CLUSTER, EDS_SERVICE_NAME,
        LRS_SERVER_NAME, locality2, "round_robin");

    PolicySelection child2 = config.childConfigs.get("priority2");
    assertThat(child2.getProvider().getPolicyName()).isEqualTo(WEIGHTED_TARGET_POLICY_NAME);
    WeightedTargetConfig childConfig2 = (WeightedTargetConfig) child2.getConfig();
    assertThat(childConfig2.targets).hasSize(1);
    WeightedPolicySelection target3 = childConfig2.targets.get(locality3.toString());
    assertThat(target3.weight).isEqualTo(20);
    assertThat(target3.policySelection.getProvider().getPolicyName()).isEqualTo(LRS_POLICY_NAME);
    assertLrsConfig((LrsConfig) target3.policySelection.getConfig(), CLUSTER, EDS_SERVICE_NAME,
        LRS_SERVER_NAME, locality3, "round_robin");

    List<EquivalentAddressGroup> priorityAddr1 =
        AddressFilter.filter(childBalancer.addresses, "priority1");
    assertThat(priorityAddr1).hasSize(3);
    assertAddressesEqual(
        Arrays.asList(endpoint1, endpoint2, endpoint3),
        priorityAddr1);
    assertAddressesEqual(
        Arrays.asList(endpoint1, endpoint2),
        AddressFilter.filter(priorityAddr1, locality1.toString()));
    assertAddressesEqual(
        Collections.singletonList(endpoint3),
        AddressFilter.filter(priorityAddr1, locality2.toString()));

    List<EquivalentAddressGroup> priorityAddr2 =
        AddressFilter.filter(childBalancer.addresses, "priority2");
    assertThat(priorityAddr2).hasSize(1);
    assertAddressesEqual(Collections.singletonList(endpoint4), priorityAddr2);
    assertAddressesEqual(
        Collections.singletonList(endpoint4),
        AddressFilter.filter(priorityAddr2, locality3.toString()));
  }

  @Test
  public void endpointResourceUpdated() {
    EquivalentAddressGroup endpoint1 = makeAddress("endpoint-addr-1");
    LocalityLbEndpoints localityLbEndpoints1 =
        buildLocalityLbEndpoints(1, 10, Collections.singletonMap(endpoint1, true));
    xdsClient.deliverClusterLoadAssignment(
        EDS_SERVICE_NAME, ImmutableMap.of(locality1, localityLbEndpoints1));
    FakeLoadBalancer childBalancer = Iterables.getOnlyElement(downstreamBalancers);

    assertThat(childBalancer.name).isEqualTo(PRIORITY_POLICY_NAME);
    PriorityLbConfig config = (PriorityLbConfig) childBalancer.config;
    assertThat(config.priorities).containsExactly("priority1");
    PolicySelection child = config.childConfigs.get("priority1");
    assertThat(child.getProvider().getPolicyName()).isEqualTo(WEIGHTED_TARGET_POLICY_NAME);
    WeightedTargetConfig childConfig = (WeightedTargetConfig) child.getConfig();
    assertThat(childConfig.targets).hasSize(1);
    WeightedPolicySelection target = childConfig.targets.get(locality1.toString());
    assertThat(target.weight).isEqualTo(10);
    assertThat(target.policySelection.getProvider().getPolicyName()).isEqualTo(LRS_POLICY_NAME);
    assertLrsConfig((LrsConfig) target.policySelection.getConfig(), CLUSTER, EDS_SERVICE_NAME,
        LRS_SERVER_NAME, locality1, "round_robin");

    List<EquivalentAddressGroup> priorityAddr =
        AddressFilter.filter(childBalancer.addresses, "priority1");
    assertThat(priorityAddr).hasSize(1);
    assertAddressesEqual(Collections.singletonList(endpoint1), priorityAddr);
    assertAddressesEqual(
        Collections.singletonList(endpoint1),
        AddressFilter.filter(priorityAddr, locality1.toString()));

    EquivalentAddressGroup endpoint2 = makeAddress("endpoint-addr-2");
    LocalityLbEndpoints localityLbEndpoints2 =
        buildLocalityLbEndpoints(1, 30, Collections.singletonMap(endpoint2, true));
    xdsClient.deliverClusterLoadAssignment(
        EDS_SERVICE_NAME, ImmutableMap.of(locality2, localityLbEndpoints2));

    config = (PriorityLbConfig) childBalancer.config;
    assertThat(config.priorities).containsExactly("priority1");
    child = config.childConfigs.get("priority1");
    assertThat(child.getProvider().getPolicyName()).isEqualTo(WEIGHTED_TARGET_POLICY_NAME);
    childConfig = (WeightedTargetConfig) child.getConfig();
    assertThat(childConfig.targets).hasSize(1);
    target = childConfig.targets.get(locality2.toString());
    assertThat(target.weight).isEqualTo(30);
    assertThat(target.policySelection.getProvider().getPolicyName()).isEqualTo(LRS_POLICY_NAME);
    assertLrsConfig((LrsConfig) target.policySelection.getConfig(), CLUSTER, EDS_SERVICE_NAME,
        LRS_SERVER_NAME, locality2, "round_robin");

    priorityAddr = AddressFilter.filter(childBalancer.addresses, "priority1");
    assertThat(priorityAddr).hasSize(1);
    assertAddressesEqual(Collections.singletonList(endpoint2), priorityAddr);
    assertAddressesEqual(
        Collections.singletonList(endpoint2),
        AddressFilter.filter(priorityAddr, locality2.toString()));
  }

  @Test
  public void endpointResourceNeverExist() {
    xdsClient.deliverResourceNotFound(EDS_SERVICE_NAME);
    assertThat(currentState).isEqualTo(ConnectivityState.TRANSIENT_FAILURE);
    PickResult result = currentPicker.pickSubchannel(mock(PickSubchannelArgs.class));
    assertThat(result.getStatus().getCode()).isEqualTo(Code.UNAVAILABLE);
    assertThat(result.getStatus().getDescription())
        .isEqualTo("Resource " + EDS_SERVICE_NAME + " is unavailable");
  }

  @Test
  public void endpointResourceRemoved() {
    deliverSimpleClusterLoadAssignment(EDS_SERVICE_NAME);
    FakeLoadBalancer childBalancer = Iterables.getOnlyElement(downstreamBalancers);
    assertThat(childBalancer.shutdown).isFalse();

    xdsClient.deliverResourceNotFound(EDS_SERVICE_NAME);
    assertThat(childBalancer.shutdown).isTrue();
    assertThat(currentState).isEqualTo(ConnectivityState.TRANSIENT_FAILURE);
    PickResult result = currentPicker.pickSubchannel(mock(PickSubchannelArgs.class));
    assertThat(result.getStatus().getCode()).isEqualTo(Code.UNAVAILABLE);
    assertThat(result.getStatus().getDescription())
        .isEqualTo("Resource " + EDS_SERVICE_NAME + " is unavailable");
  }

  @Test
  public void handleEndpointResource_ignoreUnhealthyEndpoints() {
    EquivalentAddressGroup endpoint1 = makeAddress("endpoint-addr-1");
    EquivalentAddressGroup endpoint2 = makeAddress("endpoint-addr-2");
    LocalityLbEndpoints localityLbEndpoints =
        buildLocalityLbEndpoints(1, 10, ImmutableMap.of(endpoint1, false, endpoint2, true));
    xdsClient.deliverClusterLoadAssignment(
        EDS_SERVICE_NAME, Collections.singletonMap(locality1, localityLbEndpoints));

    FakeLoadBalancer childBalancer = Iterables.getOnlyElement(downstreamBalancers);
    PriorityLbConfig config = (PriorityLbConfig) childBalancer.config;
    PolicySelection child = config.childConfigs.get("priority1");
    WeightedTargetConfig childConfig = (WeightedTargetConfig) child.getConfig();
    assertThat(childConfig.targets.keySet()).containsExactly(locality1.toString());

    List<EquivalentAddressGroup> priorityAddr =
        AddressFilter.filter(childBalancer.addresses, "priority1");
    assertThat(priorityAddr).hasSize(1);
    assertAddressesEqual(Collections.singletonList(endpoint2), priorityAddr);
    assertAddressesEqual(
        Collections.singletonList(endpoint2),
        AddressFilter.filter(priorityAddr, locality1.toString()));
  }

  @Test
  public void handleEndpointResource_ignoreLocalitiesWithNoHealthyEndpoints() {
    EquivalentAddressGroup endpoint1 = makeAddress("endpoint-addr-1");
    EquivalentAddressGroup endpoint2 = makeAddress("endpoint-addr-2");
    LocalityLbEndpoints localityLbEndpoints1 =
        buildLocalityLbEndpoints(1, 10, Collections.singletonMap(endpoint1, false));
    LocalityLbEndpoints localityLbEndpoints2 =
        buildLocalityLbEndpoints(1, 10, Collections.singletonMap(endpoint2, true));
    xdsClient.deliverClusterLoadAssignment(
        EDS_SERVICE_NAME,
        ImmutableMap.of(locality1, localityLbEndpoints1, locality2, localityLbEndpoints2));

    FakeLoadBalancer childBalancer = Iterables.getOnlyElement(downstreamBalancers);
    PriorityLbConfig config = (PriorityLbConfig) childBalancer.config;
    PolicySelection child = config.childConfigs.get("priority1");
    WeightedTargetConfig childConfig = (WeightedTargetConfig) child.getConfig();
    assertThat(childConfig.targets.keySet()).containsExactly(locality2.toString());

    List<EquivalentAddressGroup> priorityAddr =
        AddressFilter.filter(childBalancer.addresses, "priority1");
    assertThat(priorityAddr).hasSize(1);
    assertAddressesEqual(Collections.singletonList(endpoint2), priorityAddr);
    assertAddressesEqual(
        Collections.singletonList(endpoint2),
        AddressFilter.filter(priorityAddr, locality2.toString()));
  }

  @Test
  public void handleEndpointResource_ignorePrioritiesWithNoHealthyEndpoints() {
    EquivalentAddressGroup endpoint1 = makeAddress("endpoint-addr-1");
    EquivalentAddressGroup endpoint2 = makeAddress("endpoint-addr-2");
    LocalityLbEndpoints localityLbEndpoints1 =
        buildLocalityLbEndpoints(1, 10, Collections.singletonMap(endpoint1, false));
    LocalityLbEndpoints localityLbEndpoints2 =
        buildLocalityLbEndpoints(2, 10, Collections.singletonMap(endpoint2, true));
    xdsClient.deliverClusterLoadAssignment(
        EDS_SERVICE_NAME,
        ImmutableMap.of(locality1, localityLbEndpoints1, locality2, localityLbEndpoints2));

    FakeLoadBalancer childBalancer = Iterables.getOnlyElement(downstreamBalancers);
    PriorityLbConfig config = (PriorityLbConfig) childBalancer.config;
    assertThat(config.priorities).containsExactly("priority2");

    List<EquivalentAddressGroup> priorityAddr =
        AddressFilter.filter(childBalancer.addresses, "priority2");
    assertThat(priorityAddr).hasSize(1);
    assertAddressesEqual(Collections.singletonList(endpoint2), priorityAddr);
    assertAddressesEqual(
        Collections.singletonList(endpoint2),
        AddressFilter.filter(priorityAddr, locality2.toString()));
  }

  @Test
  public void handleEndpointResource_errorIfNoUsableEndpoints() {
    EquivalentAddressGroup endpoint1 = makeAddress("endpoint-addr-1");
    EquivalentAddressGroup endpoint2 = makeAddress("endpoint-addr-2");
    LocalityLbEndpoints localityLbEndpoints1 =
        buildLocalityLbEndpoints(1, 10, Collections.singletonMap(endpoint1, false));
    LocalityLbEndpoints localityLbEndpoints2 =
        buildLocalityLbEndpoints(2, 10, Collections.singletonMap(endpoint2, false));
    xdsClient.deliverClusterLoadAssignment(
        EDS_SERVICE_NAME,
        ImmutableMap.of(locality1, localityLbEndpoints1, locality2, localityLbEndpoints2));

    assertThat(downstreamBalancers).isEmpty();
    assertThat(currentState).isEqualTo(ConnectivityState.TRANSIENT_FAILURE);
    PickResult result = currentPicker.pickSubchannel(mock(PickSubchannelArgs.class));
    assertThat(result.getStatus().isOk()).isFalse();
    assertThat(result.getStatus().getCode()).isEqualTo(Code.UNAVAILABLE);
    assertThat(result.getStatus().getDescription())
        .isEqualTo("No usable priority/locality/endpoint");
  }

  @Test
  public void handleEndpointResource_shutDownExistingChildLbPoliciesIfNoUsableEndpoints() {
    deliverSimpleClusterLoadAssignment(EDS_SERVICE_NAME);
    FakeLoadBalancer childBalancer = Iterables.getOnlyElement(downstreamBalancers);
    assertThat(childBalancer.shutdown).isFalse();

    EquivalentAddressGroup endpoint1 = makeAddress("endpoint-addr-1");
    LocalityLbEndpoints localityLbEndpoints1 =
        buildLocalityLbEndpoints(1, 10, Collections.singletonMap(endpoint1, false));
    xdsClient.deliverClusterLoadAssignment(
        EDS_SERVICE_NAME, Collections.singletonMap(locality1, localityLbEndpoints1));

    assertThat(childBalancer.shutdown).isTrue();
    assertThat(currentState).isEqualTo(ConnectivityState.TRANSIENT_FAILURE);
    PickResult result = currentPicker.pickSubchannel(mock(PickSubchannelArgs.class));
    assertThat(result.getStatus().isOk()).isFalse();
    assertThat(result.getStatus().getCode()).isEqualTo(Code.UNAVAILABLE);
    assertThat(result.getStatus().getDescription())
        .isEqualTo("No usable priority/locality/endpoint");
  }

  @Test
  public void handleDrops() {
    FakeLoadBalancerProvider fakeRoundRobinProvider = new FakeLoadBalancerProvider("round_robin");
    prepareRealDownstreamLbPolicies(fakeRoundRobinProvider);
    when(mockRandom.nextInt(anyInt())).thenReturn(499_999, 1_000_000);
    EquivalentAddressGroup endpoint1 = makeAddress("endpoint-addr-1");
    LocalityLbEndpoints localityLbEndpoints1 =
        buildLocalityLbEndpoints(1, 10, Collections.singletonMap(endpoint1, true));
    xdsClient.deliverClusterLoadAssignment(
        EDS_SERVICE_NAME,
        Collections.singletonList(new DropOverload("throttle", 500_000)),
        Collections.singletonMap(locality1, localityLbEndpoints1));
    assertThat(downstreamBalancers).hasSize(1);  // one leaf balancer
    FakeLoadBalancer leafBalancer = Iterables.getOnlyElement(downstreamBalancers);
    assertThat(leafBalancer.name).isEqualTo("round_robin");
    assertAddressesEqual(Collections.singletonList(makeAddress("endpoint-addr-1")),
        leafBalancer.addresses);
    Subchannel subchannel = leafBalancer.helper.createSubchannel(
        CreateSubchannelArgs.newBuilder().setAddresses(leafBalancer.addresses).build());
    leafBalancer.deliverSubchannelState(subchannel, ConnectivityState.READY);
    assertThat(currentState).isEqualTo(ConnectivityState.READY);
    PickResult result = currentPicker.pickSubchannel(mock(PickSubchannelArgs.class));
    assertThat(result.getStatus().isOk()).isFalse();
    assertThat(result.getStatus().getCode()).isEqualTo(Code.UNAVAILABLE);
    assertThat(result.getStatus().getDescription()).isEqualTo("Dropped: throttle");
    assertThat(xdsClient.dropStats.get(EDS_SERVICE_NAME).get("throttle").get()).isEqualTo(1);

    result = currentPicker.pickSubchannel(mock(PickSubchannelArgs.class));
    assertThat(result.getStatus().isOk()).isTrue();
    assertThat(result.getSubchannel()).isSameInstanceAs(subchannel);
  }

  @Test
  public void configUpdate_changeEdsServiceName_afterChildPolicyReady_switchGracefully() {
    deliverSimpleClusterLoadAssignment(EDS_SERVICE_NAME);  // downstream LB polices instantiated
    FakeLoadBalancer childBalancer = Iterables.getOnlyElement(downstreamBalancers);
    Subchannel subchannel1 = mock(Subchannel.class);
    childBalancer.deliverSubchannelState(subchannel1, ConnectivityState.READY);
    assertThat(currentState).isEqualTo(ConnectivityState.READY);
    PickResult result = currentPicker.pickSubchannel(mock(PickSubchannelArgs.class));
    assertThat(result.getSubchannel()).isSameInstanceAs(subchannel1);

    String newEdsServiceName = "service-foo.googleapis.com";
    loadBalancer.handleResolvedAddresses(
        ResolvedAddresses.newBuilder()
            .setAddresses(Collections.<EquivalentAddressGroup>emptyList())
            .setAttributes(
                Attributes.newBuilder().set(XdsAttributes.XDS_CLIENT_POOL, xdsClientPool).build())
            .setLoadBalancingPolicyConfig(
                new EdsConfig(
                    CLUSTER, newEdsServiceName, LRS_SERVER_NAME, weightedTarget, roundRobin))
            .build());
    deliverSimpleClusterLoadAssignment(newEdsServiceName);  // instantiate the new subtree
    assertThat(downstreamBalancers).hasSize(2);
    FakeLoadBalancer newChildBalancer = downstreamBalancers.get(1);
    assertThat(currentState).isEqualTo(ConnectivityState.READY);
    result = currentPicker.pickSubchannel(mock(PickSubchannelArgs.class));
    assertThat(result.getSubchannel()).isSameInstanceAs(subchannel1);
    Subchannel subchannel2 = mock(Subchannel.class);
    newChildBalancer.deliverSubchannelState(subchannel2, ConnectivityState.READY);
    assertThat(childBalancer.shutdown).isTrue();
    result = currentPicker.pickSubchannel(mock(PickSubchannelArgs.class));
    assertThat(result.getSubchannel()).isSameInstanceAs(subchannel2);
  }

  @Test
  public void configUpdate_changeEndpointPickingPolicy() {
    FakeLoadBalancerProvider fakeRoundRobinProvider = new FakeLoadBalancerProvider("round_robin");
    prepareRealDownstreamLbPolicies(fakeRoundRobinProvider);
    deliverSimpleClusterLoadAssignment(EDS_SERVICE_NAME);  // downstream LB policies instantiated
    FakeLoadBalancer leafBalancer = Iterables.getOnlyElement(downstreamBalancers);
    assertThat(leafBalancer.name).isEqualTo("round_robin");
    FakeLoadBalancerProvider fakePickFirstProvider = new FakeLoadBalancerProvider("pick_first");
    PolicySelection fakePickFirstSelection =
        new PolicySelection(fakePickFirstProvider, null);
    loadBalancer.handleResolvedAddresses(ResolvedAddresses.newBuilder()
        .setAddresses(Collections.<EquivalentAddressGroup>emptyList())
        .setAttributes(
            Attributes.newBuilder().set(XdsAttributes.XDS_CLIENT_POOL, xdsClientPool).build())
        .setLoadBalancingPolicyConfig(
            new EdsConfig(
                CLUSTER, EDS_SERVICE_NAME, LRS_SERVER_NAME, weightedTarget, fakePickFirstSelection))
        .build());
    assertThat(leafBalancer.shutdown).isTrue();
    leafBalancer = Iterables.getOnlyElement(downstreamBalancers);
    assertThat(leafBalancer.name).isEqualTo("pick_first");
  }

  @Test
  public void endpointDiscoveryError_beforeChildPolicyInstantiated_propagateToUpstream() {
    xdsClient.deliverError(Status.UNAUTHENTICATED.withDescription("permission denied"));
    assertThat(currentState).isEqualTo(ConnectivityState.TRANSIENT_FAILURE);
    PickResult result = currentPicker.pickSubchannel(mock(PickSubchannelArgs.class));
    assertThat(result.getStatus().isOk()).isFalse();
    assertThat(result.getStatus().getCode()).isEqualTo(Code.UNAUTHENTICATED);
    assertThat(result.getStatus().getDescription()).isEqualTo("permission denied");
  }

  @Test
  public void endpointDiscoveryError_afterChildPolicyInstantiated_keepUsingCurrentEndpoints() {
    deliverSimpleClusterLoadAssignment(EDS_SERVICE_NAME);
    FakeLoadBalancer childBalancer = Iterables.getOnlyElement(downstreamBalancers);
    xdsClient.deliverError(Status.UNAVAILABLE.withDescription("not found"));

    assertThat(currentState).isEqualTo(ConnectivityState.CONNECTING);
    PickResult result = currentPicker.pickSubchannel(mock(PickSubchannelArgs.class));
    assertThat(result.getStatus().isOk()).isTrue();
    assertThat(childBalancer.shutdown).isFalse();
  }

  @Test
  public void nameResolutionError_beforeChildPolicyInstantiated_returnErrorPickerToUpstream() {
    loadBalancer.handleNameResolutionError(Status.UNIMPLEMENTED.withDescription("not found"));
    assertThat(currentState).isEqualTo(ConnectivityState.TRANSIENT_FAILURE);
    PickResult result = currentPicker.pickSubchannel(mock(PickSubchannelArgs.class));
    assertThat(result.getStatus().isOk()).isFalse();
    assertThat(result.getStatus().getCode()).isEqualTo(Code.UNIMPLEMENTED);
    assertThat(result.getStatus().getDescription()).isEqualTo("not found");
  }

  @Test
  public void nameResolutionError_afterChildPolicyInstantiated_propagateToDownstream() {
    deliverSimpleClusterLoadAssignment(EDS_SERVICE_NAME);
    FakeLoadBalancer childBalancer = Iterables.getOnlyElement(downstreamBalancers);

    loadBalancer.handleNameResolutionError(
        Status.UNAVAILABLE.withDescription("cannot reach server"));
    assertThat(childBalancer.upstreamError.getCode()).isEqualTo(Code.UNAVAILABLE);
    assertThat(childBalancer.upstreamError.getDescription())
        .isEqualTo("cannot reach server");
  }

  @Test
  public void generatePriorityLbConfig() {
    Map<Integer, Map<Locality, Integer>> prioritizedLocalityWeights = new HashMap<>();
    prioritizedLocalityWeights.put(1, ImmutableMap.of(locality1, 20, locality2, 50));
    prioritizedLocalityWeights.put(2, ImmutableMap.of(locality3, 30));
    PriorityLbConfig config =
        EdsLoadBalancer2.generatePriorityLbConfig(
            CLUSTER, EDS_SERVICE_NAME, LRS_SERVER_NAME, weightedTarget, roundRobin, registry,
            prioritizedLocalityWeights);
    assertThat(config.childConfigs).hasSize(2);
    assertThat(config.priorities).containsExactly("priority1", "priority2");
    PolicySelection child1 = config.childConfigs.get("priority1");
    assertThat(child1.getProvider().getPolicyName()).isEqualTo(WEIGHTED_TARGET_POLICY_NAME);
    WeightedTargetConfig weightedTargetConfig1 = (WeightedTargetConfig) child1.getConfig();
    assertThat(weightedTargetConfig1.targets).hasSize(2);
    WeightedPolicySelection childTarget1 = weightedTargetConfig1.targets.get(locality1.toString());
    assertThat(childTarget1.weight).isEqualTo(20);
    assertThat(childTarget1.policySelection.getProvider().getPolicyName())
        .isEqualTo(LRS_POLICY_NAME);
    assertLrsConfig((LrsConfig) childTarget1.policySelection.getConfig(), CLUSTER,
        EDS_SERVICE_NAME, LRS_SERVER_NAME, locality1, "round_robin");
    WeightedPolicySelection childTarget2 = weightedTargetConfig1.targets.get(locality2.toString());
    assertThat(childTarget2.weight).isEqualTo(50);
    assertThat(childTarget2.policySelection.getProvider().getPolicyName())
        .isEqualTo(LRS_POLICY_NAME);
    assertLrsConfig((LrsConfig) childTarget2.policySelection.getConfig(), CLUSTER,
        EDS_SERVICE_NAME, LRS_SERVER_NAME, locality2, "round_robin");

    PolicySelection child2 = config.childConfigs.get("priority2");
    assertThat(child2.getProvider().getPolicyName()).isEqualTo(WEIGHTED_TARGET_POLICY_NAME);
    WeightedTargetConfig weightedTargetConfig2 = (WeightedTargetConfig) child2.getConfig();
    assertThat(weightedTargetConfig2.targets).hasSize(1);
    WeightedPolicySelection childTarget3 = weightedTargetConfig2.targets.get(locality3.toString());
    assertThat(childTarget3.weight).isEqualTo(30);
    assertThat(childTarget3.policySelection.getProvider().getPolicyName())
        .isEqualTo(LRS_POLICY_NAME);
    assertLrsConfig((LrsConfig) childTarget3.policySelection.getConfig(), CLUSTER,
        EDS_SERVICE_NAME, LRS_SERVER_NAME, locality3, "round_robin");
  }

  @Test
  public void generateWeightedTargetLbConfig_withLrsPolicy() {
    Map<Locality, Integer> localityWeights = ImmutableMap.of(locality1, 30, locality2, 40);
    WeightedTargetConfig config =
        EdsLoadBalancer2.generateWeightedTargetLbConfig(
            CLUSTER, EDS_SERVICE_NAME, LRS_SERVER_NAME, roundRobin, registry, localityWeights);
    assertThat(config.targets).hasSize(2);
    WeightedPolicySelection target1 = config.targets.get(locality1.toString());
    assertThat(target1.weight).isEqualTo(30);
    assertThat(target1.policySelection.getProvider().getPolicyName()).isEqualTo(LRS_POLICY_NAME);
    assertLrsConfig((LrsConfig) target1.policySelection.getConfig(), CLUSTER, EDS_SERVICE_NAME,
        LRS_SERVER_NAME, locality1, "round_robin");

    WeightedPolicySelection target2 = config.targets.get(locality2.toString());
    assertThat(target2.weight).isEqualTo(40);
    assertThat(target2.policySelection.getProvider().getPolicyName()).isEqualTo(LRS_POLICY_NAME);
    assertLrsConfig((LrsConfig) target2.policySelection.getConfig(), CLUSTER, EDS_SERVICE_NAME,
        LRS_SERVER_NAME, locality2, "round_robin");
  }

  @Test
  public void generateWeightedTargetLbConfig_withoutLrsPolicy() {
    Map<Locality, Integer> localityWeights = ImmutableMap.of(locality1, 30, locality2, 40);
    WeightedTargetConfig config =
        EdsLoadBalancer2.generateWeightedTargetLbConfig(
            CLUSTER, EDS_SERVICE_NAME, null, roundRobin, registry, localityWeights);
    assertThat(config.targets).hasSize(2);
    WeightedPolicySelection target1 = config.targets.get(locality1.toString());
    assertThat(target1.weight).isEqualTo(30);
    assertThat(target1.policySelection.getProvider().getPolicyName()).isEqualTo("round_robin");

    WeightedPolicySelection target2 = config.targets.get(locality2.toString());
    assertThat(target2.weight).isEqualTo(40);
    assertThat(target2.policySelection.getProvider().getPolicyName()).isEqualTo("round_robin");
  }

  private void deliverSimpleClusterLoadAssignment(String resourceName) {
    EquivalentAddressGroup endpoint1 = makeAddress("endpoint-addr-1");
    LocalityLbEndpoints localityLbEndpoints1 =
        buildLocalityLbEndpoints(1, 10, Collections.singletonMap(endpoint1, true));
    xdsClient.deliverClusterLoadAssignment(
        resourceName,
        Collections.singletonMap(locality1, localityLbEndpoints1));
  }

  /**
   * Instantiates the downstream LB policy subtree with real implementations, except the leaf
   * policy is replaced with a fake implementation to avoid creating connections.
   */
  private void prepareRealDownstreamLbPolicies(FakeLoadBalancerProvider fakeLeafPolicyProvider) {
    registry.deregister(registry.getProvider(PRIORITY_POLICY_NAME));
    registry.register(new PriorityLoadBalancerProvider());
    registry.deregister(registry.getProvider(LRS_POLICY_NAME));
    registry.register(new LrsLoadBalancerProvider());
    PolicySelection weightedTargetSelection =
        new PolicySelection(new WeightedTargetLoadBalancerProvider(), null);
    PolicySelection fakeLeafPolicySelection =
        new PolicySelection(fakeLeafPolicyProvider, null);
    loadBalancer.handleResolvedAddresses(
        ResolvedAddresses.newBuilder()
            .setAddresses(Collections.<EquivalentAddressGroup>emptyList())
            .setAttributes(
                Attributes.newBuilder().set(XdsAttributes.XDS_CLIENT_POOL, xdsClientPool).build())
            .setLoadBalancingPolicyConfig(
                new EdsConfig(CLUSTER, EDS_SERVICE_NAME, LRS_SERVER_NAME, weightedTargetSelection,
                    fakeLeafPolicySelection))
            .build());
  }

  private static void assertLrsConfig(
      LrsConfig config, String cluster, String edsServiceName, String lrsServerName,
      Locality locality, String childPolicy) {
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
      endpoints.add(new LbEndpoint(addr, 100 /* used */, status));
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

  private final class FakeXdsClient extends XdsClient {
    private final Map<String, EdsResourceWatcher> watchers = new HashMap<>();
    private final Map<String, ConcurrentMap<String, AtomicLong>> dropStats = new HashMap<>();

    @Override
    void shutdown() {
      // no-op
    }

    @Override
    void watchEdsResource(String resourceName, EdsResourceWatcher watcher) {
      watchers.put(resourceName, watcher);
    }

    @Override
    void cancelEdsResourceWatch(String resourceName, EdsResourceWatcher watcher) {
      watchers.remove(resourceName);
    }

    @Override
    LoadStatsStore addClientStats(String clusterName, @Nullable String clusterServiceName) {
      ConcurrentMap<String, AtomicLong> dropCounters = new ConcurrentHashMap<>();
      dropStats.put(clusterServiceName, dropCounters);
      return new LoadStatsStoreImpl(clusterName, clusterServiceName,
          fakeClock.getStopwatchSupplier().get(), dropCounters);
    }

    @Override
    void removeClientStats(String clusterName, @Nullable String clusterServiceName) {
      dropStats.remove(clusterServiceName);
    }

    void deliverClusterLoadAssignment(
        String resource, Map<Locality, LocalityLbEndpoints> localityLbEndpointsMap) {
      deliverClusterLoadAssignment(
          resource, Collections.<DropOverload>emptyList(), localityLbEndpointsMap);
    }

    void deliverClusterLoadAssignment(
        final String resource, final List<DropOverload> dropOverloads,
        final Map<Locality, LocalityLbEndpoints> localityLbEndpointsMap) {
      syncContext.execute(new Runnable() {
        @Override
        public void run() {
          if (watchers.containsKey(resource)) {
            EdsUpdate.Builder builder  = EdsUpdate.newBuilder().setClusterName(resource);
            for (DropOverload dropOverload : dropOverloads) {
              builder.addDropPolicy(dropOverload);
            }
            for (Locality locality : localityLbEndpointsMap.keySet()) {
              builder.addLocalityLbEndpoints(locality, localityLbEndpointsMap.get(locality));
            }
            watchers.get(resource).onChanged(builder.build());
          }
        }
      });
    }

    void deliverResourceNotFound(final String resource) {
      syncContext.execute(new Runnable() {
        @Override
        public void run() {
          if (watchers.containsKey(resource)) {
            watchers.get(resource).onResourceDoesNotExist(resource);
          }
        }
      });
    }

    void deliverError(final Status error) {
      syncContext.execute(new Runnable() {
        @Override
        public void run() {
          for (EdsResourceWatcher watcher : watchers.values()) {
            watcher.onError(error);
          }
        }
      });
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
      downstreamBalancers.add(balancer);
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
      downstreamBalancers.remove(this);
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

  private final class FakeLbHelper extends LoadBalancer.Helper {

    @Override
    public void updateBalancingState(
        @Nonnull ConnectivityState newState, @Nonnull SubchannelPicker newPicker) {
      currentState = newState;
      currentPicker = newPicker;
    }

    @Override
    public Subchannel createSubchannel(CreateSubchannelArgs args) {
      return mock(Subchannel.class);
    }

    @Override
    public SynchronizationContext getSynchronizationContext() {
      return syncContext;
    }

    @Override
    public ScheduledExecutorService getScheduledExecutorService() {
      return fakeClock.getScheduledExecutorService();
    }

    @Override
    public ManagedChannel createOobChannel(EquivalentAddressGroup eag, String authority) {
      throw new UnsupportedOperationException("should not be called");
    }

    @Deprecated
    @Override
    public NameResolver.Factory getNameResolverFactory() {
      throw new UnsupportedOperationException("should not be called");
    }

    @Override
    public String getAuthority() {
      return AUTHORITY;
    }
  }
}
