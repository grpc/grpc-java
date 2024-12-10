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

package io.grpc.xds;

import static com.google.common.truth.Truth.assertThat;
import static io.grpc.xds.internal.security.CommonTlsContextTestsUtil.BAD_CLIENT_KEY_FILE;
import static io.grpc.xds.internal.security.CommonTlsContextTestsUtil.BAD_CLIENT_PEM_FILE;
import static io.grpc.xds.internal.security.CommonTlsContextTestsUtil.BAD_SERVER_KEY_FILE;
import static io.grpc.xds.internal.security.CommonTlsContextTestsUtil.BAD_SERVER_PEM_FILE;
import static io.grpc.xds.internal.security.CommonTlsContextTestsUtil.CA_PEM_FILE;
import static io.grpc.xds.internal.security.CommonTlsContextTestsUtil.CLIENT_KEY_FILE;
import static io.grpc.xds.internal.security.CommonTlsContextTestsUtil.CLIENT_PEM_FILE;
import static io.grpc.xds.internal.security.CommonTlsContextTestsUtil.CLIENT_SPIFFE_PEM_FILE;
import static io.grpc.xds.internal.security.CommonTlsContextTestsUtil.SERVER_1_KEY_FILE;
import static io.grpc.xds.internal.security.CommonTlsContextTestsUtil.SERVER_1_PEM_FILE;
import static io.grpc.xds.internal.security.CommonTlsContextTestsUtil.SERVER_1_SPIFFE_PEM_FILE;
import static io.grpc.xds.internal.security.CommonTlsContextTestsUtil.SPIFFE_TRUST_MAP_1_FILE;
import static io.grpc.xds.internal.security.CommonTlsContextTestsUtil.SPIFFE_TRUST_MAP_FILE;
import static org.junit.Assert.fail;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.SettableFuture;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.CertificateValidationContext;
import io.grpc.Attributes;
import io.grpc.EquivalentAddressGroup;
import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.InsecureServerCredentials;
import io.grpc.ManagedChannelBuilder;
import io.grpc.NameResolver;
import io.grpc.NameResolverProvider;
import io.grpc.NameResolverRegistry;
import io.grpc.Server;
import io.grpc.ServerCredentials;
import io.grpc.Status;
import io.grpc.StatusOr;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.GrpcCleanupRule;
import io.grpc.testing.protobuf.SimpleRequest;
import io.grpc.testing.protobuf.SimpleResponse;
import io.grpc.testing.protobuf.SimpleServiceGrpc;
import io.grpc.xds.EnvoyServerProtoData.ConnectionSourceType;
import io.grpc.xds.EnvoyServerProtoData.DownstreamTlsContext;
import io.grpc.xds.EnvoyServerProtoData.UpstreamTlsContext;
import io.grpc.xds.Filter.FilterConfig;
import io.grpc.xds.Filter.NamedFilterConfig;
import io.grpc.xds.VirtualHost.Route;
import io.grpc.xds.VirtualHost.Route.RouteMatch;
import io.grpc.xds.VirtualHost.Route.RouteMatch.PathMatcher;
import io.grpc.xds.XdsListenerResource.LdsUpdate;
import io.grpc.xds.XdsServerTestHelper.FakeXdsClient;
import io.grpc.xds.XdsServerTestHelper.FakeXdsClientPoolFactory;
import io.grpc.xds.client.Bootstrapper;
import io.grpc.xds.client.CommonBootstrapperTestUtils;
import io.grpc.xds.internal.Matchers.HeaderMatcher;
import io.grpc.xds.internal.security.CommonTlsContextTestsUtil;
import io.grpc.xds.internal.security.SslContextProviderSupplier;
import io.grpc.xds.internal.security.TlsContextManagerImpl;
import io.grpc.xds.internal.security.certprovider.FileWatcherCertificateProviderProvider;
import io.netty.handler.ssl.NotSslRecordException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.TrustManagerFactory;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

/**
 * Unit tests for {@link XdsChannelCredentials} and {@link XdsServerBuilder} for plaintext/TLS/mTLS
 * modes.
 */
@RunWith(Parameterized.class)
public class XdsSecurityClientServerTest {

  @Parameter
  public Boolean enableSpiffe;
  private Boolean originalEnableSpiffe;

  @Rule public final GrpcCleanupRule cleanupRule = new GrpcCleanupRule();
  private int port;
  private FakeNameResolverFactory fakeNameResolverFactory;
  private Bootstrapper.BootstrapInfo bootstrapInfoForClient = null;
  private Bootstrapper.BootstrapInfo bootstrapInfoForServer = null;
  private TlsContextManagerImpl tlsContextManagerForClient;
  private TlsContextManagerImpl tlsContextManagerForServer;
  private FakeXdsClient xdsClient = new FakeXdsClient();
  private FakeXdsClientPoolFactory fakePoolFactory = new FakeXdsClientPoolFactory(xdsClient);
  private static final String OVERRIDE_AUTHORITY = "foo.test.google.fr";

