/*
 * Copyright 2019 The gRPC Authors
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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import io.grpc.Attributes;
import io.grpc.ChannelLogger.ChannelLogLevel;
import io.grpc.ConnectivityState;
import io.grpc.ConnectivityStateInfo;
import io.grpc.EquivalentAddressGroup;
import io.grpc.LoadBalancer;
import io.grpc.LoadBalancer.CreateSubchannelArgs;
import io.grpc.LoadBalancer.Helper;
import io.grpc.LoadBalancer.ResolvedAddresses;
import io.grpc.LoadBalancer.Subchannel;
import io.grpc.LoadBalancer.SubchannelPicker;
import io.grpc.LoadBalancer.SubchannelStateListener;
import io.grpc.LoadBalancerRegistry;
import io.grpc.SynchronizationContext.ScheduledHandle;
import io.grpc.internal.ServiceConfigUtil.LbConfig;
import io.grpc.util.ForwardingLoadBalancerHelper;
import io.grpc.xds.XdsLbState.SubchannelStore;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

@VisibleForTesting
final class FallbackManager {

  private static final long FALLBACK_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(10); // same as grpclb

  private final Helper parentHelper;
  private final SubchannelStore subchannelStore;
  private final LoadBalancerRegistry lbRegistry;
  private final SubchannelStateListener parentListener;

  private LbConfig fallbackPolicy;

  // read-only for outer class
  private LoadBalancer fallbackBalancer;

  // Scheduled only once.  Never reset.
  @Nullable
  private ScheduledHandle fallbackTimer;

  private List<EquivalentAddressGroup> fallbackServers = ImmutableList.of();
  private Attributes fallbackAttributes;

  // allow value write by outer class
  boolean balancerWorking;

  FallbackManager(
      Helper parentHelper, SubchannelStore subchannelStore, LoadBalancerRegistry lbRegistry,
      SubchannelStateListener parentListener) {
    this.parentHelper = parentHelper;
    this.subchannelStore = subchannelStore;
    this.lbRegistry = lbRegistry;
    this.parentListener = parentListener;
  }

  void cancelFallback() {
    if (fallbackTimer != null) {
      fallbackTimer.cancel();
    }
    if (fallbackBalancer != null) {
      fallbackBalancer.shutdown();
      fallbackBalancer = null;
    }
  }

  void maybeUseFallbackPolicy() {
    if (fallbackBalancer != null) {
      return;
    }
    if (balancerWorking || subchannelStore.hasReadyBackends()) {
      return;
    }

    parentHelper.getChannelLogger().log(
        ChannelLogLevel.INFO, "Using fallback policy");
    fallbackBalancer = lbRegistry.getProvider(fallbackPolicy.getPolicyName())
        .newLoadBalancer(new ChildHelper());
    // TODO(carl-mastrangelo): propagate the load balancing config policy
    fallbackBalancer.handleResolvedAddresses(
        ResolvedAddresses.newBuilder()
            .setAddresses(fallbackServers)
            .setAttributes(fallbackAttributes)
            .build());

    // TODO: maybe update picker
  }

  void updateFallbackServers(
      List<EquivalentAddressGroup> servers, Attributes attributes,
      LbConfig fallbackPolicy) {
    this.fallbackServers = servers;
    this.fallbackAttributes = Attributes.newBuilder()
        .setAll(attributes)
        .set(LoadBalancer.ATTR_LOAD_BALANCING_CONFIG, fallbackPolicy.getRawConfigValue())
        .build();
    LbConfig currentFallbackPolicy = this.fallbackPolicy;
    this.fallbackPolicy = fallbackPolicy;
    if (fallbackBalancer != null) {
      if (fallbackPolicy.getPolicyName().equals(currentFallbackPolicy.getPolicyName())) {
        // TODO(carl-mastrangelo): propagate the load balancing config policy
        fallbackBalancer.handleResolvedAddresses(
            ResolvedAddresses.newBuilder()
                .setAddresses(fallbackServers)
                .setAttributes(fallbackAttributes)
                .build());
      } else {
        fallbackBalancer.shutdown();
        fallbackBalancer = null;
        maybeUseFallbackPolicy();
      }
    }
  }

  void maybeStartFallbackTimer() {
    if (fallbackTimer == null) {
      class FallbackTask implements Runnable {
        @Override
        public void run() {
          maybeUseFallbackPolicy();
        }
      }

      fallbackTimer = parentHelper.getSynchronizationContext().schedule(
          new FallbackTask(), FALLBACK_TIMEOUT_MS, TimeUnit.MILLISECONDS,
          parentHelper.getScheduledExecutorService());
    }
  }

  LoadBalancer getFallbackBalancer() {
    return fallbackBalancer;
  }

  private final class ChildHelper extends ForwardingLoadBalancerHelper {
    @Override
    protected Helper delegate() {
      return parentHelper;
    }

    @Override
    public Subchannel createSubchannel(final CreateSubchannelArgs args) {
      return parentHelper.createSubchannel(
          args.toBuilder().setStateListener(
              new SubchannelStateListener() {
                @Override
                public void onSubchannelState(Subchannel subchannel,
                    ConnectivityStateInfo newState) {
                  args.getStateListener().onSubchannelState(subchannel, newState);
                  parentListener.onSubchannelState(subchannel, newState);
                }
              }).build());
    }

    @Override
    public void updateBalancingState(ConnectivityState newState, SubchannelPicker newPicker) {
      if (!balancerWorking) {
        parentHelper.updateBalancingState(newState, newPicker);
      }
    }
  }
}
