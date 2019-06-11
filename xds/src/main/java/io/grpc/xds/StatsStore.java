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
import io.grpc.LoadBalancer.PickResult;
import io.grpc.xds.XdsLoadStatsStore.StatsCounter;
import javax.annotation.Nullable;

/**
 * Interface for client side load stats store. A {@code StatsStore} implementation should only be
 * responsible for keeping track of load data aggregation, any load reporting information should
 * be opaque to {@code StatsStore} and be set outside.
 */
interface StatsStore {
  /**
   * Generates a {@link ClusterStats} containing load stats and backend metrics in locality
   * granularity, as well service level drop stats for the interval since the previous call of
   * this method. The fields cluster_name and load_report_interval in the returned
   * {@link ClusterStats} needs to be set before it is ready to be sent to the traffic directory
   * for load reporting.
   *
   * <p>This method should be called in the same synchronized context that
   * {@link XdsLoadBalancer.Helper#getSynchronizationContext} returns.
   */
  ClusterStats generateLoadReport();

  /**
   * Tracks load stats for endpoints in the provided locality. To be called upon balancer locality
   * updates only for newly assigned localities. Only load stats for endpoints in added localities
   * will be reported to the remote balancer. This method needs to be called at locality updates
   * only for newly assigned localities in balancer discovery responses.
   *
   * <p>This method is not thread-safe and should be called from the same synchronized context
   * returned by {@link XdsLoadBalancer.Helper#getSynchronizationContext}.
   */
  void addLocality(XdsLocality locality);

  /**
   * Stops tracking load stats for endpoints in the provided locality. To be called upon balancer
   * locality updates only for newly removed localities. Load stats for endpoints in removed
   * localities will no longer be reported to the remote balancer when client stop sending loads
   * to them.
   *
   * <p>This method is not thread-safe and should be called from the same synchronized context *
   * returned by {@link XdsLoadBalancer.Helper#getSynchronizationContext}.
   */
  void removeLocality(XdsLocality locality);

  /**
   * Applies client side load recording to {@link PickResult}s picked by the intra-locality picker
   * for the provided locality. If the provided locality is not tracked, the original
   * {@link PickResult} will be returned.
   *
   * <p>This method is thread-safe.
   */
  PickResult interceptPickResult(PickResult pickResult, XdsLocality locality);

  /**
   * Returns the {@link StatsCounter} that does locality level stats aggregation for the provided
   * locality. If the provided locality is not tracked, {@code null} will be returned.
   *
   * <p>This method is thread-safe.
   */
  @Nullable
  StatsCounter getLocalityCounter(XdsLocality locality);

  /**
   * Records a drop decision made by a {@link io.grpc.LoadBalancer.SubchannelPicker} instance
   * with the provided category. Drops are aggregated in service level.
   *
   * <p>This method is thread-safe.
   */
  void recordDroppedRequest(String category);
}
