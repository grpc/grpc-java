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
import io.grpc.StatusException;
import io.grpc.testing.TestUtils;
import io.grpc.transport.ClientStream;
import io.grpc.transport.ClientStreamListener;
import io.grpc.transport.ClientTransport;
import io.grpc.transport.ServerListener;
import io.grpc.transport.ServerStream;
import io.grpc.transport.ServerStreamListener;
import io.grpc.transport.ServerTransport;
import io.grpc.transport.ServerTransportListener;

import io.netty.channel.Channel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.ssl.SslContext;

import org.junit.After;
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
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Tests for {@link NettyClientTransport}.
 */
@RunWith(JUnit4.class)
public class NettyClientTransportTest {
  private static final int MAX_STREAMS_PER_CONNECTION = 1;
  private static final String MESSAGE = "hello";
  private static final MethodDescriptor<String, String> METHOD = MethodDescriptor.create(
          MethodType.UNARY, "/testService/test", 10, TimeUnit.SECONDS, StringMarshaller.INSTANCE,
          StringMarshaller.INSTANCE);

  @Mock
  private ClientTransport.Listener clientTransportListener;

  private final List<NettyClientTransport> transports = new ArrayList<NettyClientTransport>();
  private NioEventLoopGroup group;
  private InetSocketAddress address;
  private NettyServer server;
  private TestServerListener serverListener = new TestServerListener();

  @Before
  public void setup() throws Exception {
    MockitoAnnotations.initMocks(this);

    group = new NioEventLoopGroup(1);

    // Start the server.
    address = TestUtils.testServerAddress(TestUtils.pickUnusedPort());
    File serverCert = TestUtils.loadCert("server1.pem");
    File key = TestUtils.loadCert("server1.key");
    SslContext serverContext = GrpcSslContexts.forServer(serverCert, key).build();
    server = new NettyServer(address, TestServerSocketChannel.class,
            group, group, serverContext, MAX_STREAMS_PER_CONNECTION,
            DEFAULT_WINDOW_SIZE, DEFAULT_WINDOW_SIZE);
    server.start(serverListener);
  }

  @After
  public void teardown() throws Exception {
    for (NettyClientTransport transport : transports) {
      transport.shutdown();
    }

    if (server != null) {
      server.shutdown();
    }

    group.shutdownGracefully(0, 10, TimeUnit.SECONDS);
  }

  /**
   * Verifies that we can create multiple TLS client transports from the same builder.
   */
  @Test
  public void creatingMultipleTlsTransportsShouldSucceed() throws Exception {
    // Create the protocol negotiator.
    File clientCert = TestUtils.loadCert("ca.pem");
    SslContext clientContext = GrpcSslContexts.forClient().trustManager(clientCert).build();
    ProtocolNegotiator negotiator = ProtocolNegotiators.tls(clientContext, address);

    // Create a couple client transports.
    for (int index = 0; index < 2; ++index) {
      NettyClientTransport transport = new NettyClientTransport(address, NioSocketChannel.class,
              group, negotiator, DEFAULT_WINDOW_SIZE, DEFAULT_WINDOW_SIZE);
      transports.add(transport);
      transport.start(clientTransportListener);
    }

    // Send a single RPC on each transport.
    final List<SettableFuture> rpcFutures = new ArrayList<SettableFuture>(transports.size());
    for (NettyClientTransport transport : transports) {
      TestClientStreamListener listener = new TestClientStreamListener();
      rpcFutures.add(listener.closedFuture);
      ClientStream stream = transport.newStream(METHOD, new Metadata.Headers(), listener);
      stream.request(1);
      stream.writeMessage(messageStream());
      stream.halfClose();
    }

    // Wait for the RPCs to complete.
    for (SettableFuture rpcFuture : rpcFutures) {
      rpcFuture.get(10, TimeUnit.SECONDS);
    }
  }

