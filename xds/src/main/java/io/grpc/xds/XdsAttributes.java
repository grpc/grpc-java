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

import com.google.protobuf.Struct;
import io.grpc.Attributes;
import io.grpc.EquivalentAddressGroup;
import io.grpc.Grpc;
import io.grpc.InternalEquivalentAddressGroup;
import io.grpc.NameResolver;
import io.grpc.xds.XdsNameResolverProvider.CallCounterProvider;
import io.grpc.xds.client.Locality;
import io.grpc.xds.client.XdsClient;
import java.util.Map;

/**
 * Attributes used for xDS implementation.
 */
final class XdsAttributes {
  /**
   * Attribute key for passing around the XdsClient object pool across NameResolver/LoadBalancers.
   */
  @NameResolver.ResolutionResultAttr
  static final Attributes.Key<XdsClient> XDS_CLIENT =
      Attributes.Key.create("io.grpc.xds.XdsAttributes.xdsClient");

  /**
   * Attribute key for passing around the latest XdsConfig across NameResolver/LoadBalancers.
   */
  @NameResolver.ResolutionResultAttr
  static final Attributes.Key<XdsConfig> XDS_CONFIG =
      Attributes.Key.create("io.grpc.xds.XdsAttributes.xdsConfig");


  /**
   * Attribute key for passing around the XdsDependencyManager across NameResolver/LoadBalancers.
   */
  @NameResolver.ResolutionResultAttr
  static final Attributes.Key<XdsConfig.XdsClusterSubscriptionRegistry>
      XDS_CLUSTER_SUBSCRIPT_REGISTRY =
      Attributes.Key.create("io.grpc.xds.XdsAttributes.xdsConfig.XdsClusterSubscriptionRegistry");

  /**
   * Attribute key for obtaining the global provider that provides atomics for aggregating
   * outstanding RPCs sent to each cluster.
   */
  @NameResolver.ResolutionResultAttr
  static final Attributes.Key<CallCounterProvider> CALL_COUNTER_PROVIDER =
      Attributes.Key.create("io.grpc.xds.XdsAttributes.callCounterProvider");

  /**
   * Map from localities to their weights.
   */
  @NameResolver.ResolutionResultAttr
  static final Attributes.Key<Integer> ATTR_LOCALITY_WEIGHT =
      Attributes.Key.create("io.grpc.xds.XdsAttributes.localityWeight");

  /**
   * Name of the cluster that provides this EquivalentAddressGroup.
   */
  @EquivalentAddressGroup.Attr
  public static final Attributes.Key<String> ATTR_CLUSTER_NAME =
      Attributes.Key.create("io.grpc.xds.XdsAttributes.clusterName");

  /**
   * The locality that this EquivalentAddressGroup is in.
   */
  @EquivalentAddressGroup.Attr
  static final Attributes.Key<Locality> ATTR_LOCALITY =
      Attributes.Key.create("io.grpc.xds.XdsAttributes.locality");

  /**
   * Endpoint weight for load balancing purposes.
   */
  @EquivalentAddressGroup.Attr
  static final Attributes.Key<Long> ATTR_SERVER_WEIGHT = InternalEquivalentAddressGroup.ATTR_WEIGHT;

  /**
   * Filter chain match for network filters.
   */
  @Grpc.TransportAttr
  static final Attributes.Key<FilterChainSelectorManager>
          ATTR_FILTER_CHAIN_SELECTOR_MANAGER = Attributes.Key.create(
          "io.grpc.xds.XdsAttributes.filterChainSelectorManager");

  /** Grace time to use when draining. Null for an infinite grace time. */
  @Grpc.TransportAttr
  static final Attributes.Key<Long> ATTR_DRAIN_GRACE_NANOS =
      Attributes.Key.create("io.grpc.xds.XdsAttributes.drainGraceTime");

  /**
   * Attribute key for xDS route metadata used in filter matching.
   */
  @NameResolver.ResolutionResultAttr
  public static final Attributes.Key<Map<String, Struct>> ATTR_FILTER_METADATA = Attributes.Key
          .create("io.grpc.xds.XdsAttributes.filterMetadata");

  /**
   * CallOptions key for xDS route metadata used in filter matching.
   */
  public static final io.grpc.CallOptions.Key<Map<String, Struct>> CALL_OPTIONS_FILTER_METADATA =
      io.grpc.CallOptions.Key.create("io.grpc.xds.XdsAttributes.filterMetadata");

  private XdsAttributes() {}
}
