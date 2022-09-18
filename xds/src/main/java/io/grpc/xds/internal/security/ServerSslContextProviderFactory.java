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

package io.grpc.xds.internal.security;

import static com.google.common.base.Preconditions.checkNotNull;

import io.grpc.xds.Bootstrapper.BootstrapInfo;
import io.grpc.xds.EnvoyServerProtoData.DownstreamTlsContext;
import io.grpc.xds.internal.security.ReferenceCountingMap.ValueFactory;
import io.grpc.xds.internal.security.certprovider.CertProviderServerSslContextProviderFactory;

/** Factory to create server-side SslContextProvider from DownstreamTlsContext. */
final class ServerSslContextProviderFactory
    implements ValueFactory<DownstreamTlsContext, SslContextProvider> {

  private BootstrapInfo bootstrapInfo;
  private final CertProviderServerSslContextProviderFactory
      certProviderServerSslContextProviderFactory;

  ServerSslContextProviderFactory(BootstrapInfo bootstrapInfo) {
    this(bootstrapInfo, CertProviderServerSslContextProviderFactory.getInstance());
  }

  ServerSslContextProviderFactory(
      BootstrapInfo bootstrapInfo, CertProviderServerSslContextProviderFactory factory) {
    this.bootstrapInfo = bootstrapInfo;
    this.certProviderServerSslContextProviderFactory = factory;
  }

  /** Creates a SslContextProvider from the given DownstreamTlsContext. */
  @Override
  public SslContextProvider create(
      DownstreamTlsContext downstreamTlsContext) {
    checkNotNull(downstreamTlsContext, "downstreamTlsContext");
    checkNotNull(
        downstreamTlsContext.getCommonTlsContext(),
        "downstreamTlsContext should have CommonTlsContext");
    if (CommonTlsContextUtil.hasCertProviderInstance(
        downstreamTlsContext.getCommonTlsContext())) {
      return certProviderServerSslContextProviderFactory.getProvider(
          downstreamTlsContext,
          bootstrapInfo.node().toEnvoyProtoNode(),
          bootstrapInfo.certProviders());
    }
    throw new UnsupportedOperationException("Unsupported configurations in DownstreamTlsContext!");
  }
}
