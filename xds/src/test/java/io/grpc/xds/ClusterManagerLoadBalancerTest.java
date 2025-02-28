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
import static io.grpc.ConnectivityState.TRANSIENT_FAILURE;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableMap;
import io.grpc.CallOptions;
import io.grpc.ConnectivityState;
import io.grpc.EquivalentAddressGroup;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancer.PickDetailsConsumer;
import io.grpc.LoadBalancer.PickResult;
import io.grpc.LoadBalancer.PickSubchannelArgs;
import io.grpc.LoadBalancer.ResolvedAddresses;
import io.grpc.LoadBalancer.Subchannel;
import io.grpc.LoadBalancer.SubchannelPicker;
import io.grpc.LoadBalancerProvider;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.MethodDescriptor.MethodType;
import io.grpc.Status;
import io.grpc.Status.Code;
import io.grpc.SynchronizationContext;
import io.grpc.internal.FakeClock;
import io.grpc.internal.PickSubchannelArgsImpl;
import io.grpc.testing.TestMethodDescriptors;
import io.grpc.util.GracefulSwitchLoadBalancer;
import io.grpc.xds.ClusterManagerLoadBalancerProvider.ClusterManagerConfig;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/** Tests for {@link ClusterManagerLoadBalancer}. */
@RunWith(JUnit4.class)
public class ClusterManagerLoadBalancerTest {
  @Rule public final MockitoRule mocks = MockitoJUnit.rule();

  private final SynchronizationContext syncContext = new SynchronizationContext(
      new Thread.UncaughtExceptionHandler() {
        @Override
        public void uncaughtException(Thread t, Throwable e) {
          throw new AssertionError(e);
        }
      });
  private final FakeClock fakeClock = new FakeClock();

  @Captor
  ArgumentCaptor<SubchannelPicker> pickerCaptor;
  @Mock
  private LoadBalancer.Helper helper;

  private final Map<String, Object> lbConfigInventory = new HashMap<>();
  private final List<FakeLoadBalancer> childBalancers = new ArrayList<>();
  private ClusterManagerLoadBalancer clusterManagerLoadBalancer;

  @Before
  public void setUp() {
    when(helper.getSynchronizationContext()).thenReturn(syncContext);
    when(helper.getScheduledExecutorService()).thenReturn(fakeClock.getScheduledExecutorService());
    lbConfigInventory.put("childA", new Object());
    lbConfigInventory.put("childB", new Object());
    lbConfigInventory.put("childC", null);
    clusterManagerLoadBalancer = new ClusterManagerLoadBalancer(helper);
    clearInvocations(helper);
  }

  @After
  public void tearDown() {
    clusterManagerLoadBalancer.shutdown();
    for (FakeLoadBalancer childLb : childBalancers) {
      assertThat(childLb.shutdown).isTrue();
    }
  }

