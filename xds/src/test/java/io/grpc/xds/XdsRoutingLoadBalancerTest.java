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
import io.grpc.xds.RouteMatch.HeaderMatcher;
import io.grpc.xds.RouteMatch.PathMatcher;
import io.grpc.xds.XdsRoutingLoadBalancer.RouteMatchingSubchannelPicker;
import io.grpc.xds.XdsRoutingLoadBalancerProvider.Route;
import io.grpc.xds.XdsRoutingLoadBalancerProvider.XdsRoutingConfig;
import java.util.ArrayList;
import java.util.Arrays;
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

/** Tests for {@link XdsRoutingLoadBalancer}. */
@RunWith(JUnit4.class)
public class XdsRoutingLoadBalancerTest {

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

  private  RouteMatch routeMatch1 =
      new RouteMatch(
          new PathMatcher("/FooService/barMethod", null, null),
          Arrays.asList(
              new HeaderMatcher("user-agent", "gRPC-Java", null, null, null, null, null, false),
              new HeaderMatcher("grpc-encoding", "gzip", null, null, null, null, null, false)),
          null);
  private RouteMatch routeMatch2 =
      new RouteMatch(
          new PathMatcher("/FooService/bazMethod", null, null),
          Collections.<HeaderMatcher>emptyList(),
          null);
  private RouteMatch routeMatch3 =
      new RouteMatch(
          new PathMatcher(null, "/", null),
          Collections.<HeaderMatcher>emptyList(),
          null);
  private List<FakeLoadBalancer> childBalancers = new ArrayList<>();
  private LoadBalancer xdsRoutingLoadBalancer;

  @Before
  public void setUp() {
    MockitoAnnotations.initMocks(this);
    when(helper.getSynchronizationContext()).thenReturn(syncContext);
    when(helper.getScheduledExecutorService()).thenReturn(fakeClock.getScheduledExecutorService());
    xdsRoutingLoadBalancer = new XdsRoutingLoadBalancer(helper);
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

    XdsRoutingConfig config =
        new XdsRoutingConfig(
            Arrays.asList(
                new Route(routeMatch1, "action_a"),
                new Route(routeMatch2, "action_b"),
                new Route(routeMatch3, "action_a")),
            ImmutableMap.of("action_a", policyA, "action_b", policyB));
    xdsRoutingLoadBalancer
        .handleResolvedAddresses(
            ResolvedAddresses.newBuilder()
                .setAddresses(Collections.<EquivalentAddressGroup>emptyList())
                .setLoadBalancingPolicyConfig(config)
                .build());

    assertThat(childBalancers).hasSize(2);
    FakeLoadBalancer childBalancer1 = childBalancers.get(0);
    FakeLoadBalancer childBalancer2 = childBalancers.get(1);
    assertThat(childBalancer1.name).isEqualTo("policy_a");
    assertThat(childBalancer2.name).isEqualTo("policy_b");
    assertThat(childBalancer1.config).isEqualTo(childConfig1);
    assertThat(childBalancer2.config).isEqualTo(childConfig2);

    // Receive an updated routing config.
    config =
        new XdsRoutingConfig(
            Arrays.asList(
                new Route(routeMatch1, "action_b"),
                new Route(routeMatch2, "action_c"),
                new Route(routeMatch3, "action_c")),
            ImmutableMap.of("action_b", policyA, "action_c", policyC));
    xdsRoutingLoadBalancer
        .handleResolvedAddresses(
            ResolvedAddresses.newBuilder()
                .setAddresses(Collections.<EquivalentAddressGroup>emptyList())
                .setLoadBalancingPolicyConfig(config)
                .build());

    assertThat(childBalancer2.shutdown)
        .isTrue();  // (immediate) shutdown because "action_b" changes policy (before ready)
    assertThat(fakeClock.numPendingTasks())
        .isEqualTo(1);  // (delayed) shutdown because "action_a" is removed
    assertThat(childBalancer1.shutdown).isFalse();
    assertThat(childBalancers).hasSize(3);
    FakeLoadBalancer childBalancer3 = childBalancers.get(1);
    FakeLoadBalancer childBalancer4 = childBalancers.get(2);
    assertThat(childBalancer3.name).isEqualTo("policy_a");
    assertThat(childBalancer3).isNotSameInstanceAs(childBalancer1);
    assertThat(childBalancer4.name).isEqualTo("policy_c");

    // Simulate subchannel state update from the leaf policy.
    Subchannel subchannel1 = mock(Subchannel.class);
    Subchannel subchannel2 = mock(Subchannel.class);
    Subchannel subchannel3 = mock(Subchannel.class);
    childBalancer1.deliverSubchannelState(subchannel1, ConnectivityState.READY);
    childBalancer3.deliverSubchannelState(subchannel2, ConnectivityState.CONNECTING);
    childBalancer4.deliverSubchannelState(subchannel3, ConnectivityState.READY);

    ArgumentCaptor<SubchannelPicker> pickerCaptor = ArgumentCaptor.forClass(null);
    verify(helper).updateBalancingState(eq(ConnectivityState.READY), pickerCaptor.capture());
    RouteMatchingSubchannelPicker picker = (RouteMatchingSubchannelPicker) pickerCaptor.getValue();
    assertThat(picker.routePickers).hasSize(3);
    assertThat(
        picker.routePickers.get(routeMatch1)
            .pickSubchannel(mock(PickSubchannelArgs.class)).getSubchannel())
        .isSameInstanceAs(subchannel2);  // routeMatch1 -> action_b -> policy_a -> subchannel2
    assertThat(
        picker.routePickers.get(routeMatch2)
            .pickSubchannel(mock(PickSubchannelArgs.class)).getSubchannel())
        .isSameInstanceAs(subchannel3);  // routeMatch2 -> action_c -> policy_c -> subchannel3
    assertThat(
        picker.routePickers.get(routeMatch3)
            .pickSubchannel(mock(PickSubchannelArgs.class)).getSubchannel())
        .isSameInstanceAs(subchannel3);  // routeMatch3 -> action_c -> policy_c -> subchannel3

    // Error propagation from upstream policies.
    Status error = Status.UNAVAILABLE.withDescription("network error");
    xdsRoutingLoadBalancer.handleNameResolutionError(error);
    assertThat(childBalancer1.upstreamError).isNull();
    assertThat(childBalancer3.upstreamError).isEqualTo(error);
    assertThat(childBalancer4.upstreamError).isEqualTo(error);
    fakeClock.forwardTime(
        XdsRoutingLoadBalancer.DELAYED_ACTION_DELETION_TIME_MINUTES, TimeUnit.MINUTES);
    assertThat(childBalancer1.shutdown).isTrue();

    xdsRoutingLoadBalancer.shutdown();
    assertThat(childBalancer3.shutdown).isTrue();
    assertThat(childBalancer4.shutdown).isTrue();
  }

