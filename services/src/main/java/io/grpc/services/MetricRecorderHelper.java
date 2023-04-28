/*
 * Copyright 2023 The gRPC Authors
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

/**
 * Utility helper class to check whether values for {@link CallMetricRecorder} and
 * {@link MetricRecorder} are inside the valid range.
 */
final class MetricRecorderHelper {

  /**
   * Return true if the utilization value is in the range [0, 1] and false otherwise.
   */
  static boolean isUtilizationValid(double utilization) {
    return utilization >= 0.0 && utilization <= 1.0;
  }

  /**
   * Return true if the cpu utilization value is in the range [0, inf) and false otherwise.
   * Occasionally users have over 100% cpu utilization and get a runaway effect where the backend
   * with highest qps gets more and more qps sent to it. So we allow cpu utilization > 1.0.
   */
  static boolean isCpuUtilizationValid(double utilization) {
    return utilization >= 0.0;
  }

  /**
   * Return true if the qps value is in the range [0, inf) and false otherwise.
   */
  static boolean isQpsValid(double qps) {
    return qps >= 0.0;
  }

  // Prevent instantiation.
  private MetricRecorderHelper() {
  }
}
