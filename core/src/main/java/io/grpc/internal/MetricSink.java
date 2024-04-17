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

package io.grpc.internal;

import io.grpc.DoubleCounterMetricInstrument;
import io.grpc.DoubleHistogramMetricInstrument;
import io.grpc.Internal;
import io.grpc.LongCounterMetricInstrument;
import io.grpc.LongHistogramMetricInstrument;
import java.util.List;
import java.util.Set;

/**
 * An internal interface representing a receiver or aggregator of gRPC metrics data.
 */
@Internal
public interface MetricSink {

  /**
   * Returns a set of names for the metrics that are currently enabled for this `MetricSink`.
   *
   * @return A set of enabled metric names.
   */
  Set<String> getEnabledMetrics();

  /**
   * Returns a list of label names that are considered optional for metrics collected by this
   * `MetricSink`.
   *
   * @return A list of optional label names.
   */
  List<String> getOptionalLabels();

  /**
   * Returns a list of metric measures used to record metric values. These measures are created
   * based on registered metrics (via MetricInstrumentRegistry) and are ordered according to their
   * registration sequence.
   *
   * @return A list of metric measures.
   */
  List<Object> getMetricsMeasures();

  /**
   * Records a value for a double-precision counter associated with specified metric instrument.
   *
   * @param metricInstrument The counter metric instrument identifies metric measure to record.
   * @param value The value to record.
   * @param requiredLabelValues A list of required label values for the metric.
   * @param optionalLabelValues A list of additional, optional label values for the metric.
   */
  default void recordDoubleCounter(DoubleCounterMetricInstrument metricInstrument, double value,
      List<String> requiredLabelValues, List<String> optionalLabelValues) {
  }

  /**
   * Records a value for a long valued counter metric associated with specified metric instrument.
   *
   * @param metricInstrument The counter metric instrument identifies metric measure to record.
   * @param value The value to record.
   * @param requiredLabelValues A list of required label values for the metric.
   * @param optionalLabelValues A list of additional, optional label values for the metric.
   */
  default void recordLongCounter(LongCounterMetricInstrument metricInstrument, long value,
      List<String> requiredLabelValues, List<String> optionalLabelValues) {
  }

  /**
   * Records a value for a double-precision histogram metric associated with specified metric
   * instrument.
   *
   * @param metricInstrument The histogram metric instrument identifies metric measure to record.
   * @param value The value to record.
   * @param requiredLabelValues A list of required label values for the metric.
   * @param optionalLabelValues A list of additional, optional label values for the metric.
   */
  default void recordDoubleHistogram(DoubleHistogramMetricInstrument metricInstrument, double value,
      List<String> requiredLabelValues, List<String> optionalLabelValues) {
  }

  /**
   * Records a value for a long valued histogram metric associated with specified metric
   * instrument.
   *
   * @param metricInstrument The histogram metric instrument identifies metric measure to record.
   * @param value The value to record.
   * @param requiredLabelValues A list of required label values for the metric.
   * @param optionalLabelValues A list of additional, optional label values for the metric.
   */
  default void recordLongHistogram(LongHistogramMetricInstrument metricInstrument, long value,
      List<String> requiredLabelValues, List<String> optionalLabelValues) {
  }
}
