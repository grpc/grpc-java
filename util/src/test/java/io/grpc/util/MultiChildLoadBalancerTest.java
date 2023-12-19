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
import static io.grpc.ConnectivityState.CONNECTING;
import static io.grpc.ConnectivityState.READY;
import static io.grpc.ConnectivityState.SHUTDOWN;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.google.common.collect.Lists;
import io.grpc.Attributes;
import io.grpc.ConnectivityState;
import io.grpc.ConnectivityStateInfo;
import io.grpc.EquivalentAddressGroup;
import io.grpc.LoadBalancer;
import io.grpc.Status;
import io.grpc.util.AbstractTestHelper.FakeSocketAddress;
import io.grpc.util.MultiChildLoadBalancer.ChildLbState;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@RunWith(JUnit4.class)
public class MultiChildLoadBalancerTest {
  @Rule
  public final MockitoRule mocks = MockitoJUnit.rule();

  private final List<EquivalentAddressGroup> servers = Lists.newArrayList();
  private final Map<List<EquivalentAddressGroup>, LoadBalancer.Subchannel> subchannels =
      new ConcurrentHashMap<>();
  @Captor
  private ArgumentCaptor<LoadBalancer.SubchannelPicker> pickerCaptor;
  @Captor
  private ArgumentCaptor<ConnectivityState> stateCaptor;
  @Captor
  private ArgumentCaptor<LoadBalancer.CreateSubchannelArgs> createArgsCaptor;
  private TestHelper testHelperInst = new TestHelper();
  private LoadBalancer.Helper mockHelper =
      mock(LoadBalancer.Helper.class, delegatesTo(testHelperInst));
  private TestLb loadBalancer;


  @Before
  public void setUp() {
    for (int i = 0; i < 3; i++) {
      SocketAddress addr = new FakeSocketAddress("server" + i);
      EquivalentAddressGroup eag = new EquivalentAddressGroup(addr);
      servers.add(eag);
    }

    loadBalancer = new TestLb(mockHelper);
  }

  @Test
  public void pickAfterResolved() throws Exception {
    Status addressesAcceptanceStatus = loadBalancer.acceptResolvedAddresses(
        LoadBalancer.ResolvedAddresses.newBuilder().setAddresses(servers).build());
    assertThat(addressesAcceptanceStatus.isOk()).isTrue();
    final LoadBalancer.Subchannel readySubchannel = subchannels.values().iterator().next();
    deliverSubchannelState(readySubchannel, ConnectivityStateInfo.forNonError(READY));

    verify(mockHelper, times(3)).createSubchannel(createArgsCaptor.capture());
    List<List<EquivalentAddressGroup>> capturedAddrs = new ArrayList<>();
    for (LoadBalancer.CreateSubchannelArgs arg : createArgsCaptor.getAllValues()) {
      capturedAddrs.add(arg.getAddresses());
    }

    assertThat(capturedAddrs).containsAtLeastElementsIn(subchannels.keySet());
    for (LoadBalancer.Subchannel subchannel : subchannels.values()) {
      verify(subchannel).requestConnection();
      verify(subchannel, never()).shutdown();
    }

    verify(mockHelper, times(2))
        .updateBalancingState(stateCaptor.capture(), pickerCaptor.capture());

    assertEquals(CONNECTING, stateCaptor.getAllValues().get(0));
    assertEquals(READY, stateCaptor.getAllValues().get(1));
    TestLb.TestSubchannelPicker subchannelPicker =
        (TestLb.TestSubchannelPicker) pickerCaptor.getValue();
    assertThat(subchannelPicker.getReadySubchannels()).containsExactly(readySubchannel);

    verifyNoMoreInteractions(mockHelper);
  }

