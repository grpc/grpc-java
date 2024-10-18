/*
 * Copyright 2023 The gRPC Authors
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

package io.grpc.internal;

import static com.google.common.base.Preconditions.checkNotNull;
import static io.grpc.ConnectivityState.CONNECTING;
import static io.grpc.ConnectivityState.IDLE;
import static io.grpc.ConnectivityState.READY;
import static io.grpc.ConnectivityState.SHUTDOWN;
import static io.grpc.ConnectivityState.TRANSIENT_FAILURE;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import io.grpc.Attributes;
import io.grpc.ConnectivityState;
import io.grpc.ConnectivityStateInfo;
import io.grpc.EquivalentAddressGroup;
import io.grpc.LoadBalancer;
import io.grpc.Status;
import io.grpc.SynchronizationContext.ScheduledHandle;
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;

/**
 * A {@link LoadBalancer} that provides no load-balancing over the addresses from the {@link
 * io.grpc.NameResolver}. The channel's default behavior is used, which is walking down the address
 * list and sticking to the first that works.
 */
final class PickFirstLeafLoadBalancer extends LoadBalancer {
  private static final Logger log = Logger.getLogger(PickFirstLeafLoadBalancer.class.getName());
  @VisibleForTesting
  static final int CONNECTION_DELAY_INTERVAL_MS = 250;
  private final Helper helper;
  private final Map<SocketAddress, SubchannelData> subchannels = new HashMap<>();
  private final IndexI addressIndex = IndexI.create(ImmutableList.of());
  private int numTf = 0;
  private boolean firstPass = true;
  @Nullable
  private ScheduledHandle scheduleConnectionTask = null;
  private ConnectivityState rawConnectivityState = IDLE;
  private ConnectivityState concludedState = IDLE;
  private final boolean enableHappyEyeballs = !isSerializingRetries()
      && PickFirstLoadBalancerProvider.isEnabledHappyEyeballs();
  private boolean notAPetiolePolicy = true; // means not under a petiole policy
  private final BackoffPolicy.Provider bkoffPolProvider = new ExponentialBackoffPolicy.Provider();
  private BackoffPolicy reconnectPolicy;
  @Nullable
  private ScheduledHandle reconnectTask = null;
  private final boolean serializingRetries = isSerializingRetries();

  PickFirstLeafLoadBalancer(Helper helper) {
    this.helper = checkNotNull(helper, "helper");
  }

  static boolean isSerializingRetries() {
    return GrpcUtil.getFlag("GRPC_SERIALIZE_RETRIES", false);
  }

