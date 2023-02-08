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
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
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
import io.grpc.LoadBalancer.SubchannelStateListener;
import io.grpc.SynchronizationContext;
import io.grpc.internal.FakeClock;
import io.grpc.services.InternalCallMetricRecorder;
import io.grpc.util.RoundRobinLoadBalancer.EmptyPicker;
import io.grpc.xds.WeightedRoundRobinLoadBalancer.WeightedRoundRobinLoadBalancerConfig;
import io.grpc.xds.WeightedRoundRobinLoadBalancer.WeightedRoundRobinPicker;
import io.grpc.xds.WeightedRoundRobinLoadBalancer.WrrSubchannel;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
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
  private ArgumentCaptor<WeightedRoundRobinPicker> pickerCaptor;

  private final List<EquivalentAddressGroup> servers = Lists.newArrayList();

  private final Map<List<EquivalentAddressGroup>, Subchannel> subchannels = Maps.newLinkedHashMap();

  private final Map<Subchannel, SubchannelStateListener> subchannelStateListeners =
        Maps.newLinkedHashMap();

  private final List<ClientCall<OrcaLoadReportRequest, OrcaLoadReport>>  oobCalls =
          new ArrayList<>();

  private WeightedRoundRobinLoadBalancer wrr;

  private final FakeClock fakeClock = new FakeClock();

  private WeightedRoundRobinLoadBalancerConfig weightedConfig =
          WeightedRoundRobinLoadBalancerConfig.newBuilder().build();

  private static final Attributes.Key<String> MAJOR_KEY = Attributes.Key.create("major-key");

  private final Attributes affinity =
            Attributes.newBuilder().set(MAJOR_KEY, "I got the keys").build();

  private static final double EDF_PRECISE = 0.01;
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
    wrr = new WeightedRoundRobinLoadBalancer(helper, fakeClock.getTimeProvider());
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
    verify(helper, times(2)).updateBalancingState(
            eq(ConnectivityState.READY), pickerCaptor.capture());
    assertThat(pickerCaptor.getAllValues().size()).isEqualTo(2);
    assertThat(pickerCaptor.getAllValues().get(0).getList().size()).isEqualTo(1);
    WeightedRoundRobinPicker weightedPicker = pickerCaptor.getAllValues().get(1);
    WrrSubchannel weightedSubchannel1 = (WrrSubchannel) weightedPicker.getList().get(0);
    WrrSubchannel weightedSubchannel2 = (WrrSubchannel) weightedPicker.getList().get(1);
    weightedSubchannel1.onLoadReport(InternalCallMetricRecorder.createMetricReport(
            0.1, 0.1, 1, new HashMap<>(), new HashMap<>()));
    weightedSubchannel2.onLoadReport(InternalCallMetricRecorder.createMetricReport(
            0.2, 0.1, 1, new HashMap<>(), new HashMap<>()));
    assertThat(fakeClock.forwardTime(11, TimeUnit.SECONDS)).isEqualTo(1);
    assertThat(weightedPicker.pickSubchannel(mockArgs)
            .getSubchannel()).isEqualTo(weightedSubchannel1);
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
    WeightedRoundRobinPicker weightedPicker = pickerCaptor.getAllValues().get(1);
    WrrSubchannel weightedSubchannel1 = (WrrSubchannel) weightedPicker.getList().get(0);
    WrrSubchannel weightedSubchannel2 = (WrrSubchannel) weightedPicker.getList().get(1);
    weightedSubchannel1.onLoadReport(InternalCallMetricRecorder.createMetricReport(
            0.1, 0.1, 1, new HashMap<>(), new HashMap<>()));
    weightedSubchannel2.onLoadReport(InternalCallMetricRecorder.createMetricReport(
            0.9, 0.1, 1, new HashMap<>(), new HashMap<>()));
    assertThat(fakeClock.forwardTime(11, TimeUnit.SECONDS)).isEqualTo(1);
    PickResult pickResult = weightedPicker.pickSubchannel(mockArgs);
    assertThat(pickResult.getSubchannel()).isEqualTo(weightedSubchannel1);
    assertThat(pickResult.getStreamTracerFactory()).isNotNull();
    assertThat(oobCalls.isEmpty()).isTrue();
    weightedConfig = WeightedRoundRobinLoadBalancerConfig.newBuilder().setEnableOobLoadReport(true)
            .build();
    syncContext.execute(() -> wrr.acceptResolvedAddresses(ResolvedAddresses.newBuilder()
            .setAddresses(servers).setLoadBalancingPolicyConfig(weightedConfig)
            .setAttributes(affinity).build()));
    pickResult = weightedPicker.pickSubchannel(mockArgs);
    assertThat(pickResult.getSubchannel()).isEqualTo(weightedSubchannel1);
    assertThat(pickResult.getStreamTracerFactory()).isNull();
    assertThat(oobCalls.size()).isEqualTo(2);
  }

  @Test
  public void pickByWeight() {
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
    WeightedRoundRobinPicker weightedPicker = pickerCaptor.getAllValues().get(2);
    WrrSubchannel weightedSubchannel1 = (WrrSubchannel) weightedPicker.getList().get(0);
    WrrSubchannel weightedSubchannel2 = (WrrSubchannel) weightedPicker.getList().get(1);
    WrrSubchannel weightedSubchannel3 = (WrrSubchannel) weightedPicker.getList().get(2);
    weightedSubchannel1.onLoadReport(InternalCallMetricRecorder.createMetricReport(
            0.12, 0.1, 22, new HashMap<>(), new HashMap<>()));
    weightedSubchannel2.onLoadReport(InternalCallMetricRecorder.createMetricReport(
            0.28, 0.1, 40, new HashMap<>(), new HashMap<>()));
    weightedSubchannel3.onLoadReport(InternalCallMetricRecorder.createMetricReport(
            0.86, 0.1, 100, new HashMap<>(), new HashMap<>()));
    assertThat(fakeClock.forwardTime(11, TimeUnit.SECONDS)).isEqualTo(1);
    Map<Subchannel, Integer> pickCount = new HashMap<>();
    for (int i = 0; i < 1000; i++) {
      Subchannel result = weightedPicker.pickSubchannel(mockArgs).getSubchannel();
      pickCount.put(result, pickCount.getOrDefault(result, 0) + 1);
    }
    assertThat(pickCount.size()).isEqualTo(3);
    assertThat(Math.abs(pickCount.get(weightedSubchannel1) / 1000.0
            - 22 / 0.12 / (22 / 0.12 + 40 / 0.28 + 100 / 0.86))).isAtMost(EDF_PRECISE);
    assertThat(Math.abs(pickCount.get(weightedSubchannel2) / 1000.0
            - 40 / 0.28 / (22 / 0.12 + 40 / 0.28 + 100 / 0.86) )).isAtMost(EDF_PRECISE);
    assertThat(Math.abs(pickCount.get(weightedSubchannel3) / 1000.0
            - 100 / 0.86 / ( 22 / 0.12 + 40 / 0.28 + 100 / 0.86) )).isAtMost(EDF_PRECISE);
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
    assertThat(pickerCaptor.getValue()).isInstanceOf(EmptyPicker.class);
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
    WeightedRoundRobinPicker weightedPicker = pickerCaptor.getAllValues().get(1);
    WrrSubchannel weightedSubchannel1 = (WrrSubchannel) weightedPicker.getList().get(0);
    WrrSubchannel weightedSubchannel2 = (WrrSubchannel) weightedPicker.getList().get(1);
    weightedSubchannel1.onLoadReport(InternalCallMetricRecorder.createMetricReport(
            0.1, 0.1, 1, new HashMap<>(), new HashMap<>()));
    weightedSubchannel2.onLoadReport(InternalCallMetricRecorder.createMetricReport(
            0.2, 0.1, 1, new HashMap<>(), new HashMap<>()));
    assertThat(fakeClock.forwardTime(5, TimeUnit.SECONDS)).isEqualTo(1);
    Map<Subchannel, Integer> pickCount = new HashMap<>();
    for (int i = 0; i < 1000; i++) {
      Subchannel result = weightedPicker.pickSubchannel(mockArgs).getSubchannel();
      pickCount.put(result, pickCount.getOrDefault(result, 0) + 1);
    }
    assertThat(pickCount.size()).isEqualTo(2);
    // within blackout period, fallback to simple round robin
    assertThat(Math.abs(pickCount.get(weightedSubchannel1) / 1000.0 - 0.5)).isAtMost(EDF_PRECISE);
    assertThat(Math.abs(pickCount.get(weightedSubchannel2) / 1000.0 - 0.5)).isAtMost(EDF_PRECISE);

    assertThat(fakeClock.forwardTime(5, TimeUnit.SECONDS)).isEqualTo(1);
    pickCount = new HashMap<>();
    for (int i = 0; i < 1000; i++) {
      Subchannel result = weightedPicker.pickSubchannel(mockArgs).getSubchannel();
      pickCount.put(result, pickCount.getOrDefault(result, 0) + 1);
    }
    assertThat(pickCount.size()).isEqualTo(2);
    // after blackout period
    assertThat(Math.abs(pickCount.get(weightedSubchannel1) / 1000.0 - 2.0 / 3))
            .isAtMost(EDF_PRECISE);
    assertThat(Math.abs(pickCount.get(weightedSubchannel2) / 1000.0 - 1.0 / 3))
            .isAtMost(EDF_PRECISE);
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
    WeightedRoundRobinPicker weightedPicker = pickerCaptor.getAllValues().get(1);
    WrrSubchannel weightedSubchannel1 = (WrrSubchannel) weightedPicker.getList().get(0);
    WrrSubchannel weightedSubchannel2 = (WrrSubchannel) weightedPicker.getList().get(1);
    weightedSubchannel1.onLoadReport(InternalCallMetricRecorder.createMetricReport(
            0.1, 0.1, 1, new HashMap<>(), new HashMap<>()));
    weightedSubchannel2.onLoadReport(InternalCallMetricRecorder.createMetricReport(
            0.2, 0.1, 1, new HashMap<>(), new HashMap<>()));
    assertThat(fakeClock.forwardTime(10, TimeUnit.SECONDS)).isEqualTo(1);
    Map<Subchannel, Integer> pickCount = new HashMap<>();
    for (int i = 0; i < 1000; i++) {
      Subchannel result = weightedPicker.pickSubchannel(mockArgs).getSubchannel();
      pickCount.put(result, pickCount.getOrDefault(result, 0) + 1);
    }
    assertThat(pickCount.size()).isEqualTo(2);
    assertThat(Math.abs(pickCount.get(weightedSubchannel1) / 1000.0 - 2.0 / 3))
            .isAtMost(EDF_PRECISE);
    assertThat(Math.abs(pickCount.get(weightedSubchannel2) / 1000.0 - 1.0 / 3))
            .isAtMost(EDF_PRECISE);

    // weight expired, fallback to simple round robin
    assertThat(fakeClock.forwardTime(300, TimeUnit.SECONDS)).isEqualTo(1);
    pickCount = new HashMap<>();
    for (int i = 0; i < 1000; i++) {
      Subchannel result = weightedPicker.pickSubchannel(mockArgs).getSubchannel();
      pickCount.put(result, pickCount.getOrDefault(result, 0) + 1);
    }
    assertThat(pickCount.size()).isEqualTo(2);
    assertThat(Math.abs(pickCount.get(weightedSubchannel1) / 1000.0 - 0.5))
            .isAtMost(EDF_PRECISE);
    assertThat(Math.abs(pickCount.get(weightedSubchannel2) / 1000.0 - 0.5))
            .isAtMost(EDF_PRECISE);
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
}
