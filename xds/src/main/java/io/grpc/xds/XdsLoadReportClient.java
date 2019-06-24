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
   * stats periodically. Calling this method on an already started {@link XdsLoadReportClient} is
   * no-op.
   *
   * <p>This method is not thread-safe and should be called from the same synchronized context
   * returned by {@link XdsLoadBalancer.Helper#getSynchronizationContext}.
   *
   * @param callback containing methods to be invoked for passing information received from load
   *                 reporting responses to xDS load balancer.
   */
  void startLoadReporting(XdsLoadReportCallback callback);

  /**
   * Terminates load reporting. Calling this method on an already stopped
   * {@link XdsLoadReportClient} is no-op.
   *
   * <p>This method is not thread-safe and should be called from the same synchronized context
   * returned by {@link XdsLoadBalancer.Helper#getSynchronizationContext}.
   */
  void stopLoadReporting();

  /**
   * Callbacks for passing information received from client load reporting responses to xDS load
   * balancer, such as the load reporting interval requested by the traffic director.
   *
   * <p>Implementations are not required to be thread-safe as callbacks will be invoked in xDS load
   * balancer's {@link io.grpc.SynchronizationContext}.
   */
  interface XdsLoadReportCallback {

    /**
     * The load reporting interval has been received.
     *
     * @param reportIntervalNano load reporting interval requested by remote traffic director.
     */
    void onReportResponse(long reportIntervalNano);
  }
}