  @Override
  public Status acceptResolvedAddresses(ResolvedAddresses resolvedAddresses) {
    if (rawConnectivityState == SHUTDOWN) {
      return Status.FAILED_PRECONDITION.withDescription("Already shut down");
    }

    // Cache whether or not this is a petiole policy, which is based off of an address attribute
    Boolean isPetiolePolicy = resolvedAddresses.getAttributes().get(IS_PETIOLE_POLICY);
    this.notAPetiolePolicy = isPetiolePolicy == null || !isPetiolePolicy;

    List<EquivalentAddressGroup> servers = resolvedAddresses.getAddresses();

    // Validate the address list
    if (servers.isEmpty()) {
      Status unavailableStatus = Status.UNAVAILABLE.withDescription(
              "NameResolver returned no usable address. addrs=" + resolvedAddresses.getAddresses()
                      + ", attrs=" + resolvedAddresses.getAttributes());
      handleNameResolutionError(unavailableStatus);
      return unavailableStatus;
    }
    for (EquivalentAddressGroup eag : servers) {
      if (eag == null) {
        Status unavailableStatus = Status.UNAVAILABLE.withDescription(
            "NameResolver returned address list with null endpoint. addrs="
                + resolvedAddresses.getAddresses() + ", attrs="
                + resolvedAddresses.getAttributes());
        handleNameResolutionError(unavailableStatus);
        return unavailableStatus;
      }
    }

    // Since we have a new set of addresses, we are again at first pass
    firstPass = true;

    List<EquivalentAddressGroup> cleanServers = deDupAddresses(servers);

    // We can optionally be configured to shuffle the address list. This can help better distribute
    // the load.
    if (resolvedAddresses.getLoadBalancingPolicyConfig()
        instanceof PickFirstLeafLoadBalancerConfig) {
      PickFirstLeafLoadBalancerConfig config
          = (PickFirstLeafLoadBalancerConfig) resolvedAddresses.getLoadBalancingPolicyConfig();
      if (config.shuffleAddressList != null && config.shuffleAddressList) {
        Collections.shuffle(cleanServers,
            config.randomSeed != null ? new Random(config.randomSeed) : new Random());
      }
    }

    final ImmutableList<EquivalentAddressGroup> newImmutableAddressGroups =
        ImmutableList.<EquivalentAddressGroup>builder().addAll(cleanServers).build();

    if (rawConnectivityState == READY) {
      // If the previous ready subchannel exists in new address list,
      // keep this connection and don't create new subchannels
      SocketAddress previousAddress = addressIndex.getCurrentAddress();
      addressIndex.updateGroups(newImmutableAddressGroups);
      if (addressIndex.seekTo(previousAddress)) {
        SubchannelData subchannelData = subchannels.get(previousAddress);
        subchannelData.getSubchannel().updateAddresses(addressIndex.getCurrentEagAsList());
        return Status.OK;
      }
      // Previous ready subchannel not in the new list of addresses
    } else {
      addressIndex.updateGroups(newImmutableAddressGroups);
    }

    // remove old subchannels that were not in new address list
    Set<SocketAddress> oldAddrs = new HashSet<>(subchannels.keySet());

    // Flatten the new EAGs addresses
    Set<SocketAddress> newAddrs = new HashSet<>();
    for (EquivalentAddressGroup endpoint : newImmutableAddressGroups) {
      newAddrs.addAll(endpoint.getAddresses());
    }

    // Shut them down and remove them
    for (SocketAddress oldAddr : oldAddrs) {
      if (!newAddrs.contains(oldAddr)) {
        subchannels.remove(oldAddr).getSubchannel().shutdown();
      }
    }

    if (oldAddrs.size() == 0) {
      // Make tests happy; they don't properly assume starting in CONNECTING
      rawConnectivityState = CONNECTING;
      updateBalancingState(CONNECTING, new Picker(PickResult.withNoResult()));
    }

    if (rawConnectivityState == READY) {
      // connect from beginning when prompted
      rawConnectivityState = IDLE;
      updateBalancingState(IDLE, new RequestConnectionPicker(this));

    } else if (rawConnectivityState == CONNECTING || rawConnectivityState == TRANSIENT_FAILURE) {
      // start connection attempt at first address
      cancelScheduleTask();
      requestConnection();
    }

    return Status.OK;
  }

  private static List<EquivalentAddressGroup> deDupAddresses(List<EquivalentAddressGroup> groups) {
    Set<SocketAddress> seenAddresses = new HashSet<>();
    List<EquivalentAddressGroup> newGroups = new ArrayList<>();

    for (EquivalentAddressGroup group : groups) {
      List<SocketAddress> addrs = new ArrayList<>();
      for (SocketAddress addr : group.getAddresses()) {
        if (seenAddresses.add(addr)) {
          addrs.add(addr);
        }
      }
      if (!addrs.isEmpty()) {
        newGroups.add(new EquivalentAddressGroup(addrs, group.getAttributes()));
      }
    }

    return newGroups;
  }

