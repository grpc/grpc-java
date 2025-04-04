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
import static io.grpc.ConnectivityState.CONNECTING;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
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
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientStreamTracer;
import io.grpc.ConnectivityState;
import io.grpc.ConnectivityStateInfo;
import io.grpc.DoubleHistogramMetricInstrument;
import io.grpc.EquivalentAddressGroup;
import io.grpc.InternalManagedChannelBuilder;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancer.CreateSubchannelArgs;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancer.PickResult;
import io.grpc.LoadBalancer.ResolvedAddresses;
import io.grpc.LoadBalancer.Subchannel;
import io.grpc.LoadBalancer.SubchannelPicker;
import io.grpc.LoadBalancer.SubchannelStateListener;
import io.grpc.LongCounterMetricInstrument;
import io.grpc.Metadata;
import io.grpc.MetricRecorder;
import io.grpc.MetricSink;
import io.grpc.NameResolver;
import io.grpc.NoopMetricSink;
import io.grpc.ServerCall;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.SynchronizationContext;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.internal.FakeClock;
import io.grpc.internal.PickFirstLoadBalancerProvider;
import io.grpc.internal.TestUtils;
import io.grpc.internal.testing.StreamRecorder;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.services.InternalCallMetricRecorder;
import io.grpc.services.MetricReport;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.GrpcCleanupRule;
import io.grpc.testing.TestMethodDescriptors;
import io.grpc.util.AbstractTestHelper;
import io.grpc.util.MultiChildLoadBalancer.ChildLbState;
import io.grpc.xds.WeightedRoundRobinLoadBalancer.StaticStrideScheduler;
import io.grpc.xds.WeightedRoundRobinLoadBalancer.WeightedChildLbState;
import io.grpc.xds.WeightedRoundRobinLoadBalancer.WeightedRoundRobinLoadBalancerConfig;
import io.grpc.xds.WeightedRoundRobinLoadBalancer.WeightedRoundRobinPicker;
import java.net.SocketAddress;
import java.util.Arrays;
import java.util.Collections;
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
import org.mockito.ArgumentMatcher;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.mockito.stubbing.Answer;

@RunWith(JUnit4.class)
public class WeightedRoundRobinLoadBalancerTest {
  @Rule
  public final MockitoRule mockito = MockitoJUnit.rule();
  @Rule
  public final GrpcCleanupRule grpcCleanupRule = new GrpcCleanupRule();

  private final TestHelper testHelperInstance;
  private final Helper helper;

  @Mock
  private LoadBalancer.PickSubchannelArgs mockArgs;

  @Captor
  private ArgumentCaptor<SubchannelPicker> pickerCaptor;
  @Captor
  private ArgumentCaptor<SubchannelPicker> pickerCaptor2;

  private final List<EquivalentAddressGroup> servers = Lists.newArrayList();
  private final Map<List<EquivalentAddressGroup>, Subchannel> subchannels = Maps.newLinkedHashMap();

  private final Queue<ClientCall<OrcaLoadReportRequest, OrcaLoadReport>> oobCalls =
          new ConcurrentLinkedQueue<>();

  private WeightedRoundRobinLoadBalancer wrr;

  private final FakeClock fakeClock = new FakeClock();

  @Mock
  private MetricRecorder mockMetricRecorder;

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

  private String channelTarget = "channel-target";
  private String locality = "locality";
  private String backendService = "the-backend-service";

