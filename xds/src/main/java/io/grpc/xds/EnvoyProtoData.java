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
import io.envoyproxy.envoy.type.FractionalPercent;
import io.envoyproxy.envoy.type.FractionalPercent.DenominatorType;
import io.grpc.EquivalentAddressGroup;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Defines gRPC data types for Envoy protobuf messages used in xDS protocol. Each data type has
 * the same name as Envoy's corresponding protobuf message, but only with fields used by gRPC.
 *
 * <p>Each data type should define a {@code fromEnvoyProtoXXX} static method to convert an Envoy
 * proto message to an instance of that data type.
 *
 * <p>For data types that need to be sent as protobuf messages, a {@code toEnvoyProtoXXX} instance
 * method is defined to convert an instance to Envoy proto message.
 */
final class EnvoyProtoData {

  // Prevent instantiation.
  private EnvoyProtoData() {
  }

  /**
   * See corresponding Envoy proto message {@link io.envoyproxy.envoy.api.v2.core.Locality}.
   */
  static final class Locality {
    private final String region;
    private final String zone;
    private final String subzone;

    /** Must only be used for testing. */
    @VisibleForTesting
    Locality(String region, String zone, String subzone) {
      this.region = region;
      this.zone = zone;
      this.subzone = subzone;
    }

    static Locality fromEnvoyProtoLocality(io.envoyproxy.envoy.api.v2.core.Locality locality) {
      return new Locality(
          /* region = */ locality.getRegion(),
          /* zone = */ locality.getZone(),
          /* subzone = */ locality.getSubZone());
    }

    io.envoyproxy.envoy.api.v2.core.Locality toEnvoyProtoLocality() {
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
        endPointWeights.add(lbEndPoint.loadBalancingWeight);
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

  /**
   * See corresponding Envoy proto message {@link io.envoyproxy.envoy.api.v2.endpoint.LbEndpoint}.
   */
  static final class LbEndpoint {
    private final EquivalentAddressGroup eag;
    private final int loadBalancingWeight;

    /** Must only be used for testing. */
    @VisibleForTesting
    LbEndpoint(EquivalentAddressGroup eag, int loadBalancingWeight) {
      this.eag = eag;
      this.loadBalancingWeight = loadBalancingWeight;
    }

    static LbEndpoint fromEnvoyProtoLbEndpoint(
        io.envoyproxy.envoy.api.v2.endpoint.LbEndpoint proto) {
      io.envoyproxy.envoy.api.v2.core.SocketAddress socketAddress =
          proto.getEndpoint().getAddress().getSocketAddress();
      return
          new LbEndpoint(
              new EquivalentAddressGroup(
                  ImmutableList.<java.net.SocketAddress>of(
                      new InetSocketAddress(socketAddress.getAddress(),
                          socketAddress.getPortValue()))),
              proto.getLoadBalancingWeight().getValue());
    }

    EquivalentAddressGroup getAddress() {
      return eag;
    }

    int getLoadBalancingWeight() {
      return loadBalancingWeight;
    }

    // TODO(chengyuanzhang): define equals()/hashCode()/toString() if necessary.
  }

  /**
   * See corresponding Enovy proto message {@link
   * io.envoyproxy.envoy.api.v2.ClusterLoadAssignment.Policy.DropOverload}.
   */
  static final class DropOverload {
    private final String category;
    private final int dropsPerMillion;

    /** Must only be used for testing. */
    @VisibleForTesting
    DropOverload(String category, int dropsPerMillion) {
      this.category = category;
      this.dropsPerMillion = dropsPerMillion;
    }

    static DropOverload fromEnvoyProtoDropOverload(
        io.envoyproxy.envoy.api.v2.ClusterLoadAssignment.Policy.DropOverload proto) {
      FractionalPercent percent = proto.getDropPercentage();
      int numerator = percent.getNumerator();
      DenominatorType type = percent.getDenominator();
      switch (type) {
        case TEN_THOUSAND:
          numerator *= 100;
          break;
        case HUNDRED:
          numerator *= 100_00;
          break;
        case MILLION:
          break;
        default:
          throw new IllegalArgumentException("Unknown denominator type of " + percent);
      }

      if (numerator > 1_000_000) {
        numerator = 1_000_000;
      }

      return new DropOverload(proto.getCategory(), numerator);
    }

    String getCategory() {
      return category;
    }

    int getDropsPerMillion() {
      return dropsPerMillion;
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
