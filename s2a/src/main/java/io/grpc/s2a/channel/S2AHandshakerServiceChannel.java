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

package io.grpc.s2a.channel;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.SECONDS;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Maps;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ChannelCredentials;
import io.grpc.ClientCall;
import io.grpc.ManagedChannel;
import io.grpc.MethodDescriptor;
import io.grpc.internal.SharedResourceHolder.Resource;
import io.grpc.netty.NettyChannelBuilder;
import java.time.Duration;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.concurrent.ThreadSafe;

/**
 * Provides APIs for managing gRPC channels to S2A servers. Each channel is local and plaintext. If
 * credentials are provided, they are used to secure the channel.
 *
 * <p>This is done as follows: for each S2A server, provides an implementation of gRPC's {@link
 * SharedResourceHolder.Resource} interface called a {@code Resource<Channel>}. A {@code
 * Resource<Channel>} is a factory for creating gRPC channels to the S2A server at a given address,
 * and a channel must be returned to the {@code Resource<Channel>} when it is no longer needed.
 *
 * <p>Typical usage pattern is below:
 *
 * <pre>{@code
 * Resource<Channel> resource = S2AHandshakerServiceChannel.getChannelResource("localhost:1234",
 * creds);
 * Channel channel = resource.create();
 * // Send an RPC over the channel to the S2A server running at localhost:1234.
 * resource.close(channel);
 * }</pre>
 */
@ThreadSafe
public final class S2AHandshakerServiceChannel {
  private static final ConcurrentMap<String, Resource<Channel>> SHARED_RESOURCE_CHANNELS =
      Maps.newConcurrentMap();
  private static final Duration CHANNEL_SHUTDOWN_TIMEOUT = Duration.ofSeconds(10);

  /**
   * Returns a {@link SharedResourceHolder.Resource} instance for managing channels to an S2A server
   * running at {@code s2aAddress}.
   *
   * @param s2aAddress the address of the S2A, typically in the format {@code host:port}.
   * @param s2aChannelCredentials the credentials to use when establishing a connection to the S2A.
   * @return a {@link ChannelResource} instance that manages a {@link Channel} to the S2A server
   *     running at {@code s2aAddress}.
   */
  public static Resource<Channel> getChannelResource(
      String s2aAddress, ChannelCredentials s2aChannelCredentials) {
    checkNotNull(s2aAddress);
    checkNotNull(s2aChannelCredentials);
    return SHARED_RESOURCE_CHANNELS.computeIfAbsent(
        s2aAddress, channelResource -> new ChannelResource(s2aAddress, s2aChannelCredentials));
  }

  /**
   * Defines how to create and destroy a {@link Channel} instance that uses shared resources. A
   * channel created by {@code ChannelResource} is a plaintext, local channel to the service running
   * at {@code targetAddress}.
   */
  private static class ChannelResource implements Resource<Channel> {
    private final String targetAddress;
    private final ChannelCredentials channelCredentials;

    public ChannelResource(String targetAddress, ChannelCredentials channelCredentials) {
      this.targetAddress = targetAddress;
      this.channelCredentials = channelCredentials;
    }

    /**
     * Creates a {@code HandshakerServiceChannel} instance to the service running at {@code
     * targetAddress}.
     */
    @Override
    public Channel create() {
      ManagedChannel channel =
          NettyChannelBuilder.forTarget(targetAddress, channelCredentials)
              .directExecutor()
              .build();
      return HandshakerServiceChannel.create(channel);
    }

    /** Destroys a {@code HandshakerServiceChannel} instance. */
    @Override
    public void close(Channel instanceChannel) {
      checkNotNull(instanceChannel);
      HandshakerServiceChannel channel = (HandshakerServiceChannel) instanceChannel;
      channel.close();
    }

    @Override
    public String toString() {
      return "grpc-s2a-channel";
    }
  }

  /**
   * Manages a channel using a {@link ManagedChannel} instance.
   */
  @VisibleForTesting
  static class HandshakerServiceChannel extends Channel {
    private static final Logger logger =
          Logger.getLogger(S2AHandshakerServiceChannel.class.getName());
    private final ManagedChannel delegate;

    static HandshakerServiceChannel create(ManagedChannel delegate) {
      checkNotNull(delegate);
      return new HandshakerServiceChannel(delegate);
    }

    private HandshakerServiceChannel(ManagedChannel delegate) {
      this.delegate = delegate;
    }

    /**
     * Returns the address of the service to which the {@code delegate} channel connects, which is
     * typically of the form {@code host:port}.
     */
    @Override
    public String authority() {
      return delegate.authority();
    }

    /** Creates a {@link ClientCall} that invokes the operations in {@link MethodDescriptor}. */
    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> newCall(
        MethodDescriptor<ReqT, RespT> methodDescriptor, CallOptions options) {
      return delegate.newCall(methodDescriptor, options);
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    public void close() {
      delegate.shutdownNow();
      try {
        delegate.awaitTermination(CHANNEL_SHUTDOWN_TIMEOUT.getSeconds(), SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        logger.log(Level.WARNING, "Channel to S2A was not shutdown.");
      }
    }
  }

  private S2AHandshakerServiceChannel() {}
}