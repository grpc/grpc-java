/*
 * Copyright 2025 The gRPC Authors
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

package io.grpc.xds.client;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.collect.ImmutableSet;
import io.grpc.Internal;
import java.util.Objects;
import javax.annotation.Nullable;

/**
 * Represents the configuration for which ORCA metrics should be propagated from backend
 * to LRS load reports, as defined in gRFC A85.
 */
@Internal
public final class BackendMetricPropagation {

  public final boolean propagateCpuUtilization;
  public final boolean propagateMemUtilization;
  public final boolean propagateApplicationUtilization;

  private final boolean propagateAllNamedMetrics;
  private final ImmutableSet<String> namedMetricKeys;

  private BackendMetricPropagation(
      boolean propagateCpuUtilization,
      boolean propagateMemUtilization,
      boolean propagateApplicationUtilization,
      boolean propagateAllNamedMetrics,
      ImmutableSet<String> namedMetricKeys) {
    this.propagateCpuUtilization = propagateCpuUtilization;
    this.propagateMemUtilization = propagateMemUtilization;
    this.propagateApplicationUtilization = propagateApplicationUtilization;
    this.propagateAllNamedMetrics = propagateAllNamedMetrics;
    this.namedMetricKeys = checkNotNull(namedMetricKeys, "namedMetricKeys");
  }

  /**
   * Creates a BackendMetricPropagation from a list of metric specifications.
   *
   * @param metricSpecs list of metric specification strings from CDS resource
   * @return BackendMetricPropagation instance
   */
  public static BackendMetricPropagation fromMetricSpecs(
      @Nullable java.util.List<String> metricSpecs) {
    if (metricSpecs == null || metricSpecs.isEmpty()) {
      return new BackendMetricPropagation(false, false, false, false, ImmutableSet.of());
    }

    boolean propagateCpuUtilization = false;
    boolean propagateMemUtilization = false;
    boolean propagateApplicationUtilization = false;
    boolean propagateAllNamedMetrics = false;
    ImmutableSet.Builder<String> namedMetricKeysBuilder = ImmutableSet.builder();
    for (String spec : metricSpecs) {
      if (spec == null) {
        continue;
      }
      switch (spec) {
        case "cpu_utilization":
          propagateCpuUtilization = true;
          break;
        case "mem_utilization":
          propagateMemUtilization = true;
          break;
        case "application_utilization":
          propagateApplicationUtilization = true;
          break;
        case "named_metrics.*":
          propagateAllNamedMetrics = true;
          break;
        default:
          if (spec.startsWith("named_metrics.")) {
            String metricKey = spec.substring("named_metrics.".length());
            if (!metricKey.isEmpty()) {
              namedMetricKeysBuilder.add(metricKey);
            }
          }
      }
    }

    return new BackendMetricPropagation(
        propagateCpuUtilization,
        propagateMemUtilization,
        propagateApplicationUtilization,
        propagateAllNamedMetrics,
        namedMetricKeysBuilder.build());
  }

  /**
   * Returns whether the given named metric key should be propagated.
   */
  public boolean shouldPropagateNamedMetric(String metricKey) {
    return propagateAllNamedMetrics || namedMetricKeys.contains(metricKey);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    BackendMetricPropagation that = (BackendMetricPropagation) o;
    return propagateCpuUtilization == that.propagateCpuUtilization
        && propagateMemUtilization == that.propagateMemUtilization
        && propagateApplicationUtilization == that.propagateApplicationUtilization
        && propagateAllNamedMetrics == that.propagateAllNamedMetrics
        && Objects.equals(namedMetricKeys, that.namedMetricKeys);
  }

  @Override
  public int hashCode() {
    return Objects.hash(propagateCpuUtilization, propagateMemUtilization,
        propagateApplicationUtilization, propagateAllNamedMetrics, namedMetricKeys);
  }
}