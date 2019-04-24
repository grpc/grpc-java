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

import io.envoyproxy.envoy.api.v2.core.Locality;
import io.grpc.LoadBalancer.PickResult;
import javax.annotation.concurrent.NotThreadSafe;

/**
 * An {@link XdsLoadStatsManager} is in charge of recording client side load stats, collecting
 * backend cost metrics and sending load reports to the remote balancer. It shares the same
 * channel with {@link XdsLoadBalancer} and its lifecycle is managed by {@link XdsLoadBalancer}.
 */
@NotThreadSafe
interface XdsLoadStatsManager {

  /**
   * Establishes load reporting communication and negotiates with the remote balancer to report load
   * stats periodically.
   *
   * <p>This method should be the first method to be called in the lifecycle of {@link
   * XdsLoadStatsManager} and should only be called once.
   *
   * <p>This method is not thread-safe and should be called from the same synchronized context
   * returned by {@link XdsLoadBalancer#helper#getSynchronizationContext}.
   */
  void startLoadReporting();

  /**
   * Terminates load reporting.
   *
   * <p>No method in {@link XdsLoadStatsManager} should be called after calling this method.
   *
   * <p>This method is not thread-safe and should be called from the same synchronized context
   * returned by {@link XdsLoadBalancer#helper#getSynchronizationContext}.
   */
  void stopLoadReporting();

  /**
   * Applies client side load recording to {@link PickResult}s picked by the intra-locality picker
   * for the provided locality.
   *
   * <p>This method is thread-safe.
   */
  PickResult interceptPickResult(PickResult pickResult, Locality locality);

  /**
   * Tracks load stats for endpoints in the provided locality. To be called upon balancer locality
   * updates only for newly assigned localities. Only load stats for endpoints in added localities
   * will be reported to the remote balancer.
   *
   * <p>This method is not thread-safe and should be called from the same synchronized context
   * returned by {@link XdsLoadBalancer#helper#getSynchronizationContext}.
   */
  void addLocality(Locality locality);

  /**
   * Stops tracking load stats for endpoints in the provided locality. To be called upon balancer
   * locality updates only for newly removed localities. Load stats for endpoints in removed
   * localities will no longer be reported to the remote balancer when client stop sending loads to
   * them.
   *
   * <p>This method is not thread-safe and should be called from the same synchronized context *
   * returned by {@link XdsLoadBalancer#helper#getSynchronizationContext}.
   */
  void removeLocality(Locality locality);

  /**
   * Records a client-side request drop with the provided category instructed by the remote
   * balancer. Stats for dropped requests are aggregated in cluster level.
   *
   * <p>This method is thread-safe.
   */
  void recordDroppedRequest(String category);
}