  @Parameters(name = "enableSpiffe={0}")
  public static Collection<Boolean> data() {
    return ImmutableList.of(true, false);
  }

  @Before
  public void setUp() throws IOException {
    saveEnvironment();
    FileWatcherCertificateProviderProvider.enableSpiffe = enableSpiffe;
  }

  private void saveEnvironment() {
    originalEnableSpiffe = FileWatcherCertificateProviderProvider.enableSpiffe;
  }

  @After
  public void tearDown() throws IOException {
    if (fakeNameResolverFactory != null) {
      NameResolverRegistry.getDefaultRegistry().deregister(fakeNameResolverFactory);
    }
    FileWatcherCertificateProviderProvider.enableSpiffe = originalEnableSpiffe;
  }

  @Test
  public void plaintextClientServer() throws Exception {
    buildServerWithTlsContext(/* downstreamTlsContext= */ null);

    SimpleServiceGrpc.SimpleServiceBlockingStub blockingStub =
        getBlockingStub(/* upstreamTlsContext= */ null,
                /* overrideAuthority= */ OVERRIDE_AUTHORITY);
    assertThat(unaryRpc("buddy", blockingStub)).isEqualTo("Hello buddy");
  }

  @Test
  public void nullFallbackCredentials_expectException() throws Exception {
    try {
      buildServerWithTlsContext(/* downstreamTlsContext= */ null, /* fallbackCredentials= */ null);
      fail("exception expected");
    } catch (NullPointerException npe) {
      assertThat(npe).hasMessageThat().isEqualTo("fallback");
    }
  }

  /** TLS channel - no mTLS. */
  @Test
  public void tlsClientServer_noClientAuthentication() throws Exception {
    DownstreamTlsContext downstreamTlsContext =
        setBootstrapInfoAndBuildDownstreamTlsContext(SERVER_1_PEM_FILE, null, null, null, null,
            null, false, false);
    buildServerWithTlsContext(downstreamTlsContext);

    // for TLS, client only needs trustCa
    UpstreamTlsContext upstreamTlsContext = setBootstrapInfoAndBuildUpstreamTlsContext(
        CLIENT_KEY_FILE, CLIENT_PEM_FILE, null, false);

    SimpleServiceGrpc.SimpleServiceBlockingStub blockingStub =
        getBlockingStub(upstreamTlsContext, /* overrideAuthority= */ OVERRIDE_AUTHORITY);
    assertThat(unaryRpc(/* requestMessage= */ "buddy", blockingStub)).isEqualTo("Hello buddy");
  }

  /**
   * Use system root ca cert for TLS channel - no mTLS.
   * Uses common_tls_context.combined_validation_context in upstream_tls_context.
   */
  @Test
  public void tlsClientServer_useSystemRootCerts_useCombinedValidationContext() throws Exception {
    Path trustStoreFilePath = getCacertFilePathForTestCa();
    try {
      setTrustStoreSystemProperties(trustStoreFilePath.toAbsolutePath().toString());
      DownstreamTlsContext downstreamTlsContext =
          setBootstrapInfoAndBuildDownstreamTlsContext(SERVER_1_PEM_FILE, null, null, null, null,
              null, false, false);
      buildServerWithTlsContext(downstreamTlsContext);

      UpstreamTlsContext upstreamTlsContext =
          setBootstrapInfoAndBuildUpstreamTlsContextForUsingSystemRootCerts(CLIENT_KEY_FILE,
              CLIENT_PEM_FILE, true);

      SimpleServiceGrpc.SimpleServiceBlockingStub blockingStub =
          getBlockingStub(upstreamTlsContext, /* overrideAuthority= */ OVERRIDE_AUTHORITY);
      assertThat(unaryRpc(/* requestMessage= */ "buddy", blockingStub)).isEqualTo("Hello buddy");
    } finally {
      Files.deleteIfExists(trustStoreFilePath);
      clearTrustStoreSystemProperties();
    }
  }

