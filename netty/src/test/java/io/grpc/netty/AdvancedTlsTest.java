/*
 * Copyright 2020 The gRPC Authors
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

package io.grpc.netty;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import com.google.common.base.Throwables;
import com.google.common.util.concurrent.MoreExecutors;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.internal.testing.TestUtils;
import io.grpc.netty.TlsOptions.VerificationAuthType;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.protobuf.SimpleRequest;
import io.grpc.testing.protobuf.SimpleResponse;
import io.grpc.testing.protobuf.SimpleServiceGrpc;
import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.OpenSsl;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import java.io.File;
import java.io.IOException;
import java.net.Socket;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.Provider;
import java.security.Security;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import org.junit.After;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

/**
 * Integration tests for Netty's TLS support.
 */
@RunWith(Parameterized.class)
public class AdvancedTlsTest {

  public static enum TlsImpl {
    TCNATIVE, JDK, CONSCRYPT;
  }

  /**
   * Iterable of various configurations to use for tests.
   */
  @Parameters(name = "{0}")
  public static Iterable<Object[]> data() {
    return Arrays.asList(new Object[][] {
        {TlsImpl.TCNATIVE}, {TlsImpl.JDK}, {TlsImpl.CONSCRYPT},
    });
  }

  @Parameter(value = 0)
  public TlsImpl tlsImpl;

  private ScheduledExecutorService executor;
  private Server server;
  private ManagedChannel channel;
  private SslProvider sslProvider;
  private Provider jdkProvider;
  private SslContextBuilder clientContextBuilder;

  // setup key/certificate files
  private File server1CertFile;
  private File server1PrivateKeyFile;
  private File clientCertChainFile;
  private File clientPrivateKeyFile;
  private File badServerCertFile;
  private File badServerPrivateKeyFile;
  private File badClientCertChainFile;
  private File badClientPrivateKeyFile;
  private X509Certificate[] serverTrustedCaCerts;
  private X509Certificate[] clientTrustedCaCerts;

  @BeforeClass
  public static void loadConscrypt() {
    TestUtils.installConscryptIfAvailable();
  }

  @Before
  public void setUp() throws NoSuchAlgorithmException, IOException, CertificateException {
    executor = Executors.newSingleThreadScheduledExecutor();
    switch (tlsImpl) {
      case TCNATIVE:
        Assume.assumeTrue(OpenSsl.isAvailable());
        sslProvider = SslProvider.OPENSSL;
        break;
      case JDK:
        Assume.assumeTrue(Arrays.asList(
            SSLContext.getDefault().getSupportedSSLParameters().getCipherSuites())
            .contains("TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256"));
        sslProvider = SslProvider.JDK;
        jdkProvider = Security.getProvider("SunJSSE");
        Assume.assumeNotNull(jdkProvider);
        try {
          // Check for presence of an (ironic) class added in Java 9
          Class.forName("java.lang.Runtime$Version");
          // Java 9+
        } catch (ClassNotFoundException ignored) {
          // Before Java 9
          try {
            GrpcSslContexts.configure(SslContextBuilder.forClient(), jdkProvider);
          } catch (IllegalArgumentException ex) {
            Assume.assumeNoException("Not Java 9+ and Jetty ALPN does not seem available", ex);
          }
        }
        break;
      case CONSCRYPT:
        sslProvider = SslProvider.JDK;
        jdkProvider = Security.getProvider("Conscrypt");
        Assume.assumeNotNull(jdkProvider);
        break;
      default:
        throw new AssertionError();
    }
    clientContextBuilder = SslContextBuilder.forClient();
    if (sslProvider == SslProvider.JDK) {
      GrpcSslContexts.configure(clientContextBuilder, jdkProvider);
    } else {
      GrpcSslContexts.configure(clientContextBuilder, sslProvider);
    }
    server1CertFile = TestUtils.loadCert("server1.pem");
    server1PrivateKeyFile = TestUtils.loadCert("server1.key");
    clientCertChainFile = TestUtils.loadCert("client.pem");
    clientPrivateKeyFile = TestUtils.loadCert("client.key");
    badServerCertFile = TestUtils.loadCert("badserver.pem");
    badServerPrivateKeyFile = TestUtils.loadCert("badserver.key");
    badClientCertChainFile = TestUtils.loadCert("badclient.pem");
    badClientPrivateKeyFile = TestUtils.loadCert("badclient.key");
    serverTrustedCaCerts = new X509Certificate[]{
        TestUtils.loadX509Cert("ca.pem")
    };
    clientTrustedCaCerts = new X509Certificate[]{
        TestUtils.loadX509Cert("ca.pem")
    };
  }

