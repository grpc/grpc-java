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

import static com.google.common.truth.Truth.assertThat;
import static io.grpc.xds.internal.security.ClientSslContextProviderFactoryTest.createAndRegisterProviderProvider;
import static io.grpc.xds.internal.security.ClientSslContextProviderFactoryTest.verifyWatcher;

import com.google.common.collect.ImmutableSet;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.CertificateValidationContext;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.CommonTlsContext;
import io.envoyproxy.envoy.type.matcher.v3.StringMatcher;
import io.grpc.xds.Bootstrapper;
import io.grpc.xds.CommonBootstrapperTestUtils;
import io.grpc.xds.EnvoyServerProtoData;
import io.grpc.xds.EnvoyServerProtoData.DownstreamTlsContext;
import io.grpc.xds.XdsInitializationException;
import io.grpc.xds.internal.security.certprovider.CertProviderServerSslContextProviderFactory;
import io.grpc.xds.internal.security.certprovider.CertificateProvider;
import io.grpc.xds.internal.security.certprovider.CertificateProviderRegistry;
import io.grpc.xds.internal.security.certprovider.CertificateProviderStore;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link ServerSslContextProviderFactory}. */
@RunWith(JUnit4.class)
public class ServerSslContextProviderFactoryTest {

  CertificateProviderRegistry certificateProviderRegistry;
  CertificateProviderStore certificateProviderStore;
  CertProviderServerSslContextProviderFactory certProviderServerSslContextProviderFactory;
  ServerSslContextProviderFactory serverSslContextProviderFactory;

  @Before
  public void setUp() {
    certificateProviderRegistry = new CertificateProviderRegistry();
    certificateProviderStore = new CertificateProviderStore(certificateProviderRegistry);
    certProviderServerSslContextProviderFactory =
        new CertProviderServerSslContextProviderFactory(certificateProviderStore);
  }

  @Test
  public void createCertProviderServerSslContextProvider() throws XdsInitializationException {
    final CertificateProvider.DistributorWatcher[] watcherCaptor =
        new CertificateProvider.DistributorWatcher[1];
    createAndRegisterProviderProvider(certificateProviderRegistry, watcherCaptor, "testca", 0);
    DownstreamTlsContext downstreamTlsContext =
        CommonTlsContextTestsUtil.buildDownstreamTlsContextForCertProviderInstance(
            "gcp_id",
            "cert-default",
            "gcp_id",
            "root-default",
            /* alpnProtocols= */ null,
            /* staticCertValidationContext= */ null,
            /* requireClientCert= */ true);

    Bootstrapper.BootstrapInfo bootstrapInfo = CommonBootstrapperTestUtils.getTestBootstrapInfo();
    serverSslContextProviderFactory =
            new ServerSslContextProviderFactory(
                    bootstrapInfo, certProviderServerSslContextProviderFactory);
    SslContextProvider sslContextProvider =
        serverSslContextProviderFactory.create(downstreamTlsContext);
    assertThat(sslContextProvider.getClass().getSimpleName()).isEqualTo(
        "CertProviderServerSslContextProvider");
    verifyWatcher(sslContextProvider, watcherCaptor[0]);
    // verify that bootstrapInfo is cached...
    sslContextProvider =
        serverSslContextProviderFactory.create(downstreamTlsContext);
    assertThat(sslContextProvider.getClass().getSimpleName()).isEqualTo(
        "CertProviderServerSslContextProvider");
  }

  @Test
  public void bothPresent_expectCertProviderServerSslContextProvider()
      throws XdsInitializationException {
    final CertificateProvider.DistributorWatcher[] watcherCaptor =
        new CertificateProvider.DistributorWatcher[1];
    createAndRegisterProviderProvider(certificateProviderRegistry, watcherCaptor, "testca", 0);
    DownstreamTlsContext downstreamTlsContext =
        CommonTlsContextTestsUtil.buildDownstreamTlsContextForCertProviderInstance(
            "gcp_id",
            "cert-default",
            "gcp_id",
            "root-default",
            /* alpnProtocols= */ null,
            /* staticCertValidationContext= */ null,
            /* requireClientCert= */ true);

    CommonTlsContext.Builder builder = downstreamTlsContext.getCommonTlsContext().toBuilder();
    builder =
        ClientSslContextProviderFactoryTest.addFilenames(builder, "foo.pem", "foo.key", "root.pem");
    downstreamTlsContext =
        new EnvoyServerProtoData.DownstreamTlsContext(
            builder.build(), downstreamTlsContext.isRequireClientCertificate());

    Bootstrapper.BootstrapInfo bootstrapInfo = CommonBootstrapperTestUtils.getTestBootstrapInfo();
    serverSslContextProviderFactory =
            new ServerSslContextProviderFactory(
                    bootstrapInfo, certProviderServerSslContextProviderFactory);
    SslContextProvider sslContextProvider =
        serverSslContextProviderFactory.create(downstreamTlsContext);
    assertThat(sslContextProvider.getClass().getSimpleName()).isEqualTo(
        "CertProviderServerSslContextProvider");
    verifyWatcher(sslContextProvider, watcherCaptor[0]);
  }

