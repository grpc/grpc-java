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
import static io.grpc.xds.XdsLbPolicies.CLUSTER_RESOLVER_POLICY_NAME;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import io.grpc.Attributes;
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
import io.grpc.Status;
import io.grpc.Status.Code;
import io.grpc.SynchronizationContext;
import io.grpc.internal.ObjectPool;
import io.grpc.xds.Bootstrapper.ServerInfo;
import io.grpc.xds.CdsLoadBalancerProvider.CdsConfig;
import io.grpc.xds.ClusterResolverLoadBalancerProvider.ClusterResolverConfig;
import io.grpc.xds.ClusterResolverLoadBalancerProvider.ClusterResolverConfig.DiscoveryMechanism;
import io.grpc.xds.EnvoyServerProtoData.OutlierDetection;
import io.grpc.xds.EnvoyServerProtoData.SuccessRateEjection;
import io.grpc.xds.EnvoyServerProtoData.UpstreamTlsContext;
import io.grpc.xds.LeastRequestLoadBalancer.LeastRequestConfig;
import io.grpc.xds.RingHashLoadBalancer.RingHashConfig;
import io.grpc.xds.XdsClusterResource.CdsUpdate;
import io.grpc.xds.internal.security.CommonTlsContextTestsUtil;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

/**
 * Tests for {@link CdsLoadBalancer2}.
 */
@RunWith(JUnit4.class)
public class CdsLoadBalancer2Test {
  @Rule public final MockitoRule mocks = MockitoJUnit.rule();

  private static final String CLUSTER = "cluster-foo.googleapis.com";
  private static final String EDS_SERVICE_NAME = "backend-service-1.googleapis.com";
  private static final String DNS_HOST_NAME = "backend-service-dns.googleapis.com:443";
  private static final ServerInfo LRS_SERVER_INFO =
      ServerInfo.create("lrs.googleapis.com", InsecureChannelCredentials.create());
  private final UpstreamTlsContext upstreamTlsContext =
      CommonTlsContextTestsUtil.buildUpstreamTlsContext("google_cloud_private_spiffe", true);
  private final OutlierDetection outlierDetection = OutlierDetection.create(
      null, null, null, null, SuccessRateEjection.create(null, null, null, null), null);


  private static final SynchronizationContext syncContext = new SynchronizationContext(
      new Thread.UncaughtExceptionHandler() {
        @Override
        public void uncaughtException(Thread t, Throwable e) {
          throw new RuntimeException(e);
          //throw new AssertionError(e);
        }
      });
  private final LoadBalancerRegistry lbRegistry = new LoadBalancerRegistry();
  private final List<FakeLoadBalancer> childBalancers = new ArrayList<>();
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
  @Captor
  private ArgumentCaptor<SubchannelPicker> pickerCaptor;
  private int xdsClientRefs;
  private CdsLoadBalancer2  loadBalancer;

