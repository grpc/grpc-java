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

package io.grpc.xds.sds;

import io.envoyproxy.envoy.api.v2.auth.DownstreamTlsContext;
import io.envoyproxy.envoy.api.v2.auth.UpstreamTlsContext;

public interface TlsContextManagerInterface {

  SslContextProvider<DownstreamTlsContext> findOrCreateServerSslContextProvider(
      DownstreamTlsContext downstreamTlsContext);

  SslContextProvider<UpstreamTlsContext> findOrCreateClientSslContextProvider(
      UpstreamTlsContext upstreamTlsContext);

  SslContextProvider<UpstreamTlsContext> releaseClientSslContextProvider(
      SslContextProvider<UpstreamTlsContext> sslContextProvider);

  SslContextProvider<DownstreamTlsContext> releaseServerSslContextProvider(
      SslContextProvider<DownstreamTlsContext> sslContextProvider);
}
