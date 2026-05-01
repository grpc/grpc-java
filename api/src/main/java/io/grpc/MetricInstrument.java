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
 * Represents a metric instrument. Metric instrument contains information used to describe a metric.
 */
@Internal
public interface MetricInstrument {
  /**
   * Returns the unique index of this metric instrument.
   *
   * @return the index of the metric instrument.
   */
  public int getIndex();

  /**
   * Returns the name of the metric.
   *
   * @return the name of the metric.
   */
  public String getName();

  /**
   * Returns a description of the metric.
   *
   * @return a description of the metric.
   */
  public String getDescription();

  /**
   * Returns the unit of measurement for the metric.
   *
   * @return the unit of measurement.
   */
  public String getUnit();

  /**
   * Returns a list of required label keys for this metric instrument.
   *
   * @return a list of required label keys.
   */
  public List<String> getRequiredLabelKeys();

  /**
   * Returns a list of optional label keys for this metric instrument.
   *
   * @return a list of optional label keys.
   */
  public List<String> getOptionalLabelKeys();

  /**
   * Indicates whether this metric instrument is enabled by default.
   *
   * @return {@code true} if this metric instrument is enabled by default,
   *         {@code false} otherwise.
   */
  public boolean isEnableByDefault();
}
