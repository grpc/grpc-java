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
import static io.grpc.LoadBalancer.ATTR_LOAD_BALANCING_CONFIG;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.protobuf.Any;
import com.google.protobuf.UInt32Value;
import io.envoyproxy.envoy.api.v2.ClusterLoadAssignment;
import io.envoyproxy.envoy.api.v2.ClusterLoadAssignment.Policy;
import io.envoyproxy.envoy.api.v2.DiscoveryRequest;
import io.envoyproxy.envoy.api.v2.DiscoveryResponse;
import io.envoyproxy.envoy.api.v2.core.Address;
import io.envoyproxy.envoy.api.v2.core.Node;
import io.envoyproxy.envoy.api.v2.core.SocketAddress;
import io.envoyproxy.envoy.api.v2.endpoint.Endpoint;
import io.envoyproxy.envoy.api.v2.endpoint.LbEndpoint;
import io.envoyproxy.envoy.service.discovery.v2.AggregatedDiscoveryServiceGrpc.AggregatedDiscoveryServiceImplBase;
import io.envoyproxy.envoy.type.FractionalPercent;
import io.envoyproxy.envoy.type.FractionalPercent.DenominatorType;
import io.grpc.Attributes;
import io.grpc.ChannelLogger;
import io.grpc.ConnectivityState;
import io.grpc.EquivalentAddressGroup;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancer.ResolvedAddresses;
import io.grpc.LoadBalancer.SubchannelPicker;
import io.grpc.LoadBalancerProvider;
import io.grpc.LoadBalancerRegistry;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.SynchronizationContext;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.internal.BackoffPolicy.Provider;
import io.grpc.internal.FakeClock;
import io.grpc.internal.JsonParser;
import io.grpc.internal.ObjectPool;
import io.grpc.internal.testing.StreamRecorder;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.GrpcCleanupRule;
import io.grpc.xds.Bootstrapper.BootstrapInfo;
import io.grpc.xds.Bootstrapper.ChannelCreds;
import io.grpc.xds.EnvoyProtoData.DropOverload;
import io.grpc.xds.EnvoyProtoData.Locality;
import io.grpc.xds.EnvoyProtoData.LocalityLbEndpoints;
import io.grpc.xds.LoadReportClient.LoadReportCallback;
import io.grpc.xds.LoadReportClientImpl.LoadReportClientFactory;
import io.grpc.xds.LocalityStore.LocalityStoreFactory;
import io.grpc.xds.LookasideLb.EndpointUpdateCallback;
import io.grpc.xds.XdsClient.EndpointUpdate;
import io.grpc.xds.XdsClient.EndpointWatcher;
import io.grpc.xds.XdsClient.RefCountedXdsClientObjectPool;
import io.grpc.xds.XdsClient.XdsClientFactory;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/**
 * Tests for {@link LookasideLb}.
 */
@RunWith(JUnit4.class)
public class LookasideLbTest {

  private static final String SERVICE_AUTHORITY = "test authority";

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
  private final StreamRecorder<DiscoveryRequest> streamRecorder = StreamRecorder.create();

  private final DiscoveryResponse edsResponse =
      DiscoveryResponse.newBuilder()
          .addResources(Any.pack(ClusterLoadAssignment.getDefaultInstance()))
          .setTypeUrl("type.googleapis.com/envoy.api.v2.ClusterLoadAssignment")
          .build();
  private final Deque<Helper> helpers = new ArrayDeque<>();
  private final Deque<LocalityStore> localityStores = new ArrayDeque<>();
  private final Deque<LoadReportClient> loadReportClients = new ArrayDeque<>();
  private final FakeClock fakeClock = new FakeClock();

  @Mock
  private Helper helper;
  @Mock
  private EndpointUpdateCallback edsUpdateCallback;
  @Mock
  private Bootstrapper bootstrapper;
  @Captor
  private ArgumentCaptor<ImmutableMap<Locality, LocalityLbEndpoints>>
      localityEndpointsMappingCaptor;

