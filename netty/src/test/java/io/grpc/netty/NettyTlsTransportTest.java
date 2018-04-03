/*
 * Copyright 2018, gRPC Authors All rights reserved.
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

import com.google.common.collect.ImmutableList;
import io.grpc.ServerStreamTracer;
import io.grpc.internal.Channelz.Security;
import io.grpc.internal.Channelz.Tls;
import io.grpc.internal.ClientTransportFactory;
import io.grpc.internal.FakeClock;
import io.grpc.internal.GrpcUtil;
import io.grpc.internal.InternalServer;
import io.grpc.internal.ManagedClientTransport;
import io.grpc.internal.TransportTracer;
import io.grpc.internal.testing.AbstractTransportTest;
import io.grpc.internal.testing.TestUtils;
import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SupportedCipherSuiteFilter;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.security.cert.CertificateException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for Netty transport with TLS enabled. */
@RunWith(JUnit4.class)
public class NettyTlsTransportTest extends AbstractTransportTest {
  // Choose an arbitrary cipher for unit test reproducibility
  private static final String CIPHER = "TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA384";
  private final FakeClock fakeClock = new FakeClock();
  private final TransportTracer.Factory fakeClockTransportTracer = new TransportTracer.Factory(
      new TransportTracer.TimeProvider() {
        @Override
        public long currentTimeMillis() {
          return fakeClock.currentTimeMillis();
        }
      });
  private ClientTransportFactory clientFactory;

  @Override
  protected boolean haveTransportTracer() {
    return true;
  }

  @After
  public void releaseClientFactory() {
    if (clientFactory != null) {
      clientFactory.close();
    }
  }

  @Override
  protected InternalServer newServer(List<ServerStreamTracer.Factory> streamTracerFactories) {
    return newServer0(/*port=*/ 0, streamTracerFactories);
  }

  @Override
  protected InternalServer newServer(
      InternalServer server, List<ServerStreamTracer.Factory> streamTracerFactories) {
    return newServer0(server.getPort(), streamTracerFactories);
  }

  private InternalServer newServer0(
      int port, List<ServerStreamTracer.Factory> streamTracerFactories) {
    SslContext serverContext;
    try {
      File serverCert = TestUtils.loadCert("server1.pem");
      File key = TestUtils.loadCert("server1.key");
      File caCert = TestUtils.loadCert("ca.pem");
      serverContext
          = GrpcSslContexts
          .forServer(serverCert, key)
          .trustManager(caCert)
          .clientAuth(ClientAuth.REQUIRE)
          .ciphers(ImmutableList.of(CIPHER), SupportedCipherSuiteFilter.INSTANCE)
          .build();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    return NettyServerBuilder
        .forPort(port)
        .protocolNegotiator(ProtocolNegotiators.serverTls(serverContext))
        .flowControlWindow(65 * 1024)
        .setTransportTracerFactory(fakeClockTransportTracer)
        .buildTransportServer(streamTracerFactories);
  }

  @Override
  protected String testAuthority(InternalServer server) {
    InetSocketAddress address = TestUtils.testServerAddress(server.getPort());
    return GrpcUtil.authorityFromHostAndPort(address.getHostString(), address.getPort());
  }

  @Override
  protected Security getExpectedClientSecurity() {
    try {
      String localCert = TestUtils.loadX509Cert("client.pem").toString();
      String remoteCert = TestUtils.loadX509Cert("server1.pem").toString();
      return Security.withTls(
          new Tls(CIPHER, localCert, remoteCert));
    } catch (CertificateException e) {
      throw new RuntimeException(e);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  protected Security getExpectedServerSecurity() {
    try {
      String localCert = TestUtils.loadX509Cert("server1.pem").toString();
      String remoteCert = TestUtils.loadX509Cert("client.pem").toString();
      return Security.withTls(
          new Tls(CIPHER, localCert, remoteCert));
    } catch (CertificateException e) {
      throw new RuntimeException(e);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  protected void advanceClock(long offset, TimeUnit unit) {
    fakeClock.forwardNanos(unit.toNanos(offset));
  }

  @Override
  protected long currentTimeMillis() {
    return fakeClock.currentTimeMillis();
  }

  @Override
  protected ManagedClientTransport newClientTransport(InternalServer server) {
    InetSocketAddress address = TestUtils.testServerAddress(server.getPort());
    if (clientFactory == null) {
      SslContext clientContext;
      try {
        File clientCert = TestUtils.loadCert("client.pem");
        File key = TestUtils.loadCert("client.key");
        File caCert = TestUtils.loadCert("ca.pem");
        clientContext
            = GrpcSslContexts
            .forClient()
            .keyManager(clientCert, key)
            .trustManager(caCert)
            .ciphers(ImmutableList.of(CIPHER), SupportedCipherSuiteFilter.INSTANCE)
            .build();
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
      // Avoid LocalChannel for testing because LocalChannel can fail with
      // io.netty.channel.ChannelException instead of java.net.ConnectException which breaks
      // serverNotListening test.
      clientFactory = NettyChannelBuilder
          // Although specified here, address is ignored because we never call build.
          .forAddress(address)
          .flowControlWindow(65 * 1024)
          .negotiationType(NegotiationType.TLS)
          .sslContext(clientContext)
          .setTransportTracerFactory(fakeClockTransportTracer)
          .buildTransportFactory();
    }
    return clientFactory.newClientTransport(
        address,
        testAuthority(server),
        null /* agent */,
        null /* proxy */);
  }

  @Test
  @Ignore("flaky")
  @Override
  public void flowControlPushBack() {}
}
