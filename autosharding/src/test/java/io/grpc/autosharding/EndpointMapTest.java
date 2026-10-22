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
import static io.grpc.ConnectivityState.CONNECTING;
import static io.grpc.ConnectivityState.IDLE;
import static io.grpc.ConnectivityState.READY;
import static io.grpc.ConnectivityState.TRANSIENT_FAILURE;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import io.grpc.Attributes;
import io.grpc.ConnectivityState;
import io.grpc.EquivalentAddressGroup;
import io.grpc.InternalEquivalentAddressGroup;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancer.PickResult;
import io.grpc.LoadBalancer.ResolvedAddresses;
import io.grpc.LoadBalancer.SubchannelPicker;
import io.grpc.LoadBalancerProvider;
import io.grpc.Status;
import io.grpc.SynchronizationContext;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link EndpointMap}. */
@RunWith(JUnit4.class)
public class EndpointMapTest {

  private final SynchronizationContext syncContext =
      new SynchronizationContext(
          (t, e) -> {
            throw new AssertionError("Unhandled exception in syncContext", e);
          });
  private final Helper helper = mock(Helper.class);
  private final FakeChildProvider childProvider = new FakeChildProvider();
  private final List<Integer> stateUpdates = new ArrayList<>();

  private EndpointMap endpointMap;

  @Before
  public void setUp() {
    when(helper.getSynchronizationContext()).thenReturn(syncContext);
    endpointMap = new EndpointMap(helper, childProvider, () -> stateUpdates.add(1));
  }

  // ---------------------------------------------------------------------------------------------
  // Endpoint set and indices
  // ---------------------------------------------------------------------------------------------

  @Test
  public void updateEndpoints_assignsDenseIndicesInResolverOrder() {
    endpointMap.updateEndpoints(endpoints("a", "b", "c"), Attributes.EMPTY);

    assertThat(endpointMap.size()).isEqualTo(3);
    assertThat(endpointMap.indexOf("a")).isEqualTo(0);
    assertThat(endpointMap.indexOf("b")).isEqualTo(1);
    assertThat(endpointMap.indexOf("c")).isEqualTo(2);
    assertThat(endpointMap.toPickerEndpoints()).hasSize(3);
  }

  @Test
  public void indexOf_unknownHostname_returnsMinusOne() {
    endpointMap.updateEndpoints(endpoints("a"), Attributes.EMPTY);

    assertThat(endpointMap.indexOf("nope")).isEqualTo(-1);
  }

  @Test
  public void updateEndpoints_duplicateHostnames_keepsOneEntry() {
    endpointMap.updateEndpoints(endpoints("a", "b", "a"), Attributes.EMPTY);

    // Indices stay dense so that they remain valid offsets into toPickerEndpoints().
    assertThat(endpointMap.size()).isEqualTo(2);
    assertThat(endpointMap.toPickerEndpoints()).hasSize(2);
    assertThat(endpointMap.indexOf("a")).isEqualTo(0);
    assertThat(endpointMap.indexOf("b")).isEqualTo(1);
  }

  @Test
  public void updateEndpoints_duplicateHostnames_firstEndpointSuppliesTheAddresses() {
    EquivalentAddressGroup first = endpointWithHostname("first-addr", "a");
    EquivalentAddressGroup second = endpointWithHostname("second-addr", "a");

    endpointMap.updateEndpoints(ImmutableList.of(first, second), Attributes.EMPTY);
    activate(0);

    assertThat(childProvider.children).hasSize(1);
    assertThat(childProvider.children.get(0).lastAddresses.getAddresses()).containsExactly(first);
  }

  @Test
  public void updateEndpoints_reordering_movesIndicesAndPickerEndpoints() {
    endpointMap.updateEndpoints(endpoints("a", "b"), Attributes.EMPTY);
    activate(0);
    reportState(0, READY);

    endpointMap.updateEndpoints(endpoints("b", "a"), Attributes.EMPTY);

    assertThat(endpointMap.indexOf("a")).isEqualTo(1);
    assertThat(endpointMap.indexOf("b")).isEqualTo(0);
    // The state moved with the endpoint, not with the index.
    assertThat(stateAt(1)).isEqualTo(READY);
    assertThat(stateAt(0)).isEqualTo(IDLE);
  }