  @Test
  public void pickAfterResolvedUpdatedHosts() throws Exception {
    Attributes.Key<String> key = Attributes.Key.create("check-that-it-is-propagated");
    FakeSocketAddress removedAddr = new FakeSocketAddress("removed");
    EquivalentAddressGroup removedEag = new EquivalentAddressGroup(removedAddr);
    FakeSocketAddress oldAddr = new FakeSocketAddress("old");
    EquivalentAddressGroup oldEag1 = new EquivalentAddressGroup(oldAddr);
    EquivalentAddressGroup oldEag2 = new EquivalentAddressGroup(
        oldAddr, Attributes.newBuilder().set(key, "oldattr").build());
    FakeSocketAddress newAddr = new FakeSocketAddress("new");
    EquivalentAddressGroup newEag = new EquivalentAddressGroup(
        newAddr, Attributes.newBuilder().set(key, "newattr").build());

    List<EquivalentAddressGroup> currentServers = Lists.newArrayList(removedEag, oldEag1);

    InOrder inOrder = inOrder(mockHelper);

    Status addressesAcceptanceStatus = loadBalancer.acceptResolvedAddresses(
        LoadBalancer.ResolvedAddresses.newBuilder().setAddresses(currentServers).build());
    assertThat(addressesAcceptanceStatus.isOk()).isTrue();
    LoadBalancer.Subchannel removedSubchannel = getSubchannel(removedEag);
    LoadBalancer.Subchannel oldSubchannel = getSubchannel(oldEag1);
    LoadBalancer.SubchannelStateListener removedListener =
        testHelperInst.getSubchannelStateListeners()
            .get(testHelperInst.getRealForMockSubChannel(removedSubchannel));

    inOrder.verify(mockHelper).updateBalancingState(eq(CONNECTING), pickerCaptor.capture());

    deliverSubchannelState(removedSubchannel, ConnectivityStateInfo.forNonError(READY));
    deliverSubchannelState(oldSubchannel, ConnectivityStateInfo.forNonError(READY));

    inOrder.verify(mockHelper, times(2)).updateBalancingState(eq(READY), pickerCaptor.capture());
    LoadBalancer.SubchannelPicker picker = pickerCaptor.getValue();
    assertThat(getList(picker)).containsExactly(removedSubchannel, oldSubchannel);

    verify(removedSubchannel, times(1)).requestConnection();
    verify(oldSubchannel, times(1)).requestConnection();

    assertThat(getChildEags(loadBalancer)).containsExactly(removedEag, oldEag1);

    // This time with Attributes
    List<EquivalentAddressGroup> latestServers = Lists.newArrayList(oldEag2, newEag);

    addressesAcceptanceStatus = loadBalancer.acceptResolvedAddresses(
        LoadBalancer.ResolvedAddresses.newBuilder().setAddresses(latestServers).build());
    assertThat(addressesAcceptanceStatus.isOk()).isTrue();

    LoadBalancer.Subchannel newSubchannel = getSubchannel(newEag);

    verify(newSubchannel, times(1)).requestConnection();
    verify(oldSubchannel, times(1)).updateAddresses(Arrays.asList(oldEag2));
    verify(removedSubchannel, times(1)).shutdown();

    removedListener.onSubchannelState(ConnectivityStateInfo.forNonError(SHUTDOWN));
    deliverSubchannelState(newSubchannel, ConnectivityStateInfo.forNonError(READY));

    assertThat(getChildEags(loadBalancer)).containsExactly(oldEag2, newEag);

    verify(mockHelper, times(3)).createSubchannel(any(LoadBalancer.CreateSubchannelArgs.class));
    inOrder.verify(mockHelper, times(2)).updateBalancingState(eq(READY), pickerCaptor.capture());

    verifyNoMoreInteractions(mockHelper);
  }

  @Test
  public void pickFromMultiAddressEags() throws Exception {
    List<SocketAddress> addressList1 = new ArrayList<>();
    List<SocketAddress> addressList2 = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      if (i % 2 == 0) {
        addressList1.add(new FakeSocketAddress("multi_" + i));
      } else {
        addressList2.add(new FakeSocketAddress("multi_" + i));
      }
    }

    EquivalentAddressGroup eag1 = new EquivalentAddressGroup(addressList1, Attributes.EMPTY);
    EquivalentAddressGroup eag2 = new EquivalentAddressGroup(addressList2, Attributes.EMPTY);

