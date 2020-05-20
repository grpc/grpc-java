/*
 * Copyright 2020 The gRPC Authors
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

package io.grpc.xds.internal.sds;

import static com.google.common.base.Preconditions.checkNotNull;

import io.envoyproxy.envoy.api.v2.auth.CertificateValidationContext;
import io.envoyproxy.envoy.api.v2.auth.CommonTlsContext;
import io.envoyproxy.envoy.api.v2.auth.DownstreamTlsContext;
import io.grpc.xds.internal.sds.trust.SdsTrustManagerFactory;
import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import java.io.IOException;
import java.security.cert.CertStoreException;
import java.security.cert.CertificateException;

/** A subclass of SslContextProvider used for server side {@link SslContext}s. */
public abstract class ServerSslContextProvider extends SslContextProvider {

  private final DownstreamTlsContext downstreamTlsContext;

  protected ServerSslContextProvider(DownstreamTlsContext downstreamTlsContext) {
    this.downstreamTlsContext = checkNotNull(downstreamTlsContext, "downstreamTlsContext");
  }

  @Override
  CommonTlsContext getCommonTlsContext() {
    return downstreamTlsContext.getCommonTlsContext();
  }

  public DownstreamTlsContext getDownstreamTlsContext() {
    return downstreamTlsContext;
  }

  protected void setClientAuthValues(
      SslContextBuilder sslContextBuilder, CertificateValidationContext localCertValidationContext)
      throws CertificateException, IOException, CertStoreException {
    if (localCertValidationContext != null) {
      sslContextBuilder.trustManager(new SdsTrustManagerFactory(localCertValidationContext));
      sslContextBuilder.clientAuth(
          downstreamTlsContext.hasRequireClientCertificate()
              ? ClientAuth.REQUIRE
              : ClientAuth.OPTIONAL);
    } else {
      sslContextBuilder.clientAuth(ClientAuth.NONE);
    }
  }
}
