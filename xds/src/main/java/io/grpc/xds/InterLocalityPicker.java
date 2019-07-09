/*
 * Copyright 2019 The gRPC Authors
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

package io.grpc.xds;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import io.grpc.LoadBalancer.PickResult;
import io.grpc.LoadBalancer.PickSubchannelArgs;
import io.grpc.LoadBalancer.SubchannelPicker;
import java.util.List;

final class InterLocalityPicker extends SubchannelPicker {

  private final List<IntraLocalityPicker> intraLocalityPickers;
  private final ThreadSafeRandom random;
  private final int totalWeight;

  static final class IntraLocalityPicker extends SubchannelPicker {
    private final XdsLocality locality;
    private final int weight;
    private final SubchannelPicker delegate;

    IntraLocalityPicker(XdsLocality locality, int weight, SubchannelPicker delegate) {
      checkNotNull(locality, "locality");
      checkArgument(weight >= 0, "weight is negative");
      checkNotNull(delegate, "delegate");

      this.locality = locality;
      this.weight = weight;
      this.delegate = delegate;
    }

    XdsLocality getLocality() {
      return locality;
    }

    int getWeight() {
      return weight;
    }

    @Override
    public PickResult pickSubchannel(PickSubchannelArgs args) {
      return delegate.pickSubchannel(args);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("locality", locality)
          .add("weight", weight)
          .add("delegate", delegate)
          .toString();
    }
  }

  InterLocalityPicker(List<IntraLocalityPicker> intraLocalityPickers) {
    this(intraLocalityPickers, ThreadSafeRandom.ThreadSafeRandomImpl.instance);
  }

  @VisibleForTesting
  InterLocalityPicker(List<IntraLocalityPicker> intraLocalityPickers, ThreadSafeRandom random) {
    checkNotNull(intraLocalityPickers, "intraLocalityPickers");
    checkArgument(!intraLocalityPickers.isEmpty(), "intraLocalityPickers is empty");

    this.intraLocalityPickers = ImmutableList.copyOf(intraLocalityPickers);

    int totalWeight = 0;
    for (IntraLocalityPicker picker : intraLocalityPickers) {
      int weight = picker.getWeight();
      totalWeight += weight;
    }
    this.totalWeight = totalWeight;

    this.random = random;
  }

  @Override
  public final PickResult pickSubchannel(PickSubchannelArgs args) {
    if (totalWeight == 0) {
      return
          intraLocalityPickers.get(random.nextInt(intraLocalityPickers.size()))
              .pickSubchannel(args);
    }
    int rand = random.nextInt(totalWeight);

    // Find the first idx such that rand < accumulatedWeights[idx]
    // Not using Arrays.binarySearch for better readability.
    int accumulatedWeight = 0;
    int idx = 0;
    for (; idx < intraLocalityPickers.size(); idx++) {
      accumulatedWeight += intraLocalityPickers.get(idx).getWeight();
      if (rand < accumulatedWeight) {
        break;
      }
    }
    return intraLocalityPickers.get(idx).pickSubchannel(args);
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
        .add("intraLocalityPickers", intraLocalityPickers)
        .add("totalWeight", totalWeight)
        .toString();
  }
}
