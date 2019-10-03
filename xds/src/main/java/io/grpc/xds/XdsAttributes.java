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
import io.envoyproxy.envoy.api.v2.auth.SdsSecretConfig;
import io.envoyproxy.envoy.api.v2.auth.TlsCertificate;
import io.grpc.Attributes;
import io.grpc.Grpc;

/**
 * Special attributes that are only useful to gRPC in the XDS context.
 */
final class XdsAttributes {
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

  private XdsAttributes() {}
}
