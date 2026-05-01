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
import java.util.Map;
import java.util.Set;

/**
 * A MetricSink that discards all records.
 */
public class NoopMetricSink implements MetricSink {
  private int size;

  @Override
  public Map<String, Boolean> getEnabledMetrics() {
    return Collections.emptyMap();
  }

  @Override
  public Set<String> getOptionalLabels() {
    return Collections.emptySet();
  }

  @Override
  public synchronized int getMeasuresSize() {
    return size;
  }

  @Override
  public synchronized void updateMeasures(List<MetricInstrument> instruments) {
    size = instruments.size();
  }
}