  @Override
  public void handleNameResolutionError(Status error) {
    if (rawConnectivityState == SHUTDOWN) {
      return;
    }

    for (SubchannelData subchannelData : subchannels.values()) {
      subchannelData.getSubchannel().shutdown();
    }
    subchannels.clear();
    addressIndex.updateGroups(ImmutableList.of());
    rawConnectivityState = TRANSIENT_FAILURE;
    updateBalancingState(TRANSIENT_FAILURE, new Picker(PickResult.withError(error)));
  }

  void processSubchannelState(SubchannelData subchannelData, ConnectivityStateInfo stateInfo) {
    ConnectivityState newState = stateInfo.getState();

    // Shutdown channels/previously relevant subchannels can still callback with state updates.
    // To prevent pickers from returning these obsolete subchannels, this logic
    // is included to check if the current list of active subchannels includes this subchannel.
    if (subchannelData != subchannels.get(getAddress(subchannelData.subchannel))) {
      return;
    }

    if (newState == SHUTDOWN) {
      return;
    }

    if (newState == IDLE && subchannelData.state == READY) {
      helper.refreshNameResolution();
    }

    // If we are transitioning from a TRANSIENT_FAILURE to CONNECTING or IDLE we ignore this state
    // transition and still keep the LB in TRANSIENT_FAILURE state. This is referred to as "sticky
    // transient failure". Only a subchannel state change to READY will get the LB out of
    // TRANSIENT_FAILURE. If the state is IDLE we additionally request a new connection so that we
    // keep retrying for a connection.

    // With the new pick first implementation, individual subchannels will have their own backoff
    // on a per-address basis. Thus, iterative requests for connections will not be requested
    // once the first pass through is complete.
    // However, every time there is an address update, we will perform a pass through for the new
    // addresses in the updated list.
    subchannelData.updateState(newState);
    if (rawConnectivityState == TRANSIENT_FAILURE || concludedState == TRANSIENT_FAILURE)  {
      if (newState == CONNECTING) {
        // each subchannel is responsible for its own backoff
        return;
      } else if (newState == IDLE) {
        requestConnection();
        return;
      }
    }

    switch (newState) {
      case IDLE:
        // Shutdown when ready: connect from beginning when prompted
        addressIndex.reset();
        rawConnectivityState = IDLE;
        updateBalancingState(IDLE, new RequestConnectionPicker(this));
        break;

      case CONNECTING:
        rawConnectivityState = CONNECTING;
        updateBalancingState(CONNECTING, new Picker(PickResult.withNoResult()));
        break;

      case READY:
        shutdownRemaining(subchannelData);
        addressIndex.seekTo(getAddress(subchannelData.subchannel));
        rawConnectivityState = READY;
        updateHealthCheckedState(subchannelData);
        break;

      case TRANSIENT_FAILURE:
        // If we are looking at current channel, request a connection if possible
        if (addressIndex.isValid()
            && subchannels.get(addressIndex.getCurrentAddress()) == subchannelData) {
          if (addressIndex.increment()) {
            cancelScheduleTask();
            requestConnection(); // is recursive so might hit the end of the addresses
          } else {
            scheduleBackoff();
          }
        }

        if (isPassComplete()) {
          rawConnectivityState = TRANSIENT_FAILURE;
          updateBalancingState(TRANSIENT_FAILURE,
              new Picker(PickResult.withError(stateInfo.getStatus())));

          // Refresh Name Resolution, but only when all 3 conditions are met
          // * We are at the end of addressIndex
          // * have had status reported for all subchannels.
          // * And one of the following conditions:
          //    * Have had enough TF reported since we completed first pass
          //    * Just completed the first pass
          if (++numTf >= addressIndex.size() || firstPass) {
            firstPass = false;
            numTf = 0;
            helper.refreshNameResolution();
          }
        }
        break;

      default:
        throw new IllegalArgumentException("Unsupported state:" + newState);
    }
  }

