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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A registry for globally registered metric instruments.
 */
@Internal
public final class MetricInstrumentRegistry {
  private static final int DEFAULT_INSTRUMENT_LIST_CAPACITY = 20;
  private static MetricInstrumentRegistry instance;
  private final Object lock = new Object();
  private final Set<String> registeredMetricNames;
  private final AtomicInteger instrumentIndexAlloc = new AtomicInteger();
  private volatile List<MetricInstrument> metricInstruments;
  private volatile int instrumentListCapacity = DEFAULT_INSTRUMENT_LIST_CAPACITY;

  private MetricInstrumentRegistry() {
    this(new ArrayList<>(DEFAULT_INSTRUMENT_LIST_CAPACITY), new HashSet<>());
  }

  @VisibleForTesting
  MetricInstrumentRegistry(List<MetricInstrument> metricInstruments,
      Set<String> registeredMetricNames) {
    this.metricInstruments = metricInstruments;
    this.registeredMetricNames = registeredMetricNames;
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
    return ImmutableList.copyOf(metricInstruments);
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
  public DoubleCounterMetricInstrument registerDoubleCounter(String name,
      String description, String unit, List<String> requiredLabelKeys,
      List<String> optionalLabelKeys, boolean enableByDefault) {
    // Null check for arguments
    synchronized (lock) {
      if (registeredMetricNames.contains(name)) {
        throw new IllegalStateException("Metric with name " + name + " already exists");
      }
      int index = instrumentIndexAlloc.getAndIncrement();
      if (index + 1 == instrumentListCapacity) {
        resizeMetricInstruments();
      }
      DoubleCounterMetricInstrument instrument = new DoubleCounterMetricInstrument(
          index, name, description, unit, requiredLabelKeys, optionalLabelKeys,
          enableByDefault);
      metricInstruments.add(index, instrument);
      registeredMetricNames.add(name);
      return instrument;
    }
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
  public LongCounterMetricInstrument registerLongCounter(String name,
      String description, String unit, List<String> requiredLabelKeys,
      List<String> optionalLabelKeys, boolean enableByDefault) {
    synchronized (lock) {
      if (registeredMetricNames.contains(name)) {
        throw new IllegalStateException("Metric with name " + name + " already exists");
      }
      int index = instrumentIndexAlloc.getAndIncrement();
      if (index + 1 == instrumentListCapacity) {
        resizeMetricInstruments();
      }
      LongCounterMetricInstrument instrument = new LongCounterMetricInstrument(
          index, name, description, unit, requiredLabelKeys, optionalLabelKeys,
          enableByDefault);
      metricInstruments.add(instrument);
      registeredMetricNames.add(name);
      return instrument;
    }
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
  public DoubleHistogramMetricInstrument registerDoubleHistogram(String name,
      String description, String unit, List<Double> bucketBoundaries,
      List<String> requiredLabelKeys, List<String> optionalLabelKeys, boolean enableByDefault) {
    synchronized (lock) {
      if (registeredMetricNames.contains(name)) {
        throw new IllegalStateException("Metric with name " + name + " already exists");
      }
      int index = instrumentIndexAlloc.getAndIncrement();
      if (index + 1 == instrumentListCapacity) {
        resizeMetricInstruments();
      }
      DoubleHistogramMetricInstrument instrument = new DoubleHistogramMetricInstrument(
          index, name, description, unit, bucketBoundaries, requiredLabelKeys,
          optionalLabelKeys,
          enableByDefault);
      metricInstruments.add(instrument);
      registeredMetricNames.add(name);
      return instrument;
    }
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
  public LongHistogramMetricInstrument registerLongHistogram(String name,
      String description, String unit, List<Long> bucketBoundaries, List<String> requiredLabelKeys,
      List<String> optionalLabelKeys, boolean enableByDefault) {
    synchronized (lock) {
      if (registeredMetricNames.contains(name)) {
        throw new IllegalStateException("Metric with name " + name + " already exists");
      }
      int index = instrumentIndexAlloc.getAndIncrement();
      if (index + 1 == instrumentListCapacity) {
        resizeMetricInstruments();
      }
      LongHistogramMetricInstrument instrument = new LongHistogramMetricInstrument(
          index, name, description, unit, bucketBoundaries, requiredLabelKeys,
          optionalLabelKeys,
          enableByDefault);
      metricInstruments.add(instrument);
      registeredMetricNames.add(name);
      return instrument;
    }
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
  public LongGaugeMetricInstrument registerLongGauge(String name, String description,
      String unit, List<String> requiredLabelKeys, List<String> optionalLabelKeys, boolean
      enableByDefault) {
    if (registeredMetricNames.contains(name)) {
      throw new IllegalStateException("Metric with name " + name + " already exists");
    }
    int index = instrumentIndexAlloc.getAndIncrement();
    if (index + 1 == metricInstruments.size()) {
      resizeMetricInstruments();
    }
    LongGaugeMetricInstrument instrument = new LongGaugeMetricInstrument(
        index, name, description, unit, requiredLabelKeys, optionalLabelKeys,
        enableByDefault);
    metricInstruments.add(instrument);
    registeredMetricNames.add(name);
    return instrument;
  }

  private synchronized void resizeMetricInstruments() {
    // Resize by factor of DEFAULT_INSTRUMENT_LIST_CAPACITY
    instrumentListCapacity = metricInstruments.size() + DEFAULT_INSTRUMENT_LIST_CAPACITY + 1;
    List<MetricInstrument> newList = new ArrayList<>(instrumentListCapacity);
    newList.addAll(metricInstruments);
    metricInstruments = newList;
  }
}