  private ManagedChannel channel;
  private ManagedChannel channel2;
  private StreamObserver<DiscoveryResponse> serverResponseWriter;
  private LoadBalancer lookasideLb;
  private ResolvedAddresses defaultResolvedAddress;

  @Before
  public void setUp() throws Exception {
    AggregatedDiscoveryServiceImplBase serviceImpl = new AggregatedDiscoveryServiceImplBase() {
      @Override
      public StreamObserver<DiscoveryRequest> streamAggregatedResources(
          final StreamObserver<DiscoveryResponse> responseObserver) {
        serverResponseWriter = responseObserver;

        return new StreamObserver<DiscoveryRequest>() {

          @Override
          public void onNext(DiscoveryRequest value) {
            streamRecorder.onNext(value);
          }

          @Override
          public void onError(Throwable t) {
            streamRecorder.onError(t);
          }

          @Override
          public void onCompleted() {
            streamRecorder.onCompleted();
            responseObserver.onCompleted();
          }
        };
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
    channel2 = cleanupRule.register(
        InProcessChannelBuilder
            .forName(serverName)
            .directExecutor()
            .build());

    doReturn(SERVICE_AUTHORITY).when(helper).getAuthority();
    doReturn(syncContext).when(helper).getSynchronizationContext();
    doReturn(mock(ChannelLogger.class)).when(helper).getChannelLogger();
    doReturn(channel, channel2).when(helper).createResolvingOobChannel(anyString());
    doReturn(fakeClock.getScheduledExecutorService()).when(helper).getScheduledExecutorService();

    LocalityStoreFactory localityStoreFactory = new LocalityStoreFactory() {
      @Override
      public LocalityStore newLocalityStore(
          Helper helper, LoadBalancerRegistry lbRegistry, LoadStatsStore loadStatsStore) {
        helpers.add(helper);
        LocalityStore localityStore = mock(LocalityStore.class);
        localityStores.add(localityStore);
        return localityStore;
      }
    };

    LoadReportClientFactory loadReportClientFactory = new LoadReportClientFactory() {
      @Override
      LoadReportClient createLoadReportClient(ManagedChannel channel, Helper helper,
          Provider backoffPolicyProvider, LoadStatsStore loadStatsStore) {
        LoadReportClient loadReportClient = mock(LoadReportClient.class);
        loadReportClients.add(loadReportClient);
        return loadReportClient;
      }
    };

    LoadBalancerRegistry lbRegistry = new LoadBalancerRegistry();
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
        return "supported1";
      }

      @Override
      public LoadBalancer newLoadBalancer(Helper helper) {
        return mock(LoadBalancer.class);
      }
    });

    lookasideLb = new LookasideLb(
        helper, edsUpdateCallback, lbRegistry, localityStoreFactory, loadReportClientFactory,
        bootstrapper);

