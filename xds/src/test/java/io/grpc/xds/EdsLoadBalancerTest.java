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
import static io.envoyproxy.envoy.api.v2.core.HealthStatus.HEALTHY;
import static io.grpc.ConnectivityState.CONNECTING;
import static io.grpc.ConnectivityState.READY;
import static io.grpc.ConnectivityState.TRANSIENT_FAILURE;
import static io.grpc.xds.XdsClientTestHelper.buildClusterLoadAssignment;
import static io.grpc.xds.XdsClientTestHelper.buildDiscoveryResponse;
import static io.grpc.xds.XdsClientTestHelper.buildDropOverload;
import static io.grpc.xds.XdsClientTestHelper.buildLbEndpoint;
import static io.grpc.xds.XdsClientTestHelper.buildLocalityLbEndpoints;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.protobuf.Any;
import io.envoyproxy.envoy.api.v2.ClusterLoadAssignment;
import io.envoyproxy.envoy.api.v2.ClusterLoadAssignment.Policy.DropOverload;
import io.envoyproxy.envoy.api.v2.DiscoveryRequest;
import io.envoyproxy.envoy.api.v2.DiscoveryResponse;
import io.envoyproxy.envoy.api.v2.endpoint.LbEndpoint;
import io.envoyproxy.envoy.api.v2.endpoint.LocalityLbEndpoints;
import io.envoyproxy.envoy.service.discovery.v2.AggregatedDiscoveryServiceGrpc.AggregatedDiscoveryServiceImplBase;
import io.grpc.Attributes;
import io.grpc.ConnectivityState;
import io.grpc.EquivalentAddressGroup;
import io.grpc.InternalLogId;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancer.PickResult;
import io.grpc.LoadBalancer.PickSubchannelArgs;
import io.grpc.LoadBalancer.ResolvedAddresses;
import io.grpc.LoadBalancer.Subchannel;
import io.grpc.LoadBalancer.SubchannelPicker;
import io.grpc.LoadBalancerProvider;
import io.grpc.LoadBalancerRegistry;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.Status.Code;
import io.grpc.SynchronizationContext;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.internal.BackoffPolicy;
import io.grpc.internal.FakeClock;
import io.grpc.internal.ObjectPool;
import io.grpc.internal.ServiceConfigUtil.PolicySelection;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.GrpcCleanupRule;
import io.grpc.xds.Bootstrapper.BootstrapInfo;
import io.grpc.xds.Bootstrapper.ChannelCreds;
import io.grpc.xds.Bootstrapper.ServerInfo;
import io.grpc.xds.EdsLoadBalancerProvider.EdsConfig;
import io.grpc.xds.EnvoyProtoData.Node;
import io.grpc.xds.LocalityStore.LocalityStoreFactory;
import io.grpc.xds.XdsClient.EndpointUpdate;
import io.grpc.xds.XdsClient.XdsChannel;
import io.grpc.xds.XdsClient.XdsChannelFactory;
import java.net.InetSocketAddress;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatcher;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/**
 * Tests for {@link EdsLoadBalancer}.
 */
@RunWith(Parameterized.class)
public class EdsLoadBalancerTest {

  private static final String CLUSTER_NAME = "eds-lb-test.example.com";
  private static final String SERVICE_AUTHORITY = "test.authority.example.com";

  @Rule
  public final MockitoRule mockitoRule = MockitoJUnit.rule();
  @Rule
  public final GrpcCleanupRule cleanupRule = new GrpcCleanupRule();

  private final SynchronizationContext syncContext = new SynchronizationContext(
      new Thread.UncaughtExceptionHandler() {
        @Override
        public void uncaughtException(Thread t, Throwable e) {
          throw new AssertionError(e);
        }
      });
  private final FakeClock fakeClock = new FakeClock();

  private final LoadBalancerRegistry lbRegistry = new LoadBalancerRegistry();

  // Child helpers keyed by locality names.
  private final Map<String, Helper> childHelpers = new HashMap<>();
  // Child balancers keyed by locality names.
  private final Map<String, LoadBalancer> childBalancers = new HashMap<>();
  private final XdsChannelFactory channelFactory = new XdsChannelFactory() {
    @Override
    XdsChannel createChannel(List<ServerInfo> servers) {
      assertThat(Iterables.getOnlyElement(servers).getServerUri())
          .isEqualTo("trafficdirector.googleapis.com");
      return new XdsChannel(channel, false);
    }
  };

  private final PolicySelection fakeEndpointPickingPolicy =
      new PolicySelection(mock(LoadBalancerProvider.class), null, new Object());

