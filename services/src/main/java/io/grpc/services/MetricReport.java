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

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.base.MoreObjects;
import io.grpc.ExperimentalApi;
import java.util.Map;

/**
 * A gRPC object of orca load report. LB policies listening at per-rpc or oob orca load reports
 * will be notified of the metrics data in this data format.
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/9381")
public final class MetricReport {
  private double cpuUtilization;
  private double memoryUtilization;
  private Map<String, Double> requestCostMetrics;
  private Map<String, Double> utilizationMetrics;

  MetricReport(double cpuUtilization, double memoryUtilization,
                   Map<String, Double> requestCostMetrics,
                   Map<String, Double> utilizationMetrics) {
    this.cpuUtilization = cpuUtilization;
    this.memoryUtilization = memoryUtilization;
    this.requestCostMetrics = checkNotNull(requestCostMetrics, "requestCostMetrics");
    this.utilizationMetrics = checkNotNull(utilizationMetrics, "utilizationMetrics");
  }

  public double getCpuUtilization() {
    return cpuUtilization;
  }

  public double getMemoryUtilization() {
    return memoryUtilization;
  }

  public Map<String, Double> getRequestCostMetrics() {
    return requestCostMetrics;
  }

  public Map<String, Double> getUtilizationMetrics() {
    return utilizationMetrics;
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
        .add("cpuUtilization", cpuUtilization)
        .add("memoryUtilization", memoryUtilization)
        .add("requestCost", requestCostMetrics)
        .add("utilization", utilizationMetrics)
        .toString();
  }
}
