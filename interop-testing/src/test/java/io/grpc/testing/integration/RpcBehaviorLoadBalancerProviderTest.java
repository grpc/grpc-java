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

package io.grpc.testing.integration;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.verify;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.grpc.Attributes;
import io.grpc.CallOptions;
import io.grpc.ConnectivityState;
import io.grpc.EquivalentAddressGroup;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancer.ResolvedAddresses;
import io.grpc.LoadBalancer.SubchannelPicker;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.internal.PickSubchannelArgsImpl;
import io.grpc.testing.TestMethodDescriptors;
import io.grpc.testing.integration.RpcBehaviorLoadBalancerProvider.RpcBehaviorConfig;
import io.grpc.testing.integration.RpcBehaviorLoadBalancerProvider.RpcBehaviorHelper;
import io.grpc.testing.integration.RpcBehaviorLoadBalancerProvider.RpcBehaviorLoadBalancer;
import io.grpc.testing.integration.RpcBehaviorLoadBalancerProvider.RpcBehaviorPicker;
import java.net.SocketAddress;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/**
 * Unit tests for {@link RpcBehaviorLoadBalancerProvider}.
 */
@RunWith(JUnit4.class)
public class RpcBehaviorLoadBalancerProviderTest {

  @Rule
  public final MockitoRule mocks = MockitoJUnit.rule();

  @Mock
  private LoadBalancer mockDelegateLb;

  @Mock
  private Helper mockHelper;

  @Mock
  private SubchannelPicker mockPicker;

  @Test
  public void parseValidConfig() {
    assertThat(buildConfig().rpcBehavior).isEqualTo("error-code-15");
  }

  @Test
  public void parseInvalidConfig() {
    Status status = new RpcBehaviorLoadBalancerProvider().parseLoadBalancingPolicyConfig(
            ImmutableMap.of("foo", "bar")).getError();
    assertThat(status.getDescription()).contains("rpcBehavior");
  }

  @Test
  public void handleResolvedAddressesDelegated() {
    RpcBehaviorLoadBalancer lb = new RpcBehaviorLoadBalancer(new RpcBehaviorHelper(mockHelper),
        mockDelegateLb);
    ResolvedAddresses resolvedAddresses = buildResolvedAddresses(buildConfig());
    lb.handleResolvedAddresses(resolvedAddresses);
    verify(mockDelegateLb).handleResolvedAddresses(resolvedAddresses);
  }

  @Test
  public void helperWrapsPicker() {
    RpcBehaviorHelper helper = new RpcBehaviorHelper(mockHelper);
    helper.setRpcBehavior("error-code-15");
    helper.updateBalancingState(ConnectivityState.READY, mockPicker);

    verify(mockHelper).updateBalancingState(eq(ConnectivityState.READY),
        isA(RpcBehaviorPicker.class));
  }

  @Test
  public void pickerAddsRpcBehaviorMetadata() {
    PickSubchannelArgsImpl args = new PickSubchannelArgsImpl(TestMethodDescriptors.voidMethod(),
        new Metadata(), CallOptions.DEFAULT);
    new RpcBehaviorPicker(mockPicker, "error-code-15").pickSubchannel(args);

    assertThat(args.getHeaders()
        .get(Metadata.Key.of("rpc-behavior", Metadata.ASCII_STRING_MARSHALLER))).isEqualTo(
        "error-code-15");
  }

  private RpcBehaviorConfig buildConfig() {
    RpcBehaviorConfig config = (RpcBehaviorConfig) new RpcBehaviorLoadBalancerProvider()
        .parseLoadBalancingPolicyConfig(
            ImmutableMap.of("rpcBehavior", "error-code-15")).getConfig();
    return config;
  }

  private ResolvedAddresses buildResolvedAddresses(RpcBehaviorConfig config) {
    ResolvedAddresses resolvedAddresses = ResolvedAddresses.newBuilder()
        .setLoadBalancingPolicyConfig(config)
        .setAddresses(ImmutableList.of(
            new EquivalentAddressGroup(new SocketAddress() {
            })))
        .setAttributes(Attributes.newBuilder().build()).build();
    return resolvedAddresses;
  }
}
