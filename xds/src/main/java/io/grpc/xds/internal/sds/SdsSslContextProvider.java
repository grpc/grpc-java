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
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.CommonTlsContext;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.SdsSecretConfig;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.Secret;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.TlsCertificate;
import io.grpc.Status;
import io.grpc.xds.EnvoyServerProtoData.BaseTlsContext;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import java.io.IOException;
import java.security.cert.CertStoreException;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;

/** Base class for  SdsClientSslContextProvider and SdsServerSslContextProvider. */
abstract class SdsSslContextProvider extends SslContextProvider implements SdsClient.SecretWatcher {

  private static final Logger logger = Logger.getLogger(SdsSslContextProvider.class.getName());

  @Nullable private final SdsClient certSdsClient;
  @Nullable private final SdsClient validationContextSdsClient;
  @Nullable private final SdsSecretConfig certSdsConfig;
  @Nullable private final SdsSecretConfig validationContextSdsConfig;
  @Nullable private final CertificateValidationContext staticCertificateValidationContext;
  private final List<CallbackPair> pendingCallbacks = new ArrayList<>();
  @Nullable protected TlsCertificate tlsCertificate;
  @Nullable private CertificateValidationContext certificateValidationContext;
  @Nullable private SslContext sslContext;

  SdsSslContextProvider(
      Node node,
      SdsSecretConfig certSdsConfig,
      SdsSecretConfig validationContextSdsConfig,
      CertificateValidationContext staticCertValidationContext,
      Executor watcherExecutor,
      Executor channelExecutor,
      BaseTlsContext tlsContext) {
    super(tlsContext);
    this.certSdsConfig = certSdsConfig;
    this.validationContextSdsConfig = validationContextSdsConfig;
    this.staticCertificateValidationContext = staticCertValidationContext;
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
  public void addCallback(Callback callback, Executor executor) {
    checkNotNull(callback, "callback");
    checkNotNull(executor, "executor");
    // if there is a computed sslContext just send it
    SslContext sslContextCopy = sslContext;
    if (sslContextCopy != null) {
      callPerformCallback(callback, executor, sslContextCopy);
    } else {
      synchronized (pendingCallbacks) {
        pendingCallbacks.add(new CallbackPair(callback, executor));
      }
    }
  }

  private void callPerformCallback(
      Callback callback, Executor executor, final SslContext sslContextCopy) {
    performCallback(
        new SslContextGetter() {
          @Override
          public SslContext get() {
            return sslContextCopy;
          }
        },
        callback,
        executor);
  }

  @Override
  public synchronized void onSecretChanged(Secret secretUpdate) {
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

  /** Gets a server or client side SslContextBuilder. */
  abstract SslContextBuilder getSslContextBuilder(
      CertificateValidationContext localCertValidationContext)
      throws CertificateException, IOException, CertStoreException;

  // this gets called only when requested secrets are ready...
  private void updateSslContext() {
    try {
      CertificateValidationContext localCertValidationContext = mergeStaticAndDynamicCertContexts();
      SslContextBuilder sslContextBuilder = getSslContextBuilder(localCertValidationContext);
      CommonTlsContext commonTlsContext = getCommonTlsContext();
      if (commonTlsContext != null && commonTlsContext.getAlpnProtocolsCount() > 0) {
        List<String> alpnList = commonTlsContext.getAlpnProtocolsList();
        ApplicationProtocolConfig apn = new ApplicationProtocolConfig(
            ApplicationProtocolConfig.Protocol.ALPN,
            ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
            ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
            alpnList);
        sslContextBuilder.applicationProtocolConfig(apn);
      }
      SslContext sslContextCopy = sslContextBuilder.build();
      sslContext = sslContextCopy;
      makePendingCallbacks(sslContextCopy);
    } catch (CertificateException | IOException | CertStoreException e) {
      logger.log(Level.SEVERE, "exception in updateSslContext", e);
    }
  }

  private CertificateValidationContext mergeStaticAndDynamicCertContexts() {
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

  private void makePendingCallbacks(SslContext sslContextCopy) {
    synchronized (pendingCallbacks) {
      for (CallbackPair pair : pendingCallbacks) {
        callPerformCallback(pair.callback, pair.executor, sslContextCopy);
      }
      pendingCallbacks.clear();
    }
  }

  @Override
  public void onError(Status error) {
    synchronized (pendingCallbacks) {
      for (CallbackPair callbackPair : pendingCallbacks) {
        callbackPair.callback.onException(error.asException());
      }
      pendingCallbacks.clear();
    }
  }

  @Override
  public void close() {
    if (certSdsClient != null) {
      certSdsClient.cancelSecretWatch(this);
      certSdsClient.shutdown();
    }
    if (validationContextSdsClient != null) {
      validationContextSdsClient.cancelSecretWatch(this);
      validationContextSdsClient.shutdown();
    }
  }

  private static class CallbackPair {
    private final Callback callback;
    private final Executor executor;

    private CallbackPair(Callback callback, Executor executor) {
      this.callback = callback;
      this.executor = executor;
    }
  }
}
