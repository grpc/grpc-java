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

import com.google.common.base.MoreObjects;
import com.google.common.collect.Maps;
import java.util.List;
import java.util.Map;

/**
 * A fake implementation of the {@link MetricRecorder} that collects all metrics in memory to allow
 * tests to make assertions against the collected metrics.
 */
public class FakeMetricRecorder implements MetricRecorder {
  public Map<String, MetricEntry<Double>> doubleCounterEntries = Maps.newHashMap();
  public Map<String, MetricEntry<Long>> longCounterEntries = Maps.newHashMap();
  public Map<String, MetricEntry<Double>> doubleHistogramCounterEntries = Maps.newHashMap();
  public Map<String, MetricEntry<Long>> longHistogramCounterEntries = Maps.newHashMap();


  @Override
  public void recordDoubleCounter(DoubleCounterMetricInstrument metricInstrument, double value,
      List<String> requiredLabelValues, List<String> optionalLabelValues) {
    doubleCounterEntries.put(metricInstrument.getName(),
        new MetricEntry<>(value, requiredLabelValues, optionalLabelValues));
  }

  @Override
  public void recordLongCounter(LongCounterMetricInstrument metricInstrument, long value,
      List<String> requiredLabelValues, List<String> optionalLabelValues) {
    longCounterEntries.put(metricInstrument.getName(),
        new MetricEntry<>(value, requiredLabelValues, optionalLabelValues));
  }

  @Override
  public void recordDoubleHistogram(DoubleHistogramMetricInstrument metricInstrument,
      double value, List<String> requiredLabelValues, List<String> optionalLabelValues) {
    doubleHistogramCounterEntries.put(metricInstrument.getName(),
        new MetricEntry<>(value, requiredLabelValues, optionalLabelValues));
  }

  @Override
  public void recordLongHistogram(LongHistogramMetricInstrument metricInstrument, long value,
      List<String> requiredLabelValues, List<String> optionalLabelValues) {
    longHistogramCounterEntries.put(metricInstrument.getName(),
        new MetricEntry<>(value, requiredLabelValues, optionalLabelValues));
  }

  public Long getLongCounterValue(String metricName) {
    return longCounterEntries.get(metricName).value;
  }

  // Returns the last recorded double histogram value.
  public Double getDoubleHistogramValue(String metricName) {
    return doubleHistogramCounterEntries.get(metricName).value;
  }

  public boolean hasLongCounterValue(String metricName) {
    return longCounterEntries.containsKey(metricName);
  }

  public void clear() {
    doubleCounterEntries.clear();
    longCounterEntries.clear();
    doubleHistogramCounterEntries.clear();
    longHistogramCounterEntries.clear();
  }

  public static class MetricEntry<T> {
    public T value;
    public List<String> requiredLabelValues;
    public List<String> optionalLabelValues;

    public MetricEntry(T value, List<String> requiredValues, List<String> optionalValues) {
      this.value = value;
      this.requiredLabelValues = requiredValues;
      this.optionalLabelValues = optionalValues;
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this).add("value", value)
          .add("requiredLabelValues", requiredLabelValues)
          .add("optionalLabelValues", optionalLabelValues).toString();
    }
  }
}
