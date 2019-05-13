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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static io.grpc.ConnectivityState.CONNECTING;
import static io.grpc.ConnectivityState.IDLE;
import static io.grpc.ConnectivityState.READY;
import static io.grpc.ConnectivityState.TRANSIENT_FAILURE;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
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
import io.grpc.LoadBalancerRegistry;
import io.grpc.Status;
import io.grpc.util.ForwardingLoadBalancerHelper;
import io.grpc.xds.InterLocalityPicker.WeightedChildPicker;
import io.grpc.xds.XdsComms.Locality;
import io.grpc.xds.XdsComms.LocalityInfo;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Manages EAG and locality info for a collection of subchannels, not including subchannels
 * created by the fallback balancer.
 */
// Must be accessed/run in SynchronizedContext.
interface LocalityStore {

  boolean hasReadyBackends();

  boolean hasNonDropBackends();

  void reset();

  void updateLocalityStore(Map<Locality, LocalityInfo> localityInfoMap);

  void handleSubchannelState(Subchannel subchannel, ConnectivityStateInfo newState);

  final class LocalityStoreImpl implements LocalityStore {
    private static final String ROUND_ROBIN = "round_robin";

    private final Helper helper;
    private final PickerFactory pickerFactory;

    private Map<Locality, LocalityLbInfo> localityMap = new HashMap<>();
    private LoadBalancerProvider loadBalancerProvider;
    private ConnectivityState overallState;

    LocalityStoreImpl(Helper helper, LoadBalancerRegistry lbRegistry) {
      this(helper, pickerFactoryImpl, lbRegistry);
    }

    @VisibleForTesting
    LocalityStoreImpl(Helper helper, PickerFactory pickerFactory, LoadBalancerRegistry lbRegistry) {
      this.helper = helper;
      this.pickerFactory = pickerFactory;
      loadBalancerProvider = checkNotNull(
          lbRegistry.getProvider(ROUND_ROBIN),
          "Unable to find '%s' LoadBalancer", ROUND_ROBIN);
    }

    @VisibleForTesting // Introduced for testing only.
    interface PickerFactory {
      SubchannelPicker picker(List<WeightedChildPicker> childPickers);
    }

    private static final PickerFactory pickerFactoryImpl =
        new PickerFactory() {
          @Override
          public SubchannelPicker picker(List<WeightedChildPicker> childPickers) {
            return new InterLocalityPicker(childPickers);
          }
        };

    @Override
    public boolean hasReadyBackends() {
      return overallState == READY;
    }

    @Override
    public boolean hasNonDropBackends() {
      // TODO: impl
      return false;
    }

    // This is triggered by xdsLoadbalancer.handleSubchannelState
    @Override
    public void handleSubchannelState(Subchannel subchannel, ConnectivityStateInfo newState) {
      // delegate to the childBalancer who manages this subchannel
      for (LocalityLbInfo localityLbInfo : localityMap.values()) {
        // This will probably trigger childHelper.updateBalancingState
        localityLbInfo.childBalancer.handleSubchannelState(subchannel, newState);
      }
    }

    @Override
    public void reset() {
      for (LocalityLbInfo localityLbInfo : localityMap.values()) {
        localityLbInfo.shutdown();
      }
      localityMap = new HashMap<>();
    }

    // This is triggered by EDS response.
    @Override
    public void updateLocalityStore(Map<Locality, LocalityInfo> localityInfoMap) {
      Set<Locality> oldLocalities = localityMap.keySet();
      Set<Locality> newLocalities = localityInfoMap.keySet();

      Iterator<Locality> iterator = oldLocalities.iterator();
      while (iterator.hasNext()) {
        Locality oldLocality = iterator.next();
        if (!newLocalities.contains(oldLocality)) {
          // No graceful transition until a high-level lb graceful transition design is available.
          localityMap.get(oldLocality).shutdown();
          iterator.remove();
          if (localityMap.isEmpty()) {
            // down-size the map
            localityMap = new HashMap<>();
          }
        }
      }

      ConnectivityState newState = null;
      List<WeightedChildPicker> childPickers = new ArrayList<>(newLocalities.size());
      for (Locality newLocality : newLocalities) {

        // Assuming standard mode only (EDS response with a list of endpoints) for now
        List<EquivalentAddressGroup> newEags = localityInfoMap.get(newLocality).eags;
        LocalityLbInfo localityLbInfo;
        ChildHelper childHelper;
        if (oldLocalities.contains(newLocality)) {
          LocalityLbInfo oldLocalityLbInfo
              = localityMap.get(newLocality);
          childHelper = oldLocalityLbInfo.childHelper;
          localityLbInfo = new LocalityLbInfo(
              localityInfoMap.get(newLocality).localityWeight,
              oldLocalityLbInfo.childBalancer,
              childHelper);
        } else {
          childHelper = new ChildHelper(newLocality);
          localityLbInfo =
              new LocalityLbInfo(
                  localityInfoMap.get(newLocality).localityWeight,
                  loadBalancerProvider.newLoadBalancer(childHelper),
                  childHelper);
          localityMap.put(newLocality, localityLbInfo);
        }
        // TODO: put endPointWeights into attributes for WRR.
        localityLbInfo.childBalancer
            .handleResolvedAddresses(
                ResolvedAddresses.newBuilder().setAddresses(newEags).build());

        if (localityLbInfo.childHelper.currentChildState == READY) {
          childPickers.add(
              new WeightedChildPicker(
                  localityInfoMap.get(newLocality).localityWeight,
                  localityLbInfo.childHelper.currentChildPicker));
        }
        newState = aggregateState(newState, childHelper.currentChildState);
      }

      updatePicker(newState, childPickers);

    }

