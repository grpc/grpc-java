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

package io.grpc.rls;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.grpc.ChannelLogger;
import io.grpc.ConnectivityState;
import io.grpc.EquivalentAddressGroup;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancer.ResolvedAddresses;
import io.grpc.LoadBalancer.SubchannelPicker;
import io.grpc.LoadBalancerProvider;
import io.grpc.LoadBalancerRegistry;
import io.grpc.NameResolver.ConfigOrError;
import io.grpc.SynchronizationContext;
import io.grpc.rls.ChildLoadBalancerHelper.ChildLoadBalancerHelperProvider;
import io.grpc.rls.LbPolicyConfiguration.ChildLbStatusListener;
import io.grpc.rls.LbPolicyConfiguration.ChildLoadBalancingPolicy;
import io.grpc.rls.LbPolicyConfiguration.ChildPolicyWrapper;
import io.grpc.rls.LbPolicyConfiguration.ChildPolicyWrapper.ChildPolicyReportingHelper;
import io.grpc.rls.LbPolicyConfiguration.InvalidChildPolicyConfigException;
import io.grpc.rls.LbPolicyConfiguration.RefCountedChildPolicyWrapperFactory;
import java.lang.Thread.UncaughtExceptionHandler;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentMatchers;

@RunWith(JUnit4.class)
public class LbPolicyConfigurationTest {

  private final Helper helper = mock(Helper.class);
  private final LoadBalancerProvider lbProvider = mock(LoadBalancerProvider.class);
  private final SubchannelStateManager subchannelStateManager = new SubchannelStateManagerImpl();
  private final SubchannelPicker picker = mock(SubchannelPicker.class);
  private final ChildLbStatusListener childLbStatusListener = mock(ChildLbStatusListener.class);
  private final ResolvedAddressFactory resolvedAddressFactory =
      new ResolvedAddressFactory() {
        @Override
        public ResolvedAddresses create(Object childLbConfig) {
          return ResolvedAddresses.newBuilder()
              .setAddresses(ImmutableList.<EquivalentAddressGroup>of())
              .build();
        }
      };
  private final RefCountedChildPolicyWrapperFactory factory =
      new RefCountedChildPolicyWrapperFactory(
          new ChildLoadBalancingPolicy(
              "targetFieldName",
              ImmutableMap.<String, Object>of("foo", "bar"),
              lbProvider),
          resolvedAddressFactory,
          new ChildLoadBalancerHelperProvider(helper, subchannelStateManager, picker),
          childLbStatusListener);

  @Before
  public void setUp() {
    doReturn(mock(ChannelLogger.class)).when(helper).getChannelLogger();
    doReturn(
        new SynchronizationContext(
            new UncaughtExceptionHandler() {
              @Override
              public void uncaughtException(Thread t, Throwable e) {
                throw new AssertionError(e);
              }
            }))
        .when(helper).getSynchronizationContext();
    doReturn(mock(LoadBalancer.class)).when(lbProvider).newLoadBalancer(any(Helper.class));
    doReturn(ConfigOrError.fromConfig(new Object()))
        .when(lbProvider).parseLoadBalancingPolicyConfig(ArgumentMatchers.<Map<String, ?>>any());
  }

  @Test
  public void childPolicyWrapper_refCounted() {
    String target = "target";
    ChildPolicyWrapper childPolicy = factory.createOrGet(target);
    assertThat(factory.childPolicyMap.keySet()).containsExactly(target);

    ChildPolicyWrapper childPolicy2 = factory.createOrGet(target);
    assertThat(factory.childPolicyMap.keySet()).containsExactly(target);
    assertThat(childPolicy2).isEqualTo(childPolicy);

    factory.release(childPolicy2);
    assertThat(factory.childPolicyMap.keySet()).containsExactly(target);

    factory.release(childPolicy);
    assertThat(factory.childPolicyMap).isEmpty();

    try {
      factory.release(childPolicy);
      fail("should not be able to access already released policy");
    } catch (IllegalStateException e) {
      assertThat(e).hasMessageThat().contains("already released");
    }
  }

  private void setState(ChildPolicyWrapper policyWrapper, ConnectivityState newState) {
    policyWrapper.getHelper().updateBalancingState(newState, picker);
  }
  
