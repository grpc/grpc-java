/*
 * Copyright 2026 The gRPC Authors
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

package io.grpc.autosharding;

import static com.google.common.truth.Truth.assertThat;
import static io.grpc.ConnectivityState.IDLE;
import static io.grpc.ConnectivityState.READY;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import io.grpc.Attributes;
import io.grpc.EquivalentAddressGroup;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancer.SubchannelPicker;
import io.grpc.LoadBalancerProvider;
import io.grpc.SynchronizationContext;
import io.grpc.autosharding.EndpointMap.EndpointHolder;
import java.net.SocketAddress;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;

@RunWith(JUnit4.class)
public class EndpointMapTest {

  private final Helper mockHelper = mock(Helper.class);
  private final LoadBalancerProvider mockProvider = mock(LoadBalancerProvider.class);
  private final LoadBalancer mockDelegate = mock(LoadBalancer.class);
  private final SynchronizationContext syncContext =
      new SynchronizationContext((t, e) -> {
        throw new AssertionError("Unhandled exception in syncContext", e);
      });

  private EndpointMap endpointMap;
  private final AtomicInteger stateChangeCount = new AtomicInteger(0);

  @Before
  public void setUp() {
    when(mockHelper.getSynchronizationContext()).thenReturn(syncContext);
    when(mockProvider.newLoadBalancer(any())).thenReturn(mockDelegate);
    endpointMap = new EndpointMap();
  }

  private EndpointHolder createHolder(int index) {
    return new EndpointHolder(index, mockHelper, mockProvider, stateChangeCount::incrementAndGet);
  }

  @Test
  public void basicMapOperations() {
    assertThat(endpointMap.isEmpty()).isTrue();
    assertThat(endpointMap.size()).isEqualTo(0);

    EndpointHolder h1 = createHolder(0);
    EndpointHolder h2 = createHolder(1);

    endpointMap.put("host1", h1);
    endpointMap.put("host2", h2);

    assertThat(endpointMap.isEmpty()).isFalse();
    assertThat(endpointMap.size()).isEqualTo(2);
    assertThat(endpointMap.get("host1")).isSameInstanceAs(h1);
    assertThat(endpointMap.get("host2")).isSameInstanceAs(h2);
    assertThat(endpointMap.get("unknown")).isNull();
    assertThat(endpointMap.keySet()).containsExactly("host1", "host2").inOrder();
    assertThat(endpointMap.values()).containsExactly(h1, h2).inOrder();

    EndpointHolder removed = endpointMap.remove("host1");
    assertThat(removed).isSameInstanceAs(h1);
    assertThat(endpointMap.size()).isEqualTo(1);
    assertThat(endpointMap.get("host1")).isNull();
  }

  @Test
  public void nullChecks() {
    EndpointHolder h = createHolder(0);

    assertThrows(NullPointerException.class, () -> endpointMap.get(null));
    assertThrows(NullPointerException.class, () -> endpointMap.put(null, h));
    assertThrows(NullPointerException.class, () -> endpointMap.put("host", null));
    assertThrows(NullPointerException.class, () -> endpointMap.remove(null));

    assertThrows(
        NullPointerException.class,
        () -> new EndpointHolder(0, null, mockProvider, null));
    assertThrows(
        NullPointerException.class,
        () -> new EndpointHolder(0, mockHelper, null, null));

    assertThrows(
        NullPointerException.class,
        () -> h.updateAddresses(null, Attributes.EMPTY));
    assertThrows(
        NullPointerException.class,
        () -> h.updateAddresses(Collections.emptyList(), null));
  }

  @Test
  public void reindex_updatesIndicesContiguously() {
    EndpointHolder h0 = createHolder(0);
    EndpointHolder h1 = createHolder(1);
    EndpointHolder h2 = createHolder(2);

    endpointMap.put("host0", h0);
    endpointMap.put("host1", h1);
    endpointMap.put("host2", h2);

    // Remove middle element
    endpointMap.remove("host1");
    assertThat(h0.getIndex()).isEqualTo(0);
    assertThat(h2.getIndex()).isEqualTo(2);

    endpointMap.reindex();
    assertThat(h0.getIndex()).isEqualTo(0);
    assertThat(h2.getIndex()).isEqualTo(1);
  }

  @Test
  public void endpointHolder_childHelperUpdatesStateAndTriggersCallback() {
    EndpointHolder holder = createHolder(0);
    assertThat(holder.getState()).isEqualTo(IDLE);

    // Capture child helper passed to LazyChildLoadBalancer
    ArgumentCaptor<Helper> helperCaptor = ArgumentCaptor.forClass(Helper.class);
    verify(mockProvider, org.mockito.Mockito.never()).newLoadBalancer(any());

    // Trigger connection to create child helper and delegate
    holder.updateAddresses(
        Collections.singletonList(new EquivalentAddressGroup(new SocketAddress() {})),
        Attributes.EMPTY);
    holder.requestConnection();

    verify(mockProvider).newLoadBalancer(helperCaptor.capture());
    Helper childHelper = helperCaptor.getValue();

    // Reset counter before state update to verify callback fires on update
    stateChangeCount.set(0);

    // Simulate child balancer updating state
    SubchannelPicker testPicker = mock(SubchannelPicker.class);
    childHelper.updateBalancingState(READY, testPicker);

    assertThat(holder.getState()).isEqualTo(READY);
    assertThat(holder.getPicker()).isSameInstanceAs(testPicker);
    assertThat(stateChangeCount.get()).isEqualTo(1);
  }

  @Test
  public void toPickerEndpoints_buildsImmutableListMatchingHolders() {
    EndpointHolder h0 = createHolder(0);
    EndpointHolder h1 = createHolder(1);

    endpointMap.put("host0", h0);
    endpointMap.put("host1", h1);

    ImmutableList<PickerEndpoint> pickerEndpoints = endpointMap.toPickerEndpoints();
    assertThat(pickerEndpoints).hasSize(2);
    assertThat(pickerEndpoints.get(0).getState()).isEqualTo(IDLE);
    assertThat(pickerEndpoints.get(1).getState()).isEqualTo(IDLE);
  }

  @Test
  public void shutdownAll_cleansUpAllHoldersAndClearsMap() {
    EndpointHolder h0 = createHolder(0);
    EndpointHolder h1 = createHolder(1);

    endpointMap.put("host0", h0);
    endpointMap.put("host1", h1);

    // Trigger connections so delegates exist
    h0.updateAddresses(
        Collections.singletonList(new EquivalentAddressGroup(new SocketAddress() {})),
        Attributes.EMPTY);
    h0.requestConnection();

    endpointMap.shutdownAll();
    assertThat(endpointMap.isEmpty()).isTrue();
    verify(mockDelegate).shutdown();
  }

  @Test
  public void toString_containsDebugFields() {
    EndpointHolder h = createHolder(3);
    endpointMap.put("host3", h);

    assertThat(endpointMap.toString()).contains("host3");
    assertThat(h.toString()).contains("index=3");
    assertThat(h.toString()).contains("state=IDLE");
  }
}
