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
import static io.grpc.ConnectivityState.SHUTDOWN;
import static io.grpc.ConnectivityState.TRANSIENT_FAILURE;

import com.google.common.base.MoreObjects;
import io.grpc.ConnectivityState;
import io.grpc.ConnectivityStateInfo;
import io.grpc.EquivalentAddressGroup;
import io.grpc.LoadBalancer;
import io.grpc.Status;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nullable;

/**
 * A {@link LoadBalancer} that provides no load-balancing over the addresses from the {@link
 * io.grpc.NameResolver}.  The channel's default behavior is used, which is walking down the address
 * list and sticking to the first that works.
 */
final class PickFirstLoadBalancer extends LoadBalancer {
  private final Helper helper;
  private Subchannel subchannel;
  private ConnectivityState currentState = IDLE;

  PickFirstLoadBalancer(Helper helper) {
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
    if (resolvedAddresses.getLoadBalancingPolicyConfig() instanceof PickFirstLoadBalancerConfig) {
      PickFirstLoadBalancerConfig config
          = (PickFirstLoadBalancerConfig) resolvedAddresses.getLoadBalancingPolicyConfig();
      if (config.shuffleAddressList != null && config.shuffleAddressList) {
        servers = new ArrayList<EquivalentAddressGroup>(servers);
        Collections.shuffle(servers,
            config.randomSeed != null ? new Random(config.randomSeed) : new Random());
      }
    }

    if (subchannel == null) {
      final Subchannel subchannel = helper.createSubchannel(
          CreateSubchannelArgs.newBuilder()
              .setAddresses(servers)
              .build());
      subchannel.start(new SubchannelStateListener() {
          @Override
          public void onSubchannelState(ConnectivityStateInfo stateInfo) {
            processSubchannelState(subchannel, stateInfo);
          }
        });
      this.subchannel = subchannel;

      // The channel state does not get updated when doing name resolving today, so for the moment
      // let LB report CONNECTION and call subchannel.requestConnection() immediately.
      updateBalancingState(CONNECTING, new Picker(PickResult.withSubchannel(subchannel)));
      subchannel.requestConnection();
    } else {
      subchannel.updateAddresses(servers);
    }

    return true;
  }

  @Override
  public void handleNameResolutionError(Status error) {
    if (subchannel != null) {
      subchannel.shutdown();
      subchannel = null;
    }
    // NB(lukaszx0) Whether we should propagate the error unconditionally is arguable. It's fine
    // for time being.
    updateBalancingState(TRANSIENT_FAILURE, new Picker(PickResult.withError(error)));
  }

  private void processSubchannelState(Subchannel subchannel, ConnectivityStateInfo stateInfo) {
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
        requestConnection();
        return;
      }
    }

    SubchannelPicker picker;
    switch (newState) {
      case IDLE:
        picker = new RequestConnectionPicker(subchannel);
        break;
      case CONNECTING:
        // It's safe to use RequestConnectionPicker here, so when coming from IDLE we could leave
        // the current picker in-place. But ignoring the potential optimization is simpler.
        picker = new Picker(PickResult.withNoResult());
        break;
      case READY:
        picker = new Picker(PickResult.withSubchannel(subchannel));
        break;
      case TRANSIENT_FAILURE:
        picker = new Picker(PickResult.withError(stateInfo.getStatus()));
        break;
      default:
        throw new IllegalArgumentException("Unsupported state:" + newState);
    }

    updateBalancingState(newState, picker);
  }

  private void updateBalancingState(ConnectivityState state, SubchannelPicker picker) {
    currentState = state;
    helper.updateBalancingState(state, picker);
  }

  @Override
  public void shutdown() {
    if (subchannel != null) {
      subchannel.shutdown();
    }
  }

  @Override
  public void requestConnection() {
    if (subchannel != null) {
      subchannel.requestConnection();
    }
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

  /** Picker that requests connection during the first pick, and returns noResult. */
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
              subchannel.requestConnection();
            }
          });
      }
      return PickResult.withNoResult();
    }
  }

  public static final class PickFirstLoadBalancerConfig {

    @Nullable
    public final Boolean shuffleAddressList;

    // For testing purposes only, not meant to be parsed from a real config.
    @Nullable final Long randomSeed;

    public PickFirstLoadBalancerConfig(@Nullable Boolean shuffleAddressList) {
      this(shuffleAddressList, null);
    }

    PickFirstLoadBalancerConfig(@Nullable Boolean shuffleAddressList, @Nullable Long randomSeed) {
      this.shuffleAddressList = shuffleAddressList;
      this.randomSeed = randomSeed;
    }
  }
}