  @After
  public void tearDown() {
    if (server != null) {
      server.shutdown();
    }
    if (channel != null) {
      channel.shutdown();
    }
    MoreExecutors.shutdownAndAwaitTermination(executor, 5, TimeUnit.SECONDS);
  }

  /**
   * Tests the basic creation and verification logic of {@code ConfigurableX509TrustManager}.
   */
  @Test
  public void basicConfigurableX509TrustManagerTest() throws Exception {
    // Expect the verification function to fail if choosing to verify certificates, while the
    // certificates provided are null.
    TlsOptions nullCertOptions = new SimpleTlsOptions(
        VerificationAuthType.CertificateAndHostNameVerification, null, true, true);
    ConfigurableX509TrustManager nullCertManager = new ConfigurableX509TrustManager(
        nullCertOptions);
    try {
      nullCertManager.checkClientTrusted(null, "");
      Assert.fail("An exception should haven been raised already.");;
    } catch (CertificateException e) {
      assertEquals(
          "Want certificate verification but got null or empty certificates", e.getMessage());
    }
    // Expect the verification function to fail if choosing to verify hostname, while the
    // SslEngine provided is null.
    TlsOptions nullSslEngineOptions = new SimpleTlsOptions(
        VerificationAuthType.CertificateAndHostNameVerification, null, true, true);
    ConfigurableX509TrustManager nullSslEngineManager = new ConfigurableX509TrustManager(
        nullSslEngineOptions);
    try {
      nullSslEngineManager.checkServerTrusted(new X509Certificate[1], "");
      Assert.fail("An exception should haven been raised already.");
    } catch (CertificateException e) {
      assertEquals(
          "SSLEngine is null. Couldn't check host name", e.getMessage());
    }
    // Expect to fail if the reloading returns an IO error.
    TlsOptions badReloadingOptions = new SimpleTlsOptions(
        VerificationAuthType.CertificateAndHostNameVerification, null, true, false);
    ConfigurableX509TrustManager badReloadingManager = new ConfigurableX509TrustManager(
        badReloadingOptions);
    try {
      Socket socket = new Socket();
      badReloadingManager.checkServerTrusted(new X509Certificate[1], "", socket);
      Assert.fail("An exception should haven been raised already.");
    } catch (CertificateException e) {
      assertEquals(
          "Failed loading trusted certs", e.getMessage());
    }
    // Expect to succeed if choosing to not verify anything.
    TlsOptions goodOptions = new SimpleTlsOptions(
        VerificationAuthType.SkipAllVerification, null, true, true);
    ConfigurableX509TrustManager goodManager = new ConfigurableX509TrustManager(
        goodOptions);
    try {
      goodManager.checkServerTrusted(new X509Certificate[1], "");
      goodManager.checkClientTrusted(new X509Certificate[1], "");
      Socket socket = new Socket();
      goodManager.checkServerTrusted(new X509Certificate[1], "", socket);
      goodManager.checkClientTrusted(new X509Certificate[1], "", socket);
    } catch (CertificateException e) {
      fail(e.getMessage());
    }
  }

