/*
 * Copyright 2016 The gRPC Authors
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
import static io.grpc.ConnectivityState.CONNECTING;
import static io.grpc.ConnectivityState.IDLE;
import static io.grpc.ConnectivityState.READY;
import static io.grpc.ConnectivityState.TRANSIENT_FAILURE;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;
import io.grpc.ConnectivityState;
import io.grpc.EquivalentAddressGroup;
import io.grpc.LoadBalancer;
import io.grpc.NameResolver;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A {@link LoadBalancer} that provides round-robin load-balancing over the {@link
 * EquivalentAddressGroup}s from the {@link NameResolver}.
 */
final class RoundRobinLoadBalancer extends MultiChildLoadBalancer {
  private final AtomicInteger sequence = new AtomicInteger(new Random().nextInt());
  private SubchannelPicker currentPicker = new FixedResultPicker(PickResult.withNoResult());

  public RoundRobinLoadBalancer(Helper helper) {
    super(helper);
  }

  /**
   * Updates picker with the list of active subchannels (state == READY).
   */
  @Override
  protected void updateOverallBalancingState() {
    List<ChildLbState> activeList = getReadyChildren();
    if (activeList.isEmpty()) {
      // No READY subchannels

      // RRLB will request connection immediately on subchannel IDLE.
      boolean isConnecting = false;
      for (ChildLbState childLbState : getChildLbStates()) {
        ConnectivityState state = childLbState.getCurrentState();
        if (state == CONNECTING || state == IDLE) {
          isConnecting = true;
          break;
        }
      }

      if (isConnecting) {
        updateBalancingState(CONNECTING, new FixedResultPicker(PickResult.withNoResult()));
      } else {
        updateBalancingState(TRANSIENT_FAILURE, createReadyPicker(getChildLbStates()));
      }
    } else {
      updateBalancingState(READY, createReadyPicker(activeList));
    }
  }

  private void updateBalancingState(ConnectivityState state, SubchannelPicker picker) {
    if (state != currentConnectivityState || !picker.equals(currentPicker)) {
      getHelper().updateBalancingState(state, picker);
      currentConnectivityState = state;
      currentPicker = picker;
    }
  }

  private SubchannelPicker createReadyPicker(Collection<ChildLbState> children) {
    List<SubchannelPicker> pickerList = new ArrayList<>();
    for (ChildLbState child : children) {
      SubchannelPicker picker = child.getCurrentPicker();
      pickerList.add(picker);
    }

    return new ReadyPicker(pickerList, sequence);
  }

  @Override
  protected ChildLbState createChildLbState(Object key) {
    return new ChildLbState(key, pickFirstLbProvider) {
      @Override
      protected ChildLbStateHelper createChildHelper() {
        return new ChildLbStateHelper() {
          @Override
          public void updateBalancingState(ConnectivityState newState, SubchannelPicker newPicker) {
            super.updateBalancingState(newState, newPicker);
            if (!resolvingAddresses && newState == IDLE) {
              getLb().requestConnection();
            }
          }
        };
      }
    };
  }

  @VisibleForTesting
  static class ReadyPicker extends SubchannelPicker {
    private final List<SubchannelPicker> subchannelPickers; // non-empty
    private final AtomicInteger index;
    private final int hashCode;

    public ReadyPicker(List<SubchannelPicker> list, AtomicInteger index) {
      checkArgument(!list.isEmpty(), "empty list");
      this.subchannelPickers = list;
      this.index = Preconditions.checkNotNull(index, "index");

      // Every created picker is checked for equality in updateBalancingState() at least once.
      // Pre-compute the hash so it can be checked cheaply. Using the hash in equals() makes it very
      // fast except when the pickers are (very likely) equal.
      //
      // For equality we treat children as a set; use hash code as defined by Set
      int sum = 0;
      for (SubchannelPicker picker : subchannelPickers) {
        sum += picker.hashCode();
      }
      this.hashCode = sum;
    }

    @Override
    public PickResult pickSubchannel(PickSubchannelArgs args) {
      return subchannelPickers.get(nextIndex()).pickSubchannel(args);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(ReadyPicker.class)
          .add("subchannelPickers", subchannelPickers)
          .toString();
    }

    private int nextIndex() {
      int i = index.getAndIncrement() & Integer.MAX_VALUE;
      return i % subchannelPickers.size();
    }

    @VisibleForTesting
    List<SubchannelPicker> getSubchannelPickers() {
      return subchannelPickers;
    }

    @Override
    public int hashCode() {
      return hashCode;
    }

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof ReadyPicker)) {
        return false;
      }
      ReadyPicker other = (ReadyPicker) o;
      if (other == this) {
        return true;
      }
      // the lists cannot contain duplicate subchannels
      return hashCode == other.hashCode
          && index == other.index
          && subchannelPickers.size() == other.subchannelPickers.size()
          && new HashSet<>(subchannelPickers).containsAll(other.subchannelPickers);
    }
  }
}
