/*
 * Copyright 2016, Google Inc. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 *    * Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 *    * Redistributions in binary form must reproduce the above
 * copyright notice, this list of conditions and the following disclaimer
 * in the documentation and/or other materials provided with the
 * distribution.
 *
 *    * Neither the name of Google Inc. nor the names of its
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package io.grpc;

import static io.grpc.ConnectivityState.IDLE;
import static io.grpc.PickFirstBalancerFactory2.PickFirstBalancer.LAST_STATE;
import static org.junit.Assert.assertEquals;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.isA;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.google.common.collect.Lists;

import io.grpc.LoadBalancer2.Helper;
import io.grpc.LoadBalancer2.PickResult;
import io.grpc.LoadBalancer2.Subchannel;
import io.grpc.PickFirstBalancerFactory2.PickFirstBalancer;
import io.grpc.PickFirstBalancerFactory2.Picker;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.net.SocketAddress;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;


/** Unit test for {@link PickFirstBalancerFactory2}. */
@RunWith(JUnit4.class)
public class PickFirstLoadBalancer2Test {
  private PickFirstBalancer loadBalancer;
  private List<ResolvedServerInfoGroup> servers = Lists.newArrayList();
  private List<SocketAddress> socketAddresses = Lists.newArrayList();
  private Attributes affinity = Attributes.EMPTY;

  @Captor
  private ArgumentCaptor<EquivalentAddressGroup> eagCaptor;
  @Captor
  private ArgumentCaptor<Picker> pickerCaptor;
  @Captor
  private ArgumentCaptor<Attributes> attrsCaptor;
  @Mock
  private Helper mockHelper;
  @Mock
  private Subchannel mockSubchannel;

  @Before
  public void setUp() {
    MockitoAnnotations.initMocks(this);
    for (int i = 0; i < 3; i++) {
      SocketAddress addr = new FakeSocketAddress("server" + i);
      servers.add(ResolvedServerInfoGroup.builder().add(new ResolvedServerInfo(addr)).build());
      socketAddresses.add(addr);
    }

    when(mockSubchannel.getAddresses()).thenReturn(new EquivalentAddressGroup(socketAddresses));
    when(mockSubchannel.getAttributes()).thenReturn(Attributes.newBuilder()
        .set(LAST_STATE, new AtomicReference<ConnectivityState>(IDLE))
        .build());
    when(mockHelper.createSubchannel(any(EquivalentAddressGroup.class), any(Attributes.class)))
        .thenReturn(mockSubchannel);

    loadBalancer = (PickFirstBalancer) PickFirstBalancerFactory2.getInstance().newLoadBalancer(
        mockHelper);
  }

  @Test
  public void pickAfterResolved() throws Exception {
    loadBalancer.handleResolvedAddresses(servers, affinity);

    verify(mockHelper).createSubchannel(eagCaptor.capture(), attrsCaptor.capture());
    verify(mockHelper).updatePicker(pickerCaptor.capture());

    assertEquals(new EquivalentAddressGroup(socketAddresses), eagCaptor.getValue());
    assertEquals(pickerCaptor.getValue().pickSubchannel(affinity, new Metadata()),
        pickerCaptor.getValue().pickSubchannel(affinity, new Metadata()));

    verifyNoMoreInteractions(mockHelper);
  }

  @Test
  public void pickAfterResolvedAndUnchanged() throws Exception {
    loadBalancer.handleResolvedAddresses(servers, affinity);
    loadBalancer.handleResolvedAddresses(servers, affinity);

    verify(mockHelper, times(1)).createSubchannel(any(EquivalentAddressGroup.class),
        any(Attributes.class));
    verify(mockHelper, times(1)).updatePicker(isA(Picker.class));

    verifyNoMoreInteractions(mockHelper);
  }

