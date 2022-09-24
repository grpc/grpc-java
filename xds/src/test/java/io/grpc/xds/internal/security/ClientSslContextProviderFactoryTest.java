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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableSet;
import io.envoyproxy.envoy.config.core.v3.DataSource;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.CertificateValidationContext;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.CommonTlsContext;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.TlsCertificate;
import io.envoyproxy.envoy.type.matcher.v3.StringMatcher;
import io.grpc.xds.Bootstrapper;
import io.grpc.xds.CommonBootstrapperTestUtils;
import io.grpc.xds.EnvoyServerProtoData.UpstreamTlsContext;
import io.grpc.xds.XdsInitializationException;
import io.grpc.xds.internal.security.certprovider.CertProviderClientSslContextProviderFactory;
import io.grpc.xds.internal.security.certprovider.CertificateProvider;
import io.grpc.xds.internal.security.certprovider.CertificateProviderProvider;
import io.grpc.xds.internal.security.certprovider.CertificateProviderRegistry;
import io.grpc.xds.internal.security.certprovider.CertificateProviderStore;
import io.grpc.xds.internal.security.certprovider.TestCertificateProvider;
import java.io.IOException;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

/** Unit tests for {@link ClientSslContextProviderFactory}. */
@RunWith(JUnit4.class)
public class ClientSslContextProviderFactoryTest {

  CertificateProviderRegistry certificateProviderRegistry;
  CertificateProviderStore certificateProviderStore;
  CertProviderClientSslContextProviderFactory certProviderClientSslContextProviderFactory;
  ClientSslContextProviderFactory clientSslContextProviderFactory;

  @Before
  public void setUp() {
    certificateProviderRegistry = new CertificateProviderRegistry();
    certificateProviderStore = new CertificateProviderStore(certificateProviderRegistry);
    certProviderClientSslContextProviderFactory =
        new CertProviderClientSslContextProviderFactory(certificateProviderStore);
  }

  @Test
  public void createCertProviderClientSslContextProvider() throws XdsInitializationException {
    final CertificateProvider.DistributorWatcher[] watcherCaptor =
        new CertificateProvider.DistributorWatcher[1];
    createAndRegisterProviderProvider(certificateProviderRegistry, watcherCaptor, "testca", 0);
    UpstreamTlsContext upstreamTlsContext =
        CommonTlsContextTestsUtil.buildUpstreamTlsContextForCertProviderInstance(
            "gcp_id",
            "cert-default",
            "gcp_id",
            "root-default",
            /* alpnProtocols= */ null,
            /* staticCertValidationContext= */ null);

    Bootstrapper.BootstrapInfo bootstrapInfo = CommonBootstrapperTestUtils.getTestBootstrapInfo();
    clientSslContextProviderFactory =
            new ClientSslContextProviderFactory(
                    bootstrapInfo, certProviderClientSslContextProviderFactory);
    SslContextProvider sslContextProvider =
        clientSslContextProviderFactory.create(upstreamTlsContext);
    assertThat(sslContextProvider.getClass().getSimpleName()).isEqualTo(
        "CertProviderClientSslContextProvider");
    verifyWatcher(sslContextProvider, watcherCaptor[0]);
    // verify that bootstrapInfo is cached...
    sslContextProvider =
        clientSslContextProviderFactory.create(upstreamTlsContext);
    assertThat(sslContextProvider.getClass().getSimpleName()).isEqualTo(
        "CertProviderClientSslContextProvider");
  }

  @Test
  public void bothPresent_expectCertProviderClientSslContextProvider()
      throws XdsInitializationException {
    final CertificateProvider.DistributorWatcher[] watcherCaptor =
        new CertificateProvider.DistributorWatcher[1];
    createAndRegisterProviderProvider(certificateProviderRegistry, watcherCaptor, "testca", 0);
    UpstreamTlsContext upstreamTlsContext =
        CommonTlsContextTestsUtil.buildUpstreamTlsContextForCertProviderInstance(
            "gcp_id",
            "cert-default",
            "gcp_id",
            "root-default",
            /* alpnProtocols= */ null,
            /* staticCertValidationContext= */ null);

    CommonTlsContext.Builder builder = upstreamTlsContext.getCommonTlsContext().toBuilder();
    builder = addFilenames(builder, "foo.pem", "foo.key", "root.pem");
    upstreamTlsContext = new UpstreamTlsContext(builder.build());

    Bootstrapper.BootstrapInfo bootstrapInfo = CommonBootstrapperTestUtils.getTestBootstrapInfo();
    clientSslContextProviderFactory =
            new ClientSslContextProviderFactory(
                    bootstrapInfo, certProviderClientSslContextProviderFactory);
    SslContextProvider sslContextProvider =
        clientSslContextProviderFactory.create(upstreamTlsContext);
    assertThat(sslContextProvider.getClass().getSimpleName()).isEqualTo(
        "CertProviderClientSslContextProvider");
    verifyWatcher(sslContextProvider, watcherCaptor[0]);
  }

