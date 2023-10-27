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
  protected boolean resolvingAddresses;

  protected final LoadBalancerProvider pickFirstLbProvider = new PickFirstLoadBalancerProvider();

  protected ConnectivityState currentConnectivityState;

  protected MultiChildLoadBalancer(Helper helper) {
    this.helper = checkNotNull(helper, "helper");
    logger.log(Level.FINE, "Created");
  }

  protected abstract SubchannelPicker getSubchannelPicker(
      Map<Object, SubchannelPicker> childPickers);

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
  protected ImmutableMap<Object, ChildLbState> getImmutableChildMap() {
    return ImmutableMap.copyOf(childLbStates);
  }

  @VisibleForTesting
  protected Collection<ChildLbState> getChildLbStates() {
    return childLbStates.values();
  }

  /**
   * Generally, the only reason to override this is to expose it to a test of a LB in a
   * different package.
   */
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
  public boolean acceptResolvedAddresses(ResolvedAddresses resolvedAddresses) {
    try {
      resolvingAddresses = true;

      // process resolvedAddresses to update children
      AcceptResolvedAddressRetVal acceptRetVal =
          acceptResolvedAddressesInternal(resolvedAddresses);
      if (!acceptRetVal.valid) {
        return false;
      }

      // Update the picker and
      updateOverallBalancingState();

      // shutdown removed children
      shutdownRemoved(acceptRetVal.removedChildren);
      return true;
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
        .setLoadBalancingPolicyConfig(childConfig)
        .build();
  }

  /**
   *   This does the work to update the child map and calculate which children have been removed.
   *   You must call {@link #updateOverallBalancingState} to update the picker
   *   and call {@link #shutdownRemoved(List)} to shutdown the endpoints that have been removed.
    */
  protected AcceptResolvedAddressRetVal acceptResolvedAddressesInternal(
      ResolvedAddresses resolvedAddresses) {
    logger.log(Level.FINE, "Received resolution result: {0}", resolvedAddresses);
    Map<Object, ChildLbState> newChildren = createChildLbMap(resolvedAddresses);

    if (newChildren.isEmpty()) {
      handleNameResolutionError(Status.UNAVAILABLE.withDescription(
          "NameResolver returned no usable address. " + resolvedAddresses));
      return new AcceptResolvedAddressRetVal(false, null);
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
        if (existingChildLbState.isDeactivated() && reactivateChildOnReuse()) {
          existingChildLbState.reactivate(childPolicyProvider);
        }
      }

      ChildLbState childLbState = childLbStates.get(key);
      ResolvedAddresses childAddresses = getChildAddresses(key, resolvedAddresses, childConfig);
      childLbStates.get(key).setResolvedAddresses(childAddresses); // update child
      if (!childLbState.deactivated) {
        childLbState.lb.handleResolvedAddresses(childAddresses); // update child LB
      }
    }

    List<ChildLbState> removedChildren = new ArrayList<>();
    // Do removals
    for (Object key : ImmutableList.copyOf(childLbStates.keySet())) {
      if (!newChildren.containsKey(key)) {
        ChildLbState childLbState = childLbStates.get(key);
        childLbState.deactivate();
        removedChildren.add(childLbState);
      }
    }

    return new AcceptResolvedAddressRetVal(true, removedChildren);
  }

  protected void shutdownRemoved(List<ChildLbState> removedChildren) {
    // Do shutdowns after updating picker to reduce the chance of failing an RPC by picking a
    // subchannel that has been shutdown.
    for (ChildLbState childLbState : removedChildren) {
      childLbState.shutdown();
    }
  }

  @Override
  public void handleNameResolutionError(Status error) {
    if (currentConnectivityState != READY)  {
      helper.updateBalancingState(TRANSIENT_FAILURE, getErrorPicker(error));
    }
  }

  protected void handleNameResolutionError(ChildLbState child, Status error) {
    child.lb.handleNameResolutionError(error);
  }

  /**
   * If true, then when a subchannel state changes to idle, the corresponding child will
   * have requestConnection called on its LB.  Also causes the PickFirstLB to be created when
   * the child is created or reused.
   */
  protected boolean reconnectOnIdle() {
    return true;
  }

  /**
   * If true, then when {@link #acceptResolvedAddresses} sees a key that was already part of the
   * child map which is deactivated, it will call reactivate on the child.
   * If false, it will leave it deactivated.
   */
  protected boolean reactivateChildOnReuse() {
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
    private final LoadBalancerProvider policyProvider;
    private ConnectivityState currentState;
    private SubchannelPicker currentPicker;
    private boolean deactivated;

    public ChildLbState(Object key, LoadBalancerProvider policyProvider, Object childConfig,
        SubchannelPicker initialPicker) {
      this(key, policyProvider, childConfig, initialPicker, null, false);
    }

    public ChildLbState(Object key, LoadBalancerProvider policyProvider, Object childConfig,
          SubchannelPicker initialPicker, ResolvedAddresses resolvedAddrs, boolean deactivated) {
      this.key = key;
      this.policyProvider = policyProvider;
      this.deactivated = deactivated;
      this.currentPicker = initialPicker;
      this.config = childConfig;
      this.lb = new GracefulSwitchLoadBalancer(new ChildLbStateHelper());
      this.currentState = deactivated ? IDLE : CONNECTING;
      this.resolvedAddresses = resolvedAddrs;
      if (!deactivated) {
        lb.switchTo(policyProvider);
      }
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

    protected GracefulSwitchLoadBalancer getLb() {
      return lb;
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

    protected void setDeactivated() {
      deactivated = true;
    }

    protected void markReactivated() {
      deactivated = false;
    }

    protected void setResolvedAddresses(ResolvedAddresses newAddresses) {
      checkNotNull(newAddresses, "Missing address list for child");
      resolvedAddresses = newAddresses;
    }

    /**
     * The default implementation. This not only marks the lb policy as not active, it also removes
     * this child from the map of children maintained by the petiole policy.
     *
     * <p>Note that this does not explicitly shutdown this child.  That will generally be done by
     * acceptResolvedAddresses on the LB, but can also be handled by an override such as is done
     * in <a href=" https://github.com/grpc/grpc-java/blob/master/xds/src/main/java/io/grpc/xds/ClusterManagerLoadBalancer.java">ClusterManagerLoadBalancer</a>.
     *
     * <p>If you plan to reactivate, you will probably want to override this to not call
     * childLbStates.remove() and handle that cleanup another way.
     */
    protected void deactivate() {
      if (deactivated) {
        return;
      }

      childLbStates.remove(key); // This means it can't be reactivated again
      deactivated = true;
      logger.log(Level.FINE, "Child balancer {0} deactivated", key);
    }

    /**
     * This base implementation does nothing but reset the flag.  If you really want to both
     * deactivate and reactivate you should override them both.
     */
    protected void reactivate(LoadBalancerProvider policyProvider) {
      deactivated = false;
    }

    protected void shutdown() {
      lb.shutdown();
      this.currentState = SHUTDOWN;
      logger.log(Level.FINE, "Child balancer {0} deleted", key);
    }

    /**
     * ChildLbStateHelper is the glue between ChildLbState and the helpers associated with the
     * petiole policy above and the PickFirstLoadBalancer's helper below.
     *
     * <p>The ChildLbState updates happen during updateBalancingState.  Otherwise, it is doing
     * simple forwarding.
     */
    protected ResolvedAddresses getResolvedAddresses() {
      return resolvedAddresses;
    }

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

    public Endpoint(EquivalentAddressGroup eag) {
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

  protected static class AcceptResolvedAddressRetVal {
    public final boolean valid;
    public final List<ChildLbState> removedChildren;

    public AcceptResolvedAddressRetVal(boolean valid, List<ChildLbState> removedChildren) {
      this.valid = valid;
      this.removedChildren = removedChildren;
    }
  }
}
