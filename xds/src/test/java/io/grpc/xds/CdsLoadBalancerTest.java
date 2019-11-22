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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
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
import io.grpc.internal.ServiceConfigUtil.LbConfig;
import io.grpc.xds.CdsLoadBalancer.CdsConfig;
import io.grpc.xds.XdsClient.ClusterUpdate;
import io.grpc.xds.XdsClient.ClusterWatcher;
import io.grpc.xds.XdsClient.RefCountedXdsClientObjectPool;
import io.grpc.xds.XdsClient.XdsClientFactory;
import io.grpc.xds.XdsLoadBalancerProvider.XdsConfig;
import java.util.ArrayDeque;
import java.util.Deque;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Tests for {@link CdsLoadBalancer}.
 */
@RunWith(JUnit4.class)
public class CdsLoadBalancerTest {

  @Rule
  public final ExpectedException thrown = ExpectedException.none();

  private final RefCountedXdsClientObjectPool xdsClientRef = new RefCountedXdsClientObjectPool(
      new XdsClientFactory() {
        @Override
        XdsClient createXdsClient() {
          xdsClient = mock(XdsClient.class);
          return xdsClient;
        }
      }
  );
  private Deque<LoadBalancer> edsLoadBalancers = new ArrayDeque<>();
  private Deque<Helper> edsLbHelpers = new ArrayDeque<>();

  @Mock
  private Helper helper;
  @Mock
  private ChannelLogger channelLogger;

  private LoadBalancer cdsLoadBalancer;
  private XdsClient xdsClient;


  @Before
  public void setUp() {
    MockitoAnnotations.initMocks(this);

    doReturn(channelLogger).when(helper).getChannelLogger();

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
        return "xds_experimental";
      }

