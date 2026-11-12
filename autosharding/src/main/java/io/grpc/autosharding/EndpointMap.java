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

import static com.google.common.base.Preconditions.checkNotNull;
import static io.grpc.ConnectivityState.CONNECTING;
import static io.grpc.ConnectivityState.IDLE;
import static io.grpc.ConnectivityState.READY;
import static io.grpc.ConnectivityState.TRANSIENT_FAILURE;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import com.google.common.net.HostAndPort;
import com.google.common.net.InetAddresses;
import io.grpc.Attributes;
import io.grpc.ConnectivityState;
import io.grpc.EquivalentAddressGroup;
import io.grpc.InternalEquivalentAddressGroup;
import io.grpc.LoadBalancer.FixedResultPicker;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancer.PickResult;
import io.grpc.LoadBalancer.ResolvedAddresses;
import io.grpc.LoadBalancer.SubchannelPicker;
import io.grpc.LoadBalancerProvider;
import io.grpc.Status;
import io.grpc.SynchronizationContext;
import io.grpc.util.ForwardingLoadBalancerHelper;
import io.grpc.util.LazyLoadBalancer;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.concurrent.NotThreadSafe;

/**
 * Owns one lazily-created {@code pick_first} child load balancer per resolved endpoint, keyed by
 * endpoint hostname, and tracks the connectivity state and picker most recently reported by each
 * child.
 *
 * <h3>Endpoint indices</h3>
 *
 * <p>Endpoints are identified by a dense index in {@code [0, size)}: their position in the list
 * most recently passed to {@link #updateEndpoints}, after duplicate hostnames are dropped.
 * {@link #indexOf} and {@link #toPickerEndpoints} agree as long as no {@link #updateEndpoints}
 * call comes between them.
 *
 * <h3>Threading model</h3>
 *
 * <p>This class is not thread-safe. Every method must be called from the
 * {@link SynchronizationContext} of the {@link Helper} supplied at construction. The sole
 * exception is {@link PickerEndpoint#requestConnection}, reached from RPC threads through the
 * snapshots returned by {@link #toPickerEndpoints}; it hops onto the synchronization context
 * before touching any state here.
 */
@NotThreadSafe
final class EndpointMap {
  private static final Logger logger = Logger.getLogger(EndpointMap.class.getName());

  private final Helper helper;
  private final LoadBalancerProvider childProvider;
  private final Runnable childStateListener;

  // The endpoints, in index order: an endpoint's index is its position here, never stored.
  // Rebuilt wholesale by updateEndpoints.
  private final List<EndpointHolder> holders = new ArrayList<>();

  // Hostname to its position in holders. Derived from holders and rebuilt with it; exists so
  // that translating an assignment's hostnames into indices stays linear in the assignment
  // size, rather than scanning the endpoints once per name.
  private final Map<String, Integer> indexByHostname = new HashMap<>();

  /**
   * Set while {@link #updateEndpoints} hands children their addresses. Their state updates are
   * still recorded but not forwarded, since the caller publishes once afterwards.
   */
  private boolean rebuilding;

  /**
   * Constructs an empty map.
   *
   * @param helper the parent LB policy's helper, used for its synchronization context and passed
   *     through to child load balancers
   * @param childProvider provides the per-endpoint child load balancer, normally {@code
   *     pick_first}. It is wrapped in a {@link LazyLoadBalancer} here, so a child is not created
   *     until it is first asked to connect
   * @param childStateListener run after a child reports a new connectivity state or picker.
   *     Invoked on the synchronization context, never during {@link #updateEndpoints} or after
   *     {@link #shutdown}
   */
  EndpointMap(Helper helper, LoadBalancerProvider childProvider, Runnable childStateListener) {
    this.helper = checkNotNull(helper, "helper");
    this.childProvider = checkNotNull(childProvider, "childProvider");
    this.childStateListener = checkNotNull(childStateListener, "childStateListener");
  }

