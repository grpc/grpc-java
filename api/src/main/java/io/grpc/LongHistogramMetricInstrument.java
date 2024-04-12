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
 * Represents a long-valued histogram metric instrument.
 */
@Internal
class LongHistogramMetricInstrument extends PartialMetricInstrument implements
    MetricInstrument {
  private final boolean isEnabledByDefault;
  private final List<Long> bucketBoundaries;

  LongHistogramMetricInstrument(long index, String name, String description, String unit,
      List<Long> bucketBoundaries, List<String> requiredLabelKeys, List<String> optionalLabelKeys,
      boolean isEnabledByDefault) {
    super(index, name, description, unit, requiredLabelKeys, optionalLabelKeys);
    this.isEnabledByDefault = isEnabledByDefault;
    this.bucketBoundaries = bucketBoundaries;
  }

  @Override
  public long getIndex() {
    return index;
  }

  @Override
  public String getName() {
    return name;
  }

  @Override
  public String getDescription() {
    return description;
  }

  @Override
  public String getUnit() {
    return unit;
  }

  @Override
  public List<String> getRequiredLabelKeys() {
    return requiredLabelKeys;
  }

  @Override
  public List<String> getOptionalLabelKeys() {
    return optionalLabelKeys;
  }

  public boolean getEnabledByDefault() {
    return isEnabledByDefault;
  }

  public List<Long> getBucketBoundaries() {
    return bucketBoundaries;
  }
}

