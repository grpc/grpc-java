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
  private double applicationUtilization;
  private double memoryUtilization;
  private double qps;
  private double eps;
  private Map<String, Double> requestCostMetrics;
  private Map<String, Double> utilizationMetrics;
  private Map<String, Double> namedMetrics;

  MetricReport(double cpuUtilization, double applicationUtilization, double memoryUtilization,
      double qps, double eps, Map<String, Double> requestCostMetrics,
      Map<String, Double> utilizationMetrics, Map<String, Double> namedMetrics) {
    this.cpuUtilization = cpuUtilization;
    this.applicationUtilization = applicationUtilization;
    this.memoryUtilization = memoryUtilization;
    this.qps = qps;
    this.eps = eps;
    this.requestCostMetrics = checkNotNull(requestCostMetrics, "requestCostMetrics");
    this.utilizationMetrics = checkNotNull(utilizationMetrics, "utilizationMetrics");
    this.namedMetrics = checkNotNull(namedMetrics, "namedMetrics");
  }

  public double getCpuUtilization() {
    return cpuUtilization;
  }

  public double getApplicationUtilization() {
    return applicationUtilization;
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

  public Map<String, Double> getNamedMetrics() {
    return namedMetrics;
  }

  public double getQps() {
    return qps;
  }

  public double getEps() {
    return eps;
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
        .add("cpuUtilization", cpuUtilization)
        .add("applicationUtilization", applicationUtilization)
        .add("memoryUtilization", memoryUtilization)
        .add("requestCost", requestCostMetrics)
        .add("utilization", utilizationMetrics)
        .add("named", namedMetrics)
        .add("qps", qps)
        .add("eps", eps)
        .toString();
  }
}
