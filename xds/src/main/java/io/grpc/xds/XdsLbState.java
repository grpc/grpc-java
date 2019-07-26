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

import io.grpc.ConnectivityStateInfo;
import io.grpc.LoadBalancer;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.internal.BackoffPolicy;
import io.grpc.internal.GrpcUtil;
import io.grpc.xds.XdsComms.AdsStreamCallback;
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
final class XdsLbState extends LoadBalancer {

  private final LocalityStore localityStore;
  private final Helper helper;
  private final ManagedChannel channel;
  private final AdsStreamCallback adsStreamCallback;
  private final BackoffPolicy.Provider backoffPolicyProvider;

  @Nullable
  private XdsComms xdsComms;

  XdsLbState(
      Helper helper,
      LocalityStore localityStore,
      ManagedChannel channel,
      AdsStreamCallback adsStreamCallback,
      BackoffPolicy.Provider backoffPolicyProvider) {
    this.helper = checkNotNull(helper, "helper");
    this.localityStore = checkNotNull(localityStore, "localityStore");
    this.channel = checkNotNull(channel, "channel");
    this.adsStreamCallback = checkNotNull(adsStreamCallback, "adsStreamCallback");
    this.backoffPolicyProvider = checkNotNull(backoffPolicyProvider, "backoffPolicyProvider");
  }

  @Override
  public void handleResolvedAddresses(ResolvedAddresses resolvedAddresses) {

    // start XdsComms if not already alive
    if (xdsComms != null) {
      xdsComms.refreshAdsStream();
    } else {
      // TODO(zdapeng): pass a helper that has the right ChannelLogger for the oobChannel
      xdsComms = new XdsComms(
          channel, helper, adsStreamCallback, localityStore, backoffPolicyProvider,
          GrpcUtil.STOPWATCH_SUPPLIER);
    }

    // TODO: maybe update picker
  }

  @Override
  public void handleNameResolutionError(Status error) {
    // NO-OP?
  }

  @Deprecated
  @Override
  public void handleSubchannelState(Subchannel subchannel, ConnectivityStateInfo newState) {
    // TODO: maybe update picker
    localityStore.handleSubchannelState(subchannel, newState);
  }

  @Override
  public void shutdown() {
    localityStore.reset();
    if (xdsComms != null) {
      xdsComms.shutdownXdsComms();
      xdsComms = null;
    }
  }
}
