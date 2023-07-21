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
    private final Map<SocketAddress, Subchannel> subchannels = new HashMap<>();
    private Index addressIndex;
    private volatile ConnectivityState currentState = IDLE;

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
          this.addressIndex = new Index(unmodifiableServers);
          createSubchannels(unmodifiableServers);
          // The channel state does not get updated when doing name resolving today, so for the moment
          // let LB report CONNECTION and call subchannel.requestConnection() immediately.
          updateBalancingState(CONNECTING, new Picker(PickResult.withSubchannel(subchannels.get(addressIndex.getCurrentAddress()))));
          // TODO: shouldn't this be withNoResult()?
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
          addressIndex.updateGroups(newImmutableAddressGroups);
          Set<SocketAddress> oldAddrs = new HashSet<>(subchannels.keySet());
          Set<SocketAddress> newAddrs = new HashSet<>();
          for (EquivalentAddressGroup endpoint : newImmutableAddressGroups) {
            for (SocketAddress addr : endpoint.getAddresses()) {
              newAddrs.add(addr);
              if (!subchannels.containsKey(addr)) {
                System.out.println("adding: " + addr);
                List<EquivalentAddressGroup> addrs = new ArrayList<>();
                addrs.add(new EquivalentAddressGroup(addr));
                final Subchannel subchannel = helper.createSubchannel(
                    CreateSubchannelArgs.newBuilder()
                    .setAddresses(addrs)
                    .build());
                subchannels.put(addr, subchannel);
                subchannel.start(new SubchannelStateListener() {
                  @Override
                  public void onSubchannelState(ConnectivityStateInfo stateInfo) {
                    processSubchannelState(subchannel, stateInfo);
                  }
                });
              }
            }
          }

          // remove old subchannels that were not in new address list
          for (SocketAddress oldAddr : oldAddrs) {
            System.out.println("all old addresses: " + oldAddr);
            if (!newAddrs.contains(oldAddr)) {
              System.out.println("removed: " + oldAddr);
              subchannels.get(oldAddr).shutdown();
              subchannels.remove(oldAddr);
            }
          }

          if (currentState == READY && !addressIndex.seekTo(previousAddress)) {
            SubchannelPicker picker = new RequestConnectionPicker(subchannels.get(addressIndex.getCurrentAddress()));
            updateBalancingState(IDLE, picker);
          } else if (currentState == CONNECTING && !addressIndex.seekTo(previousAddress)) {
            requestConnection();
          } else if (currentState == CONNECTING && addressIndex.seekTo(previousAddress)) {
            addressIndex.reset();
            requestConnection();
          } else if (currentState == TRANSIENT_FAILURE) {
            addressIndex.reset();
            requestConnection();
          }
        }
      });
    }

    @Override
    public void handleNameResolutionError(Status error) {
      for (Subchannel subchannel : subchannels.values()) {
        subchannel.shutdown();
        subchannel = null;
      }
      subchannels.clear();
      // NB(lukaszx0) Whether we should propagate the error unconditionally is arguable. It's fine
      // for time being.
      updateBalancingState(TRANSIENT_FAILURE, new Picker(PickResult.withError(error)));
    }

    void processSubchannelState(Subchannel subchannel, ConnectivityStateInfo stateInfo) {
        ConnectivityState newState = stateInfo.getState();
        // If we have shutdown channels or updated addresses, we may still receive state updates
        // from subchannels. To prevent pickers from returning these obselete subchannels, this logic
        // is included to check if the current list of active subchannels includes the current one.
        if (!subchannels.containsValue(subchannel)) {
          return; // TODO: test that containsValue() works (should work as equals is overrided)
        }
        if (newState == SHUTDOWN) {
          return;
        }
        if (newState == IDLE) {
          helper.refreshNameResolution(); // TODO: check cases for refresh name resolution
        }

        // If we are transitioning from a TRANSIENT_FAILURE to CONNECTING or IDLE we ignore this state
        // transition and still keep the LB in TRANSIENT_FAILURE state. This is referred to as "sticky
        // transient failure". Only a subchannel state change to READY will get the LB out of
        // TRANSIENT_FAILURE. If the state is IDLE we additionally request a new connection so that we
        // keep retrying for a connection.

        // With the new pick first implementation, iterative requests for connections will not be
        // requested once the first pass through is complete. This means that individual subchannels
        // are responsible for coming out of backoff and requesting a connection.
        // However, if the first pass through is complete, we report TRANSIENT_FAILURE, and an address
        // update occurs, the iterative logic will still be present for the first pass through for
        // the new address list even though we are in a state of TRANSIENT_FAILURE.
        if (currentState == TRANSIENT_FAILURE) {
          if (newState == CONNECTING) {
            return;
          } else if (newState == IDLE) {
            // coming out of backoff
            addressIndex.reset();
            requestConnection();
            return;
          }
        }

        SocketAddress currentAddr = getAddress(subchannel);
        SubchannelPicker picker;
        switch (newState) {
          case IDLE:
            // subchannel was shutdown when ready: connect from beginning when prompted
            addressIndex.reset();
            picker = new RequestConnectionPicker(subchannels.get(addressIndex.getCurrentAddress()));
            updateBalancingState(IDLE, picker);
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
            // TODO: shutdown rest of subchannels?
            break;
          case TRANSIENT_FAILURE:
            // If we are looking at current channel ...
            if (subchannels.get(addressIndex.getCurrentAddress()).equals(subchannel)) {
              // ... and if it is for the last address, report TRANSIENT_FAILURE.
              // Sticky TRANSIENT_FAILURE will only allow this to happen once.
              if (addressIndex.isAtEnd()) {
                addressIndex.reset();
                helper.refreshNameResolution();
                picker = new Picker(PickResult.withError(stateInfo.getStatus()));
                updateBalancingState(TRANSIENT_FAILURE, picker);
              // If we are not at the last address, increment and try a connection attempt
              // to the next address.
              } else {
                addressIndex.increment();
                // TODO: remove precondition after testing
                Preconditions.checkState(currentState == CONNECTING, "%s should be CONNECTING state", currentState);
                requestConnection();
                picker = new Picker(PickResult.withNoResult());
              }
              // If we are looking at another address that retried after backoff
              // and entered TRANSIENT_FAILURE, do not do any incrementing logic,
              // individual subchannels will perform their own backoffs.
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

    private void createSubchannels(List<EquivalentAddressGroup> newAddressGroup) {
      for (EquivalentAddressGroup endpoint : newAddressGroup) {
        for (SocketAddress addr : endpoint.getAddresses()) {
          List<EquivalentAddressGroup> addrs = new ArrayList<>();
          addrs.add(new EquivalentAddressGroup(addr));
          final Subchannel subchannel = helper.createSubchannel(
              CreateSubchannelArgs.newBuilder()
              .setAddresses(addrs)
              .build());
          subchannels.put(addr, subchannel);
          subchannel.start(new SubchannelStateListener() {
            @Override
            public void onSubchannelState(ConnectivityStateInfo stateInfo) {
              processSubchannelState(subchannel, stateInfo);
            }
          });
        }
      }
    }

    @Override
    public void shutdown() {
      for (Subchannel subchannel : subchannels.values()) {
        subchannel.shutdown();
      }
      subchannels.clear();
    }

    @Override
    public void requestConnection() {
      if (subchannels.size() > 0) {
        subchannels.get(addressIndex.getCurrentAddress()).requestConnection();
      }
    }

    private static void checkListHasNoNulls(List<?> list, String msg) {
      for (Object item : list) {
        Preconditions.checkNotNull(item, msg);
      }
    }

    /**
     * Returns the address that this Subchannel is bound to.  This can be called only if
     * the Subchannel has only one {@link SocketAddress}.
     *
     * <p>It should be called from the Synchronization Context.  Currently will log a warning if
     * violated.  It will become an exception eventually.  See <a
     * href="https://github.com/grpc/grpc-java/issues/5015">#5015</a> for the background.
     *
     * @throws IllegalStateException if this subchannel has more than one SocketAddress.
     */
    private static SocketAddress getAddress(Subchannel subchannel) {
      List<SocketAddress> addresses = subchannel.getAddresses().getAddresses();
      Preconditions.checkState(addresses.size() == 1, "%s does not have exactly address", addresses);
      return addresses.get(0);
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
                      updateBalancingState(CONNECTING, new Picker(PickResult.withNoResult()));
                      subchannel.requestConnection();
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
    static final class Index {
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

        public boolean isAtEnd() {
          return groupIndex == addressGroups.size() - 1 &&
              addressIndex == addressGroups.get(groupIndex).getAddresses().size() - 1;
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
