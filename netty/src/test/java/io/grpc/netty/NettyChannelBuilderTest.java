/*
 * Copyright 2015 The gRPC Authors
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

import io.grpc.ManagedChannel;
import io.grpc.netty.InternalNettyChannelBuilder.OverrideAuthorityChecker;
import io.grpc.netty.NettyTestUtil.TrackingObjectPoolForTest;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFactory;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.local.LocalChannel;
import io.netty.handler.ssl.SslContext;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class NettyChannelBuilderTest {

  @Rule public final ExpectedException thrown = ExpectedException.none();
  private final SslContext noSslContext = null;
  
  private void shutdown(ManagedChannel mc) throws Exception {
    mc.shutdownNow();
    assertTrue(mc.awaitTermination(1, TimeUnit.SECONDS));
  }

  @Test
  public void authorityIsReadable() throws Exception {
    NettyChannelBuilder builder = NettyChannelBuilder.forAddress("original", 1234);

    ManagedChannel b = builder.build();
    try {
      assertEquals("original:1234", b.authority());
    } finally {
      shutdown(b);
    }
  }

  @Test
  public void overrideAuthorityIsReadableForAddress() throws Exception {
    NettyChannelBuilder builder = NettyChannelBuilder.forAddress("original", 1234);
    overrideAuthorityIsReadableHelper(builder, "override:5678");
  }

  @Test
  public void overrideAuthorityIsReadableForTarget() throws Exception {
    NettyChannelBuilder builder = NettyChannelBuilder.forTarget("original:1234");
    overrideAuthorityIsReadableHelper(builder, "override:5678");
  }

  @Test
  public void overrideAuthorityIsReadableForSocketAddress() throws Exception {
    NettyChannelBuilder builder = NettyChannelBuilder.forAddress(new SocketAddress(){});
    overrideAuthorityIsReadableHelper(builder, "override:5678");
  }

  private void overrideAuthorityIsReadableHelper(NettyChannelBuilder builder,
      String overrideAuthority) throws Exception {
    builder.overrideAuthority(overrideAuthority);
    ManagedChannel channel = builder.build();
    try {
      assertEquals(overrideAuthority, channel.authority());
    } finally {
      shutdown(channel);
    }
  }

  @Test
  public void overrideAllowsInvalidAuthority() {
    NettyChannelBuilder builder = new NettyChannelBuilder(new SocketAddress(){});
    InternalNettyChannelBuilder.overrideAuthorityChecker(builder, new OverrideAuthorityChecker() {
      @Override
      public String checkAuthority(String authority) {
        return authority;
      }
    });
    Object unused = builder.overrideAuthority("[invalidauthority")
        .negotiationType(NegotiationType.PLAINTEXT)
        .buildTransportFactory();
  }

  @Test
  public void failOverrideInvalidAuthority() {
    NettyChannelBuilder builder = new NettyChannelBuilder(new SocketAddress(){});

    thrown.expect(IllegalArgumentException.class);
    thrown.expectMessage("Invalid authority:");

    builder.overrideAuthority("[invalidauthority");
  }

  @Test
  public void failInvalidAuthority() {
    thrown.expect(IllegalArgumentException.class);
    thrown.expectMessage("Invalid host or port");

    Object unused =
        NettyChannelBuilder.forAddress(new InetSocketAddress("invalid_authority", 1234));
  }

  @Test
  public void sslContextCanBeNull() {
    NettyChannelBuilder builder = new NettyChannelBuilder(new SocketAddress(){});
    builder.sslContext(null);
  }

  @Test
  public void failIfSslContextIsNotClient() {
    SslContext sslContext = mock(SslContext.class);
    NettyChannelBuilder builder = new NettyChannelBuilder(new SocketAddress(){});

    thrown.expect(IllegalArgumentException.class);
    thrown.expectMessage("Server SSL context can not be used for client channel");

    builder.sslContext(sslContext);
  }

  @Test
  public void createProtocolNegotiatorByType_plaintext() {
    ProtocolNegotiator negotiator = NettyChannelBuilder.createProtocolNegotiatorByType(
        NegotiationType.PLAINTEXT,
        noSslContext, null);
    // just check that the classes are the same, and that negotiator is not null.
    assertTrue(negotiator instanceof ProtocolNegotiators.PlaintextProtocolNegotiator);
    negotiator.close();
  }

  @Test
  public void createProtocolNegotiatorByType_plaintextUpgrade() {
    ProtocolNegotiator negotiator = NettyChannelBuilder.createProtocolNegotiatorByType(
        NegotiationType.PLAINTEXT_UPGRADE,
        noSslContext, null);
    // just check that the classes are the same, and that negotiator is not null.
    assertTrue(negotiator instanceof ProtocolNegotiators.PlaintextUpgradeProtocolNegotiator);
    negotiator.close();
  }

  @Test
  public void createProtocolNegotiatorByType_tlsWithNoContext() {
    thrown.expect(NullPointerException.class);
    NettyChannelBuilder.createProtocolNegotiatorByType(
        NegotiationType.TLS,
        noSslContext, null);
  }

  @Test
  public void createProtocolNegotiatorByType_tlsWithExecutor() throws Exception {
    TrackingObjectPoolForTest executorPool = new TrackingObjectPoolForTest();
    assertEquals(false, executorPool.isInUse());
    SslContext localSslContext = GrpcSslContexts.forClient().build();
    ProtocolNegotiator negotiator = NettyChannelBuilder.createProtocolNegotiatorByType(
        NegotiationType.TLS,
        localSslContext, executorPool);
    assertEquals(true, executorPool.isInUse());
    assertNotNull(negotiator);
    negotiator.close();
    assertEquals(false, executorPool.isInUse());
  }

  @Test
  public void createProtocolNegotiatorByType_tlsWithClientContext() throws SSLException {
    ProtocolNegotiators.HostPort hostPort = ProtocolNegotiators.parseAuthority("authority:1234");

    assertEquals("authority", hostPort.host);
    assertEquals(1234, hostPort.port);
  }

  @Test
  public void createProtocolNegotiatorByType_tlsWithAuthorityFallback() throws SSLException {
    ProtocolNegotiators.HostPort hostPort = ProtocolNegotiators.parseAuthority("bad_authority");

    assertEquals("bad_authority", hostPort.host);
    assertEquals(-1, hostPort.port);
  }

  @Test
  public void negativeKeepAliveTime() {
    NettyChannelBuilder builder = NettyChannelBuilder.forTarget("fakeTarget");

    thrown.expect(IllegalArgumentException.class);
    thrown.expectMessage("keepalive time must be positive");
    builder.keepAliveTime(-1L, TimeUnit.HOURS);
  }

  @Test
  public void negativeKeepAliveTimeout() {
    NettyChannelBuilder builder = NettyChannelBuilder.forTarget("fakeTarget");

    thrown.expect(IllegalArgumentException.class);
    thrown.expectMessage("keepalive timeout must be positive");
    builder.keepAliveTimeout(-1L, TimeUnit.HOURS);
  }

  @Test
  public void assertEventLoopAndChannelType_onlyGroupProvided() {
    NettyChannelBuilder builder = NettyChannelBuilder.forTarget("fakeTarget");
    builder.eventLoopGroup(mock(EventLoopGroup.class));
    thrown.expect(IllegalStateException.class);
    thrown.expectMessage("Both EventLoopGroup and ChannelType should be provided");

    builder.assertEventLoopAndChannelType();
  }

  @Test
  public void assertEventLoopAndChannelType_onlyTypeProvided() {
    NettyChannelBuilder builder = NettyChannelBuilder.forTarget("fakeTarget");
    builder.channelType(LocalChannel.class);
    thrown.expect(IllegalStateException.class);
    thrown.expectMessage("Both EventLoopGroup and ChannelType should be provided");

    builder.assertEventLoopAndChannelType();
  }

  @Test
  public void assertEventLoopAndChannelType_onlyFactoryProvided() {
    NettyChannelBuilder builder = NettyChannelBuilder.forTarget("fakeTarget");
    builder.channelFactory(new ChannelFactory<Channel>() {
      @Override
      public Channel newChannel() {
        return null;
      }
    });
    thrown.expect(IllegalStateException.class);
    thrown.expectMessage("Both EventLoopGroup and ChannelType should be provided");

    builder.assertEventLoopAndChannelType();
  }

  @Test
  public void assertEventLoopAndChannelType_usingDefault() {
    NettyChannelBuilder builder = NettyChannelBuilder.forTarget("fakeTarget");

    builder.assertEventLoopAndChannelType();
  }

  @Test
  public void assertEventLoopAndChannelType_bothProvided() {
    NettyChannelBuilder builder = NettyChannelBuilder.forTarget("fakeTarget");
    builder.eventLoopGroup(mock(EventLoopGroup.class));
    builder.channelType(LocalChannel.class);

    builder.assertEventLoopAndChannelType();
  }

  @Test
  public void useNioTransport_shouldNotFallBack() {
    NettyChannelBuilder builder = NettyChannelBuilder.forTarget("fakeTarget");
    InternalNettyChannelBuilder.useNioTransport(builder);

    builder.assertEventLoopAndChannelType();
  }
}
