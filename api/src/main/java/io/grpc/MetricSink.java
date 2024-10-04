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
import java.util.Map;
import java.util.Set;

/**
 * An internal interface representing a receiver or aggregator of gRPC metrics data.
 */
@Internal
public interface MetricSink {

  /**
   * Returns a set of names for the metrics that are currently enabled or disabled.
   *
   * @return A set of enabled metric names.
   */
  Map<String, Boolean> getEnabledMetrics();

  /**
   * Returns a set of optional label names for metrics that the sink actually wants.
   *
   * @return A set of optional label names.
   */
  Set<String> getOptionalLabels();

  /**
   * Returns size of metric measures used to record metric values. These measures are created
   * based on registered metrics (via MetricInstrumentRegistry) and are ordered according to their
   * registration sequence.
   *
   * @return Size of metric measures.
   */
  int getMeasuresSize();

  /**
   * Adds a value for a double-precision counter associated with specified metric instrument.
   *
   * @param metricInstrument The counter metric instrument identifies metric measure to add.
   * @param value The value to record.
   * @param requiredLabelValues A list of required label values for the metric.
   * @param optionalLabelValues A list of additional, optional label values for the metric.
   */
  default void addDoubleCounter(DoubleCounterMetricInstrument metricInstrument, double value,
      List<String> requiredLabelValues, List<String> optionalLabelValues) {
  }

  /**
   * Adds a value for a long valued counter metric associated with specified metric instrument.
   *
   * @param metricInstrument The counter metric instrument identifies metric measure to add.
   * @param value The value to record.
   * @param requiredLabelValues A list of required label values for the metric.
   * @param optionalLabelValues A list of additional, optional label values for the metric.
   */
  default void addLongCounter(LongCounterMetricInstrument metricInstrument, long value,
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

  /**
   * Record a long gauge value.
   *
   * @param value The value to record.
   * @param requiredLabelValues A list of required label values for the metric.
   * @param optionalLabelValues A list of additional, optional label values for the metric.
   */
  default void recordLongGauge(LongGaugeMetricInstrument metricInstrument, long value,
      List<String> requiredLabelValues, List<String> optionalLabelValues){
  }

  /**
   * Registers a callback to produce metric values for only the listed instruments. The returned
   * registration must be closed when no longer needed, which will remove the callback.
   *
   * @param callback The callback to call to record.
   * @param metricInstruments The metric instruments the callback will record against.
   */
  default Registration registerBatchCallback(Runnable callback,
      CallbackMetricInstrument... metricInstruments) {
    return () -> { };
  }

  interface Registration extends MetricRecorder.Registration {}

  void updateMeasures(List<MetricInstrument> instruments);
}
