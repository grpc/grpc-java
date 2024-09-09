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

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
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
import io.grpc.LongGaugeMetricInstrument;
import io.grpc.LongHistogramMetricInstrument;
import io.grpc.MetricInstrumentRegistry;
import io.grpc.MetricInstrumentRegistryAccessor;
import io.grpc.MetricRecorder;
import io.grpc.MetricSink;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
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
  private MetricSink mockSink = mock(MetricSink.class);
  private List<MetricSink> sinks = Arrays.asList(mockSink, mockSink);
  private MetricInstrumentRegistry registry =
      MetricInstrumentRegistryAccessor.createMetricInstrumentRegistry();
  private final DoubleCounterMetricInstrument doubleCounterInstrument =
      registry.registerDoubleCounter("counter0", DESCRIPTION, UNIT, REQUIRED_LABEL_KEYS,
          OPTIONAL_LABEL_KEYS, ENABLED);
  private final LongCounterMetricInstrument longCounterInstrument =
      registry.registerLongCounter("counter1", DESCRIPTION, UNIT, REQUIRED_LABEL_KEYS,
          OPTIONAL_LABEL_KEYS, ENABLED);
  private final DoubleHistogramMetricInstrument doubleHistogramInstrument =
      registry.registerDoubleHistogram("histogram1", DESCRIPTION, UNIT,
          Collections.emptyList(), REQUIRED_LABEL_KEYS, OPTIONAL_LABEL_KEYS, ENABLED);
  private final LongHistogramMetricInstrument longHistogramInstrument =
      registry.registerLongHistogram("histogram2", DESCRIPTION, UNIT,
          Collections.emptyList(), REQUIRED_LABEL_KEYS, OPTIONAL_LABEL_KEYS, ENABLED);
  private final LongGaugeMetricInstrument longGaugeInstrument =
      registry.registerLongGauge("gauge0", DESCRIPTION, UNIT, REQUIRED_LABEL_KEYS,
          OPTIONAL_LABEL_KEYS, ENABLED);
  private MetricRecorder recorder;

  @Before
  public void setUp() {
    recorder = new MetricRecorderImpl(sinks, registry);
  }

  @Test
  public void addCounter() {
    when(mockSink.getMeasuresSize()).thenReturn(4);

    recorder.addDoubleCounter(doubleCounterInstrument, 1.0, REQUIRED_LABEL_VALUES,
        OPTIONAL_LABEL_VALUES);
    verify(mockSink, times(2)).addDoubleCounter(eq(doubleCounterInstrument), eq(1D),
        eq(REQUIRED_LABEL_VALUES), eq(OPTIONAL_LABEL_VALUES));

    recorder.addLongCounter(longCounterInstrument, 1, REQUIRED_LABEL_VALUES,
        OPTIONAL_LABEL_VALUES);
    verify(mockSink, times(2)).addLongCounter(eq(longCounterInstrument), eq(1L),
        eq(REQUIRED_LABEL_VALUES), eq(OPTIONAL_LABEL_VALUES));

    verify(mockSink, never()).updateMeasures(registry.getMetricInstruments());
  }

  @Test
  public void recordHistogram() {
    when(mockSink.getMeasuresSize()).thenReturn(4);

    recorder.recordDoubleHistogram(doubleHistogramInstrument, 99.0, REQUIRED_LABEL_VALUES,
        OPTIONAL_LABEL_VALUES);
    verify(mockSink, times(2)).recordDoubleHistogram(eq(doubleHistogramInstrument),
        eq(99D), eq(REQUIRED_LABEL_VALUES), eq(OPTIONAL_LABEL_VALUES));

    recorder.recordLongHistogram(longHistogramInstrument, 99, REQUIRED_LABEL_VALUES,
        OPTIONAL_LABEL_VALUES);
    verify(mockSink, times(2)).recordLongHistogram(eq(longHistogramInstrument), eq(99L),
        eq(REQUIRED_LABEL_VALUES), eq(OPTIONAL_LABEL_VALUES));

    verify(mockSink, never()).updateMeasures(registry.getMetricInstruments());
  }

  @Test
  public void recordCallback() {
    MetricSink.Registration mockRegistration = mock(MetricSink.Registration.class);
    when(mockSink.getMeasuresSize()).thenReturn(5);
    when(mockSink.registerBatchCallback(any(Runnable.class), eq(longGaugeInstrument)))
        .thenReturn(mockRegistration);

    MetricRecorder.Registration registration = recorder.registerBatchCallback((recorder) -> {
      recorder.recordLongGauge(
          longGaugeInstrument, 99, REQUIRED_LABEL_VALUES, OPTIONAL_LABEL_VALUES);
    }, longGaugeInstrument);

    ArgumentCaptor<Runnable> callbackCaptor = ArgumentCaptor.forClass(Runnable.class);
    verify(mockSink, times(2))
        .registerBatchCallback(callbackCaptor.capture(), eq(longGaugeInstrument));

    callbackCaptor.getValue().run();
    // Only once, for the one sink that called the callback.
    verify(mockSink).recordLongGauge(
        longGaugeInstrument, 99, REQUIRED_LABEL_VALUES, OPTIONAL_LABEL_VALUES);

    verify(mockRegistration, never()).close();
    registration.close();
    verify(mockRegistration, times(2)).close();

    verify(mockSink, never()).updateMeasures(registry.getMetricInstruments());
  }

  @Test
  public void newRegisteredMetricUpdateMeasures() {
    // Sink is initialized with zero measures, should trigger updateMeasures() on sinks
    when(mockSink.getMeasuresSize()).thenReturn(0);

    // Double Counter
    recorder.addDoubleCounter(doubleCounterInstrument, 1.0, REQUIRED_LABEL_VALUES,
        OPTIONAL_LABEL_VALUES);
    verify(mockSink, times(2)).updateMeasures(anyList());
    verify(mockSink, times(2)).addDoubleCounter(eq(doubleCounterInstrument), eq(1D),
        eq(REQUIRED_LABEL_VALUES), eq(OPTIONAL_LABEL_VALUES));

    // Long Counter
    recorder.addLongCounter(longCounterInstrument, 1, REQUIRED_LABEL_VALUES,
        OPTIONAL_LABEL_VALUES);
    verify(mockSink, times(4)).updateMeasures(anyList());
    verify(mockSink, times(2)).addLongCounter(eq(longCounterInstrument), eq(1L),
        eq(REQUIRED_LABEL_VALUES), eq(OPTIONAL_LABEL_VALUES));

    // Double Histogram
    recorder.recordDoubleHistogram(doubleHistogramInstrument, 99.0, REQUIRED_LABEL_VALUES,
        OPTIONAL_LABEL_VALUES);
    verify(mockSink, times(6)).updateMeasures(anyList());
    verify(mockSink, times(2)).recordDoubleHistogram(eq(doubleHistogramInstrument),
        eq(99D), eq(REQUIRED_LABEL_VALUES), eq(OPTIONAL_LABEL_VALUES));

    // Long Histogram
    recorder.recordLongHistogram(longHistogramInstrument, 99, REQUIRED_LABEL_VALUES,
        OPTIONAL_LABEL_VALUES);
    verify(mockSink, times(8)).updateMeasures(registry.getMetricInstruments());
    verify(mockSink, times(2)).recordLongHistogram(eq(longHistogramInstrument), eq(99L),
        eq(REQUIRED_LABEL_VALUES), eq(OPTIONAL_LABEL_VALUES));

    // Callback
    when(mockSink.registerBatchCallback(any(Runnable.class), eq(longGaugeInstrument)))
        .thenReturn(mock(MetricSink.Registration.class));
    MetricRecorder.Registration registration = recorder.registerBatchCallback(
        (recorder) -> { }, longGaugeInstrument);
    verify(mockSink, times(10)).updateMeasures(registry.getMetricInstruments());
    verify(mockSink, times(2))
        .registerBatchCallback(any(Runnable.class), eq(longGaugeInstrument));
    registration.close();
  }

  @Test(expected = IllegalArgumentException.class)
  public void addDoubleCounterMismatchedRequiredLabelValues() {
    when(mockSink.getMeasuresSize()).thenReturn(4);

    recorder.addDoubleCounter(doubleCounterInstrument, 1.0, ImmutableList.of(),
        OPTIONAL_LABEL_VALUES);
  }

  @Test(expected = IllegalArgumentException.class)
  public void addLongCounterMismatchedRequiredLabelValues() {
    when(mockSink.getMeasuresSize()).thenReturn(4);

    recorder.addLongCounter(longCounterInstrument, 1, ImmutableList.of(),
        OPTIONAL_LABEL_VALUES);
  }

  @Test(expected = IllegalArgumentException.class)
  public void recordDoubleHistogramMismatchedRequiredLabelValues() {
    when(mockSink.getMeasuresSize()).thenReturn(4);

    recorder.recordDoubleHistogram(doubleHistogramInstrument, 99.0, ImmutableList.of(),
        OPTIONAL_LABEL_VALUES);
  }

  @Test(expected = IllegalArgumentException.class)
  public void recordLongHistogramMismatchedRequiredLabelValues() {
    when(mockSink.getMeasuresSize()).thenReturn(4);

    recorder.recordLongHistogram(longHistogramInstrument, 99, ImmutableList.of(),
        OPTIONAL_LABEL_VALUES);
  }

  @Test
  public void recordLongGaugeMismatchedRequiredLabelValues() {
    when(mockSink.getMeasuresSize()).thenReturn(4);
    when(mockSink.registerBatchCallback(any(Runnable.class), eq(longGaugeInstrument)))
        .thenReturn(mock(MetricSink.Registration.class));

    MetricRecorder.Registration registration = recorder.registerBatchCallback((recorder) -> {
      assertThrows(
          IllegalArgumentException.class,
          () -> recorder.recordLongGauge(
              longGaugeInstrument, 99, ImmutableList.of(), OPTIONAL_LABEL_VALUES));
    }, longGaugeInstrument);

    ArgumentCaptor<Runnable> callbackCaptor = ArgumentCaptor.forClass(Runnable.class);
    verify(mockSink, times(2))
        .registerBatchCallback(callbackCaptor.capture(), eq(longGaugeInstrument));
    callbackCaptor.getValue().run();
    registration.close();
  }

  @Test(expected = IllegalArgumentException.class)
  public void addDoubleCounterMismatchedOptionalLabelValues() {
    when(mockSink.getMeasuresSize()).thenReturn(4);

    recorder.addDoubleCounter(doubleCounterInstrument, 1.0, REQUIRED_LABEL_VALUES,
        ImmutableList.of());
  }

  @Test(expected = IllegalArgumentException.class)
  public void addLongCounterMismatchedOptionalLabelValues() {
    when(mockSink.getMeasuresSize()).thenReturn(4);

    recorder.addLongCounter(longCounterInstrument, 1, REQUIRED_LABEL_VALUES,
        ImmutableList.of());
  }

  @Test(expected = IllegalArgumentException.class)
  public void recordDoubleHistogramMismatchedOptionalLabelValues() {
    when(mockSink.getMeasuresSize()).thenReturn(4);

    recorder.recordDoubleHistogram(doubleHistogramInstrument, 99.0, REQUIRED_LABEL_VALUES,
        ImmutableList.of());
  }

  @Test(expected = IllegalArgumentException.class)
  public void recordLongHistogramMismatchedOptionalLabelValues() {
    when(mockSink.getMeasuresSize()).thenReturn(4);

    recorder.recordLongHistogram(longHistogramInstrument, 99, REQUIRED_LABEL_VALUES,
        ImmutableList.of());
  }

  @Test
  public void recordLongGaugeMismatchedOptionalLabelValues() {
    when(mockSink.getMeasuresSize()).thenReturn(4);
    when(mockSink.registerBatchCallback(any(Runnable.class), eq(longGaugeInstrument)))
        .thenReturn(mock(MetricSink.Registration.class));

    MetricRecorder.Registration registration = recorder.registerBatchCallback((recorder) -> {
      assertThrows(
          IllegalArgumentException.class,
          () -> recorder.recordLongGauge(
              longGaugeInstrument, 99, REQUIRED_LABEL_VALUES, ImmutableList.of()));
    }, longGaugeInstrument);

    ArgumentCaptor<Runnable> callbackCaptor = ArgumentCaptor.forClass(Runnable.class);
    verify(mockSink, times(2))
        .registerBatchCallback(callbackCaptor.capture(), eq(longGaugeInstrument));
    callbackCaptor.getValue().run();
    registration.close();
  }
}
