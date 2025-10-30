/*
 * Copyright 2018 The gRPC Authors
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

package io.grpc.alts;

import com.google.common.base.MoreObjects;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ManagedChannel;
import io.grpc.MethodDescriptor;
import io.grpc.internal.GrpcUtil;
import io.grpc.internal.SharedResourceHolder.Resource;
import io.grpc.netty.NettyChannelBuilder;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.concurrent.DefaultThreadFactory;
import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

/**
 * Class for creating a single shared gRPC channel to the ALTS Handshaker Service using
 * SharedResourceHolder. The channel to the handshaker service is local and is over plaintext. Each
 * application will have at most one connection to the handshaker service.
 */
final class HandshakerServiceChannel {

  static final Resource<Channel> SHARED_HANDSHAKER_CHANNEL =
      new ChannelResource(MoreObjects.firstNonNull(System.getenv("GCE_METADATA_HOST"), "metadata.google.internal.:8080"));


  /** Returns a resource of handshaker service channel for testing only. */
  static Resource<Channel> getHandshakerChannelForTesting(String handshakerAddress) {
    return new ChannelResource(handshakerAddress);
  }

  private static final boolean EXPERIMENTAL_ALTS_HANDSHAKER_KEEPALIVE_PARAMS =
      GrpcUtil.getFlag("GRPC_EXPERIMENTAL_ALTS_HANDSHAKER_KEEPALIVE_PARAMS", false);

  private static class ChannelResource implements Resource<Channel> {
    private final String target;

    public ChannelResource(String target) {
      this.target = target;
    }

    @Override
    public Channel create() {
      /* Use its own event loop thread pool to avoid blocking. */
      EventLoopGroup eventGroup =
          new NioEventLoopGroup(1, new DefaultThreadFactory("handshaker pool", true));
      NettyChannelBuilder channelBuilder =
          NettyChannelBuilder.forTarget(target)
          .channelType(NioSocketChannel.class, InetSocketAddress.class)
          .directExecutor()
          .eventLoopGroup(eventGroup)
          .usePlaintext();
      if (EXPERIMENTAL_ALTS_HANDSHAKER_KEEPALIVE_PARAMS) {
        channelBuilder.keepAliveTime(10, TimeUnit.MINUTES).keepAliveTimeout(10, TimeUnit.SECONDS);
      }
      ManagedChannel channel = channelBuilder.build();
      return new EventLoopHoldingChannel(channel, eventGroup);
    }

    @Override
    public void close(Channel instanceChannel) {
      ((EventLoopHoldingChannel) instanceChannel).close();
    }

    @Override
    public String toString() {
      return "grpc-alts-handshaker-service-channel";
    }
  }

  private abstract static class ForwardingChannel extends Channel {
    protected abstract Channel delegate();

    @Override
    public String authority() {
      return delegate().authority();
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> newCall(
        MethodDescriptor<ReqT, RespT> methodDescriptor, CallOptions options) {
      return delegate().newCall(methodDescriptor, options);
    }
  }

  private static class EventLoopHoldingChannel extends ForwardingChannel {
    private final ManagedChannel delegate;
    private final EventLoopGroup eventLoopGroup;

    public EventLoopHoldingChannel(ManagedChannel delegate, EventLoopGroup eventLoopGroup) {
      this.delegate = delegate;
      this.eventLoopGroup = eventLoopGroup;
    }

    @Override
    protected Channel delegate() {
      return delegate;
    }

    @SuppressWarnings("FutureReturnValueIgnored") // netty ChannelFuture
    public void close() {
      // This method will generally be run on the ResourceHolder's ScheduledExecutorService thread
      delegate.shutdownNow();
      boolean terminated = false;
      try {
        terminated = delegate.awaitTermination(2, TimeUnit.SECONDS);
      } catch (InterruptedException ex) {
        // terminated will be false
      }
      // Try hard to shutdown abruptly so any bug is more likely to be noticed during testing.
      long quietPeriodSeconds = terminated ? 0 : 1;
      eventLoopGroup.shutdownGracefully(quietPeriodSeconds, 10, TimeUnit.SECONDS);
    }
  }
}