  @Test
  public void pickAfterResolvedAndChanged() throws Exception {
    SocketAddress socketAddr = new FakeSocketAddress("newserver");
    List<SocketAddress> newSocketAddresses = Lists.newArrayList(socketAddr);
    List<ResolvedServerInfoGroup> newServers = Lists.newArrayList(
        ResolvedServerInfoGroup.builder().add(new ResolvedServerInfo(socketAddr)).build());

    final Subchannel oldSubchannel = mock(Subchannel.class);
    final EquivalentAddressGroup oldEag = new EquivalentAddressGroup(socketAddresses);
    when(oldSubchannel.getAddresses()).thenReturn(oldEag);

    final Subchannel newSubchannel = mock(Subchannel.class);
    final EquivalentAddressGroup newEag = new EquivalentAddressGroup(newSocketAddresses);
    when(newSubchannel.getAddresses()).thenReturn(newEag);

    when(mockHelper.createSubchannel(any(EquivalentAddressGroup.class), any(Attributes.class)))
        .then(new Answer<Subchannel>() {
          @Override
          public Subchannel answer(InvocationOnMock invocation) throws Throwable {
            Object[] args = invocation.getArguments();
            EquivalentAddressGroup eag = (EquivalentAddressGroup) args[0];
            if (eag.equals(oldEag)) {
              return oldSubchannel;
            } else if (eag.equals(newEag)) {
              return newSubchannel;
            }
            throw new IllegalArgumentException();
          }
        });

    loadBalancer.handleResolvedAddresses(servers, affinity);
    loadBalancer.handleResolvedAddresses(newServers, affinity);

    verify(mockHelper, times(2)).createSubchannel(eagCaptor.capture(), attrsCaptor.capture());
    verify(mockHelper, times(2)).updatePicker(pickerCaptor.capture());

    assertEquals(socketAddresses, eagCaptor.getAllValues().get(0).getAddresses());
    assertEquals(newSocketAddresses, eagCaptor.getAllValues().get(1).getAddresses());

    Subchannel subchannel = pickerCaptor.getAllValues().get(0).pickSubchannel(
        affinity, new Metadata()).getSubchannel();
    assertEquals(oldSubchannel, subchannel);

    Subchannel subchannel2 = pickerCaptor.getAllValues().get(1).pickSubchannel(affinity,
        new Metadata()).getSubchannel();
    assertEquals(newSubchannel, subchannel2);
    verify(subchannel2, never()).shutdown();

    verifyNoMoreInteractions(mockHelper);
  }

  @Test
  public void stateChangeBeforeResolution() throws Exception {
    loadBalancer.handleSubchannelState(mockSubchannel,
        ConnectivityStateInfo.forNonError(ConnectivityState.READY));

    verify(mockHelper).updatePicker(pickerCaptor.capture());
    assertEquals(PickResult.withNoResult(),
        pickerCaptor.getValue().pickSubchannel(affinity, new Metadata()));
    verifyNoMoreInteractions(mockHelper);
  }

  @Test
  public void pickAfterStateChangeAfterResolution() throws Exception {
    loadBalancer.handleResolvedAddresses(servers, affinity);
    verify(mockHelper).updatePicker(pickerCaptor.capture());
    Subchannel subchannel = pickerCaptor.getValue().pickSubchannel(affinity,
        new Metadata()).getSubchannel();
    reset(mockHelper);

    loadBalancer.handleSubchannelState(subchannel,
        ConnectivityStateInfo.forTransientFailure(Status.UNAVAILABLE));
    loadBalancer.handleSubchannelState(subchannel,
        ConnectivityStateInfo.forNonError(ConnectivityState.IDLE));
    loadBalancer.handleSubchannelState(subchannel,
        ConnectivityStateInfo.forNonError(ConnectivityState.READY));

    verify(mockHelper, times(3)).updatePicker(pickerCaptor.capture());

    assertEquals(Status.UNAVAILABLE,
        pickerCaptor.getAllValues().get(1).pickSubchannel(Attributes.EMPTY,
            new Metadata()).getStatus());
    assertEquals(Status.OK, pickerCaptor.getAllValues().get(2).pickSubchannel(Attributes.EMPTY,
        new Metadata()).getStatus());
    assertEquals(PickResult.withSubchannel(subchannel).getSubchannel(), pickerCaptor.getAllValues()
        .get(3).pickSubchannel(Attributes.EMPTY, new Metadata()).getSubchannel());

    verifyNoMoreInteractions(subchannel);
    verifyNoMoreInteractions(mockHelper);
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
