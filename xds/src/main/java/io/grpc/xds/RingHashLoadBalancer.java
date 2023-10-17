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
import static io.grpc.ConnectivityState.TRANSIENT_FAILURE;

import com.google.common.base.MoreObjects;
import com.google.common.collect.HashMultiset;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Multiset;
import com.google.common.collect.Sets;
import com.google.common.primitives.UnsignedInteger;
import io.grpc.Attributes;
import io.grpc.ConnectivityState;
import io.grpc.EquivalentAddressGroup;
import io.grpc.InternalLogId;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancerProvider;
import io.grpc.Status;
import io.grpc.SynchronizationContext;
import io.grpc.util.GracefulSwitchLoadBalancer;
import io.grpc.util.MultiChildLoadBalancer;
import io.grpc.xds.XdsLogger.XdsLogLevel;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Collection;
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
final class RingHashLoadBalancer extends MultiChildLoadBalancer {
  private static final Status RPC_HASH_NOT_FOUND =
      Status.INTERNAL.withDescription("RPC hash not found. Probably a bug because xds resolver"
          + " config selector always generates a hash.");
  private static final XxHash64 hashFunc = XxHash64.INSTANCE;

  private final XdsLogger logger;
  private final SynchronizationContext syncContext;
  private List<RingEntry> ring;
  private Iterator<ChildLbState> connectionAttemptIterator = getChildLbStates().iterator();
  private final Random random;

  RingHashLoadBalancer(Helper helper) {
    this(helper, new Random());
  }

  RingHashLoadBalancer(Helper helper, Random random) {
    super(helper);
    this.random = checkNotNull(random, "random");
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
        Sets.newHashSet(
            Sets.difference(getStrippedChildEags(getChildLbStates()), latestAddrs.keySet()));

    // Shut down subchannels for delisted addresses.
    List<RingHashChildLbState> removedChildLbStates = new ArrayList<>();
    for (EquivalentAddressGroup addr : removedAddrs) {
      removedChildLbStates.add((RingHashChildLbState) getChildLbState(addr));
    }

    try {
      resolvingAddresses = true;
      // Update the child list by creating-adding, updating addresses, and removing
      if (!super.acceptResolvedAddressesInternal(resolvedAddresses, false)) {
        handleNameResolutionError(Status.UNAVAILABLE.withDescription(
            "Ring hash lb error: EDS resolution was successful, but was not accepted by base class"
            ));
        return false;
      }

      // Now do the ringhash specific logic with weights and building the ring
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
      }
      // Calculate scale
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

      // Build the ring
      ring = buildRing(serverWeights, totalWeight, scale);

      // If we need to proactively start connecting, iterate through all the subchannels, starting
      // at a random position.
      // Alternatively, we should better start at the same position.
      Collection<ChildLbState> childLbStates = getChildLbStates();
      connectionAttemptIterator = childLbStates.iterator();
      int randomAdvance = random.nextInt(childLbStates.size());
      while (randomAdvance-- > 0) {
        connectionAttemptIterator.next();
      }

      // Update the picker before shutting down the subchannels, to reduce the chance of race
      // between picking a subchannel and shutting it down.
      updateOverallBalancingState();
      for (RingHashChildLbState childLbState : removedChildLbStates) {
        childLbState.shutdown();
      }
    } finally {
      this.resolvingAddresses = false;
    }

