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

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static io.grpc.xds.XdsNameResolver.AUTO_HOST_REWRITE_KEY;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.xds.data.orca.v3.OrcaLoadReport;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import io.grpc.Attributes;
import io.grpc.CallOptions;
import io.grpc.ClientStreamTracer;
import io.grpc.ConnectivityState;
import io.grpc.ConnectivityStateInfo;
import io.grpc.EquivalentAddressGroup;
import io.grpc.InsecureChannelCredentials;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancer.CreateSubchannelArgs;
import io.grpc.LoadBalancer.FixedResultPicker;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancer.PickDetailsConsumer;
import io.grpc.LoadBalancer.PickResult;
import io.grpc.LoadBalancer.PickSubchannelArgs;
import io.grpc.LoadBalancer.ResolvedAddresses;
import io.grpc.LoadBalancer.Subchannel;
import io.grpc.LoadBalancer.SubchannelPicker;
import io.grpc.LoadBalancer.SubchannelStateListener;
import io.grpc.LoadBalancerProvider;
import io.grpc.LoadBalancerRegistry;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.Status.Code;
import io.grpc.SynchronizationContext;
import io.grpc.internal.FakeClock;
import io.grpc.internal.ObjectPool;
import io.grpc.internal.PickFirstLoadBalancerProvider;
import io.grpc.internal.PickSubchannelArgsImpl;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.testing.TestMethodDescriptors;
import io.grpc.util.GracefulSwitchLoadBalancer;
import io.grpc.xds.ClusterImplLoadBalancerProvider.ClusterImplConfig;
import io.grpc.xds.Endpoints.DropOverload;
import io.grpc.xds.EnvoyServerProtoData.DownstreamTlsContext;
import io.grpc.xds.EnvoyServerProtoData.UpstreamTlsContext;
import io.grpc.xds.WeightedTargetLoadBalancerProvider.WeightedPolicySelection;
import io.grpc.xds.WeightedTargetLoadBalancerProvider.WeightedTargetConfig;
import io.grpc.xds.XdsNameResolverProvider.CallCounterProvider;
import io.grpc.xds.client.Bootstrapper.ServerInfo;
import io.grpc.xds.client.LoadReportClient;
import io.grpc.xds.client.LoadStatsManager2;
import io.grpc.xds.client.LoadStatsManager2.ClusterDropStats;
import io.grpc.xds.client.LoadStatsManager2.ClusterLocalityStats;
import io.grpc.xds.client.Locality;
import io.grpc.xds.client.Stats.ClusterStats;
import io.grpc.xds.client.Stats.UpstreamLocalityStats;
import io.grpc.xds.client.XdsClient;
import io.grpc.xds.internal.security.CommonTlsContextTestsUtil;
import io.grpc.xds.internal.security.SslContextProvider;
import io.grpc.xds.internal.security.SslContextProviderSupplier;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/**
 * Tests for {@link ClusterImplLoadBalancer}.
 */
@RunWith(JUnit4.class)
public class ClusterImplLoadBalancerTest {
  @Rule public final MockitoRule mocks = MockitoJUnit.rule();

  private static final double TOLERANCE = 1.0e-10;
  private static final String AUTHORITY = "api.google.com";
  private static final String CLUSTER = "cluster-foo.googleapis.com";
  private static final String EDS_SERVICE_NAME = "service.googleapis.com";
  private static final ServerInfo LRS_SERVER_INFO =
      ServerInfo.create("api.google.com", InsecureChannelCredentials.create());
  private static final Metadata.Key<OrcaLoadReport> ORCA_ENDPOINT_LOAD_METRICS_KEY =
      Metadata.Key.of(
          "endpoint-load-metrics-bin",
          ProtoUtils.metadataMarshaller(OrcaLoadReport.getDefaultInstance()));
  private final SynchronizationContext syncContext = new SynchronizationContext(
      new Thread.UncaughtExceptionHandler() {
        @Override
        public void uncaughtException(Thread t, Throwable e) {
          throw new AssertionError(e);
        }
      });
  private final FakeClock fakeClock = new FakeClock();
  private final Locality locality =
      Locality.create("test-region", "test-zone", "test-subzone");
  private final Object roundRobin = GracefulSwitchLoadBalancer.createLoadBalancingPolicyConfig(
      new FakeLoadBalancerProvider("round_robin"), null);
  private final List<FakeLoadBalancer> downstreamBalancers = new ArrayList<>();
  private final FakeTlsContextManager tlsContextManager = new FakeTlsContextManager();
  private final LoadStatsManager2 loadStatsManager =
      new LoadStatsManager2(fakeClock.getStopwatchSupplier());
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
  private final CallCounterProvider callCounterProvider = new CallCounterProvider() {
    @Override
    public AtomicLong getOrCreate(String cluster, @Nullable String edsServiceName) {
      return new AtomicLong();
    }
  };
  private final FakeLbHelper helper = new FakeLbHelper();
  private PickSubchannelArgs pickSubchannelArgs = new PickSubchannelArgsImpl(
      TestMethodDescriptors.voidMethod(), new Metadata(), CallOptions.DEFAULT,
      new PickDetailsConsumer() {});
  @Mock
  private ThreadSafeRandom mockRandom;
  private int xdsClientRefs;
  private ConnectivityState currentState;
  private SubchannelPicker currentPicker;
  private ClusterImplLoadBalancer loadBalancer;

  @Before
  public void setUp() {
    loadBalancer = new ClusterImplLoadBalancer(helper, mockRandom);
  }

  @After
  public void tearDown() {
    if (loadBalancer != null) {
      loadBalancer.shutdown();
    }
    assertThat(xdsClientRefs).isEqualTo(0);
    assertThat(downstreamBalancers).isEmpty();
  }

  @Test
  public void handleResolvedAddresses_propagateToChildPolicy() {
    FakeLoadBalancerProvider weightedTargetProvider =
        new FakeLoadBalancerProvider(XdsLbPolicies.WEIGHTED_TARGET_POLICY_NAME);
    Object weightedTargetConfig = new Object();
    ClusterImplConfig config = new ClusterImplConfig(CLUSTER, EDS_SERVICE_NAME, LRS_SERVER_INFO,
        null, Collections.<DropOverload>emptyList(),
        GracefulSwitchLoadBalancer.createLoadBalancingPolicyConfig(
            weightedTargetProvider, weightedTargetConfig),
        null, Collections.emptyMap());
    EquivalentAddressGroup endpoint = makeAddress("endpoint-addr", locality);
    deliverAddressesAndConfig(Collections.singletonList(endpoint), config);
    FakeLoadBalancer childBalancer = Iterables.getOnlyElement(downstreamBalancers);
    assertThat(Iterables.getOnlyElement(childBalancer.addresses)).isEqualTo(endpoint);
    assertThat(childBalancer.config).isSameInstanceAs(weightedTargetConfig);
    assertThat(childBalancer.attributes.get(InternalXdsAttributes.XDS_CLIENT_POOL))
        .isSameInstanceAs(xdsClientPool);
  }

