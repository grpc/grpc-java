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
import static io.grpc.ConnectivityState.IDLE;
import static io.grpc.ConnectivityState.READY;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
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
import io.grpc.LoadBalancer.PickResult;
import io.grpc.LoadBalancer.PickSubchannelArgs;
import io.grpc.LoadBalancer.ResolvedAddresses;
import io.grpc.LoadBalancer.Subchannel;
import io.grpc.LoadBalancer.SubchannelPicker;
import io.grpc.LoadBalancerProvider;
import io.grpc.LoadBalancerRegistry;
import io.grpc.xds.InterLocalityPicker.WeightedChildPicker;
import io.grpc.xds.LocalityStore.LocalityStoreImpl;
import io.grpc.xds.LocalityStore.LocalityStoreImpl.PickerFactory;
import io.grpc.xds.XdsComms.DropOverload;
import io.grpc.xds.XdsComms.LbEndpoint;
import io.grpc.xds.XdsComms.Locality;
import io.grpc.xds.XdsComms.LocalityInfo;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/**
 * Tests for {@link LocalityStore}.
 */
@RunWith(JUnit4.class)
// TODO(dapengzhang0): remove this after switching to Subchannel.start().
@SuppressWarnings("deprecation")
public class LocalityStoreTest {
  @Rule
  public final MockitoRule mockitoRule = MockitoJUnit.rule();

  private static final class FakePickerFactory implements PickerFactory {
    int totalReadyLocalities;
    int nextIndex;

    @Override
    public SubchannelPicker picker(final List<WeightedChildPicker> childPickers) {
      totalReadyLocalities = childPickers.size();

      return new SubchannelPicker() {
        @Override
        public PickResult pickSubchannel(PickSubchannelArgs args) {
          return childPickers.get(nextIndex).getPicker().pickSubchannel(args);
        }
      };
    }

    void setNextIndex(int nextIndex) {
      this.nextIndex = nextIndex;
    }
  }