  /**
   * Only called after all addresses attempted and failed (TRANSIENT_FAILURE).
   */
  private void scheduleBackoff() {
    if (!serializingRetries) {
      return;
    }

    class EndOfCurrentBackoff implements Runnable {
      @Override
      public void run() {
        reconnectTask = null;
        addressIndex.reset();
        requestConnection();
      }
    }

    // Just allow the previous one to trigger when ready if we're already in backoff
    if (reconnectTask != null) {
      return;
    }

    if (reconnectPolicy == null) {
      reconnectPolicy = bkoffPolProvider.get();
    }
    long delayNanos = reconnectPolicy.nextBackoffNanos();
    reconnectTask = helper.getSynchronizationContext().schedule(
        new EndOfCurrentBackoff(),
        delayNanos,
        TimeUnit.NANOSECONDS,
        helper.getScheduledExecutorService());
  }

  private void updateHealthCheckedState(SubchannelData subchannelData) {
    if (subchannelData.state != READY) {
      return;
    }

    if (notAPetiolePolicy || subchannelData.getHealthState() == READY) {
      updateBalancingState(READY,
          new FixedResultPicker(PickResult.withSubchannel(subchannelData.subchannel)));
    } else if (subchannelData.getHealthState() == TRANSIENT_FAILURE) {
      updateBalancingState(TRANSIENT_FAILURE, new Picker(PickResult.withError(
          subchannelData.healthStateInfo.getStatus())));
    } else if (concludedState != TRANSIENT_FAILURE) {
      updateBalancingState(subchannelData.getHealthState(),
          new Picker(PickResult.withNoResult()));
    }
  }

  private void updateBalancingState(ConnectivityState state, SubchannelPicker picker) {
    // an optimization: de-dup IDLE or CONNECTING notification.
    if (state == concludedState && (state == IDLE || state == CONNECTING)) {
      return;
    }
    concludedState = state;
    helper.updateBalancingState(state, picker);
  }

  @Override
  public void shutdown() {
    log.log(Level.FINE,
        "Shutting down, currently have {} subchannels created", subchannels.size());
    rawConnectivityState = SHUTDOWN;
    concludedState = SHUTDOWN;
    cancelScheduleTask();
    if (reconnectTask != null) {
      reconnectTask.cancel();
      reconnectTask = null;
    }
    reconnectPolicy = null;

    for (SubchannelData subchannelData : subchannels.values()) {
      subchannelData.getSubchannel().shutdown();
    }

    subchannels.clear();
  }

  /**
  * Shuts down remaining subchannels. Called when a subchannel becomes ready, which means
  * that all other subchannels must be shutdown.
  */
  private void shutdownRemaining(SubchannelData activeSubchannelData) {
    if (reconnectTask != null) {
      reconnectTask.cancel();
      reconnectTask = null;
    }
    reconnectPolicy = null;

    cancelScheduleTask();
    for (SubchannelData subchannelData : subchannels.values()) {
      if (!subchannelData.getSubchannel().equals(activeSubchannelData.subchannel)) {
        subchannelData.getSubchannel().shutdown();
      }
    }
    subchannels.clear();
    activeSubchannelData.updateState(READY);
    subchannels.put(getAddress(activeSubchannelData.subchannel), activeSubchannelData);
  }

  /**
   * Requests a connection to the next applicable address' subchannel, creating one if necessary.
   * Schedules a connection to next address in list as well.
   * If the current channel has already attempted a connection, we attempt a connection
   * to the next address/subchannel in our list.  We assume that createNewSubchannel will never
   * return null.
   */
  @Override
  public void requestConnection() {
    if (!addressIndex.isValid() || rawConnectivityState == SHUTDOWN) {
      return;
    }

    SocketAddress currentAddress = addressIndex.getCurrentAddress();
    SubchannelData subchannelData = subchannels.get(currentAddress);
    if (subchannelData == null) {
      subchannelData = createNewSubchannel(currentAddress, addressIndex.getCurrentEagAttributes());
    }

    ConnectivityState subchannelState = subchannelData.getState();
    switch (subchannelState) {
      case IDLE:
        subchannelData.subchannel.requestConnection();
        subchannelData.updateState(CONNECTING);
        scheduleNextConnection();
        break;
      case CONNECTING:
        scheduleNextConnection();
        break;
      case TRANSIENT_FAILURE:
        if (!serializingRetries) {
          addressIndex.increment();
          requestConnection();
        } else {
          if (!addressIndex.isValid()) {
            scheduleBackoff();
          } else {
            subchannelData.subchannel.requestConnection();
            subchannelData.updateState(CONNECTING);
          }
        }
        break;
      default:
        // Wait for current subchannel to change state
    }
  }


