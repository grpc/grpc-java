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

import io.envoyproxy.envoy.api.v2.core.Node;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.CertificateValidationContext;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.CommonTlsContext;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.SdsSecretConfig;
import io.grpc.netty.GrpcSslContexts;
import io.grpc.xds.EnvoyServerProtoData.DownstreamTlsContext;
import io.grpc.xds.internal.sds.trust.SdsTrustManagerFactory;
import io.netty.handler.ssl.SslContextBuilder;
import java.io.IOException;
import java.security.cert.CertStoreException;
import java.security.cert.CertificateException;
import java.util.concurrent.Executor;

/** A server SslContext provider that uses SDS to fetch secrets. */
final class SdsServerSslContextProvider extends SdsSslContextProvider {

  private SdsServerSslContextProvider(
      Node node,
      SdsSecretConfig certSdsConfig,
      SdsSecretConfig validationContextSdsConfig,
      Executor watcherExecutor,
      Executor channelExecutor,
      DownstreamTlsContext downstreamTlsContext) {
    super(node,
        certSdsConfig,
        validationContextSdsConfig,
        null,
        watcherExecutor,
        channelExecutor, downstreamTlsContext);
  }

  static SdsServerSslContextProvider getProvider(
      DownstreamTlsContext downstreamTlsContext,
      Node node,
      Executor watcherExecutor,
      Executor channelExecutor) {
    checkNotNull(downstreamTlsContext, "downstreamTlsContext");
    CommonTlsContext commonTlsContext = downstreamTlsContext.getCommonTlsContext();

    SdsSecretConfig certSdsConfig = null;
    if (commonTlsContext.getTlsCertificateSdsSecretConfigsCount() > 0) {
      certSdsConfig = commonTlsContext.getTlsCertificateSdsSecretConfigs(0);
    }

    SdsSecretConfig validationContextSdsConfig = null;
    if (commonTlsContext.hasValidationContextSdsSecretConfig()) {
      validationContextSdsConfig = commonTlsContext.getValidationContextSdsSecretConfig();
    }
    return new SdsServerSslContextProvider(
        node,
        certSdsConfig,
        validationContextSdsConfig,
        watcherExecutor,
        channelExecutor,
        downstreamTlsContext);
  }

  @Override
  protected final SslContextBuilder getSslContextBuilder(
      CertificateValidationContext localCertValidationContext)
      throws CertificateException, IOException, CertStoreException {
    SslContextBuilder sslContextBuilder =
        GrpcSslContexts.forServer(
            tlsCertificate.getCertificateChain().getInlineBytes().newInput(),
            tlsCertificate.getPrivateKey().getInlineBytes().newInput(),
            tlsCertificate.hasPassword()
                ? tlsCertificate.getPassword().getInlineString()
                : null);
    setClientAuthValues(
        sslContextBuilder,
        localCertValidationContext != null
            ? new SdsTrustManagerFactory(localCertValidationContext)
            : null);
    return sslContextBuilder;
  }
}
