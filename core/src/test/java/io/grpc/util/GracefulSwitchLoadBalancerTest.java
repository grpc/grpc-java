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
import static io.grpc.ConnectivityState.TRANSIENT_FAILURE;
import static io.grpc.util.GracefulSwitchLoadBalancer.BUFFER_PICKER;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
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
import io.grpc.LoadBalancer.PickSubchannelArgs;
import io.grpc.LoadBalancer.ResolvedAddresses;
import io.grpc.LoadBalancer.Subchannel;
import io.grpc.LoadBalancer.SubchannelPicker;
import io.grpc.LoadBalancerProvider;
import io.grpc.LoadBalancerRegistry;
import io.grpc.Status;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

/**
 * Tests for {@link GracefulSwitchLoadBalancer}.
 */
@RunWith(JUnit4.class)
public class GracefulSwitchLoadBalancerTest {
  @Rule
  public final ExpectedException thrown = ExpectedException.none();

  private final LoadBalancerRegistry lbRegistry = new LoadBalancerRegistry();
  // maps policy name to lb provide
  private final Map<String, LoadBalancerProvider> lbProviders = new HashMap<>();
  // maps policy name to lb
  private final Map<String, LoadBalancer> balancers = new HashMap<>();
  private final Map<LoadBalancer, Helper> helpers = new HashMap<>();
  private final Helper mockHelper = mock(Helper.class);
  private final GracefulSwitchLoadBalancer gracefulSwitchLb =
      new GracefulSwitchLoadBalancer(mockHelper);
  private final String[] lbPolicies = {"lb_policy_0", "lb_policy_1", "lb_policy_2", "lb_policy_3"};

  @Before
  public void setUp() {
    for (String lbPolicy : lbPolicies) {
      LoadBalancerProvider lbProvider = new FakeLoadBalancerProvider(lbPolicy);
      lbProviders.put(lbPolicy, lbProvider);
      lbRegistry.register(lbProvider);
    }
  }

  @Test
  public void canHandleEmptyAddressListFromNameResolutionForwardedToLatestPolicy() {
    gracefulSwitchLb.switchTo(lbProviders.get(lbPolicies[0]));
    LoadBalancer lb0 = balancers.get(lbPolicies[0]);
    Helper helper0 = helpers.get(lb0);
    SubchannelPicker picker = mock(SubchannelPicker.class);
    helper0.updateBalancingState(READY, picker);

    assertThat(gracefulSwitchLb.canHandleEmptyAddressListFromNameResolution()).isFalse();
    doReturn(true).when(lb0).canHandleEmptyAddressListFromNameResolution();
    assertThat(gracefulSwitchLb.canHandleEmptyAddressListFromNameResolution()).isTrue();

    gracefulSwitchLb.switchTo(lbProviders.get(lbPolicies[1]));
    LoadBalancer lb1 = balancers.get(lbPolicies[1]);

    assertThat(gracefulSwitchLb.canHandleEmptyAddressListFromNameResolution()).isFalse();

    doReturn(true).when(lb1).canHandleEmptyAddressListFromNameResolution();
    assertThat(gracefulSwitchLb.canHandleEmptyAddressListFromNameResolution()).isTrue();

    gracefulSwitchLb.switchTo(lbProviders.get(lbPolicies[2]));
    LoadBalancer lb2 = balancers.get(lbPolicies[2]);

    assertThat(gracefulSwitchLb.canHandleEmptyAddressListFromNameResolution()).isFalse();

    doReturn(true).when(lb2).canHandleEmptyAddressListFromNameResolution();
    assertThat(gracefulSwitchLb.canHandleEmptyAddressListFromNameResolution()).isTrue();
  }

