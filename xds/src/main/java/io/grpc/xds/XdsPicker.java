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

import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.grpc.ConnectivityState;
import io.grpc.LoadBalancer.PickResult;
import io.grpc.LoadBalancer.PickSubchannelArgs;
import io.grpc.LoadBalancer.SubchannelPicker;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

abstract class XdsPicker extends SubchannelPicker {

  protected final List<LocalityState> wrrList;
  private final Map<Locality, SubchannelPicker> childPickers;

  abstract Locality pickLocality();

  XdsPicker(List<LocalityState> wrrList, Map<Locality, SubchannelPicker> childPickers) {
    checkArgument(!checkNotNull(wrrList, "wrrList in null").isEmpty(), "wrrList is empty");
    for (LocalityState localityState : wrrList) {
      checkArgument(
          childPickers.containsKey(localityState.locality),
          "childPickers does not contain picker for locality %s",
          localityState.locality);
    }
    this.wrrList = ImmutableList.copyOf(wrrList);
    this.childPickers = ImmutableMap.copyOf(childPickers);
  }

  @Override
  public final PickResult pickSubchannel(PickSubchannelArgs args) {
    Locality locality = pickLocality();
    return childPickers.get(locality).pickSubchannel(args);
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
