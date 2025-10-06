/*
 * Copyright 2025 The gRPC Authors
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import io.grpc.ConnectivityState;
import io.grpc.ConnectivityStateInfo;
import io.grpc.EquivalentAddressGroup;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancer.CreateSubchannelArgs;
import io.grpc.LoadBalancer.ResolvedAddresses;
import io.grpc.LoadBalancer.Subchannel;
import io.grpc.LoadBalancer.SubchannelStateListener;
import io.grpc.LoadBalancerProvider;
import io.grpc.Status;
import io.grpc.internal.TestUtils;
import io.grpc.util.RandomSubsettingLoadBalancer.RandomSubsettingLoadBalancerConfig;
import java.net.SocketAddress;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.mockito.stubbing.Answer;

public class RandomSubsettingLoadBalancerTest {
  @Rule
  public final MockitoRule mockitoRule = MockitoJUnit.rule();

  @Mock
  private LoadBalancer.Helper mockHelper;
  @Mock
  private LoadBalancer mockChildLb;
  @Mock
  private SocketAddress mockSocketAddress;

  @Captor
  private ArgumentCaptor<ResolvedAddresses> resolvedAddrCaptor;

  private BackendDetails backendDetails;

  private RandomSubsettingLoadBalancer loadBalancer;

  private final LoadBalancerProvider mockChildLbProvider =
      new TestUtils.StandardLoadBalancerProvider("foo_policy") {
        @Override
        public LoadBalancer newLoadBalancer(LoadBalancer.Helper helper) {
          return mockChildLb;
        }
      };

  private final LoadBalancerProvider roundRobinLbProvider =
      new TestUtils.StandardLoadBalancerProvider("round_robin") {
        @Override
        public LoadBalancer newLoadBalancer(LoadBalancer.Helper helper) {
          return new RoundRobinLoadBalancer(helper);
        }
      };

  private Object newChildConfig(LoadBalancerProvider provider, Object config) {
    return GracefulSwitchLoadBalancer.createLoadBalancingPolicyConfig(provider, config);
  }

  private RandomSubsettingLoadBalancerConfig createRandomSubsettingLbConfig(
      int subsetSize, LoadBalancerProvider childLbProvider, Object childConfig) {
    return new RandomSubsettingLoadBalancer.RandomSubsettingLoadBalancerConfig.Builder()
            .setSubsetSize(subsetSize)
            .setChildConfig(newChildConfig(childLbProvider, childConfig))
            .build();
  }

  private BackendDetails setupBackends(int backendCount) {
    List<EquivalentAddressGroup> servers = Lists.newArrayList();
    Map<List<EquivalentAddressGroup>, Subchannel> subchannels = Maps.newLinkedHashMap();

    for (int i = 0; i < backendCount; i++) {
      SocketAddress addr = new FakeSocketAddress("server" + i);
      EquivalentAddressGroup addressGroup = new EquivalentAddressGroup(addr);
      servers.add(addressGroup);
      Subchannel subchannel = mock(Subchannel.class);
      subchannels.put(Arrays.asList(addressGroup), subchannel);
    }

    return new BackendDetails(servers, subchannels);
  }

  @Before
  public void setUp() {
    loadBalancer = new RandomSubsettingLoadBalancer(mockHelper);

    int backendSize = 5;
    backendDetails = setupBackends(backendSize);
  }

  @Test
  public void handleNameResolutionError() {
    int subsetSize = 2;
    Object childConfig = "someConfig";

    RandomSubsettingLoadBalancerConfig config = createRandomSubsettingLbConfig(
        subsetSize, mockChildLbProvider, childConfig);

    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder()
            .setAddresses(ImmutableList.of(new EquivalentAddressGroup(mockSocketAddress)))
            .setLoadBalancingPolicyConfig(config)
            .build());

    loadBalancer.handleNameResolutionError(Status.DEADLINE_EXCEEDED);
    verify(mockChildLb).handleNameResolutionError(Status.DEADLINE_EXCEEDED);
  }

  @Test
  public void shutdown() {
    int subsetSize = 2;
    Object childConfig = "someConfig";

    RandomSubsettingLoadBalancerConfig config = createRandomSubsettingLbConfig(
        subsetSize, mockChildLbProvider, childConfig);

    loadBalancer.acceptResolvedAddresses(
        ResolvedAddresses.newBuilder()
            .setAddresses(ImmutableList.of(new EquivalentAddressGroup(mockSocketAddress)))
            .setLoadBalancingPolicyConfig(config)
            .build());

    loadBalancer.shutdown();
    verify(mockChildLb).shutdown();
  }

  @Test
  public void acceptResolvedAddresses_mockedChildLbPolicy() {
    int subsetSize = 3;
    Object childConfig = "someConfig";

    RandomSubsettingLoadBalancerConfig config = createRandomSubsettingLbConfig(
        subsetSize, mockChildLbProvider, childConfig);

    ResolvedAddresses resolvedAddresses =
        ResolvedAddresses.newBuilder()
            .setAddresses(ImmutableList.copyOf(backendDetails.servers))
            .setLoadBalancingPolicyConfig(config)
            .build();

    loadBalancer.acceptResolvedAddresses(resolvedAddresses);

    verify(mockChildLb).acceptResolvedAddresses(resolvedAddrCaptor.capture());
    assertThat(resolvedAddrCaptor.getValue().getAddresses().size()).isEqualTo(subsetSize);
    assertThat(resolvedAddrCaptor.getValue().getLoadBalancingPolicyConfig()).isEqualTo(childConfig);
  }

  @Test
  public void acceptResolvedAddresses_roundRobinChildLbPolicy() {
    int subsetSize = 3;
    Object childConfig = null;

    RandomSubsettingLoadBalancerConfig config = createRandomSubsettingLbConfig(
        subsetSize, roundRobinLbProvider, childConfig);

    ResolvedAddresses resolvedAddresses =
        ResolvedAddresses.newBuilder()
            .setAddresses(ImmutableList.copyOf(backendDetails.servers))
            .setLoadBalancingPolicyConfig(config)
            .build();

    loadBalancer.acceptResolvedAddresses(resolvedAddresses);

    int insubset = 0;
    for (Subchannel subchannel : backendDetails.subchannels.values()) {
      LoadBalancer.SubchannelStateListener ssl =
          backendDetails.subchannelStateListeners.get(subchannel);
      if (ssl != null) { // it might be null if it's not in the subset.
        insubset += 1;
        ssl.onSubchannelState(ConnectivityStateInfo.forNonError(ConnectivityState.READY));
      }
    }

    assertThat(insubset).isEqualTo(subsetSize);
  }

  // verifies: https://github.com/grpc/proposal/blob/master/A68_graphics/subsetting100-100-5.png
  @Test
  public void backendsCanBeDistributedEvenly_subsetting100_100_5() {
    verifyConnectionsByServer(100, 100, 5, 15);
  }

  // verifies https://github.com/grpc/proposal/blob/master/A68_graphics/subsetting100-100-25.png
  @Test
  public void backendsCanBeDistributedEvenly_subsetting100_100_25() {
    verifyConnectionsByServer(100, 100, 25, 40);
  }

  // verifies: https://github.com/grpc/proposal/blob/master/A68_graphics/subsetting100-10-5.png
  @Test
  public void backendsCanBeDistributedEvenly_subsetting100_10_5() {
    verifyConnectionsByServer(100, 10, 5, 65);
  }

  // verifies: https://github.com/grpc/proposal/blob/master/A68_graphics/subsetting500-10-5.png
  @Test
  public void backendsCanBeDistributedEvenly_subsetting500_10_5() {
    verifyConnectionsByServer(500, 10, 5, 600);
  }

  // verifies: https://github.com/grpc/proposal/blob/master/A68_graphics/subsetting2000-10-5.png
  @Test
  public void backendsCanBeDistributedEvenly_subsetting2000_100_5() {
    verifyConnectionsByServer(2000, 10, 5, 1200);
  }

  public void verifyConnectionsByServer(
      int clientsCount, int serversCount, int subsetSize, int expectedMaxConnections) {
    backendDetails = setupBackends(serversCount);
    Object childConfig = "someConfig";

    List<RandomSubsettingLoadBalancerConfig> configs = Lists.newArrayList();
    for (int i = 0; i < clientsCount; i++) {
      configs.add(createRandomSubsettingLbConfig(subsetSize, mockChildLbProvider, childConfig));
    }

    Map<SocketAddress, Integer> connectionsByServer = Maps.newLinkedHashMap();

    for (RandomSubsettingLoadBalancerConfig config : configs) {
      ResolvedAddresses resolvedAddresses =
          ResolvedAddresses.newBuilder()
              .setAddresses(ImmutableList.copyOf(backendDetails.servers))
              .setLoadBalancingPolicyConfig(config)
              .build();

      loadBalancer = new RandomSubsettingLoadBalancer(mockHelper);
      loadBalancer.acceptResolvedAddresses(resolvedAddresses);

      verify(mockChildLb, atLeastOnce()).acceptResolvedAddresses(resolvedAddrCaptor.capture());
      // Verify ChildLB is only getting subsetSize ResolvedAddresses each time
      assertThat(resolvedAddrCaptor.getValue().getAddresses().size()).isEqualTo(config.subsetSize);

      for (EquivalentAddressGroup eag : resolvedAddrCaptor.getValue().getAddresses()) {
        for (SocketAddress addr : eag.getAddresses()) {
          Integer prev = connectionsByServer.getOrDefault(addr, 0);
          connectionsByServer.put(addr, prev + 1);
        }
      }
    }

    int maxConnections = Collections.max(connectionsByServer.values());

    assertThat(maxConnections <= expectedMaxConnections).isTrue();
  }

  private class BackendDetails {
    private final List<EquivalentAddressGroup> servers;
    private final Map<List<EquivalentAddressGroup>, Subchannel> subchannels;
    private final Map<Subchannel, LoadBalancer.SubchannelStateListener> subchannelStateListeners;

    BackendDetails(List<EquivalentAddressGroup> servers,
                   Map<List<EquivalentAddressGroup>, Subchannel> subchannels) {
      this.servers = servers;
      this.subchannels = subchannels;
      this.subchannelStateListeners = Maps.newLinkedHashMap();

      when(mockHelper.createSubchannel(any(LoadBalancer.CreateSubchannelArgs.class))).then(
          new Answer<Subchannel>() {
            @Override
            public Subchannel answer(InvocationOnMock invocation) throws Throwable {
              CreateSubchannelArgs args = (CreateSubchannelArgs) invocation.getArguments()[0];
              final Subchannel subchannel = backendDetails.subchannels.get(args.getAddresses());
              when(subchannel.getAllAddresses()).thenReturn(args.getAddresses());
              when(subchannel.getAttributes()).thenReturn(args.getAttributes());
              doAnswer(new Answer<Void>() {
                @Override
                public Void answer(InvocationOnMock invocation) throws Throwable {
                  subchannelStateListeners.put(subchannel,
                        (SubchannelStateListener) invocation.getArguments()[0]);
                  return null;
                }
              }).when(subchannel).start(any(SubchannelStateListener.class));
              return subchannel;
            }
          });
    }
  }

  private static class FakeSocketAddress extends SocketAddress {
    final String name;

    FakeSocketAddress(String name) {
      this.name = name;
    }

    @Override
    public String toString() {
      return "FakeSocketAddress-" + name;
    }
  }
}