  /**
   * Tests that a client and a server configured using different ConfigurableX509TrustManager(s)
   * will behave as expected.
   * This test is mainly focused on the trusting behavior on the client side.
   */
  @Test
  public void basicClientSideIntegrationTest() throws Exception {
    // Create & start a server.
    server = serverBuilder(0, server1CertFile, server1PrivateKeyFile,
        VerificationAuthType.CertificateAndHostNameVerification, true)
        .addService(new SimpleServiceImpl())
        .build()
        .start();

    // Load client side certificates into |ks|.
    final KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
    ks.load(null, null);
    int i = 1;
    for (X509Certificate cert: clientTrustedCaCerts) {
      String alias = Integer.toString(i);
      ks.setCertificateEntry(alias, cert);
      i++;
    }
    // Client side overrides the authority name and does both certificate and hostname check.
    TlsOptions checkAllOptions = new SimpleTlsOptions(
        VerificationAuthType.CertificateAndHostNameVerification, ks, true, true);
    // This is the basic mTLS integration test and should work.
    makeRpcCall(checkAllOptions, clientCertChainFile, clientPrivateKeyFile, true, false);
    // Client side doesn't overrides the authority name but does certificate and hostname check.
    // This is expected to fail because of the mismatch between the authority name and the name on
    // the server cert.
    makeRpcCall(checkAllOptions, clientCertChainFile, clientPrivateKeyFile, false, true);
    // Client side doesn't override the authority name and does certificate check only.
    TlsOptions checkCertOptions = new SimpleTlsOptions(
        VerificationAuthType.CertificateVerification, ks, true, true);
    // This should work because we doesn't check the authority name against the name on the server
    // cert.
    makeRpcCall(checkCertOptions, clientCertChainFile, clientPrivateKeyFile, false, false);
    // Create & start a server sending bad certificates, which should cause failure rpc calls.
    server = serverBuilder(0, badServerCertFile, badServerPrivateKeyFile,
        VerificationAuthType.CertificateAndHostNameVerification, true)
        .addService(new SimpleServiceImpl())
        .build()
        .start();
    makeRpcCall(checkCertOptions, clientCertChainFile, clientPrivateKeyFile, false, true);
    // Client side doesn't override the authority name or check anything, and server sends a bad
    // certificate.
    TlsOptions noCheckOptions = new SimpleTlsOptions(
        VerificationAuthType.SkipAllVerification, ks, true, true);
    // This should work because we don't check any thing.
    makeRpcCall(noCheckOptions, clientCertChainFile, clientPrivateKeyFile, false, false);
    // All previous working scenarios are expected to fail if we use a custom check that always
    // fails.
    TlsOptions noCheckOptionsAlwayFail = new SimpleTlsOptions(
        VerificationAuthType.SkipAllVerification, ks, false, true);
    makeRpcCall(noCheckOptionsAlwayFail, clientCertChainFile, clientPrivateKeyFile,
        false, true);
  }

  /**
   * Tests that a client and a server configured using different ConfigurableX509TrustManager will
   * behave as expected.
   * This test is mainly focused on the trusting behavior on the server side.
   */
  @Test
  public void basicServerSideIntegrationTest() throws Exception {
    // Create & start a server.
    server = serverBuilder(0, server1CertFile, server1PrivateKeyFile,
        VerificationAuthType.CertificateVerification, true)
        .addService(new SimpleServiceImpl())
        .build()
        .start();

    // Load client side certificates into |ks|.
    final KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
    ks.load(null, null);

    int i = 1;
    for (X509Certificate cert: clientTrustedCaCerts) {
      String alias = Integer.toString(i);
      ks.setCertificateEntry(alias, cert);
      i++;
    }
    TlsOptions checkAllOptions = new SimpleTlsOptions(
        VerificationAuthType.CertificateAndHostNameVerification, ks, true, true);
    // This is the basic mTLS integration test and should work.
    makeRpcCall(checkAllOptions, clientCertChainFile, clientPrivateKeyFile, true, false);

    // Load client side certificates into |ks|.
    final KeyStore ksBad = KeyStore.getInstance(KeyStore.getDefaultType());
    ksBad.load(null, null);

    i = 1;
    for (X509Certificate cert: clientTrustedCaCerts) {
      String alias = Integer.toString(i);
      ksBad.setCertificateEntry(alias, cert);
      i++;
    }
    // Client side will send bad certificate.
    checkAllOptions = new SimpleTlsOptions(
        VerificationAuthType.CertificateAndHostNameVerification, ksBad, true, true);
    // This is expected to fail because client sends a bad certificate.
    makeRpcCall(checkAllOptions, badClientCertChainFile, badClientPrivateKeyFile, true, true);
    // Create & start a server that doesn't check anything.
    server = serverBuilder(0, server1CertFile, server1PrivateKeyFile,
        VerificationAuthType.SkipAllVerification, true)
        .addService(new SimpleServiceImpl())
        .build()
        .start();
    // Server side doesn't check certificate, so this should work even client sends bad certificate.
    makeRpcCall(checkAllOptions, badClientCertChainFile, badClientPrivateKeyFile, true, false);
    // Create & start a server that doesn't check anything, but always fails on custom check.
    server = serverBuilder(0, server1CertFile, server1PrivateKeyFile,
        VerificationAuthType.SkipAllVerification, false)
        .addService(new SimpleServiceImpl())
        .build()
        .start();
    // This is expected to fail because server side check fails on custom check.
    makeRpcCall(checkAllOptions, badClientCertChainFile, badClientPrivateKeyFile, true, true);
  }

