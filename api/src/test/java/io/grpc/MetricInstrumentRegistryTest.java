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

import static com.google.common.truth.Truth.assertThat;
import static io.grpc.MetricInstrumentRegistry.INITIAL_INSTRUMENT_CAPACITY;

import com.google.common.collect.ImmutableList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Unit test for {@link MetricInstrumentRegistry}.
 */
@RunWith(JUnit4.class)
public class MetricInstrumentRegistryTest {
  private static final ImmutableList<String> REQUIRED_LABEL_KEYS = ImmutableList.of("KEY1", "KEY2");
  private static final ImmutableList<String> OPTIONAL_LABEL_KEYS = ImmutableList.of(
      "OPTIONAL_KEY_1");
  private static final ImmutableList<Double> DOUBLE_HISTOGRAM_BUCKETS = ImmutableList.of(0.01, 0.1);
  private static final ImmutableList<Long> LONG_HISTOGRAM_BUCKETS = ImmutableList.of(1L, 10L);
  private static final String METRIC_NAME_1 = "testMetric1";
  private static final String DESCRIPTION_1 = "description1";
  private static final String DESCRIPTION_2 = "description2";
  private static final String UNIT_1 = "unit1";
  private static final String UNIT_2 = "unit2";
  private static final boolean ENABLED = true;
  private static final boolean DISABLED = false;
  private MetricInstrumentRegistry registry = new MetricInstrumentRegistry();

  @Test
  public void registerDoubleCounterSuccess() {
    DoubleCounterMetricInstrument instrument = registry.registerDoubleCounter(
        METRIC_NAME_1, DESCRIPTION_1, UNIT_1, REQUIRED_LABEL_KEYS, OPTIONAL_LABEL_KEYS, ENABLED);
    assertThat(registry.getMetricInstruments().contains(instrument)).isTrue();
    assertThat(registry.getMetricInstruments().size()).isEqualTo(1);
    assertThat(instrument.getName()).isEqualTo(METRIC_NAME_1);
    assertThat(instrument.getDescription()).isEqualTo(DESCRIPTION_1);
    assertThat(instrument.getUnit()).isEqualTo(UNIT_1);
    assertThat(instrument.getRequiredLabelKeys()).isEqualTo(REQUIRED_LABEL_KEYS);
    assertThat(instrument.getOptionalLabelKeys()).isEqualTo(OPTIONAL_LABEL_KEYS);
    assertThat(instrument.isEnableByDefault()).isTrue();
  }

  @Test
  public void registerLongCounterSuccess() {
    LongCounterMetricInstrument instrument2 = registry.registerLongCounter(
        METRIC_NAME_1, DESCRIPTION_1, UNIT_1, REQUIRED_LABEL_KEYS, OPTIONAL_LABEL_KEYS, ENABLED);
    assertThat(registry.getMetricInstruments().contains(instrument2)).isTrue();
    assertThat(registry.getMetricInstruments().size()).isEqualTo(1);
    assertThat(instrument2.getName()).isEqualTo(METRIC_NAME_1);
    assertThat(instrument2.getDescription()).isEqualTo(DESCRIPTION_1);
    assertThat(instrument2.getUnit()).isEqualTo(UNIT_1);
    assertThat(instrument2.getRequiredLabelKeys()).isEqualTo(REQUIRED_LABEL_KEYS);
    assertThat(instrument2.getOptionalLabelKeys()).isEqualTo(OPTIONAL_LABEL_KEYS);
    assertThat(instrument2.isEnableByDefault()).isTrue();
  }

