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
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.annotation.concurrent.GuardedBy;

/**
 * A registry for globally registered metric instruments.
 */
@Internal
public final class MetricInstrumentRegistry {
  public static final int INITIAL_INSTRUMENT_CAPACITY = 5;
  private static MetricInstrumentRegistry instance;
  private final Object lock = new Object();
  private final Set<String> registeredMetricNames;
  private volatile MetricInstrument[] metricInstruments;
  private volatile int instrumentListCapacity = INITIAL_INSTRUMENT_CAPACITY;
  @GuardedBy("lock")
  private int nextAvailableMetricIndex;

  @VisibleForTesting
  MetricInstrumentRegistry() {
    this.metricInstruments = new MetricInstrument[INITIAL_INSTRUMENT_CAPACITY];
    this.registeredMetricNames = new HashSet<>();
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
    synchronized (lock) {
      return Collections.unmodifiableList(
          Arrays.asList(Arrays.copyOfRange(metricInstruments, 0, nextAvailableMetricIndex)));
    }
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
      int index = nextAvailableMetricIndex;
      if (index + 1 == instrumentListCapacity) {
        resizeMetricInstruments();
      }
      DoubleCounterMetricInstrument instrument = new DoubleCounterMetricInstrument(
          index, name, description, unit, requiredLabelKeys, optionalLabelKeys,
          enableByDefault);
      metricInstruments[index] = instrument;
      registeredMetricNames.add(name);
      nextAvailableMetricIndex += 1;
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
      int index = nextAvailableMetricIndex;
      if (index + 1 == instrumentListCapacity) {
        resizeMetricInstruments();
      }
      LongCounterMetricInstrument instrument = new LongCounterMetricInstrument(
          index, name, description, unit, requiredLabelKeys, optionalLabelKeys,
          enableByDefault);
      metricInstruments[index] = instrument;
      registeredMetricNames.add(name);
      nextAvailableMetricIndex += 1;
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
      int index = nextAvailableMetricIndex;
      if (index + 1 == instrumentListCapacity) {
        resizeMetricInstruments();
      }
      DoubleHistogramMetricInstrument instrument = new DoubleHistogramMetricInstrument(
          index, name, description, unit, bucketBoundaries, requiredLabelKeys,
          optionalLabelKeys,
          enableByDefault);
      metricInstruments[index] = instrument;
      registeredMetricNames.add(name);
      nextAvailableMetricIndex += 1;
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
      int index = nextAvailableMetricIndex;
      if (index + 1 == instrumentListCapacity) {
        resizeMetricInstruments();
      }
      LongHistogramMetricInstrument instrument = new LongHistogramMetricInstrument(
          index, name, description, unit, bucketBoundaries, requiredLabelKeys,
          optionalLabelKeys,
          enableByDefault);
      metricInstruments[index] = instrument;
      registeredMetricNames.add(name);
      nextAvailableMetricIndex += 1;
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
    synchronized (lock) {
      if (registeredMetricNames.contains(name)) {
        throw new IllegalStateException("Metric with name " + name + " already exists");
      }
      int index = nextAvailableMetricIndex;
      if (index + 1 == instrumentListCapacity) {
        resizeMetricInstruments();
      }
      LongGaugeMetricInstrument instrument = new LongGaugeMetricInstrument(
          index, name, description, unit, requiredLabelKeys, optionalLabelKeys,
          enableByDefault);
      metricInstruments[index] = instrument;
      registeredMetricNames.add(name);
      nextAvailableMetricIndex += 1;
      return instrument;
    }
  }

  private synchronized void resizeMetricInstruments() {
    // Increase the capacity of the metricInstruments array by INITIAL_INSTRUMENT_CAPACITY
    instrumentListCapacity += INITIAL_INSTRUMENT_CAPACITY;
    MetricInstrument[] resizedMetricInstruments = Arrays.copyOf(metricInstruments,
        instrumentListCapacity);
    metricInstruments = resizedMetricInstruments;
  }
}