  @Before
  public void setUp() {
    when(helper.getSynchronizationContext()).thenReturn(syncContext);
    lbRegistry.register(new FakeLoadBalancerProvider(CLUSTER_RESOLVER_POLICY_NAME));
    lbRegistry.register(new FakeLoadBalancerProvider("round_robin"));
    lbRegistry.register(
        new FakeLoadBalancerProvider("ring_hash_experimental", new RingHashLoadBalancerProvider()));
    lbRegistry.register(new FakeLoadBalancerProvider("least_request_experimental",
        new LeastRequestLoadBalancerProvider()));
    loadBalancer = new CdsLoadBalancer2(helper, lbRegistry);
    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder()
            .setAddresses(Collections.<EquivalentAddressGroup>emptyList())
            .setAttributes(
                // Other attributes not used by cluster_resolver LB are omitted.
                Attributes.newBuilder()
                    .set(InternalXdsAttributes.XDS_CLIENT_POOL, xdsClientPool)
                    .build())
            .setLoadBalancingPolicyConfig(new CdsConfig(CLUSTER))
            .build());
    assertThat(Iterables.getOnlyElement(xdsClient.watchers.keySet())).isEqualTo(CLUSTER);
  }

  @After
  public void tearDown() {
    loadBalancer.shutdown();
    assertThat(xdsClient.watchers).isEmpty();
    assertThat(xdsClientRefs).isEqualTo(0);
    assertThat(childBalancers).isEmpty();
  }

  @Test
  public void discoverTopLevelEdsCluster() {
    CdsUpdate update =
        CdsUpdate.forEds(CLUSTER, EDS_SERVICE_NAME, LRS_SERVER_INFO, 100L, upstreamTlsContext,
                outlierDetection)
            .roundRobinLbPolicy().build();
    xdsClient.deliverCdsUpdate(CLUSTER, update);
    assertThat(childBalancers).hasSize(1);
    FakeLoadBalancer childBalancer = Iterables.getOnlyElement(childBalancers);
    assertThat(childBalancer.name).isEqualTo(CLUSTER_RESOLVER_POLICY_NAME);
    ClusterResolverConfig childLbConfig = (ClusterResolverConfig) childBalancer.config;
    assertThat(childLbConfig.discoveryMechanisms).hasSize(1);
    DiscoveryMechanism instance = Iterables.getOnlyElement(childLbConfig.discoveryMechanisms);
    assertDiscoveryMechanism(instance, CLUSTER, DiscoveryMechanism.Type.EDS, EDS_SERVICE_NAME,
        null, LRS_SERVER_INFO, 100L, upstreamTlsContext, outlierDetection);
    assertThat(childLbConfig.lbPolicy.getProvider().getPolicyName()).isEqualTo("round_robin");
  }

  @Test
  public void discoverTopLevelLogicalDnsCluster() {
    CdsUpdate update =
        CdsUpdate.forLogicalDns(CLUSTER, DNS_HOST_NAME, LRS_SERVER_INFO, 100L, upstreamTlsContext)
            .leastRequestLbPolicy(3).build();
    xdsClient.deliverCdsUpdate(CLUSTER, update);
    assertThat(childBalancers).hasSize(1);
    FakeLoadBalancer childBalancer = Iterables.getOnlyElement(childBalancers);
    assertThat(childBalancer.name).isEqualTo(CLUSTER_RESOLVER_POLICY_NAME);
    ClusterResolverConfig childLbConfig = (ClusterResolverConfig) childBalancer.config;
    assertThat(childLbConfig.discoveryMechanisms).hasSize(1);
    DiscoveryMechanism instance = Iterables.getOnlyElement(childLbConfig.discoveryMechanisms);
    assertDiscoveryMechanism(instance, CLUSTER, DiscoveryMechanism.Type.LOGICAL_DNS, null,
        DNS_HOST_NAME, LRS_SERVER_INFO, 100L, upstreamTlsContext, null);
    assertThat(childLbConfig.lbPolicy.getProvider().getPolicyName())
        .isEqualTo("least_request_experimental");
    assertThat(((LeastRequestConfig) childLbConfig.lbPolicy.getConfig()).choiceCount).isEqualTo(3);
  }

  @Test
  public void nonAggregateCluster_resourceNotExist_returnErrorPicker() {
    xdsClient.deliverResourceNotExist(CLUSTER);
    verify(helper).updateBalancingState(
        eq(ConnectivityState.TRANSIENT_FAILURE), pickerCaptor.capture());
    Status unavailable = Status.UNAVAILABLE.withDescription(
        "CDS error: found 0 leaf (logical DNS or EDS) clusters for root cluster " + CLUSTER);
    assertPicker(pickerCaptor.getValue(), unavailable, null);
    assertThat(childBalancers).isEmpty();
  }

  @Test
  public void nonAggregateCluster_resourceUpdate() {
    CdsUpdate update =
        CdsUpdate.forEds(CLUSTER, null, null, 100L, upstreamTlsContext, outlierDetection)
            .roundRobinLbPolicy().build();
    xdsClient.deliverCdsUpdate(CLUSTER, update);
    assertThat(childBalancers).hasSize(1);
    FakeLoadBalancer childBalancer = Iterables.getOnlyElement(childBalancers);
    ClusterResolverConfig childLbConfig = (ClusterResolverConfig) childBalancer.config;
    DiscoveryMechanism instance = Iterables.getOnlyElement(childLbConfig.discoveryMechanisms);
    assertDiscoveryMechanism(instance, CLUSTER, DiscoveryMechanism.Type.EDS, null, null, null,
        100L, upstreamTlsContext, outlierDetection);

    update = CdsUpdate.forEds(CLUSTER, EDS_SERVICE_NAME, LRS_SERVER_INFO, 200L, null,
        outlierDetection).roundRobinLbPolicy().build();
    xdsClient.deliverCdsUpdate(CLUSTER, update);
    childLbConfig = (ClusterResolverConfig) childBalancer.config;
    instance = Iterables.getOnlyElement(childLbConfig.discoveryMechanisms);
    assertDiscoveryMechanism(instance, CLUSTER, DiscoveryMechanism.Type.EDS, EDS_SERVICE_NAME,
        null, LRS_SERVER_INFO, 200L, null, outlierDetection);
  }

  @Test
  public void nonAggregateCluster_resourceRevoked() {
    CdsUpdate update =
        CdsUpdate.forLogicalDns(CLUSTER, DNS_HOST_NAME, null, 100L, upstreamTlsContext)
            .roundRobinLbPolicy().build();
    xdsClient.deliverCdsUpdate(CLUSTER, update);
    assertThat(childBalancers).hasSize(1);
    FakeLoadBalancer childBalancer = Iterables.getOnlyElement(childBalancers);
    ClusterResolverConfig childLbConfig = (ClusterResolverConfig) childBalancer.config;
    DiscoveryMechanism instance = Iterables.getOnlyElement(childLbConfig.discoveryMechanisms);
    assertDiscoveryMechanism(instance, CLUSTER, DiscoveryMechanism.Type.LOGICAL_DNS, null,
        DNS_HOST_NAME, null, 100L, upstreamTlsContext, null);

    xdsClient.deliverResourceNotExist(CLUSTER);
    assertThat(childBalancer.shutdown).isTrue();
    Status unavailable = Status.UNAVAILABLE.withDescription(
        "CDS error: found 0 leaf (logical DNS or EDS) clusters for root cluster " + CLUSTER);
    verify(helper).updateBalancingState(
        eq(ConnectivityState.TRANSIENT_FAILURE), pickerCaptor.capture());
    assertPicker(pickerCaptor.getValue(), unavailable, null);
    assertThat(childBalancer.shutdown).isTrue();
    assertThat(childBalancers).isEmpty();
  }

  @Test
  public void discoverAggregateCluster() {
    String cluster1 = "cluster-01.googleapis.com";
    String cluster2 = "cluster-02.googleapis.com";
    // CLUSTER (aggr.) -> [cluster1 (aggr.), cluster2 (logical DNS)]
    CdsUpdate update =
        CdsUpdate.forAggregate(CLUSTER, Arrays.asList(cluster1, cluster2))
            .ringHashLbPolicy(100L, 1000L).build();
    xdsClient.deliverCdsUpdate(CLUSTER, update);
    assertThat(xdsClient.watchers.keySet()).containsExactly(CLUSTER, cluster1, cluster2);
    assertThat(childBalancers).isEmpty();
    String cluster3 = "cluster-03.googleapis.com";
    String cluster4 = "cluster-04.googleapis.com";
    // cluster1 (aggr.) -> [cluster3 (EDS), cluster4 (EDS)]
    CdsUpdate update1 =
        CdsUpdate.forAggregate(cluster1, Arrays.asList(cluster3, cluster4))
            .roundRobinLbPolicy().build();
    xdsClient.deliverCdsUpdate(cluster1, update1);
    assertThat(xdsClient.watchers.keySet()).containsExactly(
        CLUSTER, cluster1, cluster2, cluster3, cluster4);
    assertThat(childBalancers).isEmpty();
    CdsUpdate update3 = CdsUpdate.forEds(cluster3, EDS_SERVICE_NAME, LRS_SERVER_INFO, 200L,
        upstreamTlsContext, outlierDetection).roundRobinLbPolicy().build();
    xdsClient.deliverCdsUpdate(cluster3, update3);
    assertThat(childBalancers).isEmpty();
    CdsUpdate update2 =
        CdsUpdate.forLogicalDns(cluster2, DNS_HOST_NAME, null, 100L, null)
            .roundRobinLbPolicy().build();
    xdsClient.deliverCdsUpdate(cluster2, update2);
    assertThat(childBalancers).isEmpty();
    CdsUpdate update4 =
        CdsUpdate.forEds(cluster4, null, LRS_SERVER_INFO, 300L, null, outlierDetection)
            .roundRobinLbPolicy().build();
    xdsClient.deliverCdsUpdate(cluster4, update4);
    assertThat(childBalancers).hasSize(1);  // all non-aggregate clusters discovered
    FakeLoadBalancer childBalancer = Iterables.getOnlyElement(childBalancers);
    assertThat(childBalancer.name).isEqualTo(CLUSTER_RESOLVER_POLICY_NAME);
    ClusterResolverConfig childLbConfig = (ClusterResolverConfig) childBalancer.config;
    assertThat(childLbConfig.discoveryMechanisms).hasSize(3);
    // Clusters on higher level has higher priority: [cluster2, cluster3, cluster4]
    assertDiscoveryMechanism(childLbConfig.discoveryMechanisms.get(0), cluster2,
        DiscoveryMechanism.Type.LOGICAL_DNS, null, DNS_HOST_NAME, null, 100L, null, null);
    assertDiscoveryMechanism(childLbConfig.discoveryMechanisms.get(1), cluster3,
        DiscoveryMechanism.Type.EDS, EDS_SERVICE_NAME, null, LRS_SERVER_INFO, 200L,
        upstreamTlsContext, outlierDetection);
    assertDiscoveryMechanism(childLbConfig.discoveryMechanisms.get(2), cluster4,
        DiscoveryMechanism.Type.EDS, null, null, LRS_SERVER_INFO, 300L, null, outlierDetection);
    assertThat(childLbConfig.lbPolicy.getProvider().getPolicyName())
        .isEqualTo("ring_hash_experimental");  // dominated by top-level cluster's config
    assertThat(((RingHashConfig) childLbConfig.lbPolicy.getConfig()).minRingSize).isEqualTo(100L);
    assertThat(((RingHashConfig) childLbConfig.lbPolicy.getConfig()).maxRingSize).isEqualTo(1000L);
  }

  @Test
  public void aggregateCluster_noNonAggregateClusterExits_returnErrorPicker() {
    String cluster1 = "cluster-01.googleapis.com";
    // CLUSTER (aggr.) -> [cluster1 (EDS)]
    CdsUpdate update =
        CdsUpdate.forAggregate(CLUSTER, Collections.singletonList(cluster1))
            .roundRobinLbPolicy().build();
    xdsClient.deliverCdsUpdate(CLUSTER, update);
    assertThat(xdsClient.watchers.keySet()).containsExactly(CLUSTER, cluster1);
    xdsClient.deliverResourceNotExist(cluster1);
    verify(helper).updateBalancingState(
        eq(ConnectivityState.TRANSIENT_FAILURE), pickerCaptor.capture());
    Status unavailable = Status.UNAVAILABLE.withDescription(
        "CDS error: found 0 leaf (logical DNS or EDS) clusters for root cluster " + CLUSTER);
    assertPicker(pickerCaptor.getValue(), unavailable, null);
    assertThat(childBalancers).isEmpty();
  }

  @Test
  public void aggregateCluster_descendantClustersRevoked() {
    String cluster1 = "cluster-01.googleapis.com";
    String cluster2 = "cluster-02.googleapis.com";
    // CLUSTER (aggr.) -> [cluster1 (EDS), cluster2 (logical DNS)]
    CdsUpdate update =
        CdsUpdate.forAggregate(CLUSTER, Arrays.asList(cluster1, cluster2))
            .roundRobinLbPolicy().build();
    xdsClient.deliverCdsUpdate(CLUSTER, update);
    assertThat(xdsClient.watchers.keySet()).containsExactly(CLUSTER, cluster1, cluster2);
    CdsUpdate update1 = CdsUpdate.forEds(cluster1, EDS_SERVICE_NAME, LRS_SERVER_INFO, 200L,
        upstreamTlsContext, outlierDetection).roundRobinLbPolicy().build();
    xdsClient.deliverCdsUpdate(cluster1, update1);
    CdsUpdate update2 =
        CdsUpdate.forLogicalDns(cluster2, DNS_HOST_NAME, LRS_SERVER_INFO, 100L, null)
            .roundRobinLbPolicy().build();
    xdsClient.deliverCdsUpdate(cluster2, update2);
    FakeLoadBalancer childBalancer = Iterables.getOnlyElement(childBalancers);
    ClusterResolverConfig childLbConfig = (ClusterResolverConfig) childBalancer.config;
    assertThat(childLbConfig.discoveryMechanisms).hasSize(2);
    assertDiscoveryMechanism(childLbConfig.discoveryMechanisms.get(0), cluster1,
        DiscoveryMechanism.Type.EDS, EDS_SERVICE_NAME, null, LRS_SERVER_INFO, 200L,
        upstreamTlsContext, outlierDetection);
    assertDiscoveryMechanism(childLbConfig.discoveryMechanisms.get(1), cluster2,
        DiscoveryMechanism.Type.LOGICAL_DNS, null, DNS_HOST_NAME, LRS_SERVER_INFO, 100L, null,
        null);

    // Revoke cluster1, should still be able to proceed with cluster2.
    xdsClient.deliverResourceNotExist(cluster1);
    assertThat(xdsClient.watchers.keySet()).containsExactly(CLUSTER, cluster1, cluster2);
    childLbConfig = (ClusterResolverConfig) childBalancer.config;
    assertThat(childLbConfig.discoveryMechanisms).hasSize(1);
    assertDiscoveryMechanism(Iterables.getOnlyElement(childLbConfig.discoveryMechanisms), cluster2,
        DiscoveryMechanism.Type.LOGICAL_DNS, null, DNS_HOST_NAME, LRS_SERVER_INFO, 100L, null,
        null);
    verify(helper, never()).updateBalancingState(
        eq(ConnectivityState.TRANSIENT_FAILURE), any(SubchannelPicker.class));

    // All revoked.
    xdsClient.deliverResourceNotExist(cluster2);
    verify(helper).updateBalancingState(
        eq(ConnectivityState.TRANSIENT_FAILURE), pickerCaptor.capture());
    Status unavailable = Status.UNAVAILABLE.withDescription(
        "CDS error: found 0 leaf (logical DNS or EDS) clusters for root cluster " + CLUSTER);
    assertPicker(pickerCaptor.getValue(), unavailable, null);
    assertThat(childBalancer.shutdown).isTrue();
    assertThat(childBalancers).isEmpty();
  }

  @Test
  public void aggregateCluster_rootClusterRevoked() {
    String cluster1 = "cluster-01.googleapis.com";
    String cluster2 = "cluster-02.googleapis.com";
    // CLUSTER (aggr.) -> [cluster1 (EDS), cluster2 (logical DNS)]
    CdsUpdate update =
        CdsUpdate.forAggregate(CLUSTER, Arrays.asList(cluster1, cluster2))
            .roundRobinLbPolicy().build();
    xdsClient.deliverCdsUpdate(CLUSTER, update);
    assertThat(xdsClient.watchers.keySet()).containsExactly(CLUSTER, cluster1, cluster2);
    CdsUpdate update1 = CdsUpdate.forEds(cluster1, EDS_SERVICE_NAME, LRS_SERVER_INFO, 200L,
        upstreamTlsContext, outlierDetection).roundRobinLbPolicy().build();
    xdsClient.deliverCdsUpdate(cluster1, update1);
    CdsUpdate update2 =
        CdsUpdate.forLogicalDns(cluster2, DNS_HOST_NAME, LRS_SERVER_INFO, 100L, null)
            .roundRobinLbPolicy().build();
    xdsClient.deliverCdsUpdate(cluster2, update2);
    FakeLoadBalancer childBalancer = Iterables.getOnlyElement(childBalancers);
    ClusterResolverConfig childLbConfig = (ClusterResolverConfig) childBalancer.config;
    assertThat(childLbConfig.discoveryMechanisms).hasSize(2);
    assertDiscoveryMechanism(childLbConfig.discoveryMechanisms.get(0), cluster1,
        DiscoveryMechanism.Type.EDS, EDS_SERVICE_NAME, null, LRS_SERVER_INFO, 200L,
        upstreamTlsContext, outlierDetection);
    assertDiscoveryMechanism(childLbConfig.discoveryMechanisms.get(1), cluster2,
        DiscoveryMechanism.Type.LOGICAL_DNS, null, DNS_HOST_NAME, LRS_SERVER_INFO, 100L, null,
        null);

    xdsClient.deliverResourceNotExist(CLUSTER);
    assertThat(xdsClient.watchers.keySet())
        .containsExactly(CLUSTER);  // subscription to all descendant clusters cancelled
    verify(helper).updateBalancingState(
        eq(ConnectivityState.TRANSIENT_FAILURE), pickerCaptor.capture());
    Status unavailable = Status.UNAVAILABLE.withDescription(
        "CDS error: found 0 leaf (logical DNS or EDS) clusters for root cluster " + CLUSTER);
    assertPicker(pickerCaptor.getValue(), unavailable, null);
    assertThat(childBalancer.shutdown).isTrue();
    assertThat(childBalancers).isEmpty();
  }

  @Test
  public void aggregateCluster_intermediateClusterChanges() {
    String cluster1 = "cluster-01.googleapis.com";
    // CLUSTER (aggr.) -> [cluster1]
    CdsUpdate update =
        CdsUpdate.forAggregate(CLUSTER, Collections.singletonList(cluster1))
            .roundRobinLbPolicy().build();
    xdsClient.deliverCdsUpdate(CLUSTER, update);
    assertThat(xdsClient.watchers.keySet()).containsExactly(CLUSTER, cluster1);

    // CLUSTER (aggr.) -> [cluster2 (aggr.)]
    String cluster2 = "cluster-02.googleapis.com";
    update =
        CdsUpdate.forAggregate(CLUSTER, Collections.singletonList(cluster2))
            .roundRobinLbPolicy().build();
    xdsClient.deliverCdsUpdate(CLUSTER, update);
    assertThat(xdsClient.watchers.keySet()).containsExactly(CLUSTER, cluster2);

    // cluster2 (aggr.) -> [cluster3 (EDS)]
    String cluster3 = "cluster-03.googleapis.com";
    CdsUpdate update2 =
        CdsUpdate.forAggregate(cluster2, Collections.singletonList(cluster3))
            .roundRobinLbPolicy().build();
    xdsClient.deliverCdsUpdate(cluster2, update2);
    assertThat(xdsClient.watchers.keySet()).containsExactly(CLUSTER, cluster2, cluster3);
    CdsUpdate update3 = CdsUpdate.forEds(cluster3, EDS_SERVICE_NAME, LRS_SERVER_INFO, 100L,
        upstreamTlsContext, outlierDetection).roundRobinLbPolicy().build();
    xdsClient.deliverCdsUpdate(cluster3, update3);
    FakeLoadBalancer childBalancer = Iterables.getOnlyElement(childBalancers);
    ClusterResolverConfig childLbConfig = (ClusterResolverConfig) childBalancer.config;
    assertThat(childLbConfig.discoveryMechanisms).hasSize(1);
    DiscoveryMechanism instance = Iterables.getOnlyElement(childLbConfig.discoveryMechanisms);
    assertDiscoveryMechanism(instance, cluster3, DiscoveryMechanism.Type.EDS, EDS_SERVICE_NAME,
        null, LRS_SERVER_INFO, 100L, upstreamTlsContext, outlierDetection);

    // cluster2 revoked
    xdsClient.deliverResourceNotExist(cluster2);
    assertThat(xdsClient.watchers.keySet())
        .containsExactly(CLUSTER, cluster2);  // cancelled subscription to cluster3
    verify(helper).updateBalancingState(
        eq(ConnectivityState.TRANSIENT_FAILURE), pickerCaptor.capture());
    Status unavailable = Status.UNAVAILABLE.withDescription(
        "CDS error: found 0 leaf (logical DNS or EDS) clusters for root cluster " + CLUSTER);
    assertPicker(pickerCaptor.getValue(), unavailable, null);
    assertThat(childBalancer.shutdown).isTrue();
    assertThat(childBalancers).isEmpty();
  }

  @Test
  public void aggregateCluster_withLoops() {
    String cluster1 = "cluster-01.googleapis.com";
    // CLUSTER (aggr.) -> [cluster1]
    CdsUpdate update =
        CdsUpdate.forAggregate(CLUSTER, Collections.singletonList(cluster1))
            .roundRobinLbPolicy().build();
    xdsClient.deliverCdsUpdate(CLUSTER, update);
    assertThat(xdsClient.watchers.keySet()).containsExactly(CLUSTER, cluster1);

    // CLUSTER (aggr.) -> [cluster2 (aggr.)]
    String cluster2 = "cluster-02.googleapis.com";
    update =
        CdsUpdate.forAggregate(cluster1, Collections.singletonList(cluster2))
            .roundRobinLbPolicy().build();
    xdsClient.deliverCdsUpdate(cluster1, update);
    assertThat(xdsClient.watchers.keySet()).containsExactly(CLUSTER, cluster1, cluster2);

    // cluster2 (aggr.) -> [cluster3 (EDS), cluster1 (parent), cluster2 (self), cluster3 (dup)]
    String cluster3 = "cluster-03.googleapis.com";
    CdsUpdate update2 =
        CdsUpdate.forAggregate(cluster2, Arrays.asList(cluster3, cluster1, cluster2, cluster3))
            .roundRobinLbPolicy().build();
    xdsClient.deliverCdsUpdate(cluster2, update2);
    assertThat(xdsClient.watchers.keySet()).containsExactly(CLUSTER, cluster1, cluster2, cluster3);

    reset(helper);
    CdsUpdate update3 = CdsUpdate.forEds(cluster3, EDS_SERVICE_NAME, LRS_SERVER_INFO, 100L,
        upstreamTlsContext, outlierDetection).roundRobinLbPolicy().build();
    xdsClient.deliverCdsUpdate(cluster3, update3);
    verify(helper).updateBalancingState(
        eq(ConnectivityState.TRANSIENT_FAILURE), pickerCaptor.capture());
    Status unavailable = Status.UNAVAILABLE.withDescription(
        "CDS error: circular aggregate clusters directly under cluster-02.googleapis.com for root"
            + " cluster cluster-foo.googleapis.com, named [cluster-01.googleapis.com,"
            + " cluster-02.googleapis.com]");
    assertPicker(pickerCaptor.getValue(), unavailable, null);
  }

  @Test
  public void aggregateCluster_withLoops_afterEds() {
    String cluster1 = "cluster-01.googleapis.com";
    // CLUSTER (aggr.) -> [cluster1]
    CdsUpdate update =
        CdsUpdate.forAggregate(CLUSTER, Collections.singletonList(cluster1))
            .roundRobinLbPolicy().build();
    xdsClient.deliverCdsUpdate(CLUSTER, update);
    assertThat(xdsClient.watchers.keySet()).containsExactly(CLUSTER, cluster1);

    // CLUSTER (aggr.) -> [cluster2 (aggr.)]
    String cluster2 = "cluster-02.googleapis.com";
    update =
        CdsUpdate.forAggregate(cluster1, Collections.singletonList(cluster2))
            .roundRobinLbPolicy().build();
    xdsClient.deliverCdsUpdate(cluster1, update);
    assertThat(xdsClient.watchers.keySet()).containsExactly(CLUSTER, cluster1, cluster2);

    String cluster3 = "cluster-03.googleapis.com";
    CdsUpdate update2 =
        CdsUpdate.forAggregate(cluster2, Arrays.asList(cluster3))
            .roundRobinLbPolicy().build();
    xdsClient.deliverCdsUpdate(cluster2, update2);
    CdsUpdate update3 = CdsUpdate.forEds(cluster3, EDS_SERVICE_NAME, LRS_SERVER_INFO, 100L,
        upstreamTlsContext, outlierDetection).roundRobinLbPolicy().build();
    xdsClient.deliverCdsUpdate(cluster3, update3);

    // cluster2 (aggr.) -> [cluster3 (EDS)]
    CdsUpdate update2a =
        CdsUpdate.forAggregate(cluster2, Arrays.asList(cluster3, cluster1, cluster2, cluster3))
            .roundRobinLbPolicy().build();
    xdsClient.deliverCdsUpdate(cluster2, update2a);
    assertThat(xdsClient.watchers.keySet()).containsExactly(CLUSTER, cluster1, cluster2, cluster3);
    verify(helper).updateBalancingState(
        eq(ConnectivityState.TRANSIENT_FAILURE), pickerCaptor.capture());
    Status unavailable = Status.UNAVAILABLE.withDescription(
        "CDS error: circular aggregate clusters directly under cluster-02.googleapis.com for root"
            + " cluster cluster-foo.googleapis.com, named [cluster-01.googleapis.com,"
            + " cluster-02.googleapis.com]");
    assertPicker(pickerCaptor.getValue(), unavailable, null);
  }

  @Test
  public void aggregateCluster_duplicateChildren() {
    String cluster1 = "cluster-01.googleapis.com";
    String cluster2 = "cluster-02.googleapis.com";
    String cluster3 = "cluster-03.googleapis.com";
    String cluster4 = "cluster-04.googleapis.com";

    // CLUSTER (aggr.) -> [cluster1]
    CdsUpdate update =
        CdsUpdate.forAggregate(CLUSTER, Collections.singletonList(cluster1))
            .roundRobinLbPolicy().build();
    xdsClient.deliverCdsUpdate(CLUSTER, update);
    assertThat(xdsClient.watchers.keySet()).containsExactly(CLUSTER, cluster1);

    // cluster1 (aggr) -> [cluster3 (EDS), cluster2 (aggr), cluster4 (aggr)]
    CdsUpdate update1 =
        CdsUpdate.forAggregate(cluster1, Arrays.asList(cluster3, cluster2, cluster4, cluster3))
            .roundRobinLbPolicy().build();
    xdsClient.deliverCdsUpdate(cluster1, update1);
    assertThat(xdsClient.watchers.keySet()).containsExactly(
        cluster3, cluster4, cluster2, cluster1, CLUSTER);
    xdsClient.watchers.values().forEach(list -> assertThat(list.size()).isEqualTo(1));

    // cluster2 (agg) -> [cluster3 (EDS), cluster4 {agg}] with dups
    CdsUpdate update2 =
        CdsUpdate.forAggregate(cluster2, Arrays.asList(cluster3, cluster4, cluster3))
            .roundRobinLbPolicy().build();
    xdsClient.deliverCdsUpdate(cluster2, update2);

    // Define EDS cluster
    CdsUpdate update3 = CdsUpdate.forEds(cluster3, EDS_SERVICE_NAME, LRS_SERVER_INFO, 100L,
        upstreamTlsContext, outlierDetection).roundRobinLbPolicy().build();
    xdsClient.deliverCdsUpdate(cluster3, update3);

    // cluster4 (agg) -> [cluster3 (EDS)] with dups (3 copies)
    CdsUpdate update4 =
        CdsUpdate.forAggregate(cluster4, Arrays.asList(cluster3, cluster3, cluster3))
            .roundRobinLbPolicy().build();
    xdsClient.deliverCdsUpdate(cluster4, update4);
    xdsClient.watchers.values().forEach(list -> assertThat(list.size()).isEqualTo(1));

    FakeLoadBalancer childBalancer = Iterables.getOnlyElement(childBalancers);
    ClusterResolverConfig childLbConfig = (ClusterResolverConfig) childBalancer.config;
    assertThat(childLbConfig.discoveryMechanisms).hasSize(1);
    DiscoveryMechanism instance = Iterables.getOnlyElement(childLbConfig.discoveryMechanisms);
    assertDiscoveryMechanism(instance, cluster3, DiscoveryMechanism.Type.EDS, EDS_SERVICE_NAME,
        null, LRS_SERVER_INFO, 100L, upstreamTlsContext, outlierDetection);
  }

  @Test
  public void aggregateCluster_discoveryErrorBeforeChildLbCreated_returnErrorPicker() {
    String cluster1 = "cluster-01.googleapis.com";
    // CLUSTER (aggr.) -> [cluster1]
    CdsUpdate update =
        CdsUpdate.forAggregate(CLUSTER, Collections.singletonList(cluster1))
            .roundRobinLbPolicy().build();
    xdsClient.deliverCdsUpdate(CLUSTER, update);
    assertThat(xdsClient.watchers.keySet()).containsExactly(CLUSTER, cluster1);
    Status error = Status.RESOURCE_EXHAUSTED.withDescription("OOM");
    xdsClient.deliverError(error);
    verify(helper).updateBalancingState(
        eq(ConnectivityState.TRANSIENT_FAILURE), pickerCaptor.capture());
    Status expectedError = Status.UNAVAILABLE.withDescription(
        "Unable to load CDS cluster-foo.googleapis.com. xDS server returned: "
        + "RESOURCE_EXHAUSTED: OOM");
    assertPicker(pickerCaptor.getValue(), expectedError, null);
    assertThat(childBalancers).isEmpty();
  }

  @Test
  public void aggregateCluster_discoveryErrorAfterChildLbCreated_propagateToChildLb() {
    String cluster1 = "cluster-01.googleapis.com";
    // CLUSTER (aggr.) -> [cluster1 (logical DNS)]
    CdsUpdate update =
        CdsUpdate.forAggregate(CLUSTER, Collections.singletonList(cluster1))
            .roundRobinLbPolicy().build();
    xdsClient.deliverCdsUpdate(CLUSTER, update);
    CdsUpdate update1 =
        CdsUpdate.forLogicalDns(cluster1, DNS_HOST_NAME, LRS_SERVER_INFO, 200L, null)
            .roundRobinLbPolicy().build();
    xdsClient.deliverCdsUpdate(cluster1, update1);
    FakeLoadBalancer childLb = Iterables.getOnlyElement(childBalancers);
    ClusterResolverConfig childLbConfig = (ClusterResolverConfig) childLb.config;
    assertThat(childLbConfig.discoveryMechanisms).hasSize(1);

    Status error = Status.RESOURCE_EXHAUSTED.withDescription("OOM");
    xdsClient.deliverError(error);
    assertThat(childLb.upstreamError.getCode()).isEqualTo(Status.Code.UNAVAILABLE);
    assertThat(childLb.upstreamError.getDescription()).contains("RESOURCE_EXHAUSTED: OOM");
    assertThat(childLb.shutdown).isFalse();  // child LB may choose to keep working
  }

  @Test
  public void handleNameResolutionErrorFromUpstream_beforeChildLbCreated_returnErrorPicker() {
    Status upstreamError = Status.UNAVAILABLE.withDescription("unreachable");
    loadBalancer.handleNameResolutionError(upstreamError);
    verify(helper).updateBalancingState(
        eq(ConnectivityState.TRANSIENT_FAILURE), pickerCaptor.capture());
    assertPicker(pickerCaptor.getValue(), upstreamError, null);
  }

  @Test
  public void handleNameResolutionErrorFromUpstream_afterChildLbCreated_fallThrough() {
    CdsUpdate update = CdsUpdate.forEds(CLUSTER, EDS_SERVICE_NAME, LRS_SERVER_INFO, 100L,
        upstreamTlsContext, outlierDetection).roundRobinLbPolicy().build();
    xdsClient.deliverCdsUpdate(CLUSTER, update);
    FakeLoadBalancer childBalancer = Iterables.getOnlyElement(childBalancers);
    assertThat(childBalancer.shutdown).isFalse();
    loadBalancer.handleNameResolutionError(Status.UNAVAILABLE.withDescription("unreachable"));
    assertThat(childBalancer.upstreamError.getCode()).isEqualTo(Code.UNAVAILABLE);
    assertThat(childBalancer.upstreamError.getDescription()).isEqualTo("unreachable");
    verify(helper, never()).updateBalancingState(
        any(ConnectivityState.class), any(SubchannelPicker.class));
  }

  @Test
  public void unknownLbProvider() {
    try {
      xdsClient.deliverCdsUpdate(CLUSTER,
          CdsUpdate.forEds(CLUSTER, EDS_SERVICE_NAME, LRS_SERVER_INFO, 100L, upstreamTlsContext,
                  outlierDetection)
              .lbPolicyConfig(ImmutableMap.of("unknown", ImmutableMap.of("foo", "bar"))).build());
    } catch (Exception e) {
      assertThat(e).hasCauseThat().hasMessageThat().contains("No provider available");
      return;
    }
    fail("Expected the unknown LB to cause an exception");
  }

  @Test
  public void invalidLbConfig() {
    try {
      xdsClient.deliverCdsUpdate(CLUSTER,
          CdsUpdate.forEds(CLUSTER, EDS_SERVICE_NAME, LRS_SERVER_INFO, 100L, upstreamTlsContext,
                  outlierDetection).lbPolicyConfig(
                  ImmutableMap.of("ring_hash_experimental", ImmutableMap.of("minRingSize", "-1")))
              .build());
    } catch (Exception e) {
      assertThat(e).hasCauseThat().hasMessageThat().contains("Unable to parse");
      return;
    }
    fail("Expected the invalid config to cause an exception");
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

  private static void assertDiscoveryMechanism(DiscoveryMechanism instance, String name,
      DiscoveryMechanism.Type type, @Nullable String edsServiceName, @Nullable String dnsHostName,
      @Nullable ServerInfo lrsServerInfo, @Nullable Long maxConcurrentRequests,
      @Nullable UpstreamTlsContext tlsContext, @Nullable OutlierDetection outlierDetection) {
    assertThat(instance.cluster).isEqualTo(name);
    assertThat(instance.type).isEqualTo(type);
    assertThat(instance.edsServiceName).isEqualTo(edsServiceName);
    assertThat(instance.dnsHostName).isEqualTo(dnsHostName);
    assertThat(instance.lrsServerInfo).isEqualTo(lrsServerInfo);
    assertThat(instance.maxConcurrentRequests).isEqualTo(maxConcurrentRequests);
    assertThat(instance.tlsContext).isEqualTo(tlsContext);
    assertThat(instance.outlierDetection).isEqualTo(outlierDetection);
  }

  private final class FakeLoadBalancerProvider extends LoadBalancerProvider {
    private final String policyName;
    private final LoadBalancerProvider configParsingDelegate;

    FakeLoadBalancerProvider(String policyName) {
      this(policyName, null);
    }

    FakeLoadBalancerProvider(String policyName, LoadBalancerProvider configParsingDelegate) {
      this.policyName = policyName;
      this.configParsingDelegate = configParsingDelegate;
    }

    @Override
    public LoadBalancer newLoadBalancer(Helper helper) {
      FakeLoadBalancer balancer = new FakeLoadBalancer(policyName);
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

    @Override
    public NameResolver.ConfigOrError parseLoadBalancingPolicyConfig(
        Map<String, ?> rawLoadBalancingPolicyConfig) {
      if (configParsingDelegate != null) {
        return configParsingDelegate.parseLoadBalancingPolicyConfig(rawLoadBalancingPolicyConfig);
      }
      return super.parseLoadBalancingPolicyConfig(rawLoadBalancingPolicyConfig);
    }
  }

  private final class FakeLoadBalancer extends LoadBalancer {
    private final String name;
    private Object config;
    private Status upstreamError;
    private boolean shutdown;

    FakeLoadBalancer(String name) {
      this.name = name;
    }

    @Override
    public void handleResolvedAddresses(ResolvedAddresses resolvedAddresses) {
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
  }

  private final class FakeXdsClient extends XdsClient {
    // watchers needs to support any non-cyclic shaped graphs
    private final Map<String, List<ResourceWatcher<CdsUpdate>>> watchers = new HashMap<>();

    @Override
    @SuppressWarnings("unchecked")
    <T extends ResourceUpdate> void watchXdsResource(XdsResourceType<T> type, String resourceName,
                          ResourceWatcher<T> watcher) {
      assertThat(type.typeName()).isEqualTo("CDS");
      watchers.computeIfAbsent(resourceName, k -> new ArrayList<>())
          .add((ResourceWatcher<CdsUpdate>)watcher);
    }

    @Override
    @SuppressWarnings("unchecked")
    <T extends ResourceUpdate> void cancelXdsResourceWatch(XdsResourceType<T> type,
                                                           String resourceName,
                                                           ResourceWatcher<T> watcher) {
      assertThat(type.typeName()).isEqualTo("CDS");
      assertThat(watchers).containsKey(resourceName);
      List<ResourceWatcher<CdsUpdate>> watcherList = watchers.get(resourceName);
      assertThat(watcherList.remove(watcher)).isTrue();
      if (watcherList.isEmpty()) {
        watchers.remove(resourceName);
      }
    }

    private void deliverCdsUpdate(String clusterName, CdsUpdate update) {
      if (watchers.containsKey(clusterName)) {
        List<ResourceWatcher<CdsUpdate>> resourceWatchers =
            ImmutableList.copyOf(watchers.get(clusterName));
        resourceWatchers.forEach(w -> w.onChanged(update));
      }
    }

    private void deliverResourceNotExist(String clusterName)  {
      if (watchers.containsKey(clusterName)) {
        ImmutableList.copyOf(watchers.get(clusterName))
            .forEach(w -> w.onResourceDoesNotExist(clusterName));
      }
    }

    private void deliverError(Status error) {
      watchers.values().stream()
          .flatMap(List::stream)
          .forEach(w -> w.onError(error));
    }
  }
}
