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

import static com.google.common.base.Preconditions.checkNotNull;
import static io.grpc.ConnectivityState.CONNECTING;
import static io.grpc.ConnectivityState.IDLE;
import static io.grpc.ConnectivityState.READY;
import static io.grpc.ConnectivityState.SHUTDOWN;
import static io.grpc.ConnectivityState.TRANSIENT_FAILURE;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.primitives.UnsignedInts;
import io.grpc.Attributes;
import io.grpc.ConnectivityState;
import io.grpc.EquivalentAddressGroup;
import io.grpc.Internal;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancerProvider;
import io.grpc.Status;
import io.grpc.internal.PickFirstLoadBalancerProvider;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;

/**
 * A base load balancing policy for those policies which has multiple children such as
 * ClusterManager or the petiole policies.  For internal use only.
 */
@Internal
public abstract class MultiChildLoadBalancer extends LoadBalancer {

  private static final Logger logger = Logger.getLogger(MultiChildLoadBalancer.class.getName());
  private static final int OFFSET_SEED = new Random().nextInt();
  /**
   * Upper bound on how many endpoints {@link #aggregateConnectingDelayReason} names before it
   * summarises the rest. Keeps the delay reason bounded for large endpoint sets.
   */
  private static final int MAX_ENDPOINTS_IN_DELAY_REASON = 5;
  // Modify by replacing the list to release memory when no longer used.
  private List<ChildLbState> childLbStates = new ArrayList<>(0);
  private final Helper helper;
  // Set to true if currently in the process of handling resolved addresses.
  protected boolean resolvingAddresses;

  protected final LoadBalancerProvider pickFirstLbProvider = new PickFirstLoadBalancerProvider();

  protected ConnectivityState currentConnectivityState;


  protected MultiChildLoadBalancer(Helper helper) {
    this.helper = checkNotNull(helper, "helper");
    logger.log(Level.FINE, "Created");
  }

  /**
   * Using the state of all children will calculate the current connectivity state,
   * update fields, generate a picker and then call
   * {@link Helper#updateBalancingState(ConnectivityState, SubchannelPicker)}.
   */
  protected abstract void updateOverallBalancingState();

  /**
   * Override to utilize parsing of the policy configuration or alternative helper/lb generation.
   * Override this if keys are not Endpoints or if child policies have configuration. Null map
   * values preserve the child without delivering the child an update.
   */
  protected Map<Object, ResolvedAddresses> createChildAddressesMap(
      ResolvedAddresses resolvedAddresses) {
    Map<Object, ResolvedAddresses> childAddresses =
        Maps.newLinkedHashMapWithExpectedSize(resolvedAddresses.getAddresses().size());
    for (EquivalentAddressGroup eag : resolvedAddresses.getAddresses()) {
      ResolvedAddresses addresses = resolvedAddresses.toBuilder()
          .setAddresses(Collections.singletonList(eag))
          .setAttributes(Attributes.newBuilder().set(IS_PETIOLE_POLICY, true).build())
          .setLoadBalancingPolicyConfig(null)
          .build();
      childAddresses.put(new Endpoint(eag), addresses);
    }
    return childAddresses;
  }

  /**
   * Override to create an instance of a subclass.
   */
  protected ChildLbState createChildLbState(Object key) {
    return new ChildLbState(key, pickFirstLbProvider);
  }

  /**
   *   Override to completely replace the default logic or to do additional activities.
   */
  @Override
  public Status acceptResolvedAddresses(ResolvedAddresses resolvedAddresses) {
    logger.log(Level.FINE, "Received resolution result: {0}", resolvedAddresses);
    try {
      resolvingAddresses = true;

      // process resolvedAddresses to update children
      Map<Object, ResolvedAddresses> newChildAddresses = createChildAddressesMap(resolvedAddresses);

      // Handle error case
      if (newChildAddresses.isEmpty()) {
        Status unavailableStatus = Status.UNAVAILABLE.withDescription(
            "NameResolver returned no usable address. " + resolvedAddresses);
        handleNameResolutionError(unavailableStatus);
        return unavailableStatus;
      }

      return updateChildrenWithResolvedAddresses(newChildAddresses);
    } finally {
      resolvingAddresses = false;
    }
  }

