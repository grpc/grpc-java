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

import com.google.common.annotations.VisibleForTesting;
import io.envoyproxy.envoy.api.v2.auth.DownstreamTlsContext;
import io.envoyproxy.envoy.api.v2.auth.UpstreamTlsContext;
import io.grpc.xds.internal.sds.ReferenceCountingSslContextProviderMap.SslContextProviderFactory;

/**
 * Class to manage {@link SslContextProvider} objects created from inputs we get from xDS. Used by
 * gRPC-xds to access the SslContext's and is not public API. This manager manages the life-cycle of
 * {@link SslContextProvider} objects as shared resources via ref-counting as described in {@link
 * ReferenceCountingSslContextProviderMap}.
 */
public final class TlsContextManagerImpl implements TlsContextManager {

  private static TlsContextManagerImpl instance;

  private final ReferenceCountingSslContextProviderMap<UpstreamTlsContext> mapForClients;
  private final ReferenceCountingSslContextProviderMap<DownstreamTlsContext> mapForServers;

  private TlsContextManagerImpl() {
    this(new ClientSslContextProviderFactory(), new ServerSslContextProviderFactory());
  }

  @VisibleForTesting
  TlsContextManagerImpl(
      SslContextProviderFactory<UpstreamTlsContext> clientFactory,
      SslContextProviderFactory<DownstreamTlsContext> serverFactory) {
    checkNotNull(clientFactory, "clientFactory");
    checkNotNull(serverFactory, "serverFactory");
    mapForClients = new ReferenceCountingSslContextProviderMap<>(clientFactory);
    mapForServers = new ReferenceCountingSslContextProviderMap<>(serverFactory);
  }

  /** Gets the TlsContextManagerImpl singleton. */
  public static synchronized TlsContextManagerImpl getInstance() {
    if (instance == null) {
      instance = new TlsContextManagerImpl();
    }
    return instance;
  }

  @Override
  public SslContextProvider<DownstreamTlsContext> findOrCreateServerSslContextProvider(
      DownstreamTlsContext downstreamTlsContext) {
    checkNotNull(downstreamTlsContext, "downstreamTlsContext");
    return mapForServers.get(downstreamTlsContext);
  }

  @Override
  public SslContextProvider<UpstreamTlsContext> findOrCreateClientSslContextProvider(
      UpstreamTlsContext upstreamTlsContext) {
    checkNotNull(upstreamTlsContext, "upstreamTlsContext");
    return mapForClients.get(upstreamTlsContext);
  }

  @Override
  public SslContextProvider<UpstreamTlsContext> releaseClientSslContextProvider(
      SslContextProvider<UpstreamTlsContext> sslContextProvider) {
    checkNotNull(sslContextProvider, "sslContextProvider");
    return mapForClients.release(sslContextProvider);
  }

  @Override
  public SslContextProvider<DownstreamTlsContext> releaseServerSslContextProvider(
      SslContextProvider<DownstreamTlsContext> sslContextProvider) {
    checkNotNull(sslContextProvider, "sslContextProvider");
    return mapForServers.release(sslContextProvider);
  }
}
