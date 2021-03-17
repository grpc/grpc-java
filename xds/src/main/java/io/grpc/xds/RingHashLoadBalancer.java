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
import static com.google.common.base.Preconditions.checkState;
import static io.grpc.ConnectivityState.CONNECTING;
import static io.grpc.ConnectivityState.IDLE;
import static io.grpc.ConnectivityState.READY;
import static io.grpc.ConnectivityState.SHUTDOWN;
import static io.grpc.ConnectivityState.TRANSIENT_FAILURE;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import io.grpc.Attributes;
import io.grpc.ConnectivityState;
import io.grpc.ConnectivityStateInfo;
import io.grpc.EquivalentAddressGroup;
import io.grpc.InternalLogId;
import io.grpc.LoadBalancer;
import io.grpc.Status;
import io.grpc.SynchronizationContext;
import io.grpc.xds.XdsLogger.XdsLogLevel;
import io.grpc.xds.XdsSubchannelPickers.ErrorPicker;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;

/**
 * A {@link LoadBalancer} that provides consistent hashing based load balancing to upstream hosts.
 * It implements the "Ketama" hashing that maps hosts onto a circle (the "ring") by hashing its
 * addresses. Each request is routed to a host by hashing some property of the request and finding
 * the neaerest corresponding host clockwise around the ring. Each host is placed on the ring some
 * number of times proportional to its weight. With the ring partitioned appropriately, the
 * addition or removal of one host from a set of N hosts will affect only 1/N requests.
 */
final class RingHashLoadBalancer extends LoadBalancer {
  private static final Attributes.Key<AtomicReference<ConnectivityStateInfo>> STATE_INFO =
      Attributes.Key.create("state-info");

  private final XdsLogger logger;
  private final SynchronizationContext syncContext;
  private final XxHash64 hashFunc = XxHash64.INSTANCE;
  private final Map<EquivalentAddressGroup, Subchannel> subchannels = Maps.newHashMap();
  private final Helper helper;

  private ConnectivityState currentState;
  private SubchannelPicker currentPicker;
  private boolean subchannelEnteredReady;

  RingHashLoadBalancer(Helper helper) {
    this.helper = checkNotNull(helper, "helper");
    syncContext = checkNotNull(helper.getSynchronizationContext(), "syncContext");
    logger = XdsLogger.withLogId(InternalLogId.allocate("ring_hash_lb", helper.getAuthority()));
    logger.log(XdsLogLevel.INFO, "Created");
  }

  @Override
  public void handleResolvedAddresses(ResolvedAddresses resolvedAddresses) {
    logger.log(XdsLogLevel.DEBUG, "Received resolution result: {0}", resolvedAddresses);
    List<EquivalentAddressGroup> addrList = resolvedAddresses.getAddresses();
    if (addrList.isEmpty()) {
      handleNameResolutionError(Status.UNAVAILABLE.withDescription("No server addresses found"));
      return;
    }
    Map<EquivalentAddressGroup, EquivalentAddressGroup> latestAddrs = stripAttrs(addrList);
    Set<EquivalentAddressGroup> removedAddrs =
        Sets.newHashSet(Sets.difference(subchannels.keySet(), latestAddrs.keySet()));

    RingHashConfig config = (RingHashConfig) resolvedAddresses.getLoadBalancingPolicyConfig();
    Map<EquivalentAddressGroup, Long> serverWeights = new HashMap<>();
    long totalWeight = 0L;
    long minWeight = Long.MAX_VALUE;
    long maxWeight = Long.MIN_VALUE;
    for (EquivalentAddressGroup eag : addrList) {
      Long weight = eag.getAttributes().get(InternalXdsAttributes.ATTR_SERVER_WEIGHT);
      // Support two ways of server weighing: either multiple instances of the same address
      // or each address contains a per-address weight attribute. If a weight is not provided,
      // each occurrence of the address will be counted a weight value of one.
      if (weight == null) {
        weight = 1L;
      }
      minWeight = Math.min(minWeight, weight);
      maxWeight = Math.max(maxWeight, weight);
      totalWeight += weight;
      EquivalentAddressGroup addrKey = stripAttrs(eag);
      if (serverWeights.containsKey(addrKey)) {
        serverWeights.put(addrKey, serverWeights.get(addrKey) + weight);
      } else {
        serverWeights.put(addrKey, weight);
      }

      Subchannel existingSubchannel = subchannels.get(addrKey);
      if (existingSubchannel != null) {
        existingSubchannel.updateAddresses(Collections.singletonList(eag));
        continue;
      }
      Attributes attr = Attributes.newBuilder().set(
          STATE_INFO, new AtomicReference<>(ConnectivityStateInfo.forNonError(IDLE))).build();
      final Subchannel subchannel = helper.createSubchannel(
          CreateSubchannelArgs.newBuilder().setAddresses(eag).setAttributes(attr).build());
      subchannel.start(new SubchannelStateListener() {
        @Override
        public void onSubchannelState(ConnectivityStateInfo newState) {
          processSubchannelState(subchannel, newState);
        }
      });
      subchannels.put(addrKey, subchannel);
    }
    double normalizedMinWeight = (double) minWeight / totalWeight;
    // Scale up the number of hashes per host such that the least-weighted host gets a whole
    // number of hashes on the the ring. Other hosts might not end up with whole numbers, and
    // that's fine (the ring-building algorithm can handle this). This preserves the original
    // implementation's behavior: when weights aren't provided, all hosts should get an equal
    // number of hashes. In the case where this number exceeds the max_ring_size, it's scaled
    // back down to fit.
    double scale = Math.min(
        Math.ceil(normalizedMinWeight * config.minRingSize) / normalizedMinWeight,
        (double) config.maxRingSize);
    List<RingEntry> ring = new ArrayList<>();
    double currentHashes = 0.0;
    double targetHashes = 0.0;
    for (Map.Entry<EquivalentAddressGroup, Long> entry : serverWeights.entrySet()) {
      EquivalentAddressGroup addrKey = entry.getKey();
      double normalizedWeight = (double) entry.getValue() / totalWeight;
      // TODO(chengyuanzhang): is using the list of socket address correct?
      StringBuilder sb = new StringBuilder(addrKey.getAddresses().toString());
      sb.append('_');
      targetHashes += scale * normalizedWeight;
      long i = 0L;
      while (currentHashes < targetHashes) {
        sb.append(i);
        long hash = hashFunc.hashAsciiString(sb.toString());
        ring.add(new RingEntry(hash, addrKey));
        i++;
        currentHashes++;
        sb.deleteCharAt(sb.length() - 1);
      }
    }
    Collections.sort(ring);

    List<Subchannel> removedSubchannels = new ArrayList<>();
    for (EquivalentAddressGroup addr : removedAddrs) {
      removedSubchannels.add(subchannels.remove(addr));
    }

    // Update the picker before shutting down the subchannels, to reduce the chance of race
    // between picking a subchannel and shutting it down.
    updateBalancingState(new RingHashPicker(
        Collections.unmodifiableList(ring), ImmutableMap.copyOf(subchannels)));
    for (Subchannel subchann : removedSubchannels) {
      shutdownSubchannel(subchann);
    }
  }

