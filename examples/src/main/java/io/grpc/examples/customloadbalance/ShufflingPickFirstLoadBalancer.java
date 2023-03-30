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

package io.grpc.examples.customloadbalance;

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

/**
 * An example {@link LoadBalancer} largely based on {@link ShufflingPickFirstLoadBalancer} that adds
 * shuffling of the list of servers so that the first server provided in {@link ResolvedAddresses}
 * won't necessarily be the server the channel will connect to.
 */
class ShufflingPickFirstLoadBalancer extends LoadBalancer {

  private final Helper helper;
  private Subchannel subchannel;

  /**
   * This class defines the configuration used by this {@link LoadBalancer}. Note that no part of
   * the gRPC library is aware of this class, it will be populated by the
   * {@link ShufflingPickFirstLoadBalancerProvider}.
   */
  static class Config {

    final Long randomSeed;

    Config(Long randomSeed) {
      this.randomSeed = randomSeed;
    }
  }

  public ShufflingPickFirstLoadBalancer(Helper helper) {
    this.helper = checkNotNull(helper, "helper");
  }

  @Override
  public boolean acceptResolvedAddresses(ResolvedAddresses resolvedAddresses) {
    List<EquivalentAddressGroup> servers = new ArrayList<>(resolvedAddresses.getAddresses());
    if (servers.isEmpty()) {
      handleNameResolutionError(Status.UNAVAILABLE.withDescription(
          "NameResolver returned no usable address. addrs=" + resolvedAddresses.getAddresses()
              + ", attrs=" + resolvedAddresses.getAttributes()));
      return false;
    }

    Config config
        = (Config) resolvedAddresses.getLoadBalancingPolicyConfig();

    Collections.shuffle(servers,
        config.randomSeed != null ? new Random(config.randomSeed) : new Random());

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

      helper.updateBalancingState(CONNECTING, new Picker(PickResult.withNoResult()));
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
    helper.updateBalancingState(TRANSIENT_FAILURE, new Picker(PickResult.withError(error)));
  }

  private void processSubchannelState(Subchannel subchannel, ConnectivityStateInfo stateInfo) {
    ConnectivityState currentState = stateInfo.getState();
    if (currentState == SHUTDOWN) {
      return;
    }
    if (stateInfo.getState() == TRANSIENT_FAILURE || stateInfo.getState() == IDLE) {
      helper.refreshNameResolution();
    }

    SubchannelPicker picker;
    switch (currentState) {
      case IDLE:
        picker = new RequestConnectionPicker(subchannel);
        break;
      case CONNECTING:
        picker = new Picker(PickResult.withNoResult());
        break;
      case READY:
        picker = new Picker(PickResult.withSubchannel(subchannel));
        break;
      case TRANSIENT_FAILURE:
        picker = new Picker(PickResult.withError(stateInfo.getStatus()));
        break;
      default:
        throw new IllegalArgumentException("Unsupported state:" + currentState);
    }
    helper.updateBalancingState(currentState, picker);
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
            subchannel.requestConnection();
          }
        });
      }
      return PickResult.withNoResult();
    }
  }
}