/*
 * Copyright 2024 The gRPC Authors
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

package io.grpc.s2a.internal.handshaker;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.google.common.testing.NullPointerTester;
import com.google.common.testing.NullPointerTester.Visibility;
import io.grpc.Channel;
import io.grpc.InsecureChannelCredentials;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.benchmarks.Utils;
import io.grpc.internal.ObjectPool;
import io.grpc.internal.SharedResourcePool;
import io.grpc.internal.TestUtils.NoopChannelLogger;
import io.grpc.netty.GrpcHttp2ConnectionHandler;
import io.grpc.netty.InternalProtocolNegotiator;
import io.grpc.netty.InternalProtocolNegotiator.ProtocolNegotiator;
import io.grpc.s2a.internal.channel.S2AHandshakerServiceChannel;
import io.grpc.s2a.internal.handshaker.S2AIdentity;
import io.grpc.s2a.internal.handshaker.S2AProtocolNegotiatorFactory.S2AProtocolNegotiator;
import io.grpc.stub.StreamObserver;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http2.Http2ConnectionDecoder;
import io.netty.handler.codec.http2.Http2ConnectionEncoder;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.util.AsciiString;
import java.io.IOException;
import java.util.Optional;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link S2AProtocolNegotiatorFactory}. */
@RunWith(JUnit4.class)
public class S2AProtocolNegotiatorFactoryTest {
  private static final S2AIdentity LOCAL_IDENTITY = S2AIdentity.fromSpiffeId("local identity");
  private final ChannelHandlerContext mockChannelHandlerContext = mock(ChannelHandlerContext.class);
  private GrpcHttp2ConnectionHandler fakeConnectionHandler;
  private String authority;
  private int port;
  private Server fakeS2AServer;
  private ObjectPool<Channel> channelPool;

  @Before
  public void setUp() throws Exception {
    port = Utils.pickUnusedPort();
    fakeS2AServer = ServerBuilder.forPort(port).addService(new S2AServiceImpl()).build();
    fakeS2AServer.start();
    channelPool = new FakeChannelPool();
    authority = "localhost:" + port;
    fakeConnectionHandler = FakeConnectionHandler.create(authority);
  }

  @After
  public void tearDown() {
    fakeS2AServer.shutdown();
  }

  @Test
  public void handlerRemoved_success() throws Exception {
    S2AProtocolNegotiatorFactory.BufferReadsHandler handler1 =
        new S2AProtocolNegotiatorFactory.BufferReadsHandler();
    S2AProtocolNegotiatorFactory.BufferReadsHandler handler2 =
        new S2AProtocolNegotiatorFactory.BufferReadsHandler();
    EmbeddedChannel channel = new EmbeddedChannel(handler1, handler2);
    channel.writeInbound("message1");
    channel.writeInbound("message2");
    channel.writeInbound("message3");
    assertThat(handler1.getReads()).hasSize(3);
    assertThat(handler2.getReads()).isEmpty();
    channel.pipeline().remove(handler1);
    assertThat(handler2.getReads()).hasSize(3);
  }

  @Test
  public void createProtocolNegotiatorFactory_nullArgument() throws Exception {
    NullPointerTester tester = new NullPointerTester().setDefault(Optional.class, Optional.empty());

    tester.testStaticMethods(S2AProtocolNegotiatorFactory.class, Visibility.PUBLIC);
  }

  @Test
  public void createProtocolNegotiator_nullArgument() throws Exception {
    ObjectPool<Channel> pool =
            SharedResourcePool.forResource(
                S2AHandshakerServiceChannel.getChannelResource(
                    "localhost:8080", InsecureChannelCredentials.create()));

    NullPointerTester tester =
        new NullPointerTester()
            .setDefault(ObjectPool.class, pool)
            .setDefault(Optional.class, Optional.empty());

    tester.testStaticMethods(S2AProtocolNegotiator.class, Visibility.PACKAGE);
  }

  @Test
  public void createProtocolNegotiatorFactory_getsDefaultPort_succeeds() throws Exception {
    InternalProtocolNegotiator.ClientFactory clientFactory =
        S2AProtocolNegotiatorFactory.createClientFactory(LOCAL_IDENTITY, channelPool, null);

    assertThat(clientFactory.getDefaultPort()).isEqualTo(S2AProtocolNegotiatorFactory.DEFAULT_PORT);
  }

