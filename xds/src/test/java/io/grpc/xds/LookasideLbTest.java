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
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.protobuf.Any;
import com.google.protobuf.UInt32Value;
import io.envoyproxy.envoy.api.v2.ClusterLoadAssignment;
import io.envoyproxy.envoy.api.v2.ClusterLoadAssignment.Policy.DropOverload;
import io.envoyproxy.envoy.api.v2.DiscoveryRequest;
import io.envoyproxy.envoy.api.v2.DiscoveryResponse;
import io.envoyproxy.envoy.api.v2.core.Address;
import io.envoyproxy.envoy.api.v2.core.HealthStatus;
import io.envoyproxy.envoy.api.v2.core.Node;
import io.envoyproxy.envoy.api.v2.core.SocketAddress;
import io.envoyproxy.envoy.api.v2.endpoint.Endpoint;
import io.envoyproxy.envoy.api.v2.endpoint.LbEndpoint;
import io.envoyproxy.envoy.api.v2.endpoint.LocalityLbEndpoints;
import io.envoyproxy.envoy.service.discovery.v2.AggregatedDiscoveryServiceGrpc.AggregatedDiscoveryServiceImplBase;
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
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.SynchronizationContext;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.internal.FakeClock;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.GrpcCleanupRule;
import io.grpc.xds.Bootstrapper.BootstrapInfo;
import io.grpc.xds.Bootstrapper.ChannelCreds;
import io.grpc.xds.Bootstrapper.ServerInfo;
import io.grpc.xds.LookasideLb.EndpointUpdateCallback;
import io.grpc.xds.XdsClient.EndpointUpdate;
import io.grpc.xds.XdsClient.EndpointWatcher;
import io.grpc.xds.XdsClient.RefCountedXdsClientObjectPool;
import io.grpc.xds.XdsClient.XdsChannelFactory;
import io.grpc.xds.XdsClient.XdsClientFactory;
import io.grpc.xds.XdsLoadBalancerProvider.XdsConfig;
import java.net.InetSocketAddress;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatcher;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/**
 * Tests for {@link LookasideLb}.
 */
@RunWith(JUnit4.class)
public class LookasideLbTest {

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

  // Monitor responseObservers on the server that the bootstrapChannel connects to.
  private final ArrayDeque<StreamObserver<DiscoveryResponse>> responseObservers =
      new ArrayDeque<>();
  // Monitor endpointWatcher added to xdsClientFromResolvedAddresses.
  private final Map<String, EndpointWatcher> endpointWatchers = new HashMap<>();

  private final LoadBalancerRegistry lbRegistry = new LoadBalancerRegistry();

  // Child helpers keyed by locality names.
  private final Map<String, Helper> childHelpers = new HashMap<>();
  // Child balancers keyed by locality names.
  private final Map<String, LoadBalancer> childBalancers = new HashMap<>();

  @Mock
  private Helper helper;
  @Mock
  private EndpointUpdateCallback edsUpdateCallback;
  @Captor
  ArgumentCaptor<ConnectivityState> connectivityStateCaptor;
  @Captor
  ArgumentCaptor<SubchannelPicker> pickerCaptor;

  private LoadBalancer lookasideLb;
  private boolean withBootstrap;
  private boolean withXdsClientPoolAttributes;
  private ManagedChannel bootstrapChannel;
  private RefCountedXdsClientObjectPool xdsClientPoolFromResolveAddresses;
  private XdsClient xdsClientFromResolvedAddresses;

  @Before
  public void setUp() {
    doReturn(SERVICE_AUTHORITY).when(helper).getAuthority();
    doReturn(syncContext).when(helper).getSynchronizationContext();
    doReturn(mock(ChannelLogger.class)).when(helper).getChannelLogger();
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
  }

  @After
  public void tearDown() {
    lookasideLb.shutdown();

    for (LoadBalancer childBalancer : childBalancers.values()) {
      verify(childBalancer).shutdown();
    }

    if (withBootstrap) {
      assert !withXdsClientPoolAttributes;
      assertThat(bootstrapChannel.isShutdown()).isTrue();
    }
    if (withXdsClientPoolAttributes) {
      assert !withBootstrap;
      xdsClientPoolFromResolveAddresses.returnObject(xdsClientFromResolvedAddresses);
      assertThat(xdsClientPoolFromResolveAddresses.xdsClient).isNull();
    }
  }

