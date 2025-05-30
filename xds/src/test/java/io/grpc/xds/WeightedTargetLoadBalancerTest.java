/*
 * Copyright 2020 The gRPC Authors
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
import static io.grpc.ConnectivityState.TRANSIENT_FAILURE;
import static io.grpc.LoadBalancerMatchers.pickerReturns;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import io.grpc.Attributes;
import io.grpc.EquivalentAddressGroup;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancer.FixedResultPicker;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancer.PickResult;
import io.grpc.LoadBalancer.PickSubchannelArgs;
import io.grpc.LoadBalancer.ResolvedAddresses;
import io.grpc.LoadBalancer.SubchannelPicker;
import io.grpc.LoadBalancerProvider;
import io.grpc.LoadBalancerRegistry;
import io.grpc.Status;
import io.grpc.SynchronizationContext;
import io.grpc.util.GracefulSwitchLoadBalancer;
import io.grpc.xds.WeightedRandomPicker.WeightedChildPicker;
import io.grpc.xds.WeightedTargetLoadBalancerProvider.WeightedPolicySelection;
import io.grpc.xds.WeightedTargetLoadBalancerProvider.WeightedTargetConfig;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/** Tests for {@link WeightedTargetLoadBalancer}. */
@RunWith(JUnit4.class)
public class WeightedTargetLoadBalancerTest {
  @Rule public final MockitoRule mocks = MockitoJUnit.rule();

  private final SynchronizationContext syncContext = new SynchronizationContext(
      new Thread.UncaughtExceptionHandler() {
        @Override
        public void uncaughtException(Thread t, Throwable e) {
          throw new AssertionError(e);
        }
      });
  private final LoadBalancerRegistry lbRegistry = new LoadBalancerRegistry();
  private final List<LoadBalancer> childBalancers = new ArrayList<>();
  private final List<Helper> childHelpers = new ArrayList<>();
  private final int[] weights = {10, 20, 30, 40};
  private final Object[] configs = {"config0", "config1", "config3", "config4"};
  private final SocketAddress[] socketAddresses = {
    new InetSocketAddress(8080),
    new InetSocketAddress(8081),
    new InetSocketAddress(8083),
    new InetSocketAddress(8084)
  };

  private final LoadBalancerProvider fooLbProvider = new LoadBalancerProvider() {
    @Override
    public boolean isAvailable() {
      return true;
    }

    @Override
    public int getPriority() {
      return 5;
    }

    @Override
    public String getPolicyName() {
      return "foo_policy";
    }

    @Override
    public LoadBalancer newLoadBalancer(Helper helper) {
      childHelpers.add(helper);
      LoadBalancer childBalancer = mock(LoadBalancer.class);
      when(childBalancer.acceptResolvedAddresses(any())).thenReturn(Status.OK);
      childBalancers.add(childBalancer);
      fooLbCreated++;
      return childBalancer;
    }
  };

  private final LoadBalancerProvider barLbProvider = new LoadBalancerProvider() {
    @Override
    public boolean isAvailable() {
      return true;
    }

    @Override
    public int getPriority() {
      return 5;
    }

    @Override
    public String getPolicyName() {
      return "bar_policy";
    }

    @Override
    public LoadBalancer newLoadBalancer(Helper helper) {
      childHelpers.add(helper);
      LoadBalancer childBalancer = mock(LoadBalancer.class);
      when(childBalancer.acceptResolvedAddresses(any())).thenReturn(Status.OK);
      childBalancers.add(childBalancer);
      barLbCreated++;
      return childBalancer;
    }
  };

  private final WeightedPolicySelection weightedLbConfig0 = new WeightedPolicySelection(
      weights[0], newChildConfig(fooLbProvider, configs[0]));
  private final WeightedPolicySelection weightedLbConfig1 = new WeightedPolicySelection(
      weights[1], newChildConfig(barLbProvider, configs[1]));
  private final WeightedPolicySelection weightedLbConfig2 = new WeightedPolicySelection(
      weights[2],  newChildConfig(barLbProvider, configs[2]));
  private final WeightedPolicySelection weightedLbConfig3 = new WeightedPolicySelection(
      weights[3], newChildConfig(fooLbProvider, configs[3]));

