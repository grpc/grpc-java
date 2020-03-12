/*
 * Copyright 2014 The gRPC Authors
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

import io.grpc.ManagedChannel;
import io.grpc.internal.AbstractServerImplBuilder;
import io.grpc.netty.NegotiationType;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.NettyServerBuilder;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalChannel;
import io.netty.channel.local.LocalServerChannel;
import org.junit.After;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Run transport tests over the Netty in-process channel.
 */
@RunWith(JUnit4.class)
public class Http2NettyLocalChannelTest extends AbstractInteropTest {

  private DefaultEventLoopGroup eventLoopGroup = new DefaultEventLoopGroup();

  @Override
  protected AbstractServerImplBuilder<?> getServerBuilder() {
    return NettyServerBuilder
        .forAddress(new LocalAddress("in-process-1"))
        .flowControlWindow(65 * 1024)
        .maxInboundMessageSize(AbstractInteropTest.MAX_MESSAGE_SIZE)
        .channelType(LocalServerChannel.class)
        .workerEventLoopGroup(eventLoopGroup)
        .bossEventLoopGroup(eventLoopGroup);
  }

  @Override
  protected ManagedChannel createChannel() {
    NettyChannelBuilder builder = NettyChannelBuilder
        .forAddress(new LocalAddress("in-process-1"))
        .negotiationType(NegotiationType.PLAINTEXT)
        .channelType(LocalChannel.class)
        .eventLoopGroup(eventLoopGroup)
        .flowControlWindow(65 * 1024)
        .maxInboundMessageSize(AbstractInteropTest.MAX_MESSAGE_SIZE);
    // Disable the default census stats interceptor, use testing interceptor instead.
    io.grpc.internal.TestingAccessor.setStatsEnabled(builder, false);
    return builder.intercept(createCensusStatsClientInterceptor()).build();
  }

  @Override
  @After
  @SuppressWarnings("FutureReturnValueIgnored")
  public void tearDown() {
    super.tearDown();
    eventLoopGroup.shutdownGracefully();
  }
}