  @Test
  public void createCertProviderClientSslContextProvider_onlyRootCert()
      throws XdsInitializationException {
    final CertificateProvider.DistributorWatcher[] watcherCaptor =
            new CertificateProvider.DistributorWatcher[1];
    createAndRegisterProviderProvider(certificateProviderRegistry, watcherCaptor, "testca", 0);
    UpstreamTlsContext upstreamTlsContext =
            CommonTlsContextTestsUtil.buildUpstreamTlsContextForCertProviderInstance(
                    /* certInstanceName= */ null,
                    /* certName= */ null,
                    "gcp_id",
                    "root-default",
                    /* alpnProtocols= */ null,
                    /* staticCertValidationContext= */ null);

    Bootstrapper.BootstrapInfo bootstrapInfo = CommonBootstrapperTestUtils.getTestBootstrapInfo();
    clientSslContextProviderFactory =
            new ClientSslContextProviderFactory(
                    bootstrapInfo, certProviderClientSslContextProviderFactory);
    SslContextProvider sslContextProvider =
            clientSslContextProviderFactory.create(upstreamTlsContext);
    assertThat(sslContextProvider.getClass().getSimpleName()).isEqualTo(
        "CertProviderClientSslContextProvider");
    verifyWatcher(sslContextProvider, watcherCaptor[0]);
  }

  @Test
  public void createCertProviderClientSslContextProvider_withStaticContext()
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
    UpstreamTlsContext upstreamTlsContext =
            CommonTlsContextTestsUtil.buildUpstreamTlsContextForCertProviderInstance(
                    /* certInstanceName= */ null,
                    /* certName= */ null,
                    "gcp_id",
                    "root-default",
                    /* alpnProtocols= */ null,
                    staticCertValidationContext);

