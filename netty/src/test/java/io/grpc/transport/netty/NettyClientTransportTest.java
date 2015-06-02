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

import static com.google.common.base.Charsets.UTF_8;
import static io.netty.handler.codec.http2.Http2CodecUtil.DEFAULT_WINDOW_SIZE;

import com.google.common.io.ByteStreams;
import com.google.common.util.concurrent.SettableFuture;

import io.grpc.Marshaller;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.MethodType;
import io.grpc.Status;
import io.grpc.testing.TestUtils;
import io.grpc.transport.ClientStream;
import io.grpc.transport.ClientStreamListener;
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

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

/**
 * Tsests for {@link NettyClientTransport}.
 */
@RunWith(JUnit4.class)
public class NettyClientTransportTest {
  private static final String MESSAGE = "hello";

  @Mock
  private ClientTransport.Listener clientTransportListener;

  @Before
  public void setup() {
    MockitoAnnotations.initMocks(this);
  }

  /**
   * Verifies that we can create multiple TLS client transports from the same builder.
   */
  @Test
  public void creatingMultipleTlsTransportsShouldSucceed() throws Exception {
    // Start the server.
    int port = TestUtils.pickUnusedPort();
    InetSocketAddress address = TestUtils.overrideHostFromTestCa("localhost", port);
    File serverCert = TestUtils.loadCert("server1.pem");
    File key = TestUtils.loadCert("server1.key");
    SslContext serverContext = GrpcSslContexts.forServer(serverCert, key).build();
    NettyServer server = new NettyServer(address, NioServerSocketChannel.class,
            new NioEventLoopGroup(),new NioEventLoopGroup(), serverContext, 100,
            DEFAULT_WINDOW_SIZE, DEFAULT_WINDOW_SIZE);
    server.start(new TestServerListener());

    try {
      // Create the protocol negotiator.
      File clientCert = TestUtils.loadCert("ca.pem");
      SslContext clientContext = GrpcSslContexts.forClient().trustManager(clientCert).build();
      ProtocolNegotiator negotiator = ProtocolNegotiators.tls(clientContext, address);

      // Create the client transports.
      int numTransports = 2;
      final NettyClientTransport[] transports = new NettyClientTransport[numTransports];
      for (int index = 0; index < transports.length; ++index) {
        transports[index] = new NettyClientTransport(address, NioSocketChannel.class,
                new NioEventLoopGroup(), negotiator, DEFAULT_WINDOW_SIZE, DEFAULT_WINDOW_SIZE);
        transports[index].start(clientTransportListener);
      }

      // Send a single RPC on each transport.
      final SettableFuture[] futures = new SettableFuture[numTransports];
      MethodDescriptor<String, String> method = MethodDescriptor.create(MethodType.UNARY,
              "/testService/test", 10, TimeUnit.SECONDS, StringMarshaller.INSTANCE,
              StringMarshaller.INSTANCE);
      for (int index = 0; index < transports.length; ++index) {
        futures[index] = SettableFuture.create();
        NettyClientTransport transport = transports[index];
        ClientStream stream = transport.newStream(method, new Metadata.Headers(),
                new TestClientStreamListener(futures[index]));
        stream.request(1);
        stream.writeMessage(messageStream());
        stream.halfClose();
      }

      // Wait for the RPCs to complete.
      for (SettableFuture future : futures) {
        future.get(10, TimeUnit.SECONDS);
      }
    } finally {
      server.shutdown();
    }
  }

  private static InputStream messageStream() {
    return new ByteArrayInputStream(MESSAGE.getBytes());
  }

  private static class TestClientStreamListener implements ClientStreamListener {
    private final SettableFuture<?> future;

    TestClientStreamListener(SettableFuture<?> future) {
      this.future = future;
    }

    @Override
    public void headersRead(Metadata.Headers headers) {
    }

    @Override
    public void closed(Status status, Metadata.Trailers trailers) {
      if (status.isOk()) {
        future.set(null);
      } else {
        future.setException(status.asException());
      }
    }

    @Override
    public void messageRead(InputStream message) {
    }

    @Override
    public void onReady() {
    }
  }

  private static class TestServerListener implements ServerListener {

    @Override
    public ServerTransportListener transportCreated(final ServerTransport transport) {
      return new ServerTransportListener() {

        @Override
        public ServerStreamListener streamCreated(final ServerStream stream, String method,
                                                  Metadata.Headers headers) {
          stream.request(1);
          return new ServerStreamListener() {

            @Override
            public void messageRead(InputStream message) {
              // Just echo back the message.
              stream.writeMessage(messageStream());
            }

            @Override
            public void onReady() {
            }

            @Override
            public void halfClosed() {
              // Just close when the client closes.
              stream.close(Status.OK, new Metadata.Trailers());
            }

            @Override
            public void closed(Status status) {
            }
          };
        }

        @Override
        public void transportTerminated() {
        }
      };
    }

    @Override
    public void serverShutdown() {
    }
  }

  private static class StringMarshaller implements Marshaller<String> {
    static final StringMarshaller INSTANCE = new StringMarshaller();

    @Override
    public InputStream stream(String value) {
      return new ByteArrayInputStream(value.getBytes(UTF_8));
    }

    @Override
    public String parse(InputStream stream) {
      try {
        return new String(ByteStreams.toByteArray(stream), UTF_8);
      } catch (IOException ex) {
        throw new RuntimeException(ex);
      }
    }
  }
}
