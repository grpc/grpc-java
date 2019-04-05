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
import com.google.common.base.Objects;
import io.grpc.ConnectivityState;
import io.grpc.LoadBalancer.PickResult;
import io.grpc.LoadBalancer.PickSubchannelArgs;
import io.grpc.LoadBalancer.SubchannelPicker;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

class XdsPicker extends SubchannelPicker {

  private static final WrrAlgorithm wrrAlgo = new WrrAlgorithmImpl();

  private final List<LocalityState> wrrList;
  private final WrrAlgorithm wrrAlgorithm;
  private final Map<Locality, SubchannelPicker> childPickers;

  XdsPicker(List<LocalityState> wrrList, Map<Locality, SubchannelPicker> childPickers) {
    this(wrrList, childPickers, wrrAlgo);
  }

  @VisibleForTesting
  XdsPicker(
      List<LocalityState> wrrList,
      Map<Locality, SubchannelPicker> childPickers,
      WrrAlgorithm wrrAlgorithm) {
    checkArgument(!checkNotNull(wrrList, "wrrList in null").isEmpty(), "wrrList is empty");
    for (LocalityState localityState : wrrList) {
      checkArgument(
          childPickers.containsKey(localityState.locality),
          "childPickers does not contain picker for locality %s",
          localityState.locality);
    }
    this.wrrList = wrrList;
    this.childPickers = childPickers;
    this.wrrAlgorithm = wrrAlgorithm;
  }

  @Override
  public PickResult pickSubchannel(PickSubchannelArgs args) {
    Locality locality = wrrAlgorithm.pickLocality(wrrList);
    return childPickers.get(locality).pickSubchannel(args);
  }

  @VisibleForTesting // Interface of weighted Round-Robin algorithm that is convenient for test.
  interface WrrAlgorithm {
    Locality pickLocality(List<LocalityState> wrrList);
  }

  private static final class WrrAlgorithmImpl implements WrrAlgorithm {

    @Override
    public Locality pickLocality(List<LocalityState> wrrList) {
      // TODO: impl
      return wrrList.iterator().next().locality;
    }
  }

  @Immutable
  static final class Locality {
    final String region;
    final String zone;
    final String subzone;

    Locality(String region, String zone, String subzone) {
      this.region = region;
      this.zone = zone;
      this.subzone = subzone;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      Locality locality = (Locality) o;
      return Objects.equal(region, locality.region)
          && Objects.equal(zone, locality.zone)
          && Objects.equal(subzone, locality.subzone);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(region, zone, subzone);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("region", region)
          .add("zone", zone)
          .add("subzone", subzone)
          .toString();
    }
  }

  /**
   * State about the locality for WRR locality picker.
   */
  @Immutable
  static final class LocalityState {
    final Locality locality;
    final int weight;
    @Nullable // null means the subchannel state is not updated at the moment yet
    @SuppressWarnings("unused") // TODO: make use of it for WrrAlgorithmImpl
    final ConnectivityState state;

    LocalityState(Locality locality, int weight, @Nullable ConnectivityState state) {
      this.locality = locality;
      this.weight = weight;
      this.state = state;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      LocalityState that = (LocalityState) o;
      return weight == that.weight
          && Objects.equal(locality, that.locality)
          && state == that.state;
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(locality, weight, state);
    }
  }
}
