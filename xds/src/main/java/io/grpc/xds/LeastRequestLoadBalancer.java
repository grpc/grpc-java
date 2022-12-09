/*
 * Copyright 2021 The gRPC Authors
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
import static io.grpc.ConnectivityState.SHUTDOWN;
import static io.grpc.ConnectivityState.TRANSIENT_FAILURE;
import static io.grpc.xds.LeastRequestLoadBalancerProvider.DEFAULT_CHOICE_COUNT;
import static io.grpc.xds.LeastRequestLoadBalancerProvider.MAX_CHOICE_COUNT;
import static io.grpc.xds.LeastRequestLoadBalancerProvider.MIN_CHOICE_COUNT;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import io.grpc.Attributes;
import io.grpc.ClientStreamTracer;
import io.grpc.ClientStreamTracer.StreamInfo;
import io.grpc.ConnectivityState;
import io.grpc.ConnectivityStateInfo;
import io.grpc.EquivalentAddressGroup;
import io.grpc.LoadBalancer;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.xds.ThreadSafeRandom.ThreadSafeRandomImpl;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nonnull;

/**
 * A {@link LoadBalancer} that provides least request load balancing based on
 * outstanding request counters.
 * It works by sampling a number of subchannels and picking the one with the
 * fewest amount of outstanding requests.
 * The default sampling amount of two is also known as
 * the "power of two choices" (P2C).
 */
final class LeastRequestLoadBalancer extends LoadBalancer {
  @VisibleForTesting
  static final Attributes.Key<Ref<ConnectivityStateInfo>> STATE_INFO =
      Attributes.Key.create("state-info");
  @VisibleForTesting
  static final Attributes.Key<AtomicInteger> IN_FLIGHTS =
      Attributes.Key.create("in-flights");

  private final Helper helper;
  private final ThreadSafeRandom random;
  private final Map<EquivalentAddressGroup, Subchannel> subchannels =
      new HashMap<>();

  private ConnectivityState currentState;
  private LeastRequestPicker currentPicker = new EmptyPicker(EMPTY_OK);
  private int choiceCount = DEFAULT_CHOICE_COUNT;

  LeastRequestLoadBalancer(Helper helper) {
    this(helper, ThreadSafeRandomImpl.instance);
  }

  @VisibleForTesting
  LeastRequestLoadBalancer(Helper helper, ThreadSafeRandom random) {
    this.helper = checkNotNull(helper, "helper");
    this.random = checkNotNull(random, "random");
  }

  @Override
  public boolean acceptResolvedAddresses(ResolvedAddresses resolvedAddresses) {
    if (resolvedAddresses.getAddresses().isEmpty()) {
      handleNameResolutionError(Status.UNAVAILABLE.withDescription(
          "NameResolver returned no usable address. addrs=" + resolvedAddresses.getAddresses()
              + ", attrs=" + resolvedAddresses.getAttributes()));
      return false;
    }
    LeastRequestConfig config =
        (LeastRequestConfig) resolvedAddresses.getLoadBalancingPolicyConfig();
    // Config may be null if least_request is used outside xDS
    if (config != null) {
      choiceCount = config.choiceCount;
    }

    List<EquivalentAddressGroup> servers = resolvedAddresses.getAddresses();
    Set<EquivalentAddressGroup> currentAddrs = subchannels.keySet();
    Map<EquivalentAddressGroup, EquivalentAddressGroup> latestAddrs = stripAttrs(servers);
    Set<EquivalentAddressGroup> removedAddrs = setsDifference(currentAddrs, latestAddrs.keySet());

    for (Map.Entry<EquivalentAddressGroup, EquivalentAddressGroup> latestEntry :
        latestAddrs.entrySet()) {
      EquivalentAddressGroup strippedAddressGroup = latestEntry.getKey();
      EquivalentAddressGroup originalAddressGroup = latestEntry.getValue();
      Subchannel existingSubchannel = subchannels.get(strippedAddressGroup);
      if (existingSubchannel != null) {
        // EAG's Attributes may have changed.
        existingSubchannel.updateAddresses(Collections.singletonList(originalAddressGroup));
        continue;
      }
      // Create new subchannels for new addresses.
      Attributes.Builder subchannelAttrs = Attributes.newBuilder()
          .set(STATE_INFO, new Ref<>(ConnectivityStateInfo.forNonError(IDLE)))
          // Used to track the in flight requests on this particular subchannel
          .set(IN_FLIGHTS, new AtomicInteger(0));

      final Subchannel subchannel = checkNotNull(
          helper.createSubchannel(CreateSubchannelArgs.newBuilder()
              .setAddresses(originalAddressGroup)
              .setAttributes(subchannelAttrs.build())
              .build()),
          "subchannel");
      subchannel.start(new SubchannelStateListener() {
        @Override
        public void onSubchannelState(ConnectivityStateInfo state) {
          processSubchannelState(subchannel, state);
        }
      });
      subchannels.put(strippedAddressGroup, subchannel);
      subchannel.requestConnection();
    }

    ArrayList<Subchannel> removedSubchannels = new ArrayList<>();
    for (EquivalentAddressGroup addressGroup : removedAddrs) {
      removedSubchannels.add(subchannels.remove(addressGroup));
    }

    // Update the picker before shutting down the subchannels, to reduce the chance of the race
    // between picking a subchannel and shutting it down.
    updateBalancingState();

    // Shutdown removed subchannels
    for (Subchannel removedSubchannel : removedSubchannels) {
      shutdownSubchannel(removedSubchannel);
    }

    return true;
  }

