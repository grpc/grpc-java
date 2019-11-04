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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import io.envoyproxy.envoy.api.v2.auth.DownstreamTlsContext;
import io.grpc.xds.sds.ReferenceCountingSslContextProviderMap.SslContextProviderFactory;

/** Factory to create server-side SslContextProvider from DownstreamTlsContext. */
final class ServerSslContextProviderFactory
    implements SslContextProviderFactory<DownstreamTlsContext> {

  /** Creates an SslContextProvider from the given DownstreamTlsContext. */
  @Override
  public SslContextProvider<DownstreamTlsContext> createSslContextProvider(
      DownstreamTlsContext downstreamTlsContext) {
    checkNotNull(downstreamTlsContext, "downstreamTlsContext");
    checkArgument(
        downstreamTlsContext.hasCommonTlsContext(),
        "downstreamTlsContext should have CommonTlsContext");
    if (CommonTlsContextUtil.hasAllSecretsUsingFilename(
        downstreamTlsContext.getCommonTlsContext())) {
      return SecretVolumeSslContextProvider.getProviderForServer(downstreamTlsContext);
    }
    throw new UnsupportedOperationException("DownstreamTlsContext using SDS not supported");
  }
}
