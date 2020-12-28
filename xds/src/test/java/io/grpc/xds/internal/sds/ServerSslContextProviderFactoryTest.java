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

import static com.google.common.truth.Truth.assertThat;
import static io.grpc.xds.internal.sds.ClientSslContextProviderFactoryTest.createAndRegisterProviderProvider;
import static io.grpc.xds.internal.sds.ClientSslContextProviderFactoryTest.verifyWatcher;
import static io.grpc.xds.internal.sds.CommonTlsContextTestsUtil.CA_PEM_FILE;
import static io.grpc.xds.internal.sds.CommonTlsContextTestsUtil.SERVER_1_KEY_FILE;
import static io.grpc.xds.internal.sds.CommonTlsContextTestsUtil.SERVER_1_PEM_FILE;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableSet;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.CertificateValidationContext;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.CommonTlsContext;
import io.envoyproxy.envoy.type.matcher.v3.StringMatcher;
import io.grpc.xds.Bootstrapper;
import io.grpc.xds.CommonBootstrapperTestUtils;
import io.grpc.xds.EnvoyServerProtoData;
import io.grpc.xds.EnvoyServerProtoData.DownstreamTlsContext;
import io.grpc.xds.XdsInitializationException;
import io.grpc.xds.internal.certprovider.CertProviderServerSslContextProvider;
import io.grpc.xds.internal.certprovider.CertificateProvider;
import io.grpc.xds.internal.certprovider.CertificateProviderRegistry;
import io.grpc.xds.internal.certprovider.CertificateProviderStore;
import java.io.IOException;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link ServerSslContextProviderFactory}. */
@RunWith(JUnit4.class)
public class ServerSslContextProviderFactoryTest {

  Bootstrapper bootstrapper;
  CertificateProviderRegistry certificateProviderRegistry;
  CertificateProviderStore certificateProviderStore;
  CertProviderServerSslContextProvider.Factory certProviderServerSslContextProviderFactory;
  ServerSslContextProviderFactory serverSslContextProviderFactory;

  @Before
  public void setUp() {
    bootstrapper = mock(Bootstrapper.class);
    certificateProviderRegistry = new CertificateProviderRegistry();
    certificateProviderStore = new CertificateProviderStore(certificateProviderRegistry);
    certProviderServerSslContextProviderFactory =
        new CertProviderServerSslContextProvider.Factory(certificateProviderStore);
    serverSslContextProviderFactory =
        new ServerSslContextProviderFactory(
            bootstrapper, certProviderServerSslContextProviderFactory);
  }

  @Test
  public void createSslContextProvider_allFilenames() {
    DownstreamTlsContext downstreamTlsContext =
        CommonTlsContextTestsUtil.buildDownstreamTlsContextFromFilenames(
            SERVER_1_KEY_FILE, SERVER_1_PEM_FILE, CA_PEM_FILE);

    SslContextProvider sslContextProvider =
        serverSslContextProviderFactory.create(downstreamTlsContext);
    assertThat(sslContextProvider).isNotNull();
  }

  @Test
  public void createSslContextProvider_sdsConfigForTlsCert_expectException() {
    CommonTlsContext commonTlsContext =
        CommonTlsContextTestsUtil.buildCommonTlsContextFromSdsConfigForTlsCertificate(
            "name", "unix:/tmp/sds/path", CA_PEM_FILE);
    DownstreamTlsContext downstreamTlsContext =
        CommonTlsContextTestsUtil.buildInternalDownstreamTlsContext(
            commonTlsContext, /* requireClientCert= */ false);

    try {
      SslContextProvider unused =
          serverSslContextProviderFactory.create(downstreamTlsContext);
      Assert.fail("no exception thrown");
    } catch (IllegalArgumentException expected) {
      assertThat(expected).hasMessageThat().isEqualTo("unexpected TlsCertificateSdsSecretConfigs");
    }
  }

