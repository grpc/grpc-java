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

package io.grpc.util;

import static com.google.common.truth.Truth.assertThat;
import static io.grpc.ConnectivityState.CONNECTING;
import static io.grpc.ConnectivityState.READY;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import io.grpc.ConnectivityStateInfo;
import io.grpc.EquivalentAddressGroup;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancer.CreateSubchannelArgs;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancer.ResolvedAddresses;
import io.grpc.LoadBalancer.Subchannel;
import io.grpc.LoadBalancer.SubchannelPicker;
import io.grpc.LoadBalancerProvider;
import io.grpc.LoadBalancerRegistry;
import io.grpc.Status;
import java.net.SocketAddress;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests for {@link GracefulSwitchLoadBalancer}.
 */
@RunWith(JUnit4.class)
public class GracefulSwitchLoadBalancerTest {

  private final LoadBalancerRegistry lbRegistry = new LoadBalancerRegistry();
  // maps policy name to lb provide
  private final Map<String, LoadBalancerProvider> lbProviders = new HashMap<>();
  // maps policy name to lb
  private final Map<String, LoadBalancer> balancers = new HashMap<>();
  private final Map<LoadBalancer, Helper> helpers = new HashMap<>();
  private final GracefulSwitchLoadBalancer gracefulSwitchLb = new GracefulSwitchLoadBalancer();
  private final String[] lbPolicies = {"lb_policy_0", "lb_policy_1", "lb_policy_2", "lb_policy_3"};
  private final Helper[] mockHelpers = new Helper[lbPolicies.length];

  @Before
  public void setUp() {
    for (int i = 0; i < lbPolicies.length; i++) {
      String lbPolicy = lbPolicies[i];
      LoadBalancerProvider lbProvider = new FakeLoadBalancerProvider(lbPolicy);
      lbProviders.put(lbPolicy, lbProvider);
      lbRegistry.register(lbProvider);
      mockHelpers[i] = mock(Helper.class);
    }
  }

  @Test
  public void canHandleEmptyAddressListFromNameResolutionForwardedToLatestPolicy() {
    gracefulSwitchLb.switchTo(lbProviders.get(lbPolicies[0]), mockHelpers[0]);

    assertThat(gracefulSwitchLb.canHandleEmptyAddressListFromNameResolution()).isFalse();
    LoadBalancer lb0 = balancers.get(lbPolicies[0]);
    doReturn(true).when(lb0).canHandleEmptyAddressListFromNameResolution();
    assertThat(gracefulSwitchLb.canHandleEmptyAddressListFromNameResolution()).isTrue();

    gracefulSwitchLb.switchTo(lbProviders.get(lbPolicies[1]), mockHelpers[1]);
    LoadBalancer lb1 = balancers.get(lbPolicies[1]);

    assertThat(gracefulSwitchLb.canHandleEmptyAddressListFromNameResolution()).isFalse();

    doReturn(true).when(lb1).canHandleEmptyAddressListFromNameResolution();
    assertThat(gracefulSwitchLb.canHandleEmptyAddressListFromNameResolution()).isTrue();

    gracefulSwitchLb.switchTo(lbProviders.get(lbPolicies[2]), mockHelpers[2]);
    LoadBalancer lb2 = balancers.get(lbPolicies[2]);

    assertThat(gracefulSwitchLb.canHandleEmptyAddressListFromNameResolution()).isFalse();

    doReturn(true).when(lb2).canHandleEmptyAddressListFromNameResolution();
    assertThat(gracefulSwitchLb.canHandleEmptyAddressListFromNameResolution()).isTrue();
  }

