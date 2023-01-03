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
import com.google.common.collect.HashMultiset;
import com.google.common.collect.Multiset;
import com.google.common.collect.Sets;
import com.google.common.primitives.UnsignedInteger;
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
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/**
 * A {@link LoadBalancer} that provides consistent hashing based load balancing to upstream hosts.
 * It implements the "Ketama" hashing that maps hosts onto a circle (the "ring") by hashing its
 * addresses. Each request is routed to a host by hashing some property of the request and finding
 * the nearest corresponding host clockwise around the ring. Each host is placed on the ring some
 * number of times proportional to its weight. With the ring partitioned appropriately, the
 * addition or removal of one host from a set of N hosts will affect only 1/N requests.
 */
final class RingHashLoadBalancer extends LoadBalancer {
  private static final Attributes.Key<Ref<ConnectivityStateInfo>> STATE_INFO =
      Attributes.Key.create("state-info");
  private static final Status RPC_HASH_NOT_FOUND =
      Status.INTERNAL.withDescription("RPC hash not found. Probably a bug because xds resolver"
          + " config selector always generates a hash.");
  private static final XxHash64 hashFunc = XxHash64.INSTANCE;

  private final XdsLogger logger;
  private final SynchronizationContext syncContext;
  private final Map<EquivalentAddressGroup, Subchannel> subchannels = new HashMap<>();
  private final Helper helper;

  private List<RingEntry> ring;
  private ConnectivityState currentState;
  private Iterator<Subchannel> connectionAttemptIterator = subchannels.values().iterator();
  private final Random random = new Random();

  RingHashLoadBalancer(Helper helper) {
    this.helper = checkNotNull(helper, "helper");
    syncContext = checkNotNull(helper.getSynchronizationContext(), "syncContext");
    logger = XdsLogger.withLogId(InternalLogId.allocate("ring_hash_lb", helper.getAuthority()));
    logger.log(XdsLogLevel.INFO, "Created");
  }

  @Override
  public boolean acceptResolvedAddresses(ResolvedAddresses resolvedAddresses) {
    logger.log(XdsLogLevel.DEBUG, "Received resolution result: {0}", resolvedAddresses);
    List<EquivalentAddressGroup> addrList = resolvedAddresses.getAddresses();
    if (!validateAddrList(addrList)) {
      return false;
    }

    Map<EquivalentAddressGroup, EquivalentAddressGroup> latestAddrs = stripAttrs(addrList);
    Set<EquivalentAddressGroup> removedAddrs =
        Sets.newHashSet(Sets.difference(subchannels.keySet(), latestAddrs.keySet()));

    RingHashConfig config = (RingHashConfig) resolvedAddresses.getLoadBalancingPolicyConfig();
    Map<EquivalentAddressGroup, Long> serverWeights = new HashMap<>();
    long totalWeight = 0L;
    for (EquivalentAddressGroup eag : addrList) {
      Long weight = eag.getAttributes().get(InternalXdsAttributes.ATTR_SERVER_WEIGHT);
      // Support two ways of server weighing: either multiple instances of the same address
      // or each address contains a per-address weight attribute. If a weight is not provided,
      // each occurrence of the address will be counted a weight value of one.
      if (weight == null) {
        weight = 1L;
      }
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
          STATE_INFO, new Ref<>(ConnectivityStateInfo.forNonError(IDLE))).build();
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
    long minWeight = Collections.min(serverWeights.values());
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
    ring = buildRing(serverWeights, totalWeight, scale);

    // Shut down subchannels for delisted addresses.
    List<Subchannel> removedSubchannels = new ArrayList<>();
    for (EquivalentAddressGroup addr : removedAddrs) {
      removedSubchannels.add(subchannels.remove(addr));
    }
    // If we need to proactively start connecting, iterate through all the subchannels, starting
    // at a random position.
    // Alternatively, we should better start at the same position.
    connectionAttemptIterator = subchannels.values().iterator();
    int randomAdvance = random.nextInt(subchannels.size());
    while (randomAdvance-- > 0) {
      connectionAttemptIterator.next();
    }

    // Update the picker before shutting down the subchannels, to reduce the chance of race
    // between picking a subchannel and shutting it down.
    updateBalancingState();
    for (Subchannel subchann : removedSubchannels) {
      shutdownSubchannel(subchann);
    }

    return true;
  }

