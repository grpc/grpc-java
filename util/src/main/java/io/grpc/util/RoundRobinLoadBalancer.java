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
import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import io.grpc.ConnectivityState;
import io.grpc.EquivalentAddressGroup;
import io.grpc.Internal;
import io.grpc.LoadBalancer;
import io.grpc.NameResolver;
import io.grpc.Status;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import javax.annotation.Nonnull;

/**
 * A {@link LoadBalancer} that provides round-robin load-balancing over the {@link
 * EquivalentAddressGroup}s from the {@link NameResolver}.
 */
@Internal
public class RoundRobinLoadBalancer extends MultiChildLoadBalancer {
  private final Random random;
  protected RoundRobinPicker currentPicker = new EmptyPicker(EMPTY_OK);

  public RoundRobinLoadBalancer(Helper helper) {
    super(helper);
    this.random = new Random();
  }

  @Override
  protected SubchannelPicker getSubchannelPicker(Map<Object, SubchannelPicker> childPickers) {
    throw new UnsupportedOperationException(); // local updateOverallBalancingState doesn't use this
  }

  private static final Status EMPTY_OK = Status.OK.withDescription("no subchannels ready");

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
        updateBalancingState(CONNECTING,  new EmptyPicker(Status.OK));
      } else {
        updateBalancingState(TRANSIENT_FAILURE, createReadyPicker(getChildLbStates()));
      }
    } else {
      updateBalancingState(READY, createReadyPicker(activeList));
    }
  }

  private void updateBalancingState(ConnectivityState state, RoundRobinPicker picker) {
    if (state != currentConnectivityState || !picker.isEquivalentTo(currentPicker)) {
      getHelper().updateBalancingState(state, picker);
      currentConnectivityState = state;
      currentPicker = picker;
    }
  }

  protected RoundRobinPicker createReadyPicker(Collection<ChildLbState> children) {
    // initialize the Picker to a random start index to ensure that a high frequency of Picker
    // churn does not skew subchannel selection.
    int startIndex = random.nextInt(children.size());

    List<SubchannelPicker> pickerList = new ArrayList<>();
    for (ChildLbState child : children) {
      SubchannelPicker picker = child.getCurrentPicker();
      pickerList.add(picker);
    }

    return new ReadyPicker(pickerList, startIndex);
  }

  public abstract static class RoundRobinPicker extends SubchannelPicker {
    public abstract boolean isEquivalentTo(RoundRobinPicker picker);
  }

  @VisibleForTesting
  static class ReadyPicker extends RoundRobinPicker {
    private static final AtomicIntegerFieldUpdater<ReadyPicker> indexUpdater =
        AtomicIntegerFieldUpdater.newUpdater(ReadyPicker.class, "index");

    private final List<SubchannelPicker> subchannelPickers; // non-empty
    @SuppressWarnings("unused")
    private volatile int index;

    public ReadyPicker(List<SubchannelPicker> list, int startIndex) {
      checkArgument(!list.isEmpty(), "empty list");
      this.subchannelPickers = list;
      this.index = startIndex - 1;
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
      int size = subchannelPickers.size();
      int i = indexUpdater.incrementAndGet(this);
      if (i >= size) {
        int oldi = i;
        i %= size;
        indexUpdater.compareAndSet(this, oldi, i);
      }
      return i;
    }

    @VisibleForTesting
    List<SubchannelPicker> getSubchannelPickers() {
      return subchannelPickers;
    }

    @Override
    public boolean isEquivalentTo(RoundRobinPicker picker) {
      if (!(picker instanceof ReadyPicker)) {
        return false;
      }
      ReadyPicker other = (ReadyPicker) picker;
      // the lists cannot contain duplicate subchannels
      return other == this
          || (subchannelPickers.size() == other.subchannelPickers.size() && new HashSet<>(
          subchannelPickers).containsAll(other.subchannelPickers));
    }
  }

  @VisibleForTesting
  static final class EmptyPicker extends RoundRobinPicker {

    private final Status status;

    EmptyPicker(@Nonnull Status status) {
      this.status = Preconditions.checkNotNull(status, "status");
    }

    @Override
    public PickResult pickSubchannel(PickSubchannelArgs args) {
      return status.isOk() ? PickResult.withNoResult() : PickResult.withError(status);
    }

    @Override
    public boolean isEquivalentTo(RoundRobinPicker picker) {
      return picker instanceof EmptyPicker && (Objects.equal(status, ((EmptyPicker) picker).status)
          || (status.isOk() && ((EmptyPicker) picker).status.isOk()));
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(EmptyPicker.class).add("status", status).toString();
    }
  }

  /**
   * A lighter weight Reference than AtomicReference.
   */
  @VisibleForTesting
  static final class Ref<T> {
    T value;

    Ref(T value) {
      this.value = value;
    }
  }
}
