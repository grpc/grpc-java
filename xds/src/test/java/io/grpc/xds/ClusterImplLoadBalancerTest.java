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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import io.grpc.Attributes;
import io.grpc.ClientStreamTracer;
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
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.Status.Code;
import io.grpc.SynchronizationContext;
import io.grpc.internal.ObjectPool;
import io.grpc.internal.ServiceConfigUtil.PolicySelection;
import io.grpc.xds.ClusterImplLoadBalancerProvider.ClusterImplConfig;
import io.grpc.xds.EnvoyProtoData.ClusterStats;
import io.grpc.xds.EnvoyProtoData.DropOverload;
import io.grpc.xds.EnvoyProtoData.Locality;
import io.grpc.xds.EnvoyServerProtoData.DownstreamTlsContext;
import io.grpc.xds.EnvoyServerProtoData.UpstreamTlsContext;
import io.grpc.xds.LoadStatsManager.LoadStatsStore;
import io.grpc.xds.LrsLoadBalancerProvider.LrsConfig;
import io.grpc.xds.WeightedTargetLoadBalancerProvider.WeightedPolicySelection;
import io.grpc.xds.WeightedTargetLoadBalancerProvider.WeightedTargetConfig;
import io.grpc.xds.XdsNameResolverProvider.CallCounterProvider;
import io.grpc.xds.internal.sds.CommonTlsContextTestsUtil;
import io.grpc.xds.internal.sds.SslContextProvider;
import io.grpc.xds.internal.sds.SslContextProviderSupplier;
import io.grpc.xds.internal.sds.TlsContextManager;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
 * Tests for {@link ClusterImplLoadBalancer}.
 */
@RunWith(JUnit4.class)
public class ClusterImplLoadBalancerTest {
  private static final String AUTHORITY = "api.google.com";
  private static final String CLUSTER = "cluster-foo.googleapis.com";
  private static final String EDS_SERVICE_NAME = "service.googleapis.com";
  private static final String LRS_SERVER_NAME = "";
  private final SynchronizationContext syncContext = new SynchronizationContext(
      new Thread.UncaughtExceptionHandler() {
        @Override
        public void uncaughtException(Thread t, Throwable e) {
          throw new AssertionError(e);
        }
      });
  private final Locality locality =
      new Locality("test-region", "test-zone", "test-subzone");
  private final PolicySelection roundRobin =
      new PolicySelection(new FakeLoadBalancerProvider("round_robin"), null);
  private final List<FakeLoadBalancer> downstreamBalancers = new ArrayList<>();
  private final FakeTlsContextManager tlsContextManager = new FakeTlsContextManager();
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
  private final Helper helper = new FakeLbHelper();
  @Mock
  private ThreadSafeRandom mockRandom;
  private int xdsClientRefs;
  private ConnectivityState currentState;
  private SubchannelPicker currentPicker;
  private ClusterImplLoadBalancer loadBalancer;

  @Before
  public void setUp() {
    MockitoAnnotations.initMocks(this);
    loadBalancer = new ClusterImplLoadBalancer(helper, mockRandom, tlsContextManager);
  }

  @After
  public void tearDown() {
    loadBalancer.shutdown();
    assertThat(xdsClient.clusterStats).isNull();
    assertThat(xdsClientRefs).isEqualTo(0);
    assertThat(downstreamBalancers).isEmpty();
  }