  @Test
  public void handleResolvedAddressesAndNameResolutionErrorForwardedToLatestPolicy() {
    gracefulSwitchLb.switchTo(lbProviders.get(lbPolicies[0]), mockHelpers[0]);
    LoadBalancer lb0 = balancers.get(lbPolicies[0]);
    ResolvedAddresses addresses = newFakeAddresses();
    gracefulSwitchLb.handleResolvedAddresses(addresses);
    verify(lb0).handleResolvedAddresses(addresses);
    gracefulSwitchLb.handleNameResolutionError(Status.DATA_LOSS);
    verify(lb0).handleNameResolutionError(Status.DATA_LOSS);

    gracefulSwitchLb.switchTo(lbProviders.get(lbPolicies[1]), mockHelpers[1]);
    LoadBalancer lb1 = balancers.get(lbPolicies[1]);
    addresses = newFakeAddresses();
    gracefulSwitchLb.handleResolvedAddresses(addresses);
    verify(lb0, never()).handleResolvedAddresses(addresses);
    verify(lb1).handleResolvedAddresses(addresses);
    gracefulSwitchLb.handleNameResolutionError(Status.ALREADY_EXISTS);
    verify(lb0, never()).handleNameResolutionError(Status.ALREADY_EXISTS);
    verify(lb1).handleNameResolutionError(Status.ALREADY_EXISTS);

    gracefulSwitchLb.switchTo(lbProviders.get(lbPolicies[2]), mockHelpers[2]);
    verify(lb1).shutdown();
    LoadBalancer lb2 = balancers.get(lbPolicies[2]);
    addresses = newFakeAddresses();
    gracefulSwitchLb.handleResolvedAddresses(addresses);
    verify(lb0, never()).handleResolvedAddresses(addresses);
    verify(lb1, never()).handleResolvedAddresses(addresses);
    verify(lb2).handleResolvedAddresses(addresses);
    gracefulSwitchLb.handleNameResolutionError(Status.CANCELLED);
    verify(lb0, never()).handleNameResolutionError(Status.CANCELLED);
    verify(lb1, never()).handleNameResolutionError(Status.CANCELLED);
    verify(lb2).handleNameResolutionError(Status.CANCELLED);

    verifyNoMoreInteractions(lb0, lb1, lb2);
  }

  @Test
  public void shutdownTriggeredWhenSwitchAndForwardedWhenSwitchLbShutdown() {
    gracefulSwitchLb.switchTo(lbProviders.get(lbPolicies[0]), mockHelpers[0]);
    LoadBalancer lb0 = balancers.get(lbPolicies[0]);

    gracefulSwitchLb.switchTo(lbProviders.get(lbPolicies[1]), mockHelpers[1]);
    LoadBalancer lb1 = balancers.get(lbPolicies[1]);
    verify(lb1, never()).shutdown();

    gracefulSwitchLb.switchTo(lbProviders.get(lbPolicies[2]), mockHelpers[2]);
    verify(lb1).shutdown();
    LoadBalancer lb2 = balancers.get(lbPolicies[2]);
    verify(lb0, never()).shutdown();
    helpers.get(lb2).updateBalancingState(READY, mock(SubchannelPicker.class));
    verify(lb0).shutdown();

    gracefulSwitchLb.switchTo(lbProviders.get(lbPolicies[3]), mockHelpers[3]);
    LoadBalancer lb3 = balancers.get(lbPolicies[3]);
    verify(lb2, never()).shutdown();
    verify(lb3, never()).shutdown();

    gracefulSwitchLb.shutdown();
    verify(lb2).shutdown();
    verify(lb3).shutdown();

    verifyNoMoreInteractions(lb0, lb1, lb2, lb3);
  }

  @Test
  public void requestConnectionForwardedToBothPolicies() {
    gracefulSwitchLb.switchTo(lbProviders.get(lbPolicies[0]), mockHelpers[0]);
    LoadBalancer lb0 = balancers.get(lbPolicies[0]);
    gracefulSwitchLb.requestConnection();
    verify(lb0).requestConnection();

    gracefulSwitchLb.switchTo(lbProviders.get(lbPolicies[1]), mockHelpers[1]);
    LoadBalancer lb1 = balancers.get(lbPolicies[1]);
    gracefulSwitchLb.requestConnection();
    verify(lb0, times(2)).requestConnection();
    verify(lb1).requestConnection();

    gracefulSwitchLb.switchTo(lbProviders.get(lbPolicies[2]), mockHelpers[2]);
    verify(lb1).shutdown();
    LoadBalancer lb2 = balancers.get(lbPolicies[2]);
    gracefulSwitchLb.requestConnection();
    verify(lb0, times(3)).requestConnection();
    verify(lb2).requestConnection();

    // lb2 reports READY
    helpers.get(lb2).updateBalancingState(READY, mock(SubchannelPicker.class));
    verify(lb0).shutdown();

    gracefulSwitchLb.requestConnection();
    verify(lb2, times(2)).requestConnection();

    gracefulSwitchLb.switchTo(lbProviders.get(lbPolicies[3]), mockHelpers[3]);
    LoadBalancer lb3 = balancers.get(lbPolicies[3]);
    gracefulSwitchLb.requestConnection();
    verify(lb2, times(3)).requestConnection();
    verify(lb3).requestConnection();

    verifyNoMoreInteractions(lb0, lb1, lb2, lb3);
  }

