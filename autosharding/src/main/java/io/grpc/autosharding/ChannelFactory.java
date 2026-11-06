/*
 * Copyright 2026 The gRPC Authors
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

package io.grpc.autosharding;

import io.grpc.Channel;
import io.grpc.Internal;

/**
 * Creates channels to the autosharding service.
 *
 * <p>The LB policy configuration carries only an opaque {@code channel_factory_key}; the factory
 * is responsible for translating that key into a fully configured channel. Credentials and
 * per-request metadata are deliberately kept out of the configuration so that a compromised
 * control plane cannot escalate privileges, per gRFC A102. Implementations must therefore ensure
 * the key uniquely encodes every parameter needed to create the channel.
 *
 * <p>Channels are leased rather than owned: implementations may share one channel between
 * several leases, so a caller must never shut a channel down directly and must instead close its
 * lease.
 *
 * <p>Injected into the LB policy through
 * {@link AutoShardingAttributes#ATTR_CHANNEL_FACTORY}. In xDS deployments the
 * {@code cds_experimental} LB policy supplies it; in non-xDS deployments the application does.
 *
 * <p>See gRFC A119, "Creating a gRPC Channel to the Autosharding Service".
 */
@Internal
public interface ChannelFactory {

  /**
   * Returns a lease on a channel to the sharding service identified by {@code channelFactoryKey}.
   * The caller must close the lease once it no longer uses the channel.
   *
   * <p>Failure is reported by throwing, for a key that is not recognized or not allowed as well as
   * for any other reason the channel cannot be created. The LB policy then treats the failure as
   * an error from the sharding service: RPCs fall back if fallback is enabled and fail otherwise.
   * The key is not tried again until it changes.
   *
   * @throws IllegalArgumentException if the key is not recognized or not allowed
   * @throws RuntimeException if the channel cannot be created for any other reason
   */
  ChannelLease createChannel(String channelFactoryKey);

  /** A claim on a channel returned by {@link #createChannel}. */
  interface ChannelLease extends AutoCloseable {

    /** Returns the leased channel, which must not be used once the lease is closed. */
    Channel channel();

    /**
     * Gives up the claim on the channel. Whether the channel is then shut down is up to the
     * factory. Closing a lease that is already closed has no effect.
     */
    @Override
    void close();
  }
}
