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
import static org.junit.Assert.fail;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.google.common.collect.Lists;
import com.google.common.testing.EqualsTester;
import io.grpc.Attributes;
import io.grpc.ConnectivityState;
import io.grpc.ConnectivityStateInfo;
import io.grpc.EquivalentAddressGroup;
import io.grpc.LoadBalancer;
import io.grpc.Status;
import io.grpc.internal.PickFirstLoadBalancerProvider;
import io.grpc.util.AbstractTestHelper.FakeSocketAddress;
import io.grpc.util.MultiChildLoadBalancer.ChildLbState;
import io.grpc.util.MultiChildLoadBalancer.Endpoint;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
  private static final Attributes.Key<String> FOO = Attributes.Key.create("foo");

  @Rule
  public final MockitoRule mocks = MockitoJUnit.rule();

  private final List<EquivalentAddressGroup> servers = Lists.newArrayList();
  private final Map<List<EquivalentAddressGroup>, LoadBalancer.Subchannel> subchannels =
      new ConcurrentHashMap<>();
  private final Attributes affinity = Attributes.newBuilder().set(FOO, "bar").build();
  @Captor
  private ArgumentCaptor<LoadBalancer.SubchannelPicker> pickerCaptor;
  @Captor
  private ArgumentCaptor<ConnectivityState> stateCaptor;
  @Captor
  private ArgumentCaptor<LoadBalancer.CreateSubchannelArgs> createArgsCaptor;
  private final TestHelper testHelperInst = new TestHelper();
  private final LoadBalancer.Helper mockHelper =
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
  public void pickAfterResolved() {
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

    AbstractTestHelper.verifyNoMoreMeaningfulInteractions(mockHelper);
  }

  @Test
  public void pickAfterResolvedUpdatedHosts() {
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
        testHelperInst.getSubchannelStateListener(removedSubchannel);

    inOrder.verify(mockHelper).updateBalancingState(eq(CONNECTING), pickerCaptor.capture());

    deliverSubchannelState(removedSubchannel, ConnectivityStateInfo.forNonError(READY));
    deliverSubchannelState(oldSubchannel, ConnectivityStateInfo.forNonError(READY));

    inOrder.verify(mockHelper, times(2)).updateBalancingState(eq(READY), pickerCaptor.capture());
    LoadBalancer.SubchannelPicker picker = pickerCaptor.getValue();
    assertThat(getList(picker)).containsExactly(removedSubchannel, oldSubchannel);

    verify(removedSubchannel, times(1)).requestConnection();
    verify(oldSubchannel, times(1)).requestConnection();

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

    verify(mockHelper, times(3)).createSubchannel(any(LoadBalancer.CreateSubchannelArgs.class));
    inOrder.verify(mockHelper, times(2)).updateBalancingState(eq(READY), pickerCaptor.capture());
    picker = pickerCaptor.getValue();
    assertThat(getList(picker)).containsExactly(oldSubchannel, newSubchannel);

    AbstractTestHelper.verifyNoMoreMeaningfulInteractions(mockHelper);
  }

  @Test
  public void pickFromMultiAddressEags() {
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
    LoadBalancer.Subchannel evens = getSubchannel(eag1);
    deliverSubchannelState(evens, ConnectivityStateInfo.forNonError(READY));
    verify(mockHelper).updateBalancingState(eq(READY), pickerCaptor.capture());
    assertThat(pickerCaptor.getValue()).isInstanceOf(TestLb.TestSubchannelPicker.class);
    assertThat(((TestLb.TestSubchannelPicker)pickerCaptor.getValue()).childPickerMap).hasSize(2);
  }

  @Test
  public void testEndpoint_toString() {
    try {
      new Endpoint(null);
      fail("No exception thrown for null");
    } catch (NullPointerException e) {
      assertThat(e.getMessage()).contains("eag");
    }
    
    // Simple eag
    EquivalentAddressGroup eagSimple = servers.get(0);
    Endpoint simple = new Endpoint(eagSimple);
    assertEquals(addressesOnlyString(eagSimple), simple.toString());
    
    // Multiple address eag
    EquivalentAddressGroup eagMulti = createEag("addr1", "addr2");
    Endpoint multi = new Endpoint(eagMulti);
    assertEquals(addressesOnlyString(eagMulti), multi.toString());
  }

  @Test
  public void testEndpoint_equals() {
    new EqualsTester()
        .addEqualityGroup(
            createEndpoint(Attributes.EMPTY, "addr1"),
            createEndpoint(Attributes.EMPTY, "addr1"))
        .addEqualityGroup(
            createEndpoint(Attributes.EMPTY, "addr1", "addr2"),
            createEndpoint(Attributes.EMPTY, "addr2", "addr1"),
            createEndpoint(affinity, "addr1", "addr2"))
        .addEqualityGroup(
            createEndpoint(Attributes.EMPTY, "addr1", "addr3"))
        .addEqualityGroup(
            createEndpoint(Attributes.EMPTY, "addr1", "addr2", "addr3", "addr4", "addr5", "addr6",
              "addr7", "addr8", "addr9", "addr10"),
            createEndpoint(Attributes.EMPTY, "addr2", "addr1", "addr3", "addr4", "addr5", "addr6",
              "addr7", "addr8", "addr9", "addr10"))
        .addEqualityGroup(
            createEndpoint(Attributes.EMPTY, "addr1", "addr2", "addr3", "addr4", "addr5", "addr6",
              "addr7", "addr8", "addr9", "addr11"))
        .addEqualityGroup(
            createEndpoint(Attributes.EMPTY, "addr1", "addr2", "addr3", "addr4", "addr5", "addr6",
              "addr7", "addr8", "addr9", "addr10", "addr11"))
        .testEquals();
  }

  private String addressesOnlyString(EquivalentAddressGroup eag) {
    if (eag == null) {
      return null;
    }

    String withoutAttrs = eag.toString().replaceAll("\\/\\{\\}","");
    return "[" + withoutAttrs.replaceAll("[\\[\\]]", "") + "]";
  }

  @SuppressWarnings("MixedMutabilityReturnType")
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

  private EquivalentAddressGroup createEag(String... names) {
    List<SocketAddress> addresses = buildAddressList(names);
    return new EquivalentAddressGroup(addresses, Attributes.EMPTY);
  }

  private static List<SocketAddress> buildAddressList(String... names) {
    List<SocketAddress> addresses = new ArrayList<>();
    for (String name : names) {
      addresses.add(new FakeSocketAddress(name));
    }
    return addresses;
  }

  private Endpoint createEndpoint(Attributes attr, String... names) {
    EquivalentAddressGroup eag = new EquivalentAddressGroup(buildAddressList(names), attr);
    return new Endpoint(eag);
  }
  
  private LoadBalancer.Subchannel getSubchannel(EquivalentAddressGroup eag) {
    if (PickFirstLoadBalancerProvider.isEnabledNewPickFirst()) {
      for (SocketAddress addr : eag.getAddresses()) {
        LoadBalancer.Subchannel subchannel = subchannels.get(
            Arrays.asList(new EquivalentAddressGroup(addr, eag.getAttributes())));
        if (subchannel != null) {
          return subchannel;
        }
      }
    } else {
      return subchannels.get(Collections.singletonList(eag));
    }

    return null;
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
    protected void updateOverallBalancingState() {
      ConnectivityState overallState = null;
      final Map<Object, SubchannelPicker> childPickers = new HashMap<>();
      final Map<Object, ConnectivityState> childConnStates = new HashMap<>();
      for (ChildLbState childLbState : getChildLbStates()) {
        childPickers.put(childLbState.getKey(), childLbState.getCurrentPicker());
        childConnStates.put(childLbState.getKey(), childLbState.getCurrentState());
        overallState = aggregateState(overallState, childLbState.getCurrentState());
      }

      if (overallState != null) {
        getHelper().updateBalancingState(
            overallState, new TestSubchannelPicker(childPickers, childConnStates));
        currentConnectivityState = overallState;
      }

    }

    private class TestSubchannelPicker extends SubchannelPicker {
      Map<Object, SubchannelPicker> childPickerMap;
      Map<Object, ConnectivityState> childStates = new HashMap<>();

      TestSubchannelPicker(
          Map<Object, SubchannelPicker> childPickers, Map<Object, ConnectivityState> childStates) {
        this.childPickerMap = childPickers;
        this.childStates = childStates;
      }

      List<Subchannel>  getReadySubchannels() {
        List<Subchannel> readySubchannels = new ArrayList<>();
        for ( Map.Entry<Object, ConnectivityState> cur : childStates.entrySet()) {
          if (cur.getValue() == READY) {
            Subchannel s = childPickerMap.get(cur.getKey()).pickSubchannel(null).getSubchannel();
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