  @Test
  public void hostnameAttributeAbsent_fallsBackToFirstAddress() {
    EquivalentAddressGroup eag = new EquivalentAddressGroup(new NamedAddress("1.2.3.4:80"));

    endpointMap.updateEndpoints(ImmutableList.of(eag), Attributes.EMPTY);

    assertThat(endpointMap.indexOf("1.2.3.4:80")).isEqualTo(0);
  }

  // ---------------------------------------------------------------------------------------------
  // Child lifecycle across resolver updates
  // ---------------------------------------------------------------------------------------------

  @Test
  public void updateEndpoints_survivingHostname_keepsChildAndState() {
    endpointMap.updateEndpoints(endpoints("a"), Attributes.EMPTY);
    activate(0);
    reportState(0, READY);
    assertThat(childProvider.children).hasSize(1);

    endpointMap.updateEndpoints(endpoints("a", "b"), Attributes.EMPTY);

    // No new child for "a", and its connectivity state survived the update.
    assertThat(childProvider.children).hasSize(1);
    assertThat(childProvider.children.get(0).shutdown).isFalse();
    assertThat(stateAt(0)).isEqualTo(READY);
  }

  @Test
  public void updateEndpoints_survivingHostname_forwardsNewAddresses() {
    endpointMap.updateEndpoints(endpoints("a"), Attributes.EMPTY);
    activate(0);
    FakeChild child = childProvider.children.get(0);
    int acceptsBefore = child.acceptCount;

    endpointMap.updateEndpoints(endpoints("a"), Attributes.EMPTY);

    assertThat(child.acceptCount).isGreaterThan(acceptsBefore);
  }

  @Test
  public void updateEndpoints_removedHostname_shutsDownChild() {
    endpointMap.updateEndpoints(endpoints("a", "b"), Attributes.EMPTY);
    activate(0);
    activate(1);

    endpointMap.updateEndpoints(endpoints("b"), Attributes.EMPTY);

    assertThat(childProvider.children.get(0).shutdown).isTrue();
    assertThat(childProvider.children.get(1).shutdown).isFalse();
    assertThat(endpointMap.size()).isEqualTo(1);
    assertThat(endpointMap.indexOf("a")).isEqualTo(-1);
  }

  @Test
  public void updateEndpoints_toEmpty_shutsDownEverything() {
    endpointMap.updateEndpoints(endpoints("a"), Attributes.EMPTY);
    activate(0);

    endpointMap.updateEndpoints(ImmutableList.of(), Attributes.EMPTY);

    assertThat(endpointMap.size()).isEqualTo(0);
    assertThat(endpointMap.toPickerEndpoints()).isEmpty();
    assertThat(childProvider.children.get(0).shutdown).isTrue();
  }

  @Test
  public void updateEndpoints_doesNotNotifyListenerWhileRebuilding() {
    // New children publish their initial IDLE state from inside updateEndpoints(). Forwarding
    // those would make the LB policy publish one picker per endpoint for a single update.
    endpointMap.updateEndpoints(endpoints("a", "b"), Attributes.EMPTY);

    assertThat(stateUpdates).isEmpty();
  }

  @Test
  public void updateEndpoints_childReenteringDuringUpdate_seesTheCompleteNewEndpointSet() {
    endpointMap.updateEndpoints(endpoints("a", "b"), Attributes.EMPTY);
    activate(0);
    activate(1);

    // Surviving children are handed their new addresses from inside updateEndpoints() and can
    // call straight back in. Record how the map looks from in there.
    List<Integer> observedSizes = new ArrayList<>();
    List<Integer> observedIndicesOfC = new ArrayList<>();
    childProvider.onAccept =
        () -> {
          observedSizes.add(endpointMap.size());
          observedIndicesOfC.add(endpointMap.indexOf("c"));
        };

    endpointMap.updateEndpoints(endpoints("a", "b", "c"), Attributes.EMPTY);

    // Both callbacks see all three endpoints and the final indices, never a partial rebuild.
    assertThat(observedSizes).containsExactly(3, 3);
    assertThat(observedIndicesOfC).containsExactly(2, 2);
  }

  @Test
  public void childStateUpdate_notifiesListener() {
    endpointMap.updateEndpoints(endpoints("a"), Attributes.EMPTY);
    activate(0);
    stateUpdates.clear();

    reportState(0, READY);

    assertThat(stateUpdates).hasSize(1);
    assertThat(stateAt(0)).isEqualTo(READY);
  }

