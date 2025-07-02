/*
 * Copyright 2025 The gRPC Authors
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

final class SlowStartConfig {
  final double minWeightPercent;
  final double aggression;
  final long slowStartWindowNanos;

  public static Builder newBuilder() {
    return new Builder();
  }

  private SlowStartConfig(double minWeightPercent, double aggression, long slowStartWindowNanos) {
    this.minWeightPercent = minWeightPercent;
    this.aggression = aggression;
    this.slowStartWindowNanos = slowStartWindowNanos;
  }

  static final class Builder {
    private double minWeightPercent = 10.0;
    private double aggression = 1.0;
    private long slowStartWindowNanos = 0L;

    private Builder() {
    }

    @SuppressWarnings("UnusedReturnValue")
    Builder setMinWeightPercent(double minWeightPercent) {
      this.minWeightPercent = minWeightPercent;
      return this;
    }

    @SuppressWarnings("UnusedReturnValue")
    Builder setAggression(double aggression) {
      this.aggression = aggression;
      return this;
    }

    @SuppressWarnings("UnusedReturnValue")
    Builder setSlowStartWindowNanos(long slowStartWindowNanos) {
      this.slowStartWindowNanos = slowStartWindowNanos;
      return this;
    }

    SlowStartConfig build() {
      return new SlowStartConfig(minWeightPercent, aggression, slowStartWindowNanos);
    }
  }
}