  @Mock
  private Helper helper;
  @Mock
  private Bootstrapper bootstrapper;
  @Captor
  ArgumentCaptor<ConnectivityState> connectivityStateCaptor;
  @Captor
  ArgumentCaptor<SubchannelPicker> pickerCaptor;

  private LoadBalancer edsLb;
  // Simulating a CDS to EDS flow, otherwise EDS only.
  @Parameter
  public boolean isFullFlow;
  private ManagedChannel channel;
  // Response observer on server side.
  private StreamObserver<DiscoveryResponse> responseObserver;
  @Nullable
  private FakeXdsClientPool xdsClientPoolFromResolveAddresses;
  private LocalityStoreFactory localityStoreFactory = LocalityStoreFactory.getInstance();
  private int versionIno;
  private int nonce;

  @Parameters
  public static Collection<Boolean> isFullFlow() {
    return ImmutableList.of(false, true );
  }

  @Before
  public void setUp() throws Exception {
    doReturn(SERVICE_AUTHORITY).when(helper).getAuthority();
    doReturn(syncContext).when(helper).getSynchronizationContext();
    doReturn(fakeClock.getScheduledExecutorService()).when(helper).getScheduledExecutorService();

    // Register a fake round robin balancer provider.
    lbRegistry.register(new LoadBalancerProvider() {
      @Override
      public boolean isAvailable() {
        return true;
      }

      @Override
      public int getPriority() {
        return 5;
      }

      @Override
      public String getPolicyName() {
        return "round_robin";
      }

      @Override
      public LoadBalancer newLoadBalancer(Helper helper) {
        String localityName = helper.getAuthority();
        childHelpers.put(localityName, helper);
        LoadBalancer balancer = mock(LoadBalancer.class);
        childBalancers.put(localityName, balancer);
        return balancer;
      }
    });

    AggregatedDiscoveryServiceImplBase serviceImpl = new AggregatedDiscoveryServiceImplBase() {
      @Override
      public StreamObserver<DiscoveryRequest> streamAggregatedResources(
          final StreamObserver<DiscoveryResponse> responseObserver) {
        EdsLoadBalancerTest.this.responseObserver = responseObserver;
        @SuppressWarnings("unchecked")
        StreamObserver<DiscoveryRequest> requestObserver = mock(StreamObserver.class);
        return requestObserver;
      }
    };
    String serverName = InProcessServerBuilder.generateName();
    cleanupRule.register(
        InProcessServerBuilder
            .forName(serverName)
            .directExecutor()
            .addService(serviceImpl)
            .build()
            .start());
    channel = cleanupRule.register(
        InProcessChannelBuilder
            .forName(serverName)
            .directExecutor()
            .build());
    final List<ServerInfo> serverList = ImmutableList.of(
        new ServerInfo("trafficdirector.googleapis.com", ImmutableList.<ChannelCreds>of(), null));
    Node node = Node.newBuilder().build();
    BootstrapInfo bootstrapInfo = new BootstrapInfo(serverList, node);
    doReturn(bootstrapInfo).when(bootstrapper).readBootstrap();

    if (isFullFlow) {
      xdsClientPoolFromResolveAddresses = new FakeXdsClientPool(
          new XdsClientImpl(
              SERVICE_AUTHORITY,
              serverList,
              channelFactory,
              node,
              syncContext,
              fakeClock.getScheduledExecutorService(),
              mock(BackoffPolicy.Provider.class),
              fakeClock.getStopwatchSupplier()));
    }

    edsLb =
        new EdsLoadBalancer(helper, lbRegistry, localityStoreFactory, bootstrapper, channelFactory);
  }

  @After
  public void tearDown() {
    edsLb.shutdown();

    for (LoadBalancer childBalancer : childBalancers.values()) {
      verify(childBalancer).shutdown();
    }

    if (isFullFlow) {
      assertThat(xdsClientPoolFromResolveAddresses.timesGetObjectCalled)
          .isEqualTo(xdsClientPoolFromResolveAddresses.timesReturnObjectCalled);

      // Just for cleaning up the test.
      xdsClientPoolFromResolveAddresses.xdsClient.shutdown();
    }

    assertThat(channel.isShutdown()).isTrue();
  }

