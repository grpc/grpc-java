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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.base.MoreObjects;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Configuration for the {@code autosharding_experimental} LB policy, as specified by
 * {@code AutoshardingLbConfig} in gRFC A119.
 *
 * <p>{@link AutoShardingLoadBalancerProvider} parses this out of service config JSON and reports
 * a bad configuration as a {@link io.grpc.NameResolver.ConfigOrError}, so the channel gets a
 * useful message instead of an exception. The constructor here re-checks the same constraints as
 * a backstop that cannot be bypassed.
 */
final class AutoShardingLoadBalancerConfig {

  /** Default for {@link #initialAssignmentTimeoutNanos} when the field is unset. */
  static final long DEFAULT_INITIAL_ASSIGNMENT_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(60);

  /** Opaque key passed to the "Channel Factory" to reach the sharding service. */
  final String channelFactoryKey;

  /**
   * Identifies the assignments this client should receive.
   *
   * <p>May contain a single {@code %s} token, which the LB policy replaces with the locality
   * before sending it to the sharding service, or with the empty string when no locality is
   * available.
   */
  final String autoshardingTarget;

  /** Name of the request header holding the application-defined sharding key. Never empty. */
  final String keyHeaderName;

  /** Whether RPCs may fall back to the full set of resolved endpoints. */
  final boolean enableFallback;

  /**
   * How long each new client waits for its first assignment before reporting an error.
   */
  final long initialAssignmentTimeoutNanos;

  AutoShardingLoadBalancerConfig(
      String channelFactoryKey,
      String autoshardingTarget,
      String keyHeaderName,
      boolean enableFallback,
      long initialAssignmentTimeoutNanos) {
    this.channelFactoryKey = checkNotNull(channelFactoryKey, "channelFactoryKey");
    this.autoshardingTarget = checkNotNull(autoshardingTarget, "autoshardingTarget");
    this.keyHeaderName = checkNotNull(keyHeaderName, "keyHeaderName");
    checkArgument(!keyHeaderName.isEmpty(), "keyHeaderName is empty");
    checkArgument(
        initialAssignmentTimeoutNanos >= 0,
        "initialAssignmentTimeoutNanos is negative: %s",
        initialAssignmentTimeoutNanos);
    this.enableFallback = enableFallback;
    this.initialAssignmentTimeoutNanos = initialAssignmentTimeoutNanos;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof AutoShardingLoadBalancerConfig)) {
      return false;
    }
    AutoShardingLoadBalancerConfig that = (AutoShardingLoadBalancerConfig) o;
    return enableFallback == that.enableFallback
        && initialAssignmentTimeoutNanos == that.initialAssignmentTimeoutNanos
        && channelFactoryKey.equals(that.channelFactoryKey)
        && autoshardingTarget.equals(that.autoshardingTarget)
        && keyHeaderName.equals(that.keyHeaderName);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        channelFactoryKey,
        autoshardingTarget,
        keyHeaderName,
        enableFallback,
        initialAssignmentTimeoutNanos);
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
        .add("channelFactoryKey", channelFactoryKey)
        .add("autoshardingTarget", autoshardingTarget)
        .add("keyHeaderName", keyHeaderName)
        .add("enableFallback", enableFallback)
        .add("initialAssignmentTimeoutNanos", initialAssignmentTimeoutNanos)
        .toString();
  }
}
