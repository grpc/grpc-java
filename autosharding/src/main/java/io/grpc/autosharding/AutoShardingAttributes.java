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
 * Attributes the {@code autosharding_experimental} LB policy reads besides its configuration. Set
 * them on the {@link io.grpc.LoadBalancer.ResolvedAddresses} given to the policy.
 *
 * <p>To substitute a locality for {@code %s} in {@code autosharding_target}, give every endpoint
 * the same {@link io.grpc.EquivalentAddressGroup#ATTR_LOCALITY_NAME}.
 *
 * <p>Internal to gRPC, with no compatibility guarantee.
 */
@Internal
public final class AutoShardingAttributes {

  /** The {@link ChannelFactory} for the channel to the sharding service. Required. */
  public static final Attributes.Key<ChannelFactory> ATTR_CHANNEL_FACTORY =
      Attributes.Key.create("io.grpc.autosharding.channelFactory");

  private AutoShardingAttributes() {}
}
