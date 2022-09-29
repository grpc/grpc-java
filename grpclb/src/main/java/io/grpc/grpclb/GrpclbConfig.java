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

package io.grpc.grpclb;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import io.grpc.grpclb.GrpclbState.Mode;
import javax.annotation.Nullable;

final class GrpclbConfig {

  private final Mode mode;
  @Nullable
  private final String serviceName;
  private final long fallbackTimeoutMs;

  private GrpclbConfig(Mode mode, @Nullable String serviceName, long fallbackTimeoutMs) {
    this.mode = checkNotNull(mode, "mode");
    this.serviceName = serviceName;
    this.fallbackTimeoutMs = fallbackTimeoutMs;
  }

  static GrpclbConfig create(Mode mode) {
    return create(mode, null, GrpclbState.FALLBACK_TIMEOUT_MS);
  }

  static GrpclbConfig create(Mode mode, @Nullable String serviceName, long fallbackTimeoutMs) {
    checkArgument(fallbackTimeoutMs > 0, "Invalid timeout (%s)", fallbackTimeoutMs);
    return new GrpclbConfig(mode, serviceName, fallbackTimeoutMs);
  }

  Mode getMode() {
    return mode;
  }

  long getFallbackTimeoutMs() {
    return fallbackTimeoutMs;
  }

  /**
   * If specified, it overrides the name of the sevice name to be sent to the balancer. if not, the
   * target to be sent to the balancer will continue to be obtained from the target URI passed
   * to the gRPC client channel.
   */
  @Nullable
  String getServiceName() {
    return serviceName;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    GrpclbConfig that = (GrpclbConfig) o;
    return mode == that.mode
        && Objects.equal(serviceName, that.serviceName)
        && fallbackTimeoutMs == that.fallbackTimeoutMs;
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(mode, serviceName, fallbackTimeoutMs);
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
        .add("mode", mode)
        .add("serviceName", serviceName)
        .add("fallbackTimeoutMs", fallbackTimeoutMs)
        .toString();
  }
}