  @Test
  public void handleNameResolutionErrorBeforeAndAfterEdsWorkding() {
    deliverResolvedAddresses(null, null, fakeEndpointPickingPolicy);

    // handleResolutionError() before receiving any endpoint update.
    edsLb.handleNameResolutionError(Status.DATA_LOSS.withDescription("fake status"));
    verify(helper).updateBalancingState(eq(TRANSIENT_FAILURE), any(SubchannelPicker.class));

    // Endpoint update received.
    ClusterLoadAssignment clusterLoadAssignment =
        buildClusterLoadAssignment(CLUSTER_NAME,
            ImmutableList.of(
                buildLocalityLbEndpoints("region1", "zone1", "subzone1",
                    ImmutableList.of(
                        buildLbEndpoint("192.168.0.1", 8080, HEALTHY, 2)),
                    1, 0)),
            ImmutableList.of(buildDropOverload("throttle", 1000)));
    deliverClusterLoadAssignments(clusterLoadAssignment);

    // handleResolutionError() after receiving endpoint update.
    edsLb.handleNameResolutionError(Status.DATA_LOSS.withDescription("fake status"));
    // No more TRANSIENT_FAILURE.
    verify(helper, times(1)).updateBalancingState(
        eq(TRANSIENT_FAILURE), any(SubchannelPicker.class));
  }

  @Test
  public void handleEdsServiceNameChange() {
    assertThat(childHelpers).isEmpty();

    deliverResolvedAddresses("edsServiceName1", null, fakeEndpointPickingPolicy);
    ClusterLoadAssignment clusterLoadAssignment =
        buildClusterLoadAssignment("edsServiceName1",
            ImmutableList.of(
                buildLocalityLbEndpoints("region1", "zone1", "subzone1",
                    ImmutableList.of(
                        buildLbEndpoint("192.168.0.1", 8080, HEALTHY, 2)),
                    1, 0)),
            ImmutableList.<DropOverload>of());
    deliverClusterLoadAssignments(clusterLoadAssignment);
    assertThat(childHelpers).hasSize(1);
    Helper childHelper1 = childHelpers.get("subzone1");
    LoadBalancer childBalancer1 = childBalancers.get("subzone1");
    verify(childBalancer1).handleResolvedAddresses(
        argThat(RoundRobinBackendsMatcher.builder().addHostAndPort("192.168.0.1", 8080).build()));

    childHelper1.updateBalancingState(CONNECTING, mock(SubchannelPicker.class));
    assertLatestConnectivityState(CONNECTING);

    // Change edsServicename to edsServiceName2.
    deliverResolvedAddresses("edsServiceName2", null, fakeEndpointPickingPolicy);
    // The old balancer was not READY, so it will be shutdown immediately.
    verify(childBalancer1).shutdown();

    clusterLoadAssignment =
        buildClusterLoadAssignment("edsServiceName2",
            ImmutableList.of(
                buildLocalityLbEndpoints("region2", "zone2", "subzone2",
                    ImmutableList.of(
                        buildLbEndpoint("192.168.0.2", 8080, HEALTHY, 2)),
                    1, 0)),
            ImmutableList.<DropOverload>of());
    deliverClusterLoadAssignments(clusterLoadAssignment);
    assertThat(childHelpers).hasSize(2);
    Helper childHelper2 = childHelpers.get("subzone2");
    LoadBalancer childBalancer2 = childBalancers.get("subzone2");
    verify(childBalancer2).handleResolvedAddresses(
        argThat(RoundRobinBackendsMatcher.builder().addHostAndPort("192.168.0.2", 8080).build()));

    final Subchannel subchannel2 = mock(Subchannel.class);
    SubchannelPicker picker2 = new SubchannelPicker() {
      @Override
      public PickResult pickSubchannel(PickSubchannelArgs args) {
        return PickResult.withSubchannel(subchannel2);
      }
    };
    childHelper2.updateBalancingState(READY, picker2);
    assertLatestSubchannelPicker(subchannel2);

    // Change edsServiceName to edsServiceName3.
    deliverResolvedAddresses("edsServiceName3", null, fakeEndpointPickingPolicy);
    clusterLoadAssignment =
        buildClusterLoadAssignment("edsServiceName3",
            ImmutableList.of(
                buildLocalityLbEndpoints("region3", "zone3", "subzone3",
                    ImmutableList.of(
                        buildLbEndpoint("192.168.0.3", 8080, HEALTHY, 2)),
                    1, 0)),
            ImmutableList.<DropOverload>of());
    deliverClusterLoadAssignments(clusterLoadAssignment);

    assertThat(childHelpers).hasSize(3);
    Helper childHelper3 = childHelpers.get("subzone3");
    LoadBalancer childBalancer3 = childBalancers.get("subzone3");

    childHelper3.updateBalancingState(CONNECTING, mock(SubchannelPicker.class));
    // The new balancer is not READY while the old one is still READY.
    verify(childBalancer2, never()).shutdown();
    assertLatestSubchannelPicker(subchannel2);

    childHelper2.updateBalancingState(CONNECTING, mock(SubchannelPicker.class));
    // The old balancer becomes not READY, so the new balancer will update picker immediately.
    verify(childBalancer2).shutdown();
    assertLatestConnectivityState(CONNECTING);

    // Change edsServiceName to edsServiceName4.
    deliverResolvedAddresses("edsServiceName4", null, fakeEndpointPickingPolicy);
    verify(childBalancer3).shutdown();

    clusterLoadAssignment =
        buildClusterLoadAssignment("edsServiceName4",
            ImmutableList.of(
                buildLocalityLbEndpoints("region4", "zone4", "subzone4",
                    ImmutableList.of(
                        buildLbEndpoint("192.168.0.4", 8080, HEALTHY, 2)),
                    1, 0)),
            ImmutableList.<DropOverload>of());
    deliverClusterLoadAssignments(clusterLoadAssignment);

    assertThat(childHelpers).hasSize(4);
    Helper childHelper4 = childHelpers.get("subzone4");
    LoadBalancer childBalancer4 = childBalancers.get("subzone4");

    final Subchannel subchannel4 = mock(Subchannel.class);
    SubchannelPicker picker4 = new SubchannelPicker() {
      @Override
      public PickResult pickSubchannel(PickSubchannelArgs args) {
        return PickResult.withSubchannel(subchannel4);
      }
    };
    childHelper4.updateBalancingState(READY, picker4);
    assertLatestSubchannelPicker(subchannel4);

    // Change edsServiceName to edsServiceName5.
    deliverResolvedAddresses("edsServiceName5", null, fakeEndpointPickingPolicy);
    clusterLoadAssignment =
        buildClusterLoadAssignment("edsServiceName5",
            ImmutableList.of(
                buildLocalityLbEndpoints("region5", "zone5", "subzone5",
                    ImmutableList.of(
                        buildLbEndpoint("192.168.0.5", 8080, HEALTHY, 2)),
                    1, 0)),
            ImmutableList.<DropOverload>of());
    deliverClusterLoadAssignments(clusterLoadAssignment);

    assertThat(childHelpers).hasSize(5);
    Helper childHelper5 = childHelpers.get("subzone5");
    LoadBalancer childBalancer5 = childBalancers.get("subzone5");
    childHelper5.updateBalancingState(CONNECTING, mock(SubchannelPicker.class));
    // The old balancer was READY, so the new balancer will gracefully switch and not update
    // non-READY picker.
    verify(childBalancer4, never()).shutdown();
    assertLatestSubchannelPicker(subchannel4);

    final Subchannel subchannel5 = mock(Subchannel.class);
    SubchannelPicker picker5 = new SubchannelPicker() {
      @Override
      public PickResult pickSubchannel(PickSubchannelArgs args) {
        return PickResult.withSubchannel(subchannel5);
      }
    };
    childHelper5.updateBalancingState(READY, picker5);
    verify(childBalancer4).shutdown();
    assertLatestSubchannelPicker(subchannel5);
    verify(childBalancer5, never()).shutdown();
  }