  /**
   * Use system root ca cert for TLS channel - no mTLS.
   * Uses common_tls_context.validation_context in upstream_tls_context.
   */
  @Test
  public void tlsClientServer_useSystemRootCerts_validationContext() throws Exception {
    Path trustStoreFilePath = getCacertFilePathForTestCa().toAbsolutePath();
    try {
      setTrustStoreSystemProperties(trustStoreFilePath.toAbsolutePath().toString());
      DownstreamTlsContext downstreamTlsContext =
          setBootstrapInfoAndBuildDownstreamTlsContext(SERVER_1_PEM_FILE, null, null, null, null,
              null, false, false);
      buildServerWithTlsContext(downstreamTlsContext);

      UpstreamTlsContext upstreamTlsContext =
          setBootstrapInfoAndBuildUpstreamTlsContextForUsingSystemRootCerts(CLIENT_KEY_FILE,
              CLIENT_PEM_FILE, false);

      SimpleServiceGrpc.SimpleServiceBlockingStub blockingStub =
          getBlockingStub(upstreamTlsContext, /* overrideAuthority= */ OVERRIDE_AUTHORITY);
      assertThat(unaryRpc(/* requestMessage= */ "buddy", blockingStub)).isEqualTo("Hello buddy");
    } finally {
      Files.deleteIfExists(trustStoreFilePath.toAbsolutePath());
      clearTrustStoreSystemProperties();
    }
  }

  /**
   * Use system root ca cert for TLS channel - mTLS.
   * Uses common_tls_context.combined_validation_context in upstream_tls_context.
   */
  @Test
  public void tlsClientServer_useSystemRootCerts_requireClientAuth() throws Exception {
    Path trustStoreFilePath = getCacertFilePathForTestCa().toAbsolutePath();
    try {
      setTrustStoreSystemProperties(trustStoreFilePath.toAbsolutePath().toString());
      DownstreamTlsContext downstreamTlsContext =
          setBootstrapInfoAndBuildDownstreamTlsContext(SERVER_1_PEM_FILE, null, null, null, null,
              null, false, false);
      buildServerWithTlsContext(downstreamTlsContext);

      UpstreamTlsContext upstreamTlsContext =
          setBootstrapInfoAndBuildUpstreamTlsContextForUsingSystemRootCerts(CLIENT_KEY_FILE,
              CLIENT_PEM_FILE, true);

      SimpleServiceGrpc.SimpleServiceBlockingStub blockingStub =
          getBlockingStub(upstreamTlsContext, /* overrideAuthority= */ OVERRIDE_AUTHORITY);
      assertThat(unaryRpc(/* requestMessage= */ "buddy", blockingStub)).isEqualTo("Hello buddy");
    } finally {
      Files.deleteIfExists(trustStoreFilePath.toAbsolutePath());
      clearTrustStoreSystemProperties();
    }
  }

  private Path getCacertFilePathForTestCa()
      throws IOException, KeyStoreException, CertificateException, NoSuchAlgorithmException {
    KeyStore keystore = KeyStore.getInstance(KeyStore.getDefaultType());
    keystore.load(null, null);
    InputStream caCertStream = getClass().getResource("/certs/ca.pem").openStream();
    keystore.setCertificateEntry("testca", CertificateFactory.getInstance("X.509")
        .generateCertificate(caCertStream));
    caCertStream.close();
    File trustStoreFile = File.createTempFile("testca-truststore", "jks");
    FileOutputStream out = new FileOutputStream(trustStoreFile);
    keystore.store(out, "changeit".toCharArray());
    out.close();
    return trustStoreFile.toPath();
  }

  @Test
  public void tlsClientServer_Spiffe_noClientAuthentication() throws Exception {
    DownstreamTlsContext downstreamTlsContext =
        setBootstrapInfoAndBuildDownstreamTlsContext(SERVER_1_SPIFFE_PEM_FILE, null, null, null,
            null, null, false, false);
    buildServerWithTlsContext(downstreamTlsContext);

    // for TLS, client only needs trustCa, so BAD certs don't matter
    UpstreamTlsContext upstreamTlsContext = setBootstrapInfoAndBuildUpstreamTlsContext(
        BAD_CLIENT_KEY_FILE, BAD_CLIENT_PEM_FILE, SPIFFE_TRUST_MAP_FILE, false);

    SimpleServiceGrpc.SimpleServiceBlockingStub blockingStub =
        getBlockingStub(upstreamTlsContext, /* overrideAuthority= */ OVERRIDE_AUTHORITY);
    assertThat(unaryRpc(/* requestMessage= */ "buddy", blockingStub)).isEqualTo("Hello buddy");
  }

