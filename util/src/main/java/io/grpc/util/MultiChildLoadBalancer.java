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
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
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
  private final Map<Object, ChildLbState> childLbStates = new HashMap<>();
  private final Helper helper;
  // Set to true if currently in the process of handling resolved addresses.
  @VisibleForTesting
  boolean resolvingAddresses;

  protected final PickFirstLoadBalancerProvider pickFirstLbProvider =
      new PickFirstLoadBalancerProvider();


  protected MultiChildLoadBalancer(Helper helper) {
    this.helper = checkNotNull(helper, "helper");
    logger.log(Level.FINE, "Created");
  }

  @SuppressWarnings("ReferenceEquality")
  protected static EquivalentAddressGroup stripAttrs(EquivalentAddressGroup eag) {
    if (eag.getAttributes() == Attributes.EMPTY) {
      return eag;
    } else {
      return new EquivalentAddressGroup(eag.getAddresses());
    }
  }

  protected abstract SubchannelPicker getSubchannelPicker(
      Map<Object, SubchannelPicker> childPickers);

  protected SubchannelPicker getInitialPicker() {
    return EMPTY_PICKER;
  }

  protected SubchannelPicker getErrorPicker(Status error)  {
    return new ErrorPicker(error);
  }

  @VisibleForTesting
  protected Collection<ChildLbState> getChildLbStates() {
    return childLbStates.values();
  }

  protected ChildLbState getChildLbState(Object key) {
    if (key == null) {
      return null;
    }
    return childLbStates.get(key);
  }

  protected ChildLbState getChildLbStateEag(EquivalentAddressGroup eag) {
    return getChildLbState(stripAttrs(eag));
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
      if (!childLbMap.containsKey(strippedEag)) {
        childLbMap.put(strippedEag,
            createChildLbState(strippedEag, policyConfig, getInitialPicker()));
      }
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
      childLb.handleResolvedAddresses(getChildAddresses(key, resolvedAddresses, childConfig));
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
    logger.log(Level.WARNING, "Received name resolution error: {0}", error);
    boolean gotoTransientFailure = true;
    for (ChildLbState state : childLbStates.values()) {
      if (!state.deactivated) {
        gotoTransientFailure = false;
        state.lb.handleNameResolutionError(error);
      }
    }
    if (gotoTransientFailure) {
      helper.updateBalancingState(TRANSIENT_FAILURE, getErrorPicker(error));
    }
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


  public class ChildLbState {
    private final Object key;
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
      return getCurrentPicker().pickSubchannel(args).getSubchannel();
    }

    ConnectivityState getCurrentState() {
      return currentState;
    }

    public SubchannelPicker getCurrentPicker() {
      return currentPicker;
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
          updateOverallBalancingState();
        }
      }

      @Override
      protected Helper delegate() {
        return helper;
      }
    }
  }
}
