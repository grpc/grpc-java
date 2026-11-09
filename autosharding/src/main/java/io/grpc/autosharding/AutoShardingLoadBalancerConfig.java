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
import io.grpc.Metadata;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Configuration for the {@code autosharding_experimental} LB policy, as specified by
 * {@code AutoshardingLbConfig} in gRFC A119.
 */
final class AutoShardingLoadBalancerConfig {

  /** Default for {@link #getInitialAssignmentTimeoutNanos} when the field is unset. */
  static final long DEFAULT_INITIAL_ASSIGNMENT_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(60);

  /** Opaque key passed to the "Channel Factory" to reach the sharding service. */
  private final String channelFactoryKey;

  /**
   * Identifies the assignments this client should receive.
   *
   * <p>May contain a single {@code %s} token. Before sending the target to the sharding service,
   * the LB policy replaces it with the {@link io.grpc.EquivalentAddressGroup#ATTR_LOCALITY_NAME}
   * that all endpoints share, or with the empty string when they don't share one.
   */
  private final String autoshardingTarget;

  /** Request header holding the application-defined sharding key. */
  private final Metadata.Key<byte[]> keyHeader;

  /** Whether RPCs may fall back to the full set of resolved endpoints. */
  private final boolean enableFallback;

  /**
   * How long each new client waits for its first assignment before reporting an error.
   */
  private final long initialAssignmentTimeoutNanos;

  AutoShardingLoadBalancerConfig(
      String channelFactoryKey,
      String autoshardingTarget,
      Metadata.Key<byte[]> keyHeader,
      boolean enableFallback,
      long initialAssignmentTimeoutNanos) {
    this.channelFactoryKey = checkNotNull(channelFactoryKey, "channelFactoryKey");
    this.autoshardingTarget = checkNotNull(autoshardingTarget, "autoshardingTarget");
    this.keyHeader = checkNotNull(keyHeader, "keyHeader");
    checkArgument(
        initialAssignmentTimeoutNanos >= 0,
        "initialAssignmentTimeoutNanos is negative: %s",
        initialAssignmentTimeoutNanos);
    this.enableFallback = enableFallback;
    this.initialAssignmentTimeoutNanos = initialAssignmentTimeoutNanos;
  }

  String getChannelFactoryKey() {
    return channelFactoryKey;
  }

  String getAutoshardingTarget() {
    return autoshardingTarget;
  }

  Metadata.Key<byte[]> getKeyHeader() {
    return keyHeader;
  }

  boolean isFallbackEnabled() {
    return enableFallback;
  }

  long getInitialAssignmentTimeoutNanos() {
    return initialAssignmentTimeoutNanos;
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
        && keyHeader.equals(that.keyHeader);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        channelFactoryKey,
        autoshardingTarget,
        keyHeader,
        enableFallback,
        initialAssignmentTimeoutNanos);
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
        .add("channelFactoryKey", channelFactoryKey)
        .add("autoshardingTarget", autoshardingTarget)
        .add("keyHeader", keyHeader.name())
        .add("enableFallback", enableFallback)
        .add("initialAssignmentTimeoutNanos", initialAssignmentTimeoutNanos)
        .toString();
  }
}