  @Test
  public void tlsClientServer_Spiffe_noClientAuthentication_wrongServerCert() throws Exception {
    if (!enableSpiffe) {
      return;
    }
    DownstreamTlsContext downstreamTlsContext =
        setBootstrapInfoAndBuildDownstreamTlsContext(SERVER_1_PEM_FILE, null, null, null, null,
            null, false, false);
    buildServerWithTlsContext(downstreamTlsContext);

    // for TLS, client only needs trustCa, so BAD certs don't matter
    UpstreamTlsContext upstreamTlsContext = setBootstrapInfoAndBuildUpstreamTlsContext(
        BAD_CLIENT_KEY_FILE, BAD_CLIENT_PEM_FILE, SPIFFE_TRUST_MAP_FILE, false);

    SimpleServiceGrpc.SimpleServiceBlockingStub blockingStub =
        getBlockingStub(upstreamTlsContext, /* overrideAuthority= */ OVERRIDE_AUTHORITY);
    try {
      unaryRpc("buddy", blockingStub);
      fail("exception expected");
    } catch (StatusRuntimeException sre) {
      assertThat(sre.getStatus().getCode()).isEqualTo(Status.UNAVAILABLE.getCode());
      assertThat(sre.getCause().getCause().getMessage())
          .contains("Failed to extract SPIFFE ID from peer leaf certificate");
    }
  }

  @Test
  public void requireClientAuth_noClientCert_expectException()
      throws Exception {
    DownstreamTlsContext downstreamTlsContext =
        setBootstrapInfoAndBuildDownstreamTlsContext(SERVER_1_PEM_FILE, null, null, null, null,
            null, true, true);
    buildServerWithTlsContext(downstreamTlsContext);

    // for TLS, client only uses trustCa
    UpstreamTlsContext upstreamTlsContext = setBootstrapInfoAndBuildUpstreamTlsContext(
        CLIENT_KEY_FILE, CLIENT_PEM_FILE, null, false);

    SimpleServiceGrpc.SimpleServiceBlockingStub blockingStub =
        getBlockingStub(upstreamTlsContext, /* overrideAuthority= */ OVERRIDE_AUTHORITY);
    try {
      unaryRpc(/* requestMessage= */ "buddy", blockingStub);
      fail("exception expected");
    } catch (StatusRuntimeException sre) {
      if (sre.getCause() instanceof SSLHandshakeException) {
        assertThat(sre).hasCauseThat().isInstanceOf(SSLHandshakeException.class);
        assertThat(sre).hasCauseThat().hasMessageThat().contains("HANDSHAKE_FAILURE");
      } else {
        // Client cert verification is after handshake in TLSv1.3
        assertThat(sre).hasCauseThat().hasCauseThat().isInstanceOf(SSLException.class);
        assertThat(sre).hasCauseThat().hasMessageThat().contains("CERTIFICATE_REQUIRED");
      }
    }
  }

  @Test
  public void noClientAuth_sendBadClientCert_passes() throws Exception {
    DownstreamTlsContext downstreamTlsContext =
        setBootstrapInfoAndBuildDownstreamTlsContext(SERVER_1_PEM_FILE, null, null, null, null,
            null, false, false);
    buildServerWithTlsContext(downstreamTlsContext);

    UpstreamTlsContext upstreamTlsContext = setBootstrapInfoAndBuildUpstreamTlsContext(
        BAD_CLIENT_KEY_FILE, BAD_CLIENT_PEM_FILE, null, true);

    SimpleServiceGrpc.SimpleServiceBlockingStub blockingStub =
        getBlockingStub(upstreamTlsContext, /* overrideAuthority= */ OVERRIDE_AUTHORITY);
    assertThat(unaryRpc("buddy", blockingStub)).isEqualTo("Hello buddy");
  }

  @Test
  public void mtls_badClientCert_expectException() throws Exception {
    UpstreamTlsContext upstreamTlsContext = setBootstrapInfoAndBuildUpstreamTlsContext(
        BAD_CLIENT_KEY_FILE, BAD_CLIENT_PEM_FILE, null, true);
    try {
      performMtlsTestAndGetListenerWatcher(upstreamTlsContext, null, null, null, null);
      fail("exception expected");
    } catch (StatusRuntimeException sre) {
      if (sre.getCause() instanceof SSLHandshakeException) {
        assertThat(sre).hasCauseThat().isInstanceOf(SSLHandshakeException.class);
        assertThat(sre).hasCauseThat().hasMessageThat().contains("HANDSHAKE_FAILURE");
      } else {
        // Client cert verification is after handshake in TLSv1.3
        assertThat(sre).hasCauseThat().hasCauseThat().isInstanceOf(SSLException.class);
        assertThat(sre).hasCauseThat().hasMessageThat().contains("CERTIFICATE_REQUIRED");
      }
    }
  }