  /**
   * Handle the name resolution error.
   *
   * <p/>Override if you need special handling.
   */
  @Override
  public void handleNameResolutionError(Status error) {
    if (currentConnectivityState != READY)  {
      helper.updateBalancingState(
          TRANSIENT_FAILURE, new FixedResultPicker(PickResult.withError(error)));
    }
  }

  @Override
  public void shutdown() {
    logger.log(Level.FINE, "Shutdown");
    for (ChildLbState state : childLbStates) {
      state.shutdown();
    }
    childLbStates.clear();
  }

  private Status updateChildrenWithResolvedAddresses(
      Map<Object, ResolvedAddresses> newChildAddresses) {
    // Create a map with the old values
    Map<Object, ChildLbState> oldStatesMap =
        Maps.newLinkedHashMapWithExpectedSize(childLbStates.size());
    for (ChildLbState state : childLbStates) {
      oldStatesMap.put(state.getKey(), state);
    }

    // Move ChildLbStates from the map to a new list (preserving the new map's order)
    Status status = Status.OK;
    List<ChildLbState> newChildLbStates = new ArrayList<>(newChildAddresses.size());
    for (Map.Entry<Object, ResolvedAddresses> entry : newChildAddresses.entrySet()) {
      ChildLbState childLbState = oldStatesMap.remove(entry.getKey());
      if (childLbState == null) {
        childLbState = createChildLbState(entry.getKey());
      }
      newChildLbStates.add(childLbState);
    }
    // Use a random start position for child updates to weakly "shuffle" connection creation order.
    // The network will often add noise to the creation order, but this avoids giving earlier
    // children a consistent head start.
    for (ChildLbState childLbState : offsetIterable(newChildLbStates, OFFSET_SEED)) {
      ResolvedAddresses addresses = newChildAddresses.get(childLbState.getKey());
      if (addresses != null) {
        // update child LB
        Status newStatus = childLbState.lb.acceptResolvedAddresses(addresses);
        if (!newStatus.isOk()) {
          status = newStatus;
        }
      }
    }

    childLbStates = newChildLbStates;
    // Update the picker and our connectivity state
    updateOverallBalancingState();

    // Remaining entries in map are orphaned
    for (ChildLbState childLbState : oldStatesMap.values()) {
      childLbState.shutdown();
    }
    return status;
  }

  @VisibleForTesting
  static <T> Iterable<T> offsetIterable(Collection<T> c, int seed) {
    int pos;
    if (c.isEmpty()) {
      pos = 0;
    } else {
      pos = UnsignedInts.remainder(seed, c.size());
    }
    return Iterables.concat(
        Iterables.skip(c, pos),
        Iterables.limit(c, pos));
  }

  @Nullable
  protected static ConnectivityState aggregateState(
      @Nullable ConnectivityState overallState, ConnectivityState childState) {
    if (overallState == null) {
      return childState;
    }
    if (overallState == READY || childState == READY) {
      return READY;
    }
    if (overallState == CONNECTING || childState == CONNECTING) {
      return CONNECTING;
    }
    if (overallState == IDLE || childState == IDLE) {
      return IDLE;
    }
    return overallState;
  }

  protected final Helper getHelper() {
    return helper;
  }

  @VisibleForTesting
  public final Collection<ChildLbState> getChildLbStates() {
    return childLbStates;
  }

  /**
   * Filters out non-ready child load balancers (subchannels).
   */
  protected final List<ChildLbState> getReadyChildren() {
    List<ChildLbState> activeChildren = new ArrayList<>();
    for (ChildLbState child : getChildLbStates()) {
      if (child.getCurrentState() == READY) {
        activeChildren.add(child);
      }
    }
    return activeChildren;
  }

