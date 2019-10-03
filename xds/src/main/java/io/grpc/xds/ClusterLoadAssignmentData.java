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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;
import io.envoyproxy.envoy.api.v2.core.SocketAddress;
import io.grpc.EquivalentAddressGroup;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Contains data types for ClusterLoadAssignment.
 */
final class ClusterLoadAssignmentData {

  /**
   * An {@code XdsLocality} object is simply a POJO representation for {@link
   * io.envoyproxy.envoy.api.v2.core.Locality}, with only details needed for {@link
   * XdsLoadBalancer2}.
   */
  static final class XdsLocality {
    private final String region;
    private final String zone;
    private final String subzone;

    /** Must only be used for testing. */
    @VisibleForTesting
    XdsLocality(String region, String zone, String subzone) {
      this.region = region;
      this.zone = zone;
      this.subzone = subzone;
    }

    static XdsLocality fromLocalityProto(io.envoyproxy.envoy.api.v2.core.Locality locality) {
      return new XdsLocality(
          /* region = */ locality.getRegion(),
          /* zone = */ locality.getZone(),
          /* subzone = */ locality.getSubZone());
    }

    io.envoyproxy.envoy.api.v2.core.Locality toLocalityProto() {
      return io.envoyproxy.envoy.api.v2.core.Locality.newBuilder()
          .setRegion(region)
          .setZone(zone)
          .setSubZone(subzone)
          .build();
    }

    String getRegion() {
      return region;
    }

    String getZone() {
      return zone;
    }

    String getSubzone() {
      return subzone;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      XdsLocality locality = (XdsLocality) o;
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
   * Information about the locality from EDS response.
   */
  static final class LocalityInfo {
    final List<EquivalentAddressGroup> eags;
    final List<Integer> endPointWeights;
    final int localityWeight;
    final int priority;

    LocalityInfo(Collection<LbEndpoint> lbEndPoints, int localityWeight, int priority) {
      List<EquivalentAddressGroup> eags = new ArrayList<>(lbEndPoints.size());
      List<Integer> endPointWeights = new ArrayList<>(lbEndPoints.size());
      for (LbEndpoint lbEndPoint : lbEndPoints) {
        eags.add(lbEndPoint.eag);
        endPointWeights.add(lbEndPoint.endPointWeight);
      }
      this.eags = Collections.unmodifiableList(eags);
      this.endPointWeights = Collections.unmodifiableList(new ArrayList<>(endPointWeights));
      this.localityWeight = localityWeight;
      this.priority = priority;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      LocalityInfo that = (LocalityInfo) o;
      return localityWeight == that.localityWeight
          && priority == that.priority
          && Objects.equal(eags, that.eags)
          && Objects.equal(endPointWeights, that.endPointWeights);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(eags, endPointWeights, localityWeight, priority);
    }
  }

  static final class LbEndpoint {
    final EquivalentAddressGroup eag;
    final int endPointWeight;

    LbEndpoint(io.envoyproxy.envoy.api.v2.endpoint.LbEndpoint lbEndpointProto) {
      this(
          new EquivalentAddressGroup(ImmutableList.of(fromEnvoyProtoAddress(lbEndpointProto))),
          lbEndpointProto.getLoadBalancingWeight().getValue());
    }

    @VisibleForTesting
    LbEndpoint(EquivalentAddressGroup eag, int endPointWeight) {
      this.eag = eag;
      this.endPointWeight = endPointWeight;
    }

    private static java.net.SocketAddress fromEnvoyProtoAddress(
        io.envoyproxy.envoy.api.v2.endpoint.LbEndpoint lbEndpointProto) {
      SocketAddress socketAddress = lbEndpointProto.getEndpoint().getAddress().getSocketAddress();
      return new InetSocketAddress(socketAddress.getAddress(), socketAddress.getPortValue());
    }
  }

  static final class DropOverload {
    final String category;
    final int dropsPerMillion;

    DropOverload(String category, int dropsPerMillion) {
      this.category = category;
      this.dropsPerMillion = dropsPerMillion;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      DropOverload that = (DropOverload) o;
      return dropsPerMillion == that.dropsPerMillion && Objects.equal(category, that.category);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(category, dropsPerMillion);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("category", category)
          .add("dropsPerMillion", dropsPerMillion)
          .toString();
    }
  }
}
