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

import io.grpc.Attributes;
import io.grpc.ConnectivityStateInfo;
import io.grpc.EquivalentAddressGroup;
import io.grpc.LoadBalancer.Subchannel;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import java.util.List;
import javax.annotation.Nullable;

/**
 * The states of an XDS working session of {@link XdsLoadBalancer}.  Created when XdsLoadBalancer
 * switches to the current mode.  Shutdown and discarded when XdsLoadBalancer switches to another
 * mode.
 */
abstract class XdsLbState {

  abstract void handleResolvedAddressGroups(
      List<EquivalentAddressGroup> servers, Attributes attributes);

  abstract void propagateError(Status error);

  abstract void handleSubchannelState(Subchannel subchannel, ConnectivityStateInfo newState);

  /**
   * Shuts down subchannels and child loadbalancers, cancels fallback timeer, and cancels retry
   * timer.
   */
  abstract void shutdown();

  final void shutdownLbComm() {
    if (lbCommChannel() != null) {
      lbCommChannel().shutdown();
    }
    shutdownLbRpc(Status.CANCELLED.withDescription("Loadbalancer client shutdown"));
  }

  final void shutdownLbRpc(Status status) {
    if (adsStream() != null) {
      adsStream().close(status);
      // lbStream will be set to null in LbStream.cleanup()
    }
  }

  @Nullable
  abstract ManagedChannel lbCommChannel();

  @Nullable
  abstract AdsStream adsStream();

  abstract Mode mode();

  enum Mode {
    /**
     * Standard plugin: No child plugin specified in lb config. Lb will send CDS request, and then
     * EDS requests. EDS requests request for endpoints.
     */
    STANDARD,

    /**
     * Custom plugin: Child plugin specified in lb config. Lb will send EDS directly. EDS requests
     * do not request for endpoints.
     */
    CUSTOM,
  }
}