  @Test
  public void childStateUpdate_afterEndpointRemoved_isIgnored() {
    endpointMap.updateEndpoints(endpoints("a"), Attributes.EMPTY);
    activate(0);
    FakeChild child = childProvider.children.get(0);

    endpointMap.updateEndpoints(ImmutableList.of(), Attributes.EMPTY);
    stateUpdates.clear();
    child.report(READY, mock(SubchannelPicker.class));

    assertThat(stateUpdates).isEmpty();
  }

  // ---------------------------------------------------------------------------------------------
  // Connecting lazily
  // ---------------------------------------------------------------------------------------------

  @Test
  public void endpointsStartIdleWithoutCreatingChildren() {
    endpointMap.updateEndpoints(endpoints("a", "b"), Attributes.EMPTY);

    assertThat(childProvider.children).isEmpty();
    assertThat(stateAt(0)).isEqualTo(IDLE);
    assertThat(stateAt(1)).isEqualTo(IDLE);
  }

  @Test
  public void pickerEndpoint_requestConnection_createsChildAndConnects() {
    endpointMap.updateEndpoints(endpoints("a"), Attributes.EMPTY);

    endpointMap.toPickerEndpoints().get(0).requestConnection();

    assertThat(childProvider.children).hasSize(1);
    assertThat(childProvider.children.get(0).requestConnectionCount).isEqualTo(1);
  }

  @Test
  public void pickerEndpoint_repeatedRequestConnection_connectsOnce() {
    endpointMap.updateEndpoints(endpoints("a"), Attributes.EMPTY);
    // A single snapshot is shared by every concurrent RPC, so the same stale IDLE endpoint can
    // be asked to connect many times over.
    PickerEndpoint stale = endpointMap.toPickerEndpoints().get(0);

    stale.requestConnection();
    stale.requestConnection();
    stale.requestConnection();

    assertThat(childProvider.children).hasSize(1);
    assertThat(childProvider.children.get(0).requestConnectionCount).isEqualTo(1);
  }

  @Test
  public void pickerEndpoint_requestConnectionAfterEndpointRemoved_isNoOp() {
    endpointMap.updateEndpoints(endpoints("a"), Attributes.EMPTY);
    PickerEndpoint stale = endpointMap.toPickerEndpoints().get(0);

    endpointMap.updateEndpoints(endpoints("b"), Attributes.EMPTY);
    stale.requestConnection();

    assertThat(childProvider.children).isEmpty();
  }

  @Test
  public void pickerEndpoint_requestConnectionAfterShutdown_isNoOp() {
    endpointMap.updateEndpoints(endpoints("a"), Attributes.EMPTY);
    PickerEndpoint stale = endpointMap.toPickerEndpoints().get(0);

    endpointMap.shutdown();
    stale.requestConnection();

    assertThat(childProvider.children).isEmpty();
  }

  // ---------------------------------------------------------------------------------------------
  // Aggregated connectivity state (gRFC A42 rules)
  // ---------------------------------------------------------------------------------------------

  @Test
  public void aggregate_empty_isTransientFailure() {
    assertThat(endpointMap.aggregateConnectivityState()).isEqualTo(TRANSIENT_FAILURE);
  }

  @Test
  public void aggregate_anyReady_isReady() {
    setUpStates(TRANSIENT_FAILURE, TRANSIENT_FAILURE, READY);

    assertThat(endpointMap.aggregateConnectivityState()).isEqualTo(READY);
  }

  @Test
  public void aggregate_twoTransientFailures_isTransientFailure() {
    setUpStates(TRANSIENT_FAILURE, TRANSIENT_FAILURE, IDLE);

    assertThat(endpointMap.aggregateConnectivityState()).isEqualTo(TRANSIENT_FAILURE);
  }

  @Test
  public void aggregate_anyConnecting_isConnecting() {
    setUpStates(IDLE, CONNECTING);

    assertThat(endpointMap.aggregateConnectivityState()).isEqualTo(CONNECTING);
  }

  @Test
  public void aggregate_oneTransientFailureAmongMany_isConnecting() {
    setUpStates(TRANSIENT_FAILURE, IDLE);

    assertThat(endpointMap.aggregateConnectivityState()).isEqualTo(CONNECTING);
  }

