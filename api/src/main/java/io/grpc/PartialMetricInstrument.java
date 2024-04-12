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
 * A partial implementation of the {@link MetricInstrument} interface. This class
 * provides common fields and functionality for metric instruments.
 */
@Internal
abstract class PartialMetricInstrument {
  protected final long index;
  protected final String name;
  protected final String description;
  protected final String unit;
  protected final List<String> requiredLabelKeys;
  protected final List<String> optionalLabelKeys;

  /**
   * Constructs a new PartialMetricInstrument with the specified attributes.
   *
   * @param index             the unique index of this metric instrument
   * @param name              the name of the metric
   * @param description       a description of the metric
   * @param unit              the unit of measurement for the metric
   * @param requiredLabelKeys a list of required label keys for the metric
   * @param optionalLabelKeys a list of optional label keys for the metric
   */
  protected PartialMetricInstrument(long index, String name, String description, String unit,
      List<String> requiredLabelKeys, List<String> optionalLabelKeys) {
    this.index = index;
    this.name = name;
    this.description = description;
    this.unit = unit;
    this.requiredLabelKeys = requiredLabelKeys;
    this.optionalLabelKeys = optionalLabelKeys;
  }

  /**
   * Returns a list of required label keys for this metric instrument.
   *
   * @return a list of required label keys.
   */
  abstract List<String> getRequiredLabelKeys();

  /**
   * Returns a list of optional label keys for this metric instrument.
   *
   * @return a list of optional label keys.
   */
  abstract List<String> getOptionalLabelKeys();
}
