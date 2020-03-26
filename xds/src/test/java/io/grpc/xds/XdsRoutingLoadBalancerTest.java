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
import static io.grpc.ConnectivityState.READY;
import static io.grpc.ConnectivityState.TRANSIENT_FAILURE;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import io.grpc.Attributes;
import io.grpc.Attributes.Key;
import io.grpc.CallOptions;
import io.grpc.ChannelLogger;
import io.grpc.ConnectivityState;
import io.grpc.EquivalentAddressGroup;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancer.PickSubchannelArgs;
import io.grpc.LoadBalancer.ResolvedAddresses;
import io.grpc.LoadBalancer.Subchannel;
import io.grpc.LoadBalancer.SubchannelPicker;
import io.grpc.LoadBalancerProvider;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.MethodDescriptor.MethodType;
import io.grpc.Status;
import io.grpc.internal.ServiceConfigUtil.PolicySelection;
import io.grpc.internal.TestUtils;
import io.grpc.internal.TestUtils.StandardLoadBalancerProvider;
import io.grpc.testing.TestMethodDescriptors;
import io.grpc.xds.XdsRoutingLoadBalancerProvider.MethodName;
import io.grpc.xds.XdsRoutingLoadBalancerProvider.Route;
import io.grpc.xds.XdsRoutingLoadBalancerProvider.XdsRoutingConfig;
import io.grpc.xds.XdsSubchannelPickers.ErrorPicker;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.After;
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

  private final List<LoadBalancer> fooBalancers = new ArrayList<>();
  private final List<LoadBalancer> barBalancers = new ArrayList<>();
  private final List<LoadBalancer> bazBalancers = new ArrayList<>();
  private final List<Helper> fooHelpers = new ArrayList<>();
  private final List<Helper> barHelpers = new ArrayList<>();
  private final List<Helper> bazHelpers = new ArrayList<>();

  private final LoadBalancerProvider fooLbProvider =
      new StandardLoadBalancerProvider("foo_policy") {
        @Override
        public LoadBalancer newLoadBalancer(Helper helper) {
          LoadBalancer lb = mock(LoadBalancer.class);
          fooBalancers.add(lb);
          fooHelpers.add(helper);
          return lb;
        }
      };
  private final LoadBalancerProvider barLbProvider =
      new StandardLoadBalancerProvider("bar_policy") {
        @Override
        public LoadBalancer newLoadBalancer(Helper helper) {
          LoadBalancer lb = mock(LoadBalancer.class);
          barBalancers.add(lb);
          barHelpers.add(helper);
          return lb;
        }
      };
  private final LoadBalancerProvider bazLbProvider =
      new StandardLoadBalancerProvider("baz_policy") {
        @Override
        public LoadBalancer newLoadBalancer(Helper helper) {
          LoadBalancer lb = mock(LoadBalancer.class);
          bazBalancers.add(lb);
          bazHelpers.add(helper);
          return lb;
        }
      };

  @Mock
  private Helper helper;
  @Mock
  private ChannelLogger channelLogger;

  private LoadBalancer xdsRoutingLb;

  @Before
  public void setUp() {
    MockitoAnnotations.initMocks(this);
    doReturn(channelLogger).when(helper).getChannelLogger();
    xdsRoutingLb = new XdsRoutingLoadBalancer(helper);
  }

  @After
  public void tearDown() {
    xdsRoutingLb.shutdown();

    for (LoadBalancer balancer : Iterables.concat(fooBalancers, barBalancers, bazBalancers)) {
      verify(balancer).shutdown();
    }
  }

  @Test
  public void typicalWorkflow() {
    // Resolution error.
    xdsRoutingLb.handleNameResolutionError(Status.UNAUTHENTICATED);
    verify(helper).updateBalancingState(eq(TRANSIENT_FAILURE), any(SubchannelPicker.class));

    // Config update.
    Attributes attributes =
        Attributes.newBuilder().set(Key.<String>create("fakeKey"), "fakeVal").build();
    Object fooConfig1 = new Object();
    Object barConfig1 = new Object();
    Object bazConfig1 = new Object();
    Object fooConfig2 = new Object();
    XdsRoutingConfig xdsRoutingConfig = new XdsRoutingConfig(
        ImmutableList.of(
            new Route("foo_action", new MethodName("service1", "method1")),
            new Route("foo_action", new MethodName("service2", "method2")),
            new Route("bar_action", new MethodName("service1", "hello")),
            new Route("bar_action", new MethodName("service2", "hello")),
            new Route("foo_action_2", new MethodName("service2", "")),
            new Route("baz_action", new MethodName("", ""))),
        ImmutableMap.of(
            "foo_action",
            new PolicySelection(fooLbProvider, null, fooConfig1),
            "foo_action_2",
            new PolicySelection(fooLbProvider, null, fooConfig2),
            "bar_action",
            new PolicySelection(barLbProvider, null, barConfig1),
            "baz_action",
            new PolicySelection(bazLbProvider, null, bazConfig1)));
    xdsRoutingLb.handleResolvedAddresses(
        ResolvedAddresses.newBuilder()
            .setAddresses(ImmutableList.<EquivalentAddressGroup>of())
            .setAttributes(attributes)
            .setLoadBalancingPolicyConfig(xdsRoutingConfig).build());
    assertThat(fooBalancers).hasSize(2);
    ArgumentCaptor<ResolvedAddresses> resolvedAddressesCaptor = ArgumentCaptor.forClass(null);
    verify(fooBalancers.get(0)).handleResolvedAddresses(resolvedAddressesCaptor.capture());
    ResolvedAddresses resolvedAddressesFoo0 = resolvedAddressesCaptor.getValue();
    verify(fooBalancers.get(1)).handleResolvedAddresses(resolvedAddressesCaptor.capture());
    ResolvedAddresses resolvedAddressesFoo1 = resolvedAddressesCaptor.getValue();
    assertThat(barBalancers).hasSize(1);
    verify(barBalancers.get(0)).handleResolvedAddresses(resolvedAddressesCaptor.capture());
    ResolvedAddresses resolvedAddressesBar = resolvedAddressesCaptor.getValue();
    assertThat(bazBalancers).hasSize(1);
    verify(bazBalancers.get(0)).handleResolvedAddresses(resolvedAddressesCaptor.capture());
    ResolvedAddresses resolvedAddressesBaz = resolvedAddressesCaptor.getValue();
    assertThat(resolvedAddressesFoo0.getAttributes()).isEqualTo(attributes);
    assertThat(resolvedAddressesFoo1.getAttributes()).isEqualTo(attributes);
    assertThat(resolvedAddressesBar.getAttributes()).isEqualTo(attributes);
    assertThat(resolvedAddressesBaz.getAttributes()).isEqualTo(attributes);
    assertThat(
        Arrays.asList(
            resolvedAddressesFoo0.getLoadBalancingPolicyConfig(),
            resolvedAddressesFoo1.getLoadBalancingPolicyConfig()))
        .containsExactly(fooConfig1, fooConfig2);
    LoadBalancer fooBalancer1;
    Helper fooHelper1;
    Helper fooHelper2;
    if (resolvedAddressesFoo0.getLoadBalancingPolicyConfig().equals(fooConfig1)) {
      fooBalancer1 = fooBalancers.get(0);
      fooHelper1 = fooHelpers.get(0);
      fooHelper2 = fooHelpers.get(1);
    } else {
      fooBalancer1 = fooBalancers.get(1);
      fooHelper1 = fooHelpers.get(1);
      fooHelper2 = fooHelpers.get(0);
    }
    assertThat(resolvedAddressesBar.getLoadBalancingPolicyConfig()).isEqualTo(barConfig1);
    assertThat(resolvedAddressesBaz.getLoadBalancingPolicyConfig()).isEqualTo(bazConfig1);
    Helper barHelper = barHelpers.get(0);
    Helper bazHelper = bazHelpers.get(0);

    // State update.
    Subchannel subchannelFoo1 = mock(Subchannel.class);
    Subchannel subchannelFoo2 = mock(Subchannel.class);
    fooHelper1.updateBalancingState(READY, TestUtils.pickerOf(subchannelFoo1));
    fooHelper2.updateBalancingState(READY, TestUtils.pickerOf(subchannelFoo2));
    barHelper.updateBalancingState(
        TRANSIENT_FAILURE, new ErrorPicker(Status.ABORTED.withDescription("abort bar")));
    bazHelper.updateBalancingState(
        TRANSIENT_FAILURE, new ErrorPicker(Status.DATA_LOSS.withDescription("data loss baz")));
    ArgumentCaptor<ConnectivityState> connectivityStateCaptor = ArgumentCaptor.forClass(null);
    ArgumentCaptor<SubchannelPicker> subchannelPickerCaptor = ArgumentCaptor.forClass(null);
    verify(helper, atLeastOnce()).updateBalancingState(
        connectivityStateCaptor.capture(), subchannelPickerCaptor.capture());
    assertThat(connectivityStateCaptor.getValue()).isEqualTo(READY);
    SubchannelPicker picker = subchannelPickerCaptor.getValue();
    assertPickerRoutePathToSubchannel(picker, "service1", "method1", subchannelFoo1);
    assertPickerRoutePathToSubchannel(picker, "service2", "method2", subchannelFoo1);
    assertPickerRoutePathToError(
        picker, "service1", "hello", Status.ABORTED.withDescription("abort bar"));
    assertPickerRoutePathToError(
        picker, "service2", "hello", Status.ABORTED.withDescription("abort bar"));
    assertPickerRoutePathToSubchannel(picker, "service2", "otherMethod", subchannelFoo2);
    assertPickerRoutePathToError(
        picker, "otherService", "hello", Status.DATA_LOSS.withDescription("data loss baz"));

    // Resolution error.
    Status error = Status.UNAVAILABLE.withDescription("fake unavailable");
    xdsRoutingLb.handleNameResolutionError(error);
    for (LoadBalancer lb : Iterables.concat(fooBalancers, barBalancers, bazBalancers)) {
      verify(lb).handleNameResolutionError(error);
    }

    // New config update.
    Object fooConfig3 = new Object();
    Object barConfig2 = new Object();
    Object barConfig3 = new Object();
    Object bazConfig2 = new Object();
    xdsRoutingConfig = new XdsRoutingConfig(
        ImmutableList.of(
            new Route("foo_action", new MethodName("service1", "method1")),
            new Route("foo_action", new MethodName("service2", "method3")),
            new Route("bar_action", new MethodName("service1", "hello")),
            new Route("bar_action_2", new MethodName("service2", "hello")),
            new Route("baz_action", new MethodName("", ""))),
        ImmutableMap.of(
            "foo_action",
            new PolicySelection(fooLbProvider, null, fooConfig3),
            "bar_action",
            new PolicySelection(barLbProvider, null, barConfig2),
            "bar_action_2",
            new PolicySelection(barLbProvider, null, barConfig3),
            "baz_action",
            new PolicySelection(bazLbProvider, null, bazConfig2)));
    xdsRoutingLb.handleResolvedAddresses(
        ResolvedAddresses.newBuilder()
            .setAddresses(ImmutableList.<EquivalentAddressGroup>of())
            .setLoadBalancingPolicyConfig(xdsRoutingConfig)
            .build());
    verify(fooBalancer1, times(2)).handleResolvedAddresses(resolvedAddressesCaptor.capture());
    assertThat(resolvedAddressesCaptor.getValue().getLoadBalancingPolicyConfig())
        .isEqualTo(fooConfig3);
    assertThat(barBalancers).hasSize(2);
    verify(barBalancers.get(0), times(2))
        .handleResolvedAddresses(resolvedAddressesCaptor.capture());
    assertThat(resolvedAddressesCaptor.getValue().getLoadBalancingPolicyConfig())
        .isEqualTo(barConfig2);
    verify(barBalancers.get(1)).handleResolvedAddresses(resolvedAddressesCaptor.capture());
    assertThat(resolvedAddressesCaptor.getValue().getLoadBalancingPolicyConfig())
        .isEqualTo(barConfig3);
    verify(bazBalancers.get(0), times(2))
        .handleResolvedAddresses(resolvedAddressesCaptor.capture());
    assertThat(resolvedAddressesCaptor.getValue().getLoadBalancingPolicyConfig())
        .isEqualTo(bazConfig2);

    // New status update.
    Subchannel subchannelBar2 = mock(Subchannel.class);
    Helper barHelper2 = barHelpers.get(1);
    barHelper2.updateBalancingState(READY, TestUtils.pickerOf(subchannelBar2));
    verify(helper, atLeastOnce()).updateBalancingState(
        connectivityStateCaptor.capture(), subchannelPickerCaptor.capture());
    assertThat(connectivityStateCaptor.getValue()).isEqualTo(READY);
    picker = subchannelPickerCaptor.getValue();
    assertPickerRoutePathToSubchannel(picker, "service1", "method1", subchannelFoo1);
    assertPickerRoutePathToError(
        picker, "service1", "method2", Status.DATA_LOSS.withDescription("data loss baz"));
    assertPickerRoutePathToSubchannel(picker, "service2", "method3", subchannelFoo1);
    assertPickerRoutePathToError(
        picker, "service1", "hello", Status.ABORTED.withDescription("abort bar"));
    assertPickerRoutePathToSubchannel(picker, "service2", "hello", subchannelBar2);
  }

  private static PickSubchannelArgs pickSubchannelArgsForMethod(
      final String service, final String method) {
    return new PickSubchannelArgs() {

      @Override
      public CallOptions getCallOptions() {
        return CallOptions.DEFAULT;
      }

      @Override
      public Metadata getHeaders() {
        return new Metadata();
      }

      @Override
      public MethodDescriptor<Void, Void> getMethodDescriptor() {
        return MethodDescriptor.<Void, Void>newBuilder()
            .setType(MethodType.UNARY)
            .setFullMethodName(service + "/" + method)
            .setRequestMarshaller(TestMethodDescriptors.voidMarshaller())
            .setResponseMarshaller(TestMethodDescriptors.voidMarshaller())
            .build();
      }
    };
  }

  private static void assertPickerRoutePathToSubchannel(
      SubchannelPicker picker, String service, String method, Subchannel expectedSubchannel) {
    Subchannel actualSubchannel =
        picker.pickSubchannel(pickSubchannelArgsForMethod(service, method)).getSubchannel();
    assertThat(actualSubchannel).isEqualTo(expectedSubchannel);
  }

  private static void assertPickerRoutePathToError(
      SubchannelPicker picker, String service, String method, Status expectedStatus) {
    Status actualStatus =
        picker.pickSubchannel(pickSubchannelArgsForMethod(service, method)).getStatus();
    assertThat(actualStatus.getCode()).isEqualTo(expectedStatus.getCode());
    assertThat(actualStatus.getDescription()).isEqualTo(expectedStatus.getDescription());
  }
}
