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
import io.grpc.xds.EnvoyServerProtoData.UpstreamTlsContext;
import io.grpc.xds.internal.security.ReferenceCountingMap.ValueFactory;
import io.grpc.xds.internal.security.certprovider.CertProviderClientSslContextProviderFactory;

/** Factory to create client-side SslContextProvider from UpstreamTlsContext. */
final class ClientSslContextProviderFactory
    implements ValueFactory<UpstreamTlsContext, SslContextProvider> {

  private BootstrapInfo bootstrapInfo;
  private final CertProviderClientSslContextProviderFactory
      certProviderClientSslContextProviderFactory;

  ClientSslContextProviderFactory(BootstrapInfo bootstrapInfo) {
    this(bootstrapInfo, CertProviderClientSslContextProviderFactory.getInstance());
  }

  ClientSslContextProviderFactory(
      BootstrapInfo bootstrapInfo, CertProviderClientSslContextProviderFactory factory) {
    this.bootstrapInfo = bootstrapInfo;
    this.certProviderClientSslContextProviderFactory = factory;
  }

  /** Creates an SslContextProvider from the given UpstreamTlsContext. */
  @Override
  public SslContextProvider create(UpstreamTlsContext upstreamTlsContext) {
    checkNotNull(upstreamTlsContext, "upstreamTlsContext");
    checkNotNull(
        upstreamTlsContext.getCommonTlsContext(),
        "upstreamTlsContext should have CommonTlsContext");
    if (CommonTlsContextUtil.hasCertProviderInstance(
        upstreamTlsContext.getCommonTlsContext())) {
      return certProviderClientSslContextProviderFactory.getProvider(
          upstreamTlsContext,
          bootstrapInfo.node().toEnvoyProtoNode(),
          bootstrapInfo.certProviders());
    }
    throw new UnsupportedOperationException("Unsupported configurations in UpstreamTlsContext!");
  }
}
