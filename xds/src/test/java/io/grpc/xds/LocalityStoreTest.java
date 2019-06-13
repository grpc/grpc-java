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
import static org.mockito.AdditionalAnswers.returnsFirstArg;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.grpc.ChannelLogger;
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
import io.grpc.LoadBalancerRegistry;
import io.grpc.SynchronizationContext;
import io.grpc.xds.InterLocalityPicker.WeightedChildPicker;
import io.grpc.xds.LocalityStore.LocalityStoreImpl;
import io.grpc.xds.LocalityStore.LocalityStoreImpl.PickerFactory;
import io.grpc.xds.XdsComms.DropOverload;
import io.grpc.xds.XdsComms.LbEndpoint;
import io.grpc.xds.XdsComms.LocalityInfo;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/**
 * Tests for {@link LocalityStore}.
 */
@RunWith(JUnit4.class)
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
  }

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

  private final FakePickerFactory pickerFactory = new FakePickerFactory();

  private final XdsLocality locality1 = new XdsLocality("r1", "z1", "sz1");
  private final XdsLocality locality2 = new XdsLocality("r2", "z2", "sz2");
  private final XdsLocality locality3 = new XdsLocality("r3", "z3", "sz3");
  private final XdsLocality locality4 = new XdsLocality("r4", "z4", "sz4");

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
  @Mock
  private StatsStore statsStore;

  private LocalityStore localityStore;

  @Before
  public void setUp() {
    doReturn(mock(ChannelLogger.class)).when(helper).getChannelLogger();
    doReturn(mock(Subchannel.class)).when(helper).createSubchannel(any(CreateSubchannelArgs.class));
    doReturn(syncContext).when(helper).getSynchronizationContext();
    doAnswer(returnsFirstArg())
        .when(statsStore).interceptPickResult(any(PickResult.class), any(XdsLocality.class));
    lbRegistry.register(lbProvider);
    localityStore = new LocalityStoreImpl(helper, pickerFactory, lbRegistry, random, statsStore);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void updateLocalityStore_updateStatsStoreLocalityTracking() {
    Map<XdsLocality, LocalityInfo> localityInfoMap = new HashMap<>();
    localityInfoMap
        .put(locality1, new LocalityInfo(ImmutableList.of(lbEndpoint11, lbEndpoint12), 1));
    localityInfoMap
        .put(locality2, new LocalityInfo(ImmutableList.of(lbEndpoint21, lbEndpoint22), 2));
    localityStore.updateLocalityStore(localityInfoMap);
    verify(statsStore).addLocality(locality1);
    verify(statsStore).addLocality(locality2);

    localityInfoMap
        .put(locality3, new LocalityInfo(ImmutableList.of(lbEndpoint31, lbEndpoint32), 3));
    localityStore.updateLocalityStore(localityInfoMap);
    verify(statsStore).addLocality(locality3);

    localityInfoMap = ImmutableMap
        .of(locality4, new LocalityInfo(ImmutableList.of(lbEndpoint41, lbEndpoint42), 4));
    localityStore.updateLocalityStore(localityInfoMap);
    verify(statsStore).removeLocality(locality1);
    verify(statsStore).removeLocality(locality2);
    verify(statsStore).removeLocality(locality3);
    verify(statsStore).addLocality(locality4);

    localityStore.updateLocalityStore(Collections.EMPTY_MAP);
    verify(statsStore).removeLocality(locality4);
    verifyNoMoreInteractions(statsStore);
  }

  @Test
  public void updateLocalityStore_interceptPickResultUponPickReadySubchannel() {
    // Simulate receiving two localities.
    LocalityInfo localityInfo1 =
        new LocalityInfo(ImmutableList.of(lbEndpoint11, lbEndpoint12), 1);
    LocalityInfo localityInfo2 =
        new LocalityInfo(ImmutableList.of(lbEndpoint21, lbEndpoint22), 2);
    localityStore.updateLocalityStore(ImmutableMap.of(
        locality1, localityInfo1, locality2, localityInfo2));

    // Two child balancers are created.
    assertThat(loadBalancers).hasSize(2);
    assertThat(pickerFactory.totalReadyLocalities).isEqualTo(0);

    // Simulate picker updates for each of the two localities with dummy pickers.
    final PickResult result1 = PickResult.withNoResult();
    final PickResult result2 = PickResult.withNoResult();
    SubchannelPicker subchannelPicker1 = mock(SubchannelPicker.class);
    SubchannelPicker subchannelPicker2 = mock(SubchannelPicker.class);
    when(subchannelPicker1.pickSubchannel(any(PickSubchannelArgs.class)))
        .thenReturn(result1);
    when(subchannelPicker2.pickSubchannel(any(PickSubchannelArgs.class)))
        .thenReturn(result2);
    childHelpers.get("sz1").updateBalancingState(READY, subchannelPicker1);
    childHelpers.get("sz2").updateBalancingState(READY, subchannelPicker2);

    assertThat(pickerFactory.totalReadyLocalities).isEqualTo(2);
    ArgumentCaptor<SubchannelPicker> interLocalityPickerCaptor = ArgumentCaptor.forClass(null);
    verify(helper, times(2)).updateBalancingState(eq(READY), interLocalityPickerCaptor.capture());
    SubchannelPicker interLocalityPicker = interLocalityPickerCaptor.getValue();
    for (int i = 0; i < pickerFactory.totalReadyLocalities; i++) {
      pickerFactory.nextIndex = i;
      interLocalityPicker.pickSubchannel(pickSubchannelArgs);
    }
    verify(statsStore).interceptPickResult(same(result1), eq(locality1));
    verify(statsStore).interceptPickResult(same(result2), eq(locality2));
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
    Map<XdsLocality, LocalityInfo> localityInfoMap = ImmutableMap.of(
        locality1, localityInfo1, locality2, localityInfo2, locality3, localityInfo3);
    localityStore.updateLocalityStore(localityInfoMap);

    assertThat(loadBalancers).hasSize(3);
    assertThat(loadBalancers.keySet()).containsExactly("sz1", "sz2", "sz3");
    ArgumentCaptor<ResolvedAddresses> resolvedAddressesCaptor1 =
        ArgumentCaptor.forClass(ResolvedAddresses.class);
    verify(loadBalancers.get("sz1")).handleResolvedAddresses(resolvedAddressesCaptor1.capture());
    assertThat(resolvedAddressesCaptor1.getValue().getAddresses()).containsExactly(eag11, eag12);
    ArgumentCaptor<ResolvedAddresses> resolvedAddressesCaptor2 =
        ArgumentCaptor.forClass(ResolvedAddresses.class);
    verify(loadBalancers.get("sz2")).handleResolvedAddresses(resolvedAddressesCaptor2.capture());
    assertThat(resolvedAddressesCaptor2.getValue().getAddresses()).containsExactly(eag21, eag22);
    ArgumentCaptor<ResolvedAddresses> resolvedAddressesCaptor3 =
        ArgumentCaptor.forClass(ResolvedAddresses.class);
    verify(loadBalancers.get("sz3")).handleResolvedAddresses(resolvedAddressesCaptor3.capture());
    assertThat(resolvedAddressesCaptor3.getValue().getAddresses()).containsExactly(eag31, eag32);
    assertThat(pickerFactory.totalReadyLocalities).isEqualTo(0);
    verify(helper, never()).updateBalancingState(
        any(ConnectivityState.class), any(SubchannelPicker.class));

    // subchannel12 goes to CONNECTING
    CreateSubchannelArgs createSubchannelArgs =
        CreateSubchannelArgs.newBuilder().setAddresses(ImmutableList.of(eag12)).build();
    final Subchannel subchannel12 =
        childHelpers.get("sz1").createSubchannel(createSubchannelArgs);
    verify(helper).createSubchannel(createSubchannelArgs);
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
    assertThat(pickerFactory.totalReadyLocalities).isEqualTo(0);
    assertThat(subchannelPickerCaptor12.getValue().pickSubchannel(pickSubchannelArgs))
        .isEqualTo(PickResult.withNoResult());

    // subchannel31 goes to READY
    createSubchannelArgs =
        CreateSubchannelArgs.newBuilder().setAddresses(ImmutableList.of(eag31)).build();
    final Subchannel subchannel31 =
        childHelpers.get("sz3").createSubchannel(createSubchannelArgs);
    verify(helper).createSubchannel(createSubchannelArgs);
    SubchannelPicker subchannelPicker31 = new SubchannelPicker() {
      @Override
      public PickResult pickSubchannel(PickSubchannelArgs args) {
        return PickResult.withSubchannel(subchannel31);
      }
    };
    childHelpers.get("sz3").updateBalancingState(READY, subchannelPicker31);
    ArgumentCaptor<SubchannelPicker> subchannelPickerCaptor31 =
        ArgumentCaptor.forClass(SubchannelPicker.class);
    verify(helper).updateBalancingState(same(READY), subchannelPickerCaptor31.capture());
    assertThat(pickerFactory.totalReadyLocalities).isEqualTo(1);
    assertThat(
            subchannelPickerCaptor31.getValue().pickSubchannel(pickSubchannelArgs).getSubchannel())
        .isEqualTo(subchannel31);

    // subchannel12 goes to READY
    childHelpers.get("sz1").updateBalancingState(READY, subchannelPicker12);
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
    verify(loadBalancers.get("sz3")).shutdown();
    verify(loadBalancers.get("sz2"), times(2))
        .handleResolvedAddresses(resolvedAddressesCaptor2.capture());
    assertThat(resolvedAddressesCaptor2.getValue().getAddresses()).containsExactly(eag21, eag22);
    ArgumentCaptor<ResolvedAddresses> resolvedAddressesCaptor4 =
        ArgumentCaptor.forClass(ResolvedAddresses.class);
    verify(loadBalancers.get("sz4")).handleResolvedAddresses(resolvedAddressesCaptor4.capture());
    assertThat(resolvedAddressesCaptor4.getValue().getAddresses()).containsExactly(eag41, eag42);
    verify(loadBalancers.get("sz1"), times(2))
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
    Map<XdsLocality, LocalityInfo> localityInfoMap = ImmutableMap.of(
        locality1, localityInfo1, locality2, localityInfo2, locality3, localityInfo3);
    localityStore.updateLocalityStore(localityInfoMap);

    assertThat(loadBalancers).hasSize(3);
    ArgumentCaptor<ResolvedAddresses> resolvedAddressesCaptor1 =
        ArgumentCaptor.forClass(ResolvedAddresses.class);
    verify(loadBalancers.get("sz1")).handleResolvedAddresses(resolvedAddressesCaptor1.capture());
    assertThat(resolvedAddressesCaptor1.getValue().getAddresses()).containsExactly(eag11, eag12);
    ArgumentCaptor<ResolvedAddresses> resolvedAddressesCaptor2 =
        ArgumentCaptor.forClass(ResolvedAddresses.class);
    verify(loadBalancers.get("sz2")).handleResolvedAddresses(resolvedAddressesCaptor2.capture());
    assertThat(resolvedAddressesCaptor2.getValue().getAddresses()).containsExactly(eag21, eag22);
    ArgumentCaptor<ResolvedAddresses> resolvedAddressesCaptor3 =
        ArgumentCaptor.forClass(ResolvedAddresses.class);
    verify(loadBalancers.get("sz3")).handleResolvedAddresses(resolvedAddressesCaptor3.capture());
    assertThat(resolvedAddressesCaptor3.getValue().getAddresses()).containsExactly(eag31, eag32);
    assertThat(pickerFactory.totalReadyLocalities).isEqualTo(0);
    ArgumentCaptor<SubchannelPicker> subchannelPickerCaptor =
        ArgumentCaptor.forClass(SubchannelPicker.class);
    verify(helper).updateBalancingState(same(IDLE), subchannelPickerCaptor.capture());

    int times = 0;
    InOrder inOrder = inOrder(statsStore);
    doReturn(365, 1234).when(random).nextInt(1000_000);
    assertThat(subchannelPickerCaptor.getValue().pickSubchannel(pickSubchannelArgs))
        .isEqualTo(PickResult.withNoResult());
    verify(random, times(times += 2)).nextInt(1000_000);
    inOrder.verify(statsStore, never()).recordDroppedRequest(anyString());

    doReturn(366, 1235).when(random).nextInt(1000_000);
    assertThat(subchannelPickerCaptor.getValue().pickSubchannel(pickSubchannelArgs))
        .isEqualTo(PickResult.withNoResult());
    verify(random, times(times += 2)).nextInt(1000_000);
    inOrder.verify(statsStore, never()).recordDroppedRequest(anyString());

    doReturn(364, 1234).when(random).nextInt(1000_000);
    assertThat(subchannelPickerCaptor.getValue().pickSubchannel(pickSubchannelArgs).isDrop())
        .isTrue();
    verify(random, times(times += 1)).nextInt(1000_000);
    inOrder.verify(statsStore).recordDroppedRequest(eq("throttle"));

    doReturn(365, 1233).when(random).nextInt(1000_000);
    assertThat(subchannelPickerCaptor.getValue().pickSubchannel(pickSubchannelArgs).isDrop())
        .isTrue();
    verify(random, times(times += 2)).nextInt(1000_000);
    inOrder.verify(statsStore).recordDroppedRequest(eq("lb"));

    // subchannel12 goes to READY
    CreateSubchannelArgs createSubchannelArgs =
        CreateSubchannelArgs.newBuilder().setAddresses(ImmutableList.of(eag12)).build();
    final Subchannel subchannel12 = childHelpers.get("sz1").createSubchannel(createSubchannelArgs);
    verify(helper).createSubchannel(createSubchannelArgs);
    SubchannelPicker subchannelPicker12 = new SubchannelPicker() {
      @Override
      public PickResult pickSubchannel(PickSubchannelArgs args) {
        return PickResult.withSubchannel(subchannel12);
      }
    };
    childHelpers.get("sz1").updateBalancingState(READY, subchannelPicker12);
    ArgumentCaptor<SubchannelPicker> subchannelPickerCaptor12 =
        ArgumentCaptor.forClass(SubchannelPicker.class);
    verify(helper).updateBalancingState(same(READY), subchannelPickerCaptor12.capture());

    doReturn(365, 1234).when(random).nextInt(1000_000);
    assertThat(subchannelPickerCaptor12.getValue().pickSubchannel(pickSubchannelArgs)
        .getSubchannel()).isEqualTo(subchannel12);
    verify(random, times(times += 2)).nextInt(1000_000);
    inOrder.verify(statsStore, never()).recordDroppedRequest(anyString());

    doReturn(366, 1235).when(random).nextInt(1000_000);
    assertThat(subchannelPickerCaptor12.getValue().pickSubchannel(pickSubchannelArgs)
        .getSubchannel()).isEqualTo(subchannel12);
    verify(random, times(times += 2)).nextInt(1000_000);
    inOrder.verify(statsStore, never()).recordDroppedRequest(anyString());

    doReturn(364, 1234).when(random).nextInt(1000_000);
    assertThat(subchannelPickerCaptor12.getValue().pickSubchannel(pickSubchannelArgs).isDrop())
        .isTrue();
    verify(random, times(times += 1)).nextInt(1000_000);
    inOrder.verify(statsStore).recordDroppedRequest(eq("throttle"));

    doReturn(365, 1233).when(random).nextInt(1000_000);
    assertThat(subchannelPickerCaptor12.getValue().pickSubchannel(pickSubchannelArgs).isDrop())
        .isTrue();
    verify(random, times(times + 2)).nextInt(1000_000);
    inOrder.verify(statsStore).recordDroppedRequest(eq("lb"));
    inOrder.verifyNoMoreInteractions();
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
    Map<XdsLocality, LocalityInfo> localityInfoMap = ImmutableMap.of(
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
    Map<XdsLocality, LocalityInfo> localityInfoMap = ImmutableMap.of(
        locality1, localityInfo1, locality2, localityInfo2);
    localityStore.updateLocalityStore(localityInfoMap);

    assertThat(loadBalancers).hasSize(2);

    localityStore.reset();

    verify(loadBalancers.get("sz1")).shutdown();
    verify(loadBalancers.get("sz2")).shutdown();
    verify(statsStore).removeLocality(locality1);
    verify(statsStore).removeLocality(locality2);
  }
}