  @Test
  public void aggregate_soleEndpointInTransientFailure_isTransientFailure() {
    setUpStates(TRANSIENT_FAILURE);

    assertThat(endpointMap.aggregateConnectivityState()).isEqualTo(TRANSIENT_FAILURE);
  }

  @Test
  public void aggregate_allIdle_isIdle() {
    endpointMap.updateEndpoints(endpoints("a", "b"), Attributes.EMPTY);

    assertThat(endpointMap.aggregateConnectivityState()).isEqualTo(IDLE);
  }

  // ---------------------------------------------------------------------------------------------
  // Waking up an idle endpoint
  // ---------------------------------------------------------------------------------------------

  @Test
  public void maybeWakeUpIdleEndpoint_connectsLowestIndexedIdleEndpoint() {
    setUpStates(TRANSIENT_FAILURE, IDLE, IDLE);

    endpointMap.maybeWakeUpIdleEndpoint();

    assertThat(stateAt(1)).isEqualTo(CONNECTING);
    assertThat(stateAt(2)).isEqualTo(IDLE);
  }

  @Test
  public void maybeWakeUpIdleEndpoint_somethingAlreadyConnecting_doesNothing() {
    setUpStates(CONNECTING, IDLE);
    int childrenBefore = childProvider.children.size();

    endpointMap.maybeWakeUpIdleEndpoint();

    assertThat(childProvider.children).hasSize(childrenBefore);
    assertThat(stateAt(1)).isEqualTo(IDLE);
  }

  @Test
  public void maybeWakeUpIdleEndpoint_noIdleEndpoints_doesNothing() {
    setUpStates(TRANSIENT_FAILURE, TRANSIENT_FAILURE);
    int childrenBefore = childProvider.children.size();

    endpointMap.maybeWakeUpIdleEndpoint();

    assertThat(childProvider.children).hasSize(childrenBefore);
  }

  // ---------------------------------------------------------------------------------------------
  // Shutdown
  // ---------------------------------------------------------------------------------------------

  @Test
  public void shutdown_shutsDownChildrenAndEmptiesMap() {
    endpointMap.updateEndpoints(endpoints("a", "b"), Attributes.EMPTY);
    activate(0);
    activate(1);

    endpointMap.shutdown();

    assertThat(childProvider.children.get(0).shutdown).isTrue();
    assertThat(childProvider.children.get(1).shutdown).isTrue();
    assertThat(endpointMap.size()).isEqualTo(0);
    assertThat(endpointMap.indexOf("a")).isEqualTo(-1);
  }

  @Test
  public void shutdown_isIdempotent() {
    endpointMap.updateEndpoints(endpoints("a"), Attributes.EMPTY);
    activate(0);

    endpointMap.shutdown();
    endpointMap.shutdown();

    assertThat(endpointMap.size()).isEqualTo(0);
    assertThat(childProvider.children.get(0).shutdown).isTrue();
  }

  @Test
  public void shutdown_childReportingWhileShuttingDown_doesNotNotifyListener() {
    endpointMap.updateEndpoints(endpoints("a"), Attributes.EMPTY);
    activate(0);
    childProvider.reportOnShutdown = true;
    stateUpdates.clear();

    endpointMap.shutdown();

    assertThat(stateUpdates).isEmpty();
  }

  @Test
  public void shutdown_childReportingAfterShutdown_doesNotNotifyListener() {
    endpointMap.updateEndpoints(endpoints("a"), Attributes.EMPTY);
    activate(0);
    FakeChild child = childProvider.children.get(0);
    endpointMap.shutdown();
    stateUpdates.clear();

    child.report(READY, mock(SubchannelPicker.class));

    assertThat(stateUpdates).isEmpty();
  }

  @Test
  public void updateEndpoints_removedChildReportingWhileShuttingDown_doesNotNotifyListener() {
    endpointMap.updateEndpoints(endpoints("a", "b"), Attributes.EMPTY);
    activate(0);
    childProvider.reportOnShutdown = true;
    stateUpdates.clear();

    endpointMap.updateEndpoints(endpoints("b"), Attributes.EMPTY);

    assertThat(childProvider.children.get(0).shutdown).isTrue();
    assertThat(stateUpdates).isEmpty();
  }

  // ---------------------------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------------------------

