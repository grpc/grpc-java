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

package io.grpc.xds.internal.sds;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.google.common.annotations.VisibleForTesting;
import io.envoyproxy.envoy.api.v2.auth.CertificateValidationContext;
import io.envoyproxy.envoy.api.v2.auth.CommonTlsContext;
import io.envoyproxy.envoy.api.v2.auth.CommonTlsContext.ValidationContextTypeCase;
import io.envoyproxy.envoy.api.v2.auth.DownstreamTlsContext;
import io.envoyproxy.envoy.api.v2.auth.TlsCertificate;
import io.envoyproxy.envoy.api.v2.auth.UpstreamTlsContext;
import io.envoyproxy.envoy.api.v2.core.DataSource.SpecifierCase;
import io.grpc.netty.GrpcSslContexts;
import io.grpc.xds.internal.sds.trust.SdsTrustManagerFactory;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import java.io.File;
import java.io.IOException;
import java.security.cert.CertStoreException;
import java.security.cert.CertificateException;
import java.util.concurrent.Executor;
import javax.annotation.Nullable;

/**
 * An SslContext provider that uses file-based secrets (secret volume). Used for both server and
 * client SslContexts
 */
final class SecretVolumeSslContextProvider<K> extends SslContextProvider<K> {

  @Nullable private final String privateKey;
  @Nullable private final String privateKeyPassword;
  @Nullable private final String certificateChain;
  @Nullable private final CertificateValidationContext certContext;

  private SecretVolumeSslContextProvider(
      @Nullable String privateKey,
      @Nullable String privateKeyPassword,
      @Nullable String certificateChain,
      @Nullable CertificateValidationContext certContext,
      boolean server,
      K source) {
    super(source, server);
    this.privateKey = privateKey;
    this.privateKeyPassword = privateKeyPassword;
    this.certificateChain = certificateChain;
    this.certContext = certContext;
  }

  @VisibleForTesting
  @Nullable
  static CertificateValidationContext validateCertificateContext(
      @Nullable CertificateValidationContext certContext, boolean optional) {
    if (certContext == null || !certContext.hasTrustedCa()) {
      checkArgument(optional, "certContext is required");
      return null;
    }
    checkArgument(
        certContext.getTrustedCa().getSpecifierCase() == SpecifierCase.FILENAME,
        "filename expected");
    return certContext;
  }

  @VisibleForTesting
  @Nullable
  static TlsCertificate validateTlsCertificate(
      @Nullable TlsCertificate tlsCertificate, boolean optional) {
    if (tlsCertificate == null) {
      checkArgument(optional, "tlsCertificate is required");
      return null;
    }
    if (optional
        && (tlsCertificate.getPrivateKey().getSpecifierCase() == SpecifierCase.SPECIFIER_NOT_SET)
        && (tlsCertificate.getCertificateChain().getSpecifierCase()
            == SpecifierCase.SPECIFIER_NOT_SET)) {
      return null;
    }
    checkArgument(
        tlsCertificate.getPrivateKey().getSpecifierCase() == SpecifierCase.FILENAME,
        "filename expected");
    checkArgument(
        tlsCertificate.getCertificateChain().getSpecifierCase() == SpecifierCase.FILENAME,
        "filename expected");
    return tlsCertificate;
  }

  static SecretVolumeSslContextProvider<DownstreamTlsContext> getProviderForServer(
      DownstreamTlsContext downstreamTlsContext) {
    checkNotNull(downstreamTlsContext, "downstreamTlsContext");
    CommonTlsContext commonTlsContext = downstreamTlsContext.getCommonTlsContext();
    TlsCertificate tlsCertificate = null;
    if (commonTlsContext.getTlsCertificatesCount() > 0) {
      tlsCertificate = commonTlsContext.getTlsCertificates(0);
    }
    // first validate
    validateTlsCertificate(tlsCertificate, /* optional= */ false);
    CertificateValidationContext certificateValidationContext =
        getCertificateValidationContext(commonTlsContext);
    // certificateValidationContext exists in case of mTLS, else null for a server
    if (certificateValidationContext != null) {
      certificateValidationContext =
          validateCertificateContext(certificateValidationContext, /* optional= */ true);
    }
    String privateKeyPassword =
        tlsCertificate.hasPassword() ? tlsCertificate.getPassword().getInlineString() : null;
    return new SecretVolumeSslContextProvider<>(
        tlsCertificate.getPrivateKey().getFilename(),
        privateKeyPassword,
        tlsCertificate.getCertificateChain().getFilename(),
        certificateValidationContext,
        /* server= */ true,
        downstreamTlsContext);
  }

  static SecretVolumeSslContextProvider<UpstreamTlsContext> getProviderForClient(
      UpstreamTlsContext upstreamTlsContext) {
    checkNotNull(upstreamTlsContext, "upstreamTlsContext");
    CommonTlsContext commonTlsContext = upstreamTlsContext.getCommonTlsContext();
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
    return new SecretVolumeSslContextProvider<>(
        privateKey,
        privateKeyPassword,
        certificateChain,
        certificateValidationContext,
        /* server= */ false,
        upstreamTlsContext);
  }

  private static CertificateValidationContext getCertificateValidationContext(
      CommonTlsContext commonTlsContext) {
    checkNotNull(commonTlsContext, "commonTlsContext");
    ValidationContextTypeCase type = commonTlsContext.getValidationContextTypeCase();
    checkState(
        type == ValidationContextTypeCase.VALIDATION_CONTEXT
            || type == ValidationContextTypeCase.VALIDATIONCONTEXTTYPE_NOT_SET,
        "incorrect ValidationContextTypeCase");
    return type == ValidationContextTypeCase.VALIDATION_CONTEXT
        ? commonTlsContext.getValidationContext()
        : null;
  }

  @Override
  public void addCallback(final Callback callback, Executor executor) {
    checkNotNull(callback, "callback");
    checkNotNull(executor, "executor");
    // as per the contract we will read the current secrets on disk
    // this involves I/O which can potentially block the executor
    performCallback(
        new SslContextGetter() {
          @Override
          public SslContext get() throws CertificateException, IOException, CertStoreException {
            return buildSslContextFromSecrets();
          }
        },
        callback,
        executor);
  }

  @Override
  public void close() {}

  @VisibleForTesting
  SslContext buildSslContextFromSecrets()
      throws IOException, CertificateException, CertStoreException {
    SslContextBuilder sslContextBuilder;
    if (server) {
      sslContextBuilder =
          GrpcSslContexts.forServer(
              new File(certificateChain), new File(privateKey), privateKeyPassword);
      if (certContext != null) {
        sslContextBuilder.trustManager(new SdsTrustManagerFactory(certContext));
      }
    } else {
      sslContextBuilder =
          GrpcSslContexts.forClient().trustManager(new SdsTrustManagerFactory(certContext));
      if (privateKey != null && certificateChain != null) {
        sslContextBuilder.keyManager(
            new File(certificateChain), new File(privateKey), privateKeyPassword);
      }
    }
    return sslContextBuilder.build();
  }
}
