/*
 * Copyright 2021 The gRPC Authors
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

package io.grpc.xds;

import io.grpc.ExperimentalApi;

/**
 * Utility class that provides a way to configure ring hash size limits. This is applicable
 * for clients that use the ring hash load balancing policy. Note that size limits involve
 * a tradeoff between client memory consumption and accuracy of load balancing weight
 * representations. Also see https://github.com/grpc/proposal/pull/338.
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/9718")
public final class RingHashOptions {
  // Same as ClientXdsClient.DEFAULT_RING_HASH_LB_POLICY_MAX_RING_SIZE
  @VisibleForTesting
  static final long MAX_RING_SIZE_CAP = 8 * 1024 * 1024L;

  // Same as RingHashLoadBalancerProvider.DEFAULT_MAX_RING_SIZE
  private static volatile long ringSizeCap = 4 * 1024L;

  /**
   * Set the global limit for min and max ring hash sizes. Note that
   * this limit is clamped between 1 and 8M, and attempts to set
   * the limit lower or higher than that range will be silently
   * moved to the nearest number within that range. Defaults initially
   * to 4K.
   */
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/9718")
  public static void setRingSizeCap(long ringSizeCap) {
    ringSizeCap = Math.max(1, ringSizeCap);
    ringSizeCap = Math.min(MAX_RING_SIZE_CAP, ringSizeCap);
    RingHashOptions.ringSizeCap = ringSizeCap;
  }

  /**
   * Get the global limit for min and max ring hash sizes.
   */
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/9718")
  public static long getRingSizeCap() {
    return RingHashOptions.ringSizeCap;
  }
}