  /** Drives the endpoints at indices 0..n-1 into the given states. */
  private void setUpStates(ConnectivityState... states) {
    String[] names = new String[states.length];
    for (int i = 0; i < states.length; i++) {
      names[i] = "host" + i;
    }
    endpointMap.updateEndpoints(endpoints(names), Attributes.EMPTY);
    for (int i = 0; i < states.length; i++) {
      if (states[i] == IDLE) {
        continue;
      }
      activate(i);
      reportState(i, states[i]);
    }
    stateUpdates.clear();
  }

  /** Instantiates the child load balancer behind the endpoint at {@code index}. */
  private void activate(int index) {
    endpointMap.toPickerEndpoints().get(index).requestConnection();
  }

  private void reportState(int index, ConnectivityState state) {
    childForHost(hostnames.get(index)).report(state, mock(SubchannelPicker.class));
  }

  private ConnectivityState stateAt(int index) {
    return endpointMap.toPickerEndpoints().get(index).getState();
  }

  /** Returns the child load balancer created for {@code hostname}. */
  private FakeChild childForHost(String hostname) {
    for (FakeChild child : childProvider.children) {
      if (child.lastAddresses == null) {
        continue;
      }
      String childHostname =
          child
              .lastAddresses
              .getAddresses()
              .get(0)
              .getAttributes()
              .get(InternalEquivalentAddressGroup.ATTR_ADDRESS_NAME);
      if (hostname.equals(childHostname)) {
        return child;
      }
    }
    throw new AssertionError("No child load balancer created for hostname " + hostname);
  }

  /** Hostnames of the endpoints most recently produced by {@link #endpoints}. */
  private final List<String> hostnames = new ArrayList<>();

  private List<EquivalentAddressGroup> endpoints(String... hostnameArgs) {
    hostnames.clear();
    List<EquivalentAddressGroup> eags = new ArrayList<>();
    for (String hostname : hostnameArgs) {
      hostnames.add(hostname);
      eags.add(endpointWithHostname("addr-" + hostname, hostname));
    }
    return ImmutableList.copyOf(eags);
  }

  /** An endpoint at {@code addressName} advertising {@code hostname}. */
  private static EquivalentAddressGroup endpointWithHostname(String addressName, String hostname) {
    return new EquivalentAddressGroup(
        new NamedAddress(addressName),
        Attributes.newBuilder()
            .set(InternalEquivalentAddressGroup.ATTR_ADDRESS_NAME, hostname)
            .build());
  }

  /** A {@link SocketAddress} with a predictable {@link #toString}. */
  private static final class NamedAddress extends SocketAddress {
    private static final long serialVersionUID = 0L;
    private final String name;

    NamedAddress(String name) {
      this.name = name;
    }

    @Override
    public String toString() {
      return name;
    }
  }

  private static final class FakeChildProvider extends LoadBalancerProvider {
    final List<FakeChild> children = new ArrayList<>();

    /** Run from every child's {@code acceptResolvedAddresses}, to exercise re-entrancy. */
    Runnable onAccept;
    boolean reportOnShutdown;

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
      return "fake_child";
    }

    @Override
    public LoadBalancer newLoadBalancer(Helper helper) {
      FakeChild child = new FakeChild(helper, this);
      children.add(child);
      return child;
    }
  }

  /** Stands in for {@code pick_first}, including its move to CONNECTING when asked to connect. */
  private static final class FakeChild extends LoadBalancer {
    private final Helper helper;
    private final FakeChildProvider provider;
    ResolvedAddresses lastAddresses;
    int acceptCount;
    int requestConnectionCount;
    boolean shutdown;

    FakeChild(Helper helper, FakeChildProvider provider) {
      this.helper = helper;
      this.provider = provider;
    }

    @Override
    public Status acceptResolvedAddresses(ResolvedAddresses resolvedAddresses) {
      lastAddresses = resolvedAddresses;
      acceptCount++;
      if (provider.onAccept != null) {
        provider.onAccept.run();
      }
      return Status.OK;
    }

    @Override
    public void handleNameResolutionError(Status error) {}

    @Override
    public void requestConnection() {
      requestConnectionCount++;
      report(CONNECTING, new FixedResultPicker(PickResult.withNoResult()));
    }

    @Override
    public void shutdown() {
      shutdown = true;
      if (provider.reportOnShutdown) {
        report(TRANSIENT_FAILURE, new FixedResultPicker(PickResult.withNoResult()));
      }
    }

    void report(ConnectivityState state, SubchannelPicker picker) {
      helper.updateBalancingState(state, picker);
    }
  }
}
