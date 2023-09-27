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
import static com.google.common.truth.Truth.assertWithMessage;
import static io.grpc.ConnectivityState.READY;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import io.grpc.ChannelLogger;
import io.grpc.ClientStreamTracer;
import io.grpc.ConnectivityState;
import io.grpc.ConnectivityStateInfo;
import io.grpc.EquivalentAddressGroup;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancer.CreateSubchannelArgs;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancer.PickResult;
import io.grpc.LoadBalancer.PickSubchannelArgs;
import io.grpc.LoadBalancer.ResolvedAddresses;
import io.grpc.LoadBalancer.Subchannel;
import io.grpc.LoadBalancer.SubchannelPicker;
import io.grpc.LoadBalancer.SubchannelStateListener;
import io.grpc.LoadBalancerProvider;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.SynchronizationContext;
import io.grpc.internal.FakeClock;
import io.grpc.internal.FakeClock.ScheduledTask;
import io.grpc.internal.ServiceConfigUtil.PolicySelection;
import io.grpc.internal.TestUtils.StandardLoadBalancerProvider;
import io.grpc.util.OutlierDetectionLoadBalancer.AddressTracker;
import io.grpc.util.OutlierDetectionLoadBalancer.OutlierDetectionLoadBalancerConfig;
import io.grpc.util.OutlierDetectionLoadBalancer.OutlierDetectionLoadBalancerConfig.FailurePercentageEjection;
import io.grpc.util.OutlierDetectionLoadBalancer.OutlierDetectionLoadBalancerConfig.SuccessRateEjection;
import io.grpc.util.OutlierDetectionLoadBalancer.OutlierDetectionSubchannel;
import io.grpc.util.OutlierDetectionLoadBalancer.SuccessRateOutlierEjectionAlgorithm;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
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
  private LoadBalancer mockChildLb;
  @Mock
  private Helper mockHelper;
  @Mock
  private SocketAddress mockSocketAddress;
  @Mock
  private ClientStreamTracer.Factory mockStreamTracerFactory;
  @Mock
  private ClientStreamTracer mockStreamTracer;

  @Captor
  private ArgumentCaptor<ConnectivityState> connectivityStateCaptor;
  @Captor
  private ArgumentCaptor<SubchannelPicker> errorPickerCaptor;
  @Captor
  private ArgumentCaptor<SubchannelPicker> pickerCaptor;
  @Captor
  private ArgumentCaptor<ConnectivityState> stateCaptor;

  private final LoadBalancerProvider mockChildLbProvider = new StandardLoadBalancerProvider(
      "foo_policy") {
    @Override
    public LoadBalancer newLoadBalancer(Helper helper) {
      return mockChildLb;
    }
  };
  private final LoadBalancerProvider fakeLbProvider = new StandardLoadBalancerProvider(
      "fake_policy") {
    @Override
    public LoadBalancer newLoadBalancer(Helper helper) {
      return new FakeLoadBalancer(helper);
    }
  };
  private final LoadBalancerProvider roundRobinLbProvider = new StandardLoadBalancerProvider(
      "round_robin") {
    @Override
    public LoadBalancer newLoadBalancer(Helper helper) {
      return new RoundRobinLoadBalancer(helper);
    }
  };

  private final FakeClock fakeClock = new FakeClock();
  private final SynchronizationContext syncContext = new SynchronizationContext(
      new Thread.UncaughtExceptionHandler() {
        @Override
        public void uncaughtException(Thread t, Throwable e) {
          throw new AssertionError(e);
        }
      });
  private OutlierDetectionLoadBalancer loadBalancer;

  private final List<EquivalentAddressGroup> servers = Lists.newArrayList();
  private final Map<List<EquivalentAddressGroup>, Subchannel> subchannels = Maps.newLinkedHashMap();
  private final Map<Subchannel, SubchannelStateListener> subchannelStateListeners
      = Maps.newLinkedHashMap();

  private Subchannel subchannel1;
  private Subchannel subchannel2;
  private Subchannel subchannel3;
  private Subchannel subchannel4;
  private Subchannel subchannel5;

  @Before
  public void setUp() {
    for (int i = 0; i < 5; i++) {
      SocketAddress addr = new FakeSocketAddress("server" + i);
      EquivalentAddressGroup eag = new EquivalentAddressGroup(addr);
      servers.add(eag);
      Subchannel sc = mock(Subchannel.class);
      subchannels.put(Arrays.asList(eag), sc);
    }

    Iterator<Subchannel> subchannelIterator = subchannels.values().iterator();
    subchannel1 = subchannelIterator.next();
    subchannel2 = subchannelIterator.next();
    subchannel3 = subchannelIterator.next();
    subchannel4 = subchannelIterator.next();
    subchannel5 = subchannelIterator.next();

    ChannelLogger channelLogger = mock(ChannelLogger.class);

    when(mockHelper.getChannelLogger()).thenReturn(channelLogger);
    when(mockHelper.getSynchronizationContext()).thenReturn(syncContext);
    when(mockHelper.getScheduledExecutorService()).thenReturn(
        fakeClock.getScheduledExecutorService());
    when(mockHelper.createSubchannel(any(CreateSubchannelArgs.class))).then(
        new Answer<Subchannel>() {
          @Override
          public Subchannel answer(InvocationOnMock invocation) throws Throwable {
            CreateSubchannelArgs args = (CreateSubchannelArgs) invocation.getArguments()[0];
            final Subchannel subchannel = subchannels.get(args.getAddresses());
            when(subchannel.getChannelLogger()).thenReturn(channelLogger);
            when(subchannel.getAllAddresses()).thenReturn(args.getAddresses());
            when(subchannel.getAttributes()).thenReturn(args.getAttributes());
            doAnswer(new Answer<Void>() {
              @Override
              public Void answer(InvocationOnMock invocation) throws Throwable {
                subchannelStateListeners.put(subchannel,
                    (SubchannelStateListener) invocation.getArguments()[0]);
                return null;
              }
            }).when(subchannel).start(any(SubchannelStateListener.class));
            return subchannel;
          }
        });

    when(mockStreamTracerFactory.newClientStreamTracer(any(),
        any())).thenReturn(mockStreamTracer);

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
    loadBalancer.acceptResolvedAddresses(buildResolvedAddress(
        new OutlierDetectionLoadBalancerConfig.Builder()
            .setSuccessRateEjection(new SuccessRateEjection.Builder().build())
            .setChildPolicy(new PolicySelection(mockChildLbProvider, null)).build(),
        new EquivalentAddressGroup(mockSocketAddress)));
    loadBalancer.handleNameResolutionError(Status.DEADLINE_EXCEEDED);

    verify(mockChildLb).handleNameResolutionError(Status.DEADLINE_EXCEEDED);
  }

  /**
   * {@code shutdown()} is simply delegated.
   */
  @Test
  public void shutdown() {
    loadBalancer.acceptResolvedAddresses(buildResolvedAddress(
        new OutlierDetectionLoadBalancerConfig.Builder()
            .setSuccessRateEjection(new SuccessRateEjection.Builder().build())
            .setChildPolicy(new PolicySelection(mockChildLbProvider, null)).build(),
        new EquivalentAddressGroup(mockSocketAddress)));
    loadBalancer.shutdown();
    verify(mockChildLb).shutdown();
  }

  /**
   * Base case for accepting new resolved addresses.
   */
  @Test
  public void acceptResolvedAddresses() {
    OutlierDetectionLoadBalancerConfig config = new OutlierDetectionLoadBalancerConfig.Builder()
        .setSuccessRateEjection(new SuccessRateEjection.Builder().build())
        .setChildPolicy(new PolicySelection(mockChildLbProvider, null)).build();
    ResolvedAddresses resolvedAddresses = buildResolvedAddress(config,
        new EquivalentAddressGroup(mockSocketAddress));

    loadBalancer.acceptResolvedAddresses(resolvedAddresses);

    // Handling of resolved addresses is delegated
    verify(mockChildLb).handleResolvedAddresses(
        resolvedAddresses.toBuilder().setLoadBalancingPolicyConfig(config.childPolicy.getConfig())
            .build());

    // There is a single pending task to run the outlier detection algorithm
    assertThat(fakeClock.getPendingTasks()).hasSize(1);

    // The task is scheduled to run after a delay set in the config.
    ScheduledTask task = fakeClock.getPendingTasks().iterator().next();
    assertThat(task.getDelay(TimeUnit.NANOSECONDS)).isEqualTo(config.intervalNanos);
  }

  /**
   * Outlier detection first enabled, then removed.
   */
  @Test
  public void acceptResolvedAddresses_outlierDetectionDisabled() {
    OutlierDetectionLoadBalancerConfig config = new OutlierDetectionLoadBalancerConfig.Builder()
        .setSuccessRateEjection(new SuccessRateEjection.Builder().build())
        .setChildPolicy(new PolicySelection(mockChildLbProvider, null)).build();
    ResolvedAddresses resolvedAddresses = buildResolvedAddress(config,
        new EquivalentAddressGroup(mockSocketAddress));

    loadBalancer.acceptResolvedAddresses(resolvedAddresses);

    fakeClock.forwardTime(15, TimeUnit.SECONDS);

    // There is a single pending task to run the outlier detection algorithm
    assertThat(fakeClock.getPendingTasks()).hasSize(1);

    config = new OutlierDetectionLoadBalancerConfig.Builder().setChildPolicy(
        new PolicySelection(mockChildLbProvider, null)).build();
    loadBalancer.acceptResolvedAddresses(
        buildResolvedAddress(config, new EquivalentAddressGroup(mockSocketAddress)));

    // Pending task should be gone since OD is disabled.
    assertThat(fakeClock.getPendingTasks()).isEmpty();

  }

  /**
   * Tests different scenarios when the timer interval in the config changes.
   */
  @Test
  public void acceptResolvedAddresses_intervalUpdate() {
    OutlierDetectionLoadBalancerConfig config = new OutlierDetectionLoadBalancerConfig.Builder()
        .setSuccessRateEjection(new SuccessRateEjection.Builder().build())
        .setChildPolicy(new PolicySelection(mockChildLbProvider, null)).build();
    ResolvedAddresses resolvedAddresses = buildResolvedAddress(config,
        new EquivalentAddressGroup(mockSocketAddress));

    loadBalancer.acceptResolvedAddresses(resolvedAddresses);

    // Config update has doubled the interval
    config = new OutlierDetectionLoadBalancerConfig.Builder()
        .setIntervalNanos(config.intervalNanos * 2)
        .setSuccessRateEjection(new SuccessRateEjection.Builder().build())
        .setChildPolicy(new PolicySelection(mockChildLbProvider, null)).build();

    loadBalancer.acceptResolvedAddresses(
        buildResolvedAddress(config, new EquivalentAddressGroup(mockSocketAddress)));

    // If the timer has not run yet the task is just rescheduled to run after the new delay.
    assertThat(fakeClock.getPendingTasks()).hasSize(1);
    ScheduledTask task = fakeClock.getPendingTasks().iterator().next();
    assertThat(task.getDelay(TimeUnit.NANOSECONDS)).isEqualTo(config.intervalNanos);
    assertThat(task.dueTimeNanos).isEqualTo(config.intervalNanos);

    // The new interval time has passed. The next task due time should have been pushed back another
    // interval.
    forwardTime(config);
    assertThat(fakeClock.getPendingTasks()).hasSize(1);
    task = fakeClock.getPendingTasks().iterator().next();
    assertThat(task.dueTimeNanos).isEqualTo(config.intervalNanos + config.intervalNanos + 1);

    // Some time passes and a second update comes down, but now the timer has had a chance to run,
    // the new delay to timer start should consider when the timer last ran and if the interval is
    // not changing in the config, the next task due time should remain unchanged.
    fakeClock.forwardTime(4, TimeUnit.SECONDS);
    task = fakeClock.getPendingTasks().iterator().next();
    loadBalancer.acceptResolvedAddresses(
        buildResolvedAddress(config, new EquivalentAddressGroup(mockSocketAddress)));
    assertThat(task.dueTimeNanos).isEqualTo(config.intervalNanos + config.intervalNanos + 1);
  }

  /**
   * Confirm basic picking works by delegating to round_robin.
   */
  @Test
  public void delegatePick() throws Exception {
    OutlierDetectionLoadBalancerConfig config = new OutlierDetectionLoadBalancerConfig.Builder()
        .setSuccessRateEjection(new SuccessRateEjection.Builder().build())
        .setChildPolicy(new PolicySelection(roundRobinLbProvider, null)).build();

    loadBalancer.acceptResolvedAddresses(buildResolvedAddress(config, servers.get(0)));

    // Make one of the subchannels READY.
    final Subchannel readySubchannel = subchannels.values().iterator().next();
    deliverSubchannelState(readySubchannel, ConnectivityStateInfo.forNonError(READY));

    verify(mockHelper, times(3)).updateBalancingState(stateCaptor.capture(),
        pickerCaptor.capture());

    // Make sure that we can pick the single READY subchannel.
    SubchannelPicker picker = pickerCaptor.getAllValues().get(2);
    PickResult pickResult = picker.pickSubchannel(mock(PickSubchannelArgs.class));
    assertThat(((OutlierDetectionSubchannel) pickResult.getSubchannel()).delegate()).isEqualTo(
        readySubchannel);
  }

  /**
   * Any ClientStreamTracer.Factory set by the delegate picker should still get used.
   */
  @Test
  public void delegatePickTracerFactoryPreserved() throws Exception {
    OutlierDetectionLoadBalancerConfig config = new OutlierDetectionLoadBalancerConfig.Builder()
        .setSuccessRateEjection(new SuccessRateEjection.Builder().build())
        .setChildPolicy(new PolicySelection(fakeLbProvider, null)).build();

    loadBalancer.acceptResolvedAddresses(buildResolvedAddress(config, servers.get(0)));

    // Make one of the subchannels READY.
    final Subchannel readySubchannel = subchannels.values().iterator().next();
    deliverSubchannelState(readySubchannel, ConnectivityStateInfo.forNonError(READY));

    verify(mockHelper, times(2)).updateBalancingState(stateCaptor.capture(),
        pickerCaptor.capture());

    // Make sure that we can pick the single READY subchannel.
    SubchannelPicker picker = pickerCaptor.getAllValues().get(1);
    PickResult pickResult = picker.pickSubchannel(mock(PickSubchannelArgs.class));

    // Calls to a stream tracer created with the factory in the result should make it to a stream
    // tracer the underlying LB/picker is using.
    ClientStreamTracer clientStreamTracer = pickResult.getStreamTracerFactory()
        .newClientStreamTracer(ClientStreamTracer.StreamInfo.newBuilder().build(), new Metadata());
    clientStreamTracer.inboundHeaders();
    // The underlying fake LB provider is configured with a factory that returns a mock stream
    // tracer.
    verify(mockStreamTracer).inboundHeaders();
  }

  /**
   * Assure the tracer works even when the underlying LB does not have a tracer to delegate to.
   */
  @Test
  public void delegatePickTracerFactoryNotSet() throws Exception {
    // We set the mock factory to null to indicate that the delegate does not have its own tracer.
    mockStreamTracerFactory = null;

    OutlierDetectionLoadBalancerConfig config = new OutlierDetectionLoadBalancerConfig.Builder()
        .setSuccessRateEjection(new SuccessRateEjection.Builder().build())
        .setChildPolicy(new PolicySelection(fakeLbProvider, null)).build();

    loadBalancer.acceptResolvedAddresses(buildResolvedAddress(config, servers.get(0)));

    // Make one of the subchannels READY.
    final Subchannel readySubchannel = subchannels.values().iterator().next();
    deliverSubchannelState(readySubchannel, ConnectivityStateInfo.forNonError(READY));

    verify(mockHelper, times(2)).updateBalancingState(stateCaptor.capture(),
        pickerCaptor.capture());

    // Make sure that we can pick the single READY subchannel.
    SubchannelPicker picker = pickerCaptor.getAllValues().get(1);
    PickResult pickResult = picker.pickSubchannel(mock(PickSubchannelArgs.class));

    // With no delegate tracers factory a call to the OD tracer should still work
    ClientStreamTracer clientStreamTracer = pickResult.getStreamTracerFactory()
        .newClientStreamTracer(ClientStreamTracer.StreamInfo.newBuilder().build(), new Metadata());
    clientStreamTracer.inboundHeaders();

    // Sanity check to make sure the delegate tracer does not get called.
    verifyNoInteractions(mockStreamTracer);
  }

  /**
   * The success rate algorithm leaves a healthy set of addresses alone.
   */
  @Test
  public void successRateNoOutliers() {
    OutlierDetectionLoadBalancerConfig config = new OutlierDetectionLoadBalancerConfig.Builder()
        .setMaxEjectionPercent(50)
        .setSuccessRateEjection(
            new SuccessRateEjection.Builder().setMinimumHosts(3).setRequestVolume(10).build())
        .setChildPolicy(new PolicySelection(roundRobinLbProvider, null)).build();

    loadBalancer.acceptResolvedAddresses(buildResolvedAddress(config, servers));

    generateLoad(ImmutableMap.of(), 7);

    // Move forward in time to a point where the detection timer has fired.
    forwardTime(config);

    // No outliers, no ejections.
    assertEjectedSubchannels(ImmutableSet.of());
  }

  /**
   * The success rate algorithm ejects the outlier.
   */
  @Test
  public void successRateOneOutlier() {
    OutlierDetectionLoadBalancerConfig config = new OutlierDetectionLoadBalancerConfig.Builder()
        .setMaxEjectionPercent(50)
        .setSuccessRateEjection(
            new SuccessRateEjection.Builder()
                .setMinimumHosts(3)
                .setRequestVolume(10).build())
        .setChildPolicy(new PolicySelection(roundRobinLbProvider, null)).build();

    loadBalancer.acceptResolvedAddresses(buildResolvedAddress(config, servers));

    generateLoad(ImmutableMap.of(subchannel1, Status.DEADLINE_EXCEEDED), 7);

    // Move forward in time to a point where the detection timer has fired.
    forwardTime(config);

    // The one subchannel that was returning errors should be ejected.
    assertEjectedSubchannels(ImmutableSet.of(servers.get(0).getAddresses().get(0)));
  }

  /**
   * The success rate algorithm ejects the outlier, but then the config changes so that similar
   * behavior no longer gets ejected.
   */
  @Test
  public void successRateOneOutlier_configChange() {
    OutlierDetectionLoadBalancerConfig config = new OutlierDetectionLoadBalancerConfig.Builder()
        .setMaxEjectionPercent(50)
        .setSuccessRateEjection(
            new SuccessRateEjection.Builder()
                .setMinimumHosts(3)
                .setRequestVolume(10).build())
        .setChildPolicy(new PolicySelection(roundRobinLbProvider, null)).build();

    loadBalancer.acceptResolvedAddresses(buildResolvedAddress(config, servers));

    generateLoad(ImmutableMap.of(subchannel1, Status.DEADLINE_EXCEEDED), 7);

    // Move forward in time to a point where the detection timer has fired.
    forwardTime(config);

    // The one subchannel that was returning errors should be ejected.
    assertEjectedSubchannels(ImmutableSet.of(servers.get(0).getAddresses().get(0)));

    // New config sets enforcement percentage to 0.
    config = new OutlierDetectionLoadBalancerConfig.Builder()
        .setMaxEjectionPercent(50)
        .setSuccessRateEjection(
            new SuccessRateEjection.Builder()
                .setMinimumHosts(3)
                .setRequestVolume(10)
                .setEnforcementPercentage(0).build())
        .setChildPolicy(new PolicySelection(roundRobinLbProvider, null)).build();

    loadBalancer.acceptResolvedAddresses(buildResolvedAddress(config, servers));

    generateLoad(ImmutableMap.of(subchannel2, Status.DEADLINE_EXCEEDED), 8);

    // Move forward in time to a point where the detection timer has fired.
    forwardTime(config);

    // Since we brought enforcement percentage to 0, no additional ejection should have happened.
    assertEjectedSubchannels(ImmutableSet.of(servers.get(0).getAddresses().get(0)));
  }

  /**
   * The success rate algorithm ejects the outlier but after some time it should get unejected
   * if it stops being an outlier..
   */
  @Test
  public void successRateOneOutlier_unejected() {
    OutlierDetectionLoadBalancerConfig config = new OutlierDetectionLoadBalancerConfig.Builder()
        .setMaxEjectionPercent(50)
        .setSuccessRateEjection(
            new SuccessRateEjection.Builder()
                .setMinimumHosts(3)
                .setRequestVolume(10).build())
        .setChildPolicy(new PolicySelection(roundRobinLbProvider, null)).build();

    loadBalancer.acceptResolvedAddresses(buildResolvedAddress(config, servers));

    generateLoad(ImmutableMap.of(subchannel1, Status.DEADLINE_EXCEEDED), 7);

    // Move forward in time to a point where the detection timer has fired.
    fakeClock.forwardTime(config.intervalNanos + 1, TimeUnit.NANOSECONDS);

    // The one subchannel that was returning errors should be ejected.
    assertEjectedSubchannels(ImmutableSet.of(servers.get(0).getAddresses().get(0)));

    // Now we produce more load, but the subchannel start working and is no longer an outlier.
    generateLoad(ImmutableMap.of(), 8);

    // Move forward in time to a point where the detection timer has fired.
    fakeClock.forwardTime(config.maxEjectionTimeNanos + 1, TimeUnit.NANOSECONDS);

    // No subchannels should remain ejected.
    assertEjectedSubchannels(ImmutableSet.of());
  }

  /**
   * The success rate algorithm ignores addresses without enough volume.
   */
  @Test
  public void successRateOneOutlier_notEnoughVolume() {
    OutlierDetectionLoadBalancerConfig config = new OutlierDetectionLoadBalancerConfig.Builder()
        .setMaxEjectionPercent(50)
        .setSuccessRateEjection(
            new SuccessRateEjection.Builder()
                .setMinimumHosts(3)
                .setRequestVolume(20).build())
        .setChildPolicy(new PolicySelection(roundRobinLbProvider, null)).build();

    loadBalancer.acceptResolvedAddresses(buildResolvedAddress(config, servers));

    // We produce an outlier, but don't give it enough calls to reach the minimum volume.
    generateLoad(
        ImmutableMap.of(subchannel1, Status.DEADLINE_EXCEEDED),
        ImmutableMap.of(subchannel1, 19), 7);

    // Move forward in time to a point where the detection timer has fired.
    forwardTime(config);

    // The address should not have been ejected.
    assertEjectedSubchannels(ImmutableSet.of());
  }

  /**
   * The success rate algorithm does not apply if we don't have enough addresses that have the
   * required volume.
   */
  @Test
  public void successRateOneOutlier_notEnoughAddressesWithVolume() {
    OutlierDetectionLoadBalancerConfig config = new OutlierDetectionLoadBalancerConfig.Builder()
        .setMaxEjectionPercent(50)
        .setSuccessRateEjection(
            new SuccessRateEjection.Builder()
                .setMinimumHosts(5)
                .setRequestVolume(20).build())
        .setChildPolicy(new PolicySelection(roundRobinLbProvider, null)).build();

    loadBalancer.acceptResolvedAddresses(buildResolvedAddress(config, servers));

    generateLoad(
        ImmutableMap.of(subchannel1, Status.DEADLINE_EXCEEDED),
        // subchannel2 has only 19 calls which results in success rate not triggering.
        ImmutableMap.of(subchannel2, 19),
        7);

    // Move forward in time to a point where the detection timer has fired.
    forwardTime(config);

    // No subchannels should have been ejected.
    assertEjectedSubchannels(ImmutableSet.of());
  }

  /**
   * The enforcementPercentage configuration should be honored.
   */
  @Test
  public void successRateOneOutlier_enforcementPercentage() {
    OutlierDetectionLoadBalancerConfig config = new OutlierDetectionLoadBalancerConfig.Builder()
        .setMaxEjectionPercent(50)
        .setSuccessRateEjection(
            new SuccessRateEjection.Builder()
                .setMinimumHosts(3)
                .setRequestVolume(10)
                .setEnforcementPercentage(0)
                .build())
        .setChildPolicy(new PolicySelection(roundRobinLbProvider, null)).build();

    loadBalancer.acceptResolvedAddresses(buildResolvedAddress(config, servers));

    generateLoad(ImmutableMap.of(subchannel1, Status.DEADLINE_EXCEEDED), 7);

    // Move forward in time to a point where the detection timer has fired.
    forwardTime(config);

    // There is one outlier, but because enforcementPercentage is 0, nothing should be ejected.
    assertEjectedSubchannels(ImmutableSet.of());
  }

  /**
   * Two outliers get ejected.
   */
  @Test
  public void successRateTwoOutliers() {
    OutlierDetectionLoadBalancerConfig config = new OutlierDetectionLoadBalancerConfig.Builder()
        .setMaxEjectionPercent(50)
        .setSuccessRateEjection(
            new SuccessRateEjection.Builder()
                .setMinimumHosts(3)
                .setRequestVolume(10)
                .setStdevFactor(1).build())
        .setChildPolicy(new PolicySelection(roundRobinLbProvider, null)).build();

    loadBalancer.acceptResolvedAddresses(buildResolvedAddress(config, servers));

    generateLoad(ImmutableMap.of(
        subchannel1, Status.DEADLINE_EXCEEDED,
        subchannel2, Status.DEADLINE_EXCEEDED), 7);

    // Move forward in time to a point where the detection timer has fired.
    forwardTime(config);

    // The one subchannel that was returning errors should be ejected.
    assertEjectedSubchannels(ImmutableSet.of(servers.get(0).getAddresses().get(0),
        servers.get(1).getAddresses().get(0)));
  }

  /**
   * Three outliers, second one ejected even if ejecting it goes above the max ejection percentage,
   * as this matches Envoy behavior. The third one should not get ejected.
   */
  @Test
  public void successRateThreeOutliers_maxEjectionPercentage() {
    OutlierDetectionLoadBalancerConfig config = new OutlierDetectionLoadBalancerConfig.Builder()
        .setMaxEjectionPercent(30)
        .setSuccessRateEjection(
            new SuccessRateEjection.Builder()
                .setMinimumHosts(3)
                .setRequestVolume(10)
                .setStdevFactor(1).build())
        .setChildPolicy(new PolicySelection(roundRobinLbProvider, null)).build();

    loadBalancer.acceptResolvedAddresses(buildResolvedAddress(config, servers));

    generateLoad(ImmutableMap.of(
        subchannel1, Status.DEADLINE_EXCEEDED,
        subchannel2, Status.DEADLINE_EXCEEDED,
        subchannel3, Status.DEADLINE_EXCEEDED), 7);

    // Move forward in time to a point where the detection timer has fired.
    forwardTime(config);

    int totalEjected = 0;
    for (EquivalentAddressGroup addressGroup: servers) {
      totalEjected +=
          loadBalancer.trackerMap.get(addressGroup.getAddresses().get(0)).subchannelsEjected() ? 1
              : 0;
    }

    assertThat(totalEjected).isEqualTo(2);
  }


  /**
   * The success rate algorithm leaves a healthy set of addresses alone.
   */
  @Test
  public void failurePercentageNoOutliers() {
    OutlierDetectionLoadBalancerConfig config = new OutlierDetectionLoadBalancerConfig.Builder()
        .setMaxEjectionPercent(50)
        .setFailurePercentageEjection(
            new FailurePercentageEjection.Builder()
                .setMinimumHosts(3)
                .setRequestVolume(10).build())
        .setChildPolicy(new PolicySelection(roundRobinLbProvider, null)).build();

    loadBalancer.acceptResolvedAddresses(buildResolvedAddress(config, servers));

    // By default all calls will return OK.
    generateLoad(ImmutableMap.of(), 7);

    // Move forward in time to a point where the detection timer has fired.
    forwardTime(config);

    // No outliers, no ejections.
    assertEjectedSubchannels(ImmutableSet.of());
  }

  /**
   * The success rate algorithm ejects the outlier.
   */
  @Test
  public void failurePercentageOneOutlier() {
    OutlierDetectionLoadBalancerConfig config = new OutlierDetectionLoadBalancerConfig.Builder()
        .setMaxEjectionPercent(50)
        .setFailurePercentageEjection(
            new FailurePercentageEjection.Builder()
                .setMinimumHosts(3)
                .setRequestVolume(10).build())
        .setChildPolicy(new PolicySelection(roundRobinLbProvider, null)).build();

    loadBalancer.acceptResolvedAddresses(buildResolvedAddress(config, servers));

    generateLoad(ImmutableMap.of(subchannel1, Status.DEADLINE_EXCEEDED), 7);

    // Move forward in time to a point where the detection timer has fired.
    forwardTime(config);

    // The one subchannel that was returning errors should be ejected.
    assertEjectedSubchannels(ImmutableSet.of(servers.get(0).getAddresses().get(0)));
  }

  /**
   * The failure percentage algorithm ignores addresses without enough volume..
   */
  @Test
  public void failurePercentageOneOutlier_notEnoughVolume() {
    OutlierDetectionLoadBalancerConfig config = new OutlierDetectionLoadBalancerConfig.Builder()
        .setMaxEjectionPercent(50)
        .setFailurePercentageEjection(
            new FailurePercentageEjection.Builder()
                .setMinimumHosts(3)
                .setRequestVolume(100).build()) // We won't produce this much volume...
        .setChildPolicy(new PolicySelection(roundRobinLbProvider, null)).build();

    loadBalancer.acceptResolvedAddresses(buildResolvedAddress(config, servers));

    generateLoad(ImmutableMap.of(subchannel1, Status.DEADLINE_EXCEEDED), 7);

    // Move forward in time to a point where the detection timer has fired.
    forwardTime(config);

    // We should see no ejections.
    assertEjectedSubchannels(ImmutableSet.of());
  }

  /**
   * The failure percentage algorithm does not apply if we don't have enough addresses that have the
   * required volume.
   */
  @Test
  public void failurePercentageOneOutlier_notEnoughAddressesWithVolume() {
    OutlierDetectionLoadBalancerConfig config = new OutlierDetectionLoadBalancerConfig.Builder()
        .setMaxEjectionPercent(50)
        .setFailurePercentageEjection(
            new FailurePercentageEjection.Builder()
                .setMinimumHosts(5)
                .setRequestVolume(20).build())
        .setChildPolicy(new PolicySelection(roundRobinLbProvider, null)).build();

    loadBalancer.acceptResolvedAddresses(buildResolvedAddress(config, servers));

    generateLoad(
        ImmutableMap.of(subchannel1, Status.DEADLINE_EXCEEDED),
        // subchannel2 has only 19 calls which results in failure percentage not triggering.
        ImmutableMap.of(subchannel2, 19),
        7);

    // Move forward in time to a point where the detection timer has fired.
    forwardTime(config);

    // No subchannels should have been ejected.
    assertEjectedSubchannels(ImmutableSet.of());
  }

  /**
   * The enforcementPercentage configuration should be honored.
   */
  @Test
  public void failurePercentageOneOutlier_enforcementPercentage() {
    OutlierDetectionLoadBalancerConfig config = new OutlierDetectionLoadBalancerConfig.Builder()
        .setMaxEjectionPercent(50)
        .setFailurePercentageEjection(
            new FailurePercentageEjection.Builder()
                .setMinimumHosts(3)
                .setRequestVolume(10)
                .setEnforcementPercentage(0)
                .build())
        .setChildPolicy(new PolicySelection(roundRobinLbProvider, null)).build();

    loadBalancer.acceptResolvedAddresses(buildResolvedAddress(config, servers));

    generateLoad(ImmutableMap.of(subchannel1, Status.DEADLINE_EXCEEDED), 7);

    // Move forward in time to a point where the detection timer has fired.
    forwardTime(config);

    // There is one outlier, but because enforcementPercentage is 0, nothing should be ejected.
    assertEjectedSubchannels(ImmutableSet.of());
  }

  /** Success rate detects two outliers and error percentage three. */
  @Test
  public void successRateAndFailurePercentageThreeOutliers() {
    OutlierDetectionLoadBalancerConfig config = new OutlierDetectionLoadBalancerConfig.Builder()
        .setMaxEjectionPercent(100)
        .setSuccessRateEjection(
            new SuccessRateEjection.Builder()
                .setMinimumHosts(3)
                .setRequestVolume(10)
                .setStdevFactor(1).build())
        .setFailurePercentageEjection(
            new FailurePercentageEjection.Builder()
                .setThreshold(0)
                .setMinimumHosts(3)
                .setRequestVolume(1)
                .build())
        .setChildPolicy(new PolicySelection(roundRobinLbProvider, null)).build();

    loadBalancer.acceptResolvedAddresses(buildResolvedAddress(config, servers));

    // Three subchannels with problems, but one only has a single call that failed.
    // This is not enough for success rate to catch, but failure percentage is
    // configured with a 0 tolerance threshold.
    generateLoad(
        ImmutableMap.of(
            subchannel1, Status.DEADLINE_EXCEEDED,
            subchannel2, Status.DEADLINE_EXCEEDED,
            subchannel3, Status.DEADLINE_EXCEEDED),
        ImmutableMap.of(subchannel3, 1), 7);

    // Move forward in time to a point where the detection timer has fired.
    forwardTime(config);

    // Should see thee ejected, success rate cathes the first two, error percentage the
    // same two plus the subchannel with the single failure.
    assertEjectedSubchannels(ImmutableSet.of(
        servers.get(0).getAddresses().get(0),
        servers.get(1).getAddresses().get(0),
        servers.get(2).getAddresses().get(0)));
  }

  /**
   * When the address a subchannel is associated with changes it should get tracked under the new
   * address and its ejection state should match what the address has.
   */
  @Test
  public void subchannelUpdateAddress_singleReplaced() {
    OutlierDetectionLoadBalancerConfig config = new OutlierDetectionLoadBalancerConfig.Builder()
        .setMaxEjectionPercent(50)
        .setFailurePercentageEjection(
            new FailurePercentageEjection.Builder()
                .setMinimumHosts(3)
                .setRequestVolume(10).build())
        .setChildPolicy(new PolicySelection(roundRobinLbProvider, null)).build();

    loadBalancer.acceptResolvedAddresses(buildResolvedAddress(config, servers));

    generateLoad(ImmutableMap.of(subchannel1, Status.DEADLINE_EXCEEDED), 7);

    // Move forward in time to a point where the detection timer has fired.
    forwardTime(config);

    EquivalentAddressGroup oldAddressGroup = servers.get(0);
    AddressTracker oldAddressTracker = loadBalancer.trackerMap.get(
        oldAddressGroup.getAddresses().get(0));
    EquivalentAddressGroup newAddressGroup = servers.get(1);
    AddressTracker newAddressTracker = loadBalancer.trackerMap.get(
        newAddressGroup.getAddresses().get(0));

    // The one subchannel that was returning errors should be ejected.
    assertEjectedSubchannels(ImmutableSet.of(oldAddressGroup.getAddresses().get(0)));

    // The ejected subchannel gets updated with another address in the map that is not ejected
    OutlierDetectionSubchannel subchannel = oldAddressTracker.getSubchannels()
        .iterator().next();
    subchannel.updateAddresses(ImmutableList.of(newAddressGroup));

    // The replaced address should no longer have the subchannel associated with it.
    assertThat(oldAddressTracker.getSubchannels()).doesNotContain(subchannel);

    // The new address should instead have the subchannel.
    assertThat(newAddressTracker.getSubchannels()).contains(subchannel);

    // Since the new address is not ejected, the ejected subchannel moving over to it should also
    // become unejected.
    assertThat(subchannel.isEjected()).isFalse();
  }

  /**
   * If a single address gets replaced by multiple, the subchannel becomes uneligible for outlier
   * detection.
   */
  @Test
  public void subchannelUpdateAddress_singleReplacedWithMultiple() {
    OutlierDetectionLoadBalancerConfig config = new OutlierDetectionLoadBalancerConfig.Builder()
        .setMaxEjectionPercent(50)
        .setFailurePercentageEjection(
            new FailurePercentageEjection.Builder()
                .setMinimumHosts(3)
                .setRequestVolume(10).build())
        .setChildPolicy(new PolicySelection(roundRobinLbProvider, null)).build();

    loadBalancer.acceptResolvedAddresses(buildResolvedAddress(config, servers));

    generateLoad(ImmutableMap.of(), 7);

    // Move forward in time to a point where the detection timer has fired.
    forwardTime(config);

    EquivalentAddressGroup oldAddressGroup = servers.get(0);
    AddressTracker oldAddressTracker = loadBalancer.trackerMap.get(
        oldAddressGroup.getAddresses().get(0));
    EquivalentAddressGroup newAddress1 = servers.get(1);
    EquivalentAddressGroup newAddress2 = servers.get(2);

    OutlierDetectionSubchannel subchannel = oldAddressTracker.getSubchannels()
        .iterator().next();

    // The subchannel gets updated with two new addresses
    ImmutableList<EquivalentAddressGroup> addressUpdate
        = ImmutableList.of(newAddress1, newAddress2);
    subchannel.updateAddresses(addressUpdate);
    when(subchannel1.getAllAddresses()).thenReturn(addressUpdate);

    // The replaced address should no longer be tracked.
    assertThat(oldAddressTracker.getSubchannels()).doesNotContain(subchannel);

    // The old tracker should also have its call counters cleared.
    assertThat(oldAddressTracker.activeVolume()).isEqualTo(0);
    assertThat(oldAddressTracker.inactiveVolume()).isEqualTo(0);
  }

  /**
   * A subchannel with multiple addresses will again become eligible for outlier detection if it
   * receives an update with a single address.
   */
  @Test
  public void subchannelUpdateAddress_multipleReplacedWithSingle() {
    OutlierDetectionLoadBalancerConfig config = new OutlierDetectionLoadBalancerConfig.Builder()
        .setMaxEjectionPercent(50)
        .setFailurePercentageEjection(
            new FailurePercentageEjection.Builder()
                .setMinimumHosts(3)
                .setRequestVolume(10).build())
        .setChildPolicy(new PolicySelection(fakeLbProvider, null)).build();

    loadBalancer.acceptResolvedAddresses(buildResolvedAddress(config, servers));

    generateLoad(ImmutableMap.of(subchannel1, Status.DEADLINE_EXCEEDED), 6);

    // Move forward in time to a point where the detection timer has fired.
    forwardTime(config);

    EquivalentAddressGroup oldAddressGroup = servers.get(0);
    AddressTracker oldAddressTracker = loadBalancer.trackerMap.get(
        oldAddressGroup.getAddresses().get(0));
    EquivalentAddressGroup newAddressGroup1 = servers.get(1);
    AddressTracker newAddressTracker1 = loadBalancer.trackerMap.get(
        newAddressGroup1.getAddresses().get(0));
    EquivalentAddressGroup newAddressGroup2 = servers.get(2);

    // The old subchannel was returning errors and should be ejected.
    assertEjectedSubchannels(ImmutableSet.of(oldAddressGroup.getAddresses().get(0)));

    OutlierDetectionSubchannel subchannel = oldAddressTracker.getSubchannels()
        .iterator().next();

    // The subchannel gets updated with two new addresses
    ImmutableList<EquivalentAddressGroup> addressUpdate
        = ImmutableList.of(newAddressGroup1, newAddressGroup2);
    subchannel.updateAddresses(addressUpdate);
    when(subchannel1.getAllAddresses()).thenReturn(addressUpdate);

    // The replaced address should no longer be tracked.
    assertThat(oldAddressTracker.getSubchannels()).doesNotContain(subchannel);

    // The old tracker should also have its call counters cleared.
    assertThat(oldAddressTracker.activeVolume()).isEqualTo(0);
    assertThat(oldAddressTracker.inactiveVolume()).isEqualTo(0);

    // Another update takes the subchannel back to a single address.
    addressUpdate = ImmutableList.of(newAddressGroup1);
    subchannel.updateAddresses(addressUpdate);
    when(subchannel1.getAllAddresses()).thenReturn(addressUpdate);

    // The subchannel is now associated with the single new address.
    assertThat(newAddressTracker1.getSubchannels()).contains(subchannel);

    // The previously ejected subchannel should become unejected as it is now associated with an
    // unejected address.
    assertThat(subchannel.isEjected()).isFalse();
  }

  /** Both algorithms configured, but no outliers. */
  @Test
  public void successRateAndFailurePercentage_noOutliers() {
    OutlierDetectionLoadBalancerConfig config = new OutlierDetectionLoadBalancerConfig.Builder()
        .setMaxEjectionPercent(50)
        .setSuccessRateEjection(
            new SuccessRateEjection.Builder()
                .setMinimumHosts(3)
                .setRequestVolume(10).build())
        .setFailurePercentageEjection(
            new FailurePercentageEjection.Builder()
                .setMinimumHosts(3)
                .setRequestVolume(10).build())
        .setChildPolicy(new PolicySelection(roundRobinLbProvider, null)).build();

    loadBalancer.acceptResolvedAddresses(buildResolvedAddress(config, servers));

    generateLoad(ImmutableMap.of(), 7);

    // Move forward in time to a point where the detection timer has fired.
    forwardTime(config);

    // No outliers, no ejections.
    assertEjectedSubchannels(ImmutableSet.of());
  }

  /** Both algorithms configured, success rate detects an outlier. */
  @Test
  public void successRateAndFailurePercentage_successRateOutlier() {
    OutlierDetectionLoadBalancerConfig config = new OutlierDetectionLoadBalancerConfig.Builder()
        .setMaxEjectionPercent(50)
        .setSuccessRateEjection(
            new SuccessRateEjection.Builder()
                .setMinimumHosts(3)
                .setRequestVolume(10).build())
        .setFailurePercentageEjection(
            new FailurePercentageEjection.Builder()
                .setMinimumHosts(3)
                .setRequestVolume(10)
                .setEnforcementPercentage(0).build()) // Configured, but not enforcing.
        .setChildPolicy(new PolicySelection(roundRobinLbProvider, null)).build();

    loadBalancer.acceptResolvedAddresses(buildResolvedAddress(config, servers));

    generateLoad(ImmutableMap.of(subchannel1, Status.DEADLINE_EXCEEDED), 7);

    // Move forward in time to a point where the detection timer has fired.
    forwardTime(config);

    // The one subchannel that was returning errors should be ejected.
    assertEjectedSubchannels(ImmutableSet.of(servers.get(0).getAddresses().get(0)));
  }

  /** Both algorithms configured, error percentage detects an outlier. */
  @Test
  public void successRateAndFailurePercentage_errorPercentageOutlier() {
    OutlierDetectionLoadBalancerConfig config = new OutlierDetectionLoadBalancerConfig.Builder()
        .setMaxEjectionPercent(50)
        .setSuccessRateEjection(
            new SuccessRateEjection.Builder()
                .setMinimumHosts(3)
                .setRequestVolume(10)
                .setEnforcementPercentage(0).build())
        .setFailurePercentageEjection(
            new FailurePercentageEjection.Builder()
                .setMinimumHosts(3)
                .setRequestVolume(10).build()) // Configured, but not enforcing.
        .setChildPolicy(new PolicySelection(roundRobinLbProvider, null)).build();

    loadBalancer.acceptResolvedAddresses(buildResolvedAddress(config, servers));

    generateLoad(ImmutableMap.of(subchannel1, Status.DEADLINE_EXCEEDED), 7);

    // Move forward in time to a point where the detection timer has fired.
    forwardTime(config);

    // The one subchannel that was returning errors should be ejected.
    assertEjectedSubchannels(ImmutableSet.of(servers.get(0).getAddresses().get(0)));
  }

  @Test
  public void mathChecksOut() {
    ImmutableList<Double> values = ImmutableList.of(600d, 470d, 170d, 430d, 300d);
    double mean = SuccessRateOutlierEjectionAlgorithm.mean(values);
    double stdev = SuccessRateOutlierEjectionAlgorithm.standardDeviation(values, mean);

    assertThat(mean).isEqualTo(394);
    assertThat(stdev).isEqualTo(147.32277488562318);
  }

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

  private void generateLoad(Map<Subchannel, Status> statusMap, int expectedStateChanges) {
    generateLoad(statusMap, null, expectedStateChanges);
  }

  // Generates 100 calls, 20 each across the subchannels. Default status is OK.
  private void generateLoad(Map<Subchannel, Status> statusMap,
      Map<Subchannel, Integer> maxCallsMap, int expectedStateChanges) {
    deliverSubchannelState(subchannel1, ConnectivityStateInfo.forNonError(READY));
    deliverSubchannelState(subchannel2, ConnectivityStateInfo.forNonError(READY));
    deliverSubchannelState(subchannel3, ConnectivityStateInfo.forNonError(READY));
    deliverSubchannelState(subchannel4, ConnectivityStateInfo.forNonError(READY));
    deliverSubchannelState(subchannel5, ConnectivityStateInfo.forNonError(READY));

    verify(mockHelper, times(expectedStateChanges)).updateBalancingState(stateCaptor.capture(),
        pickerCaptor.capture());
    SubchannelPicker picker = pickerCaptor.getAllValues()
        .get(pickerCaptor.getAllValues().size() - 1);

    HashMap<Subchannel, Integer> callCountMap = new HashMap<>();
    for (int i = 0; i < 100; i++) {
      PickResult pickResult = picker
          .pickSubchannel(mock(PickSubchannelArgs.class));
      ClientStreamTracer clientStreamTracer = pickResult.getStreamTracerFactory()
          .newClientStreamTracer(null, null);

      Subchannel subchannel = ((OutlierDetectionSubchannel) pickResult.getSubchannel()).delegate();

      int maxCalls =
          maxCallsMap != null && maxCallsMap.containsKey(subchannel)
              ? maxCallsMap.get(subchannel) : Integer.MAX_VALUE;
      int calls = callCountMap.containsKey(subchannel) ? callCountMap.get(subchannel) : 0;
      if (calls < maxCalls) {
        callCountMap.put(subchannel, ++calls);
        clientStreamTracer.streamClosed(
            statusMap.containsKey(subchannel) ? statusMap.get(subchannel) : Status.OK);
      }
    }
  }

  // Forwards time past the moment when the timer will fire.
  private void forwardTime(OutlierDetectionLoadBalancerConfig config) {
    fakeClock.forwardTime(config.intervalNanos + 1, TimeUnit.NANOSECONDS);
  }

  // Asserts that the given addresses are ejected and the rest are not.
  void assertEjectedSubchannels(Set<SocketAddress> addresses) {
    for (Entry<SocketAddress, AddressTracker> entry : loadBalancer.trackerMap.entrySet()) {
      assertWithMessage("not ejected: " + entry.getKey())
          .that(entry.getValue().subchannelsEjected())
          .isEqualTo(addresses.contains(entry.getKey()));
    }
  }

  /** Round robin like fake load balancer. */
  private final class FakeLoadBalancer extends LoadBalancer {
    private final Helper helper;

    List<Subchannel> subchannelList;
    int lastPickIndex = -1;

    FakeLoadBalancer(Helper helper) {
      this.helper = helper;
    }

    @Override
    public boolean acceptResolvedAddresses(ResolvedAddresses resolvedAddresses) {
      subchannelList = new ArrayList<>();
      for (EquivalentAddressGroup eag: resolvedAddresses.getAddresses()) {
        Subchannel subchannel = helper.createSubchannel(CreateSubchannelArgs.newBuilder()
            .setAddresses(eag).build());
        subchannelList.add(subchannel);
        subchannel.start(mock(SubchannelStateListener.class));
        deliverSubchannelState(READY);
      }
      return true;
    }

    @Override
    public void handleNameResolutionError(Status error) {
    }

    @Override
    public void shutdown() {
    }

    void deliverSubchannelState(ConnectivityState state) {
      SubchannelPicker picker = new SubchannelPicker() {
        @Override
        public PickResult pickSubchannel(PickSubchannelArgs args) {
          if (lastPickIndex < 0 || lastPickIndex > subchannelList.size() - 1) {
            lastPickIndex = 0;
          }
          return PickResult.withSubchannel(subchannelList.get(lastPickIndex++),
              mockStreamTracerFactory);
        }
      };
      helper.updateBalancingState(state, picker);
    }
  }
}
