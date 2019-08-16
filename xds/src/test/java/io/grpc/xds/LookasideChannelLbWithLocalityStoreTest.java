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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.google.protobuf.UInt32Value;
import io.envoyproxy.envoy.api.v2.ClusterLoadAssignment;
import io.envoyproxy.envoy.api.v2.core.Address;
import io.envoyproxy.envoy.api.v2.core.Locality;
import io.envoyproxy.envoy.api.v2.core.SocketAddress;
import io.envoyproxy.envoy.api.v2.endpoint.Endpoint;
import io.envoyproxy.envoy.api.v2.endpoint.LbEndpoint;
import io.envoyproxy.envoy.api.v2.endpoint.LocalityLbEndpoints;
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
import io.grpc.SynchronizationContext;
import io.grpc.internal.BackoffPolicy;
import io.grpc.xds.LoadReportClientImpl.LoadReportClientFactory;
import io.grpc.xds.LookasideChannelLb.AbstractXdsComms;
import io.grpc.xds.LookasideChannelLb.AdsStreamCallback2;
import io.grpc.xds.LookasideChannelLb.LocalityStoreFactory;
import io.grpc.xds.LookasideChannelLb.LocalityStoreFactoryImpl;
import io.grpc.xds.LookasideChannelLb.XdsCommsFactory;
import io.grpc.xds.XdsComms.AdsStreamCallback;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/**
 * Tests for basic interactions between {@link LookasideChannelLb} and a real {@link LocalityStore},
 * with no intention to cover all branches.
 */
@RunWith(JUnit4.class)
public class LookasideChannelLbWithLocalityStoreTest {
  private static final String BALANCER_NAME = "fakeBalancerName";

  @Rule
  public final MockitoRule mockitoRule = MockitoJUnit.rule();

  @Mock
  private Helper helper;
  @Mock
  private AdsStreamCallback adsStreamCallback;
  @Mock
  private LoadReportClientFactory lrsClientFactory;
  @Mock
  private LoadReportClient lrsClient;
  @Mock
  private XdsCommsFactory xdsCommsFactory;
  @Mock
  private PickSubchannelArgs pickSubchannelArgs;
  @Mock
  private ManagedChannel channel;
  @Mock
  private AbstractXdsComms xdsComms;

  private final SynchronizationContext syncContext = new SynchronizationContext(
      new Thread.UncaughtExceptionHandler() {
        @Override
        public void uncaughtException(Thread t, Throwable e) {
          throw new AssertionError(e);
        }
      });

  private final LoadBalancerRegistry lbRegistry = new LoadBalancerRegistry();
  private final Map<String, LoadBalancer> loadBalancers = new HashMap<>();
  private final Map<String, Helper> childHelpers = new HashMap<>();

  private final LocalityStoreFactory localityStoreFactory = new LocalityStoreFactoryImpl();

  private final LoadBalancerProvider lbProvider = new LoadBalancerProvider() {

    @Override
    public boolean isAvailable() {
      return true;
    }

    @Override
    public int getPriority() {
      return 0;
    }

    @Override
    public String getPolicyName() {
      return "round_robin";
    }

    @Override
    public LoadBalancer newLoadBalancer(Helper helper) {
      LoadBalancer fakeLb = mock(LoadBalancer.class);
      loadBalancers.put(helper.getAuthority(), fakeLb);
      childHelpers.put(helper.getAuthority(), helper);
      return fakeLb;
    }
  };

  private AdsStreamCallback2 adsStreamCallback2;
  private LookasideChannelLb lookasideChannelLb;

  @Before
  public void setUp() {
    lbRegistry.register(lbProvider);

    doReturn(mock(ChannelLogger.class)).when(helper).getChannelLogger();
    doReturn(syncContext).when(helper).getSynchronizationContext();
    doReturn(channel).when(helper).createResolvingOobChannel(BALANCER_NAME);
    doReturn(lrsClient).when(lrsClientFactory).createLoadReportClient(
        same(channel), same(helper), any(BackoffPolicy.Provider.class),
        any(LoadStatsStore.class));
    doReturn(xdsComms).when(xdsCommsFactory).newXdsComms(
        same(channel), any(AdsStreamCallback2.class));

    lookasideChannelLb = new LookasideChannelLb(
        helper, adsStreamCallback, BALANCER_NAME, lrsClientFactory, lbRegistry,
        localityStoreFactory, xdsCommsFactory);

    verify(helper).createResolvingOobChannel(BALANCER_NAME);
    verify(lrsClientFactory).createLoadReportClient(
        same(channel), same(helper), isA(BackoffPolicy.Provider.class), any(LoadStatsStore.class));
    ArgumentCaptor<AdsStreamCallback2> adsStreamCallback2Captor =
        ArgumentCaptor.forClass(AdsStreamCallback2.class);
    verify(xdsCommsFactory).newXdsComms(same(channel), adsStreamCallback2Captor.capture());
    adsStreamCallback2 = adsStreamCallback2Captor.getValue();
    verify(xdsComms).start();
  }