  /**
   * If the control plane switches from using the legacy lb_policy field in the xDS Cluster proto
   * to the newer load_balancing_policy then the child policy can switch from weighted_target to
   * xds_wrr_locality (this could happen the opposite way as well). This test assures that this
   * results in the child LB changing if this were to happen. If this is not done correctly the new
   * configuration would be given to the old LB implementation which would cause a channel panic.
   */
  @Test
  public void handleResolvedAddresses_childPolicyChanges() {
    FakeLoadBalancerProvider weightedTargetProvider =
        new FakeLoadBalancerProvider(XdsLbPolicies.WEIGHTED_TARGET_POLICY_NAME);
    Object weightedTargetConfig = new Object();
    ClusterImplConfig configWithWeightedTarget = new ClusterImplConfig(CLUSTER, EDS_SERVICE_NAME,
        LRS_SERVER_INFO,
        null, Collections.<DropOverload>emptyList(),
        GracefulSwitchLoadBalancer.createLoadBalancingPolicyConfig(
            weightedTargetProvider, weightedTargetConfig),
        null, Collections.emptyMap());
    EquivalentAddressGroup endpoint = makeAddress("endpoint-addr", locality);
    deliverAddressesAndConfig(Collections.singletonList(endpoint), configWithWeightedTarget);
    FakeLoadBalancer childBalancer = Iterables.getOnlyElement(downstreamBalancers);
    assertThat(childBalancer.name).isEqualTo(XdsLbPolicies.WEIGHTED_TARGET_POLICY_NAME);
    assertThat(childBalancer.config).isSameInstanceAs(weightedTargetConfig);

    FakeLoadBalancerProvider wrrLocalityProvider =
        new FakeLoadBalancerProvider(XdsLbPolicies.WRR_LOCALITY_POLICY_NAME);
    Object wrrLocalityConfig = new Object();
    ClusterImplConfig configWithWrrLocality = new ClusterImplConfig(CLUSTER, EDS_SERVICE_NAME,
        LRS_SERVER_INFO,
        null, Collections.<DropOverload>emptyList(),
        GracefulSwitchLoadBalancer.createLoadBalancingPolicyConfig(
            wrrLocalityProvider, wrrLocalityConfig),
        null, Collections.emptyMap());
    deliverAddressesAndConfig(Collections.singletonList(endpoint), configWithWrrLocality);
    childBalancer = Iterables.getOnlyElement(downstreamBalancers);
    assertThat(childBalancer.name).isEqualTo(XdsLbPolicies.WRR_LOCALITY_POLICY_NAME);
    assertThat(childBalancer.config).isSameInstanceAs(wrrLocalityConfig);
  }

  @Test
  public void nameResolutionError_beforeChildPolicyInstantiated_returnErrorPickerToUpstream() {
    loadBalancer.handleNameResolutionError(Status.UNIMPLEMENTED.withDescription("not found"));
    assertThat(currentState).isEqualTo(ConnectivityState.TRANSIENT_FAILURE);
    PickResult result = currentPicker.pickSubchannel(pickSubchannelArgs);
    assertThat(result.getStatus().isOk()).isFalse();
    assertThat(result.getStatus().getCode()).isEqualTo(Code.UNIMPLEMENTED);
    assertThat(result.getStatus().getDescription()).isEqualTo("not found");
  }

  @Test
  public void nameResolutionError_afterChildPolicyInstantiated_propagateToDownstream() {
    FakeLoadBalancerProvider weightedTargetProvider =
        new FakeLoadBalancerProvider(XdsLbPolicies.WEIGHTED_TARGET_POLICY_NAME);
    Object weightedTargetConfig = new Object();
    ClusterImplConfig config = new ClusterImplConfig(CLUSTER, EDS_SERVICE_NAME, LRS_SERVER_INFO,
        null, Collections.<DropOverload>emptyList(),
        GracefulSwitchLoadBalancer.createLoadBalancingPolicyConfig(
            weightedTargetProvider, weightedTargetConfig),
        null, Collections.emptyMap());
    EquivalentAddressGroup endpoint = makeAddress("endpoint-addr", locality);
    deliverAddressesAndConfig(Collections.singletonList(endpoint), config);
    FakeLoadBalancer childBalancer = Iterables.getOnlyElement(downstreamBalancers);

    loadBalancer.handleNameResolutionError(
        Status.UNAVAILABLE.withDescription("cannot reach server"));
    assertThat(childBalancer.upstreamError.getCode()).isEqualTo(Code.UNAVAILABLE);
    assertThat(childBalancer.upstreamError.getDescription())
        .isEqualTo("cannot reach server");
  }

  @Test
  public void pick_addsLocalityLabel() {
    LoadBalancerProvider weightedTargetProvider = new WeightedTargetLoadBalancerProvider();
    WeightedTargetConfig weightedTargetConfig =
        buildWeightedTargetConfig(ImmutableMap.of(locality, 10));
    ClusterImplConfig config = new ClusterImplConfig(CLUSTER, EDS_SERVICE_NAME, LRS_SERVER_INFO,
        null, Collections.<DropOverload>emptyList(),
        GracefulSwitchLoadBalancer.createLoadBalancingPolicyConfig(
            weightedTargetProvider, weightedTargetConfig),
        null, Collections.emptyMap());
    EquivalentAddressGroup endpoint = makeAddress("endpoint-addr", locality);
    deliverAddressesAndConfig(Collections.singletonList(endpoint), config);
    FakeLoadBalancer leafBalancer = Iterables.getOnlyElement(downstreamBalancers);
    leafBalancer.createSubChannel();
    FakeSubchannel fakeSubchannel = helper.subchannels.poll();
    fakeSubchannel.updateState(ConnectivityStateInfo.forNonError(ConnectivityState.CONNECTING));
    fakeSubchannel.setConnectedEagIndex(0);
    fakeSubchannel.updateState(ConnectivityStateInfo.forNonError(ConnectivityState.READY));
    assertThat(currentState).isEqualTo(ConnectivityState.READY);

    PickDetailsConsumer detailsConsumer = mock(PickDetailsConsumer.class);
    pickSubchannelArgs = new PickSubchannelArgsImpl(
      TestMethodDescriptors.voidMethod(), new Metadata(), CallOptions.DEFAULT, detailsConsumer);
    PickResult result = currentPicker.pickSubchannel(pickSubchannelArgs);
    assertThat(result.getStatus().isOk()).isTrue();
    // The value will be determined by the parent policy, so can be different than the value used in
    // makeAddress() for the test.
    verify(detailsConsumer).addOptionalLabel("grpc.lb.locality", locality.toString());
  }

