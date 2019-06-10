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

/**
 * An {@code XdsLocality} object is simply a POJO representation for {@link
 * io.envoyproxy.envoy.api.v2.core.Locality}, with only details needed for {@link XdsLoadBalancer}.
 */
final class XdsLocality {
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
