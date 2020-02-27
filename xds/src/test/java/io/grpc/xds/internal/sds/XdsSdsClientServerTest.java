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

import com.google.protobuf.BoolValue;
import io.envoyproxy.envoy.api.v2.auth.CertificateValidationContext;
import io.envoyproxy.envoy.api.v2.auth.CommonTlsContext;
import io.envoyproxy.envoy.api.v2.auth.DownstreamTlsContext;
import io.envoyproxy.envoy.api.v2.auth.TlsCertificate;
import io.envoyproxy.envoy.api.v2.auth.UpstreamTlsContext;
import io.envoyproxy.envoy.api.v2.core.DataSource;
import io.grpc.Server;
import io.grpc.internal.testing.TestUtils;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.GrpcCleanupRule;
import io.grpc.testing.protobuf.SimpleRequest;
import io.grpc.testing.protobuf.SimpleResponse;
import io.grpc.testing.protobuf.SimpleServiceGrpc;
import java.io.IOException;
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

  @Test
  public void plaintextClientServer() throws IOException {
    Server server = getXdsServer(/* downstreamTlsContext= */ null);
    buildClientAndTest(
        /* upstreamTlsContext= */ null, /* overrideAuthority= */ null, "buddy", server.getPort());
  }

  /** TLS channel - no mTLS. */
  @Test
  public void tlsClientServer_noClientAuthentication() throws IOException {
    String server1Pem = TestUtils.loadCert("server1.pem").getAbsolutePath();
    String server1Key = TestUtils.loadCert("server1.key").getAbsolutePath();

    TlsCertificate tlsCert =
        TlsCertificate.newBuilder()
            .setPrivateKey(DataSource.newBuilder().setFilename(server1Key).build())
            .setCertificateChain(DataSource.newBuilder().setFilename(server1Pem).build())
            .build();

    CommonTlsContext commonTlsContext =
        CommonTlsContext.newBuilder().addTlsCertificates(tlsCert).build();

    DownstreamTlsContext downstreamTlsContext =
        DownstreamTlsContext.newBuilder()
            .setCommonTlsContext(commonTlsContext)
            .setRequireClientCertificate(BoolValue.of(false))
            .build();

    Server server = getXdsServer(downstreamTlsContext);

    // for TLS client doesn't need cert but needs trustCa
    String trustCa = TestUtils.loadCert("ca.pem").getAbsolutePath();
    CertificateValidationContext certContext =
        CertificateValidationContext.newBuilder()
            .setTrustedCa(DataSource.newBuilder().setFilename(trustCa).build())
            .build();

    CommonTlsContext commonTlsContext1 =
        CommonTlsContext.newBuilder().setValidationContext(certContext).build();

    UpstreamTlsContext upstreamTlsContext =
        UpstreamTlsContext.newBuilder().setCommonTlsContext(commonTlsContext1).build();
    buildClientAndTest(upstreamTlsContext, "foo.test.google.fr", "buddy", server.getPort());
  }

  /** mTLS - client auth enabled. */
  @Test
  public void mtlsClientServer_withClientAuthentication() throws IOException, InterruptedException {
    String server1Pem = TestUtils.loadCert("server1.pem").getAbsolutePath();
    String server1Key = TestUtils.loadCert("server1.key").getAbsolutePath();
    String trustCa = TestUtils.loadCert("ca.pem").getAbsolutePath();

    TlsCertificate tlsCert =
        TlsCertificate.newBuilder()
            .setPrivateKey(DataSource.newBuilder().setFilename(server1Key).build())
            .setCertificateChain(DataSource.newBuilder().setFilename(server1Pem).build())
            .build();

    CertificateValidationContext certContext =
        CertificateValidationContext.newBuilder()
            .setTrustedCa(DataSource.newBuilder().setFilename(trustCa).build())
            .build();

    CommonTlsContext commonTlsContext =
        CommonTlsContext.newBuilder()
            .addTlsCertificates(tlsCert)
            .setValidationContext(certContext)
            .build();

    DownstreamTlsContext downstreamTlsContext =
        DownstreamTlsContext.newBuilder()
            .setCommonTlsContext(commonTlsContext)
            .setRequireClientCertificate(BoolValue.of(false))
            .build();

    Server server = getXdsServer(downstreamTlsContext);

    String clientPem = TestUtils.loadCert("client.pem").getAbsolutePath();
    String clientKey = TestUtils.loadCert("client.key").getAbsolutePath();

    TlsCertificate tlsCert1 =
        TlsCertificate.newBuilder()
            .setPrivateKey(DataSource.newBuilder().setFilename(clientKey).build())
            .setCertificateChain(DataSource.newBuilder().setFilename(clientPem).build())
            .build();

    CommonTlsContext commonTlsContext1 =
        CommonTlsContext.newBuilder()
            .addTlsCertificates(tlsCert1)
            .setValidationContext(certContext)
            .build();

    UpstreamTlsContext upstreamTlsContext =
        UpstreamTlsContext.newBuilder().setCommonTlsContext(commonTlsContext1).build();

    buildClientAndTest(upstreamTlsContext, "foo.test.google.fr", "buddy", server.getPort());
  }

  private Server getXdsServer(DownstreamTlsContext downstreamTlsContext) throws IOException {
    XdsServerBuilder serverBuilder =
        XdsServerBuilder.forPort(0) // get unused port
            .addService(new SimpleServiceImpl())
            .tlsContext(downstreamTlsContext);
    return cleanupRule.register(serverBuilder.build()).start();
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