  @Test
  public void edsResourceUpdate_allDrop() {
    deliverResolvedAddresses(null, null, fakeEndpointPickingPolicy);

    ClusterLoadAssignment clusterLoadAssignment = buildClusterLoadAssignment(
        CLUSTER_NAME,
        ImmutableList.of(
            buildLocalityLbEndpoints("region1", "zone1", "subzone1",
                ImmutableList.of(
                    buildLbEndpoint("192.168.0.1", 8080, HEALTHY, 2)),
                1, 0)),
        ImmutableList.<DropOverload>of());
    deliverClusterLoadAssignments(clusterLoadAssignment);

    assertThat(childBalancers).hasSize(1);
    verify(childBalancers.get("subzone1")).handleResolvedAddresses(
        argThat(RoundRobinBackendsMatcher.builder().addHostAndPort("192.168.0.1", 8080).build()));
    assertThat(childHelpers).hasSize(1);
    Helper childHelper = childHelpers.get("subzone1");

    final Subchannel subchannel = mock(Subchannel.class);
    SubchannelPicker picker = new SubchannelPicker() {
      @Override
      public PickResult pickSubchannel(PickSubchannelArgs args) {
        return PickResult.withSubchannel(subchannel);
      }
    };
    childHelper.updateBalancingState(READY, picker);
    assertLatestSubchannelPicker(subchannel);

    clusterLoadAssignment = buildClusterLoadAssignment(
        CLUSTER_NAME,
        ImmutableList.of(
            buildLocalityLbEndpoints("region1", "zone1", "subzone1",
                ImmutableList.of(
                    buildLbEndpoint("192.168.0.1", 8080, HEALTHY, 2)),
                1, 0)),
        ImmutableList.of(
            buildDropOverload("cat_1", 3),
            buildDropOverload("cat_2", 1_000_001),
            buildDropOverload("cat_3", 4)));
    deliverClusterLoadAssignments(clusterLoadAssignment);

    verify(helper, atLeastOnce()).updateBalancingState(eq(READY), pickerCaptor.capture());
    SubchannelPicker pickerExpectedDropAll = pickerCaptor.getValue();
    assertThat(pickerExpectedDropAll.pickSubchannel(mock(PickSubchannelArgs.class)).isDrop())
        .isTrue();
  }

