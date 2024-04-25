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
import static org.hamcrest.CoreMatchers.hasItems;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import io.grpc.DoubleCounterMetricInstrument;
import io.grpc.DoubleHistogramMetricInstrument;
import io.grpc.LongCounterMetricInstrument;
import io.grpc.LongHistogramMetricInstrument;
import io.grpc.MetricInstrument;
import io.grpc.MetricInstrumentRegistry;
import io.grpc.MetricInstrumentRegistryAccessor;
import io.grpc.MetricRecorder;
import io.grpc.MetricSink;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;

/**
 * Unit test for {@link MetricRecorderImpl}.
 */
@RunWith(JUnit4.class)
public class MetricRecorderImplTest {

  private static final String DESCRIPTION = "description";
  private static final String UNIT = "unit";
  private static final boolean ENABLED = true;
  private static final ImmutableList<String> REQUIRED_LABEL_KEYS = ImmutableList.of("KEY1", "KEY2");
  private static final ImmutableList<String> OPTIONAL_LABEL_KEYS = ImmutableList.of(
      "OPTIONAL_KEY_1");
  private static final ImmutableList<String> REQUIRED_LABEL_VALUES = ImmutableList.of("VALUE1",
      "VALUE2");
  private static final ImmutableList<String> OPTIONAL_LABEL_VALUES = ImmutableList.of(
      "OPTIONAL_VALUE_1");
  private static final DoubleCounterMetricInstrument DOUBLE_COUNTER_INSTRUMENT =
      new DoubleCounterMetricInstrument(0, "counter0", DESCRIPTION, UNIT, REQUIRED_LABEL_KEYS,
          OPTIONAL_LABEL_KEYS, ENABLED);
  private static final LongCounterMetricInstrument LONG_COUNTER_INSTRUMENT =
      new LongCounterMetricInstrument(1, "counter1", DESCRIPTION, UNIT, REQUIRED_LABEL_KEYS,
          OPTIONAL_LABEL_KEYS, ENABLED);
  private static final DoubleHistogramMetricInstrument DOUBLE_HISTOGRAM_INSTRUMENT =
      new DoubleHistogramMetricInstrument(2, "histogram1", DESCRIPTION, UNIT,
          Collections.emptyList(), REQUIRED_LABEL_KEYS, OPTIONAL_LABEL_KEYS, ENABLED);
  private static final LongHistogramMetricInstrument LONG_HISTOGRAM_INSTRUMENT =
      new LongHistogramMetricInstrument(3, "histogram2", DESCRIPTION, UNIT,
          Collections.emptyList(), REQUIRED_LABEL_KEYS, OPTIONAL_LABEL_KEYS, ENABLED);
  private final List<MetricInstrument> instruments = Arrays.asList(DOUBLE_COUNTER_INSTRUMENT,
      LONG_COUNTER_INSTRUMENT, DOUBLE_HISTOGRAM_INSTRUMENT, LONG_HISTOGRAM_INSTRUMENT);
  private final Set<String> metricNames = Stream.of("counter1", "counter2", "histogram1",
          "histogram2")
      .collect(Collectors.toCollection(HashSet::new));
  private MetricSink mockSink = mock(MetricSink.class);
  private List<MetricSink> sinks = Arrays.asList(mockSink, mockSink);
  private MetricInstrumentRegistry registry;
  private MetricRecorder recorder;
  private ArgumentCaptor<DoubleCounterMetricInstrument> doubleCounterInstrumentCaptor =
      ArgumentCaptor.forClass(DoubleCounterMetricInstrument.class);
  private ArgumentCaptor<LongCounterMetricInstrument> longCounterInstrumentCaptor =
      ArgumentCaptor.forClass(LongCounterMetricInstrument.class);
  private ArgumentCaptor<DoubleHistogramMetricInstrument> doubleHistogramInstrumentCaptor =
      ArgumentCaptor.forClass(DoubleHistogramMetricInstrument.class);
  private ArgumentCaptor<LongHistogramMetricInstrument> longHistogramInstrumentCaptor =
      ArgumentCaptor.forClass(LongHistogramMetricInstrument.class);


  @Before
  public void setUp() {
    registry = MetricInstrumentRegistryAccessor.createMetricInstrumentRegistry(instruments,
        metricNames);
    recorder = new MetricRecorderImpl(sinks, registry);
  }

  @Test
  public void recordCounter() {
    when(mockSink.getMetricsMeasures()).thenReturn(
        Arrays.asList(new Object(), new Object(), new Object(), new Object()));

    recorder.recordDoubleCounter(DOUBLE_COUNTER_INSTRUMENT, 1.0, REQUIRED_LABEL_VALUES,
        OPTIONAL_LABEL_VALUES);
    verify(mockSink, times(2)).recordDoubleCounter(doubleCounterInstrumentCaptor.capture(), eq(1D),
        argThat(l -> hasItems("VALUE1", "VALUE2").matches(l)),
        argThat(l -> hasItems("OPTIONAL_VALUE_1").matches(l)));
    assertThat(doubleCounterInstrumentCaptor.getValue()).isEqualTo(DOUBLE_COUNTER_INSTRUMENT);

    recorder.recordLongCounter(LONG_COUNTER_INSTRUMENT, 1, REQUIRED_LABEL_VALUES,
        OPTIONAL_LABEL_VALUES);
    verify(mockSink, times(2)).recordLongCounter(longCounterInstrumentCaptor.capture(), eq(1L),
        argThat(l -> hasItems("VALUE1", "VALUE2").matches(l)),
        argThat(l -> hasItems("OPTIONAL_VALUE_1").matches(l)));
    assertThat(longCounterInstrumentCaptor.getValue()).isEqualTo(LONG_COUNTER_INSTRUMENT);

    verify(mockSink, never()).updateMeasures(registry.getMetricInstruments());
  }

