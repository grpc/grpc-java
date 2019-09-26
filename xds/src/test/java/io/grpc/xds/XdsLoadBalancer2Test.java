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
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import io.grpc.Attributes;
import io.grpc.ChannelLogger;
import io.grpc.ConnectivityStateInfo;
import io.grpc.EquivalentAddressGroup;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancer.ResolvedAddresses;
import io.grpc.LoadBalancer.Subchannel;
import io.grpc.LoadBalancer.SubchannelPicker;
import io.grpc.Status;
import io.grpc.SynchronizationContext;
import io.grpc.internal.FakeClock;
import io.grpc.xds.XdsComms.AdsStreamCallback;
import io.grpc.xds.XdsLoadBalancer2.LookasideLbFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/** Unit tests for {@link XdsLoadBalancer2}. */
@RunWith(JUnit4.class)
public class XdsLoadBalancer2Test {
  @Rule
  public final ExpectedException thrown = ExpectedException.none();
  @Rule
  public final MockitoRule mockitoRule = MockitoJUnit.rule();

  private final FakeClock fakeClock = new FakeClock();
  private final SynchronizationContext syncContext = new SynchronizationContext(
      new Thread.UncaughtExceptionHandler() {
        @Override
        public void uncaughtException(Thread t, Throwable e) {
          throw new AssertionError(e);
        }
      });

  @Mock
  private Helper helper;
  private LoadBalancer xdsLoadBalancer;
  private AdsStreamCallback adsCallback;

  private Helper lookasideLbHelper;
  private final List<LoadBalancer> lookasideLbs = new ArrayList<>();

  private Helper fallbackLbHelper;
  private final List<LoadBalancer> fallbackLbs = new ArrayList<>();

  private int requestConnectionTimes;

  @Before
  public void setUp() {
    LookasideLbFactory lookasideLbFactory = new LookasideLbFactory() {
      @Override
      public LoadBalancer newLoadBalancer(Helper helper, AdsStreamCallback adsCallback) {
        // just return a mock and record the input and output
        lookasideLbHelper = helper;
        XdsLoadBalancer2Test.this.adsCallback = adsCallback;
        LoadBalancer lookasideLb = mock(LoadBalancer.class);
        lookasideLbs.add(lookasideLb);
        return lookasideLb;
      }
    };
    LoadBalancer.Factory fallbackLbFactory = new LoadBalancer.Factory() {
      @Override
      public LoadBalancer newLoadBalancer(Helper helper) {
        // just return a mock and record the input and output
        fallbackLbHelper = helper;
        LoadBalancer fallbackLb = mock(LoadBalancer.class);
        fallbackLbs.add(fallbackLb);
        return fallbackLb;
      }
    };
    doReturn(syncContext).when(helper).getSynchronizationContext();
    doReturn(fakeClock.getScheduledExecutorService()).when(helper).getScheduledExecutorService();
    doReturn(mock(ChannelLogger.class)).when(helper).getChannelLogger();

    xdsLoadBalancer =
        new XdsLoadBalancer2(helper, lookasideLbFactory, fallbackLbFactory);
    xdsLoadBalancer.handleResolvedAddresses(
        ResolvedAddresses.newBuilder()
            .setAddresses(ImmutableList.<EquivalentAddressGroup>of()).build());
  }

  @Test
  public void tearDown() {
    assertThat(lookasideLbs).hasSize(1);
    xdsLoadBalancer.shutdown();
    for (LoadBalancer lookasideLb : lookasideLbs) {
      verify(lookasideLb).shutdown();
    }
    for (LoadBalancer fallbackLb : fallbackLbs) {
      verify(fallbackLb).shutdown();
    }
    assertThat(fakeClock.getPendingTasks()).isEmpty();
  }

  @Test
  public void canHandleEmptyAddressListFromNameResolution() {
    assertThat(xdsLoadBalancer.canHandleEmptyAddressListFromNameResolution()).isTrue();
  }

  @Test
  public void timeoutAtStartup_expectUseFallback_thenBackendReady_expectExitFallback() {
    verifyNotInFallbackMode();
    fakeClock.forwardTime(9, TimeUnit.SECONDS);
    adsCallback.onWorking();
    verifyNotInFallbackMode();
    fakeClock.forwardTime(1, TimeUnit.SECONDS);
    verifyInFallbackMode();

    SubchannelPicker subchannelPicker = mock(SubchannelPicker.class);
    lookasideLbHelper.updateBalancingState(READY, subchannelPicker);
    verify(helper).updateBalancingState(READY, subchannelPicker);
    verifyNotInFallbackMode();

    assertThat(fallbackLbs).hasSize(1);
  }

  @Test
  public void backendReadyBeforeTimeoutAtStartup_expectNoFallback() {
    verifyNotInFallbackMode();
    fakeClock.forwardTime(9, TimeUnit.SECONDS);
    verifyNotInFallbackMode();
    assertThat(fakeClock.getPendingTasks()).hasSize(1);

    adsCallback.onWorking();
    SubchannelPicker subchannelPicker = mock(SubchannelPicker.class);
    lookasideLbHelper.updateBalancingState(READY, subchannelPicker);
    verify(helper).updateBalancingState(READY, subchannelPicker);
    assertThat(fakeClock.getPendingTasks()).isEmpty();
    verifyNotInFallbackMode();

    assertThat(fallbackLbs).isEmpty();
  }

