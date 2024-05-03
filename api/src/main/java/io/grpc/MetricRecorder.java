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
 * An interface used for recording gRPC metrics. Implementations of this interface are responsible
 * for collecting and potentially reporting metrics from various gRPC components.
 */
@Internal
public interface MetricRecorder {
  /**
   * Adds a value for a double-precision counter metric instrument.
   *
   * @param metricInstrument The counter metric instrument to add the value against.
   * @param value The value to add.
   * @param requiredLabelValues A list of required label values for the metric.
   * @param optionalLabelValues A list of additional, optional label values for the metric.
   */
  default void addDoubleCounter(DoubleCounterMetricInstrument metricInstrument, double value,
      List<String> requiredLabelValues, List<String> optionalLabelValues) {}

  /**
   * Adds a value for a long valued counter metric instrument.
   *
   * @param metricInstrument The counter metric instrument to add the value against.
   * @param value The value to add.
   * @param requiredLabelValues A list of required label values for the metric.
   * @param optionalLabelValues A list of additional, optional label values for the metric.
   */
  default void addLongCounter(LongCounterMetricInstrument metricInstrument, long value,
      List<String> requiredLabelValues, List<String> optionalLabelValues) {}

  /**
   * Records a value for a double-precision histogram metric instrument.
   *
   * @param metricInstrument The histogram metric instrument to record the value against.
   * @param value The value to record.
   * @param requiredLabelValues A list of required label values for the metric.
   * @param optionalLabelValues A list of additional, optional label values for the metric.
   */
  default void recordDoubleHistogram(DoubleHistogramMetricInstrument metricInstrument, double value,
      List<String> requiredLabelValues, List<String> optionalLabelValues) {}

  /**
   * Records a value for a long valued histogram metric instrument.
   *
   * @param metricInstrument The histogram metric instrument to record the value against.
   * @param value The value to record.
   * @param requiredLabelValues A list of required label values for the metric.
   * @param optionalLabelValues A list of additional, optional label values for the metric.
   */
  default void recordLongHistogram(LongHistogramMetricInstrument metricInstrument, long value,
      List<String> requiredLabelValues, List<String> optionalLabelValues) {}
}
