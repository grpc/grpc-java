/*
 * Copyright 2015 The gRPC Authors
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
import com.google.common.base.Preconditions;
import com.google.common.base.Stopwatch;
import io.grpc.*;
import io.grpc.SynchronizationContext.ScheduledHandle;
import java.net.SocketAddress;
import java.util.*;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nullable;

/**
 * A {@link LoadBalancer} that provides no load-balancing over the addresses from the {@link
 * io.grpc.NameResolver}.  The channel's default behavior is used, which is walking down the address
 * list and sticking to the first that works.
 */
@ExperimentalApi("https://github.com/grpc/grpc-java/issues/10383")
final class PickFirstLeafLoadBalancer extends LoadBalancer {
    private final Helper helper;
    private List<EquivalentAddressGroup> addressGroups;
    private List<Subchannel> subchannels = new ArrayList<>();
    private volatile int index;
    private volatile Index addressIndex;
    private volatile boolean firstConnection = true;

    private volatile ConnectivityState currentState = IDLE;
//    private final Stopwatch connectingTimer;
//    private final ScheduledExecutorService scheduledExecutor;


    @Nullable
    private ScheduledHandle reconnectTask;

    /**
     * The policy to control back off between reconnects. Non-{@code null} when a reconnect task is
     * scheduled.
     */
    private BackoffPolicy reconnectPolicy;

    PickFirstLeafLoadBalancer(Helper helper) {
        this.helper = checkNotNull(helper, "helper");
    }

    @Override
    public boolean acceptResolvedAddresses(ResolvedAddresses resolvedAddresses) {
        List<EquivalentAddressGroup> servers = resolvedAddresses.getAddresses();
        if (servers.isEmpty()) {
            handleNameResolutionError(Status.UNAVAILABLE.withDescription(
                    "NameResolver returned no usable address. addrs=" + resolvedAddresses.getAddresses()
                            + ", attrs=" + resolvedAddresses.getAttributes()));
            return false;
        }

        // We can optionally be configured to shuffle the address list. This can help better distribute
        // the load.
        if (resolvedAddresses.getLoadBalancingPolicyConfig() instanceof PickFirstLeafLoadBalancerConfig) {
            PickFirstLeafLoadBalancerConfig config
                    = (PickFirstLeafLoadBalancerConfig) resolvedAddresses.getLoadBalancingPolicyConfig();
            if (config.shuffleAddressList != null && config.shuffleAddressList) {
                servers = new ArrayList<EquivalentAddressGroup>(servers);
                Collections.shuffle(servers,
                        config.randomSeed != null ? new Random(config.randomSeed) : new Random());
            }
        }
        if (subchannels.size() == 0) {
          List<EquivalentAddressGroup> unmodifiableServers =
              Collections.unmodifiableList(new ArrayList<>(servers));
          this.addressGroups = unmodifiableServers;
          this.addressIndex = new Index(unmodifiableServers);
          index = 0;
          addressIndex.reset();

          for (EquivalentAddressGroup endpoint : addressGroups) {
            for (SocketAddress address : endpoint.getAddresses()) {
              List<EquivalentAddressGroup> addresses = new ArrayList<>();
              addresses.add(new EquivalentAddressGroup(address));
              final Subchannel subchannel = helper.createSubchannel(
                  CreateSubchannelArgs.newBuilder()
                      .setAddresses(addresses)
                      .build());
              subchannels.add(subchannel);
            }
          }
          // The channel state does not get updated when doing name resolving today, so for the moment
          // let LB report CONNECTION and call subchannel.requestConnection() immediately.
          requestConnection();
        } else {
          updateAddresses(servers);
        }

        return true;
    }

