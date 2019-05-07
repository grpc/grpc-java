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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;
import io.envoyproxy.envoy.api.v2.core.HealthStatus;
import io.envoyproxy.envoy.api.v2.core.SocketAddress;
import io.grpc.Attributes;
import io.grpc.ConnectivityStateInfo;
import io.grpc.EquivalentAddressGroup;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancer.Subchannel;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.internal.ServiceConfigUtil.LbConfig;
import io.grpc.xds.XdsComms.AdsStreamCallback;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
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

  private final LocalityStore subchannelStore;
  private final Helper helper;
  private final AdsStreamCallback adsStreamCallback;

  @Nullable
  private XdsComms xdsComms;

  XdsLbState(
      String balancerName,
      @Nullable LbConfig childPolicy,
      @Nullable XdsComms xdsComms,
      Helper helper,
      LocalityStore subchannelStore,
      AdsStreamCallback adsStreamCallback) {
    this.balancerName = checkNotNull(balancerName, "balancerName");
    this.childPolicy = childPolicy;
    this.xdsComms = xdsComms;
    this.helper = checkNotNull(helper, "helper");
    this.subchannelStore = checkNotNull(subchannelStore, "subchannelStore");
    this.adsStreamCallback = checkNotNull(adsStreamCallback, "adsStreamCallback");
  }

  final void handleResolvedAddressGroups(
      List<EquivalentAddressGroup> servers, Attributes attributes) {

    // start XdsComms if not already alive
    if (xdsComms != null) {
      xdsComms.refreshAdsStream();
    } else {
      ManagedChannel oobChannel = helper.createResolvingOobChannel(balancerName);
      xdsComms = new XdsComms(oobChannel, helper, adsStreamCallback, subchannelStore);
    }

    // TODO: maybe update picker
  }

  final void handleNameResolutionError(Status error) {
    if (!subchannelStore.hasNonDropBackends()) {
      // TODO: maybe update picker with transient failure
    }
  }

  final void handleSubchannelState(Subchannel subchannel, ConnectivityStateInfo newState) {
    // TODO: maybe update picker
    subchannelStore.handleSubchannelState(subchannel, newState);
  }

  /**
   * Shuts down subchannels and child loadbalancers, and cancels retry timer.
   */
  void shutdown() {
    // TODO: cancel retry timer
    // TODO: shutdown child balancers
    subchannelStore.shutdown();
  }

  @Nullable
  final XdsComms shutdownAndReleaseXdsComms() {
    shutdown();
    XdsComms xdsComms = this.xdsComms;
    this.xdsComms = null;
    return xdsComms;
  }

  static final class Locality {
    final String region;
    final String zone;
    final String subzone;

    Locality(io.envoyproxy.envoy.api.v2.core.Locality locality) {
      this(
          /* region = */ locality.getRegion(),
          /* zone = */ locality.getZone(),
          /* subzone = */ locality.getSubZone());
    }

    @VisibleForTesting
    Locality(String region, String zone, String subzone) {
      this.region = region;
      this.zone = zone;
      this.subzone = subzone;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      Locality locality = (Locality) o;
      return Objects.equal(region, locality.region)
          && Objects.equal(zone, locality.zone)
          && Objects.equal(subzone, locality.subzone);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(region, zone, subzone);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("region", region)
          .add("zone", zone)
          .add("subzone", subzone)
          .toString();
    }
  }

  /**
   * Information about the locality from EDS response.
   */
  static final class LocalityInfo {
    final List<EquivalentAddressGroup> eags;
    final List<Integer> endPointWeights;
    final int localityWeight;

    LocalityInfo(Collection<LbEndpoint> lbEndPoints, int localityWeight) {
      List<EquivalentAddressGroup> eags = new ArrayList<>(lbEndPoints.size());
      List<Integer> endPointWeights = new ArrayList<>(lbEndPoints.size());
      for (LbEndpoint lbEndPoint : lbEndPoints) {
        // not sure what to do with lbEndPoint.healthStatus yet
        eags.add(lbEndPoint.eag);
        endPointWeights.add(lbEndPoint.endPointWeight);
      }
      this.eags = Collections.unmodifiableList(eags);
      this.endPointWeights = Collections.unmodifiableList(new ArrayList<>(endPointWeights));
      this.localityWeight = localityWeight;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      LocalityInfo that = (LocalityInfo) o;
      return localityWeight == that.localityWeight
          && Objects.equal(eags, that.eags)
          && Objects.equal(endPointWeights, that.endPointWeights);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(eags, endPointWeights, localityWeight);
    }
  }

  static final class LbEndpoint {
    final EquivalentAddressGroup eag;
    final int endPointWeight;
    final HealthStatus healthStatus;

    LbEndpoint(io.envoyproxy.envoy.api.v2.endpoint.LbEndpoint lbEndpointProto) {

      this(
          new EquivalentAddressGroup(ImmutableList.of(fromEnvoyProtoAddress(lbEndpointProto))),
          lbEndpointProto.getLoadBalancingWeight().getValue(),
          lbEndpointProto.getHealthStatus());
    }

    @VisibleForTesting
    LbEndpoint(EquivalentAddressGroup eag, int endPointWeight, HealthStatus healthStatus) {
      this.eag = eag;
      this.endPointWeight = endPointWeight;
      this.healthStatus = healthStatus;
    }

    private static java.net.SocketAddress fromEnvoyProtoAddress(
        io.envoyproxy.envoy.api.v2.endpoint.LbEndpoint lbEndpointProto) {
      SocketAddress socketAddress = lbEndpointProto.getEndpoint().getAddress().getSocketAddress();
      return new InetSocketAddress(socketAddress.getAddress(), socketAddress.getPortValue());
    }
  }

}
