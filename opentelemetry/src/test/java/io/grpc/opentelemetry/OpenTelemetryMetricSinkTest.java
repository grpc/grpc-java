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

package io.grpc.opentelemetry;

import static io.opentelemetry.sdk.testing.assertj.OpenTelemetryAssertions.assertThat;

import com.google.common.collect.ImmutableList;
import io.grpc.DoubleCounterMetricInstrument;
import io.grpc.DoubleHistogramMetricInstrument;
import io.grpc.LongCounterMetricInstrument;
import io.grpc.LongGaugeMetricInstrument;
import io.grpc.LongHistogramMetricInstrument;
import io.grpc.MetricInstrument;
import io.grpc.MetricSink;
import io.grpc.opentelemetry.internal.OpenTelemetryConstants;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.metrics.DoubleCounter;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.sdk.common.InstrumentationScopeInfo;
import io.opentelemetry.sdk.testing.junit4.OpenTelemetryRule;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class OpenTelemetryMetricSinkTest {

  @Rule
  public final OpenTelemetryRule openTelemetryTesting = OpenTelemetryRule.create();

  private final Meter testMeter = openTelemetryTesting.getOpenTelemetry()
      .getMeter(OpenTelemetryConstants.INSTRUMENTATION_SCOPE);

  private OpenTelemetryMetricSink sink;

  @Test
  public void updateMeasures_enabledMetrics() {
    Map<String, Boolean> enabledMetrics = new HashMap<>();
    enabledMetrics.put("client_calls_started", true);
    enabledMetrics.put("server_calls_started", true);

    List<String> optionalLabels = Arrays.asList("status");

    List<MetricInstrument> instruments = Arrays.asList(
        new DoubleCounterMetricInstrument(0, "client_calls_started",
            "Number of client calls started", "count", Collections.emptyList(),
            Collections.emptyList(),
            true),
        new LongCounterMetricInstrument(1, "server_calls_started", "Number of server calls started",
            "count", Collections.emptyList(), Collections.emptyList(), false),
        new DoubleHistogramMetricInstrument(2, "client_message_size", "Sent message size", "bytes",
            Collections.emptyList(),
            Collections.emptyList(), Collections.emptyList(), true)
    );

    // Create sink
    sink = new OpenTelemetryMetricSink(testMeter, enabledMetrics, false, optionalLabels);

    // Invoke updateMeasures
    sink.updateMeasures(instruments);

    com.google.common.truth.Truth.assertThat(sink.getMeasuresSize()).isEqualTo(3);
    // Metric is explicitly enabled for sink
    com.google.common.truth.Truth.assertThat(sink.getMeasures().get(0).getMeasure())
        .isInstanceOf(DoubleCounter.class);
    // Metric is explicitly enabled for sink
    com.google.common.truth.Truth.assertThat(sink.getMeasures().get(1).getMeasure())
        .isInstanceOf(LongCounter.class);
    // Metric is enabled by default
    com.google.common.truth.Truth.assertThat(sink.getMeasures().get(2).getMeasure())
        .isInstanceOf(DoubleHistogram.class);

  }

  @Test
  public void updateMeasure_disabledMetrics() {
    Map<String, Boolean> enabledMetrics = new HashMap<>();
    enabledMetrics.put("client_calls_started", false);
    enabledMetrics.put("server_calls_started", false);

    List<String> optionalLabels = Arrays.asList("status");

    List<MetricInstrument> instruments = Arrays.asList(
        new DoubleCounterMetricInstrument(0, "client_calls_started",
            "Number of client calls started", "count", Collections.emptyList(),
            Collections.emptyList(), true),
        new LongCounterMetricInstrument(1, "server_calls_started", "Number of server calls started",
            "count", Collections.emptyList(), Collections.emptyList(), true),
        new DoubleHistogramMetricInstrument(2, "client_message_size", "Sent message size", "bytes",
            Collections.emptyList(),
            Collections.emptyList(), Collections.emptyList(), true)
    );

    // Create sink
    sink = new OpenTelemetryMetricSink(testMeter, enabledMetrics, true, optionalLabels);

    // Invoke updateMeasures
    sink.updateMeasures(instruments);

    com.google.common.truth.Truth.assertThat(sink.getMeasuresSize()).isEqualTo(3);
    // Metric is explicitly disabled
    com.google.common.truth.Truth.assertThat(sink.getMeasures().get(0)).isNull();
    // Metric is explicitly disabled
    com.google.common.truth.Truth.assertThat(sink.getMeasures().get(1)).isNull();
    // Metric is enabled by default, but all default metrics are disabled
    com.google.common.truth.Truth.assertThat(sink.getMeasures().get(2)).isNull();

  }

  @Test
  public void addCounter_enabledMetric() {
    // set up sink with disabled metric
    Map<String, Boolean> enabledMetrics = new HashMap<>();
    enabledMetrics.put("client_latency", true);

    LongCounterMetricInstrument longCounterInstrument =
        new LongCounterMetricInstrument(0, "client_latency", "Client latency", "s",
            Collections.emptyList(),
            Collections.emptyList(), false);
    DoubleCounterMetricInstrument doubleCounterInstrument =
        new DoubleCounterMetricInstrument(1, "client_calls_started",
            "Number of client calls started", "count", Collections.emptyList(),
            Collections.emptyList(),
            true);
    // Create sink
    sink = new OpenTelemetryMetricSink(testMeter, enabledMetrics, false, Collections.emptyList());

    // Invoke updateMeasures
    sink.updateMeasures(Arrays.asList(longCounterInstrument, doubleCounterInstrument));

    sink.addLongCounter(longCounterInstrument, 123L, Collections.emptyList(),
        Collections.emptyList());
    sink.addDoubleCounter(doubleCounterInstrument, 12.0, Collections.emptyList(),
        Collections.emptyList());

    assertThat(openTelemetryTesting.getMetrics())
        .satisfiesExactlyInAnyOrder(
            metric ->
                assertThat(metric)
                    .hasInstrumentationScope(InstrumentationScopeInfo.create(
                        OpenTelemetryConstants.INSTRUMENTATION_SCOPE))
                    .hasName("client_latency")
                    .hasDescription("Client latency")
                    .hasUnit("s")
                    .hasLongSumSatisfying(
                        longSum ->
                            longSum
                                .hasPointsSatisfying(
                                    point ->
                                        point
                                            .hasValue(123L))),
            metric ->
                assertThat(metric)
                    .hasInstrumentationScope(InstrumentationScopeInfo.create(
                        OpenTelemetryConstants.INSTRUMENTATION_SCOPE))
                    .hasName("client_calls_started")
                    .hasDescription("Number of client calls started")
                    .hasUnit("count")
                    .hasDoubleSumSatisfying(
                        doubleSum ->
                            doubleSum
                                .hasPointsSatisfying(
                                    point ->
                                        point
                                            .hasValue(12.0D))));
  }

  @Test
  public void addCounter_disabledMetric() {
    // set up sink with disabled metric
    Map<String, Boolean> enabledMetrics = new HashMap<>();
    enabledMetrics.put("client_latency", false);

    LongCounterMetricInstrument instrument =
        new LongCounterMetricInstrument(0, "client_latency", "Client latency", "s",
            Collections.emptyList(),
            Collections.emptyList(), true);
    // Create sink
    sink = new OpenTelemetryMetricSink(testMeter, enabledMetrics, true, Collections.emptyList());

    // Invoke updateMeasures
    sink.updateMeasures(Arrays.asList(instrument));

    sink.addLongCounter(instrument, 123L, Collections.emptyList(), Collections.emptyList());

    assertThat(openTelemetryTesting.getMetrics()).isEmpty();
  }

  @Test
  public void addHistogram_enabledMetric() {
    // set up sink with disabled metric
    Map<String, Boolean> enabledMetrics = new HashMap<>();
    enabledMetrics.put("client_message_size", true);
    enabledMetrics.put("server_message_size", true);

    DoubleHistogramMetricInstrument doubleHistogramInstrument =
        new DoubleHistogramMetricInstrument(0, "client_message_size", "Sent message size", "bytes",
            Collections.emptyList(),
            Collections.emptyList(), Collections.emptyList(), false);
    LongHistogramMetricInstrument longHistogramInstrument =
        new LongHistogramMetricInstrument(1, "server_message_size", "Received message size",
            "bytes",
            Collections.emptyList(),
            Collections.emptyList(), Collections.emptyList(), true);

    // Create sink
    sink = new OpenTelemetryMetricSink(testMeter, enabledMetrics, false, Collections.emptyList());

    // Invoke updateMeasures
    sink.updateMeasures(Arrays.asList(doubleHistogramInstrument, longHistogramInstrument));

    sink.recordDoubleHistogram(doubleHistogramInstrument, 12.0, Collections.emptyList(),
        Collections.emptyList());
    sink.recordLongHistogram(longHistogramInstrument, 123L, Collections.emptyList(),
        Collections.emptyList());

    assertThat(openTelemetryTesting.getMetrics())
        .satisfiesExactlyInAnyOrder(
            metric ->
                assertThat(metric)
                    .hasInstrumentationScope(InstrumentationScopeInfo.create(
                        OpenTelemetryConstants.INSTRUMENTATION_SCOPE))
                    .hasName("client_message_size")
                    .hasDescription("Sent message size")
                    .hasUnit("bytes")
                    .hasHistogramSatisfying(
                        histogram ->
                            histogram.hasPointsSatisfying(
                                point ->
                                    point
                                        .hasCount(1)
                                        .hasSum(12.0))),

            metric ->
                assertThat(metric)
                    .hasInstrumentationScope(InstrumentationScopeInfo.create(
                        OpenTelemetryConstants.INSTRUMENTATION_SCOPE))
                    .hasName("server_message_size")
                    .hasDescription("Received message size")
                    .hasUnit("bytes")
                    .hasHistogramSatisfying(
                        histogram ->
                            histogram.hasPointsSatisfying(
                                point ->
                                    point
                                        .hasCount(1)
                                        .hasSum(123L))));
  }

  @Test
  public void addHistogram_disabledMetric() {
    // set up sink with disabled metric
    Map<String, Boolean> enabledMetrics = new HashMap<>();
    enabledMetrics.put("client_message_size", false);
    enabledMetrics.put("server_message_size", false);

    DoubleHistogramMetricInstrument doubleHistogramInstrument =
        new DoubleHistogramMetricInstrument(0, "client_message_size", "Sent message size", "bytes",
            Collections.emptyList(),
            Collections.emptyList(), Collections.emptyList(), false);
    LongHistogramMetricInstrument longHistogramInstrument =
        new LongHistogramMetricInstrument(1, "server_message_size", "Received message size",
            "bytes",
            Collections.emptyList(),
            Collections.emptyList(), Collections.emptyList(), true);

    // Create sink
    sink = new OpenTelemetryMetricSink(testMeter, enabledMetrics, false, Collections.emptyList());

    // Invoke updateMeasures
    sink.updateMeasures(Arrays.asList(doubleHistogramInstrument, longHistogramInstrument));

    sink.recordDoubleHistogram(doubleHistogramInstrument, 12.0, Collections.emptyList(),
        Collections.emptyList());
    sink.recordLongHistogram(longHistogramInstrument, 123L, Collections.emptyList(),
        Collections.emptyList());

    assertThat(openTelemetryTesting.getMetrics()).isEmpty();
  }

  @Test
  public void registerBatchCallback_allDisabled() {
    // set up sink with disabled metric
    Map<String, Boolean> enabledMetrics = new HashMap<>();

    LongGaugeMetricInstrument longGaugeInstrumentDisabled =
        new LongGaugeMetricInstrument(0, "disk", "Amount of disk used", "By",
            Collections.emptyList(), Collections.emptyList(), false);

    // Create sink
    sink = new OpenTelemetryMetricSink(testMeter, enabledMetrics, false, Collections.emptyList());

    // Invoke updateMeasures
    sink.updateMeasures(Arrays.asList(longGaugeInstrumentDisabled));

    MetricSink.Registration registration = sink.registerBatchCallback(() -> {
      sink.recordLongGauge(
          longGaugeInstrumentDisabled, 999, Collections.emptyList(), Collections.emptyList());
    }, longGaugeInstrumentDisabled);

    assertThat(openTelemetryTesting.getMetrics())
        .satisfiesExactlyInAnyOrder();
    registration.close();
  }

  @Test
  public void registerBatchCallback_bothEnabledAndDisabled() {
    // set up sink with disabled metric
    Map<String, Boolean> enabledMetrics = new HashMap<>();
    enabledMetrics.put("memory", true);

    LongGaugeMetricInstrument longGaugeInstrumentEnabled =
        new LongGaugeMetricInstrument(0, "memory", "Amount of memory used", "By",
            Collections.emptyList(), Collections.emptyList(), false);
    LongGaugeMetricInstrument longGaugeInstrumentDisabled =
        new LongGaugeMetricInstrument(1, "disk", "Amount of disk used", "By",
            Collections.emptyList(), Collections.emptyList(), false);

    // Create sink
    sink = new OpenTelemetryMetricSink(testMeter, enabledMetrics, false, Collections.emptyList());

    // Invoke updateMeasures
    sink.updateMeasures(Arrays.asList(longGaugeInstrumentEnabled, longGaugeInstrumentDisabled));

    MetricSink.Registration registration = sink.registerBatchCallback(() -> {
      sink.recordLongGauge(
          longGaugeInstrumentEnabled, 99, Collections.emptyList(), Collections.emptyList());
      sink.recordLongGauge(
          longGaugeInstrumentDisabled, 999, Collections.emptyList(), Collections.emptyList());
    }, longGaugeInstrumentEnabled, longGaugeInstrumentDisabled);

    assertThat(openTelemetryTesting.getMetrics())
        .satisfiesExactlyInAnyOrder(
            metric ->
                assertThat(metric)
                    .hasInstrumentationScope(InstrumentationScopeInfo.create(
                        OpenTelemetryConstants.INSTRUMENTATION_SCOPE))
                    .hasName("memory")
                    .hasDescription("Amount of memory used")
                    .hasUnit("By")
                    .hasLongGaugeSatisfying(
                        gauge ->
                            gauge.hasPointsSatisfying(
                                point ->
                                    point
                                        .hasValue(99))));

    // Gauge goes away after close
    registration.close();
    assertThat(openTelemetryTesting.getMetrics())
        .satisfiesExactlyInAnyOrder();
  }

  @Test
  public void recordLabels() {
    Map<String, Boolean> enabledMetrics = new HashMap<>();
    enabledMetrics.put("client_latency", true);

    List<String> optionalLabels = Arrays.asList("optional_label_key_2");

    LongCounterMetricInstrument longCounterInstrument =
        new LongCounterMetricInstrument(0, "client_latency", "Client latency", "s",
            ImmutableList.of("required_label_key_1", "required_label_key_2"),
            ImmutableList.of("optional_label_key_1", "optional_label_key_2"), false);

    // Create sink
    sink = new OpenTelemetryMetricSink(testMeter, enabledMetrics, false, optionalLabels);

    // Invoke updateMeasures
    sink.updateMeasures(Arrays.asList(longCounterInstrument));

    sink.addLongCounter(longCounterInstrument, 123L,
        ImmutableList.of("required_label_value_1", "required_label_value_2"),
        ImmutableList.of("optional_label_value_1", "optional_label_value_2"));

    io.opentelemetry.api.common.Attributes expectedAtrributes
        = io.opentelemetry.api.common.Attributes.of(
        AttributeKey.stringKey("required_label_key_1"), "required_label_value_1",
        AttributeKey.stringKey("required_label_key_2"), "required_label_value_2",
        AttributeKey.stringKey("optional_label_key_2"), "optional_label_value_2");

    assertThat(openTelemetryTesting.getMetrics())
        .satisfiesExactlyInAnyOrder(
            metric ->
                assertThat(metric)
                    .hasInstrumentationScope(InstrumentationScopeInfo.create(
                        OpenTelemetryConstants.INSTRUMENTATION_SCOPE))
                    .hasName("client_latency")
                    .hasDescription("Client latency")
                    .hasUnit("s")
                    .hasLongSumSatisfying(
                        longSum ->
                            longSum
                                .hasPointsSatisfying(
                                    point ->
                                        point
                                            .hasAttributes(expectedAtrributes)
                                            .hasValue(123L))));
  }
}