    /** Replaces the existing addresses, avoiding unnecessary reconnects. */
    public void updateAddresses(final List<EquivalentAddressGroup> newAddressGroups) {
      Preconditions.checkNotNull(newAddressGroups, "newAddressGroups");
      checkListHasNoNulls(newAddressGroups, "newAddressGroups contains null entry");
      Preconditions.checkArgument(!newAddressGroups.isEmpty(), "newAddressGroups is empty");
      final List<EquivalentAddressGroup> newImmutableAddressGroups =
        Collections.unmodifiableList(new ArrayList<>(newAddressGroups));
      helper.getSynchronizationContext().execute(new Runnable() {
        @Override
        public void run() {
          SocketAddress previousAddress = addressIndex.getCurrentAddress();
          index = 0;
          addressIndex.updateGroups(newImmutableAddressGroups);
          addressGroups = newImmutableAddressGroups;
          if (!addressIndex.seekTo(previousAddress)) {
            // disjoint: forced to drop connection and replace subchannels
            shutdown();
            for (EquivalentAddressGroup endpoint : addressGroups) {
              for (SocketAddress address : endpoint.getAddresses()) {
                List<EquivalentAddressGroup> addresses = new ArrayList<>();
                addresses.add(new EquivalentAddressGroup(address));
                final Subchannel subchannel = helper.createSubchannel(
                    CreateSubchannelArgs.newBuilder()
                        .setAddresses(addresses)
                        .build());
                subchannels.add(subchannel);
              }
            }
            if (currentState == READY) {
              // subchannel was READY, IDLE until prompted for a subchannel
              updateBalancingState(IDLE, new RequestConnectionPicker(subchannels.get(index)));
            } else if (currentState == CONNECTING) {
              // subchannel was connecting, want new connection
              index = addressIndex.getGroupIndex();
              requestConnection();
            }
          } else {
            // intersecting: keep current subchannel and replace all others
            index = addressIndex.getGroupIndex();
            Subchannel currentSubchannel = subchannels.get(index);
            // shutdown all except current subchannel
            for (int i = 0; i < subchannels.size(); i++) {
              if (i != index) {
                subchannels.get(i).shutdown();
              }
            }
            subchannels = new ArrayList<>();

            // lazily create subchannels with new addresses
            for (EquivalentAddressGroup endpoint : addressGroups) {
              for (SocketAddress address : endpoint.getAddresses()) {
                List<EquivalentAddressGroup> addresses = new ArrayList<>();
                addresses.add(new EquivalentAddressGroup(address));
                final Subchannel subchannel = helper.createSubchannel(
                    CreateSubchannelArgs.newBuilder()
                        .setAddresses(addresses)
                        .build());
                subchannels.add(subchannel);
              }
            }

            // replace with current subchannel
            subchannels.set(index, currentSubchannel);
          }
        }
      });
    }

    @Override
    public void handleNameResolutionError(Status error) {
      for (Subchannel subchannel : subchannels) {
        subchannel.shutdown();
        subchannels.remove(subchannel);
        subchannel = null;
      }
      index = 0;
      // NB(lukaszx0) Whether we should propagate the error unconditionally is arguable. It's fine
      // for time being.
      updateBalancingState(TRANSIENT_FAILURE, new Picker(PickResult.withError(error)));
    }

    void processSubchannelState(Subchannel subchannel, ConnectivityStateInfo stateInfo) {
        ConnectivityState newState = stateInfo.getState();
        if (newState == SHUTDOWN) {
            return;
        }
        if (newState == TRANSIENT_FAILURE || newState == IDLE) {
            helper.refreshNameResolution();
        }

        // If we are transitioning from a TRANSIENT_FAILURE to CONNECTING or IDLE we ignore this state
        // transition and still keep the LB in TRANSIENT_FAILURE state. This is referred to as "sticky
        // transient failure". Only a subchannel state change to READY will get the LB out of
        // TRANSIENT_FAILURE. If the state is IDLE we additionally request a new connection so that we
        // keep retrying for a connection.
        if (currentState == TRANSIENT_FAILURE) {
            if (newState == CONNECTING) {
                return;
            } else if (newState == IDLE) {
                index = 0;
                requestConnection();
                return;
            }
        }

        SubchannelPicker picker;
        switch (newState) {
            case IDLE:
                // we enter this case if a subchannel was shutdown when ready
                if (subchannel.equals(subchannels.get(0))) {
                  index = 0;
                  picker = new RequestConnectionPicker(subchannel); // TODO: this may be subchannels.get(0)
                  updateBalancingState(IDLE, picker);
                }
                break;
            case CONNECTING:
                // It's safe to use RequestConnectionPicker here, so when coming from IDLE we could leave
                // the current picker in-place. But ignoring the potential optimization is simpler.
                picker = new Picker(PickResult.withNoResult());
                updateBalancingState(CONNECTING, picker);
                break;
            case READY:
                picker = new Picker(PickResult.withSubchannel(subchannel));
                updateBalancingState(READY, picker);
                break;
            case TRANSIENT_FAILURE:
                if (index == subchannels.size() - 1) {
                  picker = new Picker(PickResult.withError(stateInfo.getStatus()));
                  updateBalancingState(TRANSIENT_FAILURE, picker);
                  scheduleBackoff(Status.UNAVAILABLE); // TODO: FIX ME
                } else {
                  index++;
                  addressIndex.increment();
                  requestConnection();
                  picker = new Picker(PickResult.withNoResult());
                }
                break;
            default:
                throw new IllegalArgumentException("Unsupported state:" + newState);
        }

    }

    private void updateBalancingState(ConnectivityState state, SubchannelPicker picker) {
        currentState = state;
        helper.updateBalancingState(state, picker);
    }

