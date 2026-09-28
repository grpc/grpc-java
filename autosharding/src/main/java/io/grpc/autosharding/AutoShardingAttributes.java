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

import io.grpc.Attributes;
import io.grpc.Internal;

/**
 * Attribute keys used to inject data into the {@code autosharding_experimental} LB policy.
 *
 * <p>These keys are set on the resolver result by whoever is driving the policy: the
 * {@code cds_experimental} LB policy in xDS deployments, or the application in non-xDS ones.
 * They are internal to gRPC and carry no compatibility guarantee; the supported public API for
 * configuring this policy is added separately.
 *
 * <p>The locality substituted for {@code %s} in {@code autosharding_target} is not injected here:
 * it is read from {@link io.grpc.EquivalentAddressGroup#ATTR_LOCALITY_NAME}, and used only when
 * every endpoint carries the same value.
 */
@Internal
public final class AutoShardingAttributes {

  /**
   * The "Channel Factory" used to create a channel to the sharding service.
   *
   * <p>Supplied alongside the LB policy configuration, which carries only the opaque key that
   * the factory resolves into a channel.
   */
  public static final Attributes.Key<ChannelFactory> ATTR_CHANNEL_FACTORY =
      Attributes.Key.create("io.grpc.autosharding.channelFactory");

  private AutoShardingAttributes() {}
}
