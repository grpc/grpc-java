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
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.annotations.VisibleForTesting;
import io.grpc.CallbackMetricInstrument;
import io.grpc.DoubleCounterMetricInstrument;
import io.grpc.DoubleHistogramMetricInstrument;
import io.grpc.LongCounterMetricInstrument;
import io.grpc.LongGaugeMetricInstrument;
import io.grpc.LongHistogramMetricInstrument;
import io.grpc.MetricInstrument;
import io.grpc.MetricInstrumentRegistry;
import io.grpc.MetricRecorder;
import io.grpc.MetricSink;
import java.util.ArrayList;
import java.util.BitSet;
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
    MetricRecorder.super.addDoubleCounter(metricInstrument, value, requiredLabelValues,
        optionalLabelValues);
    for (MetricSink sink : metricSinks) {
      // TODO(dnvindhya): Move updating measures logic from sink to here
      int measuresSize = sink.getMeasuresSize();
      if (measuresSize <= metricInstrument.getIndex()) {
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
    MetricRecorder.super.addLongCounter(metricInstrument, value, requiredLabelValues,
        optionalLabelValues);
    for (MetricSink sink : metricSinks) {
      int measuresSize = sink.getMeasuresSize();
      if (measuresSize <= metricInstrument.getIndex()) {
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
    MetricRecorder.super.recordDoubleHistogram(metricInstrument, value, requiredLabelValues,
        optionalLabelValues);
    for (MetricSink sink : metricSinks) {
      int measuresSize = sink.getMeasuresSize();
      if (measuresSize <= metricInstrument.getIndex()) {
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
    MetricRecorder.super.recordLongHistogram(metricInstrument, value, requiredLabelValues,
        optionalLabelValues);
    for (MetricSink sink : metricSinks) {
      int measuresSize = sink.getMeasuresSize();
      if (measuresSize <= metricInstrument.getIndex()) {
        // Measures may need updating in two cases:
        // 1. When the sink is initially created with an empty list of measures.
        // 2. When new metric instruments are registered, requiring the sink to accommodate them.
        sink.updateMeasures(registry.getMetricInstruments());
      }
      sink.recordLongHistogram(metricInstrument, value, requiredLabelValues, optionalLabelValues);
    }
  }

  @Override
  public Registration registerBatchCallback(BatchCallback callback,
      CallbackMetricInstrument... metricInstruments) {
    long largestMetricInstrumentIndex = -1;
    BitSet allowedInstruments = new BitSet();
    for (CallbackMetricInstrument metricInstrument : metricInstruments) {
      largestMetricInstrumentIndex =
          Math.max(largestMetricInstrumentIndex, metricInstrument.getIndex());
      allowedInstruments.set(metricInstrument.getIndex());
    }
    List<MetricSink.Registration> registrations = new ArrayList<>();
    for (MetricSink sink : metricSinks) {
      int measuresSize = sink.getMeasuresSize();
      if (measuresSize <= largestMetricInstrumentIndex) {
        // Measures may need updating in two cases:
        // 1. When the sink is initially created with an empty list of measures.
        // 2. When new metric instruments are registered, requiring the sink to accommodate them.
        sink.updateMeasures(registry.getMetricInstruments());
      }
      BatchRecorder singleSinkRecorder = new BatchRecorderImpl(sink, allowedInstruments);
      registrations.add(sink.registerBatchCallback(
          () -> callback.accept(singleSinkRecorder), metricInstruments));
    }
    return () -> {
      for (MetricSink.Registration registration : registrations) {
        registration.close();
      }
    };
  }

  /** Recorder for instrument values produced by a batch callback. */
  static class BatchRecorderImpl implements BatchRecorder {
    private final MetricSink sink;
    private final BitSet allowedInstruments;

    BatchRecorderImpl(MetricSink sink, BitSet allowedInstruments) {
      this.sink = checkNotNull(sink, "sink");
      this.allowedInstruments = checkNotNull(allowedInstruments, "allowedInstruments");
    }

    @Override
    public void recordLongGauge(LongGaugeMetricInstrument metricInstrument, long value,
        List<String> requiredLabelValues, List<String> optionalLabelValues) {
      BatchRecorder.super.recordLongGauge(metricInstrument, value, requiredLabelValues,
          optionalLabelValues);
      checkArgument(allowedInstruments.get(metricInstrument.getIndex()),
          "Instrument was not listed when registering callback: %s", metricInstrument);
      // Registering the callback checked that the instruments were be present in sink.
      sink.recordLongGauge(metricInstrument, value, requiredLabelValues, optionalLabelValues);
    }
  }
}