    List<EquivalentAddressGroup> multiGroups = Arrays.asList(eag1, eag2);

    Status addressesAcceptanceStatus = loadBalancer.acceptResolvedAddresses(
        LoadBalancer.ResolvedAddresses.newBuilder().setAddresses(multiGroups).build());

    assertTrue(addressesAcceptanceStatus.isOk());
    LoadBalancer.Subchannel evens = subchannels.get(Collections.singletonList(eag1));
    deliverSubchannelState(evens, ConnectivityStateInfo.forNonError(READY));
    verify(mockHelper).updateBalancingState(eq(READY), pickerCaptor.capture());
    assertThat(pickerCaptor.getValue()).isInstanceOf(TestLb.TestSubchannelPicker.class);
    assertThat(((TestLb.TestSubchannelPicker)pickerCaptor.getValue()).childPickerMap).hasSize(2);
  }

  private List<LoadBalancer.Subchannel> getList(LoadBalancer.SubchannelPicker picker) {
    if (picker instanceof LoadBalancer.FixedResultPicker) {
      LoadBalancer.Subchannel subchannel = picker.pickSubchannel(null).getSubchannel();
      return subchannel != null ? Collections.singletonList(subchannel) : Collections.emptyList();
    }
    if (picker instanceof TestLb.TestSubchannelPicker) {
      List<LoadBalancer.Subchannel> subchannelsInPicker = new ArrayList<>();
      for (LoadBalancer.SubchannelPicker childPicker :
          ((TestLb.TestSubchannelPicker)picker).childPickerMap.values()) {
        subchannelsInPicker.add(childPicker.pickSubchannel(null).getSubchannel());
      }
      return subchannelsInPicker;
    }
    return Collections.emptyList();
  }

  private LoadBalancer.Subchannel getSubchannel(EquivalentAddressGroup removedEag) {
    return subchannels.get(Collections.singletonList(removedEag));
  }

  private static List<Object> getChildEags(MultiChildLoadBalancer loadBalancer) {
    return loadBalancer.getChildLbStates().stream()
        .map(ChildLbState::getEag)
        // .map(EquivalentAddressGroup::getAddresses)
        .collect(Collectors.toList());
  }

  private void deliverSubchannelState(LoadBalancer.Subchannel subchannel,
                                      ConnectivityStateInfo newState) {
    testHelperInst.deliverSubchannelState(subchannel, newState);
  }

  private class TestLb extends MultiChildLoadBalancer {

    protected TestLb(Helper mockHelper) {
      super(mockHelper);
    }

    @Override
    protected SubchannelPicker getSubchannelPicker(Map<Object, SubchannelPicker> childPickers) {
      return new TestSubchannelPicker(childPickers);
    }

    private class TestSubchannelPicker extends SubchannelPicker {
      Map<Object, SubchannelPicker> childPickerMap;
      Map<Object, ConnectivityState> childStates = new HashMap<>();

      TestSubchannelPicker(Map<Object, SubchannelPicker> childPickers) {
        childPickerMap = childPickers;
        for (Object key : childPickerMap.keySet()) {
          childStates.put(key, getChildLbState(key).getCurrentState());
        }
      }

      List<Subchannel>  getReadySubchannels() {
        List<Subchannel> readySubchannels = new ArrayList<>();
        for ( Map.Entry<Object, ConnectivityState> cur : childStates.entrySet()) {
          if (cur.getValue() == READY) {
            Subchannel s = subchannels.get(Arrays.asList(getChildLbState(cur.getKey()).getEag()));
            readySubchannels.add(s);
          }
        }
        return readySubchannels;
      }

      @Override
      public PickResult pickSubchannel(PickSubchannelArgs args) {
        return childPickerMap.values().iterator().next().pickSubchannel(args); // Always use the 1st
      }
    }
  }

  private class TestHelper extends AbstractTestHelper {

    @Override
    public Map<List<EquivalentAddressGroup>, LoadBalancer.Subchannel> getSubchannelMap() {
      return subchannels;
    }
  }

}