  @Test
  public void bufferedStreamsShouldBeClosedWhenConnectionTerminates() throws Exception {
    // Create the protocol negotiator.
    File clientCert = TestUtils.loadCert("ca.pem");
    SslContext clientContext = GrpcSslContexts.forClient().trustManager(clientCert).build();
    ProtocolNegotiator negotiator = ProtocolNegotiators.tls(clientContext, address);

    NettyClientTransport transport = new NettyClientTransport(address, NioSocketChannel.class,
            group, negotiator, DEFAULT_WINDOW_SIZE, DEFAULT_WINDOW_SIZE);
    transports.add(transport);
    transport.start(clientTransportListener);

    // Wait for the settings to be propagated to the client so that it will respect
    // SETTINGS_MAX_CONCURRENT_STREAMS.
    Thread.sleep(1000);

    // Create 3 streams. The transport will buffer the second and third.
    TestClientStreamListener[] listeners = new TestClientStreamListener[] {
        new TestClientStreamListener(), new TestClientStreamListener(),
        new TestClientStreamListener() };
    for (TestClientStreamListener listener : listeners) {
      ClientStream stream = transport.newStream(METHOD, new Metadata.Headers(), listener);
      stream.request(1);
      stream.writeMessage(messageStream());
      stream.flush();
    }
    // Wait for the response for the stream that was actually created.
    listeners[0].responseFuture.get(10, TimeUnit.SECONDS);

    // Now forcibly terminate the connection from the server side.
    TestSocketChannel socketChannel =
            (TestSocketChannel) serverListener.transports.get(0).channel();
    socketChannel.forceShutdown();

    // Now wait for both listeners to be closed.
    for (TestClientStreamListener listener : listeners) {
      try {
        listener.closedFuture.get(10, TimeUnit.SECONDS);
      } catch (ExecutionException e) {
        // Expected.
      }
    }
  }

  private static InputStream messageStream() {
    return new ByteArrayInputStream(MESSAGE.getBytes());
  }

  private static class TestClientStreamListener implements ClientStreamListener {
    private final SettableFuture<Void> closedFuture;
    private final SettableFuture<Void> responseFuture;

    TestClientStreamListener() {
      this.closedFuture = SettableFuture.create();
      this.responseFuture = SettableFuture.create();
    }

    @Override
    public void headersRead(Metadata.Headers headers) {
    }

    @Override
    public void closed(Status status, Metadata.Trailers trailers) {
      if (status.isOk()) {
        closedFuture.set(null);
      } else {
        StatusException e = status.asException();
        closedFuture.setException(e);
        responseFuture.setException(e);
      }
    }

    @Override
    public void messageRead(InputStream message) {
      responseFuture.set(null);
    }

    @Override
    public void onReady() {
    }
  }

  /**
   * A channel that accepts incoming {@link TestSocketChannel} channels from the client.
   */
  public static class TestServerSocketChannel extends NioServerSocketChannel {
    @Override
    protected int doReadMessages(List<Object> buf) throws Exception {
      SocketChannel ch = javaChannel().accept();

      try {
        if (ch != null) {
          buf.add(new TestSocketChannel(this, ch));
          return 1;
        }
      } catch (Throwable t) {
        ch.close();
      }

      return 0;
    }
  }

  /**
   * A hack that allows us direct access to the underlying {@link java.nio.channels.SocketChannel}
   * so that we can forcibly terminate the connection.
   */
  private static class TestSocketChannel extends NioSocketChannel {
    public TestSocketChannel(Channel parent, SocketChannel socket) {
      super(parent, socket);
    }

    /**
     * Forcibly terminates the socket connection.
     */
    void forceShutdown() throws IOException {
      javaChannel().close();
    }
  }

  private static class TestServerListener implements ServerListener {
    final List<NettyServerTransport> transports = new ArrayList<NettyServerTransport>();

    @Override
    public ServerTransportListener transportCreated(final ServerTransport transport) {
      transports.add((NettyServerTransport) transport);
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
              stream.flush();
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
