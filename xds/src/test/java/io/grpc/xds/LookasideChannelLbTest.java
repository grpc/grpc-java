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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.protobuf.UInt32Value;
import io.envoyproxy.envoy.api.v2.ClusterLoadAssignment;
import io.envoyproxy.envoy.api.v2.ClusterLoadAssignment.Policy;
import io.envoyproxy.envoy.api.v2.core.Address;
import io.envoyproxy.envoy.api.v2.core.Locality;
import io.envoyproxy.envoy.api.v2.core.SocketAddress;
import io.envoyproxy.envoy.api.v2.endpoint.Endpoint;
import io.envoyproxy.envoy.api.v2.endpoint.LbEndpoint;
import io.envoyproxy.envoy.api.v2.endpoint.LocalityLbEndpoints;
import io.envoyproxy.envoy.type.FractionalPercent;
import io.envoyproxy.envoy.type.FractionalPercent.DenominatorType;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancerRegistry;
import io.grpc.ManagedChannel;
import io.grpc.internal.BackoffPolicy;
import io.grpc.xds.LoadReportClient.LoadReportCallback;
import io.grpc.xds.LoadReportClientImpl.LoadReportClientFactory;
import io.grpc.xds.LookasideChannelLb.AbstractXdsComms;
import io.grpc.xds.LookasideChannelLb.AdsStreamCallback2;
import io.grpc.xds.LookasideChannelLb.LocalityStoreFactory;
import io.grpc.xds.LookasideChannelLb.XdsCommsFactory;
import io.grpc.xds.XdsComms.AdsStreamCallback;
import io.grpc.xds.XdsComms.DropOverload;
import io.grpc.xds.XdsComms.LocalityInfo;
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
 * Tests for {@link LookasideChannelLb}.
 */
@RunWith(JUnit4.class)
public class LookasideChannelLbTest {

  private static final String BALANCER_NAME = "fakeBalancerName";

  @Rule
  public final MockitoRule mockitoRule = MockitoJUnit.rule();

  @Mock
  private Helper helper;
  @Mock
  private AdsStreamCallback adsStreamCallback;
  @Mock
  private LoadReportClientFactory loadReportClientFactory;
  @Mock
  private LoadReportClient loadReportClient;
  @Mock
  private LocalityStoreFactory localityStoreFactory;
  @Mock
  private LocalityStore localityStore;
  @Mock
  private LoadStatsStore loadStatsStore;
  @Mock
  private XdsCommsFactory xdsCommsFactory;
  private AdsStreamCallback2 adsStreamCallback2;
  @Mock
  private ManagedChannel channel;
  @Mock
  private AbstractXdsComms xdsComms;
  @Captor
  private ArgumentCaptor<ImmutableMap<XdsLocality, LocalityInfo>> localityEndpointsMappingCaptor;

  private LookasideChannelLb lookasideChannelLb;

  @Before
  public void setUp() {
    LoadBalancerRegistry lbRegistry = new LoadBalancerRegistry();

    doReturn(channel).when(helper).createResolvingOobChannel(BALANCER_NAME);
    doReturn(localityStore).when(localityStoreFactory).newLocalityStore(helper, lbRegistry);
    doReturn(loadStatsStore).when(localityStore).getLoadStatsStore();
    doReturn(loadReportClient).when(loadReportClientFactory).createLoadReportClient(
        same(channel), same(helper), any(BackoffPolicy.Provider.class), same(loadStatsStore));
    doReturn(xdsComms).when(xdsCommsFactory).newXdsComms(
        same(channel), any(AdsStreamCallback2.class));

    lookasideChannelLb = new LookasideChannelLb(
        helper, adsStreamCallback, BALANCER_NAME, loadReportClientFactory, lbRegistry,
        localityStoreFactory, xdsCommsFactory);

    verify(helper).createResolvingOobChannel(BALANCER_NAME);
    verify(localityStoreFactory).newLocalityStore(helper, lbRegistry);
    verify(loadReportClientFactory).createLoadReportClient(
        same(channel), same(helper), isA(BackoffPolicy.Provider.class), same(loadStatsStore));
    ArgumentCaptor<AdsStreamCallback2> adsStreamCallback2Captor =
        ArgumentCaptor.forClass(AdsStreamCallback2.class);
    verify(xdsCommsFactory).newXdsComms(same(channel), adsStreamCallback2Captor.capture());
    adsStreamCallback2 = adsStreamCallback2Captor.getValue();
    verify(xdsComms).start();
  }

  @Test
  public void firstAndSecondEdsResponseReceived() {
    verify(adsStreamCallback, never()).onWorking();
    verify(loadReportClient, never()).startLoadReporting(any(LoadReportCallback.class));

    // first EDS response
    adsStreamCallback2.onEdsResponse(ClusterLoadAssignment.newBuilder().build());
    verify(adsStreamCallback).onWorking();
    ArgumentCaptor<LoadReportCallback> loadReportCallbackCaptor =
        ArgumentCaptor.forClass(LoadReportCallback.class);
    verify(loadReportClient).startLoadReporting(loadReportCallbackCaptor.capture());
    LoadReportCallback loadReportCallback = loadReportCallbackCaptor.getValue();

    // second EDS response
    adsStreamCallback2.onEdsResponse(ClusterLoadAssignment.newBuilder().build());
    verify(adsStreamCallback, times(1)).onWorking();
    verify(loadReportClient, times(1)).startLoadReporting(any(LoadReportCallback.class));

    verify(localityStore, never()).updateOobMetricsReportInterval(anyLong());
    loadReportCallback.onReportResponse(1234);
    verify(localityStore).updateOobMetricsReportInterval(1234);

    verify(adsStreamCallback, never()).onError();
  }

