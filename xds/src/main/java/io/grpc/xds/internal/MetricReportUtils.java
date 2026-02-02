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

import io.grpc.services.MetricReport;
import java.util.Map;
import java.util.OptionalDouble;

/**
 * Utilities for parsing and resolving metrics from {@link MetricReport}.
 */
public final class MetricReportUtils {

  private MetricReportUtils() {}

  /**
   * Resolves a metric value from the report based on the given metric name.
   * The logic checks for specific prefixes to determine where to look up the metric:
   * <ul>
   * <li>"cpu_utilization" -> getCpuUtilization()</li>
   * <li>"application_utilization" -> getApplicationUtilization()</li>
   * <li>"memory_utilization" -> getMemoryUtilization()</li>
   * <li>"qps" -> getQps()</li>
   * <li>"eps" -> getEps()</li>
   * <li>"utilization." -> lookup in utilizationMetrics</li>
   * <li>"request_cost." -> lookup in requestCostMetrics</li>
   * <li>"named_metrics." -> lookup in namedMetrics</li>
   * </ul>
   *
   * @param report The metric report to query.
   * @param metricName The name of the custom metric to look up.
   * @return The value of the metric if found, or empty if not found.
   */
  public static OptionalDouble getMetric(MetricReport report, String metricName) {
    if (metricName.equals("cpu_utilization")) {
      return OptionalDouble.of(report.getCpuUtilization());
    } else if (metricName.equals("application_utilization")) {
      return OptionalDouble.of(report.getApplicationUtilization());
    } else if (metricName.equals("memory_utilization")) {
      return OptionalDouble.of(report.getMemoryUtilization());
    } else if (metricName.equals("qps")) {
      return OptionalDouble.of(report.getQps());
    } else if (metricName.equals("eps")) {
      return OptionalDouble.of(report.getEps());
    } else if (metricName.startsWith("utilization.")) {
      Map<String, Double> map = report.getUtilizationMetrics();
      Double val = map.get(metricName.substring("utilization.".length()));
      if (val != null) {
        return OptionalDouble.of(val);
      }
    } else if (metricName.startsWith("request_cost.")) {
      Map<String, Double> map = report.getRequestCostMetrics();
      Double val = map.get(metricName.substring("request_cost.".length()));
      if (val != null) {
        return OptionalDouble.of(val);
      }
    } else if (metricName.startsWith("named_metrics.")) {
      Map<String, Double> map = report.getNamedMetrics();
      Double val = map.get(metricName.substring("named_metrics.".length()));
      if (val != null) {
        return OptionalDouble.of(val);
      }
    }
    return OptionalDouble.empty();
  }
}
