/*
 * Copyright 2022 The gRPC Authors
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

package io.grpc.authz;

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import io.grpc.ChannelCredentials;
import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.InsecureServerCredentials;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.ServerCredentials;
import io.grpc.ServerInterceptor;
import io.grpc.StatusRuntimeException;
import io.grpc.TlsChannelCredentials;
import io.grpc.TlsServerCredentials;
import io.grpc.TlsServerCredentials.ClientAuth;
import io.grpc.internal.FakeClock;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.TlsTesting;
import io.grpc.testing.protobuf.SimpleRequest;
import io.grpc.testing.protobuf.SimpleResponse;
import io.grpc.testing.protobuf.SimpleServiceGrpc;
import java.io.Closeable;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class AuthorizationEnd2EndTest {
  public static final String SERVER_0_KEY_FILE = "server0.key";
  public static final String SERVER_0_PEM_FILE = "server0.pem";
  public static final String CLIENT_0_KEY_FILE = "client.key";
  public static final String CLIENT_0_PEM_FILE = "client.pem";
  public static final String CA_PEM_FILE = "ca.pem";

  private Server server;
  private ManagedChannel channel;

  private FakeClock fakeClock = new FakeClock();
  private File policyFile;

  private AuthorizationServerInterceptor createStaticAuthorizationInterceptor(
      String authorizationPolicy) throws Exception {
    AuthorizationServerInterceptor interceptor =
        AuthorizationServerInterceptor.create(authorizationPolicy);
    assertNotNull(interceptor);
    return interceptor;
  }

  private FileWatcherAuthorizationServerInterceptor 
      createFileWatcherAuthorizationInterceptor(File policyFile) throws Exception {
    FileWatcherAuthorizationServerInterceptor interceptor =
        FileWatcherAuthorizationServerInterceptor.create(policyFile);
    assertNotNull(interceptor);
    return interceptor;
  }

  private void initServerWithAuthzInterceptor(
      ServerInterceptor authzInterceptor, ServerCredentials serverCredentials) throws Exception {
    server = Grpc.newServerBuilderForPort(0, serverCredentials)
                .addService(new SimpleServiceImpl())
                .intercept(authzInterceptor)
                .build()
                .start();
  }

  private void createTempAuthorizationPolicy(String authorizationPolicy) throws Exception {
    policyFile = File.createTempFile("temp", "json");
    Files.write(Paths.get(policyFile.getAbsolutePath()), authorizationPolicy.getBytes(UTF_8));
  }

  private void rewriteAuthorizationPolicy(String newPolicy) throws Exception {
    assertNotNull(policyFile);
    Files.write(Paths.get(policyFile.getAbsolutePath()), newPolicy.getBytes(UTF_8));
  }

  private SimpleServiceGrpc.SimpleServiceBlockingStub getStub() {
    if (channel == null) {
      channel = Grpc.newChannelBuilderForAddress(
        "localhost", server.getPort(), InsecureChannelCredentials.create())
        .build();
    }
    return SimpleServiceGrpc.newBlockingStub(channel);
  }

  private SimpleServiceGrpc.SimpleServiceBlockingStub getStub(
      ChannelCredentials channelCredentials) {
    channel = Grpc.newChannelBuilderForAddress(
        "localhost", server.getPort(), channelCredentials)
            .overrideAuthority("foo.test.google.com.au")
            .build();
    return SimpleServiceGrpc.newBlockingStub(channel);
  }

  @After
  public void tearDown() {
    if (policyFile != null) {
      policyFile.delete();
    }
    if (server != null) {
      server.shutdown();
    }
    if (channel != null) {
      channel.shutdown();
    }
  }

  @Test
  public void staticAuthzAllowsRpcNoMatchInDenyMatchInAllowTest() throws Exception {
    String policy = "{"
        + " \"name\" : \"authz\" ,"
        + " \"deny_rules\": ["
        + "   {"
        + "     \"name\": \"deny_UnaryRpc\","
        + "     \"request\": {"
        + "       \"paths\": ["
        + "         \"*/UnaryRpc\""
        + "       ],"
        + "       \"headers\": ["
        + "         {"
        + "           \"key\": \"dev-path\","
        + "           \"values\": [\"/dev/path/*\"]"
        + "         }"
        + "       ]"
        + "     }"
        + "   }"
        + " ],"
        + " \"allow_rules\": ["
        + "   {"
        + "     \"name\": \"allow_all\""
        + "   }"
        + " ]"
        + "}";
    AuthorizationServerInterceptor interceptor = createStaticAuthorizationInterceptor(policy);
    initServerWithAuthzInterceptor(interceptor, InsecureServerCredentials.create());
    getStub().unaryRpc(SimpleRequest.getDefaultInstance());
  }

  @Test
  public void staticAuthzDeniesRpcNoMatchInDenyAndAllowTest() throws Exception {
    String policy = "{"
        + " \"name\" : \"authz\" ,"
        + " \"deny_rules\": ["
        + "   {"
        + "     \"name\": \"deny_foo\","
        + "     \"source\": {"
        + "       \"principals\": ["
        + "         \"foo\""
        + "       ]"
        + "     }"
        + "   }"
        + " ],"
        + " \"allow_rules\": ["
        + "   {"
        + "     \"name\": \"allow_ClientStreamingRpc\","
        + "     \"request\": {"
        + "       \"paths\": ["
        + "         \"*/ClientStreamingRpc\""
        + "       ]"
        + "     }"
        + "   }"
        + " ]"
        + "}";
    AuthorizationServerInterceptor interceptor = createStaticAuthorizationInterceptor(policy);
    initServerWithAuthzInterceptor(interceptor, InsecureServerCredentials.create());
    try {
      getStub().unaryRpc(SimpleRequest.getDefaultInstance());
      fail("exception expected");
    } catch (StatusRuntimeException sre) {
      assertThat(sre).hasMessageThat().isEqualTo(
          "PERMISSION_DENIED: Access Denied");
    }
  }

  @Test
  public void staticAuthzDeniesRpcMatchInDenyAndAllowTest() throws Exception {
    String policy = "{"
        + " \"name\" : \"authz\" ,"
        + " \"deny_rules\": ["
        + "   {"
        + "     \"name\": \"deny_UnaryRpc\","
        + "     \"request\": {"
        + "       \"paths\": ["
        + "         \"*/UnaryRpc\""
        + "       ]"
        + "     }"
        + "   }"
        + " ],"
        + " \"allow_rules\": ["
        + "   {"
        + "     \"name\": \"allow_UnaryRpc\","
        + "     \"request\": {"
        + "       \"paths\": ["
        + "         \"*/UnaryRpc\""
        + "       ]"
        + "     }"
        + "   }"
        + " ]"
        + "}";
    AuthorizationServerInterceptor interceptor = createStaticAuthorizationInterceptor(policy);
    initServerWithAuthzInterceptor(interceptor, InsecureServerCredentials.create());
    try {
      getStub().unaryRpc(SimpleRequest.getDefaultInstance());
      fail("exception expected");
    } catch (StatusRuntimeException sre) {
      assertThat(sre).hasMessageThat().isEqualTo(
          "PERMISSION_DENIED: Access Denied");
    }
  }

  @Test
  public void staticAuthzDeniesRpcMatchInDenyNoMatchInAllowTest() throws Exception {
    String policy = "{"
        + " \"name\" : \"authz\" ,"
        + " \"deny_rules\": ["
        + "   {"
        + "     \"name\": \"deny_UnaryRpc\","
        + "     \"request\": {"
        + "       \"paths\": ["
        + "         \"*/UnaryRpc\""
        + "       ]"
        + "     }"
        + "   }"
        + " ],"
        + " \"allow_rules\": ["
        + "   {"
        + "     \"name\": \"allow_ClientStreamingRpc\","
        + "     \"request\": {"
        + "       \"paths\": ["
        + "         \"*/ClientStreamingRpc\""
        + "       ]"
        + "     }"
        + "   }"
        + " ]"
        + "}";
    AuthorizationServerInterceptor interceptor = createStaticAuthorizationInterceptor(policy);
    initServerWithAuthzInterceptor(interceptor, InsecureServerCredentials.create());
    try {
      getStub().unaryRpc(SimpleRequest.getDefaultInstance());
      fail("exception expected");
    } catch (StatusRuntimeException sre) {
      assertThat(sre).hasMessageThat().isEqualTo(
          "PERMISSION_DENIED: Access Denied");
    }
  }

  @Test
  public void staticAuthzAllowsRpcEmptyDenyMatchInAllowTest() throws Exception {
    String policy = "{"
        + " \"name\" : \"authz\" ,"
        + " \"allow_rules\": ["
        + "   {"
        + "     \"name\": \"allow_UnaryRpc\","
        + "     \"request\": {"
        + "       \"paths\": ["
        + "         \"*/UnaryRpc\""
        + "       ]"
        + "     }"
        + "   }"
        + " ]"
        + "}";
    AuthorizationServerInterceptor interceptor = createStaticAuthorizationInterceptor(policy);
    initServerWithAuthzInterceptor(interceptor, InsecureServerCredentials.create());
    getStub().unaryRpc(SimpleRequest.getDefaultInstance());
  }

  @Test
  public void staticAuthzDeniesRpcEmptyDenyNoMatchInAllowTest() throws Exception {
    String policy = "{"
        + " \"name\" : \"authz\" ,"
        + " \"allow_rules\": ["
        + "   {"
        + "     \"name\": \"allow_ClientStreamingRpc\","
        + "     \"request\": {"
        + "       \"paths\": ["
        + "         \"*/ClientStreamingRpc\""
        + "       ]"
        + "     }"
        + "   }"
        + " ]"
        + "}";
    AuthorizationServerInterceptor interceptor = createStaticAuthorizationInterceptor(policy);
    initServerWithAuthzInterceptor(interceptor, InsecureServerCredentials.create());
    try {
      getStub().unaryRpc(SimpleRequest.getDefaultInstance());
      fail("exception expected");
    } catch (StatusRuntimeException sre) {
      assertThat(sre).hasMessageThat().isEqualTo(
          "PERMISSION_DENIED: Access Denied");
    }
  }

  @Test
  public void staticAuthzDeniesRpcWithPrincipalsFieldOnUnauthenticatedConnectionTest() 
        throws Exception {
    String policy = "{"
        + " \"name\" : \"authz\" ,"
        + " \"allow_rules\": ["
        + "   {"
        + "     \"name\": \"allow_authenticated\","
        + "     \"source\": {"
        + "       \"principals\": [\"*\", \"\"]"
        + "     }"
        + "   }"
        + " ]"
        + "}";
    AuthorizationServerInterceptor interceptor = createStaticAuthorizationInterceptor(policy);
    initServerWithAuthzInterceptor(interceptor, InsecureServerCredentials.create());
    try {
      getStub().unaryRpc(SimpleRequest.getDefaultInstance());
      fail("exception expected");
    } catch (StatusRuntimeException sre) {
      assertThat(sre).hasMessageThat().isEqualTo(
          "PERMISSION_DENIED: Access Denied");
    }
  }

  @Test
  public void staticAuthzAllowsRpcWithPrincipalsFieldOnMtlsAuthenticatedConnectionTest() 
        throws Exception {
    String policy = "{"
        + " \"name\" : \"authz\" ,"
        + " \"allow_rules\": ["
        + "   {"
        + "     \"name\": \"allow_mtls\","
        + "     \"source\": {"
        + "       \"principals\": [\"*\"]"
        + "     }"
        + "   }"
        + " ]"
        + "}";
    AuthorizationServerInterceptor interceptor = createStaticAuthorizationInterceptor(policy);
    ServerCredentials serverCredentials = TlsServerCredentials.newBuilder()
        .keyManager(TlsTesting.loadCert(SERVER_0_PEM_FILE), TlsTesting.loadCert(SERVER_0_KEY_FILE))
        .trustManager(TlsTesting.loadCert(CA_PEM_FILE))
        .clientAuth(ClientAuth.REQUIRE)
        .build();
    initServerWithAuthzInterceptor(interceptor, serverCredentials);
    ChannelCredentials channelCredentials = TlsChannelCredentials.newBuilder()
        .keyManager(TlsTesting.loadCert(CLIENT_0_PEM_FILE), TlsTesting.loadCert(CLIENT_0_KEY_FILE))
        .trustManager(TlsTesting.loadCert(CA_PEM_FILE))
        .build();
    getStub(channelCredentials).unaryRpc(SimpleRequest.getDefaultInstance());
  }

  @Test
  public void staticAuthzAllowsRpcWithPrincipalsFieldOnTlsAuthenticatedConnectionTest() 
        throws Exception {
    String policy = "{"
        + " \"name\" : \"authz\" ,"
        + " \"allow_rules\": ["
        + "   {"
        + "     \"name\": \"allow_tls\","
        + "     \"source\": {"
        + "       \"principals\": [\"\"]"
        + "     }"
        + "   }"
        + " ]"
        + "}";
    AuthorizationServerInterceptor interceptor = createStaticAuthorizationInterceptor(policy);
    ServerCredentials serverCredentials = TlsServerCredentials.newBuilder()
        .keyManager(TlsTesting.loadCert(SERVER_0_PEM_FILE), TlsTesting.loadCert(SERVER_0_KEY_FILE))
        .trustManager(TlsTesting.loadCert(CA_PEM_FILE))
        .clientAuth(ClientAuth.OPTIONAL)
        .build();
    initServerWithAuthzInterceptor(interceptor, serverCredentials);
    ChannelCredentials channelCredentials = TlsChannelCredentials.newBuilder()
        .trustManager(TlsTesting.loadCert(CA_PEM_FILE))
        .build();
    getStub(channelCredentials).unaryRpc(SimpleRequest.getDefaultInstance());
  }

  @Test
  public void fileWatcherAuthzAllowsRpcNoMatchInDenyMatchInAllowTest() throws Exception {
    String policy = "{"
        + " \"name\" : \"authz\" ,"
        + " \"deny_rules\": ["
        + "   {"
        + "     \"name\": \"deny_UnaryRpc\","
        + "     \"request\": {"
        + "       \"paths\": ["
        + "         \"*/UnaryRpc\""
        + "       ],"
        + "       \"headers\": ["
        + "         {"
        + "           \"key\": \"dev-path\","
        + "           \"values\": [\"/dev/path/*\"]"
        + "         }"
        + "       ]"
        + "     }"
        + "   }"
        + " ],"
        + " \"allow_rules\": ["
        + "   {"
        + "     \"name\": \"allow_all\""
        + "   }"
        + " ]"
        + "}";
    createTempAuthorizationPolicy(policy);
    FileWatcherAuthorizationServerInterceptor interceptor = 
        createFileWatcherAuthorizationInterceptor(policyFile);
    Closeable closeable = interceptor.scheduleRefreshes(
        100, TimeUnit.MILLISECONDS, fakeClock.getScheduledExecutorService());
    initServerWithAuthzInterceptor(interceptor, InsecureServerCredentials.create());
    closeable.close();
    getStub().unaryRpc(SimpleRequest.getDefaultInstance());
  }

  @Test
  public void fileWatcherAuthzDeniesRpcNoMatchInDenyAndAllowTest() throws Exception {
    String policy = "{"
        + " \"name\" : \"authz\" ,"
        + " \"deny_rules\": ["
        + "   {"
        + "     \"name\": \"deny_foo\","
        + "     \"source\": {"
        + "       \"principals\": ["
        + "         \"foo\""
        + "       ]"
        + "     }"
        + "   }"
        + " ],"
        + " \"allow_rules\": ["
        + "   {"
        + "     \"name\": \"allow_ClientStreamingRpc\","
        + "     \"request\": {"
        + "       \"paths\": ["
        + "         \"*/ClientStreamingRpc\""
        + "       ]"
        + "     }"
        + "   }"
        + " ]"
        + "}";
    createTempAuthorizationPolicy(policy);
    FileWatcherAuthorizationServerInterceptor interceptor = 
        createFileWatcherAuthorizationInterceptor(policyFile);
    Closeable closeable = interceptor.scheduleRefreshes(
        100, TimeUnit.MILLISECONDS, fakeClock.getScheduledExecutorService());
    initServerWithAuthzInterceptor(interceptor, InsecureServerCredentials.create());
    closeable.close();
    try {
      getStub().unaryRpc(SimpleRequest.getDefaultInstance());
      fail("exception expected");
    } catch (StatusRuntimeException sre) {
      assertThat(sre).hasMessageThat().isEqualTo(
          "PERMISSION_DENIED: Access Denied");
    }
  }

  @Test
  public void fileWatcherAuthzDeniesRpcMatchInDenyAndAllowTest() throws Exception {
    String policy = "{"
        + " \"name\" : \"authz\" ,"
        + " \"deny_rules\": ["
        + "   {"
        + "     \"name\": \"deny_UnaryRpc\","
        + "     \"request\": {"
        + "       \"paths\": ["
        + "         \"*/UnaryRpc\""
        + "       ]"
        + "     }"
        + "   }"
        + " ],"
        + " \"allow_rules\": ["
        + "   {"
        + "     \"name\": \"allow_UnaryRpc\","
        + "     \"request\": {"
        + "       \"paths\": ["
        + "         \"*/UnaryRpc\""
        + "       ]"
        + "     }"
        + "   }"
        + " ]"
        + "}";
    createTempAuthorizationPolicy(policy);
    FileWatcherAuthorizationServerInterceptor interceptor = 
        createFileWatcherAuthorizationInterceptor(policyFile);
    Closeable closeable = interceptor.scheduleRefreshes(
        100, TimeUnit.MILLISECONDS, fakeClock.getScheduledExecutorService());
    initServerWithAuthzInterceptor(interceptor, InsecureServerCredentials.create());
    closeable.close();
    try {
      getStub().unaryRpc(SimpleRequest.getDefaultInstance());
      fail("exception expected");
    } catch (StatusRuntimeException sre) {
      assertThat(sre).hasMessageThat().isEqualTo(
          "PERMISSION_DENIED: Access Denied");
    }
  }

  @Test
  public void fileWatcherAuthzDeniesRpcMatchInDenyNoMatchInAllowTest() throws Exception {
    String policy = "{"
        + " \"name\" : \"authz\" ,"
        + " \"deny_rules\": ["
        + "   {"
        + "     \"name\": \"deny_UnaryRpc\","
        + "     \"request\": {"
        + "       \"paths\": ["
        + "         \"*/UnaryRpc\""
        + "       ]"
        + "     }"
        + "   }"
        + " ],"
        + " \"allow_rules\": ["
        + "   {"
        + "     \"name\": \"allow_ClientStreamingRpc\","
        + "     \"request\": {"
        + "       \"paths\": ["
        + "         \"*/ClientStreamingRpc\""
        + "       ]"
        + "     }"
        + "   }"
        + " ]"
        + "}";
    createTempAuthorizationPolicy(policy);
    FileWatcherAuthorizationServerInterceptor interceptor = 
        createFileWatcherAuthorizationInterceptor(policyFile);
    Closeable closeable = interceptor.scheduleRefreshes(
        100, TimeUnit.MILLISECONDS, fakeClock.getScheduledExecutorService());
    initServerWithAuthzInterceptor(interceptor, InsecureServerCredentials.create());
    closeable.close();
    try {
      getStub().unaryRpc(SimpleRequest.getDefaultInstance());
      fail("exception expected");
    } catch (StatusRuntimeException sre) {
      assertThat(sre).hasMessageThat().isEqualTo(
          "PERMISSION_DENIED: Access Denied");
    }
  }

  @Test
  public void fileWatcherAuthzAllowsRpcEmptyDenyMatchInAllowTest() throws Exception {
    String policy = "{"
        + " \"name\" : \"authz\" ,"
        + " \"allow_rules\": ["
        + "   {"
        + "     \"name\": \"allow_UnaryRpc\","
        + "     \"request\": {"
        + "       \"paths\": ["
        + "         \"*/UnaryRpc\""
        + "       ]"
        + "     }"
        + "   }"
        + " ]"
        + "}";
    createTempAuthorizationPolicy(policy);
    FileWatcherAuthorizationServerInterceptor interceptor = 
        createFileWatcherAuthorizationInterceptor(policyFile);
    Closeable closeable = interceptor.scheduleRefreshes(
        100, TimeUnit.MILLISECONDS, fakeClock.getScheduledExecutorService());
    initServerWithAuthzInterceptor(interceptor, InsecureServerCredentials.create());
    closeable.close();
    getStub().unaryRpc(SimpleRequest.getDefaultInstance());
  }

  @Test
  public void fileWatcherAuthzDeniesRpcEmptyDenyNoMatchInAllowTest() throws Exception {
    String policy = "{"
        + " \"name\" : \"authz\" ,"
        + " \"allow_rules\": ["
        + "   {"
        + "     \"name\": \"allow_ClientStreamingRpc\","
        + "     \"request\": {"
        + "       \"paths\": ["
        + "         \"*/ClientStreamingRpc\""
        + "       ]"
        + "     }"
        + "   }"
        + " ]"
        + "}";
    createTempAuthorizationPolicy(policy);
    FileWatcherAuthorizationServerInterceptor interceptor = 
        createFileWatcherAuthorizationInterceptor(policyFile);
    Closeable closeable = interceptor.scheduleRefreshes(
        100, TimeUnit.MILLISECONDS, fakeClock.getScheduledExecutorService());
    initServerWithAuthzInterceptor(interceptor, InsecureServerCredentials.create());
    closeable.close();
    try {
      getStub().unaryRpc(SimpleRequest.getDefaultInstance());
      fail("exception expected");
    } catch (StatusRuntimeException sre) {
      assertThat(sre).hasMessageThat().isEqualTo(
          "PERMISSION_DENIED: Access Denied");
    }
  }

  @Test
  public void fileWatcherAuthzValidPolicyRefreshTest() throws Exception {
    String policy1 = "{"
        + " \"name\" : \"authz\" ,"
        + " \"deny_rules\": ["
        + "   {"
        + "     \"name\": \"deny_UnaryRpc\","
        + "     \"request\": {"
        + "       \"paths\": ["
        + "         \"*/UnaryRpc\""
        + "       ]"
        + "     }"
        + "   }"
        + " ],"
        + " \"allow_rules\": ["
        + "   {"
        + "     \"name\": \"allow_all\""
        + "   }"
        + " ]"
        + "}";
    createTempAuthorizationPolicy(policy1);
    FileWatcherAuthorizationServerInterceptor interceptor = 
        createFileWatcherAuthorizationInterceptor(policyFile);
    Closeable closeable = interceptor.scheduleRefreshes(
        100, TimeUnit.NANOSECONDS, fakeClock.getScheduledExecutorService());
    initServerWithAuthzInterceptor(interceptor, InsecureServerCredentials.create());
    String policy2 = "{"
        + " \"name\" : \"authz\" ,"
        + " \"allow_rules\": ["
        + "   {"
        + "     \"name\": \"allow_UnaryRpc\","
        + "     \"request\": {"
        + "       \"paths\": ["
        + "         \"*/UnaryRpc\""
        + "       ]"
        + "     }"
        + "   }"
        + " ]"
        + "}";
    rewriteAuthorizationPolicy(policy2);
    // Reload is yet to take place at 100ns. policy1 will be active here.
    assertEquals(0, fakeClock.forwardNanos(99));
    try {
      getStub().unaryRpc(SimpleRequest.getDefaultInstance());
      fail("exception expected");
    } catch (StatusRuntimeException sre) {
      assertThat(sre).hasMessageThat().isEqualTo(
          "PERMISSION_DENIED: Access Denied");
    }
    // Reload will take place making policy2 the active policy.
    assertEquals(1, fakeClock.forwardNanos(2));
    closeable.close();
    getStub().unaryRpc(SimpleRequest.getDefaultInstance());
  }

  @Test
  public void fileWatcherAuthzInvalidPolicySkipRefreshTest() throws Exception {
    String validPolicy = "{"
        + " \"name\" : \"authz\" ,"
        + " \"allow_rules\": ["
        + "   {"
        + "     \"name\": \"allow_ClientStreamingRpc\","
        + "     \"request\": {"
        + "       \"paths\": ["
        + "         \"*/ClientStreamingRpc\""
        + "       ]"
        + "     }"
        + "   }"
        + " ]"
        + "}";
    createTempAuthorizationPolicy(validPolicy);
    FileWatcherAuthorizationServerInterceptor interceptor = 
        createFileWatcherAuthorizationInterceptor(policyFile);
    Closeable closeable = interceptor.scheduleRefreshes(
        100, TimeUnit.NANOSECONDS, fakeClock.getScheduledExecutorService());
    initServerWithAuthzInterceptor(interceptor, InsecureServerCredentials.create());
    String invalidPolicy = "{}";
    rewriteAuthorizationPolicy(invalidPolicy);
    // Reload is yet to take place at 100ns. validPolicy will be active here.
    assertEquals(0, fakeClock.forwardNanos(99));
    try {
      getStub().unaryRpc(SimpleRequest.getDefaultInstance());
      fail("exception expected");
    } catch (StatusRuntimeException sre) {
      assertThat(sre).hasMessageThat().isEqualTo(
          "PERMISSION_DENIED: Access Denied");
    }
    // Reload will take place which skips the invalidPolicy. validPolicy remains
    // the active policy.
    assertEquals(1, fakeClock.forwardNanos(2));
    closeable.close();
    try {
      getStub().unaryRpc(SimpleRequest.getDefaultInstance());
      fail("exception expected");
    } catch (StatusRuntimeException sre) {
      assertThat(sre).hasMessageThat().isEqualTo(
          "PERMISSION_DENIED: Access Denied");
    }
  }

  @Test
  public void fileWatcherAuthzRecoversFromReloadTest() throws Exception {
    String validPolicy1 = "{"
        + " \"name\" : \"authz\" ,"
        + " \"allow_rules\": ["
        + "   {"
        + "     \"name\": \"allow_ClientStreamingRpc\","
        + "     \"request\": {"
        + "       \"paths\": ["
        + "         \"*/ClientStreamingRpc\""
        + "       ]"
        + "     }"
        + "   }"
        + " ]"
        + "}";
    createTempAuthorizationPolicy(validPolicy1);
    FileWatcherAuthorizationServerInterceptor interceptor = 
        createFileWatcherAuthorizationInterceptor(policyFile);
    Closeable closeable = interceptor.scheduleRefreshes(
        100, TimeUnit.NANOSECONDS, fakeClock.getScheduledExecutorService());
    initServerWithAuthzInterceptor(interceptor, InsecureServerCredentials.create());
    String invalidPolicy = "{}";
    rewriteAuthorizationPolicy(invalidPolicy);
    // Reload is yet to take place at 100ns. validPolicy1 will be active here.
    assertEquals(0, fakeClock.forwardNanos(99));
    try {
      getStub().unaryRpc(SimpleRequest.getDefaultInstance());
      fail("exception expected");
    } catch (StatusRuntimeException sre) {
      assertThat(sre).hasMessageThat().isEqualTo(
            "PERMISSION_DENIED: Access Denied");
    }
    // Reload will take place which skips the invalidPolicy. validPolicy1 remains
    // the active policy.
    assertEquals(1, fakeClock.forwardNanos(2));
    try {
      getStub().unaryRpc(SimpleRequest.getDefaultInstance());
      fail("exception expected");
    } catch (StatusRuntimeException sre) {
      assertThat(sre).hasMessageThat().isEqualTo(
            "PERMISSION_DENIED: Access Denied");
    }
    String validPolicy2 = "{"
        + " \"name\" : \"authz\" ,"
        + " \"allow_rules\": ["
        + "   {"
        + "     \"name\": \"allow_UnaryRpc\","
        + "     \"request\": {"
        + "       \"paths\": ["
        + "         \"*/UnaryRpc\""
        + "       ]"
        + "     }"
        + "   }"
        + " ]"
        + "}";
    rewriteAuthorizationPolicy(validPolicy2);
    // Next reload is yet to take place. validPolicy1 remains the active policy.
    assertEquals(0, fakeClock.forwardNanos(98));
    try {
      getStub().unaryRpc(SimpleRequest.getDefaultInstance());
      fail("exception expected");
    } catch (StatusRuntimeException sre) {
      assertThat(sre).hasMessageThat().isEqualTo(
            "PERMISSION_DENIED: Access Denied");
    }
    // Reload occurs making validPolicy2 the active policy.
    assertEquals(1, fakeClock.forwardNanos(2));
    closeable.close();
    getStub().unaryRpc(SimpleRequest.getDefaultInstance());
  }

  private static class SimpleServiceImpl extends SimpleServiceGrpc.SimpleServiceImplBase {
    @Override
    public void unaryRpc(SimpleRequest req, StreamObserver<SimpleResponse> respOb) {
      respOb.onNext(SimpleResponse.getDefaultInstance());
      respOb.onCompleted();
    }
  }
}
