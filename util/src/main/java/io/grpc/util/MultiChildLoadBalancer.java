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
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
  @VisibleForTesting
  protected boolean resolvingAddresses;

  protected final PickFirstLoadBalancerProvider pickFirstLbProvider =
      new PickFirstLoadBalancerProvider();

  protected ConnectivityState currentConnectivityState;

  protected MultiChildLoadBalancer(Helper helper) {
    this.helper = checkNotNull(helper, "helper");
    logger.log(Level.FINE, "Created");
  }

<<<<<<< HEAD
  protected abstract SubchannelPicker getSubchannelPicker(
      Map<Object, SubchannelPicker> childPickers);

  protected static EquivalentAddressGroup stripAttrs(EquivalentAddressGroup eag) {
    return new EquivalentAddressGroup(eag.getAddresses());
  }

=======
  protected static EquivalentAddressGroup stripAttrs(EquivalentAddressGroup eag) {
    if (eag.getAttributes() == Attributes.EMPTY) {
      return eag;
    } else {
      return new EquivalentAddressGroup(eag.getAddresses());
    }
  }

  protected abstract SubchannelPicker getSubchannelPicker(
      Map<Object, SubchannelPicker> childPickers);

>>>>>>> 87585d87f (Responded to a number of the code review comments.)
  protected SubchannelPicker getInitialPicker() {
    return EMPTY_PICKER;
  }

  protected SubchannelPicker getErrorPicker(Status error)  {
    return new FixedResultPicker(PickResult.withError(error));
  }

  /**
   * Generally, the only reason to override this is to expose it to a test of a LB in a different
   * package.
   */
  @VisibleForTesting
  protected Collection<ChildLbState> getChildLbStates() {
    return childLbStates.values();
  }

  /**
   * Generally, the only reason to override this is to expose it to a test of a LB in a
   * different package.
    */
  @VisibleForTesting
  protected Collection<ChildLbState> getChildLbStates() {
    return childLbStates.values();
  }

  protected ChildLbState getChildLbState(EquivalentAddressGroup eag) {
    if (eag == null) {
      return null;
    }
    return childLbStates.get(stripAttrs(eag));
  }

  /**
   * Override to utilize parsing of the policy configuration or alternative helper/lb generation.
   */
  protected Map<Object, ChildLbState> createChildLbMap(ResolvedAddresses resolvedAddresses) {
    Map<Object, ChildLbState> childLbMap = new HashMap<>();
    List<EquivalentAddressGroup> addresses = resolvedAddresses.getAddresses();
    Object policyConfig = resolvedAddresses.getLoadBalancingPolicyConfig();
    for (EquivalentAddressGroup eag : addresses) {
      EquivalentAddressGroup strippedEag = stripAttrs(eag); // keys need to be just addresses
      ChildLbState childLbState = new ChildLbState(strippedEag, pickFirstLbProvider, policyConfig,
          getInitialPicker());
      childLbMap.put(strippedEag, childLbState);
    }
    return childLbMap;
  }

  @Override
  public boolean acceptResolvedAddresses(ResolvedAddresses resolvedAddresses) {
    try {
      resolvingAddresses = true;
      return acceptResolvedAddressesInternal(resolvedAddresses);
    } finally {
      resolvingAddresses = false;
    }
  }

  protected ResolvedAddresses getChildAddresses(Object key, ResolvedAddresses resolvedAddresses,
      Object childConfig) {
    checkArgument(key instanceof EquivalentAddressGroup, "key is wrong type");

    // Retrieve the non-stripped version
    EquivalentAddressGroup eag = null;
    for (EquivalentAddressGroup equivalentAddressGroup : resolvedAddresses.getAddresses()) {
      if (stripAttrs(equivalentAddressGroup).equals(key)) {
        eag = equivalentAddressGroup;
        break;
      }
    }

    checkNotNull(eag, key.toString() + " no longer present in load balancer children");

    return resolvedAddresses.toBuilder()
        .setAddresses(Collections.singletonList(eag))
        .setLoadBalancingPolicyConfig(childConfig)
        .build();
  }

