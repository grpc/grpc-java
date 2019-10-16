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

package io.grpc.xds.sds;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.annotations.VisibleForTesting;
import io.envoyproxy.envoy.api.v2.auth.CertificateValidationContext;
import io.envoyproxy.envoy.api.v2.auth.TlsCertificate;
import io.envoyproxy.envoy.api.v2.core.DataSource.SpecifierCase;
import io.grpc.netty.GrpcSslContexts;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import java.io.File;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;
import javax.net.ssl.SSLException;

/**
 * An SslContext provider that uses file-based secrets (secret volume). Used for both server and
 * client SslContexts
 */
final class SslContextSecretVolumeSecretProvider implements SecretProvider<SslContext> {

  private static final Logger logger =
      Logger.getLogger(SslContextSecretVolumeSecretProvider.class.getName());

  private final boolean server;
  @Nullable private final String privateKey;
  @Nullable private final String privateKeyPassword;
  @Nullable private final String certificateChain;
  @Nullable private final String trustedCa;

  private SslContextSecretVolumeSecretProvider(
      @Nullable String privateKey,
      @Nullable String privateKeyPassword,
      @Nullable String certificateChain,
      @Nullable String trustedCa,
      boolean server) {
    this.privateKey = privateKey;
    this.privateKeyPassword = privateKeyPassword;
    this.certificateChain = certificateChain;
    this.trustedCa = trustedCa;
    this.server = server;
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

  static SslContextSecretVolumeSecretProvider getProviderForServer(
      TlsCertificate tlsCertificate, @Nullable CertificateValidationContext certContext) {
    // first validate
    validateTlsCertificate(tlsCertificate, /* optional= */ false);
    // certContext exists in case of mTLS, else null for a server
    if (certContext != null) {
      certContext = validateCertificateContext(certContext, /* optional= */ true);
    }
    String privateKeyPassword =
        tlsCertificate.hasPassword() ? tlsCertificate.getPassword().getInlineString() : null;
    String trustedCa = certContext != null ? certContext.getTrustedCa().getFilename() : null;
    return new SslContextSecretVolumeSecretProvider(
        tlsCertificate.getPrivateKey().getFilename(),
        privateKeyPassword,
        tlsCertificate.getCertificateChain().getFilename(),
        trustedCa,
        /* server= */ true);
  }

  static SslContextSecretVolumeSecretProvider getProviderForClient(
      @Nullable TlsCertificate tlsCertificate, CertificateValidationContext certContext) {
    // first validate
    validateCertificateContext(certContext, /* optional= */ false);
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
    return new SslContextSecretVolumeSecretProvider(
        privateKey,
        privateKeyPassword,
        certificateChain,
        certContext.getTrustedCa().getFilename(),
        /* server= */ false);
  }

  @Override
  public void addCallback(final Callback<SslContext> callback, Executor executor) {
    checkNotNull(callback, "callback");
    checkNotNull(executor, "executor");
    executor.execute(
        new Runnable() {
          @Override
          public void run() {
            // as per the contract we will read the current secrets on disk
            // this involves I/O which can potentially block the executor or event loop
            SslContext sslContext = null;
            try {
              sslContext = buildSslContextFromSecrets();
              try {
                callback.updateSecret(sslContext);
              } catch (Throwable t) {
                logger.log(Level.SEVERE, "Exception from callback.updateSecret", t);
              }
            } catch (Throwable e) {
              logger.log(Level.SEVERE, "Exception from buildSslContextFromSecrets", e);
              callback.onException(e);
            }
          }
        });
  }

  @VisibleForTesting
  SslContext buildSslContextFromSecrets() throws SSLException {
    SslContextBuilder sslContextBuilder;
    if (server) {
      sslContextBuilder =
          GrpcSslContexts.forServer(
              new File(certificateChain), new File(privateKey), privateKeyPassword);
      if (trustedCa != null) {
        sslContextBuilder.trustManager(new File(trustedCa));
      }
    } else {
      sslContextBuilder = GrpcSslContexts.forClient().trustManager(new File(trustedCa));
      if (privateKey != null && certificateChain != null) {
        sslContextBuilder.keyManager(
            new File(certificateChain), new File(privateKey), privateKeyPassword);
      }
    }
    return sslContextBuilder.build();
  }
}