  private void setUpWithBootstrap() throws Exception {
    withBootstrap = true;
    Bootstrapper bootstrapper = mock(Bootstrapper.class);
    AggregatedDiscoveryServiceImplBase serviceImpl = new AggregatedDiscoveryServiceImplBase() {
      @Override
      public StreamObserver<DiscoveryRequest> streamAggregatedResources(
          final StreamObserver<DiscoveryResponse> responseObserver) {
        responseObservers.offer(responseObserver);
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
    bootstrapChannel = cleanupRule.register(
        InProcessChannelBuilder
            .forName(serverName)
            .directExecutor()
            .build());
    List<ServerInfo> serverList =
        ImmutableList.of(
            new ServerInfo("trafficdirector.googleapis.com", ImmutableList.<ChannelCreds>of()));
    BootstrapInfo bootstrapInfo = new BootstrapInfo(serverList, Node.getDefaultInstance());
    doReturn(bootstrapInfo).when(bootstrapper).readBootstrap();

    XdsChannelFactory channelFactory = new XdsChannelFactory() {
      @Override
      ManagedChannel createChannel(List<ServerInfo> servers) {
        assertThat(Iterables.getOnlyElement(servers).getServerUri())
            .isEqualTo("trafficdirector.googleapis.com");
        return bootstrapChannel;
      }
    };

    lookasideLb = new LookasideLb(
        helper, edsUpdateCallback, lbRegistry, bootstrapper, channelFactory);
  }

  private void setUpWithXdsClientPoolAttributes() {
    withXdsClientPoolAttributes = true;
    XdsClientFactory xdsClientFactory = new XdsClientFactory() {
      @Override
      XdsClient createXdsClient() {
        return new XdsClient() {
          @Override
          void watchEndpointData(String clusterName, EndpointWatcher watcher) {
            endpointWatchers.put(clusterName, watcher);
          }

          @Override
          void cancelEndpointDataWatch(String clusterName, EndpointWatcher watcher) {
            EndpointWatcher removedWatcher = endpointWatchers.remove(clusterName);
            if (removedWatcher != null) {
              assertThat(removedWatcher).isSameInstanceAs(watcher);
            }
          }

          @Override
          void shutdown() {}
        };
      }
    };
    xdsClientPoolFromResolveAddresses = new RefCountedXdsClientObjectPool(xdsClientFactory);
    xdsClientFromResolvedAddresses = xdsClientPoolFromResolveAddresses.getObject();

    lookasideLb = new LookasideLb(
        helper, edsUpdateCallback, lbRegistry, mock(Bootstrapper.class),
        mock(XdsChannelFactory.class));
  }

  @Test
  public void handleNameResolutionErrorBeforeAndAfterEdsWorkding_withXdsClientRefAttributes() {
    setUpWithXdsClientPoolAttributes();
    handleNameResolutionErrorBeforeAndAfterEdsWorkding();
  }

  @Test
  public void handleNameResolutionErrorBeforeAndAfterEdsWorkding_withBootstrap() throws Exception {
    setUpWithBootstrap();
    handleNameResolutionErrorBeforeAndAfterEdsWorkding();
  }

  private void handleNameResolutionErrorBeforeAndAfterEdsWorkding() {
    handleResolvedAddresses(new XdsConfig(null, null, "edsServiceName1", null));

    // handleResolutionError() before receiving any endpoint update.
    lookasideLb.handleNameResolutionError(Status.DATA_LOSS.withDescription("fake status"));
    verify(helper).updateBalancingState(eq(TRANSIENT_FAILURE), any(SubchannelPicker.class));

    // Endpoint update received.
    ClusterLoadAssignment clusterLoadAssignment =
        buildClusterLoadAssignment("edsServiceName1",
            ImmutableList.of(
                buildLocalityLbEndpoints("region1", "zone1", "subzone1",
                    ImmutableList.of(
                        buildLbEndpoint("192.168.0.1", 8080, HealthStatus.HEALTHY, 2)),
                    1, 0)),
            ImmutableList.of(buildDropOverload("throttle", 1000)));
    receiveEndpointUpdate(clusterLoadAssignment);

    // handleResolutionError() after receiving endpoint update.
    lookasideLb.handleNameResolutionError(Status.DATA_LOSS.withDescription("fake status"));
    // No more TRANSIENT_FAILURE.
    verify(helper, times(1)).updateBalancingState(
        eq(TRANSIENT_FAILURE), any(SubchannelPicker.class));
  }

  @Test
  public void handleEdsServiceNameChangeInXdsConfig_switchGracefully_withXdsClientRefAttributes() {
    setUpWithXdsClientPoolAttributes();
    handleEdsServiceNameChangeInXdsConfig();
  }

  @Test
  public void handleEdsServiceNameChangeInXdsConfig_switchGracefully_withBootstrap()
      throws Exception {
    setUpWithBootstrap();
    handleEdsServiceNameChangeInXdsConfig();
  }

  private void handleEdsServiceNameChangeInXdsConfig() {
    assertThat(childHelpers).isEmpty();

    handleResolvedAddresses(new XdsConfig(null, null, "edsServiceName1", null));
    ClusterLoadAssignment clusterLoadAssignment =
        buildClusterLoadAssignment("edsServiceName1",
            ImmutableList.of(
                buildLocalityLbEndpoints("region1", "zone1", "subzone1",
                    ImmutableList.of(
                        buildLbEndpoint("192.168.0.1", 8080, HealthStatus.HEALTHY, 2)),
                    1, 0)),
            ImmutableList.<DropOverload>of());
    receiveEndpointUpdate(clusterLoadAssignment);
    assertThat(childHelpers).hasSize(1);
    Helper childHelper1 = childHelpers.get("subzone1");
    LoadBalancer childBalancer1 = childBalancers.get("subzone1");
    verify(childBalancer1).handleResolvedAddresses(
        argThat(RoundRobinBackendsMatcher.builder().addHostAndPort("192.168.0.1", 8080).build()));

    childHelper1.updateBalancingState(CONNECTING, mock(SubchannelPicker.class));
    assertLatestConnectivityState(CONNECTING);

    // Change edsServicename to edsServiceName2.
    handleResolvedAddresses(new XdsConfig(null, null, "edsServiceName2", null));
    // The old balancer was not READY, so it will be shutdown immediately.
    verify(childBalancer1).shutdown();

    clusterLoadAssignment =
        buildClusterLoadAssignment("edsServiceName2",
            ImmutableList.of(
                buildLocalityLbEndpoints("region2", "zone2", "subzone2",
                    ImmutableList.of(
                        buildLbEndpoint("192.168.0.2", 8080, HealthStatus.HEALTHY, 2)),
                    1, 0)),
            ImmutableList.<DropOverload>of());
    receiveEndpointUpdate(clusterLoadAssignment);
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
    handleResolvedAddresses(new XdsConfig(null, null, "edsServiceName3", null));
    clusterLoadAssignment =
        buildClusterLoadAssignment("edsServiceName3",
            ImmutableList.of(
                buildLocalityLbEndpoints("region3", "zone3", "subzone3",
                    ImmutableList.of(
                        buildLbEndpoint("192.168.0.3", 8080, HealthStatus.HEALTHY, 2)),
                    1, 0)),
            ImmutableList.<DropOverload>of());
    receiveEndpointUpdate(clusterLoadAssignment);

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
    handleResolvedAddresses(new XdsConfig(null, null, "edsServiceName4", null));
    verify(childBalancer3).shutdown();

    clusterLoadAssignment =
        buildClusterLoadAssignment("edsServiceName4",
            ImmutableList.of(
                buildLocalityLbEndpoints("region4", "zone4", "subzone4",
                    ImmutableList.of(
                        buildLbEndpoint("192.168.0.4", 8080, HealthStatus.HEALTHY, 2)),
                    1, 0)),
            ImmutableList.<DropOverload>of());
    receiveEndpointUpdate(clusterLoadAssignment);

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
    handleResolvedAddresses(new XdsConfig(null, null, "edsServiceName5", null));
    clusterLoadAssignment =
        buildClusterLoadAssignment("edsServiceName5",
            ImmutableList.of(
                buildLocalityLbEndpoints("region5", "zone5", "subzone5",
                    ImmutableList.of(
                        buildLbEndpoint("192.168.0.5", 8080, HealthStatus.HEALTHY, 2)),
                    1, 0)),
            ImmutableList.<DropOverload>of());
    receiveEndpointUpdate(clusterLoadAssignment);

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
  public void firstAndSecondEdsResponseReceived() throws Exception {
    setUpWithBootstrap();
    handleResolvedAddresses(new XdsConfig(null, null, "edsServiceName1", null));

    verify(edsUpdateCallback, never()).onWorking();

    // first EDS response
    ClusterLoadAssignment clusterLoadAssignment =
        buildClusterLoadAssignment("edsServiceName1",
            ImmutableList.of(
                buildLocalityLbEndpoints("region1", "zone1", "subzone1",
                    ImmutableList.of(
                        buildLbEndpoint("192.168.0.1", 8080, HealthStatus.HEALTHY, 2)),
                    1, 0)),
            ImmutableList.<DropOverload>of());
    receiveEndpointUpdate(clusterLoadAssignment);

    verify(edsUpdateCallback).onWorking();

    // second EDS response
    clusterLoadAssignment =
        buildClusterLoadAssignment("edsServiceName1",
            ImmutableList.of(
                buildLocalityLbEndpoints("region1", "zone1", "subzone1",
                    ImmutableList.of(
                        buildLbEndpoint("192.168.0.1", 8080, HealthStatus.HEALTHY, 2),
                        buildLbEndpoint("192.168.0.2", 8080, HealthStatus.HEALTHY, 2)),
                    1, 0)),
            ImmutableList.<DropOverload>of());
    receiveEndpointUpdate(clusterLoadAssignment);
    verify(edsUpdateCallback, times(1)).onWorking();
    verify(edsUpdateCallback, never()).onError();
  }

  @Test
  public void handleAllDropUpdates() throws Exception {
    setUpWithBootstrap();
    handleResolvedAddresses(new XdsConfig(null, null, "edsServiceName1", null));

    ClusterLoadAssignment clusterLoadAssignment = buildClusterLoadAssignment(
        "edsServiceName1",
        ImmutableList.of(
            buildLocalityLbEndpoints("region1", "zone1", "subzone1",
                ImmutableList.of(
                    buildLbEndpoint("192.168.0.1", 8080, HealthStatus.HEALTHY, 2)),
                1, 0)),
        ImmutableList.<DropOverload>of());
    receiveEndpointUpdate(clusterLoadAssignment);

    verify(edsUpdateCallback, never()).onAllDrop();

    clusterLoadAssignment = buildClusterLoadAssignment(
        "edsServiceName1",
        ImmutableList.of(
            buildLocalityLbEndpoints("region1", "zone1", "subzone1",
                ImmutableList.of(
                    buildLbEndpoint("192.168.0.1", 8080, HealthStatus.HEALTHY, 2)),
                1, 0)),
        ImmutableList.of(
            buildDropOverload("cat_1", 3),
            buildDropOverload("cat_2", 1_000_001),
            buildDropOverload("cat_3", 4)));
    receiveEndpointUpdate(clusterLoadAssignment);

    verify(edsUpdateCallback).onAllDrop();

    // TODO: verify picker.

    verify(edsUpdateCallback, never()).onError();
  }

  @Test
  public void handleLocalityAssignmentUpdates() throws Exception {
    setUpWithBootstrap();
    handleResolvedAddresses(new XdsConfig(null, null, "edsServiceName1", null));

    io.envoyproxy.envoy.api.v2.core.Locality localityProto1 =
        io.envoyproxy.envoy.api.v2.core.Locality
            .newBuilder()
            .setRegion("region1")
            .setZone("zone1")
            .setSubZone("subzone1")
            .build();
    LbEndpoint endpoint11 = LbEndpoint.newBuilder()
        .setEndpoint(Endpoint.newBuilder()
            .setAddress(Address.newBuilder()
                .setSocketAddress(SocketAddress.newBuilder()
                    .setAddress("addr11").setPortValue(11))))
        .setLoadBalancingWeight(UInt32Value.of(11))
        .build();
    LbEndpoint endpoint12 = LbEndpoint.newBuilder()
        .setEndpoint(Endpoint.newBuilder()
            .setAddress(Address.newBuilder()
                .setSocketAddress(SocketAddress.newBuilder()
                    .setAddress("addr12").setPortValue(12))))
        .setLoadBalancingWeight(UInt32Value.of(12))
        .build();
    io.envoyproxy.envoy.api.v2.core.Locality localityProto2 =
        io.envoyproxy.envoy.api.v2.core.Locality
            .newBuilder()
            .setRegion("region2")
            .setZone("zone2")
            .setSubZone("subzone2")
            .build();
    LbEndpoint endpoint21 = LbEndpoint.newBuilder()
        .setEndpoint(Endpoint.newBuilder()
            .setAddress(Address.newBuilder()
                .setSocketAddress(SocketAddress.newBuilder()
                    .setAddress("addr21").setPortValue(21))))
        .setLoadBalancingWeight(UInt32Value.of(21))
        .build();
    LbEndpoint endpoint22 = LbEndpoint.newBuilder()
        .setEndpoint(Endpoint.newBuilder()
            .setAddress(Address.newBuilder()
                .setSocketAddress(SocketAddress.newBuilder()
                    .setAddress("addr22").setPortValue(22))))
        .setLoadBalancingWeight(UInt32Value.of(22))
        .build();
    io.envoyproxy.envoy.api.v2.core.Locality localityProto3 =
        io.envoyproxy.envoy.api.v2.core.Locality
            .newBuilder()
            .setRegion("region3")
            .setZone("zone3")
            .setSubZone("subzone3")
            .build();
    LbEndpoint endpoint3 = LbEndpoint.newBuilder()
        .setEndpoint(Endpoint.newBuilder()
            .setAddress(Address.newBuilder()
                .setSocketAddress(SocketAddress.newBuilder()
                    .setAddress("addr31").setPortValue(31))))
        .setLoadBalancingWeight(UInt32Value.of(31))
        .build();
    ClusterLoadAssignment clusterLoadAssignment = ClusterLoadAssignment.newBuilder()
        .setClusterName("edsServiceName1")
        .addEndpoints(io.envoyproxy.envoy.api.v2.endpoint.LocalityLbEndpoints.newBuilder()
            .setLocality(localityProto1)
            .addLbEndpoints(endpoint11)
            .addLbEndpoints(endpoint12)
            .setLoadBalancingWeight(UInt32Value.of(1)))
        .addEndpoints(io.envoyproxy.envoy.api.v2.endpoint.LocalityLbEndpoints.newBuilder()
            .setLocality(localityProto2)
            .addLbEndpoints(endpoint21)
            .addLbEndpoints(endpoint22)
            .setLoadBalancingWeight(UInt32Value.of(2)))
        .addEndpoints(io.envoyproxy.envoy.api.v2.endpoint.LocalityLbEndpoints.newBuilder()
            .setLocality(localityProto3)
            .addLbEndpoints(endpoint3)
            .setLoadBalancingWeight(UInt32Value.of(0)))
        .build();
    receiveEndpointUpdate(clusterLoadAssignment);

    // TODO: verify child helpers handleResolvedAddressGroups, verify picker update.

    verify(edsUpdateCallback, never()).onError();
  }

  @Ignore // FIXME(zdapeng): Enable test once endpoint watcher timeout is implemented.
  @Test
  public void verifyErrorPropagation_withBootstrap() throws Exception {
    setUpWithBootstrap();
    handleResolvedAddresses(new XdsConfig(null, null, "edsServiceName1", null));

    verify(edsUpdateCallback, never()).onError();
    fakeClock.forwardNanos(TimeUnit.SECONDS.toNanos(20));
    verify(edsUpdateCallback).onError();
  }

  @Test
  public void verifyErrorPropagation_withXdsClientRefAttributes() {
    setUpWithXdsClientPoolAttributes();
    handleResolvedAddresses(new XdsConfig(null, null, "edsServiceName1", null));

    verify(edsUpdateCallback, never()).onError();
    endpointWatchers.get("edsServiceName1")
        .onError(Status.DATA_LOSS.withDescription("fake_status"));
    verify(edsUpdateCallback).onError();
  }

  /**
   * Converts ClusterLoadAssignment data to {@link EndpointUpdate}. All the needed data, that is
   * clusterName, localityLbEndpointsMap and dropPolicies, is extracted from ClusterLoadAssignment,
   * and all other data is ignored.
   */
  private static EndpointUpdate getEndpointUpdatefromClusterAssignment(
      ClusterLoadAssignment clusterLoadAssignment) {
    EndpointUpdate.Builder endpointUpdateBuilder = EndpointUpdate.newBuilder();
    endpointUpdateBuilder.setClusterName(clusterLoadAssignment.getClusterName());
    for (DropOverload dropOverload : clusterLoadAssignment.getPolicy().getDropOverloadsList()) {
      endpointUpdateBuilder.addDropPolicy(
          EnvoyProtoData.DropOverload.fromEnvoyProtoDropOverload(dropOverload));
    }
    for (LocalityLbEndpoints localityLbEndpoints : clusterLoadAssignment.getEndpointsList()) {
      endpointUpdateBuilder.addLocalityLbEndpoints(
          EnvoyProtoData.Locality.fromEnvoyProtoLocality(
              localityLbEndpoints.getLocality()),
          EnvoyProtoData.LocalityLbEndpoints.fromEnvoyProtoLocalityLbEndpoints(
              localityLbEndpoints));

    }
    return endpointUpdateBuilder.build();
  }

  private void handleResolvedAddresses(XdsConfig xdsConfig) {
    ResolvedAddresses.Builder resolvedAddressBuilder = ResolvedAddresses.newBuilder()
        .setAddresses(ImmutableList.<EquivalentAddressGroup>of())
        .setLoadBalancingPolicyConfig(xdsConfig);
    if (withXdsClientPoolAttributes) {
      resolvedAddressBuilder.setAttributes(
          Attributes.newBuilder().set(XdsAttributes.XDS_CLIENT_REF,
              xdsClientPoolFromResolveAddresses).build());
    }
    lookasideLb.handleResolvedAddresses(resolvedAddressBuilder.build());
  }

  private void receiveEndpointUpdate(ClusterLoadAssignment clusterLoadAssignment) {
    if (withBootstrap) {
      responseObservers.peekLast().onNext(
          buildDiscoveryResponse(
              getNextVersionInfo(),
              ImmutableList.of(Any.pack(clusterLoadAssignment)),
              XdsClientImpl.ADS_TYPE_URL_EDS,
              getNextNonce()));
    } else if (withXdsClientPoolAttributes) {
      endpointWatchers.get(clusterLoadAssignment.getClusterName())
          .onEndpointChanged(getEndpointUpdatefromClusterAssignment(clusterLoadAssignment));
    }
  }

  int versionIno;

  private String getNextVersionInfo() {
    return String.valueOf(versionIno++);
  }

  int nonce;

  private String getNextNonce() {
    return String.valueOf(nonce++);
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
  private static class RoundRobinBackendsMatcher implements ArgumentMatcher<ResolvedAddresses> {

    final Set<java.net.SocketAddress> socketAddresses;

    RoundRobinBackendsMatcher(Set<java.net.SocketAddress> socketAddresses) {
      this.socketAddresses = socketAddresses;
    }

    @Override
    public boolean matches(ResolvedAddresses argument) {
      Set<java.net.SocketAddress> backends = new HashSet<>();
      for (EquivalentAddressGroup eag : argument.getAddresses()) {
        backends.add(Iterables.getOnlyElement(eag.getAddresses()));
      }
      return socketAddresses.equals(backends);
    }

    static Builder builder() {
      return new Builder();
    }

    static final class Builder {
      final ImmutableSet.Builder<java.net.SocketAddress> socketAddressesBuilder =
          ImmutableSet.builder();

      Builder addHostAndPort(String host, int port) {
        socketAddressesBuilder.add(new InetSocketAddress(host, port));
        return this;
      }

      RoundRobinBackendsMatcher build() {
        return new RoundRobinBackendsMatcher(socketAddressesBuilder.build());
      }
    }
  }
}