  public WeightedRoundRobinLoadBalancerTest() {
    testHelperInstance = new TestHelper();
    helper = mock(Helper.class, delegatesTo(testHelperInstance));
  }

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
      testHelperInstance.setChannel(sc, channel);
      subchannels.put(Arrays.asList(eag), sc);
    }
    wrr = new WeightedRoundRobinLoadBalancer(helper, fakeClock.getDeadlineTicker(),
        new FakeRandom(0));

    verify(helper, times(3)).createSubchannel(any(CreateSubchannelArgs.class));
    reset(helper);
  }

  @Test
  public void pickChildLbTF() throws Exception {
    syncContext.execute(() -> wrr.acceptResolvedAddresses(ResolvedAddresses.newBuilder()
        .setAddresses(servers.subList(0, 1)).setLoadBalancingPolicyConfig(weightedConfig)
        .setAttributes(affinity).build()));
    Iterator<Subchannel> it = subchannels.values().iterator();
    Subchannel readySubchannel1 = it.next();
    getSubchannelStateListener(readySubchannel1).onSubchannelState(ConnectivityStateInfo
        .forTransientFailure(Status.UNAVAILABLE));
    verify(helper).updateBalancingState(
        eq(ConnectivityState.TRANSIENT_FAILURE), pickerCaptor.capture());
    final WeightedRoundRobinPicker weightedPicker =
        (WeightedRoundRobinPicker) pickerCaptor.getValue();
    weightedPicker.pickSubchannel(mockArgs);
  }

  @Test
  public void wrrLifeCycle() {
    syncContext.execute(() -> wrr.acceptResolvedAddresses(ResolvedAddresses.newBuilder()
                .setAddresses(servers).setLoadBalancingPolicyConfig(weightedConfig)
            .setAttributes(affinity).build()));
    verify(helper, times(3)).createSubchannel(
                any(CreateSubchannelArgs.class));
    assertThat(getNumFilteredPendingTasks()).isEqualTo(1);

    Iterator<Subchannel> it = subchannels.values().iterator();
    Subchannel readySubchannel1 = it.next();
    getSubchannelStateListener(readySubchannel1).onSubchannelState(ConnectivityStateInfo
            .forNonError(ConnectivityState.READY));
    Subchannel readySubchannel2  = it.next();
    getSubchannelStateListener(readySubchannel2).onSubchannelState(ConnectivityStateInfo
            .forNonError(ConnectivityState.READY));
    Subchannel connectingSubchannel = it.next();
    getSubchannelStateListener(connectingSubchannel).onSubchannelState(ConnectivityStateInfo
            .forNonError(CONNECTING));
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
    assertThat(weightedPickerStr).contains("pickers=");

    WeightedChildLbState weightedChild1 = (WeightedChildLbState) getChild(weightedPicker, 0);
    WeightedChildLbState weightedChild2 = (WeightedChildLbState) getChild(weightedPicker, 1);
    weightedChild1.new OrcaReportListener(weightedConfig.errorUtilizationPenalty).onLoadReport(
        InternalCallMetricRecorder.createMetricReport(
            0.1, 0, 0.1, 1, 0, new HashMap<>(), new HashMap<>(), new HashMap<>()));
    weightedChild2.new OrcaReportListener(weightedConfig.errorUtilizationPenalty).onLoadReport(
        InternalCallMetricRecorder.createMetricReport(
            0.2, 0, 0.1, 1, 0, new HashMap<>(), new HashMap<>(), new HashMap<>()));
    int expectedTasks = isEnabledHappyEyeballs() ? 2 : 1;
    assertThat(fakeClock.forwardTime(11, TimeUnit.SECONDS)).isEqualTo(expectedTasks);

    assertThat(getAddressesFromPick(weightedPicker)).isEqualTo(servers.get(0));
    assertThat(fakeClock.getPendingTasks().size()).isEqualTo(1);
    weightedConfig = WeightedRoundRobinLoadBalancerConfig.newBuilder()
        .setWeightUpdatePeriodNanos(500_000_000L) //.5s
        .build();
    syncContext.execute(() -> wrr.acceptResolvedAddresses(ResolvedAddresses.newBuilder()
        .setAddresses(servers).setLoadBalancingPolicyConfig(weightedConfig)
        .setAttributes(affinity).build()));
    assertThat(getNumFilteredPendingTasks()).isEqualTo(1);

    syncContext.execute(() -> wrr.shutdown());
    for (Subchannel subchannel: subchannels.values()) {
      verify(subchannel).shutdown();
    }
    assertThat(getNumFilteredPendingTasks()).isEqualTo(0);
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
    verify(helper, times(3)).createSubchannel(
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
    int expectedTasks = isEnabledHappyEyeballs() ? 2 : 1;
    assertThat(fakeClock.forwardTime(11, TimeUnit.SECONDS)).isEqualTo(expectedTasks);
    PickResult pickResult = weightedPicker.pickSubchannel(mockArgs);
    assertThat(getAddresses(pickResult)).isEqualTo(servers.get(0));
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
    assertThat(getAddresses(pickResult)).isEqualTo(servers.get(0));
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
    assertThat(getNumFilteredPendingTasks()).isEqualTo(1);

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
    assertThat(Math.abs(pickCount.get(servers.get(0)) / 10000.0 - subchannel1PickRatio))
        .isAtMost(0.0002);
    assertThat(Math.abs(pickCount.get(servers.get(1)) / 10000.0 - subchannel2PickRatio ))
        .isAtMost(0.0002);
    assertThat(Math.abs(pickCount.get(servers.get(2)) / 10000.0 - subchannel3PickRatio ))
        .isAtMost(0.0002);
  }

  private SubchannelStateListener getSubchannelStateListener(Subchannel mockSubChannel) {
    return testHelperInstance.getSubchannelStateListener(mockSubChannel);
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
            .setAttributes(affinity).build()).isOk()).isFalse();
    verify(helper, never()).createSubchannel(any(CreateSubchannelArgs.class));
    verify(helper).updateBalancingState(eq(ConnectivityState.TRANSIENT_FAILURE), any());
    assertThat(fakeClock.getPendingTasks()).isEmpty();

    syncContext.execute(() -> wrr.acceptResolvedAddresses(ResolvedAddresses.newBuilder()
            .setAddresses(servers).setLoadBalancingPolicyConfig(weightedConfig)
            .setAttributes(affinity).build()));
    verify(helper, times(3)).createSubchannel(
            any(CreateSubchannelArgs.class));
    verify(helper).updateBalancingState(eq(CONNECTING), pickerCaptor.capture());
    assertThat(pickerCaptor.getValue().pickSubchannel(mockArgs))
        .isEqualTo(PickResult.withNoResult());
    int expectedCount = isEnabledHappyEyeballs() ? servers.size() + 1 : 1;
    assertThat(fakeClock.forwardTime(11, TimeUnit.SECONDS)).isEqualTo( expectedCount);
  }

  @Test
  public void blackoutPeriod() {
    syncContext.execute(() -> wrr.acceptResolvedAddresses(ResolvedAddresses.newBuilder()
            .setAddresses(servers).setLoadBalancingPolicyConfig(weightedConfig)
            .setAttributes(affinity).build()));
    verify(helper, times(3)).createSubchannel(any(CreateSubchannelArgs.class));
    assertThat(getNumFilteredPendingTasks()).isEqualTo(1);

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
    int expectedCount = isEnabledHappyEyeballs() ? 2 : 1;
    assertThat(fakeClock.forwardTime(5, TimeUnit.SECONDS)).isEqualTo(expectedCount);
    Map<EquivalentAddressGroup, Integer> pickCount = new HashMap<>();
    for (int i = 0; i < 10000; i++) {
      EquivalentAddressGroup result = getAddressesFromPick(weightedPicker);
      pickCount.put(result, pickCount.getOrDefault(result, 0) + 1);
    }
    assertThat(pickCount.size()).isEqualTo(2);
    // within blackout period, fallback to simple round robin
    assertThat(Math.abs(pickCount.get(servers.get(0)) / 10000.0 - 0.5)).isLessThan(0.002);
    assertThat(Math.abs(pickCount.get(servers.get(1)) / 10000.0 - 0.5)).isLessThan(0.002);

    assertThat(fakeClock.forwardTime(5, TimeUnit.SECONDS)).isEqualTo(1);
    pickCount = new HashMap<>();
    for (int i = 0; i < 10000; i++) {
      EquivalentAddressGroup result = getAddressesFromPick(weightedPicker);
      pickCount.put(result, pickCount.getOrDefault(result, 0) + 1);
    }
    assertThat(pickCount.size()).isEqualTo(2);
    // after blackout period
    assertThat(Math.abs(pickCount.get(servers.get(0)) / 10000.0 - 2.0 / 3)).isLessThan(0.002);
    assertThat(Math.abs(pickCount.get(servers.get(1)) / 10000.0 - 1.0 / 3)).isLessThan(0.002);
  }

  private boolean isEnabledHappyEyeballs() {
    return PickFirstLoadBalancerProvider.isEnabledHappyEyeballs();
  }

  @Test
  public void updateWeightTimer() {
    syncContext.execute(() -> wrr.acceptResolvedAddresses(ResolvedAddresses.newBuilder()
        .setAddresses(servers).setLoadBalancingPolicyConfig(weightedConfig)
        .setAttributes(affinity).build()));
    verify(helper, times(3)).createSubchannel(
        any(CreateSubchannelArgs.class));
    assertThat(getNumFilteredPendingTasks()).isEqualTo(1);

    Iterator<Subchannel> it = subchannels.values().iterator();
    Subchannel readySubchannel1 = it.next();
    getSubchannelStateListener(readySubchannel1).onSubchannelState(ConnectivityStateInfo
        .forNonError(ConnectivityState.READY));
    Subchannel readySubchannel2  = it.next();
    getSubchannelStateListener(readySubchannel2).onSubchannelState(ConnectivityStateInfo
        .forNonError(ConnectivityState.READY));
    Subchannel connectingSubchannel = it.next();
    getSubchannelStateListener(connectingSubchannel).onSubchannelState(ConnectivityStateInfo
        .forNonError(CONNECTING));
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
    int expectedTasks = isEnabledHappyEyeballs() ? 2 : 1;
    assertThat(fakeClock.forwardTime(11, TimeUnit.SECONDS)).isEqualTo(expectedTasks);
    assertThat(getAddressesFromPick(weightedPicker)).isEqualTo(servers.get(0));
    assertThat(getNumFilteredPendingTasks()).isEqualTo(1);
    weightedConfig = WeightedRoundRobinLoadBalancerConfig.newBuilder()
        .setWeightUpdatePeriodNanos(500_000_000L) //.5s
        .build();
    syncContext.execute(() -> wrr.acceptResolvedAddresses(ResolvedAddresses.newBuilder()
        .setAddresses(servers).setLoadBalancingPolicyConfig(weightedConfig)
        .setAttributes(affinity).build()));
    assertThat(getNumFilteredPendingTasks()).isEqualTo(1);
    weightedChild1.new OrcaReportListener(weightedConfig.errorUtilizationPenalty).onLoadReport(
        InternalCallMetricRecorder.createMetricReport(
            0.2, 0, 0.1, 1, 0, new HashMap<>(), new HashMap<>(), new HashMap<>()));
    weightedChild2.new OrcaReportListener(weightedConfig.errorUtilizationPenalty).onLoadReport(
        InternalCallMetricRecorder.createMetricReport(
            0.1, 0, 0.1, 1, 0, new HashMap<>(), new HashMap<>(), new HashMap<>()));
    //timer fires, new weight updated
    expectedTasks = isEnabledHappyEyeballs() ? 2 : 1;
    assertThat(fakeClock.forwardTime(500, TimeUnit.MILLISECONDS)).isEqualTo(expectedTasks);
    assertThat(getAddressesFromPick(weightedPicker)).isEqualTo(servers.get(1));
    assertThat(getAddressesFromPick(weightedPicker)).isEqualTo(servers.get(0));
  }

  @Test
  public void weightExpired() {
    syncContext.execute(() -> wrr.acceptResolvedAddresses(ResolvedAddresses.newBuilder()
            .setAddresses(servers).setLoadBalancingPolicyConfig(weightedConfig)
            .setAttributes(affinity).build()));
    verify(helper, times(3)).createSubchannel(
            any(CreateSubchannelArgs.class));
    assertThat(getNumFilteredPendingTasks()).isEqualTo(1);

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
    int expectedTasks = isEnabledHappyEyeballs() ? 2 : 1;
    assertThat(fakeClock.forwardTime(10, TimeUnit.SECONDS)).isEqualTo(expectedTasks);
    Map<EquivalentAddressGroup, Integer> pickCount = new HashMap<>();
    for (int i = 0; i < 1000; i++) {
      EquivalentAddressGroup result = getAddressesFromPick(weightedPicker);
      pickCount.put(result, pickCount.getOrDefault(result, 0) + 1);
    }
    assertThat(pickCount.size()).isEqualTo(2);
    assertThat(Math.abs(pickCount.get(servers.get(0)) / 1000.0 - 2.0 / 3)).isLessThan(0.002);
    assertThat(Math.abs(pickCount.get(servers.get(1)) / 1000.0 - 1.0 / 3)).isLessThan(0.002);

    // weight expired, fallback to simple round robin
    assertThat(fakeClock.forwardTime(300, TimeUnit.SECONDS)).isEqualTo(1);
    pickCount = new HashMap<>();
    for (int i = 0; i < 1000; i++) {
      EquivalentAddressGroup result = getAddressesFromPick(weightedPicker);
      pickCount.put(result, pickCount.getOrDefault(result, 0) + 1);
    }
    assertThat(pickCount.size()).isEqualTo(2);
    assertThat(Math.abs(pickCount.get(servers.get(0)) / 1000.0 - 0.5)).isLessThan(0.002);
    assertThat(Math.abs(pickCount.get(servers.get(1)) / 1000.0 - 0.5)).isLessThan(0.002);
  }

  @Test
  public void rrFallback() {
    syncContext.execute(() -> wrr.acceptResolvedAddresses(ResolvedAddresses.newBuilder()
        .setAddresses(servers).setLoadBalancingPolicyConfig(weightedConfig)
        .setAttributes(affinity).build()));
    verify(helper, times(3)).createSubchannel(
        any(CreateSubchannelArgs.class));
    assertThat(getNumFilteredPendingTasks()).isEqualTo(1);

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
    int expectedTasks = isEnabledHappyEyeballs() ? 2 : 1;
    assertThat(fakeClock.forwardTime(10, TimeUnit.SECONDS)).isEqualTo(expectedTasks);
    Map<EquivalentAddressGroup, Integer> qpsByChannel = ImmutableMap.of(servers.get(0), 2,
        servers.get(1), 1);
    Map<EquivalentAddressGroup, Integer> pickCount = new HashMap<>();
    for (int i = 0; i < 1000; i++) {
      PickResult pickResult = weightedPicker.pickSubchannel(mockArgs);
      EquivalentAddressGroup addresses = getAddresses(pickResult);
      pickCount.merge(addresses, 1, Integer::sum);
      reportLoadOnRpc(pickResult, 0.1, 0, 0.1, qpsByChannel.get(addresses), 0);
    }
    assertThat(Math.abs(pickCount.get(servers.get(0)) / 1000.0 - 1.0 / 2)).isAtMost(0.1);
    assertThat(Math.abs(pickCount.get(servers.get(1)) / 1000.0 - 1.0 / 2)).isAtMost(0.1);

    // Identical to above except forwards time after each pick
    pickCount.clear();
    for (int i = 0; i < 1000; i++) {
      PickResult pickResult = weightedPicker.pickSubchannel(mockArgs);
      EquivalentAddressGroup addresses = getAddresses(pickResult);
      pickCount.merge(addresses, 1, Integer::sum);
      reportLoadOnRpc(pickResult, 0.1, 0, 0.1, qpsByChannel.get(addresses), 0);
      fakeClock.forwardTime(50, TimeUnit.MILLISECONDS);
    }
    assertThat(pickCount.size()).isEqualTo(2);
    assertThat(Math.abs(pickCount.get(servers.get(0)) / 1000.0 - 2.0 / 3)).isAtMost(0.1);
    assertThat(Math.abs(pickCount.get(servers.get(1)) / 1000.0 - 1.0 / 3)).isAtMost(0.1);
  }

  private static EquivalentAddressGroup getAddresses(PickResult pickResult) {
    return TestUtils.stripAttrs(pickResult.getSubchannel().getAddresses());
  }

  @Test
  public void unknownWeightIsAvgWeight() {
    syncContext.execute(() -> wrr.acceptResolvedAddresses(ResolvedAddresses.newBuilder()
            .setAddresses(servers).setLoadBalancingPolicyConfig(weightedConfig)
            .setAttributes(affinity).build()));
    verify(helper, times(3)).createSubchannel(
            any(CreateSubchannelArgs.class)); // 3 from setup plus 3 from the execute
    assertThat(getNumFilteredPendingTasks()).isEqualTo(1);

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
    assertThat(Math.abs(pickCount.get(servers.get(0)) / 1000.0 - 4.0 / 9)).isLessThan(0.002);
    assertThat(Math.abs(pickCount.get(servers.get(1)) / 1000.0 - 2.0 / 9)).isLessThan(0.002);
    // subchannel3's weight is average of subchannel1 and subchannel2
    assertThat(Math.abs(pickCount.get(servers.get(2)) / 1000.0 - 3.0 / 9)).isLessThan(0.002);
  }

  @Test
  public void pickFromOtherThread() throws Exception {
    syncContext.execute(() -> wrr.acceptResolvedAddresses(ResolvedAddresses.newBuilder()
            .setAddresses(servers).setLoadBalancingPolicyConfig(weightedConfig)
            .setAttributes(affinity).build()));
    verify(helper, times(3)).createSubchannel(
            any(CreateSubchannelArgs.class));
    assertThat(getNumFilteredPendingTasks()).isEqualTo(1);

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
    pickCount.put(servers.get(0), new AtomicInteger(0));
    pickCount.put(servers.get(1), new AtomicInteger(0));
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
    int expectedTasks = isEnabledHappyEyeballs() ? 2 : 1;
    assertThat(fakeClock.forwardTime(10, TimeUnit.SECONDS)).isEqualTo(expectedTasks);
    barrier.await();
    for (int i = 0; i < 1000; i++) {
      EquivalentAddressGroup result = getAddresses(weightedPicker.pickSubchannel(mockArgs));
      pickCount.get(result).addAndGet(1);
    }
    barrier.await();
    assertThat(pickCount.size()).isEqualTo(2);
    // after blackout period
    assertThat(Math.abs(pickCount.get(servers.get(0)).get() / 2000.0 - 2.0 / 3)).isLessThan(0.002);
    assertThat(Math.abs(pickCount.get(servers.get(1)).get() / 2000.0 - 1.0 / 3)).isLessThan(0.002);
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

  @Test
  public void removingAddressShutsdownSubchannel() {
    syncContext.execute(() -> wrr.acceptResolvedAddresses(ResolvedAddresses.newBuilder()
        .setAddresses(servers).setLoadBalancingPolicyConfig(weightedConfig)
        .setAttributes(affinity).build()));
    final Subchannel subchannel2 = subchannels.get(Collections.singletonList(servers.get(2)));

    InOrder inOrder = Mockito.inOrder(helper, subchannel2);
    // send LB only the first 2 addresses
    List<EquivalentAddressGroup> svs2 = Arrays.asList(servers.get(0), servers.get(1));
    syncContext.execute(() -> wrr.acceptResolvedAddresses(ResolvedAddresses.newBuilder()
        .setAddresses(svs2).setLoadBalancingPolicyConfig(weightedConfig)
        .setAttributes(affinity).build()));
    inOrder.verify(helper).updateBalancingState(eq(CONNECTING), any());
    inOrder.verify(subchannel2).shutdown();
  }


  @Test
  public void metrics() {
    // Give WRR some valid addresses to work with.
    Attributes attributesWithLocality = Attributes.newBuilder()
        .set(WeightedTargetLoadBalancer.CHILD_NAME, locality)
        .set(NameResolver.ATTR_BACKEND_SERVICE, backendService)
        .build();
    syncContext.execute(() -> wrr.acceptResolvedAddresses(ResolvedAddresses.newBuilder()
        .setAddresses(servers).setLoadBalancingPolicyConfig(weightedConfig)
        .setAttributes(attributesWithLocality).build()));

    // Flip the three subchannels to READY state to initiate the WRR logic
    Iterator<Subchannel> it = subchannels.values().iterator();
    Subchannel readySubchannel1 = it.next();
    getSubchannelStateListener(readySubchannel1).onSubchannelState(ConnectivityStateInfo
        .forNonError(ConnectivityState.READY));
    Subchannel readySubchannel2  = it.next();
    getSubchannelStateListener(readySubchannel2).onSubchannelState(ConnectivityStateInfo
        .forNonError(ConnectivityState.READY));
    Subchannel readySubchannel3  = it.next();
    getSubchannelStateListener(readySubchannel3).onSubchannelState(ConnectivityStateInfo
        .forNonError(ConnectivityState.READY));

    // WRR creates a picker that updates the weights for each of the child subchannels. This should
    // give us three "rr_fallback" metric events as we don't yet have any weights to do weighted
    // round-robin.
    verifyLongCounterRecord("grpc.lb.wrr.rr_fallback", 3, 1);

    // We should also see six records of endpoint weights. They should all be for 0 as we don't yet
    // have valid weights.
    verifyDoubleHistogramRecord("grpc.lb.wrr.endpoint_weights", 6, 0);

    // We should not yet be seeing any "endpoint_weight_stale" events since we don't even have
    // valid weights yet.
    verifyLongCounterRecord("grpc.lb.wrr.endpoint_weight_stale", 0, 1);

    // Each time weights are updated, WRR will see if each subchannel weight is useable. As we have
    // no weights yet, we should see three "endpoint_weight_not_yet_usable" metric events with the
    // value increasing by one each time as all the endpoints come online.
    verifyLongCounterRecord("grpc.lb.wrr.endpoint_weight_not_yet_usable", 1, 1);
    verifyLongCounterRecord("grpc.lb.wrr.endpoint_weight_not_yet_usable", 1, 2);
    verifyLongCounterRecord("grpc.lb.wrr.endpoint_weight_not_yet_usable", 1, 3);

    // Send one child LB state an ORCA update with some valid utilization/qps data so that weights
    // can be calculated, but it's still essentially round_robin
    Iterator<ChildLbState> childLbStates = wrr.getChildLbStates().iterator();
    ((WeightedChildLbState)childLbStates.next()).new OrcaReportListener(
        weightedConfig.errorUtilizationPenalty).onLoadReport(
        InternalCallMetricRecorder.createMetricReport(0.1, 0, 0.1, 1, 0, new HashMap<>(),
            new HashMap<>(), new HashMap<>()));

    fakeClock.forwardTime(1, TimeUnit.SECONDS);

    // Now send a second child LB state an ORCA update, so there's real weights
    ((WeightedChildLbState)childLbStates.next()).new OrcaReportListener(
        weightedConfig.errorUtilizationPenalty).onLoadReport(
        InternalCallMetricRecorder.createMetricReport(0.1, 0, 0.1, 1, 0, new HashMap<>(),
            new HashMap<>(), new HashMap<>()));
    ((WeightedChildLbState)childLbStates.next()).new OrcaReportListener(
        weightedConfig.errorUtilizationPenalty).onLoadReport(
        InternalCallMetricRecorder.createMetricReport(0.1, 0, 0.1, 1, 0, new HashMap<>(),
            new HashMap<>(), new HashMap<>()));

    // Let's reset the mock MetricsRecorder so that it's easier to verify what happened after the
    // weights were updated
    reset(mockMetricRecorder);

    // We go forward in time past the default 10s blackout period for the first child. The weights
    // would get updated as the default update interval is 1s.
    fakeClock.forwardTime(9, TimeUnit.SECONDS);

    verifyLongCounterRecord("grpc.lb.wrr.rr_fallback", 1, 1);

    // And after another second the other children have weights
    reset(mockMetricRecorder);
    fakeClock.forwardTime(1, TimeUnit.SECONDS);

    // Since we have weights on all the child LB states, the weight update should not result in
    // further rr_fallback metric entries.
    verifyLongCounterRecord("grpc.lb.wrr.rr_fallback", 0, 1);

    // We should not see an increase to the earlier count of "endpoint_weight_not_yet_usable".
    verifyLongCounterRecord("grpc.lb.wrr.endpoint_weight_not_yet_usable", 0, 1);

    // No endpoints should have gotten stale yet either.
    verifyLongCounterRecord("grpc.lb.wrr.endpoint_weight_stale", 0, 1);

    // Now with valid weights we should have seen the value in the endpoint weights histogram.
    verifyDoubleHistogramRecord("grpc.lb.wrr.endpoint_weights", 3, 10);

    reset(mockMetricRecorder);

    // Weights become stale in three minutes. Let's move ahead in time by 3 minutes and make sure
    // we get metrics events for each endpoint.
    fakeClock.forwardTime(3, TimeUnit.MINUTES);

    verifyLongCounterRecord("grpc.lb.wrr.endpoint_weight_stale", 1, 3);

    // With the weights stale each three endpoints should report 0 weights.
    verifyDoubleHistogramRecord("grpc.lb.wrr.endpoint_weights", 3, 0);

    // Since the weights are now stale the update should have triggered an additional rr_fallback
    // event.
    verifyLongCounterRecord("grpc.lb.wrr.rr_fallback", 1, 1);

    // No further weights-not-useable events should occur, since we have received weights and
    // are out of the blackout.
    verifyLongCounterRecord("grpc.lb.wrr.endpoint_weight_not_yet_usable", 0, 1);

    // All metric events should be accounted for.
    verifyNoMoreInteractions(mockMetricRecorder);
  }

  @Test
  public void metricWithRealChannel() throws Exception {
    String serverName = "wrr-metrics";
    grpcCleanupRule.register(
        InProcessServerBuilder.forName(serverName)
        .addService(ServerServiceDefinition.builder(
            TestMethodDescriptors.voidMethod().getServiceName())
          .addMethod(TestMethodDescriptors.voidMethod(), (call, headers) -> {
            call.sendHeaders(new Metadata());
            call.sendMessage(null);
            call.close(Status.OK, new Metadata());
            return new ServerCall.Listener<Void>() {};
          })
          .build())
        .directExecutor()
        .build()
        .start());
    MetricSink metrics = mock(MetricSink.class, delegatesTo(new NoopMetricSink()));
    Channel channel = grpcCleanupRule.register(
        InternalManagedChannelBuilder.addMetricSink(
            InProcessChannelBuilder.forName(serverName)
            .defaultServiceConfig(Collections.singletonMap(
                "loadBalancingConfig", Arrays.asList(Collections.singletonMap(
                    "weighted_round_robin", Collections.emptyMap()))))
            .directExecutor(),
        metrics)
        .directExecutor()
        .build());

    // Ping-pong to wait for channel to fully start
    StreamRecorder<Void> recorder = StreamRecorder.create();
    StreamObserver<Void> requestObserver = ClientCalls.asyncClientStreamingCall(
        channel.newCall(TestMethodDescriptors.voidMethod(), CallOptions.DEFAULT), recorder);
    requestObserver.onCompleted();
    assertThat(recorder.awaitCompletion(10, TimeUnit.SECONDS)).isTrue();
    assertThat(recorder.getError()).isNull();

    // Make sure at least one metric works. The other tests will make sure other metrics and the
    // edge cases are working.
    verify(metrics).addLongCounter(
        argThat((instr) -> instr.getName().equals("grpc.lb.wrr.rr_fallback")),
        eq(1L),
        eq(Arrays.asList("directaddress:///wrr-metrics")),
        eq(Arrays.asList("", "")));
  }

  // Verifies that the MetricRecorder has been called to record a long counter value of 1 for the
  // given metric name, the given number of times
  private void verifyLongCounterRecord(String name, int times, long value) {
    verify(mockMetricRecorder, times(times)).addLongCounter(
        argThat(new ArgumentMatcher<LongCounterMetricInstrument>() {
          @Override
          public boolean matches(LongCounterMetricInstrument longCounterInstrument) {
            return longCounterInstrument.getName().equals(name);
          }
        }),
        eq(value),
        eq(Lists.newArrayList(channelTarget)),
        eq(Lists.newArrayList(locality, backendService)));
  }

  // Verifies that the MetricRecorder has been called to record a given double histogram value the
  // given amount of times.
  private void verifyDoubleHistogramRecord(String name, int times, double value) {
    verify(mockMetricRecorder, times(times)).recordDoubleHistogram(
        argThat(new ArgumentMatcher<DoubleHistogramMetricInstrument>() {
          @Override
          public boolean matches(DoubleHistogramMetricInstrument doubleHistogramInstrument) {
            return doubleHistogramInstrument.getName().equals(name);
          }
        }),
        eq(value),
        eq(Lists.newArrayList(channelTarget)),
        eq(Lists.newArrayList(locality, backendService)));
  }

  private int getNumFilteredPendingTasks() {
    return AbstractTestHelper.getNumFilteredPendingTasks(fakeClock);
  }

  private static final Metadata.Key<OrcaLoadReport> ORCA_LOAD_METRICS_KEY =
        Metadata.Key.of(
            "endpoint-load-metrics-bin",
            ProtoUtils.metadataMarshaller(OrcaLoadReport.getDefaultInstance()));
  private static final ClientStreamTracer.StreamInfo STREAM_INFO =
      ClientStreamTracer.StreamInfo.newBuilder().build();

  private static void reportLoadOnRpc(
      PickResult pickResult,
      double cpuUtilization,
      double applicationUtilization,
      double memoryUtilization,
      double qps,
      double eps) {
    ClientStreamTracer childTracer = pickResult.getStreamTracerFactory()
        .newClientStreamTracer(STREAM_INFO, new Metadata());
    Metadata trailer = new Metadata();
    trailer.put(
        ORCA_LOAD_METRICS_KEY,
        OrcaLoadReport.newBuilder()
          .setCpuUtilization(cpuUtilization)
          .setApplicationUtilization(applicationUtilization)
          .setMemUtilization(memoryUtilization)
          .setRpsFractional(qps)
          .setEps(eps)
          .build());
    childTracer.inboundTrailers(trailer);
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
    public TestHelper() {
      super(fakeClock, syncContext);
    }

    @Override
    public Map<List<EquivalentAddressGroup>, Subchannel> getSubchannelMap() {
      return subchannels;
    }

    @Override
    public MetricRecorder getMetricRecorder() {
      return mockMetricRecorder;
    }

    @Override
    public String getChannelTarget() {
      return channelTarget;
    }
  }
}
