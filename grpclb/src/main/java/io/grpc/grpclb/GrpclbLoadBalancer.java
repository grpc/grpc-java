/*
 * Copyright 2016 The gRPC Authors
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

package io.grpc.grpclb;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Stopwatch;
import io.grpc.Attributes;
import io.grpc.ChannelLogger.ChannelLogLevel;
import io.grpc.ConnectivityStateInfo;
import io.grpc.EquivalentAddressGroup;
import io.grpc.LoadBalancer;
import io.grpc.Status;
import io.grpc.grpclb.GrpclbState.Mode;
import io.grpc.internal.BackoffPolicy;
import io.grpc.internal.TimeProvider;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;

/**
 * A {@link LoadBalancer} that uses the GRPCLB protocol.
 *
 * <p>Optionally, when requested by the naming system, will delegate the work to a local pick-first
 * or round-robin balancer.
 */
class GrpclbLoadBalancer extends LoadBalancer {

  private static final GrpclbConfig DEFAULT_CONFIG = GrpclbConfig.create(Mode.ROUND_ROBIN);

  private final Helper helper;
  private final TimeProvider time;
  private final Stopwatch stopwatch;
  private final SubchannelPool subchannelPool;
  private final BackoffPolicy.Provider backoffPolicyProvider;

  private GrpclbConfig config = DEFAULT_CONFIG;

  // All mutable states in this class are mutated ONLY from Channel Executor
  @Nullable
  private GrpclbState grpclbState;

  GrpclbLoadBalancer(
      Helper helper,
      SubchannelPool subchannelPool,
      TimeProvider time,
      Stopwatch stopwatch,
      BackoffPolicy.Provider backoffPolicyProvider) {
    this.helper = checkNotNull(helper, "helper");
    this.time = checkNotNull(time, "time provider");
    this.stopwatch = checkNotNull(stopwatch, "stopwatch");
    this.backoffPolicyProvider = checkNotNull(backoffPolicyProvider, "backoffPolicyProvider");
    this.subchannelPool = checkNotNull(subchannelPool, "subchannelPool");
    this.subchannelPool.init(helper, this);
    recreateStates();
    checkNotNull(grpclbState, "grpclbState");
  }

  @Deprecated
  @Override
  public void handleSubchannelState(Subchannel subchannel, ConnectivityStateInfo newState) {
    // grpclbState should never be null here since handleSubchannelState cannot be called while the
    // lb is shutdown.
    grpclbState.handleSubchannelState(subchannel, newState);
  }

  @Override
  public void handleResolvedAddresses(ResolvedAddresses resolvedAddresses) {
    Attributes attributes = resolvedAddresses.getAttributes();
    List<EquivalentAddressGroup> newLbAddresses = attributes.get(GrpclbConstants.ATTR_LB_ADDRS);
    if ((newLbAddresses == null || newLbAddresses.isEmpty())
        && resolvedAddresses.getAddresses().isEmpty()) {
      handleNameResolutionError(
          Status.UNAVAILABLE.withDescription("No backend or balancer addresses found"));
      return;
    }
    List<LbAddressGroup> newLbAddressGroups = new ArrayList<>();

    if (newLbAddresses != null) {
      for (EquivalentAddressGroup lbAddr : newLbAddresses) {
        String lbAddrAuthority = lbAddr.getAttributes().get(GrpclbConstants.ATTR_LB_ADDR_AUTHORITY);
        if (lbAddrAuthority == null) {
          throw new AssertionError(
              "This is a bug: LB address " + lbAddr + " does not have an authority.");
        }
        newLbAddressGroups.add(new LbAddressGroup(lbAddr, lbAddrAuthority));
      }
    }

    newLbAddressGroups = Collections.unmodifiableList(newLbAddressGroups);
    List<EquivalentAddressGroup> newBackendServers =
        Collections.unmodifiableList(resolvedAddresses.getAddresses());
    GrpclbConfig newConfig = (GrpclbConfig) resolvedAddresses.getLoadBalancingPolicyConfig();
    if (newConfig == null) {
      newConfig = DEFAULT_CONFIG;
    }
    if (!config.equals(newConfig)) {
      config = newConfig;
      helper.getChannelLogger().log(ChannelLogLevel.INFO, "Config: " + newConfig);
      recreateStates();
    }
    grpclbState.handleAddresses(newLbAddressGroups, newBackendServers);
  }

  @Override
  public void requestConnection() {
    if (grpclbState != null) {
      grpclbState.requestConnection();
    }
  }

  private void resetStates() {
    if (grpclbState != null) {
      grpclbState.shutdown();
      grpclbState = null;
    }
  }

  private void recreateStates() {
    resetStates();
    checkState(grpclbState == null, "Should've been cleared");
    grpclbState =
        new GrpclbState(config, helper, subchannelPool, time, stopwatch, backoffPolicyProvider);
  }

  @Override
  public void shutdown() {
    resetStates();
  }

  @Override
  public void handleNameResolutionError(Status error) {
    if (grpclbState != null) {
      grpclbState.propagateError(error);
    }
  }

  @Override
  public boolean canHandleEmptyAddressListFromNameResolution() {
    return true;
  }

  @VisibleForTesting
  @Nullable
  GrpclbState getGrpclbState() {
    return grpclbState;
  }
}
