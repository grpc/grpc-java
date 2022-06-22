/*
 * Copyright 2022 The gRPC Authors
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
import static io.grpc.xds.XdsLbPolicies.WEIGHTED_TARGET_POLICY_NAME;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.testing.EqualsTester;
import io.grpc.Attributes;
import io.grpc.ConnectivityState;
import io.grpc.EquivalentAddressGroup;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancer.ResolvedAddresses;
import io.grpc.LoadBalancer.SubchannelPicker;
import io.grpc.LoadBalancerProvider;
import io.grpc.LoadBalancerRegistry;
import io.grpc.Status;
import io.grpc.SynchronizationContext;
import io.grpc.internal.ServiceConfigUtil.PolicySelection;
import io.grpc.xds.WeightedTargetLoadBalancerProvider.WeightedPolicySelection;
import io.grpc.xds.WeightedTargetLoadBalancerProvider.WeightedTargetConfig;
import io.grpc.xds.WrrLocalityLoadBalancer.WrrLocalityConfig;
import io.grpc.xds.XdsSubchannelPickers.ErrorPicker;
import java.net.SocketAddress;
import java.util.Map;
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

/**
 * Tests for {@link WrrLocalityLoadBalancerProvider}.
 */
@RunWith(JUnit4.class)
public class WrrLocalityLoadBalancerTest {
  @Rule
  public final MockitoRule mockito = MockitoJUnit.rule();

  @Mock
  private LoadBalancerProvider mockWeightedTargetProvider;
  @Mock
  private LoadBalancer mockWeightedTargetLb;
  @Mock
  private LoadBalancerProvider mockChildProvider;
  @Mock
  private LoadBalancer mockChildLb;
  @Mock
  private Helper mockHelper;
  @Mock
  private SocketAddress mockSocketAddress;

  @Captor
  private ArgumentCaptor<ResolvedAddresses> resolvedAddressesCaptor;
  @Captor
  private ArgumentCaptor<ConnectivityState> connectivityStateCaptor;
  @Captor
  private ArgumentCaptor<SubchannelPicker> errorPickerCaptor;

  private final EquivalentAddressGroup eag = new EquivalentAddressGroup(mockSocketAddress);

  private WrrLocalityLoadBalancer loadBalancer;
  private LoadBalancerRegistry lbRegistry = new LoadBalancerRegistry();

  private final SynchronizationContext syncContext = new SynchronizationContext(
      new Thread.UncaughtExceptionHandler() {
        @Override
        public void uncaughtException(Thread t, Throwable e) {
          throw new AssertionError(e);
        }
      });

  @Before
  public void setUp() {
    when(mockHelper.getSynchronizationContext()).thenReturn(syncContext);

    when(mockWeightedTargetProvider.newLoadBalancer(isA(Helper.class))).thenReturn(
        mockWeightedTargetLb);
    when(mockWeightedTargetProvider.getPolicyName()).thenReturn(WEIGHTED_TARGET_POLICY_NAME);
    when(mockWeightedTargetProvider.isAvailable()).thenReturn(true);
    lbRegistry.register(mockWeightedTargetProvider);

    when(mockChildProvider.newLoadBalancer(isA(Helper.class))).thenReturn(mockChildLb);
    when(mockChildProvider.getPolicyName()).thenReturn("round_robin");
    lbRegistry.register(mockWeightedTargetProvider);

    loadBalancer = new WrrLocalityLoadBalancer(mockHelper, lbRegistry);
  }

  @Test
  public void handleResolvedAddresses() {
    // A two locality cluster with a mock child LB policy.
    Locality localityOne = Locality.create("region1", "zone1", "subzone1");
    Locality localityTwo = Locality.create("region2", "zone2", "subzone2");
    PolicySelection childPolicy = new PolicySelection(mockChildProvider, null);

    // The child config is delivered wrapped in the wrr_locality config and the locality weights
    // in a ResolvedAddresses attribute.
    WrrLocalityConfig wlConfig = new WrrLocalityConfig(childPolicy);
    Map<Locality, Integer> localityWeights = ImmutableMap.of(localityOne, 1, localityTwo, 2);
    deliverAddresses(wlConfig, localityWeights);

    // Assert that the child policy and the locality weights were correctly mapped to a
    // WeightedTargetConfig.
    verify(mockWeightedTargetLb).handleResolvedAddresses(resolvedAddressesCaptor.capture());
    Object config = resolvedAddressesCaptor.getValue().getLoadBalancingPolicyConfig();
    assertThat(config).isInstanceOf(WeightedTargetConfig.class);
    WeightedTargetConfig wtConfig = (WeightedTargetConfig) config;
    assertThat(wtConfig.targets).hasSize(2);
    assertThat(wtConfig.targets).containsEntry(localityOne.toString(),
        new WeightedPolicySelection(1, childPolicy));
    assertThat(wtConfig.targets).containsEntry(localityTwo.toString(),
        new WeightedPolicySelection(2, childPolicy));
  }