  private final LoadBalancerRegistry lbRegistry = new LoadBalancerRegistry();
  private final List<LoadBalancer> loadBalancers = new ArrayList<>();
  private final List<Helper> helpers = new ArrayList<>();

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
      loadBalancers.add(fakeLb);
      helpers.add(helper);
      return fakeLb;
    }
  };

  private final FakePickerFactory pickerFactory = new FakePickerFactory();

  private final Locality locality1 = new Locality("r1", "z1", "sz1");
  private final Locality locality2 = new Locality("r2", "z2", "sz2");
  private final Locality locality3 = new Locality("r3", "z3", "sz3");
  private final Locality locality4 = new Locality("r4", "z4", "sz4");

  private final EquivalentAddressGroup eag11 =
      new EquivalentAddressGroup(new InetSocketAddress("addr11", 11));
  private final EquivalentAddressGroup eag12 =
      new EquivalentAddressGroup(new InetSocketAddress("addr12", 12));
  private final EquivalentAddressGroup eag21 =
      new EquivalentAddressGroup(new InetSocketAddress("addr21", 21));
  private final EquivalentAddressGroup eag22 =
      new EquivalentAddressGroup(new InetSocketAddress("addr22", 22));
  private final EquivalentAddressGroup eag31 =
      new EquivalentAddressGroup(new InetSocketAddress("addr31", 31));
  private final EquivalentAddressGroup eag32 =
      new EquivalentAddressGroup(new InetSocketAddress("addr32", 32));
  private final EquivalentAddressGroup eag41 =
      new EquivalentAddressGroup(new InetSocketAddress("addr41", 41));
  private final EquivalentAddressGroup eag42 =
      new EquivalentAddressGroup(new InetSocketAddress("addr42", 42));

  private final LbEndpoint lbEndpoint11 = new LbEndpoint(eag11, 11);
  private final LbEndpoint lbEndpoint12 = new LbEndpoint(eag12, 12);
  private final LbEndpoint lbEndpoint21 = new LbEndpoint(eag21, 21);
  private final LbEndpoint lbEndpoint22 = new LbEndpoint(eag22, 22);
  private final LbEndpoint lbEndpoint31 = new LbEndpoint(eag31, 31);
  private final LbEndpoint lbEndpoint32 = new LbEndpoint(eag32, 32);
  private final LbEndpoint lbEndpoint41 = new LbEndpoint(eag41, 41);
  private final LbEndpoint lbEndpoint42 = new LbEndpoint(eag42, 42);

  @Mock
  private Helper helper;
  @Mock
  private PickSubchannelArgs pickSubchannelArgs;
  @Mock
  private ThreadSafeRandom random;

  private LocalityStore localityStore;

  @Before
  public void setUp() {
    doReturn(mock(ChannelLogger.class)).when(helper).getChannelLogger();
    doReturn(mock(Subchannel.class)).when(helper).createSubchannel(
        ArgumentMatchers.<EquivalentAddressGroup>anyList(), any(Attributes.class));
    lbRegistry.register(lbProvider);
    localityStore = new LocalityStoreImpl(helper, pickerFactory, lbRegistry, random);
  }

  @Test
  public void updateLoaclityStore_withEmptyDropList() {
    localityStore.updateDropPercentage(ImmutableList.<DropOverload>of());
    LocalityInfo localityInfo1 =
        new LocalityInfo(ImmutableList.of(lbEndpoint11, lbEndpoint12), 1);
    LocalityInfo localityInfo2 =
        new LocalityInfo(ImmutableList.of(lbEndpoint21, lbEndpoint22), 2);
    LocalityInfo localityInfo3 =
        new LocalityInfo(ImmutableList.of(lbEndpoint31, lbEndpoint32), 3);
    Map<Locality, LocalityInfo> localityInfoMap = ImmutableMap.of(
        locality1, localityInfo1, locality2, localityInfo2, locality3, localityInfo3);
    localityStore.updateLocalityStore(localityInfoMap);

    assertThat(loadBalancers).hasSize(3);
    ArgumentCaptor<ResolvedAddresses> resolvedAddressesCaptor1 =
        ArgumentCaptor.forClass(ResolvedAddresses.class);
    verify(loadBalancers.get(0)).handleResolvedAddresses(resolvedAddressesCaptor1.capture());
    assertThat(resolvedAddressesCaptor1.getValue().getAddresses()).containsExactly(eag11, eag12);
    ArgumentCaptor<ResolvedAddresses> resolvedAddressesCaptor2 =
        ArgumentCaptor.forClass(ResolvedAddresses.class);
    verify(loadBalancers.get(1)).handleResolvedAddresses(resolvedAddressesCaptor2.capture());
    assertThat(resolvedAddressesCaptor2.getValue().getAddresses()).containsExactly(eag21, eag22);
    ArgumentCaptor<ResolvedAddresses> resolvedAddressesCaptor3 =
        ArgumentCaptor.forClass(ResolvedAddresses.class);
    verify(loadBalancers.get(2)).handleResolvedAddresses(resolvedAddressesCaptor3.capture());
    assertThat(resolvedAddressesCaptor3.getValue().getAddresses()).containsExactly(eag31, eag32);
    assertThat(pickerFactory.totalReadyLocalities).isEqualTo(0);
    verify(helper, never()).updateBalancingState(
        any(ConnectivityState.class), any(SubchannelPicker.class));

    // subchannel12 goes to CONNECTING
    final Subchannel subchannel12 =
        helpers.get(0).createSubchannel(ImmutableList.of(eag12), Attributes.EMPTY);
    verify(helper).createSubchannel(ImmutableList.of(eag12), Attributes.EMPTY);
    SubchannelPicker subchannelPicker12 = new SubchannelPicker() {
      @Override
      public PickResult pickSubchannel(PickSubchannelArgs args) {
        return PickResult.withSubchannel(subchannel12);
      }
    };
    helpers.get(0).updateBalancingState(CONNECTING, subchannelPicker12);
    ArgumentCaptor<SubchannelPicker> subchannelPickerCaptor12 =
        ArgumentCaptor.forClass(SubchannelPicker.class);
    verify(helper).updateBalancingState(same(CONNECTING), subchannelPickerCaptor12.capture());
    assertThat(pickerFactory.totalReadyLocalities).isEqualTo(0);
    assertThat(subchannelPickerCaptor12.getValue().pickSubchannel(pickSubchannelArgs))
        .isEqualTo(PickResult.withNoResult());

    // subchannel31 goes to READY
    final Subchannel subchannel31 =
        helpers.get(2).createSubchannel(ImmutableList.of(eag31), Attributes.EMPTY);
    verify(helper).createSubchannel(ImmutableList.of(eag31), Attributes.EMPTY);
    SubchannelPicker subchannelPicker31 = new SubchannelPicker() {
      @Override
      public PickResult pickSubchannel(PickSubchannelArgs args) {
        return PickResult.withSubchannel(subchannel31);
      }
    };
    helpers.get(2).updateBalancingState(READY, subchannelPicker31);
    ArgumentCaptor<SubchannelPicker> subchannelPickerCaptor31 =
        ArgumentCaptor.forClass(SubchannelPicker.class);
    verify(helper).updateBalancingState(same(READY), subchannelPickerCaptor31.capture());
    assertThat(pickerFactory.totalReadyLocalities).isEqualTo(1);
    assertThat(
            subchannelPickerCaptor31.getValue().pickSubchannel(pickSubchannelArgs).getSubchannel())
        .isEqualTo(subchannel31);

    // subchannel12 goes to READY
    helpers.get(0).updateBalancingState(READY, subchannelPicker12);
    verify(helper, times(2)).updateBalancingState(same(READY), subchannelPickerCaptor12.capture());
    assertThat(pickerFactory.totalReadyLocalities).isEqualTo(2);
    pickerFactory.nextIndex = 0;
    assertThat(
            subchannelPickerCaptor12.getValue().pickSubchannel(pickSubchannelArgs).getSubchannel())
        .isEqualTo(subchannel12);

    // update with new addressed
    localityInfo1 =
        new LocalityInfo(ImmutableList.of(lbEndpoint11), 1);
    LocalityInfo localityInfo4 =
        new LocalityInfo(ImmutableList.of(lbEndpoint41, lbEndpoint42), 4);
    localityInfoMap = ImmutableMap.of(
        locality2, localityInfo2, locality4, localityInfo4, locality1, localityInfo1);
    localityStore.updateLocalityStore(localityInfoMap);

    assertThat(loadBalancers).hasSize(4);
    verify(loadBalancers.get(2)).shutdown();
    verify(loadBalancers.get(1), times(2))
        .handleResolvedAddresses(resolvedAddressesCaptor2.capture());
    assertThat(resolvedAddressesCaptor2.getValue().getAddresses()).containsExactly(eag21, eag22);
    ArgumentCaptor<ResolvedAddresses> resolvedAddressesCaptor4 =
        ArgumentCaptor.forClass(ResolvedAddresses.class);
    verify(loadBalancers.get(3)).handleResolvedAddresses(resolvedAddressesCaptor4.capture());
    assertThat(resolvedAddressesCaptor4.getValue().getAddresses()).containsExactly(eag41, eag42);
    verify(loadBalancers.get(0), times(2))
        .handleResolvedAddresses(resolvedAddressesCaptor1.capture());
    assertThat(resolvedAddressesCaptor1.getValue().getAddresses()).containsExactly(eag11);
    assertThat(pickerFactory.totalReadyLocalities).isEqualTo(1);

    verify(random, never()).nextInt(1000_000);
  }

  @Test
  public void updateLoaclityStore_withDrop() {
    localityStore.updateDropPercentage(ImmutableList.of(
        new DropOverload("throttle", 365),
        new DropOverload("lb", 1234)));
    LocalityInfo localityInfo1 =
        new LocalityInfo(ImmutableList.of(lbEndpoint11, lbEndpoint12), 1);
    LocalityInfo localityInfo2 =
        new LocalityInfo(ImmutableList.of(lbEndpoint21, lbEndpoint22), 2);
    LocalityInfo localityInfo3 =
        new LocalityInfo(ImmutableList.of(lbEndpoint31, lbEndpoint32), 3);
    Map<Locality, LocalityInfo> localityInfoMap = ImmutableMap.of(
        locality1, localityInfo1, locality2, localityInfo2, locality3, localityInfo3);
    localityStore.updateLocalityStore(localityInfoMap);

    assertThat(loadBalancers).hasSize(3);
    ArgumentCaptor<ResolvedAddresses> resolvedAddressesCaptor1 =
        ArgumentCaptor.forClass(ResolvedAddresses.class);
    verify(loadBalancers.get(0)).handleResolvedAddresses(resolvedAddressesCaptor1.capture());
    assertThat(resolvedAddressesCaptor1.getValue().getAddresses()).containsExactly(eag11, eag12);
    ArgumentCaptor<ResolvedAddresses> resolvedAddressesCaptor2 =
        ArgumentCaptor.forClass(ResolvedAddresses.class);
    verify(loadBalancers.get(1)).handleResolvedAddresses(resolvedAddressesCaptor2.capture());
    assertThat(resolvedAddressesCaptor2.getValue().getAddresses()).containsExactly(eag21, eag22);
    ArgumentCaptor<ResolvedAddresses> resolvedAddressesCaptor3 =
        ArgumentCaptor.forClass(ResolvedAddresses.class);
    verify(loadBalancers.get(2)).handleResolvedAddresses(resolvedAddressesCaptor3.capture());
    assertThat(resolvedAddressesCaptor3.getValue().getAddresses()).containsExactly(eag31, eag32);
    assertThat(pickerFactory.totalReadyLocalities).isEqualTo(0);
    ArgumentCaptor<SubchannelPicker> subchannelPickerCaptor =
        ArgumentCaptor.forClass(SubchannelPicker.class);
    verify(helper).updateBalancingState(same(IDLE), subchannelPickerCaptor.capture());

    int times = 0;
    doReturn(365, 1234).when(random).nextInt(1000_000);
    assertThat(subchannelPickerCaptor.getValue().pickSubchannel(pickSubchannelArgs))
        .isEqualTo(PickResult.withNoResult());
    verify(random, times(times += 2)).nextInt(1000_000);

    doReturn(366, 1235).when(random).nextInt(1000_000);
    assertThat(subchannelPickerCaptor.getValue().pickSubchannel(pickSubchannelArgs))
        .isEqualTo(PickResult.withNoResult());
    verify(random, times(times += 2)).nextInt(1000_000);

    doReturn(364, 1234).when(random).nextInt(1000_000);
    assertThat(subchannelPickerCaptor.getValue().pickSubchannel(pickSubchannelArgs).isDrop())
        .isTrue();
    verify(random, times(times += 1)).nextInt(1000_000);

    doReturn(365, 1233).when(random).nextInt(1000_000);
    assertThat(subchannelPickerCaptor.getValue().pickSubchannel(pickSubchannelArgs).isDrop())
        .isTrue();
    verify(random, times(times += 2)).nextInt(1000_000);

    // subchannel12 goes to READY
    final Subchannel subchannel12 =
        helpers.get(0).createSubchannel(ImmutableList.of(eag12), Attributes.EMPTY);
    verify(helper).createSubchannel(ImmutableList.of(eag12), Attributes.EMPTY);
    SubchannelPicker subchannelPicker12 = new SubchannelPicker() {
      @Override
      public PickResult pickSubchannel(PickSubchannelArgs args) {
        return PickResult.withSubchannel(subchannel12);
      }
    };
    helpers.get(0).updateBalancingState(READY, subchannelPicker12);
    ArgumentCaptor<SubchannelPicker> subchannelPickerCaptor12 =
        ArgumentCaptor.forClass(SubchannelPicker.class);
    verify(helper).updateBalancingState(same(READY), subchannelPickerCaptor12.capture());

    doReturn(365, 1234).when(random).nextInt(1000_000);
    assertThat(subchannelPickerCaptor12.getValue().pickSubchannel(pickSubchannelArgs)
        .getSubchannel()).isEqualTo(subchannel12);
    verify(random, times(times += 2)).nextInt(1000_000);

    doReturn(366, 1235).when(random).nextInt(1000_000);
    assertThat(subchannelPickerCaptor12.getValue().pickSubchannel(pickSubchannelArgs)
        .getSubchannel()).isEqualTo(subchannel12);
    verify(random, times(times += 2)).nextInt(1000_000);

    doReturn(364, 1234).when(random).nextInt(1000_000);
    assertThat(subchannelPickerCaptor12.getValue().pickSubchannel(pickSubchannelArgs).isDrop())
        .isTrue();
    verify(random, times(times += 1)).nextInt(1000_000);

    doReturn(365, 1233).when(random).nextInt(1000_000);
    assertThat(subchannelPickerCaptor12.getValue().pickSubchannel(pickSubchannelArgs).isDrop())
        .isTrue();
    verify(random, times(times += 2)).nextInt(1000_000);
  }

  @Test
  public void updateLoaclityStore_withAllDropBeforeLocalityUpdateConnectivityState() {
    localityStore.updateDropPercentage(ImmutableList.of(
        new DropOverload("throttle", 365),
        new DropOverload("lb", 1000_000)));
    LocalityInfo localityInfo1 =
        new LocalityInfo(ImmutableList.of(lbEndpoint11, lbEndpoint12), 1);
    LocalityInfo localityInfo2 =
        new LocalityInfo(ImmutableList.of(lbEndpoint21, lbEndpoint22), 2);
    LocalityInfo localityInfo3 =
        new LocalityInfo(ImmutableList.of(lbEndpoint31, lbEndpoint32), 3);
    Map<Locality, LocalityInfo> localityInfoMap = ImmutableMap.of(
        locality1, localityInfo1, locality2, localityInfo2, locality3, localityInfo3);
    localityStore.updateLocalityStore(localityInfoMap);

    ArgumentCaptor<SubchannelPicker> subchannelPickerCaptor =
        ArgumentCaptor.forClass(SubchannelPicker.class);
    verify(helper).updateBalancingState(same(IDLE), subchannelPickerCaptor.capture());
    doReturn(999_999).when(random).nextInt(1000_000);
    assertThat(subchannelPickerCaptor.getValue().pickSubchannel(pickSubchannelArgs).isDrop())
        .isTrue();
    verify(random, times(2)).nextInt(1000_000);
  }

  @Test
  public void reset() {
    LocalityInfo localityInfo1 =
        new LocalityInfo(ImmutableList.of(lbEndpoint11, lbEndpoint12), 1);
    LocalityInfo localityInfo2 =
        new LocalityInfo(ImmutableList.of(lbEndpoint21, lbEndpoint22), 2);
    Map<Locality, LocalityInfo> localityInfoMap = ImmutableMap.of(
        locality1, localityInfo1, locality2, localityInfo2);
    localityStore.updateLocalityStore(localityInfoMap);

    assertThat(loadBalancers).hasSize(2);

    localityStore.reset();

    verify(loadBalancers.get(0)).shutdown();
    verify(loadBalancers.get(1)).shutdown();
  }
}
