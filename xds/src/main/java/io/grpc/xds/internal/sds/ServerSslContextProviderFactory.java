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
import io.grpc.xds.EnvoyServerProtoData.DownstreamTlsContext;
import io.grpc.xds.XdsInitializationException;
import io.grpc.xds.internal.certprovider.CertProviderServerSslContextProvider;
import io.grpc.xds.internal.sds.ReferenceCountingMap.ValueFactory;
import java.util.concurrent.Executors;

/** Factory to create server-side SslContextProvider from DownstreamTlsContext. */
final class ServerSslContextProviderFactory
    implements ValueFactory<DownstreamTlsContext, SslContextProvider> {

  private final Bootstrapper bootstrapper;
  private final CertProviderServerSslContextProvider.Factory
      certProviderServerSslContextProviderFactory;

  ServerSslContextProviderFactory(Bootstrapper bootstrapper) {
    this(bootstrapper, CertProviderServerSslContextProvider.Factory.getInstance());
  }

  ServerSslContextProviderFactory(
      Bootstrapper bootstrapper, CertProviderServerSslContextProvider.Factory factory) {
    this.bootstrapper = bootstrapper;
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
      try {
        Bootstrapper.BootstrapInfo bootstrapInfo = bootstrapper.bootstrap();
        return certProviderServerSslContextProviderFactory.getProvider(
                downstreamTlsContext,
                bootstrapInfo.getNode().toEnvoyProtoNode(),
                bootstrapInfo.getCertProviders());
      } catch (XdsInitializationException e) {
        throw new RuntimeException(e);
      }
    } else if (CommonTlsContextUtil.hasAllSecretsUsingFilename(
        downstreamTlsContext.getCommonTlsContext())) {
      return SecretVolumeServerSslContextProvider.getProvider(downstreamTlsContext);
    } else if (CommonTlsContextUtil.hasAllSecretsUsingSds(
        downstreamTlsContext.getCommonTlsContext())) {
      try {
        return SdsServerSslContextProvider.getProvider(
            downstreamTlsContext,
            bootstrapper.bootstrap().getNode().toEnvoyProtoNodeV2(),
            Executors.newSingleThreadExecutor(new ThreadFactoryBuilder()
                .setNameFormat("server-sds-sslcontext-provider-%d")
                .setDaemon(true)
                .build()),
            /* channelExecutor= */ null);
      } catch (XdsInitializationException e) {
        throw new RuntimeException(e);
      }
    }
    throw new UnsupportedOperationException("Unsupported configurations in DownstreamTlsContext!");
  }
}
