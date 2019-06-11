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

import static com.google.common.truth.Truth.assertThat;

import com.google.common.testing.EqualsTester;
import io.envoyproxy.envoy.api.v2.core.Locality;
import org.junit.Test;

public class XdsLocalityTest {

  @Test
  public void convertToAndFromLocalityProto() {
    Locality locality =
        Locality.newBuilder()
            .setRegion("test_region")
            .setZone("test_zone")
            .setSubZone("test_subzone")
            .build();
    XdsLocality xdsLocality = XdsLocality.fromLocalityProto(locality);
    assertThat(xdsLocality.getRegion()).isEqualTo("test_region");
    assertThat(xdsLocality.getZone()).isEqualTo("test_zone");
    assertThat(xdsLocality.getSubzone()).isEqualTo("test_subzone");

    Locality convertedLocality = xdsLocality.toLocalityProto();
    assertThat(convertedLocality.getRegion()).isEqualTo("test_region");
    assertThat(convertedLocality.getZone()).isEqualTo("test_zone");
    assertThat(convertedLocality.getSubZone()).isEqualTo("test_subzone");
  }

  @Test
  public void equal() {
    new EqualsTester()
        .addEqualityGroup(
            new XdsLocality("region-a", "zone-a", "subzone-a"),
            new XdsLocality("region-a", "zone-a", "subzone-a"))
        .addEqualityGroup(
            new XdsLocality("region", "zone", "subzone")
        )
        .addEqualityGroup(
            new XdsLocality("", "", ""),
            new XdsLocality("", "", ""))
        .testEquals();
  }

  @Test
  public void hash() {
    assertThat(new XdsLocality("region", "zone", "subzone").hashCode())
        .isEqualTo(new XdsLocality("region", "zone","subzone").hashCode());
  }
}