  @Test
  public void handleResolvedAddressesAndNameResolutionErrorForwardedToLatestPolicy() {
    gracefulSwitchLb.switchTo(lbProviders.get(lbPolicies[0]));
    LoadBalancer lb0 = balancers.get(lbPolicies[0]);
    Helper helper0 = helpers.get(lb0);
    SubchannelPicker picker = mock(SubchannelPicker.class);
    helper0.updateBalancingState(READY, picker);

    ResolvedAddresses addresses = newFakeAddresses();
    gracefulSwitchLb.handleResolvedAddresses(addresses);
    verify(lb0).handleResolvedAddresses(addresses);
    gracefulSwitchLb.handleNameResolutionError(Status.DATA_LOSS);
    verify(lb0).handleNameResolutionError(Status.DATA_LOSS);

    gracefulSwitchLb.switchTo(lbProviders.get(lbPolicies[1]));
    LoadBalancer lb1 = balancers.get(lbPolicies[1]);
    addresses = newFakeAddresses();
    gracefulSwitchLb.handleResolvedAddresses(addresses);
    verify(lb0, never()).handleResolvedAddresses(addresses);
    verify(lb1).handleResolvedAddresses(addresses);
    gracefulSwitchLb.handleNameResolutionError(Status.ALREADY_EXISTS);
    verify(lb0, never()).handleNameResolutionError(Status.ALREADY_EXISTS);
    verify(lb1).handleNameResolutionError(Status.ALREADY_EXISTS);

    gracefulSwitchLb.switchTo(lbProviders.get(lbPolicies[2]));
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
    gracefulSwitchLb.switchTo(lbProviders.get(lbPolicies[0]));
    LoadBalancer lb0 = balancers.get(lbPolicies[0]);
    Helper helper0 = helpers.get(lb0);
    SubchannelPicker picker = mock(SubchannelPicker.class);
    helper0.updateBalancingState(READY, picker);

    gracefulSwitchLb.switchTo(lbProviders.get(lbPolicies[1]));
    LoadBalancer lb1 = balancers.get(lbPolicies[1]);
    verify(lb1, never()).shutdown();

    gracefulSwitchLb.switchTo(lbProviders.get(lbPolicies[2]));
    verify(lb1).shutdown();
    LoadBalancer lb2 = balancers.get(lbPolicies[2]);
    verify(lb0, never()).shutdown();
    helpers.get(lb2).updateBalancingState(READY, mock(SubchannelPicker.class));
    verify(lb0).shutdown();

    gracefulSwitchLb.switchTo(lbProviders.get(lbPolicies[3]));
    LoadBalancer lb3 = balancers.get(lbPolicies[3]);
    verify(lb2, never()).shutdown();
    verify(lb3, never()).shutdown();

    gracefulSwitchLb.shutdown();
    verify(lb2).shutdown();
    verify(lb3).shutdown();

    verifyNoMoreInteractions(lb0, lb1, lb2, lb3);
  }

  @Test
  public void requestConnectionForwardedToLatestPolicies() {
    gracefulSwitchLb.switchTo(lbProviders.get(lbPolicies[0]));
    LoadBalancer lb0 = balancers.get(lbPolicies[0]);
    Helper helper0 = helpers.get(lb0);
    SubchannelPicker picker = mock(SubchannelPicker.class);
    helper0.updateBalancingState(READY, picker);

    gracefulSwitchLb.requestConnection();
    verify(lb0).requestConnection();

    gracefulSwitchLb.switchTo(lbProviders.get(lbPolicies[1]));
    LoadBalancer lb1 = balancers.get(lbPolicies[1]);
    gracefulSwitchLb.requestConnection();
    verify(lb1).requestConnection();

    gracefulSwitchLb.switchTo(lbProviders.get(lbPolicies[2]));
    verify(lb1).shutdown();
    LoadBalancer lb2 = balancers.get(lbPolicies[2]);
    gracefulSwitchLb.requestConnection();
    verify(lb2).requestConnection();

    // lb2 reports READY
    helpers.get(lb2).updateBalancingState(READY, mock(SubchannelPicker.class));
    verify(lb0).shutdown();

    gracefulSwitchLb.requestConnection();
    verify(lb2, times(2)).requestConnection();

    gracefulSwitchLb.switchTo(lbProviders.get(lbPolicies[3]));
    LoadBalancer lb3 = balancers.get(lbPolicies[3]);
    gracefulSwitchLb.requestConnection();
    verify(lb3).requestConnection();

    verifyNoMoreInteractions(lb0, lb1, lb2, lb3);
  }