  private void makeRpcCall(TlsOptions options, File clientCertChainFile, File clientPrivateKeyFile,
      boolean setAuthority, boolean expectError) throws Exception {
    SslContext sslContext = clientContextBuilder
        .keyManager(clientCertChainFile, clientPrivateKeyFile)
        .trustManager(new ConfigurableX509TrustManager(options))
        .build();
    if (setAuthority) {
      channel = NettyChannelBuilder.forAddress("localhost", server.getPort())
          .overrideAuthority(TestUtils.TEST_SERVER_HOST)
          .negotiationType(NegotiationType.TLS)
          .sslContext(sslContext)
          .build();
    } else {
      channel = NettyChannelBuilder.forAddress("localhost", server.getPort())
          .negotiationType(NegotiationType.TLS)
          .sslContext(sslContext)
          .build();
    }
    try {
      SimpleServiceGrpc.SimpleServiceBlockingStub client =
          SimpleServiceGrpc.newBlockingStub(channel);
      // Send an actual request, via the full GRPC & network stack, and check that a proper
      // response comes back.
      client.unaryRpc(SimpleRequest.getDefaultInstance());
    } catch (StatusRuntimeException e) {
      if (!expectError) {
        fail("Didn't expect error but find error: " + e.getMessage());
      }
      assertEquals(
          Throwables.getStackTraceAsString(e),
          Status.Code.UNAVAILABLE, e.getStatus().getCode());
      return;
    }
    if (expectError) {
      fail("Expect error but didn't find any error");
    }
  }

  // SimpleTlsOptions is a simplified implementation of TlsOptions that will return the same custom
  // check result every time.
  static class SimpleTlsOptions extends TlsOptions {

    private KeyStore ks;
    private boolean goodCheck;
    private boolean goodReload;
    private VerificationAuthType verificationType;

    public SimpleTlsOptions(VerificationAuthType verificationAuthType,
        KeyStore ks, boolean goodCheck, boolean goodReload) {
      this.ks = ks;
      this.goodCheck = goodCheck;
      this.goodReload = goodReload;
      this.verificationType = verificationAuthType;
    }

    @Override
    VerificationAuthType getVerificationAuthType() {
      return this.verificationType;
    }

    @Override
    void verifyPeerCertificate(X509Certificate[] peerCertChain, String authType,
        SSLEngine engine) throws CertificateException {
      if (!this.goodCheck) {
        throw new CertificateException("Custom check fails");
      }
    }

    @Override
    KeyStore getTrustedCerts() throws IOException {
      if (!this.goodReload) {
        throw new IOException("Reload fails");
      }
      return this.ks;
    }
  }

  private ServerBuilder<?> serverBuilder(int port, File serverCertChainFile,
      File serverPrivateKeyFile, VerificationAuthType authType, boolean customCheckResult)
      throws IOException, CertificateException,
      NoSuchAlgorithmException, KeyStoreException {
    SslContextBuilder sslContextBuilder
        = SslContextBuilder.forServer(serverCertChainFile, serverPrivateKeyFile);
    if (sslProvider == SslProvider.JDK) {
      GrpcSslContexts.configure(sslContextBuilder, jdkProvider);
    } else {
      GrpcSslContexts.configure(sslContextBuilder, sslProvider);
    }
    final KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
    ks.load(null, null);

    int i = 1;
    for (X509Certificate cert: serverTrustedCaCerts) {
      String alias = Integer.toString(i);
      ks.setCertificateEntry(alias, cert);
      i++;
    }
    TlsOptions options = new SimpleTlsOptions(
        authType, ks, customCheckResult, true);
    TrustManager tm = new ConfigurableX509TrustManager(options);
    sslContextBuilder.trustManager(tm)
        .clientAuth(ClientAuth.REQUIRE);

    return NettyServerBuilder.forPort(port)
        .sslContext(sslContextBuilder.build());
  }

  private static class SimpleServiceImpl extends SimpleServiceGrpc.SimpleServiceImplBase {
    @Override
    public void unaryRpc(SimpleRequest req, StreamObserver<SimpleResponse> respOb) {
      respOb.onNext(SimpleResponse.getDefaultInstance());
      respOb.onCompleted();
    }
  }
}