  /**
   * Builds the {@code connecting} delay reason for a petiole policy, naming the endpoints that are
   * currently being attempted.
   *
   * <p><a href="https://github.com/grpc/proposal/blob/master/A121-delay-observability.md">gRFC
   * A121</a> requires the reason of a petiole policy's {@code connecting} delay to capture the
   * aggregate connection state across the endpoints being attempted, rather than a fixed string,
   * so that the reason identifies <em>which</em> endpoints the RPC is waiting on.
   *
   * <p>Only the endpoints in {@link ConnectivityState#CONNECTING} or {@link ConnectivityState#IDLE}
   * are listed, because those are the ones the RPC is actually waiting for. The list is sorted so
   * that an unchanged set of endpoints always produces an identical reason and therefore does not
   * churn the picker. It is also truncated, since this string is attached to every delay trace
   * event and a policy may be attempting thousands of endpoints.
   *
   * @param policyName the LB policy name to prefix the reason with, e.g. {@code "round_robin"}
   */
  protected final String aggregateConnectingDelayReason(String policyName) {
    List<String> attempting = new ArrayList<>();
    for (ChildLbState child : getChildLbStates()) {
      ConnectivityState state = child.getCurrentState();
      if (state == CONNECTING || state == IDLE) {
        attempting.add(String.valueOf(child.getKey()));
      }
    }
    if (attempting.isEmpty()) {
      return policyName + ": waiting for any endpoint to connect";
    }
    Collections.sort(attempting);
    String suffix = "";
    if (attempting.size() > MAX_ENDPOINTS_IN_DELAY_REASON) {
      suffix = " and " + (attempting.size() - MAX_ENDPOINTS_IN_DELAY_REASON) + " more";
      attempting = attempting.subList(0, MAX_ENDPOINTS_IN_DELAY_REASON);
    }
    return policyName + ": waiting for any endpoint to connect (attempting "
        + Joiner.on(", ").join(attempting) + suffix + ")";
  }

  /**
   * Returns whether a newly computed picker differs from the one currently published, taking the
   * gRFC A121 delay attributes into account.
   *
   * <p>{@link PickResult#equals} deliberately ignores {@code delayType} and {@code delayReason}
   * because they are diagnostics rather than part of the pick decision. That means
   * {@link FixedResultPicker#equals}, which delegates to it, reports two queued results as equal
   * even when their delay reason changed. A petiole policy that skips
   * {@link Helper#updateBalancingState} on picker equality would then never publish the new
   * reason, and the channel would keep reporting a stale one for the whole delay. Comparing the
   * delay attributes here keeps those updates flowing while still suppressing genuinely
   * no-op updates.
   */
  protected static boolean pickerChanged(
      @Nullable SubchannelPicker currentPicker, SubchannelPicker newPicker) {
    if (!newPicker.equals(currentPicker)) {
      return true;
    }
    PickResult current = fixedPickResult(currentPicker);
    PickResult updated = fixedPickResult(newPicker);
    if (current == null || updated == null) {
      return false;
    }
    return !Objects.equals(current.getDelayType(), updated.getDelayType())
        || !Objects.equals(current.getDelayReason(), updated.getDelayReason());
  }

  /**
   * Returns the fixed {@link PickResult} a {@link FixedResultPicker} always returns, or {@code
   * null} for any other picker. A non-fixed picker computes its result per pick, so its delay
   * attributes cannot be compared up front.
   */
  @Nullable
  private static PickResult fixedPickResult(@Nullable SubchannelPicker picker) {
    if (!(picker instanceof FixedResultPicker)) {
      return null;
    }
    // FixedResultPicker ignores its argument and always returns the result it was built with.
    return picker.pickSubchannel(null);
  }

  /**
   * This represents the state of load balancer children.  Each endpoint (represented by an
   * EquivalentAddressGroup or EDS string) will have a separate ChildLbState which in turn will
   * have a single child LoadBalancer created from the provided factory.
   *
   * <p>A ChildLbStateHelper is the glue between ChildLbState and the helpers associated with the
   * petiole policy above and the PickFirstLoadBalancer's helper below.
   *
   * <p>If you wish to store additional state information related to each subchannel, then extend
   * this class.
   */
  public class ChildLbState {
    private final Object key;
    private final LoadBalancer lb;
    private ConnectivityState currentState;
    private SubchannelPicker currentPicker = new FixedResultPicker(PickResult.withNoResult());

