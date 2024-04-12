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

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * A registry for globally registered metric instruments.
 */
@Internal
public final class MetricInstrumentRegistry {
  private static MetricInstrumentRegistry instance;
  private final List<MetricInstrument> metricInstruments;
  private final Set<String> registeredMetricNames;

  private MetricInstrumentRegistry() {
    this.metricInstruments = new CopyOnWriteArrayList<>();
    this.registeredMetricNames = new CopyOnWriteArraySet<>();
  }

  /**
   * Returns the default metric instrument registry.
   */
  public static synchronized MetricInstrumentRegistry getDefaultRegistry() {
    if (instance == null) {
      instance = new MetricInstrumentRegistry();
    }
    return instance;
  }

  /**
   * Returns a list of registered metric instruments.
   */
  public List<MetricInstrument> getMetricInstruments() {
    return Collections.unmodifiableList(metricInstruments);
  }

  /**
   * Registers a new Double Counter metric instrument.
   *
   * @param name the name of the metric
   * @param description a description of the metric
   * @param unit the unit of measurement for the metric
   * @param requiredLabelKeys a list of required label keys
   * @param optionalLabelKeys a list of optional label keys
   * @param enableByDefault whether the metric should be enabled by default
   * @return the newly created DoubleCounterMetricInstrument
   * @throws IllegalStateException if a metric with the same name already exists
   */
  // TODO(dnvindhya): Evaluate locks over synchronized methods and update if needed
  public synchronized DoubleCounterMetricInstrument registerDoubleCounter(String name,
      String description, String unit, List<String> requiredLabelKeys,
      List<String> optionalLabelKeys, boolean enableByDefault) {
    if (registeredMetricNames.contains(name)) {
      throw new IllegalStateException("Metric with name " + name + " already exists");
    }
    long instrumentIndex = metricInstruments.size();
    DoubleCounterMetricInstrument instrument = new DoubleCounterMetricInstrument(
        instrumentIndex, name, description, unit, requiredLabelKeys, optionalLabelKeys,
        enableByDefault);
    metricInstruments.add(instrument);
    registeredMetricNames.add(name);
    return instrument;
  }

  /**
   * Registers a new Long Counter metric instrument.
   *
   * @param name the name of the metric
   * @param description a description of the metric
   * @param unit the unit of measurement for the metric
   * @param requiredLabelKeys a list of required label keys
   * @param optionalLabelKeys a list of optional label keys
   * @param enableByDefault whether the metric should be enabled by default
   * @return the newly created LongCounterMetricInstrument
   * @throws IllegalStateException if a metric with the same name already exists
   */
  public synchronized LongCounterMetricInstrument registerLongCounter(String name,
      String description, String unit, List<String> requiredLabelKeys,
      List<String> optionalLabelKeys, boolean enableByDefault) {
    if (registeredMetricNames.contains(name)) {
      throw new IllegalStateException("Metric with name " + name + " already exists");
    }
    // Acquire lock?
    long instrumentIndex = metricInstruments.size();
    LongCounterMetricInstrument instrument = new LongCounterMetricInstrument(
        instrumentIndex, name, description, unit, requiredLabelKeys, optionalLabelKeys,
        enableByDefault);
    metricInstruments.add(instrument);
    registeredMetricNames.add(name);
    return instrument;
  }

  /**
   * Registers a new Double Histogram metric instrument.
   *
   * @param name the name of the metric
   * @param description a description of the metric
   * @param unit the unit of measurement for the metric
   * @param bucketBoundaries recommended set of explicit bucket boundaries for the histogram
   * @param requiredLabelKeys a list of required label keys
   * @param optionalLabelKeys a list of optional label keys
   * @param enableByDefault whether the metric should be enabled by default
   * @return the newly created DoubleHistogramMetricInstrument
   * @throws IllegalStateException if a metric with the same name already exists
   */
  public synchronized DoubleHistogramMetricInstrument registerDoubleHistogram(String name,
      String description, String unit, List<Double> bucketBoundaries,
      List<String> requiredLabelKeys, List<String> optionalLabelKeys, boolean enableByDefault) {
    if (registeredMetricNames.contains(name)) {
      throw new IllegalStateException("Metric with name " + name + " already exists");
    }
    long indexToInsertInstrument = metricInstruments.size();
    DoubleHistogramMetricInstrument instrument = new DoubleHistogramMetricInstrument(
        indexToInsertInstrument, name, description, unit, bucketBoundaries, requiredLabelKeys,
        optionalLabelKeys,
        enableByDefault);
    metricInstruments.add(instrument);
    registeredMetricNames.add(name);
    return instrument;
  }

  /**
   * Registers a new Long Histogram metric instrument.
   *
   * @param name the name of the metric
   * @param description a description of the metric
   * @param unit the unit of measurement for the metric
   * @param bucketBoundaries recommended set of explicit bucket boundaries for the histogram
   * @param requiredLabelKeys a list of required label keys
   * @param optionalLabelKeys a list of optional label keys
   * @param enableByDefault whether the metric should be enabled by default
   * @return the newly created LongHistogramMetricInstrument
   * @throws IllegalStateException if a metric with the same name already exists
   */
  public synchronized LongHistogramMetricInstrument registerLongHistogram(String name,
      String description, String unit, List<Long> bucketBoundaries, List<String> requiredLabelKeys,
      List<String> optionalLabelKeys, boolean enableByDefault) {
    if (registeredMetricNames.contains(name)) {
      throw new IllegalStateException("Metric with name " + name + " already exists");
    }
    long indexToInsertInstrument = metricInstruments.size();
    LongHistogramMetricInstrument instrument = new LongHistogramMetricInstrument(
        indexToInsertInstrument, name, description, unit, bucketBoundaries, requiredLabelKeys,
        optionalLabelKeys,
        enableByDefault);
    metricInstruments.add(instrument);
    registeredMetricNames.add(name);
    return instrument;
  }


  /**
   * Registers a new Long Gauge metric instrument.
   *
   * @param name the name of the metric
   * @param description a description of the metric
   * @param unit the unit of measurement for the metric
   * @param requiredLabelKeys a list of required label keys
   * @param optionalLabelKeys a list of optional label keys
   * @param enableByDefault whether the metric should be enabled by default
   * @return the newly created LongGaugeMetricInstrument
   * @throws IllegalStateException if a metric with the same name already exists
   */
  public synchronized LongGaugeMetricInstrument registerLongGauge(String name, String description,
      String unit, List<String> requiredLabelKeys, List<String> optionalLabelKeys, boolean
      enableByDefault) {
    if (registeredMetricNames.contains(name)) {
      throw new IllegalStateException("Metric with name " + name + " already exists");
    }
    long indexToInsertInstrument = metricInstruments.size();
    LongGaugeMetricInstrument instrument = new LongGaugeMetricInstrument(
        indexToInsertInstrument, name, description, unit, requiredLabelKeys, optionalLabelKeys,
        enableByDefault);
    metricInstruments.add(instrument);
    registeredMetricNames.add(name);
    return instrument;
  }
}
