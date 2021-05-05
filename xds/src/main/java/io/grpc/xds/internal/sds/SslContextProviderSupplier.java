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

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import io.grpc.xds.EnvoyServerProtoData.BaseTlsContext;
import io.grpc.xds.EnvoyServerProtoData.DownstreamTlsContext;
import io.grpc.xds.EnvoyServerProtoData.UpstreamTlsContext;
import io.netty.handler.ssl.SslContext;

/**
 * Enables Client or server side to initialize this object with the received {@link BaseTlsContext}
 * and communicate it to the consumer i.e. {@link SdsProtocolNegotiators}
 * to lazily evaluate the {@link SslContextProvider}. The supplier prevents credentials leakage in
 * cases where the user is not using xDS credentials but the client/server contains a non-default
 * {@link BaseTlsContext}.
 */
public final class SslContextProviderSupplier implements Closeable {

  private final BaseTlsContext tlsContext;
  private final TlsContextManager tlsContextManager;
  private SslContextProvider sslContextProvider;
  private boolean shutdown;

  public SslContextProviderSupplier(
      BaseTlsContext tlsContext, TlsContextManager tlsContextManager) {
    this.tlsContext = tlsContext;
    this.tlsContextManager = tlsContextManager;
  }

  public BaseTlsContext getTlsContext() {
    return tlsContext;
  }

  /** Updates SslContext via the passed callback. */
  public synchronized void updateSslContext(final SslContextProvider.Callback callback) {
    checkNotNull(callback, "callback");
    checkState(!shutdown, "Supplier is shutdown!");
    if (sslContextProvider == null) {
      sslContextProvider = getSslContextProvider();
    }
    // we want to increment the ref-count so call findOrCreate again...
    final SslContextProvider toRelease = getSslContextProvider();
    sslContextProvider.addCallback(
        new SslContextProvider.Callback(callback.getExecutor()) {

          @Override
          public void updateSecret(SslContext sslContext) {
            callback.updateSecret(sslContext);
            releaseSslContextProvider(toRelease);
          }

          @Override
          public void onException(Throwable throwable) {
            callback.onException(throwable);
            releaseSslContextProvider(toRelease);
          }
        });
  }

  private void releaseSslContextProvider(SslContextProvider toRelease) {
    if (tlsContext instanceof UpstreamTlsContext) {
      tlsContextManager.releaseClientSslContextProvider(toRelease);
    } else {
      tlsContextManager.releaseServerSslContextProvider(toRelease);
    }
  }

  private SslContextProvider getSslContextProvider() {
    return tlsContext instanceof UpstreamTlsContext
        ? tlsContextManager.findOrCreateClientSslContextProvider((UpstreamTlsContext) tlsContext)
        : tlsContextManager.findOrCreateServerSslContextProvider((DownstreamTlsContext) tlsContext);
  }

  /** Called by consumer when tlsContext changes. */
  @Override
  public synchronized void close() {
    if (tlsContext instanceof UpstreamTlsContext) {
      tlsContextManager.releaseClientSslContextProvider(sslContextProvider);
    } else {
      tlsContextManager.releaseServerSslContextProvider(sslContextProvider);
    }
    shutdown = true;
  }
}
