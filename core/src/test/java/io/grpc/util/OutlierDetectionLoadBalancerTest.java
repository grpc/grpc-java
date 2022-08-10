/*
 * Copyright 2022 The gRPC Authors
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

package io.grpc.util;

import static com.google.common.truth.Truth.assertThat;
import static io.grpc.ConnectivityState.CONNECTING;
import static io.grpc.ConnectivityState.READY;
import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import io.grpc.ConnectivityState;
import io.grpc.ConnectivityStateInfo;
import io.grpc.EquivalentAddressGroup;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancer.CreateSubchannelArgs;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancer.ResolvedAddresses;
import io.grpc.LoadBalancer.Subchannel;
import io.grpc.LoadBalancer.SubchannelPicker;
import io.grpc.LoadBalancer.SubchannelStateListener;
import io.grpc.LoadBalancerProvider;
import io.grpc.LoadBalancerRegistry;
import io.grpc.Status;
import io.grpc.SynchronizationContext;
import io.grpc.internal.FakeClock;
import io.grpc.internal.FakeClock.ScheduledTask;
import io.grpc.internal.ServiceConfigUtil.PolicySelection;
import io.grpc.internal.TestUtils.StandardLoadBalancerProvider;
import io.grpc.util.OutlierDetectionLoadBalancer.OutlierDetectionLoadBalancerConfig;
import io.grpc.util.OutlierDetectionLoadBalancer.OutlierDetectionLoadBalancerConfig.SuccessRateEjection;
import io.grpc.util.RoundRobinLoadBalancer.ReadyPicker;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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

/**
 * Unit tests for {@link OutlierDetectionLoadBalancer}.
 */
@RunWith(JUnit4.class)
public class OutlierDetectionLoadBalancerTest {

  @Rule
  public final MockitoRule mockitoRule = MockitoJUnit.rule();

  @Mock
  private Helper helper;
  @Mock
  private LoadBalancer mockChildLb;
  @Mock
  private Helper mockHelper;
  @Mock
  private SocketAddress mockSocketAddress;

  @Captor
  private ArgumentCaptor<ConnectivityState> connectivityStateCaptor;
  @Captor
  private ArgumentCaptor<SubchannelPicker> errorPickerCaptor;
  @Captor
  private ArgumentCaptor<SubchannelPicker> pickerCaptor;
  @Captor
  private ArgumentCaptor<ConnectivityState> stateCaptor;
  @Captor
  private ArgumentCaptor<CreateSubchannelArgs> createArgsCaptor;

  private final LoadBalancerProvider mockChildLbProvider = new StandardLoadBalancerProvider(
      "foo_policy") {
    @Override
    public LoadBalancer newLoadBalancer(Helper helper) {
      return mockChildLb;
    }
  };
  private final LoadBalancerProvider roundRobinLbProvider = new StandardLoadBalancerProvider(
      "round_robin") {
    @Override
    public LoadBalancer newLoadBalancer(Helper helper) {
      return roundRobinLoadBalancer;
    }
  };
  private RoundRobinLoadBalancer roundRobinLoadBalancer;

  private final FakeClock fakeClock = new FakeClock();
  private final SynchronizationContext syncContext = new SynchronizationContext(
      new Thread.UncaughtExceptionHandler() {
        @Override
        public void uncaughtException(Thread t, Throwable e) {
          throw new AssertionError(e);
        }
      });
  private LoadBalancerRegistry lbRegistry = new LoadBalancerRegistry();
  private OutlierDetectionLoadBalancer loadBalancer;

  private final List<EquivalentAddressGroup> servers = Lists.newArrayList();
  private final Map<List<EquivalentAddressGroup>, Subchannel> subchannels = Maps.newLinkedHashMap();
  private final Map<Subchannel, SubchannelStateListener> subchannelStateListeners
      = Maps.newLinkedHashMap();