  @Test
  public void recordHistogram() {
    when(mockSink.getMetricsMeasures()).thenReturn(
        Arrays.asList(new Object(), new Object(), new Object(), new Object()));

    recorder.recordDoubleHistogram(DOUBLE_HISTOGRAM_INSTRUMENT, 99.0, REQUIRED_LABEL_VALUES,
        OPTIONAL_LABEL_VALUES);
    verify(mockSink, times(2)).recordDoubleHistogram(doubleHistogramInstrumentCaptor.capture(),
        eq(99D),
        argThat(l -> hasItems("VALUE1", "VALUE2").matches(l)),
        argThat(l -> hasItems("OPTIONAL_VALUE_1").matches(l)));
    assertThat(doubleHistogramInstrumentCaptor.getValue()).isEqualTo(DOUBLE_HISTOGRAM_INSTRUMENT);

    recorder.recordLongHistogram(LONG_HISTOGRAM_INSTRUMENT, 99, REQUIRED_LABEL_VALUES,
        OPTIONAL_LABEL_VALUES);
    verify(mockSink, times(2)).recordLongHistogram(longHistogramInstrumentCaptor.capture(), eq(99L),
        argThat(l -> hasItems("VALUE1", "VALUE2").matches(l)),
        argThat(l -> hasItems("OPTIONAL_VALUE_1").matches(l)));
    assertThat(longHistogramInstrumentCaptor.getValue()).isEqualTo(LONG_HISTOGRAM_INSTRUMENT);

    verify(mockSink, never()).updateMeasures(registry.getMetricInstruments());
  }

  @Test
  public void newRegisteredMetricUpdateMeasures() {
    // Sink is initialized with zero measures, should trigger updateMeasures() on sinks
    when(mockSink.getMetricsMeasures()).thenReturn(new ArrayList<>());

    // Double Counter
    recorder.recordDoubleCounter(DOUBLE_COUNTER_INSTRUMENT, 1.0, REQUIRED_LABEL_VALUES,
        OPTIONAL_LABEL_VALUES);
    verify(mockSink, times(2)).updateMeasures(anyList());
    verify(mockSink, times(2)).recordDoubleCounter(doubleCounterInstrumentCaptor.capture(), eq(1D),
        argThat(l -> hasItems("VALUE1", "VALUE2").matches(l)),
        argThat(l -> hasItems("OPTIONAL_VALUE_1").matches(l)));
    assertThat(doubleCounterInstrumentCaptor.getValue()).isEqualTo(DOUBLE_COUNTER_INSTRUMENT);

    // Long Counter
    recorder.recordLongCounter(LONG_COUNTER_INSTRUMENT, 1, REQUIRED_LABEL_VALUES,
        OPTIONAL_LABEL_VALUES);
    verify(mockSink, times(4)).updateMeasures(anyList());
    verify(mockSink, times(2)).recordLongCounter(longCounterInstrumentCaptor.capture(), eq(1L),
        argThat(l -> hasItems("VALUE1", "VALUE2").matches(l)),
        argThat(l -> hasItems("OPTIONAL_VALUE_1").matches(l)));
    assertThat(longCounterInstrumentCaptor.getValue()).isEqualTo(LONG_COUNTER_INSTRUMENT);

    // Double Histogram
    recorder.recordDoubleHistogram(DOUBLE_HISTOGRAM_INSTRUMENT, 99.0, REQUIRED_LABEL_VALUES,
        OPTIONAL_LABEL_VALUES);
    verify(mockSink, times(6)).updateMeasures(anyList());
    verify(mockSink, times(2)).recordDoubleHistogram(doubleHistogramInstrumentCaptor.capture(),
        eq(99D),
        argThat(l -> hasItems("VALUE1", "VALUE2").matches(l)),
        argThat(l -> hasItems("OPTIONAL_VALUE_1").matches(l)));
    assertThat(doubleHistogramInstrumentCaptor.getValue()).isEqualTo(DOUBLE_HISTOGRAM_INSTRUMENT);

    // Long Histogram
    recorder.recordLongHistogram(LONG_HISTOGRAM_INSTRUMENT, 99, REQUIRED_LABEL_VALUES,
        OPTIONAL_LABEL_VALUES);
    verify(mockSink, times(8)).updateMeasures(registry.getMetricInstruments());
    verify(mockSink, times(2)).recordLongHistogram(longHistogramInstrumentCaptor.capture(), eq(99L),
        argThat(l -> hasItems("VALUE1", "VALUE2").matches(l)),
        argThat(l -> hasItems("OPTIONAL_VALUE_1").matches(l)));
    assertThat(longHistogramInstrumentCaptor.getValue()).isEqualTo(LONG_HISTOGRAM_INSTRUMENT);
  }
}
