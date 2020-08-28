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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static io.grpc.xds.internal.sds.CommonTlsContextUtil.getCertificateValidationContext;
import static io.grpc.xds.internal.sds.CommonTlsContextUtil.validateCertificateContext;
import static io.grpc.xds.internal.sds.CommonTlsContextUtil.validateTlsCertificate;

import com.google.common.annotations.VisibleForTesting;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.CertificateValidationContext;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.CommonTlsContext;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.TlsCertificate;
import io.grpc.netty.GrpcSslContexts;
import io.grpc.xds.EnvoyServerProtoData.UpstreamTlsContext;
import io.grpc.xds.internal.sds.trust.SdsTrustManagerFactory;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import java.io.File;
import java.io.IOException;
import java.security.cert.CertStoreException;
import java.security.cert.CertificateException;
import javax.annotation.Nullable;

/** A client SslContext provider that uses file-based secrets (secret volume). */
final class SecretVolumeClientSslContextProvider extends SslContextProvider {

  @Nullable private final String privateKey;
  @Nullable private final String privateKeyPassword;
  @Nullable private final String certificateChain;
  @Nullable private final CertificateValidationContext certContext;

  private SecretVolumeClientSslContextProvider(
      @Nullable String privateKey,
      @Nullable String privateKeyPassword,
      @Nullable String certificateChain,
      @Nullable CertificateValidationContext certContext,
      UpstreamTlsContext upstreamTlsContext) {
    super(upstreamTlsContext);
    this.privateKey = privateKey;
    this.privateKeyPassword = privateKeyPassword;
    this.certificateChain = certificateChain;
    this.certContext = certContext;
  }

  static SecretVolumeClientSslContextProvider getProvider(UpstreamTlsContext upstreamTlsContext) {
    checkNotNull(upstreamTlsContext, "upstreamTlsContext");
    CommonTlsContext commonTlsContext = upstreamTlsContext.getCommonTlsContext();
    checkArgument(
        commonTlsContext.getTlsCertificateSdsSecretConfigsCount() == 0,
        "unexpected TlsCertificateSdsSecretConfigs");
    CertificateValidationContext certificateValidationContext =
        getCertificateValidationContext(commonTlsContext);
    // first validate
    validateCertificateContext(certificateValidationContext, /* optional= */ false);
    TlsCertificate tlsCertificate = null;
    if (commonTlsContext.getTlsCertificatesCount() > 0) {
      tlsCertificate = commonTlsContext.getTlsCertificates(0);
    }
    // tlsCertificate exists in case of mTLS, else null for a client
    if (tlsCertificate != null) {
      tlsCertificate = validateTlsCertificate(tlsCertificate, /* optional= */ true);
    }
    String privateKey = null;
    String privateKeyPassword = null;
    String certificateChain = null;
    if (tlsCertificate != null) {
      privateKey = tlsCertificate.getPrivateKey().getFilename();
      if (tlsCertificate.hasPassword()) {
        privateKeyPassword = tlsCertificate.getPassword().getInlineString();
      }
      certificateChain = tlsCertificate.getCertificateChain().getFilename();
    }
    return new SecretVolumeClientSslContextProvider(
        privateKey,
        privateKeyPassword,
        certificateChain,
        certificateValidationContext,
        upstreamTlsContext);
  }

  @Override
  public void addCallback(final Callback callback) {
    checkNotNull(callback, "callback");
    // as per the contract we will read the current secrets on disk
    // this involves I/O which can potentially block the executor
    performCallback(
        new SslContextGetter() {
          @Override
          public SslContext get() throws CertificateException, IOException, CertStoreException {
            return buildSslContextFromSecrets();
          }
        },
        callback
    );
  }

  @Override
  public void close() {}

  @VisibleForTesting
  SslContext buildSslContextFromSecrets()
      throws IOException, CertificateException, CertStoreException {
    SslContextBuilder sslContextBuilder =
        GrpcSslContexts.forClient().trustManager(new SdsTrustManagerFactory(certContext));
    if (privateKey != null && certificateChain != null) {
      sslContextBuilder.keyManager(
          new File(certificateChain), new File(privateKey), privateKeyPassword);
    }
    return sslContextBuilder.build();
  }
}
