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

package io.grpc.s2a.internal.channel;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.google.errorprone.annotations.concurrent.GuardedBy;
import io.grpc.Channel;
import io.grpc.internal.ObjectPool;
import javax.annotation.concurrent.ThreadSafe;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Manages a gRPC channel pool and a cached gRPC channel to be used for communication with the S2A.
 */
@ThreadSafe
public final class S2AGrpcChannelPool implements S2AChannelPool {
  private static final int MAX_NUMBER_USERS_OF_CACHED_CHANNEL = 100000;
  private final ObjectPool<Channel> channelPool;

  @GuardedBy("this")
  private @Nullable Channel cachedChannel;

  @GuardedBy("this")
  private int numberOfUsersOfCachedChannel = 0;

  private enum State {
    OPEN,
    CLOSED,
  }
  
  ;

  @GuardedBy("this")
  private State state = State.OPEN;

  public static S2AChannelPool create(ObjectPool<Channel> channelPool) {
    checkNotNull(channelPool, "Channel pool should not be null.");
    return new S2AGrpcChannelPool(channelPool);
  }

  private S2AGrpcChannelPool(ObjectPool<Channel> channelPool) {
    this.channelPool = channelPool;
  }

  /**
   * Retrieves a channel from {@code channelPool} if {@code channel} is null, and returns {@code
   * channel} otherwise.
   *
   * @return a {@link Channel} obtained from the channel pool.
   */
  @Override
  public synchronized Channel getChannel() {
    checkState(state.equals(State.OPEN), "Channel pool is not open.");
    checkState(
        numberOfUsersOfCachedChannel < MAX_NUMBER_USERS_OF_CACHED_CHANNEL,
        "Max number of channels have been retrieved from the channel pool.");
    if (cachedChannel == null) {
      cachedChannel = channelPool.getObject();
    }
    numberOfUsersOfCachedChannel += 1;
    return cachedChannel;
  }

  /**
   * Returns {@code channel} to {@code channelPool}.
   *
   * <p>The caller must ensure that {@code channel} was retrieved from this channel pool.
   */
  @Override
  public synchronized void returnToPool(Channel channel) {
    checkState(state.equals(State.OPEN), "Channel pool is not open.");
    checkArgument(
        cachedChannel != null && numberOfUsersOfCachedChannel > 0 && cachedChannel.equals(channel),
        "Cannot return the channel to channel pool because the channel was not obtained from"
            + " channel pool.");
    numberOfUsersOfCachedChannel -= 1;
    if (numberOfUsersOfCachedChannel == 0) {
      channelPool.returnObject(channel);
      cachedChannel = null;
    }
  }

  @Override
  public synchronized void close() {
    state = State.CLOSED;
    numberOfUsersOfCachedChannel = 0;
    if (cachedChannel != null) {
      channelPool.returnObject(cachedChannel);
      cachedChannel = null;
    }
  }
}