  @Test
  public void edsResourceUpdate_localityAssignmentChange() {
    deliverResolvedAddresses(null, null, fakeEndpointPickingPolicy);

    LbEndpoint endpoint11 = buildLbEndpoint("addr11.example.com", 8011, HEALTHY, 11);
    LbEndpoint endpoint12 = buildLbEndpoint("addr12.example.com", 8012, HEALTHY, 12);
    LocalityLbEndpoints localityLbEndpoints1 = buildLocalityLbEndpoints(
        "region1", "zone1", "subzone1",
        ImmutableList.of(endpoint11, endpoint12),
        1,
        0);

    LbEndpoint endpoint21 = buildLbEndpoint("addr21.example.com", 8021, HEALTHY, 21);
    LbEndpoint endpoint22 = buildLbEndpoint("addr22.example.com", 8022, HEALTHY, 22);
    LocalityLbEndpoints localityLbEndpoints2 = buildLocalityLbEndpoints(
        "region2", "zone2", "subzone2",
        ImmutableList.of(endpoint21, endpoint22),
        2,
        0);

    LbEndpoint endpoint31 = buildLbEndpoint("addr31.example.com", 8031, HEALTHY, 31);
    LocalityLbEndpoints localityLbEndpoints3 = buildLocalityLbEndpoints(
        "region3", "zone3", "subzone3",
        ImmutableList.of(endpoint31),
        3,
        0);

    ClusterLoadAssignment clusterLoadAssignment = buildClusterLoadAssignment(
        CLUSTER_NAME,
        ImmutableList.of(localityLbEndpoints1, localityLbEndpoints2, localityLbEndpoints3),
        ImmutableList.<DropOverload>of());
    deliverClusterLoadAssignments(clusterLoadAssignment);

    assertThat(childBalancers).hasSize(3);
    verify(childBalancers.get("subzone1")).handleResolvedAddresses(
        argThat(RoundRobinBackendsMatcher.builder()
            .addHostAndPort("addr11.example.com", 8011)
            .addHostAndPort("addr12.example.com", 8012)
            .build()));
    verify(childBalancers.get("subzone2")).handleResolvedAddresses(
        argThat(RoundRobinBackendsMatcher.builder()
            .addHostAndPort("addr21.example.com", 8021)
            .addHostAndPort("addr22.example.com", 8022)
            .build()));
    verify(childBalancers.get("subzone3")).handleResolvedAddresses(
        argThat(RoundRobinBackendsMatcher.builder()
            .addHostAndPort("addr31.example.com", 8031)
            .build()));
    assertThat(childHelpers).hasSize(3);
    Helper childHelper2 = childHelpers.get("subzone2");
    final Subchannel subchannel = mock(Subchannel.class);
    SubchannelPicker picker = new SubchannelPicker() {
      @Override
      public PickResult pickSubchannel(PickSubchannelArgs args) {
        return PickResult.withSubchannel(subchannel);
      }
    };
    verify(helper, never()).updateBalancingState(eq(READY), any(SubchannelPicker.class));
    childHelper2.updateBalancingState(READY, picker);
    assertLatestSubchannelPicker(subchannel);
  }

