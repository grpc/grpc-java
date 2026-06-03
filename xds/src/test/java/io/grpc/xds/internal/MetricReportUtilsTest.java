/*
 * Copyright 2026 The gRPC Authors
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

package io.grpc.xds.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.grpc.services.InternalCallMetricRecorder;
import io.grpc.services.MetricReport;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalDouble;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link MetricReportUtils}. */
@RunWith(JUnit4.class)
public class MetricReportUtilsTest {

  @Test
  public void getMetricValue_cpuUtilization() {
    MetricReport report = createMetricReport(0.5, 0.1, 0.2, 10.0, 5.0, Collections.emptyMap());
    MetricReportUtils.ParsedMetricName parsed =
        MetricReportUtils.ParsedMetricName.parse("cpu_utilization");
    OptionalDouble result = MetricReportUtils.getMetricValue(report, parsed);
    assertTrue(result.isPresent());
    assertEquals(0.5, result.getAsDouble(), 0.0001);
  }

  @Test
  public void getMetricValue_applicationUtilization() {
    MetricReport report = createMetricReport(0.5, 0.1, 0.2, 10.0, 5.0, Collections.emptyMap());
    MetricReportUtils.ParsedMetricName parsed =
        MetricReportUtils.ParsedMetricName.parse("application_utilization");
    OptionalDouble result = MetricReportUtils.getMetricValue(report, parsed);
    assertTrue(result.isPresent());
    assertEquals(0.1, result.getAsDouble(), 0.0001);
  }

  @Test
  public void getMetricValue_memUtilization() {
    MetricReport report = createMetricReport(0.5, 0.1, 0.2, 10.0, 5.0, Collections.emptyMap());
    MetricReportUtils.ParsedMetricName parsed =
        MetricReportUtils.ParsedMetricName.parse("mem_utilization");
    OptionalDouble result = MetricReportUtils.getMetricValue(report, parsed);
    assertTrue(result.isPresent());
    assertEquals(0.2, result.getAsDouble(), 0.0001);
  }

  @Test
  public void getMetricValue_utilizationMetric() {
    Map<String, Double> utilizationMetrics = new HashMap<>();
    utilizationMetrics.put("foo", 1.23);
    MetricReport report = InternalCallMetricRecorder.createMetricReport(
         0, 0, 0, 0, 0, Collections.emptyMap(), utilizationMetrics, Collections.emptyMap());

    MetricReportUtils.ParsedMetricName parsed =
        MetricReportUtils.ParsedMetricName.parse("utilization.foo");
    OptionalDouble result = MetricReportUtils.getMetricValue(report, parsed);
    assertTrue(result.isPresent());
    assertEquals(1.23, result.getAsDouble(), 0.0001);

    MetricReportUtils.ParsedMetricName bad =
        MetricReportUtils.ParsedMetricName.parse("utilization.bar");
    assertFalse(MetricReportUtils.getMetricValue(report, bad).isPresent());
  }

  @Test
  public void getMetricValue_namedMetric() {
    Map<String, Double> namedMetrics = new HashMap<>();
    namedMetrics.put("foo", 7.89);
    MetricReport report = createMetricReport(0, 0, 0, 0, 0, namedMetrics);

    MetricReportUtils.ParsedMetricName parsed =
        MetricReportUtils.ParsedMetricName.parse("named_metrics.foo");
    OptionalDouble result = MetricReportUtils.getMetricValue(report, parsed);
    assertTrue(result.isPresent());
    assertEquals(7.89, result.getAsDouble(), 0.0001);

    MetricReportUtils.ParsedMetricName bad =
        MetricReportUtils.ParsedMetricName.parse("named_metrics.bar");
    assertFalse(MetricReportUtils.getMetricValue(report, bad).isPresent());
  }

  @Test
  public void getMetricValue_invalidMetric() {
    MetricReport report = createMetricReport(0.5, 0.1, 0.2, 10.0, 5.0, Collections.emptyMap());
    MetricReportUtils.ParsedMetricName invalid =
        MetricReportUtils.ParsedMetricName.parse("invalid_metric");
    assertFalse(MetricReportUtils.getMetricValue(report, invalid).isPresent());
  }

  private MetricReport createMetricReport(double cpu, double app, double mem, double qps,
      double eps, Map<String, Double> namedMetrics) {
    return InternalCallMetricRecorder.createMetricReport(
        cpu, app, mem, qps, eps, Collections.emptyMap(), Collections.emptyMap(), namedMetrics);
  }
}
