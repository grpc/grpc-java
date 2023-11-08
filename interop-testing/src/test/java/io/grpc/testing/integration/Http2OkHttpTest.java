/*
 * Copyright 2014 The gRPC Authors
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

package io.grpc.testing.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.google.common.base.Throwables;
import com.squareup.okhttp.ConnectionSpec;
import io.grpc.ChannelCredentials;
import io.grpc.ManagedChannel;
import io.grpc.ServerBuilder;
import io.grpc.ServerCredentials;
import io.grpc.TlsChannelCredentials;
import io.grpc.TlsServerCredentials;
import io.grpc.internal.GrpcUtil;
import io.grpc.internal.testing.StreamRecorder;
import io.grpc.internal.testing.TestUtils;
import io.grpc.netty.InternalNettyServerBuilder;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.okhttp.InternalOkHttpChannelBuilder;
import io.grpc.okhttp.OkHttpChannelBuilder;
import io.grpc.okhttp.internal.Platform;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.TlsTesting;
import io.grpc.testing.integration.EmptyProtos.Empty;
import java.io.IOException;
import java.net.InetSocketAddress;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Integration tests for GRPC over Http2 using the OkHttp framework.
 */
@RunWith(JUnit4.class)
public class Http2OkHttpTest extends AbstractInteropTest {

  private static final String BAD_HOSTNAME = "I.am.a.bad.hostname";

  @Override
  protected ServerBuilder<?> getServerBuilder() {
    // Starts the server with HTTPS.
    try {
      ServerCredentials serverCreds = TlsServerCredentials.create(
          TlsTesting.loadCert("server1.pem"), TlsTesting.loadCert("server1.key"));
      NettyServerBuilder builder = NettyServerBuilder.forPort(0, serverCreds)
          .flowControlWindow(AbstractInteropTest.TEST_FLOW_CONTROL_WINDOW)
          .maxInboundMessageSize(AbstractInteropTest.MAX_MESSAGE_SIZE);
      // Disable the default census stats tracer, use testing tracer instead.
      InternalNettyServerBuilder.setStatsEnabled(builder, false);
      return builder.addStreamTracerFactory(createCustomCensusTracerFactory());
    } catch (IOException ex) {
      throw new RuntimeException(ex);
    }
  }

  @Override
  protected OkHttpChannelBuilder createChannelBuilder() {
    int port = ((InetSocketAddress) getListenAddress()).getPort();
    ChannelCredentials channelCreds;
    try {
      channelCreds = TlsChannelCredentials.newBuilder()
          .trustManager(TlsTesting.loadCert("ca.pem"))
          .build();
    } catch (IOException ex) {
      throw new RuntimeException(ex);
    }
    OkHttpChannelBuilder builder = OkHttpChannelBuilder.forAddress("localhost", port, channelCreds)
        .maxInboundMessageSize(AbstractInteropTest.MAX_MESSAGE_SIZE)
        .overrideAuthority(GrpcUtil.authorityFromHostAndPort(
            TestUtils.TEST_SERVER_HOST, port));
    // Disable the default census stats interceptor, use testing interceptor instead.
    InternalOkHttpChannelBuilder.setStatsEnabled(builder, false);
    return builder.intercept(createCensusStatsClientInterceptor());
  }

