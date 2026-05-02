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

import com.google.auto.value.AutoValue;
import io.grpc.services.MetricReport;
import java.util.Optional;


/**
 * Utilities for parsing and resolving metrics from {@link MetricReport}.
 */
public final class MetricReportUtils {

  private MetricReportUtils() {}

  public enum MetricType {
    CPU_UTILIZATION,
    APPLICATION_UTILIZATION,
    MEMORY_UTILIZATION,
    UTILIZATION,
    NAMED_METRICS,
    INVALID
  }

  @AutoValue
  public abstract static class ParsedMetricName {
    public abstract MetricType getMetricType();

    public abstract Optional<String> getKey();

    public static ParsedMetricName create(MetricType metricType, Optional<String> key) {
      return new AutoValue_MetricReportUtils_ParsedMetricName(metricType, key);
    }

    /**
     * Pre-parses a custom metric name into a {@link ParsedMetricName}.
     *
     * @param name The custom metric name to parse.
     * @return The parsed metric name.
     */
    public static ParsedMetricName parse(String name) {
      if (name.equals("cpu_utilization")) {
        return create(MetricType.CPU_UTILIZATION, Optional.empty());
      }
      if (name.equals("application_utilization")) {
        return create(MetricType.APPLICATION_UTILIZATION, Optional.empty());
      }
      if (name.equals("mem_utilization")) {
        return create(MetricType.MEMORY_UTILIZATION, Optional.empty());
      }
      if (name.startsWith("utilization.")) {
        return create(MetricType.UTILIZATION, Optional.of(name.substring("utilization.".length())));
      }
      if (name.startsWith("named_metrics.")) {
        return create(MetricType.NAMED_METRICS,
            Optional.of(name.substring("named_metrics.".length())));
      }
      return create(MetricType.INVALID, Optional.empty());
    }

  }

  /**
   * Resolves a custom metric value for `parsedMetric`
   * Returns -1.0 if the metric is absent or invalid.
   *
   * @param report The metric report to query.
   * @param parsedMetric The parsed metric to lookup.
   * @return The metric value or -1.0 if absent.
   */

  public static double getMetricValue(MetricReport report, ParsedMetricName parsedMetric) {
    switch (parsedMetric.getMetricType()) {
      case CPU_UTILIZATION:
        return report.getCpuUtilization();
      case APPLICATION_UTILIZATION:
        return report.getApplicationUtilization();
      case MEMORY_UTILIZATION:
        return report.getMemoryUtilization();
      case UTILIZATION:
        if (parsedMetric.getKey().isPresent()) {
          String key = parsedMetric.getKey().get();
          Double val = report.getUtilizationMetrics().get(key);
          if (val != null) {
            return val;
          }
        }
        return -1.0;
      case NAMED_METRICS:
        if (parsedMetric.getKey().isPresent()) {
          String key = parsedMetric.getKey().get();
          Double val = report.getNamedMetrics().get(key);
          if (val != null) {
            return val;
          }
        }
        return -1.0;
      case INVALID:
        return -1.0;
      default:
        return -1.0;
    }
  }
}