  @Override
  public void handleNameResolutionError(Status error) {
    if (currentState != READY) {
      helper.updateBalancingState(TRANSIENT_FAILURE, new ErrorPicker(error));
    }
  }

  @Override
  public void shutdown() {
    logger.log(XdsLogLevel.INFO, "Shutdown");
    for (Subchannel subchannel : subchannels.values()) {
      shutdownSubchannel(subchannel);
    }
  }

  private void updateBalancingState(SubchannelPicker picker) {
    checkState(!subchannels.isEmpty(), "no subchannel has been created");
    ConnectivityState overallState = null;
    for (Subchannel subchannel : subchannels.values()) {
      ConnectivityStateInfo stateInfo = getSubchannelStateInfoRef(subchannel).get();
      overallState = aggregateState(overallState, stateInfo.getState());
    }
    if (overallState != currentState || picker != currentPicker || subchannelEnteredReady) {
      helper.updateBalancingState(overallState, picker);
      currentState = overallState;
      currentPicker = picker;
      subchannelEnteredReady = false;
    }
  }

  private void processSubchannelState(Subchannel subchannel, ConnectivityStateInfo stateInfo) {
    if (subchannels.get(stripAttrs(subchannel.getAddresses())) != subchannel) {
      return;
    }
    AtomicReference<ConnectivityStateInfo> subchannelStateRef =
        getSubchannelStateInfoRef(subchannel);

    // Don't proactively reconnect if the subchannel enters IDLE, even if previously was connected.
    // If the subchannel was previously in TRANSIENT_FAILURE, it is considered to stay in
    // TRANSIENT_FAILURE until it becomes READY.
    if (stateInfo.getState().equals(READY)) {
      subchannelEnteredReady = true;
    } else if (subchannelStateRef.get().getState().equals(TRANSIENT_FAILURE)) {
      return;
    }
    subchannelStateRef.set(stateInfo);
    updateBalancingState(currentPicker);
  }

  private static ConnectivityState aggregateState(
      @Nullable ConnectivityState overallState, ConnectivityState state) {
    if (overallState == null) {
      return state;
    }
    if (overallState == READY || state == READY) {
      return READY;
    }
    if (overallState == CONNECTING || state == CONNECTING) {
      return CONNECTING;
    }
    if (overallState == IDLE || state == IDLE) {
      return IDLE;
    }
    return overallState;
  }

  private static void shutdownSubchannel(Subchannel subchannel) {
    subchannel.shutdown();
    getSubchannelStateInfoRef(subchannel).set(ConnectivityStateInfo.forNonError(SHUTDOWN));
  }