  @Mock
  private Helper helper;

  private LoadBalancer weightedTargetLb;
  private int fooLbCreated;
  private int barLbCreated;

  @Before
  public void setUp() {
    when(helper.getSynchronizationContext()).thenReturn(syncContext);
    lbRegistry.register(fooLbProvider);
    lbRegistry.register(barLbProvider);

    weightedTargetLb = new WeightedTargetLoadBalancer(helper);
    clearInvocations(helper);
  }

  @After
  public void tearDown() {
    weightedTargetLb.shutdown();
    for (LoadBalancer childBalancer : childBalancers) {
      verify(childBalancer).shutdown();
    }
  }

  @Test
  public void acceptResolvedAddresses() {
    ArgumentCaptor<ResolvedAddresses> resolvedAddressesCaptor =
        ArgumentCaptor.forClass(ResolvedAddresses.class);
    Attributes.Key<Object> fakeKey = Attributes.Key.create("fake_key");
    Object fakeValue = new Object();

    Map<String, WeightedPolicySelection> targets = ImmutableMap.of(
        // {foo, 10, config0}
        "target0", weightedLbConfig0,
        // {bar, 20, config1}
        "target1", weightedLbConfig1,
        // {bar, 30, config2}
        "target2", weightedLbConfig2,
        // {foo, 40, config3}
        "target3", weightedLbConfig3);
    EquivalentAddressGroup eag0 = new EquivalentAddressGroup(socketAddresses[0]);
    eag0 = AddressFilter.setPathFilter(eag0, ImmutableList.of("target0"));
    EquivalentAddressGroup eag1 = new EquivalentAddressGroup(socketAddresses[1]);
    eag1 = AddressFilter.setPathFilter(eag1, ImmutableList.of("target1"));
    EquivalentAddressGroup eag2 = new EquivalentAddressGroup(socketAddresses[2]);
    eag2 = AddressFilter.setPathFilter(eag2, ImmutableList.of("target2"));
    EquivalentAddressGroup eag3 = new EquivalentAddressGroup(socketAddresses[3]);
    eag3 = AddressFilter.setPathFilter(eag3, ImmutableList.of("target3"));
    Status status = weightedTargetLb.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder()
            .setAddresses(ImmutableList.of(eag0, eag1, eag2, eag3))
            .setAttributes(Attributes.newBuilder().set(fakeKey, fakeValue).build())
            .setLoadBalancingPolicyConfig(new WeightedTargetConfig(targets))
            .build());
    assertThat(status.isOk()).isTrue();
    verify(helper).updateBalancingState(eq(CONNECTING), pickerReturns(PickResult.withNoResult()));
    assertThat(childBalancers).hasSize(4);
    assertThat(childHelpers).hasSize(4);
    assertThat(fooLbCreated).isEqualTo(2);
    assertThat(barLbCreated).isEqualTo(2);

    for (int i = 0; i < childBalancers.size(); i++) {
      verify(childBalancers.get(i)).acceptResolvedAddresses(resolvedAddressesCaptor.capture());
      ResolvedAddresses resolvedAddresses = resolvedAddressesCaptor.getValue();
      assertThat(resolvedAddresses.getLoadBalancingPolicyConfig()).isEqualTo(configs[i]);
      assertThat(resolvedAddresses.getAttributes().get(fakeKey)).isEqualTo(fakeValue);
      assertThat(resolvedAddresses.getAttributes().get(WeightedTargetLoadBalancer.CHILD_NAME))
          .isEqualTo("target" + i);
      assertThat(Iterables.getOnlyElement(resolvedAddresses.getAddresses()).getAddresses())
          .containsExactly(socketAddresses[i]);
    }

    // Even when a child return an error from the update, the other children should still receive
    // their updates.
    Status acceptReturnStatus = Status.UNAVAILABLE.withDescription("Didn't like something");
    when(childBalancers.get(2).acceptResolvedAddresses(any())).thenReturn(acceptReturnStatus);

