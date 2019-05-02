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

package io.grpc.internal;

import io.grpc.LoadBalancer.CreateSubchannelArgs;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancer.Subchannel;
import javax.annotation.concurrent.NotThreadSafe;

/**
 * Manages life-cycle of Subchannels.
 *
 * <p>All methods are run from the ChannelExecutor that the helper uses.
 */
@NotThreadSafe
// TODO: make it package private. Right now it has to be public for grpclb testing.
public interface SubchannelPool {
  /**
   * Pass essential utilities and the balancer that's using this pool.
   */
  void init(Helper helper);

  /**
   * Takes a {@link Subchannel} from the pool for the given {@code args.eags} if there is one
   * available. Otherwise, creates and returns a new {@code Subchannel} with the given {@code args}.
   * The given {@args.stateListener} will receive state updates for the returned subchannel.
   *
   * <p>There can be at most one Subchannel for each EAGs.  After a Subchannel is taken out of the
   * pool, it must be returned before the same EAGs can be used to call this method.
   *
   * <p>Note that {@code args.attrs} and {@code args.customOptions} will not be used if a pooled
   * subchannel is returned.
   */
  Subchannel takeOrCreateSubchannel(CreateSubchannelArgs args);

  /**
   * Puts a {@link Subchannel} back to the pool.  From this point the Subchannel is owned by the
   * pool, and the caller should stop referencing to this Subchannel.  The {@link
   * io.grpc.LoadBalancer.SubchannelStateListener} will not receive any more updates.
   *
   * <p>Can only be called with a Subchannel created by this pool.  Must not be called if the
   * Subchannel is already in the pool.
   */
  void returnSubchannel(Subchannel subchannel);

  /**
   * Shuts down all subchannels in the pool immediately.
   */
  void clear();
}
