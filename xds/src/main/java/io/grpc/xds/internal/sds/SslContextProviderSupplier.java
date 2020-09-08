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

import io.grpc.xds.EnvoyServerProtoData.UpstreamTlsContext;
import io.netty.handler.ssl.SslContext;

/**
 * Enables the CDS policy to initialize this object with the received {@link UpstreamTlsContext} &
 * communicate it to the consumer i.e. {@link SdsProtocolNegotiators.ClientSdsProtocolNegotiator}
 * to lazily evaluate the {@link SslContextProvider}. The supplier prevents credentials leakage in
 * cases where the user is not using xDS credentials but the CDS policy contains a non-default
 * {@link UpstreamTlsContext}.
 */
public final class SslContextProviderSupplier implements Closeable {

  private final UpstreamTlsContext upstreamTlsContext;
  private final TlsContextManager tlsContextManager;
  private SslContextProvider sslContextProvider;
  private boolean shutdown;

  public SslContextProviderSupplier(
      UpstreamTlsContext upstreamTlsContext, TlsContextManager tlsContextManager) {
    this.upstreamTlsContext = upstreamTlsContext;
    this.tlsContextManager = tlsContextManager;
  }

  public UpstreamTlsContext getUpstreamTlsContext() {
    return upstreamTlsContext;
  }

  /** Updates SslContext via the passed callback. */
  public synchronized void updateSslContext(final SslContextProvider.Callback callback) {
    checkNotNull(callback, "callback");
    checkState(!shutdown, "Supplier is shutdown!");
    if (sslContextProvider == null) {
      sslContextProvider =
          tlsContextManager.findOrCreateClientSslContextProvider(upstreamTlsContext);
    }
    // we want to increment the ref-count so call findOrCreate again...
    final SslContextProvider toRelease =
        tlsContextManager.findOrCreateClientSslContextProvider(upstreamTlsContext);
    sslContextProvider.addCallback(
        new SslContextProvider.Callback(callback.getExecutor()) {

          @Override
          public void updateSecret(SslContext sslContext) {
            callback.updateSecret(sslContext);
            tlsContextManager.releaseClientSslContextProvider(toRelease);
          }

          @Override
          public void onException(Throwable throwable) {
            callback.onException(throwable);
            tlsContextManager.releaseClientSslContextProvider(toRelease);
          }
        });
  }

  /** Called by {@link io.grpc.xds.CdsLoadBalancer} when upstreamTlsContext changes. */
  @Override
  public synchronized void close() {
    if (sslContextProvider != null) {
      tlsContextManager.releaseClientSslContextProvider(sslContextProvider);
    }
    shutdown = true;
  }
}
