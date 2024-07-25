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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static io.grpc.ConnectivityState.CONNECTING;
import static io.grpc.ConnectivityState.IDLE;
import static io.grpc.ConnectivityState.READY;
import static io.grpc.ConnectivityState.SHUTDOWN;
import static io.grpc.ConnectivityState.TRANSIENT_FAILURE;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
  private final Map<Object, ChildLbState> childLbStates = new LinkedHashMap<>();
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
   */
  protected Map<Object, ChildLbState> createChildLbMap(ResolvedAddresses resolvedAddresses) {
    Map<Object, ChildLbState> childLbMap = new HashMap<>();
    List<EquivalentAddressGroup> addresses = resolvedAddresses.getAddresses();
    for (EquivalentAddressGroup eag : addresses) {
      Endpoint endpoint = new Endpoint(eag); // keys need to be just addresses
      ChildLbState existingChildLbState = childLbStates.get(endpoint);
      if (existingChildLbState != null) {
        childLbMap.put(endpoint, existingChildLbState);
      } else {
        childLbMap.put(endpoint,
            createChildLbState(endpoint, null, getInitialPicker(), resolvedAddresses));
      }
    }
    return childLbMap;
  }

  /**
   * Override to create an instance of a subclass.
   */
  protected ChildLbState createChildLbState(Object key, Object policyConfig,
      SubchannelPicker initialPicker, ResolvedAddresses resolvedAddresses) {
    return new ChildLbState(key, pickFirstLbProvider, policyConfig, initialPicker);
  }

  /**
   *   Override to completely replace the default logic or to do additional activities.
   */
  @Override
  public Status acceptResolvedAddresses(ResolvedAddresses resolvedAddresses) {
    try {
      resolvingAddresses = true;

      // process resolvedAddresses to update children
      AcceptResolvedAddrRetVal acceptRetVal = acceptResolvedAddressesInternal(resolvedAddresses);
      if (!acceptRetVal.status.isOk()) {
        return acceptRetVal.status;
      }

      // Update the picker and our connectivity state
      updateOverallBalancingState();

      // shutdown removed children
      shutdownRemoved(acceptRetVal.removedChildren);
      return acceptRetVal.status;
    } finally {
      resolvingAddresses = false;
    }
  }

  /**
   * Override this if your keys are not of type Endpoint.
   * @param key Key to identify the ChildLbState
   * @param resolvedAddresses list of addresses which include attributes
   * @param childConfig a load balancing policy config. This field is optional.
   * @return a fully loaded ResolvedAddresses object for the specified key
   */
  protected ResolvedAddresses getChildAddresses(Object key, ResolvedAddresses resolvedAddresses,
      Object childConfig) {
    Endpoint endpointKey;
    if (key instanceof EquivalentAddressGroup) {
      endpointKey = new Endpoint((EquivalentAddressGroup) key);
    } else {
      checkArgument(key instanceof Endpoint, "key is wrong type");
      endpointKey = (Endpoint) key;
    }

    // Retrieve the non-stripped version
    EquivalentAddressGroup eagToUse = null;
    for (EquivalentAddressGroup currEag : resolvedAddresses.getAddresses()) {
      if (endpointKey.equals(new Endpoint(currEag))) {
        eagToUse = currEag;
        break;
      }
    }

    checkNotNull(eagToUse, key + " no longer present in load balancer children");

    return resolvedAddresses.toBuilder()
        .setAddresses(Collections.singletonList(eagToUse))
        .setAttributes(Attributes.newBuilder().set(IS_PETIOLE_POLICY, true).build())
        .setLoadBalancingPolicyConfig(childConfig)
        .build();
  }

  /**
   * Handle the name resolution error.
   *
   * <p/>Override if you need special handling.
   */
  @Override
  public void handleNameResolutionError(Status error) {
    if (currentConnectivityState != READY)  {
      helper.updateBalancingState(TRANSIENT_FAILURE, getErrorPicker(error));
    }
  }

  /**
   * Handle the name resolution error only for the specified child.
   *
   * <p/>Override if you need special handling.
   */
  protected void handleNameResolutionError(ChildLbState child, Status error) {
    child.lb.handleNameResolutionError(error);
  }

  /**
   * Creates a picker representing the state before any connections have been established.
   *
   * <p/>Override to produce a custom picker.
   */
  protected SubchannelPicker getInitialPicker() {
    return new FixedResultPicker(PickResult.withNoResult());
  }

  /**
   * Creates a new picker representing an error status.
   *
   * <p/>Override to produce a custom picker when there are errors.
   */
  protected SubchannelPicker getErrorPicker(Status error)  {
    return new FixedResultPicker(PickResult.withError(error));
  }

  @Override
  public void shutdown() {
    logger.log(Level.FINE, "Shutdown");
    for (ChildLbState state : childLbStates.values()) {
      state.shutdown();
    }
    childLbStates.clear();
  }

  /**
   *   This does the work to update the child map and calculate which children have been removed.
   *   You must call {@link #updateOverallBalancingState} to update the picker
   *   and call {@link #shutdownRemoved(List)} to shutdown the endpoints that have been removed.
    */
  protected final AcceptResolvedAddrRetVal acceptResolvedAddressesInternal(
      ResolvedAddresses resolvedAddresses) {
    logger.log(Level.FINE, "Received resolution result: {0}", resolvedAddresses);

    // Subclass handles any special manipulation to create appropriate types of keyed ChildLbStates
    Map<Object, ChildLbState> newChildren = createChildLbMap(resolvedAddresses);

    // Handle error case
    if (newChildren.isEmpty()) {
      Status unavailableStatus = Status.UNAVAILABLE.withDescription(
          "NameResolver returned no usable address. " + resolvedAddresses);
      handleNameResolutionError(unavailableStatus);
      return new AcceptResolvedAddrRetVal(unavailableStatus, null);
    }

    addMissingChildren(newChildren);

    updateChildrenWithResolvedAddresses(resolvedAddresses, newChildren);

    return new AcceptResolvedAddrRetVal(Status.OK, getRemovedChildren(newChildren.keySet()));
  }

  protected final void addMissingChildren(Map<Object, ChildLbState> newChildren) {
    // Do adds and identify reused children
    for (Map.Entry<Object, ChildLbState> entry : newChildren.entrySet()) {
      final Object key = entry.getKey();
      if (!childLbStates.containsKey(key)) {
        childLbStates.put(key, entry.getValue());
      }
    }
  }

  protected final void updateChildrenWithResolvedAddresses(ResolvedAddresses resolvedAddresses,
                                                     Map<Object, ChildLbState> newChildren) {
    for (Map.Entry<Object, ChildLbState> entry : newChildren.entrySet()) {
      Object childConfig = entry.getValue().getConfig();
      ChildLbState childLbState = childLbStates.get(entry.getKey());
      ResolvedAddresses childAddresses =
          getChildAddresses(entry.getKey(), resolvedAddresses, childConfig);
      childLbState.setResolvedAddresses(childAddresses); // update child
      childLbState.lb.handleResolvedAddresses(childAddresses); // update child LB
    }
  }

  /**
   * Identifies which children have been removed (are not part of the newChildKeys).
   */
  protected final List<ChildLbState> getRemovedChildren(Set<Object> newChildKeys) {
    List<ChildLbState> removedChildren = new ArrayList<>();
    // Do removals
    for (Object key : ImmutableList.copyOf(childLbStates.keySet())) {
      if (!newChildKeys.contains(key)) {
        ChildLbState childLbState = childLbStates.remove(key);
        removedChildren.add(childLbState);
      }
    }
    return removedChildren;
  }

  protected final void shutdownRemoved(List<ChildLbState> removedChildren) {
    // Do shutdowns after updating picker to reduce the chance of failing an RPC by picking a
    // subchannel that has been shutdown.
    for (ChildLbState childLbState : removedChildren) {
      childLbState.shutdown();
    }
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
  public final ImmutableMap<Object, ChildLbState> getImmutableChildMap() {
    return ImmutableMap.copyOf(childLbStates);
  }

  @VisibleForTesting
  public final Collection<ChildLbState> getChildLbStates() {
    return childLbStates.values();
  }

  @VisibleForTesting
  public final ChildLbState getChildLbState(Object key) {
    if (key == null) {
      return null;
    }
    if (key instanceof EquivalentAddressGroup) {
      key = new Endpoint((EquivalentAddressGroup) key);
    }
    return childLbStates.get(key);
  }

  @VisibleForTesting
  public final ChildLbState getChildLbStateEag(EquivalentAddressGroup eag) {
    return getChildLbState(new Endpoint(eag));
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
    private ResolvedAddresses resolvedAddresses;
    private final Object config;

    private final LoadBalancer lb;
    private ConnectivityState currentState;
    private SubchannelPicker currentPicker;

    public ChildLbState(Object key, LoadBalancer.Factory policyFactory, Object childConfig,
          SubchannelPicker initialPicker) {
      this.key = key;
      this.currentPicker = initialPicker;
      this.config = childConfig;
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

    public final EquivalentAddressGroup getEag() {
      if (resolvedAddresses == null || resolvedAddresses.getAddresses().isEmpty()) {
        return null;
      }
      return resolvedAddresses.getAddresses().get(0);
    }

    protected final void setResolvedAddresses(ResolvedAddresses newAddresses) {
      checkNotNull(newAddresses, "Missing address list for child");
      resolvedAddresses = newAddresses;
    }

    private Object getConfig() {
      return config;
    }

    @VisibleForTesting
    public final ResolvedAddresses getResolvedAddresses() {
      return resolvedAddresses;
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
        if (!childLbStates.containsKey(key)) {
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

  protected static class AcceptResolvedAddrRetVal {
    public final Status status;
    public final List<ChildLbState> removedChildren;

    public AcceptResolvedAddrRetVal(Status status, List<ChildLbState> removedChildren) {
      this.status = status;
      this.removedChildren = removedChildren;
    }
  }
}
