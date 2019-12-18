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

import io.envoyproxy.envoy.api.v2.auth.CertificateValidationContext;
import io.envoyproxy.envoy.api.v2.auth.CommonTlsContext;
import io.envoyproxy.envoy.api.v2.auth.DownstreamTlsContext;
import io.envoyproxy.envoy.api.v2.auth.SdsSecretConfig;
import io.envoyproxy.envoy.api.v2.auth.TlsCertificate;
import io.envoyproxy.envoy.api.v2.auth.UpstreamTlsContext;
import io.grpc.Attributes;
import io.grpc.Grpc;
import io.grpc.Internal;
import io.grpc.NameResolver;
import io.grpc.internal.ObjectPool;

/**
 * Special attributes that are only useful to gRPC in the XDS context.
 */
@Internal
public final class XdsAttributes {
  /**
   * Attribute key for SdsSecretConfig of a subchannel.
   */
  @Grpc.TransportAttr
  public static final Attributes.Key<SdsSecretConfig> ATTR_SDS_CONFIG =
          Attributes.Key.create("io.grpc.xds.XdsAttributes.sdsSecretConfig");

  /**
   * Attribute key for TlsCertificate of a subchannel.
   */
  @Grpc.TransportAttr
  public static final Attributes.Key<TlsCertificate> ATTR_TLS_CERTIFICATE =
          Attributes.Key.create("io.grpc.xds.XdsAttributes.tlsCertificate");

  /**
   * Attribute key for CertificateValidationContext of a subchannel.
   */
  @Grpc.TransportAttr
  public static final Attributes.Key<CertificateValidationContext> ATTR_CERT_VALIDATION_CONTEXT =
          Attributes.Key.create("io.grpc.xds.XdsAttributes.certificateValidationContext");

  /**
   * Attribute key for CommonTlsContext.
   */
  @Grpc.TransportAttr
  public static final Attributes.Key<CommonTlsContext> ATTR_COMMON_TLS_CONTEXT =
      Attributes.Key.create("io.grpc.xds.XdsAttributes.commonTlsContext");

  /**
   * Attribute key for UpstreamTlsContext (used by client) for subchannel.
   */
  @Grpc.TransportAttr
  public static final Attributes.Key<UpstreamTlsContext> ATTR_UPSTREAM_TLS_CONTEXT =
      Attributes.Key.create("io.grpc.xds.XdsAttributes.upstreamTlsContext");

  /**
   * Attribute key for DownstreamTlsContext (used by server).
   */
  @Grpc.TransportAttr
  public static final Attributes.Key<DownstreamTlsContext> ATTR_DOWNSTREAM_TLS_CONTEXT =
      Attributes.Key.create("io.grpc.xds.XdsAttributes.downstreamTlsContext");

  @NameResolver.ResolutionResultAttr
  static final Attributes.Key<ObjectPool<XdsClient>> XDS_CLIENT_POOL =
      Attributes.Key.create("io.grpc.xds.XdsAttributes.xdsClientPool");

  private XdsAttributes() {}
}