  // Uses a fake LocalityStoreFactory that creates a mock LocalityStore, and verifies interaction
  // between the EDS balancer and LocalityStore.
  @Test
  public void edsResourceUpdate_endpointAssignmentChange() {
    final ArrayDeque<LocalityStore> localityStores = new ArrayDeque<>();
    localityStoreFactory = new LocalityStoreFactory() {
      @Override
      LocalityStore newLocalityStore(
          InternalLogId logId,
          Helper helper,
          LoadBalancerRegistry lbRegistry,
          LoadStatsStore loadStatsStore) {
        // Note that this test approach can not verify anything about how localityStore will use the
        // helper in the arguments to delegate updates from localityStore to the EDS balancer, and
        // can not verify anything about how loadStatsStore updates localities and drop information.
        // To cover the gap, some non-exhaustive tests like
        // handleAllDropUpdates_pickersAreDropped() and
        // handleLocalityAssignmentUpdates_pickersUpdatedFromChildBalancer()are added to verify some
        // very basic behaviors.
        LocalityStore localityStore = mock(LocalityStore.class);
        localityStores.add(localityStore);
        return localityStore;
      }
    };
    edsLb =
        new EdsLoadBalancer(helper, lbRegistry, localityStoreFactory, bootstrapper, channelFactory);

    deliverResolvedAddresses("edsServiceName1", null, fakeEndpointPickingPolicy);
    assertThat(localityStores).hasSize(1);
    LocalityStore localityStore = localityStores.peekLast();

    ClusterLoadAssignment clusterLoadAssignment = buildClusterLoadAssignment(
        "edsServiceName1",
        ImmutableList.of(
            buildLocalityLbEndpoints("region1", "zone1", "subzone1",
                ImmutableList.of(
                    buildLbEndpoint("192.168.0.1", 8080, HEALTHY, 2)),
                1, 0)),
        ImmutableList.of(
            buildDropOverload("cat_1", 3),
            buildDropOverload("cat_2", 456)));
    deliverClusterLoadAssignments(clusterLoadAssignment);
    EndpointUpdate endpointUpdate = getEndpointUpdateFromClusterAssignmentV2(clusterLoadAssignment);
    verify(localityStore).updateDropPercentage(endpointUpdate.getDropPolicies());
    verify(localityStore).updateLocalityStore(endpointUpdate.getLocalityLbEndpointsMap());

    clusterLoadAssignment = buildClusterLoadAssignment(
        "edsServiceName1",
        ImmutableList.of(
            buildLocalityLbEndpoints("region1", "zone1", "subzone1",
                ImmutableList.of(
                    buildLbEndpoint("192.168.0.1", 8080, HEALTHY, 2),
                    buildLbEndpoint("192.168.0.1", 8088, HEALTHY, 2)),
                1, 0)),
        ImmutableList.of(
            buildDropOverload("cat_1", 3),
            buildDropOverload("cat_3", 4)));
    deliverClusterLoadAssignments(clusterLoadAssignment);

    endpointUpdate = getEndpointUpdateFromClusterAssignmentV2(clusterLoadAssignment);
    verify(localityStore).updateDropPercentage(endpointUpdate.getDropPolicies());
    verify(localityStore).updateLocalityStore(endpointUpdate.getLocalityLbEndpointsMap());

    // Change cluster name.
    deliverResolvedAddresses("edsServiceName2", null, fakeEndpointPickingPolicy);
    assertThat(localityStores).hasSize(2);
    localityStore = localityStores.peekLast();

    clusterLoadAssignment = buildClusterLoadAssignment(
        "edsServiceName2",
        ImmutableList.of(
            buildLocalityLbEndpoints("region2", "zone2", "subzone2",
                ImmutableList.of(
                    buildLbEndpoint("192.168.0.2", 8080, HEALTHY, 2),
                    buildLbEndpoint("192.168.0.2", 8088, HEALTHY, 2)),
                1, 0)),
        ImmutableList.of(
            buildDropOverload("cat_1", 3),
            buildDropOverload("cat_3", 4)));
    deliverClusterLoadAssignments(clusterLoadAssignment);
    endpointUpdate = getEndpointUpdateFromClusterAssignmentV2(clusterLoadAssignment);
    verify(localityStore).updateDropPercentage(endpointUpdate.getDropPolicies());
    verify(localityStore).updateLocalityStore(endpointUpdate.getLocalityLbEndpointsMap());
  }

  @Test
  public void edsResourceNotExist() {
    deliverResolvedAddresses(null, null, fakeEndpointPickingPolicy);

    // Forwarding 20 seconds so that the xds client will deem EDS resource not available.
    fakeClock.forwardTime(20, TimeUnit.SECONDS);
    assertThat(childBalancers).isEmpty();
    verify(helper).updateBalancingState(eq(TRANSIENT_FAILURE), pickerCaptor.capture());
    PickResult result = pickerCaptor.getValue().pickSubchannel(mock(PickSubchannelArgs.class));
    assertThat(result.getStatus().getCode()).isEqualTo(Code.UNAVAILABLE);
    assertThat(result.getStatus().getDescription())
        .isEqualTo("Resource " + CLUSTER_NAME + " is unavailable");
  }