  /** mTLS with Spiffe Trust Bundle - client auth enabled - using {@link XdsChannelCredentials}
   * API. */
  @Test
  public void mtlsClientServer_Spiffe_withClientAuthentication_withXdsChannelCreds()
      throws Exception {
    DownstreamTlsContext downstreamTlsContext =
        setBootstrapInfoAndBuildDownstreamTlsContext(SERVER_1_SPIFFE_PEM_FILE, null, null, null,
            null, SPIFFE_TRUST_MAP_1_FILE, true, true);
    buildServerWithTlsContext(downstreamTlsContext);

    UpstreamTlsContext upstreamTlsContext = setBootstrapInfoAndBuildUpstreamTlsContext(
        CLIENT_KEY_FILE, CLIENT_SPIFFE_PEM_FILE, SPIFFE_TRUST_MAP_1_FILE, true);

    SimpleServiceGrpc.SimpleServiceBlockingStub blockingStub =
        getBlockingStub(upstreamTlsContext, /* overrideAuthority= */ OVERRIDE_AUTHORITY);
    assertThat(unaryRpc(/* requestMessage= */ "buddy", blockingStub)).isEqualTo("Hello buddy");
  }

  @Test
  public void mtlsClientServer_Spiffe_badClientCert_expectException()
      throws Exception {
    DownstreamTlsContext downstreamTlsContext =
        setBootstrapInfoAndBuildDownstreamTlsContext(SERVER_1_SPIFFE_PEM_FILE, null, null, null,
            null, SPIFFE_TRUST_MAP_1_FILE, true, true);
    buildServerWithTlsContext(downstreamTlsContext);

    UpstreamTlsContext upstreamTlsContext = setBootstrapInfoAndBuildUpstreamTlsContext(
        CLIENT_KEY_FILE, BAD_CLIENT_PEM_FILE, SPIFFE_TRUST_MAP_1_FILE, true);
    SimpleServiceGrpc.SimpleServiceBlockingStub blockingStub =
        getBlockingStub(upstreamTlsContext, /* overrideAuthority= */ OVERRIDE_AUTHORITY);
    try {
      assertThat(unaryRpc(/* requestMessage= */ "buddy", blockingStub)).isEqualTo("Hello buddy");
      fail("exception expected");
    } catch (StatusRuntimeException sre) {
      assertThat(sre.getStatus().getCode()).isEqualTo(Status.UNAVAILABLE.getCode());
      assertThat(sre.getMessage()).contains("ssl exception");
    }
  }

  @Test
  public void mtlsClientServer_withClientAuthentication_withXdsChannelCreds()
      throws Exception {
    UpstreamTlsContext upstreamTlsContext = setBootstrapInfoAndBuildUpstreamTlsContext(
        CLIENT_KEY_FILE, CLIENT_PEM_FILE, null, true);
    performMtlsTestAndGetListenerWatcher(upstreamTlsContext, null, null, null, null);
  }

  @Test
  public void tlsServer_plaintextClient_expectException() throws Exception {
    DownstreamTlsContext downstreamTlsContext =
        setBootstrapInfoAndBuildDownstreamTlsContext(SERVER_1_PEM_FILE, null, null, null, null,
            null, false, false);
    buildServerWithTlsContext(downstreamTlsContext);

    SimpleServiceGrpc.SimpleServiceBlockingStub blockingStub =
        getBlockingStub(/* upstreamTlsContext= */ null, /* overrideAuthority= */ null);
    try {
      unaryRpc("buddy", blockingStub);
      fail("exception expected");
    } catch (StatusRuntimeException sre) {
      assertThat(sre.getStatus().getCode()).isEqualTo(Status.UNAVAILABLE.getCode());
      assertThat(sre.getStatus().getDescription()).contains("Network closed");
    }
  }

  @Test
  public void plaintextServer_tlsClient_expectException() throws Exception {
    buildServerWithTlsContext(/* downstreamTlsContext= */ null);

    // for TLS, client only needs trustCa
    UpstreamTlsContext upstreamTlsContext = setBootstrapInfoAndBuildUpstreamTlsContext(
        CLIENT_KEY_FILE, CLIENT_PEM_FILE, null, false);

    SimpleServiceGrpc.SimpleServiceBlockingStub blockingStub =
        getBlockingStub(upstreamTlsContext, /* overrideAuthority= */ OVERRIDE_AUTHORITY);
    try {
      unaryRpc("buddy", blockingStub);
      fail("exception expected");
    } catch (StatusRuntimeException sre) {
      assertThat(sre).hasCauseThat().isInstanceOf(NotSslRecordException.class);
      assertThat(sre).hasCauseThat().hasMessageThat().contains("not an SSL/TLS record");
    }
  }

