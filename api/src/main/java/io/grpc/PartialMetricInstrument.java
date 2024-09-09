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

import com.google.common.collect.ImmutableList;
import java.util.List;

/**
 * A partial implementation of the {@link MetricInstrument} interface. This class
 * provides common fields and functionality for metric instruments.
 */
@Internal
abstract class PartialMetricInstrument implements MetricInstrument {
  protected final int index;
  protected final String name;
  protected final String description;
  protected final String unit;
  protected final List<String> requiredLabelKeys;
  protected final List<String> optionalLabelKeys;
  protected final boolean enableByDefault;

  /**
   * Constructs a new PartialMetricInstrument with the specified attributes.
   *
   * @param index             the unique index of this metric instrument
   * @param name              the name of the metric
   * @param description       a description of the metric
   * @param unit              the unit of measurement for the metric
   * @param requiredLabelKeys a list of required label keys for the metric
   * @param optionalLabelKeys a list of optional label keys for the metric
   * @param enableByDefault   whether the metric should be enabled by default
   */
  protected PartialMetricInstrument(int index, String name, String description, String unit,
      List<String> requiredLabelKeys, List<String> optionalLabelKeys, boolean enableByDefault) {
    this.index = index;
    this.name = name;
    this.description = description;
    this.unit = unit;
    this.requiredLabelKeys = ImmutableList.copyOf(requiredLabelKeys);
    this.optionalLabelKeys = ImmutableList.copyOf(optionalLabelKeys);
    this.enableByDefault = enableByDefault;
  }

  @Override
  public int getIndex() {
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

  @Override
  public boolean isEnableByDefault() {
    return enableByDefault;
  }

  @Override
  public String toString() {
    return getClass().getName() + "(" + getName() + ")";
  }
}