  @Test
  public void createSubchannelForwarded() {
    gracefulSwitchLb.switchTo(lbProviders.get(lbPolicies[0]));
    LoadBalancer lb0 = balancers.get(lbPolicies[0]);
    Helper helper0 = helpers.get(lb0);
    SubchannelPicker picker = mock(SubchannelPicker.class);
    helper0.updateBalancingState(READY, picker);

    CreateSubchannelArgs createSubchannelArgs = newFakeCreateSubchannelArgs();
    helper0.createSubchannel(createSubchannelArgs);
    verify(mockHelper).createSubchannel(createSubchannelArgs);

    gracefulSwitchLb.switchTo(lbProviders.get(lbPolicies[1]));
    LoadBalancer lb1 = balancers.get(lbPolicies[1]);
    Helper helper1 = helpers.get(lb1);
    createSubchannelArgs = newFakeCreateSubchannelArgs();
    helper1.createSubchannel(createSubchannelArgs);
    verify(mockHelper).createSubchannel(createSubchannelArgs);

    createSubchannelArgs = newFakeCreateSubchannelArgs();
    helper0.createSubchannel(createSubchannelArgs);
    verify(mockHelper).createSubchannel(createSubchannelArgs);

    verifyNoMoreInteractions(lb0, lb1);
  }

  @Test
  public void updateBalancingStateIsGraceful() {
    gracefulSwitchLb.switchTo(lbProviders.get(lbPolicies[0]));
    LoadBalancer lb0 = balancers.get(lbPolicies[0]);
    Helper helper0 = helpers.get(lb0);
    SubchannelPicker picker = mock(SubchannelPicker.class);
    helper0.updateBalancingState(READY, picker);
    verify(mockHelper).updateBalancingState(READY, picker);

    gracefulSwitchLb.switchTo(lbProviders.get(lbPolicies[1]));
    LoadBalancer lb1 = balancers.get(lbPolicies[1]);
    Helper helper1 = helpers.get(lb1);
    picker = mock(SubchannelPicker.class);
    helper1.updateBalancingState(CONNECTING, picker);
    verify(mockHelper, never()).updateBalancingState(CONNECTING, picker);

    gracefulSwitchLb.switchTo(lbProviders.get(lbPolicies[2]));
    verify(lb1).shutdown();
    LoadBalancer lb2 = balancers.get(lbPolicies[2]);
    Helper helper2 = helpers.get(lb2);
    picker = mock(SubchannelPicker.class);
    helper2.updateBalancingState(CONNECTING, picker);
    verify(mockHelper, never()).updateBalancingState(CONNECTING, picker);

    // lb2 reports READY
    SubchannelPicker picker2 = mock(SubchannelPicker.class);
    helper2.updateBalancingState(READY, picker2);
    verify(lb0).shutdown();
    verify(mockHelper).updateBalancingState(READY, picker2);

    gracefulSwitchLb.switchTo(lbProviders.get(lbPolicies[3]));
    LoadBalancer lb3 = balancers.get(lbPolicies[3]);
    Helper helper3 = helpers.get(lb3);
    SubchannelPicker picker3 = mock(SubchannelPicker.class);
    helper3.updateBalancingState(CONNECTING, picker3);
    verify(mockHelper, never()).updateBalancingState(CONNECTING, picker3);

    // lb2 out of READY
    picker2 = mock(SubchannelPicker.class);
    helper2.updateBalancingState(CONNECTING, picker2);
    verify(mockHelper, never()).updateBalancingState(CONNECTING, picker2);
    verify(mockHelper).updateBalancingState(CONNECTING, picker3);
    verify(lb2).shutdown();

    picker3 = mock(SubchannelPicker.class);
    helper3.updateBalancingState(CONNECTING, picker3);
    verify(mockHelper).updateBalancingState(CONNECTING, picker3);

    verifyNoMoreInteractions(lb0, lb1, lb2, lb3);
  }