  /**
  * Happy Eyeballs
  * Schedules connection attempt to happen after a delay to the next available address.
  */
  private void scheduleNextConnection() {
    if (!enableHappyEyeballs
        || (scheduleConnectionTask != null && scheduleConnectionTask.isPending())) {
      return;
    }

    class StartNextConnection implements Runnable {
      @Override
      public void run() {
        scheduleConnectionTask = null;
        if (addressIndex.increment()) {
          requestConnection();
        }
      }
    }

    scheduleConnectionTask = helper.getSynchronizationContext().schedule(
        new StartNextConnection(),
        CONNECTION_DELAY_INTERVAL_MS,
        TimeUnit.MILLISECONDS,
        helper.getScheduledExecutorService());
  }

  private void cancelScheduleTask() {
    if (scheduleConnectionTask != null) {
      scheduleConnectionTask.cancel();
      scheduleConnectionTask = null;
    }
  }

  private SubchannelData createNewSubchannel(SocketAddress addr, Attributes attrs) {
    HealthListener hcListener = new HealthListener();
    final Subchannel subchannel = helper.createSubchannel(
        CreateSubchannelArgs.newBuilder()
            .setAddresses(Lists.newArrayList(
                new EquivalentAddressGroup(addr, attrs)))
            .addOption(HEALTH_CONSUMER_LISTENER_ARG_KEY, hcListener)
            .addOption(LoadBalancer.DISABLE_SUBCHANNEL_RECONNECT_KEY, serializingRetries)
            .build());
    if (subchannel == null) {
      log.warning("Was not able to create subchannel for " + addr);
      throw new IllegalStateException("Can't create subchannel");
    }
    SubchannelData subchannelData = new SubchannelData(subchannel, IDLE);
    hcListener.subchannelData = subchannelData;
    subchannels.put(addr, subchannelData);
    Attributes scAttrs = subchannel.getAttributes();
    if (notAPetiolePolicy || scAttrs.get(LoadBalancer.HAS_HEALTH_PRODUCER_LISTENER_KEY) == null) {
      subchannelData.healthStateInfo = ConnectivityStateInfo.forNonError(READY);
    }
    subchannel.start(stateInfo -> processSubchannelState(subchannelData, stateInfo));
    return subchannelData;
  }

  private boolean isPassComplete() {
    if (subchannels.size() < addressIndex.size()) {
      return false;
    }
    for (SubchannelData sc : subchannels.values()) {
      if (!sc.isCompletedConnectivityAttempt() ) {
        return false;
      }
    }
    return true;
  }

  private final class HealthListener implements SubchannelStateListener {
    private SubchannelData subchannelData;

    @Override
    public void onSubchannelState(ConnectivityStateInfo newState) {
      if (notAPetiolePolicy) {
        log.log(Level.WARNING,
            "Ignoring health status {0} for subchannel {1} as this is not under a petiole policy",
            new Object[]{newState, subchannelData.subchannel});
        return;
      }

      log.log(Level.FINE, "Received health status {0} for subchannel {1}",
          new Object[]{newState, subchannelData.subchannel});
      subchannelData.healthStateInfo = newState;
      if (addressIndex.isValid()
          && subchannelData == subchannels.get(addressIndex.getCurrentAddress())) {
        updateHealthCheckedState(subchannelData);
      }
    }
  }