    return true;
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
  @Override
  protected void updateOverallBalancingState() {
    checkState(!getChildLbStates().isEmpty(), "no subchannel has been created");
    boolean startConnectionAttempt = false;

    // Calculate the current overall state and whether we should attempt a connection
    int numIdle = 0;
    int numReady = 0;
    int numConnecting = 0;
    int numTransientFailure = 0;
    for (ChildLbState childLbState : getChildLbStates()) {
      ConnectivityState state = childLbState.getCurrentState();
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
    } else if (numTransientFailure == 1 && getChildLbStates().size() > 1) {
      overallState = CONNECTING;
      startConnectionAttempt = true;
    } else if (numIdle > 0) {
      overallState = IDLE;
    } else {
      overallState = TRANSIENT_FAILURE;
    }

    RingHashPicker picker = new RingHashPicker(syncContext, ring, getImmutableChildMap());
    // TODO(chengyuanzhang): avoid unnecessary reprocess caused by duplicated server addr updates
    getHelper().updateBalancingState(overallState, picker);
    this.currentConnectivityState = overallState;

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
        connectionAttemptIterator = getChildLbStates().iterator();
      }
      ((RingHashChildLbState)connectionAttemptIterator.next()).getLb().requestConnection();
    }
  }

  @Override
  protected boolean reconnectOnIdle() {
    return false;
  }

  /**
   * Create RingHashChildLbState objects with resolvedAddresses filled in.
   * @return Map of {@link Endpoint} -> {@link RingHashChildLbState}
   */
  @Override
  protected Map<Object, ChildLbState> createChildLbMap(ResolvedAddresses resolvedAddresses) {
    Map<Object, ChildLbState> childLbMap = new HashMap<>();

    List<EquivalentAddressGroup> addresses = resolvedAddresses.getAddresses();
    for (EquivalentAddressGroup eag : addresses) {
      Endpoint endpoint = new Endpoint(eag); // keys need to be just addresses

      ChildLbState existingChildLbState = getChildLbState(endpoint);
      if (existingChildLbState != null) {
        childLbMap.put(endpoint, existingChildLbState);
      } else {
        childLbMap.put(endpoint,
            new RingHashChildLbState(endpoint,
                getChildAddresses(endpoint, resolvedAddresses, null)));
      }
    }
    return childLbMap;
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
      Endpoint endpoint = new Endpoint(entry.getKey());
      double normalizedWeight = (double) entry.getValue() / totalWeight;
      // Per GRFC A61 use the first address for the hash
      StringBuilder sb = new StringBuilder(entry.getKey().getAddresses().get(0).toString());
      sb.append('_');
      int lengthWithoutCounter = sb.length();
      targetHashes += scale * normalizedWeight;
      long i = 0L;
      while (currentHashes < targetHashes) {
        sb.append(i);
        long hash = hashFunc.hashAsciiString(sb.toString());
        ring.add(new RingEntry(hash, endpoint));
        i++;
        currentHashes++;
        sb.setLength(lengthWithoutCounter);
      }
    }
    Collections.sort(ring);
    return Collections.unmodifiableList(ring);
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

  @SuppressWarnings("ReferenceEquality")
  public static EquivalentAddressGroup stripAttrs(EquivalentAddressGroup eag) {
    if (eag.getAttributes() == Attributes.EMPTY) {
      return eag;
    }
    return new EquivalentAddressGroup(eag.getAddresses());
  }

  private static final class RingHashPicker extends SubchannelPicker {
    private final SynchronizationContext syncContext;
    private final List<RingEntry> ring;
    // Avoid synchronization between pickSubchannel and subchannel's connectivity state change,
    // freeze picker's view of subchannel's connectivity state.
    // TODO(chengyuanzhang): can be more performance-friendly with
    //  IdentityHashMap<Subchannel, ConnectivityStateInfo> and RingEntry contains Subchannel.
    private final Map<Endpoint, RingHashChildLbState> pickableSubchannels;  // read-only

    private RingHashPicker(
        SynchronizationContext syncContext, List<RingEntry> ring,
        ImmutableMap<Object, ChildLbState> subchannels) {
      this.syncContext = syncContext;
      this.ring = ring;
      pickableSubchannels = new HashMap<>(subchannels.size());
      for (Map.Entry<Object, ChildLbState> entry : subchannels.entrySet()) {
        RingHashChildLbState childLbState = (RingHashChildLbState) entry.getValue();
        pickableSubchannels.put((Endpoint)entry.getKey(), childLbState);
      }
    }

    // Find the ring entry with hash next to (clockwise) the RPC's hash (binary search).
    private int getTargetIndex(Long requestHash) {
      if (ring.size() <= 1) {
        return 0;
      }

      int low = 0;
      int high = ring.size() - 1;
      int mid = (low + high) / 2;
      do {
        long midVal = ring.get(mid).hash;
        long midValL = mid == 0 ? 0 : ring.get(mid - 1).hash;
        if (requestHash <= midVal && requestHash > midValL) {
          return mid;
        }
        if (midVal < requestHash) {
          low = mid + 1;
        } else {
          high =  mid - 1;
        }
        mid = (low + high) / 2;
      } while (mid < ring.size() && low <= high);
      return mid;
    }

    @Override
    public PickResult pickSubchannel(PickSubchannelArgs args) {
      Long requestHash = args.getCallOptions().getOption(XdsNameResolver.RPC_HASH_KEY);
      if (requestHash == null) {
        return PickResult.withError(RPC_HASH_NOT_FOUND);
      }

      int targetIndex = getTargetIndex(requestHash);

      // Per gRFC A61, because of sticky-TF with PickFirst's auto reconnect on TF, we ignore
      // all TF subchannels and find the first ring entry in READY, CONNECTING or IDLE.  If
      // CONNECTING or IDLE we return a pick with no results.  Additionally, if that entry is in
      // IDLE, we initiate a connection.
      for (int i = 0; i < ring.size(); i++) {
        int index = (targetIndex + i) % ring.size();
        RingHashChildLbState childLbState = pickableSubchannels.get(ring.get(index).addrKey);

        if (childLbState.getCurrentState()  == READY) {
          return childLbState.getCurrentPicker().pickSubchannel(args);
        }

        // RPCs can be buffered if the next subchannel is pending (per A62). Otherwise, RPCs
        // are failed unless there is a READY connection.
        if (childLbState.getCurrentState() == CONNECTING) {
          return PickResult.withNoResult();
        }

        if (childLbState.getCurrentState() == IDLE || childLbState.isDeactivated()) {
          if (childLbState.isDeactivated()) {
            childLbState.activate();
          } else {
            syncContext.execute(() -> childLbState.getLb().requestConnection());
          }

          return PickResult.withNoResult(); // Indicates that this should be retried after backoff
        }
      }

      // return the pick from the original subchannel hit by hash, which is probably an error
      RingHashChildLbState originalSubchannel =
          pickableSubchannels.get(ring.get(targetIndex).addrKey);
      return originalSubchannel.getCurrentPicker().pickSubchannel(args);
    }

  }

  @Override
  protected SubchannelPicker getSubchannelPicker(Map<Object, SubchannelPicker> childPickers) {
    throw new UnsupportedOperationException("Not used by RingHash");
  }

  private static final class RingEntry implements Comparable<RingEntry> {
    private final long hash;
    private final Endpoint addrKey;

    private RingEntry(long hash, Endpoint addrKey) {
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

  static Set<EquivalentAddressGroup> getStrippedChildEags(Collection<ChildLbState> states) {
    return states.stream()
        .map(ChildLbState::getEag)
        .map(RingHashLoadBalancer::stripAttrs)
        .collect(Collectors.toSet());
  }

  @Override
  protected Collection<ChildLbState> getChildLbStates() {
    return super.getChildLbStates();
  }

  @Override
  protected ChildLbState getChildLbStateEag(EquivalentAddressGroup eag) {
    return super.getChildLbStateEag(eag);
  }

  class RingHashChildLbState extends MultiChildLoadBalancer.ChildLbState {

    public RingHashChildLbState(Endpoint key, ResolvedAddresses resolvedAddresses) {
      super(key, pickFirstLbProvider, null, EMPTY_PICKER, resolvedAddresses, true);
    }

    void updateAddresses(List<EquivalentAddressGroup> addresses, Attributes attributes) {
      ResolvedAddresses resolvedAddresses = ResolvedAddresses.newBuilder()
          .setAddresses(addresses)
          .setAttributes(attributes)
          .build();
      setResolvedAddresses(resolvedAddresses);
      handleResolvedAddresses(resolvedAddresses);
    }

    @Override
    protected void deactivate() {
      if (isDeactivated()) {
        return;
      }

      // Remove yourself, but don't shutdown yet
      removeChild(getKey());
      setDeactivated();
      logger.log(XdsLogLevel.DEBUG, "Child balancer {0} deactivated", getKey());
    }

    @Override
    protected void reactivate(LoadBalancerProvider policyProvider) {
      if (!isDeactivated()) {
        return;
      }

      currentConnectivityState = CONNECTING;
      getLb().switchTo(pickFirstLbProvider);
      markReactivated();
      getLb().acceptResolvedAddresses(this.getResolvedAddresses()); // Time to get a subchannel
      logger.log(XdsLogLevel.DEBUG, "Child balancer {0} reactivated", getKey());
    }

    public void activate() {
      reactivate(pickFirstLbProvider);
    }

    // Need to expose this to the LB class
    @Override
    protected void shutdown() {
      super.shutdown();
    }

    // Need to expose this to the LB class
    @Override
    protected GracefulSwitchLoadBalancer getLb() {
      return super.getLb();
    }

  }
}