  @Test
  public void registerDoubleHistogramSuccess() {
    DoubleHistogramMetricInstrument instrument3 = registry.registerDoubleHistogram(
        METRIC_NAME_1, DESCRIPTION_1, UNIT_1, DOUBLE_HISTOGRAM_BUCKETS, REQUIRED_LABEL_KEYS,
        OPTIONAL_LABEL_KEYS, ENABLED);
    assertThat(registry.getMetricInstruments().contains(instrument3)).isTrue();
    assertThat(registry.getMetricInstruments().size()).isEqualTo(1);
    assertThat(instrument3.getName()).isEqualTo(METRIC_NAME_1);
    assertThat(instrument3.getDescription()).isEqualTo(DESCRIPTION_1);
    assertThat(instrument3.getUnit()).isEqualTo(UNIT_1);
    assertThat(instrument3.getBucketBoundaries()).isEqualTo(DOUBLE_HISTOGRAM_BUCKETS);
    assertThat(instrument3.getRequiredLabelKeys()).isEqualTo(REQUIRED_LABEL_KEYS);
    assertThat(instrument3.getOptionalLabelKeys()).isEqualTo(OPTIONAL_LABEL_KEYS);
    assertThat(instrument3.isEnableByDefault()).isTrue();
  }

  @Test
  public void registerLongHistogramSuccess() {
    LongHistogramMetricInstrument instrument4 = registry.registerLongHistogram(
        METRIC_NAME_1, DESCRIPTION_1, UNIT_1, LONG_HISTOGRAM_BUCKETS, REQUIRED_LABEL_KEYS,
        OPTIONAL_LABEL_KEYS, ENABLED);
    assertThat(registry.getMetricInstruments().contains(instrument4)).isTrue();
    assertThat(registry.getMetricInstruments().size()).isEqualTo(1);
    assertThat(instrument4.getName()).isEqualTo(METRIC_NAME_1);
    assertThat(instrument4.getDescription()).isEqualTo(DESCRIPTION_1);
    assertThat(instrument4.getUnit()).isEqualTo(UNIT_1);
    assertThat(instrument4.getBucketBoundaries()).isEqualTo(LONG_HISTOGRAM_BUCKETS);
    assertThat(instrument4.getRequiredLabelKeys()).isEqualTo(REQUIRED_LABEL_KEYS);
    assertThat(instrument4.getOptionalLabelKeys()).isEqualTo(OPTIONAL_LABEL_KEYS);
    assertThat(instrument4.isEnableByDefault()).isTrue();
  }

  @Test
  public void registerLongGaugeSuccess() {
    LongGaugeMetricInstrument instrument4 = registry.registerLongGauge(
        METRIC_NAME_1, DESCRIPTION_1, UNIT_1, REQUIRED_LABEL_KEYS,
        OPTIONAL_LABEL_KEYS, ENABLED);
    assertThat(registry.getMetricInstruments().contains(instrument4)).isTrue();
    assertThat(registry.getMetricInstruments().size()).isEqualTo(1);
    assertThat(instrument4.getName()).isEqualTo(METRIC_NAME_1);
    assertThat(instrument4.getDescription()).isEqualTo(DESCRIPTION_1);
    assertThat(instrument4.getUnit()).isEqualTo(UNIT_1);
    assertThat(instrument4.getRequiredLabelKeys()).isEqualTo(REQUIRED_LABEL_KEYS);
    assertThat(instrument4.getOptionalLabelKeys()).isEqualTo(OPTIONAL_LABEL_KEYS);
    assertThat(instrument4.isEnableByDefault()).isTrue();
  }

  @Test(expected = IllegalStateException.class)
  public void registerDoubleCounterDuplicateName() {
    registry.registerDoubleCounter(METRIC_NAME_1, DESCRIPTION_1, UNIT_1, REQUIRED_LABEL_KEYS,
        OPTIONAL_LABEL_KEYS, ENABLED);
    registry.registerDoubleCounter(METRIC_NAME_1, DESCRIPTION_2, UNIT_2, REQUIRED_LABEL_KEYS,
        OPTIONAL_LABEL_KEYS, DISABLED);
  }

  @Test(expected = IllegalStateException.class)
  public void registerLongCounterDuplicateName() {
    registry.registerDoubleCounter(METRIC_NAME_1, DESCRIPTION_1, UNIT_1, REQUIRED_LABEL_KEYS,
        OPTIONAL_LABEL_KEYS, ENABLED);
    registry.registerLongCounter(METRIC_NAME_1, DESCRIPTION_2, UNIT_2, REQUIRED_LABEL_KEYS,
        OPTIONAL_LABEL_KEYS, DISABLED);
  }

