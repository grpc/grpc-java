/*
 * Copyright 2014, Google Inc. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 *    * Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 *    * Redistributions in binary form must reproduce the above
 * copyright notice, this list of conditions and the following disclaimer
 * in the documentation and/or other materials provided with the
 * distribution.
 *
 *    * Neither the name of Google Inc. nor the names of its
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package io.grpc.transport.netty;

import static io.netty.handler.codec.http2.Http2CodecUtil.DEFAULT_WINDOW_SIZE;
import static org.junit.Assert.assertNull;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.when;

import com.google.common.util.concurrent.SettableFuture;
import com.google.common.util.concurrent.Uninterruptibles;

import io.grpc.Metadata;
import io.grpc.testing.TestUtils;
import io.grpc.transport.ClientTransport;
import io.grpc.transport.ServerListener;
import io.grpc.transport.ServerStream;
import io.grpc.transport.ServerStreamListener;
import io.grpc.transport.ServerTransport;
import io.grpc.transport.ServerTransportListener;

import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.ssl.SslContext;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.File;
import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

/**
 * Tsests for {@link NettyClientTransport}.
 */
@RunWith(JUnit4.class)
public class NettyClientTransportTest {

  @Mock
  private ServerListener serverListener;

  @Mock
  private ServerTransportListener serverTransportListener;

  @Mock
  private ServerStreamListener serverStreamListener;

  @Before
  public void setup() {
    MockitoAnnotations.initMocks(this);
    when(serverListener.transportCreated(any(ServerTransport.class))).thenReturn(
            serverTransportListener);
    when(serverTransportListener.streamCreated(any(ServerStream.class), anyString(),
            any(Metadata.Headers.class))).thenReturn(serverStreamListener);
  }

  /**
   * Verifies that we can create multiple TLS client transports from the same builder.
   */
  @Test
  public void creatingMultipleTlsTransportsShouldSucceed() throws Exception {
    int port = TestUtils.pickUnusedPort();
    InetSocketAddress address = TestUtils.overrideHostFromTestCa("localhost", port);

    File serverCert = TestUtils.loadCert("server1.pem");
    File key = TestUtils.loadCert("server1.key");
    SslContext serverContext = GrpcSslContexts.forServer(serverCert, key).build();
    NettyServer server = new NettyServer(address, NioServerSocketChannel.class,
            new NioEventLoopGroup(),new NioEventLoopGroup(), serverContext, 100,
            DEFAULT_WINDOW_SIZE, DEFAULT_WINDOW_SIZE);
    server.start(serverListener);

    try {
      File clientCert = TestUtils.loadCert("ca.pem");
      SslContext clientContext = GrpcSslContexts.forClient().trustManager(clientCert).build();
      ProtocolNegotiator negotiator = ProtocolNegotiators.tls(clientContext, address);

      int numTransports = 2;
      final SettableFuture[] futures = new SettableFuture[numTransports];
      final NettyClientTransport[] transports = new NettyClientTransport[numTransports];
      for (int index = 0; index < transports.length; ++index) {
        transports[index] = new NettyClientTransport(address, NioSocketChannel.class,
                new NioEventLoopGroup(), negotiator, DEFAULT_WINDOW_SIZE, DEFAULT_WINDOW_SIZE);
        futures[index] = SettableFuture.create();
        final int ix = index;
        transports[index].start(new ClientTransport.Listener() {
          @Override
          public void transportShutdown() {
          }

          @Override
          @SuppressWarnings("unchecked")
          public void transportTerminated() {
            futures[ix].set(null);
          }
        });
      }

      // Give the transports a moment to complete negotiation before we tear them down. If we
      // try closing the transports during negotiation, the failureCause will be non-null.
      Uninterruptibles.sleepUninterruptibly(1, TimeUnit.SECONDS);

      for (int index = 0; index < transports.length; ++index) {
        transports[index].shutdown();
        futures[index].get(1, TimeUnit.SECONDS);
        assertNull(transports[index].failureCause());
      }
    } finally {
      server.shutdown();
    }
  }
}