  @Deprecated
  @Test
  public void handleSubchannelStateForwardedAllPolicies() {
    gracefulSwitchLb.switchTo(lbProviders.get(lbPolicies[0]), mockHelpers[0]);
    LoadBalancer lb0 = balancers.get(lbPolicies[0]);
    Subchannel subchannel = mock(Subchannel.class);
    ConnectivityStateInfo stateInfo = ConnectivityStateInfo.forNonError(CONNECTING);
    gracefulSwitchLb.handleSubchannelState(subchannel, stateInfo);
    verify(lb0).handleSubchannelState(subchannel, stateInfo);

    gracefulSwitchLb.switchTo(lbProviders.get(lbPolicies[1]), mockHelpers[1]);
    LoadBalancer lb1 = balancers.get(lbPolicies[1]);
    subchannel = mock(Subchannel.class);
    gracefulSwitchLb.handleSubchannelState(subchannel, stateInfo);
    verify(lb0).handleSubchannelState(subchannel, stateInfo);
    verify(lb1).handleSubchannelState(subchannel, stateInfo);

    gracefulSwitchLb.switchTo(lbProviders.get(lbPolicies[2]), mockHelpers[2]);
    verify(lb1).shutdown();
    LoadBalancer lb2 = balancers.get(lbPolicies[2]);
    subchannel = mock(Subchannel.class);
    gracefulSwitchLb.handleSubchannelState(subchannel, stateInfo);
    verify(lb0).handleSubchannelState(subchannel, stateInfo);
    verify(lb2).handleSubchannelState(subchannel, stateInfo);

    // lb2 reports READY
    helpers.get(lb2).updateBalancingState(READY, mock(SubchannelPicker.class));
    verify(lb0).shutdown();

    subchannel = mock(Subchannel.class);
    gracefulSwitchLb.handleSubchannelState(subchannel, stateInfo);
    verify(lb2).handleSubchannelState(subchannel, stateInfo);

    gracefulSwitchLb.switchTo(lbProviders.get(lbPolicies[3]), mockHelpers[3]);
    LoadBalancer lb3 = balancers.get(lbPolicies[3]);
    subchannel = mock(Subchannel.class);
    gracefulSwitchLb.handleSubchannelState(subchannel, stateInfo);
    verify(lb2).handleSubchannelState(subchannel, stateInfo);
    verify(lb3).handleSubchannelState(subchannel, stateInfo);

    verifyNoMoreInteractions(lb0, lb1, lb2, lb3);
  }

  @Test
  public void createSubchannelForwarded() {
    gracefulSwitchLb.switchTo(lbProviders.get(lbPolicies[0]), mockHelpers[0]);
    LoadBalancer lb0 = balancers.get(lbPolicies[0]);
    Helper helper0 = helpers.get(lb0);
    CreateSubchannelArgs createSubchannelArgs = newFakeCreateSubchannelArgs();
    helper0.createSubchannel(createSubchannelArgs);
    verify(mockHelpers[0]).createSubchannel(createSubchannelArgs);

    gracefulSwitchLb.switchTo(lbProviders.get(lbPolicies[1]), mockHelpers[1]);
    LoadBalancer lb1 = balancers.get(lbPolicies[1]);
    Helper helper1 = helpers.get(lb1);
    createSubchannelArgs = newFakeCreateSubchannelArgs();
    helper1.createSubchannel(createSubchannelArgs);
    verify(mockHelpers[0], never()).createSubchannel(createSubchannelArgs);
    verify(mockHelpers[1]).createSubchannel(createSubchannelArgs);

    createSubchannelArgs = newFakeCreateSubchannelArgs();
    helper0.createSubchannel(createSubchannelArgs);
    verify(mockHelpers[0]).createSubchannel(createSubchannelArgs);
    verify(mockHelpers[1], never()).createSubchannel(createSubchannelArgs);

    verifyNoMoreInteractions(lb0, lb1);
  }