  /** mTLS - client auth enabled then update server certs to untrusted. */
  @Test
  public void mtlsClientServer_changeServerContext_expectException()
      throws Exception {
    UpstreamTlsContext upstreamTlsContext = setBootstrapInfoAndBuildUpstreamTlsContext(
        CLIENT_KEY_FILE, CLIENT_PEM_FILE, null, true);

    performMtlsTestAndGetListenerWatcher(upstreamTlsContext, "cert-instance-name2",
            BAD_SERVER_KEY_FILE, BAD_SERVER_PEM_FILE, CA_PEM_FILE);
    DownstreamTlsContext downstreamTlsContext =
        CommonTlsContextTestsUtil.buildDownstreamTlsContext(
            "cert-instance-name2", true, true);
    EnvoyServerProtoData.Listener listener = buildListener("listener1", "0.0.0.0",
            downstreamTlsContext,
            tlsContextManagerForServer);
    xdsClient.deliverLdsUpdate(LdsUpdate.forTcpListener(listener));
    try {
      SimpleServiceGrpc.SimpleServiceBlockingStub blockingStub =
          getBlockingStub(upstreamTlsContext, OVERRIDE_AUTHORITY);
      assertThat(unaryRpc("buddy", blockingStub)).isEqualTo("Hello buddy");
      fail("exception expected");
    } catch (StatusRuntimeException sre) {
      assertThat(sre).hasCauseThat().isInstanceOf(SSLHandshakeException.class);
      assertThat(sre).hasCauseThat().hasMessageThat().isEqualTo("General OpenSslEngine problem");
    }
  }

  private void performMtlsTestAndGetListenerWatcher(
      UpstreamTlsContext upstreamTlsContext, String certInstanceName2,
      String privateKey2, String cert2, String trustCa2)
      throws Exception {
    DownstreamTlsContext downstreamTlsContext =
        setBootstrapInfoAndBuildDownstreamTlsContext(SERVER_1_PEM_FILE, certInstanceName2,
            privateKey2, cert2, trustCa2, null, true, false);

    buildServerWithFallbackServerCredentials(
            InsecureServerCredentials.create(), downstreamTlsContext);

    SimpleServiceGrpc.SimpleServiceBlockingStub blockingStub =
            getBlockingStub(upstreamTlsContext, OVERRIDE_AUTHORITY);
    assertThat(unaryRpc("buddy", blockingStub)).isEqualTo("Hello buddy");
  }

  private DownstreamTlsContext setBootstrapInfoAndBuildDownstreamTlsContext(
      String cert1, String certInstanceName2, String privateKey2,
      String cert2, String trustCa2, String spiffeFile,
      boolean hasRootCert, boolean requireClientCertificate) {
    bootstrapInfoForServer = CommonBootstrapperTestUtils
        .buildBootstrapInfo("google_cloud_private_spiffe-server", SERVER_1_KEY_FILE,
            cert1, CA_PEM_FILE, certInstanceName2, privateKey2, cert2, trustCa2, spiffeFile);
    return CommonTlsContextTestsUtil.buildDownstreamTlsContext(
        "google_cloud_private_spiffe-server", hasRootCert, requireClientCertificate);
  }

  private UpstreamTlsContext setBootstrapInfoAndBuildUpstreamTlsContext(String clientKeyFile,
      String clientPemFile, String spiffeFile, boolean hasIdentityCert) {
    bootstrapInfoForClient = CommonBootstrapperTestUtils
        .buildBootstrapInfo("google_cloud_private_spiffe-client", clientKeyFile, clientPemFile,
            CA_PEM_FILE, null, null, null, null, spiffeFile);
    return CommonTlsContextTestsUtil
        .buildUpstreamTlsContext("google_cloud_private_spiffe-client", hasIdentityCert);
  }

  private UpstreamTlsContext setBootstrapInfoAndBuildUpstreamTlsContextForUsingSystemRootCerts(
      String clientKeyFile,
      String clientPemFile,
      boolean useCombinedValidationContext) {
    bootstrapInfoForClient = CommonBootstrapperTestUtils
        .buildBootstrapInfo("google_cloud_private_spiffe-client", clientKeyFile, clientPemFile,
            CA_PEM_FILE, null, null, null, null, null);
    if (useCombinedValidationContext) {
      return CommonTlsContextTestsUtil.buildUpstreamTlsContextForCertProviderInstance(
          "google_cloud_private_spiffe-client", "ROOT", null,
          null, null,
          CertificateValidationContext.newBuilder()
              .setSystemRootCerts(
                  CertificateValidationContext.SystemRootCerts.newBuilder().build())
              .build());
    }
    return CommonTlsContextTestsUtil.buildNewUpstreamTlsContextForCertProviderInstance(
        "google_cloud_private_spiffe-client", "ROOT", null,
        null, null, CertificateValidationContext.newBuilder()
            .setSystemRootCerts(
                CertificateValidationContext.SystemRootCerts.newBuilder().build())
            .build());
  }

  private void buildServerWithTlsContext(DownstreamTlsContext downstreamTlsContext)
      throws Exception {
    buildServerWithTlsContext(downstreamTlsContext, InsecureServerCredentials.create());
  }

