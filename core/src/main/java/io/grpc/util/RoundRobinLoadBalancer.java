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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;
import io.grpc.EquivalentAddressGroup;
import io.grpc.LoadBalancer;
import io.grpc.NameResolver;
import io.grpc.Status;
import io.grpc.util.SubchannelListLoadBalancerCommons.RoundRobinPicker;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

/**
 * A {@link SubchannelListLoadBalancerCommons} that provides round-robin load-balancing over the
 * {@link EquivalentAddressGroup}s from the {@link NameResolver}.
 */
final class RoundRobinLoadBalancer extends LoadBalancer {

  private final SubchannelListLoadBalancerCommons roundRobinCommons;
  private final Random random;

  RoundRobinLoadBalancer(Helper helper) {
    this.roundRobinCommons = new SubchannelListLoadBalancerCommons(helper, () -> { },
            this::createReadyPicker);
    this.random = new Random();
  }

  private RoundRobinPicker createReadyPicker(List<Subchannel> activeSubchannelList) {
    // initialize the Picker to a random start index to ensure that a high frequency of Picker
    // churn does not skew subchannel selection.
    return new ReadyPicker(activeSubchannelList, random.nextInt(activeSubchannelList.size()));
  }

  @Override
  public boolean acceptResolvedAddresses(ResolvedAddresses resolvedAddresses) {
    return roundRobinCommons.acceptResolvedAddresses(resolvedAddresses);
  }

  @Override
  public void handleNameResolutionError(Status error) {
    roundRobinCommons.handleNameResolutionError(error);
  }

  @Override
  public void shutdown() {
    roundRobinCommons.shutdown();
  }

  @VisibleForTesting
  Collection<Subchannel> getSubchannels() {
    return roundRobinCommons.getSubchannels();
  }

  @VisibleForTesting
  static final class ReadyPicker extends RoundRobinPicker {
    private static final AtomicIntegerFieldUpdater<ReadyPicker> indexUpdater =
        AtomicIntegerFieldUpdater.newUpdater(ReadyPicker.class, "index");

    private final List<Subchannel> list; // non-empty
    @SuppressWarnings("unused")
    private volatile int index;

    ReadyPicker(List<Subchannel> list, int startIndex) {
      Preconditions.checkArgument(!list.isEmpty(), "empty list");
      this.list = list;
      this.index = startIndex - 1;
    }

    @Override
    public PickResult pickSubchannel(PickSubchannelArgs args) {
      return PickResult.withSubchannel(nextSubchannel());
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(ReadyPicker.class).add("list", list).toString();
    }

    private Subchannel nextSubchannel() {
      int size = list.size();
      int i = indexUpdater.incrementAndGet(this);
      if (i >= size) {
        int oldi = i;
        i %= size;
        indexUpdater.compareAndSet(this, oldi, i);
      }
      return list.get(i);
    }

    @VisibleForTesting
    List<Subchannel> getList() {
      return list;
    }

    @Override
    public boolean isEquivalentTo(RoundRobinPicker picker) {
      if (!(picker instanceof ReadyPicker)) {
        return false;
      }
      ReadyPicker other = (ReadyPicker) picker;
      // the lists cannot contain duplicate subchannels
      return other == this
          || (list.size() == other.list.size() && new HashSet<>(list).containsAll(other.list));
    }
  }
}
