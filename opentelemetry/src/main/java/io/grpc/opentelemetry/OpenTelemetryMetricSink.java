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

package io.grpc.opentelemetry;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSet;
import io.grpc.DoubleCounterMetricInstrument;
import io.grpc.DoubleHistogramMetricInstrument;
import io.grpc.Internal;
import io.grpc.LongCounterMetricInstrument;
import io.grpc.LongHistogramMetricInstrument;
import io.grpc.MetricInstrument;
import io.grpc.MetricSink;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.metrics.DoubleCounter;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongHistogram;
import io.opentelemetry.api.metrics.Meter;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

@Internal
final class OpenTelemetryMetricSink implements MetricSink {
  private static final Logger logger = Logger.getLogger(OpenTelemetryMetricSink.class.getName());
  private final Object lock = new Object();
  private final Meter openTelemetryMeter;
  private final Map<String, Boolean> enableMetrics;
  private final boolean disableDefaultMetrics;
  private final Set<String> optionalLabels;
  private volatile List<MeasuresData> measures = new ArrayList<>();

  OpenTelemetryMetricSink(Meter meter, Map<String, Boolean> enableMetrics,
      boolean disableDefaultMetrics, List<String> optionalLabels) {
    this.openTelemetryMeter = meter;
    this.enableMetrics = enableMetrics;
    this.disableDefaultMetrics = disableDefaultMetrics;
    this.optionalLabels = ImmutableSet.copyOf(optionalLabels);
  }

  @Override
  public Map<String, Boolean> getEnabledMetrics() {
    return enableMetrics;
  }

  @Override
  public Set<String> getOptionalLabels() {
    return optionalLabels;
  }

  @Override
  public int getMeasuresSize() {
    return measures.size();
  }

  @VisibleForTesting
  List<MeasuresData> getMeasures() {
    synchronized (lock) {
      return Collections.unmodifiableList(measures);
    }
  }

  @Override
  public void addDoubleCounter(DoubleCounterMetricInstrument metricInstrument, double value,
      List<String> requiredLabelValues, List<String> optionalLabelValues) {
    int index = metricInstrument.getIndex();
    DoubleCounter counter = (DoubleCounter) measures.get(index).getMeasure();
    if (counter == null) {
      // Disabled metric
      return;
    }
    Attributes attributes = createAttributes(metricInstrument.getRequiredLabelKeys(),
        metricInstrument.getOptionalLabelKeys(), requiredLabelValues, optionalLabelValues,
        measures.get(index).getOptionalLabelsBitSet());
    counter.add(value, attributes);
  }

  @Override
  public void addLongCounter(LongCounterMetricInstrument metricInstrument, long value,
      List<String> requiredLabelValues, List<String> optionalLabelValues) {
    MetricSink.super.addLongCounter(metricInstrument, value, requiredLabelValues,
        optionalLabelValues);
    int index = metricInstrument.getIndex();
    LongCounter counter = (LongCounter) measures.get(index).getMeasure();
    if (counter == null) {
      // Disabled metric
      return;
    }
    Attributes attributes = createAttributes(metricInstrument.getRequiredLabelKeys(),
        metricInstrument.getOptionalLabelKeys(), requiredLabelValues, optionalLabelValues,
        measures.get(index).getOptionalLabelsBitSet());
    counter.add(value, attributes);
  }

  @Override
  public void recordDoubleHistogram(DoubleHistogramMetricInstrument metricInstrument, double value,
      List<String> requiredLabelValues, List<String> optionalLabelValues) {
    MetricSink.super.recordDoubleHistogram(metricInstrument, value, requiredLabelValues,
        optionalLabelValues);
    int index = metricInstrument.getIndex();
    DoubleHistogram histogram = (DoubleHistogram) measures.get(index).getMeasure();
    if (histogram == null) {
      // Disabled metric
      return;
    }
    Attributes attributes = createAttributes(metricInstrument.getRequiredLabelKeys(),
        metricInstrument.getOptionalLabelKeys(), requiredLabelValues, optionalLabelValues,
        measures.get(index).getOptionalLabelsBitSet());

    histogram.record(value, attributes);
  }

  @Override
  public void recordLongHistogram(LongHistogramMetricInstrument metricInstrument, long value,
      List<String> requiredLabelValues, List<String> optionalLabelValues) {
    int index = metricInstrument.getIndex();
    LongHistogram histogram = (LongHistogram) measures.get(index).getMeasure();
    if (histogram == null) {
      // Disabled metric
      return;
    }
    Attributes attributes = createAttributes(metricInstrument.getRequiredLabelKeys(),
        metricInstrument.getOptionalLabelKeys(), requiredLabelValues, optionalLabelValues,
        measures.get(index).getOptionalLabelsBitSet());
    histogram.record(value, attributes);
  }

