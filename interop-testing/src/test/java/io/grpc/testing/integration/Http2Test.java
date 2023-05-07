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

package io.grpc.testing.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import io.grpc.ChannelCredentials;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.ServerBuilder;
import io.grpc.ServerCredentials;
import io.grpc.TlsChannelCredentials;
import io.grpc.TlsServerCredentials;
import io.grpc.internal.testing.TestUtils;
import io.grpc.netty.InternalNettyChannelBuilder;
import io.grpc.netty.InternalNettyServerBuilder;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.okhttp.InternalOkHttpChannelBuilder;
import io.grpc.okhttp.InternalOkHttpServerBuilder;
import io.grpc.okhttp.OkHttpChannelBuilder;
import io.grpc.okhttp.OkHttpServerBuilder;
import io.grpc.stub.MetadataUtils;
import io.grpc.testing.TlsTesting;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

/**
 * Integration tests for GRPC over the various HTTP2 transports.
 */
@RunWith(Parameterized.class)
public class Http2Test extends AbstractInteropTest {
  enum Transport {
    NETTY, OKHTTP;
  }

  /** Parameterized test cases. */
  @Parameters(name = "client={0},server={1}")
  public static Iterable<Object[]> data() {
    return Arrays.asList(new Object[][] {
      {Transport.NETTY, Transport.NETTY},
      {Transport.OKHTTP, Transport.OKHTTP},
      {Transport.OKHTTP, Transport.NETTY},
      {Transport.NETTY, Transport.OKHTTP},
    });
  }

  private final Transport clientType;
  private final Transport serverType;

  public Http2Test(Transport clientType, Transport serverType) {
    this.clientType = clientType;
    this.serverType = serverType;
  }

  @Override
  protected ServerBuilder<?> getServerBuilder() {
    // Starts the server with HTTPS.
    ServerCredentials serverCreds;
    try {
      serverCreds = TlsServerCredentials.create(
          TlsTesting.loadCert("server1.pem"), TlsTesting.loadCert("server1.key"));
    } catch (IOException ex) {
      throw new RuntimeException(ex);
    }
    ServerBuilder<?> builder;
    if (serverType == Transport.NETTY) {
      NettyServerBuilder nettyBuilder = NettyServerBuilder.forPort(0, serverCreds)
          .flowControlWindow(AbstractInteropTest.TEST_FLOW_CONTROL_WINDOW);
      // Disable the default census stats tracer, use testing tracer instead.
      InternalNettyServerBuilder.setStatsEnabled(nettyBuilder, false);
      builder = nettyBuilder;
    } else {
      OkHttpServerBuilder okHttpBuilder = OkHttpServerBuilder.forPort(0, serverCreds)
          .flowControlWindow(AbstractInteropTest.TEST_FLOW_CONTROL_WINDOW);
      // Disable the default census stats tracer, use testing tracer instead.
      InternalOkHttpServerBuilder.setStatsEnabled(okHttpBuilder, false);
      builder = okHttpBuilder;
    }
    return builder
        .maxInboundMessageSize(AbstractInteropTest.MAX_MESSAGE_SIZE)
        .addStreamTracerFactory(createCustomCensusTracerFactory());
  }

  @Override
  protected ManagedChannelBuilder<?> createChannelBuilder() {
    ChannelCredentials channelCreds;
    try {
      channelCreds = TlsChannelCredentials.newBuilder()
          .trustManager(TlsTesting.loadCert("ca.pem"))
          .build();
    } catch (Exception ex) {
      throw new RuntimeException(ex);
    }
    int port = ((InetSocketAddress) getListenAddress()).getPort();
    ManagedChannelBuilder<?> builder;
    if (clientType == Transport.NETTY) {
      NettyChannelBuilder nettyBuilder = NettyChannelBuilder
          .forAddress("localhost", port, channelCreds)
          .flowControlWindow(AbstractInteropTest.TEST_FLOW_CONTROL_WINDOW);
      // Disable the default census stats interceptor, use testing interceptor instead.
      InternalNettyChannelBuilder.setStatsEnabled(nettyBuilder, false);
      builder = nettyBuilder;
    } else {
      OkHttpChannelBuilder okHttpBuilder = OkHttpChannelBuilder
          .forAddress("localhost", port, channelCreds)
          .flowControlWindow(AbstractInteropTest.TEST_FLOW_CONTROL_WINDOW);
      // Disable the default census stats interceptor, use testing interceptor instead.
      InternalOkHttpChannelBuilder.setStatsEnabled(okHttpBuilder, false);
      builder = okHttpBuilder;
    }
    return builder
        .overrideAuthority(TestUtils.TEST_SERVER_HOST)
        .maxInboundMessageSize(AbstractInteropTest.MAX_MESSAGE_SIZE)
        .intercept(createCensusStatsClientInterceptor());
  }

  @Test
  public void remoteAddr() {
    InetSocketAddress isa = (InetSocketAddress) obtainRemoteClientAddr();
    assertEquals(InetAddress.getLoopbackAddress(), isa.getAddress());
    // It should not be the same as the server
    assertNotEquals(((InetSocketAddress) getListenAddress()).getPort(), isa.getPort());
  }

  @Test
  public void localAddr() throws Exception {
    InetSocketAddress isa = (InetSocketAddress) obtainLocalServerAddr();
    assertEquals(InetAddress.getLoopbackAddress(), isa.getAddress());
    assertEquals(((InetSocketAddress) getListenAddress()).getPort(), isa.getPort());
  }

  @Test
  public void contentLengthPermitted() throws Exception {
    // Some third-party gRPC implementations (e.g., ServiceTalk) include Content-Length. The HTTP/2
    // code starting in Netty 4.1.60.Final has special-cased handling of Content-Length, and may
    // call uncommon methods on our custom headers implementation.
    // https://github.com/grpc/grpc-java/issues/7953
    Metadata contentLength = new Metadata();
    contentLength.put(Metadata.Key.of("content-length", Metadata.ASCII_STRING_MARSHALLER), "5");
    blockingStub
        .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(contentLength))
        .emptyCall(EMPTY);
  }
}
