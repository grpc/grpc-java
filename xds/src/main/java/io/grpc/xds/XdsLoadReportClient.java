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
 * An {@link XdsLoadReportClient} is in charge of recording client side load stats, collecting
 * backend cost metrics and sending load reports to the remote balancer. It shares the same
 * channel with {@link XdsLoadBalancer} and its lifecycle is managed by {@link XdsLoadBalancer}.
 */
@NotThreadSafe
interface XdsLoadReportClient {

  /**
   * Establishes load reporting communication and negotiates with the remote balancer to report load
   * stats periodically.
   *
   * <p>This method should be the first method to be called in the lifecycle of {@link
   * XdsLoadReportClient} and should only be called once.
   *
   * <p>This method is not thread-safe and should be called from the same synchronized context
   * returned by {@link XdsLoadBalancer.Helper#getSynchronizationContext}.
   */
  void startLoadReporting();

  /**
   * Terminates load reporting.
   *
   * <p>No method in {@link XdsLoadReportClient} should be called after calling this method.
   *
   * <p>This method is not thread-safe and should be called from the same synchronized context
   * returned by {@link XdsLoadBalancer.Helper#getSynchronizationContext}.
   */
  void stopLoadReporting();
}
