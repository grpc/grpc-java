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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
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
import io.grpc.Status.Code;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
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
  @Captor
  ArgumentCaptor<SubchannelPicker> pickerCaptor;

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
  private final Map<String, Object> lbConfigInventory = new HashMap<>();
  private final List<FakeLoadBalancer> childBalancers = new ArrayList<>();
  private LoadBalancer xdsRoutingLoadBalancer;

  @Before
  public void setUp() {
    MockitoAnnotations.initMocks(this);
    when(helper.getSynchronizationContext()).thenReturn(syncContext);
    when(helper.getScheduledExecutorService()).thenReturn(fakeClock.getScheduledExecutorService());
    lbConfigInventory.put("actionA", new Object());
    lbConfigInventory.put("actionB", new Object());
    lbConfigInventory.put("actionC", null);
    xdsRoutingLoadBalancer = new XdsRoutingLoadBalancer(helper);
  }

  @After
  public void tearDown() {
    xdsRoutingLoadBalancer.shutdown();
    for (FakeLoadBalancer childLb : childBalancers) {
      assertThat(childLb.shutdown).isTrue();
    }
  }

  @Test
  public void handleResolvedAddressesUpdatesChannelPicker() {
    deliverResolvedAddresses(
        ImmutableMap.of(
            new Route(routeMatch1, "actionA"), "policy_a",
            new Route(routeMatch2, "actionB"), "policy_b"));

    verify(helper, atLeastOnce()).updateBalancingState(
        eq(ConnectivityState.CONNECTING), pickerCaptor.capture());
    RouteMatchingSubchannelPicker picker = (RouteMatchingSubchannelPicker) pickerCaptor.getValue();
    assertThat(picker.routePickers).hasSize(2);
    assertThat(picker.routePickers.get(routeMatch1).pickSubchannel(mock(PickSubchannelArgs.class)))
        .isEqualTo(PickResult.withNoResult());
    assertThat(picker.routePickers.get(routeMatch2).pickSubchannel(mock(PickSubchannelArgs.class)))
        .isEqualTo(PickResult.withNoResult());
    assertThat(childBalancers).hasSize(2);
    FakeLoadBalancer childBalancer1 = childBalancers.get(0);
    FakeLoadBalancer childBalancer2 = childBalancers.get(1);
    assertThat(childBalancer1.name).isEqualTo("policy_a");
    assertThat(childBalancer2.name).isEqualTo("policy_b");
    assertThat(childBalancer1.config).isEqualTo(lbConfigInventory.get("actionA"));
    assertThat(childBalancer2.config).isEqualTo(lbConfigInventory.get("actionB"));

    // Receive an updated config.
    deliverResolvedAddresses(
        ImmutableMap.of(
            new Route(routeMatch1, "actionA"), "policy_a",
            new Route(routeMatch3, "actionC"), "policy_c"));

    verify(helper, atLeast(2))
        .updateBalancingState(eq(ConnectivityState.CONNECTING), pickerCaptor.capture());
    picker = (RouteMatchingSubchannelPicker) pickerCaptor.getValue();
    assertThat(picker.routePickers).hasSize(2);
    assertThat(picker.routePickers).doesNotContainKey(routeMatch2);
    assertThat(picker.routePickers.get(routeMatch3).pickSubchannel(mock(PickSubchannelArgs.class)))
        .isEqualTo(PickResult.withNoResult());
    assertThat(fakeClock.numPendingTasks())
        .isEqualTo(1);  // (delayed) shutdown because "actionB" is removed
    assertThat(childBalancer1.shutdown).isFalse();
    assertThat(childBalancer2.shutdown).isFalse();

    assertThat(childBalancers).hasSize(3);
    FakeLoadBalancer childBalancer3 = childBalancers.get(2);
    assertThat(childBalancer3.name).isEqualTo("policy_c");
    assertThat(childBalancer3.config).isEqualTo(lbConfigInventory.get("actionC"));

    fakeClock.forwardTime(
        XdsRoutingLoadBalancer.DELAYED_ACTION_DELETION_TIME_MINUTES, TimeUnit.MINUTES);
    assertThat(childBalancer2.shutdown).isTrue();
  }

  @Test
  public void updateWithActionPolicyChange() {
    deliverResolvedAddresses(ImmutableMap.of(new Route(routeMatch1, "actionA"), "policy_a"));
    FakeLoadBalancer childBalancer = Iterables.getOnlyElement(childBalancers);
    assertThat(childBalancer.name).isEqualTo("policy_a");
    assertThat(childBalancer.config).isEqualTo(lbConfigInventory.get("actionA"));

    deliverResolvedAddresses(ImmutableMap.of(new Route(routeMatch1, "actionA"), "policy_b"));
    assertThat(childBalancer.shutdown).isTrue();  // immediate shutdown as the it was not ready
    assertThat(Iterables.getOnlyElement(childBalancers).name).isEqualTo("policy_b");
    assertThat(Iterables.getOnlyElement(childBalancers).config)
        .isEqualTo(lbConfigInventory.get("actionA"));
  }

  @Test
  public void updateBalancingStateFromChildBalancers() {
    deliverResolvedAddresses(
        ImmutableMap.of(
            new Route(routeMatch1, "actionA"), "policy_a",
            new Route(routeMatch2, "actionB"), "policy_b"));

    assertThat(childBalancers).hasSize(2);
    FakeLoadBalancer childBalancer1 = childBalancers.get(0);
    FakeLoadBalancer childBalancer2 = childBalancers.get(1);
    Subchannel subchannel1 = mock(Subchannel.class);
    Subchannel subchannel2 = mock(Subchannel.class);
    childBalancer1.deliverSubchannelState(subchannel1, ConnectivityState.READY);

    verify(helper).updateBalancingState(eq(ConnectivityState.READY), pickerCaptor.capture());
    RouteMatchingSubchannelPicker picker = (RouteMatchingSubchannelPicker) pickerCaptor.getValue();
    assertThat(picker.routePickers).hasSize(2);
    assertThat(
        picker.routePickers.get(routeMatch1)
            .pickSubchannel(mock(PickSubchannelArgs.class)).getSubchannel())
        .isEqualTo(subchannel1);
    assertThat(picker.routePickers.get(routeMatch2).pickSubchannel(mock(PickSubchannelArgs.class)))
        .isEqualTo(PickResult.withNoResult());

    childBalancer2.deliverSubchannelState(subchannel2, ConnectivityState.READY);
    verify(helper, times(2))
        .updateBalancingState(eq(ConnectivityState.READY), pickerCaptor.capture());
    picker = (RouteMatchingSubchannelPicker) pickerCaptor.getValue();
    assertThat(
        picker.routePickers.get(routeMatch2)
            .pickSubchannel(mock(PickSubchannelArgs.class)).getSubchannel())
        .isEqualTo(subchannel2);
  }

  @Test
  public void updateBalancingStateFromDeactivatedChildBalancer() {
    FakeLoadBalancer balancer =
        deliverAddressesAndUpdateToRemoveChildPolicy(
            new Route(routeMatch1, "actionA"), "policy_a");
    Subchannel subchannel = mock(Subchannel.class);
    balancer.deliverSubchannelState(subchannel, ConnectivityState.READY);
    verify(helper, never()).updateBalancingState(
        eq(ConnectivityState.READY), any(SubchannelPicker.class));

    deliverResolvedAddresses(ImmutableMap.of(new Route(routeMatch1, "actionA"), "policy_a"));
    verify(helper).updateBalancingState(eq(ConnectivityState.READY), pickerCaptor.capture());
    RouteMatchingSubchannelPicker picker = (RouteMatchingSubchannelPicker) pickerCaptor.getValue();
    assertThat(
        picker.routePickers.get(routeMatch1)
            .pickSubchannel(mock(PickSubchannelArgs.class)).getSubchannel())
        .isEqualTo(subchannel);
  }

  @Test
  public void errorPropagation() {
    Status error = Status.UNAVAILABLE.withDescription("resolver error");
    xdsRoutingLoadBalancer.handleNameResolutionError(error);
    verify(helper).updateBalancingState(
        eq(ConnectivityState.TRANSIENT_FAILURE), pickerCaptor.capture());
    PickResult result = pickerCaptor.getValue().pickSubchannel(mock(PickSubchannelArgs.class));
    assertThat(result.getStatus().getCode()).isEqualTo(Code.UNAVAILABLE);
    assertThat(result.getStatus().getDescription()).isEqualTo("resolver error");

    deliverResolvedAddresses(
        ImmutableMap.of(
            new Route(routeMatch1, "actionA"), "policy_a",
            new Route(routeMatch2, "actionB"), "policy_b"));

    assertThat(childBalancers).hasSize(2);
    FakeLoadBalancer childBalancer1 = childBalancers.get(0);
    FakeLoadBalancer childBalancer2 = childBalancers.get(1);

    xdsRoutingLoadBalancer.handleNameResolutionError(error);
    assertThat(childBalancer1.upstreamError.getCode()).isEqualTo(Code.UNAVAILABLE);
    assertThat(childBalancer1.upstreamError.getDescription()).isEqualTo("resolver error");
    assertThat(childBalancer2.upstreamError.getCode()).isEqualTo(Code.UNAVAILABLE);
    assertThat(childBalancer2.upstreamError.getDescription()).isEqualTo("resolver error");
  }

  @Test
  public void errorPropagationToDeactivatedChildBalancer() {
    FakeLoadBalancer balancer =
        deliverAddressesAndUpdateToRemoveChildPolicy(
            new Route(routeMatch1, "actionA"), "policy_a");
    xdsRoutingLoadBalancer.handleNameResolutionError(
        Status.UNKNOWN.withDescription("unknown error"));
    assertThat(balancer.upstreamError).isNull();
  }

  private FakeLoadBalancer deliverAddressesAndUpdateToRemoveChildPolicy(
      Route route, String childPolicyName) {
    lbConfigInventory.put("actionX", null);
    Route routeX =
        new Route(
            new RouteMatch(
                new PathMatcher(
                    "/XService/xMethod", null, null),
                Collections.<HeaderMatcher>emptyList(),
                null),
            "actionX");
    deliverResolvedAddresses(
        ImmutableMap.of(route, childPolicyName, routeX, "policy_x"));

    verify(helper, atLeastOnce()).updateBalancingState(
        eq(ConnectivityState.CONNECTING), any(SubchannelPicker.class));
    assertThat(childBalancers).hasSize(2);
    FakeLoadBalancer balancer = childBalancers.get(0);

    deliverResolvedAddresses(ImmutableMap.of(routeX, "policy_x"));
    verify(helper, atLeast(2)).updateBalancingState(
        eq(ConnectivityState.CONNECTING), any(SubchannelPicker.class));
    assertThat(Iterables.getOnlyElement(fakeClock.getPendingTasks()).getDelay(TimeUnit.MINUTES))
        .isEqualTo(XdsRoutingLoadBalancer.DELAYED_ACTION_DELETION_TIME_MINUTES);
    return balancer;
  }

  private void deliverResolvedAddresses(final Map<Route, String> childPolicies) {
    syncContext.execute(new Runnable() {
      @Override
      public void run() {
        xdsRoutingLoadBalancer
            .handleResolvedAddresses(
                ResolvedAddresses.newBuilder()
                    .setAddresses(Collections.<EquivalentAddressGroup>emptyList())
                    .setLoadBalancingPolicyConfig(buildConfig(childPolicies))
                    .build());
      }
    });
  }

  private XdsRoutingConfig buildConfig(Map<Route, String> childPolicies) {
    Map<String, PolicySelection> childPolicySelections = new LinkedHashMap<>();
    List<Route> routeList = new ArrayList<>();
    for (Route route : childPolicies.keySet()) {
      String childActionName = route.getActionName();
      String childPolicyName = childPolicies.get(route);
      Object childConfig = lbConfigInventory.get(childActionName);
      PolicySelection policy =
          new PolicySelection(new FakeLoadBalancerProvider(childPolicyName), null, childConfig);
      childPolicySelections.put(childActionName, policy);
      routeList.add(route);
    }
    return new XdsRoutingConfig(routeList, childPolicySelections);
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
