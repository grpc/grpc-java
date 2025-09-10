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

package io.grpc.xds.internal.security;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import io.grpc.netty.GrpcSslContexts;
import io.grpc.xds.EnvoyServerProtoData.BaseTlsContext;
import io.grpc.xds.EnvoyServerProtoData.DownstreamTlsContext;
import io.grpc.xds.EnvoyServerProtoData.UpstreamTlsContext;
import io.grpc.xds.TlsContextManager;
import io.netty.handler.ssl.SslContext;

import java.util.HashSet;
import java.util.Objects;
import javax.net.ssl.SSLException;
import java.util.Set;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Enables Client or server side to initialize this object with the received {@link BaseTlsContext}
 * and communicate it to the consumer i.e. {@link SecurityProtocolNegotiators}
 * to lazily evaluate the {@link SslContextProvider}. The supplier prevents credentials leakage in
 * cases where the user is not using xDS credentials but the client/server contains a non-default
 * {@link BaseTlsContext}.
 */
public final class SslContextProviderSupplier implements Closeable {

  private final BaseTlsContext tlsContext;
  private final TlsContextManager tlsContextManager;
  private final Set<String> snisSentByClients = new HashSet<>();
  private SslContextProvider sslContextProvider;
  private boolean shutdown;

  public SslContextProviderSupplier(
      BaseTlsContext tlsContext, TlsContextManager tlsContextManager) {
    this.tlsContext = checkNotNull(tlsContext, "tlsContext");
    this.tlsContextManager = checkNotNull(tlsContextManager, "tlsContextManager");
  }

  public BaseTlsContext getTlsContext() {
    return tlsContext;
  }

  /** Updates SslContext via the passed callback. */
  public synchronized void updateSslContext(final SslContextProvider.Callback callback, String sni) {
    checkNotNull(callback, "callback");
    try {
      if (!shutdown) {
        if (sslContextProvider == null) {
          sslContextProvider = getSslContextProvider(sni);
        }
      }

      // we want to increment the ref-count so call findOrCreate again...
      final SslContextProvider toRelease = getSslContextProvider();
      // When using system root certs on client side, SslContext updates via CertificateProvider is
      // only required if Mtls is also enabled, i.e. tlsContext has a cert provider instance.
      if (tlsContext instanceof UpstreamTlsContext
          && !CommonTlsContextUtil.hasCertProviderInstance(tlsContext.getCommonTlsContext())
          && CommonTlsContextUtil.isUsingSystemRootCerts(tlsContext.getCommonTlsContext())) {
        callback.getExecutor().execute(() -> {
          try {
            callback.updateSslContext(GrpcSslContexts.forClient().build());
            releaseSslContextProvider(toRelease);
          } catch (SSLException e) {
            callback.onException(e);
          }
        });
      } else {
        toRelease.addCallback(
            new SslContextProvider.Callback(callback.getExecutor()) {

              @Override
              public void updateSslContext(SslContext sslContext) {
                callback.updateSslContext(sslContext);
                releaseSslContextProvider(toRelease);
              }

              @Override
              public void onException(Throwable throwable) {
                callback.onException(throwable);
                releaseSslContextProvider(toRelease);
              }
            });
      }
    } catch (final Throwable throwable) {
      callback.getExecutor().execute(new Runnable() {
        @Override
        public void run() {
          callback.onException(throwable);
        }
      });
    }
  }

  private void releaseSslContextProvider(SslContextProvider toRelease, String sni) {
    if (tlsContext instanceof UpstreamTlsContext) {
      tlsContextManager.releaseClientSslContextProvider(toRelease, sni);
      snisSentByClients.remove(sni);
    } else {
      tlsContextManager.releaseServerSslContextProvider(toRelease);
    }
  }

  private SslContextProvider getSslContextProvider(String sni) {
    if (tlsContext instanceof UpstreamTlsContext) {
      snisSentByClients.add(sni);
      return tlsContextManager.findOrCreateClientSslContextProvider((UpstreamTlsContext) tlsContext, sni);
    }
    return tlsContextManager.findOrCreateServerSslContextProvider((DownstreamTlsContext) tlsContext);
  }

  @VisibleForTesting public boolean isShutdown() {
    return shutdown;
  }

  /** Called by consumer when tlsContext changes. */
  @Override
  public synchronized void close() {
    if (sslContextProvider != null) {
      if (tlsContext instanceof UpstreamTlsContext) {
        for (String sni: snisSentByClients) {
          tlsContextManager.releaseClientSslContextProvider(sslContextProvider, sni);
        }
      } else {
        tlsContextManager.releaseServerSslContextProvider(sslContextProvider);
      }
    }
    sslContextProvider = null;
    shutdown = true;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    SslContextProviderSupplier that = (SslContextProviderSupplier) o;
    return Objects.equals(tlsContext, that.tlsContext)
        && Objects.equals(tlsContextManager, that.tlsContextManager);
  }

  @Override
  public int hashCode() {
    return Objects.hash(tlsContext, tlsContextManager);
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
        .add("tlsContext", tlsContext)
        .add("tlsContextManager", tlsContextManager)
        .add("sslContextProvider", sslContextProvider)
        .add("shutdown", shutdown)
        .toString();
  }
}