  @Test
  public void recordLoadStats() {
    LoadBalancerProvider weightedTargetProvider = new WeightedTargetLoadBalancerProvider();
    WeightedTargetConfig weightedTargetConfig =
        buildWeightedTargetConfig(ImmutableMap.of(locality, 10));
    ClusterImplConfig config = new ClusterImplConfig(CLUSTER, EDS_SERVICE_NAME, LRS_SERVER_INFO,
        null, Collections.<DropOverload>emptyList(),
        GracefulSwitchLoadBalancer.createLoadBalancingPolicyConfig(
            weightedTargetProvider, weightedTargetConfig),
        null, Collections.emptyMap());
    EquivalentAddressGroup endpoint = makeAddress("endpoint-addr", locality);
    deliverAddressesAndConfig(Collections.singletonList(endpoint), config);
    FakeLoadBalancer leafBalancer = Iterables.getOnlyElement(downstreamBalancers);
    Subchannel subchannel = leafBalancer.createSubChannel();
    FakeSubchannel fakeSubchannel = helper.subchannels.poll();
    fakeSubchannel.updateState(ConnectivityStateInfo.forNonError(ConnectivityState.CONNECTING));
    fakeSubchannel.setConnectedEagIndex(0);
    fakeSubchannel.updateState(ConnectivityStateInfo.forNonError(ConnectivityState.READY));
    assertThat(currentState).isEqualTo(ConnectivityState.READY);
    PickResult result = currentPicker.pickSubchannel(pickSubchannelArgs);
    assertThat(result.getStatus().isOk()).isTrue();
    ClientStreamTracer streamTracer1 = result.getStreamTracerFactory().newClientStreamTracer(
        ClientStreamTracer.StreamInfo.newBuilder().build(), new Metadata());  // first RPC call
    ClientStreamTracer streamTracer2 = result.getStreamTracerFactory().newClientStreamTracer(
        ClientStreamTracer.StreamInfo.newBuilder().build(), new Metadata());  // second RPC call
    ClientStreamTracer streamTracer3 = result.getStreamTracerFactory().newClientStreamTracer(
        ClientStreamTracer.StreamInfo.newBuilder().build(), new Metadata());  // third RPC call
    // When the trailer contains an ORCA report, the listener callback will be invoked.
    Metadata trailersWithOrcaLoadReport1 = new Metadata();
    trailersWithOrcaLoadReport1.put(ORCA_ENDPOINT_LOAD_METRICS_KEY,
        OrcaLoadReport.newBuilder().setApplicationUtilization(1.414).setMemUtilization(0.034)
            .setRpsFractional(1.414).putNamedMetrics("named1", 3.14159)
            .putNamedMetrics("named2", -1.618).build());
    streamTracer1.inboundTrailers(trailersWithOrcaLoadReport1);
    streamTracer1.streamClosed(Status.OK);
    Metadata trailersWithOrcaLoadReport2 = new Metadata();
    trailersWithOrcaLoadReport2.put(ORCA_ENDPOINT_LOAD_METRICS_KEY,
        OrcaLoadReport.newBuilder().setApplicationUtilization(0.99).setMemUtilization(0.123)
            .setRpsFractional(0.905).putNamedMetrics("named1", 2.718)
            .putNamedMetrics("named2", 1.414)
            .putNamedMetrics("named3", 0.009).build());
    streamTracer2.inboundTrailers(trailersWithOrcaLoadReport2);
    streamTracer2.streamClosed(Status.UNAVAILABLE);
    ClusterStats clusterStats =
        Iterables.getOnlyElement(loadStatsManager.getClusterStatsReports(CLUSTER));
    UpstreamLocalityStats localityStats =
        Iterables.getOnlyElement(clusterStats.upstreamLocalityStatsList());
    assertThat(localityStats.locality()).isEqualTo(locality);
    assertThat(localityStats.totalIssuedRequests()).isEqualTo(3L);
    assertThat(localityStats.totalSuccessfulRequests()).isEqualTo(1L);
    assertThat(localityStats.totalErrorRequests()).isEqualTo(1L);
    assertThat(localityStats.totalRequestsInProgress()).isEqualTo(1L);
    assertThat(localityStats.loadMetricStatsMap().containsKey("named1")).isTrue();
    assertThat(
        localityStats.loadMetricStatsMap().get("named1").numRequestsFinishedWithMetric()).isEqualTo(
        2L);
    assertThat(localityStats.loadMetricStatsMap().get("named1").totalMetricValue()).isWithin(
        TOLERANCE).of(3.14159 + 2.718);
    assertThat(localityStats.loadMetricStatsMap().containsKey("named2")).isTrue();
    assertThat(
        localityStats.loadMetricStatsMap().get("named2").numRequestsFinishedWithMetric()).isEqualTo(
        2L);
    assertThat(localityStats.loadMetricStatsMap().get("named2").totalMetricValue()).isWithin(
        TOLERANCE).of(-1.618 + 1.414);
    assertThat(localityStats.loadMetricStatsMap().containsKey("named3")).isTrue();
    assertThat(
        localityStats.loadMetricStatsMap().get("named3").numRequestsFinishedWithMetric()).isEqualTo(
        1L);
    assertThat(localityStats.loadMetricStatsMap().get("named3").totalMetricValue()).isWithin(
        TOLERANCE).of(0.009);

    streamTracer3.streamClosed(Status.OK);
    subchannel.shutdown(); // stats recorder released
    clusterStats = Iterables.getOnlyElement(loadStatsManager.getClusterStatsReports(CLUSTER));
    // Locality load is reported for one last time in case of loads occurred since the previous
    // load report.
    localityStats = Iterables.getOnlyElement(clusterStats.upstreamLocalityStatsList());
    assertThat(localityStats.locality()).isEqualTo(locality);
    assertThat(localityStats.totalIssuedRequests()).isEqualTo(0L);
    assertThat(localityStats.totalSuccessfulRequests()).isEqualTo(1L);
    assertThat(localityStats.totalErrorRequests()).isEqualTo(0L);
    assertThat(localityStats.totalRequestsInProgress()).isEqualTo(0L);
    assertThat(localityStats.loadMetricStatsMap().isEmpty()).isTrue();

    clusterStats = Iterables.getOnlyElement(loadStatsManager.getClusterStatsReports(CLUSTER));
    assertThat(clusterStats.upstreamLocalityStatsList()).isEmpty();  // no longer reported
  }

  // Verifies https://github.com/grpc/grpc-java/issues/11434.
  @Test
  public void pickFirstLoadReport_onUpdateAddress() {
    Locality locality1 =
        Locality.create("test-region", "test-zone", "test-subzone");
    Locality locality2 =
        Locality.create("other-region", "other-zone", "other-subzone");

    LoadBalancerProvider pickFirstProvider = LoadBalancerRegistry
        .getDefaultRegistry().getProvider("pick_first");
    Object pickFirstConfig = pickFirstProvider.parseLoadBalancingPolicyConfig(new HashMap<>())
        .getConfig();
    ClusterImplConfig config = new ClusterImplConfig(CLUSTER, EDS_SERVICE_NAME, LRS_SERVER_INFO,
        null, Collections.<DropOverload>emptyList(),
        GracefulSwitchLoadBalancer.createLoadBalancingPolicyConfig(pickFirstProvider,
            pickFirstConfig),
        null, Collections.emptyMap());
    EquivalentAddressGroup endpoint1 = makeAddress("endpoint-addr1", locality1);
    EquivalentAddressGroup endpoint2 = makeAddress("endpoint-addr2", locality2);
    deliverAddressesAndConfig(Arrays.asList(endpoint1, endpoint2), config);

    // Leaf balancer is created by Pick First. Get FakeSubchannel created to update attributes
    // A real subchannel would get these attributes from the connected address's EAG locality.
    FakeSubchannel fakeSubchannel = helper.subchannels.poll();
    fakeSubchannel.updateState(ConnectivityStateInfo.forNonError(ConnectivityState.CONNECTING));
    fakeSubchannel.setConnectedEagIndex(0);
    fakeSubchannel.updateState(ConnectivityStateInfo.forNonError(ConnectivityState.READY));
    assertThat(currentState).isEqualTo(ConnectivityState.READY);
    PickResult result = currentPicker.pickSubchannel(pickSubchannelArgs);
    assertThat(result.getStatus().isOk()).isTrue();

    ClientStreamTracer streamTracer1 = result.getStreamTracerFactory().newClientStreamTracer(
        ClientStreamTracer.StreamInfo.newBuilder().build(), new Metadata());  // first RPC call
    streamTracer1.streamClosed(Status.OK);

    ClusterStats clusterStats = Iterables.getOnlyElement(
        loadStatsManager.getClusterStatsReports(CLUSTER));
    UpstreamLocalityStats localityStats = Iterables.getOnlyElement(
        clusterStats.upstreamLocalityStatsList());
    assertThat(localityStats.locality()).isEqualTo(locality1);
    assertThat(localityStats.totalIssuedRequests()).isEqualTo(1L);
    assertThat(localityStats.totalSuccessfulRequests()).isEqualTo(1L);
    assertThat(localityStats.totalErrorRequests()).isEqualTo(0L);

    fakeSubchannel.updateState(ConnectivityStateInfo.forNonError(ConnectivityState.IDLE));
    loadBalancer.requestConnection();
    fakeSubchannel.updateState(ConnectivityStateInfo.forNonError(ConnectivityState.CONNECTING));

    // Faksubchannel mimics update address and returns different locality
    if (PickFirstLoadBalancerProvider.isEnabledNewPickFirst()) {
      fakeSubchannel.updateState(ConnectivityStateInfo.forTransientFailure(
          Status.UNAVAILABLE.withDescription("Try second address instead")));
      fakeSubchannel = helper.subchannels.poll();
      fakeSubchannel.updateState(ConnectivityStateInfo.forNonError(ConnectivityState.CONNECTING));
      fakeSubchannel.setConnectedEagIndex(0);
      fakeSubchannel.updateState(ConnectivityStateInfo.forNonError(ConnectivityState.READY));
    } else {
      fakeSubchannel.setConnectedEagIndex(1);
      fakeSubchannel.updateState(ConnectivityStateInfo.forNonError(ConnectivityState.READY));
    }
    result = currentPicker.pickSubchannel(pickSubchannelArgs);
    assertThat(result.getStatus().isOk()).isTrue();
    ClientStreamTracer streamTracer2 = result.getStreamTracerFactory().newClientStreamTracer(
        ClientStreamTracer.StreamInfo.newBuilder().build(), new Metadata());  // second RPC call
    streamTracer2.streamClosed(Status.UNAVAILABLE);

    clusterStats = Iterables.getOnlyElement(loadStatsManager.getClusterStatsReports(CLUSTER));
    List<UpstreamLocalityStats> upstreamLocalityStatsList =
        clusterStats.upstreamLocalityStatsList();
    UpstreamLocalityStats localityStats1 = Iterables.find(upstreamLocalityStatsList,
        upstreamLocalityStats -> upstreamLocalityStats.locality().equals(locality1));
    assertThat(localityStats1.totalIssuedRequests()).isEqualTo(0L);
    assertThat(localityStats1.totalSuccessfulRequests()).isEqualTo(0L);
    assertThat(localityStats1.totalErrorRequests()).isEqualTo(0L);
    UpstreamLocalityStats localityStats2 = Iterables.find(upstreamLocalityStatsList,
        upstreamLocalityStats -> upstreamLocalityStats.locality().equals(locality2));
    assertThat(localityStats2.totalIssuedRequests()).isEqualTo(1L);
    assertThat(localityStats2.totalSuccessfulRequests()).isEqualTo(0L);
    assertThat(localityStats2.totalErrorRequests()).isEqualTo(1L);

    loadBalancer.shutdown();
    loadBalancer = null;
    // No more references are held for localityStats1 hence dropped.
    // Locality load is reported for one last time in case of loads occurred since the previous
    // load report.
    clusterStats = Iterables.getOnlyElement(loadStatsManager.getClusterStatsReports(CLUSTER));
    localityStats2 = Iterables.getOnlyElement(clusterStats.upstreamLocalityStatsList());

    assertThat(localityStats2.locality()).isEqualTo(locality2);
    assertThat(localityStats2.totalIssuedRequests()).isEqualTo(0L);
    assertThat(localityStats2.totalSuccessfulRequests()).isEqualTo(0L);
    assertThat(localityStats2.totalErrorRequests()).isEqualTo(0L);
    assertThat(localityStats2.totalRequestsInProgress()).isEqualTo(0L);

    assertThat(loadStatsManager.getClusterStatsReports(CLUSTER)).isEmpty();
  }