  private void buildServerWithTlsContext(
      DownstreamTlsContext downstreamTlsContext, ServerCredentials fallbackCredentials)
      throws Exception {
    buildServerWithFallbackServerCredentials(fallbackCredentials, downstreamTlsContext);
  }

  private void buildServerWithFallbackServerCredentials(
      ServerCredentials fallbackCredentials,
      DownstreamTlsContext downstreamTlsContext)
      throws Exception {
    ServerCredentials xdsCredentials = XdsServerCredentials.create(fallbackCredentials);
    XdsServerBuilder builder = XdsServerBuilder.forPort(0, xdsCredentials)
            .xdsClientPoolFactory(fakePoolFactory)
            .addService(new SimpleServiceImpl());
    buildServer(builder, downstreamTlsContext);
  }

  private void buildServer(
      XdsServerBuilder builder,
      DownstreamTlsContext downstreamTlsContext)
      throws Exception {
    tlsContextManagerForServer = new TlsContextManagerImpl(bootstrapInfoForServer);
    XdsServerWrapper xdsServer = (XdsServerWrapper) builder.build();
    SettableFuture<Throwable> startFuture = startServerAsync(xdsServer);
    EnvoyServerProtoData.Listener listener = buildListener("listener1", "10.1.2.3",
            downstreamTlsContext, tlsContextManagerForServer);
    LdsUpdate listenerUpdate = LdsUpdate.forTcpListener(listener);
    xdsClient.deliverLdsUpdate(listenerUpdate);
    startFuture.get(10, TimeUnit.SECONDS);
    port = xdsServer.getPort();
    URI expectedUri = new URI("sectest://localhost:" + port);
    fakeNameResolverFactory = new FakeNameResolverFactory.Builder(expectedUri).build();
    NameResolverRegistry.getDefaultRegistry().register(fakeNameResolverFactory);
  }

  static EnvoyServerProtoData.Listener buildListener(
      String name, String address, DownstreamTlsContext tlsContext,
      TlsContextManager tlsContextManager) {
    EnvoyServerProtoData.FilterChainMatch filterChainMatch =
        EnvoyServerProtoData.FilterChainMatch.create(
            0,
            ImmutableList.of(),
            ImmutableList.of(),
            ImmutableList.of(),
            ConnectionSourceType.ANY,
            ImmutableList.of(),
            ImmutableList.of(),
            "");
    String fullPath = "/" + SimpleServiceGrpc.SERVICE_NAME + "/" + "UnaryRpc";
    RouteMatch routeMatch =
            RouteMatch.create(
                    PathMatcher.fromPath(fullPath, true),
                    Collections.<HeaderMatcher>emptyList(), null);
    VirtualHost virtualHost = VirtualHost.create(
            "virtual-host", Collections.singletonList(OVERRIDE_AUTHORITY),
            Arrays.asList(Route.forAction(routeMatch, null,
                    ImmutableMap.<String, FilterConfig>of())),
            ImmutableMap.<String, FilterConfig>of());
    HttpConnectionManager httpConnectionManager = HttpConnectionManager.forVirtualHosts(
            0L, Collections.singletonList(virtualHost),
            new ArrayList<NamedFilterConfig>());
    EnvoyServerProtoData.FilterChain defaultFilterChain = EnvoyServerProtoData.FilterChain.create(
        "filter-chain-foo", filterChainMatch, httpConnectionManager, tlsContext,
        tlsContextManager);
    EnvoyServerProtoData.Listener listener = EnvoyServerProtoData.Listener.create(
        name, address, ImmutableList.of(defaultFilterChain), null);
    return listener;
  }

  private SimpleServiceGrpc.SimpleServiceBlockingStub getBlockingStub(
      final UpstreamTlsContext upstreamTlsContext, String overrideAuthority)
      throws URISyntaxException {
    ManagedChannelBuilder<?> channelBuilder =
        Grpc.newChannelBuilder(
            "sectest://localhost:" + port,
            XdsChannelCredentials.create(InsecureChannelCredentials.create()));

    if (overrideAuthority != null) {
      channelBuilder = channelBuilder.overrideAuthority(overrideAuthority);
    }
    InetSocketAddress socketAddress =
        new InetSocketAddress(Inet4Address.getLoopbackAddress(), port);
    tlsContextManagerForClient = new TlsContextManagerImpl(bootstrapInfoForClient);
    Attributes attrs =
        (upstreamTlsContext != null)
            ? Attributes.newBuilder()
                .set(InternalXdsAttributes.ATTR_SSL_CONTEXT_PROVIDER_SUPPLIER,
                    new SslContextProviderSupplier(
                        upstreamTlsContext, tlsContextManagerForClient))
                .build()
            : Attributes.EMPTY;
    fakeNameResolverFactory.setServers(
        ImmutableList.of(new EquivalentAddressGroup(socketAddress, attrs)));
    return SimpleServiceGrpc.newBlockingStub(cleanupRule.register(channelBuilder.build()));
  }

