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

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import io.envoyproxy.envoy.api.v2.core.Node;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.CertificateValidationContext;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.SdsSecretConfig;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.Secret;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.TlsCertificate;
import io.grpc.xds.EnvoyServerProtoData.BaseTlsContext;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;

/** Base class for  SdsClientSslContextProvider and SdsServerSslContextProvider. */
abstract class SdsSslContextProvider extends DynamicSslContextProvider implements
    SdsClient.SecretWatcher {

  private static final Logger logger = Logger.getLogger(SdsSslContextProvider.class.getName());

  @Nullable private final SdsClient certSdsClient;
  @Nullable private final SdsClient validationContextSdsClient;
  @Nullable private final SdsSecretConfig certSdsConfig;
  @Nullable private final SdsSecretConfig validationContextSdsConfig;
  @Nullable protected TlsCertificate tlsCertificate;
  @Nullable private CertificateValidationContext certificateValidationContext;

  protected SdsSslContextProvider(
      Node node,
      SdsSecretConfig certSdsConfig,
      SdsSecretConfig validationContextSdsConfig,
      CertificateValidationContext staticCertValidationContext,
      Executor watcherExecutor,
      Executor channelExecutor,
      BaseTlsContext tlsContext) {
    super(tlsContext, staticCertValidationContext);
    this.certSdsConfig = certSdsConfig;
    this.validationContextSdsConfig = validationContextSdsConfig;
    if (certSdsConfig != null && certSdsConfig.isInitialized()) {
      certSdsClient =
          SdsClient.Factory.createSdsClient(certSdsConfig, node, watcherExecutor, channelExecutor);
      certSdsClient.start();
      certSdsClient.watchSecret(this);
    } else {
      certSdsClient = null;
    }
    if (validationContextSdsConfig != null && validationContextSdsConfig.isInitialized()) {
      validationContextSdsClient =
          SdsClient.Factory.createSdsClient(
              validationContextSdsConfig, node, watcherExecutor, channelExecutor);
      validationContextSdsClient.start();
      validationContextSdsClient.watchSecret(this);
    } else {
      validationContextSdsClient = null;
    }
  }

  @Override
  public final synchronized void onSecretChanged(Secret secretUpdate) {
    checkNotNull(secretUpdate);
    if (secretUpdate.hasTlsCertificate()) {
      checkState(
          secretUpdate.getName().equals(certSdsConfig.getName()), "tlsCert names don't match");
      logger.log(Level.FINEST, "onSecretChanged certSdsConfig.name={0}", certSdsConfig.getName());
      tlsCertificate = secretUpdate.getTlsCertificate();
      if (certificateValidationContext != null || validationContextSdsConfig == null) {
        updateSslContext();
      }
    } else if (secretUpdate.hasValidationContext()) {
      checkState(
          secretUpdate.getName().equals(validationContextSdsConfig.getName()),
          "validationContext names don't match");
      logger.log(
          Level.FINEST,
          "onSecretChanged validationContextSdsConfig.name={0}",
          validationContextSdsConfig.getName());
      certificateValidationContext = secretUpdate.getValidationContext();
      if (tlsCertificate != null || certSdsConfig == null) {
        updateSslContext();
      }
    } else {
      throw new UnsupportedOperationException(
          "Unexpected secret type:" + secretUpdate.getTypeCase());
    }
  }

  @Override
  protected final CertificateValidationContext generateCertificateValidationContext() {
    if (staticCertificateValidationContext == null) {
      return certificateValidationContext;
    }
    if (certificateValidationContext == null) {
      return staticCertificateValidationContext;
    }
    CertificateValidationContext.Builder localCertContextBuilder =
        certificateValidationContext.toBuilder();
    return localCertContextBuilder.mergeFrom(staticCertificateValidationContext).build();
  }

  @Override
  public final void close() {
    if (certSdsClient != null) {
      certSdsClient.cancelSecretWatch(this);
      certSdsClient.shutdown();
    }
    if (validationContextSdsClient != null) {
      validationContextSdsClient.cancelSecretWatch(this);
      validationContextSdsClient.shutdown();
    }
  }
}