  @Test
  public void recevieAllDropBeforeTimeoutAtStartup_expectNoFallback() {
    verifyNotInFallbackMode();
    assertThat(fakeClock.getPendingTasks()).hasSize(1);

    adsCallback.onAllDrop();
    assertThat(fakeClock.getPendingTasks()).isEmpty();
    verifyNotInFallbackMode();

    assertThat(fallbackLbs).isEmpty();
  }

  @Test
  public void lookasideChannelFailsWithoutSeeingEdsResponseBeforeTimeoutAtStartup() {
    verifyNotInFallbackMode();
    assertThat(fakeClock.getPendingTasks()).hasSize(1);

    adsCallback.onError();
    verifyInFallbackMode();

    assertThat(fallbackLbs).hasSize(1);
  }

  @Test
  public void lookasideChannelSeeingEdsResponseThenFailsBeforeTimeoutAtStartup() {
    verifyNotInFallbackMode();
    assertThat(fakeClock.getPendingTasks()).hasSize(1);
    adsCallback.onWorking();
    adsCallback.onError();
    verifyNotInFallbackMode();

    fakeClock.forwardTime(10, TimeUnit.SECONDS);
    verifyInFallbackMode();

    assertThat(fallbackLbs).hasSize(1);
  }

  @Test
  public void fallbackWillHandleLastResolvedAddresses() {
    verifyNotInFallbackMode();

    ResolvedAddresses resolvedAddresses = ResolvedAddresses.newBuilder()
        .setAddresses(ImmutableList.<EquivalentAddressGroup>of())
        .setAttributes(
            Attributes.newBuilder().set(Attributes.Key.create("k"), new Object()).build())
        .setLoadBalancingPolicyConfig(new Object())
        .build();
    xdsLoadBalancer.handleResolvedAddresses(resolvedAddresses);

    adsCallback.onError();
    LoadBalancer fallbackLb =  Iterables.getLast(fallbackLbs);
    verify(fallbackLb).handleResolvedAddresses(same(resolvedAddresses));
  }

  private void verifyInFallbackMode() {
    assertThat(lookasideLbs).isNotEmpty();
    assertThat(fallbackLbs).isNotEmpty();
    LoadBalancer lookasideLb =  Iterables.getLast(lookasideLbs);
    LoadBalancer fallbackLb =  Iterables.getLast(fallbackLbs);
    verify(lookasideLb, never()).shutdown();
    verify(fallbackLb, never()).shutdown();

    xdsLoadBalancer.requestConnection();
    verify(lookasideLb, times(++requestConnectionTimes)).requestConnection();
    verify(fallbackLb).requestConnection();

    ResolvedAddresses resolvedAddresses = ResolvedAddresses.newBuilder()
        .setAddresses(ImmutableList.<EquivalentAddressGroup>of())
        .setAttributes(
            Attributes.newBuilder().set(Attributes.Key.create("k"), new Object()).build())
        .setLoadBalancingPolicyConfig(new Object())
        .build();
    xdsLoadBalancer.handleResolvedAddresses(resolvedAddresses);
    verify(lookasideLb).handleResolvedAddresses(same(resolvedAddresses));
    verify(fallbackLb).handleResolvedAddresses(same(resolvedAddresses));

    Status status = Status.DATA_LOSS.withDescription("");
    xdsLoadBalancer.handleNameResolutionError(status);
    verify(lookasideLb).handleNameResolutionError(same(status));
    verify(fallbackLb).handleNameResolutionError(same(status));

    SubchannelPicker subchannelPicker = mock(SubchannelPicker.class);
    lookasideLbHelper.updateBalancingState(CONNECTING, subchannelPicker);
    verify(helper, never()).updateBalancingState(CONNECTING, subchannelPicker);
    fallbackLbHelper.updateBalancingState(CONNECTING, subchannelPicker);
    verify(helper).updateBalancingState(CONNECTING, subchannelPicker);
  }

  private void verifyNotInFallbackMode() {
    for (LoadBalancer fallbackLb : fallbackLbs) {
      verify(fallbackLb).shutdown();
    }
    LoadBalancer lookasideLb =  Iterables.getLast(lookasideLbs);

    xdsLoadBalancer.requestConnection();
    verify(lookasideLb, times(++requestConnectionTimes)).requestConnection();

    ResolvedAddresses resolvedAddresses = ResolvedAddresses.newBuilder()
        .setAddresses(ImmutableList.<EquivalentAddressGroup>of())
        .setAttributes(
            Attributes.newBuilder().set(Attributes.Key.create("k"), new Object()).build())
        .setLoadBalancingPolicyConfig(new Object())
        .build();
    xdsLoadBalancer.handleResolvedAddresses(resolvedAddresses);
    verify(lookasideLb).handleResolvedAddresses(same(resolvedAddresses));

    Status status = Status.DATA_LOSS.withDescription("");
    xdsLoadBalancer.handleNameResolutionError(status);
    verify(lookasideLb).handleNameResolutionError(same(status));

    SubchannelPicker subchannelPicker = mock(SubchannelPicker.class);
    lookasideLbHelper.updateBalancingState(CONNECTING, subchannelPicker);
    verify(helper).updateBalancingState(CONNECTING, subchannelPicker);
  }

  @Deprecated
  @Test
  public void handleSubchannelState_shouldThrow() {
    Subchannel subchannel = mock(Subchannel.class);
    ConnectivityStateInfo connectivityStateInfo = ConnectivityStateInfo.forNonError(READY);
    thrown.expect(UnsupportedOperationException.class);
    xdsLoadBalancer.handleSubchannelState(subchannel, connectivityStateInfo);
  }
}