  private OkHttpChannelBuilder createChannelBuilderPreCredentialsApi() {
    int port = ((InetSocketAddress) getListenAddress()).getPort();
    OkHttpChannelBuilder builder = OkHttpChannelBuilder.forAddress("localhost", port)
        .maxInboundMessageSize(AbstractInteropTest.MAX_MESSAGE_SIZE)
        .connectionSpec(new ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
            .build())
        .overrideAuthority(GrpcUtil.authorityFromHostAndPort(
            TestUtils.TEST_SERVER_HOST, port));
    try {
      builder.sslSocketFactory(TestUtils.newSslSocketFactoryForCa(Platform.get().getProvider(),
          TestUtils.loadCert("ca.pem")));
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    // Disable the default census stats interceptor, use testing interceptor instead.
    InternalOkHttpChannelBuilder.setStatsEnabled(builder, false);
    return builder.intercept(createCensusStatsClientInterceptor());
  }

  @Test
  public void receivedDataForFinishedStream() throws Exception {
    Messages.ResponseParameters.Builder responseParameters =
        Messages.ResponseParameters.newBuilder()
        .setSize(1);
    Messages.StreamingOutputCallRequest.Builder requestBuilder =
        Messages.StreamingOutputCallRequest.newBuilder();
    for (int i = 0; i < 1000; i++) {
      requestBuilder.addResponseParameters(responseParameters);
    }

    StreamRecorder<Messages.StreamingOutputCallResponse> recorder = StreamRecorder.create();
    StreamObserver<Messages.StreamingOutputCallRequest> requestStream =
        asyncStub.fullDuplexCall(recorder);
    Messages.StreamingOutputCallRequest request = requestBuilder.build();
    requestStream.onNext(request);
    recorder.firstValue().get();
    requestStream.onError(new Exception("failed"));

    recorder.awaitCompletion();

    assertEquals(EMPTY, blockingStub.emptyCall(EMPTY));
  }

  @Test
  public void wrongHostNameFailHostnameVerification() throws Exception {
    int port = ((InetSocketAddress) getListenAddress()).getPort();
    ManagedChannel channel = createChannelBuilderPreCredentialsApi()
        .overrideAuthority(GrpcUtil.authorityFromHostAndPort(
            BAD_HOSTNAME, port))
        .build();
    TestServiceGrpc.TestServiceBlockingStub blockingStub =
        TestServiceGrpc.newBlockingStub(channel);

    Throwable actualThrown = null;
    try {
      blockingStub.emptyCall(Empty.getDefaultInstance());
    } catch (Throwable t) {
      actualThrown = t;
    }
    assertNotNull("The rpc should have been failed due to hostname verification", actualThrown);
    Throwable cause = Throwables.getRootCause(actualThrown);
    assertTrue(
        "Failed by unexpected exception: " + cause, cause instanceof SSLPeerUnverifiedException);
    channel.shutdown();
  }

  @Test
  public void hostnameVerifierWithBadHostname() throws Exception {
    int port = ((InetSocketAddress) getListenAddress()).getPort();
    ManagedChannel channel = createChannelBuilderPreCredentialsApi()
        .overrideAuthority(GrpcUtil.authorityFromHostAndPort(
            BAD_HOSTNAME, port))
        .hostnameVerifier(new HostnameVerifier() {
          @Override
          public boolean verify(String hostname, SSLSession session) {
            return true;
          }
        })
        .build();
    TestServiceGrpc.TestServiceBlockingStub blockingStub =
        TestServiceGrpc.newBlockingStub(channel);

    blockingStub.emptyCall(Empty.getDefaultInstance());

    channel.shutdown();
  }

  @Test
  public void hostnameVerifierWithCorrectHostname() throws Exception {
    int port = ((InetSocketAddress) getListenAddress()).getPort();
    ManagedChannel channel = createChannelBuilderPreCredentialsApi()
        .overrideAuthority(GrpcUtil.authorityFromHostAndPort(
            TestUtils.TEST_SERVER_HOST, port))
        .hostnameVerifier(new HostnameVerifier() {
          @Override
          public boolean verify(String hostname, SSLSession session) {
            return false;
          }
        })
        .build();
    TestServiceGrpc.TestServiceBlockingStub blockingStub =
        TestServiceGrpc.newBlockingStub(channel);

    Throwable actualThrown = null;
    try {
      blockingStub.emptyCall(Empty.getDefaultInstance());
    } catch (Throwable t) {
      actualThrown = t;
    }
    assertNotNull("The rpc should have been failed due to hostname verification", actualThrown);
    Throwable cause = Throwables.getRootCause(actualThrown);
    assertTrue(
        "Failed by unexpected exception: " + cause, cause instanceof SSLPeerUnverifiedException);
    channel.shutdown();
  }
}