  @Test
  public void handleResolvedAddresses_propagateToChildPolicy() {
    FakeLoadBalancerProvider weightedTargetProvider =
        new FakeLoadBalancerProvider(XdsLbPolicies.WEIGHTED_TARGET_POLICY_NAME);
    Object weightedTargetConfig = new Object();
    ClusterImplConfig config = new ClusterImplConfig(CLUSTER, EDS_SERVICE_NAME, LRS_SERVER_NAME,
        null, Collections.<DropOverload>emptyList(),
        new PolicySelection(weightedTargetProvider, weightedTargetConfig), null);
    EquivalentAddressGroup endpoint = makeAddress("endpoint-addr", locality);
    deliverAddressesAndConfig(Collections.singletonList(endpoint), config);
    FakeLoadBalancer childBalancer = Iterables.getOnlyElement(downstreamBalancers);
    assertThat(Iterables.getOnlyElement(childBalancer.addresses)).isEqualTo(endpoint);
    assertThat(childBalancer.config).isSameInstanceAs(weightedTargetConfig);
    assertThat(childBalancer.attributes.get(InternalXdsAttributes.XDS_CLIENT_POOL))
        .isSameInstanceAs(xdsClientPool);
    assertThat(childBalancer.attributes.get(
        InternalXdsAttributes.ATTR_CLUSTER_SERVICE_LOAD_STATS_STORE)).isNotNull();
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
    FakeLoadBalancerProvider weightedTargetProvider =
        new FakeLoadBalancerProvider(XdsLbPolicies.WEIGHTED_TARGET_POLICY_NAME);
    Object weightedTargetConfig = new Object();
    ClusterImplConfig config = new ClusterImplConfig(CLUSTER, EDS_SERVICE_NAME, LRS_SERVER_NAME,
        null, Collections.<DropOverload>emptyList(),
        new PolicySelection(weightedTargetProvider, weightedTargetConfig), null);
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
  public void dropRpcsWithRespectToLbConfigDropCategories() {
    LoadBalancerProvider weightedTargetProvider = new WeightedTargetLoadBalancerProvider();
    WeightedTargetConfig weightedTargetConfig =
        buildWeightedTargetConfig(ImmutableMap.of(locality, 10));
    ClusterImplConfig config = new ClusterImplConfig(CLUSTER, EDS_SERVICE_NAME, LRS_SERVER_NAME,
        null, Collections.singletonList(new DropOverload("throttle", 500_000)),
        new PolicySelection(weightedTargetProvider, weightedTargetConfig), null);
    EquivalentAddressGroup endpoint = makeAddress("endpoint-addr", locality);
    deliverAddressesAndConfig(Collections.singletonList(endpoint), config);
    when(mockRandom.nextInt(anyInt())).thenReturn(499_999, 999_999, 1_000_000);
    assertThat(downstreamBalancers).hasSize(1);  // one leaf balancer
    FakeLoadBalancer leafBalancer = Iterables.getOnlyElement(downstreamBalancers);
    assertThat(leafBalancer.name).isEqualTo("round_robin");
    assertThat(Iterables.getOnlyElement(leafBalancer.addresses).getAddresses())
        .isEqualTo(endpoint.getAddresses());
    Subchannel subchannel = leafBalancer.helper.createSubchannel(
        CreateSubchannelArgs.newBuilder().setAddresses(leafBalancer.addresses).build());
    leafBalancer.deliverSubchannelState(subchannel, ConnectivityState.READY);
    assertThat(currentState).isEqualTo(ConnectivityState.READY);
    PickResult result = currentPicker.pickSubchannel(mock(PickSubchannelArgs.class));
    assertThat(result.getStatus().isOk()).isFalse();
    assertThat(result.getStatus().getCode()).isEqualTo(Code.UNAVAILABLE);
    assertThat(result.getStatus().getDescription()).isEqualTo("Dropped: throttle");
    assertThat(xdsClient.clusterStats.categorizedDrops.get("throttle"))
        .isEqualTo(1);

    //  Config update updates drop policies.
    config = new ClusterImplConfig(CLUSTER, EDS_SERVICE_NAME, LRS_SERVER_NAME, null,
        Collections.singletonList(new DropOverload("lb", 1_000_000)),
        new PolicySelection(weightedTargetProvider, weightedTargetConfig), null);
    loadBalancer.handleResolvedAddresses(
        ResolvedAddresses.newBuilder()
            .setAddresses(Collections.singletonList(endpoint))
            .setAttributes(
                Attributes.newBuilder()
                    .set(InternalXdsAttributes.XDS_CLIENT_POOL, xdsClientPool)
                    .build())
            .setLoadBalancingPolicyConfig(config)
            .build());
    result = currentPicker.pickSubchannel(mock(PickSubchannelArgs.class));
    assertThat(result.getStatus().isOk()).isFalse();
    assertThat(result.getStatus().getCode()).isEqualTo(Code.UNAVAILABLE);
    assertThat(result.getStatus().getDescription()).isEqualTo("Dropped: lb");
    assertThat(xdsClient.clusterStats.categorizedDrops.get("lb"))
        .isEqualTo(1);

    result = currentPicker.pickSubchannel(mock(PickSubchannelArgs.class));
    assertThat(result.getStatus().isOk()).isTrue();
    assertThat(result.getSubchannel()).isSameInstanceAs(subchannel);
  }

  @Test
  public void maxConcurrentRequests_appliedByLbConfig_enableCircuitBreaking() {
    boolean originalEnableCircuitBreaking = ClusterImplLoadBalancer.enableCircuitBreaking;
    ClusterImplLoadBalancer.enableCircuitBreaking = true;
    subtest_maxConcurrentRequests_appliedByLbConfig(true);
    ClusterImplLoadBalancer.enableCircuitBreaking = originalEnableCircuitBreaking;
  }

  @Test
  public void maxConcurrentRequests_appliedByLbConfig_circuitBreakingDisabledByDefault() {
    subtest_maxConcurrentRequests_appliedByLbConfig(false);
  }

  private void subtest_maxConcurrentRequests_appliedByLbConfig(boolean enableCircuitBreaking) {
    long maxConcurrentRequests = 100L;
    LoadBalancerProvider weightedTargetProvider = new WeightedTargetLoadBalancerProvider();
    WeightedTargetConfig weightedTargetConfig =
        buildWeightedTargetConfig(ImmutableMap.of(locality, 10));
    ClusterImplConfig config = new ClusterImplConfig(CLUSTER, EDS_SERVICE_NAME, LRS_SERVER_NAME,
        maxConcurrentRequests, Collections.<DropOverload>emptyList(),
        new PolicySelection(weightedTargetProvider, weightedTargetConfig), null);
    EquivalentAddressGroup endpoint = makeAddress("endpoint-addr", locality);
    deliverAddressesAndConfig(Collections.singletonList(endpoint), config);
    assertThat(downstreamBalancers).hasSize(1);  // one leaf balancer
    FakeLoadBalancer leafBalancer = Iterables.getOnlyElement(downstreamBalancers);
    assertThat(leafBalancer.name).isEqualTo("round_robin");
    assertThat(Iterables.getOnlyElement(leafBalancer.addresses).getAddresses())
        .isEqualTo(endpoint.getAddresses());
    Subchannel subchannel = leafBalancer.helper.createSubchannel(
        CreateSubchannelArgs.newBuilder().setAddresses(leafBalancer.addresses).build());
    leafBalancer.deliverSubchannelState(subchannel, ConnectivityState.READY);
    assertThat(currentState).isEqualTo(ConnectivityState.READY);
    for (int i = 0; i < maxConcurrentRequests; i++) {
      PickResult result = currentPicker.pickSubchannel(mock(PickSubchannelArgs.class));
      assertThat(result.getStatus().isOk()).isTrue();
      assertThat(result.getSubchannel()).isSameInstanceAs(subchannel);
      assertThat(result.getStreamTracerFactory()).isNotNull();
      ClientStreamTracer.Factory streamTracerFactory = result.getStreamTracerFactory();
      streamTracerFactory.newClientStreamTracer(ClientStreamTracer.StreamInfo.newBuilder().build(),
          new Metadata());
    }
    assertThat(xdsClient.clusterStats.totalDrops).isEqualTo(0L);

    PickResult result = currentPicker.pickSubchannel(mock(PickSubchannelArgs.class));
    if (enableCircuitBreaking) {
      assertThat(result.getStatus().isOk()).isFalse();
      assertThat(result.getStatus().getCode()).isEqualTo(Code.UNAVAILABLE);
      assertThat(result.getStatus().getDescription())
          .isEqualTo("Cluster max concurrent requests limit exceeded");
      assertThat(xdsClient.clusterStats.totalDrops).isEqualTo(1L);
    } else {
      assertThat(result.getStatus().isOk()).isTrue();
      assertThat(result.getSubchannel()).isSameInstanceAs(subchannel);
      assertThat(xdsClient.clusterStats.totalDrops).isEqualTo(0L);
    }

    // Config update increments circuit breakers max_concurrent_requests threshold.
    maxConcurrentRequests = 101L;
    config = new ClusterImplConfig(CLUSTER, EDS_SERVICE_NAME, LRS_SERVER_NAME,
        maxConcurrentRequests, Collections.<DropOverload>emptyList(),
        new PolicySelection(weightedTargetProvider, weightedTargetConfig), null);
    deliverAddressesAndConfig(Collections.singletonList(endpoint), config);

    result = currentPicker.pickSubchannel(mock(PickSubchannelArgs.class));
    assertThat(result.getStatus().isOk()).isTrue();
    assertThat(result.getSubchannel()).isSameInstanceAs(subchannel);
    if (enableCircuitBreaking) {
      assertThat(xdsClient.clusterStats.totalDrops).isEqualTo(1L);
    } else {
      assertThat(xdsClient.clusterStats.totalDrops).isEqualTo(0L);
    }
  }

  @Test
  public void maxConcurrentRequests_appliedWithDefaultValue_enableCircuitBreaking() {
    boolean originalEnableCircuitBreaking = ClusterImplLoadBalancer.enableCircuitBreaking;
    ClusterImplLoadBalancer.enableCircuitBreaking = true;
    subtest_maxConcurrentRequests_appliedWithDefaultValue(true);
    ClusterImplLoadBalancer.enableCircuitBreaking = originalEnableCircuitBreaking;
  }

  @Test
  public void maxConcurrentRequests_appliedWithDefaultValue_circuitBreakingDisabledByDefault() {
    subtest_maxConcurrentRequests_appliedWithDefaultValue(false);
  }

  private void subtest_maxConcurrentRequests_appliedWithDefaultValue(
      boolean enableCircuitBreaking) {
    LoadBalancerProvider weightedTargetProvider = new WeightedTargetLoadBalancerProvider();
    WeightedTargetConfig weightedTargetConfig =
        buildWeightedTargetConfig(ImmutableMap.of(locality, 10));
    ClusterImplConfig config = new ClusterImplConfig(CLUSTER, EDS_SERVICE_NAME, LRS_SERVER_NAME,
        null, Collections.<DropOverload>emptyList(),
        new PolicySelection(weightedTargetProvider, weightedTargetConfig), null);
    EquivalentAddressGroup endpoint = makeAddress("endpoint-addr", locality);
    deliverAddressesAndConfig(Collections.singletonList(endpoint), config);
    assertThat(downstreamBalancers).hasSize(1);  // one leaf balancer
    FakeLoadBalancer leafBalancer = Iterables.getOnlyElement(downstreamBalancers);
    assertThat(leafBalancer.name).isEqualTo("round_robin");
    assertThat(Iterables.getOnlyElement(leafBalancer.addresses).getAddresses())
        .isEqualTo(endpoint.getAddresses());
    Subchannel subchannel = leafBalancer.helper.createSubchannel(
        CreateSubchannelArgs.newBuilder().setAddresses(leafBalancer.addresses).build());
    leafBalancer.deliverSubchannelState(subchannel, ConnectivityState.READY);
    assertThat(currentState).isEqualTo(ConnectivityState.READY);
    for (int i = 0; i < ClusterImplLoadBalancer.DEFAULT_PER_CLUSTER_MAX_CONCURRENT_REQUESTS; i++) {
      PickResult result = currentPicker.pickSubchannel(mock(PickSubchannelArgs.class));
      assertThat(result.getStatus().isOk()).isTrue();
      assertThat(result.getSubchannel()).isSameInstanceAs(subchannel);
      assertThat(result.getStreamTracerFactory()).isNotNull();
      ClientStreamTracer.Factory streamTracerFactory = result.getStreamTracerFactory();
      streamTracerFactory.newClientStreamTracer(ClientStreamTracer.StreamInfo.newBuilder().build(),
          new Metadata());
    }
    assertThat(xdsClient.clusterStats.totalDrops).isEqualTo(0L);

    PickResult result = currentPicker.pickSubchannel(mock(PickSubchannelArgs.class));
    if (enableCircuitBreaking) {
      assertThat(result.getStatus().isOk()).isFalse();
      assertThat(result.getStatus().getCode()).isEqualTo(Code.UNAVAILABLE);
      assertThat(result.getStatus().getDescription())
          .isEqualTo("Cluster max concurrent requests limit exceeded");
      assertThat(xdsClient.clusterStats.totalDrops).isEqualTo(1L);
    } else {
      assertThat(result.getStatus().isOk()).isTrue();
      assertThat(result.getSubchannel()).isSameInstanceAs(subchannel);
      assertThat(xdsClient.clusterStats.totalDrops).isEqualTo(0L);
    }
  }

  @Test
  public void endpointAddressesAttachedWithClusterName() {
    LoadBalancerProvider weightedTargetProvider = new WeightedTargetLoadBalancerProvider();
    WeightedTargetConfig weightedTargetConfig =
        buildWeightedTargetConfig(ImmutableMap.of(locality, 10));
    ClusterImplConfig config = new ClusterImplConfig(CLUSTER, EDS_SERVICE_NAME, LRS_SERVER_NAME,
        null, Collections.<DropOverload>emptyList(),
        new PolicySelection(weightedTargetProvider, weightedTargetConfig), null);
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
  }

  @Test
  public void endpointAddressesAttachedWithTlsConfig_enableSecurity() {
    boolean originalEnableSecurity = ClusterImplLoadBalancer.enableSecurity;
    ClusterImplLoadBalancer.enableSecurity = true;
    subtest_endpointAddressesAttachedWithTlsConfig(true);
    ClusterImplLoadBalancer.enableSecurity = originalEnableSecurity;
  }

  @Test
  public void endpointAddressesAttachedWithTlsConfig_securityDisabledByDefault() {
    subtest_endpointAddressesAttachedWithTlsConfig(false);
  }

  private void subtest_endpointAddressesAttachedWithTlsConfig(boolean enableSecurity) {
    UpstreamTlsContext upstreamTlsContext =
        CommonTlsContextTestsUtil.buildUpstreamTlsContextFromFilenames(
            CommonTlsContextTestsUtil.CLIENT_KEY_FILE,
            CommonTlsContextTestsUtil.CLIENT_PEM_FILE,
            CommonTlsContextTestsUtil.CA_PEM_FILE);
    LoadBalancerProvider weightedTargetProvider = new WeightedTargetLoadBalancerProvider();
    WeightedTargetConfig weightedTargetConfig =
        buildWeightedTargetConfig(ImmutableMap.of(locality, 10));
    ClusterImplConfig config = new ClusterImplConfig(CLUSTER, EDS_SERVICE_NAME, LRS_SERVER_NAME,
        null, Collections.<DropOverload>emptyList(),
        new PolicySelection(weightedTargetProvider, weightedTargetConfig), upstreamTlsContext);
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
      if (enableSecurity) {
        assertThat(supplier.getUpstreamTlsContext()).isEqualTo(upstreamTlsContext);
      } else {
        assertThat(supplier).isNull();
      }
    }

    // Removes UpstreamTlsContext from the config.
    config = new ClusterImplConfig(CLUSTER, EDS_SERVICE_NAME, LRS_SERVER_NAME,
        null, Collections.<DropOverload>emptyList(),
        new PolicySelection(weightedTargetProvider, weightedTargetConfig), null);
    deliverAddressesAndConfig(Arrays.asList(endpoint1, endpoint2), config);
    assertThat(Iterables.getOnlyElement(downstreamBalancers)).isSameInstanceAs(leafBalancer);
    subchannel = leafBalancer.helper.createSubchannel(args);  // creates new connections
    for (EquivalentAddressGroup eag : subchannel.getAllAddresses()) {
      assertThat(eag.getAttributes().get(InternalXdsAttributes.ATTR_SSL_CONTEXT_PROVIDER_SUPPLIER))
          .isNull();
    }

    // Config with a new UpstreamTlsContext.
    upstreamTlsContext =
        CommonTlsContextTestsUtil.buildUpstreamTlsContextFromFilenames(
            CommonTlsContextTestsUtil.BAD_CLIENT_KEY_FILE,
            CommonTlsContextTestsUtil.BAD_CLIENT_PEM_FILE,
            CommonTlsContextTestsUtil.CA_PEM_FILE);
    config = new ClusterImplConfig(CLUSTER, EDS_SERVICE_NAME, LRS_SERVER_NAME,
        null, Collections.<DropOverload>emptyList(),
        new PolicySelection(weightedTargetProvider, weightedTargetConfig), upstreamTlsContext);
    deliverAddressesAndConfig(Arrays.asList(endpoint1, endpoint2), config);
    assertThat(Iterables.getOnlyElement(downstreamBalancers)).isSameInstanceAs(leafBalancer);
    subchannel = leafBalancer.helper.createSubchannel(args);  // creates new connections
    for (EquivalentAddressGroup eag : subchannel.getAllAddresses()) {
      SslContextProviderSupplier supplier =
          eag.getAttributes().get(InternalXdsAttributes.ATTR_SSL_CONTEXT_PROVIDER_SUPPLIER);
      if (enableSecurity) {
        assertThat(supplier.getUpstreamTlsContext()).isEqualTo(upstreamTlsContext);
      } else {
        assertThat(supplier).isNull();
      }
    }
  }