  @Override
  public void updateMeasures(List<MetricInstrument> instruments) {
    synchronized (lock) {
      if (measures.size() >= instruments.size()) {
        // Already up-to-date
        return;
      }

      List<MeasuresData> newMeasures = new ArrayList<>(instruments.size());
      // Reuse existing measures
      newMeasures.addAll(measures);

      for (int i = measures.size(); i < instruments.size(); i++) {
        MetricInstrument instrument = instruments.get(i);
        int optionalLabelsSize = instrument.getOptionalLabelKeys().size();
        // Check if the metric is disabled
        if (!shouldEnableMetric(instrument)) {
          // Adding null measure for disabled Metric
          newMeasures.add(new MeasuresData(instrument, new BitSet(optionalLabelsSize), null));
          continue;
        }

        BitSet bitSet = new BitSet(instrument.getOptionalLabelKeys().size());
        if (optionalLabels.isEmpty()) {
          // initialize an empty list
        } else {
          List<String> labels = instrument.getOptionalLabelKeys();
          for (int j = 0; j < labels.size(); j++) {
            if (optionalLabels.contains(labels.get(j))) {
              bitSet.set(j);
            }
          }
        }

        int index = instrument.getIndex();
        String name = instrument.getName();
        String unit = instrument.getUnit();
        String description = instrument.getDescription();

        Object openTelemetryMeasure;
        if (instrument instanceof DoubleCounterMetricInstrument) {
          openTelemetryMeasure = openTelemetryMeter.counterBuilder(name)
              .setUnit(unit)
              .setDescription(description)
              .ofDoubles()
              .build();
        } else if (instrument instanceof LongCounterMetricInstrument) {
          openTelemetryMeasure = openTelemetryMeter.counterBuilder(name)
              .setUnit(unit)
              .setDescription(description)
              .build();
        } else if (instrument instanceof DoubleHistogramMetricInstrument) {
          openTelemetryMeasure = openTelemetryMeter.histogramBuilder(name)
              .setUnit(unit)
              .setDescription(description)
              .build();
        } else if (instrument instanceof LongHistogramMetricInstrument) {
          openTelemetryMeasure = openTelemetryMeter.histogramBuilder(name)
              .setUnit(unit)
              .setDescription(description)
              .ofLongs()
              .build();
        } else {
          logger.log(Level.FINE, "Unsupported metric instrument type : {0}", instrument);
          openTelemetryMeasure = null;
        }
        newMeasures.add(index, new MeasuresData(instrument, bitSet, openTelemetryMeasure));
      }

      measures = newMeasures;
    }
  }

  private boolean shouldEnableMetric(MetricInstrument instrument) {
    String name = instrument.getName();
    boolean enabledForSink = enableMetrics.getOrDefault(name, false);
    boolean notDisabledDefault =  instrument.isEnableByDefault() && !disableDefaultMetrics;
    return enabledForSink || notDisabledDefault;
    // return enableMetrics.getOrDefault(name, false) || !disableDefaultMetrics
    //     !! instrument.isEnableByDefault();
  }


  private Attributes createAttributes(List<String> requiredLabelKeys,
      List<String> optionalLabelKeys,
      List<String> requiredLabelValues, List<String> optionalLabelValues, BitSet bitSet) {
    AttributesBuilder builder = Attributes.builder();
    // Required Labels
    for (int i = 0; i < requiredLabelKeys.size(); i++) {
      builder.put(requiredLabelKeys.get(i), requiredLabelValues.get(i));
    }
    // Optional labels
    for (int i = bitSet.nextSetBit(0); i >= 0 && i < optionalLabelKeys.size();
        i = bitSet.nextSetBit(i + 1)) {
      if (i == Integer.MAX_VALUE) {
        break; // or (i+1) would overflow
      }
      builder.put(optionalLabelKeys.get(i), optionalLabelValues.get(i));
    }
    return builder.build();
  }


  @Internal
  static final class MeasuresData {
    MetricInstrument instrument;
    BitSet optionalLabelsIndices;
    Object measure;

    MeasuresData(MetricInstrument instrument, BitSet optionalLabelsIndices, Object measure) {
      this.instrument = instrument;
      this.optionalLabelsIndices = optionalLabelsIndices;
      this.measure = measure;
    }

    public MetricInstrument getInstrument() {
      return instrument;
    }

    public BitSet getOptionalLabelsBitSet() {
      return optionalLabelsIndices;
    }

    public Object getMeasure() {
      return measure;
    }
  }

}