  @Test
  public void acceptResolvedAddressesUpdatesChannelPicker() {
    deliverResolvedAddresses(ImmutableMap.of("childA", "policy_a", "childB", "policy_b"));

    verify(helper, atLeastOnce()).updateBalancingState(
        eq(ConnectivityState.CONNECTING), pickerCaptor.capture());
    SubchannelPicker picker = pickerCaptor.getValue();
    assertThat(pickSubchannel(picker, "childA")).isEqualTo(PickResult.withNoResult());
    assertThat(pickSubchannel(picker, "childB")).isEqualTo(PickResult.withNoResult());
    assertThat(childBalancers).hasSize(2);
    assertThat(childBalancers.stream()
        .filter(b -> b.name.equals("policy_a"))
        .count()).isEqualTo(1);
    assertThat(childBalancers.stream()
        .filter(b -> b.name.equals("policy_b"))
        .count()).isEqualTo(1);
    FakeLoadBalancer childBalancer1 = getChildBalancerByName("policy_a");
    FakeLoadBalancer childBalancer2 = getChildBalancerByName("policy_b");
    assertThat(childBalancer1.config).isEqualTo(lbConfigInventory.get("childA"));
    assertThat(childBalancer2.config).isEqualTo(lbConfigInventory.get("childB"));

    // Receive an updated config.
    deliverResolvedAddresses(ImmutableMap.of("childA", "policy_a", "childC", "policy_c"));

    verify(helper, atLeast(2))
        .updateBalancingState(eq(ConnectivityState.CONNECTING), pickerCaptor.capture());
    picker = pickerCaptor.getValue();
    assertThat(pickSubchannel(picker, "childA")).isEqualTo(PickResult.withNoResult());
    assertThat(pickSubchannel(picker, "childC")).isEqualTo(PickResult.withNoResult());
    Status status = pickSubchannel(picker, "childB").getStatus();
    assertThat(status.getCode()).isEqualTo(Code.UNAVAILABLE);
    assertThat(status.getDescription()).isEqualTo(
        "CDS encountered error: unable to find available subchannel for cluster childB");
    assertThat(fakeClock.numPendingTasks())
        .isEqualTo(1);  // (delayed) shutdown because "childB" is removed
    assertThat(childBalancer1.shutdown).isFalse();
    assertThat(childBalancer2.shutdown).isFalse();

    assertThat(childBalancers).hasSize(3);
    FakeLoadBalancer childBalancer3 = getChildBalancerByName("policy_c");
    assertThat(childBalancer3.config).isEqualTo(lbConfigInventory.get("childC"));

    // delayed policy_b deletion
    fakeClock.forwardTime(
        ClusterManagerLoadBalancer.DELAYED_CHILD_DELETION_TIME_MINUTES, TimeUnit.MINUTES);
    assertThat(childBalancer2.shutdown).isTrue();
  }

  @Test
  public void updateBalancingStateFromChildBalancers() {
    deliverResolvedAddresses(ImmutableMap.of("childA", "policy_a", "childB", "policy_b"));

    assertThat(childBalancers).hasSize(2);
    FakeLoadBalancer childBalancer1 = getChildBalancerByName("policy_a");
    FakeLoadBalancer childBalancer2 = getChildBalancerByName("policy_b");
    Subchannel subchannel1 = mock(Subchannel.class);
    Subchannel subchannel2 = mock(Subchannel.class);
    childBalancer1.deliverSubchannelState(subchannel1, ConnectivityState.READY);

    verify(helper).updateBalancingState(eq(ConnectivityState.READY), pickerCaptor.capture());
    SubchannelPicker picker = pickerCaptor.getValue();
    assertThat(pickSubchannel(picker, "childA").getSubchannel()).isEqualTo(subchannel1);
    assertThat(pickSubchannel(picker, "childB")).isEqualTo(PickResult.withNoResult());

    childBalancer2.deliverSubchannelState(subchannel2, ConnectivityState.READY);
    verify(helper, times(2))
        .updateBalancingState(eq(ConnectivityState.READY), pickerCaptor.capture());
    assertThat(pickSubchannel(pickerCaptor.getValue(), "childB").getSubchannel())
        .isEqualTo(subchannel2);
  }

  private FakeLoadBalancer getChildBalancerByName(String name) {
    for (FakeLoadBalancer childLb : childBalancers) {
      if (childLb.name.equals(name)) {
        return childLb;
      }
    }
    return null;
  }

  @Test
  public void ignoreBalancingStateUpdateForDeactivatedChildLbs() {
    deliverResolvedAddresses(ImmutableMap.of("childA", "policy_a", "childB", "policy_b"));
    deliverResolvedAddresses(ImmutableMap.of("childB", "policy_b"));
    FakeLoadBalancer childBalancer1 = getChildBalancerByName("policy_a");  // policy_a (deactivated)
    Subchannel subchannel = mock(Subchannel.class);
    childBalancer1.deliverSubchannelState(subchannel, ConnectivityState.READY);
    verify(helper, never()).updateBalancingState(
        eq(ConnectivityState.READY), any(SubchannelPicker.class));

    // Reactivate policy_a, balancing state update reflects the latest connectivity state and
    // picker.
    deliverResolvedAddresses(ImmutableMap.of("childA", "policy_a", "childB", "policy_b"));
    verify(helper).updateBalancingState(eq(ConnectivityState.READY), pickerCaptor.capture());
    assertThat(pickSubchannel(pickerCaptor.getValue(), "childA").getSubchannel())
        .isEqualTo(subchannel);
  }

