/*
 * Copyright 2022 The gRPC Authors
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

package io.grpc.services;

import io.grpc.ExperimentalApi;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Implements the service/APIs for Out-of-Band metrics reporting, only for utilization metrics.
 * A user should use the public set-APIs to update the server machine's utilization metrics data.
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/9006")
public final class MetricRecorder {
  private volatile ConcurrentHashMap<String, Double> metricsData = new ConcurrentHashMap<>();
  private volatile double cpuUtilization;
  private volatile double applicationUtilization;
  private volatile double memoryUtilization;
  private volatile double qps;
  private volatile double eps;

  public static MetricRecorder newInstance() {
    return new MetricRecorder();
  }

  private MetricRecorder() {}

  /**
   * Update the metrics value in the range [0, 1] corresponding to the specified key. Values outside
   * the valid range are ignored.
   */
  public void putUtilizationMetric(String key, double value) {
    if (!MetricRecorderHelper.isUtilizationValid(value)) {
      return;
    }
    metricsData.put(key, value);
  }

  /**
   * Replace the whole metrics data using the specified map. No range validation.
   */
  public void setAllUtilizationMetrics(Map<String, Double> metrics) {
    metricsData = new ConcurrentHashMap<>(metrics);
  }

  /**
   * Remove the metrics data entry corresponding to the specified key.
   */
  public void removeUtilizationMetric(String key) {
    metricsData.remove(key);
  }

  /**
   * Update the CPU utilization metrics data in the range [0, inf). Values outside the valid range
   * are ignored.
   */
  public void setCpuUtilizationMetric(double value) {
    if (!MetricRecorderHelper.isCpuOrApplicationUtilizationValid(value)) {
      return;
    }
    cpuUtilization = value;
  }

  /**
   * Clear the CPU utilization metrics data.
   */
  public void clearCpuUtilizationMetric() {
    cpuUtilization = 0;
  }

  /**
   * Update the application specific utilization metrics data in the range [0, inf). Values outside
   * the valid range are ignored.
   */
  public void setApplicationUtilizationMetric(double value) {
    if (!MetricRecorderHelper.isCpuOrApplicationUtilizationValid(value)) {
      return;
    }
    applicationUtilization = value;
  }

  /**
   * Clear the application specific utilization metrics data.
   */
  public void clearApplicationUtilizationMetric() {
    applicationUtilization = 0;
  }

  /**
   * Update the memory utilization metrics data in the range [0, 1]. Values outside the valid range
   * are ignored.
   */
  public void setMemoryUtilizationMetric(double value) {
    if (!MetricRecorderHelper.isUtilizationValid(value)) {
      return;
    }
    memoryUtilization = value;
  }

  /**
   * Clear the memory utilization metrics data.
   */
  public void clearMemoryUtilizationMetric() {
    memoryUtilization = 0;
  }

  /**
   * Update the QPS metrics data in the range [0, inf). Values outside the valid range are ignored.
   */
  public void setQpsMetric(double value) {
    if (!MetricRecorderHelper.isRateValid(value)) {
      return;
    }
    qps = value;
  }

  /**
   * Clear the QPS metrics data.
   */
  public void clearQpsMetric() {
    qps = 0;
  }

  /**
   * Update the EPS metrics data in the range [0, inf). Values outside the valid range are ignored.
   */
  public void setEpsMetric(double value) {
    if (!MetricRecorderHelper.isRateValid(value)) {
      return;
    }
    this.eps = value;
  }

  /**
   * Clear the EPS metrics data.
   */
  public void clearEpsMetric() {
    eps = 0;
  }

  MetricReport getMetricReport() {
    return new MetricReport(cpuUtilization, applicationUtilization, memoryUtilization, qps, eps,
        Collections.emptyMap(), Collections.unmodifiableMap(metricsData), Collections.emptyMap());
  }
}
