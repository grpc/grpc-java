/*
 * Copyright 2026 The gRPC Authors
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
import static org.junit.Assert.assertThrows;

import com.github.xds.core.v3.CollectionEntry;
import com.github.xds.core.v3.ResourceLocator;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.protobuf.Any;
import com.google.protobuf.StringValue;
import com.google.protobuf.UInt32Value;
import io.envoyproxy.envoy.config.core.v3.Address;
import io.envoyproxy.envoy.config.core.v3.HealthStatus;
import io.envoyproxy.envoy.config.core.v3.SocketAddress;
import io.envoyproxy.envoy.config.endpoint.v3.Endpoint;
import io.envoyproxy.envoy.config.endpoint.v3.LbEndpoint;
import io.envoyproxy.envoy.config.endpoint.v3.LbEndpointCollection;
import io.grpc.xds.XdsLbEndpointCollectionResource.LbEndpointCollectionUpdate;
import io.grpc.xds.client.XdsResourceType.ResourceInvalidException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link XdsLbEndpointCollectionResource}. */
@RunWith(JUnit4.class)
public class XdsLbEndpointCollectionResourceTest {

  private final XdsLbEndpointCollectionResource resource =
      XdsLbEndpointCollectionResource.getInstance();

  @Test
  public void typeInfo() {
    assertThat(resource.typeName()).isEqualTo("LEDS");
    assertThat(resource.typeUrl())
        .isEqualTo("type.googleapis.com/envoy.config.endpoint.v3.LbEndpointCollection");
    assertThat(resource.unpackedClassName()).isEqualTo(LbEndpointCollection.class);
    assertThat(resource.isFullStateOfTheWorld()).isFalse();
    assertThat(resource.shouldRetrieveResourceKeysForArgs()).isTrue();
  }

  @Test
  public void doParse_multipleEntries() throws ResourceInvalidException {
    LbEndpointCollection collection = LbEndpointCollection.newBuilder()
        .addEntries(inlineEntry(lbEndpoint("172.14.14.5", 8888, 20, HealthStatus.HEALTHY)))
        .addEntries(inlineEntry(lbEndpoint("172.14.14.6", 8888, 30, HealthStatus.UNHEALTHY)))
        .build();

    LbEndpointCollectionUpdate update = resource.doParse(null, collection);

    assertThat(update.getEndpointCollection()).isEqualTo(
        Endpoints.LbEndpointCollection.create(ImmutableList.of(
            Endpoints.LbEndpoint.create("172.14.14.5", 8888, 20, true, "", ImmutableMap.of()),
            Endpoints.LbEndpoint.create("172.14.14.6", 8888, 30, false, "", ImmutableMap.of()))));
  }

  @Test
  public void doParse_emptyEntriesIsValid() throws ResourceInvalidException {
    LbEndpointCollectionUpdate update =
        resource.doParse(null, LbEndpointCollection.getDefaultInstance());

    assertThat(update.getEndpointCollection().endpoints()).isEmpty();
  }

  @Test
  public void doParse_entryWithoutInlineEntry() {
    LbEndpointCollection collection = LbEndpointCollection.newBuilder()
        .addEntries(CollectionEntry.newBuilder()
            .setLocator(ResourceLocator.getDefaultInstance()))
        .build();

    ResourceInvalidException ex = assertThrows(ResourceInvalidException.class,
        () -> resource.doParse(null, collection));
    assertThat(ex).hasMessageThat().isEqualTo("CollectionEntry with no inline_entry");
  }

  @Test
  public void doParse_inlineEntryWithWrongResourceType() {
    LbEndpointCollection collection = LbEndpointCollection.newBuilder()
        .addEntries(CollectionEntry.newBuilder()
            .setInlineEntry(CollectionEntry.InlineEntry.newBuilder()
                .setResource(Any.pack(StringValue.of("not-an-endpoint")))))
        .build();

    ResourceInvalidException ex = assertThrows(ResourceInvalidException.class,
        () -> resource.doParse(null, collection));
    assertThat(ex).hasMessageThat().contains("Can't decode LbEndpoint");
  }

  @Test
  public void doParse_endpointWithoutAddress() {
    LbEndpointCollection collection = LbEndpointCollection.newBuilder()
        .addEntries(inlineEntry(LbEndpoint.getDefaultInstance()))
        .build();

    ResourceInvalidException ex = assertThrows(ResourceInvalidException.class,
        () -> resource.doParse(null, collection));
    assertThat(ex).hasMessageThat().isEqualTo("LbEndpoint with no endpoint/address");
  }

  @Test
  public void doParse_endpointWithNonIpAddress() {
    LbEndpointCollection collection = LbEndpointCollection.newBuilder()
        .addEntries(inlineEntry(lbEndpoint("example.com", 8888, 20, HealthStatus.HEALTHY)))
        .build();

    ResourceInvalidException ex = assertThrows(ResourceInvalidException.class,
        () -> resource.doParse(null, collection));
    assertThat(ex).hasMessageThat().contains("Address is not an IP");
  }

  @Test
  public void doParse_wrongMessageType() {
    ResourceInvalidException ex = assertThrows(ResourceInvalidException.class,
        () -> resource.doParse(null, LbEndpoint.getDefaultInstance()));
    assertThat(ex).hasMessageThat().contains("Invalid message type");
  }

  private static CollectionEntry inlineEntry(LbEndpoint endpoint) {
    return CollectionEntry.newBuilder()
        .setInlineEntry(CollectionEntry.InlineEntry.newBuilder().setResource(Any.pack(endpoint)))
        .build();
  }

  private static LbEndpoint lbEndpoint(
      String address, int port, int weight, HealthStatus healthStatus) {
    return LbEndpoint.newBuilder()
        .setEndpoint(Endpoint.newBuilder()
            .setAddress(Address.newBuilder()
                .setSocketAddress(
                    SocketAddress.newBuilder().setAddress(address).setPortValue(port))))
        .setHealthStatus(healthStatus)
        .setLoadBalancingWeight(UInt32Value.newBuilder().setValue(weight))
        .build();
  }
}
