/*
 * Copyright 2023 The gRPC Authors
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
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.github.xds.data.orca.v3.OrcaLoadReport;
import com.github.xds.service.orca.v3.OrcaLoadReportRequest;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.protobuf.Duration;
import io.grpc.Attributes;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ConnectivityState;
import io.grpc.ConnectivityStateInfo;
import io.grpc.EquivalentAddressGroup;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancer.CreateSubchannelArgs;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancer.PickResult;
import io.grpc.LoadBalancer.ResolvedAddresses;
import io.grpc.LoadBalancer.Subchannel;
import io.grpc.LoadBalancer.SubchannelPicker;
import io.grpc.LoadBalancer.SubchannelStateListener;
import io.grpc.SynchronizationContext;
import io.grpc.internal.FakeClock;
import io.grpc.internal.TestUtils;
import io.grpc.services.InternalCallMetricRecorder;
import io.grpc.services.MetricReport;
import io.grpc.util.AbstractTestHelper;
import io.grpc.util.MultiChildLoadBalancer.ChildLbState;
import io.grpc.xds.WeightedRoundRobinLoadBalancer.StaticStrideScheduler;
import io.grpc.xds.WeightedRoundRobinLoadBalancer.WeightedChildLbState;
import io.grpc.xds.WeightedRoundRobinLoadBalancer.WeightedRoundRobinLoadBalancerConfig;
import io.grpc.xds.WeightedRoundRobinLoadBalancer.WeightedRoundRobinPicker;
import java.net.SocketAddress;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.mockito.stubbing.Answer;

@RunWith(JUnit4.class)
public class WeightedRoundRobinLoadBalancerTest {
  @Rule
  public final MockitoRule mockito = MockitoJUnit.rule();

  private final TestHelper testHelperInstance = new TestHelper();
  private Helper helper = mock(Helper.class, delegatesTo(testHelperInstance));

  @Mock
  private LoadBalancer.PickSubchannelArgs mockArgs;

  @Captor
  private ArgumentCaptor<SubchannelPicker> pickerCaptor;
  @Captor
  private ArgumentCaptor<SubchannelPicker> pickerCaptor2;

  private final List<EquivalentAddressGroup> servers = Lists.newArrayList();
  private final Map<List<EquivalentAddressGroup>, Subchannel> subchannels = Maps.newLinkedHashMap();
  private final Map<Subchannel, Subchannel> mockToRealSubChannelMap = new HashMap<>();
  private final Map<Subchannel, SubchannelStateListener> subchannelStateListeners =
        Maps.newLinkedHashMap();

  private final Queue<ClientCall<OrcaLoadReportRequest, OrcaLoadReport>> oobCalls =
          new ConcurrentLinkedQueue<>();

  private WeightedRoundRobinLoadBalancer wrr;

  private final FakeClock fakeClock = new FakeClock();

  private WeightedRoundRobinLoadBalancerConfig weightedConfig =
          WeightedRoundRobinLoadBalancerConfig.newBuilder().build();

  private static final Attributes.Key<String> MAJOR_KEY = Attributes.Key.create("major-key");

  private final Attributes affinity =
            Attributes.newBuilder().set(MAJOR_KEY, "I got the keys").build();

  private final SynchronizationContext syncContext = new SynchronizationContext(
      new Thread.UncaughtExceptionHandler() {
          @Override
          public void uncaughtException(Thread t, Throwable e) {
            throw new AssertionError(e);
          }
      });

  @Before
  public void setup() {
    for (int i = 0; i < 3; i++) {
      SocketAddress addr = new FakeSocketAddress("server" + i);
      EquivalentAddressGroup eag = new EquivalentAddressGroup(addr);
      servers.add(eag);
      Subchannel sc = helper.createSubchannel(CreateSubchannelArgs.newBuilder().setAddresses(eag)
          .build());
      Channel channel = mock(Channel.class);
      when(channel.newCall(any(), any())).then(
          new Answer<ClientCall<OrcaLoadReportRequest, OrcaLoadReport>>() {
            @SuppressWarnings("unchecked")
            @Override
            public ClientCall<OrcaLoadReportRequest, OrcaLoadReport> answer(
                    InvocationOnMock invocation) throws Throwable {
              ClientCall<OrcaLoadReportRequest, OrcaLoadReport> clientCall = mock(ClientCall.class);
              oobCalls.add(clientCall);
              return clientCall;
            }
          });
      testHelperInstance.setChannel(mockToRealSubChannelMap.get(sc), channel);
      subchannels.put(Arrays.asList(eag), sc);
    }
    wrr = new WeightedRoundRobinLoadBalancer(helper, fakeClock.getDeadlineTicker(),
        new FakeRandom(0));

    verify(helper, times(3)).createSubchannel(any(CreateSubchannelArgs.class));
  }

  @Test
  public void wrrLifeCycle() {
    syncContext.execute(() -> wrr.acceptResolvedAddresses(ResolvedAddresses.newBuilder()
                .setAddresses(servers).setLoadBalancingPolicyConfig(weightedConfig)
            .setAttributes(affinity).build()));
    verify(helper, times(6)).createSubchannel(
                any(CreateSubchannelArgs.class));
    assertThat(fakeClock.getPendingTasks().size()).isEqualTo(1);

    Iterator<Subchannel> it = subchannels.values().iterator();
    Subchannel readySubchannel1 = it.next();
    getSubchannelStateListener(readySubchannel1).onSubchannelState(ConnectivityStateInfo
            .forNonError(ConnectivityState.READY));
    Subchannel readySubchannel2  = it.next();
    getSubchannelStateListener(readySubchannel2).onSubchannelState(ConnectivityStateInfo
            .forNonError(ConnectivityState.READY));
    Subchannel connectingSubchannel = it.next();
    getSubchannelStateListener(connectingSubchannel).onSubchannelState(ConnectivityStateInfo
            .forNonError(ConnectivityState.CONNECTING));
    verify(helper, times(2)).updateBalancingState(
            eq(ConnectivityState.READY), pickerCaptor.capture());
    assertThat(pickerCaptor.getAllValues().size()).isEqualTo(2);
    WeightedRoundRobinPicker weightedPicker =
        (WeightedRoundRobinPicker) pickerCaptor.getAllValues().get(0);
    assertThat(weightedPicker.getChildren().size()).isEqualTo(1);
    weightedPicker = (WeightedRoundRobinPicker) pickerCaptor.getAllValues().get(1);
    assertThat(weightedPicker.getChildren().size()).isEqualTo(2);
    String weightedPickerStr = weightedPicker.toString();
    assertThat(weightedPickerStr).contains("enableOobLoadReport=false");
    assertThat(weightedPickerStr).contains("errorUtilizationPenalty=1.0");
    assertThat(weightedPickerStr).contains("list=");

    WeightedChildLbState weightedChild1 = (WeightedChildLbState) getChild(weightedPicker, 0);
    WeightedChildLbState weightedChild2 = (WeightedChildLbState) getChild(weightedPicker, 1);
    weightedChild1.new OrcaReportListener(weightedConfig.errorUtilizationPenalty).onLoadReport(
        InternalCallMetricRecorder.createMetricReport(
            0.1, 0, 0.1, 1, 0, new HashMap<>(), new HashMap<>(), new HashMap<>()));
    weightedChild2.new OrcaReportListener(weightedConfig.errorUtilizationPenalty).onLoadReport(
        InternalCallMetricRecorder.createMetricReport(
            0.2, 0, 0.1, 1, 0, new HashMap<>(), new HashMap<>(), new HashMap<>()));
    assertThat(fakeClock.forwardTime(11, TimeUnit.SECONDS)).isEqualTo(1);

    assertThat(getAddressesFromPick(weightedPicker)).isEqualTo(weightedChild1.getEag());
    assertThat(fakeClock.getPendingTasks().size()).isEqualTo(1);
    weightedConfig = WeightedRoundRobinLoadBalancerConfig.newBuilder()
        .setWeightUpdatePeriodNanos(500_000_000L) //.5s
        .build();
    syncContext.execute(() -> wrr.acceptResolvedAddresses(ResolvedAddresses.newBuilder()
        .setAddresses(servers).setLoadBalancingPolicyConfig(weightedConfig)
        .setAttributes(affinity).build()));
    assertThat(fakeClock.getPendingTasks().size()).isEqualTo(1);

    syncContext.execute(() -> wrr.shutdown());
    for (Subchannel subchannel: subchannels.values()) {
      verify(subchannel).shutdown();
    }
    assertThat(fakeClock.getPendingTasks().size()).isEqualTo(0);
    verifyNoMoreInteractions(mockArgs);
  }

  /**
   * Picks subchannel using mockArgs, gets its EAG, and then strips the Attrs to make a key.
   */
  private EquivalentAddressGroup getAddressesFromPick(WeightedRoundRobinPicker weightedPicker) {
    return TestUtils.stripAttrs(
        weightedPicker.pickSubchannel(mockArgs).getSubchannel().getAddresses());
  }

  @Test
  public void enableOobLoadReportConfig() {
    syncContext.execute(() -> wrr.acceptResolvedAddresses(ResolvedAddresses.newBuilder()
            .setAddresses(servers).setLoadBalancingPolicyConfig(weightedConfig)
            .setAttributes(affinity).build()));
    verify(helper, times(6)).createSubchannel(
            any(CreateSubchannelArgs.class));
    Iterator<Subchannel> it = subchannels.values().iterator();
    Subchannel readySubchannel1 = it.next();
    getSubchannelStateListener(readySubchannel1).onSubchannelState(ConnectivityStateInfo
            .forNonError(ConnectivityState.READY));
    Subchannel readySubchannel2  = it.next();
    getSubchannelStateListener(readySubchannel2).onSubchannelState(ConnectivityStateInfo
            .forNonError(ConnectivityState.READY));
    verify(helper, times(2)).updateBalancingState(
            eq(ConnectivityState.READY), pickerCaptor.capture());
    WeightedRoundRobinPicker weightedPicker =
        (WeightedRoundRobinPicker) pickerCaptor.getAllValues().get(1);
    WeightedChildLbState weightedChild1 = (WeightedChildLbState) getChild(weightedPicker, 0);
    WeightedChildLbState weightedChild2 = (WeightedChildLbState) getChild(weightedPicker, 1);
    weightedChild1.new OrcaReportListener(weightedConfig.errorUtilizationPenalty).onLoadReport(
        InternalCallMetricRecorder.createMetricReport(
            0.1, 0, 0.1, 1, 0, new HashMap<>(), new HashMap<>(), new HashMap<>()));
    weightedChild2.new OrcaReportListener(weightedConfig.errorUtilizationPenalty).onLoadReport(
        InternalCallMetricRecorder.createMetricReport(
            0.9, 0, 0.1, 1, 0, new HashMap<>(), new HashMap<>(), new HashMap<>()));
    assertThat(fakeClock.forwardTime(11, TimeUnit.SECONDS)).isEqualTo(1);
    PickResult pickResult = weightedPicker.pickSubchannel(mockArgs);
    assertThat(getAddresses(pickResult))
        .isEqualTo(weightedChild1.getEag());
    assertThat(pickResult.getStreamTracerFactory()).isNotNull(); // verify per-request listener
    assertThat(oobCalls.isEmpty()).isTrue();

    weightedConfig = WeightedRoundRobinLoadBalancerConfig.newBuilder().setEnableOobLoadReport(true)
            .setOobReportingPeriodNanos(20_030_000_000L)
            .build();
    syncContext.execute(() -> wrr.acceptResolvedAddresses(ResolvedAddresses.newBuilder()
            .setAddresses(servers).setLoadBalancingPolicyConfig(weightedConfig)
            .setAttributes(affinity).build()));
    verify(helper, times(3)).updateBalancingState(
            eq(ConnectivityState.READY), pickerCaptor2.capture());
    weightedPicker = (WeightedRoundRobinPicker) pickerCaptor2.getAllValues().get(2);
    pickResult = weightedPicker.pickSubchannel(mockArgs);
    assertThat(getAddresses(pickResult))
        .isEqualTo(weightedChild1.getEag());
    assertThat(pickResult.getStreamTracerFactory()).isNull();
    OrcaLoadReportRequest golden = OrcaLoadReportRequest.newBuilder().setReportInterval(
            Duration.newBuilder().setSeconds(20).setNanos(30000000).build()).build();
    assertThat(oobCalls.size()).isEqualTo(2);
    verify(oobCalls.poll()).sendMessage(eq(golden));
    verify(oobCalls.poll()).sendMessage(eq(golden));
  }

  private void pickByWeight(MetricReport r1, MetricReport r2, MetricReport r3,
                            double subchannel1PickRatio, double subchannel2PickRatio,
                            double subchannel3PickRatio) {
    syncContext.execute(() -> wrr.acceptResolvedAddresses(ResolvedAddresses.newBuilder()
            .setAddresses(servers).setLoadBalancingPolicyConfig(weightedConfig)
            .setAttributes(affinity).build()));
    verify(helper, times(6)).createSubchannel(
            any(CreateSubchannelArgs.class));
    assertThat(fakeClock.getPendingTasks().size()).isEqualTo(1);

    Iterator<Subchannel> it = subchannels.values().iterator();
    Subchannel readySubchannel1 = it.next();
    getSubchannelStateListener(readySubchannel1).onSubchannelState(ConnectivityStateInfo
            .forNonError(ConnectivityState.READY));
    Subchannel readySubchannel2  = it.next();
    getSubchannelStateListener(readySubchannel2).onSubchannelState(ConnectivityStateInfo
            .forNonError(ConnectivityState.READY));
    Subchannel readySubchannel3 = it.next();
    getSubchannelStateListener(readySubchannel3).onSubchannelState(ConnectivityStateInfo
            .forNonError(ConnectivityState.READY));
    verify(helper, times(3)).updateBalancingState(
            eq(ConnectivityState.READY), pickerCaptor.capture());
    WeightedRoundRobinPicker weightedPicker =
        (WeightedRoundRobinPicker) pickerCaptor.getAllValues().get(2);
    WeightedChildLbState weightedChild1 = (WeightedChildLbState) getChild(weightedPicker, 0);
    WeightedChildLbState weightedChild2 = (WeightedChildLbState) getChild(weightedPicker, 1);
    WeightedChildLbState weightedChild3 = (WeightedChildLbState) getChild(weightedPicker, 2);
    weightedChild1.new OrcaReportListener(weightedConfig.errorUtilizationPenalty).onLoadReport(r1);
    weightedChild2.new OrcaReportListener(weightedConfig.errorUtilizationPenalty).onLoadReport(r2);
    weightedChild3.new OrcaReportListener(weightedConfig.errorUtilizationPenalty).onLoadReport(r3);

    assertThat(fakeClock.forwardTime(11, TimeUnit.SECONDS)).isEqualTo(1);
    Map<EquivalentAddressGroup, Integer> pickCount = new HashMap<>();
    for (int i = 0; i < 10000; i++) {
      EquivalentAddressGroup result = getAddressesFromPick(weightedPicker);
      pickCount.put(result, pickCount.getOrDefault(result, 0) + 1);
    }
    assertThat(pickCount.size()).isEqualTo(3);
    assertThat(Math.abs(pickCount.get(weightedChild1.getEag()) / 10000.0 - subchannel1PickRatio))
        .isAtMost(0.0002);
    assertThat(Math.abs(pickCount.get(weightedChild2.getEag()) / 10000.0 - subchannel2PickRatio ))
        .isAtMost(0.0002);
    assertThat(Math.abs(pickCount.get(weightedChild3.getEag()) / 10000.0 - subchannel3PickRatio ))
        .isAtMost(0.0002);
  }

  private SubchannelStateListener getSubchannelStateListener(Subchannel mockSubChannel) {
    return subchannelStateListeners.get(mockToRealSubChannelMap.get(mockSubChannel));
  }

  private static ChildLbState getChild(WeightedRoundRobinPicker picker, int index) {
    return picker.getChildren().get(index);
  }

  @Test
  public void pickByWeight_largeWeight() {
    MetricReport report1 = InternalCallMetricRecorder.createMetricReport(
        0.1, 0, 0.1, 999, 0, new HashMap<>(), new HashMap<>(), new HashMap<>());
    MetricReport report2 = InternalCallMetricRecorder.createMetricReport(
        0.9, 0, 0.1, 2, 0, new HashMap<>(), new HashMap<>(), new HashMap<>());
    MetricReport report3 = InternalCallMetricRecorder.createMetricReport(
        0.86, 0, 0.1, 100, 0, new HashMap<>(), new HashMap<>(), new HashMap<>());
    double meanWeight = (999 / 0.1 + 2 / 0.9 + 100 / 0.86) / 3;
    double cappedMin = meanWeight * 0.1; // min capped at minRatio * meanWeight
    double totalWeight = 999 / 0.1 + cappedMin + cappedMin;
    pickByWeight(report1, report2, report3, 999 / 0.1 / totalWeight, cappedMin / totalWeight,
            cappedMin / totalWeight);
  }

  @Test
  public void pickByWeight_largeWeight_useApplicationUtilization() {
    MetricReport report1 = InternalCallMetricRecorder.createMetricReport(
        0.44, 0.1, 0.1, 999, 0, new HashMap<>(), new HashMap<>(), new HashMap<>());
    MetricReport report2 = InternalCallMetricRecorder.createMetricReport(
        0.12, 0.9, 0.1, 2, 0, new HashMap<>(), new HashMap<>(), new HashMap<>());
    MetricReport report3 = InternalCallMetricRecorder.createMetricReport(
        0.33, 0.86, 0.1, 100, 0, new HashMap<>(), new HashMap<>(), new HashMap<>());
    double meanWeight = (999 / 0.1 + 2 / 0.9 + 100 / 0.86) / 3;
    double cappedMin = meanWeight * 0.1;
    double totalWeight = 999 / 0.1 + cappedMin + cappedMin; // min capped at minRatio * meanWeight
    pickByWeight(report1, report2, report3, 999 / 0.1 / totalWeight, cappedMin / totalWeight,
        cappedMin / totalWeight);
  }

  @Test
  public void pickByWeight_largeWeight_withEps_defaultErrorUtilizationPenalty() {
    MetricReport report1 = InternalCallMetricRecorder.createMetricReport(
        0.1, 0, 0.1, 999, 13, new HashMap<>(), new HashMap<>(), new HashMap<>());
    MetricReport report2 = InternalCallMetricRecorder.createMetricReport(
        0.9, 0, 0.1, 2, 1.8, new HashMap<>(), new HashMap<>(), new HashMap<>());
    MetricReport report3 = InternalCallMetricRecorder.createMetricReport(
        0.86, 0, 0.1, 100, 3, new HashMap<>(), new HashMap<>(), new HashMap<>());
    double weight1 = 999 / (0.1 + 13 / 999F * weightedConfig.errorUtilizationPenalty); // ~5609.899
    double weight2 = 2 / (0.9 + 1.8 / 2F * weightedConfig.errorUtilizationPenalty); // ~0.317
    double weight3 = 100 / (0.86 + 3 / 100F * weightedConfig.errorUtilizationPenalty); // ~96.154
    double meanWeight = (weight1 + weight2 + weight3) / 3;
    double cappedMin = meanWeight * 0.1; // min capped at minRatio * meanWeight
    double totalWeight = weight1 + cappedMin + cappedMin;
    pickByWeight(report1, report2, report3, weight1 / totalWeight, cappedMin / totalWeight,
        cappedMin / totalWeight);
  }

  @Test
  public void pickByWeight_normalWeight() {
    MetricReport report1 = InternalCallMetricRecorder.createMetricReport(
        0.12, 0, 0.1, 22, 0, new HashMap<>(), new HashMap<>(), new HashMap<>());
    MetricReport report2 = InternalCallMetricRecorder.createMetricReport(
        0.28, 0, 0.1, 40, 0, new HashMap<>(), new HashMap<>(), new HashMap<>());
    MetricReport report3 = InternalCallMetricRecorder.createMetricReport(
        0.86, 0, 0.1, 100, 0, new HashMap<>(), new HashMap<>(), new HashMap<>());
    double totalWeight = 22 / 0.12 + 40 / 0.28 + 100 / 0.86;
    pickByWeight(report1, report2, report3, 22 / 0.12 / totalWeight,
            40 / 0.28 / totalWeight, 100 / 0.86 / totalWeight
    );
  }

  @Test
  public void pickByWeight_normalWeight_useApplicationUtilization() {
    MetricReport report1 = InternalCallMetricRecorder.createMetricReport(
        0.72, 0.12, 0.1, 22, 0, new HashMap<>(), new HashMap<>(), new HashMap<>());
    MetricReport report2 = InternalCallMetricRecorder.createMetricReport(
        0.98, 0.28, 0.1, 40, 0, new HashMap<>(), new HashMap<>(), new HashMap<>());
    MetricReport report3 = InternalCallMetricRecorder.createMetricReport(
        0.99, 0.86, 0.1, 100, 0, new HashMap<>(), new HashMap<>(), new HashMap<>());
    double totalWeight = 22 / 0.12 + 40 / 0.28 + 100 / 0.86;
    pickByWeight(report1, report2, report3, 22 / 0.12 / totalWeight,
        40 / 0.28 / totalWeight, 100 / 0.86 / totalWeight
    );
  }

  @Test
  public void pickByWeight_normalWeight_withEps_defaultErrorUtilizationPenalty() {
    MetricReport report1 = InternalCallMetricRecorder.createMetricReport(
        0.12, 0, 0.1, 22, 19.7, new HashMap<>(), new HashMap<>(), new HashMap<>());
    MetricReport report2 = InternalCallMetricRecorder.createMetricReport(
        0.28, 0, 0.1, 40, 0.998, new HashMap<>(), new HashMap<>(), new HashMap<>());
    MetricReport report3 = InternalCallMetricRecorder.createMetricReport(
        0.86, 0, 0.1, 100, 3.14159, new HashMap<>(), new HashMap<>(), new HashMap<>());
    double weight1 = 22 / (0.12 + 19.7 / 22F * weightedConfig.errorUtilizationPenalty);
    double weight2 = 40 / (0.28 + 0.998 / 40F * weightedConfig.errorUtilizationPenalty);
    double weight3 = 100 / (0.86 + 3.14159 / 100F * weightedConfig.errorUtilizationPenalty);
    double totalWeight = weight1 + weight2 + weight3;

    pickByWeight(report1, report2, report3, weight1 / totalWeight, weight2 / totalWeight,
        weight3 / totalWeight);
  }

  @Test
  public void pickByWeight_normalWeight_withEps_customErrorUtilizationPenalty() {
    weightedConfig = WeightedRoundRobinLoadBalancerConfig.newBuilder()
        .setErrorUtilizationPenalty(1.75F).build();

    MetricReport report1 = InternalCallMetricRecorder.createMetricReport(
        0.12, 0, 0.1, 22, 19.7, new HashMap<>(), new HashMap<>(), new HashMap<>());
    MetricReport report2 = InternalCallMetricRecorder.createMetricReport(
        0.28, 0, 0.1, 40, 0.998, new HashMap<>(), new HashMap<>(), new HashMap<>());
    MetricReport report3 = InternalCallMetricRecorder.createMetricReport(
        0.86, 0, 0.1, 100, 3.14159, new HashMap<>(), new HashMap<>(), new HashMap<>());
    double weight1 = 22 / (0.12 + 19.7 / 22F * weightedConfig.errorUtilizationPenalty);
    double weight2 = 40 / (0.28 + 0.998 / 40F * weightedConfig.errorUtilizationPenalty);
    double weight3 = 100 / (0.86 + 3.14159 / 100F * weightedConfig.errorUtilizationPenalty);
    double totalWeight = weight1 + weight2 + weight3;

    pickByWeight(report1, report2, report3, weight1 / totalWeight, weight2 / totalWeight,
        weight3 / totalWeight);
  }

  @Test
  public void pickByWeight_avgWeight_zeroCpuUtilization_withEps_customErrorUtilizationPenalty() {
    weightedConfig = WeightedRoundRobinLoadBalancerConfig.newBuilder()
        .setErrorUtilizationPenalty(1.75F).build();

    MetricReport report1 = InternalCallMetricRecorder.createMetricReport(
        0, 0, 0.1, 22, 19.7, new HashMap<>(), new HashMap<>(), new HashMap<>());
    MetricReport report2 = InternalCallMetricRecorder.createMetricReport(
        0, 0, 0.1, 40, 0.998, new HashMap<>(), new HashMap<>(), new HashMap<>());
    MetricReport report3 = InternalCallMetricRecorder.createMetricReport(
        0, 0, 0.1, 100, 3.14159, new HashMap<>(), new HashMap<>(), new HashMap<>());
    double avgSubchannelPickRatio = 1.0 / 3;

    pickByWeight(report1, report2, report3, avgSubchannelPickRatio, avgSubchannelPickRatio,
        avgSubchannelPickRatio);
  }

  @Test
  public void emptyConfig() {
    assertThat(wrr.acceptResolvedAddresses(ResolvedAddresses.newBuilder()
            .setAddresses(servers).setLoadBalancingPolicyConfig(null)
            .setAttributes(affinity).build())).isFalse();
    verify(helper, times(3)).createSubchannel(any(CreateSubchannelArgs.class));
    verify(helper).updateBalancingState(eq(ConnectivityState.TRANSIENT_FAILURE), any());
    assertThat(fakeClock.getPendingTasks()).isEmpty();

    syncContext.execute(() -> wrr.acceptResolvedAddresses(ResolvedAddresses.newBuilder()
            .setAddresses(servers).setLoadBalancingPolicyConfig(weightedConfig)
            .setAttributes(affinity).build()));
    verify(helper, times(6)).createSubchannel(
            any(CreateSubchannelArgs.class));
    verify(helper).updateBalancingState(eq(ConnectivityState.CONNECTING), pickerCaptor.capture());
    assertThat(pickerCaptor.getValue().getClass().getName())
        .isEqualTo("io.grpc.util.RoundRobinLoadBalancer$EmptyPicker");
    assertThat(fakeClock.forwardTime(11, TimeUnit.SECONDS)).isEqualTo(1);
  }

  @Test
  public void blackoutPeriod() {
    syncContext.execute(() -> wrr.acceptResolvedAddresses(ResolvedAddresses.newBuilder()
            .setAddresses(servers).setLoadBalancingPolicyConfig(weightedConfig)
            .setAttributes(affinity).build()));
    verify(helper, times(6)).createSubchannel(
            any(CreateSubchannelArgs.class));
    assertThat(fakeClock.getPendingTasks().size()).isEqualTo(1);

    Iterator<Subchannel> it = subchannels.values().iterator();
    Subchannel readySubchannel1 = it.next();
    getSubchannelStateListener(readySubchannel1).onSubchannelState(ConnectivityStateInfo
            .forNonError(ConnectivityState.READY));
    Subchannel readySubchannel2  = it.next();
    getSubchannelStateListener(readySubchannel2).onSubchannelState(ConnectivityStateInfo
            .forNonError(ConnectivityState.READY));
    verify(helper, times(2)).updateBalancingState(
            eq(ConnectivityState.READY), pickerCaptor.capture());
    WeightedRoundRobinPicker weightedPicker =
        (WeightedRoundRobinPicker) pickerCaptor.getAllValues().get(1);
    WeightedChildLbState weightedChild1 = (WeightedChildLbState) getChild(weightedPicker, 0);
    WeightedChildLbState weightedChild2 = (WeightedChildLbState) getChild(weightedPicker, 1);
    weightedChild1.new OrcaReportListener(weightedConfig.errorUtilizationPenalty).onLoadReport(
        InternalCallMetricRecorder.createMetricReport(
            0.1, 0, 0.1, 1, 0, new HashMap<>(), new HashMap<>(), new HashMap<>()));
    weightedChild2.new OrcaReportListener(weightedConfig.errorUtilizationPenalty).onLoadReport(
        InternalCallMetricRecorder.createMetricReport(
            0.2, 0, 0.1, 1, 0, new HashMap<>(), new HashMap<>(), new HashMap<>()));
    assertThat(fakeClock.forwardTime(5, TimeUnit.SECONDS)).isEqualTo(1);
    Map<EquivalentAddressGroup, Integer> pickCount = new HashMap<>();
    for (int i = 0; i < 10000; i++) {
      EquivalentAddressGroup result = getAddressesFromPick(weightedPicker);
      pickCount.put(result, pickCount.getOrDefault(result, 0) + 1);
    }
    assertThat(pickCount.size()).isEqualTo(2);
    // within blackout period, fallback to simple round robin
    assertThat(Math.abs(pickCount.get(weightedChild1.getEag()) / 10000.0 - 0.5)).isLessThan(0.002);
    assertThat(Math.abs(pickCount.get(weightedChild2.getEag()) / 10000.0 - 0.5)).isLessThan(0.002);

    assertThat(fakeClock.forwardTime(5, TimeUnit.SECONDS)).isEqualTo(1);
    pickCount = new HashMap<>();
    for (int i = 0; i < 10000; i++) {
      EquivalentAddressGroup result = getAddressesFromPick(weightedPicker);
      pickCount.put(result, pickCount.getOrDefault(result, 0) + 1);
    }
    assertThat(pickCount.size()).isEqualTo(2);
    // after blackout period
    assertThat(Math.abs(pickCount.get(weightedChild1.getEag()) / 10000.0 - 2.0 / 3))
            .isLessThan(0.002);
    assertThat(Math.abs(pickCount.get(weightedChild2.getEag()) / 10000.0 - 1.0 / 3))
            .isLessThan(0.002);
  }

  @Test
  public void updateWeightTimer() {
    syncContext.execute(() -> wrr.acceptResolvedAddresses(ResolvedAddresses.newBuilder()
        .setAddresses(servers).setLoadBalancingPolicyConfig(weightedConfig)
        .setAttributes(affinity).build()));
    verify(helper, times(6)).createSubchannel(
        any(CreateSubchannelArgs.class));
    assertThat(fakeClock.getPendingTasks().size()).isEqualTo(1);

    Iterator<Subchannel> it = subchannels.values().iterator();
    Subchannel readySubchannel1 = it.next();
    getSubchannelStateListener(readySubchannel1).onSubchannelState(ConnectivityStateInfo
        .forNonError(ConnectivityState.READY));
    Subchannel readySubchannel2  = it.next();
    getSubchannelStateListener(readySubchannel2).onSubchannelState(ConnectivityStateInfo
        .forNonError(ConnectivityState.READY));
    Subchannel connectingSubchannel = it.next();
    getSubchannelStateListener(connectingSubchannel).onSubchannelState(ConnectivityStateInfo
        .forNonError(ConnectivityState.CONNECTING));
    verify(helper, times(2)).updateBalancingState(
        eq(ConnectivityState.READY), pickerCaptor.capture());
    assertThat(pickerCaptor.getAllValues().size()).isEqualTo(2);
    WeightedRoundRobinPicker weightedPicker =
        (WeightedRoundRobinPicker) pickerCaptor.getAllValues().get(0);
    assertThat(weightedPicker.getChildren().size()).isEqualTo(1);
    weightedPicker = (WeightedRoundRobinPicker) pickerCaptor.getAllValues().get(1);
    assertThat(weightedPicker.getChildren().size()).isEqualTo(2);
    WeightedChildLbState weightedChild1 = (WeightedChildLbState) getChild(weightedPicker, 0);
    WeightedChildLbState weightedChild2 = (WeightedChildLbState) getChild(weightedPicker, 1);
    weightedChild1.new OrcaReportListener(weightedConfig.errorUtilizationPenalty).onLoadReport(
        InternalCallMetricRecorder.createMetricReport(
            0.1, 0, 0.1, 1, 0, new HashMap<>(), new HashMap<>(), new HashMap<>()));
    weightedChild2.new OrcaReportListener(weightedConfig.errorUtilizationPenalty).onLoadReport(
        InternalCallMetricRecorder.createMetricReport(
            0.2, 0, 0.1, 1, 0, new HashMap<>(), new HashMap<>(), new HashMap<>()));
    assertThat(fakeClock.forwardTime(11, TimeUnit.SECONDS)).isEqualTo(1);
    assertThat(getAddressesFromPick(weightedPicker))
        .isEqualTo(weightedChild1.getEag());
    assertThat(fakeClock.getPendingTasks().size()).isEqualTo(1);
    weightedConfig = WeightedRoundRobinLoadBalancerConfig.newBuilder()
        .setWeightUpdatePeriodNanos(500_000_000L) //.5s
        .build();
    syncContext.execute(() -> wrr.acceptResolvedAddresses(ResolvedAddresses.newBuilder()
        .setAddresses(servers).setLoadBalancingPolicyConfig(weightedConfig)
        .setAttributes(affinity).build()));
    assertThat(fakeClock.getPendingTasks().size()).isEqualTo(1);
    weightedChild1.new OrcaReportListener(weightedConfig.errorUtilizationPenalty).onLoadReport(
        InternalCallMetricRecorder.createMetricReport(
            0.2, 0, 0.1, 1, 0, new HashMap<>(), new HashMap<>(), new HashMap<>()));
    weightedChild2.new OrcaReportListener(weightedConfig.errorUtilizationPenalty).onLoadReport(
        InternalCallMetricRecorder.createMetricReport(
            0.1, 0, 0.1, 1, 0, new HashMap<>(), new HashMap<>(), new HashMap<>()));
    //timer fires, new weight updated
    assertThat(fakeClock.forwardTime(500, TimeUnit.MILLISECONDS)).isEqualTo(1);
    assertThat(getAddressesFromPick(weightedPicker))
        .isEqualTo(weightedChild2.getEag());
    assertThat(getAddressesFromPick(weightedPicker))
        .isEqualTo(weightedChild1.getEag());
  }

  @Test
  public void weightExpired() {
    syncContext.execute(() -> wrr.acceptResolvedAddresses(ResolvedAddresses.newBuilder()
            .setAddresses(servers).setLoadBalancingPolicyConfig(weightedConfig)
            .setAttributes(affinity).build()));
    verify(helper, times(6)).createSubchannel(
            any(CreateSubchannelArgs.class));
    assertThat(fakeClock.getPendingTasks().size()).isEqualTo(1);

    Iterator<Subchannel> it = subchannels.values().iterator();
    Subchannel readySubchannel1 = it.next();
    getSubchannelStateListener(readySubchannel1).onSubchannelState(ConnectivityStateInfo
            .forNonError(ConnectivityState.READY));
    Subchannel readySubchannel2  = it.next();
    getSubchannelStateListener(readySubchannel2).onSubchannelState(ConnectivityStateInfo
            .forNonError(ConnectivityState.READY));
    verify(helper, times(2)).updateBalancingState(
            eq(ConnectivityState.READY), pickerCaptor.capture());
    WeightedRoundRobinPicker weightedPicker =
        (WeightedRoundRobinPicker) pickerCaptor.getAllValues().get(1);
    WeightedChildLbState weightedChild1 = (WeightedChildLbState) getChild(weightedPicker, 0);
    WeightedChildLbState weightedChild2 = (WeightedChildLbState) getChild(weightedPicker, 1);
    weightedChild1.new OrcaReportListener(weightedConfig.errorUtilizationPenalty).onLoadReport(
        InternalCallMetricRecorder.createMetricReport(
            0.1, 0, 0.1, 1, 0, new HashMap<>(), new HashMap<>(), new HashMap<>()));
    weightedChild2.new OrcaReportListener(weightedConfig.errorUtilizationPenalty).onLoadReport(
        InternalCallMetricRecorder.createMetricReport(
            0.2, 0, 0.1, 1, 0, new HashMap<>(), new HashMap<>(), new HashMap<>()));
    assertThat(fakeClock.forwardTime(10, TimeUnit.SECONDS)).isEqualTo(1);
    Map<EquivalentAddressGroup, Integer> pickCount = new HashMap<>();
    for (int i = 0; i < 1000; i++) {
      EquivalentAddressGroup result = getAddressesFromPick(weightedPicker);
      pickCount.put(result, pickCount.getOrDefault(result, 0) + 1);
    }
    assertThat(pickCount.size()).isEqualTo(2);
    assertThat(Math.abs(pickCount.get(weightedChild1.getEag()) / 1000.0 - 2.0 / 3))
            .isLessThan(0.002);
    assertThat(Math.abs(pickCount.get(weightedChild2.getEag()) / 1000.0 - 1.0 / 3))
            .isLessThan(0.002);

    // weight expired, fallback to simple round robin
    assertThat(fakeClock.forwardTime(300, TimeUnit.SECONDS)).isEqualTo(1);
    pickCount = new HashMap<>();
    for (int i = 0; i < 1000; i++) {
      EquivalentAddressGroup result = getAddressesFromPick(weightedPicker);
      pickCount.put(result, pickCount.getOrDefault(result, 0) + 1);
    }
    assertThat(pickCount.size()).isEqualTo(2);
    assertThat(Math.abs(pickCount.get(weightedChild1.getEag()) / 1000.0 - 0.5))
            .isLessThan(0.002);
    assertThat(Math.abs(pickCount.get(weightedChild2.getEag()) / 1000.0 - 0.5))
            .isLessThan(0.002);
  }

  @Test
  public void rrFallback() {
    syncContext.execute(() -> wrr.acceptResolvedAddresses(ResolvedAddresses.newBuilder()
        .setAddresses(servers).setLoadBalancingPolicyConfig(weightedConfig)
        .setAttributes(affinity).build()));
    verify(helper, times(6)).createSubchannel(
        any(CreateSubchannelArgs.class));
    assertThat(fakeClock.getPendingTasks().size()).isEqualTo(1);

    Iterator<Subchannel> it = subchannels.values().iterator();
    Subchannel readySubchannel1 = it.next();
    getSubchannelStateListener(readySubchannel1).onSubchannelState(ConnectivityStateInfo
        .forNonError(ConnectivityState.READY));
    Subchannel readySubchannel2  = it.next();
    getSubchannelStateListener(readySubchannel2).onSubchannelState(ConnectivityStateInfo
        .forNonError(ConnectivityState.READY));
    verify(helper, times(2)).updateBalancingState(
        eq(ConnectivityState.READY), pickerCaptor.capture());
    WeightedRoundRobinPicker weightedPicker =
        (WeightedRoundRobinPicker) pickerCaptor.getAllValues().get(1);
    assertThat(fakeClock.forwardTime(10, TimeUnit.SECONDS)).isEqualTo(1);
    WeightedChildLbState weightedChild1 = (WeightedChildLbState) getChild(weightedPicker, 0);
    WeightedChildLbState weightedChild2 = (WeightedChildLbState) getChild(weightedPicker, 1);
    Map<EquivalentAddressGroup, Integer> qpsByChannel = ImmutableMap.of(weightedChild1.getEag(), 2,
        weightedChild2.getEag(), 1);
    Map<EquivalentAddressGroup, Integer> pickCount = new HashMap<>();
    for (int i = 0; i < 1000; i++) {
      PickResult pickResult = weightedPicker.pickSubchannel(mockArgs);
      EquivalentAddressGroup addresses = getAddresses(pickResult);
      pickCount.merge(addresses, 1, Integer::sum);
      assertThat(pickResult.getStreamTracerFactory()).isNotNull();
      WeightedChildLbState childLbState = (WeightedChildLbState) wrr.getChildLbStateEag(addresses);
      childLbState.new OrcaReportListener(weightedConfig.errorUtilizationPenalty).onLoadReport(
          InternalCallMetricRecorder.createMetricReport(
              0.1, 0, 0.1, qpsByChannel.get(addresses), 0,
              new HashMap<>(), new HashMap<>(), new HashMap<>()));
    }
    assertThat(Math.abs(pickCount.get(weightedChild1.getEag()) / 1000.0 - 1.0 / 2))
        .isAtMost(0.1);
    assertThat(Math.abs(pickCount.get(weightedChild2.getEag()) / 1000.0 - 1.0 / 2))
        .isAtMost(0.1);

    // Identical to above except forwards time after each pick
    pickCount.clear();
    for (int i = 0; i < 1000; i++) {
      PickResult pickResult = weightedPicker.pickSubchannel(mockArgs);
      EquivalentAddressGroup addresses = getAddresses(pickResult);
      pickCount.merge(addresses, 1, Integer::sum);
      assertThat(pickResult.getStreamTracerFactory()).isNotNull();
      WeightedChildLbState childLbState = (WeightedChildLbState) wrr.getChildLbStateEag(addresses);
      childLbState.new OrcaReportListener(weightedConfig.errorUtilizationPenalty).onLoadReport(
          InternalCallMetricRecorder.createMetricReport(
              0.1, 0, 0.1, qpsByChannel.get(addresses), 0,
              new HashMap<>(), new HashMap<>(), new HashMap<>()));
      fakeClock.forwardTime(50, TimeUnit.MILLISECONDS);
    }
    assertThat(pickCount.size()).isEqualTo(2);
    assertThat(Math.abs(pickCount.get(weightedChild1.getEag()) / 1000.0 - 2.0 / 3))
        .isAtMost(0.1);
    assertThat(Math.abs(pickCount.get(weightedChild2.getEag()) / 1000.0 - 1.0 / 3))
        .isAtMost(0.1);
  }

  private static EquivalentAddressGroup getAddresses(PickResult pickResult) {
    return TestUtils.stripAttrs(pickResult.getSubchannel().getAddresses());
  }

  @Test
  public void unknownWeightIsAvgWeight() {
    syncContext.execute(() -> wrr.acceptResolvedAddresses(ResolvedAddresses.newBuilder()
            .setAddresses(servers).setLoadBalancingPolicyConfig(weightedConfig)
            .setAttributes(affinity).build()));
    verify(helper, times(6)).createSubchannel(
            any(CreateSubchannelArgs.class)); // 3 from setup plus 3 from the execute
    assertThat(fakeClock.getPendingTasks().size()).isEqualTo(1);

    Iterator<Subchannel> it = subchannels.values().iterator();
    Subchannel readySubchannel1 = it.next();
    getSubchannelStateListener(readySubchannel1)
        .onSubchannelState(ConnectivityStateInfo.forNonError(ConnectivityState.READY));
    Subchannel readySubchannel2  = it.next();
    getSubchannelStateListener(readySubchannel2)
        .onSubchannelState(ConnectivityStateInfo.forNonError(ConnectivityState.READY));
    Subchannel readySubchannel3  = it.next();
    getSubchannelStateListener(readySubchannel3)
        .onSubchannelState(ConnectivityStateInfo.forNonError(ConnectivityState.READY));
    verify(helper, times(3)).updateBalancingState(
            eq(ConnectivityState.READY), pickerCaptor.capture());
    WeightedRoundRobinPicker weightedPicker =
        (WeightedRoundRobinPicker) pickerCaptor.getAllValues().get(2);
    WeightedChildLbState weightedChild1 = (WeightedChildLbState) getChild(weightedPicker, 0);
    WeightedChildLbState weightedChild2 = (WeightedChildLbState) getChild(weightedPicker, 1);
    WeightedChildLbState weightedChild3 = (WeightedChildLbState) getChild(weightedPicker, 2);
    weightedChild1.new OrcaReportListener(weightedConfig.errorUtilizationPenalty).onLoadReport(
        InternalCallMetricRecorder.createMetricReport(
            0.1, 0, 0.1, 1, 0, new HashMap<>(), new HashMap<>(), new HashMap<>()));
    weightedChild2.new OrcaReportListener(weightedConfig.errorUtilizationPenalty).onLoadReport(
        InternalCallMetricRecorder.createMetricReport(
            0.2, 0, 0.1, 1, 0, new HashMap<>(), new HashMap<>(), new HashMap<>()));
    assertThat(fakeClock.forwardTime(10, TimeUnit.SECONDS)).isEqualTo(1);
    Map<EquivalentAddressGroup, Integer> pickCount = new HashMap<>();
    for (int i = 0; i < 1000; i++) {
      Subchannel result = weightedPicker.pickSubchannel(mockArgs).getSubchannel();
      pickCount.merge(result.getAddresses(), 1, Integer::sum);
    }
    assertThat(pickCount.size()).isEqualTo(3);
    assertThat(Math.abs(pickCount.get(weightedChild1.getEag()) / 1000.0 - 4.0 / 9))
            .isLessThan(0.002);
    assertThat(Math.abs(pickCount.get(weightedChild2.getEag()) / 1000.0 - 2.0 / 9))
            .isLessThan(0.002);
    // subchannel3's weight is average of subchannel1 and subchannel2
    assertThat(Math.abs(pickCount.get(weightedChild3.getEag()) / 1000.0 - 3.0 / 9))
            .isLessThan(0.002);
  }

  @Test
  public void pickFromOtherThread() throws Exception {
    syncContext.execute(() -> wrr.acceptResolvedAddresses(ResolvedAddresses.newBuilder()
            .setAddresses(servers).setLoadBalancingPolicyConfig(weightedConfig)
            .setAttributes(affinity).build()));
    verify(helper, times(6)).createSubchannel(
            any(CreateSubchannelArgs.class));
    assertThat(fakeClock.getPendingTasks().size()).isEqualTo(1);

    Iterator<Subchannel> it = subchannels.values().iterator();
    Subchannel readySubchannel1 = it.next();
    getSubchannelStateListener(readySubchannel1).onSubchannelState(ConnectivityStateInfo
            .forNonError(ConnectivityState.READY));
    Subchannel readySubchannel2  = it.next();
    getSubchannelStateListener(readySubchannel2).onSubchannelState(ConnectivityStateInfo
            .forNonError(ConnectivityState.READY));
    verify(helper, times(2)).updateBalancingState(
            eq(ConnectivityState.READY), pickerCaptor.capture());
    WeightedRoundRobinPicker weightedPicker =
        (WeightedRoundRobinPicker) pickerCaptor.getAllValues().get(1);
    WeightedChildLbState weightedChild1 = (WeightedChildLbState) getChild(weightedPicker, 0);
    WeightedChildLbState weightedChild2 = (WeightedChildLbState) getChild(weightedPicker, 1);
    weightedChild1.new OrcaReportListener(weightedConfig.errorUtilizationPenalty).onLoadReport(
        InternalCallMetricRecorder.createMetricReport(
            0.1, 0, 0.1, 1, 0, new HashMap<>(), new HashMap<>(), new HashMap<>()));
    weightedChild2.new OrcaReportListener(weightedConfig.errorUtilizationPenalty).onLoadReport(
        InternalCallMetricRecorder.createMetricReport(
            0.2, 0, 0.1, 1, 0, new HashMap<>(), new HashMap<>(), new HashMap<>()));
    CyclicBarrier barrier = new CyclicBarrier(2);
    Map<EquivalentAddressGroup, AtomicInteger> pickCount = new ConcurrentHashMap<>();
    pickCount.put(weightedChild1.getEag(), new AtomicInteger(0));
    pickCount.put(weightedChild2.getEag(), new AtomicInteger(0));
    new Thread(new Runnable() {
      @Override
      public void run() {
        try {
          weightedPicker.pickSubchannel(mockArgs);
          barrier.await();
          for (int i = 0; i < 1000; i++) {
            Subchannel result = weightedPicker.pickSubchannel(mockArgs).getSubchannel();
            pickCount.get(result.getAddresses()).addAndGet(1);
          }
          barrier.await();
        } catch (Exception ex) {
          throw new AssertionError(ex);
        }
      }
    }).start();
    assertThat(fakeClock.forwardTime(10, TimeUnit.SECONDS)).isEqualTo(1);
    barrier.await();
    for (int i = 0; i < 1000; i++) {
      EquivalentAddressGroup result = getAddresses(weightedPicker.pickSubchannel(mockArgs));
      pickCount.get(result).addAndGet(1);
    }
    barrier.await();
    assertThat(pickCount.size()).isEqualTo(2);
    // after blackout period
    assertThat(Math.abs(pickCount.get(weightedChild1.getEag()).get() / 2000.0 - 2.0 / 3))
            .isLessThan(0.002);
    assertThat(Math.abs(pickCount.get(weightedChild2.getEag()).get() / 2000.0 - 1.0 / 3))
            .isLessThan(0.002);
  }

  @Test(expected = NullPointerException.class)
  public void wrrConfig_TimeValueNonNull() {
    WeightedRoundRobinLoadBalancerConfig.newBuilder().setBlackoutPeriodNanos((Long) null);
  }

  @Test(expected = NullPointerException.class)
  public void wrrConfig_BooleanValueNonNull() {
    WeightedRoundRobinLoadBalancerConfig.newBuilder().setEnableOobLoadReport((Boolean) null);
  }

  @Test(expected = IllegalArgumentException.class)
  public void emptyWeights() {
    float[] weights = {};
    Random random = new Random(0);
    VerifyingScheduler sss = new VerifyingScheduler(weights, new AtomicInteger(random.nextInt()));
    sss.pick();
  }

  @Test
  public void testPicksEqualsWeights() {
    float[] weights = {1.0f, 2.0f, 3.0f};
    Random random = new Random(0);
    VerifyingScheduler sss = new VerifyingScheduler(weights, new AtomicInteger(random.nextInt()));
    int[] expectedPicks = new int[] {1, 2, 3};
    int[] picks = new int[3];
    for (int i = 0; i < 6; i++) {
      picks[sss.pick()] += 1;
    }
    assertThat(picks).isEqualTo(expectedPicks);
  }

  @Test
  public void testContainsZeroWeightUseMean() {
    float[] weights = {3.0f, 0.0f, 1.0f};
    Random random = new Random(0);
    VerifyingScheduler sss = new VerifyingScheduler(weights, new AtomicInteger(random.nextInt()));
    int[] expectedPicks = new int[] {3, 2, 1};
    int[] picks = new int[3];
    for (int i = 0; i < 6; i++) {
      picks[sss.pick()] += 1;
    }
    assertThat(picks).isEqualTo(expectedPicks);
  }

  @Test
  public void testContainsNegativeWeightUseMean() {
    float[] weights = {3.0f, -1.0f, 1.0f};
    Random random = new Random(0);
    VerifyingScheduler sss = new VerifyingScheduler(weights, new AtomicInteger(random.nextInt()));
    int[] expectedPicks = new int[] {3, 2, 1};
    int[] picks = new int[3];
    for (int i = 0; i < 6; i++) {
      picks[sss.pick()] += 1;
    }
    assertThat(picks).isEqualTo(expectedPicks);
  }

  @Test
  public void testAllSameWeights() {
    float[] weights = {1.0f, 1.0f, 1.0f};
    Random random = new Random(0);
    VerifyingScheduler sss = new VerifyingScheduler(weights, new AtomicInteger(random.nextInt()));
    int[] expectedPicks = new int[] {2, 2, 2};
    int[] picks = new int[3];
    for (int i = 0; i < 6; i++) {
      picks[sss.pick()] += 1;
    }
    assertThat(picks).isEqualTo(expectedPicks);
  }

  @Test
  public void testAllZeroWeightsIsRoundRobin() {
    float[] weights = {0.0f, 0.0f, 0.0f};
    Random random = new Random(0);
    VerifyingScheduler sss = new VerifyingScheduler(weights, new AtomicInteger(random.nextInt()));
    int[] expectedPicks = new int[] {2, 2, 2};
    int[] picks = new int[3];
    for (int i = 0; i < 6; i++) {
      picks[sss.pick()] += 1;
    }
    assertThat(picks).isEqualTo(expectedPicks);
  }

  @Test
  public void testAllInvalidWeightsIsRoundRobin() {
    float[] weights = {-3.1f, -0.0f, 0.0f};
    Random random = new Random(0);
    VerifyingScheduler sss = new VerifyingScheduler(weights, new AtomicInteger(random.nextInt()));
    int[] expectedPicks = new int[] {2, 2, 2};
    int[] picks = new int[3];
    for (int i = 0; i < 6; i++) {
      picks[sss.pick()] += 1;
    }
    assertThat(picks).isEqualTo(expectedPicks);
  }

  @Test
  public void testTwoWeights() {
    float[] weights = {1.43f, 2.119f};
    Random random = new Random(0);
    VerifyingScheduler sss = new VerifyingScheduler(weights, new AtomicInteger(random.nextInt()));
    double totalWeight = 1.43 + 2.119;
    Map<Integer, Integer> pickCount = new HashMap<>();
    for (int i = 0; i < 1000; i++) {
      int result = sss.pick();
      pickCount.put(result, pickCount.getOrDefault(result, 0) + 1);
    }
    for (int i = 0; i < 2; i++) {
      assertThat(Math.abs(pickCount.getOrDefault(i, 0) / 1000.0 - weights[i] / totalWeight))
          .isLessThan(0.002);
    }
  }

  @Test
  public void testManyWeights() {
    float[] weights = {1.3f, 2.5f, 3.23f, 4.11f, 7.001f};
    Random random = new Random(0);
    VerifyingScheduler sss = new VerifyingScheduler(weights, new AtomicInteger(random.nextInt()));
    double totalWeight = 1.3 + 2.5 + 3.23 + 4.11 + 7.001;
    Map<Integer, Integer> pickCount = new HashMap<>();
    for (int i = 0; i < 1000; i++) {
      int result = sss.pick();
      pickCount.put(result, pickCount.getOrDefault(result, 0) + 1);
    }
    for (int i = 0; i < 5; i++) {
      assertThat(Math.abs(pickCount.getOrDefault(i, 0) / 1000.0 - weights[i] / totalWeight))
          .isLessThan(0.002);
    }
  }

  @Test
  public void testMaxClamped() {
    float[] weights = {81f, 1f, 1f, 1f, 1f, 1f, 1f, 1f,
        1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f};
    VerifyingScheduler sss = new VerifyingScheduler(weights, new AtomicInteger(0));
    int[] picks = new int[weights.length];

    // max gets clamped to mean*maxRatio = 50 for this set of weights. So if we
    // pick 50 + 19 times we should get all possible picks.
    for (int i = 1; i < 70; i++) {
      picks[sss.pick()] += 1;
    }
    int[] expectedPicks = new int[] {50, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1};
    assertThat(picks).isEqualTo(expectedPicks);
  }

  @Test
  public void testMinClamped() {
    float[] weights = {100f, 1e-10f};
    VerifyingScheduler sss = new VerifyingScheduler(weights, new AtomicInteger(0));
    int[] picks = new int[weights.length];

    // We pick 201 elements and ensure that the second channel (with epsilon
    // weight) also gets picked. The math is: mean value of elements is ~50, so
    // the first channel keeps its weight of 100, but the second element's weight
    // gets capped from below to 50*0.1 = 5.
    for (int i = 0; i < 105; i++) {
      picks[sss.pick()] += 1;
    }
    int[] expectedPicks = new int[] {100, 5};
    assertThat(picks).isEqualTo(expectedPicks);
  }

  @Test
  public void testDeterministicPicks() {
    float[] weights = {2.0f, 3.0f, 6.0f};
    AtomicInteger sequence = new AtomicInteger(0);
    VerifyingScheduler sss = new VerifyingScheduler(weights, sequence);
    assertThat(sequence.get()).isEqualTo(0);
    assertThat(sss.pick()).isEqualTo(1);
    assertThat(sequence.get()).isEqualTo(2);
    assertThat(sss.pick()).isEqualTo(2);
    assertThat(sequence.get()).isEqualTo(3);
    assertThat(sss.pick()).isEqualTo(2);
    assertThat(sequence.get()).isEqualTo(6);
    assertThat(sss.pick()).isEqualTo(0);
    assertThat(sequence.get()).isEqualTo(7);
    assertThat(sss.pick()).isEqualTo(1);
    assertThat(sequence.get()).isEqualTo(8);
    assertThat(sss.pick()).isEqualTo(2);
    assertThat(sequence.get()).isEqualTo(9);
  }

  @Test
  public void testImmediateWraparound() {
    float[] weights = {1.0f, 2.0f, 3.0f, 4.0f, 5.0f};
    VerifyingScheduler sss = new VerifyingScheduler(weights, new AtomicInteger(-1));
    double totalWeight = 15;
    Map<Integer, Integer> pickCount = new HashMap<>();
    for (int i = 0; i < 1000; i++) {
      int result = sss.pick();
      pickCount.put(result, pickCount.getOrDefault(result, 0) + 1);
    }
    for (int i = 0; i < 5; i++) {
      assertThat(Math.abs(pickCount.getOrDefault(i, 0) / 1000.0 - weights[i] / totalWeight))
          .isLessThan(0.002);
    }
  }
  
  @Test
  public void testWraparound() {
    float[] weights = {1.0f, 2.0f, 3.0f, 4.0f, 5.0f};
    VerifyingScheduler sss = new VerifyingScheduler(weights, new AtomicInteger(-500));
    double totalWeight = 15;
    Map<Integer, Integer> pickCount = new HashMap<>();
    for (int i = 0; i < 1000; i++) {
      int result = sss.pick();
      pickCount.put(result, pickCount.getOrDefault(result, 0) + 1);
    }
    for (int i = 0; i < 5; i++) {
      assertThat(Math.abs(pickCount.getOrDefault(i, 0) / 1000.0 - weights[i] / totalWeight))
          .isLessThan(0.002);
    }
  }

  @Test
  public void testDeterministicWraparound() {
    float[] weights = {2.0f, 3.0f, 6.0f};
    AtomicInteger sequence = new AtomicInteger(-1);
    VerifyingScheduler sss = new VerifyingScheduler(weights, sequence);
    assertThat(sequence.get()).isEqualTo(-1);
    assertThat(sss.pick()).isEqualTo(1);
    assertThat(sequence.get()).isEqualTo(2);
    assertThat(sss.pick()).isEqualTo(2);
    assertThat(sequence.get()).isEqualTo(3);
    assertThat(sss.pick()).isEqualTo(2);
    assertThat(sequence.get()).isEqualTo(6);
    assertThat(sss.pick()).isEqualTo(0);
    assertThat(sequence.get()).isEqualTo(7);
    assertThat(sss.pick()).isEqualTo(1);
    assertThat(sequence.get()).isEqualTo(8);
    assertThat(sss.pick()).isEqualTo(2);
    assertThat(sequence.get()).isEqualTo(9);
  }

  private static final class VerifyingScheduler {
    private final StaticStrideScheduler delegate;
    private final int max;
    private final AtomicInteger sequence;

    public VerifyingScheduler(float[] weights, AtomicInteger sequence) {
      this.delegate = new StaticStrideScheduler(weights, sequence);
      this.max = weights.length;
      this.sequence = sequence;
    }

    public int pick() {
      int start = sequence.get();
      int i = delegate.pick();
      assertThat(sequence.get() - start).isAtMost(max);
      return i;
    }
  }

  private static class FakeSocketAddress extends SocketAddress {
    final String name;

    FakeSocketAddress(String name) {
      this.name = name;
    }

    @Override public String toString() {
      return "FakeSocketAddress-" + name;
    }
  }

  private static class FakeRandom extends Random {
    private int nextInt;

    public FakeRandom(int nextInt) {
      this.nextInt = nextInt;
    }

    @Override
    public int nextInt() {
      // return constant value to disable init deadline randomization in the scheduler
      return nextInt;
    }
  }

  private class TestHelper extends AbstractTestHelper {

    @Override
    public Map<List<EquivalentAddressGroup>, Subchannel> getSubchannelMap() {
      return subchannels;
    }

    @Override
    public Map<Subchannel, Subchannel> getMockToRealSubChannelMap() {
      return mockToRealSubChannelMap;
    }

    @Override
    public Map<Subchannel, SubchannelStateListener> getSubchannelStateListeners() {
      return subchannelStateListeners;
    }

    @Override
    public SynchronizationContext getSynchronizationContext() {
      return syncContext;
    }

    @Override
    public ScheduledExecutorService getScheduledExecutorService() {
      return fakeClock.getScheduledExecutorService();
    }


  }
}
