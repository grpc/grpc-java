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

import com.google.common.annotations.VisibleForTesting;
import io.envoyproxy.envoy.api.v2.auth.DownstreamTlsContext;
import io.envoyproxy.envoy.api.v2.auth.UpstreamTlsContext;
import io.grpc.Internal;
import io.grpc.xds.sds.ReferenceCountingSslContextProviderMap.SslContextProviderFactory;

/**
 * Class to manage {@link SslContextProvider} objects created from inputs we get from xDS. Used by
 * gRPC-xds to access the SslContext's and is not public API. This manager manages the life-cycle of
 * {@link SslContextProvider} objects as shared resources via ref-counting as described in {@link
 * ReferenceCountingSslContextProviderMap}.
 */
@Internal
public final class TlsContextManager {

  private static TlsContextManager instance;

  private final ReferenceCountingSslContextProviderMap<UpstreamTlsContext> mapForClients;
  private final ReferenceCountingSslContextProviderMap<DownstreamTlsContext> mapForServers;

  private TlsContextManager() {
    this(new ClientSslContextProviderFactory(), new ServerSslContextProviderFactory());
  }

  @VisibleForTesting
  TlsContextManager(
      SslContextProviderFactory<UpstreamTlsContext> clientFactory,
      SslContextProviderFactory<DownstreamTlsContext> serverFactory) {
    checkNotNull(clientFactory, "clientFactory");
    checkNotNull(serverFactory, "serverFactory");
    mapForClients = new ReferenceCountingSslContextProviderMap<>(clientFactory);
    mapForServers = new ReferenceCountingSslContextProviderMap<>(serverFactory);
  }

  /** Gets the TlsContextManager singleton. */
  public static synchronized TlsContextManager getInstance() {
    if (instance == null) {
      instance = new TlsContextManager();
    }
    return instance;
  }

  /** Creates a SslContextProvider. Used for retrieving a server-side SslContext. */
  public SslContextProvider<DownstreamTlsContext> findOrCreateServerSslContextProvider(
      DownstreamTlsContext downstreamTlsContext) {
    checkNotNull(downstreamTlsContext, "downstreamTlsContext");
    return mapForServers.get(downstreamTlsContext);
  }

  /** Creates a SslContextProvider. Used for retrieving a client-side SslContext. */
  public SslContextProvider<UpstreamTlsContext> findOrCreateClientSslContextProvider(
      UpstreamTlsContext upstreamTlsContext) {
    checkNotNull(upstreamTlsContext, "upstreamTlsContext");
    return mapForClients.get(upstreamTlsContext);
  }

  /**
   * Releases an instance of the given client-side {@link SslContextProvider}.
   *
   * <p>The instance must have been obtained from {@link #findOrCreateClientSslContextProvider}.
   * Otherwise will throw IllegalArgumentException.
   *
   * <p>Caller must not release a reference more than once. It's advised that you clear the
   * reference to the instance with the null returned by this method.
   */
  public SslContextProvider<UpstreamTlsContext> releaseClientSslContextProvider(
      SslContextProvider<UpstreamTlsContext> sslContextProvider) {
    checkNotNull(sslContextProvider, "sslContextProvider");
    return mapForClients.release(sslContextProvider);
  }

  /**
   * Releases an instance of the given server-side {@link SslContextProvider}.
   *
   * <p>The instance must have been obtained from {@link #findOrCreateServerSslContextProvider}.
   * Otherwise will throw IllegalArgumentException.
   *
   * <p>Caller must not release a reference more than once. It's advised that you clear the
   * reference to the instance with the null returned by this method.
   */
  public SslContextProvider<DownstreamTlsContext> releaseServerSslContextProvider(
      SslContextProvider<DownstreamTlsContext> sslContextProvider) {
    checkNotNull(sslContextProvider, "sslContextProvider");
    return mapForServers.release(sslContextProvider);
  }
}
