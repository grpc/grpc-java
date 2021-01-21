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

import io.grpc.Attributes;
import io.grpc.EquivalentAddressGroup;
import io.grpc.Grpc;
import io.grpc.Internal;
import io.grpc.NameResolver;
import io.grpc.internal.ObjectPool;
import io.grpc.xds.LoadStatsManager.LoadStatsStore;
import io.grpc.xds.XdsNameResolverProvider.CallCounterProvider;
import io.grpc.xds.internal.sds.SslContextProviderSupplier;

/**
 * Internal attributes used for xDS implementation. Do not use.
 */
@Internal
public final class InternalXdsAttributes {

  // TODO(sanjaypujare): move to xds internal package.
  /** Attribute key for SslContextProviderSupplier (used from client) for a subchannel. */
  @Grpc.TransportAttr
  public static final Attributes.Key<SslContextProviderSupplier>
      ATTR_SSL_CONTEXT_PROVIDER_SUPPLIER =
          Attributes.Key.create("io.grpc.xds.internal.sds.SslContextProviderSupplier");

  /**
   * Attribute key for passing around the XdsClient object pool across NameResolver/LoadBalancers.
   */
  @NameResolver.ResolutionResultAttr
  static final Attributes.Key<ObjectPool<XdsClient>> XDS_CLIENT_POOL =
      Attributes.Key.create("io.grpc.xds.InternalXdsAttributes.xdsClientPool");

  /**
   * Attribute key for obtaining the global provider that provides atomics for aggregating
   * outstanding RPCs sent to each cluster.
   */
  @NameResolver.ResolutionResultAttr
  static final Attributes.Key<CallCounterProvider> CALL_COUNTER_PROVIDER =
      Attributes.Key.create("io.grpc.xds.InternalXdsAttributes.callCounterProvider");

  /**
   * Name of the cluster that provides this EquivalentAddressGroup.
   */
  @Internal
  @EquivalentAddressGroup.Attr
  public static final Attributes.Key<String> ATTR_CLUSTER_NAME =
      Attributes.Key.create("io.grpc.xds.InternalXdsAttributes.clusterName");

  // TODO (chengyuanzhang): temporary solution for migrating to LRS policy. Should access
  //   stats object via XdsClient interface.
  static final Attributes.Key<LoadStatsStore> ATTR_CLUSTER_SERVICE_LOAD_STATS_STORE =
      Attributes.Key.create("io.grpc.xds.InternalXdsAttributes.loadStatsStore");

  private InternalXdsAttributes() {}
}