  @Test
  public void s2aProtocolNegotiator_getHostNameOnNull_returnsNull() throws Exception {
    assertThat(S2AProtocolNegotiatorFactory.S2AProtocolNegotiator.getHostNameFromAuthority(null))
        .isNull();
  }

  @Test
  public void s2aProtocolNegotiator_getHostNameOnValidAuthority_returnsValidHostname()
      throws Exception {
    assertThat(
            S2AProtocolNegotiatorFactory.S2AProtocolNegotiator.getHostNameFromAuthority(
                "hostname:80"))
        .isEqualTo("hostname");
  }

  @Test
  public void createProtocolNegotiatorFactory_buildsAnS2AProtocolNegotiatorOnClientSide_succeeds()
      throws Exception {
    InternalProtocolNegotiator.ClientFactory clientFactory =
        S2AProtocolNegotiatorFactory.createClientFactory(LOCAL_IDENTITY, channelPool, null);

    ProtocolNegotiator clientNegotiator = clientFactory.newNegotiator();

    assertThat(clientNegotiator).isInstanceOf(S2AProtocolNegotiator.class);
    assertThat(clientNegotiator.scheme()).isEqualTo(AsciiString.of("https"));
  }

  @Test
  public void closeProtocolNegotiator_verifyProtocolNegotiatorIsClosedOnClientSide()
      throws Exception {
    InternalProtocolNegotiator.ClientFactory clientFactory =
        S2AProtocolNegotiatorFactory.createClientFactory(LOCAL_IDENTITY, channelPool, null);
    ProtocolNegotiator clientNegotiator = clientFactory.newNegotiator();

    clientNegotiator.close();

    assertThat(((FakeChannelPool) channelPool).isChannelCached()).isFalse();
  }

  @Test
  public void createChannelHandler_addHandlerToMockContext() throws Exception {
    ProtocolNegotiator clientNegotiator =
        S2AProtocolNegotiatorFactory.S2AProtocolNegotiator.createForClient(
            channelPool, LOCAL_IDENTITY, null);

    ChannelHandler channelHandler = clientNegotiator.newHandler(fakeConnectionHandler);

    ((ChannelDuplexHandler) channelHandler).userEventTriggered(mockChannelHandlerContext, "event");
    verify(mockChannelHandlerContext).fireUserEventTriggered("event");
  }

  /** A {@code GrpcHttp2ConnectionHandler} that does nothing. */
  private static class FakeConnectionHandler extends GrpcHttp2ConnectionHandler {
    private static final Http2ConnectionDecoder DECODER = mock(Http2ConnectionDecoder.class);
    private static final Http2ConnectionEncoder ENCODER = mock(Http2ConnectionEncoder.class);
    private static final Http2Settings SETTINGS = new Http2Settings();
    private final String authority;

    static FakeConnectionHandler create(String authority) {
      return new FakeConnectionHandler(null, DECODER, ENCODER, SETTINGS, authority);
    }

    private FakeConnectionHandler(
        ChannelPromise channelUnused,
        Http2ConnectionDecoder decoder,
        Http2ConnectionEncoder encoder,
        Http2Settings initialSettings,
        String authority) {
      super(channelUnused, decoder, encoder, initialSettings, new NoopChannelLogger());
      this.authority = authority;
    }

    @Override
    public String getAuthority() {
      return authority;
    }
  }

  /** An S2A server that handles GetTlsConfiguration request. */
  private static class S2AServiceImpl extends S2AServiceGrpc.S2AServiceImplBase {
    static final FakeWriter writer = new FakeWriter();

    @Override
    public StreamObserver<SessionReq> setUpSession(StreamObserver<SessionResp> responseObserver) {
      return new StreamObserver<SessionReq>() {
        @Override
        public void onNext(SessionReq req) {
          try {
            responseObserver.onNext(writer.handleResponse(req));
          } catch (IOException e) {
            responseObserver.onError(e);
          }
        }

        @Override
        public void onError(Throwable t) {}

        @Override
        public void onCompleted() {}
      };
    }
  }

  private static class FakeChannelPool implements ObjectPool<Channel> {
    private final Channel mockChannel = mock(Channel.class);
    private @Nullable Channel cachedChannel = null;

    @Override
    public Channel getObject() {
      if (cachedChannel == null) {
        cachedChannel = mockChannel;
      }
      return cachedChannel;
    }

    @Override
    public Channel returnObject(Object object) {
      assertThat(object).isSameInstanceAs(mockChannel);
      cachedChannel = null;
      return null;
    }

    public boolean isChannelCached() {
      return (cachedChannel != null);
    }
  }
}