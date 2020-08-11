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
import io.grpc.Grpc;
import io.grpc.Internal;
import io.grpc.NameResolver;
import io.grpc.internal.ObjectPool;
import io.grpc.xds.EnvoyServerProtoData.UpstreamTlsContext;
import io.grpc.xds.LoadStatsManager.LoadStatsStore;

/**
 * Special attributes that are only useful to gRPC in the XDS context.
 */
@Internal
public final class XdsAttributes {
  /**
   * Attribute key for UpstreamTlsContext (used by client) for subchannel.
   */
  @Grpc.TransportAttr
  public static final Attributes.Key<UpstreamTlsContext> ATTR_UPSTREAM_TLS_CONTEXT =
      Attributes.Key.create("io.grpc.xds.XdsAttributes.upstreamTlsContext");

  @NameResolver.ResolutionResultAttr
  static final Attributes.Key<ObjectPool<XdsClient>> XDS_CLIENT_POOL =
      Attributes.Key.create("io.grpc.xds.XdsAttributes.xdsClientPool");

  // TODO (chengyuanzhang): temporary solution for migrating to LRS policy. Should access
  //   stats object via XdsClient interface.
  static final Attributes.Key<LoadStatsStore> ATTR_CLUSTER_SERVICE_LOAD_STATS_STORE =
      Attributes.Key.create("io.grpc.xds.XdsAttributes.loadStatsStore");

  private XdsAttributes() {}
}
