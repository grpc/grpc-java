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
import static org.junit.Assert.fail;

import io.grpc.ChannelCredentials;
import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.InsecureServerCredentials;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.ServerCredentials;
import io.grpc.StatusRuntimeException;
import io.grpc.TlsChannelCredentials;
import io.grpc.TlsServerCredentials;
import io.grpc.TlsServerCredentials.ClientAuth;
import io.grpc.internal.testing.TestUtils;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.protobuf.SimpleRequest;
import io.grpc.testing.protobuf.SimpleResponse;
import io.grpc.testing.protobuf.SimpleServiceGrpc;
import java.io.File;
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

  private void initServerWithStaticAuthz(
      String authorizationPolicy, ServerCredentials serverCredentials) throws Exception {
    AuthorizationServerInterceptor authzInterceptor = 
        AuthorizationServerInterceptor.create(authorizationPolicy);
    server = Grpc.newServerBuilderForPort(0, serverCredentials)
                .addService(new SimpleServiceImpl())
                .intercept(authzInterceptor)
                .build()
                .start();
  }

  private SimpleServiceGrpc.SimpleServiceBlockingStub getStub() {
    channel = 
        Grpc.newChannelBuilderForAddress(
            "localhost", server.getPort(), InsecureChannelCredentials.create())
            .build();
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
    initServerWithStaticAuthz(policy, InsecureServerCredentials.create());
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
    initServerWithStaticAuthz(policy, InsecureServerCredentials.create());
    try {
      getStub().unaryRpc(SimpleRequest.getDefaultInstance());
      fail("exception expected");
    } catch (StatusRuntimeException sre) {
      assertThat(sre).hasMessageThat().isEqualTo(
          "PERMISSION_DENIED: Access Denied");
    } catch (Exception e) {
      throw new AssertionError("the test failed ", e);
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
    initServerWithStaticAuthz(policy, InsecureServerCredentials.create());
    try {
      getStub().unaryRpc(SimpleRequest.getDefaultInstance());
      fail("exception expected");
    } catch (StatusRuntimeException sre) {
      assertThat(sre).hasMessageThat().isEqualTo(
          "PERMISSION_DENIED: Access Denied");
    } catch (Exception e) {
      throw new AssertionError("the test failed ", e);
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
    initServerWithStaticAuthz(policy, InsecureServerCredentials.create());
    try {
      getStub().unaryRpc(SimpleRequest.getDefaultInstance());
      fail("exception expected");
    } catch (StatusRuntimeException sre) {
      assertThat(sre).hasMessageThat().isEqualTo(
          "PERMISSION_DENIED: Access Denied");
    } catch (Exception e) {
      throw new AssertionError("the test failed ", e);
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
    initServerWithStaticAuthz(policy, InsecureServerCredentials.create());
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
    initServerWithStaticAuthz(policy, InsecureServerCredentials.create());
    try {
      getStub().unaryRpc(SimpleRequest.getDefaultInstance());
      fail("exception expected");
    } catch (StatusRuntimeException sre) {
      assertThat(sre).hasMessageThat().isEqualTo(
          "PERMISSION_DENIED: Access Denied");
    } catch (Exception e) {
      throw new AssertionError("the test failed ", e);
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
    initServerWithStaticAuthz(policy, InsecureServerCredentials.create());
    try {
      getStub().unaryRpc(SimpleRequest.getDefaultInstance());
      fail("exception expected");
    } catch (StatusRuntimeException sre) {
      assertThat(sre).hasMessageThat().isEqualTo(
          "PERMISSION_DENIED: Access Denied");
    } catch (Exception e) {
      throw new AssertionError("the test failed ", e);
    }
  }

  @Test
  public void staticAuthzAllowsRpcWithPrincipalsFieldOnMtlsAuthenticatedConnectionTest() 
        throws Exception {
    File caCertFile = TestUtils.loadCert(CA_PEM_FILE);
    File serverKey0File = TestUtils.loadCert(SERVER_0_KEY_FILE);
    File serverCert0File = TestUtils.loadCert(SERVER_0_PEM_FILE);
    File clientKey0File = TestUtils.loadCert(CLIENT_0_KEY_FILE);
    File clientCert0File = TestUtils.loadCert(CLIENT_0_PEM_FILE);
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
    ServerCredentials serverCredentials = TlsServerCredentials.newBuilder()
        .keyManager(serverCert0File, serverKey0File)
        .trustManager(caCertFile)
        .clientAuth(ClientAuth.REQUIRE)
        .build();
    initServerWithStaticAuthz(policy, serverCredentials);
    ChannelCredentials channelCredentials = TlsChannelCredentials.newBuilder()
        .keyManager(clientCert0File, clientKey0File)
        .trustManager(caCertFile)
        .build();
    getStub(channelCredentials).unaryRpc(SimpleRequest.getDefaultInstance());
  }

  @Test
  public void staticAuthzAllowsRpcWithPrincipalsFieldOnTlsAuthenticatedConnectionTest() 
        throws Exception {
    File caCertFile = TestUtils.loadCert(CA_PEM_FILE);
    File serverKey0File = TestUtils.loadCert(SERVER_0_KEY_FILE);
    File serverCert0File = TestUtils.loadCert(SERVER_0_PEM_FILE);
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
    ServerCredentials serverCredentials = TlsServerCredentials.newBuilder()
        .keyManager(serverCert0File, serverKey0File)
        .trustManager(caCertFile)
        .clientAuth(ClientAuth.OPTIONAL)
        .build();
    initServerWithStaticAuthz(policy, serverCredentials);
    ChannelCredentials channelCredentials = TlsChannelCredentials.newBuilder()
        .trustManager(caCertFile)
        .build();
    getStub(channelCredentials).unaryRpc(SimpleRequest.getDefaultInstance());
  }

  private static class SimpleServiceImpl extends SimpleServiceGrpc.SimpleServiceImplBase {
    @Override
    public void unaryRpc(SimpleRequest req, StreamObserver<SimpleResponse> respOb) {
      respOb.onNext(SimpleResponse.getDefaultInstance());
      respOb.onCompleted();
    }
  }
}
