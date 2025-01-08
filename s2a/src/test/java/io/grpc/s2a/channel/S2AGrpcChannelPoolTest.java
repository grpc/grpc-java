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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;

import io.grpc.Channel;
import io.grpc.internal.ObjectPool;
import io.grpc.s2a.internal.channel.S2AChannelPool;
import io.grpc.s2a.internal.channel.S2AGrpcChannelPool;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link S2AGrpcChannelPool}. */
@RunWith(JUnit4.class)
public final class S2AGrpcChannelPoolTest {
  @Test
  public void getChannel_success() throws Exception {
    FakeChannelPool fakeChannelPool = new FakeChannelPool();
    S2AChannelPool s2aChannelPool = S2AGrpcChannelPool.create(fakeChannelPool);

    Channel channel = s2aChannelPool.getChannel();

    assertThat(channel).isNotNull();
    assertThat(fakeChannelPool.isChannelCached()).isTrue();
    assertThat(s2aChannelPool.getChannel()).isEqualTo(channel);
  }

  @Test
  public void returnToPool_success() throws Exception {
    FakeChannelPool fakeChannelPool = new FakeChannelPool();
    S2AChannelPool s2aChannelPool = S2AGrpcChannelPool.create(fakeChannelPool);

    s2aChannelPool.returnToPool(s2aChannelPool.getChannel());

    assertThat(fakeChannelPool.isChannelCached()).isFalse();
  }

  @Test
  public void returnToPool_channelStillCachedBecauseMultipleChannelsRetrieved() throws Exception {
    FakeChannelPool fakeChannelPool = new FakeChannelPool();
    S2AChannelPool s2aChannelPool = S2AGrpcChannelPool.create(fakeChannelPool);

    s2aChannelPool.getChannel();
    s2aChannelPool.returnToPool(s2aChannelPool.getChannel());

    assertThat(fakeChannelPool.isChannelCached()).isTrue();
  }

  @Test
  public void returnToPool_failureBecauseChannelWasNotFromPool() throws Exception {
    S2AChannelPool s2aChannelPool = S2AGrpcChannelPool.create(new FakeChannelPool());

    IllegalArgumentException expected =
        assertThrows(
            IllegalArgumentException.class,
            () -> s2aChannelPool.returnToPool(mock(Channel.class)));
    assertThat(expected)
        .hasMessageThat()
        .isEqualTo(
            "Cannot return the channel to channel pool because the channel was not obtained from"
                + " channel pool.");
  }

  @Test
  public void close_success() throws Exception {
    FakeChannelPool fakeChannelPool = new FakeChannelPool();
    try (S2AChannelPool s2aChannelPool = S2AGrpcChannelPool.create(fakeChannelPool)) {
      s2aChannelPool.getChannel();
    }

    assertThat(fakeChannelPool.isChannelCached()).isFalse();
  }

  @Test
  public void close_poolIsUnusable() throws Exception {
    S2AChannelPool s2aChannelPool = S2AGrpcChannelPool.create(new FakeChannelPool());
    s2aChannelPool.close();

    IllegalStateException expected =
        assertThrows(IllegalStateException.class, s2aChannelPool::getChannel);

    assertThat(expected).hasMessageThat().isEqualTo("Channel pool is not open.");
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