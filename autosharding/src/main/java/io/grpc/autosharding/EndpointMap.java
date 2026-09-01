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

import static io.grpc.ConnectivityState.IDLE;

import io.grpc.Attributes;
import io.grpc.ConnectivityState;
import io.grpc.EquivalentAddressGroup;
import io.grpc.LoadBalancer.FixedResultPicker;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancer.PickResult;
import io.grpc.LoadBalancer.ResolvedAddresses;
import io.grpc.LoadBalancer.SubchannelPicker;
import io.grpc.LoadBalancerProvider;
import io.grpc.util.ForwardingLoadBalancerHelper;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Manages the mapping from endpoint hostname to {@link EndpointHolder} and their lifecycle.
 */
final class EndpointMap {
  private final Map<String, EndpointHolder> map = new LinkedHashMap<>();

  EndpointHolder get(String hostname) {
    return map.get(hostname);
  }

  void put(String hostname, EndpointHolder holder) {
    map.put(hostname, holder);
  }

  EndpointHolder remove(String hostname) {
    return map.remove(hostname);
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

  void shutdownAll() {
    for (EndpointHolder holder : map.values()) {
      holder.shutdown();
    }
    map.clear();
  }

  void reindex() {
    int nextIdx = 0;
    for (EndpointHolder holder : map.values()) {
      holder.index = nextIdx++;
    }
  }

  /**
   * Holds the state and lazy child load balancer for a single endpoint.
   */
  static final class EndpointHolder {
    int index;
    final LazyChildLoadBalancer childLb;
    ConnectivityState state = IDLE;
    SubchannelPicker picker = new FixedResultPicker(PickResult.withNoResult());

    EndpointHolder(
        int index,
        Helper helper,
        LoadBalancerProvider pickFirstProvider,
        Runnable stateUpdateCallback) {
      this.index = index;
      this.childLb = new LazyChildLoadBalancer(
          new ChildHelper(helper, stateUpdateCallback), pickFirstProvider);
    }

    void updateAddresses(List<EquivalentAddressGroup> eags, Attributes attributes) {
      ResolvedAddresses childAddresses = ResolvedAddresses.newBuilder()
          .setAddresses(eags)
          .setAttributes(attributes)
          .build();
      childLb.acceptResolvedAddresses(childAddresses);
    }

    void requestConnection() {
      childLb.requestConnection();
    }

    void shutdown() {
      childLb.shutdown();
    }

    private final class ChildHelper extends ForwardingLoadBalancerHelper {
      private final Helper delegateHelper;
      private final Runnable stateUpdateCallback;

      ChildHelper(Helper delegateHelper, Runnable stateUpdateCallback) {
        this.delegateHelper = delegateHelper;
        this.stateUpdateCallback = stateUpdateCallback;
      }

      @Override
      protected Helper delegate() {
        return delegateHelper;
      }

      @Override
      public void updateBalancingState(ConnectivityState newState, SubchannelPicker newPicker) {
        state = newState;
        picker = newPicker;
        if (stateUpdateCallback != null) {
          stateUpdateCallback.run();
        }
      }
    }
  }
}
