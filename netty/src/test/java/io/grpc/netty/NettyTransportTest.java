/*
 * Copyright 2016, Google Inc. All rights reserved.
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

package io.grpc.netty;

import io.grpc.Attributes;
import io.grpc.internal.ClientTransportFactory;
import io.grpc.internal.InternalServer;
import io.grpc.internal.ManagedClientTransport;
import io.grpc.internal.testing.AbstractTransportTest;
import io.grpc.netty.InternalNettyChannelBuilder.TransportCreationParamsFilter;
import io.grpc.netty.InternalNettyChannelBuilder.TransportCreationParamsFilterFactory;
import io.grpc.netty.ProtocolNegotiators.AbstractBufferingHandler;

import io.netty.channel.ChannelHandlerContext;
import io.netty.util.AsciiString;

import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import javax.annotation.Nullable;

/** Unit tests for Netty transport. */
@RunWith(JUnit4.class)
public class NettyTransportTest extends AbstractTransportTest {
  // Avoid LocalChannel for testing because LocalChannel can fail with
  // io.netty.channel.ChannelException instead of java.net.ConnectException which breaks
  // serverNotListening test.
  private ClientTransportFactory clientFactory;
  private static final Attributes PROTOCOL_NEGOTIATION_ATTRIBUTES = Attributes.newBuilder()
      .set(Attributes.Key.<String>of("fakeKey"), "fakeValue")
      .build();

  @Before
  public void setUpClientFactory() {
    NettyChannelBuilder builder = NettyChannelBuilder
        // Although specified here, address is ignored because we never call build.
        .forAddress("localhost", 0)
        .flowControlWindow(65 * 1024);

    TransportCreationParamsFilterFactory paramsFilterFactory =
        new TransportCreationParamsFilterFactory() {
          @Override
          public TransportCreationParamsFilter create(
              final SocketAddress targetServerAddress, final String authority,
              final String userAgent) {
            final ProtocolNegotiator negotiator = new ProtocolNegotiator() {
              @Override
              public Handler newHandler(GrpcHttp2ConnectionHandler handler) {
                return new TestNegotiatorHandler(handler);
              }
            };

            return new TransportCreationParamsFilter() {
              @Override
              public ProtocolNegotiator getProtocolNegotiator() {
                return negotiator;
              }

              @Override
              public SocketAddress getTargetServerAddress() {
                return targetServerAddress;
              }

              @Override
              public String getAuthority() {
                return authority;
              }

              @Nullable
              @Override
              public String getUserAgent() {
                return userAgent;
              }
            };
          }
        };

    InternalNettyChannelBuilder.setDynamicTransportParamsFactory(builder, paramsFilterFactory);
    clientFactory = builder.buildTransportFactory();

    expectedClientStreamAttributes = PROTOCOL_NEGOTIATION_ATTRIBUTES;
  }

  @After
  public void releaseClientFactory() {
    clientFactory.close();
  }

  @Override
  protected InternalServer newServer() {
    return NettyServerBuilder
        .forPort(0)
        .flowControlWindow(65 * 1024)
        .buildTransportServer();
  }

  @Override
  protected InternalServer newServer(InternalServer server) {
    int port = server.getPort();
    return NettyServerBuilder
        .forPort(port)
        .flowControlWindow(65 * 1024)
        .buildTransportServer();
  }

  @Override
  protected ManagedClientTransport newClientTransport(InternalServer server) {
    int port = server.getPort();
    return clientFactory.newClientTransport(
        new InetSocketAddress("localhost", port),
        "localhost:" + port,
        null /* agent */);
  }

  @Test
  @Ignore("flaky")
  @Override
  public void flowControlPushBack() {}

  private static final class TestNegotiatorHandler extends AbstractBufferingHandler
      implements ProtocolNegotiator.Handler {

    final GrpcHttp2ConnectionHandler handler;

    TestNegotiatorHandler(GrpcHttp2ConnectionHandler handler) {
      super(handler);
      this.handler = handler;
    }

    @Override
    public AsciiString scheme() {
      return Utils.HTTP;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
      writeBufferedAndRemove(ctx);
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
      handler.handleProtocolNegotiationCompleted(PROTOCOL_NEGOTIATION_ATTRIBUTES);
      writeBufferedAndRemove(ctx);
      super.channelActive(ctx);
    }
  }

}
