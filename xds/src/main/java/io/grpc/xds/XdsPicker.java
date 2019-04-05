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

final class XdsPicker<LocalityT> extends SubchannelPicker {

  private final List<LocalityT> localities;
  private final int[] accumulatedWeights;
  private final Map<LocalityT, SubchannelPicker> childPickers;
  private final Random random;
  private final int totalWeight;

  XdsPicker(
      List<LocalityT> localities,
      List<Integer> weights,
      Map<LocalityT, SubchannelPicker> childPickers) {
    this(localities, weights, childPickers, new Random());
  }

  @VisibleForTesting
  XdsPicker(
      List<LocalityT> localities,
      List<Integer> weights,
      Map<LocalityT, SubchannelPicker> childPickers,
      Random random) {
    checkArgument(
        !checkNotNull(localities, "localities in null").isEmpty(),
        "localities list is empty");
    checkArgument(
        checkNotNull(weights, "weights in null").size() == localities.size(),
        "localities and weights are of different sizes");

    this.localities = ImmutableList.copyOf(localities);


    accumulatedWeights = new int[weights.size()];
    int totalWeight = 0;
    for (int i = 0; i < weights.size(); i++) {
      checkArgument(weights.get(i) >= 0, "weights[%s] is negative", i);
      totalWeight += weights.get(i);
      accumulatedWeights[i] = totalWeight;
    }
    this.totalWeight = totalWeight;

    this.childPickers = ImmutableMap.copyOf(childPickers);
    for (LocalityT locality : localities) {
      checkArgument(
          childPickers.containsKey(locality),
          "childPickers does not contain picker for locality %s",
          locality);
    }

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
      for (int idx = 0; idx < accumulatedWeights.length; idx++) {
        if (rand < accumulatedWeights[idx]) {
          locality = localities.get(idx);
          break;
        }
      }
      checkNotNull(locality, "locality not found");
    }

    return childPickers.get(locality).pickSubchannel(args);
  }
}