    Bootstrapper.BootstrapInfo bootstrapInfo = CommonBootstrapperTestUtils.getTestBootstrapInfo();
    clientSslContextProviderFactory =
            new ClientSslContextProviderFactory(bootstrapInfo,
                    certProviderClientSslContextProviderFactory);
    SslContextProvider sslContextProvider =
            clientSslContextProviderFactory.create(upstreamTlsContext);
    assertThat(sslContextProvider.getClass().getSimpleName()).isEqualTo(
        "CertProviderClientSslContextProvider");
    verifyWatcher(sslContextProvider, watcherCaptor[0]);
  }

  @Test
  public void createCertProviderClientSslContextProvider_2providers()
      throws XdsInitializationException {
    final CertificateProvider.DistributorWatcher[] watcherCaptor =
        new CertificateProvider.DistributorWatcher[2];
    createAndRegisterProviderProvider(certificateProviderRegistry, watcherCaptor, "testca", 0);

    createAndRegisterProviderProvider(
        certificateProviderRegistry, watcherCaptor, "file_watcher", 1);

    UpstreamTlsContext upstreamTlsContext =
        CommonTlsContextTestsUtil.buildUpstreamTlsContextForCertProviderInstance(
            "gcp_id",
            "cert-default",
            "file_provider",
            "root-default",
            /* alpnProtocols= */ null,
            /* staticCertValidationContext= */ null);

    Bootstrapper.BootstrapInfo bootstrapInfo = CommonBootstrapperTestUtils.getTestBootstrapInfo();
    clientSslContextProviderFactory =
            new ClientSslContextProviderFactory(
                    bootstrapInfo, certProviderClientSslContextProviderFactory);
    SslContextProvider sslContextProvider =
        clientSslContextProviderFactory.create(upstreamTlsContext);
    assertThat(sslContextProvider.getClass().getSimpleName()).isEqualTo(
        "CertProviderClientSslContextProvider");
    verifyWatcher(sslContextProvider, watcherCaptor[0]);
    verifyWatcher(sslContextProvider, watcherCaptor[1]);
  }

  @Test
  public void createNewCertProviderClientSslContextProvider_withSans() {
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
    UpstreamTlsContext upstreamTlsContext =
        CommonTlsContextTestsUtil.buildNewUpstreamTlsContextForCertProviderInstance(
            "gcp_id",
            "cert-default",
            "file_provider",
            "root-default",
            /* alpnProtocols= */ null,
            staticCertValidationContext);

    Bootstrapper.BootstrapInfo bootstrapInfo = CommonBootstrapperTestUtils.getTestBootstrapInfo();
    clientSslContextProviderFactory =
        new ClientSslContextProviderFactory(
            bootstrapInfo, certProviderClientSslContextProviderFactory);
    SslContextProvider sslContextProvider =
        clientSslContextProviderFactory.create(upstreamTlsContext);
    assertThat(sslContextProvider.getClass().getSimpleName()).isEqualTo(
        "CertProviderClientSslContextProvider");
    verifyWatcher(sslContextProvider, watcherCaptor[0]);
    verifyWatcher(sslContextProvider, watcherCaptor[1]);
  }

  @Test
  public void createNewCertProviderClientSslContextProvider_onlyRootCert() {
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
    UpstreamTlsContext upstreamTlsContext =
        CommonTlsContextTestsUtil.buildNewUpstreamTlsContextForCertProviderInstance(
            /* certInstanceName= */ null,
            /* certName= */ null,
            "gcp_id",
            "root-default",
            /* alpnProtocols= */ null,
            staticCertValidationContext);

    Bootstrapper.BootstrapInfo bootstrapInfo = CommonBootstrapperTestUtils.getTestBootstrapInfo();
    clientSslContextProviderFactory =
        new ClientSslContextProviderFactory(
            bootstrapInfo, certProviderClientSslContextProviderFactory);
    SslContextProvider sslContextProvider =
        clientSslContextProviderFactory.create(upstreamTlsContext);
    assertThat(sslContextProvider.getClass().getSimpleName()).isEqualTo(
        "CertProviderClientSslContextProvider");
    verifyWatcher(sslContextProvider, watcherCaptor[0]);
  }

  @Test
  public void createNullCommonTlsContext_exception() throws IOException {
    clientSslContextProviderFactory =
            new ClientSslContextProviderFactory(
                    null, certProviderClientSslContextProviderFactory);
    UpstreamTlsContext upstreamTlsContext = new UpstreamTlsContext(null);
    try {
      clientSslContextProviderFactory.create(upstreamTlsContext);
      Assert.fail("no exception thrown");
    } catch (NullPointerException expected) {
      assertThat(expected)
              .hasMessageThat()
              .isEqualTo("upstreamTlsContext should have CommonTlsContext");
    }
  }

  static void createAndRegisterProviderProvider(
      CertificateProviderRegistry certificateProviderRegistry,
      final CertificateProvider.DistributorWatcher[] watcherCaptor,
      String testca,
      final int i) {
    final CertificateProviderProvider mockProviderProviderTestCa =
        mock(CertificateProviderProvider.class);
    when(mockProviderProviderTestCa.getName()).thenReturn(testca);

    when(mockProviderProviderTestCa.createCertificateProvider(
            any(Object.class), any(CertificateProvider.DistributorWatcher.class), eq(true)))
        .thenAnswer(
            new Answer<CertificateProvider>() {
              @Override
              public CertificateProvider answer(InvocationOnMock invocation) throws Throwable {
                Object[] args = invocation.getArguments();
                CertificateProvider.DistributorWatcher watcher =
                    (CertificateProvider.DistributorWatcher) args[1];
                watcherCaptor[i] = watcher;
                return new TestCertificateProvider(
                    watcher, true, args[0], mockProviderProviderTestCa, false);
              }
            });
    certificateProviderRegistry.register(mockProviderProviderTestCa);
  }

  static void verifyWatcher(
      SslContextProvider sslContextProvider, CertificateProvider.DistributorWatcher watcherCaptor) {
    assertThat(watcherCaptor).isNotNull();
    assertThat(watcherCaptor.getDownstreamWatchers()).hasSize(1);
    assertThat(watcherCaptor.getDownstreamWatchers().iterator().next())
        .isSameInstanceAs(sslContextProvider);
  }

  @SuppressWarnings("deprecation")
  static CommonTlsContext.Builder addFilenames(
      CommonTlsContext.Builder builder, String certChain, String privateKey, String trustCa) {
    TlsCertificate tlsCert =
        TlsCertificate.newBuilder()
            .setCertificateChain(DataSource.newBuilder().setFilename(certChain))
            .setPrivateKey(DataSource.newBuilder().setFilename(privateKey))
            .build();
    CertificateValidationContext certContext =
        CertificateValidationContext.newBuilder()
            .setTrustedCa(DataSource.newBuilder().setFilename(trustCa))
            .build();
    CommonTlsContext.CertificateProviderInstance certificateProviderInstance =
        builder.getValidationContextCertificateProviderInstance();
    CommonTlsContext.CombinedCertificateValidationContext.Builder combinedBuilder =
        CommonTlsContext.CombinedCertificateValidationContext.newBuilder();
    combinedBuilder
        .setDefaultValidationContext(certContext)
        .setValidationContextCertificateProviderInstance(certificateProviderInstance);
    return builder
        .addTlsCertificates(tlsCert)
        .setCombinedValidationContext(combinedBuilder.build());
  }
}
