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

import static com.google.common.base.Preconditions.checkNotNull;

import com.github.xds.core.v3.CollectionEntry;
import com.google.common.base.MoreObjects;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import io.envoyproxy.envoy.config.endpoint.v3.LbEndpoint;
import io.envoyproxy.envoy.config.endpoint.v3.LbEndpointCollection;
import io.grpc.xds.XdsLbEndpointCollectionResource.LbEndpointCollectionUpdate;
import io.grpc.xds.client.XdsClient.ResourceUpdate;
import io.grpc.xds.client.XdsResourceType;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The {@code LbEndpointCollection} (LEDS) resource type, as described in gRFC A95. An
 * {@code LbEndpointCollection} holds the list of endpoints for a single locality of an EDS
 * resource, allowing that list to be fetched separately from the EDS resource itself.
 */
final class XdsLbEndpointCollectionResource extends XdsResourceType<LbEndpointCollectionUpdate> {
  static final String ADS_TYPE_URL_LEDS =
      "type.googleapis.com/envoy.config.endpoint.v3.LbEndpointCollection";

  private static final String TYPE_URL_LB_ENDPOINT =
      "type.googleapis.com/envoy.config.endpoint.v3.LbEndpoint";

  private static final XdsLbEndpointCollectionResource instance =
      new XdsLbEndpointCollectionResource();

  static XdsLbEndpointCollectionResource getInstance() {
    return instance;
  }

  @Override
  public String typeName() {
    return "LEDS";
  }

  @Override
  public String typeUrl() {
    return ADS_TYPE_URL_LEDS;
  }

  @Override
  public boolean shouldRetrieveResourceKeysForArgs() {
    return true;
  }

  @Override
  protected boolean isFullStateOfTheWorld() {
    return false;
  }

  @Override
  protected Class<LbEndpointCollection> unpackedClassName() {
    return LbEndpointCollection.class;
  }

  @Override
  protected LbEndpointCollectionUpdate doParse(Args args, Message unpackedMessage)
      throws ResourceInvalidException {
    if (!(unpackedMessage instanceof LbEndpointCollection)) {
      throw new ResourceInvalidException("Invalid message type: " + unpackedMessage.getClass());
    }
    return processLbEndpointCollection((LbEndpointCollection) unpackedMessage);
  }

  private static LbEndpointCollectionUpdate processLbEndpointCollection(
      LbEndpointCollection collection) throws ResourceInvalidException {
    // An empty entries field is valid; the locality is then considered unreachable.
    List<Endpoints.LbEndpoint> endpoints = new ArrayList<>(collection.getEntriesCount());
    for (CollectionEntry entry : collection.getEntriesList()) {
      if (!entry.hasInlineEntry()) {
        throw new ResourceInvalidException("CollectionEntry with no inline_entry");
      }
      LbEndpoint lbEndpointProto;
      try {
        lbEndpointProto = unpackCompatibleType(
            entry.getInlineEntry().getResource(), LbEndpoint.class, TYPE_URL_LB_ENDPOINT, null);
      } catch (InvalidProtocolBufferException e) {
        throw new ResourceInvalidException("Can't decode LbEndpoint: " + e.getMessage(), e);
      }
      StructOrError<Endpoints.LbEndpoint> endpointOrError =
          XdsEndpointResource.parseLbEndpoint(lbEndpointProto);
      if (endpointOrError.getErrorDetail() != null) {
        throw new ResourceInvalidException(endpointOrError.getErrorDetail());
      }
      endpoints.add(endpointOrError.getStruct());
    }
    return new LbEndpointCollectionUpdate(Endpoints.LbEndpointCollection.create(endpoints));
  }

  /** The parsed representation of an {@code LbEndpointCollection} resource. */
  static final class LbEndpointCollectionUpdate implements ResourceUpdate {
    private final Endpoints.LbEndpointCollection endpointCollection;

    LbEndpointCollectionUpdate(Endpoints.LbEndpointCollection endpointCollection) {
      this.endpointCollection = checkNotNull(endpointCollection, "endpointCollection");
    }

    Endpoints.LbEndpointCollection getEndpointCollection() {
      return endpointCollection;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      LbEndpointCollectionUpdate that = (LbEndpointCollectionUpdate) o;
      return Objects.equals(endpointCollection, that.endpointCollection);
    }

    @Override
    public int hashCode() {
      return Objects.hash(endpointCollection);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("endpointCollection", endpointCollection)
          .toString();
    }
  }
}
