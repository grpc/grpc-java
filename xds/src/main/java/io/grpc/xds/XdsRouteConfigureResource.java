/*
 * Copyright 2022 The gRPC Authors
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
import static io.grpc.xds.AbstractXdsClient.ResourceType.RDS;
import static io.grpc.xds.AbstractXdsClient.ResourceType;
import static io.grpc.xds.Bootstrapper.ServerInfo;

import com.google.common.base.MoreObjects;
import com.google.protobuf.Message;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.grpc.SynchronizationContext;
import io.grpc.xds.ClientXdsClient.ResourceInvalidException;
import io.grpc.xds.XdsClient.ResourceUpdate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import javax.annotation.Nullable;

class XdsRouteConfigureResource extends XdsResourceType {
  static final String ADS_TYPE_URL_RDS_V2 =
      "type.googleapis.com/envoy.api.v2.RouteConfiguration";
  static final String ADS_TYPE_URL_RDS =
      "type.googleapis.com/envoy.config.route.v3.RouteConfiguration";
  private final FilterRegistry filterRegistry;

  public XdsRouteConfigureResource(SynchronizationContext syncContext,
                                   FilterRegistry filterRegistry) {
    super(syncContext);
    this.filterRegistry = filterRegistry;
  }

  @Override
  @Nullable
  String extractResourceName(Message unpackedResource) {
    if (!(unpackedResource instanceof RouteConfiguration)) {
      return null;
    }
    return ((RouteConfiguration) unpackedResource).getName();
  }

  @Override
  ResourceType typeName() {
    return RDS;
  }

  @Override
  String typeUrl() {
    return ADS_TYPE_URL_RDS;
  }

  @Override
  String typeUrlV2() {
    return ADS_TYPE_URL_RDS_V2;
  }

  @Nullable
  @Override
  ResourceType dependentResource() {
    return null;
  }

  @Override
  Class<RouteConfiguration> unpackedClassName() {
    return RouteConfiguration.class;
  }

  @Override
  ResourceUpdate doParse(ServerInfo serverInfo, Message unpackedMessage,
                         Set<String> retainedResources, boolean isResourceV3)
      throws ResourceInvalidException {
    if (!(unpackedMessage instanceof RouteConfiguration)) {
      throw new ResourceInvalidException("Invalid message type: " + unpackedMessage.getClass());
    }
    return processRouteConfiguration((RouteConfiguration) unpackedMessage,
        filterRegistry, enableFaultInjection && isResourceV3);
  }

  private static RdsUpdate processRouteConfiguration(
      RouteConfiguration routeConfig, FilterRegistry filterRegistry, boolean parseHttpFilter)
      throws ResourceInvalidException {
    return new RdsUpdate(extractVirtualHosts(routeConfig, filterRegistry, parseHttpFilter));
  }

  static final class RdsUpdate implements ResourceUpdate {
    // The list virtual hosts that make up the route table.
    final List<VirtualHost> virtualHosts;

    RdsUpdate(List<VirtualHost> virtualHosts) {
      this.virtualHosts = Collections.unmodifiableList(
          new ArrayList<>(checkNotNull(virtualHosts, "virtualHosts")));
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("virtualHosts", virtualHosts)
          .toString();
    }

    @Override
    public int hashCode() {
      return Objects.hash(virtualHosts);
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      RdsUpdate that = (RdsUpdate) o;
      return Objects.equals(virtualHosts, that.virtualHosts);
    }
  }
}
