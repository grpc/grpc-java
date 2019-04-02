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
import static io.grpc.ConnectivityState.CONNECTING;
import static io.grpc.ConnectivityState.IDLE;
import static io.grpc.ConnectivityState.READY;
import static io.grpc.ConnectivityState.TRANSIENT_FAILURE;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.envoyproxy.envoy.api.v2.core.HealthStatus;
import io.envoyproxy.envoy.api.v2.core.SocketAddress;
import io.grpc.Attributes;
import io.grpc.ChannelLogger.ChannelLogLevel;
import io.grpc.ConnectivityState;
import io.grpc.ConnectivityStateInfo;
import io.grpc.EquivalentAddressGroup;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancer.PickResult;
import io.grpc.LoadBalancer.PickSubchannelArgs;
import io.grpc.LoadBalancer.ResolvedAddresses;
import io.grpc.LoadBalancer.Subchannel;
import io.grpc.LoadBalancer.SubchannelPicker;
import io.grpc.LoadBalancerProvider;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.internal.ServiceConfigUtil.LbConfig;
import io.grpc.util.ForwardingLoadBalancerHelper;
import io.grpc.xds.XdsComms.AdsStreamCallback;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

  private final SubchannelStore subchannelStore;
  private final Helper helper;
  private final AdsStreamCallback adsStreamCallback;

  @Nullable
  private XdsComms xdsComms;

  XdsLbState(
      String balancerName,
      @Nullable LbConfig childPolicy,
      @Nullable XdsComms xdsComms,
      Helper helper,
      SubchannelStore subchannelStore,
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
      // ** This is wrong **
      // FIXME: use name resolver to resolve addresses for balancerName, and create xdsComms in
      // name resolver listener callback.
      // TODO: consider pass a fake EAG as a static final field visible to tests and verify
      // createOobChannel() with this EAG in tests.
      ManagedChannel oobChannel = helper.createOobChannel(
          new EquivalentAddressGroup(ImmutableList.<java.net.SocketAddress>of(
              new java.net.SocketAddress() {})),
          balancerName);
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

  @VisibleForTesting // Interface of weighted Round-Robin algorithm that is convenient for test.
  interface WrrAlgorithm {
    @Nullable
    Locality pickLocality(List<LocalityState> wrrList);
  }

  private static final class WrrAlgorithmImpl implements WrrAlgorithm {

    @Override
    public Locality pickLocality(List<LocalityState> wrrList) {
      if (wrrList.isEmpty()) {
        return null;
      }
      return wrrList.iterator().next().locality;
    }
  }

  static final class LocalityPicker {
    private final List<LocalityState> wrrList;
    private final WrrAlgorithm wrrAlgorithm;

    LocalityPicker(List<LocalityState> wrrList, WrrAlgorithm wrrAlgorithm) {
      this.wrrList = wrrList;
      this.wrrAlgorithm = wrrAlgorithm;
    }

    @Nullable
    Locality pickLocality() {
      return wrrAlgorithm.pickLocality(wrrList);
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      LocalityPicker that = (LocalityPicker) o;
      return Objects.equal(wrrList, that.wrrList);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(wrrList);
    }
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
   * State about the locality for WRR locality picker.
   */
  static final class LocalityState {
    final Locality locality;
    final int weight;
    @Nullable // null means the subchannel state is not updated at the moment yet
    @SuppressWarnings("unused") // TODO: use it for locality picker
    final ConnectivityState state;

    LocalityState(Locality locality, int weight, @Nullable ConnectivityState state) {
      this.locality = locality;
      this.weight = weight;
      this.state = state;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      LocalityState that = (LocalityState) o;
      return weight == that.weight
          && Objects.equal(locality, that.locality)
          && state == that.state;
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(locality, weight, state);
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


  static final class XdsPicker extends SubchannelPicker {

    private LocalityPicker localityPicker;
    private final Map<Locality, SubchannelPicker> childPickers;

    private static final XdsPicker BUFFER_PICKER = new XdsPicker(null, null);

    XdsPicker(LocalityPicker localityPicker, Map<Locality, SubchannelPicker> childPickers) {
      this.localityPicker = localityPicker;
      this.childPickers = childPickers;
    }

    @Override
    public PickResult pickSubchannel(PickSubchannelArgs args) {
      Locality locality = localityPicker.pickLocality();
      if (locality == null) {
        return PickResult.withNoResult();
      }
      return childPickers.get(localityPicker.pickLocality()).pickSubchannel(args);
    }
  }

  /**
   * Manages EAG and locality info for a collection of subchannels, not including subchannels
   * created by the fallback balancer.
   */
  static final class SubchannelStoreImpl implements SubchannelStore {
    private static final SubchannelPicker BUFFER_PICKER = new SubchannelPicker() {
      @Override
      public PickResult pickSubchannel(PickSubchannelArgs args) {
        return PickResult.withNoResult();
      }

      @Override
      public String toString() {
        return "BUFFER_PICKER";
      }
    };
    private static final WrrAlgorithm wrrAlgorithm = new WrrAlgorithmImpl();

    private final SubchannelPool subchannelPool;
    private final Helper helper;
    private final WrrAlgorithm wrrAlgo;

    private XdsPicker currentPicker = XdsPicker.BUFFER_PICKER;
    private ConnectivityState currentState;

    private Map<Locality, IntraLocalitySubchannelStore> localityStore = new HashMap<>();
    private LoadBalancerProvider loadBalancerProvider;
    private boolean shutdown;

    @VisibleForTesting
    SubchannelStoreImpl(Helper helper, SubchannelPool subchannelPool, WrrAlgorithm wrrAlgo) {
      this.helper = helper;
      this.subchannelPool = subchannelPool;
      this.wrrAlgo = wrrAlgo;
    }

    SubchannelStoreImpl(Helper helper, SubchannelPool subchannelPool) {
      this(helper, subchannelPool, wrrAlgorithm);
    }

    @Override
    public boolean hasReadyBackends() {
      for (IntraLocalitySubchannelStore localitySubchannels : localityStore.values()) {
        for (ConnectivityStateInfo state : localitySubchannels.subchannels.values()) {
          if (state != null && state.getState() == ConnectivityState.READY) {
            return true;
          }
        }
      }
      return false;
    }

    @Override
    public boolean hasNonDropBackends() {
      // TODO: impl
      return false;
    }

    // This is triggered by xdsLoadbalancer.handleSubchannelState
    @Override
    public void handleSubchannelState(Subchannel subchannel, ConnectivityStateInfo newState) {
      // just in case the subchannel is in the pool
      subchannelPool.handleSubchannelState(subchannel, newState);

      // delegate to the childBalancer who manages this subchannel
      for (IntraLocalitySubchannelStore intraLocalitySubchannelStore : localityStore.values()) {
        if (intraLocalitySubchannelStore.subchannels.keySet().contains(subchannel)) {
          intraLocalitySubchannelStore.subchannels.put(subchannel, newState);

          // This will probably trigger childHelper.updateBalancingState
          intraLocalitySubchannelStore.childBalancer.handleSubchannelState(subchannel, newState);
          return;
        }
      }
    }

    @Override
    public void shutdown() {
      shutdown = true;
      for (IntraLocalitySubchannelStore subchannels : localityStore.values()) {
        subchannels.shutdown();
      }
      localityStore = ImmutableMap.of();
    }

    // This is triggered by EDS response.
    @Override
    public void updateLocalityStore(Map<Locality, LocalityInfo> localityInfoMap) {
      if (shutdown) {
        return;
      }

      Set<Locality> oldLocalities = localityStore.keySet();
      Set<Locality> newLocalities = localityInfoMap.keySet();

      for (Locality oldLocality : oldLocalities) {
        if (!newLocalities.contains(oldLocality)) {
          // No graceful transition until a high-level lb graceful transition design is available.
          localityStore.get(oldLocality).shutdown();
          localityStore.remove(oldLocality);
          if (localityStore.isEmpty()) {
            // down-size the map
            localityStore = new HashMap<>();
          }
        }
      }

      ConnectivityState newState = null;
      List<LocalityState> localityStates = new ArrayList<>(newLocalities.size());
      Map<Locality, SubchannelPicker> childPickers = new HashMap<>();
      for (Locality newLocality : newLocalities) {

        // Assuming standard mode only (EDS response with a list of endpoints) for now
        List<EquivalentAddressGroup> newEags = localityInfoMap.get(newLocality).eags;
        IntraLocalitySubchannelStore intraLocalitySubchannelStore;
        if (oldLocalities.contains(newLocality)) {
          intraLocalitySubchannelStore = localityStore.get(newLocality);
        } else {
          intraLocalitySubchannelStore =
              new IntraLocalitySubchannelStore(newLocality, loadBalancerProvider);
          localityStore.put(newLocality, intraLocalitySubchannelStore);
        }
        // TODO: put endPointWeights into attributes for WRR.
        intraLocalitySubchannelStore.childBalancer
            .handleResolvedAddresses(
                ResolvedAddresses.newBuilder().setServers(newEags).build());

        LocalityState localityState =
            new LocalityState(
                newLocality,
                localityInfoMap.get(newLocality).localityWeight,
                intraLocalitySubchannelStore.currentChildState);
        localityStates.add(localityState);
        childPickers.put(newLocality, intraLocalitySubchannelStore.currentChildPicker);
        newState = aggregateState(newState, intraLocalitySubchannelStore.currentChildState);
      }

      localityStates = Collections.unmodifiableList(localityStates);
      LocalityPicker localityPicker = new LocalityPicker(localityStates, wrrAlgo);
      if (!localityPicker.equals(currentPicker.localityPicker)) {
        childPickers = Collections.unmodifiableMap(childPickers);
        XdsPicker newXdsPicker = new XdsPicker(localityPicker, childPickers);
        updatePicker(newState, newXdsPicker);
      }
    }

    private static ConnectivityState aggregateState(
        ConnectivityState overallState, ConnectivityState childState) {
      if (overallState == READY || childState == READY) {
        return READY;
      }
      if (overallState == CONNECTING || childState == CONNECTING) {
        return CONNECTING;
      }
      if (overallState == IDLE || childState == IDLE) {
        return IDLE;
      }
      if (childState == TRANSIENT_FAILURE) {
        return TRANSIENT_FAILURE;
      }
      return overallState;
    }

    @Override
    public void updateLoadBalancerProvider(LoadBalancerProvider loadBalancerProvider) {
      if (shutdown) {
        return;
      }
      this.loadBalancerProvider = loadBalancerProvider;
    }

    /**
     * Update the given picker to the helper if it's different from the current one.
     */
    private void updatePicker(ConnectivityState state, XdsPicker picker) {
      currentPicker = picker;
      currentState = state;
      helper.getChannelLogger().log(
            ChannelLogLevel.INFO, "Picker updated - state: {0}, picker: {1}", state, picker);
      if (state != null) {
        helper.updateBalancingState(state, picker);
      }
    }

    private void updateChildState(
        Locality locality, ConnectivityState newChildState, SubchannelPicker newChildPicker) {
      if (currentPicker.childPickers.containsKey(locality)) {
        ConnectivityState overallState = aggregateState(currentState, newChildState);

        List<LocalityState> localityStates =
            new ArrayList<>(currentPicker.localityPicker.wrrList);
        for (int i = 0; i < localityStates.size(); i++) {
          LocalityState curLocality = localityStates.get(i);
          if (curLocality.locality.equals(locality)) {
            localityStates.set(i, new LocalityState(locality, curLocality.weight, newChildState));
          }
        }
        localityStates = Collections.unmodifiableList(localityStates);

        Map<Locality, SubchannelPicker> childPickers = new HashMap<>(currentPicker.childPickers);
        childPickers.put(locality, newChildPicker);
        childPickers = Collections.unmodifiableMap(childPickers);

        XdsPicker xdsPicker =
            new XdsPicker(new LocalityPicker(localityStates, wrrAlgo), childPickers);
        updatePicker(overallState, xdsPicker);
      }
    }

    /**
     * SubchannelStore for a single Locality.
     */
    final class IntraLocalitySubchannelStore {
      final Locality locality;
      final LoadBalancer childBalancer;

      SubchannelPicker currentChildPicker = BUFFER_PICKER;

      @Nullable // null means the subchannel state is not updated at the moment yet
      ConnectivityState currentChildState;

      // Set of subchannels in this locality, with additional state info for CachedSubchannelPool
      private Map<Subchannel, ConnectivityStateInfo> subchannels = new HashMap<>();

      IntraLocalitySubchannelStore(Locality locality, LoadBalancerProvider loadBalancerProvider) {
        this.locality = locality;
        this.childBalancer = checkNotNull(loadBalancerProvider, "loadBalancerProvider")
            .newLoadBalancer(new ChildHelper());
      }

      void shutdown() {
        childBalancer.shutdown();
        for (Map.Entry<Subchannel, ConnectivityStateInfo> subchannel : subchannels.entrySet()) {
          ConnectivityStateInfo stateInfo = subchannel.getValue();
          if (stateInfo == null) {
            stateInfo = ConnectivityStateInfo.forNonError(IDLE);
          }
          subchannelPool.returnSubchannel(subchannel.getKey(), stateInfo);
        }
        subchannels = ImmutableMap.of();
      }

      class ChildHelper extends ForwardingLoadBalancerHelper {

        @Override
        protected Helper delegate() {
          return helper;
        }

        @Override
        public Subchannel createSubchannel(List<EquivalentAddressGroup> addrs, Attributes attrs) {

          // delegate to parent helper
          Subchannel subchannel = subchannelPool.takeOrCreateSubchannel(addrs, attrs);

          if (!subchannels.containsKey(subchannel)) {
            subchannels.put(subchannel, null); // its state will be updated later
          }
          return subchannel;
        }

        // This is triggered by child balancer
        @Override
        public void updateBalancingState(ConnectivityState newState, SubchannelPicker newPicker) {
          checkNotNull(newState, "newState");
          checkNotNull(newPicker, "newPicker");

          currentChildState = newState;
          currentChildPicker = newPicker;

          // delegate to parent helper
          updateChildState(locality, newState, newPicker);
        }

        @Override
        public String toString() {
          return MoreObjects.toStringHelper(this).add("locality", locality).toString();
        }

        @Override
        public String getAuthority() {
          //FIXME: This should be a new proposed field of Locality, locality_name
          return locality.subzone;
        }
      }
    }
  }

  /**
   * The interface of {@link XdsLbState.SubchannelStoreImpl} that is convenient for testing.
   */
  // Must be accessed/run in SynchronizedContext.
  public interface SubchannelStore {

    boolean hasReadyBackends();

    boolean hasNonDropBackends();

    void handleSubchannelState(Subchannel subchannel, ConnectivityStateInfo newState);

    void shutdown();

    void updateLocalityStore(Map<Locality, LocalityInfo> localityInfoMap);

    void updateLoadBalancerProvider(LoadBalancerProvider loadBalancerProvider);
  }
}
