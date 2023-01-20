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

package io.grpc.xds.internal.security.certprovider;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.annotations.VisibleForTesting;
import io.envoyproxy.envoy.config.core.v3.Node;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.CertificateValidationContext;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.CommonTlsContext;
import io.grpc.Internal;
import io.grpc.xds.Bootstrapper.CertificateProviderInfo;
import io.grpc.xds.EnvoyServerProtoData.DownstreamTlsContext;
import io.grpc.xds.internal.security.SslContextProvider;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Creates CertProviderServerSslContextProvider.
 */
@Internal
public final class CertProviderServerSslContextProviderFactory {

  private static final CertProviderServerSslContextProviderFactory DEFAULT_INSTANCE =
      new CertProviderServerSslContextProviderFactory(CertificateProviderStore.getInstance());
  private final CertificateProviderStore certificateProviderStore;

  @VisibleForTesting
  public CertProviderServerSslContextProviderFactory(
      CertificateProviderStore certificateProviderStore) {
    this.certificateProviderStore = certificateProviderStore;
  }

  public static CertProviderServerSslContextProviderFactory getInstance() {
    return DEFAULT_INSTANCE;
  }

  /**
   * Creates a {@link CertProviderServerSslContextProvider}.
   */
  public SslContextProvider getProvider(
      DownstreamTlsContext downstreamTlsContext,
      Node node,
      @Nullable Map<String, CertificateProviderInfo> certProviders) {
    checkNotNull(downstreamTlsContext, "downstreamTlsContext");
    CommonTlsContext commonTlsContext = downstreamTlsContext.getCommonTlsContext();
    CertificateValidationContext staticCertValidationContext
        = CertProviderSslContextProvider.getStaticValidationContext(commonTlsContext);
    CommonTlsContext.CertificateProviderInstance rootCertInstance
        = CertProviderSslContextProvider.getRootCertProviderInstance(commonTlsContext);
    CommonTlsContext.CertificateProviderInstance certInstance
        = CertProviderSslContextProvider.getCertProviderInstance(commonTlsContext);
    return new CertProviderServerSslContextProvider(
        node,
        certProviders,
        certInstance,
        rootCertInstance,
        staticCertValidationContext,
        downstreamTlsContext,
        certificateProviderStore);
  }
}