  @Test
  public void createCertProviderServerSslContextProvider_onlyCertInstance()
      throws XdsInitializationException {
    final CertificateProvider.DistributorWatcher[] watcherCaptor =
            new CertificateProvider.DistributorWatcher[1];
    createAndRegisterProviderProvider(certificateProviderRegistry, watcherCaptor, "testca", 0);
    DownstreamTlsContext downstreamTlsContext =
            CommonTlsContextTestsUtil.buildDownstreamTlsContextForCertProviderInstance(
                    "gcp_id",
                    "cert-default",
                    /* rootInstanceName= */ null,
                    /* rootCertName= */ null,
                    /* alpnProtocols= */ null,
                    /* staticCertValidationContext= */ null,
                    /* requireClientCert= */ true);

    Bootstrapper.BootstrapInfo bootstrapInfo = CommonBootstrapperTestUtils.getTestBootstrapInfo();
    serverSslContextProviderFactory =
            new ServerSslContextProviderFactory(
                    bootstrapInfo, certProviderServerSslContextProviderFactory);
    SslContextProvider sslContextProvider =
            serverSslContextProviderFactory.create(downstreamTlsContext);
    assertThat(sslContextProvider.getClass().getSimpleName()).isEqualTo(
        "CertProviderServerSslContextProvider");
    verifyWatcher(sslContextProvider, watcherCaptor[0]);
  }

  @Test
  public void createCertProviderServerSslContextProvider_withStaticContext()
      throws XdsInitializationException {
    final CertificateProvider.DistributorWatcher[] watcherCaptor =
            new CertificateProvider.DistributorWatcher[1];
    createAndRegisterProviderProvider(certificateProviderRegistry, watcherCaptor, "testca", 0);
    @SuppressWarnings("deprecation")
    CertificateValidationContext staticCertValidationContext =
            CertificateValidationContext.newBuilder()
                    .addAllMatchSubjectAltNames(
                            ImmutableSet.of(
                                    StringMatcher.newBuilder().setExact("foo").build(),
                                    StringMatcher.newBuilder().setExact("bar").build()))
                    .build();
    DownstreamTlsContext downstreamTlsContext =
            CommonTlsContextTestsUtil.buildDownstreamTlsContextForCertProviderInstance(
                    "gcp_id",
                    "cert-default",
                    "gcp_id",
                    "root-default",
                    /* alpnProtocols= */ null,
                    staticCertValidationContext,
                    /* requireClientCert= */ true);

    Bootstrapper.BootstrapInfo bootstrapInfo = CommonBootstrapperTestUtils.getTestBootstrapInfo();
    serverSslContextProviderFactory =
            new ServerSslContextProviderFactory(
                    bootstrapInfo, certProviderServerSslContextProviderFactory);
    SslContextProvider sslContextProvider =
            serverSslContextProviderFactory.create(downstreamTlsContext);
    assertThat(sslContextProvider.getClass().getSimpleName()).isEqualTo(
        "CertProviderServerSslContextProvider");
    verifyWatcher(sslContextProvider, watcherCaptor[0]);
  }

  @Test
  public void createCertProviderServerSslContextProvider_2providers()
      throws XdsInitializationException {
    final CertificateProvider.DistributorWatcher[] watcherCaptor =
        new CertificateProvider.DistributorWatcher[2];
    createAndRegisterProviderProvider(certificateProviderRegistry, watcherCaptor, "testca", 0);

    createAndRegisterProviderProvider(
        certificateProviderRegistry, watcherCaptor, "file_watcher", 1);

    DownstreamTlsContext downstreamTlsContext =
        CommonTlsContextTestsUtil.buildDownstreamTlsContextForCertProviderInstance(
            "gcp_id",
            "cert-default",
            "file_provider",
            "root-default",
            /* alpnProtocols= */ null,
            /* staticCertValidationContext= */ null,
            /* requireClientCert= */ true);

    Bootstrapper.BootstrapInfo bootstrapInfo = CommonBootstrapperTestUtils.getTestBootstrapInfo();
    serverSslContextProviderFactory =
            new ServerSslContextProviderFactory(
                    bootstrapInfo, certProviderServerSslContextProviderFactory);
    SslContextProvider sslContextProvider =
        serverSslContextProviderFactory.create(downstreamTlsContext);
    assertThat(sslContextProvider.getClass().getSimpleName()).isEqualTo(
        "CertProviderServerSslContextProvider");
    verifyWatcher(sslContextProvider, watcherCaptor[0]);
    verifyWatcher(sslContextProvider, watcherCaptor[1]);
  }

  @Test
  public void createNewCertProviderServerSslContextProvider_withSans()
      throws XdsInitializationException {
    final CertificateProvider.DistributorWatcher[] watcherCaptor =
        new CertificateProvider.DistributorWatcher[2];
    createAndRegisterProviderProvider(certificateProviderRegistry, watcherCaptor, "testca", 0);
    createAndRegisterProviderProvider(
        certificateProviderRegistry, watcherCaptor, "file_watcher", 1);
    @SuppressWarnings("deprecation")
    CertificateValidationContext staticCertValidationContext =
        CertificateValidationContext.newBuilder()
            .addAllMatchSubjectAltNames(
                ImmutableSet.of(
                    StringMatcher.newBuilder().setExact("foo").build(),
                    StringMatcher.newBuilder().setExact("bar").build()))
            .build();

    DownstreamTlsContext downstreamTlsContext =
        CommonTlsContextTestsUtil.buildNewDownstreamTlsContextForCertProviderInstance(
            "gcp_id",
            "cert-default",
            "file_provider",
            "root-default",
            /* alpnProtocols= */ null,
            staticCertValidationContext,
            /* requireClientCert= */ true);

    Bootstrapper.BootstrapInfo bootstrapInfo = CommonBootstrapperTestUtils.getTestBootstrapInfo();
    serverSslContextProviderFactory =
        new ServerSslContextProviderFactory(
            bootstrapInfo, certProviderServerSslContextProviderFactory);
    SslContextProvider sslContextProvider =
        serverSslContextProviderFactory.create(downstreamTlsContext);
    assertThat(sslContextProvider.getClass().getSimpleName()).isEqualTo(
        "CertProviderServerSslContextProvider");
    verifyWatcher(sslContextProvider, watcherCaptor[0]);
    verifyWatcher(sslContextProvider, watcherCaptor[1]);
  }
}