  @Test
  public void routeMatchingSubchannelPicker_typicalRouting() {
    Subchannel subchannel1 = mock(Subchannel.class);
    Subchannel subchannel2 = mock(Subchannel.class);
    Subchannel subchannel3 = mock(Subchannel.class);
    RouteMatchingSubchannelPicker routeMatchingPicker =
        new RouteMatchingSubchannelPicker(
            ImmutableMap.of(
                routeMatch1, pickerOf(subchannel1),
                routeMatch2, pickerOf(subchannel2),
                routeMatch3, pickerOf(subchannel3)));

    PickSubchannelArgs args1 =
        createPickSubchannelArgs(
            "FooService", "barMethod",
            ImmutableMap.of("user-agent", "gRPC-Java", "grpc-encoding", "gzip"));
    assertThat(routeMatchingPicker.pickSubchannel(args1).getSubchannel())
        .isSameInstanceAs(subchannel1);

    PickSubchannelArgs args2 =
        createPickSubchannelArgs(
            "FooService", "bazMethod",
            ImmutableMap.of("user-agent", "gRPC-Java", "custom-key", "custom-value"));
    assertThat(routeMatchingPicker.pickSubchannel(args2).getSubchannel())
        .isSameInstanceAs(subchannel2);

    PickSubchannelArgs args3 =
        createPickSubchannelArgs(
            "FooService", "barMethod",
            ImmutableMap.of("user-agent", "gRPC-Java", "custom-key", "custom-value"));
    assertThat(routeMatchingPicker.pickSubchannel(args3).getSubchannel())
        .isSameInstanceAs(subchannel3);

    PickSubchannelArgs args4 =
        createPickSubchannelArgs(
            "BazService", "fooMethod",
            Collections.<String, String>emptyMap());
    assertThat(routeMatchingPicker.pickSubchannel(args4).getSubchannel())
        .isSameInstanceAs(subchannel3);
  }

  private static SubchannelPicker pickerOf(final Subchannel subchannel) {
    return new SubchannelPicker() {
      @Override
      public PickResult pickSubchannel(PickSubchannelArgs args) {
        return PickResult.withSubchannel(subchannel);
      }
    };
  }

  private static PickSubchannelArgs createPickSubchannelArgs(
      String service, String method, Map<String, String> headers) {
    MethodDescriptor<Void, Void> methodDescriptor =
        MethodDescriptor.<Void, Void>newBuilder()
            .setType(MethodType.UNARY).setFullMethodName(service + "/" + method)
            .setRequestMarshaller(TestMethodDescriptors.voidMarshaller())
            .setResponseMarshaller(TestMethodDescriptors.voidMarshaller())
            .build();
    Metadata metadata = new Metadata();
    for (Map.Entry<String, String> entry : headers.entrySet()) {
      metadata.put(
          Metadata.Key.of(entry.getKey(), Metadata.ASCII_STRING_MARSHALLER), entry.getValue());
    }
    return new PickSubchannelArgsImpl(methodDescriptor, metadata, CallOptions.DEFAULT);
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