  @Test
  public void switchWhileOldPolicyIsNotReady() {
    gracefulSwitchLb.switchTo(lbProviders.get(lbPolicies[0]));
    LoadBalancer lb0 = balancers.get(lbPolicies[0]);
    Helper helper0 = helpers.get(lb0);
    SubchannelPicker picker = mock(SubchannelPicker.class);
    helper0.updateBalancingState(READY, picker);
    picker = mock(SubchannelPicker.class);
    helper0.updateBalancingState(CONNECTING, picker);

    verify(lb0, never()).shutdown();
    gracefulSwitchLb.switchTo(lbProviders.get(lbPolicies[1]));
    verify(lb0).shutdown();
    LoadBalancer lb1 = balancers.get(lbPolicies[1]);

    Helper helper1 = helpers.get(lb1);
    picker = mock(SubchannelPicker.class);
    helper1.updateBalancingState(CONNECTING, picker);
    verify(mockHelper).updateBalancingState(CONNECTING, picker);

    verify(lb1, never()).shutdown();
    gracefulSwitchLb.switchTo(lbProviders.get(lbPolicies[2]));
    verify(lb1).shutdown();
    LoadBalancer lb2 = balancers.get(lbPolicies[2]);

    verifyNoMoreInteractions(lb0, lb1, lb2);
  }

  @Test
  public void switchWhileOldPolicyGoesFromReadyToNotReady() {
    gracefulSwitchLb.switchTo(lbProviders.get(lbPolicies[0]));
    LoadBalancer lb0 = balancers.get(lbPolicies[0]);
    Helper helper0 = helpers.get(lb0);
    SubchannelPicker picker = mock(SubchannelPicker.class);
    helper0.updateBalancingState(READY, picker);

    gracefulSwitchLb.switchTo(lbProviders.get(lbPolicies[1]));
    verify(lb0, never()).shutdown();

    LoadBalancer lb1 = balancers.get(lbPolicies[1]);
    Helper helper1 = helpers.get(lb1);
    SubchannelPicker picker1 = mock(SubchannelPicker.class);
    helper1.updateBalancingState(CONNECTING, picker1);
    verify(mockHelper, never()).updateBalancingState(CONNECTING, picker1);

    picker = mock(SubchannelPicker.class);
    helper0.updateBalancingState(CONNECTING, picker);
    verify(lb0).shutdown();
    verify(mockHelper, never()).updateBalancingState(CONNECTING, picker);
    verify(mockHelper).updateBalancingState(CONNECTING, picker1);

    picker1 = mock(SubchannelPicker.class);
    helper1.updateBalancingState(READY, picker1);
    verify(mockHelper).updateBalancingState(READY, picker1);

    verifyNoMoreInteractions(lb0, lb1);
  }

  @Test
  public void switchWhileOldPolicyGoesFromReadyToNotReadyWhileNewPolicyStillIdle() {
    gracefulSwitchLb.switchTo(lbProviders.get(lbPolicies[0]));
    LoadBalancer lb0 = balancers.get(lbPolicies[0]);
    InOrder inOrder = inOrder(lb0, mockHelper);
    Helper helper0 = helpers.get(lb0);
    SubchannelPicker picker = mock(SubchannelPicker.class);
    helper0.updateBalancingState(READY, picker);

    gracefulSwitchLb.switchTo(lbProviders.get(lbPolicies[1]));
    verify(lb0, never()).shutdown();

    LoadBalancer lb1 = balancers.get(lbPolicies[1]);
    Helper helper1 = helpers.get(lb1);

    picker = mock(SubchannelPicker.class);
    helper0.updateBalancingState(CONNECTING, picker);

    verify(mockHelper, never()).updateBalancingState(CONNECTING, picker);
    inOrder.verify(mockHelper).updateBalancingState(CONNECTING, BUFFER_PICKER);
    inOrder.verify(lb0).shutdown(); // shutdown after update

    picker = mock(SubchannelPicker.class);
    helper1.updateBalancingState(CONNECTING, picker);
    inOrder.verify(mockHelper).updateBalancingState(CONNECTING, picker);

    inOrder.verifyNoMoreInteractions();
    verifyNoMoreInteractions(lb1);
  }

