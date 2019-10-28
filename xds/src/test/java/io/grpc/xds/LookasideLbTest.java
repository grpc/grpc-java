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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.google.common.collect.ImmutableList;
import io.grpc.Attributes;
import io.grpc.EquivalentAddressGroup;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancer.ResolvedAddresses;
import io.grpc.LoadBalancer.SubchannelPicker;
import io.grpc.LoadBalancerRegistry;
import io.grpc.internal.JsonParser;
import io.grpc.xds.LookasideChannelLb.LookasideChannelCallback;
import io.grpc.xds.LookasideLb.LookasideChannelLbFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests for {@link LookasideLb}.
 */
@RunWith(JUnit4.class)
public class LookasideLbTest {

  private final Helper helper = mock(Helper.class);
  private final List<Helper> helpers = new ArrayList<>();
  private final List<LoadBalancer> balancers = new ArrayList<>();
  private final LookasideChannelLbFactory lookasideChannelLbFactory =
      new LookasideChannelLbFactory() {
        @Override
        public LoadBalancer newLoadBalancer(
            Helper helper, LookasideChannelCallback lookasideChannelCallback, String balancerName) {
          // just return a mock and record helper and balancer.
          helpers.add(helper);
          LoadBalancer balancer = mock(LoadBalancer.class);
          balancers.add(balancer);
          return balancer;
        }
      };

  private LoadBalancer lookasideLb = new LookasideLb(
      helper, mock(LookasideChannelCallback.class), lookasideChannelLbFactory,
      new LoadBalancerRegistry());


  @Test
  public void handleChildPolicyChangeThenBalancerNameChangeThenChildPolicyChange()
      throws Exception {
    assertThat(helpers).isEmpty();
    assertThat(balancers).isEmpty();

    List<EquivalentAddressGroup> eags = ImmutableList.of();
    String lbConfigRaw11 = "{\"balancerName\" : \"dns:///balancer1.example.com:8080\"}";
    @SuppressWarnings("unchecked")
    Map<String, ?> lbConfig11 = (Map<String, ?>) JsonParser.parse(lbConfigRaw11);
    ResolvedAddresses resolvedAddresses = ResolvedAddresses.newBuilder()
        .setAddresses(eags)
        .setAttributes(Attributes.newBuilder().set(ATTR_LOAD_BALANCING_CONFIG, lbConfig11).build())
        .build();
    lookasideLb.handleResolvedAddresses(resolvedAddresses);

    assertThat(helpers).hasSize(1);
    assertThat(balancers).hasSize(1);
    Helper helper1 = helpers.get(0);
    LoadBalancer balancer1 = balancers.get(0);
    verify(balancer1).handleResolvedAddresses(resolvedAddresses);

    SubchannelPicker picker1 = mock(SubchannelPicker.class);
    helper1.updateBalancingState(CONNECTING, picker1);
    verify(helper).updateBalancingState(CONNECTING, picker1);

    String lbConfigRaw12 = "{"
        + "\"balancerName\" : \"dns:///balancer1.example.com:8080\","
        + "\"childPolicy\" : [{\"unsupported\" : {\"key\" : \"val\"}}, {\"unsupported_2\" : {}}]"
        + "}";
    @SuppressWarnings("unchecked")
    Map<String, ?> lbConfig12 = (Map<String, ?>) JsonParser.parse(lbConfigRaw12);
    resolvedAddresses = ResolvedAddresses.newBuilder()
        .setAddresses(eags)
        .setAttributes(Attributes.newBuilder().set(ATTR_LOAD_BALANCING_CONFIG, lbConfig12).build())
        .build();
    lookasideLb.handleResolvedAddresses(resolvedAddresses);

    verify(balancer1).handleResolvedAddresses(resolvedAddresses);

    verify(balancer1, never()).shutdown();
    assertThat(helpers).hasSize(1);
    assertThat(balancers).hasSize(1);

    // change balancer name policy to balancer2.example.com
    String lbConfigRaw21 = "{\"balancerName\" : \"dns:///balancer2.example.com:8080\"}";
    @SuppressWarnings("unchecked")
    Map<String, ?> lbConfig21 = (Map<String, ?>) JsonParser.parse(lbConfigRaw21);
    resolvedAddresses = ResolvedAddresses.newBuilder()
        .setAddresses(eags)
        .setAttributes(Attributes.newBuilder().set(ATTR_LOAD_BALANCING_CONFIG, lbConfig21).build())
        .build();
    lookasideLb.handleResolvedAddresses(resolvedAddresses);

    verify(balancer1).shutdown();
    assertThat(helpers).hasSize(2);
    assertThat(balancers).hasSize(2);
    Helper helper2 = helpers.get(1);
    LoadBalancer balancer2 = balancers.get(1);
    verify(balancer1, never()).handleResolvedAddresses(resolvedAddresses);
    verify(balancer2).handleResolvedAddresses(resolvedAddresses);

    picker1 = mock(SubchannelPicker.class);
    helper1.updateBalancingState(CONNECTING, picker1);
    verify(helper, never()).updateBalancingState(CONNECTING, picker1);
    SubchannelPicker picker2 = mock(SubchannelPicker.class);
    helper2.updateBalancingState(CONNECTING, picker2);
    verify(helper).updateBalancingState(CONNECTING, picker2);

    String lbConfigRaw22 = "{"
        + "\"balancerName\" : \"dns:///balancer2.example.com:8080\","
        + "\"childPolicy\" : [{\"unsupported\" : {\"key\" : \"val\"}}, {\"unsupported_2\" : {}}]"
        + "}";
    @SuppressWarnings("unchecked")
    Map<String, ?> lbConfig22 = (Map<String, ?>) JsonParser.parse(lbConfigRaw22);
    resolvedAddresses = ResolvedAddresses.newBuilder()
        .setAddresses(eags)
        .setAttributes(Attributes.newBuilder().set(ATTR_LOAD_BALANCING_CONFIG, lbConfig22).build())
        .build();
    lookasideLb.handleResolvedAddresses(resolvedAddresses);

    verify(balancer2).handleResolvedAddresses(resolvedAddresses);

    assertThat(helpers).hasSize(2);
    assertThat(balancers).hasSize(2);

    verify(balancer2, never()).shutdown();
    lookasideLb.shutdown();
    verify(balancer2).shutdown();
  }

  @Test
  public void handleResolvedAddress_createLbChannel()
      throws Exception {
    // Test balancer created with the default real LookasideChannelLbFactory
    lookasideLb = new LookasideLb(helper, mock(LookasideChannelCallback.class));
    String lbConfigRaw11 = "{'balancerName' : 'dns:///balancer1.example.com:8080'}"
        .replace("'", "\"");
    @SuppressWarnings("unchecked")
    Map<String, ?> lbConfig11 = (Map<String, ?>) JsonParser.parse(lbConfigRaw11);
    ResolvedAddresses resolvedAddresses = ResolvedAddresses.newBuilder()
        .setAddresses(ImmutableList.<EquivalentAddressGroup>of())
        .setAttributes(Attributes.newBuilder().set(ATTR_LOAD_BALANCING_CONFIG, lbConfig11).build())
        .build();

    verify(helper, never()).createResolvingOobChannel(anyString());
    try {
      lookasideLb.handleResolvedAddresses(resolvedAddresses);
    } catch (RuntimeException e) {
      // Expected because helper is a mock and helper.createResolvingOobChannel() returns null.
    }
    verify(helper).createResolvingOobChannel("dns:///balancer1.example.com:8080");
  }
}