  private boolean validateAddrList(List<EquivalentAddressGroup> addrList) {
    if (addrList.isEmpty()) {
      handleNameResolutionError(Status.UNAVAILABLE.withDescription("Ring hash lb error: EDS "
          + "resolution was successful, but returned server addresses are empty."));
      return false;
    }

    String dupAddrString = validateNoDuplicateAddresses(addrList);
    if (dupAddrString != null) {
      handleNameResolutionError(Status.UNAVAILABLE.withDescription("Ring hash lb error: EDS "
          + "resolution was successful, but there were duplicate addresses: " + dupAddrString));
      return false;
    }

    long totalWeight = 0;
    for (EquivalentAddressGroup eag : addrList) {
      Long weight = eag.getAttributes().get(InternalXdsAttributes.ATTR_SERVER_WEIGHT);

      if (weight == null) {
        weight = 1L;
      }

      if (weight < 0) {
        handleNameResolutionError(Status.UNAVAILABLE.withDescription(
            String.format("Ring hash lb error: EDS resolution was successful, but returned a "
                + "negative weight for %s.", stripAttrs(eag))));
        return false;
      }
      if (weight > UnsignedInteger.MAX_VALUE.longValue()) {
        handleNameResolutionError(Status.UNAVAILABLE.withDescription(
            String.format("Ring hash lb error: EDS resolution was successful, but returned a weight"
                + " too large to fit in an unsigned int for %s.", stripAttrs(eag))));
        return false;
      }
      totalWeight += weight;
    }

    if (totalWeight > UnsignedInteger.MAX_VALUE.longValue()) {
      handleNameResolutionError(Status.UNAVAILABLE.withDescription(
          String.format(
              "Ring hash lb error: EDS resolution was successful, but returned a sum of weights too"
              + " large to fit in an unsigned int (%d).", totalWeight)));
      return false;
    }

    return true;
  }

  @Nullable
  private String validateNoDuplicateAddresses(List<EquivalentAddressGroup> addrList) {
    Set<SocketAddress> addresses = new HashSet<>();
    Multiset<String> dups = HashMultiset.create();
    for (EquivalentAddressGroup eag : addrList) {
      for (SocketAddress address : eag.getAddresses()) {
        if (!addresses.add(address)) {
          dups.add(address.toString());
        }
      }
    }

    if (!dups.isEmpty()) {
      return dups.entrySet().stream()
          .map((dup) ->
              String.format("Address: %s, count: %d", dup.getElement(), dup.getCount() + 1))
          .collect(Collectors.joining("; "));
    }

    return null;
  }

  private static List<RingEntry> buildRing(
      Map<EquivalentAddressGroup, Long> serverWeights, long totalWeight, double scale) {
    List<RingEntry> ring = new ArrayList<>();
    double currentHashes = 0.0;
    double targetHashes = 0.0;
    for (Map.Entry<EquivalentAddressGroup, Long> entry : serverWeights.entrySet()) {
      EquivalentAddressGroup addrKey = entry.getKey();
      double normalizedWeight = (double) entry.getValue() / totalWeight;
      // TODO(chengyuanzhang): is using the list of socket address correct?
      StringBuilder sb = new StringBuilder(addrKey.getAddresses().toString());
      sb.append('_');
      int lengthWithoutCounter = sb.length();
      targetHashes += scale * normalizedWeight;
      long i = 0L;
      while (currentHashes < targetHashes) {
        sb.append(i);
        long hash = hashFunc.hashAsciiString(sb.toString());
        ring.add(new RingEntry(hash, addrKey));
        i++;
        currentHashes++;
        sb.setLength(lengthWithoutCounter);
      }
    }
    Collections.sort(ring);
    return Collections.unmodifiableList(ring);
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
    subchannels.clear();
  }

