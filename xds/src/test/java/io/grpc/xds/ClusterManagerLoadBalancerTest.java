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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableMap;
import io.grpc.CallOptions;
import io.grpc.ConnectivityState;
import io.grpc.EquivalentAddressGroup;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancer.Helper;
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
import io.grpc.SynchronizationContext;
import io.grpc.internal.FakeClock;
import io.grpc.internal.PickSubchannelArgsImpl;
import io.grpc.internal.ServiceConfigUtil.PolicySelection;
import io.grpc.testing.TestMethodDescriptors;
import io.grpc.xds.ClusterManagerLoadBalancerProvider.ClusterManagerConfig;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/** Tests for {@link ClusterManagerLoadBalancer}. */
@RunWith(JUnit4.class)
public class ClusterManagerLoadBalancerTest {

  private final SynchronizationContext syncContext = new SynchronizationContext(
      new Thread.UncaughtExceptionHandler() {
        @Override
        public void uncaughtException(Thread t, Throwable e) {
          throw new AssertionError(e);
        }
      });
  private final FakeClock fakeClock = new FakeClock();

  @Mock
  private LoadBalancer.Helper helper;

  private List<FakeLoadBalancer> childBalancers = new ArrayList<>();
  private LoadBalancer clusterManagerLoadBalancer;

  @Before
  public void setUp() {
    MockitoAnnotations.initMocks(this);
    when(helper.getSynchronizationContext()).thenReturn(syncContext);
    when(helper.getScheduledExecutorService()).thenReturn(fakeClock.getScheduledExecutorService());
    clusterManagerLoadBalancer = new ClusterManagerLoadBalancer(helper);
  }

  @Test
  public void typicalWorkflow() {
    Object childConfig1 = new Object();
    Object childConfig2 = new Object();
    PolicySelection policyA =
        new PolicySelection(new FakeLoadBalancerProvider("policy_a"), null, childConfig1);
    PolicySelection policyB =
        new PolicySelection(new FakeLoadBalancerProvider("policy_b"), null, childConfig2);
    PolicySelection policyC =
        new PolicySelection(new FakeLoadBalancerProvider("policy_c"), null , null);
    Map<String, PolicySelection> childPolicies =
        ImmutableMap.of("childA", policyA, "childB", policyB);
    clusterManagerLoadBalancer
        .handleResolvedAddresses(
            ResolvedAddresses.newBuilder()
                .setAddresses(Collections.<EquivalentAddressGroup>emptyList())
                .setLoadBalancingPolicyConfig(new ClusterManagerConfig(childPolicies))
                .build());

    assertThat(childBalancers).hasSize(2);
    FakeLoadBalancer childBalancer1 = childBalancers.get(0);
    FakeLoadBalancer childBalancer2 = childBalancers.get(1);
    assertThat(childBalancer1.name).isEqualTo("policy_a");
    assertThat(childBalancer2.name).isEqualTo("policy_b");
    assertThat(childBalancer1.config).isEqualTo(childConfig1);
    assertThat(childBalancer2.config).isEqualTo(childConfig2);

    // Receive an updated config.
    childPolicies = ImmutableMap.of("childA", policyA, "childC", policyC);
    clusterManagerLoadBalancer
        .handleResolvedAddresses(
            ResolvedAddresses.newBuilder()
                .setAddresses(Collections.<EquivalentAddressGroup>emptyList())
                .setLoadBalancingPolicyConfig(new ClusterManagerConfig(childPolicies))
                .build());

    assertThat(fakeClock.numPendingTasks())
        .isEqualTo(1);  // (delayed) shutdown because "childB" is removed
    assertThat(childBalancer1.shutdown).isFalse();
    assertThat(childBalancer2.shutdown).isFalse();

    assertThat(childBalancers).hasSize(3);
    FakeLoadBalancer childBalancer3 = childBalancers.get(2);
    assertThat(childBalancer3.name).isEqualTo("policy_c");

    // Simulate subchannel state update from the leaf policy.
    Subchannel subchannel1 = mock(Subchannel.class);
    Subchannel subchannel2 = mock(Subchannel.class);
    childBalancer1.deliverSubchannelState(subchannel1, ConnectivityState.READY);
    childBalancer2.deliverSubchannelState(subchannel2, ConnectivityState.READY);

    ArgumentCaptor<SubchannelPicker> pickerCaptor = ArgumentCaptor.forClass(null);
    verify(helper).updateBalancingState(eq(ConnectivityState.READY), pickerCaptor.capture());
    SubchannelPicker picker = pickerCaptor.getValue();

    assertThat(pickSubchannel(picker, "childA").getSubchannel()).isEqualTo(subchannel1);
    Status status = pickSubchannel(picker, "childB").getStatus();
    assertThat(status.getCode()).isEqualTo(Status.Code.INTERNAL);
    assertThat(status.getDescription()).isEqualTo("Unable to find cluster childB");
    assertThat(pickSubchannel(picker, "childC")).isEqualTo(PickResult.withNoResult());

    // Error propagation from upstream policies.
    Status error = Status.UNAVAILABLE.withDescription("network error");
    clusterManagerLoadBalancer.handleNameResolutionError(error);
    assertThat(childBalancer1.upstreamError).isEqualTo(error);
    assertThat(childBalancer2.upstreamError).isNull();
    assertThat(childBalancer3.upstreamError).isEqualTo(error);
    fakeClock.forwardTime(
        ClusterManagerLoadBalancer.DELAYED_ACTION_DELETION_TIME_MINUTES, TimeUnit.MINUTES);
    assertThat(childBalancer2.shutdown).isTrue();

    clusterManagerLoadBalancer.shutdown();
    assertThat(childBalancer1.shutdown).isTrue();
    assertThat(childBalancer3.shutdown).isTrue();
  }

  private static PickResult pickSubchannel(SubchannelPicker picker, String name) {
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
                ClusterManagerLoadBalancer.ROUTING_CLUSTER_NAME_KEY, name));
    return picker.pickSubchannel(args);
  }

  private final class FakeLoadBalancerProvider extends LoadBalancerProvider {
    private final String policyName;

    FakeLoadBalancerProvider(String policyName) {
      this.policyName = policyName;
    }

    @Override
    public LoadBalancer newLoadBalancer(Helper helper) {
      FakeLoadBalancer balancer = new FakeLoadBalancer(policyName, helper);
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
    private Object config;
    private Status upstreamError;
    private boolean shutdown;

    FakeLoadBalancer(String name, Helper helper) {
      this.name = name;
      this.helper = helper;
    }

    @Override
    public void handleResolvedAddresses(ResolvedAddresses resolvedAddresses) {
      config = resolvedAddresses.getLoadBalancingPolicyConfig();
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