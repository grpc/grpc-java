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

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import io.envoyproxy.envoy.api.v2.auth.CertificateValidationContext;
import io.envoyproxy.envoy.api.v2.auth.CommonTlsContext;
import io.envoyproxy.envoy.api.v2.auth.DownstreamTlsContext;
import io.envoyproxy.envoy.api.v2.auth.SdsSecretConfig;
import io.envoyproxy.envoy.api.v2.auth.Secret;
import io.envoyproxy.envoy.api.v2.auth.TlsCertificate;
import io.envoyproxy.envoy.api.v2.auth.UpstreamTlsContext;
import io.envoyproxy.envoy.api.v2.core.Node;
import io.grpc.Status;
import io.grpc.netty.GrpcSslContexts;
import io.grpc.xds.sds.trust.SdsTrustManagerFactory;
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

/**
 * An SslContext provider that uses SDS to fetch secrets. Used for both server and client
 * SslContexts
 */
final class SdsSslContextProvider<K> extends SslContextProvider<K>
    implements SdsClient.SecretWatcher {

  private static final Logger logger = Logger.getLogger(SdsSslContextProvider.class.getName());

  @Nullable private final SdsClient certSdsClient;
  @Nullable private final SdsClient validationContextSdsClient;
  @Nullable private final SdsSecretConfig certSdsConfig;
  @Nullable private final SdsSecretConfig validationContextSdsConfig;
  private final List<CallbackPair> pendingCallbacks = new ArrayList<>();
  @Nullable private TlsCertificate tlsCertificate;
  @Nullable private CertificateValidationContext certificateValidationContext;
  @Nullable private SslContext sslContext;

  private SdsSslContextProvider(
      Node node,
      SdsSecretConfig certSdsConfig,
      SdsSecretConfig validationContextSdsConfig,
      Executor watcherExecutor,
      Executor channelExecutor,
      boolean server,
      K source) {
    super(source, server);
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

  static SdsSslContextProvider<UpstreamTlsContext> getProviderForClient(
      UpstreamTlsContext upstreamTlsContext,
      Node node,
      Executor watcherExecutor,
      Executor channelExecutor) {
    checkNotNull(upstreamTlsContext, "upstreamTlsContext");
    CommonTlsContext commonTlsContext = upstreamTlsContext.getCommonTlsContext();
    SdsSecretConfig validationContextSdsConfig =
        commonTlsContext.getValidationContextSdsSecretConfig();

    SdsSecretConfig certSdsConfig = null;
    if (commonTlsContext.getTlsCertificateSdsSecretConfigsCount() > 0) {
      certSdsConfig = commonTlsContext.getTlsCertificateSdsSecretConfigs(0);
    }
    return new SdsSslContextProvider<>(
        node,
        certSdsConfig,
        validationContextSdsConfig,
        watcherExecutor,
        channelExecutor,
        false,
        upstreamTlsContext);
  }

  static SdsSslContextProvider<DownstreamTlsContext> getProviderForServer(
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
    return new SdsSslContextProvider<>(
        node,
        certSdsConfig,
        validationContextSdsConfig,
        watcherExecutor,
        channelExecutor,
        true,
        downstreamTlsContext);
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
      tlsCertificate = secretUpdate.getTlsCertificate();
      if (certificateValidationContext != null || validationContextSdsConfig == null) {
        updateSslContext();
      }
    } else if (secretUpdate.hasValidationContext()) {
      checkState(
          secretUpdate.getName().equals(validationContextSdsConfig.getName()),
          "validationContext names don't match");
      certificateValidationContext = secretUpdate.getValidationContext();
      if (tlsCertificate != null || certSdsConfig == null) {
        updateSslContext();
      }
    } else {
      throw new UnsupportedOperationException(
          "Unexpected secret type:" + secretUpdate.getTypeCase());
    }
  }

  // this gets called only when requested secrets are ready...
  private void updateSslContext() {
    try {
      SslContextBuilder sslContextBuilder;
      if (server) {
        sslContextBuilder =
            GrpcSslContexts.forServer(
                tlsCertificate.getCertificateChain().getInlineBytes().newInput(),
                tlsCertificate.getPrivateKey().getInlineBytes().newInput(),
                tlsCertificate.hasPassword()
                    ? tlsCertificate.getPassword().getInlineString()
                    : null);
        if (certificateValidationContext != null) {
          sslContextBuilder.trustManager(new SdsTrustManagerFactory(certificateValidationContext));
        }
      } else {
        sslContextBuilder =
            GrpcSslContexts.forClient()
                .trustManager(new SdsTrustManagerFactory(certificateValidationContext));
        if (tlsCertificate != null) {
          sslContextBuilder.keyManager(
              tlsCertificate.getCertificateChain().getInlineBytes().newInput(),
              tlsCertificate.getPrivateKey().getInlineBytes().newInput(),
              tlsCertificate.hasPassword() ? tlsCertificate.getPassword().getInlineString() : null);
        }
      }
      SslContext sslContextCopy = sslContextBuilder.build();
      sslContext = sslContextCopy;
      makePendingCallbacks(sslContextCopy);
    } catch (CertificateException | IOException | CertStoreException e) {
      logger.log(Level.SEVERE, "exception in updateSslContext", e);
    }
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
  void close() {
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