  @Test(expected = IllegalStateException.class)
  public void registerDoubleHistogramDuplicateName() {
    registry.registerLongHistogram(METRIC_NAME_1, DESCRIPTION_1, UNIT_1, LONG_HISTOGRAM_BUCKETS,
        REQUIRED_LABEL_KEYS, OPTIONAL_LABEL_KEYS, ENABLED);
    registry.registerDoubleHistogram(METRIC_NAME_1, DESCRIPTION_2, UNIT_2, DOUBLE_HISTOGRAM_BUCKETS,
        REQUIRED_LABEL_KEYS, OPTIONAL_LABEL_KEYS, DISABLED);
  }

  @Test(expected = IllegalStateException.class)
  public void registerLongHistogramDuplicateName() {
    registry.registerLongCounter(METRIC_NAME_1, DESCRIPTION_1, UNIT_1, REQUIRED_LABEL_KEYS,
        OPTIONAL_LABEL_KEYS, ENABLED);
    registry.registerLongHistogram(METRIC_NAME_1, DESCRIPTION_2, UNIT_2, LONG_HISTOGRAM_BUCKETS,
        REQUIRED_LABEL_KEYS, OPTIONAL_LABEL_KEYS, DISABLED);
  }

  @Test(expected = IllegalStateException.class)
  public void registerLongGaugeDuplicateName() {
    registry.registerDoubleHistogram(METRIC_NAME_1, DESCRIPTION_1, UNIT_1, DOUBLE_HISTOGRAM_BUCKETS,
        REQUIRED_LABEL_KEYS, OPTIONAL_LABEL_KEYS, ENABLED);
    registry.registerLongGauge(METRIC_NAME_1, DESCRIPTION_2, UNIT_2, REQUIRED_LABEL_KEYS,
        OPTIONAL_LABEL_KEYS, DISABLED);
  }

  @Test
  public void getMetricInstrumentsMultipleRegistered() {
    DoubleCounterMetricInstrument instrument1 = registry.registerDoubleCounter(
        "testMetric1", DESCRIPTION_1, UNIT_1, REQUIRED_LABEL_KEYS, OPTIONAL_LABEL_KEYS, ENABLED);
    LongCounterMetricInstrument instrument2 = registry.registerLongCounter(
        "testMetric2", DESCRIPTION_2, UNIT_2, REQUIRED_LABEL_KEYS, OPTIONAL_LABEL_KEYS, DISABLED);
    DoubleHistogramMetricInstrument instrument3 = registry.registerDoubleHistogram(
        "testMetric3", DESCRIPTION_2, UNIT_2, DOUBLE_HISTOGRAM_BUCKETS, REQUIRED_LABEL_KEYS,
        OPTIONAL_LABEL_KEYS, DISABLED);

    List<MetricInstrument> instruments = registry.getMetricInstruments();
    assertThat(instruments.size()).isEqualTo(3);
    assertThat(instruments.contains(instrument1)).isTrue();
    assertThat(instruments.contains(instrument2)).isTrue();
    assertThat(instruments.contains(instrument3)).isTrue();
  }

  @Test
  public void resizeMetricInstrumentsCapacityIncrease() {
    int initialCapacity = INITIAL_INSTRUMENT_CAPACITY;
    MetricInstrumentRegistry testRegistry = new MetricInstrumentRegistry();

    // Registering enough instruments to trigger resize
    for (int i = 0; i < initialCapacity + 1; i++) {
      testRegistry.registerLongHistogram("name" + i, "desc", "unit", ImmutableList.of(),
          ImmutableList.of(), ImmutableList.of(), true);
    }

    assertThat(testRegistry.getMetricInstruments().size()).isGreaterThan(initialCapacity);
  }

}