  @Test
  public void updateBalancingStateIsGraceful() {
    gracefulSwitchLb.switchTo(lbProviders.get(lbPolicies[0]), mockHelpers[0]);
    LoadBalancer lb0 = balancers.get(lbPolicies[0]);
    Helper helper0 = helpers.get(lb0);
    SubchannelPicker picker = mock(SubchannelPicker.class);
    helper0.updateBalancingState(CONNECTING, picker);
    verify(mockHelpers[0]).updateBalancingState(CONNECTING, picker);

    gracefulSwitchLb.switchTo(lbProviders.get(lbPolicies[1]), mockHelpers[1]);
    LoadBalancer lb1 = balancers.get(lbPolicies[1]);
    Helper helper1 = helpers.get(lb1);
    picker = mock(SubchannelPicker.class);
    helper1.updateBalancingState(CONNECTING, picker);
    verify(mockHelpers[0], never()).updateBalancingState(CONNECTING, picker);
    verify(mockHelpers[1], never()).updateBalancingState(CONNECTING, picker);
    picker = mock(SubchannelPicker.class);
    helper0.updateBalancingState(CONNECTING, picker);
    verify(mockHelpers[0]).updateBalancingState(CONNECTING, picker);
    verify(mockHelpers[1], never()).updateBalancingState(CONNECTING, picker);

    gracefulSwitchLb.switchTo(lbProviders.get(lbPolicies[2]), mockHelpers[2]);
    verify(lb1).shutdown();
    LoadBalancer lb2 = balancers.get(lbPolicies[2]);
    Helper helper2 = helpers.get(lb2);
    picker = mock(SubchannelPicker.class);
    helper2.updateBalancingState(CONNECTING, picker);
    verify(mockHelpers[0], never()).updateBalancingState(CONNECTING, picker);
    verify(mockHelpers[2], never()).updateBalancingState(CONNECTING, picker);
    picker = mock(SubchannelPicker.class);
    helper0.updateBalancingState(CONNECTING, picker);
    verify(mockHelpers[0]).updateBalancingState(CONNECTING, picker);
    verify(mockHelpers[2], never()).updateBalancingState(CONNECTING, picker);

    // lb2 reports READY
    picker = mock(SubchannelPicker.class);
    helper2.updateBalancingState(READY, picker);
    verify(lb0).shutdown();
    verify(mockHelpers[2]).updateBalancingState(READY, picker);
    picker = mock(SubchannelPicker.class);
    helper2.updateBalancingState(CONNECTING, picker);
    verify(mockHelpers[2]).updateBalancingState(CONNECTING, picker);

    gracefulSwitchLb.switchTo(lbProviders.get(lbPolicies[3]), mockHelpers[3]);
    LoadBalancer lb3 = balancers.get(lbPolicies[3]);
    Helper helper3 = helpers.get(lb3);
    picker = mock(SubchannelPicker.class);
    helper3.updateBalancingState(CONNECTING, picker);
    verify(mockHelpers[2], never()).updateBalancingState(CONNECTING, picker);
    verify(mockHelpers[3], never()).updateBalancingState(CONNECTING, picker);
    picker = mock(SubchannelPicker.class);
    helper2.updateBalancingState(CONNECTING, picker);
    verify(mockHelpers[2]).updateBalancingState(CONNECTING, picker);
    verify(mockHelpers[3], never()).updateBalancingState(CONNECTING, picker);

    verifyNoMoreInteractions(lb0, lb1, lb2, lb3);
  }

  private final class FakeLoadBalancerProvider extends LoadBalancerProvider {

    final String policyName;

    FakeLoadBalancerProvider(String policyName) {
      this.policyName = policyName;
    }

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
      return policyName;
    }

    @Override
    public LoadBalancer newLoadBalancer(Helper helper) {
      LoadBalancer balancer = mock(LoadBalancer.class);
      balancers.put(policyName, balancer);
      helpers.put(balancer, helper);
      return balancer;
    }
  }

  private static ResolvedAddresses newFakeAddresses() {
    return ResolvedAddresses
        .newBuilder()
        .setAddresses(
            Collections.singletonList(new EquivalentAddressGroup(mock(SocketAddress.class))))
        .build();
  }

  private static CreateSubchannelArgs newFakeCreateSubchannelArgs() {
    return CreateSubchannelArgs
        .newBuilder()
        .setAddresses(new EquivalentAddressGroup(mock(SocketAddress.class)))
        .build();
  }
}
