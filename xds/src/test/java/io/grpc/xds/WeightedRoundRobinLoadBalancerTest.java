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
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
import io.grpc.ChannelLogger;
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
import io.grpc.services.InternalCallMetricRecorder;
import io.grpc.services.MetricReport;
import io.grpc.xds.WeightedRoundRobinLoadBalancer.StaticStrideScheduler;
import io.grpc.xds.WeightedRoundRobinLoadBalancer.WeightedRoundRobinLoadBalancerConfig;
import io.grpc.xds.WeightedRoundRobinLoadBalancer.WeightedRoundRobinPicker;
import io.grpc.xds.WeightedRoundRobinLoadBalancer.WrrSubchannel;
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

  @Mock
  Helper helper;

  @Mock
  private LoadBalancer.PickSubchannelArgs mockArgs;

  @Captor
  private ArgumentCaptor<SubchannelPicker> pickerCaptor;
  @Captor
  private ArgumentCaptor<SubchannelPicker> pickerCaptor2;

  private final List<EquivalentAddressGroup> servers = Lists.newArrayList();

  private final Map<List<EquivalentAddressGroup>, Subchannel> subchannels = Maps.newLinkedHashMap();

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
      Subchannel sc = mock(Subchannel.class);
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
      when(sc.asChannel()).thenReturn(channel);
      subchannels.put(Arrays.asList(eag), sc);
    }
    when(helper.getSynchronizationContext()).thenReturn(syncContext);
    when(helper.getScheduledExecutorService()).thenReturn(
            fakeClock.getScheduledExecutorService());
    when(helper.createSubchannel(any(CreateSubchannelArgs.class)))
          .then(new Answer<Subchannel>() {
            @Override
            public Subchannel answer(InvocationOnMock invocation) throws Throwable {
              CreateSubchannelArgs args = (CreateSubchannelArgs) invocation.getArguments()[0];
              final Subchannel subchannel = subchannels.get(args.getAddresses());
              when(subchannel.getAllAddresses()).thenReturn(args.getAddresses());
              when(subchannel.getAttributes()).thenReturn(args.getAttributes());
              when(subchannel.getChannelLogger()).thenReturn(mock(ChannelLogger.class));
              doAnswer(
                new Answer<Void>() {
                    @Override
                    public Void answer(InvocationOnMock invocation) throws Throwable {
                        subchannelStateListeners.put(
                                subchannel, (SubchannelStateListener) invocation.getArguments()[0]);
                        return null;
                    }
                }).when(subchannel).start(any(SubchannelStateListener.class));
              return subchannel;
            }
            });
    wrr = new WeightedRoundRobinLoadBalancer(helper, fakeClock.getDeadlineTicker(),
        new FakeRandom(0));
  }

  @Test
  public void wrrLifeCycle() {
    syncContext.execute(() -> wrr.acceptResolvedAddresses(ResolvedAddresses.newBuilder()
                .setAddresses(servers).setLoadBalancingPolicyConfig(weightedConfig)
            .setAttributes(affinity).build()));
    verify(helper, times(3)).createSubchannel(
                any(CreateSubchannelArgs.class));
    assertThat(fakeClock.getPendingTasks().size()).isEqualTo(1);

    Iterator<Subchannel> it = subchannels.values().iterator();
    Subchannel readySubchannel1 = it.next();
    subchannelStateListeners.get(readySubchannel1).onSubchannelState(ConnectivityStateInfo
            .forNonError(ConnectivityState.READY));
    Subchannel readySubchannel2  = it.next();
    subchannelStateListeners.get(readySubchannel2).onSubchannelState(ConnectivityStateInfo
            .forNonError(ConnectivityState.READY));
    Subchannel connectingSubchannel = it.next();
    subchannelStateListeners.get(connectingSubchannel).onSubchannelState(ConnectivityStateInfo
            .forNonError(ConnectivityState.CONNECTING));
    verify(helper, times(2)).updateBalancingState(
            eq(ConnectivityState.READY), pickerCaptor.capture());
    assertThat(pickerCaptor.getAllValues().size()).isEqualTo(2);
    WeightedRoundRobinPicker weightedPicker =
        (WeightedRoundRobinPicker) pickerCaptor.getAllValues().get(0);
    assertThat(weightedPicker.getList().size()).isEqualTo(1);
    weightedPicker = (WeightedRoundRobinPicker) pickerCaptor.getAllValues().get(1);
    assertThat(weightedPicker.getList().size()).isEqualTo(2);
    String weightedPickerStr = weightedPicker.toString();
    assertThat(weightedPickerStr).contains("enableOobLoadReport=false");
    assertThat(weightedPickerStr).contains("errorUtilizationPenalty=1.0");
    assertThat(weightedPickerStr).contains("list=");

    WrrSubchannel weightedSubchannel1 = (WrrSubchannel) weightedPicker.getList().get(0);
    WrrSubchannel weightedSubchannel2 = (WrrSubchannel) weightedPicker.getList().get(1);
    weightedSubchannel1.new OrcaReportListener(weightedConfig.errorUtilizationPenalty).onLoadReport(
        InternalCallMetricRecorder.createMetricReport(
            0.1, 0, 0.1, 1, 0, new HashMap<>(), new HashMap<>(), new HashMap<>()));
    weightedSubchannel2.new OrcaReportListener(weightedConfig.errorUtilizationPenalty).onLoadReport(
        InternalCallMetricRecorder.createMetricReport(
            0.2, 0, 0.1, 1, 0, new HashMap<>(), new HashMap<>(), new HashMap<>()));
    assertThat(fakeClock.forwardTime(11, TimeUnit.SECONDS)).isEqualTo(1);
    assertThat(weightedPicker.pickSubchannel(mockArgs)
        .getSubchannel()).isEqualTo(weightedSubchannel1);
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

  @Test
  public void enableOobLoadReportConfig() {
    syncContext.execute(() -> wrr.acceptResolvedAddresses(ResolvedAddresses.newBuilder()
            .setAddresses(servers).setLoadBalancingPolicyConfig(weightedConfig)
            .setAttributes(affinity).build()));
    verify(helper, times(3)).createSubchannel(
            any(CreateSubchannelArgs.class));
    Iterator<Subchannel> it = subchannels.values().iterator();
    Subchannel readySubchannel1 = it.next();
    subchannelStateListeners.get(readySubchannel1).onSubchannelState(ConnectivityStateInfo
            .forNonError(ConnectivityState.READY));
    Subchannel readySubchannel2  = it.next();
    subchannelStateListeners.get(readySubchannel2).onSubchannelState(ConnectivityStateInfo
            .forNonError(ConnectivityState.READY));
    verify(helper, times(2)).updateBalancingState(
            eq(ConnectivityState.READY), pickerCaptor.capture());
    WeightedRoundRobinPicker weightedPicker =
        (WeightedRoundRobinPicker) pickerCaptor.getAllValues().get(1);
    WrrSubchannel weightedSubchannel1 = (WrrSubchannel) weightedPicker.getList().get(0);
    WrrSubchannel weightedSubchannel2 = (WrrSubchannel) weightedPicker.getList().get(1);
    weightedSubchannel1.new OrcaReportListener(weightedConfig.errorUtilizationPenalty).onLoadReport(
        InternalCallMetricRecorder.createMetricReport(
            0.1, 0, 0.1, 1, 0, new HashMap<>(), new HashMap<>(), new HashMap<>()));
    weightedSubchannel2.new OrcaReportListener(weightedConfig.errorUtilizationPenalty).onLoadReport(
        InternalCallMetricRecorder.createMetricReport(
            0.9, 0, 0.1, 1, 0, new HashMap<>(), new HashMap<>(), new HashMap<>()));
    assertThat(fakeClock.forwardTime(11, TimeUnit.SECONDS)).isEqualTo(1);
    PickResult pickResult = weightedPicker.pickSubchannel(mockArgs);
    assertThat(pickResult.getSubchannel()).isEqualTo(weightedSubchannel1);
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
    assertThat(pickResult.getSubchannel()).isEqualTo(weightedSubchannel1);
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
    verify(helper, times(3)).createSubchannel(
            any(CreateSubchannelArgs.class));
    assertThat(fakeClock.getPendingTasks().size()).isEqualTo(1);

    Iterator<Subchannel> it = subchannels.values().iterator();
    Subchannel readySubchannel1 = it.next();
    subchannelStateListeners.get(readySubchannel1).onSubchannelState(ConnectivityStateInfo
            .forNonError(ConnectivityState.READY));
    Subchannel readySubchannel2  = it.next();
    subchannelStateListeners.get(readySubchannel2).onSubchannelState(ConnectivityStateInfo
            .forNonError(ConnectivityState.READY));
    Subchannel readySubchannel3 = it.next();
    subchannelStateListeners.get(readySubchannel3).onSubchannelState(ConnectivityStateInfo
            .forNonError(ConnectivityState.READY));
    verify(helper, times(3)).updateBalancingState(
            eq(ConnectivityState.READY), pickerCaptor.capture());
    WeightedRoundRobinPicker weightedPicker =
        (WeightedRoundRobinPicker) pickerCaptor.getAllValues().get(2);
    WrrSubchannel weightedSubchannel1 = (WrrSubchannel) weightedPicker.getList().get(0);
    WrrSubchannel weightedSubchannel2 = (WrrSubchannel) weightedPicker.getList().get(1);
    WrrSubchannel weightedSubchannel3 = (WrrSubchannel) weightedPicker.getList().get(2);
    weightedSubchannel1.new OrcaReportListener(weightedConfig.errorUtilizationPenalty).onLoadReport(
        r1);
    weightedSubchannel2.new OrcaReportListener(weightedConfig.errorUtilizationPenalty).onLoadReport(
        r2);
    weightedSubchannel3.new OrcaReportListener(weightedConfig.errorUtilizationPenalty).onLoadReport(
        r3);
    assertThat(fakeClock.forwardTime(11, TimeUnit.SECONDS)).isEqualTo(1);
    Map<Subchannel, Integer> pickCount = new HashMap<>();
    for (int i = 0; i < 10000; i++) {
      Subchannel result = weightedPicker.pickSubchannel(mockArgs).getSubchannel();
      pickCount.put(result, pickCount.getOrDefault(result, 0) + 1);
    }
    assertThat(pickCount.size()).isEqualTo(3);
    assertThat(Math.abs(pickCount.get(weightedSubchannel1) / 10000.0 - subchannel1PickRatio))
        .isAtMost(0.0001);
    assertThat(Math.abs(pickCount.get(weightedSubchannel2) / 10000.0 - subchannel2PickRatio ))
        .isAtMost(0.0001);
    assertThat(Math.abs(pickCount.get(weightedSubchannel3) / 10000.0 - subchannel3PickRatio ))
        .isAtMost(0.0001);
  }

  @Test
  public void pickByWeight_largeWeight() {
    MetricReport report1 = InternalCallMetricRecorder.createMetricReport(
        0.1, 0, 0.1, 999, 0, new HashMap<>(), new HashMap<>(), new HashMap<>());
    MetricReport report2 = InternalCallMetricRecorder.createMetricReport(
        0.9, 0, 0.1, 2, 0, new HashMap<>(), new HashMap<>(), new HashMap<>());
    MetricReport report3 = InternalCallMetricRecorder.createMetricReport(
        0.86, 0, 0.1, 100, 0, new HashMap<>(), new HashMap<>(), new HashMap<>());
    double totalWeight = 999 / 0.1 + 2 / 0.9 + 100 / 0.86;

    pickByWeight(report1, report2, report3, 999 / 0.1 / totalWeight, 2 / 0.9 / totalWeight,
            100 / 0.86 / totalWeight);
  }

  @Test
  public void pickByWeight_largeWeight_useApplicationUtilization() {
    MetricReport report1 = InternalCallMetricRecorder.createMetricReport(
        0.44, 0.1, 0.1, 999, 0, new HashMap<>(), new HashMap<>(), new HashMap<>());
    MetricReport report2 = InternalCallMetricRecorder.createMetricReport(
        0.12, 0.9, 0.1, 2, 0, new HashMap<>(), new HashMap<>(), new HashMap<>());
    MetricReport report3 = InternalCallMetricRecorder.createMetricReport(
        0.33, 0.86, 0.1, 100, 0, new HashMap<>(), new HashMap<>(), new HashMap<>());
    double totalWeight = 999 / 0.1 + 2 / 0.9 + 100 / 0.86;

    pickByWeight(report1, report2, report3, 999 / 0.1 / totalWeight, 2 / 0.9 / totalWeight,
        100 / 0.86 / totalWeight);
  }

  @Test
  public void pickByWeight_largeWeight_withEps_defaultErrorUtilizationPenalty() {
    MetricReport report1 = InternalCallMetricRecorder.createMetricReport(
        0.1, 0, 0.1, 999, 13, new HashMap<>(), new HashMap<>(), new HashMap<>());
    MetricReport report2 = InternalCallMetricRecorder.createMetricReport(
        0.9, 0, 0.1, 2, 1.8, new HashMap<>(), new HashMap<>(), new HashMap<>());
    MetricReport report3 = InternalCallMetricRecorder.createMetricReport(
        0.86, 0, 0.1, 100, 3, new HashMap<>(), new HashMap<>(), new HashMap<>());
    double weight1 = 999 / (0.1 + 13 / 999F * weightedConfig.errorUtilizationPenalty);
    double weight2 = 2 / (0.9 + 1.8 / 2F * weightedConfig.errorUtilizationPenalty);
    double weight3 = 100 / (0.86 + 3 / 100F * weightedConfig.errorUtilizationPenalty);
    double totalWeight = weight1 + weight2 + weight3;

    pickByWeight(report1, report2, report3, weight1 / totalWeight, weight2 / totalWeight,
        weight3 / totalWeight);
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
    verify(helper, never()).createSubchannel(any(CreateSubchannelArgs.class));
    verify(helper).updateBalancingState(eq(ConnectivityState.TRANSIENT_FAILURE), any());
    assertThat(fakeClock.getPendingTasks()).isEmpty();

    syncContext.execute(() -> wrr.acceptResolvedAddresses(ResolvedAddresses.newBuilder()
            .setAddresses(servers).setLoadBalancingPolicyConfig(weightedConfig)
            .setAttributes(affinity).build()));
    verify(helper, times(3)).createSubchannel(
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
    verify(helper, times(3)).createSubchannel(
            any(CreateSubchannelArgs.class));
    assertThat(fakeClock.getPendingTasks().size()).isEqualTo(1);

    Iterator<Subchannel> it = subchannels.values().iterator();
    Subchannel readySubchannel1 = it.next();
    subchannelStateListeners.get(readySubchannel1).onSubchannelState(ConnectivityStateInfo
            .forNonError(ConnectivityState.READY));
    Subchannel readySubchannel2  = it.next();
    subchannelStateListeners.get(readySubchannel2).onSubchannelState(ConnectivityStateInfo
            .forNonError(ConnectivityState.READY));
    verify(helper, times(2)).updateBalancingState(
            eq(ConnectivityState.READY), pickerCaptor.capture());
    WeightedRoundRobinPicker weightedPicker =
        (WeightedRoundRobinPicker) pickerCaptor.getAllValues().get(1);
    WrrSubchannel weightedSubchannel1 = (WrrSubchannel) weightedPicker.getList().get(0);
    WrrSubchannel weightedSubchannel2 = (WrrSubchannel) weightedPicker.getList().get(1);
    weightedSubchannel1.new OrcaReportListener(weightedConfig.errorUtilizationPenalty).onLoadReport(
        InternalCallMetricRecorder.createMetricReport(
            0.1, 0, 0.1, 1, 0, new HashMap<>(), new HashMap<>(), new HashMap<>()));
    weightedSubchannel2.new OrcaReportListener(weightedConfig.errorUtilizationPenalty).onLoadReport(
        InternalCallMetricRecorder.createMetricReport(
            0.2, 0, 0.1, 1, 0, new HashMap<>(), new HashMap<>(), new HashMap<>()));
    assertThat(fakeClock.forwardTime(5, TimeUnit.SECONDS)).isEqualTo(1);
    Map<Subchannel, Integer> pickCount = new HashMap<>();
    for (int i = 0; i < 1000; i++) {
      Subchannel result = weightedPicker.pickSubchannel(mockArgs).getSubchannel();
      pickCount.put(result, pickCount.getOrDefault(result, 0) + 1);
    }
    assertThat(pickCount.size()).isEqualTo(2);
    // within blackout period, fallback to simple round robin
    assertThat(Math.abs(pickCount.get(weightedSubchannel1) / 1000.0 - 0.5)).isAtMost(0.001);
    assertThat(Math.abs(pickCount.get(weightedSubchannel2) / 1000.0 - 0.5)).isAtMost(0.001);

    assertThat(fakeClock.forwardTime(5, TimeUnit.SECONDS)).isEqualTo(1);
    pickCount = new HashMap<>();
    for (int i = 0; i < 1000; i++) {
      Subchannel result = weightedPicker.pickSubchannel(mockArgs).getSubchannel();
      pickCount.put(result, pickCount.getOrDefault(result, 0) + 1);
    }
    assertThat(pickCount.size()).isEqualTo(2);
    // after blackout period
    assertThat(Math.abs(pickCount.get(weightedSubchannel1) / 1000.0 - 2.0 / 3))
            .isAtMost(0.001);
    assertThat(Math.abs(pickCount.get(weightedSubchannel2) / 1000.0 - 1.0 / 3))
            .isAtMost(0.001);
  }

  @Test
  public void updateWeightTimer() {
    syncContext.execute(() -> wrr.acceptResolvedAddresses(ResolvedAddresses.newBuilder()
        .setAddresses(servers).setLoadBalancingPolicyConfig(weightedConfig)
        .setAttributes(affinity).build()));
    verify(helper, times(3)).createSubchannel(
        any(CreateSubchannelArgs.class));
    assertThat(fakeClock.getPendingTasks().size()).isEqualTo(1);

    Iterator<Subchannel> it = subchannels.values().iterator();
    Subchannel readySubchannel1 = it.next();
    subchannelStateListeners.get(readySubchannel1).onSubchannelState(ConnectivityStateInfo
        .forNonError(ConnectivityState.READY));
    Subchannel readySubchannel2  = it.next();
    subchannelStateListeners.get(readySubchannel2).onSubchannelState(ConnectivityStateInfo
        .forNonError(ConnectivityState.READY));
    Subchannel connectingSubchannel = it.next();
    subchannelStateListeners.get(connectingSubchannel).onSubchannelState(ConnectivityStateInfo
        .forNonError(ConnectivityState.CONNECTING));
    verify(helper, times(2)).updateBalancingState(
        eq(ConnectivityState.READY), pickerCaptor.capture());
    assertThat(pickerCaptor.getAllValues().size()).isEqualTo(2);
    WeightedRoundRobinPicker weightedPicker =
        (WeightedRoundRobinPicker) pickerCaptor.getAllValues().get(0);
    assertThat(weightedPicker.getList().size()).isEqualTo(1);
    weightedPicker = (WeightedRoundRobinPicker) pickerCaptor.getAllValues().get(1);
    assertThat(weightedPicker.getList().size()).isEqualTo(2);
    WrrSubchannel weightedSubchannel1 = (WrrSubchannel) weightedPicker.getList().get(0);
    WrrSubchannel weightedSubchannel2 = (WrrSubchannel) weightedPicker.getList().get(1);
    weightedSubchannel1.new OrcaReportListener(weightedConfig.errorUtilizationPenalty).onLoadReport(
        InternalCallMetricRecorder.createMetricReport(
            0.1, 0, 0.1, 1, 0, new HashMap<>(), new HashMap<>(), new HashMap<>()));
    weightedSubchannel2.new OrcaReportListener(weightedConfig.errorUtilizationPenalty).onLoadReport(
        InternalCallMetricRecorder.createMetricReport(
            0.2, 0, 0.1, 1, 0, new HashMap<>(), new HashMap<>(), new HashMap<>()));
    assertThat(fakeClock.forwardTime(11, TimeUnit.SECONDS)).isEqualTo(1);
    assertThat(weightedPicker.pickSubchannel(mockArgs)
        .getSubchannel()).isEqualTo(weightedSubchannel1);
    assertThat(fakeClock.getPendingTasks().size()).isEqualTo(1);
    weightedConfig = WeightedRoundRobinLoadBalancerConfig.newBuilder()
        .setWeightUpdatePeriodNanos(500_000_000L) //.5s
        .build();
    syncContext.execute(() -> wrr.acceptResolvedAddresses(ResolvedAddresses.newBuilder()
        .setAddresses(servers).setLoadBalancingPolicyConfig(weightedConfig)
        .setAttributes(affinity).build()));
    assertThat(fakeClock.getPendingTasks().size()).isEqualTo(1);
    weightedSubchannel1.new OrcaReportListener(weightedConfig.errorUtilizationPenalty).onLoadReport(
        InternalCallMetricRecorder.createMetricReport(
            0.2, 0, 0.1, 1, 0, new HashMap<>(), new HashMap<>(), new HashMap<>()));
    weightedSubchannel2.new OrcaReportListener(weightedConfig.errorUtilizationPenalty).onLoadReport(
        InternalCallMetricRecorder.createMetricReport(
            0.1, 0, 0.1, 1, 0, new HashMap<>(), new HashMap<>(), new HashMap<>()));
    //timer fires, new weight updated
    assertThat(fakeClock.forwardTime(500, TimeUnit.MILLISECONDS)).isEqualTo(1);
    assertThat(weightedPicker.pickSubchannel(mockArgs)
        .getSubchannel()).isEqualTo(weightedSubchannel2);

  }

  @Test
  public void weightExpired() {
    syncContext.execute(() -> wrr.acceptResolvedAddresses(ResolvedAddresses.newBuilder()
            .setAddresses(servers).setLoadBalancingPolicyConfig(weightedConfig)
            .setAttributes(affinity).build()));
    verify(helper, times(3)).createSubchannel(
            any(CreateSubchannelArgs.class));
    assertThat(fakeClock.getPendingTasks().size()).isEqualTo(1);

    Iterator<Subchannel> it = subchannels.values().iterator();
    Subchannel readySubchannel1 = it.next();
    subchannelStateListeners.get(readySubchannel1).onSubchannelState(ConnectivityStateInfo
            .forNonError(ConnectivityState.READY));
    Subchannel readySubchannel2  = it.next();
    subchannelStateListeners.get(readySubchannel2).onSubchannelState(ConnectivityStateInfo
            .forNonError(ConnectivityState.READY));
    verify(helper, times(2)).updateBalancingState(
            eq(ConnectivityState.READY), pickerCaptor.capture());
    WeightedRoundRobinPicker weightedPicker =
        (WeightedRoundRobinPicker) pickerCaptor.getAllValues().get(1);
    WrrSubchannel weightedSubchannel1 = (WrrSubchannel) weightedPicker.getList().get(0);
    WrrSubchannel weightedSubchannel2 = (WrrSubchannel) weightedPicker.getList().get(1);
    weightedSubchannel1.new OrcaReportListener(weightedConfig.errorUtilizationPenalty).onLoadReport(
        InternalCallMetricRecorder.createMetricReport(
            0.1, 0, 0.1, 1, 0, new HashMap<>(), new HashMap<>(), new HashMap<>()));
    weightedSubchannel2.new OrcaReportListener(weightedConfig.errorUtilizationPenalty).onLoadReport(
        InternalCallMetricRecorder.createMetricReport(
            0.2, 0, 0.1, 1, 0, new HashMap<>(), new HashMap<>(), new HashMap<>()));
    assertThat(fakeClock.forwardTime(10, TimeUnit.SECONDS)).isEqualTo(1);
    Map<Subchannel, Integer> pickCount = new HashMap<>();
    for (int i = 0; i < 1000; i++) {
      Subchannel result = weightedPicker.pickSubchannel(mockArgs).getSubchannel();
      pickCount.put(result, pickCount.getOrDefault(result, 0) + 1);
    }
    assertThat(pickCount.size()).isEqualTo(2);
    assertThat(Math.abs(pickCount.get(weightedSubchannel1) / 1000.0 - 2.0 / 3))
            .isAtMost(0.001);
    assertThat(Math.abs(pickCount.get(weightedSubchannel2) / 1000.0 - 1.0 / 3))
            .isAtMost(0.001);

    // weight expired, fallback to simple round robin
    assertThat(fakeClock.forwardTime(300, TimeUnit.SECONDS)).isEqualTo(1);
    pickCount = new HashMap<>();
    for (int i = 0; i < 1000; i++) {
      Subchannel result = weightedPicker.pickSubchannel(mockArgs).getSubchannel();
      pickCount.put(result, pickCount.getOrDefault(result, 0) + 1);
    }
    assertThat(pickCount.size()).isEqualTo(2);
    assertThat(Math.abs(pickCount.get(weightedSubchannel1) / 1000.0 - 0.5))
            .isAtMost(0.001);
    assertThat(Math.abs(pickCount.get(weightedSubchannel2) / 1000.0 - 0.5))
            .isAtMost(0.001);
  }

  @Test
  public void rrFallback() {
    syncContext.execute(() -> wrr.acceptResolvedAddresses(ResolvedAddresses.newBuilder()
        .setAddresses(servers).setLoadBalancingPolicyConfig(weightedConfig)
        .setAttributes(affinity).build()));
    verify(helper, times(3)).createSubchannel(
        any(CreateSubchannelArgs.class));
    assertThat(fakeClock.getPendingTasks().size()).isEqualTo(1);

    Iterator<Subchannel> it = subchannels.values().iterator();
    Subchannel readySubchannel1 = it.next();
    subchannelStateListeners.get(readySubchannel1).onSubchannelState(ConnectivityStateInfo
        .forNonError(ConnectivityState.READY));
    Subchannel readySubchannel2  = it.next();
    subchannelStateListeners.get(readySubchannel2).onSubchannelState(ConnectivityStateInfo
        .forNonError(ConnectivityState.READY));
    verify(helper, times(2)).updateBalancingState(
        eq(ConnectivityState.READY), pickerCaptor.capture());
    WeightedRoundRobinPicker weightedPicker =
        (WeightedRoundRobinPicker) pickerCaptor.getAllValues().get(1);
    assertThat(fakeClock.forwardTime(10, TimeUnit.SECONDS)).isEqualTo(1);
    WrrSubchannel weightedSubchannel1 = (WrrSubchannel) weightedPicker.getList().get(0);
    WrrSubchannel weightedSubchannel2 = (WrrSubchannel) weightedPicker.getList().get(1);
    Map<WrrSubchannel, Integer> qpsByChannel = ImmutableMap.of(weightedSubchannel1, 2,
        weightedSubchannel2, 1);
    Map<Subchannel, Integer> pickCount = new HashMap<>();
    for (int i = 0; i < 1000; i++) {
      PickResult pickResult = weightedPicker.pickSubchannel(mockArgs);
      pickCount.put(pickResult.getSubchannel(),
          pickCount.getOrDefault(pickResult.getSubchannel(), 0) + 1);
      assertThat(pickResult.getStreamTracerFactory()).isNotNull();
      WrrSubchannel subchannel = (WrrSubchannel)pickResult.getSubchannel();
      subchannel.new OrcaReportListener(weightedConfig.errorUtilizationPenalty).onLoadReport(
          InternalCallMetricRecorder.createMetricReport(
              0.1, 0, 0.1, qpsByChannel.get(subchannel), 0,
              new HashMap<>(), new HashMap<>(), new HashMap<>()));
    }
    assertThat(Math.abs(pickCount.get(weightedSubchannel1) / 1000.0 - 1.0 / 2))
        .isAtMost(0.1);
    assertThat(Math.abs(pickCount.get(weightedSubchannel2) / 1000.0 - 1.0 / 2))
        .isAtMost(0.1);
    pickCount.clear();
    for (int i = 0; i < 1000; i++) {
      PickResult pickResult = weightedPicker.pickSubchannel(mockArgs);
      pickCount.put(pickResult.getSubchannel(),
          pickCount.getOrDefault(pickResult.getSubchannel(), 0) + 1);
      assertThat(pickResult.getStreamTracerFactory()).isNotNull();
      WrrSubchannel subchannel = (WrrSubchannel) pickResult.getSubchannel();
      subchannel.new OrcaReportListener(weightedConfig.errorUtilizationPenalty).onLoadReport(
          InternalCallMetricRecorder.createMetricReport(
              0.1, 0, 0.1, qpsByChannel.get(subchannel), 0,
              new HashMap<>(), new HashMap<>(), new HashMap<>()));
      fakeClock.forwardTime(50, TimeUnit.MILLISECONDS);
    }
    assertThat(pickCount.size()).isEqualTo(2);
    assertThat(Math.abs(pickCount.get(weightedSubchannel1) / 1000.0 - 2.0 / 3))
        .isAtMost(0.1);
    assertThat(Math.abs(pickCount.get(weightedSubchannel2) / 1000.0 - 1.0 / 3))
        .isAtMost(0.1);
  }

  @Test
  public void unknownWeightIsAvgWeight() {
    syncContext.execute(() -> wrr.acceptResolvedAddresses(ResolvedAddresses.newBuilder()
            .setAddresses(servers).setLoadBalancingPolicyConfig(weightedConfig)
            .setAttributes(affinity).build()));
    verify(helper, times(3)).createSubchannel(
            any(CreateSubchannelArgs.class));
    assertThat(fakeClock.getPendingTasks().size()).isEqualTo(1);

    Iterator<Subchannel> it = subchannels.values().iterator();
    Subchannel readySubchannel1 = it.next();
    subchannelStateListeners.get(readySubchannel1).onSubchannelState(ConnectivityStateInfo
            .forNonError(ConnectivityState.READY));
    Subchannel readySubchannel2  = it.next();
    subchannelStateListeners.get(readySubchannel2).onSubchannelState(ConnectivityStateInfo
            .forNonError(ConnectivityState.READY));
    Subchannel readySubchannel3  = it.next();
    subchannelStateListeners.get(readySubchannel3).onSubchannelState(ConnectivityStateInfo
            .forNonError(ConnectivityState.READY));
    verify(helper, times(3)).updateBalancingState(
            eq(ConnectivityState.READY), pickerCaptor.capture());
    WeightedRoundRobinPicker weightedPicker =
        (WeightedRoundRobinPicker) pickerCaptor.getAllValues().get(2);
    WrrSubchannel weightedSubchannel1 = (WrrSubchannel) weightedPicker.getList().get(0);
    WrrSubchannel weightedSubchannel2 = (WrrSubchannel) weightedPicker.getList().get(1);
    WrrSubchannel weightedSubchannel3 = (WrrSubchannel) weightedPicker.getList().get(2);
    weightedSubchannel1.new OrcaReportListener(weightedConfig.errorUtilizationPenalty).onLoadReport(
        InternalCallMetricRecorder.createMetricReport(
            0.1, 0, 0.1, 1, 0, new HashMap<>(), new HashMap<>(), new HashMap<>()));
    weightedSubchannel2.new OrcaReportListener(weightedConfig.errorUtilizationPenalty).onLoadReport(
        InternalCallMetricRecorder.createMetricReport(
            0.2, 0, 0.1, 1, 0, new HashMap<>(), new HashMap<>(), new HashMap<>()));
    assertThat(fakeClock.forwardTime(10, TimeUnit.SECONDS)).isEqualTo(1);
    Map<Subchannel, Integer> pickCount = new HashMap<>();
    for (int i = 0; i < 1000; i++) {
      Subchannel result = weightedPicker.pickSubchannel(mockArgs).getSubchannel();
      pickCount.put(result, pickCount.getOrDefault(result, 0) + 1);
    }
    assertThat(pickCount.size()).isEqualTo(3);
    assertThat(Math.abs(pickCount.get(weightedSubchannel1) / 1000.0 - 4.0 / 9))
            .isAtMost(0.001);
    assertThat(Math.abs(pickCount.get(weightedSubchannel2) / 1000.0 - 2.0 / 9))
            .isAtMost(0.001);
    // subchannel3's weight is average of subchannel1 and subchannel2
    assertThat(Math.abs(pickCount.get(weightedSubchannel3) / 1000.0 - 3.0 / 9))
            .isAtMost(0.001);
  }

  @Test
  public void pickFromOtherThread() throws Exception {
    syncContext.execute(() -> wrr.acceptResolvedAddresses(ResolvedAddresses.newBuilder()
            .setAddresses(servers).setLoadBalancingPolicyConfig(weightedConfig)
            .setAttributes(affinity).build()));
    verify(helper, times(3)).createSubchannel(
            any(CreateSubchannelArgs.class));
    assertThat(fakeClock.getPendingTasks().size()).isEqualTo(1);

    Iterator<Subchannel> it = subchannels.values().iterator();
    Subchannel readySubchannel1 = it.next();
    subchannelStateListeners.get(readySubchannel1).onSubchannelState(ConnectivityStateInfo
            .forNonError(ConnectivityState.READY));
    Subchannel readySubchannel2  = it.next();
    subchannelStateListeners.get(readySubchannel2).onSubchannelState(ConnectivityStateInfo
            .forNonError(ConnectivityState.READY));
    verify(helper, times(2)).updateBalancingState(
            eq(ConnectivityState.READY), pickerCaptor.capture());
    WeightedRoundRobinPicker weightedPicker =
        (WeightedRoundRobinPicker) pickerCaptor.getAllValues().get(1);
    WrrSubchannel weightedSubchannel1 = (WrrSubchannel) weightedPicker.getList().get(0);
    WrrSubchannel weightedSubchannel2 = (WrrSubchannel) weightedPicker.getList().get(1);
    weightedSubchannel1.new OrcaReportListener(weightedConfig.errorUtilizationPenalty).onLoadReport(
        InternalCallMetricRecorder.createMetricReport(
            0.1, 0, 0.1, 1, 0, new HashMap<>(), new HashMap<>(), new HashMap<>()));
    weightedSubchannel2.new OrcaReportListener(weightedConfig.errorUtilizationPenalty).onLoadReport(
        InternalCallMetricRecorder.createMetricReport(
            0.2, 0, 0.1, 1, 0, new HashMap<>(), new HashMap<>(), new HashMap<>()));
    CyclicBarrier barrier = new CyclicBarrier(2);
    Map<Subchannel, AtomicInteger> pickCount = new ConcurrentHashMap<>();
    pickCount.put(weightedSubchannel1, new AtomicInteger(0));
    pickCount.put(weightedSubchannel2, new AtomicInteger(0));
    new Thread(new Runnable() {
      @Override
      public void run() {
        try {
          weightedPicker.pickSubchannel(mockArgs);
          barrier.await();
          for (int i = 0; i < 1000; i++) {
            Subchannel result = weightedPicker.pickSubchannel(mockArgs).getSubchannel();
            pickCount.get(result).addAndGet(1);
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
      Subchannel result = weightedPicker.pickSubchannel(mockArgs).getSubchannel();
      pickCount.get(result).addAndGet(1);
    }
    barrier.await();
    assertThat(pickCount.size()).isEqualTo(2);
    // after blackout period
    assertThat(Math.abs(pickCount.get(weightedSubchannel1).get() / 2000.0 - 2.0 / 3))
            .isAtMost(0.001);
    assertThat(Math.abs(pickCount.get(weightedSubchannel2).get() / 2000.0 - 1.0 / 3))
            .isAtMost(0.001);
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
    Random random = new Random();
    StaticStrideScheduler sss = new StaticStrideScheduler(weights, random);
    sss.pick();
  }

  @Test
  public void testPicksEqualsWeights() {
    float[] weights = {1.0f, 2.0f, 3.0f};
    Random random = new Random();
    StaticStrideScheduler sss = new StaticStrideScheduler(weights, random);
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
    Random random = new Random();
    StaticStrideScheduler sss = new StaticStrideScheduler(weights, random);
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
    Random random = new Random();
    StaticStrideScheduler sss = new StaticStrideScheduler(weights, random);
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
    Random random = new Random();
    StaticStrideScheduler sss = new StaticStrideScheduler(weights, random);
    int[] expectedPicks = new int[] {2, 2, 2};
    int[] picks = new int[3];
    for (int i = 0; i < 6; i++) {
      picks[sss.pick()] += 1;
    }
    assertThat(picks).isEqualTo(expectedPicks);
  }

  @Test
  public void testAllZeroWeightsUseOne() {
    float[] weights = {0.0f, 0.0f, 0.0f};
    Random random = new Random();
    StaticStrideScheduler sss = new StaticStrideScheduler(weights, random);
    int[] expectedPicks = new int[] {2, 2, 2};
    int[] picks = new int[3];
    for (int i = 0; i < 6; i++) {
      picks[sss.pick()] += 1;
    }
    assertThat(picks).isEqualTo(expectedPicks);
  }

  @Test
  public void testAllInvalidWeightsUseOne() {
    float[] weights = {-3.1f, -0.0f, 0.0f};
    Random random = new Random();
    StaticStrideScheduler sss = new StaticStrideScheduler(weights, random);
    int[] expectedPicks = new int[] {2, 2, 2};
    int[] picks = new int[3];
    for (int i = 0; i < 6; i++) {
      picks[sss.pick()] += 1;
    }
    assertThat(picks).isEqualTo(expectedPicks);
  }

  @Test
  public void testLargestWeightIndexPickedEveryGeneration() {
    float[] weights = {1.0f, 2.0f, 3.0f};
    int largestWeightIndex = 2;
    Random random = new Random();
    StaticStrideScheduler sss = new StaticStrideScheduler(weights, random);
    int largestWeightPickCount = 0;
    int kMaxWeight = 65535;
    for (int i = 0; i < largestWeightIndex * kMaxWeight; i++) {
      if (sss.pick() == largestWeightIndex) {
        largestWeightPickCount += 1;
      }
    }
    assertThat(largestWeightPickCount).isEqualTo(kMaxWeight);
  }

  @Test
  public void testStaticStrideSchedulerNonIntegers1() {
    float[] weights = {2.0f, (float) (10.0 / 3.0), 1.0f};
    Random random = new Random();
    StaticStrideScheduler sss = new StaticStrideScheduler(weights, random);
    double totalWeight = 2 + 10.0 / 3.0 + 1.0;
    Map<Integer, Integer> pickCount = new HashMap<>();
    for (int i = 0; i < 1000; i++) {
      int result = sss.pick();
      pickCount.merge(result, 1, (o, v) -> o + v);
    }
    for (int i = 0; i < 3; i++) {
      assertThat(Math.abs(pickCount.getOrDefault(i, 0) / 1000.0 - weights[i] / totalWeight))
          .isAtMost(0.001);
    }
  }

  @Test
  public void testStaticStrideSchedulerNonIntegers2() {
    float[] weights = {0.5f, 0.3f, 1.0f};
    Random random = new Random();
    StaticStrideScheduler sss = new StaticStrideScheduler(weights, random);
    double totalWeight = 1.8;
    Map<Integer, Integer> pickCount = new HashMap<>();
    for (int i = 0; i < 1000; i++) {
      int result = sss.pick();
      pickCount.put(result, pickCount.getOrDefault(result, 0) + 1);
    }
    for (int i = 0; i < 3; i++) {
      assertThat(Math.abs(pickCount.getOrDefault(i, 0) / 1000.0 - weights[i] / totalWeight))
          .isAtMost(0.001);
    }
  }

  @Test
  public void testTwoWeights() {
    float[] weights = {1.0f, 2.0f};
    Random random = new Random();
    StaticStrideScheduler sss = new StaticStrideScheduler(weights, random);
    double totalWeight = 3;
    Map<Integer, Integer> pickCount = new HashMap<>();
    for (int i = 0; i < 1000; i++) {
      int result = sss.pick();
      pickCount.put(result, pickCount.getOrDefault(result, 0) + 1);
    }
    for (int i = 0; i < 2; i++) {
      assertThat(Math.abs(pickCount.getOrDefault(i, 0) / 1000.0 - weights[i] / totalWeight))
          .isAtMost(0.001);
    }
  }

  @Test
  public void testManyWeights() {
    float[] weights = {1.0f, 2.0f, 3.0f, 4.0f, 5.0f};
    Random random = new Random();
    StaticStrideScheduler sss = new StaticStrideScheduler(weights, random);
    double totalWeight = 15;
    Map<Integer, Integer> pickCount = new HashMap<>();
    for (int i = 0; i < 1000; i++) {
      int result = sss.pick();
      pickCount.put(result, pickCount.getOrDefault(result, 0) + 1);
    }
    for (int i = 0; i < 5; i++) {
      assertThat(Math.abs(pickCount.getOrDefault(i, 0) / 1000.0 - weights[i] / totalWeight))
          .isAtMost(0.0011);
    }
  }

  @Test
  public void testManyComplexWeights() {
    float[] weights = {1.2f, 2.4f, 222.56f, 1.1f, 15.0f, 226342.0f, 5123.0f, 532.2f};
    Random random = new Random();
    StaticStrideScheduler sss = new StaticStrideScheduler(weights, random);
    double totalWeight = 1.2 + 2.4 + 222.56 + 15.0 + 226342.0 + 5123.0 + 0.0001;
    Map<Integer, Integer> pickCount = new HashMap<>();
    for (int i = 0; i < 1000; i++) {
      int result = sss.pick();
      pickCount.put(result, pickCount.getOrDefault(result, 0) + 1);
    }
    for (int i = 0; i < 8; i++) {
      assertThat(Math.abs(pickCount.getOrDefault(i, 0) / 1000.0 - weights[i] / totalWeight))
          .isAtMost(0.004);
    }
  }

  @Test
  public void testDeterministicPicks() {
    float[] weights = {2.0f, 3.0f, 6.0f};
    Random random = new FakeRandom(0);
    StaticStrideScheduler sss = new StaticStrideScheduler(weights, random);
    assertThat(sss.getSequence()).isEqualTo(0);
    assertThat(sss.pick()).isEqualTo(1);
    assertThat(sss.getSequence()).isEqualTo(2);
    assertThat(sss.pick()).isEqualTo(2);
    assertThat(sss.getSequence()).isEqualTo(3);
    assertThat(sss.pick()).isEqualTo(2);
    assertThat(sss.getSequence()).isEqualTo(6);
    assertThat(sss.pick()).isEqualTo(0);
    assertThat(sss.getSequence()).isEqualTo(7);
    assertThat(sss.pick()).isEqualTo(1);
    assertThat(sss.getSequence()).isEqualTo(8);
    assertThat(sss.pick()).isEqualTo(2);
    assertThat(sss.getSequence()).isEqualTo(9);
  }

  @Test
  public void testImmediateWraparound() {
    float[] weights = {1.0f, 2.0f, 3.0f, 4.0f, 5.0f};
    Random random = new FakeRandom(-1);
    StaticStrideScheduler sss = new StaticStrideScheduler(weights, random);
    double totalWeight = 15;
    Map<Integer, Integer> pickCount = new HashMap<>();
    for (int i = 0; i < 1000; i++) {
      int result = sss.pick();
      pickCount.put(result, pickCount.getOrDefault(result, 0) + 1);
    }
    for (int i = 0; i < 5; i++) {
      assertThat(Math.abs(pickCount.getOrDefault(i, 0) / 1000.0 - weights[i] / totalWeight))
          .isAtMost(0.001);
    }
  }
  
  @Test
  public void testWraparound() {
    float[] weights = {1.0f, 2.0f, 3.0f, 4.0f, 5.0f};
    Random random = new FakeRandom(-500);
    StaticStrideScheduler sss = new StaticStrideScheduler(weights, random);
    double totalWeight = 15;
    Map<Integer, Integer> pickCount = new HashMap<>();
    for (int i = 0; i < 1000; i++) {
      int result = sss.pick();
      pickCount.put(result, pickCount.getOrDefault(result, 0) + 1);
    }
    for (int i = 0; i < 5; i++) {
      assertThat(Math.abs(pickCount.getOrDefault(i, 0) / 1000.0 - weights[i] / totalWeight))
          .isAtMost(0.0011);
    }
  }

  @Test
  public void testDeterministicWraparound() {
    float[] weights = {2.0f, 3.0f, 6.0f};
    Random random = new FakeRandom(-1);
    StaticStrideScheduler sss = new StaticStrideScheduler(weights, random);
    assertThat(sss.getSequence()).isEqualTo(0xFFFF_FFFFL);
    assertThat(sss.pick()).isEqualTo(1);
    assertThat(sss.getSequence()).isEqualTo(2);
    assertThat(sss.pick()).isEqualTo(2);
    assertThat(sss.getSequence()).isEqualTo(3);
    assertThat(sss.pick()).isEqualTo(2);
    assertThat(sss.getSequence()).isEqualTo(6);
    assertThat(sss.pick()).isEqualTo(0);
    assertThat(sss.getSequence()).isEqualTo(7);
    assertThat(sss.pick()).isEqualTo(1);
    assertThat(sss.getSequence()).isEqualTo(8);
    assertThat(sss.pick()).isEqualTo(2);
    assertThat(sss.getSequence()).isEqualTo(9);
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
}