<<<<<<< HEAD
  protected ChildLbState getChildLbState(Object key) {
    if (key == null) {
      return null;
    }
    if (key instanceof EquivalentAddressGroup) {
      key = new Endpoint((EquivalentAddressGroup) key);
    }
    return childLbStates.get(key);
  }

  /**
   * Generally, the only reason to override this is to expose it to a test of a LB in a different
   * package.
   */
  protected ChildLbState getChildLbStateEag(EquivalentAddressGroup eag) {
    return getChildLbState(new Endpoint(eag));
  }

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
        childLbMap.put(endpoint, createChildLbState(endpoint, null, getInitialPicker()));
      }
    }
    return childLbMap;
  }

  /**
   * Override to create an instance of a subclass.
   */
  protected ChildLbState createChildLbState(Object key, Object policyConfig,
      SubchannelPicker initialPicker) {
    return new ChildLbState(key, pickFirstLbProvider, policyConfig, initialPicker);
  }

  /**
   *   Override to completely replace the default logic or to do additional activities.
   */
  @Override
  public Status acceptResolvedAddresses(ResolvedAddresses resolvedAddresses) {
    try {
      resolvingAddresses = true;
      return acceptResolvedAddressesInternal(resolvedAddresses);
    } finally {
      resolvingAddresses = false;
    }
  }
=======