    String lbConfigRaw11 = "{\"balancerName\" : \"dns:///balancer1.example.com:8080\"}";
    @SuppressWarnings("unchecked")
    Map<String, ?> lbConfig11 = (Map<String, ?>) JsonParser.parse(lbConfigRaw11);
    defaultResolvedAddress = ResolvedAddresses.newBuilder()
        .setAddresses(ImmutableList.<EquivalentAddressGroup>of())
        .setAttributes(Attributes.newBuilder().set(ATTR_LOAD_BALANCING_CONFIG, lbConfig11).build())
        .build();
  }

  @Test
  public void canHandleEmptyAddressListFromNameResolution() {
    assertThat(lookasideLb.canHandleEmptyAddressListFromNameResolution()).isTrue();
  }

  @Test
  public void handleNameResolutionErrorBeforeAndAfterEdsWorkding() throws Exception {
    XdsClientFactory xdsClientFactory = new XdsClientFactory() {
      @Override
      XdsClient createXdsClient() {
        return mock(XdsClient.class);
      }
    };
    ObjectPool<XdsClient> xdsClientRef = new RefCountedXdsClientObjectPool(xdsClientFactory);
    XdsClient xdsClientFromResolver = xdsClientRef.getObject();

    String lbConfigRaw =
        "{'childPolicy' : [{'supported1' : {}}], 'edsServiceName' : 'edsServiceName1'}"
            .replace("'", "\"");
    @SuppressWarnings("unchecked")
    Map<String, ?> lbConfig = (Map<String, ?>) JsonParser.parse(lbConfigRaw);
    ResolvedAddresses resolvedAddresses = ResolvedAddresses.newBuilder()
        .setAddresses(ImmutableList.<EquivalentAddressGroup>of())
        .setAttributes(Attributes.newBuilder()
            .set(ATTR_LOAD_BALANCING_CONFIG, lbConfig)
            .set(XdsAttributes.XDS_CLIENT_REF, xdsClientRef)
            .build())
        .build();

    lookasideLb.handleResolvedAddresses(resolvedAddresses);

    assertThat(helpers).hasSize(1);
    assertThat(localityStores).hasSize(1);
    ArgumentCaptor<EndpointWatcher> endpointWatcherCaptor =
        ArgumentCaptor.forClass(EndpointWatcher.class);
    verify(xdsClientFromResolver).watchEndpointData(
        eq("edsServiceName1"), endpointWatcherCaptor.capture());
    EndpointWatcher endpointWatcher = endpointWatcherCaptor.getValue();

    // handleResolutionError() before receiving any endpoint update.
    lookasideLb.handleNameResolutionError(Status.DATA_LOSS.withDescription("fake status"));
    verify(helper).updateBalancingState(eq(TRANSIENT_FAILURE), any(SubchannelPicker.class));

    // Endpoint update received.
    endpointWatcher.onEndpointChanged(
        EndpointUpdate.newBuilder().setClusterName("edsServiceName1").build());

    // handleResolutionError() after receiving endpoint update.
    lookasideLb.handleNameResolutionError(Status.DATA_LOSS.withDescription("fake status"));
    // No more TRANSIENT_FAILURE.
    verify(helper, times(1)).updateBalancingState(
        eq(TRANSIENT_FAILURE), any(SubchannelPicker.class));
  }

  @SuppressWarnings("unchecked")
  @Test
  public void handleEdsServiceNameChangeInXdsConfig_swtichGracefully()
      throws Exception {
    assertThat(helpers).isEmpty();
    assertThat(localityStores).isEmpty();
    assertThat(loadReportClients).isEmpty();

    List<EquivalentAddressGroup> eags = ImmutableList.of();
    XdsClientFactory xdsClientFactory = new XdsClientFactory() {
      @Override
      XdsClient createXdsClient() {
        return mock(XdsClient.class);
      }
    };
    ObjectPool<XdsClient> xdsClientRef = new RefCountedXdsClientObjectPool(xdsClientFactory);
    XdsClient xdsClientFromResolver = xdsClientRef.getObject();

    String lbConfigRaw =
        "{'childPolicy' : [{'supported1' : {}}], 'edsServiceName' : 'edsServiceName1'}"
            .replace("'", "\"");
    @SuppressWarnings("unchecked")
    Map<String, ?> lbConfig = (Map<String, ?>) JsonParser.parse(lbConfigRaw);
    ResolvedAddresses resolvedAddresses = ResolvedAddresses.newBuilder()
        .setAddresses(eags)
        .setAttributes(Attributes.newBuilder()
            .set(ATTR_LOAD_BALANCING_CONFIG, lbConfig)
            .set(XdsAttributes.XDS_CLIENT_REF, xdsClientRef)
            .build())
        .build();
    lookasideLb.handleResolvedAddresses(resolvedAddresses);

    assertThat(helpers).hasSize(1);
    assertThat(localityStores).hasSize(1);
    Helper helper1 = helpers.peekLast();
    LocalityStore localityStore1 = localityStores.peekLast();

    SubchannelPicker picker1 = mock(SubchannelPicker.class);
    helper1.updateBalancingState(CONNECTING, picker1);
    verify(helper).updateBalancingState(CONNECTING, picker1);

    // Change edsServicename to edsServiceName2.
    lbConfigRaw = "{'childPolicy' : [{'supported1' : {}}], 'edsServiceName' : 'edsServiceName2'}"
        .replace("'", "\"");
    lbConfig = (Map<String, ?>) JsonParser.parse(lbConfigRaw);
    resolvedAddresses = ResolvedAddresses.newBuilder()
        .setAddresses(eags)
        .setAttributes(Attributes.newBuilder()
            .set(ATTR_LOAD_BALANCING_CONFIG, lbConfig)
            .set(XdsAttributes.XDS_CLIENT_REF, xdsClientRef)
            .build())
        .build();
    lookasideLb.handleResolvedAddresses(resolvedAddresses);
    assertThat(helpers).hasSize(2);
    assertThat(localityStores).hasSize(2);
    Helper helper2 = helpers.peekLast();
    LocalityStore localityStore2 = localityStores.peekLast();
    SubchannelPicker picker2 = mock(SubchannelPicker.class);
    helper2.updateBalancingState(CONNECTING, picker2);
    verify(helper).updateBalancingState(CONNECTING, picker2);
    verify(localityStore1).reset();
    helper2.updateBalancingState(READY, picker2);
    verify(helper).updateBalancingState(READY, picker2);

    // Change edsServiceName to edsServiceName3.
    lbConfigRaw =  "{'childPolicy' : [{'supported1' : {}}], 'edsServiceName' : 'edsServiceName3'}"
        .replace("'", "\"");
    lbConfig = (Map<String, ?>) JsonParser.parse(lbConfigRaw);
    resolvedAddresses = ResolvedAddresses.newBuilder()
        .setAddresses(eags)
        .setAttributes(Attributes.newBuilder()
            .set(ATTR_LOAD_BALANCING_CONFIG, lbConfig)
            .set(XdsAttributes.XDS_CLIENT_REF, xdsClientRef)
            .build())
        .build();
    lookasideLb.handleResolvedAddresses(resolvedAddresses);

    assertThat(helpers).hasSize(3);
    assertThat(localityStores).hasSize(3);
    Helper helper3 = helpers.peekLast();
    LocalityStore localityStore3 = localityStores.peekLast();

    SubchannelPicker picker3 = mock(SubchannelPicker.class);
    helper3.updateBalancingState(CONNECTING, picker3);
    verify(helper, never()).updateBalancingState(CONNECTING, picker3);
    verify(localityStore2, never()).reset();
    picker2 = mock(SubchannelPicker.class);
    helper2.updateBalancingState(CONNECTING, picker2);
    // The old balancer becomes not READY, so the new balancer will update picker immediately.
    verify(helper).updateBalancingState(CONNECTING, picker3);
    verify(localityStore2).reset();

    // Change edsServiceName to edsServiceName4.
    lbConfigRaw =  "{'childPolicy' : [{'supported1' : {}}], 'edsServiceName' : 'edsServiceName4'}"
        .replace("'", "\"");
    lbConfig = (Map<String, ?>) JsonParser.parse(lbConfigRaw);
    resolvedAddresses = ResolvedAddresses.newBuilder()
        .setAddresses(eags)
        .setAttributes(Attributes.newBuilder()
            .set(ATTR_LOAD_BALANCING_CONFIG, lbConfig)
            .set(XdsAttributes.XDS_CLIENT_REF, xdsClientRef)
            .build())
        .build();
    lookasideLb.handleResolvedAddresses(resolvedAddresses);

    assertThat(helpers).hasSize(4);
    assertThat(localityStores).hasSize(4);
    Helper helper4 = helpers.peekLast();
    LocalityStore localityStore4 = localityStores.peekLast();
    verify(localityStore3).reset();
    SubchannelPicker picker4 = mock(SubchannelPicker.class);
    helper4.updateBalancingState(READY, picker4);
    verify(helper).updateBalancingState(READY, picker4);

    // Change edsServiceName to edsServiceName5.
    lbConfigRaw =  "{'childPolicy' : [{'supported1' : {}}], 'edsServiceName' : 'edsServiceName5'}"
        .replace("'", "\"");
    lbConfig = (Map<String, ?>) JsonParser.parse(lbConfigRaw);
    resolvedAddresses = ResolvedAddresses.newBuilder()
        .setAddresses(eags)
        .setAttributes(Attributes.newBuilder()
            .set(ATTR_LOAD_BALANCING_CONFIG, lbConfig)
            .set(XdsAttributes.XDS_CLIENT_REF, xdsClientRef)
            .build())
        .build();
    lookasideLb.handleResolvedAddresses(resolvedAddresses);

    assertThat(helpers).hasSize(5);
    assertThat(localityStores).hasSize(5);

    Helper helper5 = helpers.peekLast();
    LocalityStore localityStore5 = localityStores.peekLast();
    SubchannelPicker picker5 = mock(SubchannelPicker.class);
    helper5.updateBalancingState(CONNECTING, picker5);
    // The old balancer was READY, so the new balancer will gracefully switch and not update
    // non-READY picker.
    verify(helper, never()).updateBalancingState(any(ConnectivityState.class), eq(picker5));
    verify(localityStore4, never()).reset();

    helper5.updateBalancingState(READY, picker5);
    verify(helper).updateBalancingState(READY, picker5);
    verify(localityStore4).reset();

    verify(localityStore5, never()).reset();
    lookasideLb.shutdown();
    verify(localityStore5).reset();

    xdsClientRef.returnObject(xdsClientFromResolver);
  }

  @Deprecated // balancerName will be unsupported.
  @Test
  public void handleResolvedAddress_createLbChannel()
      throws Exception {
    // Test balancer created with the default real LookasideChannelLbFactory
    lookasideLb = new LookasideLb(helper, mock(EndpointUpdateCallback.class));
    String lbConfigRaw = "{'balancerName' : 'dns:///balancer1.example.com:8080'}"
        .replace("'", "\"");
    @SuppressWarnings("unchecked")
    Map<String, ?> lbConfig = (Map<String, ?>) JsonParser.parse(lbConfigRaw);
    ResolvedAddresses resolvedAddresses = ResolvedAddresses.newBuilder()
        .setAddresses(ImmutableList.<EquivalentAddressGroup>of())
        .setAttributes(Attributes.newBuilder().set(ATTR_LOAD_BALANCING_CONFIG, lbConfig).build())
        .build();

    verify(helper, never()).createResolvingOobChannel(anyString());
    lookasideLb.handleResolvedAddresses(resolvedAddresses);
    verify(helper).createResolvingOobChannel("dns:///balancer1.example.com:8080");

    lookasideLb.shutdown();
  }

  @Test
  public void handleResolvedAddress_withBootstrap() throws Exception {
    BootstrapInfo bootstrapInfo = new BootstrapInfo(
        "trafficdirector.googleapis.com", ImmutableList.<ChannelCreds>of(),
        Node.getDefaultInstance());
    doReturn(bootstrapInfo).when(bootstrapper).readBootstrap();

    String lbConfigRaw =
        "{'childPolicy' : [{'supported1' : {}}], 'edsServiceName' : 'edsServiceName1'}"
            .replace("'", "\"");
    @SuppressWarnings("unchecked")
    Map<String, ?> lbConfig = (Map<String, ?>) JsonParser.parse(lbConfigRaw);
    ResolvedAddresses resolvedAddresses = ResolvedAddresses.newBuilder()
        .setAddresses(ImmutableList.<EquivalentAddressGroup>of())
        .setAttributes(Attributes.newBuilder()
            .set(ATTR_LOAD_BALANCING_CONFIG, lbConfig)
            .build())
        .build();

    verify(helper, never()).createResolvingOobChannel(anyString());
    lookasideLb.handleResolvedAddresses(resolvedAddresses);
    verify(helper).createResolvingOobChannel("trafficdirector.googleapis.com");

    assertThat(helpers).hasSize(1);
    assertThat(localityStores).hasSize(1);
    Helper helper1 = helpers.peekLast();
    LocalityStore localityStore1 = localityStores.peekLast();
    SubchannelPicker picker = mock(SubchannelPicker.class);
    helper1.updateBalancingState(READY, picker);
    verify(helper).updateBalancingState(READY, picker);

    lookasideLb.shutdown();
    verify(localityStore1).reset();
  }

  @Test
  public void handleResolvedAddress_withXdsClientRefAttributes() throws Exception {
    XdsClientFactory xdsClientFactory = new XdsClientFactory() {
      @Override
      XdsClient createXdsClient() {
        return mock(XdsClient.class);
      }
    };
    ObjectPool<XdsClient> xdsClientRef = new RefCountedXdsClientObjectPool(xdsClientFactory);
    XdsClient xdsClientFromResolver = xdsClientRef.getObject();

    String lbConfigRaw =
        "{'childPolicy' : [{'supported1' : {}}], 'edsServiceName' : 'edsServiceName1'}"
            .replace("'", "\"");
    @SuppressWarnings("unchecked")
    Map<String, ?> lbConfig = (Map<String, ?>) JsonParser.parse(lbConfigRaw);
    ResolvedAddresses resolvedAddresses = ResolvedAddresses.newBuilder()
        .setAddresses(ImmutableList.<EquivalentAddressGroup>of())
        .setAttributes(Attributes.newBuilder()
            .set(ATTR_LOAD_BALANCING_CONFIG, lbConfig)
            .set(XdsAttributes.XDS_CLIENT_REF, xdsClientRef)
            .build())
        .build();

    lookasideLb.handleResolvedAddresses(resolvedAddresses);

    assertThat(helpers).hasSize(1);
    assertThat(localityStores).hasSize(1);
    ArgumentCaptor<EndpointWatcher> endpointWatcherCaptor =
        ArgumentCaptor.forClass(EndpointWatcher.class);
    verify(xdsClientFromResolver).watchEndpointData(
        eq("edsServiceName1"), endpointWatcherCaptor.capture());
    EndpointWatcher endpointWatcher = endpointWatcherCaptor.getValue();

    Helper helper1 = helpers.peekLast();
    SubchannelPicker picker = mock(SubchannelPicker.class);
    helper1.updateBalancingState(READY, picker);
    verify(helper).updateBalancingState(READY, picker);

    // Mimic resolver shutdown
    xdsClientRef.returnObject(xdsClientFromResolver);
    verify(xdsClientFromResolver, never()).shutdown();
    lookasideLb.shutdown();
    verify(xdsClientFromResolver).cancelEndpointDataWatch("edsServiceName1", endpointWatcher);
    verify(xdsClientFromResolver).shutdown();
  }

  @Test
  public void firstAndSecondEdsResponseReceived() {
    lookasideLb.handleResolvedAddresses(defaultResolvedAddress);

    verify(edsUpdateCallback, never()).onWorking();
    LoadReportClient loadReportClient = Iterables.getOnlyElement(loadReportClients);
    verify(loadReportClient, never()).startLoadReporting(any(LoadReportCallback.class));

    // first EDS response
    serverResponseWriter.onNext(edsResponse);
    verify(edsUpdateCallback).onWorking();
    ArgumentCaptor<LoadReportCallback> loadReportCallbackCaptor =
        ArgumentCaptor.forClass(LoadReportCallback.class);
    verify(loadReportClient).startLoadReporting(loadReportCallbackCaptor.capture());
    LoadReportCallback loadReportCallback = loadReportCallbackCaptor.getValue();

    // second EDS response
    serverResponseWriter.onNext(edsResponse);
    verify(edsUpdateCallback, times(1)).onWorking();
    verify(loadReportClient, times(1)).startLoadReporting(any(LoadReportCallback.class));

    LocalityStore localityStore = Iterables.getOnlyElement(localityStores);
    verify(localityStore, never()).updateOobMetricsReportInterval(anyLong());
    loadReportCallback.onReportResponse(1234);
    verify(localityStore).updateOobMetricsReportInterval(1234);

    verify(edsUpdateCallback, never()).onError();

    lookasideLb.shutdown();
  }

  @Test
  public void handleDropUpdates() {
    lookasideLb.handleResolvedAddresses(defaultResolvedAddress);

    LocalityStore localityStore = Iterables.getOnlyElement(localityStores);
    verify(localityStore, never()).updateDropPercentage(
        ArgumentMatchers.<ImmutableList<DropOverload>>any());

    serverResponseWriter.onNext(edsResponse);
    verify(localityStore).updateDropPercentage(eq(ImmutableList.<DropOverload>of()));

    ClusterLoadAssignment clusterLoadAssignment = ClusterLoadAssignment.newBuilder()
        .setPolicy(Policy.newBuilder()
            .addDropOverloads(Policy.DropOverload.newBuilder()
                .setCategory("cat_1").setDropPercentage(FractionalPercent.newBuilder()
                    .setDenominator(DenominatorType.HUNDRED)
                    .setNumerator(3)
                    .build())
                .build())

            .addDropOverloads(Policy.DropOverload.newBuilder()
                .setCategory("cat_2").setDropPercentage(FractionalPercent.newBuilder()
                    .setDenominator(DenominatorType.TEN_THOUSAND)
                    .setNumerator(45)
                    .build())
                .build())
            .addDropOverloads(Policy.DropOverload.newBuilder()
                .setCategory("cat_3").setDropPercentage(FractionalPercent.newBuilder()
                    .setDenominator(DenominatorType.MILLION)
                    .setNumerator(6789)
                    .build())
                .build())
            .build())
        .build();
    serverResponseWriter.onNext(
        DiscoveryResponse.newBuilder()
            .addResources(Any.pack(clusterLoadAssignment))
            .setTypeUrl("type.googleapis.com/envoy.api.v2.ClusterLoadAssignment")
            .build());

    verify(edsUpdateCallback, never()).onAllDrop();
    verify(localityStore).updateDropPercentage(ImmutableList.of(
        new DropOverload("cat_1", 300_00),
        new DropOverload("cat_2", 45_00),
        new DropOverload("cat_3", 6789)));


    clusterLoadAssignment = ClusterLoadAssignment.newBuilder()
        .setPolicy(Policy.newBuilder()
            .addDropOverloads(Policy.DropOverload.newBuilder()
                .setCategory("cat_1").setDropPercentage(FractionalPercent.newBuilder()
                    .setDenominator(DenominatorType.HUNDRED)
                    .setNumerator(3)
                    .build())
                .build())
            .addDropOverloads(Policy.DropOverload.newBuilder()
                .setCategory("cat_2").setDropPercentage(FractionalPercent.newBuilder()
                    .setDenominator(DenominatorType.HUNDRED)
                    .setNumerator(101)
                    .build())
                .build())
            .addDropOverloads(Policy.DropOverload.newBuilder()
                .setCategory("cat_3").setDropPercentage(FractionalPercent.newBuilder()
                    .setDenominator(DenominatorType.HUNDRED)
                    .setNumerator(23)
                    .build())
                .build())
            .build())
        .build();
    serverResponseWriter.onNext(
        DiscoveryResponse.newBuilder()
            .addResources(Any.pack(clusterLoadAssignment))
            .setTypeUrl("type.googleapis.com/envoy.api.v2.ClusterLoadAssignment")
            .build());

    verify(edsUpdateCallback).onAllDrop();
    verify(localityStore).updateDropPercentage(ImmutableList.of(
        new DropOverload("cat_1", 300_00),
        new DropOverload("cat_2", 100_00_00)));

    verify(edsUpdateCallback, never()).onError();

    lookasideLb.shutdown();
  }

  @Test
  public void handleLocalityAssignmentUpdates() {
    lookasideLb.handleResolvedAddresses(defaultResolvedAddress);

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
    serverResponseWriter.onNext(
        DiscoveryResponse.newBuilder()
            .addResources(Any.pack(clusterLoadAssignment))
            .setTypeUrl("type.googleapis.com/envoy.api.v2.ClusterLoadAssignment")
            .build());

    Locality locality1 = Locality.fromEnvoyProtoLocality(localityProto1);
    LocalityLbEndpoints localityInfo1 = new LocalityLbEndpoints(
        ImmutableList.of(
            EnvoyProtoData.LbEndpoint.fromEnvoyProtoLbEndpoint(endpoint11),
            EnvoyProtoData.LbEndpoint.fromEnvoyProtoLbEndpoint(endpoint12)),
        1, 0);
    LocalityLbEndpoints localityInfo2 = new LocalityLbEndpoints(
        ImmutableList.of(
            EnvoyProtoData.LbEndpoint.fromEnvoyProtoLbEndpoint(endpoint21),
            EnvoyProtoData.LbEndpoint.fromEnvoyProtoLbEndpoint(endpoint22)),
        2, 0);
    Locality locality2 = Locality.fromEnvoyProtoLocality(localityProto2);

    LocalityStore localityStore = Iterables.getOnlyElement(localityStores);
    InOrder inOrder = inOrder(localityStore);
    inOrder.verify(localityStore).updateDropPercentage(ImmutableList.<DropOverload>of());
    inOrder.verify(localityStore).updateLocalityStore(localityEndpointsMappingCaptor.capture());
    assertThat(localityEndpointsMappingCaptor.getValue()).containsExactly(
        locality1, localityInfo1, locality2, localityInfo2).inOrder();

    verify(edsUpdateCallback, never()).onError();

    lookasideLb.shutdown();
  }

  @Test
  public void verifyRpcErrorPropagation() {
    lookasideLb.handleResolvedAddresses(defaultResolvedAddress);

    verify(edsUpdateCallback, never()).onError();
    serverResponseWriter.onError(new RuntimeException());
    verify(edsUpdateCallback).onError();
  }

  @Test
  public void shutdown() {
    lookasideLb.handleResolvedAddresses(defaultResolvedAddress);

    LocalityStore localityStore = Iterables.getOnlyElement(localityStores);
    LoadReportClient loadReportClient = Iterables.getOnlyElement(loadReportClients);
    verify(localityStore, never()).reset();
    verify(loadReportClient, never()).stopLoadReporting();
    assertThat(channel.isShutdown()).isFalse();

    lookasideLb.shutdown();

    verify(localityStore).reset();
    verify(loadReportClient).stopLoadReporting();
    assertThat(channel.isShutdown()).isTrue();
  }

  /**
   * Tests load reporting is initiated after receiving the first valid EDS response from the traffic
   * director, then its operation is independent of load balancing until xDS load balancer is
   * shutdown.
   */
  @Test
  public void reportLoadAfterReceivingFirstEdsResponseUntilShutdown() {
    lookasideLb.handleResolvedAddresses(defaultResolvedAddress);

    // Simulates a syntactically incorrect EDS response.
    serverResponseWriter.onNext(DiscoveryResponse.getDefaultInstance());
    LoadReportClient loadReportClient = Iterables.getOnlyElement(loadReportClients);
    verify(loadReportClient, never()).startLoadReporting(any(LoadReportCallback.class));
    verify(edsUpdateCallback, never()).onWorking();
    verify(edsUpdateCallback, never()).onError();

    // Simulate a syntactically correct EDS response.
    DiscoveryResponse edsResponse =
        DiscoveryResponse.newBuilder()
            .addResources(Any.pack(ClusterLoadAssignment.getDefaultInstance()))
            .setTypeUrl("type.googleapis.com/envoy.api.v2.ClusterLoadAssignment")
            .build();
    serverResponseWriter.onNext(edsResponse);

    verify(edsUpdateCallback).onWorking();

    ArgumentCaptor<LoadReportCallback> lrsCallbackCaptor = ArgumentCaptor.forClass(null);
    verify(loadReportClient).startLoadReporting(lrsCallbackCaptor.capture());
    lrsCallbackCaptor.getValue().onReportResponse(19543);
    LocalityStore localityStore = Iterables.getOnlyElement(localityStores);
    verify(localityStore).updateOobMetricsReportInterval(19543);

    // Simulate another EDS response from the same remote balancer.
    serverResponseWriter.onNext(edsResponse);
    verifyNoMoreInteractions(edsUpdateCallback, loadReportClient);

    // Simulate an EDS error response.
    serverResponseWriter.onError(Status.ABORTED.asException());
    verify(edsUpdateCallback).onError();

    verifyNoMoreInteractions(edsUpdateCallback, loadReportClient);
    verify(localityStore, times(1)).updateOobMetricsReportInterval(anyLong()); // only once

    lookasideLb.shutdown();
  }
}
