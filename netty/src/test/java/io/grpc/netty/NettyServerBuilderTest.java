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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import io.grpc.ServerStreamTracer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.local.LocalServerChannel;
import io.netty.handler.ssl.SslContext;
import java.net.InetSocketAddress;
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
  public void addMultipleListenAddresses() {
    builder.addListenAddress(new InetSocketAddress(8081));
    NettyServer server =
        builder.buildTransportServers(ImmutableList.<ServerStreamTracer.Factory>of());

    assertThat(server.getListenSocketAddresses()).hasSize(2);
  }

  @Test
  public void sslContextCanBeNull() {
    builder.sslContext(null);
  }

  @Test
  public void failIfSslContextIsNotServer() {
    SslContext sslContext = mock(SslContext.class);
    when(sslContext.isClient()).thenReturn(true);

    IllegalArgumentException e = assertThrows(
        IllegalArgumentException.class, () -> builder.sslContext(sslContext));
    assertThat(e).hasMessageThat().isEqualTo("Client SSL context can not be used for server");
  }

  @Test
  public void failIfKeepAliveTimeNegative() {
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
        () -> builder.keepAliveTime(-10L, TimeUnit.HOURS));
    assertThat(e).hasMessageThat().isEqualTo("keepalive time must be positive：-10");
  }

  @Test
  public void failIfKeepAliveTimeoutNegative() {
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
        () -> builder.keepAliveTimeout(-10L, TimeUnit.HOURS));
    assertThat(e).hasMessageThat().isEqualTo("keepalive timeout must be positive: -10");
  }

  @Test
  public void failIfMaxConcurrentCallsPerConnectionNegative() {
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
        () -> builder.maxConcurrentCallsPerConnection(0));
    assertThat(e).hasMessageThat().isEqualTo("max must be positive: 0");
  }

  @Test
  public void failIfMaxInboundMetadataSizeNonPositive() {
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
        () -> builder.maxInboundMetadataSize(0));
    assertThat(e).hasMessageThat().isEqualTo("maxInboundMetadataSize must be positive: 0");
  }

  @Test
  public void failIfSoftInboundMetadataSizeNonPositive() {
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
        () -> builder.maxInboundMetadataSize(0, 100));
    assertThat(e).hasMessageThat().isEqualTo("softLimitHeaderListSize must be positive: 0");
  }

  @Test
  public void failIfMaxInboundMetadataSizeSmallerThanSoft() {
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
        () -> builder.maxInboundMetadataSize(100, 80));
    assertThat(e).hasMessageThat().isEqualTo("maxInboundMetadataSize: 80 "
        + "must be greater than softLimitHeaderListSize: 100");
  }

  @Test
  public void failIfMaxConnectionIdleNegative() {
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
        () -> builder.maxConnectionIdle(-1, TimeUnit.HOURS));
    assertThat(e).hasMessageThat().isEqualTo("max connection idle must be positive: -1");
  }

  @Test
  public void failIfMaxConnectionAgeNegative() {
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
        () -> builder.maxConnectionAge(-1, TimeUnit.HOURS));
    assertThat(e).hasMessageThat().isEqualTo("max connection age must be positive: -1");
  }

  @Test
  public void failIfMaxConnectionAgeGraceNegative() {
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
        () -> builder.maxConnectionAgeGrace(-1, TimeUnit.HOURS));
    assertThat(e).hasMessageThat().isEqualTo("max connection age grace must be non-negative: -1");
  }

  @Test
  public void failIfPermitKeepAliveTimeNegative() {
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
        () -> builder.permitKeepAliveTime(-1, TimeUnit.HOURS));
    assertThat(e).hasMessageThat().isEqualTo("permit keepalive time must be non-negative: -1");
  }

  @Test
  public void assertEventLoopsAndChannelType_onlyBossGroupProvided() {
    EventLoopGroup mockEventLoopGroup = mock(EventLoopGroup.class);
    builder.bossEventLoopGroup(mockEventLoopGroup);
    IllegalStateException e = assertThrows(IllegalStateException.class,
        builder::assertEventLoopsAndChannelType);
    assertThat(e).hasMessageThat().isEqualTo(
        "All of BossEventLoopGroup, WorkerEventLoopGroup and ChannelType should be provided "
            + "or neither should be");
  }

  @Test
  public void assertEventLoopsAndChannelType_onlyWorkerGroupProvided() {
    EventLoopGroup mockEventLoopGroup = mock(EventLoopGroup.class);
    builder.workerEventLoopGroup(mockEventLoopGroup);
    IllegalStateException e = assertThrows(IllegalStateException.class,
        builder::assertEventLoopsAndChannelType);
    assertThat(e).hasMessageThat().isEqualTo(
        "All of BossEventLoopGroup, WorkerEventLoopGroup and ChannelType should be provided "
            + "or neither should be");
  }

  @Test
  public void assertEventLoopsAndChannelType_onlyTypeProvided() {
    builder.channelType(LocalServerChannel.class);
    IllegalStateException e = assertThrows(IllegalStateException.class,
        builder::assertEventLoopsAndChannelType);
    assertThat(e).hasMessageThat().isEqualTo(
        "All of BossEventLoopGroup, WorkerEventLoopGroup and ChannelType should be provided "
            + "or neither should be");
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
