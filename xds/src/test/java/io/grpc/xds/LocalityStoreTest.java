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

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.truth.Truth.assertThat;
import static io.grpc.ConnectivityState.CONNECTING;
import static io.grpc.ConnectivityState.READY;
import static io.grpc.ConnectivityState.TRANSIENT_FAILURE;
import static io.grpc.Status.UNAVAILABLE;
import static io.grpc.xds.XdsSubchannelPickers.BUFFER_PICKER;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import io.envoyproxy.envoy.api.v2.endpoint.ClusterStats;
import io.grpc.ChannelLogger;
import io.grpc.ClientStreamTracer;
import io.grpc.ConnectivityState;
import io.grpc.EquivalentAddressGroup;
import io.grpc.InternalLogId;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancer.PickResult;
import io.grpc.LoadBalancer.PickSubchannelArgs;
import io.grpc.LoadBalancer.ResolvedAddresses;
import io.grpc.LoadBalancer.Subchannel;
import io.grpc.LoadBalancer.SubchannelPicker;
import io.grpc.LoadBalancerProvider;
import io.grpc.LoadBalancerRegistry;
import io.grpc.Status;
import io.grpc.SynchronizationContext;
import io.grpc.internal.FakeClock;
import io.grpc.internal.FakeClock.ScheduledTask;
import io.grpc.internal.FakeClock.TaskFilter;
import io.grpc.xds.ClientLoadCounter.LoadRecordingStreamTracerFactory;
import io.grpc.xds.ClientLoadCounter.MetricsRecordingListener;
import io.grpc.xds.EnvoyProtoData.DropOverload;
import io.grpc.xds.EnvoyProtoData.LbEndpoint;
import io.grpc.xds.EnvoyProtoData.Locality;
import io.grpc.xds.EnvoyProtoData.LocalityLbEndpoints;
import io.grpc.xds.InterLocalityPicker.WeightedChildPicker;
import io.grpc.xds.LocalityStore.LocalityStoreImpl;
import io.grpc.xds.LocalityStore.LocalityStoreImpl.PickerFactory;
import io.grpc.xds.OrcaOobUtil.OrcaOobReportListener;
import io.grpc.xds.OrcaOobUtil.OrcaReportingConfig;
import io.grpc.xds.OrcaOobUtil.OrcaReportingHelperWrapper;
import io.grpc.xds.OrcaPerRequestUtil.OrcaPerRequestReportListener;
import io.grpc.xds.XdsSubchannelPickers.ErrorPicker;
import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatcher;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.mockito.stubbing.Answer;

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
    List<WeightedChildPicker> perLocalitiesPickers;

    @Override
    public SubchannelPicker picker(final List<WeightedChildPicker> childPickers) {
      totalReadyLocalities = childPickers.size();
      perLocalitiesPickers = Collections.unmodifiableList(childPickers);

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

  private final InternalLogId logId = InternalLogId.allocate("locality-store-test", null);
  private final LoadBalancerRegistry lbRegistry = new LoadBalancerRegistry();
  private final Map<String, LoadBalancer> loadBalancers = new HashMap<>();
  private final Map<String, Helper> childHelpers = new HashMap<>();
  private final Map<String, FakeOrcaReportingHelperWrapper> childHelperWrappers = new HashMap<>();
  private final FakeClock fakeClock = new FakeClock();

  private final TaskFilter deactivationTaskFilter = new TaskFilter() {
    @Override
    public boolean shouldAccept(Runnable runnable) {
      return runnable.toString().contains("DeletionTask");
    }
  };

  private final TaskFilter failOverTaskFilter = new TaskFilter() {
    @Override
    public boolean shouldAccept(Runnable runnable) {
      return runnable.toString().contains("FailOverTask");
    }
  };

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

  private final LbEndpoint lbEndpoint11 = new LbEndpoint(eag11, 11, true);
  private final LbEndpoint lbEndpoint12 = new LbEndpoint(eag12, 12, true);
  private final LbEndpoint lbEndpoint21 = new LbEndpoint(eag21, 21, true);
  private final LbEndpoint lbEndpoint22 = new LbEndpoint(eag22, 22, true);
  private final LbEndpoint lbEndpoint31 = new LbEndpoint(eag31, 31, true);
  private final LbEndpoint lbEndpoint32 = new LbEndpoint(eag32, 32, true);
  private final LbEndpoint lbEndpoint41 = new LbEndpoint(eag41, 41, true);
  private final LbEndpoint lbEndpoint42 = new LbEndpoint(eag42, 42, true);

  private final Map<String, Locality> namedLocalities
      = ImmutableMap.of("sz1", locality1, "sz2", locality2, "sz3", locality3, "sz4", locality4);

  @Mock
  private Helper helper;
  @Mock
  private PickSubchannelArgs pickSubchannelArgs;
  @Mock
  private ThreadSafeRandom random;
  @Mock
  private OrcaPerRequestUtil orcaPerRequestUtil;
  @Mock
  private OrcaOobUtil orcaOobUtil;
  private final FakeLoadStatsStore fakeLoadStatsStore = new FakeLoadStatsStore();
  private final LoadStatsStore loadStatsStore =
      mock(LoadStatsStore.class, delegatesTo(fakeLoadStatsStore));

  private LocalityStore localityStore;

  @Before
  public void setUp() {
    doReturn(mock(ChannelLogger.class)).when(helper).getChannelLogger();
    doReturn(syncContext).when(helper).getSynchronizationContext();
    doReturn(fakeClock.getScheduledExecutorService()).when(helper).getScheduledExecutorService();
    when(orcaOobUtil.newOrcaReportingHelperWrapper(any(Helper.class),
        any(OrcaOobReportListener.class)))
        .thenAnswer(new Answer<OrcaReportingHelperWrapper>() {
          @Override
          public OrcaReportingHelperWrapper answer(InvocationOnMock invocation) {
            Helper h = invocation.getArgument(0);
            FakeOrcaReportingHelperWrapper res =
                new FakeOrcaReportingHelperWrapper(h);
            childHelperWrappers.put(h.getAuthority(), res);
            return res;
          }
        });
    lbRegistry.register(lbProvider);
    localityStore =
        new LocalityStoreImpl(logId, helper, pickerFactory, lbRegistry, random, loadStatsStore,
            orcaPerRequestUtil, orcaOobUtil);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void updateLocalityStore_updateStatsStoreLocalityTracking() {
    Map<Locality, LocalityLbEndpoints> localityInfoMap = new HashMap<>();
    localityInfoMap
        .put(locality1,
            new LocalityLbEndpoints(ImmutableList.of(lbEndpoint11, lbEndpoint12), 1, 0));
    localityInfoMap
        .put(locality2,
            new LocalityLbEndpoints(ImmutableList.of(lbEndpoint21, lbEndpoint22), 2, 0));
    localityStore.updateLocalityStore(ImmutableMap.copyOf(localityInfoMap));
    verify(loadStatsStore).addLocality(locality1);
    verify(loadStatsStore).addLocality(locality2);

    localityInfoMap
        .put(locality3,
            new LocalityLbEndpoints(ImmutableList.of(lbEndpoint31, lbEndpoint32), 3, 0));
    localityStore.updateLocalityStore(ImmutableMap.copyOf(localityInfoMap));
    verify(loadStatsStore).addLocality(locality3);

    localityInfoMap = ImmutableMap
        .of(locality4,
            new LocalityLbEndpoints(ImmutableList.of(lbEndpoint41, lbEndpoint42), 4, 0));
    localityStore.updateLocalityStore(ImmutableMap.copyOf(localityInfoMap));
    verify(loadStatsStore).removeLocality(locality1);
    verify(loadStatsStore).removeLocality(locality2);
    verify(loadStatsStore).removeLocality(locality3);
    verify(loadStatsStore).addLocality(locality4);

    localityStore.updateLocalityStore(ImmutableMap.copyOf(Collections.EMPTY_MAP));
    verify(loadStatsStore).removeLocality(locality4);
  }

  @Test
  public void updateLocalityStore_pickResultInterceptedForLoadRecordingWhenSubchannelReady() {
    // Simulate receiving two localities.
    LocalityLbEndpoints localityInfo1 =
        new LocalityLbEndpoints(ImmutableList.of(lbEndpoint11, lbEndpoint12), 1, 0);
    LocalityLbEndpoints localityInfo2 =
        new LocalityLbEndpoints(ImmutableList.of(lbEndpoint21, lbEndpoint22), 2, 0);
    localityStore.updateLocalityStore(ImmutableMap.of(
        locality1, localityInfo1, locality2, localityInfo2));

    // Two child balancers are created.
    assertThat(loadBalancers).hasSize(2);
    assertThat(pickerFactory.totalReadyLocalities).isEqualTo(0);

    ClientStreamTracer.Factory metricsTracingFactory1 = mock(ClientStreamTracer.Factory.class);
    ClientStreamTracer.Factory metricsTracingFactory2 = mock(ClientStreamTracer.Factory.class);
    when(orcaPerRequestUtil.newOrcaClientStreamTracerFactory(any(ClientStreamTracer.Factory.class),
        any(OrcaPerRequestReportListener.class)))
        .thenReturn(metricsTracingFactory1, metricsTracingFactory2);

    Subchannel subchannel1 = mock(Subchannel.class);
    Subchannel subchannel2 = mock(Subchannel.class);
    final PickResult result1 = PickResult.withSubchannel(subchannel1);
    final PickResult result2 =
        PickResult.withSubchannel(subchannel2, mock(ClientStreamTracer.Factory.class));
    SubchannelPicker subchannelPicker1 = mock(SubchannelPicker.class);
    SubchannelPicker subchannelPicker2 = mock(SubchannelPicker.class);
    when(subchannelPicker1.pickSubchannel(any(PickSubchannelArgs.class)))
        .thenReturn(result1);
    when(subchannelPicker2.pickSubchannel(any(PickSubchannelArgs.class)))
        .thenReturn(result2);

    // Simulate picker updates for the two localities with dummy pickers.
    childHelpers.get("sz1").updateBalancingState(READY, subchannelPicker1);
    childHelpers.get("sz2").updateBalancingState(READY, subchannelPicker2);

    assertThat(pickerFactory.totalReadyLocalities).isEqualTo(2);
    ArgumentCaptor<SubchannelPicker> interLocalityPickerCaptor = ArgumentCaptor.forClass(null);
    verify(helper, times(2)).updateBalancingState(eq(READY), interLocalityPickerCaptor.capture());
    SubchannelPicker interLocalityPicker = interLocalityPickerCaptor.getValue();

    // Verify each PickResult picked is intercepted with client stream tracer factory for
    // recording load and backend metrics.
    Map<Subchannel, Locality> localitiesBySubchannel
        = ImmutableMap.of(subchannel1, locality1, subchannel2, locality2);
    Map<Subchannel, ClientStreamTracer.Factory> metricsTracingFactoriesBySubchannel
        = ImmutableMap.of(subchannel1, metricsTracingFactory1, subchannel2, metricsTracingFactory2);
    for (int i = 0; i < pickerFactory.totalReadyLocalities; i++) {
      pickerFactory.nextIndex = i;
      PickResult pickResult = interLocalityPicker.pickSubchannel(pickSubchannelArgs);
      Subchannel expectedSubchannel = pickResult.getSubchannel();
      Locality expectedLocality = localitiesBySubchannel.get(expectedSubchannel);
      ArgumentCaptor<OrcaPerRequestReportListener> listenerCaptor = ArgumentCaptor.forClass(null);
      verify(orcaPerRequestUtil, times(i + 1))
          .newOrcaClientStreamTracerFactory(any(ClientStreamTracer.Factory.class),
              listenerCaptor.capture());
      assertThat(listenerCaptor.getValue()).isInstanceOf(MetricsRecordingListener.class);
      MetricsRecordingListener listener = (MetricsRecordingListener) listenerCaptor.getValue();
      assertThat(listener.getCounter())
          .isSameInstanceAs(fakeLoadStatsStore.localityCounters.get(expectedLocality));
      assertThat(pickResult.getStreamTracerFactory())
          .isInstanceOf(LoadRecordingStreamTracerFactory.class);
      LoadRecordingStreamTracerFactory loadRecordingFactory =
          (LoadRecordingStreamTracerFactory) pickResult.getStreamTracerFactory();
      assertThat(loadRecordingFactory.getCounter())
          .isSameInstanceAs(fakeLoadStatsStore.localityCounters.get(expectedLocality));
      assertThat(loadRecordingFactory.delegate())
          .isSameInstanceAs(metricsTracingFactoriesBySubchannel.get(expectedSubchannel));
    }
  }

  @Test
  public void childLbPerformOobBackendMetricsAggregation() {
    // Simulate receiving two localities.
    LocalityLbEndpoints localityInfo1 =
        new LocalityLbEndpoints(ImmutableList.of(lbEndpoint11, lbEndpoint12), 1, 0);
    LocalityLbEndpoints localityInfo2 =
        new LocalityLbEndpoints(ImmutableList.of(lbEndpoint21, lbEndpoint22), 2, 0);
    localityStore.updateLocalityStore(ImmutableMap.of(
        locality1, localityInfo1, locality2, localityInfo2));

    // Two child balancers are created.
    assertThat(loadBalancers).hasSize(2);
    assertThat(childHelperWrappers).hasSize(2);

    class HelperMatcher implements ArgumentMatcher<Helper> {

      private final String authority;

      private HelperMatcher(String authority) {
        this.authority = checkNotNull(authority, "authority");
      }

      @Override
      public boolean matches(Helper argument) {
        return authority.equals(argument.getAuthority());
      }
    }

    Map<String, Locality> localities = ImmutableMap.of("sz1", locality1, "sz2", locality2);
    for (Helper h : childHelpers.values()) {
      ArgumentCaptor<OrcaOobReportListener> listenerCaptor = ArgumentCaptor.forClass(null);
      verify(orcaOobUtil)
          .newOrcaReportingHelperWrapper(argThat(new HelperMatcher(h.getAuthority())),
              listenerCaptor.capture());
      assertThat(listenerCaptor.getValue()).isInstanceOf(MetricsRecordingListener.class);
      MetricsRecordingListener listener = (MetricsRecordingListener) listenerCaptor.getValue();
      assertThat(listener.getCounter())
          .isSameInstanceAs(fakeLoadStatsStore
              .localityCounters.get(localities.get(h.getAuthority())));
    }

    // Simulate receiving updates for backend metrics reporting interval.
    localityStore.updateOobMetricsReportInterval(1952);
    for (FakeOrcaReportingHelperWrapper orcaWrapper : childHelperWrappers.values()) {
      assertThat(orcaWrapper.reportIntervalNanos).isEqualTo(1952);
    }

    localityStore.updateOobMetricsReportInterval(9251);
    for (FakeOrcaReportingHelperWrapper orcaWrapper : childHelperWrappers.values()) {
      assertThat(orcaWrapper.reportIntervalNanos).isEqualTo(9251);
    }
  }

  @Test
  public void updateOobMetricsReportIntervalBeforeChildLbCreated() {
    // Simulate receiving update for backend metrics reporting interval.
    localityStore.updateOobMetricsReportInterval(1952);

    assertThat(loadBalancers).isEmpty();

    // Simulate receiving two localities.
    LocalityLbEndpoints localityInfo1 =
        new LocalityLbEndpoints(ImmutableList.of(lbEndpoint11, lbEndpoint12), 1, 0);
    LocalityLbEndpoints localityInfo2 =
        new LocalityLbEndpoints(ImmutableList.of(lbEndpoint21, lbEndpoint22), 2, 0);
    localityStore.updateLocalityStore(ImmutableMap.of(
        locality1, localityInfo1, locality2, localityInfo2));

    // Two child balancers are created.
    assertThat(loadBalancers).hasSize(2);
    assertThat(childHelperWrappers).hasSize(2);

    for (FakeOrcaReportingHelperWrapper orcaWrapper : childHelperWrappers.values()) {
      assertThat(orcaWrapper.reportIntervalNanos).isEqualTo(1952);
    }
  }

  @Test
  public void updateLoaclityStore_withEmptyDropList() {
    localityStore.updateDropPercentage(ImmutableList.<DropOverload>of());
    LocalityLbEndpoints localityInfo1 =
        new LocalityLbEndpoints(ImmutableList.of(lbEndpoint11, lbEndpoint12), 1, 0);
    LocalityLbEndpoints localityInfo2 =
        new LocalityLbEndpoints(ImmutableList.of(lbEndpoint21, lbEndpoint22), 2, 0);
    LocalityLbEndpoints localityInfo3 =
        new LocalityLbEndpoints(ImmutableList.of(lbEndpoint31, lbEndpoint32), 3, 0);
    ImmutableMap<Locality, LocalityLbEndpoints> localityInfoMap = ImmutableMap.of(
        locality1, localityInfo1, locality2, localityInfo2, locality3, localityInfo3);
    localityStore.updateLocalityStore(localityInfoMap);
    verify(helper).updateBalancingState(CONNECTING, BUFFER_PICKER);

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
    // verify no more updateBalancingState except the initial CONNECTING state
    verify(helper, times(1)).updateBalancingState(
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
    verify(helper, times(2)).updateBalancingState(
        same(CONNECTING), subchannelPickerCaptor12.capture());
    assertThat(pickerFactory.totalReadyLocalities).isEqualTo(0);
    assertThat(subchannelPickerCaptor12.getValue().pickSubchannel(pickSubchannelArgs))
        .isEqualTo(PickResult.withNoResult());

    // subchannel31 goes to READY
    final Subchannel subchannel31 = mock(Subchannel.class);
    SubchannelPicker subchannelPicker31 = new SubchannelPicker() {
      @Override
      public PickResult pickSubchannel(PickSubchannelArgs args) {
        return PickResult.withSubchannel(subchannel31);
      }
    };
    childHelpers.get("sz3").updateBalancingState(READY, subchannelPicker31);
    ArgumentCaptor<SubchannelPicker> subchannelPickerCaptor =
        ArgumentCaptor.forClass(null);
    verify(helper).updateBalancingState(same(READY), subchannelPickerCaptor.capture());
    assertThat(pickerFactory.totalReadyLocalities).isEqualTo(1);
    pickerFactory.nextIndex = 0;
    assertThat(subchannelPickerCaptor.getValue().pickSubchannel(pickSubchannelArgs).getSubchannel())
        .isEqualTo(subchannel31);

    // subchannel12 goes to READY
    childHelpers.get("sz1").updateBalancingState(READY, subchannelPicker12);
    verify(helper, times(2)).updateBalancingState(same(READY), subchannelPickerCaptor.capture());
    assertThat(pickerFactory.totalReadyLocalities).isEqualTo(2);

    SubchannelPicker interLocalityPicker = subchannelPickerCaptor.getValue();
    Set<Subchannel> pickedReadySubchannels = new HashSet<>();
    for (int i = 0; i < pickerFactory.totalReadyLocalities; i++) {
      pickerFactory.nextIndex = i;
      PickResult result = interLocalityPicker.pickSubchannel(pickSubchannelArgs);
      pickedReadySubchannels.add(result.getSubchannel());
    }
    assertThat(pickedReadySubchannels).containsExactly(subchannel31, subchannel12);

    // update with new addresses
    localityInfo1 =
        new LocalityLbEndpoints(ImmutableList.of(lbEndpoint11), 1, 0);
    LocalityLbEndpoints localityInfo4 =
        new LocalityLbEndpoints(ImmutableList.of(lbEndpoint41, lbEndpoint42), 4, 0);
    localityInfoMap = ImmutableMap.of(
        locality2, localityInfo2, locality4, localityInfo4, locality1, localityInfo1);
    localityStore.updateLocalityStore(localityInfoMap);

    assertThat(loadBalancers).hasSize(4);
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

    fakeClock.forwardTime(14, TimeUnit.MINUTES);
    verify(loadBalancers.get("sz3"), never()).shutdown();
    fakeClock.forwardTime(1, TimeUnit.MINUTES);
    verify(loadBalancers.get("sz3")).shutdown();

    verify(random, never()).nextInt(1000_000);
  }

  @Test
  public void updateLoaclityStore_deactivateAndReactivate() {
    LocalityLbEndpoints localityInfo1 =
        new LocalityLbEndpoints(ImmutableList.of(lbEndpoint11, lbEndpoint12), 1, 0);
    LocalityLbEndpoints localityInfo2 =
        new LocalityLbEndpoints(ImmutableList.of(lbEndpoint21, lbEndpoint22), 2, 0);
    LocalityLbEndpoints localityInfo3 =
        new LocalityLbEndpoints(ImmutableList.of(lbEndpoint31, lbEndpoint32), 3, 0);
    ImmutableMap<Locality, LocalityLbEndpoints> localityInfoMap = ImmutableMap.of(
        locality1, localityInfo1, locality2, localityInfo2, locality3, localityInfo3);
    localityStore.updateLocalityStore(localityInfoMap);

    assertThat(loadBalancers.keySet()).containsExactly("sz1", "sz2", "sz3");

    LoadBalancer lb1 = loadBalancers.get("sz1");
    LoadBalancer lb2 = loadBalancers.get("sz2");
    LoadBalancer lb3 = loadBalancers.get("sz3");

    final Subchannel subchannel1 = mock(Subchannel.class);
    SubchannelPicker subchannelPicker1 = new SubchannelPicker() {
      @Override
      public PickResult pickSubchannel(PickSubchannelArgs args) {
        return PickResult.withSubchannel(subchannel1);
      }
    };
    childHelpers.get("sz1").updateBalancingState(READY, subchannelPicker1);
    final Subchannel subchannel3 = mock(Subchannel.class);
    SubchannelPicker subchannelPicker3 = new SubchannelPicker() {
      @Override
      public PickResult pickSubchannel(PickSubchannelArgs args) {
        return PickResult.withSubchannel(subchannel3);
      }
    };
    childHelpers.get("sz3").updateBalancingState(READY, subchannelPicker3);

    // update localities, removing sz1, sz2, keeping sz3, and adding sz4
    LocalityLbEndpoints localityInfo4 =
        new LocalityLbEndpoints(ImmutableList.of(lbEndpoint41, lbEndpoint42), 4, 0);
    localityInfoMap = ImmutableMap.of(locality3, localityInfo3, locality4, localityInfo4);
    localityStore.updateLocalityStore(localityInfoMap);

    assertThat(loadBalancers.keySet()).containsExactly("sz1", "sz2", "sz3", "sz4");

    LoadBalancer lb4 = loadBalancers.get("sz4");

    ArgumentCaptor<SubchannelPicker> subchannelPickerCaptor = ArgumentCaptor.forClass(null);
    // helper updated multiple times. Don't care how many times, just capture the latest picker
    verify(helper, atLeastOnce()).updateBalancingState(
        same(READY), subchannelPickerCaptor.capture());
    assertThat(pickerFactory.totalReadyLocalities).isEqualTo(1);
    pickerFactory.nextIndex = 0;
    assertThat(subchannelPickerCaptor.getValue().pickSubchannel(pickSubchannelArgs).getSubchannel())
        .isEqualTo(subchannel3);

    // verify no traffic will go to deactivated locality
    final Subchannel subchannel2 = mock(Subchannel.class);
    SubchannelPicker subchannelPicker2 = new SubchannelPicker() {
      @Override
      public PickResult pickSubchannel(PickSubchannelArgs args) {
        return PickResult.withSubchannel(subchannel2);
      }
    };
    childHelpers.get("sz2").updateBalancingState(READY, subchannelPicker2);
    verify(helper, atLeastOnce()).updateBalancingState(
        same(READY), subchannelPickerCaptor.capture());
    assertThat(pickerFactory.totalReadyLocalities).isEqualTo(1);
    pickerFactory.nextIndex = 0;
    assertThat(subchannelPickerCaptor.getValue().pickSubchannel(pickSubchannelArgs).getSubchannel())
        .isEqualTo(subchannel3);

    // update localities, reactivating sz1
    localityInfoMap = ImmutableMap.of(
        locality1, localityInfo1, locality3, localityInfo3, locality4, localityInfo4);
    localityStore.updateLocalityStore(localityInfoMap);
    verify(helper, atLeastOnce()).updateBalancingState(
        same(READY), subchannelPickerCaptor.capture());
    assertThat(pickerFactory.totalReadyLocalities).isEqualTo(2);
    pickerFactory.nextIndex = 0;
    assertThat(subchannelPickerCaptor.getValue().pickSubchannel(pickSubchannelArgs).getSubchannel())
        .isEqualTo(subchannel1);
    pickerFactory.nextIndex = 1;
    assertThat(subchannelPickerCaptor.getValue().pickSubchannel(pickSubchannelArgs).getSubchannel())
        .isEqualTo(subchannel3);

    verify(lb2, never()).shutdown();
    // delayed deletion timer expires, no reactivation
    fakeClock.forwardTime(15, TimeUnit.MINUTES);
    verify(lb1, never()).shutdown();
    verify(lb2).shutdown();
    // update localities, re-adding sz2, keeping sz1, and removing sz3, sz4
    localityInfoMap = ImmutableMap.of(
        locality1, localityInfo1, locality2, localityInfo2);
    localityStore.updateLocalityStore(localityInfoMap);

    LoadBalancer newLb2 = loadBalancers.get("sz2");
    assertThat(newLb2).isNotSameInstanceAs(lb2);

    verify(helper, atLeastOnce()).updateBalancingState(
        same(READY), subchannelPickerCaptor.capture());
    assertThat(pickerFactory.totalReadyLocalities).isEqualTo(1);
    pickerFactory.nextIndex = 0;
    assertThat(subchannelPickerCaptor.getValue().pickSubchannel(pickSubchannelArgs).getSubchannel())
        .isEqualTo(subchannel1);
    // sz3, sz4 pending removal
    assertThat(fakeClock.getPendingTasks(deactivationTaskFilter)).hasSize(2);

    // verify lb1, lb3, and lb4 never shutdown and never changed since created
    verify(lb1, never()).shutdown();
    verify(newLb2, never()).shutdown();
    verify(lb3, never()).shutdown();
    verify(lb4, never()).shutdown();
    assertThat(loadBalancers.get("sz1")).isSameInstanceAs(lb1);
    assertThat(loadBalancers.get("sz2")).isSameInstanceAs(newLb2);
    assertThat(loadBalancers.get("sz3")).isSameInstanceAs(lb3);
    assertThat(loadBalancers.get("sz4")).isSameInstanceAs(lb4);

    localityStore.reset();
    assertThat(fakeClock.getPendingTasks(deactivationTaskFilter)).isEmpty();
    verify(lb1).shutdown();
    verify(newLb2).shutdown();
    verify(lb3).shutdown();
    verify(lb4).shutdown();
  }

  @Test
  public void updateLoaclityStore_withDrop() {
    localityStore.updateDropPercentage(ImmutableList.of(
        new DropOverload("throttle", 365),
        new DropOverload("lb", 1234)));
    LocalityLbEndpoints localityInfo1 =
        new LocalityLbEndpoints(ImmutableList.of(lbEndpoint11, lbEndpoint12), 1, 0);
    LocalityLbEndpoints localityInfo2 =
        new LocalityLbEndpoints(ImmutableList.of(lbEndpoint21, lbEndpoint22), 2, 0);
    LocalityLbEndpoints localityInfo3 =
        new LocalityLbEndpoints(ImmutableList.of(lbEndpoint31, lbEndpoint32), 3, 0);
    ImmutableMap<Locality, LocalityLbEndpoints> localityInfoMap = ImmutableMap.of(
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
    verify(helper).updateBalancingState(same(CONNECTING), subchannelPickerCaptor.capture());

    int times = 0;
    InOrder inOrder = inOrder(loadStatsStore);
    doReturn(365, 1234).when(random).nextInt(1000_000);
    assertThat(subchannelPickerCaptor.getValue().pickSubchannel(pickSubchannelArgs))
        .isEqualTo(PickResult.withNoResult());
    verify(random, times(times += 2)).nextInt(1000_000);
    inOrder.verify(loadStatsStore, never()).recordDroppedRequest(anyString());

    doReturn(366, 1235).when(random).nextInt(1000_000);
    assertThat(subchannelPickerCaptor.getValue().pickSubchannel(pickSubchannelArgs))
        .isEqualTo(PickResult.withNoResult());
    verify(random, times(times += 2)).nextInt(1000_000);
    inOrder.verify(loadStatsStore, never()).recordDroppedRequest(anyString());

    doReturn(364, 1234).when(random).nextInt(1000_000);
    assertThat(subchannelPickerCaptor.getValue().pickSubchannel(pickSubchannelArgs).isDrop())
        .isTrue();
    verify(random, times(times += 1)).nextInt(1000_000);
    inOrder.verify(loadStatsStore).recordDroppedRequest(eq("throttle"));

    doReturn(365, 1233).when(random).nextInt(1000_000);
    assertThat(subchannelPickerCaptor.getValue().pickSubchannel(pickSubchannelArgs).isDrop())
        .isTrue();
    verify(random, times(times += 2)).nextInt(1000_000);
    inOrder.verify(loadStatsStore).recordDroppedRequest(eq("lb"));

    // subchannel12 goes to READY
    final Subchannel subchannel12 = mock(Subchannel.class);
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
    inOrder.verify(loadStatsStore, never()).recordDroppedRequest(anyString());

    doReturn(366, 1235).when(random).nextInt(1000_000);
    assertThat(subchannelPickerCaptor12.getValue().pickSubchannel(pickSubchannelArgs)
        .getSubchannel()).isEqualTo(subchannel12);
    verify(random, times(times += 2)).nextInt(1000_000);
    inOrder.verify(loadStatsStore, never()).recordDroppedRequest(anyString());

    doReturn(364, 1234).when(random).nextInt(1000_000);
    assertThat(subchannelPickerCaptor12.getValue().pickSubchannel(pickSubchannelArgs).isDrop())
        .isTrue();
    verify(random, times(times += 1)).nextInt(1000_000);
    inOrder.verify(loadStatsStore).recordDroppedRequest(eq("throttle"));

    doReturn(365, 1233).when(random).nextInt(1000_000);
    assertThat(subchannelPickerCaptor12.getValue().pickSubchannel(pickSubchannelArgs).isDrop())
        .isTrue();
    verify(random, times(times + 2)).nextInt(1000_000);
    inOrder.verify(loadStatsStore).recordDroppedRequest(eq("lb"));
    inOrder.verifyNoMoreInteractions();
  }

  @Test
  public void updateLoaclityStore_withAllDropBeforeLocalityUpdateConnectivityState() {
    localityStore.updateDropPercentage(ImmutableList.of(
        new DropOverload("throttle", 365),
        new DropOverload("lb", 1000_000)));
    LocalityLbEndpoints localityInfo1 =
        new LocalityLbEndpoints(ImmutableList.of(lbEndpoint11, lbEndpoint12), 1, 0);
    LocalityLbEndpoints localityInfo2 =
        new LocalityLbEndpoints(ImmutableList.of(lbEndpoint21, lbEndpoint22), 2, 0);
    LocalityLbEndpoints localityInfo3 =
        new LocalityLbEndpoints(ImmutableList.of(lbEndpoint31, lbEndpoint32), 3, 0);
    ImmutableMap<Locality, LocalityLbEndpoints> localityInfoMap = ImmutableMap.of(
        locality1, localityInfo1, locality2, localityInfo2, locality3, localityInfo3);
    localityStore.updateLocalityStore(localityInfoMap);

    ArgumentCaptor<SubchannelPicker> subchannelPickerCaptor =
        ArgumentCaptor.forClass(SubchannelPicker.class);
    verify(helper).updateBalancingState(same(CONNECTING), subchannelPickerCaptor.capture());
    doReturn(999_999).when(random).nextInt(1000_000);
    assertThat(subchannelPickerCaptor.getValue().pickSubchannel(pickSubchannelArgs).isDrop())
        .isTrue();
    verify(random, times(2)).nextInt(1000_000);
  }

  @Test
  public void updateLoaclityStore_withUnHealthyEndPoints() {
    LbEndpoint lbEndpoint11 = new LbEndpoint(eag11, 11, true);
    LbEndpoint lbEndpoint12 = new LbEndpoint(eag12, 12, true);
    LbEndpoint lbEndpoint21 = new LbEndpoint(eag21, 21, false); // unhealthy
    LbEndpoint lbEndpoint22 = new LbEndpoint(eag22, 22, true);
    LbEndpoint lbEndpoint31 = new LbEndpoint(eag31, 31, false); // unhealthy
    LbEndpoint lbEndpoint32 = new LbEndpoint(eag32, 32, false); // unhealthy
    LbEndpoint lbEndpoint41 = new LbEndpoint(eag41, 41, true);
    LbEndpoint lbEndpoint42 = new LbEndpoint(eag42, 42, true);
    LocalityLbEndpoints localityInfo1 =
        new LocalityLbEndpoints(ImmutableList.of(lbEndpoint11, lbEndpoint12), 1, 0);
    LocalityLbEndpoints localityInfo2 =
        new LocalityLbEndpoints(ImmutableList.of(lbEndpoint21, lbEndpoint22), 2, 0);
    LocalityLbEndpoints localityInfo3 =
        new LocalityLbEndpoints(ImmutableList.of(lbEndpoint31, lbEndpoint32), 3, 0);
    LocalityLbEndpoints localityInfo4 =
        new LocalityLbEndpoints(ImmutableList.of(lbEndpoint41, lbEndpoint42), 4, 0);
    ImmutableMap<Locality, LocalityLbEndpoints> localityInfoMap = ImmutableMap.of(
        locality1, localityInfo1, locality2, localityInfo2, locality3, localityInfo3,
        locality4, localityInfo4);
    localityStore.updateLocalityStore(localityInfoMap);
    verify(helper).updateBalancingState(CONNECTING, BUFFER_PICKER);

    assertThat(loadBalancers).hasSize(4);
    assertThat(loadBalancers.keySet()).containsExactly("sz1", "sz2", "sz3", "sz4");
    ArgumentCaptor<ResolvedAddresses> resolvedAddressesCaptor1 =
        ArgumentCaptor.forClass(ResolvedAddresses.class);
    verify(loadBalancers.get("sz1")).handleResolvedAddresses(resolvedAddressesCaptor1.capture());
    assertThat(resolvedAddressesCaptor1.getValue().getAddresses()).containsExactly(eag11, eag12);
    ArgumentCaptor<ResolvedAddresses> resolvedAddressesCaptor2 =
        ArgumentCaptor.forClass(ResolvedAddresses.class);
    verify(loadBalancers.get("sz2")).handleResolvedAddresses(resolvedAddressesCaptor2.capture());
    assertThat(resolvedAddressesCaptor2.getValue().getAddresses()).containsExactly(eag22);
    verify(loadBalancers.get("sz3"), never()).handleResolvedAddresses(any(ResolvedAddresses.class));
    ArgumentCaptor<Status> statusCaptor = ArgumentCaptor.forClass(Status.class);
    verify(loadBalancers.get("sz3")).handleNameResolutionError(statusCaptor.capture());
    assertThat(statusCaptor.getValue().getCode()).isEqualTo(Status.Code.UNAVAILABLE);
    ArgumentCaptor<ResolvedAddresses> resolvedAddressesCaptor4 =
        ArgumentCaptor.forClass(ResolvedAddresses.class);
    verify(loadBalancers.get("sz4")).handleResolvedAddresses(resolvedAddressesCaptor4.capture());
    assertThat(resolvedAddressesCaptor4.getValue().getAddresses()).containsExactly(eag41, eag42);
    // verify no more updateBalancingState except the initial CONNECTING state
    verify(helper, times(1)).updateBalancingState(
        any(ConnectivityState.class), any(SubchannelPicker.class));

    // update with different healthy status
    lbEndpoint11 = new LbEndpoint(eag11, 11, false); // unhealthy
    lbEndpoint12 = new LbEndpoint(eag12, 12, false); // unhealthy
    lbEndpoint21 = new LbEndpoint(eag21, 21, true);
    lbEndpoint22 = new LbEndpoint(eag22, 22, false); // unhealthy
    lbEndpoint31 = new LbEndpoint(eag31, 31, true);
    lbEndpoint32 = new LbEndpoint(eag32, 32, false); // unhealthy
    localityInfo1 =
        new LocalityLbEndpoints(ImmutableList.of(lbEndpoint11, lbEndpoint12), 1, 0);
    localityInfo2 =
        new LocalityLbEndpoints(ImmutableList.of(lbEndpoint21, lbEndpoint22), 2, 0);
    localityInfo3 =
        new LocalityLbEndpoints(ImmutableList.of(lbEndpoint31, lbEndpoint32), 3, 0);
    localityInfoMap = ImmutableMap.of(
        locality1, localityInfo1, locality2, localityInfo2, locality3, localityInfo3);
    localityStore.updateLocalityStore(localityInfoMap);

    verify(loadBalancers.get("sz1"), times(1))
        .handleResolvedAddresses(any(ResolvedAddresses.class));
    verify(loadBalancers.get("sz1"), times(1)).handleNameResolutionError(statusCaptor.capture());
    assertThat(statusCaptor.getValue().getCode()).isEqualTo(Status.Code.UNAVAILABLE);
    verify(loadBalancers.get("sz2"), times(2))
        .handleResolvedAddresses(resolvedAddressesCaptor2.capture());
    assertThat(resolvedAddressesCaptor2.getValue().getAddresses()).containsExactly(eag21);
    ArgumentCaptor<ResolvedAddresses> resolvedAddressesCaptor3 =
        ArgumentCaptor.forClass(ResolvedAddresses.class);
    verify(loadBalancers.get("sz3")).handleResolvedAddresses(resolvedAddressesCaptor3.capture());
    assertThat(resolvedAddressesCaptor3.getValue().getAddresses()).containsExactly(eag31);
    verify(helper, times(2)).updateBalancingState(CONNECTING, BUFFER_PICKER);

    // update with all endpoints unhealthy
    lbEndpoint11 = new LbEndpoint(eag11, 11, false); // unhealthy
    lbEndpoint12 = new LbEndpoint(eag12, 12, false); // unhealthy
    lbEndpoint31 = new LbEndpoint(eag31, 31, false); // unhealthy
    lbEndpoint32 = new LbEndpoint(eag32, 32, false); // unhealthy
    localityInfo1 =
        new LocalityLbEndpoints(ImmutableList.of(lbEndpoint11, lbEndpoint12), 1, 0);
    localityInfo3 =
        new LocalityLbEndpoints(ImmutableList.of(lbEndpoint31, lbEndpoint32), 3, 0);
    localityInfoMap = ImmutableMap.of(locality1, localityInfo1, locality3, localityInfo3);
    localityStore.updateLocalityStore(localityInfoMap);

    verify(loadBalancers.get("sz1"), times(2)).handleNameResolutionError(statusCaptor.capture());
    assertThat(statusCaptor.getValue().getCode()).isEqualTo(Status.Code.UNAVAILABLE);
    verify(loadBalancers.get("sz3"), times(2)).handleNameResolutionError(statusCaptor.capture());
    assertThat(statusCaptor.getValue().getCode()).isEqualTo(Status.Code.UNAVAILABLE);

    // mimic child balancers update subchannels to TRANSIENT_FAILURE after handleNameResolutionError
    childHelpers.get("sz1").updateBalancingState(TRANSIENT_FAILURE, new ErrorPicker(UNAVAILABLE));
    childHelpers.get("sz3").updateBalancingState(TRANSIENT_FAILURE, new ErrorPicker(UNAVAILABLE));
    verify(helper).updateBalancingState(eq(TRANSIENT_FAILURE), any(SubchannelPicker.class));
  }

  @Test
  public void updateLocalityStore_OnlyUpdatingWeightsStillUpdatesPicker() {
    LocalityLbEndpoints localityInfo1 =
        new LocalityLbEndpoints(ImmutableList.of(lbEndpoint11, lbEndpoint12), 1, 0);
    LocalityLbEndpoints localityInfo2 =
        new LocalityLbEndpoints(ImmutableList.of(lbEndpoint21, lbEndpoint22), 2, 0);
    LocalityLbEndpoints localityInfo3 =
        new LocalityLbEndpoints(ImmutableList.of(lbEndpoint31, lbEndpoint32), 3, 0);
    ImmutableMap<Locality, LocalityLbEndpoints> localityInfoMap = ImmutableMap.of(
        locality1, localityInfo1, locality2, localityInfo2, locality3, localityInfo3);
    localityStore.updateLocalityStore(localityInfoMap);

    assertThat(loadBalancers).hasSize(3);
    assertThat(loadBalancers.keySet()).containsExactly("sz1", "sz2", "sz3");
    assertThat(pickerFactory.totalReadyLocalities).isEqualTo(0);

    // Update locality weights before any subchannel becomes READY.
    localityInfo1 = new LocalityLbEndpoints(ImmutableList.of(lbEndpoint11, lbEndpoint12), 4, 0);
    localityInfo2 = new LocalityLbEndpoints(ImmutableList.of(lbEndpoint21, lbEndpoint22), 5, 0);
    localityInfo3 = new LocalityLbEndpoints(ImmutableList.of(lbEndpoint31, lbEndpoint32), 6, 0);
    localityInfoMap = ImmutableMap.of(
        locality1, localityInfo1, locality2, localityInfo2, locality3, localityInfo3);
    localityStore.updateLocalityStore(localityInfoMap);

    assertThat(pickerFactory.totalReadyLocalities).isEqualTo(0);

    final Map<Subchannel, Locality> localitiesBySubchannel = new HashMap<>();
    for (final Helper h : childHelpers.values()) {
      h.updateBalancingState(READY, new SubchannelPicker() {
        @Override
        public PickResult pickSubchannel(PickSubchannelArgs args) {
          Subchannel subchannel = mock(Subchannel.class);
          localitiesBySubchannel.put(subchannel, namedLocalities.get(h.getAuthority()));
          return PickResult.withSubchannel(subchannel);
        }
      });
    }

    assertThat(pickerFactory.totalReadyLocalities).isEqualTo(3);
    for (int i = 0; i < pickerFactory.totalReadyLocalities; i++) {
      WeightedChildPicker weightedChildPicker
          = pickerFactory.perLocalitiesPickers.get(i);
      Subchannel subchannel
          = weightedChildPicker.getPicker().pickSubchannel(pickSubchannelArgs).getSubchannel();
      assertThat(weightedChildPicker.getWeight())
          .isEqualTo(
              localityInfoMap.get(localitiesBySubchannel.get(subchannel)).getLocalityWeight());
    }
  }

  @Test
  public void reset() {
    LocalityLbEndpoints localityInfo1 =
        new LocalityLbEndpoints(ImmutableList.of(lbEndpoint11, lbEndpoint12), 1, 0);
    LocalityLbEndpoints localityInfo2 =
        new LocalityLbEndpoints(ImmutableList.of(lbEndpoint21, lbEndpoint22), 2, 0);
    ImmutableMap<Locality, LocalityLbEndpoints> localityInfoMap = ImmutableMap.of(
        locality1, localityInfo1, locality2, localityInfo2);
    localityStore.updateLocalityStore(localityInfoMap);

    assertThat(loadBalancers).hasSize(2);

    localityStore.reset();

    verify(loadBalancers.get("sz1")).shutdown();
    verify(loadBalancers.get("sz2")).shutdown();
    verify(loadStatsStore).removeLocality(locality1);
    verify(loadStatsStore).removeLocality(locality2);

    // Regression test for same locality added back.
    localityStore.updateLocalityStore(localityInfoMap);
    assertThat(loadBalancers).hasSize(2);
    localityStore.reset();
    verify(loadBalancers.get("sz1")).shutdown();
    verify(loadBalancers.get("sz2")).shutdown();
    verify(loadStatsStore, times(2)).removeLocality(locality1);
    verify(loadStatsStore, times(2)).removeLocality(locality2);
  }

  /**
   * Tests the scenario of the following sequence of events.
   * (In the format of "event detail - expected state", with abbreviations C for CONNECTING,
   * F for TRANSIENT_FAILURE, R for READ, and D for deactivated etc.)
   * EDS update: P0 sz1; P1 sz2; P2 sz3; P3 sz4 - P0 C, P1 N/A, P2 N/A, P3 N/A
   * 10 secs passes P0 still CONNECTING - P0 C, P1 C, P2 N/A, P3 N/A
   * 5 secs passes P1 in TRANSIENT_FAILURE - P0 C, P1 F, P2 C, P3 N/A
   * 4 secs passes P2 READY - P0 C, P1 F, P2 R, P3 N/A
   * P0 gets READY - P0 R, P1 F&D, P2 R&D, P3 N/A
   * P1 gets READY - P0 R, P1 R&D, P2 R&D, P3 N/A
   * 10 min passes P0 in TRANSIENT_FAILURE - P0 F, P1 R, P2 R&D, P3 N/A
   * 5 min passes - P0 F, P1 R, P2 N/A, P3 N/A
   * P1 in TRANSIENT_FAILURE - P0 F, P1 F, P2 C, P3 N/A
   * 10 secs passes - P0 F, P1 F, P2 C, P3 C
   * P1, P3 gets READY - P0 F, P1 R, P2 C&D, P3 R&D
   * EDS update, localities moved: P0 sz1, sz3; P1 sz4; P2 sz2 - P0 C, P1 R, P2 R&D
   * 15 min passes - P0 C, P1 R, P2 N/A
   * EDS update, locality removed: P0 sz1, sz3, - P0 C, P1 N/A, sz4 R&D
   * sz3 gets READY - P0 R, P1 N/A, sz4 R&D
   * EDS update, locality comes back and another removed: P0 sz1, P1 sz4 - P0 C, P1 R, sz3 R&D
   *
   * <p>Should also verify that when locality store is updated with new EDS data, state of all
   * localities should be updated before the child balancer of each locality handles new addresses.
   */
  @Test
  public void multipriority() {
    // EDS update: P0 sz1; P1 sz2; P2 sz3; P3 sz4 - P0 C, P1 N/A, P2 N/A, P3 N/A
    LocalityLbEndpoints localityInfo1 =
        new LocalityLbEndpoints(ImmutableList.of(lbEndpoint11, lbEndpoint12), 1, 0);
    LocalityLbEndpoints localityInfo2 =
        new LocalityLbEndpoints(ImmutableList.of(lbEndpoint21, lbEndpoint22), 2, 1);
    LocalityLbEndpoints localityInfo3 =
        new LocalityLbEndpoints(ImmutableList.of(lbEndpoint31, lbEndpoint32), 3, 2);
    LocalityLbEndpoints localityInfo4 =
        new LocalityLbEndpoints(ImmutableList.of(lbEndpoint41, lbEndpoint42), 3, 3);
    final ImmutableMap<Locality, LocalityLbEndpoints> localityInfoMap = ImmutableMap.of(
        locality1, localityInfo1, locality2, localityInfo2, locality3, localityInfo3, locality4,
        localityInfo4);
    syncContext.execute(new Runnable() {
      @Override
      public void run() {
        localityStore.updateLocalityStore(localityInfoMap);
      }
    });
    assertThat(loadBalancers.keySet()).containsExactly("sz1");
    LoadBalancer lb1 = loadBalancers.get("sz1");
    InOrder inOrder = inOrder(lb1, helper);
    ArgumentCaptor<ResolvedAddresses> resolvedAddressesCaptor1 = ArgumentCaptor.forClass(null);
    inOrder.verify(helper).updateBalancingState(CONNECTING, BUFFER_PICKER);
    inOrder.verify(lb1).handleResolvedAddresses(resolvedAddressesCaptor1.capture());
    assertThat(resolvedAddressesCaptor1.getValue().getAddresses()).containsExactly(eag11, eag12);

    // 10 sec passes P0 still CONNECTING - P0 C, P1 C, P2 N/A, P3 N/A
    fakeClock.forwardTime(9, TimeUnit.SECONDS);
    assertThat(loadBalancers.keySet()).containsExactly("sz1");
    assertThat(fakeClock.getPendingTasks(failOverTaskFilter)).hasSize(1);
    ScheduledTask failOverTask =
        Iterables.getOnlyElement(fakeClock.getPendingTasks(failOverTaskFilter));
    fakeClock.forwardTime(1, TimeUnit.SECONDS);
    assertThat(failOverTask.isCancelled()).isFalse();
    assertThat(fakeClock.getPendingTasks(failOverTaskFilter)).doesNotContain(failOverTask);
    assertThat(fakeClock.getPendingTasks(failOverTaskFilter)).hasSize(1);
    failOverTask = Iterables.getOnlyElement(fakeClock.getPendingTasks(failOverTaskFilter));
    assertThat(fakeClock.getPendingTasks(deactivationTaskFilter)).isEmpty();
    assertThat(loadBalancers.keySet()).containsExactly("sz1", "sz2");
    LoadBalancer lb2 = loadBalancers.get("sz2");
    inOrder = inOrder(lb1, lb2, helper);
    inOrder.verify(helper).updateBalancingState(CONNECTING, BUFFER_PICKER);
    ArgumentCaptor<ResolvedAddresses> resolvedAddressesCaptor2 = ArgumentCaptor.forClass(null);
    inOrder.verify(lb2).handleResolvedAddresses(resolvedAddressesCaptor2.capture());
    assertThat(resolvedAddressesCaptor2.getValue().getAddresses()).containsExactly(eag21, eag22);

    // 5 secs passes P1 in TRANSIENT_FAILURE - P0 C, P1 F, P2 C, P3 N/A
    fakeClock.forwardTime(5, TimeUnit.SECONDS);
    assertThat(fakeClock.getPendingTasks(failOverTaskFilter)).containsExactly(failOverTask);
    final Helper helper2 = childHelpers.get("sz2");
    helper.getSynchronizationContext().execute(new Runnable() {
      @Override
      public void run() {
        helper2.updateBalancingState(TRANSIENT_FAILURE, new ErrorPicker(UNAVAILABLE));
      }
    });
    assertThat(failOverTask.isCancelled()).isTrue();
    assertThat(fakeClock.getPendingTasks(failOverTaskFilter)).doesNotContain(failOverTask);
    assertThat(fakeClock.getPendingTasks(failOverTaskFilter)).hasSize(1);
    failOverTask = Iterables.getOnlyElement(fakeClock.getPendingTasks(failOverTaskFilter));
    assertThat(fakeClock.getPendingTasks(deactivationTaskFilter)).isEmpty();
    assertThat(loadBalancers.keySet()).containsExactly("sz1", "sz2", "sz3");
    LoadBalancer lb3 = loadBalancers.get("sz3");
    inOrder = inOrder(lb3, helper);
    // The order of the following two updateBalancingState() does not matter. We want to verify
    // lb3.handleResolvedAddresses() is after them.
    inOrder.verify(helper).updateBalancingState(same(TRANSIENT_FAILURE), isA(ErrorPicker.class));
    inOrder.verify(helper).updateBalancingState(CONNECTING, BUFFER_PICKER);
    ArgumentCaptor<ResolvedAddresses> resolvedAddressesCaptor3 = ArgumentCaptor.forClass(null);
    inOrder.verify(lb3).handleResolvedAddresses(resolvedAddressesCaptor3.capture());
    assertThat(resolvedAddressesCaptor3.getValue().getAddresses()).containsExactly(eag31, eag32);

    // 5 secs passes P2 READY - P0 C, P1 F, P2 R, P3 N/A
    fakeClock.forwardTime(4, TimeUnit.SECONDS);
    assertThat(fakeClock.getPendingTasks(failOverTaskFilter)).containsExactly(failOverTask);
    final Helper helper3 = childHelpers.get("sz3");
    final Subchannel mockSubchannel3 = mock(Subchannel.class);
    helper.getSynchronizationContext().execute(new Runnable() {
      @Override
      public void run() {
        helper3.updateBalancingState(
            READY,
            new SubchannelPicker() {
              @Override
              public PickResult pickSubchannel(PickSubchannelArgs args) {
                return PickResult.withSubchannel(mockSubchannel3);
              }
            });
      }
    });
    assertThat(failOverTask.isCancelled()).isTrue();
    assertThat(fakeClock.getPendingTasks(failOverTaskFilter)).isEmpty();
    assertThat(fakeClock.getPendingTasks(deactivationTaskFilter)).isEmpty();
    ArgumentCaptor<SubchannelPicker> subchannelPickerCaptor = ArgumentCaptor.forClass(null);
    inOrder.verify(helper).updateBalancingState(same(READY), subchannelPickerCaptor.capture());
    assertThat(subchannelPickerCaptor.getValue()
            .pickSubchannel(mock(PickSubchannelArgs.class))
            .getSubchannel())
        .isSameInstanceAs(mockSubchannel3);

    // P0 gets READY - P0 R, P1 F&D, P2 R&D, P3 N/A
    final Helper helper1 = childHelpers.get("sz1");
    final Subchannel mockSubchannel1 = mock(Subchannel.class);
    helper.getSynchronizationContext().execute(new Runnable() {
      @Override
      public void run() {
        helper1.updateBalancingState(
            READY,
            new SubchannelPicker() {
              @Override
              public PickResult pickSubchannel(PickSubchannelArgs args) {
                return PickResult.withSubchannel(mockSubchannel1);
              }
            });
      }
    });
    assertThat(fakeClock.getPendingTasks(failOverTaskFilter)).isEmpty();
    Collection<ScheduledTask> deactivationTasks = fakeClock.getPendingTasks(deactivationTaskFilter);
    assertThat(deactivationTasks).hasSize(2);
    inOrder.verify(helper).updateBalancingState(same(READY), subchannelPickerCaptor.capture());
    assertThat(subchannelPickerCaptor.getValue()
            .pickSubchannel(mock(PickSubchannelArgs.class))
            .getSubchannel())
        .isSameInstanceAs(mockSubchannel1);

    // P1 gets READY - P0 R, P1 R&D, P2 R&D, P3 N/A
    final Subchannel mockSubchannel2 = mock(Subchannel.class);
    helper.getSynchronizationContext().execute(new Runnable() {
      @Override
      public void run() {
        helper2.updateBalancingState(
            READY,
            new SubchannelPicker() {
              @Override
              public PickResult pickSubchannel(PickSubchannelArgs args) {
                return PickResult.withSubchannel(mockSubchannel2);
              }
            });
      }
    });
    assertThat(fakeClock.getPendingTasks(failOverTaskFilter)).isEmpty();
    assertThat(fakeClock.getPendingTasks(deactivationTaskFilter)).hasSize(2);
    inOrder.verify(helper, never())
        .updateBalancingState(any(ConnectivityState.class), any(SubchannelPicker.class));

    // 10 min passes P0 in TRANSIENT_FAILURE - P0 F, P1 R, P2 R&D, P3 N/A
    fakeClock.forwardTime(10, TimeUnit.MINUTES);
    helper.getSynchronizationContext().execute(new Runnable() {
      @Override
      public void run() {
        helper1.updateBalancingState(TRANSIENT_FAILURE, new ErrorPicker(UNAVAILABLE));
      }
    });
    assertThat(fakeClock.getPendingTasks(failOverTaskFilter)).isEmpty();
    assertThat(fakeClock.getPendingTasks(deactivationTaskFilter)).hasSize(1);
    ScheduledTask deactivationTask =
        Iterables.getOnlyElement(fakeClock.getPendingTasks(deactivationTaskFilter));
    assertThat(deactivationTask).isSameInstanceAs(Iterables.get(deactivationTasks, 1));
    assertThat(Iterables.get(deactivationTasks, 0).isCancelled()).isTrue();
    inOrder.verify(helper, never())
        .updateBalancingState(CONNECTING, BUFFER_PICKER);


    // 5 min passes - P0 F, P1 R, P2 N/A, P3 N/A
    verify(lb3, never()).shutdown();
    fakeClock.forwardTime(5, TimeUnit.MINUTES);
    assertThat(fakeClock.getPendingTasks(failOverTaskFilter)).isEmpty();
    assertThat(fakeClock.getPendingTasks(deactivationTaskFilter)).isEmpty();
    verify(lb3).shutdown();

    // P1 in TRANSIENT_FAILURE - P0 F, P1 F, P2 C, P3 N/A
    helper.getSynchronizationContext().execute(new Runnable() {
      @Override
      public void run() {
        helper2.updateBalancingState(TRANSIENT_FAILURE, new ErrorPicker(UNAVAILABLE));
      }
    });
    assertThat(fakeClock.getPendingTasks(failOverTaskFilter)).hasSize(1);
    failOverTask = Iterables.getOnlyElement(fakeClock.getPendingTasks(failOverTaskFilter));
    assertThat(fakeClock.getPendingTasks(deactivationTaskFilter)).isEmpty();
    // The order of the following two updateBalancingState() does not matter. We only want to verify
    // these are the latest tow updateBalancingState().
    inOrder.verify(helper).updateBalancingState(same(TRANSIENT_FAILURE), isA(ErrorPicker.class));
    inOrder.verify(helper).updateBalancingState(CONNECTING, BUFFER_PICKER);
    assertThat(loadBalancers.keySet()).containsExactly("sz1", "sz2", "sz3");
    assertThat(loadBalancers.get("sz3")).isNotSameInstanceAs(lb3);
    lb3 = loadBalancers.get("sz3");
    assertThat(childHelpers.get("sz3")).isNotSameInstanceAs(helper3);

    // 10 secs passes - P0 F, P1 F, P2 C, P3 C
    fakeClock.forwardTime(10, TimeUnit.SECONDS);
    assertThat(fakeClock.getPendingTasks(failOverTaskFilter)).hasSize(1);
    assertThat(fakeClock.getPendingTasks(failOverTaskFilter)).doesNotContain(failOverTask);
    assertThat(fakeClock.getPendingTasks(deactivationTaskFilter)).isEmpty();
    assertThat(loadBalancers.keySet()).containsExactly("sz1", "sz2", "sz3", "sz4");
    LoadBalancer lb4 = loadBalancers.get("sz4");
    inOrder = inOrder(lb1, lb2, lb3, lb4, helper);
    inOrder.verify(helper).updateBalancingState(CONNECTING, BUFFER_PICKER);
    ArgumentCaptor<ResolvedAddresses> resolvedAddressesCaptor4 = ArgumentCaptor.forClass(null);
    inOrder.verify(lb4).handleResolvedAddresses(resolvedAddressesCaptor4.capture());
    assertThat(resolvedAddressesCaptor4.getValue().getAddresses()).containsExactly(eag41, eag42);

    // P1, P3 gets READY - P0 F, P1 R, P2 C&D, P3 R&D
    final Subchannel mockSubchannel22 = mock(Subchannel.class);
    final Subchannel mockSubchannel4 = mock(Subchannel.class);
    final Helper helper4 = childHelpers.get("sz4");
    helper.getSynchronizationContext().execute(new Runnable() {
      @Override
      public void run() {
        helper2.updateBalancingState(
            READY,
            new SubchannelPicker() {
              @Override
              public PickResult pickSubchannel(PickSubchannelArgs args) {
                return PickResult.withSubchannel(mockSubchannel22);
              }
            });
        helper4.updateBalancingState(
            READY,
            new SubchannelPicker() {
              @Override
              public PickResult pickSubchannel(PickSubchannelArgs args) {
                return PickResult.withSubchannel(mockSubchannel4);
              }
            });
      }
    });
    assertThat(fakeClock.getPendingTasks(failOverTaskFilter)).isEmpty();
    assertThat(fakeClock.getPendingTasks(deactivationTaskFilter)).hasSize(2);
    inOrder.verify(helper).updateBalancingState(same(READY), subchannelPickerCaptor.capture());
    assertThat(subchannelPickerCaptor.getValue()
            .pickSubchannel(mock(PickSubchannelArgs.class))
            .getSubchannel())
        .isSameInstanceAs(mockSubchannel22);

    // EDS update, localities moved: P0 sz1, sz3; P1 sz4; P2 sz2 - P0 C, P1 R, P2 R&D
    localityInfo1 =
        new LocalityLbEndpoints(ImmutableList.of(lbEndpoint12), 1, 0);
    localityInfo2 =
        new LocalityLbEndpoints(ImmutableList.of(lbEndpoint22), 2, 2);
    localityInfo3 =
        new LocalityLbEndpoints(ImmutableList.of(lbEndpoint32), 3, 0);
    localityInfo4 =
        new LocalityLbEndpoints(ImmutableList.of(lbEndpoint42), 4, 1);
    final ImmutableMap<Locality, LocalityLbEndpoints> localityInfoMap2 = ImmutableMap.of(
        locality1, localityInfo1, locality2, localityInfo2, locality3, localityInfo3, locality4,
        localityInfo4);
    syncContext.execute(new Runnable() {
      @Override
      public void run() {
        localityStore.updateLocalityStore(localityInfoMap2);
      }
    });
    assertThat(loadBalancers.keySet()).containsExactly("sz1", "sz2", "sz3", "sz4");
    assertThat(loadBalancers.values()).containsExactly(lb1, lb2, lb3, lb4);
    inOrder.verify(helper).updateBalancingState(same(READY), subchannelPickerCaptor.capture());
    assertThat(subchannelPickerCaptor.getValue()
            .pickSubchannel(mock(PickSubchannelArgs.class))
            .getSubchannel())
        .isSameInstanceAs(mockSubchannel4);
    // The order of the following four handleResolvedAddresses() does not matter. We want to verify
    // they are after helper.updateBalancingState()
    inOrder.verify(lb1).handleResolvedAddresses(resolvedAddressesCaptor1.capture());
    assertThat(resolvedAddressesCaptor1.getValue().getAddresses()).containsExactly(eag12);
    inOrder.verify(lb2).handleResolvedAddresses(resolvedAddressesCaptor2.capture());
    assertThat(resolvedAddressesCaptor2.getValue().getAddresses()).containsExactly(eag22);
    inOrder.verify(lb3).handleResolvedAddresses(resolvedAddressesCaptor3.capture());
    assertThat(resolvedAddressesCaptor3.getValue().getAddresses()).containsExactly(eag32);
    inOrder.verify(lb4).handleResolvedAddresses(resolvedAddressesCaptor4.capture());
    assertThat(resolvedAddressesCaptor4.getValue().getAddresses()).containsExactly(eag42);
    assertThat(fakeClock.getPendingTasks(failOverTaskFilter)).isEmpty();
    assertThat(fakeClock.getPendingTasks(deactivationTaskFilter)).hasSize(1);

    // 15 min passes - P0 C, P1 R, P2 N/A
    verify(lb2, never()).shutdown();
    fakeClock.forwardTime(15, TimeUnit.MINUTES);
    assertThat(fakeClock.getPendingTasks(failOverTaskFilter)).isEmpty();
    assertThat(fakeClock.getPendingTasks(deactivationTaskFilter)).isEmpty();
    inOrder.verify(lb2).shutdown();
    inOrder.verifyNoMoreInteractions();

    // EDS update, locality removed: P0 sz1, sz3, - P0 C, P1 N/A, sz4 R&D
    localityInfo1 =
        new LocalityLbEndpoints(ImmutableList.of(lbEndpoint11), 1, 0);
    localityInfo3 =
        new LocalityLbEndpoints(ImmutableList.of(lbEndpoint31), 3, 0);
    final ImmutableMap<Locality, LocalityLbEndpoints> localityInfoMap3 = ImmutableMap.of(
        locality1, localityInfo1,locality3, localityInfo3);
    syncContext.execute(new Runnable() {
      @Override
      public void run() {
        localityStore.updateLocalityStore(localityInfoMap3);
      }
    });
    inOrder.verify(helper).updateBalancingState(CONNECTING, BUFFER_PICKER);
    // The order of the following two handleResolvedAddresses() does not matter. We want to verify
    // they are after helper.updateBalancingState()
    inOrder.verify(lb1).handleResolvedAddresses(resolvedAddressesCaptor1.capture());
    assertThat(resolvedAddressesCaptor1.getValue().getAddresses()).containsExactly(eag11);
    inOrder.verify(lb3).handleResolvedAddresses(resolvedAddressesCaptor3.capture());
    assertThat(resolvedAddressesCaptor3.getValue().getAddresses()).containsExactly(eag31);
    inOrder.verifyNoMoreInteractions();
    assertThat(fakeClock.getPendingTasks(failOverTaskFilter)).isEmpty();
    assertThat(fakeClock.getPendingTasks(deactivationTaskFilter)).hasSize(1);

    // sz3 gets READY - P0 R, P1 N/A, sz4 R&D
    final Helper newHelper3 = childHelpers.get("sz3");
    final Subchannel newMockSubchannel3 = mock(Subchannel.class);
    syncContext.execute(new Runnable() {
      @Override
      public void run() {
        newHelper3.updateBalancingState(
            READY,
            new SubchannelPicker() {
              @Override
              public PickResult pickSubchannel(PickSubchannelArgs args) {
                return PickResult.withSubchannel(newMockSubchannel3);
              }
            }
        );
      }
    });
    assertThat(fakeClock.getPendingTasks(failOverTaskFilter)).isEmpty();
    assertThat(fakeClock.getPendingTasks(deactivationTaskFilter)).hasSize(1);
    inOrder.verify(helper).updateBalancingState(same(READY), subchannelPickerCaptor.capture());
    assertThat(subchannelPickerCaptor.getValue()
            .pickSubchannel(mock(PickSubchannelArgs.class))
            .getSubchannel())
        .isSameInstanceAs(newMockSubchannel3);
    inOrder.verifyNoMoreInteractions();

    // EDS update, locality comes back and another removed: P0 sz1, P1 sz4 - P0 C, P1 R, sz3 R&D
    localityInfo1 =
        new LocalityLbEndpoints(ImmutableList.of(lbEndpoint11), 1, 0);
    localityInfo4 =
        new LocalityLbEndpoints(ImmutableList.of(lbEndpoint41), 4, 1);
    final ImmutableMap<Locality, LocalityLbEndpoints> localityInfoMap4 = ImmutableMap.of(
        locality1, localityInfo1,locality4, localityInfo4);
    syncContext.execute(new Runnable() {
      @Override
      public void run() {
        localityStore.updateLocalityStore(localityInfoMap4);
      }
    });
    inOrder.verify(helper, atLeastOnce()).updateBalancingState(
        same(READY), subchannelPickerCaptor.capture());
    assertThat(subchannelPickerCaptor.getValue()
        .pickSubchannel(mock(PickSubchannelArgs.class))
        .getSubchannel())
        .isSameInstanceAs(mockSubchannel4);
    // The order of the following two handleResolvedAddresses() does not matter. We want to verify
    // they are after helper.updateBalancingState()
    inOrder.verify(lb1).handleResolvedAddresses(resolvedAddressesCaptor1.capture());
    assertThat(resolvedAddressesCaptor1.getValue().getAddresses()).containsExactly(eag11);
    inOrder.verify(lb4).handleResolvedAddresses(resolvedAddressesCaptor4.capture());
    assertThat(resolvedAddressesCaptor4.getValue().getAddresses()).containsExactly(eag41);
    inOrder.verifyNoMoreInteractions();
    assertThat(fakeClock.getPendingTasks(failOverTaskFilter)).isEmpty();
    assertThat(fakeClock.getPendingTasks(deactivationTaskFilter)).hasSize(1);

    verify(lb1, never()).shutdown();
    verify(lb3, never()).shutdown();
    verify(lb4, never()).shutdown();
    localityStore.reset();
    assertThat(fakeClock.getPendingTasks(deactivationTaskFilter)).isEmpty();
    verify(lb1).shutdown();
    verify(lb3).shutdown();
    verify(lb4).shutdown();
  }

  private static final class FakeLoadStatsStore implements LoadStatsStore {

    Map<Locality, ClientLoadCounter> localityCounters = new HashMap<>();

    @Override
    public ClusterStats generateLoadReport() {
      throw new AssertionError("Should not be called");
    }

    @Override
    public void addLocality(Locality locality) {
      assertThat(localityCounters).doesNotContainKey(locality);
      localityCounters.put(locality, new ClientLoadCounter());
    }

    @Override
    public void removeLocality(Locality locality) {
      assertThat(localityCounters).containsKey(locality);
      localityCounters.remove(locality);
    }

    @Nullable
    @Override
    public ClientLoadCounter getLocalityCounter(Locality locality) {
      return localityCounters.get(locality);
    }

    @Override
    public void recordDroppedRequest(String category) {
      // NO-OP, verify by invocations.
    }
  }

  private static final class FakeOrcaReportingHelperWrapper extends OrcaReportingHelperWrapper {

    final Helper delegate;
    long reportIntervalNanos = -1;

    FakeOrcaReportingHelperWrapper(Helper delegate) {
      this.delegate = checkNotNull(delegate, "delegate");
    }

    @Override
    public void setReportingConfig(OrcaReportingConfig config) {
      reportIntervalNanos = config.getReportIntervalNanos();
    }

    @Override
    public Helper asHelper() {
      return delegate;
    }
  }
}
