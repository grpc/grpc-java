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

import static com.google.common.base.Preconditions.checkArgument;

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
      List<String> requiredLabelValues, List<String> optionalLabelValues) {
    checkArgument(requiredLabelValues != null
            && requiredLabelValues.size() == metricInstrument.getRequiredLabelKeys().size(),
        "Incorrect number of required labels provided. Expected: %s",
        metricInstrument.getRequiredLabelKeys().size());
    checkArgument(optionalLabelValues != null
            && optionalLabelValues.size() == metricInstrument.getOptionalLabelKeys().size(),
        "Incorrect number of optional labels provided. Expected: %s",
        metricInstrument.getOptionalLabelKeys().size());
  }

  /**
   * Adds a value for a long valued counter metric instrument.
   *
   * @param metricInstrument The counter metric instrument to add the value against.
   * @param value The value to add.
   * @param requiredLabelValues A list of required label values for the metric.
   * @param optionalLabelValues A list of additional, optional label values for the metric.
   */
  default void addLongCounter(LongCounterMetricInstrument metricInstrument, long value,
      List<String> requiredLabelValues, List<String> optionalLabelValues) {
    checkArgument(requiredLabelValues != null
            && requiredLabelValues.size() == metricInstrument.getRequiredLabelKeys().size(),
        "Incorrect number of required labels provided. Expected: %s",
        metricInstrument.getRequiredLabelKeys().size());
    checkArgument(optionalLabelValues != null
            && optionalLabelValues.size() == metricInstrument.getOptionalLabelKeys().size(),
        "Incorrect number of optional labels provided. Expected: %s",
        metricInstrument.getOptionalLabelKeys().size());
  }

  /**
   * Records a value for a double-precision histogram metric instrument.
   *
   * @param metricInstrument The histogram metric instrument to record the value against.
   * @param value The value to record.
   * @param requiredLabelValues A list of required label values for the metric.
   * @param optionalLabelValues A list of additional, optional label values for the metric.
   */
  default void recordDoubleHistogram(DoubleHistogramMetricInstrument metricInstrument, double value,
      List<String> requiredLabelValues, List<String> optionalLabelValues) {
    checkArgument(requiredLabelValues != null
            && requiredLabelValues.size() == metricInstrument.getRequiredLabelKeys().size(),
        "Incorrect number of required labels provided. Expected: %s",
        metricInstrument.getRequiredLabelKeys().size());
    checkArgument(optionalLabelValues != null
            && optionalLabelValues.size() == metricInstrument.getOptionalLabelKeys().size(),
        "Incorrect number of optional labels provided. Expected: %s",
        metricInstrument.getOptionalLabelKeys().size());
  }

  /**
   * Records a value for a long valued histogram metric instrument.
   *
   * @param metricInstrument The histogram metric instrument to record the value against.
   * @param value The value to record.
   * @param requiredLabelValues A list of required label values for the metric.
   * @param optionalLabelValues A list of additional, optional label values for the metric.
   */
  default void recordLongHistogram(LongHistogramMetricInstrument metricInstrument, long value,
      List<String> requiredLabelValues, List<String> optionalLabelValues) {
    checkArgument(requiredLabelValues != null
            && requiredLabelValues.size() == metricInstrument.getRequiredLabelKeys().size(),
        "Incorrect number of required labels provided. Expected: %s",
        metricInstrument.getRequiredLabelKeys().size());
    checkArgument(optionalLabelValues != null
            && optionalLabelValues.size() == metricInstrument.getOptionalLabelKeys().size(),
        "Incorrect number of optional labels provided. Expected: %s",
        metricInstrument.getOptionalLabelKeys().size());
  }

  /**
   * Registers a callback to produce metric values for only the listed instruments. The returned
   * registration must be closed when no longer needed, which will remove the callback.
   *
   * @param callback The callback to call to record.
   * @param metricInstruments The metric instruments the callback will record against.
   */
  default Registration registerBatchCallback(BatchCallback callback,
      CallbackMetricInstrument... metricInstruments) {
    return () -> { };
  }

  /** Callback to record gauge values. */
  interface BatchCallback {
    /** Records instrument values into {@code recorder}. */
    void accept(BatchRecorder recorder);
  }

  /** Recorder for instrument values produced by a batch callback. */
  interface BatchRecorder {
    /**
     * Record a long gauge value.
     *
     * @param value The value to record.
     * @param requiredLabelValues A list of required label values for the metric.
     * @param optionalLabelValues A list of additional, optional label values for the metric.
     */
    default void recordLongGauge(LongGaugeMetricInstrument metricInstrument, long value,
        List<String> requiredLabelValues, List<String> optionalLabelValues) {
      checkArgument(requiredLabelValues != null
              && requiredLabelValues.size() == metricInstrument.getRequiredLabelKeys().size(),
          "Incorrect number of required labels provided. Expected: %s",
          metricInstrument.getRequiredLabelKeys().size());
      checkArgument(optionalLabelValues != null
              && optionalLabelValues.size() == metricInstrument.getOptionalLabelKeys().size(),
          "Incorrect number of optional labels provided. Expected: %s",
          metricInstrument.getOptionalLabelKeys().size());
    }
  }

  /** A handle to a registration, that allows unregistration. */
  interface Registration extends AutoCloseable {
    // Redefined to not throw an exception.
    /** Unregister. */
    @Override
    void close();
  }
}
