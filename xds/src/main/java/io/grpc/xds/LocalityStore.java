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
import com.google.common.collect.ImmutableList;
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
import io.grpc.xds.ClientLoadCounter.LoadRecordingSubchannelPicker;
import io.grpc.xds.ClientLoadCounter.MetricsObservingSubchannelPicker;
import io.grpc.xds.ClientLoadCounter.MetricsRecordingListener;
import io.grpc.xds.InterLocalityPicker.WeightedChildPicker;
import io.grpc.xds.XdsComms.DropOverload;
import io.grpc.xds.XdsComms.LocalityInfo;
import io.grpc.xds.XdsSubchannelPickers.ErrorPicker;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * Manages EAG and locality info for a collection of subchannels, not including subchannels
 * created by the fallback balancer.
 */
// Must be accessed/run in SynchronizedContext.
interface LocalityStore {

  void reset();

  void updateLocalityStore(Map<XdsLocality, LocalityInfo> localityInfoMap);

  void updateDropPercentage(ImmutableList<DropOverload> dropOverloads);

  void handleSubchannelState(Subchannel subchannel, ConnectivityStateInfo newState);

  StatsStore getStatsStore();

  final class LocalityStoreImpl implements LocalityStore {
    private static final String ROUND_ROBIN = "round_robin";

    private final Helper helper;
    private final PickerFactory pickerFactory;
    private final LoadBalancerProvider loadBalancerProvider;
    private final ThreadSafeRandom random;
    private final StatsStore statsStore;
    private final OrcaPerRequestUtil orcaPerRequestUtil;

    private Map<XdsLocality, LocalityLbInfo> localityMap = new HashMap<>();
    private ImmutableList<DropOverload> dropOverloads = ImmutableList.of();

    LocalityStoreImpl(Helper helper, LoadBalancerRegistry lbRegistry) {
      this(helper, pickerFactoryImpl, lbRegistry, ThreadSafeRandom.ThreadSafeRandomImpl.instance,
          new XdsLoadStatsStore(), OrcaPerRequestUtil.getInstance());
    }

    @VisibleForTesting
    LocalityStoreImpl(
        Helper helper,
        PickerFactory pickerFactory,
        LoadBalancerRegistry lbRegistry,
        ThreadSafeRandom random,
        StatsStore statsStore,
        OrcaPerRequestUtil orcaPerRequestUtil) {
      this.helper = checkNotNull(helper, "helper");
      this.pickerFactory = checkNotNull(pickerFactory, "pickerFactory");
      loadBalancerProvider = checkNotNull(
          lbRegistry.getProvider(ROUND_ROBIN),
          "Unable to find '%s' LoadBalancer", ROUND_ROBIN);
      this.random = checkNotNull(random, "random");
      this.statsStore = checkNotNull(statsStore, "statsStore");
      this.orcaPerRequestUtil = checkNotNull(orcaPerRequestUtil, "orcaPerRequestUtil");
    }

    @VisibleForTesting // Introduced for testing only.
    interface PickerFactory {
      SubchannelPicker picker(List<WeightedChildPicker> childPickers);
    }

    private static final class DroppablePicker extends SubchannelPicker {

      final ImmutableList<DropOverload> dropOverloads;
      final SubchannelPicker delegate;
      final ThreadSafeRandom random;
      final StatsStore statsStore;

      DroppablePicker(
          ImmutableList<DropOverload> dropOverloads, SubchannelPicker delegate,
          ThreadSafeRandom random, StatsStore statsStore) {
        this.dropOverloads = dropOverloads;
        this.delegate = delegate;
        this.random = random;
        this.statsStore = statsStore;
      }

      @Override
      public PickResult pickSubchannel(PickSubchannelArgs args) {
        for (DropOverload dropOverload : dropOverloads) {
          int rand = random.nextInt(1000_000);
          if (rand < dropOverload.dropsPerMillion) {
            statsStore.recordDroppedRequest(dropOverload.category);
            return PickResult.withDrop(Status.UNAVAILABLE.withDescription(
                "dropped by loadbalancer: " + dropOverload.toString()));
          }
        }
        return delegate.pickSubchannel(args);
      }

      @Override
      public String toString() {
        return MoreObjects.toStringHelper(this)
            .add("dropOverloads", dropOverloads)
            .add("delegate", delegate)
            .toString();
      }
    }