  @Test
  public void edsResourceRemoved() {
    deliverResolvedAddresses(null, null, fakeEndpointPickingPolicy);
    ClusterLoadAssignment clusterLoadAssignment =
        buildClusterLoadAssignment(CLUSTER_NAME,
            ImmutableList.of(
                buildLocalityLbEndpoints("region", "zone", "subzone",
                    ImmutableList.of(
                        buildLbEndpoint("192.168.0.1", 8080, HEALTHY, 2)),
                    1, 0)),
            ImmutableList.<DropOverload>of());
    deliverClusterLoadAssignments(clusterLoadAssignment);

    assertThat(childBalancers).hasSize(1);
    assertThat(childHelpers).hasSize(1);
    LoadBalancer localityBalancer = childBalancers.get("subzone");
    Helper localityBalancerHelper = childHelpers.get("subzone");
    final Subchannel subchannel = mock(Subchannel.class);
    SubchannelPicker picker = new SubchannelPicker() {
      @Override
      public PickResult pickSubchannel(PickSubchannelArgs args) {
        return PickResult.withSubchannel(subchannel);
      }
    };
    localityBalancerHelper.updateBalancingState(READY, picker);
    verify(helper).updateBalancingState(eq(READY), pickerCaptor.capture());
    PickResult result = pickerCaptor.getValue().pickSubchannel(mock(PickSubchannelArgs.class));
    assertThat(result.getSubchannel()).isSameInstanceAs(subchannel);

    // The whole cluster is no longer accessible.
    // Note that EDS resource removal is achieved by CDS resource update.
    responseObserver.onNext(
        buildDiscoveryResponse(
            String.valueOf(versionIno++),
            Collections.<Any>emptyList(),
            XdsClientImpl.ADS_TYPE_URL_CDS_V2,
            String.valueOf(nonce++)));

    verify(localityBalancer).shutdown();
    verify(helper).updateBalancingState(eq(TRANSIENT_FAILURE), pickerCaptor.capture());
    result = pickerCaptor.getValue().pickSubchannel(mock(PickSubchannelArgs.class));
    assertThat(result.getStatus().getCode()).isEqualTo(Code.UNAVAILABLE);
    assertThat(result.getStatus().getDescription())
        .isEqualTo("Resource " + CLUSTER_NAME + " is unavailable");
  }

  @Test
  public void transientError_noPreviousEndpointUpdateReceived() {
    deliverResolvedAddresses(null, null, fakeEndpointPickingPolicy);

    // Forwarding 20 seconds so that the xds client will deem EDS resource not available.
    fakeClock.forwardTime(20, TimeUnit.SECONDS);
    verify(helper).updateBalancingState(eq(TRANSIENT_FAILURE), any(SubchannelPicker.class));
  }

  @Test
  public void transientError_withPreviousEndpointUpdateReceived() {
    deliverResolvedAddresses(null, null, fakeEndpointPickingPolicy);
    // Endpoint update received.
    ClusterLoadAssignment clusterLoadAssignment =
        buildClusterLoadAssignment(CLUSTER_NAME,
            ImmutableList.of(
                buildLocalityLbEndpoints("region1", "zone1", "subzone1",
                    ImmutableList.of(
                        buildLbEndpoint("192.168.0.1", 8080, HEALTHY, 2)),
                    1, 0)),
            ImmutableList.of(buildDropOverload("throttle", 1000)));
    deliverClusterLoadAssignments(clusterLoadAssignment);

    verify(helper, never()).updateBalancingState(
        eq(TRANSIENT_FAILURE), any(SubchannelPicker.class));

    // XdsClient stream receives an error.
    responseObserver.onError(new RuntimeException("fake error"));
    verify(helper, never()).updateBalancingState(
        eq(TRANSIENT_FAILURE), any(SubchannelPicker.class));
  }