  /**
   * Updates the overall balancing state by aggregating the connectivity states of all subchannels.
   *
   * <p>Aggregation rules (in order of dominance):
   * <ol>
   *   <li>If there is at least one subchannel in READY state, overall state is READY</li>
   *   <li>If there are <em>2 or more</em> subchannels in TRANSIENT_FAILURE, overall state is
   *   TRANSIENT_FAILURE</li>
   *   <li>If there is at least one subchannel in CONNECTING state, overall state is
   *   CONNECTING</li>
   *   <li> If there is one subchannel in TRANSIENT_FAILURE state and there is
   *    more than one subchannel, report CONNECTING </li>
   *   <li>If there is at least one subchannel in IDLE state, overall state is IDLE</li>
   *   <li>Otherwise, overall state is TRANSIENT_FAILURE</li>
   * </ol>
   */
  private void updateBalancingState() {
    checkState(!subchannels.isEmpty(), "no subchannel has been created");
    boolean startConnectionAttempt = false;
    int numIdle = 0;
    int numReady = 0;
    int numConnecting = 0;
    int numTransientFailure = 0;
    for (Subchannel subchannel : subchannels.values()) {
      ConnectivityState state = getSubchannelStateInfoRef(subchannel).value.getState();
      if (state == READY) {
        numReady++;
        break;
      } else if (state == TRANSIENT_FAILURE) {
        numTransientFailure++;
      } else if (state == CONNECTING ) {
        numConnecting++;
      } else if (state == IDLE) {
        numIdle++;
      }
    }
    ConnectivityState overallState;
    if (numReady > 0) {
      overallState = READY;
    } else if (numTransientFailure >= 2) {
      overallState = TRANSIENT_FAILURE;
      startConnectionAttempt = (numConnecting == 0);
    } else if (numConnecting > 0) {
      overallState = CONNECTING;
    } else if (numTransientFailure == 1 && subchannels.size() > 1) {
      overallState = CONNECTING;
      startConnectionAttempt = true;
    } else if (numIdle > 0) {
      overallState = IDLE;
    } else {
      overallState = TRANSIENT_FAILURE;
      startConnectionAttempt = true;
    }
    RingHashPicker picker = new RingHashPicker(syncContext, ring, subchannels);
    // TODO(chengyuanzhang): avoid unnecessary reprocess caused by duplicated server addr updates
    helper.updateBalancingState(overallState, picker);
    currentState = overallState;
    // While the ring_hash policy is reporting TRANSIENT_FAILURE, it will
    // not be getting any pick requests from the priority policy.
    // However, because the ring_hash policy does not attempt to
    // reconnect to subchannels unless it is getting pick requests,
    // it will need special handling to ensure that it will eventually
    // recover from TRANSIENT_FAILURE state once the problem is resolved.
    // Specifically, it will make sure that it is attempting to connect to
    // at least one subchannel at any given time.  After a given subchannel
    // fails a connection attempt, it will move on to the next subchannel
    // in the ring.  It will keep doing this until one of the subchannels
    // successfully connects, at which point it will report READY and stop
    // proactively trying to connect.  The policy will remain in
    // TRANSIENT_FAILURE until at least one subchannel becomes connected,
    // even if subchannels are in state CONNECTING during that time.
    //
    // Note that we do the same thing when the policy is in state
    // CONNECTING, just to ensure that we don't remain in CONNECTING state
    // indefinitely if there are no new picks coming in.
    if (startConnectionAttempt) {
      if (!connectionAttemptIterator.hasNext()) {
        connectionAttemptIterator = subchannels.values().iterator();
      }
      connectionAttemptIterator.next().requestConnection();
    }
  }

  private void processSubchannelState(Subchannel subchannel, ConnectivityStateInfo stateInfo) {
    if (subchannels.get(stripAttrs(subchannel.getAddresses())) != subchannel) {
      return;
    }
    if (stateInfo.getState() == TRANSIENT_FAILURE || stateInfo.getState() == IDLE) {
      helper.refreshNameResolution();
    }
    updateConnectivityState(subchannel, stateInfo);
    updateBalancingState();
  }

  private void updateConnectivityState(Subchannel subchannel, ConnectivityStateInfo stateInfo) {
    Ref<ConnectivityStateInfo> subchannelStateRef = getSubchannelStateInfoRef(subchannel);
    ConnectivityState previousConnectivityState = subchannelStateRef.value.getState();
    // Don't proactively reconnect if the subchannel enters IDLE, even if previously was connected.
    // If the subchannel was previously in TRANSIENT_FAILURE, it is considered to stay in
    // TRANSIENT_FAILURE until it becomes READY.
    if (previousConnectivityState == TRANSIENT_FAILURE) {
      if (stateInfo.getState() == CONNECTING || stateInfo.getState() == IDLE) {
        return;
      }
    }
    subchannelStateRef.value = stateInfo;
  }