  @Test
  public void createSslContextProvider_sdsConfigForCertValidationContext_expectException() {
    CommonTlsContext commonTlsContext =
        CommonTlsContextTestsUtil.buildCommonTlsContextFromSdsConfigForValidationContext(
            "name", "unix:/tmp/sds/path", SERVER_1_KEY_FILE, SERVER_1_PEM_FILE);
    DownstreamTlsContext downstreamTlsContext =
        CommonTlsContextTestsUtil.buildInternalDownstreamTlsContext(
            commonTlsContext, /* requireClientCert= */ false);

    try {
      SslContextProvider unused =
          serverSslContextProviderFactory.create(downstreamTlsContext);
      Assert.fail("no exception thrown");
    } catch (IllegalStateException expected) {
      assertThat(expected).hasMessageThat().isEqualTo("incorrect ValidationContextTypeCase");
    }
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
    when(bootstrapper.bootstrap()).thenReturn(bootstrapInfo);
    SslContextProvider sslContextProvider =
        serverSslContextProviderFactory.create(downstreamTlsContext);
    assertThat(sslContextProvider).isInstanceOf(CertProviderServerSslContextProvider.class);
    verifyWatcher(sslContextProvider, watcherCaptor[0]);
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
    when(bootstrapper.bootstrap()).thenReturn(bootstrapInfo);
    SslContextProvider sslContextProvider =
        serverSslContextProviderFactory.create(downstreamTlsContext);
    assertThat(sslContextProvider).isInstanceOf(CertProviderServerSslContextProvider.class);
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
    when(bootstrapper.bootstrap()).thenReturn(bootstrapInfo);
    SslContextProvider sslContextProvider =
            serverSslContextProviderFactory.create(downstreamTlsContext);
    assertThat(sslContextProvider).isInstanceOf(CertProviderServerSslContextProvider.class);
    verifyWatcher(sslContextProvider, watcherCaptor[0]);
  }

  @Test
  public void createCertProviderServerSslContextProvider_withStaticContext()
      throws XdsInitializationException {
    final CertificateProvider.DistributorWatcher[] watcherCaptor =
            new CertificateProvider.DistributorWatcher[1];
    createAndRegisterProviderProvider(certificateProviderRegistry, watcherCaptor, "testca", 0);
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
    when(bootstrapper.bootstrap()).thenReturn(bootstrapInfo);
    SslContextProvider sslContextProvider =
            serverSslContextProviderFactory.create(downstreamTlsContext);
    assertThat(sslContextProvider).isInstanceOf(CertProviderServerSslContextProvider.class);
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
    when(bootstrapper.bootstrap()).thenReturn(bootstrapInfo);
    SslContextProvider sslContextProvider =
        serverSslContextProviderFactory.create(downstreamTlsContext);
    assertThat(sslContextProvider).isInstanceOf(CertProviderServerSslContextProvider.class);
    verifyWatcher(sslContextProvider, watcherCaptor[0]);
    verifyWatcher(sslContextProvider, watcherCaptor[1]);
  }

  @Test
  public void createCertProviderServerSslContextProvider_exception()
      throws XdsInitializationException {
    DownstreamTlsContext downstreamTlsContext =
        CommonTlsContextTestsUtil.buildDownstreamTlsContextForCertProviderInstance(
            "gcp_id",
            "cert-default",
            "gcp_id",
            "root-default",
            /* alpnProtocols= */ null,
            /* staticCertValidationContext= */ null,
            /* requireClientCert= */ true);
    when(bootstrapper.bootstrap())
        .thenThrow(new XdsInitializationException("test exception"));
    try {
      serverSslContextProviderFactory.create(downstreamTlsContext);
      Assert.fail("no exception thrown");
    } catch (RuntimeException expected) {
      assertThat(expected).hasMessageThat().contains("test exception");
    }
  }

  @Test
  public void createEmptyCommonTlsContext_exception() throws IOException {
    DownstreamTlsContext downstreamTlsContext =
        CommonTlsContextTestsUtil.buildDownstreamTlsContextFromFilenames(null, null, null);
    try {
      serverSslContextProviderFactory.create(downstreamTlsContext);
      Assert.fail("no exception thrown");
    } catch (UnsupportedOperationException expected) {
      assertThat(expected)
          .hasMessageThat()
          .isEqualTo("Unsupported configurations in DownstreamTlsContext!");
    }
  }

  @Test
  public void createNullCommonTlsContext_exception() throws IOException {
    DownstreamTlsContext downstreamTlsContext = new DownstreamTlsContext(null, true);
    try {
      serverSslContextProviderFactory.create(downstreamTlsContext);
      Assert.fail("no exception thrown");
    } catch (NullPointerException expected) {
      assertThat(expected)
              .hasMessageThat()
              .isEqualTo("downstreamTlsContext should have CommonTlsContext");
    }
  }
}