  @After
  public void tearDown() {
    // release timers
    lookasideChannelLb.shutdown();
  }

  @Test
  public void verifyUpdateLocalityStore() {
    EquivalentAddressGroup eag11 =
        new EquivalentAddressGroup(new InetSocketAddress("addr11", 11));
    EquivalentAddressGroup eag12 =
        new EquivalentAddressGroup(new InetSocketAddress("addr12", 12));
    EquivalentAddressGroup eag21 =
        new EquivalentAddressGroup(new InetSocketAddress("addr21", 21));
    EquivalentAddressGroup eag22 =
        new EquivalentAddressGroup(new InetSocketAddress("addr22", 22));

    Locality localityProto1 = Locality.newBuilder()
        .setRegion("region1").setZone("zone1").setSubZone("sz1").build();
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
        .setRegion("region2").setZone("zone2").setSubZone("sz2").build();
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
        .setRegion("region3").setZone("zone3").setSubZone("sz3").build();
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
            .setLoadBalancingWeight(UInt32Value.of(0)))  // weight 0
        .build();

    adsStreamCallback2.onEdsResponse(clusterLoadAssignment);

    assertThat(loadBalancers).hasSize(2);
    assertThat(loadBalancers.keySet()).containsExactly("sz1", "sz2");
    ArgumentCaptor<ResolvedAddresses> resolvedAddressesCaptor1 =
        ArgumentCaptor.forClass(ResolvedAddresses.class);
    verify(loadBalancers.get("sz1")).handleResolvedAddresses(resolvedAddressesCaptor1.capture());
    assertThat(resolvedAddressesCaptor1.getValue().getAddresses()).containsExactly(eag11, eag12);
    ArgumentCaptor<ResolvedAddresses> resolvedAddressesCaptor2 =
        ArgumentCaptor.forClass(ResolvedAddresses.class);
    verify(loadBalancers.get("sz2")).handleResolvedAddresses(resolvedAddressesCaptor2.capture());
    assertThat(resolvedAddressesCaptor2.getValue().getAddresses()).containsExactly(eag21, eag22);

    verify(helper, never()).updateBalancingState(
        any(ConnectivityState.class), any(SubchannelPicker.class));

    // subchannel12 goes to CONNECTING
    final Subchannel subchannel12 = mock(Subchannel.class);
    SubchannelPicker subchannelPicker12 = new SubchannelPicker() {
      @Override
      public PickResult pickSubchannel(PickSubchannelArgs args) {
        return PickResult.withSubchannel(subchannel12);
      }
    };
    childHelpers.get("sz1").updateBalancingState(CONNECTING, subchannelPicker12);
    ArgumentCaptor<SubchannelPicker> subchannelPickerCaptor12 =
        ArgumentCaptor.forClass(SubchannelPicker.class);
    verify(helper).updateBalancingState(same(CONNECTING), subchannelPickerCaptor12.capture());
    assertThat(subchannelPickerCaptor12.getValue().pickSubchannel(pickSubchannelArgs))
        .isEqualTo(PickResult.withNoResult());

    // subchannel21 goes to READY
    final Subchannel subchannel21 = mock(Subchannel.class);
    SubchannelPicker subchannelPicker21 = new SubchannelPicker() {
      @Override
      public PickResult pickSubchannel(PickSubchannelArgs args) {
        return PickResult.withSubchannel(subchannel21);
      }
    };
    childHelpers.get("sz2").updateBalancingState(READY, subchannelPicker21);
    ArgumentCaptor<SubchannelPicker> subchannelPickerCaptor =
        ArgumentCaptor.forClass(null);
    verify(helper).updateBalancingState(same(READY), subchannelPickerCaptor.capture());
    assertThat(subchannelPickerCaptor.getValue().pickSubchannel(pickSubchannelArgs).getSubchannel())
        .isEqualTo(subchannel21);

  }
}
