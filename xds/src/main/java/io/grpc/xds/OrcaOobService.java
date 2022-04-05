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

package io.grpc.xds;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.annotations.VisibleForTesting;
import io.grpc.BindableService;
import io.grpc.ExperimentalApi;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Implements the service/APIs for Out-of-Band metrics reporting, only for utilization metrics.
 * Register the returned service {@link #getService()} to the server, then a client can request
 * for periodic load reports. A user should use the public set-APIs to update the server machine's
 * utilization metrics data.
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/9006")
public final class OrcaOobService {
  /**
   * Empty or invalid (non-positive) minInterval config in will be treated to this default value.
   */
  public static final long DEFAULT_MIN_REPORT_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(30);

  private final OrcaServiceImpl orcaService;

  /**
   * Construct an OOB metrics reporting service.
   *
   * @param minInterval configures the minimum metrics reporting interval for the service. Bad
   *        configuration (non-positive) will be overridden to service default (30s).
   *        Minimum metrics reporting interval means, if the setting in the client's
   *        request is invalid (non-positive) or below this value, they will be treated
   *        as this value.
   */
  public OrcaOobService(long minInterval, TimeUnit timeUnit,
                        ScheduledExecutorService timeService) {
    this.orcaService = new OrcaServiceImpl(minInterval > 0 ? timeUnit.toNanos(minInterval)
        : DEFAULT_MIN_REPORT_INTERVAL_NANOS, checkNotNull(timeService));
  }

  public OrcaOobService(ScheduledExecutorService timeService) {
    this(DEFAULT_MIN_REPORT_INTERVAL_NANOS, TimeUnit.NANOSECONDS, timeService);
  }

  /**
   * Returns the service instance to be bound to the server for ORCA OOB functionality.
   */
  public BindableService getService() {
    return orcaService;
  }

  @VisibleForTesting
  int getClientsCount() {
    return orcaService.clientCount.get();
  }

  /**
   * Update the metrics value corresponding to the specified key.
   */
  public void setUtilizationMetric(String key, double value) {
    orcaService.setUtilizationMetric(key, value);
  }

  /**
   * Replace the whole metrics data using the specified map.
   */
  public void setAllUtilizationMetrics(Map<String, Double> metrics) {
    orcaService.setAllUtilizationMetrics(metrics);
  }

  /**
   * Remove the metrics data entry corresponding to the specified key.
   */
  public void deleteUtilizationMetric(String key) {
    orcaService.deleteUtilizationMetric(key);
  }

  /**
   * Update the CPU utilization metrics data.
   */
  public void setCpuUtilizationMetric(double value) {
    orcaService.setCpuUtilizationMetric(value);
  }

  /**
   * Clear the CPU utilization metrics data.
   */
  public void deleteCpuUtilizationMetric() {
    orcaService.deleteCpuUtilizationMetric();
  }

  /**
   * Update the memory utilization metrics data.
   */
  public void setMemoryUtilizationMetric(double value) {
    orcaService.setMemoryUtilizationMetric(value);
  }

  /**
   * Clear the memory utilization metrics data.
   */
  public void deleteMemoryUtilizationMetric() {
    orcaService.deleteMemoryUtilizationMetric();
  }
}
