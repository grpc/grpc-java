/*
 * Copyright 2026 The gRPC Authors
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

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import io.grpc.ChannelConfigurator;
import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.NameResolverRegistry;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerCredentials;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.TlsServerCredentials;
import io.grpc.internal.testing.TestUtils;
import io.grpc.testing.TlsTesting;
import io.grpc.testing.protobuf.SimpleRequest;
import io.grpc.testing.protobuf.SimpleServiceGrpc;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.TrustManagerFactory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Integration test for verifying that when the xDS client connects to a fake xDS control plane, the
 * request contains the Authorization header with a JWT token configured via the bootstrap config.
 */
@RunWith(JUnit4.class)
public class XdsJwtCallCredsIntegrationTest {

  private Path trustStorePath;
  private Path jwtTokenFile;
  private String jwtToken;
  private Server server;
  private XdsTestControlPlaneService controlPlaneService;
  private XdsNameResolverProvider nameResolverProvider;
  private ManagedChannel channel;
  private final AtomicReference<String> receivedAuthHeader = new AtomicReference<>();
  private final CountDownLatch authHeaderLatch = new CountDownLatch(1);

  @Before
  public void setUp() throws Exception {
    setEnableXdsBootstrapCallCreds(true);

    // Client-side TLS trust setup
    trustStorePath = generateTrustStore();
    System.setProperty("javax.net.ssl.trustStore", trustStorePath.toAbsolutePath().toString());
    System.setProperty("javax.net.ssl.trustStorePassword", "changeit");
    System.setProperty("javax.net.ssl.trustStoreType", "JKS");
    createDefaultTrustManager();

    // Create JWT token file
    jwtTokenFile = Files.createTempFile("jwt-token", ".txt");
    jwtToken = generateJwtToken();
    Files.write(jwtTokenFile, jwtToken.getBytes(StandardCharsets.UTF_8));
  }

  @After
  public void tearDown() throws Exception {
    if (channel != null) {
      channel.shutdownNow();
      channel.awaitTermination(5, TimeUnit.SECONDS);
    }
    if (server != null) {
      server.shutdownNow();
      server.awaitTermination(5, TimeUnit.SECONDS);
    }
    if (nameResolverProvider != null) {
      NameResolverRegistry.getDefaultRegistry().deregister(nameResolverProvider);
    }

    System.clearProperty("javax.net.ssl.trustStore");
    System.clearProperty("javax.net.ssl.trustStorePassword");
    System.clearProperty("javax.net.ssl.trustStoreType");
    createDefaultTrustManager();

    if (trustStorePath != null) {
      Files.deleteIfExists(trustStorePath);
    }
    if (jwtTokenFile != null) {
      Files.deleteIfExists(jwtTokenFile);
    }
    setEnableXdsBootstrapCallCreds(false);
  }

