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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import io.envoyproxy.envoy.api.v2.auth.DownstreamTlsContext;
import io.grpc.xds.Bootstrapper;
import io.grpc.xds.internal.sds.ReferenceCountingSslContextProviderMap.SslContextProviderFactory;
import java.io.IOException;
import java.util.concurrent.Executors;

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
    } else if (CommonTlsContextUtil.hasAllSecretsUsingSds(
        downstreamTlsContext.getCommonTlsContext())) {
      try {
        return SdsSslContextProvider.getProviderForServer(
            downstreamTlsContext,
            Bootstrapper.getInstance().readBootstrap().getNode(),
            Executors.newSingleThreadExecutor(new ThreadFactoryBuilder()
                .setNameFormat("server-sds-sslcontext-provider-%d")
                .setDaemon(true)
                .build()),
            /* channelExecutor= */ null);
      } catch (IOException ioe) {
        throw new RuntimeException(ioe);
      }
    }
    throw new UnsupportedOperationException(
        "DownstreamTlsContext to have all filenames or all SdsConfig");
  }
}
