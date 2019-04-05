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
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.grpc.LoadBalancer.PickResult;
import io.grpc.LoadBalancer.PickSubchannelArgs;
import io.grpc.LoadBalancer.SubchannelPicker;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

final class InterLocalityPicker<LocalityT> extends SubchannelPicker {

  private final List<LocalityT> localities;
  private final List<Integer> weights;
  private final Map<LocalityT, SubchannelPicker> childPickers;
  private final Random random;
  private final int totalWeight;

  InterLocalityPicker(
      List<LocalityT> localities,
      List<Integer> weights,
      Map<LocalityT, SubchannelPicker> childPickers) {
    this(localities, weights, childPickers, ThreadSafeRadom.instance);
  }

  private static final class ThreadSafeRadom extends Random {
    static final long serialVersionUID = 145234;
    static final Random instance = new ThreadSafeRadom();

    @Override
    public int nextInt(int bound) {
      return ThreadLocalRandom.current().nextInt();
    }
  }

  @VisibleForTesting
  InterLocalityPicker(
      List<LocalityT> localities,
      List<Integer> weights,
      Map<LocalityT, SubchannelPicker> childPickers,
      Random random) {

    checkNotNull(localities, "localities in null");
    checkArgument(
        !localities.isEmpty(),
        "localities list is empty");
    this.localities = ImmutableList.copyOf(localities);

    checkNotNull(weights, "weights in null");
    checkArgument(
        weights.size() == localities.size(),
        "localities and weights are of different sizes");
    int totalWeight = 0;
    for (int i = 0; i < weights.size(); i++) {
      checkArgument(weights.get(i) >= 0, "weights[%s] is negative", i);
      totalWeight += weights.get(i);
    }
    this.totalWeight = totalWeight;
    this.weights = ImmutableList.copyOf(weights);

    checkNotNull(childPickers, "childPickers is null");
    for (LocalityT locality : localities) {
      checkArgument(
          childPickers.containsKey(locality),
          "childPickers does not contain picker for locality %s",
          locality);
    }
    this.childPickers = ImmutableMap.copyOf(childPickers);

    this.random = random;
  }

  @Override
  public final PickResult pickSubchannel(PickSubchannelArgs args) {
    LocalityT locality = null;

    if (totalWeight == 0) {
      locality = localities.get(random.nextInt(localities.size()));
    } else {
      int rand = random.nextInt(totalWeight);

      // Find the first idx such that rand < accumulatedWeights[idx]
      // Not using Arrays.binarySearch for better readability.
      int accumulatedWeight = 0;
      for (int idx = 0; idx < weights.size(); idx++) {
        accumulatedWeight += weights.get(idx);
        if (rand < accumulatedWeight) {
          locality = localities.get(idx);
          break;
        }
      }
      checkNotNull(locality, "locality not found");
    }

    return childPickers.get(locality).pickSubchannel(args);
  }
}