  private static void shutdownSubchannel(Subchannel subchannel) {
    subchannel.shutdown();
    getSubchannelStateInfoRef(subchannel).value = ConnectivityStateInfo.forNonError(SHUTDOWN);
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

  private static Ref<ConnectivityStateInfo> getSubchannelStateInfoRef(
      Subchannel subchannel) {
    return checkNotNull(subchannel.getAttributes().get(STATE_INFO), "STATE_INFO");
  }

  private static final class RingHashPicker extends SubchannelPicker {
    private final SynchronizationContext syncContext;
    private final List<RingEntry> ring;
    // Avoid synchronization between pickSubchannel and subchannel's connectivity state change,
    // freeze picker's view of subchannel's connectivity state.
    // TODO(chengyuanzhang): can be more performance-friendly with
    //  IdentityHashMap<Subchannel, ConnectivityStateInfo> and RingEntry contains Subchannel.
    private final Map<EquivalentAddressGroup, SubchannelView> pickableSubchannels;  // read-only

    private RingHashPicker(
        SynchronizationContext syncContext, List<RingEntry> ring,
        Map<EquivalentAddressGroup, Subchannel> subchannels) {
      this.syncContext = syncContext;
      this.ring = ring;
      pickableSubchannels = new HashMap<>(subchannels.size());
      for (Map.Entry<EquivalentAddressGroup, Subchannel> entry : subchannels.entrySet()) {
        Subchannel subchannel = entry.getValue();
        ConnectivityStateInfo stateInfo = subchannel.getAttributes().get(STATE_INFO).value;
        pickableSubchannels.put(entry.getKey(), new SubchannelView(subchannel, stateInfo));
      }
    }

    @Override
    public PickResult pickSubchannel(PickSubchannelArgs args) {
      Long requestHash = args.getCallOptions().getOption(XdsNameResolver.RPC_HASH_KEY);
      if (requestHash == null) {
        return PickResult.withError(RPC_HASH_NOT_FOUND);
      }

      // Find the ring entry with hash next to (clockwise) the RPC's hash.
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

      // Try finding a READY subchannel. Starting from the ring entry next to the RPC's hash.
      // If the one of the first two subchannels is not in TRANSIENT_FAILURE, return result
      // based on that subchannel. Otherwise, fail the pick unless a READY subchannel is found.
      // Meanwhile, trigger connection for the channel and status:
      // For the first subchannel that is in IDLE or TRANSIENT_FAILURE;
      // And for the second subchannel that is in IDLE or TRANSIENT_FAILURE;
      // And for each of the following subchannels that is in TRANSIENT_FAILURE or IDLE,
      // stop until we find the first subchannel that is in CONNECTING or IDLE status.
      boolean foundFirstNonFailed = false;  // true if having subchannel(s) in CONNECTING or IDLE
      Subchannel firstSubchannel = null;
      Subchannel secondSubchannel = null;
      for (int i = 0; i < ring.size(); i++) {
        int index = (mid + i) % ring.size();
        EquivalentAddressGroup addrKey = ring.get(index).addrKey;
        SubchannelView subchannel = pickableSubchannels.get(addrKey);
        if (subchannel.stateInfo.getState() == READY) {
          return PickResult.withSubchannel(subchannel.subchannel);
        }

        // RPCs can be buffered if any of the first two subchannels is pending. Otherwise, RPCs
        // are failed unless there is a READY connection.
        if (firstSubchannel == null) {
          firstSubchannel = subchannel.subchannel;
          PickResult maybeBuffer = pickSubchannelsNonReady(subchannel);
          if (maybeBuffer != null) {
            return maybeBuffer;
          }
        } else if (subchannel.subchannel != firstSubchannel && secondSubchannel == null) {
          secondSubchannel = subchannel.subchannel;
          PickResult maybeBuffer = pickSubchannelsNonReady(subchannel);
          if (maybeBuffer != null) {
            return maybeBuffer;
          }
        } else if (subchannel.subchannel != firstSubchannel
            && subchannel.subchannel != secondSubchannel) {
          if (!foundFirstNonFailed) {
            pickSubchannelsNonReady(subchannel);
            if (subchannel.stateInfo.getState() != TRANSIENT_FAILURE) {
              foundFirstNonFailed = true;
            }
          }
        }
      }
      // Fail the pick with error status of the original subchannel hit by hash.
      SubchannelView originalSubchannel = pickableSubchannels.get(ring.get(mid).addrKey);
      return PickResult.withError(originalSubchannel.stateInfo.getStatus());
    }

    @Nullable
    private PickResult pickSubchannelsNonReady(SubchannelView subchannel) {
      if (subchannel.stateInfo.getState() == TRANSIENT_FAILURE
          || subchannel.stateInfo.getState() == IDLE ) {
        final Subchannel finalSubchannel = subchannel.subchannel;
        syncContext.execute(new Runnable() {
          @Override
          public void run() {
            finalSubchannel.requestConnection();
          }
        });
      }
      if (subchannel.stateInfo.getState() == CONNECTING
          || subchannel.stateInfo.getState() == IDLE) {
        return PickResult.withNoResult();
      } else {
        return null;
      }
    }
  }

  /**
   * An unmodifiable view of a subchannel with state not subject to its real connectivity
   * state changes.
   */
  private static final class SubchannelView {
    private final Subchannel subchannel;
    private final ConnectivityStateInfo stateInfo;

    private SubchannelView(Subchannel subchannel, ConnectivityStateInfo stateInfo) {
      this.subchannel = subchannel;
      this.stateInfo = stateInfo;
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
   * A lighter weight Reference than AtomicReference.
   */
  private static final class Ref<T> {
    T value;

    Ref(T value) {
      this.value = value;
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
