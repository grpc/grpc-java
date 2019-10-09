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

import io.envoyproxy.envoy.api.v2.auth.DownstreamTlsContext;
import io.envoyproxy.envoy.api.v2.auth.UpstreamTlsContext;
import io.grpc.Internal;
import io.netty.handler.ssl.SslContext;

/**
 * Class to manage secrets used to create SSL contexts - this effectively manages SSL contexts
 * (aka TlsContexts) based on inputs we get from xDS. This is used by gRPC-xds to access the
 * SSL contexts/secrets and is not public API.
 */
@Internal
public final class TlsContextManager {

  /**
   * Finds an existing SecretProvider or creates it if it doesn't exist.
   * Used for retrieving a server-side SslContext
   */
  public SecretProvider<SslContext> findOrCreateServerSslContextProvider(
      DownstreamTlsContext downstreamTlsContext) {
    // TODO(sanjaypujare): implementation of SecretProvider<SslContext>
    return null;
  }

  /**
   * Finds an existing SecretProvider or creates it if it doesn't exist.
   * Used for retrieving a client-side SslContext
   */
  public SecretProvider<SslContext> findOrCreateClientSslContextProvider(
      UpstreamTlsContext upstreamTlsContext) {
    // TODO(sanjaypujare): implementation of SecretProvider<SslContext>
    return null;
  }
}