  @Test
  public void dropRpcsWithRespectToLbConfigDropCategories() {
    LoadBalancerProvider weightedTargetProvider = new WeightedTargetLoadBalancerProvider();
    WeightedTargetConfig weightedTargetConfig =
        buildWeightedTargetConfig(ImmutableMap.of(locality, 10));
    ClusterImplConfig config = new ClusterImplConfig(CLUSTER, EDS_SERVICE_NAME, LRS_SERVER_INFO,
        null, Collections.singletonList(DropOverload.create("throttle", 500_000)),
        GracefulSwitchLoadBalancer.createLoadBalancingPolicyConfig(
            weightedTargetProvider, weightedTargetConfig),
        null, Collections.emptyMap());
    EquivalentAddressGroup endpoint = makeAddress("endpoint-addr", locality);
    deliverAddressesAndConfig(Collections.singletonList(endpoint), config);
    when(mockRandom.nextInt(anyInt())).thenReturn(499_999, 999_999, 1_000_000);
    assertThat(downstreamBalancers).hasSize(1);  // one leaf balancer
    FakeLoadBalancer leafBalancer = Iterables.getOnlyElement(downstreamBalancers);
    assertThat(leafBalancer.name).isEqualTo("round_robin");
    assertThat(Iterables.getOnlyElement(leafBalancer.addresses).getAddresses())
        .isEqualTo(endpoint.getAddresses());
    leafBalancer.createSubChannel();
    FakeSubchannel fakeSubchannel = helper.subchannels.poll();
    fakeSubchannel.updateState(ConnectivityStateInfo.forNonError(ConnectivityState.CONNECTING));
    fakeSubchannel.setConnectedEagIndex(0);
    fakeSubchannel.updateState(ConnectivityStateInfo.forNonError(ConnectivityState.READY));

    assertThat(currentState).isEqualTo(ConnectivityState.READY);
    PickResult result = currentPicker.pickSubchannel(pickSubchannelArgs);
    assertThat(result.getStatus().isOk()).isFalse();
    assertThat(result.getStatus().getCode()).isEqualTo(Code.UNAVAILABLE);
    assertThat(result.getStatus().getDescription()).isEqualTo("Dropped: throttle");
    ClusterStats clusterStats =
        Iterables.getOnlyElement(loadStatsManager.getClusterStatsReports(CLUSTER));
    assertThat(clusterStats.clusterServiceName()).isEqualTo(EDS_SERVICE_NAME);
    assertThat(Iterables.getOnlyElement(clusterStats.droppedRequestsList()).category())
        .isEqualTo("throttle");
    assertThat(Iterables.getOnlyElement(clusterStats.droppedRequestsList()).droppedCount())
        .isEqualTo(1L);
    assertThat(clusterStats.totalDroppedRequests()).isEqualTo(1L);

    //  Config update updates drop policies.
    config = new ClusterImplConfig(CLUSTER, EDS_SERVICE_NAME, LRS_SERVER_INFO, null,
        Collections.singletonList(DropOverload.create("lb", 1_000_000)),
        GracefulSwitchLoadBalancer.createLoadBalancingPolicyConfig(
            weightedTargetProvider, weightedTargetConfig),
        null, Collections.emptyMap());
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder()
            .setAddresses(Collections.singletonList(endpoint))
            .setAttributes(
                Attributes.newBuilder()
                    .set(InternalXdsAttributes.XDS_CLIENT_POOL, xdsClientPool)
                    .build())
            .setLoadBalancingPolicyConfig(config)
            .build());
    result = currentPicker.pickSubchannel(pickSubchannelArgs);
    assertThat(result.getStatus().isOk()).isFalse();
    assertThat(result.getStatus().getCode()).isEqualTo(Code.UNAVAILABLE);
    assertThat(result.getStatus().getDescription()).isEqualTo("Dropped: lb");
    clusterStats =
        Iterables.getOnlyElement(loadStatsManager.getClusterStatsReports(CLUSTER));
    assertThat(clusterStats.clusterServiceName()).isEqualTo(EDS_SERVICE_NAME);
    assertThat(Iterables.getOnlyElement(clusterStats.droppedRequestsList()).category())
        .isEqualTo("lb");
    assertThat(Iterables.getOnlyElement(clusterStats.droppedRequestsList()).droppedCount())
        .isEqualTo(1L);
    assertThat(clusterStats.totalDroppedRequests()).isEqualTo(1L);

