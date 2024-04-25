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

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.verify;

import com.google.common.collect.Lists;
import io.grpc.LongCounterMetricInstrument;
import io.grpc.MetricInstrumentRegistry;
import io.grpc.MetricRecorder;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@RunWith(JUnit4.class)
public class MetricLongCounterTest {

  @Rule
  public MockitoRule mockitoRule = MockitoJUnit.rule();

  @Mock
  MetricRecorder mockMetricRecorder;

  @Test
  public void incrementAndAdd() {
    LongCounterMetricInstrument instrument = MetricInstrumentRegistry.getDefaultRegistry()
        .registerLongCounter("long",
            "description", "unit", Lists.newArrayList(), Lists.newArrayList(), true);
    List<String> requiredLabelValues = Lists.newArrayList();
    List<String> optionalLabelValues = Lists.newArrayList();
    MetricLongCounter counter = new MetricLongCounter(mockMetricRecorder, instrument);

    counter.increment(requiredLabelValues, optionalLabelValues);
    verify(mockMetricRecorder).recordLongCounter(instrument, 1, requiredLabelValues,
        optionalLabelValues);

    counter.increment(requiredLabelValues, optionalLabelValues);
    verify(mockMetricRecorder).recordLongCounter(instrument, 2, requiredLabelValues,
        optionalLabelValues);

    counter.add(2L, requiredLabelValues, optionalLabelValues);
    verify(mockMetricRecorder).recordLongCounter(instrument, 4, requiredLabelValues,
        optionalLabelValues);

    counter.add(3L, requiredLabelValues, optionalLabelValues);
    verify(mockMetricRecorder).recordLongCounter(instrument, 7, requiredLabelValues,
        optionalLabelValues);

    counter.add(5L, requiredLabelValues, optionalLabelValues);
    verify(mockMetricRecorder).recordLongCounter(instrument, 12, requiredLabelValues,
        optionalLabelValues);

    assertThat(counter.value()).isEqualTo(12);
  }
}