  @Test
  public void handleResolvedAddresses_noLocalityWeights() {
    // A two locality cluster with a mock child LB policy.
    PolicySelection childPolicy = new PolicySelection(mockChildProvider, null);

    // The child config is delivered wrapped in the wrr_locality config and the locality weights
    // in a ResolvedAddresses attribute.
    WrrLocalityConfig wlConfig = new WrrLocalityConfig(childPolicy);
    deliverAddresses(wlConfig, null);

    // With no locality weights, we should get a TRANSIENT_FAILURE.
    verify(mockHelper).getAuthority();
    verify(mockHelper).updateBalancingState(eq(ConnectivityState.TRANSIENT_FAILURE),
        isA(ErrorPicker.class));
  }

  @Test
  public void handleNameResolutionError_noChildLb() {
    loadBalancer.handleNameResolutionError(Status.DEADLINE_EXCEEDED);

    verify(mockHelper).updateBalancingState(connectivityStateCaptor.capture(),
        errorPickerCaptor.capture());
    assertThat(connectivityStateCaptor.getValue()).isEqualTo(ConnectivityState.TRANSIENT_FAILURE);
    assertThat(errorPickerCaptor.getValue().toString()).isEqualTo(
        new ErrorPicker(Status.DEADLINE_EXCEEDED).toString());
  }

  @Test
  public void handleNameResolutionError_withChildLb() {
    deliverAddresses(new WrrLocalityConfig(new PolicySelection(mockChildProvider, null)),
        ImmutableMap.of(
            Locality.create("region", "zone", "subzone"), 1));
    loadBalancer.handleNameResolutionError(Status.DEADLINE_EXCEEDED);

    verify(mockHelper, never()).updateBalancingState(isA(ConnectivityState.class),
        isA(ErrorPicker.class));
    verify(mockWeightedTargetLb).handleNameResolutionError(Status.DEADLINE_EXCEEDED);
  }

  @Test
  public void localityWeightAttributeNotPropagated() {
    Locality locality = Locality.create("region1", "zone1", "subzone1");
    PolicySelection childPolicy = new PolicySelection(mockChildProvider, null);

    WrrLocalityConfig wlConfig = new WrrLocalityConfig(childPolicy);
    Map<Locality, Integer> localityWeights = ImmutableMap.of(locality, 1);
    deliverAddresses(wlConfig, localityWeights);

    // Assert that the child policy and the locality weights were correctly mapped to a
    // WeightedTargetConfig.
    verify(mockWeightedTargetLb).handleResolvedAddresses(resolvedAddressesCaptor.capture());
    assertThat(resolvedAddressesCaptor.getValue().getAttributes()
        .get(InternalXdsAttributes.ATTR_LOCALITY_WEIGHTS)).isNull();
  }

  @Test
  public void shutdown() {
    deliverAddresses(new WrrLocalityConfig(new PolicySelection(mockChildProvider, null)),
        ImmutableMap.of(
            Locality.create("region", "zone", "subzone"), 1));
    loadBalancer.shutdown();

    verify(mockWeightedTargetLb).shutdown();
  }

  @Test
  public void configEquality() {
    WrrLocalityConfig configOne = new WrrLocalityConfig(
        new PolicySelection(mockChildProvider, null));
    WrrLocalityConfig configTwo = new WrrLocalityConfig(
        new PolicySelection(mockChildProvider, null));
    WrrLocalityConfig differentConfig = new WrrLocalityConfig(
        new PolicySelection(mockChildProvider, "config"));

    new EqualsTester().addEqualityGroup(configOne, configTwo).addEqualityGroup(differentConfig)
        .testEquals();
  }

  private void deliverAddresses(WrrLocalityConfig config, Map<Locality, Integer> localityWeights) {
    loadBalancer.handleResolvedAddresses(
        ResolvedAddresses.newBuilder().setAddresses(ImmutableList.of(eag)).setAttributes(
                Attributes.newBuilder()
                    .set(InternalXdsAttributes.ATTR_LOCALITY_WEIGHTS, localityWeights).build())
            .setLoadBalancingPolicyConfig(config).build());
  }
}
