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

package io.grpc.xds.sds;

import static com.google.common.truth.Truth.assertThat;

import com.google.protobuf.BoolValue;
import io.envoyproxy.envoy.api.v2.auth.CertificateValidationContext;
import io.envoyproxy.envoy.api.v2.auth.CommonTlsContext;
import io.envoyproxy.envoy.api.v2.auth.DownstreamTlsContext;
import io.envoyproxy.envoy.api.v2.auth.TlsCertificate;
import io.envoyproxy.envoy.api.v2.auth.UpstreamTlsContext;
import io.envoyproxy.envoy.api.v2.core.DataSource;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.examples.helloworld.GreeterGrpc;
import io.grpc.examples.helloworld.HelloReply;
import io.grpc.examples.helloworld.HelloRequest;
import io.grpc.internal.testing.TestUtils;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Unit tests for {@link XdsChannelBuilder} and {@link XdsServerBuilder} for plaintext/TLS/mTLS
 * modes.
 */
@RunWith(JUnit4.class)
public class XdsSdsClientServerTest {

  @Test
  public void buildsPlaintextClientServer() throws IOException {
    XdsServerBuilder serverBuilder =
        XdsServerBuilder.forPort(8080).addService(new GreeterImpl()).tlsContext(null);
    Server server = serverBuilder.build();
    server.start();

    // now build the client channel
    XdsChannelBuilder builder = XdsChannelBuilder.forTarget("localhost:8080").tlsContext(null);
    ManagedChannel channel = builder.build();
    GreeterGrpc.GreeterBlockingStub blockingStub = GreeterGrpc.newBlockingStub(channel);
    String resp = greet("buddy", blockingStub);
    assertThat(resp).isEqualTo("Hello buddy");
    server.shutdownNow();
  }

  /** Say hello to server. */
  private static String greet(String name, GreeterGrpc.GreeterBlockingStub blockingStub) {
    HelloRequest request = HelloRequest.newBuilder().setName(name).build();
    HelloReply response = blockingStub.sayHello(request);
    return response.getMessage();
  }

  /** TLS channel - no mTLS. */
  @Test
  public void buildsTlsClientServer() throws IOException {
    String server1Pem = TestUtils.loadCert("server1.pem").getAbsolutePath();
    String server1Key = TestUtils.loadCert("server1.key").getAbsolutePath();

    // TlsCert but no certCa
    TlsCertificate tlsCert =
        TlsCertificate.newBuilder()
            .setPrivateKey(DataSource.newBuilder().setFilename(server1Key).build())
            .setCertificateChain(DataSource.newBuilder().setFilename(server1Pem).build())
            .build();

    // commonTlsContext
    CommonTlsContext commonTlsContext =
        CommonTlsContext.newBuilder().addTlsCertificates(tlsCert).build();

    // build the DownstreamTlsContext
    DownstreamTlsContext tlsContext =
        DownstreamTlsContext.newBuilder()
            .setCommonTlsContext(commonTlsContext)
            .setRequireClientCertificate(BoolValue.of(false))
            .build();

    XdsServerBuilder serverBuilder =
        XdsServerBuilder.forPort(8080).addService(new GreeterImpl()).tlsContext(tlsContext);
    Server server = serverBuilder.build();
    server.start();

    // now build the client channel
    // client only needs trustCa
    String trustCa = TestUtils.loadCert("ca.pem").getAbsolutePath();
    CertificateValidationContext certContext =
        CertificateValidationContext.newBuilder()
            .setTrustedCa(DataSource.newBuilder().setFilename(trustCa).build())
            .build();

    // commonTlsContext
    CommonTlsContext commonTlsContext1 =
        CommonTlsContext.newBuilder().setValidationContext(certContext).build();

    UpstreamTlsContext tlsContext1 =
        UpstreamTlsContext.newBuilder().setCommonTlsContext(commonTlsContext1).build();

    XdsChannelBuilder builder =
        XdsChannelBuilder.forTarget("localhost:8080")
            .tlsContext(tlsContext1)
            .overrideAuthority("foo.test.google.fr");
    ManagedChannel channel = builder.build();
    GreeterGrpc.GreeterBlockingStub blockingStub = GreeterGrpc.newBlockingStub(channel);
    String resp = greet("buddy", blockingStub);
    assertThat(resp).isEqualTo("Hello buddy");
    server.shutdownNow();
  }

  /** mTLS - client auth enabled. */
  @Test
  public void buildsMtlsClientServer() throws IOException, InterruptedException {
    String server1Pem = TestUtils.loadCert("server1.pem").getAbsolutePath();
    String server1Key = TestUtils.loadCert("server1.key").getAbsolutePath();
    String trustCa = TestUtils.loadCert("ca.pem").getAbsolutePath();

    // TlsCert
    TlsCertificate tlsCert =
        TlsCertificate.newBuilder()
            .setPrivateKey(DataSource.newBuilder().setFilename(server1Key).build())
            .setCertificateChain(DataSource.newBuilder().setFilename(server1Pem).build())
            .build();

    CertificateValidationContext certContext =
        CertificateValidationContext.newBuilder()
            .setTrustedCa(DataSource.newBuilder().setFilename(trustCa).build())
            .build();

    // commonTlsContext
    CommonTlsContext commonTlsContext =
        CommonTlsContext.newBuilder()
            .addTlsCertificates(tlsCert)
            .setValidationContext(certContext)
            .build();

    // build the DownstreamTlsContext
    DownstreamTlsContext tlsContext =
        DownstreamTlsContext.newBuilder()
            .setCommonTlsContext(commonTlsContext)
            .setRequireClientCertificate(BoolValue.of(false))
            .build();

    XdsServerBuilder serverBuilder =
        XdsServerBuilder.forPort(8080).addService(new GreeterImpl()).tlsContext(tlsContext);
    Server server = serverBuilder.build();
    server.start();

    // now build the client channel
    String clientPem = TestUtils.loadCert("client.pem").getAbsolutePath();
    String clientKey = TestUtils.loadCert("client.key").getAbsolutePath();

    // TlsCert
    TlsCertificate tlsCert1 =
        TlsCertificate.newBuilder()
            .setPrivateKey(DataSource.newBuilder().setFilename(clientKey).build())
            .setCertificateChain(DataSource.newBuilder().setFilename(clientPem).build())
            .build();

    // commonTlsContext
    CommonTlsContext commonTlsContext1 =
        CommonTlsContext.newBuilder()
            .addTlsCertificates(tlsCert1)
            .setValidationContext(certContext)
            .build();

    UpstreamTlsContext tlsContext1 =
        UpstreamTlsContext.newBuilder().setCommonTlsContext(commonTlsContext1).build();

    XdsChannelBuilder builder =
        XdsChannelBuilder.forTarget("localhost:8080")
            .tlsContext(tlsContext1)
            .overrideAuthority("foo.test.google.fr");
    ManagedChannel channel = builder.build();
    GreeterGrpc.GreeterBlockingStub blockingStub = GreeterGrpc.newBlockingStub(channel);
    String resp = greet("buddy", blockingStub);
    assertThat(resp).isEqualTo("Hello buddy");
    server.shutdownNow();
  }

  private static class GreeterImpl extends GreeterGrpc.GreeterImplBase {

    @Override
    public void sayHello(HelloRequest req, StreamObserver<HelloReply> responseObserver) {
      HelloReply reply = HelloReply.newBuilder().setMessage("Hello " + req.getName()).build();
      responseObserver.onNext(reply);
      responseObserver.onCompleted();
    }
  }
}
