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
import io.grpc.xds.internal.security.trust.XdsTrustManagerFactory;
import io.netty.handler.ssl.SslContextBuilder;

import java.io.IOException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertStoreException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import javax.net.ssl.SSLException;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/** A client SslContext provider using CertificateProviderInstance to fetch secrets. */
final class CertProviderClientSslContextProvider extends CertProviderSslContextProvider {

  CertProviderClientSslContextProvider(
      Node node,
      @Nullable Map<String, CertificateProviderInfo> certProviders,
      CommonTlsContext.CertificateProviderInstance certInstance,
      CommonTlsContext.CertificateProviderInstance rootCertInstance,
      CertificateValidationContext staticCertValidationContext,
      UpstreamTlsContext upstreamTlsContext,
      CertificateProviderStore certificateProviderStore) {
    super(
        node,
        certProviders,
        certInstance,
        rootCertInstance,
        staticCertValidationContext,
        upstreamTlsContext,
        certificateProviderStore);
    // Null rootCertInstance implies hasSystemRootCerts because of the check in
    // CertProviderClientSslContextProviderFactory.
    if (rootCertInstance == null && !isMtls()) {
      try {
        // Instantiate sslContext so that addCallback will immediately update the callback with
        // the SslContext.
        sslContext = getSslContextBuilder(staticCertificateValidationContext).build();
      } catch (CertStoreException | CertificateException | IOException e) {
        throw new RuntimeException(e);
      }
    }
  }

  @Override
  protected final SslContextBuilder getSslContextBuilder(
          CertificateValidationContext certificateValidationContextdationContext)
      throws CertificateException, IOException, CertStoreException {
    SslContextBuilder sslContextBuilder = GrpcSslContexts.forClient();
    if (rootCertInstance != null) {
      if (savedSpiffeTrustMap != null) {
        sslContextBuilder = sslContextBuilder.trustManager(
          new XdsTrustManagerFactory(
              savedSpiffeTrustMap,
              certificateValidationContextdationContext));
      } else {
        sslContextBuilder = sslContextBuilder.trustManager(
            new XdsTrustManagerFactory(
                savedTrustedRoots.toArray(new X509Certificate[0]),
                certificateValidationContextdationContext));
      }
    } else {
      try {
        sslContextBuilder = sslContextBuilder.trustManager(
            new XdsTrustManagerFactory(
                getX509CertificatesFromSystemTrustStore(),
                certificateValidationContextdationContext));
      } catch (KeyStoreException | NoSuchAlgorithmException e) {
        throw new CertStoreException(e);
      }
    }
    if (isMtls()) {
      sslContextBuilder.keyManager(savedKey, savedCertChain);
    }
    return sslContextBuilder;
  }

  private X509Certificate[] getX509CertificatesFromSystemTrustStore() throws KeyStoreException, CertificateException, IOException, NoSuchAlgorithmException {
    TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
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