  private SocketAddress getAddress(Subchannel subchannel) {
    return subchannel.getAddresses().getAddresses().get(0);
  }

  @VisibleForTesting
  ConnectivityState getConcludedConnectivityState() {
    return this.concludedState;
  }

  /**
   * No-op picker which doesn't add any custom picking logic. It just passes already known result
   * received in constructor.
   */
  private static final class Picker extends SubchannelPicker {
    private final PickResult result;

    Picker(PickResult result) {
      this.result = checkNotNull(result, "result");
    }

    @Override
    public PickResult pickSubchannel(PickSubchannelArgs args) {
      return result;
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(Picker.class).add("result", result).toString();
    }
  }

  /**
   * Picker that requests connection during the first pick, and returns noResult.
   */
  private final class RequestConnectionPicker extends SubchannelPicker {
    private final PickFirstLeafLoadBalancer pickFirstLeafLoadBalancer;
    private final AtomicBoolean connectionRequested = new AtomicBoolean(false);

    RequestConnectionPicker(PickFirstLeafLoadBalancer pickFirstLeafLoadBalancer) {
      this.pickFirstLeafLoadBalancer =
          checkNotNull(pickFirstLeafLoadBalancer, "pickFirstLeafLoadBalancer");
    }

    @Override
    public PickResult pickSubchannel(PickSubchannelArgs args) {
      if (connectionRequested.compareAndSet(false, true)) {
        helper.getSynchronizationContext().execute(pickFirstLeafLoadBalancer::requestConnection);
      }
      return PickResult.withNoResult();
    }
  }

  interface IndexI {
    static IndexI create(List<EquivalentAddressGroup> groups) {
      if (PickFirstLoadBalancerProvider.isEnabledHappyEyeballs()) {
        return new IndexHappyEyeballs(groups);
      } else {
        return new IndexNonHE(groups);
      }
    }

    boolean isValid();

    boolean isAtBeginning();

    /**
     * Move to next address in group.  If last address in group move to first address of next group.
     *
     * @return false if went off end of the list, otherwise true
     */
    boolean increment();

    void reset();

    SocketAddress getCurrentAddress();

    Attributes getCurrentEagAttributes();

    List<EquivalentAddressGroup> getCurrentEagAsList();

    /**
     * Update to new groups, resetting the current index.
     */
    void updateGroups(List<EquivalentAddressGroup> newGroups);

    /**
     * Returns false if the needle was not found and the current index was left unchanged.
     */
    boolean seekTo(SocketAddress needle);

    int size();

    int getGroupIndex();
  }

  /**
   * IndexNonHE as in 'i', the pointer to an entry. Not a "search index."
   * All updates should be done in a synchronization context.
   */
  @VisibleForTesting
  private static class IndexNonHE implements IndexI {
    private List<EquivalentAddressGroup> addressGroups;
    private int size;
    private int groupIndex;
    private int addressIndex;

    public IndexNonHE(List<EquivalentAddressGroup> groups) {
      updateGroups(groups);
    }

    @Override
    public boolean isValid() {
      // Is invalid if empty or has incremented off the end
      return groupIndex < addressGroups.size();
    }

    @Override
    public boolean isAtBeginning() {
      return groupIndex == 0 && addressIndex == 0;
    }

    /**
     * Move to next address in group.  If last address in group move to first address of next group.
     * @return false if went off end of the list, otherwise true
     */
    @Override
    public boolean increment() {
      if (!isValid()) {
        return false;
      }

      EquivalentAddressGroup group = addressGroups.get(groupIndex);
      addressIndex++;
      if (addressIndex >= group.getAddresses().size()) {
        groupIndex++;
        addressIndex = 0;
        return groupIndex < addressGroups.size();
      }

      return true;
    }

    @Override
    public void reset() {
      groupIndex = 0;
      addressIndex = 0;
    }