    private static final class ErrorPicker extends SubchannelPicker {

      final Status error;

      ErrorPicker(Status error) {
        this.error = checkNotNull(error, "error");
      }

      @Override
      public PickResult pickSubchannel(PickSubchannelArgs args) {
        return PickResult.withError(error);
      }
    }

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

    private static ConnectivityState aggregateState(
        ConnectivityState overallState, ConnectivityState childState) {
      if (overallState == null) {
        return childState;
      }
      if (overallState == READY || childState == READY) {
        return READY;
      }
      if (overallState == CONNECTING || childState == CONNECTING) {
        return CONNECTING;
      }
      if (overallState == IDLE || childState == IDLE) {
        return IDLE;
      }
      return overallState;
    }

    private void updateChildState(
        Locality locality, ConnectivityState newChildState, SubchannelPicker newChildPicker) {
      if (!localityMap.containsKey(locality)) {
        return;
      }

      List<WeightedChildPicker> childPickers = new ArrayList<>();

      ConnectivityState overallState = null;
      for (Locality l : localityMap.keySet()) {
        LocalityLbInfo localityLbInfo = localityMap.get(l);
        ConnectivityState childState;
        SubchannelPicker childPicker;
        if (l.equals(locality)) {
          childState = newChildState;
          childPicker = newChildPicker;
        } else {
          childState = localityLbInfo.childHelper.currentChildState;
          childPicker = localityLbInfo.childHelper.currentChildPicker;
        }
        overallState = aggregateState(overallState, childState);

        if (READY == childState) {
          childPickers.add(
              new WeightedChildPicker(localityLbInfo.localityWeight, childPicker));
        }
      }

      updatePicker(overallState, childPickers);
      this.overallState = overallState;
    }

    private void updatePicker(ConnectivityState state,  List<WeightedChildPicker> childPickers) {
      childPickers = Collections.unmodifiableList(childPickers);
      SubchannelPicker picker;
      if (childPickers.isEmpty()) {
        if (state == TRANSIENT_FAILURE) {
          picker = new ErrorPicker(Status.UNAVAILABLE); // TODO: more details in status
        } else {
          picker = BUFFER_PICKER;
        }
      } else {
        picker = pickerFactory.picker(childPickers);
      }
      if (state != null) {
        helper.getChannelLogger().log(
            ChannelLogLevel.INFO, "Picker updated - state: {0}, picker: {1}", state, picker);
        helper.updateBalancingState(state, picker);
      }
    }

    /**
     * State of a single Locality.
     */
    static final class LocalityLbInfo {

      final int localityWeight;
      final LoadBalancer childBalancer;
      final ChildHelper childHelper;

      LocalityLbInfo(
          int localityWeight, LoadBalancer childBalancer, ChildHelper childHelper) {
        checkArgument(localityWeight >= 0, "localityWeight must be non-negative");
        this.localityWeight = localityWeight;
        this.childBalancer = checkNotNull(childBalancer, "childBalancer");
        this.childHelper = checkNotNull(childHelper, "childHelper");
      }

      void shutdown() {
        childBalancer.shutdown();
      }
    }

    class ChildHelper extends ForwardingLoadBalancerHelper {

      private final Locality locality;

      private SubchannelPicker currentChildPicker = BUFFER_PICKER;
      private ConnectivityState currentChildState = null;

      ChildHelper(Locality locality) {
        this.locality = checkNotNull(locality, "locality");
      }

      @Override
      protected Helper delegate() {
        return helper;
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
