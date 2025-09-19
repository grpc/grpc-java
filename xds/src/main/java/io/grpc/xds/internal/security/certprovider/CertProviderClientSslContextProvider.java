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

package io.grpc.xds.internal.security.certprovider;

import io.envoyproxy.envoy.config.core.v3.Node;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.CertificateValidationContext;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.CommonTlsContext;
import io.grpc.netty.GrpcSslContexts;
import io.grpc.xds.EnvoyServerProtoData.UpstreamTlsContext;
import io.grpc.xds.client.Bootstrapper.CertificateProviderInfo;
import io.grpc.xds.internal.security.CommonTlsContextUtil;
import io.grpc.xds.internal.security.trust.XdsTrustManagerFactory;
import io.netty.handler.ssl.SslContextBuilder;
import java.io.IOException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertStoreException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.AbstractMap;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/** A client SslContext provider using CertificateProviderInstance to fetch secrets. */
final class CertProviderClientSslContextProvider extends CertProviderSslContextProvider {

  private final String sniForSanMatching;

  CertProviderClientSslContextProvider(
          Node node,
          @Nullable Map<String, CertificateProviderInfo> certProviders,
          CommonTlsContext.CertificateProviderInstance certInstance,
          CommonTlsContext.CertificateProviderInstance rootCertInstance,
          CertificateValidationContext staticCertValidationContext,
          UpstreamTlsContext upstreamTlsContext,
          String sniForSanMatching, CertificateProviderStore certificateProviderStore) {
    super(
        node,
        certProviders,
        certInstance,
        rootCertInstance,
        staticCertValidationContext,
        upstreamTlsContext,
        certificateProviderStore);
    this.sniForSanMatching = upstreamTlsContext.getAutoSniSanValidation()? sniForSanMatching : null;
    if (rootCertInstance == null
        && CommonTlsContextUtil.isUsingSystemRootCerts(tlsContext.getCommonTlsContext())
        && !isMtls()) {
      try {
        // Instantiate sslContext so that addCallback will immediately update the callback with
        // the SslContext.
        AbstractMap.SimpleImmutableEntry<SslContextBuilder, TrustManager> sslContextBuilderAndTm =
            getSslContextBuilderAndExtendedX509TrustManager(staticCertificateValidationContext);
        sslContextAndExtendedX509TrustManager = new AbstractMap.SimpleImmutableEntry(
            sslContextBuilderAndTm.getKey().build(), sslContextBuilderAndTm.getValue());
      } catch (CertStoreException | CertificateException | IOException e) {
        throw new RuntimeException(e);
      }
    }
  }

  @Override
  protected final AbstractMap.SimpleImmutableEntry<SslContextBuilder, TrustManager>
      getSslContextBuilderAndExtendedX509TrustManager(
          CertificateValidationContext certificateValidationContext)
              throws CertificateException, IOException, CertStoreException {
    SslContextBuilder sslContextBuilder = GrpcSslContexts.forClient();
    XdsTrustManagerFactory trustManagerFactory;
    if (rootCertInstance != null) {
      if (savedSpiffeTrustMap != null) {
        trustManagerFactory = new XdsTrustManagerFactory(
            savedSpiffeTrustMap,
            certificateValidationContext, sniForSanMatching);
        sslContextBuilder = sslContextBuilder.trustManager(trustManagerFactory);
      } else {
        trustManagerFactory = new XdsTrustManagerFactory(
            savedTrustedRoots.toArray(new X509Certificate[0]),
            certificateValidationContext, sniForSanMatching);
        sslContextBuilder = sslContextBuilder.trustManager(trustManagerFactory);
      }
    } else {
      try {
        trustManagerFactory = new XdsTrustManagerFactory(
            getX509CertificatesFromSystemTrustStore(),
            certificateValidationContext, sniForSanMatching);
        sslContextBuilder = sslContextBuilder.trustManager(trustManagerFactory);
      } catch (KeyStoreException | NoSuchAlgorithmException e) {
        throw new CertStoreException(e);
      }
    }
    if (isMtls()) {
      sslContextBuilder.keyManager(savedKey, savedCertChain);
    }
    return new AbstractMap.SimpleImmutableEntry<>(sslContextBuilder,
        io.grpc.internal.CertificateUtils.getX509ExtendedTrustManager(
            Arrays.asList(trustManagerFactory.getTrustManagers())));
  }

  private X509Certificate[] getX509CertificatesFromSystemTrustStore()
      throws KeyStoreException, NoSuchAlgorithmException {
    TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(
        TrustManagerFactory.getDefaultAlgorithm());
    trustManagerFactory.init((KeyStore) null);

    List<TrustManager> trustManagers = Arrays.asList(trustManagerFactory.getTrustManagers());
    List<X509Certificate> rootCerts = trustManagers.stream()
        .filter(X509TrustManager.class::isInstance)
        .map(X509TrustManager.class::cast)
        .map(trustManager -> Arrays.asList(trustManager.getAcceptedIssuers()))
        .flatMap(Collection::stream)
        .collect(Collectors.toList());
    return rootCerts.toArray(new X509Certificate[rootCerts.size()]);
  }
}