  private void deliverAddressesAndConfig(List<EquivalentAddressGroup> addresses,
      ClusterImplConfig config) {
    loadBalancer.handleResolvedAddresses(
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
    LoadBalancerProvider lrsBalancerProvider = new LrsLoadBalancerProvider();
    Map<String, WeightedPolicySelection> targets = new HashMap<>();
    for (Locality locality : localityWeights.keySet()) {
      int weight = localityWeights.get(locality);
      LrsConfig lrsConfig =
          new LrsConfig(CLUSTER, EDS_SERVICE_NAME, LRS_SERVER_NAME, locality, roundRobin);
      WeightedPolicySelection weightedLocalityLbPolicy =
          new WeightedPolicySelection(weight, new PolicySelection(lrsBalancerProvider, lrsConfig));
      targets.put(locality.toString(), weightedLocalityLbPolicy);
    }
    return new WeightedTargetConfig(Collections.unmodifiableMap(targets));
  }

  /**
   * Create a locality-labeled address.
   */
  private static EquivalentAddressGroup makeAddress(final String name, Locality locality) {
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

    return AddressFilter.setPathFilter(new EquivalentAddressGroup(new FakeSocketAddress(name)),
        Collections.singletonList(locality.toString()));
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
    public void handleResolvedAddresses(ResolvedAddresses resolvedAddresses) {
      addresses = resolvedAddresses.getAddresses();
      config = resolvedAddresses.getLoadBalancingPolicyConfig();
      attributes = resolvedAddresses.getAttributes();
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
  }

  private final class FakeLbHelper extends LoadBalancer.Helper {

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
      return new FakeSubchannel(args.getAddresses());
    }

    @Override
    public ManagedChannel createOobChannel(EquivalentAddressGroup eag, String authority) {
      throw new UnsupportedOperationException("should not be called");
    }

    @Override
    public String getAuthority() {
      return AUTHORITY;
    }
  }

  private static final class FakeSubchannel extends Subchannel {
    private final List<EquivalentAddressGroup> eags;

    private FakeSubchannel(List<EquivalentAddressGroup> eags) {
      this.eags = eags;
    }

    @Override
    public void shutdown() {
    }

    @Override
    public void requestConnection() {
    }

    @Override
    public List<EquivalentAddressGroup> getAllAddresses() {
      return eags;
    }

    @Override
    public Attributes getAttributes() {
      return Attributes.EMPTY;
    }
  }

  private static final class FakeXdsClient extends XdsClient {
    private FakeLoadStatsStore clusterStats;

    @Override
    LoadStatsStore addClientStats(String clusterName, @Nullable String clusterServiceName) {
      assertThat(clusterStats).isNull();
      clusterStats = new FakeLoadStatsStore();
      return clusterStats;
    }

    @Override
    void removeClientStats(String clusterName, @Nullable String clusterServiceName) {
      assertThat(clusterStats).isNotNull();
      clusterStats = null;
    }
  }

  private static final class FakeLoadStatsStore implements LoadStatsStore {
    private final Map<String, Long> categorizedDrops = new HashMap<>();
    private int totalDrops;

    @Override
    public ClusterStats generateLoadReport() {
      throw new UnsupportedOperationException("should not be called");
    }

    @Override
    public ClientLoadCounter addLocality(Locality locality) {
      return new ClientLoadCounter();
    }

    @Override
    public void removeLocality(Locality locality) {
      // no-op
    }

    @Override
    public void recordDroppedRequest(String category) {
      if (!categorizedDrops.containsKey(category)) {
        categorizedDrops.put(category, 1L);
      } else {
        categorizedDrops.put(category, categorizedDrops.get(category) + 1L);
      }
      totalDrops++;
    }

    @Override
    public void recordDroppedRequest() {
      totalDrops++;
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
