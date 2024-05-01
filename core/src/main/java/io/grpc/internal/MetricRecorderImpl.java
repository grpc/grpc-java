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

import static com.google.common.base.Preconditions.checkArgument;

import com.google.common.annotations.VisibleForTesting;
import io.grpc.DoubleCounterMetricInstrument;
import io.grpc.DoubleHistogramMetricInstrument;
import io.grpc.LongCounterMetricInstrument;
import io.grpc.LongHistogramMetricInstrument;
import io.grpc.MetricInstrument;
import io.grpc.MetricInstrumentRegistry;
import io.grpc.MetricRecorder;
import io.grpc.MetricSink;
import java.util.List;

/**
 * Provides a central point  for gRPC components to record metric values. Metrics can be exported to
 * monitoring systems by configuring one or more {@link MetricSink}s.
 *
 * <p>This class encapsulates the interaction with metric sinks, including updating them with
 * the latest set of {@link MetricInstrument}s provided by the {@link MetricInstrumentRegistry}.
 */
final class MetricRecorderImpl implements MetricRecorder {

  private final List<MetricSink> metricSinks;
  private final MetricInstrumentRegistry registry;

  @VisibleForTesting
  MetricRecorderImpl(List<MetricSink> metricSinks, MetricInstrumentRegistry registry) {
    this.metricSinks = metricSinks;
    this.registry = registry;
  }

  /**
   * Records a double counter value.
   *
   * @param metricInstrument the {@link DoubleCounterMetricInstrument} to record.
   * @param value the value to record.
   * @param requiredLabelValues the required label values for the metric.
   * @param optionalLabelValues the optional label values for the metric.
   */
  @Override
  public void addDoubleCounter(DoubleCounterMetricInstrument metricInstrument, double value,
      List<String> requiredLabelValues, List<String> optionalLabelValues) {
    checkArgument(requiredLabelValues != null
            && requiredLabelValues.size() == metricInstrument.getRequiredLabelKeys().size(),
        "Incorrect number of required labels provided. Expected: "
            + metricInstrument.getRequiredLabelKeys().size());
    checkArgument(optionalLabelValues != null
            && optionalLabelValues.size() == metricInstrument.getOptionalLabelKeys().size(),
        "Incorrect number of optional labels provided. Expected: "
            + metricInstrument.getOptionalLabelKeys().size());
    for (MetricSink sink : metricSinks) {
      // TODO(dnvindhya): Move updating measures logic from sink to here
      int measuresSize = sink.getMeasuresSize();
      if (measuresSize != -1 && measuresSize <= metricInstrument.getIndex()) {
        // Measures may need updating in two cases:
        // 1. When the sink is initially created with an empty list of measures.
        // 2. When new metric instruments are registered, requiring the sink to accommodate them.
        sink.updateMeasures(registry.getMetricInstruments());
      }
      sink.addDoubleCounter(metricInstrument, value, requiredLabelValues, optionalLabelValues);
    }
  }

  /**
   * Records a long counter value.
   *
   * @param metricInstrument the {@link LongCounterMetricInstrument} to record.
   * @param value the value to record.
   * @param requiredLabelValues the required label values for the metric.
   * @param optionalLabelValues the optional label values for the metric.
   */
  @Override
  public void addLongCounter(LongCounterMetricInstrument metricInstrument, long value,
      List<String> requiredLabelValues, List<String> optionalLabelValues) {
    checkArgument(requiredLabelValues != null
            && requiredLabelValues.size() == metricInstrument.getRequiredLabelKeys().size(),
        "Incorrect number of required labels provided. Expected: "
            + metricInstrument.getRequiredLabelKeys().size());
    checkArgument(optionalLabelValues != null
            && optionalLabelValues.size() == metricInstrument.getOptionalLabelKeys().size(),
        "Incorrect number of optional labels provided. Expected: "
            + metricInstrument.getOptionalLabelKeys().size());
    for (MetricSink sink : metricSinks) {
      int measuresSize = sink.getMeasuresSize();
      if (measuresSize != -1 && measuresSize <= metricInstrument.getIndex()) {
        // Measures may need updating in two cases:
        // 1. When the sink is initially created with an empty list of measures.
        // 2. When new metric instruments are registered, requiring the sink to accommodate them.
        sink.updateMeasures(registry.getMetricInstruments());
      }
      sink.addLongCounter(metricInstrument, value, requiredLabelValues, optionalLabelValues);
    }
  }

  /**
   * Records a double histogram value.
   *
   * @param metricInstrument the {@link DoubleHistogramMetricInstrument} to record.
   * @param value the value to record.
   * @param requiredLabelValues the required label values for the metric.
   * @param optionalLabelValues the optional label values for the metric.
   */
  @Override
  public void recordDoubleHistogram(DoubleHistogramMetricInstrument metricInstrument, double value,
      List<String> requiredLabelValues, List<String> optionalLabelValues) {
    checkArgument(requiredLabelValues != null
            && requiredLabelValues.size() == metricInstrument.getRequiredLabelKeys().size(),
        "Incorrect number of required labels provided. Expected: "
            + metricInstrument.getRequiredLabelKeys().size());
    checkArgument(optionalLabelValues != null
            && optionalLabelValues.size() == metricInstrument.getOptionalLabelKeys().size(),
        "Incorrect number of optional labels provided. Expected: "
            + metricInstrument.getOptionalLabelKeys().size());
    for (MetricSink sink : metricSinks) {
      int measuresSize = sink.getMeasuresSize();
      if (measuresSize != -1 && measuresSize <= metricInstrument.getIndex()) {
        // Measures may need updating in two cases:
        // 1. When the sink is initially created with an empty list of measures.
        // 2. When new metric instruments are registered, requiring the sink to accommodate them.
        sink.updateMeasures(registry.getMetricInstruments());
      }
      sink.recordDoubleHistogram(metricInstrument, value, requiredLabelValues, optionalLabelValues);
    }
  }

  /**
   * Records a long histogram value.
   *
   * @param metricInstrument the {@link LongHistogramMetricInstrument} to record.
   * @param value the value to record.
   * @param requiredLabelValues the required label values for the metric.
   * @param optionalLabelValues the optional label values for the metric.
   */
  @Override
  public void recordLongHistogram(LongHistogramMetricInstrument metricInstrument, long value,
      List<String> requiredLabelValues, List<String> optionalLabelValues) {
    checkArgument(requiredLabelValues != null
            && requiredLabelValues.size() == metricInstrument.getRequiredLabelKeys().size(),
        "Incorrect number of required labels provided. Expected: "
            + metricInstrument.getRequiredLabelKeys().size());
    checkArgument(optionalLabelValues != null
            && optionalLabelValues.size() == metricInstrument.getOptionalLabelKeys().size(),
        "Incorrect number of optional labels provided. Expected: "
            + metricInstrument.getOptionalLabelKeys().size());
    for (MetricSink sink : metricSinks) {
      int measuresSize = sink.getMeasuresSize();
      if (measuresSize != -1 && measuresSize <= metricInstrument.getIndex()) {
        // Measures may need updating in two cases:
        // 1. When the sink is initially created with an empty list of measures.
        // 2. When new metric instruments are registered, requiring the sink to accommodate them.
        sink.updateMeasures(registry.getMetricInstruments());
      }
      sink.recordLongHistogram(metricInstrument, value, requiredLabelValues, optionalLabelValues);
    }
  }
}
