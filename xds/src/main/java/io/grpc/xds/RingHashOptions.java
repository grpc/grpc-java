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

import com.google.common.annotations.VisibleForTesting;
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
  @VisibleForTesting
  // Same as RingHashLoadBalancerProvider.DEFAULT_MAX_RING_SIZE
  static final long DEFAULT_RING_SIZE_CAP = 4 * 1024L;

  // Limits ring hash sizes to restrict client memory usage.
  private static volatile long ringSizeCap = DEFAULT_RING_SIZE_CAP;

  private RingHashOptions() {} // Prevent instantiation

  /**
   * Set the global limit for the min and max number of ring hash entries per ring.
   * Note that this limit is clamped between 1 entry and 8,388,608 entries, and new
   * limits lying outside that range will be silently moved to the nearest number within
   * that range. Defaults initially to 4096 entries.
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
