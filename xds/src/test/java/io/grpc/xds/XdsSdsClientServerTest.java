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
import static io.grpc.xds.XdsClientWrapperForServerSdsTest.buildFilterChainMatch;
import static org.junit.Assert.fail;

import io.envoyproxy.envoy.api.v2.auth.DownstreamTlsContext;
import io.envoyproxy.envoy.api.v2.auth.UpstreamTlsContext;
import io.grpc.Server;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.GrpcCleanupRule;
import io.grpc.testing.protobuf.SimpleRequest;
import io.grpc.testing.protobuf.SimpleResponse;
import io.grpc.testing.protobuf.SimpleServiceGrpc;
import io.grpc.xds.internal.sds.CommonTlsContextTestsUtil;
import io.grpc.xds.internal.sds.SdsProtocolNegotiators;
import io.grpc.xds.internal.sds.SecretVolumeSslContextProviderTest;
import io.grpc.xds.internal.sds.XdsChannelBuilder;
import io.grpc.xds.internal.sds.XdsServerBuilder;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.Arrays;
import javax.net.ssl.SSLHandshakeException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Unit tests for {@link XdsChannelBuilder} and {@link XdsServerBuilder} for plaintext/TLS/mTLS
 * modes.
 */
@RunWith(JUnit4.class)
public class XdsSdsClientServerTest {

  @Rule public final GrpcCleanupRule cleanupRule = new GrpcCleanupRule();
  private Server server;

  @Test
  public void plaintextClientServer() throws IOException {
    getXdsServer(/* downstreamTlsContext= */ null);
    buildClientAndTest(
        /* upstreamTlsContext= */ null, /* overrideAuthority= */ null, "buddy", server.getPort());
  }

  /** TLS channel - no mTLS. */
  @Test
  public void tlsClientServer_noClientAuthentication() throws IOException {
    DownstreamTlsContext downstreamTlsContext =
        SecretVolumeSslContextProviderTest.buildDownstreamTlsContextFromFilenames(
            CommonTlsContextTestsUtil.SERVER_1_KEY_FILE,
            CommonTlsContextTestsUtil.SERVER_1_PEM_FILE,
            null);

    getXdsServer(downstreamTlsContext);

    // for TLS, client only needs trustCa
    UpstreamTlsContext upstreamTlsContext =
        SecretVolumeSslContextProviderTest.buildUpstreamTlsContextFromFilenames(
            null, null, CommonTlsContextTestsUtil.CA_PEM_FILE);
    buildClientAndTest(upstreamTlsContext, "foo.test.google.fr", "buddy", server.getPort());
  }

  /** mTLS - client auth enabled. */
  @Test
  public void mtlsClientServer_withClientAuthentication() throws IOException {
    UpstreamTlsContext upstreamTlsContext =
        SecretVolumeSslContextProviderTest.buildUpstreamTlsContextFromFilenames(
            CommonTlsContextTestsUtil.CLIENT_KEY_FILE,
            CommonTlsContextTestsUtil.CLIENT_PEM_FILE,
            CommonTlsContextTestsUtil.CA_PEM_FILE);
    performMtlsTestAndGetListenerWatcher(upstreamTlsContext);
  }

  /** mTLS - client auth enabled then update server certs to untrusted. */
  @Test
  public void mtlsClientServer_changeServerContext_expectException() throws IOException {
    UpstreamTlsContext upstreamTlsContext =
        SecretVolumeSslContextProviderTest.buildUpstreamTlsContextFromFilenames(
            CommonTlsContextTestsUtil.CLIENT_KEY_FILE,
            CommonTlsContextTestsUtil.CLIENT_PEM_FILE,
            CommonTlsContextTestsUtil.CA_PEM_FILE);
    XdsClient.ListenerWatcher listenerWatcher = performMtlsTestAndGetListenerWatcher(
        upstreamTlsContext);
    DownstreamTlsContext downstreamTlsContext =
        SecretVolumeSslContextProviderTest.buildDownstreamTlsContextFromFilenames(
            CommonTlsContextTestsUtil.BAD_SERVER_KEY_FILE,
            CommonTlsContextTestsUtil.BAD_SERVER_PEM_FILE,
            CommonTlsContextTestsUtil.CA_PEM_FILE);
    XdsClientWrapperForServerSdsTest
        .generateListenerUpdateToWatcher(server.getPort(), downstreamTlsContext, listenerWatcher);
    try {
      buildClientAndTest(upstreamTlsContext, "foo.test.google.fr", "buddy", server.getPort());
      fail("exception expected");
    } catch (StatusRuntimeException sre) {
      assertThat(sre).hasCauseThat().isInstanceOf(SSLHandshakeException.class);
      assertThat(sre).hasCauseThat().hasMessageThat().isEqualTo("General OpenSslEngine problem");
    }
  }