  @Before
  public void setUp() {
    for (int i = 0; i < 3; i++) {
      SocketAddress addr = new FakeSocketAddress("server" + i);
      EquivalentAddressGroup eag = new EquivalentAddressGroup(addr);
      servers.add(eag);
      Subchannel sc = mock(Subchannel.class);
      subchannels.put(Arrays.asList(eag), sc);
    }

    when(mockHelper.getSynchronizationContext()).thenReturn(syncContext);
    when(mockHelper.getScheduledExecutorService()).thenReturn(
        fakeClock.getScheduledExecutorService());
    when(mockHelper.createSubchannel(any(CreateSubchannelArgs.class)))
        .then(new Answer<Subchannel>() {
          @Override
          public Subchannel answer(InvocationOnMock invocation) throws Throwable {
            CreateSubchannelArgs args = (CreateSubchannelArgs) invocation.getArguments()[0];
            final Subchannel subchannel = subchannels.get(args.getAddresses());
            when(subchannel.getAllAddresses()).thenReturn(args.getAddresses());
            when(subchannel.getAttributes()).thenReturn(args.getAttributes());
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

    roundRobinLoadBalancer = new RoundRobinLoadBalancer(mockHelper);
    //lbRegistry.register(mockChildLbProvider);
    lbRegistry.register(roundRobinLbProvider);

    loadBalancer = new OutlierDetectionLoadBalancer(mockHelper, fakeClock.getTimeProvider());
  }

  @Test
  public void handleNameResolutionError_noChildLb() {
    loadBalancer.handleNameResolutionError(Status.DEADLINE_EXCEEDED);

    verify(mockHelper).updateBalancingState(connectivityStateCaptor.capture(),
        errorPickerCaptor.capture());
    assertThat(connectivityStateCaptor.getValue()).isEqualTo(ConnectivityState.TRANSIENT_FAILURE);
  }

  @Test
  public void handleNameResolutionError_withChildLb() {
    loadBalancer.handleResolvedAddresses(buildResolvedAddress(defaultSuccessRateConfig(mockChildLbProvider),
        new EquivalentAddressGroup(mockSocketAddress)));
    loadBalancer.handleNameResolutionError(Status.DEADLINE_EXCEEDED);

    verify(mockChildLb).handleNameResolutionError(Status.DEADLINE_EXCEEDED);
  }

  /** {@code shutdown()} is simply delegated. */
  @Test
  public void shutdown() {
    loadBalancer.handleResolvedAddresses(buildResolvedAddress(defaultSuccessRateConfig(mockChildLbProvider),
        new EquivalentAddressGroup(mockSocketAddress)));
    loadBalancer.shutdown();
    verify(mockChildLb).shutdown();
  }

  /** Base case for accepting new resolved addresses. */
  @Test
  public void handleResolvedAddresses() {
    OutlierDetectionLoadBalancerConfig config = defaultSuccessRateConfig(mockChildLbProvider);
    ResolvedAddresses resolvedAddresses = buildResolvedAddress(config,
        new EquivalentAddressGroup(mockSocketAddress));

    loadBalancer.handleResolvedAddresses(resolvedAddresses);

    // Handling of resolved addresses is delegated
    verify(mockChildLb).handleResolvedAddresses(resolvedAddresses);

    // There is a single pending task to run the outlier detection algorithm
    assertThat(fakeClock.getPendingTasks()).hasSize(1);

    // The task is scheduled to run after a delay set in the config.
    ScheduledTask task = fakeClock.getPendingTasks().iterator().next();
    assertThat(task.getDelay(TimeUnit.SECONDS)).isEqualTo(config.intervalSecs);
  }

  /** Outlier detection first enabled, then removed. */
  @Test
  public void handleResolvedAddresses_outlierDetectionDisabled() {
    OutlierDetectionLoadBalancerConfig config = defaultSuccessRateConfig(mockChildLbProvider);
    ResolvedAddresses resolvedAddresses = buildResolvedAddress(config,
        new EquivalentAddressGroup(mockSocketAddress));

    loadBalancer.handleResolvedAddresses(resolvedAddresses);

    fakeClock.forwardTime(15, TimeUnit.SECONDS);

    // There is a single pending task to run the outlier detection algorithm
    assertThat(fakeClock.getPendingTasks()).hasSize(1);

    loadBalancer.handleResolvedAddresses(
        buildResolvedAddress(disabledConfig(), new EquivalentAddressGroup(mockSocketAddress)));

    // Pending task should be gone since OD is disabled.
    assertThat(fakeClock.getPendingTasks()).isEmpty();

  }

  /** Tests different scenarios when the timer interval in the config changes. */
  @Test
  public void handleResolvedAddresses_intervalUpdate() {
    OutlierDetectionLoadBalancerConfig config = customIntervalConfig(null);
    ResolvedAddresses resolvedAddresses = buildResolvedAddress(config,
        new EquivalentAddressGroup(mockSocketAddress));

    loadBalancer.handleResolvedAddresses(resolvedAddresses);

    // Config update has doubled the interval
    config = customIntervalConfig(config.intervalSecs * 2);

    loadBalancer.handleResolvedAddresses(
        buildResolvedAddress(config,
            new EquivalentAddressGroup(mockSocketAddress)));

    // If the timer has not run yet the task is just rescheduled to run after the new delay.
    assertThat(fakeClock.getPendingTasks()).hasSize(1);
    ScheduledTask task = fakeClock.getPendingTasks().iterator().next();
    assertThat(task.getDelay(TimeUnit.SECONDS)).isEqualTo(config.intervalSecs);
    assertThat(task.dueTimeNanos).isEqualTo(TimeUnit.SECONDS.toNanos(config.intervalSecs));

    // The new interval time has passed. The next task due time should have been pushed back another
    // interval.
    fakeClock.forwardTime(config.intervalSecs + 1, TimeUnit.SECONDS);
    assertThat(fakeClock.getPendingTasks()).hasSize(1);
    task = fakeClock.getPendingTasks().iterator().next();
    assertThat(task.dueTimeNanos).isEqualTo(
        TimeUnit.SECONDS.toNanos(config.intervalSecs + config.intervalSecs + 1));

    // Some time passes and a second update comes down, but now the timer has had a chance to run,
    // the new delay to timer start should consider when the timer last ran and if the interval is
    // not changing in the config, the next task due time should remain unchanged.
    fakeClock.forwardTime(4, TimeUnit.SECONDS);
    task = fakeClock.getPendingTasks().iterator().next();
    loadBalancer.handleResolvedAddresses(
        buildResolvedAddress(config,
            new EquivalentAddressGroup(mockSocketAddress)));
    assertThat(task.dueTimeNanos).isEqualTo(
        TimeUnit.SECONDS.toNanos(config.intervalSecs + config.intervalSecs + 1));
  }

  // @Test
  // public void pickAfterResolved() throws Exception {
  //   final Subchannel readySubchannel = subchannels.values().iterator().next();
  //
  //   loadBalancer.handleResolvedAddresses(
  //       buildResolvedAddress(defaultSuccessRateConfig(roundRobinLbProvider), servers));
  //
  //   deliverSubchannelState(readySubchannel, ConnectivityStateInfo.forNonError(READY));
  //
  //   verify(mockHelper, times(3)).createSubchannel(createArgsCaptor.capture());
  //
  //   List<List<EquivalentAddressGroup>> capturedAddrs = new ArrayList<>();
  //   for (CreateSubchannelArgs arg : createArgsCaptor.getAllValues()) {
  //     capturedAddrs.add(arg.getAddresses());
  //   }
  //
  //   assertThat(capturedAddrs).containsAtLeastElementsIn(subchannels.keySet());
  //   for (Subchannel subchannel : subchannels.values()) {
  //     verify(subchannel).requestConnection();
  //     verify(subchannel, never()).shutdown();
  //   }
  //   //
  //   // verify(mockHelper, times(2))
  //   //     .updateBalancingState(stateCaptor.capture(), pickerCaptor.capture());
  //   //
  //   // assertEquals(CONNECTING, stateCaptor.getAllValues().get(0));
  //   // assertEquals(READY, stateCaptor.getAllValues().get(1));
  //   // assertThat(getList(pickerCaptor.getValue())).containsExactly(readySubchannel);
  //   //
  //   // verifyNoMoreInteractions(mockHelper);
  // }


  private static class FakeSocketAddress extends SocketAddress {
    final String name;

    FakeSocketAddress(String name) {
      this.name = name;
    }

    @Override
    public String toString() {
      return "FakeSocketAddress-" + name;
    }
  }

  private OutlierDetectionLoadBalancerConfig defaultSuccessRateConfig(
      LoadBalancerProvider childLbProvider) {
    return new OutlierDetectionLoadBalancerConfig(new PolicySelection(childLbProvider, null),
        null, null, null, null,
        new SuccessRateEjection(null, null, null, null), null);
  }

  private OutlierDetectionLoadBalancerConfig customIntervalConfig(Long intervalSecs) {
    return new OutlierDetectionLoadBalancerConfig(new PolicySelection(mockChildLbProvider, null),
        intervalSecs != null ? intervalSecs : null, null, null, null,
        new SuccessRateEjection(null, null, null, null), null);
  }

  private OutlierDetectionLoadBalancerConfig disabledConfig() {
    return new OutlierDetectionLoadBalancerConfig(new PolicySelection(mockChildLbProvider, null),
        null, null, null, null,
        null, null);
  }

  private ResolvedAddresses buildResolvedAddress(OutlierDetectionLoadBalancerConfig config,
      EquivalentAddressGroup... servers) {
    return ResolvedAddresses.newBuilder().setAddresses(ImmutableList.copyOf(servers))
        .setLoadBalancingPolicyConfig(config).build();
  }

  private ResolvedAddresses buildResolvedAddress(OutlierDetectionLoadBalancerConfig config,
      List<EquivalentAddressGroup> servers) {
    return ResolvedAddresses.newBuilder().setAddresses(ImmutableList.copyOf(servers))
        .setLoadBalancingPolicyConfig(config).build();
  }

  private void deliverSubchannelState(Subchannel subchannel, ConnectivityStateInfo newState) {
    subchannelStateListeners.get(subchannel).onSubchannelState(newState);
  }

  private static List<Subchannel> getList(SubchannelPicker picker) {
    return picker instanceof ReadyPicker ? ((ReadyPicker) picker).getList() :
        Collections.<Subchannel>emptyList();
  }
}
