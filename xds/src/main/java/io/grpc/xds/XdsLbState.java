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

import static com.google.common.base.Preconditions.checkNotNull;

import io.grpc.Attributes;
import io.grpc.ConnectivityStateInfo;
import io.grpc.EquivalentAddressGroup;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancer.Subchannel;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.internal.ServiceConfigUtil.LbConfig;
import io.grpc.xds.XdsComms.AdsStreamCallback;
import java.util.List;
import javax.annotation.Nullable;

/**
 * The states of an XDS working session of {@link XdsLoadBalancer}.  Created when XdsLoadBalancer
 * switches to the current mode.  Shutdown and discarded when XdsLoadBalancer switches to another
 * mode.
 *
 * <p>There might be two implementations:
 *
 * <ul>
 *   <li>Standard plugin: No child plugin specified in lb config. Lb will send CDS request,
 *       and then EDS requests. EDS requests request for endpoints.</li>
 *   <li>Custom plugin: Child plugin specified in lb config. Lb will send EDS directly. EDS requests
 *       do not request for endpoints.</li>
 * </ul>
 */
class XdsLbState {

  final String balancerName;

  @Nullable
  final LbConfig childPolicy;

  private final LocalityStore localityStore;
  private final Helper helper;
  private final ManagedChannel channel;
  private final AdsStreamCallback adsStreamCallback;

  @Nullable
  private XdsComms xdsComms;

  XdsLbState(
      String balancerName,
      @Nullable LbConfig childPolicy,
      Helper helper,
      LocalityStore localityStore,
      ManagedChannel channel,
      AdsStreamCallback adsStreamCallback) {
    this.balancerName = checkNotNull(balancerName, "balancerName");
    this.childPolicy = childPolicy;
    this.helper = checkNotNull(helper, "helper");
    this.localityStore = checkNotNull(localityStore, "localityStore");
    this.channel = checkNotNull(channel, "channel");
    this.adsStreamCallback = checkNotNull(adsStreamCallback, "adsStreamCallback");
  }

  final void handleResolvedAddressGroups(
      List<EquivalentAddressGroup> servers, Attributes attributes) {

    // start XdsComms if not already alive
    if (xdsComms != null) {
      xdsComms.refreshAdsStream();
    } else {
      xdsComms = new XdsComms(channel, helper, adsStreamCallback, localityStore);
    }

    // TODO: maybe update picker
  }

  final void handleNameResolutionError(Status error) {
    // NO-OP?
  }

  final void handleSubchannelState(Subchannel subchannel, ConnectivityStateInfo newState) {
    // TODO: maybe update picker
    localityStore.handleSubchannelState(subchannel, newState);
  }

  ManagedChannel shutdownAndReleaseChannel(String message) {
    localityStore.reset();
    if (xdsComms != null) {
      xdsComms.shutdownLbRpc(message);
      xdsComms = null;
    }
    return channel;
  }
}