    private static final PickerFactory pickerFactoryImpl =
        new PickerFactory() {
          @Override
          public SubchannelPicker picker(List<WeightedChildPicker> childPickers) {
            return new InterLocalityPicker(childPickers);
          }
        };

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
      for (XdsLocality locality : localityMap.keySet()) {
        localityMap.get(locality).shutdown();
        statsStore.removeLocality(locality);
      }
      localityMap = new HashMap<>();
    }

    // This is triggered by EDS response.
    @Override
    public void updateLocalityStore(Map<XdsLocality, LocalityInfo> localityInfoMap) {
      Set<XdsLocality> oldLocalities = localityMap.keySet();
      Set<XdsLocality> newLocalities = localityInfoMap.keySet();

      final Set<XdsLocality> toRemove = new HashSet<>();
      Iterator<XdsLocality> iterator = oldLocalities.iterator();
      while (iterator.hasNext()) {
        XdsLocality oldLocality = iterator.next();
        if (!newLocalities.contains(oldLocality)) {
          toRemove.add(oldLocality);
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
      for (XdsLocality newLocality : newLocalities) {

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
          statsStore.addLocality(newLocality);
          childHelper = new ChildHelper(newLocality, statsStore.getLocalityCounter(newLocality));
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

      // There is a race between picking a subchannel and updating localities, which leads to
      // the possibility that RPCs will be sent to a removed locality. As a result, those RPC
      // loads will not be recorded. We consider this to be natural. By removing locality counters
      // after updating subchannel pickers, we eliminate the race and conservatively record loads
      // happening in that period.
      helper.getSynchronizationContext().execute(new Runnable() {
        @Override
        public void run() {
          for (XdsLocality locality : toRemove) {
            statsStore.removeLocality(locality);
          }
        }
      });
    }

    @Override
    public void updateDropPercentage(ImmutableList<DropOverload> dropOverloads) {
      this.dropOverloads = checkNotNull(dropOverloads, "dropOverloads");
    }

    @Override
    public StatsStore getStatsStore() {
      return statsStore;
    }

    @Nullable
    private static ConnectivityState aggregateState(
        @Nullable ConnectivityState overallState, @Nullable ConnectivityState childState) {
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
        XdsLocality locality, ConnectivityState newChildState, SubchannelPicker newChildPicker) {
      if (!localityMap.containsKey(locality)) {
        return;
      }

      List<WeightedChildPicker> childPickers = new ArrayList<>();

      ConnectivityState overallState = null;
      for (XdsLocality l : localityMap.keySet()) {
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
    }

    private void updatePicker(
        @Nullable ConnectivityState state,  List<WeightedChildPicker> childPickers) {
      childPickers = Collections.unmodifiableList(childPickers);
      SubchannelPicker picker;
      if (childPickers.isEmpty()) {
        if (state == TRANSIENT_FAILURE) {
          picker = new ErrorPicker(Status.UNAVAILABLE); // TODO: more details in status
        } else {
          picker = XdsSubchannelPickers.BUFFER_PICKER;
        }
      } else {
        picker = pickerFactory.picker(childPickers);
      }

      if (!dropOverloads.isEmpty()) {
        picker = new DroppablePicker(dropOverloads, picker, random, statsStore);
        if (state == null) {
          state = IDLE;
        }
      }

      if (state != null) {
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

      private final XdsLocality locality;
      private final ClientLoadCounter counter;

      private SubchannelPicker currentChildPicker = XdsSubchannelPickers.BUFFER_PICKER;
      private ConnectivityState currentChildState = null;

      ChildHelper(XdsLocality locality, ClientLoadCounter counter) {
        this.locality = checkNotNull(locality, "locality");
        this.counter = checkNotNull(counter, "counter");
      }

      @Override
      protected Helper delegate() {
        return helper;
      }

      // This is triggered by child balancer
      @Override
      public void updateBalancingState(ConnectivityState newState,
          final SubchannelPicker newPicker) {
        checkNotNull(newState, "newState");
        checkNotNull(newPicker, "newPicker");

        currentChildState = newState;
        currentChildPicker =
            new LoadRecordingSubchannelPicker(counter,
                new MetricsObservingSubchannelPicker(new MetricsRecordingListener(counter),
                    newPicker, orcaPerRequestUtil));

        // delegate to parent helper
        updateChildState(locality, newState, currentChildPicker);
      }

      @Override
      public String toString() {
        return MoreObjects.toStringHelper(this).add("locality", locality).toString();
      }

      @Override
      public String getAuthority() {
        //FIXME: This should be a new proposed field of Locality, locality_name
        return locality.getSubzone();
      }
    }
  }
}
