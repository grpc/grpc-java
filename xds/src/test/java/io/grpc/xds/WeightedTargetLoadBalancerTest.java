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
import static io.grpc.xds.XdsSubchannelPickers.BUFFER_PICKER;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.grpc.Attributes;
import io.grpc.ChannelLogger;
import io.grpc.EquivalentAddressGroup;
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
import io.grpc.xds.RandomWeightedPicker.WeightedChildPicker;
import io.grpc.xds.RandomWeightedPicker.WeightedPickerFactory;
import io.grpc.xds.WeightedTargetLoadBalancerProvider.WeightedChildLbConfig;
import io.grpc.xds.WeightedTargetLoadBalancerProvider.WeightedTargetConfig;
import io.grpc.xds.XdsSubchannelPickers.ErrorPicker;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/** Tests for {@link WeightedTargetLoadBalancer}. */
@RunWith(JUnit4.class)
public class WeightedTargetLoadBalancerTest {

  private final LoadBalancerRegistry lbRegistry = new LoadBalancerRegistry();

  private final List<LoadBalancer> childBalancers = new ArrayList<>();
  private final List<Helper> childHelpers = new ArrayList<>();
  private final int[] weights = new int[]{10, 20, 30, 40};
  private final Object[] configs = new Object[]{"config0", "config1", "config3", "config4"};
  private final WeightedChildLbConfig weightedLbConfig0 =
      new WeightedChildLbConfig(weights[0], "foo_policy", configs[0]);
  private final WeightedChildLbConfig weightedLbConfig1 =
      new WeightedChildLbConfig(weights[1], "bar_policy", configs[1]);
  private final WeightedChildLbConfig weightedLbConfig2 =
      new WeightedChildLbConfig(weights[2], "bar_policy", configs[2]);
  private final WeightedChildLbConfig weightedLbConfig3 =
      new WeightedChildLbConfig(weights[3], "foo_policy", configs[3]);

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
      childBalancers.add(childBalancer);
      barLbCreated++;
      return childBalancer;
    }
  };

  private final WeightedPickerFactory pickerFactory = new WeightedPickerFactory() {
    // Pick from the child picker with the given weight: weightToPick.
    @Override
    public SubchannelPicker picker(final List<WeightedChildPicker> childPickers) {
      childPickerWeights = new ArrayList<>(childPickers.size());
      for (WeightedChildPicker weightedChildPicker : childPickers) {
        childPickerWeights.add(weightedChildPicker.weight);
      }
      return new SubchannelPicker() {
        @Override
        public PickResult pickSubchannel(PickSubchannelArgs args) {
          for (WeightedChildPicker weightedChildPicker : childPickers) {
            if (weightedChildPicker.weight == weightToPick) {
              return weightedChildPicker.getPicker().pickSubchannel(args);
            }
          }
          throw new AssertionError("There is no picker with expected weight: " + weightToPick);
        }
      };
    }
  };

  @Mock
  private Helper helper;
  @Mock
  private ChannelLogger channelLogger;

  private LoadBalancer weightedTargetLb;
  private int fooLbCreated;
  private int barLbCreated;
  private List<Integer> childPickerWeights;
  private int weightToPick;

  @Before
  public void setUp() {
    MockitoAnnotations.initMocks(this);

    doReturn(channelLogger).when(helper).getChannelLogger();
    lbRegistry.register(fooLbProvider);
    lbRegistry.register(barLbProvider);

    weightedTargetLb = new WeightedTargetLoadBalancer(
        helper,
        lbRegistry,
        pickerFactory);
  }

  @After
  public void tearDown() {
    weightedTargetLb.shutdown();
    for (LoadBalancer childBalancer : childBalancers) {
      verify(childBalancer).shutdown();
    }
  }

  @Test
  public void handleResolvedAddresses() {
    ArgumentCaptor<ResolvedAddresses> resolvedAddressesCaptor = ArgumentCaptor.forClass(null);
    Attributes.Key<Object> fakeKey = Attributes.Key.create("fake_key");
    Object fakeValue = new Object();

    Map<String, WeightedChildLbConfig> targets = ImmutableMap.of(
        // {foo, 10, config0}
        "target0", weightedLbConfig0,
        // {bar, 20, config1}
        "target1", weightedLbConfig1,
        // {bar, 30, config2}
        "target2", weightedLbConfig2,
        // {foo, 40, config3}
        "target3", weightedLbConfig3);
    weightedTargetLb.handleResolvedAddresses(
        ResolvedAddresses.newBuilder()
            .setAddresses(ImmutableList.<EquivalentAddressGroup>of())
            .setAttributes(Attributes.newBuilder().set(fakeKey, fakeValue).build())
            .setLoadBalancingPolicyConfig(new WeightedTargetConfig(targets))
            .build());

    assertThat(childBalancers).hasSize(4);
    assertThat(childHelpers).hasSize(4);
    assertThat(fooLbCreated).isEqualTo(2);
    assertThat(barLbCreated).isEqualTo(2);

    for (int i = 0; i < childBalancers.size(); i++) {
      verify(childBalancers.get(i)).handleResolvedAddresses(resolvedAddressesCaptor.capture());
      assertThat(resolvedAddressesCaptor.getValue().getLoadBalancingPolicyConfig())
          .isEqualTo(configs[i]);
      assertThat(resolvedAddressesCaptor.getValue().getAttributes().get(fakeKey))
          .isEqualTo(fakeValue);
    }

    // Update new weighted target config for a typical workflow.
    // target0 removed. target1, target2, target3 changed weight and config. target4 added.
    int[] newWeights = new int[]{11, 22, 33, 44};
    Object[] newConfigs = new Object[]{"newConfig1", "newConfig2", "newConfig3", "newConfig4"};
    Map<String, WeightedChildLbConfig> newTargets = ImmutableMap.of(
        "target1", new WeightedChildLbConfig(newWeights[0], "bar_policy", newConfigs[0]),
        "target2", new WeightedChildLbConfig(newWeights[1], "bar_policy", newConfigs[1]),
        "target3", new WeightedChildLbConfig(newWeights[2], "foo_policy", newConfigs[2]),
        "target4", new WeightedChildLbConfig(newWeights[3], "foo_policy", newConfigs[3]));
    weightedTargetLb.handleResolvedAddresses(
        ResolvedAddresses.newBuilder()
            .setAddresses(ImmutableList.<EquivalentAddressGroup>of())
            .setLoadBalancingPolicyConfig(new WeightedTargetConfig(newTargets))
            .build());

    assertThat(childBalancers).hasSize(5);
    assertThat(childHelpers).hasSize(5);
    assertThat(fooLbCreated).isEqualTo(3); // One more foo LB created for target4
    assertThat(barLbCreated).isEqualTo(2);

    verify(childBalancers.get(0)).shutdown();
    for (int i = 1; i < childBalancers.size(); i++) {
      verify(childBalancers.get(i), atLeastOnce())
          .handleResolvedAddresses(resolvedAddressesCaptor.capture());
      assertThat(resolvedAddressesCaptor.getValue().getLoadBalancingPolicyConfig())
          .isEqualTo(newConfigs[i - 1]);
    }
  }

  @Test
  public void handleNameResolutionError() {
    ArgumentCaptor<SubchannelPicker> pickerCaptor = ArgumentCaptor.forClass(null);
    ArgumentCaptor<Status> statusCaptor = ArgumentCaptor.forClass(null);

    // Error before any child balancer created.
    weightedTargetLb.handleNameResolutionError(Status.DATA_LOSS);

    verify(helper).updateBalancingState(eq(TRANSIENT_FAILURE), pickerCaptor.capture());
    PickResult pickResult = pickerCaptor.getValue().pickSubchannel(mock(PickSubchannelArgs.class));
    assertThat(pickResult.getStatus().getCode()).isEqualTo(Status.Code.DATA_LOSS);

    // Child configs updated.
    Map<String, WeightedChildLbConfig> targets = ImmutableMap.of(
        // {foo, 10, config0}
        "target0", weightedLbConfig0,
        // {bar, 20, config1}
        "target1", weightedLbConfig1,
        // {bar, 30, config2}
        "target2", weightedLbConfig2,
        // {foo, 40, config3}
        "target3", weightedLbConfig3);
    weightedTargetLb.handleResolvedAddresses(
        ResolvedAddresses.newBuilder()
            .setAddresses(ImmutableList.<EquivalentAddressGroup>of())
            .setLoadBalancingPolicyConfig(new WeightedTargetConfig(targets))
            .build());

    // Error after child balancers created.
    weightedTargetLb.handleNameResolutionError(Status.ABORTED);

    for (LoadBalancer childBalancer : childBalancers) {
      verify(childBalancer).handleNameResolutionError(statusCaptor.capture());
      assertThat(statusCaptor.getValue().getCode()).isEqualTo(Status.Code.ABORTED);
    }
  }

  @Test
  public void balancingStateUpdatedFromChildBalancers() {
    Map<String, WeightedChildLbConfig> targets = ImmutableMap.of(
        // {foo, 10, config0}
        "target0", weightedLbConfig0,
        // {bar, 20, config1}
        "target1", weightedLbConfig1,
        // {bar, 30, config2}
        "target2", weightedLbConfig2,
        // {foo, 40, config3}
        "target3", weightedLbConfig3);
    weightedTargetLb.handleResolvedAddresses(
        ResolvedAddresses.newBuilder()
            .setAddresses(ImmutableList.<EquivalentAddressGroup>of())
            .setLoadBalancingPolicyConfig(new WeightedTargetConfig(targets))
            .build());

    // Subchannels to be created for each child balancer.
    final Subchannel[] subchannels = new Subchannel[]{
        mock(Subchannel.class),
        mock(Subchannel.class),
        mock(Subchannel.class),
        mock(Subchannel.class)};
    ArgumentCaptor<SubchannelPicker> pickerCaptor = ArgumentCaptor.forClass(null);

    // One child balancer goes to TRANSIENT_FAILURE.
    childHelpers.get(1).updateBalancingState(TRANSIENT_FAILURE, new ErrorPicker(Status.ABORTED));
    verify(helper, never()).updateBalancingState(
        eq(TRANSIENT_FAILURE), any(SubchannelPicker.class));
    verify(helper).updateBalancingState(eq(CONNECTING), eq(BUFFER_PICKER));

    // Another child balancer goes to READY.
    SubchannelPicker subchannelPicker = new SubchannelPicker() {
      @Override
      public PickResult pickSubchannel(PickSubchannelArgs args) {
        return PickResult.withSubchannel(subchannels[2]);
      }
    };
    childHelpers.get(2).updateBalancingState(READY, subchannelPicker);
    verify(helper).updateBalancingState(eq(READY), pickerCaptor.capture());
    assertThat(childPickerWeights).containsExactly(weights[2]);
    weightToPick = weights[2];
    assertThat(pickerCaptor.getValue().pickSubchannel(mock(PickSubchannelArgs.class))
        .getSubchannel())
        .isEqualTo(subchannels[2]);

    // Another child balancer goes to READY.
    subchannelPicker = new SubchannelPicker() {
      @Override
      public PickResult pickSubchannel(PickSubchannelArgs args) {
        return PickResult.withSubchannel(subchannels[3]);
      }
    };
    childHelpers.get(3).updateBalancingState(READY, subchannelPicker);
    verify(helper, times(2)).updateBalancingState(eq(READY), pickerCaptor.capture());
    assertThat(childPickerWeights).containsExactly(weights[2], weights[3]);
    for (int i : new int[]{2, 3}) {
      weightToPick = weights[i];
      assertThat(pickerCaptor.getValue().pickSubchannel(mock(PickSubchannelArgs.class))
          .getSubchannel())
          .isEqualTo(subchannels[i]);
    }

    // Another child balancer goes to READY.
    subchannelPicker = new SubchannelPicker() {
      @Override
      public PickResult pickSubchannel(PickSubchannelArgs args) {
        return PickResult.withSubchannel(subchannels[0]);
      }
    };
    childHelpers.get(0).updateBalancingState(READY, subchannelPicker);
    verify(helper, times(3)).updateBalancingState(eq(READY), pickerCaptor.capture());
    assertThat(childPickerWeights).containsExactly(weights[0], weights[2], weights[3]);
    for (int i : new int[]{0, 2, 3}) {
      weightToPick = weights[i];
      assertThat(pickerCaptor.getValue().pickSubchannel(mock(PickSubchannelArgs.class))
          .getSubchannel())
          .isEqualTo(subchannels[i]);
    }

    // One of READY child balancers goes to TRANSIENT_FAILURE.
    childHelpers.get(2).updateBalancingState(TRANSIENT_FAILURE, new ErrorPicker(Status.DATA_LOSS));
    verify(helper, times(4)).updateBalancingState(eq(READY), pickerCaptor.capture());
    assertThat(childPickerWeights).containsExactly(weights[0], weights[3]);
    for (int i : new int[]{0, 3}) {
      weightToPick = weights[i];
      assertThat(pickerCaptor.getValue().pickSubchannel(mock(PickSubchannelArgs.class))
          .getSubchannel())
          .isEqualTo(subchannels[i]);
    }

    // All child balancers go to TRANSIENT_FAILURE.
    childHelpers.get(3).updateBalancingState(TRANSIENT_FAILURE, new ErrorPicker(Status.DATA_LOSS));
    childHelpers.get(0).updateBalancingState(TRANSIENT_FAILURE, new ErrorPicker(Status.CANCELLED));
    verify(helper).updateBalancingState(eq(TRANSIENT_FAILURE), any(SubchannelPicker.class));
  }
}