>>>>>>> 87585d87f (Responded to a number of the code review comments.)

  /**
   * Override this if your keys are not of type Endpoint.
   * @param key Key to identify the ChildLbState
   * @param resolvedAddresses list of addresses which include attributes
   * @param childConfig a load balancing policy config. This field is optional.
   * @return a fully loaded ResolvedAddresses object for the specified key
   */
  protected ResolvedAddresses getChildAddresses(Object key, ResolvedAddresses resolvedAddresses,
      Object childConfig) {
    if (key instanceof EquivalentAddressGroup) {
      key = new Endpoint((EquivalentAddressGroup) key);
    }
    checkArgument(key instanceof Endpoint, "key is wrong type");

    // Retrieve the non-stripped version
    EquivalentAddressGroup eagToUse = null;
    for (EquivalentAddressGroup currEag : resolvedAddresses.getAddresses()) {
      if (key.equals(new Endpoint(currEag))) {
        eagToUse = currEag;
        break;
      }
    }

    checkNotNull(eagToUse, key + " no longer present in load balancer children");

    return resolvedAddresses.toBuilder()
        .setAddresses(Collections.singletonList(eagToUse))
        .setLoadBalancingPolicyConfig(childConfig)
        .build();
  }

  private Status acceptResolvedAddressesInternal(ResolvedAddresses resolvedAddresses) {
    logger.log(Level.FINE, "Received resolution result: {0}", resolvedAddresses);
    Map<Object, ChildLbState> newChildren = createChildLbMap(resolvedAddresses);

    if (newChildren.isEmpty()) {
      Status unavailableStatus = Status.UNAVAILABLE.withDescription(
              "NameResolver returned no usable address. " + resolvedAddresses);
      handleNameResolutionError(unavailableStatus);
      return unavailableStatus;
    }

    // Do adds and updates
    for (Map.Entry<Object, ChildLbState> entry : newChildren.entrySet()) {
      final Object key = entry.getKey();
      LoadBalancerProvider childPolicyProvider = entry.getValue().getPolicyProvider();
      Object childConfig = entry.getValue().getConfig();
      if (!childLbStates.containsKey(key)) {
        childLbStates.put(key, entry.getValue());
      } else {
        // Reuse the existing one
        ChildLbState existingChildLbState = childLbStates.get(key);
        if (existingChildLbState.isDeactivated()) {
          existingChildLbState.reactivate(childPolicyProvider);
        }
      }

      LoadBalancer childLb = childLbStates.get(key).lb;
      ResolvedAddresses childAddresses = getChildAddresses(key, resolvedAddresses, childConfig);
      childLbStates.get(key).setResolvedAddresses(childAddresses); // update child state
      childLb.handleResolvedAddresses(childAddresses); // update child LB
      if (childLb != entry.getValue().getLb()) {
        entry.getValue().shutdown(); // Reused old LB
      }
    }

    // Do removals
    for (Object key : ImmutableList.copyOf(childLbStates.keySet())) {
      if (!newChildren.containsKey(key)) {
        childLbStates.get(key).deactivate();
      }
    }
    // Must update channel picker before return so that new RPCs will not be routed to deleted
    // clusters and resolver can remove them in service config.
    updateOverallBalancingState();
    return Status.OK;
  }

  @Override
  public void handleNameResolutionError(Status error) {
    if (currentConnectivityState != READY)  {
      updateHelperBalancingState(TRANSIENT_FAILURE, getErrorPicker(error));
    }
  }

  protected void handleNameResolutionError(ChildLbState child, Status error) {
    child.lb.handleNameResolutionError(error);
  }

  /**
   * If true, then when a subchannel state changes to idle, the corresponding child will
   * have requestConnection called on its LB.
   */
  protected boolean reconnectOnIdle() {
    return true;
  }

  @Override
  public void shutdown() {
    logger.log(Level.INFO, "Shutdown");
    for (ChildLbState state : childLbStates.values()) {
      state.shutdown();
    }
    childLbStates.clear();
  }

  protected void updateOverallBalancingState() {
    ConnectivityState overallState = null;
    final Map<Object, SubchannelPicker> childPickers = new HashMap<>();
    for (ChildLbState childLbState : getChildLbStates()) {
      if (childLbState.deactivated) {
        continue;
      }
      childPickers.put(childLbState.key, childLbState.currentPicker);
      overallState = aggregateState(overallState, childLbState.currentState);
    }
    if (overallState != null) {
      helper.updateBalancingState(overallState, getSubchannelPicker(childPickers));
      currentConnectivityState = overallState;
    }
  }

  protected final void updateHelperBalancingState(ConnectivityState newState,
      SubchannelPicker newPicker) {
    helper.updateBalancingState(newState, newPicker);
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

  protected Helper getHelper() {
    return helper;
  }

  protected void removeChild(Object key) {
    childLbStates.remove(key);
  }

  /**
   * Filters out non-ready and deactivated child load balancers (subchannels).
   */
  protected List<ChildLbState> getReadyChildren() {
    List<ChildLbState> activeChildren = new ArrayList<>();
    for (ChildLbState child : getChildLbStates()) {
      if (!child.isDeactivated() && child.getCurrentState() == READY) {
        activeChildren.add(child);
      }
    }
    return activeChildren;
  }

  /**
   * This represents the state of load balancer children.  Each endpoint (represented by an
   * EquivalentAddressGroup or EDS string) will have a separate ChildLbState which in turn will
   * define a GracefulSwitchLoadBalancer.  When the GracefulSwitchLoadBalancer is activated, a
   * single PickFirstLoadBalancer will be created which will then create a subchannel and start
   * trying to connect to it.
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
    private final GracefulSwitchLoadBalancer lb;
    private LoadBalancerProvider policyProvider;
    private ConnectivityState currentState = CONNECTING;
    private SubchannelPicker currentPicker;
    private boolean deactivated;

    public ChildLbState(Object key, LoadBalancerProvider policyProvider, Object childConfig,
        SubchannelPicker initialPicker) {
      this.key = key;
      this.policyProvider = policyProvider;
      lb = new GracefulSwitchLoadBalancer(new ChildLbStateHelper());
      lb.switchTo(policyProvider);
      currentPicker = initialPicker;
      config = childConfig;
    }

    @Override
    public String toString() {
      return "Address = " + key
          + ", state = " + currentState
          + ", picker type: " + currentPicker.getClass()
          + ", lb: " + lb.delegate().getClass()
          + (deactivated ? ", deactivated" : "");
    }

    public Object getKey() {
      return key;
    }

    Object getConfig() {
      return config;
    }

    public LoadBalancerProvider getPolicyProvider() {
      return policyProvider;
    }

    protected Subchannel getSubchannels(PickSubchannelArgs args) {
      if (getCurrentPicker() == null) {
        return null;
      }
      return getCurrentPicker().pickSubchannel(args).getSubchannel();
    }

    public ConnectivityState getCurrentState() {
      return currentState;
    }

    public SubchannelPicker getCurrentPicker() {
      return currentPicker;
    }

    public EquivalentAddressGroup getEag() {
      if (resolvedAddresses == null || resolvedAddresses.getAddresses().isEmpty()) {
        return null;
      }
      return resolvedAddresses.getAddresses().get(0);
    }

    public boolean isDeactivated() {
      return deactivated;
    }

    @VisibleForTesting
    LoadBalancer getLb() {
      return this.lb;
    }

    protected void setDeactivated() {
      deactivated = true;
    }

    protected void setResolvedAddresses(ResolvedAddresses newAddresses) {
      checkNotNull(newAddresses, "Missing address list for child");
      resolvedAddresses = newAddresses;
    }

    protected void deactivate() {
      if (deactivated) {
        return;
      }

      shutdown();
      childLbStates.remove(key);
      deactivated = true;
      logger.log(Level.FINE, "Child balancer {0} deactivated", key);
    }

    protected void reactivate(LoadBalancerProvider policyProvider) {
      if (!this.policyProvider.getPolicyName().equals(policyProvider.getPolicyName())) {
        Object[] objects = {
            key, this.policyProvider.getPolicyName(),policyProvider.getPolicyName()};
        logger.log(Level.FINE, "Child balancer {0} switching policy from {1} to {2}", objects);
        lb.switchTo(policyProvider);
        this.policyProvider = policyProvider;
      } else {
        logger.log(Level.FINE, "Child balancer {0} reactivated", key);
        lb.acceptResolvedAddresses(resolvedAddresses);
      }

      deactivated = false;
    }

    protected void shutdown() {
      lb.shutdown();
      this.currentState = SHUTDOWN;
      logger.log(Level.FINE, "Child balancer {0} deleted", key);
    }

<<<<<<< HEAD
    /**
     * ChildLbStateHelper is the glue between ChildLbState and the helpers associated with the
     * petiole policy above and the PickFirstLoadBalancer's helper below.
     *
     * <p>The ChildLbState updates happen during updateBalancingState.  Otherwise, it is doing
     * simple forwarding.
     */
=======
>>>>>>> 87585d87f (Responded to a number of the code review comments.)
    private final class ChildLbStateHelper extends ForwardingLoadBalancerHelper {

      @Override
      public void updateBalancingState(final ConnectivityState newState,
          final SubchannelPicker newPicker) {
        // If we are already in the process of resolving addresses, the overall balancing state
        // will be updated at the end of it, and we don't need to trigger that update here.
        if (!childLbStates.containsKey(key)) {
          return;
        }
        // Subchannel picker and state are saved, but will only be propagated to the channel
        // when the child instance exits deactivated state.
        currentState = newState;
        currentPicker = newPicker;
        if (!deactivated && !resolvingAddresses) {
          if (newState == IDLE && reconnectOnIdle()) {
            lb.requestConnection();
          }
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
   * Ignores the attributes, orders the addresses in a deterministic manner and converts each
   * address into a string for easy comparison.  Also caches the hashcode.
   * Is used as a key for ChildLbState for most load balancers (ClusterManagerLB uses a String).
   */
  protected static class Endpoint {
    final String[] addrs;
    final int hashCode;

    Endpoint(EquivalentAddressGroup eag) {
      checkNotNull(eag, "eag");

      addrs = new String[eag.getAddresses().size()];
      int i = 0;
      for (SocketAddress address : eag.getAddresses()) {
        addrs[i] = address.toString();
      }
      Arrays.sort(addrs);

      hashCode = Arrays.hashCode(addrs);
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
      if (other == null) {
        return false;
      }

      if (!(other instanceof Endpoint)) {
        return false;
      }
      Endpoint o = (Endpoint) other;
      if (o.hashCode != hashCode || o.addrs.length != addrs.length) {
        return false;
      }

      return Arrays.equals(o.addrs, this.addrs);
    }

    @Override
    public String toString() {
      return Arrays.toString(addrs);
    }
  }
}