  @Test
  public void verfiyDropOverload() {
    verify(localityStore, never()).updateDropPercentage(
        ArgumentMatchers.<ImmutableList<DropOverload>>any());

    adsStreamCallback2.onEdsResponse(ClusterLoadAssignment.newBuilder().build());
    verify(localityStore).updateDropPercentage(eq(ImmutableList.<DropOverload>of()));

    adsStreamCallback2.onEdsResponse(ClusterLoadAssignment.newBuilder()
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
        .build());
    verify(adsStreamCallback, never()).onAllDrop();
    verify(localityStore).updateDropPercentage(ImmutableList.of(
        new DropOverload("cat_1", 300_00),
        new DropOverload("cat_2", 45_00),
        new DropOverload("cat_3", 6789)));


    adsStreamCallback2.onEdsResponse(ClusterLoadAssignment.newBuilder()
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
        .build());
    verify(adsStreamCallback).onAllDrop();
    verify(localityStore).updateDropPercentage(ImmutableList.of(
        new DropOverload("cat_1", 300_00),
        new DropOverload("cat_2", 100_00_00)));

    verify(adsStreamCallback, never()).onError();
  }

  @Test
  public void verifyUpdateLocalityStore() {
    Locality localityProto1 = Locality.newBuilder()
        .setRegion("region1").setZone("zone1").setSubZone("subzone1").build();
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
    Locality localityProto2 = Locality.newBuilder()
        .setRegion("region2").setZone("zone2").setSubZone("subzone2").build();
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
    Locality localityProto3 = Locality.newBuilder()
        .setRegion("region3").setZone("zone3").setSubZone("subzone3").build();
    LbEndpoint endpoint3 = LbEndpoint.newBuilder()
        .setEndpoint(Endpoint.newBuilder()
            .setAddress(Address.newBuilder()
                .setSocketAddress(SocketAddress.newBuilder()
                    .setAddress("addr31").setPortValue(31))))
        .setLoadBalancingWeight(UInt32Value.of(31))
        .build();
    ClusterLoadAssignment clusterLoadAssignment = ClusterLoadAssignment.newBuilder()
            .addEndpoints(LocalityLbEndpoints.newBuilder()
                .setLocality(localityProto1)
                .addLbEndpoints(endpoint11)
                .addLbEndpoints(endpoint12)
                .setLoadBalancingWeight(UInt32Value.of(1)))
            .addEndpoints(LocalityLbEndpoints.newBuilder()
                .setLocality(localityProto2)
                .addLbEndpoints(endpoint21)
                .addLbEndpoints(endpoint22)
                .setLoadBalancingWeight(UInt32Value.of(2)))
            .addEndpoints(LocalityLbEndpoints.newBuilder()
                .setLocality(localityProto3)
                .addLbEndpoints(endpoint3)
                .setLoadBalancingWeight(UInt32Value.of(0)))
            .build();
    adsStreamCallback2.onEdsResponse(clusterLoadAssignment);

    XdsLocality locality1 = XdsLocality.fromLocalityProto(localityProto1);
    LocalityInfo localityInfo1 = new LocalityInfo(
        ImmutableList.of(
            new XdsComms.LbEndpoint(endpoint11),
            new XdsComms.LbEndpoint(endpoint12)),
        1, 0);
    LocalityInfo localityInfo2 = new LocalityInfo(
        ImmutableList.of(
            new XdsComms.LbEndpoint(endpoint21),
            new XdsComms.LbEndpoint(endpoint22)),
        2, 0);
    XdsLocality locality2 = XdsLocality.fromLocalityProto(localityProto2);

    InOrder inOrder = inOrder(localityStore);
    inOrder.verify(localityStore).updateDropPercentage(ImmutableList.<DropOverload>of());
    inOrder.verify(localityStore).updateLocalityStore(localityEndpointsMappingCaptor.capture());
    assertThat(localityEndpointsMappingCaptor.getValue()).containsExactly(
        locality1, localityInfo1, locality2, localityInfo2).inOrder();

    verify(adsStreamCallback, never()).onError();
  }

  @Test
  public void onAdsStreamError() {
    verify(adsStreamCallback, never()).onError();
    adsStreamCallback2.onError();
    verify(adsStreamCallback).onError();
  }

  @Test
  public void shutdown() {
    verify(loadReportClient, never()).stopLoadReporting();
    verify(xdsComms, never()).shutdown();
    verify(channel, never()).shutdown();

    lookasideChannelLb.shutdown();

    verify(loadReportClient).stopLoadReporting();
    verify(xdsComms).shutdown();
    verify(channel).shutdown();
  }
}