    result = currentPicker.pickSubchannel(pickSubchannelArgs);
    assertThat(result.getStatus().isOk()).isTrue();
  }

  @Test
  public void maxConcurrentRequests_appliedByLbConfig_disableCircuitBreaking() {
    boolean originalEnableCircuitBreaking = ClusterImplLoadBalancer.enableCircuitBreaking;
    ClusterImplLoadBalancer.enableCircuitBreaking = false;
    subtest_maxConcurrentRequests_appliedByLbConfig(false);
    ClusterImplLoadBalancer.enableCircuitBreaking = originalEnableCircuitBreaking;
  }

  @Test
  public void maxConcurrentRequests_appliedByLbConfig_circuitBreakingEnabledByDefault() {
    subtest_maxConcurrentRequests_appliedByLbConfig(true);
  }

  private void subtest_maxConcurrentRequests_appliedByLbConfig(boolean enableCircuitBreaking) {
    long maxConcurrentRequests = 100L;
    LoadBalancerProvider weightedTargetProvider = new WeightedTargetLoadBalancerProvider();
    WeightedTargetConfig weightedTargetConfig =
        buildWeightedTargetConfig(ImmutableMap.of(locality, 10));
    ClusterImplConfig config = new ClusterImplConfig(CLUSTER, EDS_SERVICE_NAME, LRS_SERVER_INFO,
        maxConcurrentRequests, Collections.<DropOverload>emptyList(),
        GracefulSwitchLoadBalancer.createLoadBalancingPolicyConfig(
            weightedTargetProvider, weightedTargetConfig),
        null, Collections.emptyMap());
    EquivalentAddressGroup endpoint = makeAddress("endpoint-addr", locality);
    deliverAddressesAndConfig(Collections.singletonList(endpoint), config);
    assertThat(downstreamBalancers).hasSize(1);  // one leaf balancer
    FakeLoadBalancer leafBalancer = Iterables.getOnlyElement(downstreamBalancers);
    assertThat(leafBalancer.name).isEqualTo("round_robin");
    assertThat(Iterables.getOnlyElement(leafBalancer.addresses).getAddresses())
        .isEqualTo(endpoint.getAddresses());
    leafBalancer.createSubChannel();
    FakeSubchannel fakeSubchannel = helper.subchannels.poll();
    fakeSubchannel.updateState(ConnectivityStateInfo.forNonError(ConnectivityState.CONNECTING));
    fakeSubchannel.setConnectedEagIndex(0);
    fakeSubchannel.updateState(ConnectivityStateInfo.forNonError(ConnectivityState.READY));
    assertThat(currentState).isEqualTo(ConnectivityState.READY);
    assertThat(currentState).isEqualTo(ConnectivityState.READY);
    for (int i = 0; i < maxConcurrentRequests; i++) {
      PickResult result = currentPicker.pickSubchannel(pickSubchannelArgs);
      assertThat(result.getStatus().isOk()).isTrue();
      ClientStreamTracer.Factory streamTracerFactory = result.getStreamTracerFactory();
      streamTracerFactory.newClientStreamTracer(
          ClientStreamTracer.StreamInfo.newBuilder().build(), new Metadata());
    }
    ClusterStats clusterStats =
        Iterables.getOnlyElement(loadStatsManager.getClusterStatsReports(CLUSTER));
    assertThat(clusterStats.clusterServiceName()).isEqualTo(EDS_SERVICE_NAME);
    assertThat(clusterStats.totalDroppedRequests()).isEqualTo(0L);

    PickResult result = currentPicker.pickSubchannel(pickSubchannelArgs);
    clusterStats = Iterables.getOnlyElement(loadStatsManager.getClusterStatsReports(CLUSTER));
    assertThat(clusterStats.clusterServiceName()).isEqualTo(EDS_SERVICE_NAME);
    if (enableCircuitBreaking) {
      assertThat(result.getStatus().isOk()).isFalse();
      assertThat(result.getStatus().getCode()).isEqualTo(Code.UNAVAILABLE);
      assertThat(result.getStatus().getDescription())
          .isEqualTo("Cluster max concurrent requests limit exceeded");
      assertThat(clusterStats.totalDroppedRequests()).isEqualTo(1L);
    } else {
      assertThat(result.getStatus().isOk()).isTrue();
      assertThat(clusterStats.totalDroppedRequests()).isEqualTo(0L);
    }

    // Config update increments circuit breakers max_concurrent_requests threshold.
    maxConcurrentRequests = 101L;
    config = new ClusterImplConfig(CLUSTER, EDS_SERVICE_NAME, LRS_SERVER_INFO,
        maxConcurrentRequests, Collections.<DropOverload>emptyList(),
        GracefulSwitchLoadBalancer.createLoadBalancingPolicyConfig(
            weightedTargetProvider, weightedTargetConfig),
        null, Collections.emptyMap());
    deliverAddressesAndConfig(Collections.singletonList(endpoint), config);

    result = currentPicker.pickSubchannel(pickSubchannelArgs);
    assertThat(result.getStatus().isOk()).isTrue();
    result.getStreamTracerFactory().newClientStreamTracer(
        ClientStreamTracer.StreamInfo.newBuilder().build(), new Metadata());  // 101th request
    clusterStats = Iterables.getOnlyElement(loadStatsManager.getClusterStatsReports(CLUSTER));
    assertThat(clusterStats.clusterServiceName()).isEqualTo(EDS_SERVICE_NAME);
    assertThat(clusterStats.totalDroppedRequests()).isEqualTo(0L);

    result = currentPicker.pickSubchannel(pickSubchannelArgs);  // 102th request
    clusterStats = Iterables.getOnlyElement(loadStatsManager.getClusterStatsReports(CLUSTER));
    assertThat(clusterStats.clusterServiceName()).isEqualTo(EDS_SERVICE_NAME);
    if (enableCircuitBreaking) {
      assertThat(result.getStatus().isOk()).isFalse();
      assertThat(result.getStatus().getCode()).isEqualTo(Code.UNAVAILABLE);
      assertThat(result.getStatus().getDescription())
          .isEqualTo("Cluster max concurrent requests limit exceeded");
      assertThat(clusterStats.totalDroppedRequests()).isEqualTo(1L);
    } else {
      assertThat(result.getStatus().isOk()).isTrue();
      assertThat(clusterStats.totalDroppedRequests()).isEqualTo(0L);
    }
  }

  @Test
  public void maxConcurrentRequests_appliedWithDefaultValue_disableCircuitBreaking() {
    boolean originalEnableCircuitBreaking = ClusterImplLoadBalancer.enableCircuitBreaking;
    ClusterImplLoadBalancer.enableCircuitBreaking = false;
    subtest_maxConcurrentRequests_appliedWithDefaultValue(false);
    ClusterImplLoadBalancer.enableCircuitBreaking = originalEnableCircuitBreaking;
  }

  @Test
  public void maxConcurrentRequests_appliedWithDefaultValue_circuitBreakingEnabledByDefault() {
    subtest_maxConcurrentRequests_appliedWithDefaultValue(true);
  }

  private void subtest_maxConcurrentRequests_appliedWithDefaultValue(
      boolean enableCircuitBreaking) {
    LoadBalancerProvider weightedTargetProvider = new WeightedTargetLoadBalancerProvider();
    WeightedTargetConfig weightedTargetConfig =
        buildWeightedTargetConfig(ImmutableMap.of(locality, 10));
    ClusterImplConfig config = new ClusterImplConfig(CLUSTER, EDS_SERVICE_NAME, LRS_SERVER_INFO,
        null, Collections.<DropOverload>emptyList(),
        GracefulSwitchLoadBalancer.createLoadBalancingPolicyConfig(
            weightedTargetProvider, weightedTargetConfig),
        null, Collections.emptyMap());
    EquivalentAddressGroup endpoint = makeAddress("endpoint-addr", locality);
    deliverAddressesAndConfig(Collections.singletonList(endpoint), config);
    assertThat(downstreamBalancers).hasSize(1);  // one leaf balancer
    FakeLoadBalancer leafBalancer = Iterables.getOnlyElement(downstreamBalancers);
    assertThat(leafBalancer.name).isEqualTo("round_robin");
    assertThat(Iterables.getOnlyElement(leafBalancer.addresses).getAddresses())
        .isEqualTo(endpoint.getAddresses());
    leafBalancer.createSubChannel();
    FakeSubchannel fakeSubchannel = helper.subchannels.poll();
    fakeSubchannel.updateState(ConnectivityStateInfo.forNonError(ConnectivityState.CONNECTING));
    fakeSubchannel.setConnectedEagIndex(0);
    fakeSubchannel.updateState(ConnectivityStateInfo.forNonError(ConnectivityState.READY));
    assertThat(currentState).isEqualTo(ConnectivityState.READY);
    assertThat(currentState).isEqualTo(ConnectivityState.READY);
    for (int i = 0; i < ClusterImplLoadBalancer.DEFAULT_PER_CLUSTER_MAX_CONCURRENT_REQUESTS; i++) {
      PickResult result = currentPicker.pickSubchannel(pickSubchannelArgs);
      assertThat(result.getStatus().isOk()).isTrue();
      ClientStreamTracer.Factory streamTracerFactory = result.getStreamTracerFactory();
      streamTracerFactory.newClientStreamTracer(
          ClientStreamTracer.StreamInfo.newBuilder().build(), new Metadata());
    }
    ClusterStats clusterStats =
        Iterables.getOnlyElement(loadStatsManager.getClusterStatsReports(CLUSTER));
    assertThat(clusterStats.clusterServiceName()).isEqualTo(EDS_SERVICE_NAME);
    assertThat(clusterStats.totalDroppedRequests()).isEqualTo(0L);

    PickResult result = currentPicker.pickSubchannel(pickSubchannelArgs);
    clusterStats = Iterables.getOnlyElement(loadStatsManager.getClusterStatsReports(CLUSTER));
    assertThat(clusterStats.clusterServiceName()).isEqualTo(EDS_SERVICE_NAME);
    if (enableCircuitBreaking) {
      assertThat(result.getStatus().isOk()).isFalse();
      assertThat(result.getStatus().getCode()).isEqualTo(Code.UNAVAILABLE);
      assertThat(result.getStatus().getDescription())
          .isEqualTo("Cluster max concurrent requests limit exceeded");
      assertThat(clusterStats.totalDroppedRequests()).isEqualTo(1L);
    } else {
      assertThat(result.getStatus().isOk()).isTrue();
      assertThat(clusterStats.totalDroppedRequests()).isEqualTo(0L);
    }
  }

  @Test
  public void endpointAddressesAttachedWithClusterName() {
    LoadBalancerProvider weightedTargetProvider = new WeightedTargetLoadBalancerProvider();
    WeightedTargetConfig weightedTargetConfig =
        buildWeightedTargetConfig(ImmutableMap.of(locality, 10));
    ClusterImplConfig config = new ClusterImplConfig(CLUSTER, EDS_SERVICE_NAME, LRS_SERVER_INFO,
        null, Collections.<DropOverload>emptyList(),
        GracefulSwitchLoadBalancer.createLoadBalancingPolicyConfig(
            weightedTargetProvider, weightedTargetConfig),
        null, Collections.emptyMap());
    // One locality with two endpoints.
    EquivalentAddressGroup endpoint1 = makeAddress("endpoint-addr1", locality);
    EquivalentAddressGroup endpoint2 = makeAddress("endpoint-addr2", locality);
    deliverAddressesAndConfig(Arrays.asList(endpoint1, endpoint2), config);
    assertThat(downstreamBalancers).hasSize(1);  // one leaf balancer
    FakeLoadBalancer leafBalancer = Iterables.getOnlyElement(downstreamBalancers);
    assertThat(leafBalancer.name).isEqualTo("round_robin");

    // Simulates leaf load balancer creating subchannels.
    CreateSubchannelArgs args =
        CreateSubchannelArgs.newBuilder()
            .setAddresses(leafBalancer.addresses)
            .build();
    Subchannel subchannel = leafBalancer.helper.createSubchannel(args);
    for (EquivalentAddressGroup eag : subchannel.getAllAddresses()) {
      assertThat(eag.getAttributes().get(InternalXdsAttributes.ATTR_CLUSTER_NAME))
          .isEqualTo(CLUSTER);
    }

    // An address update should also retain the cluster attribute.
    subchannel.updateAddresses(leafBalancer.addresses);
    for (EquivalentAddressGroup eag : subchannel.getAllAddresses()) {
      assertThat(eag.getAttributes().get(InternalXdsAttributes.ATTR_CLUSTER_NAME))
          .isEqualTo(CLUSTER);
    }
  }

  @Test
  public void
        endpointsWithAuthorityHostname_autoHostRewriteEnabled_pickResultHasAuthorityHostname() {
    System.setProperty("GRPC_EXPERIMENTAL_XDS_AUTHORITY_REWRITE", "true");
    try {
      LoadBalancerProvider weightedTargetProvider = new WeightedTargetLoadBalancerProvider();
      WeightedTargetConfig weightedTargetConfig =
          buildWeightedTargetConfig(ImmutableMap.of(locality, 10));
      ClusterImplConfig config = new ClusterImplConfig(CLUSTER, EDS_SERVICE_NAME, LRS_SERVER_INFO,
          null, Collections.<DropOverload>emptyList(),
          GracefulSwitchLoadBalancer.createLoadBalancingPolicyConfig(
              weightedTargetProvider, weightedTargetConfig),
          null, Collections.emptyMap());
      EquivalentAddressGroup endpoint1 = makeAddress("endpoint-addr1", locality,
          "authority-host-name");
      deliverAddressesAndConfig(Arrays.asList(endpoint1), config);
      assertThat(downstreamBalancers).hasSize(1);  // one leaf balancer
      FakeLoadBalancer leafBalancer = Iterables.getOnlyElement(downstreamBalancers);
      assertThat(leafBalancer.name).isEqualTo("round_robin");

      // Simulates leaf load balancer creating subchannels.
      CreateSubchannelArgs args =
          CreateSubchannelArgs.newBuilder()
              .setAddresses(leafBalancer.addresses)
              .build();
      Subchannel subchannel = leafBalancer.helper.createSubchannel(args);
      subchannel.start(infoObject -> {
        if (infoObject.getState() == ConnectivityState.READY) {
          helper.updateBalancingState(
              ConnectivityState.READY,
              new FixedResultPicker(PickResult.withSubchannel(subchannel)));
        }
      });
      assertThat(subchannel.getAttributes().get(InternalXdsAttributes.ATTR_ADDRESS_NAME)).isEqualTo(
          "authority-host-name");
      for (EquivalentAddressGroup eag : subchannel.getAllAddresses()) {
        assertThat(eag.getAttributes().get(InternalXdsAttributes.ATTR_ADDRESS_NAME))
            .isEqualTo("authority-host-name");
      }

      leafBalancer.deliverSubchannelState(subchannel, ConnectivityState.READY);
      assertThat(currentState).isEqualTo(ConnectivityState.READY);
      PickDetailsConsumer detailsConsumer = mock(PickDetailsConsumer.class);
      pickSubchannelArgs = new PickSubchannelArgsImpl(
          TestMethodDescriptors.voidMethod(), new Metadata(),
          CallOptions.DEFAULT.withOption(AUTO_HOST_REWRITE_KEY, true), detailsConsumer);
      PickResult result = currentPicker.pickSubchannel(pickSubchannelArgs);
      assertThat(result.getAuthorityOverride()).isEqualTo("authority-host-name");
    } finally {
      System.clearProperty("GRPC_EXPERIMENTAL_XDS_AUTHORITY_REWRITE");
    }
  }

  @Test
  public void
        endpointWithAuthorityHostname_autoHostRewriteNotEnabled_pickResultNoAuthorityHostname() {
    LoadBalancerProvider weightedTargetProvider = new WeightedTargetLoadBalancerProvider();
    WeightedTargetConfig weightedTargetConfig =
        buildWeightedTargetConfig(ImmutableMap.of(locality, 10));
    ClusterImplConfig config = new ClusterImplConfig(CLUSTER, EDS_SERVICE_NAME, LRS_SERVER_INFO,
        null, Collections.<DropOverload>emptyList(),
        GracefulSwitchLoadBalancer.createLoadBalancingPolicyConfig(
            weightedTargetProvider, weightedTargetConfig),
        null, Collections.emptyMap());
    EquivalentAddressGroup endpoint1 = makeAddress("endpoint-addr1", locality,
        "authority-host-name");
    deliverAddressesAndConfig(Arrays.asList(endpoint1), config);
    assertThat(downstreamBalancers).hasSize(1);  // one leaf balancer
    FakeLoadBalancer leafBalancer = Iterables.getOnlyElement(downstreamBalancers);
    assertThat(leafBalancer.name).isEqualTo("round_robin");

    // Simulates leaf load balancer creating subchannels.
    CreateSubchannelArgs args =
        CreateSubchannelArgs.newBuilder()
            .setAddresses(leafBalancer.addresses)
            .build();
    Subchannel subchannel = leafBalancer.helper.createSubchannel(args);
    subchannel.start(infoObject -> {
      if (infoObject.getState() == ConnectivityState.READY) {
        helper.updateBalancingState(
            ConnectivityState.READY,
            new FixedResultPicker(PickResult.withSubchannel(subchannel)));
      }
    });
    // Sub Channel wrapper args won't have the address name although addresses will.
    assertThat(subchannel.getAttributes().get(InternalXdsAttributes.ATTR_ADDRESS_NAME)).isNull();
    for (EquivalentAddressGroup eag : subchannel.getAllAddresses()) {
      assertThat(eag.getAttributes().get(InternalXdsAttributes.ATTR_ADDRESS_NAME))
          .isEqualTo("authority-host-name");
    }

    leafBalancer.deliverSubchannelState(subchannel, ConnectivityState.READY);
    assertThat(currentState).isEqualTo(ConnectivityState.READY);
    PickDetailsConsumer detailsConsumer = mock(PickDetailsConsumer.class);
    pickSubchannelArgs = new PickSubchannelArgsImpl(
        TestMethodDescriptors.voidMethod(), new Metadata(), CallOptions.DEFAULT, detailsConsumer);
    PickResult result = currentPicker.pickSubchannel(pickSubchannelArgs);
    assertThat(result.getAuthorityOverride()).isNull();
  }

  @Test
  public void endpointAddressesAttachedWithTlsConfig_securityEnabledByDefault() {
    UpstreamTlsContext upstreamTlsContext =
        CommonTlsContextTestsUtil.buildUpstreamTlsContext("google_cloud_private_spiffe", true);
    LoadBalancerProvider weightedTargetProvider = new WeightedTargetLoadBalancerProvider();
    WeightedTargetConfig weightedTargetConfig =
        buildWeightedTargetConfig(ImmutableMap.of(locality, 10));
    ClusterImplConfig config = new ClusterImplConfig(CLUSTER, EDS_SERVICE_NAME, LRS_SERVER_INFO,
        null, Collections.<DropOverload>emptyList(),
        GracefulSwitchLoadBalancer.createLoadBalancingPolicyConfig(
            weightedTargetProvider, weightedTargetConfig),
        upstreamTlsContext, Collections.emptyMap());
    // One locality with two endpoints.
    EquivalentAddressGroup endpoint1 = makeAddress("endpoint-addr1", locality);
    EquivalentAddressGroup endpoint2 = makeAddress("endpoint-addr2", locality);
    deliverAddressesAndConfig(Arrays.asList(endpoint1, endpoint2), config);
    assertThat(downstreamBalancers).hasSize(1);  // one leaf balancer
    FakeLoadBalancer leafBalancer = Iterables.getOnlyElement(downstreamBalancers);
    assertThat(leafBalancer.name).isEqualTo("round_robin");
    // Simulates leaf load balancer creating subchannels.
    CreateSubchannelArgs args =
        CreateSubchannelArgs.newBuilder()
            .setAddresses(leafBalancer.addresses)
            .build();
    Subchannel subchannel = leafBalancer.helper.createSubchannel(args);
    for (EquivalentAddressGroup eag : subchannel.getAllAddresses()) {
      SslContextProviderSupplier supplier =
          eag.getAttributes().get(InternalXdsAttributes.ATTR_SSL_CONTEXT_PROVIDER_SUPPLIER);
      assertThat(supplier.getTlsContext()).isEqualTo(upstreamTlsContext);
    }

    // Removes UpstreamTlsContext from the config.
    config = new ClusterImplConfig(CLUSTER, EDS_SERVICE_NAME, LRS_SERVER_INFO,
        null, Collections.<DropOverload>emptyList(),
        GracefulSwitchLoadBalancer.createLoadBalancingPolicyConfig(
            weightedTargetProvider, weightedTargetConfig),
        null, Collections.emptyMap());
    deliverAddressesAndConfig(Arrays.asList(endpoint1, endpoint2), config);
    assertThat(Iterables.getOnlyElement(downstreamBalancers)).isSameInstanceAs(leafBalancer);
    subchannel = leafBalancer.helper.createSubchannel(args);  // creates new connections
    for (EquivalentAddressGroup eag : subchannel.getAllAddresses()) {
      assertThat(eag.getAttributes().get(InternalXdsAttributes.ATTR_SSL_CONTEXT_PROVIDER_SUPPLIER))
          .isNull();
    }

    // Config with a new UpstreamTlsContext.
    upstreamTlsContext =
        CommonTlsContextTestsUtil.buildUpstreamTlsContext("google_cloud_private_spiffe1", true);
    config = new ClusterImplConfig(CLUSTER, EDS_SERVICE_NAME, LRS_SERVER_INFO,
        null, Collections.<DropOverload>emptyList(),
        GracefulSwitchLoadBalancer.createLoadBalancingPolicyConfig(
            weightedTargetProvider, weightedTargetConfig),
        upstreamTlsContext, Collections.emptyMap());
    deliverAddressesAndConfig(Arrays.asList(endpoint1, endpoint2), config);
    assertThat(Iterables.getOnlyElement(downstreamBalancers)).isSameInstanceAs(leafBalancer);
    subchannel = leafBalancer.helper.createSubchannel(args);  // creates new connections
    for (EquivalentAddressGroup eag : subchannel.getAllAddresses()) {
      SslContextProviderSupplier supplier =
          eag.getAttributes().get(InternalXdsAttributes.ATTR_SSL_CONTEXT_PROVIDER_SUPPLIER);
      assertThat(supplier.isShutdown()).isFalse();
      assertThat(supplier.getTlsContext()).isEqualTo(upstreamTlsContext);
    }
    loadBalancer.shutdown();
    for (EquivalentAddressGroup eag : subchannel.getAllAddresses()) {
      SslContextProviderSupplier supplier =
              eag.getAttributes().get(InternalXdsAttributes.ATTR_SSL_CONTEXT_PROVIDER_SUPPLIER);
      assertThat(supplier.isShutdown()).isTrue();
    }
    loadBalancer = null;
  }

  private void deliverAddressesAndConfig(List<EquivalentAddressGroup> addresses,
      ClusterImplConfig config) {
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder()
            .setAddresses(addresses)
            .setAttributes(
                Attributes.newBuilder()
                    .set(InternalXdsAttributes.XDS_CLIENT_POOL, xdsClientPool)
                    .set(InternalXdsAttributes.CALL_COUNTER_PROVIDER, callCounterProvider)
                    .build())
            .setLoadBalancingPolicyConfig(config)
            .build());
  }

  private WeightedTargetConfig buildWeightedTargetConfig(Map<Locality, Integer> localityWeights) {
    Map<String, WeightedPolicySelection> targets = new HashMap<>();
    for (Locality locality : localityWeights.keySet()) {
      int weight = localityWeights.get(locality);
      WeightedPolicySelection weightedLocalityLbPolicy =
          new WeightedPolicySelection(weight, roundRobin);
      targets.put(locality.toString(), weightedLocalityLbPolicy);
    }
    return new WeightedTargetConfig(Collections.unmodifiableMap(targets));
  }

  /**
   * Create a locality-labeled address.
   */
  private static EquivalentAddressGroup makeAddress(final String name, Locality locality) {
    return makeAddress(name, locality, null);
  }

  private static EquivalentAddressGroup makeAddress(final String name, Locality locality,
      String authorityHostname) {
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

    Attributes.Builder attributes = Attributes.newBuilder()
        .set(InternalXdsAttributes.ATTR_LOCALITY, locality)
        // Unique but arbitrary string
        .set(InternalXdsAttributes.ATTR_LOCALITY_NAME, locality.toString());
    if (authorityHostname != null) {
      attributes.set(InternalXdsAttributes.ATTR_ADDRESS_NAME, authorityHostname);
    }
    EquivalentAddressGroup eag = new EquivalentAddressGroup(new FakeSocketAddress(name),
        attributes.build());
    return AddressFilter.setPathFilter(eag, Collections.singletonList(locality.toString()));
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
    private Attributes attributes;
    private Status upstreamError;

    FakeLoadBalancer(String name, Helper helper) {
      this.name = name;
      this.helper = helper;
    }

    @Override
    public Status acceptResolvedAddresses(ResolvedAddresses resolvedAddresses) {
      addresses = resolvedAddresses.getAddresses();
      config = resolvedAddresses.getLoadBalancingPolicyConfig();
      attributes = resolvedAddresses.getAttributes();
      return Status.OK;
    }

    @Override
    public void handleNameResolutionError(Status error) {
      upstreamError = error;
    }

    @Override
    public void shutdown() {
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

    Subchannel createSubChannel() {
      Subchannel subchannel = helper.createSubchannel(
          CreateSubchannelArgs.newBuilder().setAddresses(addresses).build());
      subchannel.start(infoObject -> {
        if (infoObject.getState() == ConnectivityState.READY) {
          helper.updateBalancingState(
              ConnectivityState.READY,
              new FixedResultPicker(PickResult.withSubchannel(subchannel)));
        }
      });
      subchannel.requestConnection();
      return subchannel;
    }
  }

  private final class FakeLbHelper extends LoadBalancer.Helper {

    private final Queue<FakeSubchannel> subchannels = new LinkedList<>();

    @Override
    public SynchronizationContext getSynchronizationContext() {
      return syncContext;
    }

    @Override
    public void updateBalancingState(
        @Nonnull ConnectivityState newState, @Nonnull SubchannelPicker newPicker) {
      currentState = newState;
      currentPicker = newPicker;
    }

    @Override
    public Subchannel createSubchannel(CreateSubchannelArgs args) {
      FakeSubchannel subchannel = new FakeSubchannel(args.getAddresses(), args.getAttributes());
      subchannels.add(subchannel);
      return subchannel;
    }

    @Override
    public ManagedChannel createOobChannel(EquivalentAddressGroup eag, String authority) {
      throw new UnsupportedOperationException("should not be called");
    }

    @Override
    public String getAuthority() {
      return AUTHORITY;
    }

    @Override
    public void refreshNameResolution() {}
  }

  private static final class FakeSubchannel extends Subchannel {
    private final List<EquivalentAddressGroup> eags;
    private final Attributes attrs;
    private SubchannelStateListener listener;
    private Attributes connectedAttributes;
    private ConnectivityStateInfo state = ConnectivityStateInfo.forNonError(ConnectivityState.IDLE);
    private boolean connectionRequested;

    private FakeSubchannel(List<EquivalentAddressGroup> eags, Attributes attrs) {
      this.eags = eags;
      this.attrs = attrs;
    }

    @Override
    public void start(SubchannelStateListener listener) {
      this.listener = checkNotNull(listener, "listener");
    }

    @Override
    public void shutdown() {
    }

    @Override
    public void requestConnection() {
      if (state.getState() == ConnectivityState.IDLE) {
        this.connectionRequested = true;
      }
    }

    @Override
    public List<EquivalentAddressGroup> getAllAddresses() {
      return eags;
    }

    @Override
    public Attributes getAttributes() {
      return attrs;
    }

    @Override
    public void updateAddresses(List<EquivalentAddressGroup> addrs) {
    }

    @Override
    public Attributes getConnectedAddressAttributes() {
      return connectedAttributes;
    }

    public void updateState(ConnectivityStateInfo newState) {
      switch (newState.getState()) {
        case IDLE:
          assertThat(state.getState()).isEqualTo(ConnectivityState.READY);
          break;
        case CONNECTING:
          assertThat(state.getState())
              .isIn(Arrays.asList(ConnectivityState.IDLE, ConnectivityState.TRANSIENT_FAILURE));
          if (state.getState() == ConnectivityState.IDLE) {
            assertWithMessage("Connection requested").that(this.connectionRequested).isTrue();
            this.connectionRequested = false;
          }
          break;
        case READY:
        case TRANSIENT_FAILURE:
          assertThat(state.getState()).isEqualTo(ConnectivityState.CONNECTING);
          break;
        default:
          break;
      }
      this.state = newState;
      listener.onSubchannelState(newState);
    }

    public void setConnectedEagIndex(int eagIndex) {
      this.connectedAttributes = eags.get(eagIndex).getAttributes();
    }
  }

  private final class FakeXdsClient extends XdsClient {

    @Override
    public ClusterDropStats addClusterDropStats(
        ServerInfo lrsServerInfo, String clusterName, @Nullable String edsServiceName) {
      return loadStatsManager.getClusterDropStats(clusterName, edsServiceName);
    }

    @Override
    public ClusterLocalityStats addClusterLocalityStats(
        ServerInfo lrsServerInfo, String clusterName, @Nullable String edsServiceName,
        Locality locality) {
      return loadStatsManager.getClusterLocalityStats(clusterName, edsServiceName, locality);
    }

    @Override
    public TlsContextManager getSecurityConfig() {
      return tlsContextManager;
    }

    @Override
    public Map<ServerInfo, LoadReportClient> getServerLrsClientMap() {
      return null;
    }
  }

  private static final class FakeTlsContextManager implements TlsContextManager {
    @Override
    public SslContextProvider findOrCreateClientSslContextProvider(
        UpstreamTlsContext upstreamTlsContext) {
      SslContextProvider sslContextProvider = mock(SslContextProvider.class);
      when(sslContextProvider.getUpstreamTlsContext()).thenReturn(upstreamTlsContext);
      return sslContextProvider;
    }

    @Override
    public SslContextProvider releaseClientSslContextProvider(
        SslContextProvider sslContextProvider) {
      // no-op
      return null;
    }

    @Override
    public SslContextProvider findOrCreateServerSslContextProvider(
        DownstreamTlsContext downstreamTlsContext) {
      throw new UnsupportedOperationException("should not be called");
    }

    @Override
    public SslContextProvider releaseServerSslContextProvider(
        SslContextProvider sslContextProvider) {
      throw new UnsupportedOperationException("should not be called");
    }
  }
}