  /**
   * Converts ClusterLoadAssignment data to {@link EndpointUpdate}. All the needed data, that is
   * clusterName, localityLbEndpointsMap and dropPolicies, is extracted from ClusterLoadAssignment,
   * and all other data is ignored.
   */
  private static EndpointUpdate getEndpointUpdateFromClusterAssignmentV2(
      ClusterLoadAssignment clusterLoadAssignment) {
    EndpointUpdate.Builder endpointUpdateBuilder = EndpointUpdate.newBuilder();
    endpointUpdateBuilder.setClusterName(clusterLoadAssignment.getClusterName());
    for (DropOverload dropOverload : clusterLoadAssignment.getPolicy().getDropOverloadsList()) {
      endpointUpdateBuilder.addDropPolicy(
          EnvoyProtoData.DropOverload.fromEnvoyProtoDropOverloadV2(dropOverload));
    }
    for (LocalityLbEndpoints localityLbEndpoints : clusterLoadAssignment.getEndpointsList()) {
      endpointUpdateBuilder.addLocalityLbEndpoints(
          EnvoyProtoData.Locality.fromEnvoyProtoLocalityV2(
              localityLbEndpoints.getLocality()),
          EnvoyProtoData.LocalityLbEndpoints.fromEnvoyProtoLocalityLbEndpointsV2(
              localityLbEndpoints));
    }
    return endpointUpdateBuilder.build();
  }

  private void deliverResolvedAddresses(
      @Nullable String edsServiceName,
      @Nullable String lrsServerName,
      PolicySelection endpointPickingPolicy) {
    EdsConfig config =
        new EdsConfig(CLUSTER_NAME, edsServiceName, lrsServerName, endpointPickingPolicy);
    ResolvedAddresses.Builder resolvedAddressBuilder = ResolvedAddresses.newBuilder()
        .setAddresses(ImmutableList.<EquivalentAddressGroup>of())
        .setLoadBalancingPolicyConfig(config);
    if (isFullFlow) {
      resolvedAddressBuilder.setAttributes(
          Attributes.newBuilder().set(XdsAttributes.XDS_CLIENT_POOL,
              xdsClientPoolFromResolveAddresses).build());
    }
    edsLb.handleResolvedAddresses(resolvedAddressBuilder.build());
  }

  private void deliverClusterLoadAssignments(ClusterLoadAssignment clusterLoadAssignment) {
    responseObserver.onNext(
          buildDiscoveryResponse(
              String.valueOf(versionIno++),
              ImmutableList.of(Any.pack(clusterLoadAssignment)),
              XdsClientImpl.ADS_TYPE_URL_EDS_V2,
              String.valueOf(nonce++)));
  }

  private void assertLatestConnectivityState(ConnectivityState expectedState) {
    verify(helper, atLeastOnce()).updateBalancingState(
        connectivityStateCaptor.capture(), pickerCaptor.capture());
    assertThat(connectivityStateCaptor.getValue()).isEqualTo(expectedState);
  }

  private void assertLatestSubchannelPicker(Subchannel expectedSubchannelToPick) {
    assertLatestConnectivityState(READY);
    assertThat(
            pickerCaptor.getValue().pickSubchannel(mock(PickSubchannelArgs.class)).getSubchannel())
        .isEqualTo(expectedSubchannelToPick);
  }

  /**
   * Matcher of ResolvedAddresses for round robin load balancer based on the set of backends.
   */
  private static final class RoundRobinBackendsMatcher
      implements ArgumentMatcher<ResolvedAddresses> {

    final List<java.net.SocketAddress> socketAddresses;

    RoundRobinBackendsMatcher(List<java.net.SocketAddress> socketAddresses) {
      this.socketAddresses = socketAddresses;
    }

    @Override
    public boolean matches(ResolvedAddresses argument) {
      List<java.net.SocketAddress> backends = new ArrayList<>();
      for (EquivalentAddressGroup eag : argument.getAddresses()) {
        backends.add(Iterables.getOnlyElement(eag.getAddresses()));
      }
      return socketAddresses.equals(backends);
    }

    static Builder builder() {
      return new Builder();
    }

    static final class Builder {
      final List<java.net.SocketAddress> socketAddresses = new ArrayList<>();

      Builder addHostAndPort(String host, int port) {
        socketAddresses.add(new InetSocketAddress(host, port));
        return this;
      }

      RoundRobinBackendsMatcher build() {
        return new RoundRobinBackendsMatcher(socketAddresses);
      }
    }
  }

  /**
   * A fake ObjectPool of XdsClient that keeps track of invocation times of getObject() and
   * returnObject().
   */
  private static final class FakeXdsClientPool implements ObjectPool<XdsClient> {
    final XdsClient xdsClient;
    int timesGetObjectCalled;
    int timesReturnObjectCalled;

    FakeXdsClientPool(XdsClient xdsClient) {
      this.xdsClient = xdsClient;
    }

    @Override
    public synchronized XdsClient getObject() {
      timesGetObjectCalled++;
      return xdsClient;
    }

    @Override
    public synchronized XdsClient returnObject(Object object) {
      timesReturnObjectCalled++;
      assertThat(timesReturnObjectCalled).isAtMost(timesGetObjectCalled);
      return null;
    }
  }
}
