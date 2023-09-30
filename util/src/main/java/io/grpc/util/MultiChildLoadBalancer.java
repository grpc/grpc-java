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

  protected abstract SubchannelPicker getSubchannelPicker(
      Map<Object, SubchannelPicker> childPickers);

  protected SubchannelPicker getInitialPicker() {
    return EMPTY_PICKER;
  }

  protected SubchannelPicker getErrorPicker(Status error)  {
    return new FixedResultPicker(PickResult.withError(error));
  }

  @VisibleForTesting
  protected Collection<ChildLbState> getChildLbStates() {
    return childLbStates.values();
  }

  protected ChildLbState getChildLbState(Object key) {
    if (key == null) {
      return null;
    }
    if (key instanceof EquivalentAddressGroup) {
      key = new Endpoint((EquivalentAddressGroup) key);
    }
    return childLbStates.get(key);
  }

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
      ChildLbState childLbState = childLbMap.getOrDefault(endpoint,
          createChildLbState(endpoint, null, getInitialPicker()));
      childLbMap.put(endpoint, childLbState);
    }
    return childLbMap;
  }

  protected ChildLbState createChildLbState(Object key, Object policyConfig,
      SubchannelPicker initialPicker) {
    return new ChildLbState(key, pickFirstLbProvider, policyConfig, initialPicker);
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

  /**
   * Override this if your keys are not of type EquivalentAddressGroup.
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
      if (key.equals(currEag)) {
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

  private boolean acceptResolvedAddressesInternal(ResolvedAddresses resolvedAddresses) {
    logger.log(Level.FINE, "Received resolution result: {0}", resolvedAddresses);
    Map<Object, ChildLbState> newChildren = createChildLbMap(resolvedAddresses);

    if (newChildren.isEmpty()) {
      handleNameResolutionError(Status.UNAVAILABLE.withDescription(
          "NameResolver returned no usable address. " + resolvedAddresses));
      return false;
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
    return true;
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
      }

      deactivated = false;
    }

    protected void shutdown() {
      lb.shutdown();
      this.currentState = SHUTDOWN;
      logger.log(Level.FINE, "Child balancer {0} deleted", key);
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

      int hash = 1;
      for (String address : addrs) {
        hash = 31 * hash + address.hashCode();
      }
      hashCode = hash;
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

      // Handling eags is convenient for tests
      if (other instanceof EquivalentAddressGroup) {
        other = new Endpoint((EquivalentAddressGroup) other);
      } else if (!(other instanceof Endpoint)) {
        return false;
      }
      Endpoint o = (Endpoint) other;
      if (o.hashCode != hashCode || o.addrs.length != addrs.length) {
        return false;
      }

      for (int i = 0; i < addrs.length; i++) {
        if (!addrs[i].equals(o.addrs[i])) {
          return false;
        }
      }

      return true;
    }

    @Override
    public String toString() {
      return Arrays.toString(addrs);
    }
  }
}