  @Test
  public void raceBetweenShutdownAndChildLbBalancingStateUpdate() {
    deliverResolvedAddresses(ImmutableMap.of("childA", "policy_a", "childB", "policy_b"));
    verify(helper).updateBalancingState(
        eq(ConnectivityState.CONNECTING), any(SubchannelPicker.class));
    FakeLoadBalancer childBalancer = childBalancers.iterator().next();

    // LB shutdown and subchannel state change can happen simultaneously. If shutdown runs first,
    // any further balancing state update should be ignored.
    clusterManagerLoadBalancer.shutdown();
    childBalancer.deliverSubchannelState(mock(Subchannel.class), ConnectivityState.READY);
    verifyNoMoreInteractions(helper);
  }

  @Test
  public void handleNameResolutionError_beforeChildLbsInstantiated_returnErrorPicker() {
    clusterManagerLoadBalancer.handleNameResolutionError(
        Status.UNAVAILABLE.withDescription("resolver error"));
    verify(helper).updateBalancingState(
        eq(ConnectivityState.TRANSIENT_FAILURE), pickerCaptor.capture());
    PickResult result = pickerCaptor.getValue().pickSubchannel(mock(PickSubchannelArgs.class));
    assertThat(result.getStatus().getCode()).isEqualTo(Code.UNAVAILABLE);
    assertThat(result.getStatus().getDescription()).isEqualTo("resolver error");
  }

  @Test
  public void handleNameResolutionError_afterChildLbsInstantiated_propagateToChildLbs() {
    deliverResolvedAddresses(ImmutableMap.of("childA", "policy_a", "childB", "policy_b"));
    assertThat(childBalancers).hasSize(2);
    FakeLoadBalancer childBalancer1 = getChildBalancerByName("policy_a");
    FakeLoadBalancer childBalancer2 = getChildBalancerByName("policy_b");
    clusterManagerLoadBalancer.handleNameResolutionError(
        Status.UNAVAILABLE.withDescription("resolver error"));
    assertThat(childBalancer1.upstreamError.getCode()).isEqualTo(Code.UNAVAILABLE);
    assertThat(childBalancer1.upstreamError.getDescription()).isEqualTo("resolver error");
    assertThat(childBalancer2.upstreamError.getCode()).isEqualTo(Code.UNAVAILABLE);
    assertThat(childBalancer2.upstreamError.getDescription()).isEqualTo("resolver error");
  }

  @Test
  public void handleNameResolutionError_notPropagateToDeactivatedChildLbs() {
    deliverResolvedAddresses(ImmutableMap.of("childA", "policy_a", "childB", "policy_b"));
    deliverResolvedAddresses(ImmutableMap.of("childB", "policy_b"));
    FakeLoadBalancer childBalancer1 = getChildBalancerByName("policy_a");  // policy_a (deactivated)
    FakeLoadBalancer childBalancer2 = getChildBalancerByName("policy_b");  // policy_b
    clusterManagerLoadBalancer.handleNameResolutionError(
        Status.UNKNOWN.withDescription("unknown error"));
    assertThat(childBalancer1.upstreamError).isNull();
    assertThat(childBalancer2.upstreamError.getCode()).isEqualTo(Code.UNKNOWN);
    assertThat(childBalancer2.upstreamError.getDescription()).isEqualTo("unknown error");
  }

  @Test
  public void noDuplicateOverallBalancingStateUpdate() {
    deliverResolvedAddresses(ImmutableMap.of("childA", "policy_a", "childB", "policy_b"), true);

    // The test child LBs would have triggered state updates, let's make sure the overall balancing
    // state was only updated once but that the new state reflects the state the child LB reported.
    verify(helper, times(1)).updateBalancingState(
        eq(TRANSIENT_FAILURE), isA(SubchannelPicker.class));
  }

  private void deliverResolvedAddresses(final Map<String, String> childPolicies) {
    deliverResolvedAddresses(childPolicies, false);
  }