  /** Say hello to server. */
  private static String unaryRpc(
      String requestMessage, SimpleServiceGrpc.SimpleServiceBlockingStub blockingStub) {
    SimpleRequest request = SimpleRequest.newBuilder().setRequestMessage(requestMessage).build();
    SimpleResponse response = blockingStub.unaryRpc(request);
    return response.getResponseMessage();
  }

  private SettableFuture<Throwable> startServerAsync(final Server xdsServer) throws Exception {
    cleanupRule.register(xdsServer);
    final SettableFuture<Throwable> settableFuture = SettableFuture.create();
    Executors.newSingleThreadExecutor().execute(new Runnable() {
      @Override
      public void run() {
        try {
          xdsServer.start();
          settableFuture.set(null);
        } catch (Throwable e) {
          settableFuture.set(e);
        }
      }
    });
    xdsClient.ldsResource.get(8000, TimeUnit.MILLISECONDS);
    return settableFuture;
  }

  private void setTrustStoreSystemProperties(String trustStoreFilePath) throws Exception {
    System.setProperty("javax.net.ssl.trustStore", trustStoreFilePath);
    System.setProperty("javax.net.ssl.trustStorePassword", "changeit");
    System.setProperty("javax.net.ssl.trustStoreType", "JKS");
    createDefaultTrustManager();
  }

  private void clearTrustStoreSystemProperties() throws Exception {
    System.clearProperty("javax.net.ssl.trustStore");
    System.clearProperty("javax.net.ssl.trustStorePassword");
    System.clearProperty("javax.net.ssl.trustStoreType");
    createDefaultTrustManager();
  }

  /**
   * Workaround the JDK's TrustManagerStore race. TrustManagerStore has a cache for the default
   * certs based on the system properties. But updating the cache is not thread-safe and can cause a
   * half-updated cache to appear fully-updated. When both the client and server initialize their
   * trust store simultaneously, one can see a half-updated value. Creating the trust manager here
   * fixes the cache while no other threads are running and thus the client and server threads won't
   * race to update it. See https://github.com/grpc/grpc-java/issues/11678.
   */
  private void createDefaultTrustManager() throws Exception {
    TrustManagerFactory factory =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    factory.init((KeyStore) null);
  }

  private static class SimpleServiceImpl extends SimpleServiceGrpc.SimpleServiceImplBase {

    @Override
    public void unaryRpc(SimpleRequest req, StreamObserver<SimpleResponse> responseObserver) {
      SimpleResponse response =
          SimpleResponse.newBuilder()
              .setResponseMessage("Hello " + req.getRequestMessage())
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }

  private static final class FakeNameResolverFactory extends NameResolverProvider {
    final URI expectedUri;
    List<EquivalentAddressGroup> servers = ImmutableList.of();
    final ArrayList<FakeNameResolver> resolvers = new ArrayList<>();

    FakeNameResolverFactory(URI expectedUri) {
      this.expectedUri = expectedUri;
    }

    void setServers(List<EquivalentAddressGroup> servers) {
      this.servers = servers;
    }

    @Override
    public NameResolver newNameResolver(final URI targetUri, NameResolver.Args args) {
      if (!expectedUri.equals(targetUri)) {
        return null;
      }
      FakeNameResolver resolver = new FakeNameResolver();
      resolvers.add(resolver);
      return resolver;
    }

    @Override
    public String getDefaultScheme() {
      return "sectest";
    }

    @Override
    protected boolean isAvailable() {
      return true;
    }

    @Override
    protected int priority() {
      return 5;
    }

    final class FakeNameResolver extends NameResolver {
      Listener2 listener;

      @Override
      public String getServiceAuthority() {
        return expectedUri.getAuthority();
      }

      @Override
      public void start(Listener2 listener) {
        this.listener = listener;
        resolved();
      }

      @Override
      public void refresh() {
        resolved();
      }

      void resolved() {
        ResolutionResult.Builder builder = ResolutionResult.newBuilder()
            .setAddressesOrError(StatusOr.fromValue(servers));
        listener.onResult(builder.build());
      }

      @Override
      public void shutdown() {
      }

      @Override
      public String toString() {
        return "FakeNameResolver";
      }
    }

    static final class Builder {
      final URI expectedUri;

      Builder(URI expectedUri) {
        this.expectedUri = expectedUri;
      }

      FakeNameResolverFactory build() {
        return new FakeNameResolverFactory(expectedUri);
      }
    }
  }
}