  @Test
  public void jwtCallCredsAppliedToXdsControlPlane() throws Exception {
    controlPlaneService = new XdsTestControlPlaneService();

    File certFile = TestUtils.loadCert("server1.pem");
    File keyFile = TestUtils.loadCert("server1.key");
    ServerCredentials serverCreds = TlsServerCredentials.newBuilder()
        .keyManager(certFile, keyFile)
        .build();

    ServerInterceptor authCheckingInterceptor = new ServerInterceptor() {
      @Override
      public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
          ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
        String authHeader = headers.get(
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER));
        if (authHeader != null) {
          receivedAuthHeader.set(authHeader);
          authHeaderLatch.countDown();
        }
        return next.startCall(call, headers);
      }
    };

    server = Grpc.newServerBuilderForPort(0, serverCreds)
        .addService(ServerInterceptors.intercept(controlPlaneService, authCheckingInterceptor))
        .build()
        .start();

    // Setup bootstrap configuration with call_creds pointing to the JWT token file.
    Map<String, ?> bootstrapOverride = ImmutableMap.of(
        "node", ImmutableMap.of(
            "id", UUID.randomUUID().toString(),
            "cluster", "cluster0"),
        "xds_servers", Collections.singletonList(
            ImmutableMap.of(
                "server_uri", "localhost:" + server.getPort(),
                "channel_creds", Collections.singletonList(
                    ImmutableMap.of("type", "tls")
                ),
                "call_creds", Collections.singletonList(
                    ImmutableMap.of(
                        "type", "jwt_token_file",
                        "config", ImmutableMap.of(
                            "jwt_token_file", jwtTokenFile.toAbsolutePath().toString())
                    )
                ),
                "server_features", Lists.newArrayList("xds_v3")
            )
        ),
        "server_listener_resource_name_template", "grpc/server?udpa.resource.listening_address="
    );

    // Register name resolver
    nameResolverProvider = XdsNameResolverProvider.createForTest("test-xds", bootstrapOverride);
    NameResolverRegistry.getDefaultRegistry().register(nameResolverProvider);

    // Create channel and make a dummy RPC call to trigger name resolver and connection
    channel = Grpc.newChannelBuilder("test-xds:///test-server", InsecureChannelCredentials.create())
        .childChannelConfigurator(new ChannelConfigurator() {
          @Override
          public void configureChannelBuilder(ManagedChannelBuilder<?> builder) {
            builder.overrideAuthority("waterzooi.test.google.be");
          }
        })
        .build();
    SimpleServiceGrpc.SimpleServiceBlockingStub blockingStub =
        SimpleServiceGrpc.newBlockingStub(channel);

    try {
      blockingStub.unaryRpc(SimpleRequest.getDefaultInstance());
    } catch (Exception e) {
      // Expected to fail since control plane doesn't actually serve the LDS/RDS configs for
      // test-server, but the connection/stream to control plane should still happen.
    }

    // Verify control plane received the token in Authorization header
    assertThat(authHeaderLatch.await(10, TimeUnit.SECONDS)).isTrue();
    assertThat(receivedAuthHeader.get()).isEqualTo("Bearer " + jwtToken);
  }

  private static void setEnableXdsBootstrapCallCreds(boolean enable) {
    try {
      java.lang.reflect.Field field =
          io.grpc.xds.client.BootstrapperImpl.class
              .getDeclaredField("enableXdsBootstrapCallCreds");
      field.setAccessible(true);
      field.set(null, enable);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private static Path generateTrustStore() throws Exception {
    KeyStore keystore = KeyStore.getInstance("JKS");
    keystore.load(null, null);
    try (InputStream caCertStream = TlsTesting.loadCert("ca.pem")) {
      keystore.setCertificateEntry("testca",
          CertificateFactory.getInstance("X.509").generateCertificate(caCertStream));
    }
    File trustStoreFile = File.createTempFile("testca-truststore", ".jks");
    trustStoreFile.deleteOnExit();
    try (FileOutputStream out = new FileOutputStream(trustStoreFile)) {
      keystore.store(out, "changeit".toCharArray());
    }
    return trustStoreFile.toPath();
  }

  private static void createDefaultTrustManager() throws Exception {
    TrustManagerFactory factory =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    factory.init((KeyStore) null);
  }

  private static String generateJwtToken() {
    String header = "{\"alg\":\"none\",\"typ\":\"JWT\"}";
    String payload = "{\"exp\":2000000000}";
    String signature = "";

    String headerBase64 = com.google.common.io.BaseEncoding.base64Url().omitPadding()
        .encode(header.getBytes(StandardCharsets.UTF_8));
    String payloadBase64 = com.google.common.io.BaseEncoding.base64Url().omitPadding()
        .encode(payload.getBytes(StandardCharsets.UTF_8));
    String signatureBase64 = com.google.common.io.BaseEncoding.base64Url().omitPadding()
        .encode(signature.getBytes(StandardCharsets.UTF_8));

    return headerBase64 + "." + payloadBase64 + "." + signatureBase64;
  }
}