  /**
   * Replaces the set of endpoints, assigning each a new index.
   *
   * <p>An endpoint whose hostname appears in both the old and the new set keeps its child load
   * balancer, along with its connections and last reported state; only its addresses and index
   * are refreshed. Endpoints that disappear have their child load balancers shut down. New
   * endpoints start out IDLE with no child load balancer instantiated.
   *
   * <p>If several endpoints resolve to the same hostname, the first one wins and the rest are
   * dropped.
   *
   * <p>Children handed new addresses here often report a connectivity state before this method
   * returns. Those reports are recorded but not forwarded to the {@code childStateListener}, so
   * that one resolver update produces one picker rather than one per endpoint. <strong>The
   * caller must therefore publish a picker itself once this returns</strong>, or the channel is
   * left holding a picker built from the previous endpoint set.
   *
   * @param endpoints the endpoints from the resolver, in the order the resolver supplied them
   * @param attributes the resolver attributes, forwarded to every child load balancer
   */
  void updateEndpoints(List<EquivalentAddressGroup> endpoints, Attributes attributes) {
    Map<String, EquivalentAddressGroup> addressesByHostname = new LinkedHashMap<>();
    for (EquivalentAddressGroup endpoint : endpoints) {
      String hostname = hostnameOf(endpoint);
      if (addressesByHostname.putIfAbsent(hostname, endpoint) != null) {
        logger.log(Level.FINE, "Dropping duplicate endpoint for hostname {0}", hostname);
      }
    }

    // Children of endpoints the resolver no longer reports are shut down and dropped.
    Map<String, EndpointHolder> survivors = new HashMap<>();
    for (EndpointHolder holder : holders) {
      if (addressesByHostname.containsKey(holder.hostname)) {
        survivors.put(holder.hostname, holder);
      } else {
        holder.shutdown();
      }
    }

    // Install the whole endpoint set and its indices before touching any child. A child given
    // addresses in the second pass can call back in synchronously, and everything it can reach
    // -- size(), indexOf(), toPickerEndpoints() -- has to already agree on the new set.
    holders.clear();
    indexByHostname.clear();
    for (String hostname : addressesByHostname.keySet()) {
      EndpointHolder survivor = survivors.get(hostname);
      indexByHostname.put(hostname, holders.size());
      holders.add(survivor != null ? survivor : new EndpointHolder(hostname));
    }

    rebuilding = true;
    try {
      for (EndpointHolder holder : holders) {
        holder.updateAddresses(addressesByHostname.get(holder.hostname), attributes);
      }
    } finally {
      rebuilding = false;
    }
  }

  /** Returns the number of endpoints currently held. */
  int size() {
    return holders.size();
  }

  /**
   * Returns the index of {@code hostname}, or {@code -1} if no endpoint with that hostname is
   * currently held. Used to translate the hostnames in an {@link Assignment} into the indices
   * that {@link SliceMap} and {@link AutoShardingPicker} work with.
   */
  int indexOf(String hostname) {
    Integer index = indexByHostname.get(hostname);
    return index == null ? -1 : index;
  }

  /**
   * Returns an immutable snapshot of the current endpoint states, where element {@code i}
   * describes the endpoint with index {@code i}.
   *
   * <p>The snapshot is safe to hand to a picker running on RPC threads: it captures the
   * connectivity state and picker by value, and reaches back into this class only through
   * {@link PickerEndpoint#requestConnection}.
   */
  ImmutableList<PickerEndpoint> toPickerEndpoints() {
    ImmutableList.Builder<PickerEndpoint> snapshot =
        ImmutableList.builderWithExpectedSize(holders.size());
    for (EndpointHolder holder : holders) {
      snapshot.add(holder.toPickerEndpoint());
    }
    return snapshot.build();
  }

  /**
   * Returns the aggregated connectivity state to report for the channel, using the {@code
   * ring_hash} rules from gRFC A42 that gRFC A119 adopts:
   *
   * <ol>
   *   <li>at least one endpoint READY, report READY;
   *   <li>two or more endpoints TRANSIENT_FAILURE, report TRANSIENT_FAILURE;
   *   <li>at least one endpoint CONNECTING, report CONNECTING;
   *   <li>exactly one endpoint TRANSIENT_FAILURE and more than one endpoint, report CONNECTING;
   *   <li>at least one endpoint IDLE, report IDLE;
   *   <li>otherwise report TRANSIENT_FAILURE.
   * </ol>
   *
   * <p>An empty map reports TRANSIENT_FAILURE, matching rule 6.
   */
  ConnectivityState aggregateConnectivityState() {
    int connecting = 0;
    int idle = 0;
    int transientFailure = 0;
    for (EndpointHolder holder : holders) {
      switch (holder.state) {
        case READY:
          return READY;
        case CONNECTING:
          connecting++;
          break;
        case IDLE:
          idle++;
          break;
        case TRANSIENT_FAILURE:
          transientFailure++;
          break;
        default:
          break;
      }
    }
    if (transientFailure >= 2) {
      return TRANSIENT_FAILURE;
    }
    if (connecting > 0) {
      return CONNECTING;
    }
    if (transientFailure == 1 && holders.size() > 1) {
      return CONNECTING;
    }
    if (idle > 0) {
      return IDLE;
    }
    return TRANSIENT_FAILURE;
  }

  /**
   * Starts connecting on one IDLE endpoint if the aggregated state is CONNECTING or
   * TRANSIENT_FAILURE and no endpoint is already CONNECTING; otherwise does nothing, so it can be
   * called after every child state update and resolver update.
   *
   * <p>Because this policy only connects in response to picks, such an aggregated state could
   * otherwise persist with nothing in flight to resolve it, so gRFC A119 has the policy nudge a
   * single endpoint. An IDLE or READY aggregate is left alone, which keeps the policy lazy.
   */
  void maybeWakeUpIdleEndpoint() {
    ConnectivityState aggregate = aggregateConnectivityState();
    if (aggregate != CONNECTING && aggregate != TRANSIENT_FAILURE) {
      return;
    }
    EndpointHolder firstIdle = null;
    for (EndpointHolder holder : holders) {
      if (holder.state == CONNECTING) {
        return;
      }
      if (firstIdle == null && holder.state == IDLE) {
        firstIdle = holder;
      }
    }
    if (firstIdle != null) {
      firstIdle.requestConnection();
    }
  }

