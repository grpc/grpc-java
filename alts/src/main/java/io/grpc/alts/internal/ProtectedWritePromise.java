/*
 * Copyright 2019 The gRPC Authors
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

package io.grpc.alts.internal;

import io.netty.channel.ChannelPromise;

/**
 * Promise used when flushing the {@code pendingUnprotectedWrites} queue. It manages the many-to
 * many relationship between pending unprotected messages and the individual writes. Each
 * protected frame will be written using the same instance of this promise and it will accumulate
 * the results. Once all frames have been successfully written (or any failed), all of the
 * promises for the pending unprotected writes are notified.
 *
 * <p>NOTE: this code is based on code in Netty's {@code Http2CodecUtil}.
 */
public interface ProtectedWritePromise {
  /**
   * Adds a promise for a pending unprotected write. This will be notified after all of the writes
   * complete.
   */
  void addUnprotectedPromise(ChannelPromise promise);

  /**
   * Allocate a new promise for the write of a protected frame. This will be used to aggregate the
   * overall success of the unprotected promises.
   *
   * @return {@code this} promise.
   */
  ChannelPromise newPromise();

  /**
   * Signify that no more {@link #newPromise()} allocations will be made. The aggregation can not
   * be successful until this method is called.
   *
   * @return {@code this} promise.
   */
  ChannelPromise doneAllocatingPromises();
}