    // Update new weighted target config for a typical workflow.
    // target0 removed. target1, target2, target3 changed weight and config. target4 added.
    int[] newWeights = new int[]{11, 22, 33, 44};
    Object[] newConfigs = new Object[]{"newConfig1", "newConfig2", "newConfig3", "newConfig4"};
    Map<String, WeightedPolicySelection> newTargets = ImmutableMap.of(
        "target1",
        new WeightedPolicySelection(
            newWeights[0], newChildConfig(barLbProvider, newConfigs[0])),
        "target2",
        new WeightedPolicySelection(
            newWeights[1], newChildConfig(barLbProvider, newConfigs[1])),
        "target3",
        new WeightedPolicySelection(
            newWeights[2], newChildConfig(fooLbProvider, newConfigs[2])),
        "target4",
        new WeightedPolicySelection(
            newWeights[3], newChildConfig(fooLbProvider, newConfigs[3])));
    status = weightedTargetLb.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder()
            .setAddresses(ImmutableList.<EquivalentAddressGroup>of())
            .setLoadBalancingPolicyConfig(new WeightedTargetConfig(newTargets))
            .build());
    assertThat(status.getCode()).isEqualTo(acceptReturnStatus.getCode());
    verify(helper, atLeast(2))
        .updateBalancingState(eq(CONNECTING), pickerReturns(PickResult.withNoResult()));
    assertThat(childBalancers).hasSize(5);
    assertThat(childHelpers).hasSize(5);
    assertThat(fooLbCreated).isEqualTo(3); // One more foo LB created for target4
    assertThat(barLbCreated).isEqualTo(2);

    verify(childBalancers.get(0)).shutdown();
    for (int i = 1; i < childBalancers.size(); i++) {
      verify(childBalancers.get(i), atLeastOnce())
          .acceptResolvedAddresses(resolvedAddressesCaptor.capture());
      assertThat(resolvedAddressesCaptor.getValue().getLoadBalancingPolicyConfig())
          .isEqualTo(newConfigs[i - 1]);
    }
  }

  @Test
  public void handleNameResolutionError() {
    ArgumentCaptor<SubchannelPicker> pickerCaptor = ArgumentCaptor.forClass(SubchannelPicker.class);
    ArgumentCaptor<Status> statusCaptor = ArgumentCaptor.forClass(Status.class);

    // Error before any child balancer created.
    weightedTargetLb.handleNameResolutionError(Status.DATA_LOSS);

    verify(helper).updateBalancingState(eq(TRANSIENT_FAILURE), pickerCaptor.capture());
    PickResult pickResult = pickerCaptor.getValue().pickSubchannel(mock(PickSubchannelArgs.class));
    assertThat(pickResult.getStatus().getCode()).isEqualTo(Status.Code.DATA_LOSS);

    // Child configs updated.
    Map<String, WeightedPolicySelection> targets = ImmutableMap.of(
        // {foo, 10, config0}
        "target0", weightedLbConfig0,
        // {bar, 20, config1}
        "target1", weightedLbConfig1,
        // {bar, 30, config2}
        "target2", weightedLbConfig2,
        // {foo, 40, config3}
        "target3", weightedLbConfig3);
    weightedTargetLb.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder()
            .setAddresses(ImmutableList.<EquivalentAddressGroup>of())
            .setLoadBalancingPolicyConfig(new WeightedTargetConfig(targets))
            .build());
    verify(helper).updateBalancingState(eq(CONNECTING), pickerReturns(PickResult.withNoResult()));

    // Error after child balancers created.
    weightedTargetLb.handleNameResolutionError(Status.ABORTED);

    for (LoadBalancer childBalancer : childBalancers) {
      verify(childBalancer).handleNameResolutionError(statusCaptor.capture());
      assertThat(statusCaptor.getValue().getCode()).isEqualTo(Status.Code.ABORTED);
    }
  }

  @Test
  public void balancingStateUpdatedFromChildBalancers() {
    Map<String, WeightedPolicySelection> targets = ImmutableMap.of(
        // {foo, 10, config0}
        "target0", weightedLbConfig0,
        // {bar, 20, config1}
        "target1", weightedLbConfig1,
        // {bar, 30, config2}
        "target2", weightedLbConfig2,
        // {foo, 40, config3}
        "target3", weightedLbConfig3);
    weightedTargetLb.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder()
            .setAddresses(ImmutableList.<EquivalentAddressGroup>of())
            .setLoadBalancingPolicyConfig(new WeightedTargetConfig(targets))
            .build());
    verify(helper).updateBalancingState(eq(CONNECTING), pickerReturns(PickResult.withNoResult()));

    // Subchannels to be created for each child balancer.
    final SubchannelPicker[] subchannelPickers = new SubchannelPicker[]{
        mock(SubchannelPicker.class),
        mock(SubchannelPicker.class),
        mock(SubchannelPicker.class),
        mock(SubchannelPicker.class)};
    final SubchannelPicker[] failurePickers = new SubchannelPicker[]{
        new FixedResultPicker(PickResult.withError(Status.CANCELLED)),
        new FixedResultPicker(PickResult.withError(Status.ABORTED)),
        new FixedResultPicker(PickResult.withError(Status.DATA_LOSS)),
        new FixedResultPicker(PickResult.withError(Status.DATA_LOSS))
    };
    ArgumentCaptor<SubchannelPicker> pickerCaptor = ArgumentCaptor.forClass(SubchannelPicker.class);

    // One child balancer goes to TRANSIENT_FAILURE.
    childHelpers.get(1).updateBalancingState(TRANSIENT_FAILURE, failurePickers[1]);
    verify(helper, never()).updateBalancingState(
        eq(TRANSIENT_FAILURE), any(SubchannelPicker.class));
    verify(helper, times(2))
        .updateBalancingState(eq(CONNECTING), pickerReturns(PickResult.withNoResult()));

    // Another child balancer goes to READY.
    childHelpers.get(2).updateBalancingState(READY, subchannelPickers[2]);
    verify(helper).updateBalancingState(eq(READY), pickerCaptor.capture());
    assertThat(pickerCaptor.getValue()).isInstanceOf(WeightedRandomPicker.class);
    WeightedRandomPicker overallPicker = (WeightedRandomPicker) pickerCaptor.getValue();
    assertThat(overallPicker.weightedChildPickers)
        .containsExactly(new WeightedChildPicker(weights[2], subchannelPickers[2]));

    // Another child balancer goes to READY.
    childHelpers.get(3).updateBalancingState(READY, subchannelPickers[3]);
    verify(helper, times(2)).updateBalancingState(eq(READY), pickerCaptor.capture());
    overallPicker = (WeightedRandomPicker) pickerCaptor.getValue();
    assertThat(overallPicker.weightedChildPickers)
        .containsExactly(
            new WeightedChildPicker(weights[2], subchannelPickers[2]),
            new WeightedChildPicker(weights[3], subchannelPickers[3]));

    // Another child balancer goes to READY.
    childHelpers.get(0).updateBalancingState(READY, subchannelPickers[0]);
    verify(helper, times(3)).updateBalancingState(eq(READY), pickerCaptor.capture());
    overallPicker = (WeightedRandomPicker) pickerCaptor.getValue();
    assertThat(overallPicker.weightedChildPickers)
        .containsExactly(
            new WeightedChildPicker(weights[0], subchannelPickers[0]),
            new WeightedChildPicker(weights[2], subchannelPickers[2]),
            new WeightedChildPicker(weights[3], subchannelPickers[3]));

    // One of READY child balancers goes to TRANSIENT_FAILURE.
    childHelpers.get(2).updateBalancingState(TRANSIENT_FAILURE, failurePickers[2]);
    verify(helper, times(4)).updateBalancingState(eq(READY), pickerCaptor.capture());
    overallPicker = (WeightedRandomPicker) pickerCaptor.getValue();
    assertThat(overallPicker.weightedChildPickers)
        .containsExactly(
            new WeightedChildPicker(weights[0], subchannelPickers[0]),
            new WeightedChildPicker(weights[3], subchannelPickers[3]));

    // All child balancers go to TRANSIENT_FAILURE.
    childHelpers.get(3).updateBalancingState(TRANSIENT_FAILURE, failurePickers[3]);
    childHelpers.get(0).updateBalancingState(TRANSIENT_FAILURE, failurePickers[0]);
    verify(helper).updateBalancingState(eq(TRANSIENT_FAILURE), pickerCaptor.capture());
    overallPicker = (WeightedRandomPicker) pickerCaptor.getValue();
    assertThat(overallPicker.weightedChildPickers)
        .containsExactly(
            new WeightedChildPicker(weights[0], failurePickers[0]),
            new WeightedChildPicker(weights[1], failurePickers[1]),
            new WeightedChildPicker(weights[2], failurePickers[2]),
            new WeightedChildPicker(weights[3], failurePickers[3]));
  }

  @Test
  public void raceBetweenShutdownAndChildLbBalancingStateUpdate() {
    Map<String, WeightedPolicySelection> targets = ImmutableMap.of(
        "target0", weightedLbConfig0,
        "target1", weightedLbConfig1);
    weightedTargetLb.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder()
            .setAddresses(ImmutableList.<EquivalentAddressGroup>of())
            .setLoadBalancingPolicyConfig(new WeightedTargetConfig(targets))
            .build());
    verify(helper).updateBalancingState(eq(CONNECTING), pickerReturns(PickResult.withNoResult()));

    // LB shutdown and subchannel state change can happen simultaneously. If shutdown runs first,
    // any further balancing state update should be ignored.
    weightedTargetLb.shutdown();
    Helper weightedChildHelper0 = childHelpers.iterator().next();
    weightedChildHelper0.updateBalancingState(READY, mock(SubchannelPicker.class));
    verifyNoMoreInteractions(helper);
  }

  // When the ChildHelper is asked to update the overall balancing state, it should not do that if
  // the update was triggered by the parent LB that will handle triggering the overall state update.
  @Test
  public void noDuplicateOverallBalancingStateUpdate() {
    FakeLoadBalancerProvider fakeLbProvider = new FakeLoadBalancerProvider();

    Map<String, WeightedPolicySelection> targets = ImmutableMap.of(
        "target0", new WeightedPolicySelection(
            weights[0], newChildConfig(fakeLbProvider, configs[0])),
        "target3", new WeightedPolicySelection(
            weights[3], newChildConfig(fakeLbProvider, configs[3])));
    weightedTargetLb.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder()
            .setAddresses(ImmutableList.<EquivalentAddressGroup>of())
            .setLoadBalancingPolicyConfig(new WeightedTargetConfig(targets))
            .build());

    // Both of the two child LB policies will call the helper to update the balancing state.
    // But since those calls happen during the handling of teh resolved addresses of the parent
    // WeightedTargetLLoadBalancer, the overall balancing state should only be updated once.
    verify(helper, times(1)).updateBalancingState(any(), any());

  }

  private Object newChildConfig(LoadBalancerProvider provider, Object config) {
    return GracefulSwitchLoadBalancer.createLoadBalancingPolicyConfig(provider, config);
  }

  private static class FakeLoadBalancerProvider extends LoadBalancerProvider {

    @Override
    public boolean isAvailable() {
      return true;
    }

    @Override
    public int getPriority() {
      return 5;
    }

    @Override
    public String getPolicyName() {
      return "foo";
    }

    @Override
    public LoadBalancer newLoadBalancer(Helper helper) {
      return new FakeLoadBalancer(helper);
    }
  }

  static class FakeLoadBalancer extends LoadBalancer {

    private Helper helper;

    FakeLoadBalancer(Helper helper) {
      this.helper = helper;
    }

    @Override
    public Status acceptResolvedAddresses(ResolvedAddresses resolvedAddresses) {
      helper.updateBalancingState(
          TRANSIENT_FAILURE, new FixedResultPicker(PickResult.withError(Status.INTERNAL)));
      return Status.OK;
    }

    @Override
    public void handleNameResolutionError(Status error) {
    }

    @Override
    public void shutdown() {
    }
  }
}
