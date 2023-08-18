/*
 * Copyright 2023 The gRPC Authors
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
import io.grpc.internal.ServiceConfigUtil.PolicySelection;
import io.grpc.internal.TestUtils;
import io.grpc.util.DeterministicSubsettingLoadBalancer.DeterministicSubsettingLoadBalancerConfig;
import io.grpc.util.OutlierDetectionLoadBalancerTest.FakeSocketAddress;
import java.net.SocketAddress;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
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

public class DeterministicSubsettingLoadBalancerTest {

  private List<EquivalentAddressGroup> servers = Lists.newArrayList();
  private Map<List<EquivalentAddressGroup>, Subchannel> subchannels = Maps.newLinkedHashMap();

  private final Map<Subchannel, LoadBalancer.SubchannelStateListener> subchannelStateListeners =
      Maps.newLinkedHashMap();
  @Rule public final MockitoRule mockitoRule = MockitoJUnit.rule();
  @Mock private LoadBalancer.Helper mockHelper;
  @Mock private LoadBalancer mockChildLb;
  @Mock private SocketAddress mockSocketAddress;
  @Captor
  private ArgumentCaptor<ConnectivityState> connectivityStateCaptor;
  @Captor
  private ArgumentCaptor<LoadBalancer.SubchannelPicker> errorPickerCaptor;

  @Captor private ArgumentCaptor<ResolvedAddresses> resolvedAddrCaptor;

  private final LoadBalancerProvider mockChildLbProvider =
      new TestUtils.StandardLoadBalancerProvider("foo_policy") {
        @Override
        public LoadBalancer newLoadBalancer(LoadBalancer.Helper helper) {
          return mockChildLb;
        }
      };

  private DeterministicSubsettingLoadBalancer loadBalancer;

  private final LoadBalancerProvider roundRobinLbProvider =
      new TestUtils.StandardLoadBalancerProvider("round_robin") {
        @Override
        public LoadBalancer newLoadBalancer(LoadBalancer.Helper helper) {
          return new RoundRobinLoadBalancer(helper);
        }
      };

  private void setupBackends(int backendCount) {
    servers = Lists.newArrayList();
    subchannels = Maps.newLinkedHashMap();
    for (int i = 0; i < backendCount; i++) {
      SocketAddress addr = new FakeSocketAddress("server" + i);
      EquivalentAddressGroup e = new EquivalentAddressGroup(addr);
      servers.add(e);
      Subchannel sc = mock(Subchannel.class);
      subchannels.put(Arrays.asList(e), sc);
    }
  }

  @Before
  public void setUp() {
    loadBalancer = new DeterministicSubsettingLoadBalancer(mockHelper);
  }

  public void addMock() {
    when(mockHelper.createSubchannel(any(LoadBalancer.CreateSubchannelArgs.class))).then(
        new Answer<Subchannel>() {
          @Override
          public Subchannel answer(InvocationOnMock invocation) throws Throwable {
            CreateSubchannelArgs args = (CreateSubchannelArgs) invocation.getArguments()[0];
            final Subchannel subchannel = subchannels.get(args.getAddresses());
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

  @Test
  public void handleNameResoutionError_noChildLb() {
    loadBalancer.handleNameResolutionError(Status.DEADLINE_EXCEEDED);

    verify(mockHelper).updateBalancingState(connectivityStateCaptor.capture(),
      errorPickerCaptor.capture());
    assertThat(connectivityStateCaptor.getValue()).isEqualTo(ConnectivityState.TRANSIENT_FAILURE);
  }
  @Test
  public void handleNameResolutionError_withChildLb() {
    DeterministicSubsettingLoadBalancerConfig config = new DeterministicSubsettingLoadBalancer.DeterministicSubsettingLoadBalancerConfig.Builder()
      .setSubsetSize(2)
      .setClientIndex(0)
      .setSortAddresses(false)
      .setChildPolicy(new PolicySelection(mockChildLbProvider, null))
      .build();

    loadBalancer.acceptResolvedAddresses(ResolvedAddresses.newBuilder()
        .setAddresses(ImmutableList.of(new EquivalentAddressGroup(mockSocketAddress)))
        .setLoadBalancingPolicyConfig(config)
        .build());
    loadBalancer.handleNameResolutionError(Status.DEADLINE_EXCEEDED);

    verify(mockChildLb).handleNameResolutionError(Status.DEADLINE_EXCEEDED);
  }

  @Test
  public void shutdown() {
    DeterministicSubsettingLoadBalancerConfig config = new DeterministicSubsettingLoadBalancer.DeterministicSubsettingLoadBalancerConfig.Builder()
      .setSubsetSize(2)
      .setClientIndex(0)
      .setSortAddresses(false)
      .setChildPolicy(new PolicySelection(mockChildLbProvider, null))
      .build();

    loadBalancer.acceptResolvedAddresses(ResolvedAddresses.newBuilder()
      .setAddresses(ImmutableList.of(new EquivalentAddressGroup(mockSocketAddress)))
      .setLoadBalancingPolicyConfig(config)
      .build());
    loadBalancer.shutdown();
    verify(mockChildLb).shutdown();
  }

  @Test
  public void addressComparator() {
    setupBackends(5);
    List<SocketAddress> sorted = Lists.newArrayList();
    for (EquivalentAddressGroup eag: servers) {
      sorted.addAll(eag.getAddresses());
    }

    Collections.shuffle(servers);
    List<SocketAddress> addresses = Lists.newArrayList();
    for (EquivalentAddressGroup eag: servers) {
      addresses.addAll(eag.getAddresses());
    }

    assertThat(addresses).isNotEqualTo(sorted);
    addresses.sort(new DeterministicSubsettingLoadBalancer.AddressComparator());

    assertThat(addresses).isEqualTo(sorted);
  }

  @Test
  public void acceptResolvedAddresses_mocked() {
    int subsetSize = 3;
    DeterministicSubsettingLoadBalancerConfig config =
        new DeterministicSubsettingLoadBalancerConfig.Builder()
            .setSubsetSize(subsetSize)
            .setClientIndex(0)
            .setSortAddresses(true)
            .setChildPolicy(new PolicySelection(mockChildLbProvider, null))
            .build();

    ResolvedAddresses resolvedAddresses =
        ResolvedAddresses.newBuilder()
            .setAddresses(ImmutableList.of(new EquivalentAddressGroup(mockSocketAddress)))
            .setLoadBalancingPolicyConfig(config)
            .build();

    assertThat(loadBalancer.acceptResolvedAddresses(resolvedAddresses)).isTrue();

    verify(mockChildLb)
        .handleResolvedAddresses(
            resolvedAddresses.toBuilder()
                .setLoadBalancingPolicyConfig(config.childPolicy.getConfig())
                .build());
  }

  @Test
  public void acceptResolvedAddresses() {
    addMock();
    setupBackends(6);
    int subsetSize = 3;
    DeterministicSubsettingLoadBalancerConfig config =
        new DeterministicSubsettingLoadBalancerConfig.Builder()
            .setSubsetSize(subsetSize)
            .setClientIndex(0)
            .setSortAddresses(false)
            .setChildPolicy(new PolicySelection(roundRobinLbProvider, null))
            .build();

    ResolvedAddresses resolvedAddresses =
        ResolvedAddresses.newBuilder()
            .setAddresses(ImmutableList.copyOf(servers))
            .setLoadBalancingPolicyConfig(config)
            .build();

    assertThat(loadBalancer.acceptResolvedAddresses(resolvedAddresses)).isTrue();

    int insubset = 0;
    for (Subchannel subchannel : subchannels.values()) {
      LoadBalancer.SubchannelStateListener sc = subchannelStateListeners.get(subchannel);
      if (sc != null) { // it might be null if it's not in the subset.
        insubset += 1;
        sc.onSubchannelState(ConnectivityStateInfo.forNonError(ConnectivityState.READY));
      }
    }

    assertThat(insubset).isEqualTo(subsetSize);
  }

  @Test
  public void sortingBackends() {
    setupBackends(4);
    // Shuffle servers so that they're not in 0, 1, 2 order
    List<EquivalentAddressGroup> shuffledServers = Lists.newArrayList(
      servers.get(1), servers.get(3), servers.get(2), servers.get(0));
    int subsetSize = 2;
    DeterministicSubsettingLoadBalancerConfig sortConfig =
      new DeterministicSubsettingLoadBalancerConfig.Builder()
        .setSubsetSize(subsetSize)
        .setClientIndex(0)
        .setSortAddresses(true)
        .setChildPolicy(new PolicySelection(mockChildLbProvider, null))
        .build();

    DeterministicSubsettingLoadBalancerConfig dontSortConfig =
      new DeterministicSubsettingLoadBalancerConfig.Builder()
        .setSubsetSize(subsetSize)
        .setClientIndex(0)
        .setSortAddresses(false)
        .setChildPolicy(new PolicySelection(mockChildLbProvider, null))
        .build();

    List<DeterministicSubsettingLoadBalancerConfig> configs = Lists.newArrayList(sortConfig, dontSortConfig);
    List<List<EquivalentAddressGroup>> actual = Lists.newArrayList();
    for (DeterministicSubsettingLoadBalancerConfig config : configs) {
      ResolvedAddresses resolvedAddresses =
          ResolvedAddresses.newBuilder()
              .setAddresses(ImmutableList.copyOf(shuffledServers))
              .setLoadBalancingPolicyConfig(config)
              .build();
      loadBalancer.acceptResolvedAddresses(resolvedAddresses);
      verify(mockChildLb, atLeastOnce()).handleResolvedAddresses(resolvedAddrCaptor.capture());
      // Verify ChildLB is only getting subsetSize ResolvedAddresses each time
      assertThat(resolvedAddrCaptor.getValue().getAddresses().size()).isEqualTo(subsetSize);
      actual.add(resolvedAddrCaptor.getValue().getAddresses());
    }
    List<EquivalentAddressGroup> actualSorted = actual.get(0);
    List<EquivalentAddressGroup> actualUnsorted = actual.get(1);

    // We will sort, and then round 0 will shift from 0,1,2,3 to 3,0,1,2
    assertThat(actualSorted).isEqualTo(Lists.newArrayList(servers.get(3), servers.get(0)));
    // We will not sort, but round 0 will shift from 1,3,2,0 to 0,1,3,2 (same order given indices)
    assertThat(actualUnsorted).isEqualTo(Lists.newArrayList(servers.get(0), servers.get(1)));
  }

  @Test
  public void closesUnusedConns() {
    addMock();
    setupBackends(6);
    List<DeterministicSubsettingLoadBalancerConfig> configs = Lists.newArrayList();
    for (int i = 0; i < 2; i++) {
      configs.add(
          new DeterministicSubsettingLoadBalancerConfig.Builder()
              .setSubsetSize(3)
              .setClientIndex(i)
              .setSortAddresses(false)
              .setChildPolicy(new PolicySelection(roundRobinLbProvider, null))
              .build());
    }
    Iterator<Subchannel> scIterator = subchannels.values().iterator();
    scIterator.next(); // subchannel0
    Subchannel subchannel1 = scIterator.next();
    Subchannel subchannel2 = scIterator.next();
    scIterator.next(); // subchannel3
    Subchannel subchannel4 = scIterator.next();
    scIterator.next(); // subchannel5

    // In the first call to RR.acceptResolvedAddresses, all subchannels will be new
    // with nothing to close. in the second iteration, we need to remove the subchannels
    // from the first subset.
    List<List<Subchannel>> subsets =
        Lists.newArrayList(
            Lists.newArrayList(), Lists.newArrayList(subchannel4, subchannel1, subchannel2));
    int newconns = 0;

    for (int i = 0; i < 2; i++) {
      DeterministicSubsettingLoadBalancerConfig config = configs.get(i);
      ResolvedAddresses resolvedAddresses =
          ResolvedAddresses.newBuilder()
              .setAddresses(ImmutableList.copyOf(servers))
              .setLoadBalancingPolicyConfig(config)
              .build();
      loadBalancer.acceptResolvedAddresses(resolvedAddresses);
      for (Subchannel sc : subsets.get(i)) {
        verify(sc).shutdown();
      }
      for (Subchannel sc : subchannels.values()) {
        LoadBalancer.SubchannelStateListener ssl = subchannelStateListeners.get(sc);
        if (ssl != null) {
          newconns += 1;
          ssl.onSubchannelState(ConnectivityStateInfo.forNonError(ConnectivityState.READY));
        }
      }
      subchannelStateListeners.clear();
    }
    assertThat(newconns).isEqualTo(6);
  }

  @Test
  public void reusesConns() {
    addMock();
    setupBackends(3);
    List<DeterministicSubsettingLoadBalancerConfig> configs = Lists.newArrayList();
    for (int i = 0; i < 3; i++) {
      configs.add(
          new DeterministicSubsettingLoadBalancerConfig.Builder()
              .setSubsetSize(3)
              .setClientIndex(i)
              .setSortAddresses(false)
              .setChildPolicy(new PolicySelection(roundRobinLbProvider, null))
              .build());
    }

    List<Integer> perRun = Lists.newArrayList();

    for (DeterministicSubsettingLoadBalancerConfig config : configs) {
      ResolvedAddresses resolvedAddresses =
          ResolvedAddresses.newBuilder()
              .setAddresses(ImmutableList.copyOf(servers))
              .setLoadBalancingPolicyConfig(config)
              .build();
      loadBalancer.acceptResolvedAddresses(resolvedAddresses);
      int numSubchannelsOpened = 0;
      for (Subchannel subchannel : subchannels.values()) {
        LoadBalancer.SubchannelStateListener sc = subchannelStateListeners.get(subchannel);
        if (sc != null) {
          sc.onSubchannelState(ConnectivityStateInfo.forNonError(ConnectivityState.READY));
          numSubchannelsOpened += 1;
        }
      }
      perRun.add(numSubchannelsOpened);
      subchannelStateListeners.clear();
    }
    assertThat(perRun).isEqualTo(Lists.newArrayList(3, 0, 0));
  }

  @Test
  public void backendsCanBeDistributedEvenly() {
    // Backends can be distributed evenly, so they should be. Therefore, maxDiff = 0
    verifyCreatesSubsets(12, 8, 3, 0);
  }

  @Test
  public void backendsCanNotBeDistributedEvenly() {
    // Backends can't be distributed evenly because there are excluded backends in every round and
    // not enough clients to fill the last round. This provides 2 opportunities for a backend to be
    // excluded, so the maxDiff is its maximum, 2
    verifyCreatesSubsets(37, 22, 5, 2);
  }

  @Test
  public void notEnoughClientsForLastRound() {
    // There are no excluded backends in each round, but there are not enough clients for the
    // last round, meaning there is only one chance for a backend to be excluded.
    // Therefore, maxDiff =1
    verifyCreatesSubsets(20, 7, 5, 1);
  }

  @Test
  public void excludedBackendsInEveryRound() {
    // There are enough clients to fill the last round, but there are excluded backends
    // in every round, meaning there is only one chance for a backend to be excluded.
    // Therefore, maxDiff =1
    verifyCreatesSubsets(21, 8, 5, 1);
  }

  @Test
  public void excludedStartBiggerThanEnd() {
    // There are 3 excluded backends on each round, and sometimes the selected excluded backends
    // wrap around.
    verifyCreatesSubsets(7, 3, 4, 1);
  }

  public void verifyCreatesSubsets(int backends, int clients, int subsetSize, int maxDiff) {
    setupBackends(backends);
    List<DeterministicSubsettingLoadBalancerConfig> configs = Lists.newArrayList();
    for (int i = 0; i < clients; i++) {
      configs.add(
          new DeterministicSubsettingLoadBalancerConfig.Builder()
              .setSubsetSize(subsetSize)
              .setClientIndex(i)
              .setSortAddresses(false)
              .setChildPolicy(new PolicySelection(mockChildLbProvider, null))
              .build());
    }

    Map<SocketAddress, Integer> subsetDistn = Maps.newLinkedHashMap();

    for (DeterministicSubsettingLoadBalancerConfig config : configs) {
      ResolvedAddresses resolvedAddresses =
          ResolvedAddresses.newBuilder()
              .setAddresses(ImmutableList.copyOf(servers))
              .setLoadBalancingPolicyConfig(config)
              .build();
      loadBalancer.acceptResolvedAddresses(resolvedAddresses);
      verify(mockChildLb, atLeastOnce()).handleResolvedAddresses(resolvedAddrCaptor.capture());
      // Verify ChildLB is only getting subsetSize ResolvedAddresses each time
      assertThat(resolvedAddrCaptor.getValue().getAddresses().size()).isEqualTo(subsetSize);
      for (EquivalentAddressGroup eag : resolvedAddrCaptor.getValue().getAddresses()) {
        for (SocketAddress addr : eag.getAddresses()) {
          Integer prev = subsetDistn.getOrDefault(addr, 0);
          subsetDistn.put(addr, prev + 1);
        }
      }
    }
    int maxConns = Collections.max(subsetDistn.values());
    int minConns = Collections.min(subsetDistn.values());

    assertThat(maxConns <= minConns + maxDiff).isTrue();
  }
}
