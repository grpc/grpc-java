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

package io.grpc.xds.internal.certprovider;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.annotations.VisibleForTesting;
import io.envoyproxy.envoy.config.core.v3.Node;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.CertificateValidationContext;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.CommonTlsContext;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.CommonTlsContext.CombinedCertificateValidationContext;
import io.grpc.Internal;
import io.grpc.netty.GrpcSslContexts;
import io.grpc.xds.Bootstrapper.CertificateProviderInfo;
import io.grpc.xds.EnvoyServerProtoData.DownstreamTlsContext;
import io.grpc.xds.internal.sds.trust.SdsTrustManagerFactory;
import io.netty.handler.ssl.SslContextBuilder;

import java.io.IOException;
import java.security.cert.CertStoreException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Map;

/** A server SslContext provider using CertificateProviderInstance to fetch secrets. */
@Internal
public final class CertProviderServerSslContextProvider extends CertProviderSslContextProvider {

  private CertProviderServerSslContextProvider(
          Node node,
          Map<String, CertificateProviderInfo> certProviders,
          CommonTlsContext.CertificateProviderInstance certInstance,
          CommonTlsContext.CertificateProviderInstance rootCertInstance,
          CertificateValidationContext staticCertValidationContext,
          DownstreamTlsContext downstreamTlsContext,
          CertificateProviderStore certificateProviderStore) {
    super(
        node,
        certProviders,
        checkNotNull(certInstance, "Server SSL requires certInstance"),
        rootCertInstance,
        staticCertValidationContext,
        downstreamTlsContext,
        certificateProviderStore);
  }

  @Override
  protected final SslContextBuilder getSslContextBuilder(
      CertificateValidationContext certificateValidationContextdationContext)
      throws CertStoreException, CertificateException, IOException {
    SslContextBuilder sslContextBuilder = SslContextBuilder.forServer(savedKey, savedCertChain);
    setClientAuthValues(
        sslContextBuilder,
        isMtls()
            ? new SdsTrustManagerFactory(
                savedTrustedRoots.toArray(new X509Certificate[0]),
                certificateValidationContextdationContext)
            : null);
    sslContextBuilder = GrpcSslContexts.configure(sslContextBuilder);
    return sslContextBuilder;
  }

  /** Creates CertProviderServerSslContextProvider. */
  @Internal
  public static final class Factory {
    private static final Factory DEFAULT_INSTANCE =
        new Factory(CertificateProviderStore.getInstance());
    private final CertificateProviderStore certificateProviderStore;

    @VisibleForTesting public Factory(CertificateProviderStore certificateProviderStore) {
      this.certificateProviderStore = certificateProviderStore;
    }

    public static Factory getInstance() {
      return DEFAULT_INSTANCE;
    }

    /** Creates a {@link CertProviderServerSslContextProvider}. */
    public CertProviderServerSslContextProvider getProvider(
        DownstreamTlsContext downstreamTlsContext,
        Node node,
        Map<String, CertificateProviderInfo> certProviders) {
      checkNotNull(downstreamTlsContext, "downstreamTlsContext");
      CommonTlsContext commonTlsContext = downstreamTlsContext.getCommonTlsContext();
      CommonTlsContext.CertificateProviderInstance rootCertInstance = null;
      CertificateValidationContext staticCertValidationContext = null;
      if (commonTlsContext.hasCombinedValidationContext()) {
        CombinedCertificateValidationContext combinedValidationContext =
            commonTlsContext.getCombinedValidationContext();
        if (combinedValidationContext.hasValidationContextCertificateProviderInstance()) {
          rootCertInstance =
              combinedValidationContext.getValidationContextCertificateProviderInstance();
        }
        if (combinedValidationContext.hasDefaultValidationContext()) {
          staticCertValidationContext = combinedValidationContext.getDefaultValidationContext();
        }
      } else if (commonTlsContext.hasValidationContextCertificateProviderInstance()) {
        rootCertInstance = commonTlsContext.getValidationContextCertificateProviderInstance();
      } else if (commonTlsContext.hasValidationContext()) {
        staticCertValidationContext = commonTlsContext.getValidationContext();
      }
      CommonTlsContext.CertificateProviderInstance certInstance = null;
      if (commonTlsContext.hasTlsCertificateCertificateProviderInstance()) {
        certInstance = commonTlsContext.getTlsCertificateCertificateProviderInstance();
      }
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
}
