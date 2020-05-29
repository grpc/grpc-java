/*
 * Copyright 2016 The gRPC Authors
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
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.common.truth.Truth;
import io.grpc.ServerStreamTracer.Factory;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.local.LocalServerChannel;
import io.netty.handler.ssl.SslContext;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Unit tests for {@link NettyServerBuilder}.
 */
@RunWith(JUnit4.class)
public class NettyServerBuilderTest {

  private NettyServerBuilder builder = NettyServerBuilder.forPort(8080);

  @Test
  public void createMultipleServers() {
    builder.addListenAddress(new InetSocketAddress(8081));
    List<NettyServer> servers = builder.buildTransportServers(ImmutableList.<Factory>of());

    Truth.assertThat(servers).hasSize(2);
  }

  @Test
  public void sslContextCanBeNull() {
    builder.sslContext(null);
  }

  @Test
  public void failIfSslContextIsNotServer() {
    SslContext sslContext = mock(SslContext.class);
    when(sslContext.isClient()).thenReturn(true);

    try {
      builder.sslContext(sslContext);
      fail();
    } catch (IllegalArgumentException ex) {
      assertEquals("Client SSL context can not be used for server", ex.getMessage());
    }

  }

  @Test
  public void failIfKeepAliveTimeNegative() {

    try {
      builder.keepAliveTime(-10L, TimeUnit.HOURS);
      fail();
    } catch (IllegalArgumentException ex) {
      assertEquals("keepalive time must be positive:-10", ex.getMessage());
    }
  }

  @Test
  public void failIfKeepAliveTimeoutNegative() {
    try {
      builder.keepAliveTimeout(-10L, TimeUnit.HOURS);
      fail();
    } catch (IllegalArgumentException ex) {
      assertEquals("keepalive timeout must be positive: -10", ex.getMessage());
    }
  }

  @Test
  public void failIfMaxConcurrentCallsPerConnectionNegative() {
    try {
      builder.maxConcurrentCallsPerConnection(0);
      fail();
    } catch (IllegalArgumentException ex) {
      assertEquals("max must be positive: 0", ex.getMessage());
    }
  }

  @Test
  public void failIfMaxInboundMetadataSizeNonPositive() {
    try {
      builder.maxInboundMetadataSize(0);
      fail();
    } catch (IllegalArgumentException ex) {
      assertEquals("maxInboundMetadataSize must be positive: 0", ex.getMessage());
    }
  }

  @Test
  public void failIfMaxConnectionIdleNegative() {
    try {
      builder.maxConnectionIdle(-1, TimeUnit.HOURS);
      fail();
    } catch (IllegalArgumentException ex) {
      assertEquals("max connection idle must be positive: -1", ex.getMessage());
    }
  }

  @Test
  public void failIfMaxConnectionAgeNegative() {
    try {
      builder.maxConnectionAge(-1, TimeUnit.HOURS);
      fail();
    } catch (IllegalArgumentException ex) {
      assertEquals("max connection age must be positive: -1", ex.getMessage());
    }
  }

  @Test
  public void failIfMaxConnectionAgeGraceNegative() {
    try {
      builder.maxConnectionAgeGrace(-1, TimeUnit.HOURS);
      fail();
    } catch (IllegalArgumentException ex) {
      assertEquals("max connection age grace must be non-negative: -1", ex.getMessage());
    }
  }

  @Test
  public void failIfPermitKeepAliveTimeNegative() {
    try {
      builder.permitKeepAliveTime(-1, TimeUnit.HOURS);
      fail();
    } catch (IllegalArgumentException ex) {
      assertEquals("permit keepalive time must be non-negative: -1", ex.getMessage());
    }
  }

  @Test
  public void assertEventLoopsAndChannelType_onlyBossGroupProvided() {
    EventLoopGroup mockEventLoopGroup = mock(EventLoopGroup.class);
    builder.bossEventLoopGroup(mockEventLoopGroup);

    try {
      builder.assertEventLoopsAndChannelType();
      fail();
    } catch (IllegalStateException ex) {
      assertEquals(
          "All of BossEventLoopGroup, WorkerEventLoopGroup and ChannelType should be provided or neither should be",
          ex.getMessage());
    }
  }

  @Test
  public void assertEventLoopsAndChannelType_onlyWorkerGroupProvided() {
    EventLoopGroup mockEventLoopGroup = mock(EventLoopGroup.class);
    builder.workerEventLoopGroup(mockEventLoopGroup);
    try {
      builder.assertEventLoopsAndChannelType();
      fail();
    } catch (IllegalStateException ex) {
      assertEquals(
          "All of BossEventLoopGroup, WorkerEventLoopGroup and ChannelType should be provided or neither should be",
          ex.getMessage());
    }
  }

  @Test
  public void assertEventLoopsAndChannelType_onlyTypeProvided() {
    builder.channelType(LocalServerChannel.class);
    try {
      builder.assertEventLoopsAndChannelType();
      fail();
    } catch (IllegalStateException ex) {
      assertEquals(
          "All of BossEventLoopGroup, WorkerEventLoopGroup and ChannelType should be provided or neither should be",
          ex.getMessage());
    }
  }

  @Test
  public void assertEventLoopsAndChannelType_usingDefault() {
    builder.assertEventLoopsAndChannelType();
  }

  @Test
  public void assertEventLoopsAndChannelType_allProvided() {
    EventLoopGroup mockEventLoopGroup = mock(EventLoopGroup.class);

    builder.bossEventLoopGroup(mockEventLoopGroup);
    builder.workerEventLoopGroup(mockEventLoopGroup);
    builder.channelType(LocalServerChannel.class);

    builder.assertEventLoopsAndChannelType();
  }

  @Test
  public void useNioTransport_shouldNotThrow() {
    InternalNettyServerBuilder.useNioTransport(builder);

    builder.assertEventLoopsAndChannelType();
  }
}