  @Override
  public void handleNameResolutionError(Status error) {
    if (currentState != READY)  {
      updateBalancingState(TRANSIENT_FAILURE, new EmptyPicker(error));
    }
  }

  private void processSubchannelState(Subchannel subchannel, ConnectivityStateInfo stateInfo) {
    if (subchannels.get(stripAttrs(subchannel.getAddresses())) != subchannel) {
      return;
    }
    if (stateInfo.getState() == TRANSIENT_FAILURE || stateInfo.getState() == IDLE) {
      helper.refreshNameResolution();
    }
    if (stateInfo.getState() == IDLE) {
      subchannel.requestConnection();
    }
    Ref<ConnectivityStateInfo> subchannelStateRef = getSubchannelStateInfoRef(subchannel);
    if (subchannelStateRef.value.getState().equals(TRANSIENT_FAILURE)) {
      if (stateInfo.getState().equals(CONNECTING) || stateInfo.getState().equals(IDLE)) {
        return;
      }
    }
    subchannelStateRef.value = stateInfo;
    updateBalancingState();
  }

  private void shutdownSubchannel(Subchannel subchannel) {
    subchannel.shutdown();
    getSubchannelStateInfoRef(subchannel).value =
        ConnectivityStateInfo.forNonError(SHUTDOWN);
  }

  @Override
  public void shutdown() {
    for (Subchannel subchannel : getSubchannels()) {
      shutdownSubchannel(subchannel);
    }
    subchannels.clear();
  }

  private static final Status EMPTY_OK = Status.OK.withDescription("no subchannels ready");

  /**
   * Updates picker with the list of active subchannels (state == READY).
   */
  @SuppressWarnings("ReferenceEquality")
  private void updateBalancingState() {
    List<Subchannel> activeList = filterNonFailingSubchannels(getSubchannels());
    if (activeList.isEmpty()) {
      // No READY subchannels, determine aggregate state and error status
      boolean isConnecting = false;
      Status aggStatus = EMPTY_OK;
      for (Subchannel subchannel : getSubchannels()) {
        ConnectivityStateInfo stateInfo = getSubchannelStateInfoRef(subchannel).value;
        // This subchannel IDLE is not because of channel IDLE_TIMEOUT,
        // in which case LB is already shutdown.
        // LRLB will request connection immediately on subchannel IDLE.
        if (stateInfo.getState() == CONNECTING || stateInfo.getState() == IDLE) {
          isConnecting = true;
        }
        if (aggStatus == EMPTY_OK || !aggStatus.isOk()) {
          aggStatus = stateInfo.getStatus();
        }
      }
      updateBalancingState(isConnecting ? CONNECTING : TRANSIENT_FAILURE,
          // If all subchannels are TRANSIENT_FAILURE, return the Status associated with
          // an arbitrary subchannel, otherwise return OK.
          new EmptyPicker(aggStatus));
    } else {
      updateBalancingState(READY, new ReadyPicker(activeList, choiceCount, random));
    }
  }

  private void updateBalancingState(ConnectivityState state, LeastRequestPicker picker) {
    if (state != currentState || !picker.isEquivalentTo(currentPicker)) {
      helper.updateBalancingState(state, picker);
      currentState = state;
      currentPicker = picker;
    }
  }

  /**
   * Filters out non-ready subchannels.
   */
  private static List<Subchannel> filterNonFailingSubchannels(
      Collection<Subchannel> subchannels) {
    List<Subchannel> readySubchannels = new ArrayList<>(subchannels.size());
    for (Subchannel subchannel : subchannels) {
      if (isReady(subchannel)) {
        readySubchannels.add(subchannel);
      }
    }
    return readySubchannels;
  }

  /**
   * Converts list of {@link EquivalentAddressGroup} to {@link EquivalentAddressGroup} set and
   * remove all attributes. The values are the original EAGs.
   */
  private static Map<EquivalentAddressGroup, EquivalentAddressGroup> stripAttrs(
      List<EquivalentAddressGroup> groupList) {
    Map<EquivalentAddressGroup, EquivalentAddressGroup> addrs = new HashMap<>(groupList.size() * 2);
    for (EquivalentAddressGroup group : groupList) {
      addrs.put(stripAttrs(group), group);
    }
    return addrs;
  }

  private static EquivalentAddressGroup stripAttrs(EquivalentAddressGroup eag) {
    return new EquivalentAddressGroup(eag.getAddresses());
  }

  @VisibleForTesting
  Collection<Subchannel> getSubchannels() {
    return subchannels.values();
  }