  private XdsClient.ListenerWatcher performMtlsTestAndGetListenerWatcher(
      UpstreamTlsContext upstreamTlsContext)
      throws IOException {
    DownstreamTlsContext downstreamTlsContext =
        SecretVolumeSslContextProviderTest.buildDownstreamTlsContextFromFilenames(
            CommonTlsContextTestsUtil.SERVER_1_KEY_FILE,
            CommonTlsContextTestsUtil.SERVER_1_PEM_FILE,
            CommonTlsContextTestsUtil.CA_PEM_FILE);

    XdsClient.ListenerWatcher listenerWatcher = getXdsServer(downstreamTlsContext);
    buildClientAndTest(upstreamTlsContext, "foo.test.google.fr", "buddy", server.getPort());
    return listenerWatcher;
  }

  private XdsClient.ListenerWatcher getXdsServer(DownstreamTlsContext downstreamTlsContext)
      throws IOException {
    int port = findFreePort();
    XdsServerBuilder builder =
        XdsServerBuilder.forPort(port).addService(new SimpleServiceImpl());
    final XdsClientWrapperForServerSds xdsClientWrapperForServerSds =
        XdsClientWrapperForServerSdsTest
            .createXdsClientWrapperForServerSds(port, downstreamTlsContext);
    SdsProtocolNegotiators.ServerSdsProtocolNegotiator serverSdsProtocolNegotiator =
        new SdsProtocolNegotiators.ServerSdsProtocolNegotiator(xdsClientWrapperForServerSds);
    server = cleanupRule.register(builder.buildServer(serverSdsProtocolNegotiator)).start();
    return xdsClientWrapperForServerSds.getListenerWatcher();
  }

  private static int findFreePort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      socket.setReuseAddress(true);
      return socket.getLocalPort();
    }
  }

  static EnvoyServerProtoData.Listener buildListener(
      String name,
      String address,
      int port,
      DownstreamTlsContext tlsContext) {
    EnvoyServerProtoData.FilterChainMatch filterChainMatch =
        buildFilterChainMatch(port, address);
    EnvoyServerProtoData.FilterChain filterChain1 =
        new EnvoyServerProtoData.FilterChain(filterChainMatch, tlsContext);
    EnvoyServerProtoData.Listener listener =
        new EnvoyServerProtoData.Listener(name, address, Arrays.asList(filterChain1));
    return listener;
  }

  private void buildClientAndTest(
      UpstreamTlsContext upstreamTlsContext,
      String overrideAuthority,
      String requestMessage,
      int serverPort) {

    XdsChannelBuilder builder =
        XdsChannelBuilder.forTarget("localhost:" + serverPort).tlsContext(upstreamTlsContext);
    if (overrideAuthority != null) {
      builder = builder.overrideAuthority(overrideAuthority);
    }
    SimpleServiceGrpc.SimpleServiceBlockingStub blockingStub =
        SimpleServiceGrpc.newBlockingStub(cleanupRule.register(builder.build()));
    String resp = unaryRpc(requestMessage, blockingStub);
    assertThat(resp).isEqualTo("Hello " + requestMessage);
  }

  /** Say hello to server. */
  private static String unaryRpc(
      String requestMessage, SimpleServiceGrpc.SimpleServiceBlockingStub blockingStub) {
    SimpleRequest request = SimpleRequest.newBuilder().setRequestMessage(requestMessage).build();
    SimpleResponse response = blockingStub.unaryRpc(request);
    return response.getResponseMessage();
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
}
