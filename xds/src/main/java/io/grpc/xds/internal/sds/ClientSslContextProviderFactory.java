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

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import io.grpc.xds.Bootstrapper;
import io.grpc.xds.EnvoyServerProtoData.UpstreamTlsContext;
import io.grpc.xds.XdsInitializationException;
import io.grpc.xds.internal.certprovider.CertProviderClientSslContextProvider;
import io.grpc.xds.internal.sds.ReferenceCountingMap.ValueFactory;
import java.util.concurrent.Executors;

/** Factory to create client-side SslContextProvider from UpstreamTlsContext. */
final class ClientSslContextProviderFactory
    implements ValueFactory<UpstreamTlsContext, SslContextProvider> {

  private final Bootstrapper bootstrapper;
  private final CertProviderClientSslContextProvider.Factory
      certProviderClientSslContextProviderFactory;

  ClientSslContextProviderFactory(Bootstrapper bootstrapper) {
    this(bootstrapper, CertProviderClientSslContextProvider.Factory.getInstance());
  }

  ClientSslContextProviderFactory(
      Bootstrapper bootstrapper, CertProviderClientSslContextProvider.Factory factory) {
    this.bootstrapper = bootstrapper;
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
      try {
        Bootstrapper.BootstrapInfo bootstrapInfo = bootstrapper.bootstrap();
        return certProviderClientSslContextProviderFactory.getProvider(
                upstreamTlsContext,
                bootstrapInfo.getNode().toEnvoyProtoNode(),
                bootstrapInfo.getCertProviders());
      } catch (XdsInitializationException e) {
        throw new RuntimeException(e);
      }
    } else if (CommonTlsContextUtil.hasAllSecretsUsingFilename(
        upstreamTlsContext.getCommonTlsContext())) {
      return SecretVolumeClientSslContextProvider.getProvider(upstreamTlsContext);
    } else if (CommonTlsContextUtil.hasAllSecretsUsingSds(
        upstreamTlsContext.getCommonTlsContext())) {
      try {
        return SdsClientSslContextProvider.getProvider(
            upstreamTlsContext,
            bootstrapper.bootstrap().getNode().toEnvoyProtoNodeV2(),
            Executors.newSingleThreadExecutor(new ThreadFactoryBuilder()
                .setNameFormat("client-sds-sslcontext-provider-%d")
                .setDaemon(true)
                .build()),
            /* channelExecutor= */ null);
      } catch (XdsInitializationException e) {
        throw new RuntimeException(e);
      }
    }
    throw new UnsupportedOperationException("Unsupported configurations in UpstreamTlsContext!");
  }
}