  @Test
  public void childLoadBalancingPolicy_multiTarget() {
    List<String> targets = new ArrayList<>();
    List<ChildPolicyWrapper> policyWrappers = new ArrayList<>();

    for (int i = 1; i <= 3; i++) {
      String target = "target" + i;
      targets.add(target);
      policyWrappers.add(factory.createOrGet(target));
    }

    // Set to states: null, READY, null
    setState(policyWrappers.get(1), ConnectivityState.READY);
    ChildPolicyWrapper childPolicy = factory.createOrGet(targets);
    assertSame(policyWrappers.get(1), childPolicy);
    factory.release(childPolicy);

    // Set to states: null, CONNECTING, null
    setState(policyWrappers.get(1), ConnectivityState.CONNECTING);
    childPolicy = factory.createOrGet(targets);
    assertSame(policyWrappers.get(1), childPolicy);
    factory.release(childPolicy);

    // Set to states: null, CONNECTING, READY
    setState(policyWrappers.get(2), ConnectivityState.READY);
    childPolicy = factory.createOrGet(targets);
    assertSame(policyWrappers.get(2), childPolicy);
    factory.release(childPolicy);

    // Set to states: READY, CONNECTING, READY
    setState(policyWrappers.get(0), ConnectivityState.READY);
    childPolicy = factory.createOrGet(targets);
    assertSame(policyWrappers.get(0), childPolicy);
    factory.release(childPolicy);

    // Set to states: TRANSIENT_FAILURE, CONNECTING, READY
    setState(policyWrappers.get(0), ConnectivityState.TRANSIENT_FAILURE);
    childPolicy = factory.createOrGet(targets);
    assertSame(policyWrappers.get(2), childPolicy);
    factory.release(childPolicy);

    // Set to states: TRANSIENT_FAILURE, TRANSIENT_FAILURE, TRANSIENT_FAILURE
    setState(policyWrappers.get(1), ConnectivityState.TRANSIENT_FAILURE);
    setState(policyWrappers.get(2), ConnectivityState.TRANSIENT_FAILURE);
    childPolicy = factory.createOrGet(targets);
    assertSame(policyWrappers.get(0), childPolicy);
    factory.release(childPolicy);

    // Set to states: CONNECTING, CONNECTING, CONNECTING
    setState(policyWrappers.get(0), ConnectivityState.CONNECTING);
    setState(policyWrappers.get(1), ConnectivityState.CONNECTING);
    setState(policyWrappers.get(2), ConnectivityState.CONNECTING);
    childPolicy = factory.createOrGet(targets);
    assertSame(policyWrappers.get(0), childPolicy);
    factory.release(childPolicy);

    // Cleanup
    for (ChildPolicyWrapper policyWrapper : policyWrappers) {
      factory.release(policyWrapper);
    }
  }
  
  @Test
  public void childLoadBalancingPolicy_effectiveChildPolicy() {
    LoadBalancerProvider mockProvider = mock(LoadBalancerProvider.class);
    ChildLoadBalancingPolicy childLbPolicy =
        new ChildLoadBalancingPolicy(
            "targetFieldName",
            ImmutableMap.<String, Object>of("foo", "bar"),
            mockProvider);

    assertThat(childLbPolicy.getEffectiveChildPolicy("target"))
        .containsExactly("foo", "bar", "targetFieldName", "target");
    assertThat(childLbPolicy.getEffectiveLbProvider()).isEqualTo(mockProvider);
  }

  @Test
  public void childLoadBalancingPolicy_noPolicyProvided() {
    LoadBalancerProvider mockProvider = mock(LoadBalancerProvider.class);
    when(mockProvider.getPolicyName()).thenReturn("rls");
    when(mockProvider.isAvailable()).thenReturn(true);

    LoadBalancerRegistry.getDefaultRegistry().register(mockProvider);
    try {
      ChildLoadBalancingPolicy.create(
          "targetFieldName",
          ImmutableList.<Map<String, ?>>of(
              ImmutableMap.<String, Object>of(
                  "rls", ImmutableMap.of(), "rls2", ImmutableMap.of())));
      fail("parsing exception expected");
    } catch (InvalidChildPolicyConfigException e) {
      assertThat(e).hasMessageThat()
          .contains("childPolicy should have exactly one loadbalancing policy");
    } finally {
      LoadBalancerRegistry.getDefaultRegistry().deregister(mockProvider);
    }
  }

  @Test
  public void childLoadBalancingPolicy_tooManyChildPolicies() {
    try {
      ChildLoadBalancingPolicy
          .create("targetFieldName", ImmutableList.<Map<String, ?>>of());
      fail("parsing exception expected");
    } catch (InvalidChildPolicyConfigException e) {
      assertThat(e).hasMessageThat().contains("no valid childPolicy found");
    }
  }

  @Test
  public void updateBalancingState_triggersListener() {
    ChildPolicyWrapper childPolicyWrapper = factory.createOrGet("foo.google.com");
    ChildPolicyReportingHelper childPolicyReportingHelper = childPolicyWrapper.getHelper();
    SubchannelPicker childPicker = mock(SubchannelPicker.class);

    childPolicyReportingHelper.updateBalancingState(ConnectivityState.READY, childPicker);

    verify(childLbStatusListener).onStatusChanged(ConnectivityState.READY);
    assertThat(childPolicyWrapper.getPicker()).isEqualTo(childPicker);
    // picker governs childPickers will be reported to parent LB
    verify(helper).updateBalancingState(ConnectivityState.READY, picker);
  }
}
