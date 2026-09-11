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
import static com.google.common.base.Preconditions.checkState;
import static io.grpc.ConnectivityState.IDLE;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import io.grpc.Attributes;
import io.grpc.ConnectivityState;
import io.grpc.EquivalentAddressGroup;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancer.FixedResultPicker;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancer.PickResult;
import io.grpc.LoadBalancer.ResolvedAddresses;
import io.grpc.LoadBalancer.SubchannelPicker;
import io.grpc.util.ForwardingLoadBalancerHelper;
import io.grpc.util.LazyLoadBalancer;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;

/**
 * Manages the mapping from endpoint hostname to {@link EndpointHolder} and coordinates
 * child load balancer lifecycle and connectivity state updates.
 *
 * <p>Threading model: This class is not thread-safe. All methods must be invoked from the
 * {@link io.grpc.SynchronizationContext} by the parent load balancer.
 */
@NotThreadSafe
final class EndpointMap {
  private final Map<String, EndpointHolder> map = new LinkedHashMap<>();

  @Nullable
  EndpointHolder get(String hostname) {
    return map.get(checkNotNull(hostname, "hostname"));
  }

  void put(String hostname, EndpointHolder holder) {
    map.put(checkNotNull(hostname, "hostname"), checkNotNull(holder, "holder"));
  }

  @Nullable
  EndpointHolder remove(String hostname) {
    return map.remove(checkNotNull(hostname, "hostname"));
  }

  Collection<EndpointHolder> values() {
    return map.values();
  }

  Set<String> keySet() {
    return map.keySet();
  }

  int size() {
    return map.size();
  }

  boolean isEmpty() {
    return map.isEmpty();
  }

  void clear() {
    map.clear();
  }

  /**
   * Re-assigns contiguous 0-based index values across all current endpoint holders.
   */
  void reindex() {
    int nextIdx = 0;
    for (EndpointHolder holder : map.values()) {
      holder.setIndex(nextIdx++);
    }
  }

  /**
   * Shuts down all child load balancers and clears the map.
   */
  void shutdownAll() {
    for (EndpointHolder holder : map.values()) {
      holder.shutdown();
    }
    map.clear();
  }

  /**
   * Builds an immutable snapshot list of {@link PickerEndpoint}s placed strictly at their
   * corresponding {@link EndpointHolder#getIndex()} positions.
   *
   * @throws IllegalStateException if endpoint indices are not contiguous from 0 to N-1
   */
  ImmutableList<PickerEndpoint> toPickerEndpoints() {
    int size = map.size();
    if (size == 0) {
      return ImmutableList.of();
    }
    PickerEndpoint[] array = new PickerEndpoint[size];
    for (EndpointHolder holder : map.values()) {
      int idx = holder.getIndex();
      checkState(
          idx >= 0 && idx < size,
          "Endpoint holder index %s is out of bounds for size %s",
          idx,
          size);
      checkState(
          array[idx] == null,
          "Duplicate endpoint holder index %s detected",
          idx);
      array[idx] = holder.toPickerEndpoint();
    }
    return ImmutableList.copyOf(array);
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
        .add("map", map)
        .toString();
  }

  /**
   * Holds the connectivity state, picker, and lazy child load balancer for a single endpoint.
   */
  static final class EndpointHolder {
    private int index;
    private final LazyLoadBalancer childLb;
    private final AtomicBoolean connectingScheduled = new AtomicBoolean(false);
    private final Helper helper;
    private ConnectivityState state = IDLE;
    private SubchannelPicker picker = new FixedResultPicker(PickResult.withNoResult());

    EndpointHolder(
        int index,
        Helper helper,
        LoadBalancer.Factory pickFirstFactory,
        @Nullable Runnable stateUpdateCallback) {
      this.index = index;
      this.helper = checkNotNull(helper, "helper");
      this.childLb = new LazyLoadBalancer(
          new ChildHelper(helper, stateUpdateCallback),
          checkNotNull(pickFirstFactory, "pickFirstFactory"));
    }

    int getIndex() {
      return index;
    }

    void setIndex(int index) {
      this.index = index;
    }

    ConnectivityState getState() {
      return state;
    }

    SubchannelPicker getPicker() {
      return picker;
    }

    LazyLoadBalancer getChildLb() {
      return childLb;
    }

    PickerEndpoint toPickerEndpoint() {
      return new PickerEndpoint(state, picker, this::exitIdle);
    }

    private void exitIdle() {
      if (connectingScheduled.compareAndSet(false, true)) {
        helper.getSynchronizationContext().execute(() -> {
          connectingScheduled.set(false);
          childLb.requestConnection();
        });
      }
    }

    void updateAddresses(List<EquivalentAddressGroup> eags, Attributes attributes) {
      ResolvedAddresses childAddresses = ResolvedAddresses.newBuilder()
          .setAddresses(ImmutableList.copyOf(checkNotNull(eags, "eags")))
          .setAttributes(checkNotNull(attributes, "attributes"))
          .build();
      childLb.acceptResolvedAddresses(childAddresses);
    }

    void requestConnection() {
      childLb.requestConnection();
    }

    void shutdown() {
      childLb.shutdown();
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("index", index)
          .add("state", state)
          .add("childLb", childLb)
          .toString();
    }

    private final class ChildHelper extends ForwardingLoadBalancerHelper {
      private final Helper delegateHelper;
      @Nullable private final Runnable stateUpdateCallback;

      ChildHelper(Helper delegateHelper, @Nullable Runnable stateUpdateCallback) {
        this.delegateHelper = checkNotNull(delegateHelper, "delegateHelper");
        this.stateUpdateCallback = stateUpdateCallback;
      }

      @Override
      protected Helper delegate() {
        return delegateHelper;
      }

      @Override
      public void updateBalancingState(ConnectivityState newState, SubchannelPicker newPicker) {
        state = checkNotNull(newState, "newState");
        picker = checkNotNull(newPicker, "newPicker");
        if (stateUpdateCallback != null) {
          stateUpdateCallback.run();
        }
      }

      @Override
      public String toString() {
        return MoreObjects.toStringHelper(this)
            .add("delegateHelper", delegateHelper)
            .toString();
      }
    }
  }
}
