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

import com.google.errorprone.annotations.CanIgnoreReturnValue;
import io.grpc.Channel;
import javax.annotation.concurrent.ThreadSafe;

/** Manages a channel pool to be used for communication with the S2A. */
@ThreadSafe
public interface S2AChannelPool extends AutoCloseable {
  /**
   * Retrieves an open channel to the S2A from the channel pool.
   *
   * @throws IllegalStateException if no channel is available.
   */
  @CanIgnoreReturnValue
  Channel getChannel();

  /** Returns a channel to the channel pool. */
  void returnToPool(Channel channel);

  /**
   * Returns all channels to the channel pool and closes the pool so that no new channels can be
   * retrieved from the pool.
   */
  @Override
  void close();
}