    @Override
    public SocketAddress getCurrentAddress() {
      if (!isValid()) {
        throw new IllegalStateException("Index is past the end of the address group list");
      }
      return addressGroups.get(groupIndex).getAddresses().get(addressIndex);
    }

    @Override
    public Attributes getCurrentEagAttributes() {
      if (!isValid()) {
        throw new IllegalStateException("Index is off the end of the address group list");
      }
      return addressGroups.get(groupIndex).getAttributes();
    }

    @Override
    public List<EquivalentAddressGroup> getCurrentEagAsList() {
      return Collections.singletonList(
          new EquivalentAddressGroup(getCurrentAddress(), getCurrentEagAttributes()));
    }

    /**
     * Update to new groups, resetting the current index.
     */
    @Override
    public void updateGroups(List<EquivalentAddressGroup> newGroups) {
      addressGroups = checkNotNull(newGroups, "newGroups");
      reset();
      int size = 0;
      for (EquivalentAddressGroup eag : newGroups) {
        size += eag.getAddresses().size();
      }
      this.size = size;
    }

    /**
     * Returns false if the needle was not found and the current index was left unchanged.
     */
    @Override
    public boolean seekTo(SocketAddress needle) {
      for (int i = 0; i < addressGroups.size(); i++) {
        EquivalentAddressGroup group = addressGroups.get(i);
        int j = group.getAddresses().indexOf(needle);
        if (j == -1) {
          continue;
        }
        this.groupIndex = i;
        this.addressIndex = j;
        return true;
      }
      return false;
    }

    @Override
    public int size() {
      return size;
    }

    @Override
    public int getGroupIndex() {
      return groupIndex;
    }
  }

  private static final class IndexHappyEyeballs implements IndexI {
    private List<EquivalentAddressGroup> addressGroups;
    private List<InterleavedEntry> interleavedAddresses = new ArrayList<>();
    private int interleavedIndex = 0;

    public IndexHappyEyeballs(List<EquivalentAddressGroup> groups) {
      updateGroups(groups);
    }

    @Override
    public boolean increment() {
      if (!isValid()) {
        return false;
      }

      interleavedIndex++;

      return isValid();
    }

    @Override
    public boolean isValid() {
      return interleavedIndex < interleavedAddresses.size();
    }

    @Override
    public boolean isAtBeginning() {
      return interleavedIndex == 0;
    }

    @Override
    public void reset() {
      interleavedIndex = 0;
    }

    @Override
    public SocketAddress getCurrentAddress() {
      if (!isValid()) {
        throw new IllegalStateException("Index is past the end of the address group list");
      }
      return interleavedAddresses.get(interleavedIndex).address;
    }

    @Override
    public Attributes getCurrentEagAttributes() {
      return getCurrentEag().getAttributes();
    }

    @Override
    public List<EquivalentAddressGroup> getCurrentEagAsList() {
      return Collections.singletonList(getCurrentEag());
    }

    @Override
    public int getGroupIndex() {
      if (!isValid()) {
        throw new IllegalStateException("Index is past the end of the address group list");
      }
      return interleavedAddresses.get(interleavedIndex).addressGroup;
    }

    private EquivalentAddressGroup getCurrentEag() {
      if (!isValid()) {
        throw new IllegalStateException("Index is past the end of the address group list");
      }
      return addressGroups.get(interleavedAddresses.get(interleavedIndex).addressGroup);
    }

