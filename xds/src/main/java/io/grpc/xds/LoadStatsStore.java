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

import io.envoyproxy.envoy.api.v2.endpoint.ClusterStats;
import io.grpc.xds.EnvoyProtoData.Locality;
import javax.annotation.Nullable;

/**
 * Interface for client side load stats store. An {@code LoadStatsStore} maintains load stats per
 * cluster:cluster_service exposed by traffic director from a gRPC client's perspective,
 * including dropped calls. Load stats for endpoints (i.e., Google backends) are aggregated in
 * locality granularity (i.e., Google cluster) while the numbers of dropped calls are aggregated
 * in cluster:cluster_service granularity.
 *
 * <p>An {@code LoadStatsStore} only tracks loads for localities exposed by remote traffic
 * director. A proper usage should be
 *
 * <ol>
 *   <li>Let {@link LoadStatsStore} track the locality newly exposed by traffic director by
 *       calling {@link #addLocality(Locality)}.
 *   <li>Use the locality counter returned by {@link #getLocalityCounter(Locality)} to record
 *       load stats for the corresponding locality.
 *   <li>Tell {@link LoadStatsStore} to stop tracking the locality no longer exposed by traffic
 *       director by calling {@link #removeLocality(Locality)}.
 * </ol>
 *
 * <p>No locality information is needed for recording dropped calls since they are aggregated in
 * cluster granularity.
 */
interface LoadStatsStore {

  /**
   * Generates a {@link ClusterStats} proto message as the load report based on recorded load stats
   * (including RPC * counts, backend metrics and dropped calls) for the interval since the previous
   * call of this method.
   *
   * <p>Loads for localities no longer under tracking will not be included in generated load reports
   * once all of theirs loads are completed and reported.
   *
   * <p>The fields {@code cluster_name} and {@code load_report_interval} in the returned {@link
   * ClusterStats} needs to be set before it is ready to be sent to the traffic director for load
   * reporting.
   *
   * <p>This method is not thread-safe and should be called from the same synchronized context
   * used by {@link LoadReportClient}.
   */
  ClusterStats generateLoadReport();

  /**
   * Starts tracking load stats for endpoints in the provided locality. Only load stats for
   * endpoints in added localities will be recorded and included in generated load reports.
   *
   * <p>This method needs to be called at locality updates only for newly assigned localities in
   * endpoint discovery responses before recording loads for those localities.
   *
   * <p>This method is not thread-safe and should be called from the same synchronized context
   * used by {@link LoadReportClient}.
   */
  void addLocality(Locality locality);

  /**
   * Stops tracking load stats for endpoints in the provided locality. gRPC clients are expected not
   * to send loads to localities no longer exposed by traffic director. Load stats for endpoints in
   * removed localities will no longer be included in future generated load reports after their
   * recorded and ongoing loads have been reported.
   *
   * <p>This method needs to be called at locality updates only for newly removed localities.
   * Forgetting calling this method for localities no longer under track will result in memory
   * waste and keep including zero-load upstream locality stats in generated load reports.
   *
   * <p>This method is not thread-safe and should be called from the same synchronized context
   * used by {@link LoadReportClient}.
   */
  void removeLocality(Locality locality);

  /**
   * Returns the locality counter that does locality level stats aggregation for the provided
   * locality. If the provided locality is not tracked, {@code null} will be returned.
   *
   * <p>This method is thread-safe.
   */
  @Nullable
  ClientLoadCounter getLocalityCounter(Locality locality);

  /**
   * Records a drop decision made by a {@link io.grpc.LoadBalancer.SubchannelPicker} instance
   * with the provided category. Drops are aggregated in cluster granularity.
   *
   * <p>This method is thread-safe.
   */
  void recordDroppedRequest(String category);
}