  /**
   * Converts list of {@link EquivalentAddressGroup} to {@link EquivalentAddressGroup} set and
   * remove all attributes. The values are the original EAGs.
   */
  private static Map<EquivalentAddressGroup, EquivalentAddressGroup> stripAttrs(
      List<EquivalentAddressGroup> groupList) {
    Map<EquivalentAddressGroup, EquivalentAddressGroup> addrs =
        new HashMap<>(groupList.size() * 2);
    for (EquivalentAddressGroup group : groupList) {
      addrs.put(stripAttrs(group), group);
    }
    return addrs;
  }

  private static EquivalentAddressGroup stripAttrs(EquivalentAddressGroup eag) {
    return new EquivalentAddressGroup(eag.getAddresses());
  }

  private static AtomicReference<ConnectivityStateInfo> getSubchannelStateInfoRef(
      Subchannel subchannel) {
    return checkNotNull(subchannel.getAttributes().get(STATE_INFO), "STATE_INFO");
  }

  private final class RingHashPicker extends SubchannelPicker {
    private final List<RingEntry> ring;
    // shallow copy of subchannels
    private final Map<EquivalentAddressGroup, Subchannel> pickableSubchannels;

    private RingHashPicker(
        List<RingEntry> ring, Map<EquivalentAddressGroup, Subchannel> pickableSubchannels) {
      this.ring = ring;
      this.pickableSubchannels = pickableSubchannels;
    }

    @Override
    public PickResult pickSubchannel(PickSubchannelArgs args) {
      Long requestHash = args.getCallOptions().getOption(XdsNameResolver.RPC_HASH_KEY);
      if (requestHash == null) {
        return PickResult.withError(Status.INTERNAL.withDescription("RPC hash not found"));
      }
      if (ring.isEmpty()) {
        return PickResult.withNoResult();
      }
      // Find the nearest corresponding host clockwise around the ring.
      int low = 0;
      int high = ring.size();
      int mid;
      while (true) {
        mid = (low + high) / 2;
        if (mid == ring.size()) {
          mid = 0;
          break;
        }
        long midVal = ring.get(mid).hash;
        long midValL = mid == 0 ? 0 : ring.get(mid - 1).hash;
        if (requestHash <= midVal && requestHash > midValL) {
          break;
        }
        if (midVal < requestHash) {
          low = mid + 1;
        } else {
          high =  mid - 1;
        }
        if (low > high) {
          mid = 0;
          break;
        }
      }

      // Try finding the first non-TRANSIENT_FAILURE connection.
      int attempt = 0;
      Subchannel subchannel;
      ConnectivityStateInfo stateInfo;
      do {
        int index = (mid + attempt) % ring.size();
        EquivalentAddressGroup addrKey = ring.get(index).addrKey;
        subchannel = pickableSubchannels.get(addrKey);
        stateInfo = getSubchannelStateInfoRef(subchannel).get();
        attempt++;
      } while (attempt < ring.size() && stateInfo.getState() == TRANSIENT_FAILURE);

      if (stateInfo.getState() == READY) {
        return PickResult.withSubchannel(subchannel);
      }
      if (stateInfo.getState() == TRANSIENT_FAILURE) {  // all connections in TRANSIENT_FAILRE
        // TODO(chengyuanzhang): shall we use some other error?
        return PickResult.withError(stateInfo.getStatus());
      }
      if (stateInfo.getState() == IDLE) {
        final Subchannel finalSubchannel = subchannel;
        syncContext.execute(new Runnable() {
          @Override
          public void run() {
            finalSubchannel.requestConnection();
          }
        });
      }
      return PickResult.withNoResult();  // queue the pick and re-process later
    }
  }

  private static final class RingEntry implements Comparable<RingEntry> {
    private final long hash;
    private final EquivalentAddressGroup addrKey;

    private RingEntry(long hash, EquivalentAddressGroup addrKey) {
      this.hash = hash;
      this.addrKey = addrKey;
    }

    @Override
    public int compareTo(RingEntry entry) {
      return Long.compare(hash, entry.hash);
    }
  }

  /**
   * Configures the ring property. The larger the ring is (that is, the more hashes there are
   * for each provided host) the better the request distribution will reflect the desired weights.
   */
  static final class RingHashConfig {
    final long minRingSize;
    final long maxRingSize;

    RingHashConfig(long minRingSize, long maxRingSize) {
      checkArgument(minRingSize > 0, "minRingSize <= 0");
      checkArgument(maxRingSize > 0, "maxRingSize <= 0");
      checkArgument(minRingSize <= maxRingSize, "minRingSize > maxRingSize");
      this.minRingSize = minRingSize;
      this.maxRingSize = maxRingSize;
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("minRingSize", minRingSize)
          .add("maxRingSize", maxRingSize)
          .toString();
    }
  }
}