      @Override
      public LoadBalancer newLoadBalancer(Helper helper) {
        edsLbHelpers.add(helper);
        LoadBalancer edsLoadBalancer = mock(LoadBalancer.class);
        edsLoadBalancers.add(edsLoadBalancer);
        return edsLoadBalancer;
      }
    });

    cdsLoadBalancer = new CdsLoadBalancer(helper, lbRegistry);
  }

  @Test
  public void canHandleEmptyAddressListFromNameResolution() {
    assertThat(cdsLoadBalancer.canHandleEmptyAddressListFromNameResolution()).isTrue();
  }

  @Test
  public void missingCdsConfig() {
    ResolvedAddresses resolvedAddresses = ResolvedAddresses.newBuilder()
        .setAddresses(ImmutableList.<EquivalentAddressGroup>of())
        .setAttributes(Attributes.newBuilder()
            .set(XdsAttributes.XDS_CLIENT_REF, xdsClientRef).build())
        .build();

    thrown.expect(IllegalArgumentException.class);
    thrown.expectMessage("Expecting a CDS LB config, but the actual LB config is 'null'");
    cdsLoadBalancer.handleResolvedAddresses(resolvedAddresses);
  }

  @Test
  public void invalidConfigType() {
    Object invalidConfig = new Object();
    ResolvedAddresses resolvedAddresses = ResolvedAddresses.newBuilder()
        .setAddresses(ImmutableList.<EquivalentAddressGroup>of())
        .setAttributes(Attributes.newBuilder()
            .set(XdsAttributes.XDS_CLIENT_REF, xdsClientRef).build())
        .setLoadBalancingPolicyConfig(invalidConfig)
        .build();

    thrown.expect(IllegalArgumentException.class);
    thrown.expectMessage("Expecting a CDS LB config, but the actual LB config is '"
        + invalidConfig + "'");
    cdsLoadBalancer.handleResolvedAddresses(resolvedAddresses);
  }

  @Test
  public void handleCdsConfigs() {
    assertThat(xdsClient).isNull();

    CdsConfig cdsConfig1 = new CdsConfig("foo.googleapis.com");
    ResolvedAddresses resolvedAddresses1 = ResolvedAddresses.newBuilder()
        .setAddresses(ImmutableList.<EquivalentAddressGroup>of())
        .setAttributes(Attributes.newBuilder()
            .set(XdsAttributes.XDS_CLIENT_REF, xdsClientRef).build())
        .setLoadBalancingPolicyConfig(cdsConfig1)
        .build();
    cdsLoadBalancer.handleResolvedAddresses(resolvedAddresses1);

    ArgumentCaptor<ClusterWatcher> clusterWatcherCaptor1 = ArgumentCaptor.forClass(null);
    verify(xdsClient).watchClusterData(eq("foo.googleapis.com"), clusterWatcherCaptor1.capture());
    assertThat(edsLbHelpers).hasSize(1);
    assertThat(edsLoadBalancers).hasSize(1);
    Helper edsLbHelper1 = edsLbHelpers.poll();
    LoadBalancer edsLoadBalancer1 = edsLoadBalancers.poll();

    ClusterWatcher clusterWatcher1 = clusterWatcherCaptor1.getValue();
    clusterWatcher1.onClusterChanged(
        ClusterUpdate.newBuilder()
            .setClusterName("foo.googleapis.com")
            .setEdsServiceName("edsServiceFoo.googleapis.com")
            .setLbPolicy("round_robin")
            .setEnableLrs(false)
            .build());

    ArgumentCaptor<ResolvedAddresses> resolvedAddressesCaptor1 = ArgumentCaptor.forClass(null);
    verify(edsLoadBalancer1).handleResolvedAddresses(resolvedAddressesCaptor1.capture());
    XdsConfig expectedXdsConfig = new XdsConfig(
        null,
        new LbConfig("round_robin", ImmutableMap.<String, Object>of()),
        null,
        "edsServiceFoo.googleapis.com",
        null);
    ResolvedAddresses resolvedAddressesFoo = resolvedAddressesCaptor1.getValue();
    LoadStatsStore loadStatsStoreFoo = resolvedAddressesFoo.getAttributes()
        .get(XdsAttributes.LOAD_STATS_STORE_REF);
    assertThat(resolvedAddressesFoo.getLoadBalancingPolicyConfig()).isEqualTo(expectedXdsConfig);
    assertThat(resolvedAddressesFoo.getAttributes().get(XdsAttributes.XDS_CLIENT_REF))
        .isSameInstanceAs(xdsClientRef);
    assertThat(resolvedAddressesFoo.getAttributes().get(XdsAttributes.LOAD_STATS_STORE_REF))
        .isNotNull();
    verify(xdsClient, never()).reportClientStats(
        anyString(), anyString(), any(LoadStatsStore.class));

    SubchannelPicker picker1 = mock(SubchannelPicker.class);
    edsLbHelper1.updateBalancingState(ConnectivityState.READY, picker1);
    verify(helper).updateBalancingState(ConnectivityState.READY, picker1);

    CdsConfig cdsConfig2 = new CdsConfig("bar.googleapis.com");
    ResolvedAddresses resolvedAddresses2 = ResolvedAddresses.newBuilder()
        .setAddresses(ImmutableList.<EquivalentAddressGroup>of())
        .setAttributes(Attributes.newBuilder()
            .set(XdsAttributes.XDS_CLIENT_REF, xdsClientRef).build())
        .setLoadBalancingPolicyConfig(cdsConfig2)
        .build();
    cdsLoadBalancer.handleResolvedAddresses(resolvedAddresses2);

    ArgumentCaptor<ClusterWatcher> clusterWatcherCaptor2 = ArgumentCaptor.forClass(null);
    verify(xdsClient).watchClusterData(eq("bar.googleapis.com"), clusterWatcherCaptor2.capture());
    verify(xdsClient).cancelClusterDataWatch("foo.googleapis.com", clusterWatcher1);
    assertThat(edsLbHelpers).hasSize(1);
    assertThat(edsLoadBalancers).hasSize(1);
    Helper edsLbHelper2 = edsLbHelpers.poll();
    LoadBalancer edsLoadBalancer2 = edsLoadBalancers.poll();

    ClusterWatcher clusterWatcher2 = clusterWatcherCaptor2.getValue();
    clusterWatcher2.onClusterChanged(
        ClusterUpdate.newBuilder()
            .setClusterName("bar.googleapis.com")
            .setEdsServiceName("edsServiceBar.googleapis.com")
            .setLbPolicy("round_robin")
            .setEnableLrs(true)
            .setLrsServerName("lrsBar.googleapis.com")
            .build());

    ArgumentCaptor<ResolvedAddresses> resolvedAddressesCaptor2 = ArgumentCaptor.forClass(null);
    verify(edsLoadBalancer2).handleResolvedAddresses(resolvedAddressesCaptor2.capture());
    expectedXdsConfig = new XdsConfig(
        null,
        new LbConfig("round_robin", ImmutableMap.<String, Object>of()),
        null,
        "edsServiceBar.googleapis.com",
        "lrsBar.googleapis.com");
    ResolvedAddresses resolvedAddressesBar = resolvedAddressesCaptor2.getValue();
    assertThat(resolvedAddressesBar.getLoadBalancingPolicyConfig()).isEqualTo(expectedXdsConfig);
    assertThat(resolvedAddressesBar.getAttributes().get(XdsAttributes.XDS_CLIENT_REF))
        .isSameInstanceAs(xdsClientRef);
    LoadStatsStore loadStatsStoreBar = resolvedAddressesBar.getAttributes()
        .get(XdsAttributes.LOAD_STATS_STORE_REF);
    assertThat(loadStatsStoreBar).isNotSameInstanceAs(loadStatsStoreFoo);

    SubchannelPicker picker2 = mock(SubchannelPicker.class);
    edsLbHelper2.updateBalancingState(ConnectivityState.CONNECTING, picker2);
    verify(helper, never()).updateBalancingState(ConnectivityState.CONNECTING, picker2);
    verify(edsLoadBalancer1, never()).shutdown();

    picker2 = mock(SubchannelPicker.class);
    edsLbHelper2.updateBalancingState(ConnectivityState.READY, picker2);
    verify(helper).updateBalancingState(ConnectivityState.READY, picker2);
    verify(edsLoadBalancer1).shutdown();

    cdsLoadBalancer.shutdown();
    verify(edsLoadBalancer2).shutdown();
    assertThat(xdsClientRef.xdsClient).isNull();

    clusterWatcher2.onClusterChanged(
        ClusterUpdate.newBuilder()
            .setClusterName("bar.googleapis.com")
            .setEdsServiceName("edsServiceBar2.googleapis.com")
            .setLbPolicy("round_robin")
            .setEnableLrs(false)
            .build());
    verify(edsLoadBalancer2, times(2)).handleResolvedAddresses(resolvedAddressesCaptor2.capture());
    expectedXdsConfig = new XdsConfig(
        null,
        new LbConfig("round_robin", ImmutableMap.<String, Object>of()),
        null,
        "edsServiceBar2.googleapis.com",
        null);
    ResolvedAddresses resolvedAddressesBar2 = resolvedAddressesCaptor2.getValue();
    assertThat(resolvedAddressesBar2.getLoadBalancingPolicyConfig()).isEqualTo(expectedXdsConfig);
    LoadStatsStore loadStatsStoreBar2 = resolvedAddressesBar2.getAttributes()
        .get(XdsAttributes.LOAD_STATS_STORE_REF);
    // LoadStatsStore should be reused for the same cluster.
    assertThat(loadStatsStoreBar2).isSameInstanceAs(loadStatsStoreBar);
  }
}
