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

import io.grpc.LongCounterMetricInstrument;
import io.grpc.MetricRecorder;
import java.util.List;

/**
 * Maintains a counter while also recording new values with a {@link MetricRecorder}.
 */
public class MetricLongCounter {

  private final MetricRecorder recorder;
  private final LongCounterMetricInstrument instrument;
  private final LongCounter longCounter;

  /**
   * Creates a new {@code MetricLongCounter} for a given recorder and instrument.
   */
  public MetricLongCounter(MetricRecorder recorder, LongCounterMetricInstrument instrument) {
    this.recorder = recorder;
    this.instrument = instrument;
    this.longCounter = LongCounterFactory.create();
  }

  /**
   * Updates the counter with the given delta value and records the change with the
   * {@link MetricRecorder}.
   */
  public void add(long delta, List<String> requiredLabelValues, List<String> optionalLabelValues) {
    longCounter.add(delta);
    recorder.recordLongCounter(instrument, longCounter.value(), requiredLabelValues,
        optionalLabelValues);
  }

  /**
   * Increments the counter by one and records the change with the {@link MetricRecorder}.
   */
  public void increment(List<String> requiredLabelValues, List<String> optionalLabelValues) {
    add(1, requiredLabelValues, optionalLabelValues);
  }

  /**
   * Returns the current value of the counter.
   */
  public long value() {
    return longCounter.value();
  }
}