  private static Ref<ConnectivityStateInfo> getSubchannelStateInfoRef(
      Subchannel subchannel) {
    return checkNotNull(subchannel.getAttributes().get(STATE_INFO), "STATE_INFO");
  }

  private static AtomicInteger getInFlights(Subchannel subchannel) {
    return checkNotNull(subchannel.getAttributes().get(IN_FLIGHTS), "IN_FLIGHTS");
  }

  // package-private to avoid synthetic access
  static boolean isReady(Subchannel subchannel) {
    return getSubchannelStateInfoRef(subchannel).value.getState() == READY;
  }

  private static <T> Set<T> setsDifference(Set<T> a, Set<T> b) {
    Set<T> aCopy = new HashSet<>(a);
    aCopy.removeAll(b);
    return aCopy;
  }

  // Only subclasses are ReadyPicker or EmptyPicker
  private abstract static class LeastRequestPicker extends SubchannelPicker {
    abstract boolean isEquivalentTo(LeastRequestPicker picker);
  }

  @VisibleForTesting
  static final class ReadyPicker extends LeastRequestPicker {
    private final List<Subchannel> list; // non-empty
    private final int choiceCount;
    private final ThreadSafeRandom random;

    ReadyPicker(List<Subchannel> list, int choiceCount, ThreadSafeRandom random) {
      checkArgument(!list.isEmpty(), "empty list");
      this.list = list;
      this.choiceCount = choiceCount;
      this.random = checkNotNull(random, "random");
    }

    @Override
    public PickResult pickSubchannel(PickSubchannelArgs args) {
      final Subchannel subchannel = nextSubchannel();
      final OutstandingRequestsTracingFactory factory =
          new OutstandingRequestsTracingFactory(getInFlights(subchannel));
      return PickResult.withSubchannel(subchannel, factory);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(ReadyPicker.class)
                        .add("list", list)
                        .add("choiceCount", choiceCount)
                        .toString();
    }

    private Subchannel nextSubchannel() {
      Subchannel candidate = list.get(random.nextInt(list.size()));
      for (int i = 0; i < choiceCount - 1; ++i) {
        Subchannel sampled = list.get(random.nextInt(list.size()));
        if (getInFlights(sampled).get() < getInFlights(candidate).get()) {
          candidate = sampled;
        }
      }
      return candidate;
    }

    @VisibleForTesting
    List<Subchannel> getList() {
      return list;
    }

    @Override
    boolean isEquivalentTo(LeastRequestPicker picker) {
      if (!(picker instanceof ReadyPicker)) {
        return false;
      }
      ReadyPicker other = (ReadyPicker) picker;
      // the lists cannot contain duplicate subchannels
      return other == this
          || ((list.size() == other.list.size() && new HashSet<>(list).containsAll(other.list))
                && choiceCount == other.choiceCount);
    }
  }

  @VisibleForTesting
  static final class EmptyPicker extends LeastRequestPicker {

    private final Status status;

    EmptyPicker(@Nonnull Status status) {
      this.status = Preconditions.checkNotNull(status, "status");
    }

    @Override
    public PickResult pickSubchannel(PickSubchannelArgs args) {
      return status.isOk() ? PickResult.withNoResult() : PickResult.withError(status);
    }

    @Override
    boolean isEquivalentTo(LeastRequestPicker picker) {
      return picker instanceof EmptyPicker && (Objects.equal(status, ((EmptyPicker) picker).status)
          || (status.isOk() && ((EmptyPicker) picker).status.isOk()));
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(EmptyPicker.class).add("status", status).toString();
    }
  }

  /**
   * A lighter weight Reference than AtomicReference.
   */
  static final class Ref<T> {
    T value;

    Ref(T value) {
      this.value = value;
    }
  }

  private static final class OutstandingRequestsTracingFactory extends
      ClientStreamTracer.Factory {
    private final AtomicInteger inFlights;

    private OutstandingRequestsTracingFactory(AtomicInteger inFlights) {
      this.inFlights = checkNotNull(inFlights, "inFlights");
    }

    @Override
    public ClientStreamTracer newClientStreamTracer(StreamInfo info, Metadata headers) {
      return new ClientStreamTracer() {
        @Override
        public void streamCreated(Attributes transportAttrs, Metadata headers) {
          inFlights.incrementAndGet();
        }

        @Override
        public void streamClosed(Status status) {
          inFlights.decrementAndGet();
        }
      };
    }
  }

  static final class LeastRequestConfig {
    final int choiceCount;

    LeastRequestConfig(int choiceCount) {
      checkArgument(choiceCount >= MIN_CHOICE_COUNT, "choiceCount <= 1");
      // Even though a choiceCount value larger than 2 is currently considered valid in xDS
      // we restrict it to 10 here as specified in "A48: xDS Least Request LB Policy".
      this.choiceCount = Math.min(choiceCount, MAX_CHOICE_COUNT);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("choiceCount", choiceCount)
          .toString();
    }
  }
}