    @Override
    public void updateGroups(List<EquivalentAddressGroup> newGroups) {
      addressGroups = checkNotNull(newGroups, "newGroups");
      Boolean firstIsV6 = null;
      List<InterleavedEntry> v4Entries = new ArrayList<>();
      List<InterleavedEntry> v6Entries = new ArrayList<>();
      for (int g = 0; g <  newGroups.size(); g++) {
        EquivalentAddressGroup eag = newGroups.get(g);
        for (int a = 0; a < eag.getAddresses().size(); a++) {
          SocketAddress addr = eag.getAddresses().get(a);
          boolean isIpV4 = addr instanceof InetSocketAddress
              && ((InetSocketAddress) addr).getAddress() instanceof Inet4Address;
          if (isIpV4) {
            if (firstIsV6 == null) {
              firstIsV6 = false;
            }
            v4Entries.add(new InterleavedEntry(g, addr));
          } else {
            if (firstIsV6 == null) {
              firstIsV6 = true;
            }
            v6Entries.add(new InterleavedEntry(g, addr));
          }
        }
      }

      this.interleavedAddresses =
          firstIsV6 != null && firstIsV6
          ? interleave(v6Entries, v4Entries)
          : interleave(v4Entries, v6Entries);

      reset();
    }

    private static List<InterleavedEntry> interleave(List<InterleavedEntry> firstFamily,
                                                     List<InterleavedEntry> secondFamily) {
      if (firstFamily.isEmpty()) {
        return secondFamily;
      }
      if (secondFamily.isEmpty()) {
        return firstFamily;
      }

      List<InterleavedEntry> result = new ArrayList<>();
      for (int i = 0; i < Math.max(firstFamily.size(), secondFamily.size()); i++) {
        if (i < firstFamily.size()) {
          result.add(firstFamily.get(i));
        }
        if (i < secondFamily.size()) {
          result.add(secondFamily.get(i));
        }
      }
      return result;
    }

    @Override
    public boolean seekTo(SocketAddress needle) {
      checkNotNull(needle, "needle");
      for (int i = 0; i < interleavedAddresses.size(); i++) {
        if (interleavedAddresses.get(i).address.equals(needle)) {
          this.interleavedIndex = i;
          return true;
        }
      }
      return false;
    }

    @Override
    public int size() {
      return interleavedAddresses.size();
    }

    private static final class InterleavedEntry {
      private final int addressGroup;
      private final SocketAddress address;

      public InterleavedEntry(int addressGroup, SocketAddress address) {
        this.addressGroup = addressGroup;
        this.address = address;
      }
    }
  }

  @VisibleForTesting
  int getGroupIndex() {
    return addressIndex.getGroupIndex();
  }

  @VisibleForTesting
  boolean isIndexValid() {
    return addressIndex.isValid();
  }

  private static final class SubchannelData {
    private final Subchannel subchannel;
    private ConnectivityState state;
    private boolean completedConnectivityAttempt = false;
    private ConnectivityStateInfo healthStateInfo = ConnectivityStateInfo.forNonError(IDLE);

    public SubchannelData(Subchannel subchannel, ConnectivityState state) {
      this.subchannel = subchannel;
      this.state = state;
    }

    public Subchannel getSubchannel() {
      return this.subchannel;
    }

    public ConnectivityState getState() {
      return this.state;
    }

    public boolean isCompletedConnectivityAttempt() {
      return completedConnectivityAttempt;
    }

    private void updateState(ConnectivityState newState) {
      this.state = newState;
      if (newState == READY || newState == TRANSIENT_FAILURE) {
        completedConnectivityAttempt = true;
      } else if (newState == IDLE) {
        completedConnectivityAttempt = false;
      }
    }

    private ConnectivityState getHealthState() {
      return healthStateInfo.getState();
    }
  }

  public static final class PickFirstLeafLoadBalancerConfig {

    @Nullable
    public final Boolean shuffleAddressList;

    // For testing purposes only, not meant to be parsed from a real config.
    @Nullable
    final Long randomSeed;

    public PickFirstLeafLoadBalancerConfig(@Nullable Boolean shuffleAddressList) {
      this(shuffleAddressList, null);
    }

    PickFirstLeafLoadBalancerConfig(@Nullable Boolean shuffleAddressList,
        @Nullable Long randomSeed) {
      this.shuffleAddressList = shuffleAddressList;
      this.randomSeed = randomSeed;
    }
  }

}
