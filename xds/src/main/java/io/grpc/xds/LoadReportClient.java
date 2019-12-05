/*
 * Copyright 2019 The gRPC Authors
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

import javax.annotation.concurrent.NotThreadSafe;

/**
 * A {@link LoadReportClient} is the gRPC client's load reporting agent that establishes
 * connections to traffic director for reporting load stats from gRPC client's perspective.
 *
 * <p>Each {@link LoadReportClient} instance is responsible for reporting loads for a single
 * <b>cluster</b>.
 */
@NotThreadSafe
interface LoadReportClient {

  /**
   * Establishes load reporting communication and negotiates with traffic director to report load
   * stats periodically. Calling this method on an already started {@link LoadReportClient} is
   * no-op.
   *
   * @param callback containing methods to be invoked for passing information received from load
   *                 reporting responses to xDS load balancer.
   */
  // TODO(chengyuanzhang): do not expose this method.
  void startLoadReporting(LoadReportCallback callback);

  /**
   * Terminates load reporting. Calling this method on an already stopped
   * {@link LoadReportClient} is no-op.
   *
   */
  // TODO(chengyuanzhang): do not expose this method.
  void stopLoadReporting();

  /**
   * Provides this LoadReportClient source of load stats data for the given cluster service.
   * If requested, data from the given {@code loadStatsStore} is periodically queried and
   * sent to traffic director by this LoadReportClient.
   *
   * @param clusterServiceName name of the cluster service.
   * @param loadStatsStore storage of load stats.
   */
  void addLoadStatsStore(String clusterServiceName, LoadStatsStore loadStatsStore);

  /**
   * Stops providing load stats data for the given cluster service.
   *
   * @param clusterServiceName name of the cluster service.
   */
  void removeLoadStatsStore(String clusterServiceName);

  /**
   * Callbacks for passing information received from client load reporting responses to xDS load
   * balancer, such as the load reporting interval requested by the traffic director.
   *
   * <p>Implementations are not required to be thread-safe as callbacks will be invoked in xDS load
   * balancer's {@link io.grpc.SynchronizationContext}.
   */
  interface LoadReportCallback {

    /**
     * The load reporting interval has been received.
     *
     * @param reportIntervalNano load reporting interval requested by remote traffic director.
     */
    void onReportResponse(long reportIntervalNano);
  }
}