    @SuppressWarnings("this-escape")
    // TODO(okshiva): Fix 'this-escape' from the constructor before making the API public.
    public ChildLbState(Object key, LoadBalancer.Factory policyFactory) {
      this.key = key;
      this.lb = policyFactory.newLoadBalancer(createChildHelper());
      this.currentState = CONNECTING;
    }

    protected ChildLbStateHelper createChildHelper() {
      return new ChildLbStateHelper();
    }

    /**
     * Override for unique behavior such as delayed shutdowns of subchannels.
     */
    protected void shutdown() {
      lb.shutdown();
      this.currentState = SHUTDOWN;
      logger.log(Level.FINE, "Child balancer {0} deleted", key);
    }

    @Override
    public String toString() {
      return "Address = " + key
          + ", state = " + currentState
          + ", picker type: " + currentPicker.getClass()
          + ", lb: " + lb;
    }

    public final Object getKey() {
      return key;
    }

    @VisibleForTesting
    public final LoadBalancer getLb() {
      return lb;
    }

    @VisibleForTesting
    public final SubchannelPicker getCurrentPicker() {
      return currentPicker;
    }

    public final ConnectivityState getCurrentState() {
      return currentState;
    }

    protected final void setCurrentState(ConnectivityState newState) {
      currentState = newState;
    }

    protected final void setCurrentPicker(SubchannelPicker newPicker) {
      currentPicker = newPicker;
    }

    /**
     * ChildLbStateHelper is the glue between ChildLbState and the helpers associated with the
     * petiole policy above and the PickFirstLoadBalancer's helper below.
     *
     * <p>The ChildLbState updates happen during updateBalancingState.  Otherwise, it is doing
     * simple forwarding.
     */
    protected class ChildLbStateHelper extends ForwardingLoadBalancerHelper {

      /**
       * Update current state and picker for this child and then use
       * {@link #updateOverallBalancingState()} for the parent LB.
       */
      @Override
      public void updateBalancingState(final ConnectivityState newState,
          final SubchannelPicker newPicker) {
        if (currentState == SHUTDOWN) {
          return;
        }

        currentState = newState;
        currentPicker = newPicker;
        // If we are already in the process of resolving addresses, the overall balancing state
        // will be updated at the end of it, and we don't need to trigger that update here.
        if (!resolvingAddresses) {
          updateOverallBalancingState();
        }
      }

      @Override
      protected Helper delegate() {
        return helper;
      }
    }
  }

  /**
   * Endpoint is an optimization to quickly lookup and compare EquivalentAddressGroup address sets.
   * It ignores the attributes. Is used as a key for ChildLbState for most load balancers
   * (ClusterManagerLB uses a String).
   */
  protected static class Endpoint {
    final Collection<SocketAddress> addrs;
    final int hashCode;

    public Endpoint(EquivalentAddressGroup eag) {
      checkNotNull(eag, "eag");

      if (eag.getAddresses().size() < 10) {
        addrs = eag.getAddresses();
      } else {
        // This is expected to be very unlikely in practice
        addrs = new HashSet<>(eag.getAddresses());
      }
      int sum = 0;
      for (SocketAddress address : eag.getAddresses()) {
        sum += address.hashCode();
      }
      hashCode = sum;
    }

    @Override
    public int hashCode() {
      return hashCode;
    }

    @Override
    public boolean equals(Object other) {
      if (this == other) {
        return true;
      }

      if (!(other instanceof Endpoint)) {
        return false;
      }
      Endpoint o = (Endpoint) other;
      if (o.hashCode != hashCode || o.addrs.size() != addrs.size()) {
        return false;
      }

      return o.addrs.containsAll(addrs);
    }

    @Override
    public String toString() {
      return addrs.toString();
    }
  }
}