    private void scheduleBackoff(Status s) { // TODO: FIX ME
//      helper.getSynchronizationContext().throwIfNotInThisSynchronizationContext();
//
//      class EndOfCurrentBackoff implements Runnable {
//        @Override
//        public void run() {
//          reconnectTask = null;
//          index = 0;
//          requestConnection();
//        }
//      }
//      updateBalancingState(TRANSIENT_FAILURE, new Picker(PickResult.withNoResult()));
//      long delayNanos =
//          reconnectPolicy.nextBackoffNanos() - connectingTimer.elapsed(TimeUnit.NANOSECONDS);
////      channelLogger.log(
////          ChannelLogger.ChannelLogLevel.INFO,
////          "TRANSIENT_FAILURE ({0}). Will reconnect after {1} ns",
////          printShortStatus(status), delayNanos);
//      Preconditions.checkState(reconnectTask == null, "previous reconnectTask is not done");
//      reconnectTask = helper.getSynchronizationContext().schedule(
//          new EndOfCurrentBackoff(),
//          delayNanos,
//          TimeUnit.NANOSECONDS,
//          scheduledExecutor);

    }

    @Override
    public void shutdown() {
      if (subchannels != null) {
        for (int i = 0; i <= index; i++) {
          subchannels.get(i).shutdown();
        }
        subchannels = new ArrayList<>();
      }
    }

    @Override
    public void requestConnection() { // TODO: only start subchannel if it is new connection
      if (index < subchannels.size() && subchannels.get(index) != null) {
        if (firstConnection) {
          firstConnection = false;
          subchannels.get(index).start(new SubchannelStateListener() {
            @Override
            public void onSubchannelState(ConnectivityStateInfo stateInfo) {
              processSubchannelState(subchannels.get(index), stateInfo);
            }
          });
        }
        // The channel state does not get updated when doing name resolving today, so for the moment
        // let LB report CONNECTION and call subchannel.requestConnection() immediately.
        updateBalancingState(CONNECTING, new Picker(PickResult.withSubchannel(subchannels.get(index)))); // TODO: confirm desired behavior
        subchannels.get(index).requestConnection();
        // TODO: when coming out of backoff, what does this achieve? "asks to create connection if there isn't an active one
        //  if this does not properly reinitialize, we need to recreate a subchannel
      }
    }

    private static void checkListHasNoNulls(List<?> list, String msg) {
      for (Object item : list) {
        Preconditions.checkNotNull(item, msg);
      }
    }

    @VisibleForTesting
    ConnectivityState getCurrentState() {
      return this.currentState;
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
        private final Subchannel subchannel;
        private final AtomicBoolean connectionRequested = new AtomicBoolean(false);

        RequestConnectionPicker(Subchannel subchannel) {
            this.subchannel = checkNotNull(subchannel, "subchannel");
        }

        @Override
        public PickResult pickSubchannel(PickSubchannelArgs args) {
            if (connectionRequested.compareAndSet(false, true)) {
                helper.getSynchronizationContext().execute(new Runnable() {
                    @Override
                    public void run() {
                      PickFirstLeafLoadBalancer.super.requestConnection(); // TODO: this cannot be subchannel.requestConnection()
                    }
                });
            }
            return PickResult.withNoResult();
        }
    }

    /**
     * Index as in 'i', the pointer to an entry. Not a "search index."
     */
    @VisibleForTesting
    static final class Index { // TODO: redesigning index class
        private List<EquivalentAddressGroup> addressGroups;
        private int groupIndex;
        private int addressIndex;

        public Index(List<EquivalentAddressGroup> groups) {
            this.addressGroups = groups;
        }

        public boolean isValid() {
            // addressIndex will never be invalid
            return groupIndex < addressGroups.size();
        }

        public boolean isAtBeginning() {
            return groupIndex == 0 && addressIndex == 0;
        }

        public void increment() {
            EquivalentAddressGroup group = addressGroups.get(groupIndex);
            addressIndex++;
            if (addressIndex >= group.getAddresses().size()) {
                groupIndex++;
                addressIndex = 0;
            }
        }

        public void reset() {
            groupIndex = 0;
            addressIndex = 0;
        }

        public SocketAddress getCurrentAddress() {
            return addressGroups.get(groupIndex).getAddresses().get(addressIndex);
        }

        public Attributes getCurrentEagAttributes() {
            return addressGroups.get(groupIndex).getAttributes();
        }

        public List<EquivalentAddressGroup> getGroups() {
            return addressGroups;
        }

        public int getGroupIndex() {
          return groupIndex;
        }

        /**
         * Update to new groups, resetting the current index.
         */
        public void updateGroups(List<EquivalentAddressGroup> newGroups) {
            addressGroups = newGroups;
            reset();
        }

        /**
         * Returns false if the needle was not found and the current index was left unchanged.
         */
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
        PickFirstLeafLoadBalancerConfig(@Nullable Boolean shuffleAddressList, @Nullable Long randomSeed) {
            this.shuffleAddressList = shuffleAddressList;
            this.randomSeed = randomSeed;
        }
    }
}