  private void deliverResolvedAddresses(final Map<String, String> childPolicies, boolean failing) {
    clusterManagerLoadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder()
            .setAddresses(Collections.<EquivalentAddressGroup>emptyList())
            .setLoadBalancingPolicyConfig(buildConfig(childPolicies, failing))
            .build());
  }

  // Prevent ClusterManagerLB from detecting different providers even when the configuration is the
  // same.
  private Map<List<Object>, FakeLoadBalancerProvider> fakeLoadBalancerProviderCache
      = new HashMap<>();

  private ClusterManagerConfig buildConfig(Map<String, String> childPolicies, boolean failing) {
    Map<String, Object> childConfigs = new LinkedHashMap<>();
    for (String name : childPolicies.keySet()) {
      String childPolicyName = childPolicies.get(name);
      Object childConfig = lbConfigInventory.get(name);
      FakeLoadBalancerProvider lbProvider =
          fakeLoadBalancerProviderCache.get(Arrays.asList(childPolicyName, failing));
      if (lbProvider == null) {
        lbProvider = new FakeLoadBalancerProvider(childPolicyName, failing);
        fakeLoadBalancerProviderCache.put(Arrays.asList(childPolicyName, failing), lbProvider);
      }
      Object policy =
          GracefulSwitchLoadBalancer.createLoadBalancingPolicyConfig(lbProvider, childConfig);
      childConfigs.put(name, policy);
    }
    return new ClusterManagerConfig(childConfigs);
  }

  private static PickResult pickSubchannel(SubchannelPicker picker, String clusterName) {
    PickSubchannelArgs args =
        new PickSubchannelArgsImpl(
            MethodDescriptor.<Void, Void>newBuilder()
                .setType(MethodType.UNARY)
                .setFullMethodName("/service/method")
                .setRequestMarshaller(TestMethodDescriptors.voidMarshaller())
                .setResponseMarshaller(TestMethodDescriptors.voidMarshaller())
                .build(),
            new Metadata(),
            CallOptions.DEFAULT.withOption(
                XdsNameResolver.CLUSTER_SELECTION_KEY, clusterName),
            new PickDetailsConsumer() {});
    return picker.pickSubchannel(args);
  }

  private final class FakeLoadBalancerProvider extends LoadBalancerProvider {
    private final String policyName;
    private final boolean failing;

    FakeLoadBalancerProvider(String policyName, boolean failing) {
      this.policyName = policyName;
      this.failing = failing;
    }

    @Override
    public LoadBalancer newLoadBalancer(Helper helper) {
      FakeLoadBalancer balancer = new FakeLoadBalancer(policyName, helper, failing);
      childBalancers.add(balancer);
      return balancer;
    }

    @Override
    public boolean isAvailable() {
      return true;
    }

    @Override
    public int getPriority() {
      return 0;  // doesn't matter
    }

    @Override
    public String getPolicyName() {
      return policyName;
    }
  }

  private final class FakeLoadBalancer extends LoadBalancer {
    private final String name;
    private final Helper helper;
    private final boolean failing;
    private Object config;
    private Status upstreamError;
    private boolean shutdown;

    FakeLoadBalancer(String name, Helper helper, boolean failing) {
      this.name = name;
      this.helper = helper;
      this.failing = failing;
    }

    @Override
    public Status acceptResolvedAddresses(ResolvedAddresses resolvedAddresses) {
      config = resolvedAddresses.getLoadBalancingPolicyConfig();

      if (failing) {
        helper.updateBalancingState(
            TRANSIENT_FAILURE, new FixedResultPicker(PickResult.withError(Status.INTERNAL)));
      }
      return Status.OK;
    }

    @Override
    public void handleNameResolutionError(Status error) {
      upstreamError = error;
    }

    @Override
    public void shutdown() {
      shutdown = true;
      childBalancers.remove(this);
    }

    void deliverSubchannelState(final Subchannel subchannel, ConnectivityState state) {
      SubchannelPicker picker = new SubchannelPicker() {
        @Override
        public PickResult pickSubchannel(PickSubchannelArgs args) {
          return PickResult.withSubchannel(subchannel);
        }
      };
      helper.updateBalancingState(state, picker);
    }
  }
}