  /** Shuts down every child load balancer and empties the map. Idempotent. */
  void shutdown() {
    for (EndpointHolder holder : holders) {
      holder.shutdown();
    }
    holders.clear();
    indexByHostname.clear();
  }

  /**
   * Returns the hostname identifying {@code endpoint}. Falls back to the endpoint's first
   * address when the hostname attribute from gRFC A81 is absent, per gRFC A119.
   */
  private static String hostnameOf(EquivalentAddressGroup endpoint) {
    String hostname =
        endpoint.getAttributes().get(InternalEquivalentAddressGroup.ATTR_ADDRESS_NAME);
    return hostname != null ? hostname : addressName(endpoint.getAddresses().get(0));
  }

  /**
   * Formats an address as {@code ip:port}, with IPv6 compressed and in brackets, to match how an
   * assignment names endpoints. {@link InetSocketAddress#toString()} would add a {@code /} and
   * any hostname.
   */
  private static String addressName(SocketAddress address) {
    if (!(address instanceof InetSocketAddress)) {
      return address.toString();
    }
    InetSocketAddress inetAddress = (InetSocketAddress) address;
    String host =
        inetAddress.isUnresolved()
            ? inetAddress.getHostString()
            : InetAddresses.toAddrString(inetAddress.getAddress());
    return HostAndPort.fromParts(host, inetAddress.getPort()).toString();
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this).add("endpoints", holders).toString();
  }

  /**
   * The child load balancer for a single endpoint, together with the connectivity state and
   * picker it most recently reported.
   */
  private final class EndpointHolder {
    private final String hostname;
    private final LazyLoadBalancer childLb;
    private ConnectivityState state = IDLE;
    private SubchannelPicker picker = new FixedResultPicker(PickResult.withNoResult());
    private boolean childShutdown;

    EndpointHolder(String hostname) {
      this.hostname = hostname;
      this.childLb = new LazyLoadBalancer(new ChildHelper(), childProvider);
    }

    /** Captures the current state for use by a picker on RPC threads. */
    PickerEndpoint toPickerEndpoint() {
      return new PickerEndpoint(state, picker, this::exitIdle);
    }

    void updateAddresses(EquivalentAddressGroup endpoint, Attributes attributes) {
      Status status =
          childLb.acceptResolvedAddresses(
              ResolvedAddresses.newBuilder()
                  .setAddresses(ImmutableList.of(endpoint))
                  .setAttributes(attributes)
                  .build());
      if (!status.isOk()) {
        // pick_first only rejects an address list it cannot use at all, which should not happen
        // for the single well-formed endpoint we pass. Report it rather than silently dropping
        // it; the endpoint simply stays in whatever state it was already in.
        logger.log(
            Level.WARNING,
            "Child load balancer for endpoint {0} rejected its addresses: {1}",
            new Object[] {hostname, status});
      }
    }

    /** Starts connecting if this endpoint is IDLE. */
    void requestConnection() {
      if (childShutdown || state != IDLE) {
        return;
      }
      childLb.requestConnection();
    }

    /**
     * The {@link PickerEndpoint.ExitIdler} handed to pickers. Called from RPC threads, so it hops
     * onto the synchronization context, where {@link #requestConnection} re-checks the state;
     * that makes repeated calls, and calls for a removed endpoint, harmless.
     */
    private void exitIdle() {
      helper.getSynchronizationContext().execute(this::requestConnection);
    }

    void shutdown() {
      if (childShutdown) {
        return;
      }
      // Set before shutting the child down: a child may report from inside its own shutdown(),
      // and this flag is what keeps that report away from the listener, both here and for the
      // endpoints updateEndpoints() removes before it sets rebuilding.
      childShutdown = true;
      childLb.shutdown();
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("hostname", hostname)
          .add("state", state)
          .toString();
    }

    /**
     * Intercepts the child's balancing state so that it is recorded here instead of being
     * published straight to the channel. The LB policy aggregates across all endpoints and
     * publishes a single state and picker of its own.
     */
    private final class ChildHelper extends ForwardingLoadBalancerHelper {
      @Override
      protected Helper delegate() {
        return helper;
      }

      @Override
      public void updateBalancingState(ConnectivityState newState, SubchannelPicker newPicker) {
        if (childShutdown) {
          return;
        }
        state = newState;
        picker = newPicker;
        if (!rebuilding) {
          childStateListener.run();
        }
      }
    }
  }
}
