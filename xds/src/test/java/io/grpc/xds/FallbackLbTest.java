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

package io.grpc.xds;

import static com.google.common.truth.Truth.assertThat;
import static io.grpc.ConnectivityState.CONNECTING;
import static io.grpc.LoadBalancer.ATTR_LOAD_BALANCING_CONFIG;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.grpc.Attributes;
import io.grpc.EquivalentAddressGroup;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancer.ResolvedAddresses;
import io.grpc.LoadBalancer.SubchannelPicker;
import io.grpc.LoadBalancerProvider;
import io.grpc.LoadBalancerRegistry;
import io.grpc.internal.JsonParser;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests for {@link FallbackLb}.
 */
@RunWith(JUnit4.class)
// TODO(creamsoup) use parsed service config
@SuppressWarnings("deprecation")
public class FallbackLbTest {

  private final LoadBalancerProvider fallbackProvider1 = new LoadBalancerProvider() {
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
      return "fallback_1";
    }

    @Override
    public LoadBalancer newLoadBalancer(Helper helper) {
      helpers1.add(helper);
      LoadBalancer balancer = mock(LoadBalancer.class);
      balancers1.add(balancer);
      return balancer;
    }
  };

  private final LoadBalancerProvider fallbackProvider2 = new LoadBalancerProvider() {
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
      return "fallback_2";
    }

    @Override
    public LoadBalancer newLoadBalancer(Helper helper) {
      // just return mock and recored helper and balancer
      helpers2.add(helper);
      LoadBalancer balancer = mock(LoadBalancer.class);
      balancers2.add(balancer);
      return balancer;
    }
  };

  private final Helper helper = mock(Helper.class);
  private final List<Helper> helpers1 = new ArrayList<>();
  private final List<Helper> helpers2 = new ArrayList<>();
  private final List<LoadBalancer> balancers1 = new ArrayList<>();
  private final List<LoadBalancer> balancers2 = new ArrayList<>();

  private LoadBalancer fallbackLb;

  @Before
  public void setUp() {
    LoadBalancerRegistry lbRegistry = new LoadBalancerRegistry();
    lbRegistry.register(fallbackProvider1);
    lbRegistry.register(fallbackProvider2);
    fallbackLb = new FallbackLb(helper, lbRegistry);

    assertThat(helpers1).isEmpty();
    assertThat(helpers2).isEmpty();
    assertThat(balancers1).isEmpty();
    assertThat(balancers2).isEmpty();
  }

  @Test
  public void handlePolicyChanges() throws Exception {
    EquivalentAddressGroup eag111 = new EquivalentAddressGroup(mock(SocketAddress.class));
    EquivalentAddressGroup eag112 = new EquivalentAddressGroup(mock(SocketAddress.class));
    List<EquivalentAddressGroup> eags11 = ImmutableList.of(eag111, eag112);
    String lbConfigRaw11 = "{"
        + "\"fallbackPolicy\" : [{\"fallback_1\" : { \"fallback_1_option\" : \"yes\"}}]"
        + "}";
    @SuppressWarnings("unchecked")
    Map<String, ?> lbConfig11 = (Map<String, ?>) JsonParser.parse(lbConfigRaw11);
    fallbackLb.handleResolvedAddresses(ResolvedAddresses.newBuilder()
        .setAddresses(eags11)
        .setAttributes(Attributes.newBuilder().set(ATTR_LOAD_BALANCING_CONFIG, lbConfig11).build())
        .build());

    assertThat(helpers1).hasSize(1);
    assertThat(balancers1).hasSize(1);
    Helper helper1 = helpers1.get(0);
    LoadBalancer balancer1 = balancers1.get(0);
    verify(balancer1).handleResolvedAddresses(ResolvedAddresses.newBuilder()
        .setAddresses(eags11)
        .setAttributes(Attributes.newBuilder()
            .set(ATTR_LOAD_BALANCING_CONFIG, ImmutableMap.of("fallback_1_option", "yes")).build())
        .build());

    SubchannelPicker picker1 = mock(SubchannelPicker.class);
    helper1.updateBalancingState(CONNECTING, picker1);
    verify(helper).updateBalancingState(CONNECTING, picker1);

    EquivalentAddressGroup eag121 = new EquivalentAddressGroup(mock(SocketAddress.class));
    List<EquivalentAddressGroup> eags12 = ImmutableList.of(eag121);
    String lbConfigRaw12 = "{"
        + "\"fallbackPolicy\" : [{\"fallback_1\" : { \"fallback_1_option\" : \"no\"}}]"
        + "}";
    @SuppressWarnings("unchecked")
    Map<String, ?> lbConfig12 = (Map<String, ?>) JsonParser.parse(lbConfigRaw12);
    fallbackLb.handleResolvedAddresses(ResolvedAddresses.newBuilder()
        .setAddresses(eags12)
        .setAttributes(Attributes.newBuilder().set(ATTR_LOAD_BALANCING_CONFIG, lbConfig12).build())
        .build());

    verify(balancer1).handleResolvedAddresses(ResolvedAddresses.newBuilder()
        .setAddresses(eags12)
        .setAttributes(Attributes.newBuilder()
            .set(ATTR_LOAD_BALANCING_CONFIG, ImmutableMap.of("fallback_1_option", "no")).build())
        .build());

    verify(balancer1, never()).shutdown();
    assertThat(helpers2).isEmpty();
    assertThat(balancers2).isEmpty();

    // change fallback policy to fallback_2
    EquivalentAddressGroup eag211 = new EquivalentAddressGroup(mock(SocketAddress.class));
    EquivalentAddressGroup eag212 = new EquivalentAddressGroup(mock(SocketAddress.class));
    List<EquivalentAddressGroup> eags21 = ImmutableList.of(eag211, eag212);
    String lbConfigRaw21 = "{"
        + "\"fallbackPolicy\" : [{\"fallback_2\" : { \"fallback_2_option\" : \"yes\"}}]"
        + "}";
    @SuppressWarnings("unchecked")
    Map<String, ?> lbConfig21 = (Map<String, ?>) JsonParser.parse(lbConfigRaw21);
    fallbackLb.handleResolvedAddresses(ResolvedAddresses.newBuilder()
        .setAddresses(eags21)
        .setAttributes(Attributes.newBuilder().set(ATTR_LOAD_BALANCING_CONFIG, lbConfig21).build())
        .build());

    verify(balancer1).shutdown();
    assertThat(helpers2).hasSize(1);
    assertThat(balancers2).hasSize(1);
    Helper helper2 = helpers2.get(0);
    LoadBalancer balancer2 = balancers2.get(0);
    verify(balancer1, never()).handleResolvedAddresses(ResolvedAddresses.newBuilder()
        .setAddresses(eags21)
        .setAttributes(Attributes.newBuilder()
            .set(ATTR_LOAD_BALANCING_CONFIG, ImmutableMap.of("fallback_2_option", "yes")).build())
        .build());
    verify(balancer2).handleResolvedAddresses(ResolvedAddresses.newBuilder()
        .setAddresses(eags21)
        .setAttributes(Attributes.newBuilder()
            .set(ATTR_LOAD_BALANCING_CONFIG, ImmutableMap.of("fallback_2_option", "yes")).build())
        .build());

    picker1 = mock(SubchannelPicker.class);
    helper1.updateBalancingState(CONNECTING, picker1);
    verify(helper, never()).updateBalancingState(CONNECTING, picker1);
    SubchannelPicker picker2 = mock(SubchannelPicker.class);
    helper2.updateBalancingState(CONNECTING, picker2);
    verify(helper).updateBalancingState(CONNECTING, picker2);

    EquivalentAddressGroup eag221 = new EquivalentAddressGroup(mock(SocketAddress.class));
    List<EquivalentAddressGroup> eags22 = ImmutableList.of(eag221);
    String lbConfigRaw22 = "{"
        + "\"fallbackPolicy\" : [{\"fallback_2\" : { \"fallback_2_option\" : \"no\"}}]"
        + "}";
    @SuppressWarnings("unchecked")
    Map<String, ?> lbConfig22 = (Map<String, ?>) JsonParser.parse(lbConfigRaw22);
    fallbackLb.handleResolvedAddresses(ResolvedAddresses.newBuilder()
        .setAddresses(eags22)
        .setAttributes(Attributes.newBuilder().set(ATTR_LOAD_BALANCING_CONFIG, lbConfig22).build())
        .build());

    verify(balancer2).handleResolvedAddresses(ResolvedAddresses.newBuilder()
        .setAddresses(eags22)
        .setAttributes(Attributes.newBuilder()
            .set(ATTR_LOAD_BALANCING_CONFIG, ImmutableMap.of("fallback_2_option", "no")).build())
        .build());

    assertThat(helpers1).hasSize(1);
    assertThat(balancers1).hasSize(1);
    assertThat(helpers2).hasSize(1);
    assertThat(balancers2).hasSize(1);

    verify(balancer2, never()).shutdown();
    fallbackLb.shutdown();
    verify(balancer2).shutdown();
  }

  @Test
  public void propagateAddressesToFallbackPolicy() throws Exception {
    EquivalentAddressGroup eag1 = new EquivalentAddressGroup(
        ImmutableList.<SocketAddress>of(new InetSocketAddress(8080)));
    EquivalentAddressGroup eag2 = new EquivalentAddressGroup(
        ImmutableList.<SocketAddress>of(new InetSocketAddress(8082)));
    List<EquivalentAddressGroup> eags = ImmutableList.of(eag1, eag2);

    String lbConfigRaw = "{"
        + "\"fallbackPolicy\" : [{\"fallback_1\" : { \"fallback_1_option\" : \"yes\"}}]"
        + "}";
    @SuppressWarnings("unchecked")
    Map<String, ?> lbConfig = (Map<String, ?>) JsonParser.parse(lbConfigRaw);
    fallbackLb.handleResolvedAddresses(ResolvedAddresses.newBuilder()
        .setAddresses(eags)
        .setAttributes(Attributes.newBuilder().set(ATTR_LOAD_BALANCING_CONFIG, lbConfig).build())
        .build());

    LoadBalancer balancer1 = balancers1.get(0);
    verify(balancer1).handleResolvedAddresses(
        ResolvedAddresses.newBuilder()
            .setAddresses(ImmutableList.of(eag1, eag2))
            .setAttributes(
                Attributes.newBuilder()
                    .set(ATTR_LOAD_BALANCING_CONFIG, ImmutableMap.of("fallback_1_option", "yes"))
                    .build())
            .build());
  }
}
