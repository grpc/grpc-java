/*
 * Copyright 2024 The gRPC Authors
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

package io.grpc;

import java.util.List;

/**
 * Represents a double-valued histogram metric instrument.
 */
@Internal
public final class DoubleHistogramMetricInstrument extends PartialMetricInstrument {
  private final List<Double> bucketBoundaries;

  public DoubleHistogramMetricInstrument(int index, String name, String description, String unit,
      List<Double> bucketBoundaries, List<String> requiredLabelKeys, List<String> optionalLabelKeys,
      boolean enableByDefault) {
    super(index, name, description, unit, requiredLabelKeys, optionalLabelKeys, enableByDefault);
    this.bucketBoundaries = bucketBoundaries;
  }

  public List<Double> getBucketBoundaries() {
    return bucketBoundaries;
  }
}