  @Test
  public void newPolicyNameTheSameAsPendingPolicy_shouldHaveNoEffect() {
    gracefulSwitchLb.switchTo(lbProviders.get(lbPolicies[0]));
    LoadBalancer lb0 = balancers.get(lbPolicies[0]);
    Helper helper0 = helpers.get(lb0);
    SubchannelPicker picker = mock(SubchannelPicker.class);
    helper0.updateBalancingState(READY, picker);

    gracefulSwitchLb.switchTo(lbProviders.get(lbPolicies[1]));
    LoadBalancer lb1 = balancers.get(lbPolicies[1]);

    gracefulSwitchLb.switchTo(lbProviders.get(lbPolicies[1]));
    assertThat(balancers.get(lbPolicies[1])).isSameInstanceAs(lb1);

    verifyNoMoreInteractions(lb0, lb1);
  }

  @Test
  public void newPolicyNameTheSameAsCurrentPolicy_shouldShutdownPendingLb() {
    gracefulSwitchLb.switchTo(lbProviders.get(lbPolicies[0]));
    LoadBalancer lb0 = balancers.get(lbPolicies[0]);

    gracefulSwitchLb.switchTo(lbProviders.get(lbPolicies[0]));
    assertThat(balancers.get(lbPolicies[0])).isSameInstanceAs(lb0);

    Helper helper0 = helpers.get(lb0);
    SubchannelPicker picker = mock(SubchannelPicker.class);
    helper0.updateBalancingState(READY, picker);

    gracefulSwitchLb.switchTo(lbProviders.get(lbPolicies[1]));
    LoadBalancer lb1 = balancers.get(lbPolicies[1]);

    gracefulSwitchLb.switchTo(lbProviders.get(lbPolicies[0]));
    verify(lb1).shutdown();
    assertThat(balancers.get(lbPolicies[0])).isSameInstanceAs(lb0);

    verifyNoMoreInteractions(lb0, lb1);
  }


  @Test
  public void newLbFactoryEqualToOldOneShouldHaveNoEffect() {
    final List<LoadBalancer> balancers = new ArrayList<>();

    final class LoadBalancerFactoryWithId extends LoadBalancer.Factory {
      final int id;

      LoadBalancerFactoryWithId(int id) {
        this.id = id;
      }

      @Override
      public LoadBalancer newLoadBalancer(Helper helper) {
        LoadBalancer balancer = mock(LoadBalancer.class);
        balancers.add(balancer);
        return balancer;
      }

      @Override
      public boolean equals(Object o) {
        if (!(o instanceof LoadBalancerFactoryWithId)) {
          return false;
        }
        LoadBalancerFactoryWithId that = (LoadBalancerFactoryWithId) o;
        return id == that.id;
      }

      @Override
      public int hashCode() {
        return id;
      }
    }

    gracefulSwitchLb.switchTo(new LoadBalancerFactoryWithId(0));
    assertThat(balancers).hasSize(1);
    LoadBalancer lb0 = balancers.get(0);

    gracefulSwitchLb.switchTo(new LoadBalancerFactoryWithId(0));
    assertThat(balancers).hasSize(1);

    gracefulSwitchLb.switchTo(new LoadBalancerFactoryWithId(1));
    assertThat(balancers).hasSize(2);
    LoadBalancer lb1 = balancers.get(1);
    verify(lb0).shutdown();

    verifyNoMoreInteractions(lb0, lb1);
  }

  @Test
  public void transientFailureOnInitialResolutionError() {
    gracefulSwitchLb.handleNameResolutionError(Status.DATA_LOSS);
    ArgumentCaptor<SubchannelPicker> pickerCaptor = ArgumentCaptor.forClass(null);
    verify(mockHelper).updateBalancingState(eq(TRANSIENT_FAILURE), pickerCaptor.capture());
    SubchannelPicker picker = pickerCaptor.getValue();
    assertThat(picker.pickSubchannel(mock(PickSubchannelArgs.class)).getStatus().getCode())
        .isEqualTo(Status.Code.DATA_LOSS);
  }

  @Deprecated
  @Test
  public void handleSubchannelState_shouldThrow() {
    gracefulSwitchLb.switchTo(lbProviders.get(lbPolicies[0]));
    Subchannel subchannel = mock(Subchannel.class);
    ConnectivityStateInfo connectivityStateInfo = ConnectivityStateInfo.forNonError(READY);
    thrown.expect(UnsupportedOperationException.class);
    gracefulSwitchLb.handleSubchannelState(subchannel, connectivityStateInfo);
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
